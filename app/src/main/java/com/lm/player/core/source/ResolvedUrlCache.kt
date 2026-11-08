package com.lm.player.core.source

import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 统一的平台直链缓存（播放 / 预取 / 下载 / 服务器曲库加速共用）。
 *
 * 键：平台 + 平台曲目 ID + 音质。只放内存（平台签名链接很快失效）。
 * TTL 优先按 URL 自带的过期参数收敛到「过期前 1 分钟」，否则 8 分钟（与服务端 playUrlCache 一致）。
 */
object ResolvedUrlCache {
    private const val DEFAULT_TTL_MS = 8 * 60 * 1000L
    private const val MIN_TTL_MS = 60 * 1000L
    private const val MAX_TTL_MS = 30 * 60 * 1000L
    private const val MAX_ENTRIES = 300

    data class Entry(
        val url: String,
        val qualityKey: String,
        val platform: String,
        val expiresAt: Long,
        val viaLocal: Boolean
    )

    private val map = ConcurrentHashMap<String, Entry>()

    fun key(platform: String, songKey: String, qualityKey: String): String =
        "${platform.lowercase()}|$songKey|${qualityKey.lowercase()}"

    fun get(platform: String, songKey: String, qualityKey: String): Entry? {
        val k = key(platform, songKey, qualityKey)
        val e = map[k] ?: return null
        if (e.expiresAt <= System.currentTimeMillis()) {
            map.remove(k)
            return null
        }
        return e
    }

    fun put(platform: String, songKey: String, requestedQuality: String, url: String, actualQuality: String, viaLocal: Boolean) {
        if (url.isBlank()) return
        val now = System.currentTimeMillis()
        map[key(platform, songKey, requestedQuality)] = Entry(url, actualQuality, platform, now + ttlFor(url, now), viaLocal)
        if (map.size > MAX_ENTRIES) prune(now)
    }

    fun invalidate(platform: String, songKey: String, qualityKey: String) {
        map.remove(key(platform, songKey, qualityKey))
    }

    /** 播放器报 403/404 时按 URL 失效所有指向它的条目。 */
    fun invalidateUrl(url: String) {
        if (url.isBlank()) return
        map.entries.removeIf { it.value.url == url }
    }

    fun clear() = map.clear()

    private fun prune(now: Long) {
        map.entries.removeIf { it.value.expiresAt <= now }
        if (map.size > MAX_ENTRIES) {
            map.entries.sortedBy { it.value.expiresAt }.take(map.size - MAX_ENTRIES).forEach { map.remove(it.key) }
        }
    }

    fun ttlFor(url: String, now: Long): Long {
        val expiry = extractExpiryMs(url, now) ?: return DEFAULT_TTL_MS
        return (expiry - now - 60_000L).coerceIn(MIN_TTL_MS, MAX_TTL_MS)
    }

    /** 解析 URL 查询参数里的秒/毫秒级过期时间；只接受合理区间，避免把歌曲 ID 当成时间。 */
    fun extractExpiryMs(url: String, now: Long = System.currentTimeMillis()): Long? {
        val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return null
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val key = pair.substring(0, idx).lowercase()
            if (key !in setOf("expires", "expire", "deadline", "expires_at", "expire_at", "e")) continue
            val value = runCatching { URLDecoder.decode(pair.substring(idx + 1), "UTF-8") }.getOrNull()?.trim() ?: continue
            val raw = value.toLongOrNull() ?: continue
            val millis = when (raw) {
                in 1_000_000_000L..9_999_999_999L -> raw * 1000L
                in 1_000_000_000_000L..9_999_999_999_999L -> raw
                else -> continue
            }
            if (millis > now - 86_400_000L && millis < now + 7L * 86_400_000L) return millis
        }
        return null
    }
}
