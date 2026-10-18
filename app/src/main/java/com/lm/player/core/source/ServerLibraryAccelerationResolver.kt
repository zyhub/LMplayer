package com.lm.player.core.source

import android.util.Log
import com.lm.player.core.media.SongMatchingResolver
import com.lm.player.core.model.AudioQuality
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.network.LemonMusicProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 服务器曲库歌曲的本地加速解析。
 *
 * 服务器曲库只有文件元数据，没有平台 source/songId；这里先用 title+artist 调服务器
 * /api/search 找到平台曲目，再交给 QuickJS 本地取链。任何失败都返回 failure，
 * 调用方直接回退 NAS 文件。
 */
object ServerLibraryAccelerationResolver {
    private const val TAG = "ServerLibAccel"
    private const val BUDGET_MS = 1_500L
    private const val CACHE_TTL_MS = 30 * 60 * 1000L
    private const val FALLBACK_ORDER = "kw,tx,wy,kg,mg"

    data class Resolved(
        val url: String,
        val quality: AudioQuality,
        val source: String,
        val matchedTrack: UnifiedSong
    )

    private data class Cached(val url: String, val quality: AudioQuality, val source: String, val at: Long)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val localScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 超时后本地求值仍会继续；限制并发，避免慢脚本无限堆积。
    private val localPermits = Semaphore(2)

    suspend fun resolve(
        protocol: LemonMusicProtocol,
        serverId: String,
        song: UnifiedSong,
        overrideQuality: String? = null
    ): Result<Resolved> {
        if (!SourceAccelerationController.shouldAccelerate()) {
            return Result.failure(IllegalStateException("音源加速未启用"))
        }
        val quality = overrideQuality?.let { AudioQuality.fromKey(it) } ?: inferQuality(song)
        val key = "${serverId}|${song.id}|${quality.key}"
        cache[key]?.takeIf { System.currentTimeMillis() - it.at <= CACHE_TTL_MS }?.let {
            return Result.success(Resolved(it.url, it.quality, it.source, song))
        }
        val platforms = platformOrder(serverId)
        if (platforms.isEmpty()) return Result.failure(IllegalStateException("没有可用平台"))
        val startedAt = System.currentTimeMillis()
        val match = withTimeoutOrNull(BUDGET_MS) {
            var found = searchBatch(protocol, song, platforms.take(2))
            if (found == null && platforms.size > 2) {
                found = searchBatch(protocol, song, platforms.drop(2).take(2))
            }
            found
        } ?: return Result.failure(IllegalStateException("服务器曲库搜索超时或未命中"))

        val platform = sourceOf(match)
        val info = buildMusicInfo(match)
        val remaining = (BUDGET_MS - (System.currentTimeMillis() - startedAt)).coerceAtLeast(1L)
        // 关键：本地 QuickJS 求值放在独立 scope 中，超时只停止等待，不取消 native evaluate。
        // 直接 withTimeout 取消 evaluate 会破坏 QuickJS 状态，下一次求值可能 SIGSEGV。
        val localDeferred = localScope.async {
            localPermits.withPermit {
                SourceAccelerationSession.resolveMusicUrl(platform, quality.key, info)
            }
        }
        val local = withTimeoutOrNull(remaining) { localDeferred.await() }
            ?: return Result.failure(IllegalStateException("服务器曲库本地解析超时"))
        val url = local.getOrNull()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return Result.failure(IllegalStateException("服务器曲库本地解析失败"))
        cache[key] = Cached(url, quality, platform, System.currentTimeMillis())
        return Result.success(Resolved(url, quality, platform, match))
    }

    fun clear() = cache.clear()

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
                        limit = 10
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
        if (source.durationMs > 0 && candidate.durationMs > 0 &&
            kotlin.math.abs(source.durationMs - candidate.durationMs) > 2_500L
        ) return false
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
        Log.i(TAG, "platform order=${ordered}")
        return ordered
    }
}
