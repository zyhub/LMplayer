package com.lm.player.core.source.catalog

import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.source.PlaybackAccelerator
import com.lm.player.core.source.ResolvePriority
import com.lm.player.core.source.SourceAccelerationSession
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject

/**
 * 柠檬服务端「目录类」接口的描述。由 [CatalogOp.from] 从发往服务器的请求解析得到，
 * 本地执行结果与服务端响应**同形**（{ok:true, data:{...}}），客户端现有解析代码不变。
 */
sealed class CatalogOp {
    enum class Kind(
        val freshMs: Long,
        val staleMs: Long,
        val localFirstBudgetMs: Long,
        val localOnlyBudgetMs: Long
    ) {
        SEARCH(10 * MIN, DAY, 3_500, 9_000),
        RECOMMEND(HOUR, 3 * DAY, 5_000, 12_000),
        TOPLISTS(6 * HOUR, 7 * DAY, 7_000, 15_000),
        TOPLIST_DETAIL(30 * MIN, 3 * DAY, 6_000, 15_000),
        PLAYLIST(30 * MIN, 3 * DAY, 8_000, 20_000),
        ALBUM(DAY, 30 * DAY, 6_000, 15_000),
        NEW_LIST(HOUR, 3 * DAY, 5_000, 12_000),
        LYRIC(7 * DAY, 90 * DAY, 5_000, 10_000);
    }

    abstract val kind: Kind
    abstract val platform: String
    abstract val cacheKey: String

    data class Search(val keyword: String, override val platform: String, val page: Int, val limit: Int, val type: String) : CatalogOp() {
        override val kind = Kind.SEARCH
        override val cacheKey get() = "search|$type|$platform|$page|$limit|$keyword"
    }

    data class AlbumDetail(override val platform: String, val id: String, val page: Int, val limit: Int) : CatalogOp() {
        override val kind = Kind.ALBUM
        override val cacheKey get() = "album|$platform|$id|$page|$limit"
    }

    data class Recommend(override val platform: String, val sort: String, val page: Int, val limit: Int) : CatalogOp() {
        override val kind = Kind.RECOMMEND
        override val cacheKey get() = "recommend|$platform|$sort|$page|$limit"
    }

    data class Playlist(override val platform: String, val input: String, val partial: Boolean) : CatalogOp() {
        override val kind = Kind.PLAYLIST
        override val cacheKey get() = "playlist|$platform|$partial|$input"
    }

    data class Toplists(override val platform: String) : CatalogOp() {
        override val kind = Kind.TOPLISTS
        override val cacheKey get() = "toplists|$platform"
    }

    data class ToplistDetail(override val platform: String, val id: String, val page: Int, val limit: Int) : CatalogOp() {
        override val kind = Kind.TOPLIST_DETAIL
        override val cacheKey get() = "toplist|$platform|$id|$page|$limit"
    }

    data class NewList(override val platform: String, val albums: Boolean, val region: String, val page: Int, val limit: Int) : CatalogOp() {
        override val kind = Kind.NEW_LIST
        override val cacheKey get() = "new|$albums|$platform|$region|$page|$limit"
    }

    data class Lyric(val body: JSONObject) : CatalogOp() {
        override val kind = Kind.LYRIC
        override val platform: String get() = body.optString("source").lowercase()
        override val cacheKey: String
            get() = "lyric|$platform|" + listOf("songId", "songmid", "hash", "copyrightId", "musicId", "name", "singer")
                .joinToString("|") { body.optString(it) }
    }

    companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val DAY = 24 * HOUR

        private fun Request.q(name: String, def: String = ""): String = url.queryParameter(name)?.trim().orEmpty().ifEmpty { def }
        private fun Request.qi(name: String, def: Int): Int = url.queryParameter(name)?.trim()?.toIntOrNull() ?: def

