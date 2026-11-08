package com.lm.player.core.source.catalog

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * server/musicSdk.js 的 Kotlin 移植（MIT License, Copyright (c) 2026 jia070310）。
 * 输出 JSON 的字段与服务端完全一致，客户端现有解析代码可直接复用；歌曲 ID 规则不变。
 *
 * 未移植：插件音源搜索（searchPluginMedia）、酷狗 KRC 逐字歌词（客户端只用 LRC）。
 */
internal object MusicSdk {

    val SOURCES = listOf("kw", "kg", "tx", "wy", "mg")

    private val WY_HEADERS = mapOf("Referer" to "https://music.163.com", "User-Agent" to UA_PC)
    private val MG_HEADERS = mapOf("Referer" to "https://music.migu.cn", "User-Agent" to UA_PC_SHORT, "channel" to "0146951")
    private val KG_HEADERS = mapOf("Referer" to "https://www.kugou.com/", "User-Agent" to UA_PC)
    private val KG_MOBILE_HTML_HEADERS = mapOf("Referer" to "https://m.kugou.com/", "User-Agent" to UA_IPHONE_FULL)
    private val TX_H5_HEADERS = mapOf("Referer" to "https://y.qq.com/m/index.html", "User-Agent" to UA_IPHONE_FULL)
    private val KG_MOBILE_HOSTS = listOf("https://mobilecdn.kugou.com", "http://mobilecdn.kugou.com", "http://mobilecdnbj.kugou.com")
    private const val PLAYLIST_PARTIAL_LIMIT = 100

    // ======================================================================
    // utils
    // ======================================================================

    fun cleanHtml(v: Any?): String {
        if (!truthy(v)) return ""
        var s = jstr(v).replace(Regex("<[^>]+>"), "")
        s = Regex("\\\\u([0-9a-fA-F]{4})").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
        s = s.replace(Regex("&nbsp;", RegexOption.IGNORE_CASE), " ")
        s = Regex("&#(\\d+);").replace(s) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        s = Regex("&#x([0-9a-f]+);", RegexOption.IGNORE_CASE).replace(s) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private val ARTIST_SPLIT = Regex("(?:\\s*/\\s*|\\s*[;|]\\s*|\\s*[&＆]\\s*|、|，|,)+")

    fun formatArtists(v: Any?): String {
        var s = cleanHtml(v)
        if (s.isEmpty()) return ""
        s = s.replace("\\&", "&")
        if (s.contains('\u0000')) return s.split('\u0000').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" / ")
        val parts = s.split(ARTIST_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return s
        val seen = HashSet<String>()
        return parts.filter { seen.add(it.lowercase()) }.joinToString(" / ")
    }

    private fun formatSize(bytes: Any?): String {
        val n = jnum(bytes)
        if (n.isNaN() || n <= 0) return ""
        return if (n < 1024 * 1024) String.format(Locale.US, "%.1fKB", n / 1024) else String.format(Locale.US, "%.1fMB", n / 1024 / 1024)
    }

    /** qualityMap 保持插入顺序；值为 false/0/null 的档位跳过 */
    private fun buildTypes(qualityMap: LinkedHashMap<String, Any?>, formatMap: Map<String, String> = emptyMap()): MutableList<JSONObject> {
        val types = ArrayList<JSONObject>()
        for ((type, size) in qualityMap) {
            if (size == null || size == JSONObject.NULL || size == false || (size is Number && size.toDouble() == 0.0)) continue
            val entry = JSONObject().put("type", type)
            val sizeText = when (size) {
                is String -> size
                true -> ""
                else -> formatSize(size)
            }
            val fmt = formatMap[type]
            when {
                !fmt.isNullOrEmpty() -> entry.put("format", fmt.lowercase())
                type == "128k" || type == "320k" -> entry.put("format", "mp3")
                type in setOf("flac", "flac24bit", "hires", "master") -> entry.put("format", "flac")
                type == "atmos" || type == "atmos_plus" -> entry.put("format", "m4a")
            }
            if (sizeText.isNotEmpty()) entry.put("size", sizeText)
            types.add(entry)
        }
        return types
    }

    private fun parseKwTypes(item: JSONObject): MutableList<JSONObject> {
        val minfo = jos(item.g("N_MINFO"), item.g("MINFO"))
        if (minfo.isEmpty()) return mutableListOf()
        val map = LinkedHashMap<String, Any?>()
        val formats = HashMap<String, String>()
        data class Cand(val bitrate: Int, val level: String, val fmt: String, val size: String)
        val hires = ArrayList<Cand>()
        val master = ArrayList<Cand>()
        val re = Regex("level:([^,]+),bitrate:(\\d+),format:([^,]+),size:([^;]+)", RegexOption.IGNORE_CASE)
        for (part in minfo.split(';')) {
            val m = re.find(part) ?: continue
            val level = m.groupValues[1].trim()
            val bitrate = m.groupValues[2].toIntOrNull() ?: 0
            val fmt = m.groupValues[3].trim().lowercase()
            val sizeRaw = m.groupValues[4].trim()
            if (sizeRaw.isEmpty() || !Regex("^[\\d.]+", RegexOption.IGNORE_CASE).containsMatchIn(sizeRaw) || sizeRaw.contains("zpmb", true)) continue
            val size = sizeRaw.uppercase()
            if (bitrate == 128 && fmt == "mp3") { map["128k"] = size; formats["128k"] = "mp3"; continue }
            if (bitrate == 320 && fmt == "mp3") { map["320k"] = size; formats["320k"] = "mp3"; continue }
            if (bitrate == 2000 && (fmt == "flac" || level == "ff")) { map["flac"] = size; formats["flac"] = "flac"; continue }
            if ((fmt == "mflac" || fmt == "flac") && bitrate >= 4000) {
                val c = Cand(bitrate, level, fmt, size)
                if (level.contains("zply", true) || level.contains("zpga501", true) || level.contains("master", true) ||
                    bitrate == 20501 || bitrate >= 24000
                ) master.add(c) else hires.add(c)
            }
        }
        if (hires.isNotEmpty()) {
            val sorted = hires.sortedBy { c ->
                when {
                    c.level == "zpga201" || c.bitrate == 20201 -> 0
                    c.bitrate == 4000 -> 1
                    else -> 10 + c.bitrate
                }
            }
            map["flac24bit"] = sorted[0].size
            formats["flac24bit"] = "flac"
        }
        if (master.isNotEmpty()) {
            map["master"] = master.maxByOrNull { it.bitrate }!!.size
            formats["master"] = "flac"
        } else if (map["flac24bit"] != null && hires.size > 1) {
            val top = hires.maxByOrNull { it.bitrate }
            if (top != null && top.size != map["flac24bit"]) {
                map["master"] = top.size
                formats["master"] = "flac"
            }
        }
        return buildTypes(map, formats)
    }

    private fun parseKgTypes(item: JSONObject): MutableList<JSONObject> {
        val map = LinkedHashMap<String, Any?>()
        if (truthy(item.g("FileSize"))) map["128k"] = item.g("FileSize")
        if (truthy(item.g("HQFileSize"))) map["320k"] = item.g("HQFileSize")
        if (truthy(item.g("SQFileSize"))) map["flac"] = item.g("SQFileSize")
        if (truthy(item.g("ResFileSize"))) map["flac24bit"] = item.g("ResFileSize")
        val masterSize = jor(item.g("MasterFileSize"), item.g("filesize_master"), item.g("filesize_super"), item.g("ExtFileSize"), item.g("filesize_ext"))
        if (truthy(masterSize)) map["master"] = masterSize
        return buildTypes(map)
    }

    private fun nn(vararg v: Any?): Any? = v.firstOrNull { !it.isNullish() }

    private fun parseTxTypes(item: JSONObject): MutableList<JSONObject> {
        val f = item.o("file") ?: JSONObject()
        val map = LinkedHashMap<String, Any?>()
        val formats = HashMap<String, String>()
        val s128 = nn(f.g("size_128mp3"), item.g("size128"))
        val s320 = nn(f.g("size_320mp3"), item.g("size320"))
        val sflac = nn(f.g("size_flac"), item.g("sizeflac"))
        val shires = nn(f.g("size_hires"), item.g("sizehires"))
        val smaster = nn(f.g("size_new_master"), f.g("size_master"), item.g("sizemaster"), item.g("size_new_master"))
        val satmos = nn(f.g("size_new_dolby"), f.g("size_dolby"), f.g("size_atmos"), item.g("sizedolby"))
        val satmosPlus = nn(f.g("size_new_atmos"), f.g("size_atmos_plus"), item.g("sizeatmos"))
        if (truthy(s128)) { map["128k"] = s128; formats["128k"] = "mp3" }
        if (truthy(s320)) { map["320k"] = s320; formats["320k"] = "mp3" }
        if (truthy(sflac)) { map["flac"] = sflac; formats["flac"] = "flac" }
        if (truthy(shires)) { map["flac24bit"] = shires; formats["flac24bit"] = "flac" }
        if (truthy(smaster)) { map["master"] = smaster; formats["master"] = "flac" }
        if (truthy(satmos)) { map["atmos"] = satmos; formats["atmos"] = "m4a" }
        if (truthy(satmosPlus)) { map["atmos_plus"] = satmosPlus; formats["atmos_plus"] = "m4a" }
        return buildTypes(map, formats)
    }

    private fun parseWyTypes(item: JSONObject, priv: JSONObject? = item.o("privilege")): MutableList<JSONObject> {
        val map = LinkedHashMap<String, Any?>()
        fun size(k: String): Any? = item.o(k)?.g("size")
        if (truthy(size("jm"))) map["master"] = size("jm")
        if (truthy(size("hr"))) map["flac24bit"] = size("hr")
        if (truthy(size("sq"))) map["flac"] = size("sq")
        if (truthy(size("h"))) map["320k"] = size("h")
        if (truthy(size("m")) && map["320k"] == null) map["320k"] = size("m")
        if (truthy(size("l"))) map["128k"] = size("l")
        if (truthy(size("je"))) map["atmos"] = size("je")
        if (truthy(size("db")) && map["atmos"] == null) map["atmos"] = size("db")
        val p = priv ?: JSONObject()
        val level = p.s("playMaxbrLevel", "downloadMaxbrLevel", "maxBrLevel", "plLevel").lowercase()
        val maxbr = jnum(p.g("maxbr")).let { if (it.isNaN()) 0.0 else it }
        if (map.isEmpty() && (maxbr > 0 || level.isNotEmpty())) {
            when {
                Regex("jymaster|master").containsMatchIn(level) -> map["master"] = true
                level.contains("hires") -> map["flac24bit"] = true
                level.contains("lossless") || maxbr >= 999000 -> map["flac"] = true
                maxbr >= 320000 || Regex("exhigh|higher").containsMatchIn(level) -> map["320k"] = true
                maxbr >= 128000 || level.contains("standard") -> map["128k"] = true
            }
        } else {
            if (Regex("jymaster|master").containsMatchIn(level) && map["master"] == null) map["master"] = true
            if (level.contains("hires") && map["flac24bit"] == null) map["flac24bit"] = true
            if (level.contains("lossless") && map["flac"] == null) map["flac"] = true
        }
        return buildTypes(map)
    }

    private fun parseMgTypes(item: JSONObject): MutableList<JSONObject> {
        val map = LinkedHashMap<String, Any?>()
        val formats = (jor(item.g("newRateFormats"), item.g("rateFormats"), item.g("newFormat"), item.g("audioFormats")) as? JSONArray).objs()
        for (type in formats) {
            val size = nn(type.g("asize"), type.g("isize"), type.g("fileSize"), type.g("size"))
            when (jos(type.g("formatType"), type.g("format")).uppercase()) {
                "PQ" -> map["128k"] = size
                "HQ" -> map["320k"] = size
                "SQ" -> map["flac"] = size
                "ZQ", "ZQ24" -> map["flac24bit"] = size
                "ZQ3D", "ATM", "ATMOS" -> map["atmos"] = size
                "MASTER", "ZQM", "SQ24" -> if (map["master"] == null) map["master"] = size
            }
        }
        return buildTypes(map)
    }

    private val TYPE_ORDER = listOf("master", "atmos_plus", "atmos", "hires", "flac24bit", "flac", "320k", "128k")

    fun withTypes(base: JSONObject, types: List<JSONObject>): JSONObject {
        val ordered = types.sortedBy { t -> TYPE_ORDER.indexOf(t.optString("type")).let { if (it < 0) 999 else it } }
        base.put("types", JSONArray(ordered))
        base.put("qualitys", JSONArray(ordered.map { it.optString("type") }.filter { it.isNotEmpty() }))
        return base
    }

    /** 给已映射歌曲挂上 types/qualitys（发现页新歌/榜单复用） */
    fun attachSongTypes(source: String, raw: JSONObject?, mapped: JSONObject, fallback: Boolean = true): JSONObject {
        val item = raw ?: JSONObject()
        var types: MutableList<JSONObject> = when (source) {
            "tx" -> parseTxTypes(item)
            "wy" -> parseWyTypes(item)
            "kw" -> parseKwTypes(item)
            "kg" -> {
                val audio = item.o("audio_info") ?: item.o("audioInfo") ?: JSONObject()
                val t = parseKgTypes(
                    obj(
                        "FileSize" to jor(audio.g("filesize"), item.g("filesize"), item.g("FileSize")),
                        "HQFileSize" to jor(audio.g("filesize_320"), item.g("filesize_320"), item.g("320filesize"), item.g("HQFileSize")),
                        "SQFileSize" to jor(audio.g("filesize_flac"), item.g("filesize_flac"), item.g("sqfilesize"), item.g("SQFileSize")),
                        "ResFileSize" to jor(audio.g("filesize_high"), item.g("filesize_high"), item.g("ResFileSize"))
                    )
                )
                val hash = jos(mapped.g("hash"), item.g("hash"), item.g("FileHash"))
                if (hash.isNotEmpty() && t.isNotEmpty()) t[0].put("hash", hash)
                t
            }
            "mg" -> parseMgTypes(item)
            else -> mutableListOf()
        }
        if (types.isEmpty() && fallback) {
            types = buildTypes(linkedMapOf("flac" to true, "320k" to true, "128k" to true))
        }
        return withTypes(mapped, types)
    }

    fun formatTime(seconds: Long): String {
        if (seconds <= 0) return ""
        val m = seconds / 60
        val s = seconds % 60
        return "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    }

    fun formatTime(v: Any?): String = formatTime(jnum(v).let { if (it.isNaN()) 0L else it.toLong() })

    fun formatPlayCount(n: Any?): String {
        val num = jnum(n)
        if (num.isNaN() || num <= 0) return ""
        fun fmt(x: Double) = String.format(Locale.US, "%.1f", x).removeSuffix(".0")
        return when {
            num >= 100000000 -> "${fmt(num / 100000000)}亿"
            num >= 10000 -> "${fmt(num / 10000)}万"
            else -> jstr(num)
        }
    }

    private fun isoDate(ms: Any?): String {
        val v = jnum(ms)
        if (v.isNaN() || v <= 0) return ""
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(v.toLong()))
    }

    fun kwPicUrl(item: JSONObject): String {
        val album = item.s("web_albumpic_short").trim()
        if (album.isNotEmpty()) return "https://img4.kuwo.cn/star/albumcover/${album.replaceFirst("120", "500")}"
        for (key in listOf("albumpic", "album_pic", "pic120", "musicPic", "pic", "img")) {
            val v = item.g(key) as? String ?: continue
            val t = v.trim()
            if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(t) && Regex("albumcover|star/album", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
                return t.replaceFirst("/120/", "/500/")
            }
        }
        val artist = item.s("web_artistpic_short").trim()
        if (artist.isNotEmpty()) return "https://img1.kuwo.cn/star/starheads/${artist.replaceFirst("120", "500")}"
        return ""
    }

    fun kgPicUrl(item: JSONObject): String {
        val img = jos(
            item.g("Image"), item.g("AlbumImage"), item.g("album_img"), item.g("imgurl"),
            item.o("album_info")?.g("sizable_cover"), item.o("album_info")?.g("imgurl"),
            item.g("cover"), item.g("img"), item.g("pic")
        )
        return img.replace("{size}", "400")
    }

    fun mgPicUrl(item: JSONObject): String {
        for (key in listOf("img3", "img2", "img1", "imgUrl", "cover")) {
            val v = item.g(key)
            if (v is String && v.isNotEmpty()) return v
        }
        val imgs = ((item.g("imgItems") ?: item.g("imgList")) as? JSONArray).objs()
        if (imgs.isNotEmpty()) {
            val best = imgs.firstOrNull { it.optString("imgSizeType") == "03" || it.optString("imgSizeType") == "02" } ?: imgs[0]
            return best.s("img", "url")
        }
        return ""
    }

    private fun txCoverUrl(item: JSONObject, albumMid: String): String {
        if (albumMid.isNotEmpty() && albumMid != "0") return "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumMid.jpg"
        val singerMid = item.arr("singer").objs().firstOrNull()?.s("mid").orEmpty()
        if (singerMid.isNotEmpty()) return "https://y.gtimg.cn/music/photo_new/T001R500x500M000$singerMid.jpg"
        return ""
    }

    fun formatTxSingers(singers: Any?): String {
        if (!truthy(singers)) return ""
        return when (singers) {
            is JSONArray -> formatArtists(singers.values().map { s ->
                when (s) {
                    null, JSONObject.NULL -> ""
                    is String -> s
                    is JSONObject -> s.s("name", "title", "singer_name", "singerName")
                    else -> jstr(s)
                }
            }.filter { it.isNotEmpty() }.joinToString("/"))
            is JSONObject -> when {
                singers.arr("singerList") != null -> formatTxSingers(singers.arr("singerList"))
                singers.arr("singers") != null -> formatTxSingers(singers.arr("singers"))
                else -> formatArtists(singers.s("name", "title", "singer_name", "singerName"))
            }
            else -> formatArtists(jstr(singers))
        }
    }

    fun mapTxSongItem(item: JSONObject): JSONObject {
        val types = parseTxTypes(item)
        val albumMid = jos(item.o("album")?.g("mid"), item.g("albummid"))
        val albumName = cleanHtml(jos(item.o("album")?.g("name"), item.g("albumname")))
        val songmid = jos(item.g("mid"), item.g("songmid"))
        val songId = when {
            !item.g("id").isNullish() && jstr(item.g("id")).isNotEmpty() -> jstr(item.g("id"))
            !item.g("songid").isNullish() && jstr(item.g("songid")).isNotEmpty() -> jstr(item.g("songid"))
            else -> songmid
        }
        val strMediaMid = jos(item.o("file")?.g("media_mid"), item.g("strMediaMid"), item.g("media_mid"))
        val img = txCoverUrl(item, albumMid)
        val singer = formatTxSingers(item.g("singer")).ifEmpty { formatTxSingers(item.g("singerList")) }
            .ifEmpty { formatTxSingers(item.g("singers")) }
            .ifEmpty { formatArtists(jos(item.g("singername"), item.g("singerName"), item.g("singer_name"), item.g("artist"))) }
        return withTypes(
            obj(
                "id" to songmid,
                "name" to cleanHtml(jos(item.g("title"), item.g("songname"), item.g("name"))),
                "singer" to singer,
                "album" to albumName,
                "albumName" to albumName,
                "interval" to formatTime(item.g("interval")),
                "source" to "tx",
                "songId" to songId,
                "songmid" to songmid,
                "strMediaMid" to strMediaMid,
                "musicId" to songId,
                "albumId" to albumMid,
                "albumMid" to albumMid,
                "albummid" to albumMid,
                "img" to img,
                "picUrl" to img
            ),
            types
        )
    }

    private fun splitCombined(raw: String): Pair<String, String>? {
        val idx = raw.indexOf(" - ")
        if (idx < 0) return null
        return raw.substring(0, idx).trim() to raw.substring(idx + 3).trim()
    }

    fun mapKgSongItem(item: JSONObject): JSONObject {
        val audio = item.o("audio_info") ?: item
        var hash = jos(audio.g("hash"), item.g("hash"), item.g("FileHash"))
        var name = jos(item.g("songname"), item.g("SongName"), item.g("name"), item.g("audio_name"), item.g("remark"))
        var singer = jos(item.g("author_name"), item.g("SingerName"), item.g("singer"))
        if (singer.isEmpty()) {
            item.arr("authors").objs().map { it.s("author_name") }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
                ?.let { singer = it.joinToString("、") }
        }
        if (singer.isEmpty()) {
            item.arr("singerinfo").objs().map { it.s("name", "author_name", "singername") }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
                ?.let { singer = it.joinToString("、") }
        }
        val filename = item.s("filename")
        if (filename.isNotEmpty() && (name.isEmpty() || singer.isEmpty())) {
            val parts = splitCombined(filename)
            if (parts != null) {
                if (singer.isEmpty()) singer = parts.first
                if (name.isEmpty() || name == filename) name = parts.second
            } else if (name.isEmpty()) {
                name = filename
            }
        }
        if (name.isNotEmpty()) {
            val parts = splitCombined(name)
            if (parts != null && parts.first.isNotEmpty() && parts.second.isNotEmpty()) {
                if (singer.isEmpty() || name.startsWith("$singer - ") || name.startsWith("${parts.first} - ")) {
                    if (singer.isEmpty()) singer = parts.first
                    name = parts.second
                }
            }
        }
        val goods = item.arr("relate_goods").objs()
        fun goodsSize(br: Int): Any? = goods.firstOrNull { jnum(it.g("bitrate")).toInt() == br }?.let { jor(it.g("size"), it.g("filesize")) } ?: 0
        fun goodsHash(br: Int): String = goods.firstOrNull { jnum(it.g("bitrate")).toInt() == br && truthy(it.g("hash")) }?.s("hash").orEmpty()
        if (hash.isEmpty()) hash = goodsHash(128).ifEmpty { goodsHash(320) }.ifEmpty { goods.firstOrNull()?.s("hash").orEmpty() }

        var types = parseKgTypes(
            obj(
                "FileSize" to jor(audio.g("filesize"), item.g("filesize"), item.g("FileSize"), goodsSize(128), item.g("size")),
                "HQFileSize" to jor(audio.g("filesize_320"), item.g("filesize_320"), item.g("320filesize"), item.g("HQFileSize"), goodsSize(320)),
                "SQFileSize" to jor(
                    audio.g("filesize_flac"), item.g("filesize_flac"), item.g("sqfilesize"), item.g("SQFileSize"),
                    goods.firstOrNull { jnum(it.g("bitrate")).let { b -> b >= 900 && b < 2000 } }?.g("size")
                ),
                "ResFileSize" to jor(
                    audio.g("filesize_high"), item.g("filesize_high"), item.g("ResFileSize"),
                    goods.firstOrNull { jnum(it.g("bitrate")) >= 2000 }?.g("size")
                ),
                "MasterFileSize" to jor(
                    audio.g("filesize_super"), item.g("filesize_super"), audio.g("filesize_master"),
                    item.g("filesize_master"), item.g("ExtFileSize"), item.g("MasterFileSize")
                )
            )
        )
        if (types.isEmpty() && hash.isNotEmpty()) types = parseKgTypes(obj("FileSize" to jor(item.g("size"), 1)))
        if (types.isNotEmpty()) {
            val h128 = goodsHash(128)
            val h320 = goodsHash(320)
            for (t in types) {
                val type = t.optString("type")
                when {
                    type == "128k" && h128.isNotEmpty() -> t.put("hash", h128)
                    type == "320k" && h320.isNotEmpty() -> t.put("hash", h320)
                    !t.has("hash") && hash.isNotEmpty() -> t.put("hash", hash)
                }
            }
        }
        val rawDuration = jor(audio.g("timelength"), item.g("timelen"), item.g("Duration"), item.g("duration"), 0)
        val d = jnum(rawDuration).let { if (it.isNaN()) 0.0 else it }
        val durationSec = if (d > 10000) (d / 1000).toLong() else d.toLong()
        val albumAudioId = jos(audio.g("audio_id"), item.g("album_audio_id"), item.g("mixsongid"), item.g("add_mixsongid"), item.g("ID"), item.g("albumAudioId"))
        val albumName = cleanHtml(
            jos(item.o("album_info")?.g("album_name"), item.o("albuminfo")?.g("name"), item.g("AlbumName"), item.g("album"), item.g("album_name"))
        )
        val pic = kgPicUrl(item)
        return withTypes(
            obj(
                "id" to hash.ifEmpty { albumAudioId },
                "name" to cleanHtml(name),
                "singer" to formatArtists(singer),
                "album" to albumName,
                "albumName" to albumName,
                "albumId" to jos(item.g("album_id"), item.g("albumid"), item.o("album_info")?.g("album_id"), item.o("albuminfo")?.g("id"), item.g("AlbumID")),
                "interval" to formatTime(durationSec),
                "source" to "kg",
                "songId" to hash.ifEmpty { albumAudioId },
                "hash" to hash,
                "albumAudioId" to albumAudioId,
                "duration" to durationSec,
                "picUrl" to pic,
                "img" to pic
            ),
            types
        )
    }

    private fun mapKgWebSearchItem(item: JSONObject): JSONObject {
        val types = parseKgTypes(item)
        val pic = kgPicUrl(item)
        return withTypes(
            obj(
                "id" to item.s("FileHash"),
                "name" to cleanHtml(item.g("SongName")),
                "singer" to formatArtists(item.g("SingerName")),
                "album" to cleanHtml(item.g("AlbumName")),
                "interval" to formatTime(jor(item.g("Duration"), 0)),
                "source" to "kg",
                "songId" to item.s("FileHash"),
                "hash" to item.s("FileHash"),
                "albumId" to item.s("AlbumID"),
                "albumAudioId" to jos(item.g("ID"), item.g("AlbumAudioID"), item.g("MixSongID")),
                "duration" to (jor(item.g("Duration"), 0) ?: 0),
                "picUrl" to pic,
                "img" to pic
            ),
            types
        )
    }

    private fun wyAlbumPic(al: JSONObject?): String {
        val url = al?.s("picUrl")?.trim().orEmpty()
        return if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) url else ""
    }

