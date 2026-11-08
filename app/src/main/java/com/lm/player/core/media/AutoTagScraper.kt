package com.lm.player.core.media

import android.content.Context
import android.util.Log
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.network.LemonMusicProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 播放时自动补全封面 / 歌词 / 缺失标签。
 *
 * 这里只做「调度」，匹配策略完全交给服务端：
 * `/api/tag/match` 的音源优先级、`fillMissingOnly`、外语歌手过滤等都由服务端设置决定，
 * App 不重复实现一套策略，避免与手动修标签（TagEditorScreen）产生两套行为。
 *
 * 链路：
 *   1. /api/tag/match          按标题/歌手/专辑在线匹配（wy → kw → tx 依次尝试）
 *   2. isSongMatch             客户端再校验一次候选，防服务端返回同名不同曲
 *   3. /api/tag/match-apply    取封面与歌词等完整元数据
 *   4. /api/tag/write          把封面/歌词/缺失字段直接嵌入服务器音频文件
 *
 * 设计约束：
 *  - 只对服务器曲库曲目、且「已知无内嵌封面」的歌曲触发（封面齐全视为元数据完整）；
 *  - 歌名永不写入，只在当前值为空时补歌手 / 专辑 / 年份 / 流派，避免误改用户信息；
 *  - 同一文件在一个进程内只尝试一次，失败静默；
 *  - 属于「锦上添花」，刮错时仍可用播放页的在线修标签手动修正。
 */
object AutoTagScraper {
    private const val TAG = "AutoTagScraper"
    private const val PREFS = "lemon_settings_prefs"
    private const val KEY_ENABLED = "auto_tag_scrape_on_play"

    /** 服务端支持的在线音源，按顺序尝试 */
    private val MATCH_SOURCES = listOf("wy", "kw", "tx")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 已尝试过的服务器文件路径（进程内去重，避免同一首歌反复请求） */
    private val attemptedPaths = HashSet<String>()

    /** 单飞：同一时间只刮一首，防止连续切歌时并发请求服务端 */
    @Volatile
    private var running = false

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    /** 播放回调里调用：不阻塞播放，条件不满足时直接返回 */
    fun maybeScrape(context: Context, song: UnifiedSong) {
        if (!isEnabled(context)) return
        if (!song.isServerLibrarySong) return
        // 封面已存在的曲目视为元数据完整，不再触发（歌词缺失没有可靠的列表信号）
        if (!LemonMusicProtocol.isKnownMissingEmbeddedCoverUrl(song.coverUrl)) return
        val filePath = LemonMusicProtocol.getServerFilePath(song.id, song.streamUrl, song.coverUrl)
            ?.removePrefix("local:")
            ?.trim()
        if (filePath.isNullOrBlank()) return
        synchronized(attemptedPaths) {
            if (!attemptedPaths.add(filePath)) return
        }
        if (running) return
        running = true
        val appContext = context.applicationContext
        scope.launch {
            try {
                scrape(appContext, song, filePath)
            } catch (e: Exception) {
                Log.d(TAG, "scrape failed: ${song.title} - ${e.message}")
            } finally {
                running = false
            }
        }
    }

    private suspend fun scrape(context: Context, song: UnifiedSong, filePath: String) {
        val candidate = findCandidate(context, song) ?: run {
            Log.d(TAG, "no reliable match: ${song.title} - ${song.artist}")
            return
        }
        val meta = LemonMusicProtocol.tagMatchApply(candidate.match, candidate.source, context)
            .getOrNull() ?: return

        val writeMeta = buildWriteMeta(meta, song)
        if (writeMeta.length() == 0) return

        val written = LemonMusicProtocol.tagWrite(filePath, writeMeta, context).getOrNull() ?: false
        if (!written) return

        // 写回后就地刷新，避免要重启 / 重同步才显示新封面、新歌词
        if (writeMeta.has("pic") || writeMeta.has("picUrl")) {
            LemonMusicProtocol.markEmbeddedCoverWritten(filePath)
            val coverUrl = LemonMusicProtocol.activeInstance?.resolveCoverArtUrlForLocalPath(filePath)
            PlaybackQueueManager.updateSongMetadata(songId = song.id, coverUrl = coverUrl)
        }
        if (writeMeta.has("lyric")) {
            LyricsManager.invalidateLyrics(song.id)
        }
        Log.i(TAG, "scraped: ${song.title} - ${song.artist} (${writeMeta.keys().asSequence().toList()})")
    }

    private class MatchCandidate(val match: JSONObject, val source: String)

    /** 依次尝试各音源，返回首个通过客户端校验的候选 */
    private suspend fun findCandidate(context: Context, song: UnifiedSong): MatchCandidate? {
        val cleanArtist = song.artist.trim().takeUnless { it.isBlank() || it == "未知艺术家" }.orEmpty()
        val cleanAlbum = song.album.trim().takeUnless { it.isBlank() || it == "未知专辑" }.orEmpty()
        for (source in MATCH_SOURCES) {
            val results = LemonMusicProtocol.tagMatch(
                artist = cleanArtist,
                title = song.title.trim(),
                album = cleanAlbum,
                source = source,
                context = context
            ).getOrNull() ?: continue
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val title = item.optText("name", "title")
                val artist = item.optText("artist", "singer")
                if (SongMatchingResolver.isSongMatch(
                        title1 = song.title,
                        artist1 = song.artist,
                        durationMs1 = song.durationMs,
                        title2 = title,
                        artist2 = artist,
                        durationMs2 = 0L,
                        album1 = song.album,
                        album2 = item.optText("album")
                    )
                ) {
                    return MatchCandidate(item, source)
                }
            }
        }
        return null
    }

    /**
     * 组装写回字段：
     * 封面与歌词只要匹配到就写；歌手/专辑仅在本地当前为空时补充，歌名一律不写；
     * 年份/流派在 UnifiedSong 上没有字段可比对，故匹配到就直接写入。
     */
    private fun buildWriteMeta(meta: JSONObject, song: UnifiedSong): JSONObject {
        val result = JSONObject()
        meta.optText("pic", "cover", "coverBase64").takeIf { it.isNotBlank() }?.let { result.put("pic", it) }
        meta.optText("picUrl", "pic_url", "coverUrl").takeIf { it.isNotBlank() }?.let { result.put("picUrl", it) }
        meta.optText("lyric", "lrc").takeIf { it.isNotBlank() }?.let { result.put("lyric", it) }

        fun fillIfMissing(field: String, current: String, vararg keys: String) {
            val hasValue = current.trim().isNotEmpty()
            if (hasValue) return
            meta.optText(*keys).takeIf { it.isNotBlank() }?.let { result.put(field, it) }
        }
        fillIfMissing("artist", song.artist, "artist", "singer")
        fillIfMissing("album", song.album, "album")
        fillIfMissing("year", "", "year", "yearStr")
        fillIfMissing("genre", "", "genre")
        return result
    }

    private fun JSONObject.optText(vararg keys: String): String {
        for (key in keys) {
            val value = optString(key, "").trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }
}