        /** 只识别目录类接口；其它返回 null（交给服务器） */
        fun from(request: Request): CatalogOp? {
            val path = request.url.encodedPath.removePrefix("/api/v1").let { if (it.startsWith("/api")) it else "/api$it" }
                .trimEnd('/')
            val method = request.method.uppercase()
            if (method == "POST" && path == "/api/play/lyric") {
                val text = runCatching {
                    val buf = Buffer()
                    request.body?.writeTo(buf)
                    buf.readUtf8()
                }.getOrNull() ?: return null
                val body = runCatching { JSONObject(text) }.getOrNull() ?: return null
                // 资料库（服务器曲库文件）的歌词以服务器为准，不走本地音源加速
                if (body.optString("filePath").isNotBlank() || body.optString("path").isNotBlank()) return null
                return Lyric(body)
            }
            if (method != "GET") return null
            val source = request.q("source", "kw").lowercase()
            if (source !in MusicSdk.SOURCES) return null
            return when (path) {
                "/api/search" -> request.q("keyword").takeIf { it.isNotEmpty() }?.let { Search(it, source, request.qi("page", 1), request.qi("limit", 30), "music") }
                "/api/search/album", "/api/album/search" -> request.q("keyword").takeIf { it.isNotEmpty() }?.let { Search(it, source, request.qi("page", 1), request.qi("limit", 30), "album") }
                "/api/search/playlist" -> request.q("keyword").takeIf { it.isNotEmpty() }?.let { Search(it, source, request.qi("page", 1), request.qi("limit", 30), "playlist") }
                "/api/search/album/detail" -> request.q("id").takeIf { it.isNotEmpty() }?.let { AlbumDetail(source, it, request.qi("page", 1), request.qi("limit", 0)) }
                "/api/album" -> request.q("id").takeIf { it.isNotEmpty() }?.let { AlbumDetail(source, it, 1, 0) }
                "/api/playlist/recommend" -> Recommend(source, request.q("sort", "new"), maxOf(1, request.qi("page", 1)), request.qi("limit", 30).coerceIn(10, 60))
                "/api/playlist" -> request.q("url").takeIf { it.isNotEmpty() }?.let {
                    Playlist(source, it, request.q("partial") == "1" || request.q("partial") == "true")
                }
                "/api/discover/toplists" -> Toplists(request.q("source", "tx").lowercase())
                "/api/discover/toplist" -> request.q("id").takeIf { it.isNotEmpty() }?.let {
                    ToplistDetail(request.q("source", "tx").lowercase(), it, request.qi("page", 1), request.qi("limit", 100))
                }
                "/api/discover/new-songs" -> NewList(request.q("source", "tx").lowercase(), false, request.q("region"), request.qi("page", 1), request.qi("limit", 27))
                "/api/discover/new-albums" -> NewList(request.q("source", "tx").lowercase(), true, request.q("region"), request.qi("page", 1), request.qi("limit", 20))
                else -> null
            }
        }
    }
}

/**
 * 本地目录服务（需求③）：在 App 内直接请求各平台公开接口，不经柠檬服务器。
 */
object LocalCatalog {

    private fun ok(data: JSONObject): JSONObject = JSONObject().put("ok", true).put("data", data).put("local", true)

    /** 执行目录操作，返回与服务端同形的响应体。失败抛异常。 */
    suspend fun execute(op: CatalogOp): JSONObject = when (op) {
        is CatalogOp.Search -> ok(
            when (op.type) {
                "album" -> MusicSdk.searchAlbums(op.keyword, op.platform, op.page, op.limit)
                "playlist" -> MusicSdk.searchPlaylists(op.keyword, op.platform, op.page, op.limit)
                else -> MusicSdk.searchMusic(op.keyword, op.platform, op.page, op.limit)
            }
        )
        is CatalogOp.AlbumDetail -> {
            val data = MusicSdk.fetchAlbum(op.platform, op.id)
            val limit = op.limit.coerceIn(0, 100)
            val list = data.optJSONArray("list")
            if (limit > 0 && list != null && list.length() > limit) {
                val start = ((op.page.coerceAtLeast(1) - 1) * limit)
                val sliced = JSONArray()
                for (i in start until minOf(list.length(), start + limit)) sliced.put(list.get(i))
                data.put("list", sliced).put("trackPage", op.page).put("trackPageSize", limit).put("trackTotal", list.length())
            }
            ok(data)
        }
        is CatalogOp.Recommend -> ok(MusicSdk.fetchRecommendPlaylists(op.platform, op.sort, op.page, op.limit))
        is CatalogOp.Playlist -> ok(MusicSdk.fetchPlaylist(op.platform, op.input, op.partial))
        is CatalogOp.Toplists -> ok(DiscoverSdk.fetchToplists(op.platform))
        is CatalogOp.ToplistDetail -> ok(DiscoverSdk.fetchToplistDetail(op.platform, op.id, op.page, op.limit))
        is CatalogOp.NewList -> ok(
            if (op.albums) DiscoverSdk.fetchNewAlbums(op.platform, op.region, op.page, op.limit)
            else DiscoverSdk.fetchNewSongs(op.platform, op.region, op.page, op.limit)
        )
        is CatalogOp.Lyric -> lyric(op.body)
    }

