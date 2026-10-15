package com.lm.player.core.network

/**
 * 音乐平台 CDN 请求头字典与防盗链处理器
 * 深度借鉴并对齐柠檬音乐服务端的 musicCdnHeaders.js 规范
 * 用于解决手机端直接从各大音乐平台 CDN 拉流与下载时的 403 Forbidden 校验
 */
object MusicCdnHeaders {

    /** 统一浏览器 User-Agent，与各大平台 Web 端主流规范一致 */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 各平台专用 Referer 与 Origin 字典（缺少 Referer 时 QQ/网易等会直接拦截 403） */
    private val SOURCE_REFERERS = mapOf(
        "tx" to mapOf(
            "Referer" to "https://y.qq.com/",
            "Origin" to "https://y.qq.com"
        ),
        "qq" to mapOf(
            "Referer" to "https://y.qq.com/",
            "Origin" to "https://y.qq.com"
        ),
        "kw" to mapOf(
            "Referer" to "https://www.kuwo.cn/"
        ),
        "kg" to mapOf(
            "Referer" to "https://www.kugou.com/"
        ),
        "wy" to mapOf(
            "Referer" to "https://music.163.com/"
        ),
        "mg" to mapOf(
            "Referer" to "https://music.migu.cn/"
        )
    )

    /**
     * 根据平台标识构造拉取 CDN 音频所需的标准请求头
     */
    fun buildHeaders(source: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val platform = source.lowercase().trim()
        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] = USER_AGENT
        headers["Accept"] = "*/*"

        val platformHeaders = SOURCE_REFERERS[platform]
        if (platformHeaders != null) {
            headers.putAll(platformHeaders)
        }

        headers.putAll(extra)
        return headers
    }

    /**
     * 智能从直链 URL 中推测所属音源平台，并自动匹配 Referer
     */
    fun guessSourceFromUrl(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("qq.com") || lower.contains("tc.qq.com") || lower.contains("gtimg.cn") -> "tx"
            lower.contains("kuwo.cn") -> "kw"
            lower.contains("kugou.com") || lower.contains("kgimg.com") -> "kg"
            lower.contains("163.com") || lower.contains("126.net") -> "wy"
            lower.contains("migu.cn") -> "mg"
            else -> ""
        }
    }

    /**
     * 为给定的音频直链 URL 自动构造全套防盗链 Headers
     */
    fun buildHeadersForUrl(url: String, explicitSource: String? = null): Map<String, String> {
        val source = explicitSource?.takeIf { it.isNotBlank() } ?: guessSourceFromUrl(url)
        return buildHeaders(source)
    }
}
