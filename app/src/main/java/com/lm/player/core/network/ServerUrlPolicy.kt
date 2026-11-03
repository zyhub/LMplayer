package com.lm.player.core.network

import java.net.URI

/** 服务器地址安全策略：允许私网 HTTP，公网 HTTP 必须改为 HTTPS。 */
object ServerUrlPolicy {
    /**
     * 将 URL 的 scheme 部分规范为小写，其余部分原样保留（避免破坏大小写敏感的路径/参数）。
     *
     * 用户配置服务器地址时可能输入大写 HTTPS://，由此拼出的封面与流地址 scheme 也为大写；
     * 而 Coil 的 Fetcher 注册表按小写 "http"/"https" 精确匹配 scheme，客户端大量
     * startsWith("http") 判定同样大小写敏感，大写 scheme 会导致封面零请求、播放链路误判。
     * RFC 3986 规定 scheme 不区分大小写，统一归一为小写。
     */
    fun normalizeSchemeCase(raw: String): String {
        val separator = raw.indexOf("://")
        if (separator <= 0) return raw
        val scheme = raw.substring(0, separator)
        val lowered = scheme.lowercase()
        return if (scheme == lowered) raw else lowered + raw.substring(separator)
    }

    fun validate(raw: String): String? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull()
            ?: return "服务器地址格式不正确"
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") return "服务器地址必须以 http:// 或 https:// 开头"
        val host = uri.host?.trim()?.lowercase().orEmpty()
        if (host.isBlank()) return "服务器地址缺少主机名"
        // 自动兼容 HTTP：不再限制公网域名，仅校验地址格式。
        return null
    }

    fun isPrivateHost(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (h == "localhost" || h == "::1" || h.endsWith(".local")) return true
        if (h.contains(":") && (h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd"))) return true
        val parts = h.split(".")
        if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return false
        val a = parts[0].toInt()
        val b = parts[1].toInt()
        return when {
            a == 10 -> true
            a == 127 -> true
            a == 169 && b == 254 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            else -> false
        }
    }
}
