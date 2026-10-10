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

        // 2. 异步在后台线程加载 Conscrypt 安全提供商、Media3 缓存与 Room 数据库，0ms 阻塞冷启动主线程
        CoroutineScope(Dispatchers.IO).launch {
            NetworkClientFactory.installSecurityProvider()

            // 键名统一为 stream_cache_enabled_v2：此前 Application 读 stream_cache_enabled
            // 而 Media3Factory 读 stream_cache_enabled_v2，两个键互不同步，
            // 该布尔值会被两处竞争改写（P3-4）。
            val prefs = getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE)
            val streamCacheEnabled = prefs.getBoolean("stream_cache_enabled_v2", false)
            com.lm.player.core.media.Media3Factory.setCacheEnabled(streamCacheEnabled)
            com.lm.player.core.media.Media3Factory.prewarm(this@LMApplication)

            // Room 首次 open 要建库/校验 schema，是在磁盘上做实事的操作。
            // 放在主线程会直接拖慢冷启动首帧（与上面的注释目标相矛盾），此处挪进 IO 协程。
            // 单例本身是 @Volatile + synchronized 的，主线程后续取用拿到的是同一实例。
            ZdsDatabase.getInstance(this@LMApplication)
        }

        // 3. 后台灵动岛引擎初始化（纯内存状态读取，无磁盘 IO）
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
                    // 图片磁盘缓存 96MB（原 256MB）：与流媒体缓存、OkHttp 缓存合计
                    // 不应把 cacheDir 撑到 GB 级；Coil 内存缓存已能覆盖绝大多数滚动场景
                    .maxSizeBytes(96L * 1024 * 1024)
                    .build()
            }
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowHardware(Build.VERSION.SDK_INT >= 29)
            .allowRgb565(true)
            .respectCacheHeaders(false)
            .build()
    }
}