    private fun mapWyPlaylistTrack(item: JSONObject, priv: JSONObject?, pl: JSONObject?): JSONObject {
        val types = parseWyTypes(item, priv)
        val artists = (item.arr("ar") ?: item.arr("artists")).objs()
        val albumName = cleanHtml(item.o("al")?.s("name"))
        val albumArtistsSrc = ArrayList<Any?>()
        pl?.g("artist")?.let { albumArtistsSrc.add(it) }
        pl?.arr("artists")?.values()?.let { albumArtistsSrc.addAll(it) }
        val albumArtist = formatArtists(albumArtistsSrc.map { a -> if (a is String) a else (a as? JSONObject)?.s("name").orEmpty() }.filter { it.isNotEmpty() }.joinToString("/"))
        val singer = formatArtists(artists.map { it.s("name") }.filter { it.isNotEmpty() }.joinToString("/")).ifEmpty { albumArtist }
        val pic = wyAlbumPic(item.o("al"))
        val alId = item.o("al")?.g("id")
        return withTypes(
            obj(
                "id" to jstr(item.g("id")),
                "name" to cleanHtml(item.g("name")),
                "singer" to singer,
                "albumArtist" to albumArtist.ifEmpty { singer },
                "album" to albumName,
                "albumName" to albumName,
                "albumId" to if (alId != null) jstr(alId) else "",
                "interval" to formatTime((jnum(jor(item.g("dt"), 0)) / 1000).toLong()),
                "source" to "wy",
                "songId" to jstr(item.g("id")),
                "songmid" to jstr(item.g("id")),
                "picUrl" to pic,
                "img" to pic
            ),
            types
        )
    }

    private fun mapAlbumItem(id: Any?, name: Any?, artist: Any?, img: Any?, publishTime: Any?, count: Any?, source: String): JSONObject = obj(
        "id" to jstr(id),
        "name" to cleanHtml(name),
        "artist" to formatArtists(artist),
        "img" to jos(img),
        "publishTime" to jos(publishTime),
        "count" to jnum(count).let { if (it.isNaN()) 0 else it.toLong() },
        "source" to source
    )

    fun mapRecommendItem(id: Any?, name: Any?, author: Any?, img: Any?, playCount: Any?, total: Any?, desc: Any?, source: String): JSONObject = obj(
        "id" to jstr(id),
        "name" to cleanHtml(name),
        "author" to cleanHtml(jos(author)),
        "img" to jos(img),
        "play_count" to jos(playCount),
        "total" to (if (truthy(total)) total else 0),
        "desc" to cleanHtml(jos(desc)),
        "source" to source
    )

    private fun pageResult(list: List<JSONObject>, total: Long, limit: Int, minOnePage: Boolean = true): JSONObject {
        val safeTotal = if (total > 0) total else list.size.toLong()
        val pages = Math.ceil(safeTotal.toDouble() / limit).toLong()
        return obj("list" to JSONArray(list), "allPage" to if (minOnePage) maxOf(1, pages) else pages, "total" to safeTotal)
    }

    private fun emptyPage(): JSONObject = obj("list" to JSONArray(), "allPage" to 0, "total" to 0)

    // ======================================================================
    // 酷狗移动端
    // ======================================================================

    private fun parseKgTagResponse(raw: String): JSONObject? {
        var text = raw.removePrefix("﻿").trim()
        val startMark = "<!--KG_TAG_RES_START-->"
        val endMark = "<!--KG_TAG_RES_END-->"
        val start = text.indexOf(startMark)
        if (start >= 0) {
            val from = start + startMark.length
            val end = text.indexOf(endMark, from)
            text = (if (end >= 0) text.substring(from, end) else text.substring(from)).trim()
        }
        return runCatching { JSONObject(text) }.getOrElse {
            val i = text.indexOf('{')
            val j = text.lastIndexOf('}')
            if (i >= 0 && j > i) runCatching { JSONObject(text.substring(i, j + 1)) }.getOrNull() else null
        }
    }

    private suspend fun kgFetchMobileJson(url: String, retries: Int = 1): JSONObject {
        var lastErr: Throwable? = null
        for (attempt in 0..retries) {
            try {
                val data = parseKgTagResponse(CatalogHttp.get(url, KG_HEADERS))
                if (data != null) return data
                lastErr = IllegalStateException("酷狗接口返回异常")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastErr = e
            }
            if (attempt < retries) delay(120L * (attempt + 1))
        }
        throw lastErr ?: IllegalStateException("酷狗接口请求失败")
    }

    private val kgAlbumCoverCache = ConcurrentHashMap<String, String>()

    suspend fun kgResolveAlbumCover(albumId: String): String {
        val id = albumId.trim()
        if (id.isEmpty() || id == "0") return ""
        kgAlbumCoverCache[id]?.let { return it }
        var data: JSONObject? = null
        for (host in KG_MOBILE_HOSTS) {
            data = runCatching { kgFetchMobileJson("$host/api/v3/album/info?albumid=${encodeUri(id)}", 1) }.getOrNull()
            if (data?.o("data") != null) break
        }
        val img = jos(data?.o("data")?.g("imgurl"), data?.o("data")?.g("img")).replace("{size}", "400")
        kgAlbumCoverCache[id] = img
        return img
    }

    private suspend fun enrichKgSongsCoversByAlbum(songs: List<JSONObject>, concurrency: Int = 6): List<JSONObject> {
        if (songs.isEmpty()) return songs
        val needIds = songs.filter { !truthy(jor(it.g("picUrl"), it.g("img"))) && truthy(it.g("albumId")) }
            .map { jstr(it.g("albumId")).trim() }.filter { it.isNotEmpty() && it != "0" }.distinct()
        if (needIds.isNotEmpty()) {
            coroutineScope {
                needIds.chunked(concurrency).forEach { chunk ->
                    chunk.map { id -> async { runCatching { kgResolveAlbumCover(id) } } }.awaitAll()
                }
            }
        }
        return songs.map { s ->
            if (truthy(jor(s.g("picUrl"), s.g("img")))) return@map s
            val pic = s.s("albumId").trim().let { if (it.isEmpty()) "" else kgAlbumCoverCache[it].orEmpty() }
            if (pic.isEmpty()) s else s.copyObj().put("picUrl", pic).put("img", pic)
        }
    }

    private fun enrichKgListCovers(primary: List<JSONObject>, vararg coverSources: List<JSONObject>?): List<JSONObject> {
        if (primary.isEmpty()) return primary
        val coverByHash = HashMap<String, String>()
        for (list in coverSources) {
            list ?: continue
            for (song in list) {
                val h = jos(song.g("hash"), song.g("id")).lowercase()
                val pic = jos(song.g("picUrl"), song.g("img"))
                if (h.isNotEmpty() && pic.isNotEmpty() && !coverByHash.containsKey(h)) coverByHash[h] = pic
            }
        }
        if (coverByHash.isEmpty()) return primary
        return primary.map { song ->
            if (truthy(jor(song.g("picUrl"), song.g("img")))) return@map song
            val pic = coverByHash[jos(song.g("hash"), song.g("id")).lowercase()] ?: return@map song
            song.copyObj().put("picUrl", pic).put("img", pic)
        }
    }

