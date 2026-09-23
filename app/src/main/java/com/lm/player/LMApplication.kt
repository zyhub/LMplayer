package com.lm.player

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.network.NetworkClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LMApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()

        // 1. 异步在后台线程加载 Conscrypt 安全提供商与 Media3 缓存，0ms 阻塞冷启动主线程
        CoroutineScope(Dispatchers.IO).launch {
            NetworkClientFactory.installSecurityProvider()
            val streamCacheEnabled = getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE).getBoolean("stream_cache_enabled", true)
            com.lm.player.core.media.Media3Factory.setCacheEnabled(streamCacheEnabled)
            com.lm.player.core.media.Media3Factory.prewarm(this@LMApplication)
        }

        // 2. 预初始化全局 Room 数据库单例
        ZdsDatabase.getInstance(this)
    }

    /**
     * 针对车机与移动平台调优的高性能全局 Coil 图像加载器
     */
    override fun newImageLoader(): ImageLoader {
        val okHttpClient = NetworkClientFactory.createOkHttpClient(this)
        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .crossfade(false) // 关闭多余淡入淡出动画，换取极致流畅与零掉帧
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowHardware(Build.VERSION.SDK_INT >= 29)
            .allowRgb565(true)
            .respectCacheHeaders(false)
            .build()
    }
}
