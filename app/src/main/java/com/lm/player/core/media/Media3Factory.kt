package com.lm.player.core.media

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lm.player.core.network.NetworkClientFactory
import okhttp3.OkHttpClient
import java.io.File

@OptIn(UnstableApi::class)
object Media3Factory {

    private const val TAG = "Media3Factory"

    @Volatile
    private var simpleCacheInstance: SimpleCache? = null

    @Volatile
    private var sharedExoPlayer: ExoPlayer? = null

    @Synchronized
    fun getSimpleCache(context: Context): SimpleCache {
        if (simpleCacheInstance == null) {
            val cacheDir = File(context.applicationContext.cacheDir, "media3_lru_stream_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            // 流媒体缓存上限：**按可用空间动态取值**，上限 300MB。
            // 此前硬编码 2GB —— 而 cacheDir 下还有图片缓存与 OkHttp 缓存，三者合计可达 2.3GB，
            // 电视盒子/车机内置存储常为 8~16GB，会显著挤占空间；Android 只在存储告急时才回收
            // 缓存目录且不保证。行业常规（ExoPlayer 官方示例）为 50~200MB。
            val cacheBudgetBytes = run {
                val capBytes = 300L * 1024 * 1024
                try {
                    val stat = android.os.StatFs(cacheDir.absolutePath)
                    // 取「上限」与「可用空间的 1/10」中的较小值，最低保留 64MB 保证基本缓冲能力
                    val dynamic = (stat.availableBytes / 10).coerceAtLeast(64L * 1024 * 1024)
                    minOf(capBytes, dynamic)
                } catch (_: Exception) {
                    capBytes
                }
            }
            val evictor = LeastRecentlyUsedCacheEvictor(cacheBudgetBytes)
            val databaseProvider = StandaloneDatabaseProvider(context.applicationContext)
            simpleCacheInstance = SimpleCache(cacheDir, evictor, databaseProvider)
        }
        return simpleCacheInstance!!
    }

    /**
     * 在后台线程异步预热 Media3 磁盘与 SQLite 缓存，消除主线程冷启动 IO 阻塞
     */
    fun prewarm(context: Context) {
        try {
            getSimpleCache(context)
        } catch (_: Exception) {}
    }

    @Volatile
    private var isCacheEnabled = false

    fun setCacheEnabled(enabled: Boolean) {
        isCacheEnabled = enabled
    }

    fun isCacheEnabled(): Boolean = isCacheEnabled

    /**
     * 构建双协议自适应数据源工厂：
     * 1. 使用 DefaultDataSource.Factory 智能分发：
     *    - file:// 与 content:// 协议直通本地文件解码，彻底解决本地已下载歌曲播放无反应问题；
     *    - http:// 与 https:// 协议受 isCacheEnabled 控制：
     *      * 开启缓存时由 OkHttpDataSource 与 SimpleCache 接管，支持高速流媒体与磁盘断点续传；
     *      * 关闭缓存时直通 DefaultDataSource 纯内存流式试听，彻底不写磁盘缓存。
     */
    fun buildDataSourceFactory(context: Context, okHttpClient: OkHttpClient): DataSource.Factory {
        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)

        val upstreamFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        val cache = getSimpleCache(context)
        val cacheDataSinkFactory = CacheDataSink.Factory()
            .setCache(cache)
            .setFragmentSize(20L * 1024 * 1024) // 20MB 单切片，避免默认 5MB 频繁断开重连触发服务端代理并发限制
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return DataSource.Factory {
            if (isCacheEnabled) {
                cacheDataSourceFactory.createDataSource()
            } else {
                upstreamFactory.createDataSource()
            }
        }
    }

