package com.lm.player.core.media

import android.content.Context
import android.util.Log
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.network.EmbeddedCoverProbe
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
 *   0. HEAD /api/tag/cover      探测文件是否真的有内嵌封面（曲库索引 hasPicture 可能陈旧）
 *   1. /api/tag/match          按标题/歌手/专辑在线匹配（wy → kw → tx 依次尝试）；
 *                              原标题无结果时再用清洗后的标题兜底（见 [titleVariants]）
 *   2. isSongMatch             客户端再校验一次候选，防服务端返回同名不同曲
 *   3. /api/tag/match-apply    取封面与歌词等完整元数据
 *   4. /api/tag/write          把封面/歌词/缺失字段直接嵌入服务器音频文件
 *
 * 设计约束：
 *  - 只对服务器曲库曲目、且服务器确认「无内嵌封面」的歌曲触发（封面齐全视为元数据完整）；
 *  - 歌名永不写入（清洗后的标题只用于搜索与校验），只在当前值为空时补歌手 / 专辑；
 *  - 同一文件在一个进程内只尝试一次；仅网络 / 服务端临时故障时允许下次播放重试；
 *  - 属于「锦上添花」，刮错时仍可用播放页的在线修标签手动修正。
 */
object AutoTagScraper {
    private const val TAG = "AutoTagScraper"
    private const val PREFS = "lemon_settings_prefs"
    private const val KEY_ENABLED = "auto_tag_scrape_on_play"

    /** App 在缺少歌手 / 专辑时显示的占位文案 */
    private val PLACEHOLDER_TEXTS = setOf("未知艺术家", "未知歌手", "未知专辑", "单曲精选", "精选集")

    /** 服务端支持的在线音源，按顺序尝试 */
    private val MATCH_SOURCES = listOf("wy", "kw", "tx")

