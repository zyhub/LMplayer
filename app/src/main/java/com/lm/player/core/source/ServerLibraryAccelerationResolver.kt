package com.lm.player.core.source

import android.util.Log
import com.lm.player.core.media.SongMatchingResolver
import com.lm.player.core.model.AudioQuality
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.network.LemonMusicProtocol
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 服务器曲库歌曲的本地加速解析。
 *
 * 服务器曲库只有文件元数据，没有平台 source/songId；这里先用 title+artist 调服务器
 * /api/search 找到平台曲目，再交给 QuickJS 本地取链。任何失败都返回 failure，
 * 调用方直接回退 NAS 文件。
 *
 * 预算策略：
 * - 搜索与本地取链使用**独立**预算，避免搜索慢时把本地取链压到只剩几十毫秒；
 * - 本地取链走 [SourceAccelerationSession.resolveMusicUrlWithBudget]，超时只停止等待、
 *   不取消 native evaluate；被放弃的求值由对应引擎 busy 标记隔离。
 */
object ServerLibraryAccelerationResolver {
    private const val TAG = "ServerLibAccel"
    private const val SEARCH_BUDGET_MS = 1_800L
    private const val LOCAL_BUDGET_MS = 2_200L
    private const val DEFAULT_CACHE_TTL_MS = 10 * 60 * 1000L
    private const val MIN_CACHE_TTL_MS = 60 * 1000L
    private const val MAX_CACHE_TTL_MS = 30 * 60 * 1000L
    private const val MATCH_CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    private const val CACHE_SOFT_LIMIT = 512
    private const val FALLBACK_ORDER = "kw,tx,wy,kg,mg"

    data class Resolved(
        val url: String,
        val quality: AudioQuality,
        val source: String,
        val matchedTrack: UnifiedSong,
        val fromCache: Boolean = false,
        val requestedQuality: AudioQuality = quality
    )

    private data class Cached(
        val url: String,
        val quality: AudioQuality,
        val source: String,
        val expiresAt: Long,
        val requestedQuality: AudioQuality
    )

    private data class CachedMatch(val song: UnifiedSong, val at: Long)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val matchCache = ConcurrentHashMap<String, CachedMatch>()

    suspend fun resolve(
        protocol: LemonMusicProtocol,
        serverId: String,
        song: UnifiedSong,
        overrideQuality: String? = null,
        refresh: Boolean = false
    ): Result<Resolved> {
        if (!SourceAccelerationController.shouldAccelerate()) {
            return Result.failure(IllegalStateException("音源加速未启用"))
        }
        val quality = overrideQuality?.let { AudioQuality.fromKey(it) } ?: inferQuality(song)
        val key = cacheKey(serverId, song.id, quality.key)
        val now = System.currentTimeMillis()
        if (refresh) {
            cache.remove(key)
        } else {
            cache[key]?.takeIf { it.expiresAt > now }?.let {
                return Result.success(
                    Resolved(
                        url = it.url,
                        quality = it.quality,
                        source = it.source,
                        matchedTrack = song,
                        fromCache = true,
                        requestedQuality = it.requestedQuality
                    )
                )
            }
        }
        val platforms = platformOrder(serverId)
        if (platforms.isEmpty()) return Result.failure(IllegalStateException("没有可用平台"))

        val cacheMatchKey = matchKey(serverId, song)
        val cachedMatch = if (!refresh) {
            matchCache[cacheMatchKey]?.takeIf { now - it.at <= MATCH_CACHE_TTL_MS }?.song
        } else {
            null
        }
        // 服务器 LOST 时不能再调 /api/search；但若之前已经匹配过平台曲目，
        // 本地引擎仍可独立从 CDN 取链，应继续尝试，而不是直接放弃。
        if (SourceAccelerationController.isLost && cachedMatch == null) {
            return Result.failure(IllegalStateException("服务器离线，无法匹配曲库"))
        }
        val match = cachedMatch ?: searchForMatch(protocol, song, platforms)
            ?: return Result.failure(IllegalStateException("服务器曲库搜索超时或未命中"))
        if (!refresh) {
            matchCache[cacheMatchKey] = CachedMatch(match, now)
        }

        val platform = sourceOf(match)
        val info = buildMusicInfo(match)
        val local = SourceAccelerationSession.resolveMusicUrlWithQualityFallback(
            source = platform,
            preferredQuality = quality.key,
            musicInfoJson = info,
            budgetMs = LOCAL_BUDGET_MS
        )
        val resolution = local.getOrNull()
            ?: return Result.failure(
                IllegalStateException(
                    "服务器曲库本地解析失败: ${local.exceptionOrNull()?.message ?: "空地址"}"
                )
            )
        val url = resolution.url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return Result.failure(IllegalStateException("服务器曲库本地解析返回非 http 地址"))
        val actualQuality = AudioQuality.fromKey(resolution.qualityKey)
        val resolvedAt = System.currentTimeMillis()
        cache[key] = Cached(
            url = url,
            quality = actualQuality,
            source = platform,
            expiresAt = resolvedAt + effectiveTtlMs(url, resolvedAt),
            requestedQuality = quality
        )
        pruneCaches(resolvedAt)
        if (actualQuality != quality) {
            Log.i(TAG, "server library quality fallback: ${quality.key} -> ${actualQuality.key}")
        }
        return Result.success(
            Resolved(
                url = url,
                quality = actualQuality,
                source = platform,
                matchedTrack = match,
                requestedQuality = quality
            )
        )
    }

