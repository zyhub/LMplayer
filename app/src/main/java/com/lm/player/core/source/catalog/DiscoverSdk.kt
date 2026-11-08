package com.lm.player.core.source.catalog

import com.lm.player.core.source.catalog.MusicSdk.attachSongTypes
import com.lm.player.core.source.catalog.MusicSdk.cleanHtml
import com.lm.player.core.source.catalog.MusicSdk.formatArtists
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * server/discoverSdk.js 的 Kotlin 移植（发现页：新歌 / 新碟 / 排行榜；MIT License, Copyright (c) 2026 jia070310）。
 */
internal object DiscoverSdk {

    private val TX_HEADERS = mapOf("Referer" to "https://y.qq.com/", "User-Agent" to UA_PC)
    private val WY_HEADERS = mapOf("Referer" to "https://music.163.com", "User-Agent" to UA_PC)
    private val KW_HEADERS = mapOf("Referer" to "https://www.kuwo.cn/", "User-Agent" to UA_PC)
    private val MG_HEADERS = mapOf("Referer" to "https://m.music.migu.cn/", "User-Agent" to "Mozilla/5.0", "channel" to "0146951")

    private fun formatTime(seconds: Any?): String {
        val n = jnum(seconds).let { if (it.isNaN()) 0.0 else it }
        if (n <= 0) return ""
        val m = Math.floor(n / 60).toLong()
        val s = Math.floor(n % 60).toLong()
        return "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    }

    private fun pageSlice(list: List<JSONObject>, page: Int, limit: Int): JSONObject {
        val total = list.size
        val start = ((page - 1) * limit).coerceAtLeast(0)
        val slice = if (start >= total) emptyList() else list.subList(start, minOf(total, start + limit))
        return obj("list" to JSONArray(slice), "total" to total, "page" to page, "allPage" to maxOf(1, Math.ceil(total.toDouble() / limit).toInt()))
    }

    private fun unsupported(page: Int): JSONObject =
        obj("list" to JSONArray(), "total" to 0, "page" to page, "allPage" to 0, "regions" to JSONArray(), "unsupported" to true)

    private suspend fun txMusicu(payload: JSONObject): JSONObject? =
        parseJsonObj(CatalogHttp.get("https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data=${encodeUri(payload.toString())}", TX_HEADERS))

    private suspend fun txMusicuPost(payload: JSONObject): JSONObject? {
        val body = JSONObject(payload.toString())
        val comm = obj("ct" to 24, "cv" to 0, "format" to "json", "platform" to "yqq.json", "uin" to "0", "g_tk" to 5381, "needNewCode" to 1)
        payload.optJSONObject("comm")?.let { c -> c.keys().forEach { comm.put(it, c.get(it)) } }
        body.put("comm", comm)
        return parseJsonObj(CatalogHttp.post("https://u.y.qq.com/cgi-bin/musicu.fcg", body.toString(), "application/json", TX_HEADERS + ("Origin" to "https://y.qq.com")))
    }

    private fun dedupeAlbums(items: List<JSONObject>): List<JSONObject> {
        val map = LinkedHashMap<String, JSONObject>()
        for (item in items) {
            val id = item.s("id").trim()
            val name = item.s("name").trim()
            if (id.isEmpty() || id == "0" || id == "undefined" || name.isEmpty() || map.containsKey(id)) continue
            map[id] = item
        }
        return map.values.toList()
    }

    private fun mapTxSong(item: JSONObject): JSONObject {
        val albumMid = jos(item.o("album")?.g("mid"), item.g("albummid"))
        val albumName = cleanHtml(jos(item.o("album")?.g("name"), item.g("albumname")))
        val songmid = jos(item.g("mid"), item.g("songmid"))
        val songId = if (!item.g("id").isNullish() && jstr(item.g("id")).isNotEmpty()) jstr(item.g("id")) else songmid
        val img = if (albumMid.isNotEmpty() && albumMid != "0") "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumMid.jpg" else ""
        val singers = item.arr("singer")
        return attachSongTypes(
            "tx", item, obj(
                "id" to songmid,
                "name" to cleanHtml(jos(item.g("title"), item.g("songname"), item.g("name"))),
                "singer" to formatArtists(if (singers != null) singers.objs().joinToString("/") { it.s("name") } else item.s("singername")),
                "album" to albumName,
                "albumName" to albumName,
                "interval" to formatTime(jor(item.g("interval"), 0)),
                "source" to "tx",
                "songId" to songId,
                "songmid" to songmid,
                "strMediaMid" to jos(item.o("file")?.g("media_mid"), item.g("strMediaMid")),
                "musicId" to songId,
                "albumId" to albumMid,
                "albumMid" to albumMid,
                "albummid" to albumMid,
                "img" to img,
                "picUrl" to img
            )
        )
    }

    private fun mapWySong(item: JSONObject): JSONObject {
        val al = item.o("al") ?: item.o("album")
        val pic = jos(item.o("al")?.g("picUrl"), item.o("album")?.g("picUrl"))
        return attachSongTypes(
            "wy", item, obj(
                "id" to jstr(item.g("id")),
                "name" to cleanHtml(item.g("name")),
                "singer" to formatArtists((item.arr("ar") ?: item.arr("artists")).objs().joinToString("/") { it.s("name") }),
                "album" to cleanHtml(al?.s("name")),
                "albumName" to cleanHtml(al?.s("name")),
                "albumId" to jos(item.o("al")?.g("id"), item.o("album")?.g("id")),
                "interval" to formatTime(Math.floor(jnum(jor(item.g("dt"), item.g("duration"), 0)) / 1000)),
                "source" to "wy",
                "songId" to jstr(item.g("id")),
                "musicId" to jstr(item.g("id")),
                "img" to pic,
                "picUrl" to pic
            )
        )
    }

    private fun pickKwInterval(item: JSONObject): String {
        for (key in listOf("song_duration", "songDuration", "songTimeMinutes", "duration")) {
            val raw = item.g(key) ?: continue
            val s = jstr(raw).trim()
            if (s.isEmpty()) continue
            if (s.contains(':')) return s
            val n = s.toDoubleOrNull() ?: continue
            if (n <= 0) continue
            if (n < 30 && (truthy(item.g("song_duration")) || truthy(item.g("songDuration")))) continue
            if (n > 10000) return formatTime(Math.floor(n / 1000))
            return formatTime(n)
        }
        return ""
    }

    private fun pickKwPic(item: JSONObject): String {
        val raw = item.s("pic", "albumpic", "pic120", "pic500", "hts_img", "img", "cover")
        if (raw.isEmpty()) return ""
        return raw.replaceFirst("/120/", "/500/")
    }

    private fun mapKwSong(item: JSONObject): JSONObject {
        val rid = jos(item.g("rid"), item.g("id")).ifEmpty { item.s("musicrid").replace(Regex("^MUSIC_", RegexOption.IGNORE_CASE), "") }
        val pic = pickKwPic(item)
        val album = cleanHtml(item.s("album", "ALBUM", "albumname"))
        return attachSongTypes(
            "kw", item, obj(
                "id" to rid,
                "name" to cleanHtml(item.s("name", "songname", "SONGNAME")),
                "singer" to formatArtists(item.s("artist", "ARTIST", "singer")),
                "album" to album,
                "albumName" to album,
                "albumId" to item.s("albumid", "ALBUMID", "albumId"),
                "interval" to pickKwInterval(item),
                "source" to "kw",
                "songId" to rid,
                "musicId" to rid,
                "rid" to rid,
                "img" to pic,
                "picUrl" to pic
            )
        )
    }

    private suspend fun kwFillMissingCovers(songs: List<JSONObject>) {
        val need = songs.filter { !truthy(jor(it.g("img"), it.g("picUrl"))) && truthy(jor(it.g("rid"), it.g("songId"), it.g("id"))) }
        if (need.isEmpty()) return
        coroutineScope {
            need.chunked(8).forEach { chunk ->
                chunk.map { song ->
                    async {
                        runCatching {
                            val mid = jos(song.g("rid"), song.g("songId"), song.g("id"))
                            val info = parseJsonObj(CatalogHttp.get("http://wapi.kuwo.cn/api/www/music/musicInfo?mid=${encodeUri(mid)}&httpsStatus=1", KW_HEADERS))?.o("data")
                            val pic = pickKwPic(info ?: JSONObject())
                            if (pic.isNotEmpty()) song.put("img", pic).put("picUrl", pic)
                            if (song.s("interval").isEmpty() && info != null) pickKwInterval(info).takeIf { it.isNotEmpty() }?.let { song.put("interval", it) }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private fun mapKgSong(item: JSONObject): JSONObject {
        val parts = item.s("filename").split(" - ")
        val singer = if (parts.size > 1) parts[0] else item.s("singername")
        val name = if (parts.size > 1) parts.drop(1).joinToString(" - ") else item.s("songname", "filename")
        val cover = item.s("album_sizable_cover", "imgurl").replaceFirst("{size}", "400")
        return attachSongTypes(
            "kg", item, obj(
                "id" to jos(item.g("hash"), item.g("album_audio_id")),
                "name" to cleanHtml(name),
                "singer" to formatArtists(singer),
                "album" to cleanHtml(item.s("album_name")),
                "interval" to formatTime(item.g("duration")),
                "source" to "kg",
                "hash" to item.s("hash"),
                "songId" to item.s("hash"),
                "albumAudioId" to item.s("album_audio_id"),
                "img" to cover,
                "picUrl" to cover
            )
        )
    }

    private fun mapMgSong(item: JSONObject): JSONObject {
        val o = item.o("objectInfo") ?: item
        val singers = (o.arr("singerList") ?: o.arr("singers")).objs().joinToString("/") { it.s("name") }
        val img = o.s("albumImg", "img1")
        return attachSongTypes(
            "mg", o, obj(
                "id" to jos(o.g("copyrightId"), o.g("songId")),
                "name" to cleanHtml(jor(o.g("songName"), o.g("name"))),
                "singer" to formatArtists(singers.ifEmpty { o.s("singer") }),
                "album" to cleanHtml(o.s("album")),
                "source" to "mg",
                "songId" to jos(o.g("copyrightId"), o.g("songId")),
                "copyrightId" to o.s("copyrightId"),
                "img" to img,
                "picUrl" to img,
                "interval" to ""
            )
        )
    }

    private fun mapAlbum(id: Any?, name: Any?, artist: Any?, img: Any?, publishTime: Any?, count: Any?, source: String): JSONObject = obj(
        "id" to jstr(id),
        "name" to cleanHtml(name),
        "artist" to formatArtists(artist),
        "img" to jos(img),
        "publishTime" to jos(publishTime),
        "count" to jnum(count).let { if (it.isNaN()) 0 else it.toLong() },
        "source" to source
    )

    private fun mapRankCard(id: Any?, name: Any?, cover: Any?, color: Any?, songs: List<Pair<String, String>>, source: String, updateTime: Any?): JSONObject = obj(
        "id" to jstr(id),
        "name" to cleanHtml(name),
        "cover" to jos(cover),
        "color" to jos(color),
        "updateTime" to jos(updateTime),
        "source" to source,
        "songs" to JSONArray(songs.take(3).map { (n, s) -> obj("name" to cleanHtml(n), "singer" to formatArtists(s)) }
            .filter { it.s("name").isNotEmpty() || it.s("singer").isNotEmpty() })
    )

    private suspend fun fillRankPreviewSongs(cards: List<JSONObject>, fetch: suspend (String) -> List<Pair<String, String>>) {
        coroutineScope {
            cards.filter { (it.arr("songs")?.length() ?: 0) == 0 }.chunked(8).forEach { chunk ->
                chunk.map { card ->
                    async {
                        runCatching {
                            val songs = fetch(card.s("id"))
                            if (songs.isNotEmpty()) {
                                card.put("songs", JSONArray(songs.take(3).map { (n, s) -> obj("name" to cleanHtml(n), "singer" to formatArtists(s)) }
                                    .filter { it.s("name").isNotEmpty() || it.s("singer").isNotEmpty() }))
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private fun regions(vararg items: Pair<String, String>): JSONArray = JSONArray(items.map { obj("id" to it.first, "label" to it.second) })

    private val TX_SONG_REGIONS get() = regions("latest" to "最新", "inland" to "内地", "hktw" to "港台", "eu" to "欧美", "korea" to "韩国", "japan" to "日本")
    private val TX_ALBUM_REGIONS get() = regions("inland" to "内地", "hktw" to "港台", "eu" to "欧美", "korea" to "韩国", "japan" to "日本")
    private val WY_SONG_REGIONS get() = regions("0" to "全部", "7" to "华语", "96" to "欧美", "8" to "日本", "16" to "韩国")
    private val WY_ALBUM_REGIONS get() = regions("ALL" to "全部", "ZH" to "华语", "EA" to "欧美", "KR" to "韩国", "JP" to "日本")
    private val TX_SONG_TYPE = mapOf("latest" to 0, "inland" to 1, "hktw" to 2, "eu" to 3, "korea" to 4, "japan" to 5)
    private val TX_ALBUM_TYPE = mapOf("inland" to 1, "hktw" to 2, "eu" to 3, "korea" to 4, "japan" to 5)

    // ---------------- QQ ----------------

    private suspend fun txNewSongs(region: String, page: Int, limit: Int): JSONObject {
        val reg = region.ifEmpty { "latest" }
        val type = TX_SONG_TYPE[reg] ?: 0
        val data = txMusicu(obj("comm" to obj("ct" to 24, "cv" to 0), "new_song" to obj("module" to "newsong.NewSongServer", "method" to "get_new_song_info", "param" to obj("type" to type))))
        val d = data?.o("new_song")?.o("data")
        var songs = (d?.arr("songlist") ?: d?.arr("song_list") ?: d?.arr("list")).objs()
        if (songs.isEmpty()) {
            val fb = txMusicu(obj("comm" to obj("ct" to 24), "new_song" to obj("module" to "QQMusic.MusichallServer", "method" to "GetNewSong", "param" to obj("type" to type))))
            val fd = fb?.o("new_song")?.o("data")
            songs = (fd?.arr("song_list") ?: fd?.arr("list")).objs()
        }
        val mapped = songs.map(::mapTxSong).filter { truthy(it.g("songmid")) || truthy(it.g("id")) }
        return pageSlice(mapped, page, limit).put("regions", TX_SONG_REGIONS).put("region", reg).put("source", "tx")
    }

    private suspend fun txNewAlbums(region: String, page: Int, limit: Int): JSONObject {
        val reg = region.ifEmpty { "inland" }
        val area = TX_ALBUM_TYPE[reg] ?: 1
        val sin = maxOf(0, (page - 1) * limit)
        val data = txMusicuPost(obj("new_album" to obj("module" to "newalbum.NewAlbumServer", "method" to "get_new_album_info", "param" to obj("area" to area, "sin" to sin, "num" to limit))))
        var list = data?.o("new_album")?.o("data")?.arr("albums").objs()
        var total = jint(data?.o("new_album")?.o("data")?.g("total")).takeIf { it > 0 } ?: list.size.toLong()
        if (list.isEmpty()) {
            val legacy = txMusicu(obj("comm" to obj("ct" to 24), "new_album" to obj(
                "module" to "QQMusic.MusichallServer", "method" to "GetNewAlbum",
                "param" to obj("type" to area, "category" to "-1", "genre" to 0, "year" to 1, "company" to -1, "sort" to 1, "start" to sin, "end" to sin + limit - 1)
            )))
            val ld = legacy?.o("new_album")?.o("data")
            list = (ld?.arr("album_list") ?: ld?.arr("list") ?: ld?.arr("albums")).objs().map { item ->
                val album = item.o("album") ?: item
                val authors = (item.arr("author") ?: item.arr("singers")) ?: JSONArray()
                obj(
                    "mid" to album.s("mid", "album_mid", "albumMID"),
                    "id" to jor(album.g("id"), album.g("album_id")),
                    "name" to jor(album.g("name"), album.g("title"), album.g("album_name")),
                    "singers" to authors,
                    "release_time" to album.s("time_public", "public_time", "pub_time"),
                    "song_count" to (jor(album.g("song_count"), 0) ?: 0)
                )
            }
            total = jint(jor(ld?.g("size"), ld?.g("total"))).takeIf { it > 0 } ?: list.size.toLong()
        }
        val mapped = list.map { item ->
            val mid = item.s("mid", "album_mid", "albumMID")
            val singers = item.arr("singers")
            mapAlbum(
                jor(mid, item.g("id"), item.g("album_id")),
                jor(item.g("name"), item.g("album_name"), item.g("title")),
                if (singers != null) singers.objs().joinToString("/") { it.s("name", "title") } else item.s("singer_name", "singerName"),
                jor(item.g("album_pic"), if (mid.isNotEmpty()) "https://y.gtimg.cn/music/photo_new/T002R300x300M000$mid.jpg" else ""),
                item.s("release_time", "public_time", "pub_time"),
                jor(item.g("song_count"), 0),
                "tx"
            )
        }
        return obj(
            "list" to JSONArray(mapped), "total" to total, "page" to page,
            "allPage" to maxOf(1, Math.ceil((if (total > 0) total else list.size.toLong()).toDouble() / limit).toInt()),
            "regions" to TX_ALBUM_REGIONS, "region" to reg, "source" to "tx"
        )
    }

    private suspend fun txToplists(): JSONObject {
        val data = txMusicu(obj("comm" to obj("ct" to 24, "cv" to 0), "toplist" to obj("module" to "musicToplist.ToplistInfoServer", "method" to "GetAll", "param" to JSONObject())))
        var groups = (data?.o("toplist")?.o("data")?.arr("group") ?: data?.o("toplist")?.o("data")?.arr("list")).objs()
        if (groups.isEmpty()) {
            val legacy = txMusicu(obj("comm" to obj("ct" to 24), "toplist" to obj("module" to "music.web_toplist_svr", "method" to "get_toplist_index", "param" to JSONObject())))
            val raw = legacy?.o("toplist")?.o("data")?.arr("topList") ?: legacy?.o("toplist")?.o("data")?.arr("group")
            groups = if (raw != null) listOf(obj("groupName" to "", "toplist" to raw)) else emptyList()
        }
        val cards = ArrayList<JSONObject>()
        for (g in groups) {
            for (item in (g.arr("toplist") ?: g.arr("list") ?: g.arr("group_top_list")).objs()) {
                val id = jor(item.g("topId"), item.g("id"), item.g("topID"))
                if (!truthy(id)) continue
                val songs = (item.arr("song") ?: item.arr("songlist") ?: item.arr("songs")).objs().map { s ->
                    val singer = s.arr("singer")
                    jos(s.g("title"), s.g("songname"), s.g("name")) to (if (singer != null) singer.objs().joinToString("/") { it.s("name") } else s.s("singerName", "singername"))
                }
                cards.add(mapRankCard(
                    id, jor(item.g("title"), item.g("name"), item.g("topTitle")),
                    item.s("headPicUrl", "frontPicUrl", "picUrl", "cover"), item.s("color", "title_shared_color"),
                    songs, "tx", item.s("updateTime", "update_time")
                ))
            }
        }
        return obj("list" to JSONArray(cards), "total" to cards.size, "source" to "tx")
    }

    private suspend fun txToplistDetail(id: String, page: Int, limit: Int): JSONObject {
        val data = txMusicu(obj("comm" to obj("ct" to 24, "cv" to 0), "detail" to obj(
            "module" to "musicToplist.ToplistInfoServer", "method" to "GetDetail",
            "param" to obj("topId" to (id.toLongOrNull() ?: id), "offset" to (page - 1) * limit, "num" to limit, "period" to "")
        )))
        val detail = data?.o("detail")?.o("data")
        val songs = (detail?.arr("songInfoList") ?: detail?.o("data")?.arr("song") ?: detail?.arr("songlist")).objs()
        val info = detail?.o("data") ?: detail ?: JSONObject()
        return obj(
            "info" to obj(
                "id" to id,
                "name" to cleanHtml(jos(info.g("title"), info.g("topTitle"), info.g("name")).ifEmpty { "排行榜" }),
                "cover" to info.s("headPicUrl", "frontPicUrl", "picUrl"),
                "updateTime" to info.s("updateTime"),
                "source" to "tx"
            ),
            "list" to JSONArray(songs.map(::mapTxSong)),
            "total" to (jint(detail?.g("totalNum")).takeIf { it > 0 } ?: songs.size.toLong()),
            "page" to page,
            "source" to "tx"
        )
    }

    // ---------------- 网易云 ----------------

    private suspend fun wyNewSongs(region: String, page: Int, limit: Int): JSONObject {
        val type = region.ifEmpty { "0" }
        val data = parseJsonObj(CatalogHttp.get("https://music.163.com/api/v1/discovery/new/songs?areaId=$type&total=true&limit=100", WY_HEADERS))
        val songs = (data?.arr("data") ?: data?.arr("songList")).objs()
        return pageSlice(songs.map(::mapWySong), page, limit).put("regions", WY_SONG_REGIONS).put("region", type).put("source", "wy")
    }

    private suspend fun wyNewAlbums(region: String, page: Int, limit: Int): JSONObject {
        val area = if (region in setOf("ALL", "ZH", "EA", "KR", "JP")) region else "ALL"
        val offset = (page - 1) * limit
        val data = parseJsonObj(CatalogHttp.get("https://music.163.com/api/album/new?area=${encodeUri(area)}&limit=$limit&offset=$offset", WY_HEADERS))
        val list = data?.arr("albums").objs()
        val total = jint(data?.g("total")).takeIf { it > 0 } ?: list.size.toLong()
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val mapped = list.map { item ->
            mapAlbum(
                item.g("id"), item.g("name"),
                item.arr("artists").objs().joinToString("/") { it.s("name") }.ifEmpty { item.o("artist")?.s("name").orEmpty() },
                item.g("picUrl"),
                jnum(item.g("publishTime")).let { if (it.isNaN() || it <= 0) "" else f.format(Date(it.toLong())) },
                item.g("size"), "wy"
            )
        }
        return obj(
            "list" to JSONArray(mapped), "total" to total, "page" to page, "allPage" to maxOf(1, Math.ceil(total.toDouble() / limit).toInt()),
            "regions" to WY_ALBUM_REGIONS, "region" to area, "source" to "wy"
        )
    }

    private suspend fun wyFetchRankPreview(id: String): List<Pair<String, String>> {
        val tracks = parseJsonObj(CatalogHttp.get("https://music.163.com/api/v6/playlist/detail?id=$id&n=3&s=8", WY_HEADERS))?.o("playlist")?.arr("tracks").objs()
        return tracks.take(3).map { t -> cleanHtml(t.g("name")) to formatArtists(t.arr("ar").objs().joinToString("/") { it.s("name") }) }
    }

    private suspend fun wyToplists(): JSONObject {
        val data = parseJsonObj(CatalogHttp.get("https://music.163.com/api/toplist", WY_HEADERS))
        val list = data?.arr("list").objs().map { item ->
            val songs = item.arr("tracks").objs().take(3).map { t -> jos(t.g("first"), t.g("name")) to jos(t.g("second")).ifEmpty { t.arr("ar").objs().joinToString("/") { it.s("name") } } }
            mapRankCard(item.g("id"), item.g("name"), item.g("coverImgUrl"), "", songs, "wy", item.s("updateFrequency"))
        }
        fillRankPreviewSongs(list) { wyFetchRankPreview(it) }
        return obj("list" to JSONArray(list), "total" to list.size, "source" to "wy")
    }

    private suspend fun wyToplistDetail(id: String, page: Int, limit: Int): JSONObject {
        val data = parseJsonObj(CatalogHttp.get("https://music.163.com/api/v6/playlist/detail?id=$id&n=100000&s=8", WY_HEADERS))
        val pl = data?.o("playlist") ?: throw IllegalStateException("无法获取网易云榜单")
        val sliced = pageSlice(pl.arr("tracks").objs().map(::mapWySong), page, limit)
        return sliced.put("info", obj("id" to id, "name" to cleanHtml(pl.g("name")), "cover" to pl.s("coverImgUrl"), "updateTime" to "", "source" to "wy"))
            .put("source", "wy")
    }

    // ---------------- 酷我 ----------------

    private suspend fun kwNewSongs(page: Int, limit: Int): JSONObject {
        val data = parseJsonObj(CatalogHttp.get("http://wapi.kuwo.cn/api/www/bang/bang/musicList?bangId=17&pn=$page&rn=$limit&httpsStatus=1", KW_HEADERS))
        val list = data?.o("data")?.arr("musicList").objs()
        val total = jint(data?.o("data")?.g("num")).takeIf { it > 0 } ?: list.size.toLong()
        return obj(
            "list" to JSONArray(list.map(::mapKwSong)), "total" to total, "page" to page,
            "allPage" to maxOf(1, Math.ceil(total.toDouble() / limit).toInt()), "regions" to JSONArray(), "region" to "", "source" to "kw"
        )
    }

    private suspend fun kwNewAlbums(page: Int, limit: Int): JSONObject {
        val collected = ArrayList<JSONObject>()
        val rn = minOf(100, maxOf(40, limit * 4))
        val attempts = listOf(
            "http://kbangserver.kuwo.cn/ksong.s?from=pc&fmt=json&pn=0&rn=$rn&type=bang&data=content&id=17&show_copyright_off=0&pcmp4=1&isbang=1&userid=0",
            "http://wapi.kuwo.cn/api/www/bang/bang/musicList?bangId=17&pn=1&rn=$rn&httpsStatus=1"
        )
        for (url in attempts) {
            if (collected.isNotEmpty()) break
            runCatching {
                val data = parseJsonObj(CatalogHttp.get(url, KW_HEADERS))
                (data?.o("data")?.arr("musicList") ?: data?.arr("musiclist") ?: data?.arr("list")).objs().forEach { item ->
                    collected.add(mapAlbum(
                        jor(item.g("albumid"), item.g("ALBUMID"), item.g("albumId")), jor(item.g("album"), item.g("ALBUM"), item.g("albumname")),
                        jor(item.g("artist"), item.g("ARTIST"), item.g("singer")), item.s("albumpic", "pic", "img"),
                        item.s("releaseDate", "pub"), 0, "kw"
                    ))
                }
            }
        }
        if (collected.isEmpty()) {
            runCatching {
                val data = parseJsonObj(CatalogHttp.get("http://wapi.kuwo.cn/openapi/v1/pc/disc/shelves?pn=1&rn=40", KW_HEADERS))
                (data?.o("data")?.arr("albumList") ?: data?.o("data")?.arr("list")).objs().forEach { item ->
                    collected.add(mapAlbum(
                        jor(item.g("albumid"), item.g("id")), jor(item.g("album"), item.g("name")), item.g("artist"),
                        jor(item.g("pic"), item.g("img")), item.s("releaseDate"), jor(item.g("musiccnt"), item.g("total"), 0), "kw"
                    ))
                }
            }
        }
        return pageSlice(dedupeAlbums(collected), page, limit).put("regions", JSONArray()).put("region", "").put("source", "kw")
    }

    private data class KwBang(val list: List<JSONObject>, val total: Long, var name: String, var cover: String, var updateTime: String)

    private suspend fun kwBangMusicList(id: String, page: Int, limit: Int): KwBang {
        val bangId = id.trim()
        if (bangId.isEmpty()) return KwBang(emptyList(), 0, "", "", "")
        suspend fun tryWapi(): KwBang? {
            val data = parseJsonObj(CatalogHttp.get("http://wapi.kuwo.cn/api/www/bang/bang/musicList?bangId=${encodeUri(bangId)}&pn=$page&rn=$limit&httpsStatus=1", KW_HEADERS))
            if (jnum(data?.g("code")) != 200.0) return null
            val list = data?.o("data")?.arr("musicList").objs()
            if (list.isEmpty()) return null
            val d = data?.o("data")
            return KwBang(list, jint(d?.g("num")).takeIf { it > 0 } ?: list.size.toLong(), cleanHtml(d?.s("name", "title")), d?.s("pic", "img").orEmpty(), d?.s("pub").orEmpty())
        }
        suspend fun tryKbang(): KwBang? {
            val pn = maxOf(0, page - 1)
            val data = parseJsonObj(CatalogHttp.get("http://kbangserver.kuwo.cn/ksong.s?from=pc&fmt=json&pn=$pn&rn=$limit&type=bang&data=content&id=${encodeUri(bangId)}&show_copyright_off=0&pcmp4=1&isbang=1&userid=0", KW_HEADERS))
            val list = (data?.arr("musiclist") ?: data?.o("data")?.arr("musicList") ?: data?.arr("list")).objs()
            if (list.isEmpty()) return null
            return KwBang(
                list, jint(jor(data?.g("num"), data?.o("data")?.g("num"))).takeIf { it > 0 } ?: list.size.toLong(),
                cleanHtml(jos(data?.g("name"), data?.g("leader"), data?.o("data")?.g("name"))),
                jos(data?.g("pic"), data?.g("v9_pic2"), data?.o("data")?.g("pic")),
                jos(data?.g("pub"), data?.o("data")?.g("pub"))
            )
        }
        runCatching { tryWapi() }.getOrNull()?.let { hit ->
            if (hit.name.isEmpty() && page <= 1) {
                runCatching { tryKbang() }.getOrNull()?.let { meta ->
                    if (meta.name.isNotEmpty()) hit.name = meta.name
                    if (hit.cover.isEmpty() && meta.cover.isNotEmpty()) hit.cover = meta.cover
                    if (hit.updateTime.isEmpty() && meta.updateTime.isNotEmpty()) hit.updateTime = meta.updateTime
                }
            }
            return hit
        }
        runCatching { tryKbang() }.getOrNull()?.let { return it }
        return KwBang(emptyList(), 0, "", "", "")
    }

    private suspend fun kwToplists(): JSONObject {
        val data = parseJsonObj(CatalogHttp.get("http://wapi.kuwo.cn/api/www/bang/bang/bangMenu?httpsStatus=1", KW_HEADERS))
        val cards = ArrayList<JSONObject>()
        for (g in data?.arr("data").objs()) {
            for (item in g.arr("list").objs()) {
                val bangId = jor(item.g("sourceid"), item.g("bangId"), item.g("bangid"))
                if (!truthy(bangId)) continue
                cards.add(mapRankCard(bangId, item.g("name"), jor(item.g("pic"), item.g("img")), "", emptyList(), "kw", item.s("pub")))
            }
        }
        fillRankPreviewSongs(cards) { id ->
            kwBangMusicList(id, 1, 3).list.take(3).map { cleanHtml(it.s("name", "songname")) to formatArtists(it.s("artist", "singer")) }
        }
        return obj("list" to JSONArray(cards), "total" to cards.size, "source" to "kw")
    }

    private suspend fun kwToplistDetail(id: String, page: Int, limit: Int): JSONObject {
        val raw = kwBangMusicList(id, page, limit)
        val mapped = raw.list.map(::mapKwSong).filter { truthy(it.g("id")) && truthy(it.g("name")) }
        kwFillMissingCovers(mapped)
        return obj(
            "info" to obj("id" to id, "name" to raw.name.ifEmpty { "排行榜" }, "cover" to raw.cover, "updateTime" to raw.updateTime, "source" to "kw"),
            "list" to JSONArray(mapped),
            "total" to (if (raw.total > 0) raw.total else raw.list.size.toLong()),
            "page" to page,
            "source" to "kw"
        )
    }

    // ---------------- 酷狗 ----------------

    private suspend fun kgNewSongs(page: Int, limit: Int): JSONObject {
        val data = parseJsonObj(CatalogHttp.get("http://mobilecdn.kugou.com/api/v3/rank/song?version=9108&rankid=8888&page=$page&pagesize=$limit&area_code=1", mapOf("User-Agent" to "Mozilla/5.0")))
        val list = data?.o("data")?.arr("info").objs()
        val total = jint(data?.o("data")?.g("total")).takeIf { it > 0 } ?: list.size.toLong()
        return obj(
            "list" to JSONArray(list.map(::mapKgSong)), "total" to total, "page" to page,
            "allPage" to maxOf(1, Math.ceil(total.toDouble() / limit).toInt()), "regions" to JSONArray(), "region" to "", "source" to "kg"
        )
    }

    private suspend fun kgNewAlbums(page: Int, limit: Int): JSONObject {
        val collected = ArrayList<JSONObject>()
        runCatching {
            val data = parseJsonObj(CatalogHttp.get("http://service.mobile.kugou.com/v1/yueku/recommend?plat=0&type=8&operator=2&version=8352", mapOf("User-Agent" to UA_PC)))
            data?.o("data")?.o("info")?.arr("album").objs().forEach { item ->
                collected.add(mapAlbum(
                    jor(item.g("albumid"), item.g("album_id")), jor(item.g("albumname"), item.g("album_name")),
                    jor(item.g("singername"), item.g("author_name")), item.s("imgurl", "sizable_cover").replaceFirst("{size}", "400"),
                    item.s("publishtime"), jor(item.g("songcount"), 0), "kg"
                ))
            }
        }
        if (collected.size < maxOf(limit, 12)) {
            runCatching {
                val data = parseJsonObj(CatalogHttp.get("http://mobilecdn.kugou.com/api/v3/rank/song?version=9108&rankid=8888&page=1&pagesize=50&area_code=1", mapOf("User-Agent" to UA_PC)))
                data?.o("data")?.arr("info").objs().forEach { item ->
                    val id = jor(item.g("album_id"), item.g("albumid"))
                    if (!truthy(id)) return@forEach
                    val parts = item.s("filename").split(" - ")
                    val singer = if (parts.size > 1) parts[0] else jos(item.arr("authors").objs().firstOrNull()?.g("author_name"), item.g("singername"))
                    collected.add(mapAlbum(
                        id, jos(item.g("album_name"), item.g("albumname"), item.g("remark"), item.g("songname")), singer,
                        item.s("album_sizable_cover", "imgurl").replaceFirst("{size}", "400"),
                        jos(item.g("addtime"), item.g("rank_id_publish_date")).take(10), 0, "kg"
                    ))
                }
            }
        }
        return pageSlice(dedupeAlbums(collected), page, limit).put("regions", JSONArray()).put("region", "").put("source", "kg")
    }

    private fun mapKgRankPreviewSongs(songinfo: JSONArray?): List<Pair<String, String>> =
        songinfo.objs().take(3).map { s ->
            var name = cleanHtml(s.s("name", "title"))
            var singer = formatArtists(s.s("author", "singer", "singername", "artist"))
            val combined = s.s("songname", "filename")
            if ((name.isEmpty() || singer.isEmpty()) && combined.isNotEmpty()) {
                val parts = combined.split(" - ")
                if (parts.size > 1) {
                    if (singer.isEmpty()) singer = formatArtists(parts[0])
                    if (name.isEmpty()) name = cleanHtml(parts.drop(1).joinToString(" - "))
                } else if (name.isEmpty()) {
                    name = cleanHtml(combined)
                }
            }
            name to singer
        }.filter { it.first.isNotEmpty() || it.second.isNotEmpty() }

    private suspend fun kgToplists(): JSONObject {
        val data = parseJsonObj(CatalogHttp.get("http://mobilecdn.kugou.com/api/v3/rank/list?version=9108&apiver=6&withsong=1", mapOf("User-Agent" to "Mozilla/5.0")))
        val list = data?.o("data")?.arr("info").objs().map { item ->
            mapRankCard(
                jor(item.g("rankid"), item.g("rank_id"), item.g("id")), jor(item.g("rankname"), item.g("rank_name")),
                item.s("imgurl", "img_9", "bannerurl").replaceFirst("{size}", "400"), "",
                mapKgRankPreviewSongs(item.arr("songinfo")), "kg", item.s("update_frequency")
            )
        }
        return obj("list" to JSONArray(list), "total" to list.size, "source" to "kg")
    }

    private suspend fun kgToplistDetail(id: String, page: Int, limit: Int): JSONObject {
        val data = parseJsonObj(CatalogHttp.get("http://mobilecdn.kugou.com/api/v3/rank/song?version=9108&rankid=$id&page=$page&pagesize=$limit&area_code=1", mapOf("User-Agent" to "Mozilla/5.0")))
        val list = data?.o("data")?.arr("info").objs()
        return obj(
            "info" to obj("id" to id, "name" to "排行榜", "cover" to "", "updateTime" to "", "source" to "kg"),
            "list" to JSONArray(list.map(::mapKgSong)),
            "total" to (jint(data?.o("data")?.g("total")).takeIf { it > 0 } ?: list.size.toLong()),
            "page" to page,
            "source" to "kg"
        )
    }

    // ---------------- 咪咕 ----------------

    private suspend fun mgNewSongs(page: Int, limit: Int): JSONObject {
        val data = parseJsonObj(CatalogHttp.get(
            "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/querycontentbyId.do?columnId=27553319&needAll=0",
            mapOf("Referer" to "https://m.music.migu.cn/", "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X)", "channel" to "0146951")
        ))
        val contents = (data?.o("columnInfo")?.arr("contents") ?: data?.o("data")?.arr("contents")).objs()
        val songs = contents.mapNotNull { c ->
            val o = c.o("objectInfo") ?: c
            if (!truthy(o.g("copyrightId")) && !truthy(o.g("songId"))) null else mapMgSong(o)
        }
        if (songs.isEmpty()) return unsupported(page)
        return pageSlice(songs, page, limit).put("regions", JSONArray()).put("region", "").put("source", "mg")
    }

    private suspend fun mgNewAlbums(page: Int, limit: Int): JSONObject = try {
        val songsRes = mgNewSongs(1, 100)
        if (songsRes.optBoolean("unsupported")) unsupported(page) else {
            val collected = songsRes.arr("list").objs().mapNotNull { s ->
                val name = cleanHtml(s.s("album"))
                if (name.isEmpty()) null
                else mapAlbum(jos(s.g("albumId"), s.g("albumid"), name), name, s.g("singer"), jor(s.g("img"), s.g("picUrl")), "", 0, "mg")
            }
            val list = dedupeAlbums(collected)
            if (list.isEmpty()) unsupported(page) else pageSlice(list, page, limit).put("regions", JSONArray()).put("region", "").put("source", "mg")
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        unsupported(page)
    }

    private suspend fun mgToplists(): JSONObject {
        val data = parseJsonObj(CatalogHttp.get("https://app.c.nf.migu.cn/MIGUM3.0/bmw/billboard/home/v1.0", MG_HEADERS))
        val columns = (data?.o("data")?.o("columnInfo")?.arr("contents") ?: data?.o("data")?.arr("contents")).objs()
        val cards = columns.mapNotNull { c ->
            val o = c.o("objectInfo") ?: c
            val id = jor(o.g("columnId"), o.g("id"), o.g("billboardId"))
            if (!truthy(id)) null else mapRankCard(id, jor(o.g("title"), o.g("columnTitle"), o.g("name")), o.s("imageUrl", "img"), "", emptyList(), "mg", "")
        }
        if (cards.isEmpty()) return obj("list" to JSONArray(), "total" to 0, "source" to "mg", "unsupported" to true)
        return obj("list" to JSONArray(cards), "total" to cards.size, "source" to "mg")
    }

    private suspend fun mgToplistDetail(id: String, page: Int, limit: Int): JSONObject {
        val data = parseJsonObj(CatalogHttp.get("https://app.c.nf.migu.cn/MIGUM3.0/bmw/billboard/home/list/v1.0?columnId=${encodeUri(id)}", MG_HEADERS))
        val contents = (data?.o("data")?.o("columnInfo")?.arr("contents") ?: data?.o("data")?.arr("contents")).objs()
        val songs = contents.mapNotNull { c ->
            val o = c.o("objectInfo") ?: c
            if (!truthy(o.g("copyrightId")) && !truthy(o.g("songId"))) null else mapMgSong(o)
        }
        return pageSlice(songs, page, limit)
            .put("info", obj("id" to id, "name" to "排行榜", "cover" to "", "updateTime" to "", "source" to "mg"))
            .put("source", "mg")
    }

    // ---------------- 导出 ----------------

    suspend fun fetchNewSongs(source: String, region: String = "", page: Int = 1, limit: Int = 27): JSONObject = when (source) {
        "tx" -> txNewSongs(region, page, limit)
        "wy" -> wyNewSongs(region, page, limit)
        "kw" -> kwNewSongs(page, limit)
        "kg" -> kgNewSongs(page, limit)
        "mg" -> mgNewSongs(page, limit)
        else -> throw IllegalArgumentException("不支持的平台: $source")
    }

    suspend fun fetchNewAlbums(source: String, region: String = "", page: Int = 1, limit: Int = 20): JSONObject = when (source) {
        "tx" -> txNewAlbums(region, page, limit)
        "wy" -> wyNewAlbums(region, page, limit)
        "kw" -> kwNewAlbums(page, limit)
        "kg" -> kgNewAlbums(page, limit)
        "mg" -> mgNewAlbums(page, limit)
        else -> throw IllegalArgumentException("不支持的平台: $source")
    }

    suspend fun fetchToplists(source: String): JSONObject = when (source) {
        "tx" -> txToplists()
        "wy" -> wyToplists()
        "kw" -> kwToplists()
        "kg" -> kgToplists()
        "mg" -> mgToplists()
        else -> throw IllegalArgumentException("不支持的平台: $source")
    }

    suspend fun fetchToplistDetail(source: String, id: String, page: Int = 1, limit: Int = 100): JSONObject {
        if (id.isBlank()) throw IllegalArgumentException("缺少榜单 ID")
        return when (source) {
            "tx" -> txToplistDetail(id, page, limit)
            "wy" -> wyToplistDetail(id, page, limit)
            "kw" -> kwToplistDetail(id, page, limit)
            "kg" -> kgToplistDetail(id, page, limit)
            "mg" -> mgToplistDetail(id, page, limit)
            else -> throw IllegalArgumentException("不支持的平台: $source")
        }
    }
}
