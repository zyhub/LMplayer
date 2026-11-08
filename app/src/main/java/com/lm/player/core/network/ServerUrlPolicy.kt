package com.lm.player.core.network

import java.net.URI

/** 服务器地址策略：格式校验、协议/主机名规范化、地址与端口的拆分与拼接。 */
object ServerUrlPolicy {
    /** 柠檬音乐服务端默认端口：添加服务器时端口框的预填值 */
    const val DEFAULT_PORT = 7983

    /**
     * 只把 URL 的协议名（scheme）转小写，其余原样保留（路径、参数可能区分大小写）。
     * 用于兜底歌曲表里以前按大写 HTTPS:// 存下的封面/流地址：图片库按小写 http/https 匹配加载器，
     * 大写协议会导致封面一个请求都不发。（吸收自 PR #4，贡献者 527934973）
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

    /** 拆开的服务器地址。host 已转小写；IPv6 保留方括号；path 以 / 开头或为空 */
    data class Parts(val scheme: String, val host: String, val port: Int?, val path: String)

    private val SCHEME_RE = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")

    /**
     * 解析用户输入或已保存的地址。协议可省略（默认 http），协议名与主机名一律转小写；
     * 路径保持原样（服务端反代路径可能区分大小写）。格式不对时返回 null。
     */
    fun parse(raw: String): Parts? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        val m = SCHEME_RE.find(s)
        val scheme = m?.groupValues?.get(1)?.lowercase() ?: "http"
        if (m != null) s = s.substring(m.range.last + 1)
        // 去掉 query / fragment，服务器根地址不需要
        s = s.substringBefore('?').substringBefore('#')
        val slash = s.indexOf('/')
        val authority = if (slash >= 0) s.substring(0, slash) else s
        val path = if (slash >= 0) s.substring(slash).trimEnd('/') else ""
        val hostPort = authority.substringAfterLast('@')

        val host: String
        var port: Int? = null
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end < 0) return null
            host = hostPort.substring(0, end + 1)
            val rest = hostPort.substring(end + 1)
            if (rest.startsWith(":")) {
                if (rest.length > 1) port = rest.substring(1).toIntOrNull() ?: return null
            } else if (rest.isNotEmpty()) return null
        } else {
            val first = hostPort.indexOf(':')
            if (first >= 0) {
                // 多个冒号 = 没加方括号的 IPv6，无法区分端口
                if (hostPort.lastIndexOf(':') != first) return null
                host = hostPort.substring(0, first)
                val ps = hostPort.substring(first + 1)
                if (ps.isNotEmpty()) port = ps.toIntOrNull() ?: return null
            } else {
                host = hostPort
            }
        }
        if (host.isBlank() || host.any { it.isWhitespace() }) return null
        if (port != null && port !in 1..65535) return null
        return Parts(scheme, host.lowercase(), port, path)
    }

    fun build(p: Parts): String = buildString {
        append(p.scheme).append("://").append(p.host)
        if (p.port != null) append(':').append(p.port)
        append(p.path)
    }

    /**
     * 规范化已保存/传入的地址：协议名、主机名转小写，去掉末尾斜杠。
     * 例：`HTTP://NAS.Local:7983/Music/` → `http://nas.local:7983/Music`。无法解析时只做 trim。
     */
    fun canonicalize(raw: String): String =
        parse(raw)?.let { build(it) } ?: raw.trim().trimEnd('/')

    /** 编辑已有服务器时，把完整地址拆成「地址（不含端口）」和「端口」两栏 */
    fun splitForEditing(url: String): Pair<String, String> {
        val p = parse(url) ?: return url.trim() to ""
        return build(p.copy(port = null)) to (p.port?.toString() ?: "")
    }

    /**
     * 用地址框和端口框拼出完整地址。
     * - 地址里自带端口（如老习惯的 192.168.1.100:3000）时以地址里的为准；
     * - 端口框为空表示使用协议默认端口（http 80 / https 443），用于反向代理的域名。
     */
    fun compose(address: String, portInput: String): Result<String> {
        if (address.isBlank()) return Result.failure(IllegalArgumentException("请输入服务器地址"))
        val p = parse(address)
            ?: return Result.failure(IllegalArgumentException("服务器地址格式不正确"))
        if (p.scheme != "http" && p.scheme != "https") {
            return Result.failure(IllegalArgumentException("服务器地址必须以 http:// 或 https:// 开头"))
        }
        if (p.port != null) return Result.success(build(p))
        val portText = portInput.trim()
        if (portText.isEmpty()) return Result.success(build(p))
        val port = portText.toIntOrNull()
        if (port == null || port !in 1..65535) {
            return Result.failure(IllegalArgumentException("端口必须是 1～65535 之间的数字"))
        }
        return Result.success(build(p.copy(port = port)))
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
