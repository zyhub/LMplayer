package com.lm.player.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 播放前轻量探活：只对“本地音源加速拿到的直链”使用。
 *
 * 用 GET + Range: bytes=0-1 而不是 HEAD：部分 CDN 对 HEAD 返回 403，但 Range GET 正常。
 * 总超时 1.5 秒；失败视为不可播，调用方立即回退服务器/NAS，避免 ExoPlayer 先报错再走
 * onPlayerError 的三级容灾。
 */
object CdnProbe {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(1_200, TimeUnit.MILLISECONDS)
            .readTimeout(1_200, TimeUnit.MILLISECONDS)
            .callTimeout(1_500, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build()
    }

    fun probe(url: String): Boolean = runCatching {
        val builder = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-1")
            .header("Accept", "*/*")
        MusicCdnHeaders.buildHeadersForUrl(url).forEach { (key, value) ->
            builder.header(key, value)
        }
        client.newCall(builder.build()).execute().use { response ->
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            (response.code == 200 || response.code == 206) && !contentType.contains("text/html")
        }
    }.getOrDefault(false)
}