    /** 探活/播放失败后主动失效，避免 403 后 30 分钟内反复拿到同一条坏链。 */
    fun invalidate(serverId: String, song: UnifiedSong, qualityKey: String) {
        cache.remove(cacheKey(serverId, song.id, AudioQuality.fromKey(qualityKey).key))
    }

    fun clear() {
        cache.clear()
        matchCache.clear()
    }

    private suspend fun searchForMatch(
        protocol: LemonMusicProtocol,
        song: UnifiedSong,
        platforms: List<String>
    ): UnifiedSong? = withTimeoutOrNull(SEARCH_BUDGET_MS) {
        var found = searchBatch(protocol, song, platforms.take(2))
        if (found == null && platforms.size > 2) {
            found = searchBatch(protocol, song, platforms.drop(2).take(2))
        }
        found
    }

    private suspend fun searchBatch(
        protocol: LemonMusicProtocol,
        song: UnifiedSong,
        platforms: List<String>
    ): UnifiedSong? = coroutineScope {
        val results = platforms.map { platform ->
            async {
                runCatching {
                    protocol.searchOnline(
                        keyword = "${song.title} ${song.artist}".trim(),
                        source = platform,
                        page = 1,
                        limit = 10,
                        callTimeoutMs = SEARCH_BUDGET_MS - 250L
                    ).getOrNull().orEmpty()
                }.getOrDefault(emptyList())
            }
        }.awaitAll()
        results.forEach { list ->
            list.firstOrNull { isStrictMatch(song, it) }?.let { return@coroutineScope it }
        }
        null
    }

    private fun isStrictMatch(source: UnifiedSong, candidate: UnifiedSong): Boolean {
        if (source.title.isBlank() || candidate.title.isBlank()) return false
        // 不再用 2.5 秒硬阈值预检：SongMatchingResolver 已含 5 秒阈值 + 版本标记判断，
        // 预检反而会把 3~4 秒的 CD 抓轨/现场版本误杀。
        return SongMatchingResolver.isSongMatch(
            source.title, source.artist, source.durationMs,
            candidate.title, candidate.artist, candidate.durationMs,
            source.album, candidate.album
        )
    }

    private fun sourceOf(song: UnifiedSong): String {
        val clean = song.id.removePrefix("lemon_online_")
        return if (clean.contains("_")) clean.substringBefore("_") else "kw"
    }

    private fun buildMusicInfo(song: UnifiedSong): String {
        val obj = runCatching { JSONObject(song.rawMetaJson ?: "{}") }.getOrDefault(JSONObject())
        val clean = song.id.removePrefix("lemon_online_")
        val rawId = if (clean.contains("_")) clean.substringAfter("_") else clean
        if (rawId.isNotBlank()) {
            if (obj.optString("id").isBlank()) obj.put("id", rawId)
            if (obj.optString("songId").isBlank()) obj.put("songId", rawId)
        }
        if (obj.optString("name").isBlank()) obj.put("name", song.title)
        if (obj.optString("singer").isBlank()) obj.put("singer", song.artist)
        if (obj.optString("albumName").isBlank()) obj.put("albumName", song.album)
        listOf("hash", "songmid").forEach { key ->
            if (obj.has(key) && obj.optString(key).isBlank()) obj.remove(key)
        }
        return obj.toString()
    }

