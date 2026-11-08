package com.lm.player.core.source

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 柠檬服务端「特征探活」（链路状态机、设置页在线检测、「测试连接」共用）。
 *
 * 反向代理场景下「有 HTTP 响应」不等于柠檬在线：反代会返回 502/504、HTML 错误页、门户登录页（常为 200），
 * 甚至缓存里的旧 JSON。这里只认柠檬服务端自身的特征：
 * 1. `GET /api/health`（免登录）：HTTP 200 + JSON + `ok == true` + 数值型 `uptime`；
 *    带时间戳参数与 no-cache 头穿透缓存，两次相隔 >8 秒 uptime 却没变 → 判定为缓存的旧结果；
 * 2. `GET /api/auth/me`：已登录返回 `{user:{...}}`，未登录返回 401 `{code:"UNAUTHORIZED"}`；
 *    反代自己的 401/403/HTML 不带这些字段。既是备用探活，也是「登录是否有效」的唯一判据。
 */
object LemonServerProbe {
    /** 探活请求标记：LocalCatalogGateway 熔断时放行带此头的请求 */
    const val PROBE_HEADER = "X-LM-Probe"

    data class Outcome(
        /** 确认是柠檬服务端在响应 */
        val alive: Boolean,
        val reason: String,
        /** 仅 auth/me：令牌是否有效（null = 未检查 / 无法判断） */
        val authOk: Boolean? = null
    )

    private data class UptimeMark(val uptime: Long, val at: Long)

    private val lastUptime = ConcurrentHashMap<String, UptimeMark>()

    fun resetCache(base: String) {
        lastUptime.remove(base)
    }

    private fun noCache(builder: Request.Builder): Request.Builder = builder
        .header("Accept", "application/json")
        .header("Cache-Control", "no-cache, no-store")
        .header("Pragma", "no-cache")
        .header(PROBE_HEADER, "1")

    private fun describe(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "域名解析失败"
        is java.net.ConnectException -> "连接被拒绝"
        is javax.net.ssl.SSLException -> "TLS 失败"
        is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "连接超时"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** 主探活：/api/health 柠檬特征 + 反缓存校验 */
    fun health(client: OkHttpClient, base: String): Outcome {
        val request = noCache(Request.Builder().url("$base/api/health?_lm=${System.currentTimeMillis()}").get()).build()
        return try {
            client.newCall(request).execute().use { resp ->
                val type = resp.header("Content-Type").orEmpty().lowercase()
                val text = resp.body?.string().orEmpty().take(64 * 1024)
                val json = runCatching { JSONObject(text) }.getOrNull()
                val uptime = (json?.opt("uptime") as? Number)?.toLong() ?: -1L
                val lemon = resp.code == 200 && json != null && json.optBoolean("ok") && uptime >= 0
                if (!lemon) {
                    return Outcome(false, "HTTP ${resp.code} ${type.substringBefore(';').ifBlank { "无类型" }}（不是柠檬服务端的响应）")
                }
                val now = System.currentTimeMillis()
                val prev = lastUptime[base]
                val stale = prev != null && uptime == prev.uptime && now - prev.at > 8_000L
                if (!stale) lastUptime[base] = UptimeMark(uptime, now)
                if (stale) Outcome(false, "探活结果疑似被反代缓存（uptime 未变化）") else Outcome(true, "ok")
            }
        } catch (e: java.io.IOException) {
            Outcome(false, describe(e))
        }
    }

    /** 备用探活 + 鉴权校验：/api/auth/me */
    fun authMe(client: OkHttpClient, base: String, token: String?): Outcome {
        val builder = noCache(Request.Builder().url("$base/api/auth/me?_lm=${System.currentTimeMillis()}").get())
        token?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        return try {
            client.newCall(builder.build()).execute().use { resp ->
                val json = runCatching { JSONObject(resp.body?.string().orEmpty().take(64 * 1024)) }.getOrNull()
                when {
                    json != null && resp.code == 200 && json.optJSONObject("user") != null -> Outcome(true, "ok", authOk = true)
                    json != null && resp.code == 401 && json.optString("code") == "UNAUTHORIZED" -> Outcome(true, "未登录或登录已过期", authOk = false)
                    else -> Outcome(false, "auth/me HTTP ${resp.code}（不是柠檬服务端的响应）")
                }
            }
        } catch (e: java.io.IOException) {
            Outcome(false, describe(e))
        }
    }

    /** 是否确认是柠檬服务端在线：主探活失败时再用 auth/me 确认（部分反代单独拦截/改写了 /api/health） */
    fun alive(client: OkHttpClient, base: String, token: String?): Outcome {
        val primary = health(client, base)
        if (primary.alive) return primary
        val second = authMe(client, base, token)
        return if (second.alive) second else primary
    }

    /** 带超时的派生客户端：继承共享客户端的证书/UA 配置，但不走磁盘缓存、不受 300s callTimeout 影响 */
    fun clientFrom(base: OkHttpClient, callTimeoutMs: Long): OkHttpClient = base.newBuilder()
        .cache(null)
        .connectTimeout(minOf(callTimeoutMs, 5_000L), java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(minOf(callTimeoutMs, 7_000L), java.util.concurrent.TimeUnit.MILLISECONDS)
        .callTimeout(callTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()
}