    // ---- 标题清洗（参照服务端 filenameParse.js 的规则；服务端标签匹配分支不做清洗） ----
    /** 音轨号前缀：「62 」「03.」「(12)」「【5】」「第3首」；只认 1–3 位数字，「1984 年」不会被误删 */
    private val TRACK_PREFIX = Regex(
        """^(?:\d{1,3}(?:\s*[.\-_、．]\s*|\s+)|\(\s*\d{1,3}\s*\)\s*[-_.]?\s*|【\s*\d{1,3}\s*】\s*|第\s*\d{1,3}\s*[首轨]\s*[-_.]?\s*)"""
    )
    private val NOISE_PATTERNS = listOf(
        Regex("""\[(?:\d{2,3}\s?k(?:bps)?|flac(?:\s*24bit)?|mp3|ape|wav|dsd|hi-?res|atmos|oq|sq|hq)\]""", RegexOption.IGNORE_CASE),
        Regex("""【[^】]*(?:音乐|音质|下载|无损|试听|高品质|环绕)[^】]*】"""),
        Regex("""[\[(（【]\s*(?:CD|DVD|LP|EP|单曲|专辑)\s*[\])）】]""", RegexOption.IGNORE_CASE)
    )
    private val EMPTY_BRACKETS = Regex("""[\[(（【]\s*[\])）】]""")
    /** 末尾括号：「遥远的她(张学友)」 */
    private val TRAILING_PAREN = Regex("""^(.+?)\s*[(（]\s*([^)）]+?)\s*[)）]\s*$""")
    /** 括号里是版本说明而不是歌手时保留整段标题：「慢慢 (Live)」「xx（伴奏版）」 */
    private val VERSION_HINT = Regex(
        """live|remix|mix|cover|inst\.?|instrumental|acoustic|demo|edit|ver\.?|version|伴奏|纯音乐|钢琴|现场|完整|正式|片头|片尾|插曲|主题曲|版|dj""",
        RegexOption.IGNORE_CASE
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 已尝试过的服务器文件路径（进程内去重，避免同一首歌反复请求） */
    private val attemptedPaths = HashSet<String>()

    /** 单飞：同一时间只刮一首，防止连续切歌时并发请求服务端 */
    @Volatile
    private var running = false

    /** 一次补全的结局：DONE = 已完成或确定无需/无法补全；RETRY = 临时故障，下次播放可再试 */
    private enum class Outcome { DONE, RETRY }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    /** 播放回调里调用：不阻塞播放，条件不满足时直接返回 */
    fun maybeScrape(context: Context, song: UnifiedSong) {
        if (!isEnabled(context)) return
        if (!song.isServerLibrarySong) return
        val filePath = LemonMusicProtocol.getServerFilePath(song.id, song.streamUrl, song.coverUrl)
            ?.removePrefix("local:")
            ?.trim()
        if (filePath.isNullOrBlank()) return
        // 先确认能开始，再记「已尝试」：另一首正在补全时切到这首，不会被误记成已尝试
        synchronized(attemptedPaths) {
            if (running || filePath in attemptedPaths) return
            attemptedPaths.add(filePath)
            running = true
        }
        val appContext = context.applicationContext
        scope.launch {
            var outcome = Outcome.RETRY
            try {
                outcome = scrape(appContext, song, filePath)
            } catch (e: Exception) {
                Log.i(TAG, "scrape failed: ${song.title} - ${e.message}")
            } finally {
                synchronized(attemptedPaths) {
                    // 临时故障解除「已尝试」，下次播放这首可重试；确定无匹配的保持标记，避免每次播放都在线搜索
                    if (outcome == Outcome.RETRY) attemptedPaths.remove(filePath)
                    running = false
                }
            }
        }
    }

    private suspend fun scrape(context: Context, song: UnifiedSong, filePath: String): Outcome {
        // 封面门禁：以服务器实际能否取到封面为准，不只看曲库索引
        when (val probe = LemonMusicProtocol.probeEmbeddedCover(filePath, context)) {
            EmbeddedCoverProbe.MISSING -> Log.i(TAG, "cover probe=missing, start: ${song.title} - ${song.artist}")
            EmbeddedCoverProbe.PRESENT -> {
                Log.d(TAG, "skip(cover-exists): ${song.title} - ${song.artist}")
                return Outcome.DONE
            }
            EmbeddedCoverProbe.DENIED -> {
                Log.i(TAG, "skip(cover-forbidden): ${song.title}")
                return Outcome.DONE
            }
            else -> {
                Log.i(TAG, "skip(cover-probe=$probe), retry later: ${song.title}")
                return Outcome.RETRY
            }
        }

        val search = findCandidate(context, song)
        val candidate = search.candidate ?: run {
            Log.i(TAG, "no reliable match: ${song.title} - ${song.artist} (allFailed=${search.allRequestsFailed})")
            return if (search.allRequestsFailed) Outcome.RETRY else Outcome.DONE
        }
        val meta = LemonMusicProtocol.tagMatchApply(candidate.match, candidate.source, context)
            .getOrNull() ?: return Outcome.RETRY

        val writeMeta = buildWriteMeta(meta, song)
        if (writeMeta.length() == 0) return Outcome.DONE

        val written = LemonMusicProtocol.tagWrite(filePath, writeMeta, context).getOrNull() ?: false
        if (!written) return Outcome.RETRY

        // 写回后就地刷新，避免要重启 / 重同步才显示新封面、新歌词、补上的歌手专辑
        val coverWritten = writeMeta.has("pic") || writeMeta.has("picUrl")
        if (coverWritten) LemonMusicProtocol.markEmbeddedCoverWritten(filePath)
        // 直接用文件封面地址（resolveCoverArtUrlForLocalPath 可能返回之前解析到的在线封面）
        val coverUrl = if (coverWritten) LemonMusicProtocol.activeInstance?.getCoverArtUrlForPath(filePath) else null
        PlaybackQueueManager.updateSongMetadata(
            songId = song.id,
            artist = writeMeta.optString("artist").ifBlank { null },
            album = writeMeta.optString("album").ifBlank { null },
            coverUrl = coverUrl
        )
        if (writeMeta.has("lyric")) {
            LyricsManager.invalidateLyrics(song.id)
        }
        Log.i(TAG, "scraped[${candidate.variant}]: ${song.title} - ${song.artist} (${writeMeta.keys().asSequence().toList()})")
        return Outcome.DONE
    }

    /** 服务端标签匹配给本机文件用的结果（封面、歌词、缺失时补的歌手 / 专辑） */
    class LocalTagMeta(val coverBase64: String?, val coverUrl: String?, val lyric: String?, val artist: String?, val album: String?)

    /** 多选「补全标签」的一首结果：meta 为 null 且 serverDown = false 表示在线没有可靠匹配 */
    class LocalTagResult(val meta: LocalTagMeta?, val serverDown: Boolean)

    /**
     * 下载管理多选「补全标签」：用服务端的标签匹配（/api/tag/match → /api/tag/match-apply，音源优先级由服务端设置决定）
     * 为本机已下载的文件取封面 / 歌词 / 缺失的歌手专辑。只读服务端，不改服务器上的文件。
     */
    suspend fun fetchTagMetaForLocal(context: Context, song: UnifiedSong): LocalTagResult {
        val search = findCandidate(context, song)
        val candidate = search.candidate ?: return LocalTagResult(null, search.allRequestsFailed)
        val meta = LemonMusicProtocol.tagMatchApply(candidate.match, candidate.source, context).getOrNull()
            ?: return LocalTagResult(null, true)
        val write = buildWriteMeta(meta, song)
        return LocalTagResult(
            LocalTagMeta(
                coverBase64 = write.optString("pic").ifBlank { null },
                coverUrl = write.optString("picUrl").ifBlank { null },
                lyric = write.optString("lyric").ifBlank { null },
                artist = write.optString("artist").ifBlank { null },
                album = write.optString("album").ifBlank { null }
            ),
            false
        )
    }

    private class MatchCandidate(val match: JSONObject, val source: String, val variant: String)

    private class SearchResult(val candidate: MatchCandidate?, val allRequestsFailed: Boolean)

    /** 搜索与校验用的标题 / 歌手组合；只用于匹配，不写回文件 */
    private data class TitleVariant(val label: String, val title: String, val artist: String)

    /**
     * 原始标题优先（v0），无结果时再用清洗版（v1）兜底：
     * 去音轨号 / 音质噪声，末尾括号里若是歌手则拆出来（「62 遥远的她(张学友)」→ 遥远的她 / 张学友）。
     * v1 必须有歌手可校验才尝试，避免「24 小时」洗成「小时」后误配同名歌。
     */
    private fun titleVariants(song: UnifiedSong): List<TitleVariant> {
        val rawTitle = collapseSpace(song.title)
        val artist = cleanField(song.artist)
        val variants = mutableListOf(TitleVariant("v0", rawTitle, artist))

        var title = rawTitle.replaceFirst(TRACK_PREFIX, "")
        NOISE_PATTERNS.forEach { title = it.replace(title, " ") }
        title = collapseSpace(EMPTY_BRACKETS.replace(title, " "))
        var variantArtist = artist
        TRAILING_PAREN.matchEntire(title)?.let { m ->
            val outer = collapseSpace(m.groupValues[1])
            val inner = collapseSpace(m.groupValues[2])
            if (outer.isNotEmpty() && inner.isNotEmpty() && !VERSION_HINT.containsMatchIn(inner)) {
                when {
                    artist.isEmpty() && inner.length <= 16 -> {
                        title = outer
                        variantArtist = inner
                    }
                    artistLooksSame(inner, artist) -> title = outer
                }
            }
        }
        if (title.isNotEmpty() && variantArtist.isNotEmpty() &&
            (title != rawTitle || variantArtist != artist)
        ) {
            variants += TitleVariant("v1", title, variantArtist)
        }
        return variants
    }

    /** 依次尝试各标题版本与各音源，返回首个通过客户端校验的候选 */
    private suspend fun findCandidate(context: Context, song: UnifiedSong): SearchResult {
        val cleanAlbum = cleanField(song.album)
        var requests = 0
        var failures = 0
        for (variant in titleVariants(song)) {
            for (source in MATCH_SOURCES) {
                requests++
                val response = LemonMusicProtocol.tagMatch(
                    artist = variant.artist,
                    title = variant.title,
                    album = cleanAlbum,
                    source = source,
                    context = context
                )
                val results = response.getOrNull()
                if (results == null) {
                    failures++
                    continue
                }
                var firstTitle = ""
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val title = item.optText("name", "title")
                    val artist = item.optText("artist", "singer")
                    if (i == 0) firstTitle = "$title - $artist"
                    if (SongMatchingResolver.isSongMatch(
                            title1 = variant.title,
                            artist1 = variant.artist,
                            durationMs1 = song.durationMs,
                            title2 = title,
                            artist2 = artist,
                            durationMs2 = 0L,
                            album1 = song.album,
                            album2 = item.optText("album")
                        )
                    ) {
                        Log.i(TAG, "[${variant.label}] match($source) accepted: $title - $artist")
                        return SearchResult(MatchCandidate(item, source, variant.label), false)
                    }
                }
                Log.d(TAG, "[${variant.label}] match($source) count=${results.length()} title=${variant.title} none accepted, first=$firstTitle")
            }
        }
        return SearchResult(null, requests > 0 && failures == requests)
    }