    // ======================================================================
    // 歌曲搜索
    // ======================================================================

    private suspend fun kwSearch(keyword: String, page: Int, limit: Int): JSONObject {
        val url = "https://search.kuwo.cn/r.s?client=kt&all=${encodeUri(keyword)}&pn=${page - 1}&rn=$limit&uid=0&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&moession="
        val raw = CatalogHttp.get(url)
        val data = JSONObject(raw.replace('\'', '"'))
        val abs = data.arr("abslist") ?: return emptyPage()
        val list = abs.objs().map { item ->
            val types = parseKwTypes(item)
            val rid = item.s("MUSICRID").replace("MUSIC_", "")
            val id = rid.ifEmpty { item.s("DC_TARGETID") }
            val pic = kwPicUrl(item)
            withTypes(
                obj(
                    "id" to id,
                    "name" to cleanHtml(item.g("SONGNAME")),
                    "singer" to formatArtists(item.g("ARTIST")),
                    "album" to cleanHtml(item.g("ALBUM")),
                    "albumName" to cleanHtml(item.g("ALBUM")),
                    "albumId" to jos(item.g("ALBUMID"), item.g("albumid"), item.g("AlbumId")),
                    "interval" to formatTime(jint(item.g("DURATION"))),
                    "source" to "kw",
                    "songId" to id,
                    "musicId" to id,
                    "rid" to rid,
                    "dcTargetId" to item.s("DC_TARGETID"),
                    "picUrl" to pic,
                    "img" to pic
                ),
                types
            )
        }
        val total = jint(data.g("TOTAL"))
        return obj("list" to JSONArray(list), "allPage" to Math.ceil(total.toDouble() / limit).toLong(), "total" to total)
    }

    private suspend fun kgSearchWeb(keyword: String, page: Int, limit: Int): JSONObject? {
        val url = "https://songsearch.kugou.com/song_search_v2?keyword=${encodeUri(keyword)}&page=$page&pagesize=$limit&userid=-1&clientver=2000&platform=WebFilter&filter=2&iscorrection=1&privilege_filter=0&area_code=1"
        val data = parseJsonObj(CatalogHttp.get(url, KG_HEADERS))
        val lists = data?.o("data")?.arr("lists")?.objs().orEmpty()
        if (lists.isEmpty()) return null
        return pageResult(lists.map(::mapKgWebSearchItem), jint(data?.o("data")?.g("total")), limit)
    }

    private suspend fun kgSearchMobile(keyword: String, page: Int, limit: Int): JSONObject? {
        val url = "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=${encodeUri(keyword)}&page=$page&pagesize=$limit&showtype=1"
        val data = kgFetchMobileJson(url, 2)
        val lists = (data.o("data")?.arr("lists") ?: data.o("data")?.arr("info")).objs()
        if (jnum(data.g("status")) != 1.0 || lists.isEmpty()) return null
        val list = lists.map(::mapKgSongItem).filter { truthy(it.g("hash")) || truthy(it.g("name")) }
        if (list.isEmpty()) return null
        return pageResult(list, jint(data.o("data")?.g("total")).let { if (it > 0) it else list.size.toLong() }, limit)
    }

    private suspend fun kgSearchComplex(keyword: String, page: Int, limit: Int): JSONObject? {
        val url = "https://complexsearch.kugou.com/v2/search/song?keyword=${encodeUri(keyword)}&page=$page&pagesize=$limit&bitrate=0&isfuzzy=0&tag=em&inputtype=0&platform=WebFilter&userid=-1&clientver=2000&iscorrection=1&privilege_filter=0&filter=2&token=&appid=1014"
        val data = parseJsonObj(CatalogHttp.get(url, KG_HEADERS))
        val lists = data?.o("data")?.arr("lists")?.objs().orEmpty()
        if (lists.isEmpty()) return null
        return pageResult(lists.map(::mapKgWebSearchItem), jint(data?.o("data")?.g("total")), limit)
    }

    private suspend fun kgSearch(keyword: String, page: Int, limit: Int): JSONObject = coroutineScope {
        val mobileD = async { runCatching { kgSearchMobile(keyword, page, limit) }.getOrNull() }
        val webD = async { runCatching { kgSearchWeb(keyword, page, limit) }.getOrNull() }
        val complexD = async { runCatching { kgSearchComplex(keyword, page, limit) }.getOrNull() }
        val mobile = mobileD.await()
        val web = webD.await()
        val complex = complexD.await()
        val settled = listOf(mobile, web, complex)
        val primary = settled.firstOrNull { (it?.arr("list")?.length() ?: 0) > 0 } ?: return@coroutineScope emptyPage()
        val coverLists = settled.filter { it != null && it !== primary && (it.arr("list")?.length() ?: 0) > 0 }.map { it!!.arr("list").objs() }
        var list = enrichKgListCovers(primary.arr("list").objs(), *coverLists.toTypedArray())
        val withPic = list.count { truthy(jor(it.g("picUrl"), it.g("img"))) }
        if (withPic < minOf(3, list.size) && (web?.arr("list")?.length() ?: 0) > 0) {
            val webFilled = enrichKgListCovers(web!!.arr("list").objs(), primary.arr("list").objs(), complex?.arr("list").objs())
            if (webFilled.any { truthy(jor(it.g("picUrl"), it.g("img"))) }) {
                val albumFilled = enrichKgSongsCoversByAlbum(webFilled)
                return@coroutineScope web.copyObj().put("list", JSONArray(albumFilled))
            }
        }
        list = enrichKgSongsCoversByAlbum(list)
        primary.copyObj().put("list", JSONArray(list))
    }

    private suspend fun txH5Search(keyword: String, page: Int, limit: Int, type: Int): JSONObject {
        val url = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp?g_tk=5381&uin=0&format=json" +
            "&inCharset=utf-8&outCharset=utf-8&notice=0&platform=h5&needNewCode=1" +
            "&w=${encodeUri(keyword)}&zhidaqu=1&catZhida=1&t=$type&flag=1&ie=utf-8&sem=1&aggr=0" +
            "&perpage=$limit&n=$limit&p=$page&remoteplace=txt.mqq.all"
        val data = parseJsonObj(CatalogHttp.get(url, TX_H5_HEADERS)) ?: throw IllegalStateException("QQ音乐搜索返回异常，请稍后重试")
        if (jnum(data.g("code")) != 0.0) throw IllegalStateException("QQ音乐搜索失败(${jstr(data.g("code"))})，请稍后重试")
        return data
    }

    private suspend fun txSearch(keyword: String, page: Int, limit: Int): JSONObject {
        val data = txH5Search(keyword, page, limit, 0)
        val list = data.o("data")?.o("song")?.arr("list") ?: return emptyPage()
        val total = jint(data.o("data")?.o("song")?.g("totalnum")).let { if (it > 0) it else list.length().toLong() }
        return pageResult(list.objs().map(::mapTxSongItem), total, limit)
    }

    private suspend fun wySearch(keyword: String, page: Int, limit: Int): JSONObject {
        val offset = (page - 1) * limit
        val data = parseJsonObj(CatalogHttp.get("https://music.163.com/api/cloudsearch/pc?s=${encodeUri(keyword)}&type=1&offset=$offset&limit=$limit", WY_HEADERS))
        val songs = data?.o("result")?.arr("songs") ?: return emptyPage()
        val total = jint(data.o("result")?.g("songCount"))
        val list = songs.objs().map { item ->
            val types = parseWyTypes(item)
            val al = item.o("al") ?: item.o("album")
            withTypes(
                obj(
                    "id" to jstr(item.g("id")),
                    "name" to cleanHtml(item.g("name")),
                    "singer" to formatArtists((item.arr("ar") ?: item.arr("artists")).objs().joinToString("/") { it.s("name") }),
                    "album" to cleanHtml(al?.g("name")),
                    "albumName" to cleanHtml(al?.g("name")),
                    "albumId" to jos(al?.g("id")),
                    "interval" to formatTime((jnum(jor(item.g("dt"), item.g("duration"), 0)) / 1000).toLong()),
                    "source" to "wy",
                    "songId" to jstr(item.g("id")),
                    "picUrl" to item.o("al")?.s("picUrl").orEmpty(),
                    "img" to item.o("al")?.s("picUrl").orEmpty()
                ),
                types
            )
        }
        return obj("list" to JSONArray(list), "allPage" to Math.ceil(total.toDouble() / limit).toLong(), "total" to total)
    }

    private suspend fun mgSearch(keyword: String, page: Int, limit: Int): JSONObject {
        val url = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/search_all.do?text=${encodeUri(keyword)}&pageNo=$page&pageSize=$limit&searchSwitch=%7B%22song%22%3A1%7D"
        val data = parseJsonObj(CatalogHttp.get(url, MG_HEADERS))
        val result = data?.o("songResultData")?.arr("result") ?: return emptyPage()
        val total = jint(data.o("songResultData")?.g("totalCount"))
        val list = result.objs().map { item ->
            val types = parseMgTypes(item)
            val firstAlbum = item.arr("albums").objs().firstOrNull()
            val pic = mgPicUrl(item)
            withTypes(
                obj(
                    "id" to jos(item.g("copyrightId"), item.g("id")),
                    "name" to cleanHtml(item.g("name")),
                    "singer" to formatArtists(item.arr("singers").objs().joinToString("/") { it.s("name") }),
                    "album" to cleanHtml(firstAlbum?.g("name")),
                    "albumName" to cleanHtml(firstAlbum?.g("name")),
                    "albumId" to jos(firstAlbum?.g("id"), item.g("albumId")),
                    "interval" to "",
                    "source" to "mg",
                    "songId" to jos(item.g("copyrightId"), item.g("id")),
                    "copyrightId" to item.s("copyrightId"),
                    "picUrl" to pic,
                    "img" to pic
                ),
                types
            )
        }
        return obj("list" to JSONArray(list), "allPage" to Math.ceil(total.toDouble() / limit).toLong(), "total" to total)
    }

    suspend fun searchMusic(keyword: String, source: String, page: Int = 1, limit: Int = 30): JSONObject = when (source) {
        "kw" -> kwSearch(keyword, page, limit)
        "kg" -> kgSearch(keyword, page, limit)
        "tx" -> txSearch(keyword, page, limit)
        "wy" -> wySearch(keyword, page, limit)
        "mg" -> mgSearch(keyword, page, limit)
        else -> throw IllegalArgumentException("不支持的搜索源: $source")
    }

    // ======================================================================
    // 专辑搜索
    // ======================================================================

    private suspend fun wyAlbumSearch(keyword: String, page: Int, limit: Int): JSONObject {
        val offset = (page - 1) * limit
        val data = parseJsonObj(CatalogHttp.get("https://music.163.com/api/cloudsearch/pc?s=${encodeUri(keyword)}&type=10&offset=$offset&limit=$limit", WY_HEADERS))
        val albums = data?.o("result")?.arr("albums") ?: return emptyPage()
        val total = jint(data.o("result")?.g("albumCount"))
        return pageResult(albums.objs().map { item ->
            mapAlbumItem(
                item.g("id"), item.g("name"), item.arr("artists").objs().joinToString("/") { it.s("name") },
                item.g("picUrl"), isoDate(item.g("publishTime")), item.g("size"), "wy"
            )
        }, total, limit)
    }

    private suspend fun txAlbumSearch(keyword: String, page: Int, limit: Int): JSONObject {
        val data = txH5Search(keyword, page, limit, 8)
        val list = data.o("data")?.o("album")?.arr("list")?.objs().orEmpty()
        if (list.isEmpty()) return emptyPage()
        val total = jint(data.o("data")?.o("album")?.g("totalnum")).let { if (it > 0) it else list.size.toLong() }
        return pageResult(list.map { item ->
            val albumMid = item.s("albumMID", "albummid", "albumId")
            mapAlbumItem(
                jor(albumMid, item.g("albumID"), item.g("albumid")),
                jor(item.g("albumName"), item.g("albumname"), item.g("name")),
                item.s("singerName", "singer", "singername"),
                jor(item.g("albumPic"), if (albumMid.isNotEmpty()) "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg" else ""),
                item.s("publicTime"),
                jor(item.g("song_count"), item.g("songnum"), 0),
                "tx"
            )
        }, total, limit)
    }

    private suspend fun kwAlbumSearch(keyword: String, page: Int, limit: Int): JSONObject {
        val url = "https://search.kuwo.cn/r.s?client=kt&all=${encodeUri(keyword)}&pn=${page - 1}&rn=$limit&uid=0&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1&ft=album&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&moession="
        val data = parseKuwoBody(CatalogHttp.get(url))
        val list = (data.arr("albumlist") ?: data.arr("abslist")).objs()
        if (list.isEmpty()) return emptyPage()
        val total = jint(jor(data.g("total"), data.g("TOTAL")))
        return pageResult(list.map { item ->
            val pic = jos(item.g("img"), item.g("hts_img")).ifEmpty { if (truthy(item.g("pic"))) "https://img4.kuwo.cn/star/albumcover/500/${item.s("pic")}" else "" }
            mapAlbumItem(
                jor(item.g("albumid"), item.g("ALBUMID"), item.g("id"), item.g("DC_TARGETID"), ""),
                jor(item.g("name"), item.g("NAME"), item.g("ALBUM"), item.g("DISPALBUMNAME")),
                jor(item.g("artist"), item.g("ARTIST"), item.g("fartist")),
                pic,
                item.s("pub", "RELEASEDATE", "releaseDate"),
                jor(item.g("musiccnt"), item.g("SONGNUM"), item.g("songnum"), 0),
                "kw"
            )
        }, total, limit)
    }

    private suspend fun kgAlbumSearch(keyword: String, page: Int, limit: Int): JSONObject {
        val data = kgFetchMobileJson("http://mobilecdn.kugou.com/api/v3/search/album?format=json&keyword=${encodeUri(keyword)}&page=$page&pagesize=$limit&showtype=1", 2)
        val info = data.o("data")?.arr("info")?.objs().orEmpty()
        if (jnum(data.g("status")) != 1.0 || info.isEmpty()) return emptyPage()
        val total = jint(data.o("data")?.g("total")).let { if (it > 0) it else info.size.toLong() }
        return pageResult(info.map { item ->
            mapAlbumItem(
                jor(item.g("albumid"), item.g("album_id")), jor(item.g("albumname"), item.g("album_name")),
                jor(item.g("singername"), item.g("author_name")), item.s("imgurl", "img").replaceFirst("{size}", "400"),
                item.s("publish_date", "publishtime"), jor(item.g("songcount"), item.g("song_count"), 0), "kg"
            )
        }, total, limit)
    }

    private suspend fun mgAlbumSearch(keyword: String, page: Int, limit: Int): JSONObject {
        val url = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/search_all.do?text=${encodeUri(keyword)}&pageNo=$page&pageSize=$limit&searchSwitch=%7B%22album%22%3A1%7D"
        val data = parseJsonObj(CatalogHttp.get(url, MG_HEADERS))
        val result = data?.o("albumResultData")?.arr("result") ?: return emptyPage()
        val total = jint(data.o("albumResultData")?.g("totalCount"))
        val mapped = result.objs().map { item ->
            mapAlbumItem(
                jor(item.g("id"), item.g("copyrightId")), item.g("name"),
                jor(item.g("singer"), item.arr("singers").objs().joinToString("/") { it.s("name") }),
                jos(item.arr("imgItems").objs().firstOrNull()?.g("img"), item.g("img")),
                item.s("publishDate", "publishTime"), jor(item.g("songCount"), item.g("totalCount"), 0), "mg"
            )
        }
        val list = mapped.filter { it.optString("id").length < 14 }.take(limit)
        return pageResult(list.ifEmpty { mapped.take(limit) }, total, limit)
    }

    suspend fun searchAlbums(keyword: String, source: String, page: Int = 1, limit: Int = 30): JSONObject = when (source) {
        "wy" -> wyAlbumSearch(keyword, page, limit)
        "tx" -> txAlbumSearch(keyword, page, limit)
        "kw" -> kwAlbumSearch(keyword, page, limit)
        "kg" -> kgAlbumSearch(keyword, page, limit)
        "mg" -> mgAlbumSearch(keyword, page, limit)
        else -> throw IllegalArgumentException("不支持的搜索源: $source")
    }

    // ======================================================================
    // 歌单搜索
    // ======================================================================

