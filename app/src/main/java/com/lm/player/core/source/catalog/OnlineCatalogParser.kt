package com.lm.player.core.source.catalog

import com.lm.player.core.media.SongMatchingResolver
import com.lm.player.core.model.LemonToplist
import com.lm.player.core.model.UnifiedAlbum
import com.lm.player.core.model.UnifiedPlaylist
import com.lm.player.core.model.UnifiedSong
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 把与服务端同形的目录 JSON 解析成客户端模型。
 *
 * 只用于「没有服务器」的场景（已解绑模式、跨平台匹配）；有服务器时仍由各端 LemonMusicProtocol 自己解析。
 * 规则逐项照搬 LemonMusicProtocol（searchOnline / fetchOnlineSongList / 推荐歌单 / 榜单 / 新碟），
 * 歌曲 ID 统一为 lemon_online_{平台}_{平台ID}，与服务器路径得到的 ID 完全一致。
 */
object OnlineCatalogParser {
    private const val MAX_PLAUSIBLE_DURATION_MS = 24L * 60L * 60L * 1000L

    private fun JSONObject.clean(vararg keys: String): String {
        for (k in keys) {
            val v = optString(k).trim()
            if (v.isNotEmpty() && !v.equals("null", true) && !v.equals("undefined", true)) return v
        }
        return ""
    }

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** 与 LemonMusicProtocol.stableFallbackSongId 一致 */
    private fun stableFallbackSongId(name: String, singer: String, album: String, durationMs: Long): String =
        "h" + md5("${name.trim().lowercase()}|${singer.trim().lowercase()}|${album.trim().lowercase()}|${durationMs / 1000L}").take(16)

