package com.lm.player.core.network

/**
 * 服务器请求延迟监测（按 host 维度）。
 *
 * 只统计 JSON/控制类 /api/ 请求，不统计 /api/play/local、/api/play/proxy 等
 * 长连接音频流，否则一个 30MB 的下载会被误判成「服务器延迟极高」。
 *
 * 用途：
 * 1. 音源状态看板展示服务器延迟与在线状态；
 * 2. 延迟升高时，允许使用最近成功解析过的直链缓存（见 ServerSourceRepository）。
 */
object ServerLatencyMonitor {

    data class Sample(
        val ewmaMs: Long = 0L,
        val lastMs: Long = 0L,
        val lastSuccessAt: Long = 0L,
        val lastFailureAt: Long = 0L,
        val sampleCount: Int = 0,
        val failureCount: Int = 0
    )

    private val samples = java.util.concurrent.ConcurrentHashMap<String, Sample>()

    /** 低于该值视为正常；超过则进入「延迟偏高」状态 */
    const val DEGRADED_THRESHOLD_MS = 1_200L

    fun record(host: String, elapsedMs: Long, success: Boolean) {
        val key = normalizeHost(host)
        if (key.isBlank()) return
        val now = System.currentTimeMillis()
        samples.compute(key) { _, old ->
            val prev = old ?: Sample()
            val safeElapsed = elapsedMs.coerceIn(0L, 60_000L)
            val nextEwma = if (prev.sampleCount <= 0) safeElapsed else (prev.ewmaMs * 7 + safeElapsed * 3) / 10
            prev.copy(
                ewmaMs = nextEwma,
                lastMs = safeElapsed,
                lastSuccessAt = if (success) now else prev.lastSuccessAt,
                lastFailureAt = if (success) prev.lastFailureAt else now,
                sampleCount = prev.sampleCount + 1,
                failureCount = prev.failureCount + if (success) 0 else 1
            )
        }
    }

    fun get(host: String): Sample? = samples[normalizeHost(host)]

    /** OkHttp 的 request.url.host 不带端口；配置里的 serverHost 常带 :8099，必须统一键。 */
    fun normalizeHost(host: String): String =
        host.trim().lowercase().substringBefore(':').substringBefore('/')

    fun latencyMs(host: String): Long = get(host)?.ewmaMs ?: 0L

    /** 最近一次成功请求是否在 graceMs 内（用于判断服务器是否仍算「在线」） */
    fun isRecentlyOnline(host: String, graceMs: Long = 10 * 60 * 1000L): Boolean {
        val sample = get(host) ?: return false
        return sample.lastSuccessAt > 0L && System.currentTimeMillis() - sample.lastSuccessAt <= graceMs
    }

    /** 延迟偏高：EWMA 超阈值，或最近一次请求明显超阈值 */
    fun isDegraded(host: String): Boolean {
        val sample = get(host) ?: return false
        return sample.ewmaMs >= DEGRADED_THRESHOLD_MS || sample.lastMs >= DEGRADED_THRESHOLD_MS * 2
    }

    fun clear() = samples.clear()
}
