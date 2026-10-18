package com.lm.player.core.network

import java.net.URI

/** 服务器地址安全策略：允许私网 HTTP，公网 HTTP 必须改为 HTTPS。 */
object ServerUrlPolicy {
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
