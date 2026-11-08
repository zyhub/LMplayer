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
    /** 探活结果：可播 / 明确不可播（403、404、HTML 页面）/ 超时或网络错误（无法判断） */
    enum class Result { OK, BAD, UNKNOWN }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(1_200, TimeUnit.MILLISECONDS)
            .readTimeout(1_200, TimeUnit.MILLISECONDS)
            .callTimeout(1_500, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * 探活详情：除可播与否外，还给出文件总字节数（来自 Content-Range / Content-Length，未知为 -1）与 Content-Type，
     * 供上层按「字节数 ÷ 时长」估算真实码率，识别「请求无损却返回 MP3」。
     */
    data class Info(val result: Result, val totalBytes: Long, val contentType: String)

    fun probe(url: String): Boolean = probeResult(url) == Result.OK

    fun probeResult(url: String): Result = probeInfo(url).result

    fun probeInfo(url: String): Info = try {
        val builder = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-1")
            .header("Accept", "*/*")
        MusicCdnHeaders.buildHeadersForUrl(url).forEach { (key, value) ->
            builder.header(key, value)
        }
        client.newCall(builder.build()).execute().use { response ->
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            val total = when (response.code) {
                206 -> response.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
                200 -> response.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
                else -> -1L
            }
            if ((response.code == 200 || response.code == 206) && !contentType.contains("text/html")) Info(Result.OK, total, contentType)
            else Info(Result.BAD, total, contentType)
        }
    } catch (_: java.io.InterruptedIOException) {
        Info(Result.UNKNOWN, -1L, "")
    } catch (_: java.io.IOException) {
        Info(Result.UNKNOWN, -1L, "")
    } catch (_: Exception) {
        Info(Result.BAD, -1L, "")
    }
}
