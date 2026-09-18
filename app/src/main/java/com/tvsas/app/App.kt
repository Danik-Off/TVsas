package com.tvsas.app

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.tvsas.app.data.Api
import com.tvsas.app.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application(), ImageLoaderFactory {

    companion object {
        /** Process-wide scope for fire-and-forget work that must outlive a screen (e.g. saving progress). */
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Api.init(this)
    }

    /**
     * Image pipeline tuned for low-end TV boxes: RGB_565 bitmaps (half the RAM of ARGB_8888),
     * a modest memory cache and a shared OkHttp client.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { Api.client }
        .bitmapConfig(Bitmap.Config.RGB_565)
        .allowRgb565(true)
        .allowHardware(false) // hardware bitmaps misbehave on several old GPUs / API 26-27 boxes
        .crossfade(false)
        .memoryCache {
            MemoryCache.Builder(this).maxSizePercent(0.12).build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                .maxSizeBytes(40L * 1024 * 1024)
                .build()
        }
        .build()
}