    fun normalizeImageUrl(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return ""
        var url = rawUrl.trim()
        if (url.isEmpty() || url.equals("null", true) || url.equals("undefined", true)) return ""
        if (url.startsWith("//")) {
            url = "https:$url"
        } else if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("data:")) {
            if (url.matches(Regex("^(120|150|240|300|500)/.*")) || url.contains("s3s") || url.contains("s4s") || url.contains("star/albumcover")) {
                val upgraded = url.replaceFirst(Regex("^(120|150|240|300)/"), "500/")
                url = "https://img1.kuwo.cn/star/albumcover/${upgraded.trimStart('/')}"
            } else {
                return ""
            }
        }
        if (url.contains("kwcdn.kuwo.cn", ignoreCase = true)) {
            url = url.replace(Regex("""(?i)(?:[a-zA-Z0-9_\-]+\.)*kwcdn\.kuwo\.cn"""), "img1.kuwo.cn")
        }
        if (url.contains("{size}")) url = url.replace("{size}", "300")
        if (url.contains("/star/albumcover/120/")) url = url.replace("/star/albumcover/120/", "/star/albumcover/500/")
        if (url.contains("music.126.net") && !url.contains("param=")) url += if (url.contains("?")) "&param=300y300" else "?param=300y300"
        if (url.startsWith("http://")) {
            val lower = url.lowercase()
            if (!lower.contains("kwcdn.kuwo.cn") && (lower.contains("kuwo.cn") || lower.contains("koowo.cn") || lower.contains("kugou.com") ||
                    lower.contains("gtimg.cn") || lower.contains("126.net") || lower.contains("migu.cn"))
            ) url = "https://" + url.removePrefix("http://")
        }
        return url
    }

    private fun parseClockDurationMs(raw: String): Long? {
        val text = raw.trim()
        if (!text.contains(":")) return null
        val segs = text.split(":")
        if (segs.size !in 2..3) return null
        val seconds = segs.last().trim().toDoubleOrNull() ?: return null
        if (seconds < 0.0 || seconds >= 60.0) return null
        val ints = segs.dropLast(1).map { it.trim().toLongOrNull() ?: return null }
        if (ints.any { it < 0L }) return null
        val total = when (ints.size) {
            1 -> ints[0] * 60.0 + seconds
            2 -> ints[0] * 3600.0 + ints[1] * 60.0 + seconds
            else -> return null
        }
        if (total <= 0.0) return null
        return (total * 1000.0).toLong()
    }

    fun parseDurationMs(s: JSONObject): Long {
        parseClockDurationMs(s.optString("interval"))?.let { return it }
        val d = if (s.has("duration")) s.optDouble("duration", Double.NaN) else Double.NaN
        val i = if (s.has("interval")) s.optDouble("interval", Double.NaN) else Double.NaN
        val numeric = if (!d.isNaN() && d > 0.0) d else i
        if (numeric.isNaN() || numeric <= 0.0) return 0L
        val ms = if (numeric > 86_400.0) numeric else numeric * 1000.0
        if (ms > MAX_PLAUSIBLE_DURATION_MS) return 0L
        return ms.toLong()
    }

    private fun text(raw: String) = SongMatchingResolver.unescapeMusicText(raw)

    /** 搜索结果（对齐 searchOnline）：ID 取 songmid → songId → hash → copyrightId → id */
    fun searchSongs(list: JSONArray?, source: String): List<UnifiedSong> {
        if (list == null) return emptyList()
        val out = ArrayList<UnifiedSong>(list.length())
        val seen = HashSet<String>()
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            val rawId = s.optString("songmid").ifBlank { s.optString("songId") }.ifBlank { s.optString("hash") }
                .ifBlank { s.optString("copyrightId") }.ifBlank { s.optString("id").takeIf { it != "0" }.orEmpty() }
            val platformId = rawId.takeIf { it.isNotBlank() && it != "0" }
            val name = text(s.optString("name").ifBlank { s.optString("title", "") })
            if (name.isBlank()) continue
            val singer = text(s.optString("singer").ifBlank { s.optString("artist", "未知歌手") })
            val album = text(s.optString("albumName").ifBlank { s.optString("album", "") })
            val duration = parseDurationMs(s)
            val cover = normalizeImageUrl(s.clean("cover", "img", "pic", "picUrl", "albumpic", "imgurl"))
            val sSource = s.optString("source", source).ifBlank { source }
            val id = "lemon_online_${sSource}_${platformId ?: stableFallbackSongId(name, singer, album, duration)}"
            if (!seen.add(id)) continue
            out.add(
                UnifiedSong(
                    id = id, title = name, artist = singer, album = album, durationMs = duration, coverUrl = cover,
                    streamUrl = "", serverId = "lemon_online", format = "mp3", relativeFolderPath = null, rawMetaJson = s.toString()
                )
            )
        }
        return out
    }

    /** 列表（榜单/歌单/专辑/新歌，对齐 fetchOnlineSongList）：ID 取 songmid → id */
    fun listSongs(data: JSONObject?, source: String): List<UnifiedSong> {
        val list = data?.optJSONArray("list") ?: return emptyList()
        val info = data.optJSONObject("info")
        val fallbackCover = normalizeImageUrl(info?.clean("img", "pic", "picUrl", "cover"))
        val out = ArrayList<UnifiedSong>(list.length())
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            val rawId = s.optString("songmid").ifBlank { s.optString("id") }
            val platformId = rawId.takeIf { it.isNotBlank() && it != "0" }
            val title = text(s.optString("name").ifBlank { s.optString("title", "未知曲目") })
            val singer = text(s.optString("singer").ifBlank { s.optString("artist", "未知歌手") })
            val album = text(s.optString("albumName").ifBlank { s.optString("album", "在线精选") })
            val duration = parseDurationMs(s)
            var cover = s.clean("cover", "img", "pic", "picUrl", "pic_url", "albumpic", "album_pic", "albumPic", "imgurl", "coverUrl")
            if (cover.isBlank()) cover = s.optJSONArray("albumImgs")?.optJSONObject(0)?.clean("img", "webpImg", "pic").orEmpty()
            if (cover.isBlank()) cover = s.clean("web_albumpic_short", "web_artistpic_short", "hts_MVPIC", "pic500", "pic300", "pic120")
            cover = normalizeImageUrl(cover).ifBlank { fallbackCover }
            val sSource = s.optString("source", source).ifBlank { source }
            out.add(
                UnifiedSong(
                    id = "lemon_online_${sSource}_${platformId ?: stableFallbackSongId(title, singer, album, duration)}",
                    title = title, artist = singer, album = album, durationMs = duration, coverUrl = cover,
                    streamUrl = "", serverId = "lemon_online", format = "mp3", relativeFolderPath = null, rawMetaJson = s.toString()
                )
            )
        }
        return out
    }

    /** 推荐歌单 / 歌单搜索 → lemon_rec_{平台}_{id} */
    fun playlists(data: JSONObject?, source: String): List<UnifiedPlaylist> {
        val list = data?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<UnifiedPlaylist>(list.length())
        val seen = HashSet<String>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("id").ifBlank { item.optString("play_id") }
            if (id.isBlank() || id == "0") continue
            val name = text(item.optString("name").ifBlank { item.optString("title", "精选推荐") })
            val cover = normalizeImageUrl(item.clean("cover", "img", "pic", "coverImgUrl", "picUrl", "img2", "txt5", "pic_url", "image"))
            val unifiedId = "lemon_rec_${item.optString("source", source).ifBlank { source }}_$id"
            if (!seen.add(unifiedId)) continue
            out.add(
                UnifiedPlaylist(
                    id = unifiedId, name = name, coverUrl = cover, songCount = item.optInt("total", item.optInt("count", 0)),
                    isOnline = true, serverId = "lemon_music", isDiscover = true
                )
            )
        }
        return out
    }

    fun toplists(data: JSONObject?, source: String): List<LemonToplist> {
        val list = data?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<LemonToplist>(list.length())
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("id").ifBlank { item.optString("topId") }
            if (id.isBlank()) continue
            out.add(
                LemonToplist(
                    id = id,
                    name = text(item.optString("name").ifBlank { item.optString("title", "热歌榜") }),
                    coverUrl = normalizeImageUrl(item.clean("cover", "img", "pic", "picUrl", "pic_url", "coverUrl")),
                    updateFrequency = item.optString("updateTime").ifBlank { item.optString("updateFrequency").ifBlank { item.optString("period", "每日更新") } },
                    source = source
                )
            )
        }
        return out
    }

    /** 新碟 / 专辑搜索 → lemon_discover_album_{平台}_{id} */
    fun albums(data: JSONObject?, source: String): List<UnifiedAlbum> {
        val list = data?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<UnifiedAlbum>(list.length())
        val seen = HashSet<String>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("id").ifBlank { item.optString("albumId") }
            if (id.isBlank() || id == "0") continue
            val name = text(item.optString("name").ifBlank { item.optString("title", "") })
            if (name.isBlank()) continue
            var cover = item.clean("cover", "img", "pic", "picUrl", "pic_url", "album_pic", "albumpic", "albumPic", "imgurl", "coverUrl", "hts_img")
            if (cover.isBlank() && source == "tx" && id.length >= 10 && id.all { it.isLetterOrDigit() }) {
                cover = "https://y.gtimg.cn/music/photo_new/T002R500x500M000$id.jpg"
            }
            val unifiedId = "lemon_discover_album_${item.optString("source", source).ifBlank { source }}_$id"
            if (!seen.add(unifiedId)) continue
            out.add(
                UnifiedAlbum(
                    id = unifiedId,
                    title = name,
                    artist = text(item.optString("artist").ifBlank { item.optString("singer", "未知歌手") }),
                    coverUrl = normalizeImageUrl(cover),
                    songCount = item.optInt("total", item.optInt("count", item.optInt("songCount", 0))),
                    year = item.optString("publishTime").ifBlank { item.optString("year") }.take(4).toIntOrNull()
                )
            )
        }
        return out
    }
}