    /** 结果是否「空」（本地优先时空结果会再问服务器）。 */
    fun isEmpty(op: CatalogOp, response: JSONObject): Boolean {
        if (op is CatalogOp.Lyric) return response.optString("lyric").isBlank() && response.optString("ylyric").isBlank()
        val data = response.optJSONObject("data") ?: return true
        return (data.optJSONArray("list")?.length() ?: 0) == 0
    }

    /**
     * 歌词：对齐服务端 POST /api/play/lyric（trackMeta.fetchTrackLyric）：
     * 先平台歌词接口，失败再调本地音源脚本的 lyric 动作。
     */
    suspend fun lyric(body: JSONObject): JSONObject {
        val clientLyric = body.optString("lyric")
        val clientYlyric = body.optString("ylyric")
        val source = body.optString("source").lowercase()
        val songId = listOf("songId", "songmid", "hash", "copyrightId", "musicId").map { body.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
        val merged = PlaybackAccelerator.alignMusicInfo(body)
        val src = source.takeIf { it in MusicSdk.SOURCES }.orEmpty()
        fun result(l: String, t: String = "", r: String = "", y: String = "") =
            JSONObject().put("ok", true).put("lyric", l).put("tlyric", t).put("rlyric", r).put("ylyric", y).put("local", true)
        if (songId.isEmpty() && merged.optString("name").isBlank()) return result(clientLyric, y = clientYlyric)
        if (src.isNotEmpty() && songId.isNotEmpty()) {
            val lrc = runCatching { MusicSdk.getLyric(songId, src, merged) }.getOrNull()
            if (lrc != null && (lrc.optString("lyric").isNotBlank() || lrc.optString("ylyric").isNotBlank())) {
                return result(lrc.optString("lyric"), lrc.optString("tlyric"), lrc.optString("rlyric"), lrc.optString("ylyric"))
            }
        }
        if (src.isNotEmpty() && SourceAccelerationSession.isReady()) {
            val raw = SourceAccelerationSession.invokeAction(src, "lyric", merged, 6_000L, ResolvePriority.PREFETCH).getOrNull()
            val obj = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (obj != null) {
                val l = obj.optString("lyric").ifBlank { obj.optString("lrc") }
                val y = obj.optString("lxlyric")
                if (l.isNotBlank() || y.isNotBlank()) return result(l, obj.optString("tlyric"), obj.optString("rlyric"), y)
            }
        }
        return result(clientLyric, y = clientYlyric)
    }

    // ---------------- 给客户端内部使用的便捷接口 ----------------

    /** 本地搜索并解析成 UnifiedSong（跨平台匹配、服务器曲库匹配用）。 */
    suspend fun searchSongs(keyword: String, platform: String, page: Int = 1, limit: Int = 10): List<UnifiedSong> {
        val data = MusicSdk.searchMusic(keyword, platform, page, limit)
        return OnlineCatalogParser.searchSongs(data.optJSONArray("list"), platform)
    }

    /** 在线曲目的本地歌词（服务器不可用时 LyricsManager 兜底）。 */
    suspend fun lyricForSong(song: UnifiedSong): String? {
        if (!PlaybackAccelerator.isOnlineSong(song)) return null
        val body = PlaybackAccelerator.buildMusicInfo(song)
        val res = runCatching { lyric(body) }.getOrNull() ?: return null
        return res.optString("lyric").ifBlank { res.optString("ylyric") }.takeIf { it.isNotBlank() }
    }
}
