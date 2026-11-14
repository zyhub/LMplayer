package com.lm.player.core.source

import android.content.Context
import android.util.Log
import com.lm.player.core.model.AudioQuality
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.network.LemonMusicProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URLDecoder

/**
 * 服务器曲库歌曲的本地加速解析。
 *
 * 服务器曲库只有文件元数据，没有平台 source/songId：要先知道它在哪个平台对应哪一首，再交给 QuickJS 本地取链。
 * 对应关系存在**服务器歌曲加速表**（[ServerTrackAccelStore]，2026-11-13 起取代原来最多 800 首的匹配文件）：
 * 表里已匹配就跳过现搜；没有时用本地目录服务（不经服务器）在各平台搜同曲目，搜到写进表里。
 * 后台预匹配（[ServerTrackPrematcher]）会提前把整个曲库填进这张表。任何失败都返回 failure，调用方回退 NAS 文件。
 */
object ServerLibraryAccelerationResolver {
    private const val TAG = "ServerLibAccel"
    private const val DEFAULT_CACHE_TTL_MS = 10 * 60 * 1000L
    private const val MIN_CACHE_TTL_MS = 60 * 1000L
    private const val MAX_CACHE_TTL_MS = 30 * 60 * 1000L
    private const val FALLBACK_ORDER = "kw,tx,wy,kg,mg"

    data class Resolved(
        val url: String,
        val quality: AudioQuality,
        val source: String,
        val matchedTrack: UnifiedSong,
        val fromCache: Boolean = false,
        val requestedQuality: AudioQuality = quality,
        /** 探活拿到的文件大小（0=未知），播放缓存固定键用 */
        val contentBytes: Long = 0L,
        /** 链接实际对应的平台歌曲 ID */
        val sourceSongId: String = ""
    )

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        ServerTrackAccelStore.init(context)
    }

    suspend fun resolve(
        protocol: LemonMusicProtocol?,
        serverId: String,
        song: UnifiedSong,
        overrideQuality: String? = null,
        refresh: Boolean = false,
        priority: ResolvePriority = ResolvePriority.PLAY,
        /** 最低可接受音质：下载传目标音质 —— 本地拿不到同音质就返回失败，交给服务器原文件（2026-11-13 从手机端移植） */
        floorQuality: AudioQuality? = null,
        /** 本平台所有音源都拿不到时，是否到其它平台找同一首歌（下载的「换源」；服务器离线时播放也开） */
        crossPlatform: Boolean = false,
        budgetMsOverride: Long? = null
    ): Result<Resolved> {
        // TV 按需启动：服务器歌曲取链以前不会触发引擎加载，模式一直是「只走服务器」，离线时只能干等超时。
        // 现在先确保引擎：服务器在线时不等（本次走服务器），服务器离线时等引擎加载好（最多 8 秒）
        SourceAccelerationController.ensureEnginesForResolve()
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

        // 加速表里已匹配（且服务器文件没变）→ 跳过现搜；refresh（上次取链失败）时重新搜一次
        val stored = if (!refresh) ServerTrackAccelStore.matchFor(song) else null
        val match = stored ?: CrossPlatformMatcher.findFirst(song, platformOrder(serverId), searchDeadline)
            ?: return Result.failure(IllegalStateException("服务器曲库歌曲未匹配到平台曲目"))
        if (stored == null) {
            // 写表不占取链时间
            ioScope.launch { ServerTrackAccelStore.recordMatch(serverId, song, match) }
        } else {
            Log.d(TAG, "match from table: ${song.title} -> ${PlaybackAccelerator.platformOf(match)}")
        }
        val hit = PlaybackAccelerator.localResolve(
            song = match,
            requested = quality,
            priority = priority,
            budgetMs = budgetMsOverride ?: budget,
            crossPlatform = crossPlatform,
            refresh = refresh,
            lenientProbe = lenient,
            floorQuality = floorQuality ?: AudioQuality.Q_128K
        ) ?: return Result.failure(IllegalStateException("服务器曲库本地解析失败"))
        if (hit.quality != quality) Log.i(TAG, "server library quality fallback: ${quality.key} -> ${hit.quality.key}")
        return Result.success(
            Resolved(
                url = hit.url,
                quality = hit.quality,
                source = hit.platform,
                matchedTrack = match,
                requestedQuality = quality,
                contentBytes = hit.contentBytes,
                sourceSongId = hit.sourceSongId.ifBlank {
                    if (hit.platform.equals(PlaybackAccelerator.platformOf(match), true)) PlaybackAccelerator.rawIdOf(match) else ""
                }
            )
        )
    }

    /** 探活/播放失败后主动失效。 */
    fun invalidate(serverId: String, song: UnifiedSong, qualityKey: String) {
        ioScope.launch {
            val match = ServerTrackAccelStore.matchFor(song) ?: return@launch
            SourceAccelerationSession.qualitiesFrom(qualityKey).forEach {
                ResolvedUrlCache.invalidate(PlaybackAccelerator.platformOf(match), PlaybackAccelerator.rawIdOf(match), it)
            }
        }
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

    /** 搜索平台顺序：按服务器音源健康度排序，只取本地有脚本能取链的平台（预匹配也用） */
    internal suspend fun platformOrder(serverId: String, requireResolvable: Boolean = true): List<String> {
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
        // 预匹配只用本地目录服务搜索（Kotlin，不需要 JS 引擎）：引擎没加载时按服务器音源声明的平台排
        if (!requireResolvable && !SourceAccelerationSession.isReady()) return ordered.take(4)
        return ordered.filter { SourceAccelerationSession.canResolve(it) }.take(4)
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
