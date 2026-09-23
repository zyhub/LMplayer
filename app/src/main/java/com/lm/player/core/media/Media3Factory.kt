package com.lm.player.core.media

import android.content.Context
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
            // 2GB 最大磁盘 LRU 缓存，超出时自动淘汰最早未命中的音轨缓存切片
            val evictor = LeastRecentlyUsedCacheEvictor(2L * 1024 * 1024 * 1024)
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
    private var isCacheEnabled = true

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
            .setUserAgent("LMPlayer/1.0 (Android; Low-Latency-Streaming-Engine)")

        val upstreamFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        val cache = getSimpleCache(context)
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return DataSource.Factory {
            if (isCacheEnabled) {
                cacheDataSourceFactory.createDataSource()
            } else {
                upstreamFactory.createDataSource()
            }
        }
    }

    @Synchronized
    fun getSharedExoPlayer(context: Context): ExoPlayer {
        if (sharedExoPlayer == null) {
            val appContext = context.applicationContext
            val okHttpClient = NetworkClientFactory.createOkHttpClient(appContext)
            val dataSourceFactory = buildDataSourceFactory(appContext, okHttpClient)

            val renderersFactory = DefaultRenderersFactory(appContext)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

            // 极速低延迟播放缓冲控制器 (bufferForPlaybackMs: 500ms 极速起播，避免传统 2500ms 等待)
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 15_000,
                    /* maxBufferMs = */ 50_000,
                    /* bufferForPlaybackMs = */ 500,
                    /* bufferForPlaybackAfterRebufferMs = */ 1_000
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            // 车机/手机音频焦点配置：自动处理导航提示语音混音/压音 (Ducking) 与来电暂停
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            sharedExoPlayer = ExoPlayer.Builder(appContext)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(DefaultMediaSourceFactory(appContext).setDataSourceFactory(dataSourceFactory))
                .setAudioAttributes(audioAttributes, true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .setHandleAudioBecomingNoisy(true) // 拔出耳机或蓝牙断开自动暂停
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
        try {
            simpleCacheInstance?.let { cache ->
                for (key in cache.keys.toSet()) {
                    try { cache.removeResource(key) } catch (_: Exception) {}
                }
            }
            val cacheDir = File(context.applicationContext.cacheDir, "media3_lru_stream_cache")
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
                cacheDir.mkdirs()
            }
        } catch (_: Exception) {}
    }

    fun clearCache(context: Context) = clearStreamCache(context)
}
