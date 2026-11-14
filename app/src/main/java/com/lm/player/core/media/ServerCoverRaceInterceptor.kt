package com.lm.player.core.media

import android.util.Log
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 服务器歌曲封面「服务器 / 平台」竞速（2026-11-13，服务器歌曲加速第 4 期）。
 *
 * 服务器曲库歌曲的封面是服务器接口（`/api/tag/cover?path=…`），弱网下第一次加载很慢。
 * 加速表里有这首歌的平台封面（CDN）时：
 * - 网络正常：服务器和平台一起请求，谁先拿到用谁的（另一个取消）；
 * - 弱网（服务器离线 / 平均耗时超过 800 毫秒 / 最近请求失败）：直接先用平台封面，拿不到再请求服务器。
 *
 * 放在图片加载器这一层，所有界面（主页卡片、播放页三主题、列表、小组件）都自动生效；
 * 只换显示用的图片来源，不改资料库里的封面地址（11-11 规则）。看过的封面照样进图片磁盘缓存，下次直接命中。
 */
object ServerCoverRaceInterceptor : Interceptor {
    private const val TAG = "CoverRace"

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = when (val data = request.data) {
            is String -> data
            is android.net.Uri -> data.toString()
            else -> null
        }
        if (url == null || !url.contains("/api/tag/cover")) return chain.proceed(request)
        val platformUrl = runCatching {
            com.lm.player.core.source.ServerTrackAccelStore.platformCoverForServerCoverUrl(url)
        }.getOrNull()
        if (platformUrl.isNullOrBlank()) return chain.proceed(request)
        val alt = request.newBuilder().data(platformUrl).build()

        if (com.lm.player.core.source.SourceAccelerationController.isServerWeak()) {
            val p = chain.proceed(alt)
            if (p is SuccessResult) return p
            return chain.proceed(request)
        }

        val result: ImageResult? = coroutineScope {
            val server = async { runCatching { chain.proceed(request) }.getOrNull() }
            val platform = async { runCatching { chain.proceed(alt) }.getOrNull() }
            // 先成功的一方；都失败时为 null
            val winner = CompletableDeferred<ImageResult?>()
            val remaining = AtomicInteger(2)
            var serverResult: ImageResult? = null
            fun onDone(r: ImageResult?, fromPlatform: Boolean) {
                if (r is SuccessResult && winner.complete(r) && fromPlatform) Log.d(TAG, "platform cover first: $platformUrl")
                if (remaining.decrementAndGet() == 0) winner.complete(null)
            }
            launch {
                val r = server.await()
                serverResult = r
                onDone(r, fromPlatform = false)
            }
            launch { onDone(platform.await(), fromPlatform = true) }
            val r = winner.await()
            server.cancel()
            platform.cancel()
            // 都失败：交回服务器那一次的失败结果（界面照常走它的失败重试）
            r ?: serverResult
        }
        return result ?: chain.proceed(request)
    }
}
