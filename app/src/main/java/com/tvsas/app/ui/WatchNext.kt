package com.tvsas.app.ui

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.tvsas.app.App
import com.tvsas.app.R
import com.tvsas.app.data.Api
import com.tvsas.app.data.HistoryEntry
import com.tvsas.app.util.Format
import kotlinx.coroutines.launch

/**
 * Mirrors the local "continue watching" history into the Android TV launcher's
 * "Watch Next" row (API 26+). Every call is fire-and-forget on the IO scope and swallows
 * errors: some boxes ship without the TV provider at all.
 */
// tvprovider 1.0.0 marks its public builders as RestrictedApi; every Watch Next client suppresses this.
@SuppressLint("RestrictedApi")
object WatchNext {

    private const val SCHEME = "tvsas"
    private const val HOST = "watch"

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    fun deepLink(videoUuid: String): Uri = Uri.Builder().scheme(SCHEME).authority(HOST).appendPath(videoUuid).build()

    fun videoUuidFrom(uri: Uri?): String? =
        uri?.takeIf { it.scheme == SCHEME && it.host == HOST }?.lastPathSegment?.takeIf { it.isNotEmpty() }

    fun update(context: Context, entry: HistoryEntry) {
        if (!supported) return
        val app = context.applicationContext
        App.ioScope.launch { runCatching { publish(app, entry) } }
    }

    fun remove(context: Context, videoUuid: String) {
        if (!supported) return
        val app = context.applicationContext
        App.ioScope.launch { runCatching { delete(app, videoUuid) } }
    }

    fun clear(context: Context) {
        if (!supported) return
        val app = context.applicationContext
        App.ioScope.launch {
            runCatching { app.contentResolver.delete(TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun publish(ctx: Context, e: HistoryEntry) {
        val t = e.topic
        val intentUri = Intent(Intent.ACTION_VIEW, deepLink(e.videoUuid), ctx, LaunchActivity::class.java)
            .toUri(Intent.URI_INTENT_SCHEME)
        val builder = WatchNextProgram.Builder()
            .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setLastEngagementTimeUtcMillis(e.updatedAt)
            .setTitle(t.title)
            .setDescription(
                listOfNotNull(ctx.getString(R.string.app_name), t.categoryTitle, Format.duration(e.durationSec).takeIf { it.isNotEmpty() })
                    .joinToString(" · "),
            )
            .setDurationMillis(e.durationSec * 1000)
            .setLastPlaybackPositionMillis(e.positionSec * 1000)
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
            .setIntentUri(Uri.parse(intentUri))
            .setInternalProviderId(e.videoUuid)
        t.coverUuid?.let { builder.setPosterArtUri(Uri.parse(Api.imageUrl(it, 640, 360))) }
        val values = builder.build().toContentValues()

        val resolver = ctx.contentResolver
        val existing = findId(ctx, e.videoUuid)
        if (existing != null) {
            resolver.update(TvContractCompat.buildWatchNextProgramUri(existing), values, null, null)
        } else {
            resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun delete(ctx: Context, videoUuid: String) {
        val id = findId(ctx, videoUuid) ?: return
        ctx.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun findId(ctx: Context, videoUuid: String): Long? {
        val projection = arrayOf(
            TvContractCompat.WatchNextPrograms._ID,
            TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
        )
        ctx.contentResolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == videoUuid) return c.getLong(0)
            }
        }
        return null
    }

    @Suppress("unused")
    private fun uriFor(id: Long): Uri = ContentUris.withAppendedId(TvContractCompat.WatchNextPrograms.CONTENT_URI, id)
}
