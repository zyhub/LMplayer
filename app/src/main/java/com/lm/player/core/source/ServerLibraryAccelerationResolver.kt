package com.lm.player.core.source

import android.content.Context
import android.util.Log
import com.lm.player.core.media.SongMatchingResolver
import com.lm.player.core.model.AudioQuality
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.network.LemonMusicProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 服务器曲库歌曲的本地加速解析。
 *
 * 服务器曲库只有文件元数据，没有平台 source/songId：先用**本地目录服务**（不经服务器）在各平台搜索同曲目，
 * 再交给 QuickJS 本地取链。匹配结果落盘（30 天），服务器离线时播放过的曲库歌曲仍可本地取链。
 * 任何失败都返回 failure，调用方回退 NAS 文件。
 */
object ServerLibraryAccelerationResolver {
    private const val TAG = "ServerLibAccel"
    private const val DEFAULT_CACHE_TTL_MS = 10 * 60 * 1000L
    private const val MIN_CACHE_TTL_MS = 60 * 1000L
    private const val MAX_CACHE_TTL_MS = 30 * 60 * 1000L
    private const val MATCH_TTL_MS = 30L * 24 * 60 * 60 * 1000L
    private const val MATCH_LIMIT = 800
    private const val MATCH_FILE = "accel_library_matches.json"
    private const val FALLBACK_ORDER = "kw,tx,wy,kg,mg"

    data class Resolved(
        val url: String,
        val quality: AudioQuality,
        val source: String,
        val matchedTrack: UnifiedSong,
        val fromCache: Boolean = false,
        val requestedQuality: AudioQuality = quality
    )

    private data class CachedMatch(val song: UnifiedSong, val at: Long)

    private val matchCache = ConcurrentHashMap<String, CachedMatch>()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var loaded = false

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun resolve(
        protocol: LemonMusicProtocol?,
        serverId: String,
        song: UnifiedSong,
        overrideQuality: String? = null,
        refresh: Boolean = false,
        priority: ResolvePriority = ResolvePriority.PLAY
    ): Result<Resolved> {
        val mode = SourceAccelerationController.playbackMode()
        if (mode != SourceAccelerationController.Mode.LOCAL_FIRST && mode != SourceAccelerationController.Mode.LOCAL_ONLY) {
            return Result.failure(IllegalStateException("音源加速未启用"))
        }
        val lenient = mode == SourceAccelerationController.Mode.LOCAL_ONLY
        // 搜索匹配与本地取链使用**独立**预算，避免搜索慢时把取链压到只剩几百毫秒
        val searchBudget = if (lenient) 5_000L else 2_500L
        val budget = if (lenient) PlaybackAccelerator.LOCAL_ONLY_BUDGET_MS else PlaybackAccelerator.LOCAL_FIRST_BUDGET_MS
        val searchDeadline = System.currentTimeMillis() + searchBudget
        val quality = overrideQuality?.let { AudioQuality.fromKey(it) } ?: inferQuality(song)
        ensureLoaded()

        val key = matchKey(serverId, song)
        val now = System.currentTimeMillis()
        val cachedMatch = if (!refresh) matchCache[key]?.takeIf { now - it.at <= MATCH_TTL_MS }?.song else null
        val match = cachedMatch ?: CrossPlatformMatcher.findFirst(song, platformOrder(serverId), searchDeadline)
            ?: return Result.failure(IllegalStateException("服务器曲库歌曲未匹配到平台曲目"))
        if (cachedMatch == null) {
            matchCache[key] = CachedMatch(match, now)
            persistSoon()
        }
        val hit = PlaybackAccelerator.localResolve(
            song = match,
            requested = quality,
            priority = priority,
            budgetMs = budget,
            crossPlatform = false,
            refresh = refresh,
            lenientProbe = lenient
        ) ?: return Result.failure(IllegalStateException("服务器曲库本地解析失败"))
        if (hit.quality != quality) Log.i(TAG, "server library quality fallback: ${quality.key} -> ${hit.quality.key}")
        return Result.success(
            Resolved(
                url = hit.url,
                quality = hit.quality,
                source = hit.platform,
                matchedTrack = match,
                requestedQuality = quality
            )
        )
    }

    /** 探活/播放失败后主动失效。 */
    fun invalidate(serverId: String, song: UnifiedSong, qualityKey: String) {
        val match = matchCache[matchKey(serverId, song)]?.song ?: return
        SourceAccelerationSession.qualitiesFrom(qualityKey).forEach {
            ResolvedUrlCache.invalidate(PlaybackAccelerator.platformOf(match), PlaybackAccelerator.rawIdOf(match), it)
        }
    }

    fun clear() {
        matchCache.clear()
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
                val level = health.firstOrNull { it.id.equals(cap.id, true) }?.level ?: source.healthLevel
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
        return ordered.filter { SourceAccelerationSession.canResolve(it) }.take(4)
    }

    private fun matchKey(serverId: String, song: UnifiedSong): String {
        val title = SongMatchingResolver.normalizeTrackTitle(song.title)
        val artist = SongMatchingResolver.normalizeArtist(song.artist)
        return "$serverId|$title|$artist|${song.durationMs / 1000L}"
    }

    // ---------------- 匹配结果持久化 ----------------

    private fun matchFile(): File? = appContext?.let { File(it.filesDir, MATCH_FILE) }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            val file = matchFile() ?: return
            runCatching {
                if (!file.exists()) return
                val arr = JSONArray(file.readText(Charsets.UTF_8))
                val now = System.currentTimeMillis()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val at = o.optLong("at")
                    if (now - at > MATCH_TTL_MS) continue
                    val s = o.optJSONObject("song") ?: continue
                    matchCache[o.optString("key")] = CachedMatch(
                        UnifiedSong(
                            id = s.optString("id"),
                            title = s.optString("title"),
                            artist = s.optString("artist"),
                            album = s.optString("album"),
                            durationMs = s.optLong("durationMs"),
                            coverUrl = s.optString("coverUrl"),
                            serverId = "lemon_online",
                            format = "mp3",
                            rawMetaJson = s.optString("raw").takeIf { it.isNotBlank() }
                        ),
                        at
                    )
                }
            }.onFailure { Log.w(TAG, "load match cache failed", it) }
        }
    }

    @Volatile
    private var persistScheduled = false

    private fun persistSoon() {
        if (persistScheduled) return
        persistScheduled = true
        ioScope.launch {
            kotlinx.coroutines.delay(3_000L)
            persistScheduled = false
            val file = matchFile() ?: return@launch
            runCatching {
                val arr = JSONArray()
                matchCache.entries.sortedByDescending { it.value.at }.take(MATCH_LIMIT).forEach { (k, v) ->
                    arr.put(
                        JSONObject()
                            .put("key", k)
                            .put("at", v.at)
                            .put(
                                "song", JSONObject()
                                    .put("id", v.song.id)
                                    .put("title", v.song.title)
                                    .put("artist", v.song.artist)
                                    .put("album", v.song.album)
                                    .put("durationMs", v.song.durationMs)
                                    .put("coverUrl", v.song.coverUrl)
                                    .put("raw", v.song.rawMetaJson.orEmpty())
                            )
                    )
                }
                val tmp = File(file.parentFile, "$MATCH_FILE.tmp")
                tmp.writeText(arr.toString(), Charsets.UTF_8)
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }.onFailure { Log.w(TAG, "persist match cache failed", it) }
        }
    }

    /**
     * 默认 10 分钟；若 URL 自带可信的过期参数，则把 TTL 收敛到「过期前 1 分钟」。
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