    private suspend fun wyPlaylistSearch(keyword: String, page: Int, limit: Int): JSONObject {
        val offset = (page - 1) * limit
        val data = parseJsonObj(CatalogHttp.get("https://music.163.com/api/cloudsearch/pc?s=${encodeUri(keyword)}&type=1000&offset=$offset&limit=$limit", WY_HEADERS))
        val playlists = data?.o("result")?.arr("playlists")?.objs().orEmpty()
        if (playlists.isEmpty()) return emptyPage()
        val total = jint(data?.o("result")?.g("playlistCount")).let { if (it > 0) it else playlists.size.toLong() }
        return pageResult(playlists.map { item ->
            mapRecommendItem(item.g("id"), item.g("name"), item.o("creator")?.g("nickname"), item.g("coverImgUrl"),
                formatPlayCount(item.g("playCount")), item.g("trackCount"), item.g("description"), "wy")
        }, total, limit)
    }

    private suspend fun txPlaylistSearch(keyword: String, page: Int, limit: Int): JSONObject {
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_music_search_songlist" +
            "?remoteplace=txt.yqq.center&searchid=${System.currentTimeMillis()}&page_no=$page&num_per_page=$limit" +
            "&query=${encodeUri(keyword)}&format=json&outCharset=utf-8"
        val data = parseJsonObj(CatalogHttp.get(url, mapOf("Referer" to "https://y.qq.com/", "User-Agent" to UA_PC)))
        if (data == null || jnum(data.g("code")) != 0.0) throw IllegalStateException("QQ音乐歌单搜索失败，请稍后重试")
        val list = data.o("data")?.arr("list")?.objs().orEmpty()
        if (list.isEmpty()) return emptyPage()
        val total = jint(jor(data.o("data")?.g("display_num"), data.o("data")?.g("sum"))).let { if (it > 0) it else list.size.toLong() }
        return pageResult(list.map { item ->
            mapRecommendItem(
                jor(item.g("dissid"), item.g("tid"), item.g("id")),
                cleanHtml(jos(item.g("dissname"), item.g("title"), item.g("name"))),
                jos(item.o("creator")?.g("name"), item.g("nickname"), item.g("creator_name")),
                jos(item.g("imgurl"), item.g("cover_url_medium"), item.g("logo")),
                formatPlayCount(jor(item.g("listennum"), item.g("access_num"), item.g("play_count"))),
                jor(item.g("song_count"), item.g("songnum"), item.g("total"), 0),
                cleanHtml(jos(item.g("introduction"), item.g("desc"))),
                "tx"
            )
        }, total, limit)
    }

    private suspend fun kwPlaylistSearch(keyword: String, page: Int, limit: Int): JSONObject {
        val url = "https://search.kuwo.cn/r.s?client=kt&all=${encodeUri(keyword)}&pn=${page - 1}&rn=$limit&uid=0&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1&ft=playlist&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&moession="
        val data = parseKuwoBody(CatalogHttp.get(url))
        val list = (data.arr("abslist") ?: data.arr("playlist") ?: data.arr("list")).objs()
        if (list.isEmpty()) return emptyPage()
        val total = jint(jor(data.g("total"), data.g("TOTAL"))).let { if (it > 0) it else list.size.toLong() }
        return pageResult(list.mapNotNull { item ->
            val pid = item.s("playlistid", "PLAYLISTID", "id", "DC_TARGETID")
            val digest = jos(item.g("digest"), item.g("DIGEST")).ifEmpty { "8" }
            if (pid.isEmpty()) return@mapNotNull null
            mapRecommendItem(
                "digest-${digest}__$pid",
                jor(item.g("name"), item.g("NAME"), item.g("PLAYLISTNAME"), item.g("DISSNAME")),
                item.s("uname", "NICK", "artist", "ARTIST"),
                item.s("img", "hts_img", "pic"),
                formatPlayCount(jor(item.g("playcnt"), item.g("PLAYCNT"), item.g("listencnt"))),
                jor(item.g("musiccnt"), item.g("SONGNUM"), item.g("songnum"), item.g("total"), 0),
                item.s("info", "INFO", "desc"),
                "kw"
            )
        }, total, limit)
    }

    private suspend fun kgPlaylistSearch(keyword: String, page: Int, limit: Int): JSONObject {
        val data = kgFetchMobileJson("http://mobilecdn.kugou.com/api/v3/search/special?format=json&keyword=${encodeUri(keyword)}&page=$page&pagesize=$limit&showtype=1", 2)
        val info = data.o("data")?.arr("info")?.objs().orEmpty()
        if (jnum(data.g("status")) != 1.0 || info.isEmpty()) return emptyPage()
        val total = jint(data.o("data")?.g("total")).let { if (it > 0) it else info.size.toLong() }
        return pageResult(info.map { item ->
            mapRecommendItem(
                "id_${jos(item.g("specialid"), item.g("special_id"), item.g("gid"))}",
                jor(item.g("specialname"), item.g("special_name"), item.g("intro")),
                item.s("nickname", "singername", "username"),
                item.s("imgurl", "img").replace("{size}", "400"),
                formatPlayCount(jor(item.g("playcount"), item.g("play_count"), item.g("total_play_count"))),
                jor(item.g("songcount"), item.g("song_count"), 0),
                item.s("intro", "description"),
                "kg"
            )
        }.filter { it.optString("id").let { id -> id.isNotEmpty() && id != "id_" } }, total, limit)
    }

    private suspend fun mgPlaylistSearch(keyword: String, page: Int, limit: Int): JSONObject {
        val url = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/search_all.do?text=${encodeUri(keyword)}&pageNo=$page&pageSize=$limit&searchSwitch=%7B%22songList%22%3A1%7D"
        val data = parseJsonObj(CatalogHttp.get(url, MG_HEADERS))
        val holder = data?.o("songListResultData") ?: data?.o("playlistResultData") ?: data?.o("songlistResultData")
        val result = holder?.arr("result")?.objs().orEmpty()
        if (result.isEmpty()) return emptyPage()
        val total = jint(holder?.g("totalCount")).let { if (it > 0) it else result.size.toLong() }
        return pageResult(result.map { item ->
            mapRecommendItem(
                jor(item.g("id"), item.g("musicListId"), item.g("playlistId"), item.g("copyrightId")),
                jor(item.g("name"), item.g("title")),
                item.s("ownerName", "userName", "createName", "singer"),
                jos(item.arr("imgItems").objs().firstOrNull()?.g("img"), item.g("img"), item.g("image")),
                formatPlayCount(jor(item.g("playCount"), item.g("playNum"), item.g("keepNum"))),
                jor(item.g("musicNum"), item.g("songCount"), item.g("totalCount"), 0),
                item.s("summary", "intro"),
                "mg"
            )
        }.filter { it.optString("id").isNotEmpty() }, total, limit)
    }

    suspend fun searchPlaylists(keyword: String, source: String, page: Int = 1, limit: Int = 30): JSONObject = when (source) {
        "wy" -> wyPlaylistSearch(keyword, page, limit)
        "tx" -> txPlaylistSearch(keyword, page, limit)
        "kw" -> kwPlaylistSearch(keyword, page, limit)
        "kg" -> kgPlaylistSearch(keyword, page, limit)
        "mg" -> mgPlaylistSearch(keyword, page, limit)
        else -> throw IllegalArgumentException("不支持的搜索源: $source")
    }

    // ======================================================================
    // 专辑详情
    // ======================================================================

    private fun formatWyAlbumArtists(artist: Any?, artists: JSONArray?): String {
        if (artists != null && artists.length() > 0) return formatArtists(artists.objs().joinToString("/") { it.s("name") })
        if (artist is JSONArray && artist.length() > 0) return formatArtists(artist.objs().joinToString("/") { it.s("name") })
        if (artist is JSONObject) return formatArtists(artist.s("name"))
        return ""
    }

    private suspend fun wyAlbum(id: String): JSONObject {
        var data = parseJsonObj(CatalogHttp.get("https://music.163.com/api/album/$id?ext=true", WY_HEADERS))
        if (jnum(data?.g("code")) != 200.0 || data?.o("album") == null) {
            data = parseJsonObj(CatalogHttp.get("https://music.163.com/api/v1/album/$id", WY_HEADERS))
        }
        if (jnum(data?.g("code")) != 200.0 || data?.o("album") == null) throw IllegalStateException("无法获取网易云专辑")
        val album = data!!.o("album")!!
        if ((album.arr("songs")?.length() ?: 0) == 0 && jnum(album.g("size")) > 0) {
            val ext = parseJsonObj(CatalogHttp.get("https://music.163.com/api/album/$id?ext=true", WY_HEADERS))
            ext?.o("album")?.arr("songs")?.takeIf { it.length() > 0 }?.let { album.put("songs", it) }
        }
        val privMap = data.arr("privileges").objs().associateBy { jstr(it.g("id")) }
        var list = album.arr("songs").objs().map { mapWyPlaylistTrack(it, privMap[jstr(it.g("id"))], album) }
        if (list.isEmpty() && truthy(album.g("name"))) {
            val searched = wySearch(album.s("name"), 1, 100)
            val albumName = cleanHtml(album.g("name"))
            list = searched.arr("list").objs().filter { cleanHtml(jor(it.g("album"), it.g("albumName"))) == albumName }
        }
        if (list.isEmpty()) throw IllegalStateException("无法获取网易云专辑歌曲")
        return obj(
            "list" to JSONArray(list),
            "total" to (jint(album.g("size")).takeIf { it > 0 } ?: list.size.toLong()),
            "source" to "wy",
            "info" to obj(
                "name" to cleanHtml(album.g("name")),
                "img" to album.s("picUrl"),
                "desc" to cleanHtml(album.s("description")),
                "author" to formatWyAlbumArtists(album.g("artist"), album.arr("artists")),
                "publishTime" to isoDate(album.g("publishTime")),
                "genre" to cleanHtml(jos(album.g("tags"), album.g("subType"))),
                "language" to cleanHtml(album.s("language"))
            )
        )
    }

    private fun pickTxGenreText(basic: JSONObject): String {
        val raw = nn(basic.g("genreNew"), basic.g("genre"), basic.g("genre_name"), basic.g("tags"), basic.g("tagList"))
        return when (raw) {
            null -> ""
            is String -> raw
            is Number -> jstr(raw)
            is JSONArray -> raw.values().map { item ->
                when (item) {
                    is String -> item
                    is JSONObject -> item.s("name", "tagName", "title")
                    else -> ""
                }
            }.filter { it.isNotEmpty() }.joinToString("/")
            is JSONObject -> raw.s("name", "tagName", "title")
            else -> ""
        }
    }

    private suspend fun txAlbum(id: String): JSONObject {
        val albumMid = id
        val albumID: Any = if (Regex("^\\d+$").matches(albumMid)) albumMid.toLong() else 0
        val pageSize = 100
        var begin = 0
        var detail: JSONObject? = null
        var total = 0L
        val list = ArrayList<JSONObject>()
        while (true) {
            val payload = obj(
                "comm" to obj("cv" to 1602, "ct" to 20),
                "detail" to obj(
                    "method" to "GetAlbumDetail",
                    "param" to obj("albumMid" to albumMid, "albumID" to albumID),
                    "module" to "music.musichallAlbum.AlbumInfoServer"
                ),
                "songs" to obj(
                    "method" to "GetAlbumSongList",
                    "param" to obj("albumMid" to albumMid, "begin" to begin, "num" to pageSize, "order" to 1),
                    "module" to "music.musichallAlbum.AlbumSongList"
                )
            )
            val data = parseJsonObj(CatalogHttp.get("https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data=${encodeUri(payload.toString())}"))
            if (jnum(data?.g("code")) != 0.0) throw IllegalStateException("无法获取 QQ 音乐专辑")
            if (detail == null) detail = data?.o("detail")?.o("data") ?: JSONObject()
            val songData = data?.o("songs")?.o("data") ?: JSONObject()
            total = jint(songData.g("totalNum")).takeIf { it > 0 } ?: total
            val batch = songData.arr("songList").objs().map { entry -> mapTxSongItem(entry.o("songInfo") ?: entry) }
                .filter { truthy(it.g("name")) || truthy(it.g("id")) }
            list.addAll(batch)
            if (batch.isEmpty() || list.size >= total || batch.size < pageSize) break
            begin += pageSize
        }
        if (list.isEmpty()) throw IllegalStateException("无法获取 QQ 音乐专辑")
        val d = detail ?: JSONObject()
        val basic = d.o("basicInfo") ?: d
        val author = formatTxSingers(jor(d.g("singer"), basic.g("singer")))
            .ifEmpty { formatTxSingers(jor(d.g("singerList"), basic.g("singerList"))) }
            .ifEmpty { formatTxSingers(jor(d.g("singers"), basic.g("singers"))) }
            .ifEmpty { formatArtists(basic.s("singername", "singerName", "singer_name")) }
        val filled = list.map { track -> if (track.s("singer").trim().isNotEmpty() || author.isEmpty()) track else track.put("singer", author) }
        return obj(
            "list" to JSONArray(filled),
            "total" to (if (total > 0) total else filled.size.toLong()),
            "source" to "tx",
            "info" to obj(
                "name" to cleanHtml(jos(basic.g("name"), basic.g("title"), filled.firstOrNull()?.g("album"))),
                "img" to jos(basic.g("pic"), basic.g("picUrl"), filled.firstOrNull()?.g("img")),
                "desc" to cleanHtml(jos(d.g("desc"), d.g("description"), basic.g("desc"))),
                "author" to author,
                "publishTime" to basic.s("publishDate", "aDate", "pubTime", "time_public"),
                "genre" to cleanHtml(pickTxGenreText(basic)),
                "language" to cleanHtml(basic.s("language", "lan")),
                "albumType" to cleanHtml(basic.s("albumType", "type"))
            )
        )
    }

    private fun mapKwListItem(item: JSONObject, albumFallback: String = "", picFallback: String = "", albumId: String? = null): JSONObject {
        val types = parseKwTypes(item)
        val musicRid = jos(item.g("musicrid"), item.g("MUSICRID")).replace(Regex("^MUSIC_", RegexOption.IGNORE_CASE), "")
        val dcTargetId = jos(item.g("dcTargetId"), item.g("DC_TARGETID"), item.g("audiosourceid"))
        val songId = jos(item.g("id"), musicRid, dcTargetId)
        val albumName = cleanHtml(jos(item.g("album"), item.g("ALBUM"), albumFallback))
        val pic = kwPicUrl(item).ifEmpty { picFallback }
        return withTypes(
            obj(
                "id" to songId,
                "name" to cleanHtml(jor(item.g("name"), item.g("SONGNAME"))),
                "singer" to formatArtists(jor(item.g("artist"), item.g("ARTIST"))),
                "album" to albumName,
                "albumName" to albumName,
                "interval" to formatTime(jint(item.g("duration")).takeIf { it > 0 } ?: jint(item.g("DURATION"))),
                "source" to "kw",
                "songId" to songId,
                "songmid" to songId,
                "musicId" to songId,
                "rid" to musicRid.ifEmpty { songId },
                "dcTargetId" to dcTargetId,
                "albumId" to (albumId ?: item.s("albumid")),
                "picUrl" to pic,
                "img" to pic
            ),
            types
        )
    }