    /**
     * 释放进程级共享播放器。
     *
     * **为什么必须有这个方法**：sharedExoPlayer 是进程级静态字段，此前全工程没有任何一处调用
     * release()。播放器一旦 build 过就会一直持有：
     * - setWakeMode(C.WAKE_MODE_NETWORK) 带来的 PARTIAL_WAKE_LOCK + WifiLock（用户暂停后依然持有）；
     * - 音频解码器、AudioTrack 与 OkHttp 连接；
     * - PlaybackQueueManager 注册的 Player.Listener（通过弱引用守卫可自动失效，见该类 ensurePlayerListener）。
     * 结果是从最近任务划掉应用后，进程仍可能存活数分钟到数十分钟，期间设备无法进入深度睡眠。
     *
     * 只在「用户明确要求彻底停止播放」的路径上调用；调用后下次 getSharedExoPlayer 会重建实例。
     */
    @Synchronized
    fun releaseSharedPlayer() {
        val player = sharedExoPlayer ?: return
        sharedExoPlayer = null
        try {
            player.release()
            Log.i(TAG, "Shared ExoPlayer released")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to release shared ExoPlayer", e)
        }
    }

    @Synchronized
    fun getSharedExoPlayer(context: Context): ExoPlayer {
        if (sharedExoPlayer == null) {
            val appContext = context.applicationContext
            isCacheEnabled = appContext.getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE)
                .getBoolean("stream_cache_enabled_v2", false)
            val okHttpClient = NetworkClientFactory.createOkHttpClient(appContext)
            val dataSourceFactory = buildDataSourceFactory(appContext, okHttpClient)

            val renderersFactory = DefaultRenderersFactory(appContext)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

            // 针对柠檬音乐服务端 /api/play/proxy (15秒 Socket 空闲超时) 调优缓冲窗口：
            // minBufferMs=50s 与 maxBufferMs=55s 仅差 5 秒 (< 15秒)，保证流连接每 5 秒持续读取保活，彻底根治播半首断开暂停问题
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 50_000,
                    /* maxBufferMs = */ 55_000,
                    /* bufferForPlaybackMs = */ 600,
                    /* bufferForPlaybackAfterRebufferMs = */ 1_500
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            // 车机/手机音频属性：由 PlaybackService 智能管理音频焦点压音 (Ducking) 与通话暂停恢复，
            // 防止车机导航 (高德/百度)/雷达/语音助手强抢 AUDIOFOCUS_GAIN 导致 ExoPlayer 永久停止后台播放
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            sharedExoPlayer = ExoPlayer.Builder(appContext)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(DefaultMediaSourceFactory(appContext).setDataSourceFactory(dataSourceFactory))
                .setAudioAttributes(audioAttributes, false)
                .setWakeMode(C.WAKE_MODE_NETWORK) // 同时持有 CPU WakeLock 与 WifiLock，防止息屏或后台流媒体休眠断流
                .setHandleAudioBecomingNoisy(false) // 避免车机蓝牙/通道切换广播 ACTION_AUDIO_BECOMING_NOISY 误停后台播放
                .build()
        }
        return sharedExoPlayer!!
    }

    /**
     * 获取当前流媒体缓存大小 (字节)
     */
    fun getCacheSizeBytes(context: Context): Long {
        return try {
            val cacheDir = File(context.applicationContext.cacheDir, "media3_lru_stream_cache")
            if (cacheDir.exists()) {
                cacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 清理流媒体缓存
     */
    fun clearStreamCache(context: Context) {
        // 注意：本函数**不会**释放播放器或删除缓存目录。
        try {

            // 关键点：DataSource 工厂在**构建播放器时**捕获了 SimpleCache 实例。
            // 因此不能「一边使用、一边 release 并删除目录」——SimpleCache 内部的 released
            // 断言会抛 IllegalStateException，内存索引与磁盘分片也会不一致。
            //
            // 但同样不能靠「先 releaseSharedPlayer()」来解决：那会让**正在播放的曲目直接中断**，
            // 用户点一下「清理试听缓存」音乐就哑了，必须手动恢复 —— 为一个清理动作付这个代价是不可接受的。
            //
            // 正确做法：**不释放、不删目录**，只逐 key 清掉缓存内容。这样
            //   ① 播放器的 DataSource 引用依旧有效，播放完全不受影响；
            //   ② 磁盘上的分片文件被逐个删除，空间照常释放；
            //   ③ SimpleCache 的索引与磁盘始终一致，不会抛异常。
            // SimpleCache 自身的 LRU 上限（按可用空间动态取值，上限 300MB）负责后续回收。
            val cache = simpleCacheInstance
            if (cache != null) {
                for (key in cache.keys.toSet()) {
                    try { cache.removeResource(key) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    fun clearCache(context: Context) = clearStreamCache(context)
}