    private fun inferQuality(song: UnifiedSong): AudioQuality {
        val fmt = song.format.lowercase()
        val br = song.bitRate
        return when {
            fmt in setOf("flac", "wav", "ape", "alac") && br >= 1200 -> AudioQuality.Q_HIRES
            fmt in setOf("flac", "wav", "ape", "alac") -> AudioQuality.Q_FLAC
            br >= 256 -> AudioQuality.Q_320K
            else -> AudioQuality.Q_128K
        }
    }

    private suspend fun platformOrder(serverId: String): List<String> {
        val platforms = LinkedHashMap<String, Int>()
        val entities = runCatching { ServerSourceRepository.getCachedSources(serverId) }.getOrDefault(emptyList())
        entities.filter { it.isActive }.forEach { source ->
            val health = ServerSourceRepository.parseHealthPlatforms(source.healthPlatformsJson)
            val caps = ServerSourceRepository.parsePlatforms(source.platformsJson)
            caps.forEach { cap ->
                val level = health.firstOrNull { it.id.equals(cap.id, true) }?.level
                    ?: source.healthLevel
                val score = when (level.lowercase()) {
                    "ok" -> 100
                    "watch" -> -10
                    "mixed" -> -20
                    "preview" -> -100
                    else -> 0
                }
                platforms[cap.id.lowercase()] = maxOf(platforms[cap.id.lowercase()] ?: Int.MIN_VALUE, score)
            }
        }
        val ordered = platforms.entries.sortedByDescending { it.value }.map { it.key }.toMutableList()
        FALLBACK_ORDER.split(",").forEach { if (it !in ordered) ordered.add(it) }
        Log.i(TAG, "platform order=$ordered")
        return ordered
    }

    private fun cacheKey(serverId: String, songId: String, qualityKey: String): String =
        "$serverId|$songId|$qualityKey"

    private fun matchKey(serverId: String, song: UnifiedSong): String {
        val title = SongMatchingResolver.normalizeTrackTitle(song.title)
        val artist = SongMatchingResolver.normalizeArtist(song.artist)
        return "$serverId|$title|$artist|${song.durationMs / 1000L}"
    }

    private fun pruneCaches(now: Long) {
        if (cache.size <= CACHE_SOFT_LIMIT) return
        val cacheIterator = cache.entries.iterator()
        while (cacheIterator.hasNext()) {
            if (cacheIterator.next().value.expiresAt <= now) cacheIterator.remove()
        }
        val matchIterator = matchCache.entries.iterator()
        while (matchIterator.hasNext()) {
            if (now - matchIterator.next().value.at > MATCH_CACHE_TTL_MS) matchIterator.remove()
        }
    }

    /**
     * 默认 10 分钟；若 URL 自带可信的过期参数，则把 TTL 收敛到“过期前 1 分钟”，
     * 避免平台签名 15 分钟而本地缓存 30 分钟导致 403。
     */
    internal fun effectiveTtlMs(url: String, now: Long): Long {
        val expiry = extractExpiryMs(url) ?: return DEFAULT_CACHE_TTL_MS
        val remaining = expiry - now - 60_000L
        return remaining.coerceIn(MIN_CACHE_TTL_MS, MAX_CACHE_TTL_MS)
    }

    /** 解析 URL 查询参数中的秒/毫秒级过期时间；仅接受合理范围内的数值，避免误把歌曲 ID 当时间。 */
    internal fun extractExpiryMs(url: String): Long? {
        val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return null
        val now = System.currentTimeMillis()
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val key = pair.substring(0, idx).lowercase()
            if (key !in setOf("expires", "expire", "deadline", "expires_at", "expire_at")) continue
            val value = runCatching {
                URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            }.getOrNull()?.trim() ?: continue
            val raw = value.toLongOrNull() ?: continue
            val millis = when {
                raw in 1_000_000_000L..9_999_999_999L -> raw * 1000L
                raw in 1_000_000_000_000L..9_999_999_999_999L -> raw
                else -> continue
            }
            if (millis > now - 86_400_000L && millis < now + 7L * 86_400_000L) return millis
        }
        return null
    }
}
