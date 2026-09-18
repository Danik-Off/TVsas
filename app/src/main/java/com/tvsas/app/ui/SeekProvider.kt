package com.tvsas.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.leanback.widget.PlaybackSeekDataProvider
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import com.tvsas.app.data.Api
import com.tvsas.app.data.Storyboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Seek positions every [stepMs] (one remote press = one step) plus preview frames cut from the
 * server's storyboard sprite. The sprite is downloaded once, already downscaled by the server,
 * so even a three-hour video costs a few MB of RAM on the box.
 */
class SeekProvider(
    private val context: Context,
    private val scope: CoroutineScope,
    durationMs: Long,
    stepMs: Long,
    private val storyboard: Storyboard?,
) : PlaybackSeekDataProvider() {

    val hasStoryboard: Boolean get() = storyboard != null

    private val positions: LongArray = run {
        val step = stepMs.coerceAtLeast(1_000)
        val count = (durationMs / step).toInt() + 1
        LongArray(count + 1) { i -> if (i < count) i * step else durationMs }
    }

    private var sheet: Bitmap? = null
    private var sheetFailed = false
    private var loading = false
    private val tiles = LruCache<Int, Bitmap>(48)
    private val pending = LinkedHashMap<Int, ResultCallback>()

    override fun getSeekPositions(): LongArray = positions

    override fun getThumbnail(index: Int, callback: ResultCallback) {
        val sb = storyboard ?: return
        if (sheetFailed) return
        val tile = sb.tileAt((positions[index] / 1000).toInt()) ?: return
        tiles.get(tile.startSec)?.let { callback.onThumbnailLoaded(it, index); return }

        val current = sheet
        if (current != null) {
            crop(current, sb, tile)?.let { callback.onThumbnailLoaded(it, index) }
            return
        }
        pending[index] = callback
        if (!loading) loadSheet(sb)
    }

    private fun loadSheet(sb: Storyboard) {
        loading = true
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                // Fit the sprite into ~1065 px wide and at most 4096 px tall (decoder-friendly on old GPUs).
                val scale = minOf(1f, MAX_WIDTH.toFloat() / sb.sheetWidth, MAX_HEIGHT.toFloat() / sb.sheetHeight)
                val width = (sb.sheetWidth * scale).toInt().coerceAtLeast(64)
                val request = ImageRequest.Builder(context)
                    .data(Api.storyboardUrl(sb.fileUuid, width))
                    .size(Size.ORIGINAL)
                    .allowHardware(false)
                    .bitmapConfig(Bitmap.Config.RGB_565)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .build()
                runCatching { (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap }.getOrNull()
            }
            loading = false
            if (bitmap == null) {
                sheetFailed = true
                pending.clear()
                return@launch
            }
            sheet = bitmap
            val waiting = pending.toList()
            pending.clear()
            waiting.forEach { (index, cb) ->
                sb.tileAt((positions[index] / 1000).toInt())?.let { tile ->
                    crop(bitmap, sb, tile)?.let { cb.onThumbnailLoaded(it, index) }
                }
            }
        }
    }

    private fun crop(sheet: Bitmap, sb: Storyboard, tile: Storyboard.Tile): Bitmap? {
        tiles.get(tile.startSec)?.let { return it }
        val sx = sheet.width.toFloat() / sb.sheetWidth
        val sy = sheet.height.toFloat() / sb.sheetHeight
        val x = (tile.x * sx).toInt()
        val y = (tile.y * sy).toInt()
        val w = (sb.tileWidth * sx).toInt().coerceAtMost(sheet.width - x)
        val h = (sb.tileHeight * sy).toInt().coerceAtMost(sheet.height - y)
        if (w <= 0 || h <= 0) return null
        return runCatching { Bitmap.createBitmap(sheet, x, y, w, h) }.getOrNull()?.also { tiles.put(tile.startSec, it) }
    }

    override fun reset() {
        pending.clear()
    }

    fun release() {
        pending.clear()
        tiles.evictAll()
        sheet?.recycle()
        sheet = null
    }

    private companion object {
        const val MAX_WIDTH = 1065
        const val MAX_HEIGHT = 4096
    }
}
