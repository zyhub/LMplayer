package com.lm.player

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.media.DynamicIslandManager
import com.lm.player.core.network.NetworkClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LMApplication : Application(), ImageLoaderFactory {

    private val activeStartedActivities = mutableSetOf<Int>()

    override fun onCreate() {
        super.onCreate()

        // 1. 监听应用前后台切换，实现“应用内隐藏灵动岛，挂后台时手机顶部即时呈现概念版灵动岛”
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

            override fun onActivityStarted(activity: Activity) {
                activeStartedActivities.add(System.identityHashCode(activity))
                DynamicIslandManager.onAppBackgroundStateChanged(this@LMApplication, inBackground = false)
            }

            override fun onActivityResumed(activity: Activity) {
                activeStartedActivities.add(System.identityHashCode(activity))
                DynamicIslandManager.onAppBackgroundStateChanged(this@LMApplication, inBackground = false)
            }

            override fun onActivityPaused(activity: Activity) {}

            override fun onActivityStopped(activity: Activity) {
                activeStartedActivities.remove(System.identityHashCode(activity))
                if (activeStartedActivities.isEmpty() && !activity.isChangingConfigurations) {
                    DynamicIslandManager.onAppBackgroundStateChanged(this@LMApplication, inBackground = true)
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                activeStartedActivities.remove(System.identityHashCode(activity))
            }
        })

        // 2. 异步在后台线程加载 Conscrypt 安全提供商与 Media3 缓存，0ms 阻塞冷启动主线程
        CoroutineScope(Dispatchers.IO).launch {
            NetworkClientFactory.installSecurityProvider()
            val streamCacheEnabled = getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE).getBoolean("stream_cache_enabled", true)
            com.lm.player.core.media.Media3Factory.setCacheEnabled(streamCacheEnabled)
            com.lm.player.core.media.Media3Factory.prewarm(this@LMApplication)
        }

        // 3. 预初始化全局 Room 数据库单例与后台灵动岛引擎
        ZdsDatabase.getInstance(this)
        DynamicIslandManager.ensureInitialized(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_UI_HIDDEN) {
            activeStartedActivities.clear()
            DynamicIslandManager.onAppBackgroundStateChanged(this, inBackground = true)
        }
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
