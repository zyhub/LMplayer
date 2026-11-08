package com.lm.player.core.source

import com.lm.player.core.media.SongMatchingResolver
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.source.catalog.LocalCatalog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 跨平台同曲匹配（对齐服务端 server/utils/crossPlatformMatch.js）：用本地目录服务在其它平台搜索同名同版本曲目。
 *
 * 匹配同时满足两条才算：
 * 1. 服务端的名称/歌手打分 ≥ 60（[trackNameArtistScore]）；
 * 2. 客户端 [SongMatchingResolver.isSongMatch] 的版本校验（Live/伴奏/时长差异），避免串到别的版本。
 */
object CrossPlatformMatcher {
    private const val MIN_MATCH_SCORE = 60
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    private const val SEARCH_TIMEOUT_MS = 3_500L

    /** 与服务端 PLATFORM_ORDER 一致 */
    val PLATFORM_ORDER = listOf("kw", "kg", "tx", "wy", "mg")

    private data class Cached(val list: List<UnifiedSong>, val at: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    private fun normalizeMatchText(s: String): String = s.lowercase()
        .replace(Regex("[（(].*?[）)]"), "")
        .replace(Regex("[\\[【].*?[\\]】]"), "")
        .replace(Regex("\\s+"), "")
        .trim()

    fun trackNameArtistScore(candidate: UnifiedSong, name: String, artist: String): Int {
        val cn = normalizeMatchText(candidate.title)
        val ca = normalizeMatchText(candidate.artist)
        val n = normalizeMatchText(name)
        val a = normalizeMatchText(artist)
        if (cn.isEmpty() || n.isEmpty()) return 0
        var score = when {
            cn == n -> 100
            cn.contains(n) || n.contains(cn) -> 60
            else -> return 0
        }
        if (a.isNotEmpty() && ca.isNotEmpty()) {
            score += if (ca == a || ca.contains(a) || a.contains(ca)) 40 else 5
        }
        return score
    }

    fun isSameSong(source: UnifiedSong, candidate: UnifiedSong): Boolean {
        if (trackNameArtistScore(candidate, source.title, source.artist) < MIN_MATCH_SCORE) return false
        return SongMatchingResolver.isSongMatch(
            source.title, source.artist, source.durationMs,
            candidate.title, candidate.artist, candidate.durationMs,
            source.album, candidate.album
        )
    }

    private fun cacheKey(song: UnifiedSong, exclude: String): String =
        "${SongMatchingResolver.normalizeTrackTitle(song.title)}|${SongMatchingResolver.normalizeArtist(song.artist)}|${song.durationMs / 1000L}|$exclude"

    /**
     * 返回其它平台上的同曲候选（按平台顺序，每个平台最多 1 首最佳匹配）。
     * @param platforms 为空时用 [PLATFORM_ORDER] 中本地有脚本支持的平台
     */
    suspend fun alternatives(
        song: UnifiedSong,
        exclude: String,
        deadline: Long,
        platforms: List<String> = emptyList()
    ): List<UnifiedSong> {
        if (song.title.isBlank()) return emptyList()
        val key = cacheKey(song, exclude)
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { now - it.at < CACHE_TTL_MS }?.let { return it.list }
        val targets = platforms.ifEmpty { PLATFORM_ORDER }
            .filter { !it.equals(exclude, true) }
            .filter { SourceAccelerationSession.canResolve(it) || platforms.isNotEmpty() }
        if (targets.isEmpty()) return emptyList()
        val keyword = listOf(song.title, song.artist.takeIf { it.isNotBlank() && !it.contains("未知") })
            .filterNotNull().joinToString(" ").trim()
        val timeout = (deadline - now).coerceIn(500L, SEARCH_TIMEOUT_MS)
        val found = coroutineScope {
            targets.map { p ->
                async {
                    withTimeoutOrNull(timeout) {
                        runCatching { LocalCatalog.searchSongs(keyword, p, 1, 10) }.getOrDefault(emptyList())
                    }.orEmpty().firstOrNull { isSameSong(song, it) }
                }
            }.awaitAll()
        }.filterNotNull()
        // 只缓存命中结果：搜索失败/超时不能被当成「没有同曲」缓存 6 小时
        if (found.isNotEmpty()) {
            cache[key] = Cached(found, now)
            if (cache.size > 512) cache.entries.removeIf { now - it.value.at > CACHE_TTL_MS }
        }
        return found
    }

    /** 服务器曲库歌曲（没有平台 ID）在各平台中找一首可取链的同曲。 */
    suspend fun findFirst(song: UnifiedSong, platforms: List<String>, deadline: Long): UnifiedSong? =
        alternatives(song, exclude = "", deadline = deadline, platforms = platforms).firstOrNull()

    fun clear() = cache.clear()
}
