package com.lm.player.core.network

import android.util.Log
import com.lm.player.core.media.LrcParser
import com.lm.player.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 柠檬音乐 (Lemon Music) 原生网络协议适配器
 * 对接开源项目 https://github.com/jia070310/lemon-muisc
 * 支持：
 * 1. Bearer Token 登录鉴权与免密会话校验 (/api/auth/login, /api/auth/me)
 * 2. 音乐库全量与分页曲目获取 (/api/library/tracks)
 * 3. 专辑与歌手聚合查询 (/api/library/albums, /api/library/artists)
 * 4. 自定义与推荐歌单获取 (/api/library/playlists, /api/playlist/recommend)
 * 5. 音频 HTTP 206 Range 低延迟串流 (/api/play/local)
 * 6. 内嵌高保真专辑封面提取 (/api/tag/cover)
 * 7. 本地与云端同步滚动 LRC 歌词解析 (/api/play/lyric)
 * 8. 全网多平台在线搜索与试听串流 (/api/search, /api/play/url)
 */
class LemonMusicProtocol(
    private val client: OkHttpClient,
    private val serverUrl: String,
    private val username: String,
    private var tokenOrPasswordPlain: String
) : NasMusicProtocol {

    private val cleanBase = serverUrl.trim().trimEnd('/')
    private var authToken: String = ""

    companion object {
        private const val TAG = "LemonMusicProtocol"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // 内存高速全局映射缓存：songId <-> NAS 物理绝对路径与曲目实体 (跨实例共享)
        private val songIdToPathMap = ConcurrentHashMap<String, String>()
        private val songIdToSongMap = ConcurrentHashMap<String, UnifiedSong>()

        fun md5(input: String): String {
            val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    init {
        // 如果外部传入的本身就是 Token (形如 session-xxx 或大于 20 位的字符且不包含常规弱密码特征)，可作为初始 token
        if (tokenOrPasswordPlain.startsWith("lemon-") || tokenOrPasswordPlain.length >= 32) {
            authToken = tokenOrPasswordPlain
        }
    }

    private fun extractRelativeFolderPath(filePath: String, artist: String, album: String): String {
        if (filePath.isNotBlank()) {
            val normalized = filePath.replace('\\', '/')
            val parentDir = normalized.substringBeforeLast('/', "")
            if (parentDir.isNotBlank()) {
                val segments = parentDir.split('/').filter {
                    it.isNotBlank() &&
                            !it.equals("music", ignoreCase = true) &&
                            !it.equals("musics", ignoreCase = true) &&
                            !it.equals("media", ignoreCase = true) &&
                            !it.equals("mnt", ignoreCase = true) &&
                            !it.equals("storage", ignoreCase = true) &&
                            !it.equals("vol1", ignoreCase = true) &&
                            !it.equals("vol2", ignoreCase = true) &&
                            !it.equals("volume1", ignoreCase = true) &&
                            !it.equals("volume2", ignoreCase = true) &&
                            !it.equals("share", ignoreCase = true) &&
                            !it.startsWith("volume", ignoreCase = true) &&
                            !it.contains(":")
                }
                if (segments.isNotEmpty()) {
                    return segments.joinToString("/")
                }
            }
        }
        val safeArtist = if (artist.isNotBlank() && !artist.contains("未知")) artist else ""
        val safeAlbum = if (album.isNotBlank() && !album.contains("未知")) album else ""
        return when {
            safeArtist.isNotBlank() && safeAlbum.isNotBlank() -> "$safeArtist/$safeAlbum"
            safeArtist.isNotBlank() -> safeArtist
            else -> "LemonMusic"
        }
    }

    /**
     * 登录鉴权：支持快速会话校验与用户名密码登录
     */
    override suspend fun authenticate(config: ServerConfig): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 1. 若已有 token，优先测试 /api/auth/me 免密验证
            val candidateToken = authToken.ifBlank { tokenOrPasswordPlain }
            if (candidateToken.isNotBlank() && candidateToken.length >= 20) {
                val meReq = Request.Builder()
                    .url("$cleanBase/api/auth/me")
                    .header("Authorization", "Bearer $candidateToken")
                    .get()
                    .build()
                client.newCall(meReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val json = JSONObject(body)
                        if (json.optJSONObject("user") != null || json.optString("token").isNotBlank()) {
                            authToken = candidateToken
                            Log.i(TAG, "Lemon Music session token validated successfully")
                            return@withContext Result.success(authToken)
                        }
                    }
                }
            }

            // 2. 账号密码登录 /api/auth/login
            val loginPayload = JSONObject().apply {
                put("username", username.ifBlank { config.username })
                put("password", tokenOrPasswordPlain.ifBlank { config.tokenOrApiKey })
                put("remember", true)
            }
            val request = Request.Builder()
                .url("$cleanBase/api/auth/login")
                .post(loginPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    val errMsg = try { JSONObject(body).optString("error", "登录失败 (HTTP ${resp.code})") } catch (_: Exception) { "HTTP ${resp.code}" }
                    return@withContext Result.failure(Exception(errMsg))
                }
                val json = JSONObject(body)
                val token = json.optString("token")
                if (token.isNotBlank()) {
                    authToken = token
                    Log.i(TAG, "Lemon Music login succeeded for user: $username")
                    Result.success(token)
                } else {
                    Result.failure(Exception(json.optString("error", "未能获取登录令牌")))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lemon Music authentication failed", e)
            Result.failure(e)
        }
    }

    private fun checkAuth() {
        if (authToken.isBlank() && tokenOrPasswordPlain.isNotBlank() && tokenOrPasswordPlain.length >= 20) {
            authToken = tokenOrPasswordPlain
        }
    }

    /**
     * 同步全量音乐库曲目 (/api/library/tracks?all=1)
     */
    override suspend fun getSongList(offset: Int, limit: Int): Result<List<UnifiedSong>> = withContext(Dispatchers.IO) {
        try {
            checkAuth()
            val url = "$cleanBase/api/library/tracks?all=1"
            val req = Request.Builder()
                .url(url)
                .apply {
                    if (authToken.isNotBlank()) header("Authorization", "Bearer $authToken")
                }
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("获取曲库失败 (HTTP ${resp.code})"))
                }
                val json = JSONObject(body)
                val dataArr = json.optJSONArray("data") ?: JSONArray()
                val resultList = ArrayList<UnifiedSong>(dataArr.length())

                for (i in 0 until dataArr.length()) {
                    val item = dataArr.optJSONObject(i) ?: continue
                    val filePath = item.optString("filePath").trim()
                    if (filePath.isBlank()) continue

                    val fileName = item.optString("fileName")
                    val rawTitle = item.optString("title").ifBlank { item.optString("parsedTitle") }
                    val title = rawTitle.ifBlank { fileName.substringBeforeLast('.', fileName) }
                    val rawArtist = item.optString("artist").ifBlank { item.optString("parsedArtist") }
                    val artist = rawArtist.ifBlank { "未知歌手" }
                    val album = item.optString("album").ifBlank { "未知专辑" }
                    val durationSec = item.optDouble("duration", 0.0)
                    val durationMs = (durationSec * 1000).toLong()
                    val format = item.optString("format", "flac").lowercase()
                    val sizeBytes = item.optLong("size", 0L)
                    val bitRate = if (durationSec > 0 && sizeBytes > 0) {
                        ((sizeBytes * 8) / durationSec / 1000).toInt().coerceIn(128, 1411)
                    } else {
                        if (format in listOf("flac", "ape", "wav")) 960 else 320
                    }

                    val songId = "lemon_${md5(filePath)}"
                    val song = UnifiedSong(
                        id = songId,
                        title = title,
                        artist = artist,
                        artistId = "lemon_artist_${md5(artist)}",
                        album = album,
                        albumId = "lemon_album_${md5("$artist/$album")}",
                        durationMs = durationMs,
                        coverUrl = getCoverArtUrl(songId),
                        streamUrl = getStreamUrl(songId),
                        serverId = "lemon_music",
                        localFilePath = null,
                        downloadStatus = DownloadStatus.NOT_DOWNLOADED,
                        bitRate = bitRate,
                        format = format,
                        isFavorite = false,
                        relativeFolderPath = extractRelativeFolderPath(filePath, artist, album)
                    )

                    songIdToPathMap[songId] = filePath
                    songIdToSongMap[songId] = song
                    resultList.add(song)
                }

                Log.i(TAG, "Lemon Music parsed ${resultList.size} tracks from library")
                Result.success(resultList)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch Lemon Music song list", e)
            Result.failure(e)
        }
    }

    fun getRecentlyAdded(limit: Int = 30): Result<List<UnifiedSong>> {
        val list = songIdToSongMap.values.toList().takeLast(limit).reversed()
        return Result.success(list)
    }

    fun getRecentlyPlayed(limit: Int = 30): Result<List<UnifiedSong>> {
        val list = songIdToSongMap.values.toList().take(limit)
        return Result.success(list)
    }

    /**
     * 获取专辑聚合列表 (/api/library/albums)
     */
    override suspend fun getAlbums(offset: Int, limit: Int): Result<List<UnifiedAlbum>> = withContext(Dispatchers.IO) {
        try {
            checkAuth()
            val url = "$cleanBase/api/library/albums?page=1&limit=500"
            val req = Request.Builder()
                .url(url)
                .apply { if (authToken.isNotBlank()) header("Authorization", "Bearer $authToken") }
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("获取专辑失败 (HTTP ${resp.code})"))
                val json = JSONObject(body)
                val dataArr = json.optJSONArray("data") ?: JSONArray()
                val albums = ArrayList<UnifiedAlbum>(dataArr.length())

                for (i in 0 until dataArr.length()) {
                    val item = dataArr.optJSONObject(i) ?: continue
                    val name = item.optString("name").ifBlank { item.optString("album", "未知专辑") }
                    val artist = item.optString("artist", "未知歌手")
                    val count = item.optInt("count", 0)
                    val samplePath = item.optString("samplePath")
                    val yearStr = item.optString("year")
                    val year = yearStr.toIntOrNull()

                    val sampleSongId = if (samplePath.isNotBlank()) "lemon_${md5(samplePath)}" else ""
                    if (samplePath.isNotBlank()) songIdToPathMap[sampleSongId] = samplePath

                    albums.add(
                        UnifiedAlbum(
                            id = "lemon_album_${md5("$artist/$name")}",
                            title = name,
                            artist = artist,
                            coverUrl = if (sampleSongId.isNotBlank()) getCoverArtUrl(sampleSongId) else "",
                            songCount = count,
                            year = year
                        )
                    )
                }
                Result.success(albums)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch Lemon Music albums", e)
            Result.failure(e)
        }
    }

    /**
     * 获取歌手聚合列表 (/api/library/artists)
     */
    override suspend fun getArtists(): Result<List<UnifiedArtist>> = withContext(Dispatchers.IO) {
        try {
            checkAuth()
            val url = "$cleanBase/api/library/artists?page=1&limit=500"
            val req = Request.Builder()
                .url(url)
                .apply { if (authToken.isNotBlank()) header("Authorization", "Bearer $authToken") }
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("获取歌手失败 (HTTP ${resp.code})"))
                val json = JSONObject(body)
                val dataArr = json.optJSONArray("data") ?: JSONArray()
                val artists = ArrayList<UnifiedArtist>(dataArr.length())

                for (i in 0 until dataArr.length()) {
                    val item = dataArr.optJSONObject(i) ?: continue
                    val name = item.optString("name", "未知歌手")
                    val count = item.optInt("count", 0)
                    val samplePath = item.optString("samplePath")
                    val sampleSongId = if (samplePath.isNotBlank()) "lemon_${md5(samplePath)}" else ""
                    if (samplePath.isNotBlank()) songIdToPathMap[sampleSongId] = samplePath

                    artists.add(
                        UnifiedArtist(
                            id = "lemon_artist_${md5(name)}",
                            name = name,
                            avatarUrl = if (sampleSongId.isNotBlank()) getCoverArtUrl(sampleSongId) else "",
                            albumCount = count
                        )
                    )
                }
                Result.success(artists)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch Lemon Music artists", e)
            Result.failure(e)
        }
    }

    /**
     * 获取歌单列表：包含柠檬自建歌单与发现推荐歌单
     */
    override suspend fun getPlaylists(): Result<List<UnifiedPlaylist>> = withContext(Dispatchers.IO) {
        try {
            checkAuth()
            val playlists = ArrayList<UnifiedPlaylist>()

            // 仅获取用户自建歌单 (/api/library/playlists)，彻底还原纯净资料库
            val customReq = Request.Builder()
                .url("$cleanBase/api/library/playlists")
                .apply { if (authToken.isNotBlank()) header("Authorization", "Bearer $authToken") }
                .get()
                .build()
            client.newCall(customReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    val list = json.optJSONArray("data") ?: JSONArray()
                    for (i in 0 until list.length()) {
                        val pl = list.optJSONObject(i) ?: continue
                        val id = pl.optString("id", "lemon_pl_$i")
                        val name = pl.optString("name", "未命名歌单")
                        val coverUrl = pl.optString("coverUrl")
                        val tracks = pl.optJSONArray("tracks") ?: pl.optJSONArray("paths") ?: JSONArray()
                        playlists.add(
                            UnifiedPlaylist(
                                id = id,
                                name = name,
                                coverUrl = coverUrl,
                                songCount = tracks.length(),
                                isOnline = true,
                                serverId = "lemon_music",
                                isDiscover = false
                            )
                        )
                    }
                }
            }

            Result.success(playlists)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch playlists", e)
            Result.failure(e)
        }
    }

    /**
     * 发现专属：获取在线推荐歌单 (/api/playlist/recommend)
     */
    suspend fun getDiscoverRecommendPlaylists(
        source: String = "kw",
        page: Int = 1,
        limit: Int = 30
    ): Result<List<UnifiedPlaylist>> = withContext(Dispatchers.IO) {
        try {
            checkAuth()
            val url = "$cleanBase/api/playlist/recommend?source=$source&sort=hot&page=$page&limit=$limit"
            val req = Request.Builder()
                .url(url)
                .apply { if (authToken.isNotBlank()) header("Authorization", "Bearer $authToken") }
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("获取推荐歌单失败"))
                val json = JSONObject(body)
                val recData = json.optJSONObject("data")
                val list = recData?.optJSONArray("list") ?: json.optJSONArray("data") ?: JSONArray()
                val playlists = ArrayList<UnifiedPlaylist>(list.length())
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val id = item.optString("id").ifBlank { item.optString("play_id") }
                    val name = item.optString("name").ifBlank { item.optString("title", "精选推荐") }
                    val cover = item.optString("img").ifBlank { item.optString("pic", "") }
                    val count = item.optInt("total", 30)
                    if (id.isNotBlank()) {
                        playlists.add(
                            UnifiedPlaylist(
                                id = "lemon_rec_${item.optString("source", source)}_$id",
                                name = name,
                                coverUrl = cover,
                                songCount = count,
                                isOnline = true,
                                serverId = "lemon_music",
                                isDiscover = true
                            )
                        )
                    }
                }
                Result.success(playlists)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 发现专属：获取官方排行榜列表 (/api/discover/toplists)
     */
    suspend fun getDiscoverToplists(source: String = "kw"): Result<List<LemonToplist>> = withContext(Dispatchers.IO) {
        try {
            checkAuth()
            val url = "$cleanBase/api/discover/toplists?source=$source"
            val req = Request.Builder()
                .url(url)
                .apply { if (authToken.isNotBlank()) header("Authorization", "Bearer $authToken") }
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("获取排行榜失败"))
                val json = JSONObject(body)
                val dataObj = json.optJSONObject("data")
                val list = dataObj?.optJSONArray("list") ?: json.optJSONArray("data") ?: JSONArray()
                val toplists = ArrayList<LemonToplist>(list.length())
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val id = item.optString("id").ifBlank { item.optString("topId") }
                    val name = item.optString("name").ifBlank { item.optString("title", "热歌榜") }
                    val cover = item.optString("img").ifBlank { item.optString("pic", "") }
                    val updateFreq = item.optString("updateFrequency").ifBlank { item.optString("period", "每日更新") }
                    if (id.isNotBlank()) {
                        toplists.add(
                            LemonToplist(
                                id = id,
                                name = name,
                                coverUrl = cover,
                                updateFrequency = updateFreq,
                                source = source
                            )
                        )
                    }
                }
                Result.success(toplists)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 发现专属：获取排行榜单曲目 (/api/discover/toplist)
     */
    suspend fun getDiscoverToplistSongs(
        toplistId: String,
        source: String = "kw",
        limit: Int = 100
    ): Result<List<UnifiedSong>> = withContext(Dispatchers.IO) {
        val url = "$cleanBase/api/discover/toplist?source=$source&id=${URLEncoder.encode(toplistId, "UTF-8")}&limit=$limit"
        fetchOnlineSongList(url, source)
    }

    /**
     * 发现专属：获取新歌首发单曲列表 (/api/discover/new-songs)
     */
    suspend fun getDiscoverNewSongs(
        source: String = "kw",
        region: String = "",
        limit: Int = 30
    ): Result<List<UnifiedSong>> = withContext(Dispatchers.IO) {
        val url = "$cleanBase/api/discover/new-songs?source=$source&region=$region&limit=$limit"
        fetchOnlineSongList(url, source)
    }

    /**
     * 在线单曲列表公共解析器
     */
    private suspend fun fetchOnlineSongList(url: String, source: String): Result<List<UnifiedSong>> = withContext(Dispatchers.IO) {
        try {
            checkAuth()
            val req = Request.Builder()
                .url(url)
                .apply { if (authToken.isNotBlank()) header("Authorization", "Bearer $authToken") }
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("获取在线歌曲失败"))
                val json = JSONObject(body)
                val dataObj = json.optJSONObject("data")
                val list = dataObj?.optJSONArray("list") ?: json.optJSONArray("data") ?: JSONArray()
                val songs = ArrayList<UnifiedSong>(list.length())
                for (i in 0 until list.length()) {
                    val s = list.optJSONObject(i) ?: continue
                    val songId = s.optString("songmid").ifBlank { s.optString("id", "online_$i") }
                    val title = s.optString("name").ifBlank { s.optString("title", "未知曲目") }
                    val singer = s.optString("singer").ifBlank { s.optString("artist", "未知歌手") }
                    val albumName = s.optString("albumName").ifBlank { s.optString("album", "在线精选") }
                    val duration = s.optDouble("interval", s.optDouble("duration", 0.0))
                    val cover = s.optString("img").ifBlank { s.optString("pic", "") }
                    val sSource = s.optString("source", source)
                    val unifiedId = "lemon_online_${sSource}_$songId"

                    songs.add(
                        UnifiedSong(
                            id = unifiedId,
                            title = title,
                            artist = singer,
                            album = albumName,
                            durationMs = (duration * 1000).toLong(),
                            coverUrl = cover,
                            streamUrl = "", // 在线试听通过 resolveOnlineStreamUrl 换取
                            serverId = "lemon_online",
                            format = "mp3"
                        )
                    )
                }
                Result.success(songs)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取指定歌单内的歌曲列表
     */
    override suspend fun getPlaylistSongs(playlistId: String): Result<List<UnifiedSong>> = withContext(Dispatchers.IO) {
        try {
            checkAuth()
            // A. 若是发现推荐歌单：调用 /api/playlist?url=id&source=...
            if (playlistId.startsWith("lemon_rec_")) {
                val parts = playlistId.removePrefix("lemon_rec_").split("_", limit = 2)
                val source = parts.getOrNull(0) ?: "kw"
                val rawId = parts.getOrNull(1) ?: playlistId
                val url = "$cleanBase/api/playlist?source=$source&url=${URLEncoder.encode(rawId, "UTF-8")}"
                return@withContext fetchOnlineSongList(url, source)
            }

            // B. 若是排行榜单：调用 /api/discover/toplist?id=...&source=...
            if (playlistId.startsWith("lemon_toplist_")) {
                val parts = playlistId.removePrefix("lemon_toplist_").split("_", limit = 2)
                val source = parts.getOrNull(0) ?: "kw"
                val rawId = parts.getOrNull(1) ?: playlistId
                val url = "$cleanBase/api/discover/toplist?source=$source&id=${URLEncoder.encode(rawId, "UTF-8")}&limit=100"
                return@withContext fetchOnlineSongList(url, source)
            }

            // B. 用户自建歌单：查出自建歌单的 paths，调用 /api/library/tracks/by-paths
            val customReq = Request.Builder()
                .url("$cleanBase/api/library/playlists")
                .apply { if (authToken.isNotBlank()) header("Authorization", "Bearer $authToken") }
                .get()
                .build()
            var targetPaths: List<String> = emptyList()
            client.newCall(customReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    val list = json.optJSONArray("data") ?: JSONArray()
                    for (i in 0 until list.length()) {
                        val pl = list.optJSONObject(i) ?: continue
                        if (pl.optString("id") == playlistId) {
                            val arr = pl.optJSONArray("paths") ?: pl.optJSONArray("tracks") ?: JSONArray()
                            val pList = ArrayList<String>()
                            for (j in 0 until arr.length()) {
                                val item = arr.opt(j)
                                if (item is String) pList.add(item)
                                else if (item is JSONObject) pList.add(item.optString("filePath"))
                            }
                            targetPaths = pList
                            break
                        }
                    }
                }
            }

            if (targetPaths.isEmpty()) {
                return@withContext Result.success(emptyList())
            }

            // 批量获取路径曲目元数据
            val payload = JSONObject().apply {
                put("paths", JSONArray(targetPaths))
            }
            val byPathsReq = Request.Builder()
                .url("$cleanBase/api/library/tracks/by-paths")
                .apply { if (authToken.isNotBlank()) header("Authorization", "Bearer $authToken") }
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(byPathsReq).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("获取歌单曲目失败"))
                val json = JSONObject(body)
                val arr = json.optJSONArray("data") ?: JSONArray()
                val songs = ArrayList<UnifiedSong>(arr.length())
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val filePath = item.optString("filePath").trim()
                    if (filePath.isBlank()) continue
                    val fileName = item.optString("fileName")
                    val title = item.optString("title").ifBlank { fileName.substringBeforeLast('.', fileName) }
                    val artist = item.optString("artist", "未知歌手")
                    val album = item.optString("album", "未知专辑")
                    val durationMs = (item.optDouble("duration", 0.0) * 1000).toLong()
                    val songId = "lemon_${md5(filePath)}"

                    val song = UnifiedSong(
                        id = songId,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = durationMs,
                        coverUrl = getCoverArtUrl(songId),
                        streamUrl = getStreamUrl(songId),
                        serverId = "lemon_music",
                        format = item.optString("format", "flac"),
                        relativeFolderPath = extractRelativeFolderPath(filePath, artist, album)
                    )
                    songIdToPathMap[songId] = filePath
                    songIdToSongMap[songId] = song
                    songs.add(song)
                }
                Result.success(songs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get playlist songs", e)
            Result.failure(e)
        }
    }

    /**
     * 文件夹层级树构建
     */
    override suspend fun getFolders(parentId: String?): Result<List<ServerFolderItem>> = withContext(Dispatchers.IO) {
        try {
            // 从当前已缓存的 song 物理路径自动生成多级目录树
            val folderMap = LinkedHashMap<String, Int>()
            val rootPrefix = parentId?.trimEnd('/') ?: ""

            songIdToPathMap.values.forEach { path ->
                val norm = path.replace('\\', '/')
                if (rootPrefix.isBlank()) {
                    val topDir = norm.substringBefore('/', "").ifBlank { "Music" }
                    folderMap[topDir] = (folderMap[topDir] ?: 0) + 1
                } else if (norm.startsWith("$rootPrefix/")) {
                    val sub = norm.removePrefix("$rootPrefix/")
                    val childDir = sub.substringBefore('/')
                    if (childDir.isNotBlank()) {
                        val fullChild = "$rootPrefix/$childDir"
                        folderMap[fullChild] = (folderMap[fullChild] ?: 0) + 1
                    }
                }
            }

            val list = folderMap.map { (dirPath, count) ->
                ServerFolderItem(
                    id = dirPath,
                    name = dirPath.substringAfterLast('/'),
                    isFolder = true,
                    parentId = parentId,
                    childCount = count,
                    coverUrl = ""
                )
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getFolderSongs(folderId: String): Result<List<UnifiedSong>> = withContext(Dispatchers.IO) {
        try {
            val normFolder = folderId.replace('\\', '/').trimEnd('/')
            val songs = songIdToSongMap.values.filter { song ->
                val path = songIdToPathMap[song.id]?.replace('\\', '/') ?: ""
                path.startsWith("$normFolder/")
            }
            Result.success(songs)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取音频 Range 流媒体播放链接
     */
    override fun getStreamUrl(songId: String, maxBitrate: Int?): String {
        val path = songIdToPathMap[songId] ?: ""
        if (path.isBlank()) return ""
        val enc = try { URLEncoder.encode(path, "UTF-8") } catch (_: Exception) { path }
        return if (authToken.isNotBlank()) {
            "$cleanBase/api/play/local?path=$enc&token=$authToken"
        } else {
            "$cleanBase/api/play/local?path=$enc"
        }
    }

    /**
     * 获取内嵌专辑封面图链接
     */
    override fun getCoverArtUrl(mediaId: String, size: Int): String {
        val path = songIdToPathMap[mediaId] ?: ""
        if (path.isBlank()) return ""
        val enc = try { URLEncoder.encode(path, "UTF-8") } catch (_: Exception) { path }
        return if (authToken.isNotBlank()) {
            "$cleanBase/api/tag/cover?path=$enc&token=$authToken"
        } else {
            "$cleanBase/api/tag/cover?path=$enc"
        }
    }

    /**
     * 获取歌词 (/api/play/lyric)
     */
    override suspend fun getLyrics(songId: String): Result<LyricResult> = withContext(Dispatchers.IO) {
        try {
            checkAuth()
            val song = songIdToSongMap[songId]
            val path = songIdToPathMap[songId] ?: ""

            val payload = JSONObject().apply {
                put("name", song?.title ?: "")
                put("singer", song?.artist ?: "")
                put("filePath", path)
            }
            val req = Request.Builder()
                .url("$cleanBase/api/play/lyric")
                .apply { if (authToken.isNotBlank()) header("Authorization", "Bearer $authToken") }
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("歌词获取失败"))
                val json = JSONObject(body)
                val lyricStr = json.optString("lyric").ifBlank { json.optString("ylyric") }
                if (lyricStr.isNotBlank()) {
                    val parsed = LrcParser.parse(lyricStr)
                    return@withContext Result.success(parsed)
                }
                Result.failure(Exception("歌词为空"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun scrobble(songId: String, submission: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        Result.success(Unit)
    }

    // ==================== 拓展能力：多平台在线全网检索 ====================

    /**
     * 全网在线检索歌曲（落雪引擎）
     * 支持酷我、酷狗、QQ音乐、网易云等平台聚合
     */
    suspend fun searchOnline(
        keyword: String,
        source: String = "kw",
        page: Int = 1,
        limit: Int = 30
    ): Result<List<UnifiedSong>> = withContext(Dispatchers.IO) {
        try {
            checkAuth()
            val encKw = URLEncoder.encode(keyword, "UTF-8")
            val url = "$cleanBase/api/search?keyword=$encKw&source=$source&page=$page&limit=$limit"
            val req = Request.Builder()
                .url(url)
                .apply { if (authToken.isNotBlank()) header("Authorization", "Bearer $authToken") }
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("在线搜索失败"))
                val json = JSONObject(body)
                val dataObj = json.optJSONObject("data")
                val list = dataObj?.optJSONArray("list") ?: json.optJSONArray("data") ?: JSONArray()
                val songs = ArrayList<UnifiedSong>(list.length())

                for (i in 0 until list.length()) {
                    val s = list.optJSONObject(i) ?: continue
                    val songId = s.optString("songmid").ifBlank { s.optString("id", "s_$i") }
                    val name = s.optString("name").ifBlank { s.optString("title", "") }
                    if (name.isBlank()) continue
                    val singer = s.optString("singer").ifBlank { s.optString("artist", "未知歌手") }
                    val albumName = s.optString("albumName").ifBlank { s.optString("album", "") }
                    val duration = s.optDouble("interval", s.optDouble("duration", 0.0))
                    val cover = s.optString("img").ifBlank { s.optString("pic", "") }
                    val sSource = s.optString("source", source)

                    val unifiedId = "lemon_online_${sSource}_$songId"
                    songs.add(
                        UnifiedSong(
                            id = unifiedId,
                            title = name,
                            artist = singer,
                            album = albumName,
                            durationMs = (duration * 1000).toLong(),
                            coverUrl = cover,
                            streamUrl = "", // 在线试听通过 resolveOnlineStreamUrl 换取
                            serverId = "lemon_online",
                            format = "mp3"
                        )
                    )
                }
                Result.success(songs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search online songs", e)
            Result.failure(e)
        }
    }

    /**
     * 换取在线曲目播放流地址 (/api/play/url)
     */
    suspend fun resolveOnlineStreamUrl(
        songId: String,
        source: String = "kw",
        quality: String = "128k"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            checkAuth()
            val cleanId = songId.removePrefix("lemon_online_")
            val actualSource = if (cleanId.contains("_")) cleanId.substringBefore("_") else source
            val rawId = if (cleanId.contains("_")) cleanId.substringAfter("_") else cleanId
            val payload = JSONObject().apply {
                put("songId", rawId)
                put("source", actualSource)
                put("quality", quality)
            }
            val req = Request.Builder()
                .url("$cleanBase/api/play/url")
                .apply { if (authToken.isNotBlank()) header("Authorization", "Bearer $authToken") }
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("获取在线播放地址失败"))
                val json = JSONObject(body)
                val streamUrl = json.optString("url")
                if (streamUrl.isNotBlank()) {
                    val finalUrl = if (streamUrl.startsWith("/")) "$cleanBase$streamUrl" else streamUrl
                    Result.success(finalUrl)
                } else {
                    Result.failure(Exception("未获得播放直链"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