    private suspend fun kwAlbum(id: String): JSONObject {
        val infoData = runCatching { parseJsonObj(CatalogHttp.get("http://wapi.kuwo.cn/api/www/album/albumInfo?albumId=$id")) }.getOrNull()
        val meta = infoData?.o("data") ?: JSONObject()
        val attempts = listOf(
            "http://search.kuwo.cn/r.s?client=kt&pn=0&rn=1000&uid=0&ver=kwplayer_ar_9.2.2.1&vipver=1&ft=music&albumid=$id&encoding=utf8&rformat=json&show_copyright_off=1&newver=1",
            "http://search.kuwo.cn/r.s?client=kt&rformat=json&encoding=utf8&pcmp4=1&vipver=1&newver=1&pn=0&rn=1000&albumid=$id&ft=album"
        )
        val settled = coroutineScope {
            attempts.map { url ->
                async {
                    runCatching {
                        val data = parseKuwoBody(CatalogHttp.get(url))
                        (data.arr("musiclist") ?: data.arr("abslist")).objs()
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
        }
        val musiclist = settled.firstOrNull { it.isNotEmpty() }.orEmpty()
        var list: List<JSONObject> = emptyList()
        if (musiclist.isNotEmpty()) {
            list = musiclist.map { mapKwListItem(it, jos(meta.g("album"), meta.g("name")), meta.s("pic"), id) }
        } else if (truthy(meta.g("album"))) {
            val searched = kwSearch(meta.s("album"), 1, 100)
            val albumName = cleanHtml(meta.g("album"))
            list = searched.arr("list").objs().filter { cleanHtml(jor(it.g("album"), it.g("albumName"))) == albumName }
        }
        if (list.isEmpty()) throw IllegalStateException("无法获取酷我专辑")
        val author = formatArtists(jos(meta.g("artist"), meta.g("ARTIST")))
        val filled = list.map { t -> if (t.s("singer").trim().isNotEmpty() || author.isEmpty()) t else t.put("singer", author) }
        return obj(
            "list" to JSONArray(filled),
            "total" to (jint(meta.g("total")).takeIf { it > 0 } ?: filled.size.toLong()),
            "source" to "kw",
            "info" to obj(
                "name" to cleanHtml(jos(meta.g("album"), meta.g("name"))),
                "img" to meta.s("pic"),
                "desc" to cleanHtml(meta.s("albuminfo", "info", "desc")),
                "author" to author,
                "publishTime" to meta.s("releaseDate", "pub", "RELEASEDATE"),
                "genre" to cleanHtml(meta.s("lang", "content_type"))
            )
        )
    }

    private suspend fun kgAlbum(id: String): JSONObject {
        val infoData = runCatching { kgFetchMobileJson("http://mobilecdn.kugou.com/api/v3/album/info?albumid=$id", 2) }.getOrNull()
        val albumInfo = infoData?.o("data") ?: JSONObject()
        val all = ArrayList<JSONObject>()
        var page = 1
        var total = 0L
        while (true) {
            val data = kgFetchMobileJson("http://mobilecdn.kugou.com/api/v3/album/song?albumid=$id&page=$page&pagesize=300&version=9108", 2)
            if (jnum(data.g("status")) != 1.0) break
            total = jint(data.o("data")?.g("total")).takeIf { it > 0 } ?: total
            val batch = data.o("data")?.arr("info").objs().map(::mapKgSongItem).filter { truthy(it.g("hash")) || truthy(it.g("name")) }
            all.addAll(batch)
            if (batch.isEmpty() || all.size >= total) break
            page += 1
        }
        if (all.isEmpty()) throw IllegalStateException("无法获取酷狗专辑")
        val albumCover = albumInfo.s("imgurl", "img").replace("{size}", "400")
        val author = formatArtists(albumInfo.s("singername", "author_name"))
        var list: List<JSONObject> = if (albumCover.isNotEmpty()) all.map { s -> if (truthy(jor(s.g("picUrl"), s.g("img")))) s else s.put("picUrl", albumCover).put("img", albumCover) } else all
        if (author.isNotEmpty()) list = list.map { t -> if (t.s("singer").trim().isNotEmpty()) t else t.put("singer", author) }
        return obj(
            "list" to JSONArray(list),
            "total" to (if (total > 0) total else list.size.toLong()),
            "source" to "kg",
            "info" to obj(
                "name" to cleanHtml(albumInfo.s("albumname", "name")),
                "img" to albumCover,
                "desc" to cleanHtml(albumInfo.s("intro", "description")),
                "author" to author,
                "publishTime" to albumInfo.s("publishtime", "publish_date"),
                "genre" to cleanHtml(albumInfo.s("language", "type", "genre"))
            )
        )
    }

    private fun mapMgSong(item: JSONObject, albumName: String, albumImg: String, nameKeys: Array<String>, singerKeys: Array<String>): JSONObject {
        val types = parseMgTypes(item)
        val img = jos(item.g("img3"), item.g("img2"), item.g("img1")).ifEmpty { albumImg }
        val singers = singerKeys.asSequence().mapNotNull { item.arr(it) }.firstOrNull().objs()
        return withTypes(
            obj(
                "id" to jos(item.g("copyrightId"), item.g("songId"), item.g("id")),
                "name" to cleanHtml(item.s(*nameKeys)),
                "singer" to formatArtists(singers.joinToString("/") { it.s("name") }),
                "album" to cleanHtml(jos(item.g("album"), albumName)),
                "albumName" to cleanHtml(jos(item.g("album"), albumName)),
                "interval" to formatTime(jor(item.g("duration"), 0)),
                "source" to "mg",
                "songId" to jos(item.g("copyrightId"), item.g("songId"), item.g("id")),
                "copyrightId" to item.s("copyrightId"),
                "picUrl" to img,
                "img" to img
            ),
            types
        )
    }

    private suspend fun mgAlbum(id: String): JSONObject {
        val headers = mapOf("Referer" to "https://m.music.migu.cn/", "User-Agent" to UA_IPHONE, "channel" to "0146951")
        val endpoints = listOf(
            { albumId: String, pageNo: Int -> "https://app.c.nf.migu.cn/MIGUM3.0/resource/music/album/song/v2.0?albumId=$albumId&pageNo=$pageNo&pageSize=50" },
            { albumId: String, pageNo: Int -> "https://c.musicapp.migu.cn/MIGUM3.0/resource/music/album/song/v2.0?albumId=$albumId&pageNo=$pageNo&pageSize=50" }
        )
        val all = ArrayList<JSONObject>()
        var total = 0L
        for (build in endpoints) {
            all.clear()
            var page = 1
            total = 0L
            while (true) {
                val data = parseJsonObj(runCatching { CatalogHttp.get(build(id, page), headers) }.getOrNull())
                val code = data?.g("code")
                if (code != "000000" && jnum(code) != 0.0) break
                total = jint(data?.o("data")?.g("totalCount")).takeIf { it > 0 } ?: total
                val batch = (data?.o("data")?.arr("songList") ?: data?.o("data")?.arr("songs")).objs()
                    .map { mapMgSong(it, "", "", arrayOf("songName", "name"), arrayOf("singerList", "singers")) }
                all.addAll(batch)
                if (batch.isEmpty() || all.size >= total) break
                page += 1
            }
            if (all.isNotEmpty()) break
        }
        if (all.isEmpty()) throw IllegalStateException("暂无法获取咪咕专辑歌曲，请尝试其他平台")
        val infoData = parseJsonObj(runCatching { CatalogHttp.get("https://c.musicapp.migu.cn/MIGUM3.0/resource/info/albumInfo?albumId=$id", headers) }.getOrNull())
        val albumInfo = infoData?.o("data") ?: JSONObject()
        return obj(
            "list" to JSONArray(all),
            "total" to (if (total > 0) total else all.size.toLong()),
            "source" to "mg",
            "info" to obj(
                "name" to cleanHtml(jos(albumInfo.g("name"), albumInfo.g("title"), all.firstOrNull()?.g("album"))),
                "img" to jos(albumInfo.g("img"), albumInfo.arr("imgItems").objs().firstOrNull()?.g("img"), all.firstOrNull()?.g("img")),
                "desc" to cleanHtml(albumInfo.s("summary", "desc")),
                "author" to formatArtists(albumInfo.arr("singers").objs().joinToString("/") { it.s("name") }.ifEmpty { albumInfo.s("singer") }),
                "publishTime" to albumInfo.s("publishTime", "publishDate"),
                "genre" to cleanHtml(albumInfo.s("genre", "language", "type"))
            )
        )
    }

    suspend fun fetchAlbum(source: String, id: String): JSONObject = when (source) {
        "wy" -> wyAlbum(id)
        "tx" -> txAlbum(id)
        "kw" -> kwAlbum(id)
        "kg" -> kgAlbum(id)
        "mg" -> mgAlbum(id)
        else -> throw IllegalArgumentException("不支持的平台: $source")
    }

    // ======================================================================
    // 歌单详情
    // ======================================================================

    private data class PlaylistInput(
        val id: String? = null,
        val token: String = "",
        val digestId: String? = null,
        val globalCollectionId: String? = null,
        val encodeGcid: String? = null,
        val shareQuery: Map<String, String> = emptyMap()
    )

    private fun extractKgShareUrl(raw: String): String {
        val input = raw.trim()
        val m = Regex("https?://[^\\s<>\"'（）()]+", RegexOption.IGNORE_CASE).find(input)?.value ?: return input
        return m.replace(Regex("[，。；、！]+$"), "")
    }

    private fun kgShareQueryFromInput(raw: String): Map<String, String> = runCatching {
        val uri = android.net.Uri.parse(extractKgShareUrl(raw))
        mapOf(
            "srcCid" to (uri.getQueryParameter("src_cid") ?: uri.getQueryParameter("srcCid") ?: ""),
            "uid" to (uri.getQueryParameter("uid") ?: uri.getQueryParameter("u") ?: ""),
            "iszlist" to (uri.getQueryParameter("iszlist") ?: ""),
            "chl" to (uri.getQueryParameter("chl") ?: "")
        )
    }.getOrDefault(emptyMap())

    private fun parsePlaylistInput(source: String, raw: String): PlaylistInput {
        val input = raw.trim()
        if (input.isEmpty()) throw IllegalArgumentException("请输入歌单链接或 ID")
        var token = ""
        var body = input
        if (input.contains("###")) {
            val idx = input.indexOf("###")
            body = input.substring(0, idx).trim()
            token = input.substring(idx + 3).trim()
        }
        fun idFromUrl(vararg patterns: Regex): String? {
            for (re in patterns) re.find(body)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { return it }
            return null
        }
        return when (source) {
            "wy" -> PlaylistInput(id = idFromUrl(Regex("(?:\\?|&)id=(\\d+)"), Regex("/playlist/(\\d+)"), Regex("^(\\d+)$"))
                ?: throw IllegalArgumentException("无法解析网易云歌单，请粘贴完整链接或歌单 ID"), token = token)
            "tx" -> PlaylistInput(id = idFromUrl(Regex("/playlist/(\\d+)"), Regex("[?&]id=(\\d+)"), Regex("^(\\d+)$"))
                ?: throw IllegalArgumentException("无法解析 QQ 音乐歌单，请粘贴完整链接或歌单 ID"))
            "kw" -> if (Regex("^digest-\\d+__\\d+$").matches(body)) PlaylistInput(digestId = body)
                else PlaylistInput(id = idFromUrl(Regex("/playlist(?:_detail)?/(\\d+)"), Regex("[?&]pid=(\\d+)"), Regex("^(\\d+)$"))
                    ?: throw IllegalArgumentException("无法解析酷我歌单，请粘贴完整链接或歌单 ID"))
            "mg" -> PlaylistInput(id = idFromUrl(Regex("/playlist/(\\d+)"), Regex("[?&](?:playlistId|id)=(\\d+)"), Regex("^(\\d+)$"))
                ?: throw IllegalArgumentException("无法解析咪咕歌单，请粘贴完整链接或歌单 ID"))
            "kg" -> {
                val collectionId = Regex("global_collection_id=([a-zA-Z0-9_]+)").find(body)?.groupValues?.get(1)
                    ?: Regex("global_specialid=([a-zA-Z0-9_]+)").find(body)?.groupValues?.get(1)
                    ?: Regex("(collection_[a-zA-Z0-9_]+)").find(body)?.groupValues?.get(1)
                if (collectionId?.startsWith("collection_") == true) return PlaylistInput(globalCollectionId = collectionId)
                val gcid = Regex("(gcid_[a-zA-Z0-9_]+)").find(body)?.groupValues?.get(1)
                if (gcid != null) return PlaylistInput(encodeGcid = gcid, shareQuery = kgShareQueryFromInput(body))
                if (Regex("^id_\\d+$").matches(body)) return PlaylistInput(id = body.removePrefix("id_"))
                val id = idFromUrl(Regex("/special/single/(\\d+)"), Regex("/songlist/(\\d+)"), Regex("^(\\d+)$"))
                    ?: throw IllegalArgumentException("无法解析酷狗歌单，请使用歌单分享链接或数字 ID")
                PlaylistInput(id = id)
            }
            else -> throw IllegalArgumentException("不支持的平台: $source")
        }
    }

    private fun coversMatch(a: String, b: String): Boolean {
        fun norm(s: String) = s.replace("{size}", "400").replace(Regex("^https?:", RegexOption.IGNORE_CASE), "").substringBefore('?').trim()
        val na = norm(a)
        val nb = norm(b)
        return na.isNotEmpty() && nb.isNotEmpty() && na == nb
    }

    private fun clearMatchingTrackCovers(list: List<JSONObject>, playlistCover: String): List<JSONObject> {
        if (playlistCover.isEmpty()) return list
        return list.map { s ->
            val pic = jos(s.g("picUrl"), s.g("img"))
            if (!coversMatch(pic, playlistCover)) s else s.copyObj().put("picUrl", "").put("img", "")
        }
    }

    private fun finalizePlaylistInfo(info: JSONObject?, list: List<JSONObject>): JSONObject {
        val base = info?.copyObj() ?: obj("name" to "", "img" to "", "desc" to "", "author" to "", "play_count" to "")
        if (truthy(base.g("img")) || truthy(base.g("picUrl"))) {
            base.put("img", jos(base.g("img"), base.g("picUrl")).replace("{size}", "400"))
            return base
        }
        val fromTrack = list.firstOrNull { truthy(it.g("picUrl")) || truthy(it.g("img")) }?.s("picUrl")?.ifEmpty { null }
            ?: list.firstOrNull { truthy(it.g("img")) }?.s("img").orEmpty()
        if (fromTrack.isNotEmpty()) base.put("img", fromTrack)
        return base
    }

    private fun buildPlaylistResponse(list: List<JSONObject>, total: Long, source: String, info: JSONObject?, partial: Boolean = false, hasMore: Boolean = false): JSONObject {
        val nextInfo = finalizePlaylistInfo(info, list)
        val cleaned = clearMatchingTrackCovers(list, jos(nextInfo.g("img"), nextInfo.g("picUrl")))
        return obj(
            "list" to JSONArray(cleaned),
            "total" to (if (total > 0) total else cleaned.size.toLong()),
            "source" to source,
            "info" to nextInfo,
            "partial" to partial,
            "hasMore" to (partial && hasMore)
        )
    }

    private suspend fun wyFetchSongsByIds(ids: List<String>, headers: Map<String, String>): Pair<List<JSONObject>, List<JSONObject>> {
        val songs = ArrayList<JSONObject>()
        val privileges = ArrayList<JSONObject>()
        val post = headers + ("Content-Type" to "application/x-www-form-urlencoded")
        for (chunk in ids.chunked(200)) {
            val c = JSONArray(chunk.map { JSONObject().put("id", it) }).toString()
            val data = parseJsonObj(runCatching { CatalogHttp.post("https://music.163.com/api/v3/song/detail", "c=${encodeUri(c)}", "application/x-www-form-urlencoded", post) }.getOrNull())
            if (jnum(data?.g("code")) != 200.0) continue
            songs.addAll(data?.arr("songs").objs())
            privileges.addAll(data?.arr("privileges").objs())
        }
        return songs to privileges
    }

    private suspend fun wyFillMissingTrackPics(list: List<JSONObject>, headers: Map<String, String>): List<JSONObject> {
        val missing = list.filter { !truthy(jor(it.g("picUrl"), it.g("img"))) && truthy(it.g("id")) }.map { it.s("id") }
        if (missing.isEmpty()) return list
        val (songs, _) = wyFetchSongsByIds(missing, headers)
        val picById = HashMap<String, String>()
        songs.forEach { s -> wyAlbumPic(s.o("al")).takeIf { it.isNotEmpty() }?.let { picById[jstr(s.g("id"))] = it } }
        if (picById.isEmpty()) return list
        return list.map { s ->
            if (truthy(jor(s.g("picUrl"), s.g("img")))) s
            else picById[s.s("id")]?.let { s.copyObj().put("picUrl", it).put("img", it) } ?: s
        }
    }

    private suspend fun wyPlaylist(input: PlaylistInput, partial: Boolean): JSONObject {
        val headers = HashMap(WY_HEADERS)
        if (input.token.isNotEmpty()) headers["Cookie"] = "MUSIC_U=${input.token}"
        val data = parseJsonObj(CatalogHttp.get("https://music.163.com/api/v6/playlist/detail?id=${input.id}&n=100000&s=8", headers))
        val pl = data?.o("playlist")
        if (jnum(data?.g("code")) != 200.0 || pl == null) throw IllegalStateException("无法获取网易云歌单，私人歌单请在链接或 ID 后加 ###MUSIC_U")
        val privMap = HashMap<String, JSONObject>()
        data.arr("privileges").objs().forEach { privMap[jstr(it.g("id"))] = it }
        val trackIdList = pl.arr("trackIds").values().map { t -> if (t is JSONObject) jstr(t.g("id")) else jstr(t) }.filter { it.isNotEmpty() && it != "0" }
        val total = jint(pl.g("trackCount")).takeIf { it > 0 } ?: trackIdList.size.toLong()
        val info = obj(
            "name" to cleanHtml(pl.g("name")),
            "img" to pl.s("coverImgUrl"),
            "desc" to cleanHtml(pl.s("description")),
            "author" to cleanHtml(pl.o("creator")?.s("nickname")),
            "play_count" to formatPlayCount(pl.g("playCount"))
        )
        val inlineTracks = pl.arr("tracks").objs()
        if (partial) {
            val partialIds = trackIdList.take(PLAYLIST_PARTIAL_LIMIT)
            var tracks = inlineTracks.filter { jstr(it.g("id")) in partialIds }
            if (partialIds.size > tracks.size || trackIdList.size > inlineTracks.size) {
                val (songs, privs) = wyFetchSongsByIds(partialIds, headers)
                privs.forEach { privMap[jstr(it.g("id"))] = it }
                val trackMap = songs.associateBy { jstr(it.g("id")) }
                tracks = partialIds.mapNotNull { trackMap[it] }
            } else {
                tracks = tracks.take(PLAYLIST_PARTIAL_LIMIT)
            }
            val list = wyFillMissingTrackPics(tracks.map { mapWyPlaylistTrack(it, privMap[jstr(it.g("id"))], pl) }, headers)
            return buildPlaylistResponse(list, total, "wy", info, partial = true, hasMore = total > list.size)
        }
        var tracks = inlineTracks
        if (trackIdList.size > tracks.size) {
            val (songs, privs) = wyFetchSongsByIds(trackIdList, headers)
            privs.forEach { privMap[jstr(it.g("id"))] = it }
            val trackMap = songs.associateBy { jstr(it.g("id")) }
            tracks = trackIdList.mapNotNull { trackMap[it] }
        }
        if (tracks.isEmpty() && (trackIdList.isNotEmpty() || truthy(pl.g("trackCount")))) {
            throw IllegalStateException("歌单歌曲未返回，若为私人歌单请在链接或 ID 后加 ###MUSIC_U")
        }
        val list = wyFillMissingTrackPics(tracks.map { mapWyPlaylistTrack(it, privMap[jstr(it.g("id"))], pl) }, headers)
        return buildPlaylistResponse(list, total, "wy", info)
    }

    private fun txPlaylistHeaders(id: String) = mapOf(
        "Referer" to "https://y.qq.com/n/yqq/playlist/$id.html",
        "Origin" to "https://y.qq.com",
        "User-Agent" to UA_PC
    )

    private suspend fun txFetchPlaylistCd(id: String, songBegin: Int, songNum: Int): JSONObject? {
        val url = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg" +
            "?type=1&json=1&utf8=1&onlysong=0&new_format=1&disstid=$id" +
            "&song_begin=$songBegin&song_num=$songNum" +
            "&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8" +
            "&notice=0&platform=yqq.json&needNewCode=0"
        val data = parseJsonObj(runCatching { CatalogHttp.get(url, txPlaylistHeaders(id)) }.getOrNull())
        val cd = data?.arr("cdlist")?.optJSONObject(0)
        if (jnum(data?.g("code")) != 0.0 || cd == null) return null
        return cd
    }

    private suspend fun txFetchPlaylistCdViaMusicu(id: String, songBegin: Int, songNum: Int): JSONObject? {
        val payload = obj(
            "comm" to obj("ct" to 24, "cv" to 0, "format" to "json", "platform" to "yqq.json", "uin" to "0", "g_tk" to 5381, "needNewCode" to 1),
            "req_1" to obj(
                "module" to "music.srfDissInfo.aiDissInfo",
                "method" to "uniform_get_Dissinfo",
                "param" to obj(
                    "disstid" to (id.toLongOrNull() ?: id), "userinfo" to 1, "tag" to 1, "orderlist" to 1,
                    "song_begin" to songBegin, "song_num" to songNum, "onlysonglist" to 0, "enc_host_uin" to ""
                )
            )
        )
        val headers = txPlaylistHeaders(id) + mapOf("Referer" to "https://y.qq.com/n/yqq/playsquare/$id.html")
        val data = parseJsonObj(runCatching { CatalogHttp.post("https://u.y.qq.com/cgi-bin/musicu.fcg", payload.toString(), "application/json", headers) }.getOrNull())
        val result = data?.o("req_1")?.o("data")
        if (jnum(data?.g("code")) != 0.0 || jnum(data?.o("req_1")?.g("code")) != 0.0 || result == null) return null
        val dirinfo = result.o("dirinfo") ?: JSONObject()
        val songlist = result.arr("songlist") ?: JSONArray()
        return obj(
            "dissname" to dirinfo.s("title", "dissname"),
            "logo" to dirinfo.s("picurl", "logo"),
            "desc" to dirinfo.s("desc"),
            "nickname" to dirinfo.s("host_nick", "nickname"),
            "visitnum" to (jor(dirinfo.g("listennum"), dirinfo.g("visitnum"), 0) ?: 0),
            "songnum" to (jor(result.g("songnum"), result.g("total_song_num"), songlist.length()) ?: 0),
            "songlist" to songlist
        )
    }

    private suspend fun txPlaylist(input: PlaylistInput, partial: Boolean): JSONObject {
        val id = input.id!!
        val pageSize = if (partial) PLAYLIST_PARTIAL_LIMIT else 1000
        var cd = txFetchPlaylistCd(id, 0, pageSize)
        if (cd?.arr("songlist") == null) cd = txFetchPlaylistCdViaMusicu(id, 0, pageSize)
        if (cd?.arr("songlist") == null) throw IllegalStateException("无法获取 QQ 音乐歌单")
        val info = obj(
            "name" to cleanHtml(cd.g("dissname")),
            "img" to cd.s("logo"),
            "desc" to cleanHtml(cd.s("desc")),
            "author" to cleanHtml(cd.s("nickname")),
            "play_count" to formatPlayCount(cd.g("visitnum"))
        )
        val songlist = cd.arr("songlist").objs()
        val total = jint(cd.g("songnum")).takeIf { it > 0 } ?: songlist.size.toLong()
        var all = songlist.map(::mapTxSongItem)
        if (partial) return buildPlaylistResponse(all, total, "tx", info, partial = true, hasMore = total > all.size)
        while (all.size < total) {
            val next = txFetchPlaylistCd(id, all.size, pageSize) ?: txFetchPlaylistCdViaMusicu(id, all.size, pageSize)
            val batch = next?.arr("songlist").objs().map(::mapTxSongItem)
            if (batch.isEmpty()) break
            all = all + batch
            if (batch.size < pageSize) break
        }
        return buildPlaylistResponse(all, total, "tx", info)
    }

    private suspend fun kwPlaylist(input: PlaylistInput, partial: Boolean): JSONObject {
        input.digestId?.let { digestId ->
            val m = Regex("^digest-(\\d+)__(\\d+)$").find(digestId)
            if (m != null) {
                val digest = m.groupValues[1].toInt()
                val pid = m.groupValues[2]
                if (digest == 8 || digest == 13) return kwPlaylist(PlaylistInput(id = pid), partial)
                if (digest == 5) {
                    val infoData = parseJsonObj(runCatching { CatalogHttp.get("http://qukudata.kuwo.cn/q.k?op=query&cont=ninfo&node=$pid&pn=0&rn=1&fmt=json&src=mbox&level=2") }.getOrNull())
                    val sourceId = infoData?.arr("child")?.optJSONObject(0)?.g("sourceid")
                    if (truthy(sourceId)) return kwPlaylist(PlaylistInput(id = jstr(sourceId)), partial)
                }
                return kwPlaylist(PlaylistInput(id = pid), partial)
            }
        }
        val id = input.id ?: throw IllegalStateException("无法获取酷我歌单")
        var page = 0
        val all = ArrayList<JSONObject>()
        var meta: JSONObject? = null
        var total = 0L
        while (true) {
            val pageSize = if (partial && page == 0) PLAYLIST_PARTIAL_LIMIT else 1000
            val url = "http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=$id&pn=$page&rn=$pageSize&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=MUSIC_9.0.5.0_W1&newver=1"
            val data = parseKuwoBody(CatalogHttp.get(url))
            if (data.optString("result") != "ok" && (data.arr("musiclist")?.length() ?: 0) == 0) throw IllegalStateException("无法获取酷我歌单")
            if (meta == null) {
                meta = obj(
                    "name" to cleanHtml(data.g("title")),
                    "img" to data.s("pic"),
                    "desc" to cleanHtml(data.s("info")),
                    "author" to cleanHtml(data.s("uname")),
                    "play_count" to formatPlayCount(data.g("playnum"))
                )
                total = jint(data.g("total"))
            }
            val batch = data.arr("musiclist").objs().map { mapKwListItem(it) }
            all.addAll(batch)
            if (partial) {
                val t = if (total > 0) total else all.size.toLong()
                return buildPlaylistResponse(all, t, "kw", meta, partial = true, hasMore = all.size < t)
            }
            if (batch.isEmpty() || all.size >= total) break
            page += 1
        }
        return buildPlaylistResponse(all, total, "kw", meta)
    }

    private suspend fun mgPlaylist(input: PlaylistInput, partial: Boolean): JSONObject {
        val id = input.id!!
        val infoData = parseJsonObj(runCatching {
            CatalogHttp.get("https://c.musicapp.migu.cn/MIGUM3.0/resource/playlist/v2.0?playlistId=$id", mapOf("Referer" to "https://music.migu.cn", "User-Agent" to UA_IPHONE))
        }.getOrNull())
        val plInfo = infoData?.o("data") ?: JSONObject()
        val info = obj(
            "name" to cleanHtml(plInfo.g("title")),
            "img" to jos(plInfo.o("imgItem")?.g("img"), plInfo.g("img")),
            "desc" to cleanHtml(plInfo.s("summary")),
            "author" to cleanHtml(plInfo.s("ownerName")),
            "play_count" to formatPlayCount(plInfo.o("opNumItem")?.g("playNum"))
        )
        val all = ArrayList<JSONObject>()
        var page = 1
        var total = 0L
        while (true) {
            val data = parseJsonObj(CatalogHttp.get(
                "https://app.c.nf.migu.cn/MIGUM3.0/resource/playlist/song/v2.0?pageNo=$page&pageSize=50&playlistId=$id",
                mapOf("Referer" to "https://m.music.migu.cn/", "User-Agent" to UA_IPHONE)
            ))
            val code = data?.g("code")
            if (code != "000000" && jnum(code) != 0.0) throw IllegalStateException("无法获取咪咕歌单")
            total = jint(data?.o("data")?.g("totalCount")).takeIf { it > 0 } ?: total
            val batch = data?.o("data")?.arr("songList").objs().map { item ->
                val m = mapMgSong(item, "", mgPicUrl(item), arrayOf("songName"), arrayOf("singerList"))
                m
            }
            all.addAll(batch)
            if (partial) return buildPlaylistResponse(all, total, "mg", info, partial = true, hasMore = total > all.size)
            if (batch.isEmpty() || all.size >= total) break
            page += 1
        }
        return buildPlaylistResponse(all, total, "mg", info)
    }

    // ---------------- 酷狗歌单 ----------------

    private fun extractJsonAfterMarker(html: String, marker: String): String? {
        val idx = html.indexOf(marker)
        if (idx < 0) return null
        var i = idx + marker.length
        while (i < html.length && html[i].isWhitespace()) i++
        val start = i
        if (i >= html.length) return null
        val open = html[i]
        if (open != '[' && open != '{') return null
        val close = if (open == '[') ']' else '}'
        var depth = 0
        var inStr = false
        var esc = false
        while (i < html.length) {
            val ch = html[i]
            if (inStr) {
                if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false
            } else if (ch == '"') {
                inStr = true
            } else if (ch == open) {
                depth++
            } else if (ch == close) {
                depth--
                if (depth == 0) return html.substring(start, i + 1)
            }
            i++
        }
        return null
    }

    private fun kgMobileSongPageUrl(specialId: String, page: Int, pageSize: Int, host: String) =
        "$host/api/v3/special/song?plat=0&specialid=$specialId&page=$page&pagesize=$pageSize&version=9108&with_res_tag=1"

    private fun kgMobileInfoUrl(specialId: String, host: String) = "$host/api/v3/special/info?specialid=$specialId"

    private fun kgMapMobilePlaylistInfo(p: JSONObject): JSONObject = obj(
        "name" to cleanHtml(p.s("specialname", "name")),
        "img" to p.s("imgurl", "img").replace("{size}", "400"),
        "desc" to cleanHtml(p.s("intro", "description")),
        "author" to cleanHtml(p.s("nickname", "singername", "username")),
        "play_count" to formatPlayCount(jor(p.g("playcount"), p.g("play_count"), p.g("total_play_count")))
    )

    private fun kgMapMobileSongPage(data: JSONObject?): Pair<List<JSONObject>, Long> {
        val batch = data?.o("data")?.arr("info").objs().map(::mapKgSongItem).filter { truthy(it.g("hash")) || truthy(it.g("name")) }
        val total = jint(data?.o("data")?.g("total")).takeIf { it > 0 } ?: batch.size.toLong()
        return batch to total
    }

    private suspend fun kgFetchMobilePlaylistPages(specialId: String, partial: Boolean, pageSize: Int = 100): JSONObject {
        var infoData: JSONObject? = null
        var firstData: JSONObject? = null
        var activeHost = KG_MOBILE_HOSTS[0]
        var lastErr: Throwable? = null
        for (host in KG_MOBILE_HOSTS) {
            try {
                val (info, first) = coroutineScope {
                    val i = async { runCatching { kgFetchMobileJson(kgMobileInfoUrl(specialId, host)) }.getOrNull() }
                    val f = async { kgFetchMobileJson(kgMobileSongPageUrl(specialId, 1, pageSize, host)) }
                    i.await() to f.await()
                }
                if (jnum(first.g("status")) == 1.0 && first.g("data") != null) {
                    infoData = info
                    firstData = first
                    activeHost = host
                    break
                }
                lastErr = IllegalStateException("无法获取酷狗歌单")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastErr = e
            }
        }
        val first = firstData ?: throw (lastErr ?: IllegalStateException("无法获取酷狗歌单"))
        val (firstBatch, total) = kgMapMobileSongPage(first)
        if (firstBatch.isEmpty()) throw IllegalStateException("无法获取酷狗歌单")
        val info = kgMapMobilePlaylistInfo(infoData?.o("data") ?: JSONObject())
        val cover = info.s("img")
        if (partial) {
            val list = enrichKgSongsCoversByAlbum(clearMatchingTrackCovers(firstBatch, cover))
            return obj("list" to JSONArray(list), "total" to total, "source" to "kg", "info" to finalizePlaylistInfo(info, list), "hasMore" to (total > list.size), "partial" to true)
        }
        val totalPages = Math.ceil(total.toDouble() / pageSize).toInt()
        val pages = ArrayList<List<JSONObject>>()
        pages.add(firstBatch)
        if (totalPages > 1) {
            val rest = coroutineScope {
                (2..totalPages).map { p -> async { runCatching { kgFetchMobileJson(kgMobileSongPageUrl(specialId, p, pageSize, activeHost)) }.getOrNull() } }.awaitAll()
            }
            rest.forEach { data -> if (jnum(data?.g("status")) == 1.0 && data?.g("data") != null) pages.add(kgMapMobileSongPage(data).first) }
        }
        val list = enrichKgSongsCoversByAlbum(clearMatchingTrackCovers(pages.flatten(), cover))
        return obj("list" to JSONArray(list), "total" to (if (total > 0) total else list.size.toLong()), "source" to "kg", "info" to finalizePlaylistInfo(info, list), "hasMore" to false, "partial" to false)
    }

    private suspend fun kgPlaylistFromHtml(id: String): JSONObject {
        val html = CatalogHttp.get("https://www.kugou.com/yy/special/single/$id.html", KG_HEADERS)
        var listRaw: JSONArray? = null
        Regex("global\\.data\\s*=\\s*(\\[[\\s\\S]*?])\\s*;").find(html)?.let { m -> listRaw = runCatching { JSONArray(m.groupValues[1]) }.getOrNull() }
        if ((listRaw?.length() ?: 0) == 0) {
            extractJsonAfterMarker(html, "var data=")?.let { listRaw = runCatching { JSONArray(it) }.getOrNull() }
        }
        var info = obj("name" to "", "img" to "", "desc" to "", "author" to "", "play_count" to "")
        val legacyInfo = Regex("var\\s+global\\s*=\\s*(\\{[\\s\\S]*?})\\s*;").find(html)
        if (legacyInfo != null) {
            runCatching { JSONObject(legacyInfo.groupValues[1]) }.getOrNull()?.let { g ->
                info = obj(
                    "name" to cleanHtml(g.s("name", "specialname")), "img" to g.s("pic", "img"),
                    "desc" to cleanHtml(g.s("intro", "desc")), "author" to cleanHtml(g.s("nickname", "user_name")),
                    "play_count" to formatPlayCount(jor(g.g("play_count"), g.g("playcount")))
                )
            }
        } else {
            extractJsonAfterMarker(html, "var specialInfo =")?.let { json ->
                runCatching { JSONObject(json) }.getOrNull()?.let { g ->
                    info = obj(
                        "name" to cleanHtml(g.s("name", "class_name")), "img" to g.s("image", "pic"),
                        "desc" to cleanHtml(g.s("intro")), "author" to cleanHtml(g.s("nickname")),
                        "play_count" to formatPlayCount(jor(g.g("play_count"), g.g("playcount")))
                    )
                }
            }
        }
        val raw = listRaw?.values().orEmpty()
        if (raw.isEmpty()) throw IllegalStateException("无法解析酷狗歌单歌曲，请尝试使用完整分享链接")
        val mapped = raw.map { item ->
            if (item is String) mapKgSongItem(obj("hash" to item, "SongName" to "", "SingerName" to "")) else mapKgSongItem(item as? JSONObject ?: JSONObject())
        }.filter { truthy(it.g("hash")) || truthy(it.g("name")) }
        val list = enrichKgSongsCoversByAlbum(clearMatchingTrackCovers(mapped, info.s("img")))
        return obj("list" to JSONArray(list), "total" to list.size, "source" to "kg", "info" to finalizePlaylistInfo(info, list))
    }

    private suspend fun kgPlaylistFromSpecial(id: String, partial: Boolean): JSONObject = try {
        kgFetchMobilePlaylistPages(id, partial)
    } catch (mobileErr: Exception) {
        if (mobileErr is kotlinx.coroutines.CancellationException) throw mobileErr
        try {
            kgPlaylistFromHtml(id)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw mobileErr
        }
    }

    private data class KgShareIds(
        val specialId: String,
        val globalCollectionId: String,
        val listinfo: JSONObject,
        val songs: List<JSONObject>,
        val fromShareEmbed: Boolean = false
    )

    private fun kgExtractIdsFromShareHtml(html: String): KgShareIds {
        val raw = extractJsonAfterMarker(html, "window.\$output =") ?: extractJsonAfterMarker(html, "window.\$output=")
        val payload = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
        val info = payload?.o("info") ?: payload ?: JSONObject()
        val listinfo = info.o("listinfo") ?: JSONObject()
        val specialId = jos(info.g("specialid"), info.g("special_id"), listinfo.g("specialid"))
            .ifEmpty { Regex("\"specialid\"\\s*:\\s*\"?([1-9]\\d{2,})").find(html)?.groupValues?.get(1).orEmpty() }.trim()
        val gid = jos(listinfo.g("global_collection_id"), info.g("global_collection_id"), payload?.g("global_collection_id"), payload?.g("global_specialid"))
            .ifEmpty { Regex("\"global_collection_id\"\\s*:\\s*\"(collection_[^\"]+)\"").find(html)?.groupValues?.get(1).orEmpty() }
            .ifEmpty { Regex("\"global_specialid\"\\s*:\\s*\"(collection_[^\"]+)\"").find(html)?.groupValues?.get(1).orEmpty() }.trim()
        val songs = (info.arr("songs") ?: payload?.arr("songs")).objs()
        return KgShareIds(
            specialId = if (Regex("^[1-9]\\d*$").matches(specialId)) specialId else "",
            globalCollectionId = if (gid.startsWith("collection_")) gid else "",
            listinfo = listinfo,
            songs = songs
        )
    }

    private suspend fun kgPlaylistFromShareEmbed(ids: KgShareIds, partial: Boolean): JSONObject {
        val listinfo = ids.listinfo
        if (ids.songs.isEmpty()) throw IllegalStateException("酷狗分享页未包含歌曲数据")
        val cover = listinfo.s("pic", "img", "cover").replace("{size}", "400")
        val info = obj(
            "name" to cleanHtml(listinfo.s("name", "specialname")),
            "img" to cover,
            "desc" to cleanHtml(listinfo.s("intro")),
            "author" to cleanHtml(listinfo.s("list_create_username", "nickname", "username")),
            "play_count" to formatPlayCount(jor(listinfo.g("heat"), listinfo.g("play_count"), listinfo.g("collect_count")))
        )
        val mapped = ids.songs.map(::mapKgSongItem).filter { truthy(it.g("hash")) || truthy(it.g("name")) }
        val total = jint(listinfo.g("count")).takeIf { it > 0 } ?: mapped.size.toLong()
        val list = enrichKgSongsCoversByAlbum(clearMatchingTrackCovers(mapped, cover))
        val hasMore = total > list.size
        return obj(
            "list" to JSONArray(list), "total" to total, "source" to "kg", "info" to finalizePlaylistInfo(info, list),
            "hasMore" to hasMore, "partial" to partial, "truncated" to (!partial && hasMore)
        )
    }

    private const val KG_WEB_SIGN_SALT = "OIlwieks28dk2k092lksi2UIkp"
    private const val KG_WEB_MID = "lemonmusicwebmid00000000000000001"

    private fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun kgSignWebParams(params: Map<String, String>, salt: String = KG_WEB_SIGN_SALT, body: String = ""): String {
        val entries = params.filter { it.value.isNotEmpty() }.toSortedMap()
        return md5Hex(salt + entries.entries.joinToString("") { "${it.key}=${it.value}" } + body + salt)
    }

    private fun queryString(params: Map<String, String>): String =
        params.entries.joinToString("&") { "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }

    private suspend fun kgDecodeGcid(gcid: String): String {
        val params = linkedMapOf(
            "dfid" to "-", "appid" to "1005", "mid" to "0", "clientver" to "20109",
            "clienttime" to (System.currentTimeMillis() / 1000).toString(), "uuid" to "-"
        )
        val body = obj("ret_info" to 1, "data" to JSONArray().put(obj("id" to gcid, "id_type" to 2))).toString()
        params["signature"] = kgSignWebParams(params, KG_WEB_SIGN_SALT, body)
        val data = parseJsonObj(CatalogHttp.post("https://t.kugou.com/v1/songlist/batch_decode?${queryString(params)}", body, "application/json", KG_MOBILE_HTML_HEADERS))
        val entry = data?.o("data")?.arr("list").objs().firstOrNull { it.optString("id") == gcid }
        val gid = entry?.s("global_collection_id").orEmpty()
        if (jnum(data?.g("status")) != 1.0 || !Regex("^collection_\\w+$").matches(gid)) throw IllegalStateException("无法解码酷狗歌单 ID")
        return gid
    }

    private suspend fun kgResolveGcid(encodeGcid: String, shareQuery: Map<String, String>): KgShareIds {
        val gcid = encodeGcid.trim()
        if (!Regex("^gcid_[a-zA-Z0-9_]+$", RegexOption.IGNORE_CASE).matches(gcid)) throw IllegalStateException("无法解析酷狗 gcid 歌单")
        runCatching { kgDecodeGcid(gcid) }.getOrNull()?.let {
            return KgShareIds("", it, JSONObject(), emptyList())
        }
        val qs = LinkedHashMap<String, String>()
        val srcCid = shareQuery["srcCid"].orEmpty().trim().ifEmpty { gcid.replace(Regex("^gcid_", RegexOption.IGNORE_CASE), "") }
        if (srcCid.isNotEmpty()) qs["src_cid"] = srcCid
        shareQuery["uid"]?.takeIf { it.isNotEmpty() }?.let { qs["uid"] = it }
        shareQuery["iszlist"]?.takeIf { it.isNotEmpty() }?.let { qs["iszlist"] = it }
        shareQuery["chl"]?.takeIf { it.isNotEmpty() }?.let { qs["chl"] = it }
        val suffix = if (qs.isEmpty()) "" else "?${queryString(qs)}"
        val urls = listOf(
            "https://m.kugou.com/songlist/$gcid/$suffix",
            "https://m3ws.kugou.com/songlist/$gcid/$suffix",
            "https://www.kugou.com/songlist/$gcid/$suffix"
        )
        var lastErr: Throwable? = null
        var best: KgShareIds? = null
        for (url in urls) {
            try {
                val ids = kgExtractIdsFromShareHtml(CatalogHttp.get(url, KG_MOBILE_HTML_HEADERS))
                if (ids.globalCollectionId.isNotEmpty() || ids.specialId.isNotEmpty()) return ids
                if (ids.songs.isNotEmpty() && (best == null || ids.songs.size > best.songs.size)) best = ids
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastErr = e
            }
        }
        best?.let { return it.copy(fromShareEmbed = true) }
        throw lastErr ?: IllegalStateException("无法解析酷狗 gcid 歌单，请改用数字 ID 或官网链接")
    }

    private suspend fun kgFetchOtherListNofilt(gid: String, beginIdx: Int, pageSize: Int): JSONObject {
        if (gid.isBlank()) throw IllegalStateException("缺少 global_collection_id")
        val params = linkedMapOf(
            "area_code" to "1", "begin_idx" to beginIdx.toString(), "plat" to "1", "type" to "1", "mode" to "1",
            "personal_switch" to "1", "pagesize" to pageSize.toString(), "global_collection_id" to gid, "userid" to "0",
            "module" to "CloudMusic", "need_sort" to "1", "need_rd" to "0", "appid" to "1005", "clientver" to "12345",
            "clienttime" to (System.currentTimeMillis() / 1000).toString(), "mid" to KG_WEB_MID, "uuid" to KG_WEB_MID, "dfid" to "-"
        )
        params["signature"] = kgSignWebParams(params)
        val data = parseJsonObj(CatalogHttp.get(
            "https://gateway.kugou.com/pubsongs/v2/get_other_list_file_nofilt?${queryString(params)}",
            KG_HEADERS + mapOf("Referer" to "https://m.kugou.com/", "mid" to KG_WEB_MID)
        ))
        if (data == null || jnum(data.g("status")) != 1.0 || (!data.g("error_code").isNullish() && jnum(data.g("error_code")) != 0.0)) {
            throw IllegalStateException(jos(data?.g("errmsg"), data?.g("error")).ifEmpty { "酷狗歌单接口失败" })
        }
        return data
    }

    private suspend fun kgPlaylistFromGid(gid: String, partial: Boolean): JSONObject {
        val pageSize = if (partial) PLAYLIST_PARTIAL_LIMIT else 300
        val firstData = kgFetchOtherListNofilt(gid, 0, pageSize)
        val li = firstData.o("data")?.o("list_info") ?: firstData.o("data")?.o("info") ?: JSONObject()
        val cover = li.s("pic", "img", "cover", "create_user_pic").replace("{size}", "400")
        val info = obj(
            "name" to cleanHtml(li.s("name", "specialname", "list_name")),
            "img" to cover,
            "desc" to cleanHtml(li.s("intro", "desc")),
            "author" to cleanHtml(li.s("list_create_username", "nickname", "username", "create_username")),
            "play_count" to formatPlayCount(jor(li.g("heat"), li.g("play_count"), li.g("collect_count")))
        )
        fun mapPage(data: JSONObject): List<JSONObject> =
            (data.o("data")?.arr("songs") ?: data.o("data")?.o("info")?.arr("songs") ?: data.o("data")?.arr("lists")).objs().map(::mapKgSongItem)
        fun normalize(tracks: List<JSONObject>): List<JSONObject> {
            val seen = HashSet<String>()
            return tracks.filter { s ->
                if (!truthy(s.g("name")) || !(truthy(s.g("hash")) || truthy(s.g("albumAudioId")))) return@filter false
                val key = if (truthy(s.g("albumAudioId"))) "audio:${s.s("albumAudioId")}" else "hash:${s.s("hash")}:${s.s("albumId")}"
                seen.add(key)
            }
        }
        val firstBatch = mapPage(firstData)
        val total = jint(jor(firstData.o("data")?.g("count"), firstData.o("data")?.g("total"))).takeIf { it > 0 } ?: firstBatch.size.toLong()
        if (partial) {
            val list = enrichKgSongsCoversByAlbum(clearMatchingTrackCovers(normalize(firstBatch), cover))
            val hasMore = total > firstBatch.size
            return obj(
                "list" to JSONArray(list), "total" to (if (hasMore) total - (firstBatch.size - list.size) else list.size.toLong()),
                "source" to "kg", "info" to finalizePlaylistInfo(info, list), "hasMore" to hasMore, "partial" to true
            )
        }
        val batches = ArrayList<List<JSONObject>>()
        batches.add(firstBatch)
        val pageKeys = HashSet<String>()
        pageKeys.add(firstBatch.joinToString(",") { jos(it.g("albumAudioId"), it.g("hash"), it.g("id")) })
        var begin = firstBatch.size
        while (begin < total) {
            val batch = mapPage(kgFetchOtherListNofilt(gid, begin, pageSize))
            if (batch.isEmpty()) break
            val key = batch.joinToString(",") { jos(it.g("albumAudioId"), it.g("hash"), it.g("id")) }
            if (!pageKeys.add(key)) break
            batches.add(batch)
            begin += batch.size
        }
        val flat = batches.flatten()
        val list = enrichKgSongsCoversByAlbum(clearMatchingTrackCovers(normalize(flat), cover))
        val hasMore = begin < total
        val normalizedTotal = if (hasMore) maxOf(list.size.toLong(), total - (flat.size - list.size)) else list.size.toLong()
        return obj("list" to JSONArray(list), "total" to normalizedTotal, "source" to "kg", "info" to finalizePlaylistInfo(info, list), "hasMore" to hasMore, "partial" to false, "truncated" to hasMore)
    }

    private suspend fun kgResolveShareInput(raw: String): String {
        val input = extractKgShareUrl(raw)
        if (!Regex("^https?://t\\d*\\.kugou\\.com/", RegexOption.IGNORE_CASE).containsMatchIn(input)) return input
        return runCatching { CatalogHttp.request("GET", input, null, null, KG_HEADERS).finalUrl }.getOrDefault(input)
    }

    private suspend fun kgPlaylist(parsed: PlaylistInput, partial: Boolean): JSONObject {
        parsed.id?.let { return kgPlaylistFromSpecial(it, partial) }
        parsed.encodeGcid?.let { gcid ->
            val ids = kgResolveGcid(gcid, parsed.shareQuery)
            if (ids.globalCollectionId.isNotEmpty()) {
                return try {
                    kgPlaylistFromGid(ids.globalCollectionId, partial)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (ids.songs.isNotEmpty()) kgPlaylistFromShareEmbed(ids, partial) else throw e
                }
            }
            if (ids.specialId.isNotEmpty()) return kgPlaylistFromSpecial(ids.specialId, partial)
            if (ids.fromShareEmbed || ids.songs.isNotEmpty()) return kgPlaylistFromShareEmbed(ids, partial)
            throw IllegalStateException("无法解析酷狗 gcid 歌单，请改用数字 ID 或官网链接")
        }
        parsed.globalCollectionId?.let { return kgPlaylistFromGid(it, partial) }
        throw IllegalStateException("无法解析酷狗歌单")
    }

    suspend fun fetchPlaylist(source: String, input: String, partial: Boolean = false): JSONObject {
        if (source !in SOURCES) throw IllegalArgumentException("不支持的平台: $source")
        val resolved = if (source == "kg") kgResolveShareInput(input) else input
        val parsed = parsePlaylistInput(source, resolved)
        return when (source) {
            "wy" -> wyPlaylist(parsed, partial)
            "tx" -> txPlaylist(parsed, partial)
            "kw" -> kwPlaylist(parsed, partial)
            "mg" -> mgPlaylist(parsed, partial)
            else -> kgPlaylist(parsed, partial)
        }
    }

    // ======================================================================
    // 推荐歌单
    // ======================================================================

    private suspend fun wyRecommendPlaylists(sort: String, page: Int, limit: Int): JSONObject {
        val order = if (sort == "new") "new" else "hot"
        val offset = (page - 1) * limit
        val data = parseJsonObj(CatalogHttp.get(
            "https://music.163.com/api/playlist/list?cat=${encodeUri("全部")}&order=$order&limit=$limit&offset=$offset",
            mapOf("Referer" to "https://music.163.com", "User-Agent" to UA_PC_SHORT)
        ))
        val playlists = data?.arr("playlists")
        if (jnum(data?.g("code")) != 200.0 || playlists == null) throw IllegalStateException("无法获取网易云推荐歌单")
        val list = playlists.objs().map { item ->
            mapRecommendItem(item.g("id"), item.g("name"), item.o("creator")?.g("nickname"), item.g("coverImgUrl"),
                formatPlayCount(item.g("playCount")), item.g("trackCount"), item.g("description"), "wy")
        }
        return obj("list" to JSONArray(list), "total" to (jor(data.g("total"), playlists.length()) ?: 0), "page" to page, "limit" to limit, "source" to "wy")
    }

    private suspend fun txRecommendPlaylists(sort: String, page: Int, limit: Int): JSONObject {
        val order = if (sort == "new") 2 else 5
        val payload = obj(
            "comm" to obj("cv" to 1602, "ct" to 20),
            "playlist" to obj(
                "method" to "get_playlist_by_tag",
                "param" to obj("id" to 10000000, "sin" to limit * (page - 1), "size" to limit, "order" to order, "cur_page" to page),
                "module" to "playlist.PlayListPlazaServer"
            )
        )
        val data = parseJsonObj(CatalogHttp.get("https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data=${encodeUri(payload.toString())}"))
        val pl = data?.o("playlist")?.o("data")
        val vList = pl?.arr("v_playlist")
        if (jnum(data?.g("code")) != 0.0 || vList == null) throw IllegalStateException("无法获取 QQ 音乐推荐歌单")
        val list = vList.objs().map { item ->
            mapRecommendItem(
                item.g("tid"), item.g("title"), item.o("creator_info")?.g("nick"),
                jor(item.g("cover_url_medium"), item.g("cover_url_big")), formatPlayCount(item.g("access_num")),
                jor(item.g("song_num"), item.g("songnum"), item.g("total_song_num"), item.arr("song_ids")?.length(), 0),
                item.g("desc"), "tx"
            )
        }
        return obj("list" to JSONArray(list), "total" to (jor(pl.g("total"), vList.length()) ?: 0), "page" to page, "limit" to limit, "source" to "tx")
    }

    private suspend fun kwRecommendPlaylists(sort: String, page: Int, limit: Int): JSONObject {
        val order = if (sort == "new") "new" else "hot"
        val data = parseJsonObj(CatalogHttp.get("http://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList?loginUid=0&loginSid=0&appUid=76039576&pn=$page&rn=$limit&order=$order"))
        val inner = data?.o("data")
        val items = inner?.arr("data")
        if (jnum(data?.g("code")) != 200.0 || items == null) throw IllegalStateException("无法获取酷我推荐歌单")
        val list = items.objs().map { item ->
            mapRecommendItem("digest-${item.s("digest")}__${item.s("id")}", item.g("name"), item.g("uname"), item.g("img"),
                formatPlayCount(item.g("listencnt")), item.g("total"), item.g("desc"), "kw")
        }
        return obj(
            "list" to JSONArray(list), "total" to (jor(inner.g("total"), items.length()) ?: 0),
            "page" to (jor(inner.g("pn"), page) ?: page), "limit" to (jor(inner.g("rn"), limit) ?: limit), "source" to "kw"
        )
    }

    private suspend fun kgRecommendPlaylists(sort: String, page: Int, limit: Int): JSONObject {
        val t = when (sort) { "hot" -> "6"; "new" -> "7"; else -> "5" }
        val data = parseJsonObj(CatalogHttp.get(
            "http://www2.kugou.kugou.com/yueku/v9/special/getSpecial?is_ajax=1&cdn=cdn&t=$t&c=&p=$page",
            mapOf("User-Agent" to UA_PC_SHORT)
        ))
        val db = data?.arr("special_db")
        if (jnum(data?.g("status")) != 1.0 || db == null) throw IllegalStateException("无法获取酷狗推荐歌单")
        val list = db.objs().map { item ->
            mapRecommendItem(
                "id_${item.s("specialid")}", item.g("specialname"), jor(item.g("nickname"), item.g("singername")),
                item.s("imgurl").replaceFirst("{size}", "400").ifEmpty { item.s("img") },
                formatPlayCount(jor(item.g("play_count"), item.g("total_play_count"))), item.g("songcount"), item.g("intro"), "kg"
            )
        }
        val total = jint(data.g("recordcount"))
        val effLimit = if (list.isNotEmpty()) list.size else limit
        return obj(
            "list" to JSONArray(list), "total" to total, "page" to page, "limit" to effLimit,
            "hasMore" to (if (total > 0) page.toLong() * effLimit < total else list.isNotEmpty()), "source" to "kg"
        )
    }

    private fun extractMgRecommendItems(contents: JSONArray?, list: MutableList<JSONObject>, ids: MutableSet<String>) {
        for (item in contents.objs()) {
            if ((item.arr("contents")?.length() ?: 0) > 0) extractMgRecommendItems(item.arr("contents"), list, ids)
            val viewId = item.s("viewId")
            val id = jos(item.g("resId"), item.g("txt4")).ifEmpty { if (viewId.startsWith("4006-")) viewId.split('-').getOrElse(1) { "" } else "" }
            if (id.isNotEmpty() && truthy(item.g("txt")) && ids.add(id)) {
                list.add(mapRecommendItem(id, item.g("txt"), "", jor(item.g("img"), item.g("img2"), item.g("txt5")), "", 0, "", "mg"))
            }
        }
    }

    private suspend fun mgRecommendPlaylists(page: Int, limit: Int): JSONObject {
        val data = parseJsonObj(CatalogHttp.get(
            "https://app.c.nf.migu.cn/pc/bmw/page-data/playlist-square-recommend/v1.0?templateVersion=2&pageNo=$page",
            mapOf("Referer" to "https://m.music.migu.cn/", "User-Agent" to UA_IPHONE)
        ))
        val contents = data?.o("data")?.arr("contents")
        if (data?.g("code") != "000000" || contents == null) throw IllegalStateException("无法获取咪咕推荐歌单")
        val list = ArrayList<JSONObject>()
        extractMgRecommendItems(contents, list, HashSet())
        return obj("list" to JSONArray(list), "total" to 0, "page" to page, "limit" to (if (list.isNotEmpty()) list.size else limit), "hasMore" to list.isNotEmpty(), "source" to "mg")
    }

    suspend fun fetchRecommendPlaylists(source: String, sort: String = "hot", page: Int = 1, limit: Int = 30): JSONObject = when (source) {
        "wy" -> wyRecommendPlaylists(sort, page, limit)
        "tx" -> txRecommendPlaylists(sort, page, limit)
        "kw" -> kwRecommendPlaylists(sort, page, limit)
        "kg" -> kgRecommendPlaylists(sort, page, limit)
        "mg" -> mgRecommendPlaylists(page, limit)
        else -> throw IllegalArgumentException("不支持的平台: $source")
    }

    // ======================================================================
    // 歌词（LRC）
    // ======================================================================

    private val KW_LYRIC_HEADERS = mapOf("Referer" to "https://www.kuwo.cn/", "User-Agent" to UA_PC)
    private val KG_LYRIC_HEADERS = mapOf("Referer" to "https://www.kugou.com/", "User-Agent" to UA_PC)

    private fun lyricResult(lyric: String = "", tlyric: String = "", rlyric: String = "", ylyric: String = ""): JSONObject = obj(
        "lyric" to lyric.replace("\r", "").trim(),
        "tlyric" to tlyric.replace("\r", "").trim(),
        "rlyric" to rlyric.replace("\r", "").trim(),
        "ylyric" to ylyric.replace("\r", "").trim()
    )

    private fun fmtLrcTime(sec: Double): String {
        val m = Math.floor(sec / 60).toLong()
        val s = sec % 60
        return "${m.toString().padStart(2, '0')}:${String.format(Locale.US, "%.2f", s).padStart(5, '0')}"
    }

    private fun parseKwLrcPayload(data: JSONObject?): JSONObject? {
        if (data == null) return null
        val list = (data.o("data")?.arr("lrclist") ?: data.o("data")?.arr("lrcList") ?: data.arr("lrclist")).objs()
        if (list.isNotEmpty()) {
            val lines = list.filter { !it.g("lineLyric").isNullish() && !it.g("time").isNullish() }
                .joinToString("\n") { "[${fmtLrcTime(jnum(it.g("time")).let { t -> if (t.isNaN()) 0.0 else t })}]${jstr(it.g("lineLyric"))}" }
            if (lines.isNotBlank()) return lyricResult(lines)
        }
        for (key in listOf("lrctxt", "lyric", "songLrc", "lrc", "content")) {
            val raw = data.o("data")?.g(key) ?: data.g(key)
            if (raw is String && raw.isNotBlank()) return lyricResult(raw.trim())
        }
        return null
    }

    private suspend fun fetchKwLyricById(musicId: String): JSONObject? {
        val raw = musicId.trim()
        if (raw.isEmpty()) return null
        val bare = raw.replace(Regex("^MUSIC_", RegexOption.IGNORE_CASE), "")
        val ids = listOf(raw, bare, if (Regex("^\\d+$").matches(bare)) "MUSIC_$bare" else "").filter { it.isNotEmpty() }.distinct()
        for (id in ids) {
            val urls = listOf(
                "https://www.kuwo.cn/openapi/v1/www/lyric/getlyric?musicId=$id",
                "https://www.kuwo.cn/newh5/singles/songinfoandlrc?musicId=$id",
                "https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=$id&httpsStatus=1",
                "https://wbxapi.kuwo.cn/api/www/lyric/lyric?httpsStatus=1&musicId=$id"
            )
            for (url in urls) {
                val text = runCatching { CatalogHttp.get(url, KW_LYRIC_HEADERS) }.getOrNull() ?: continue
                val data = parseJsonObj(text.trim()) ?: parseJsonObj(text.replace(Regex("^[^{\\[]+"), ""))
                val parsed = parseKwLrcPayload(data)
                if (parsed?.s("lyric")?.isNotEmpty() == true) return parsed
            }
        }
        return null
    }

    private suspend fun wyLyric(songId: String): JSONObject {
        val data = parseJsonObj(CatalogHttp.get("https://music.163.com/api/song/lyric?id=$songId&lv=1&tv=1&rv=1&yv=1&kv=1", mapOf("Referer" to "https://music.163.com")))
        return lyricResult(
            data?.o("lrc")?.s("lyric").orEmpty(), data?.o("tlyric")?.s("lyric").orEmpty(),
            data?.o("romalrc")?.s("lyric").orEmpty(), data?.o("yrc")?.s("lyric").orEmpty()
        )
    }

    private fun maybeBase64(text: String): String {
        if (text.isEmpty() || text.contains('[') || !Regex("^[A-Za-z0-9+/=\\s]+$").matches(text.take(80))) return text
        return runCatching { String(android.util.Base64.decode(text, android.util.Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(text)
    }

    private suspend fun txLyric(songmid: String, extra: JSONObject): JSONObject {
        val ids = listOf(extra.s("songmid"), extra.s("strMediaMid"), songmid).filter { it.isNotEmpty() }.distinct()
        for (id in ids) {
            val data = parseJsonObj(runCatching {
                CatalogHttp.get("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$id&format=json&nobase64=1", mapOf("Referer" to "https://y.qq.com/portal/player.html"))
            }.getOrNull()) ?: continue
            val parsed = lyricResult(maybeBase64(data.s("lyric")), maybeBase64(data.s("trans")), maybeBase64(data.s("roma", "rom")))
            if (parsed.s("lyric").isNotEmpty()) return parsed
        }
        return lyricResult()
    }

    private suspend fun kwLyric(songId: String, extra: JSONObject): JSONObject {
        val ids = listOf(songId, extra.s("musicId"), extra.s("rid"), extra.s("dcTargetId")).filter { it.isNotEmpty() }.distinct()
        for (id in ids) fetchKwLyricById(id)?.let { return it }
        val name = extra.s("name")
        val singer = extra.s("singer")
        val keyword = listOf(name, singer).filter { it.isNotEmpty() }.joinToString(" ")
        if (keyword.isNotEmpty() && name.isNotEmpty()) {
            runCatching {
                val result = kwSearch(keyword, 1, 8)
                for (hit in result.arr("list").objs()) {
                    // 必须同名同歌手，避免用错歌词
                    if (cleanHtml(hit.g("name")).lowercase() != name.trim().lowercase()) continue
                    if (singer.isNotEmpty()) {
                        val s = hit.s("singer").lowercase()
                        val main = singer.lowercase().split(Regex("[/|,，、;；\\s]+")).firstOrNull { it.isNotEmpty() } ?: singer.lowercase()
                        if (!s.contains(main)) continue
                    }
                    for (id in listOf(hit.s("songId"), hit.s("id"), hit.s("musicId"), hit.s("rid")).filter { it.isNotEmpty() }) {
                        if (id in ids) continue
                        fetchKwLyricById(id)?.let { return it }
                    }
                }
            }
        }
        return lyricResult()
    }

    private suspend fun kgDownloadLyric(candidate: JSONObject): JSONObject? {
        for (client in listOf("pc", "mobi")) {
            val data = parseJsonObj(runCatching {
                CatalogHttp.get(
                    "https://lyrics.kugou.com/download?ver=1&client=$client&id=${candidate.s("id")}&accesskey=${candidate.s("accesskey")}&fmt=lrc&charset=utf8",
                    KG_LYRIC_HEADERS
                )
            }.getOrNull()) ?: continue
            val content = data.s("content")
            if (content.isEmpty()) continue
            val text = runCatching { String(android.util.Base64.decode(content, android.util.Base64.DEFAULT), Charsets.UTF_8).trim() }.getOrDefault("")
            if (text.isNotEmpty()) return lyricResult(text)
        }
        return null
    }

    private suspend fun kgSearchLyricCandidates(hash: String, extra: JSONObject): List<JSONObject> {
        val albumAudioId = extra.s("albumAudioId", "album_audio_id")
        val duration = jstr(nn(extra.g("duration"), extra.g("interval")) ?: "")
        val queries = listOf(
            "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=&duration=$duration&hash=$hash&album_audio_id=$albumAudioId",
            "https://krcs.kugou.com/search?ver=1&man=yes&client=pc&keyword=&duration=$duration&hash=$hash&album_audio_id=$albumAudioId",
            "https://krcs.kugou.com/search?ver=1&man=yes&client=pc&keyword=&duration=&hash=$hash&album_audio_id="
        )
        val out = ArrayList<JSONObject>()
        for (url in queries) {
            val data = parseJsonObj(runCatching { CatalogHttp.get(url, KG_LYRIC_HEADERS) }.getOrNull()) ?: continue
            data.arr("candidates").objs().filter { truthy(it.g("id")) && truthy(it.g("accesskey")) }.forEach { out.add(it) }
            if (out.isNotEmpty()) break
        }
        return out
    }

    private suspend fun kgLyric(hash: String, extra: JSONObject): JSONObject {
        if (hash.isEmpty()) return lyricResult()
        for (c in kgSearchLyricCandidates(hash, extra)) kgDownloadLyric(c)?.let { return it }
        val keyword = listOf(extra.s("name"), extra.s("singer")).filter { it.isNotEmpty() }.joinToString(" ")
        if (keyword.isNotEmpty()) {
            runCatching {
                val result = kgSearch(keyword, 1, 8)
                for (hit in result.arr("list").objs()) {
                    val hitHash = jos(hit.g("hash"), hit.g("songId"), hit.g("id"))
                    if (hitHash.isEmpty() || hitHash == hash) continue
                    val more = kgSearchLyricCandidates(hitHash, extra.copyObj().apply {
                        put("albumAudioId", jos(hit.g("albumAudioId"), extra.g("albumAudioId")))
                        put("duration", jor(hit.g("duration"), extra.g("duration")) ?: "")
                    })
                    for (c in more) kgDownloadLyric(c)?.let { return it }
                }
            }
        }
        return lyricResult()
    }

    private suspend fun mgLyric(copyrightId: String): JSONObject {
        val data = parseJsonObj(CatalogHttp.get("https://music.migu.cn/v3/api/music/audioPlayer/getLyric?copyrightId=$copyrightId", mapOf("Referer" to "https://music.migu.cn")))
        return lyricResult(data?.s("lyric").orEmpty(), data?.s("translatedLyric").orEmpty(), data?.s("transliterationLyric", "romaLyric").orEmpty())
    }

    /** 对齐服务端 getLyric：各平台 ID 字段不同（QQ songmid、酷狗 hash、咪咕 copyrightId）。 */
    suspend fun getLyric(songId: String, source: String, extra: JSONObject): JSONObject {
        val id = when (source) {
            "tx" -> jos(extra.g("songmid"), extra.g("strMediaMid"), songId)
            "kg" -> jos(extra.g("hash"), songId)
            "mg" -> jos(extra.g("copyrightId"), songId)
            "kw" -> jos(extra.g("musicId"), extra.g("rid"), extra.g("dcTargetId"), songId)
            else -> songId
        }
        if (id.isEmpty()) return lyricResult()
        return try {
            when (source) {
                "wy" -> wyLyric(id)
                "tx" -> txLyric(id, extra)
                "kw" -> kwLyric(id, extra)
                "kg" -> kgLyric(id, extra)
                "mg" -> mgLyric(id)
                else -> lyricResult()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            lyricResult()
        }
    }
}