    private fun artistLooksSame(a: String, b: String): Boolean {
        val x = a.lowercase().replace(Regex("""\s+"""), "")
        val y = b.lowercase().replace(Regex("""\s+"""), "")
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.contains(y) || y.contains(x)
    }

    private fun cleanField(value: String): String =
        collapseSpace(value).takeUnless { it.isBlank() || it in PLACEHOLDER_TEXTS }.orEmpty()

    private fun collapseSpace(value: String): String = value.replace(Regex("""\s+"""), " ").trim()

    /**
     * 组装写回字段：
     * 封面与歌词只要匹配到就写；歌手/专辑仅在本地当前为空时补充，歌名一律不写。
     * 年份 / 流派不写 —— UnifiedSong 上没有这两个字段可比对，
     * 无条件写入会覆盖文件里原本正确的值；需要时用播放页的在线修标签手动选择写入。
     */
    private fun buildWriteMeta(meta: JSONObject, song: UnifiedSong): JSONObject {
        val result = JSONObject()
        meta.optText("pic", "cover", "coverBase64").takeIf { it.isNotBlank() }?.let { result.put("pic", it) }
        meta.optText("picUrl", "pic_url", "coverUrl").takeIf { it.isNotBlank() }?.let { result.put("picUrl", it) }
        meta.optText("lyric", "lrc").takeIf { it.isNotBlank() }?.let { result.put("lyric", it) }

        fun fillIfMissing(field: String, current: String, vararg keys: String) {
            // 同步写库时空值会被填成占位文案，占位也视为缺失
            val hasValue = current.trim().let { it.isNotEmpty() && it !in PLACEHOLDER_TEXTS }
            if (hasValue) return
            meta.optText(*keys).takeIf { it.isNotBlank() }?.let { result.put(field, it) }
        }
        fillIfMissing("artist", song.artist, "artist", "singer")
        fillIfMissing("album", song.album, "album")
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
