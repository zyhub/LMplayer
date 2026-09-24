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

        // 发现页短期内存快取 (缓存 5 分钟，显著提速主页切换与展示)
        private val discoverPlaylistsCache = ConcurrentHashMap<String, Pair<Long, List<UnifiedPlaylist>>>()
        private val discoverToplistsCache = ConcurrentHashMap<String, Pair<Long, List<LemonToplist>>>()
        private val discoverNewSongsCache = ConcurrentHashMap<String, Pair<Long, List<UnifiedSong>>>()
        private const val DISCOVER_CACHE_TTL_MS = 5 * 60 * 1000L

        fun md5(input: String): String {
            val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun getServerFilePath(songId: String, streamUrl: String? = null): String? {
            songIdToPathMap[songId]?.let { return it }
            val song = songIdToSongMap[songId]
            val url = streamUrl ?: song?.streamUrl
            if (url != null && url.contains("path=")) {
                try {
                    val enc = url.substringAfter("path=").substringBefore("&")
                    return java.net.URLDecoder.decode(enc, "UTF-8")
                } catch (_: Exception) {}
            }
            return null
        }

        fun registerServerFilePath(songId: String, filePath: String) {
            if (songId.isNotBlank() && filePath.isNotBlank()) {
                songIdToPathMap[songId] = filePath
            }
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

    suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val ok = ensureAuthenticated()
            if (ok) Result.success(true) else Result.failure(Exception("鉴权失败"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getAuthToken(): String = authToken

    fun setAuthToken(token: String) {
        if (token.isNotBlank()) authToken = token
    }

    suspend fun ensureAuthenticated(): Boolean = withContext(Dispatchers.IO) {
        if (authToken.isNotBlank()) return@withContext true
        if (tokenOrPasswordPlain.startsWith("lemon-") || tokenOrPasswordPlain.length >= 32) {
            authToken = tokenOrPasswordPlain
            return@withContext true
        }
        val config = ServerConfig(
            id = "lemon_music",
            name = "Lemon Music",
            type = ServerType.LEMON_MUSIC,
            serverUrl = serverUrl,
            username = username,
            tokenOrApiKey = tokenOrPasswordPlain
        )
        val res = authenticate(config)
        res.isSuccess
    }

    private fun newAuthRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .apply {
                if (authToken.isNotBlank()) {
                    header("Authorization", "Bearer $authToken")
                }
            }
    }

    /**
     * 同步全量音乐库曲目 (/api/library/tracks?all=1)
     */
    override suspend fun getSongList(offset: Int, limit: Int): Result<List<UnifiedSong>> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val url = "$cleanBase/api/library/tracks?all=1"
            val req = newAuthRequest(url).get().build()

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

                // 补充查询 /api/download/list 中已完成下载的任务，避免服务端后台刮削或扫描延迟导致曲目暂时缺失
                try {
                    val dlListReq = newAuthRequest("$cleanBase/api/download/list").get().build()
                    client.newCall(dlListReq).execute().use { dlResp ->
                        if (dlResp.isSuccessful) {
                            val dlBody = dlResp.body?.string() ?: ""
                            val dlArr = JSONArray(dlBody)
                            val existingPaths = songIdToPathMap.values.toSet()
                            for (j in 0 until dlArr.length()) {
                                val dlItem = dlArr.optJSONObject(j) ?: continue
                                val status = dlItem.optString("status")
                                val filePath = dlItem.optString("filePath").trim()
                                if ((status == "completed" || status == "finished") && filePath.isNotBlank() && !existingPaths.contains(filePath)) {
                                    val name = dlItem.optString("name", "未命名歌曲")
                                    val singer = dlItem.optString("singer", "未知歌手")
                                    val album = dlItem.optString("album", "未知专辑")
                                    val songId = "lemon_${md5(filePath)}"
                                    val song = UnifiedSong(
                                        id = songId,
                                        title = name,
                                        artist = singer,
                                        artistId = "lemon_artist_${md5(singer)}",
                                        album = album,
                                        albumId = "lemon_album_${md5("$singer/$album")}",
                                        durationMs = (dlItem.optDouble("interval", 0.0) * 1000).toLong(),
                                        coverUrl = getCoverArtUrl(songId),
                                        streamUrl = getStreamUrl(songId),
                                        serverId = "lemon_music",
                                        localFilePath = null,
                                        downloadStatus = DownloadStatus.NOT_DOWNLOADED,
                                        bitRate = 320,
                                        format = filePath.substringAfterLast('.', "mp3").lowercase(),
                                        isFavorite = false,
                                        relativeFolderPath = extractRelativeFolderPath(filePath, singer, album)
                                    )
                                    songIdToPathMap[songId] = filePath
                                    songIdToSongMap[songId] = song
                                    resultList.add(song)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to merge completed tasks from /api/download/list", e)
                }

                Log.i(TAG, "Lemon Music parsed ${resultList.size} tracks from library (including completed download tasks)")
                Result.success(resultList)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch Lemon Music song list", e)
            Result.failure(e)
        }
    }

    /**
     * 触发服务端重新扫描音乐库 (/api/library/scan-start)
     */
    suspend fun triggerServerScan(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val req = newAuthRequest("$cleanBase/api/library/scan-start")
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(req).execute().use { resp ->
                Result.success(resp.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 查询服务端后台扫描状态 (/api/library/scan-status)
     */
    suspend fun getServerScanStatus(): Result<LemonScanStatus> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val req = newAuthRequest("$cleanBase/api/library/scan-status").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("获取扫描状态失败 (HTTP ${resp.code})"))
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val scanObj = json.optJSONObject("scan")
                val isScanning = scanObj?.optBoolean("scanning", false) ?: scanObj?.optBoolean("active", false) ?: false
                val cachedCount = scanObj?.optInt("current", 0) ?: scanObj?.optInt("cachedCount", 0) ?: 0
                val pendingCount = scanObj?.optInt("pendingCount", 0) ?: 0
                val total = scanObj?.optInt("total", cachedCount + pendingCount) ?: cachedCount
                Result.success(
                    LemonScanStatus(
                        isScanning = isScanning,
                        cachedCount = cachedCount,
                        pendingCount = pendingCount,
                        total = total
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取真实风格流派聚合列表 (/api/library/genres)
     */
    suspend fun getGenres(page: Int = 1, limit: Int = 100): Result<List<UnifiedGenre>> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val url = "$cleanBase/api/library/genres?page=$page&limit=$limit"
            val req = newAuthRequest(url).get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("获取风格流派失败 (HTTP ${resp.code})"))
                val json = JSONObject(body)
                val dataArr = json.optJSONArray("data") ?: JSONArray()
                val genres = ArrayList<UnifiedGenre>(dataArr.length())
                for (i in 0 until dataArr.length()) {
                    val item = dataArr.optJSONObject(i) ?: continue
                    val name = item.optString("name").trim()
                    if (name.isBlank() || name == "未知风格") continue
                    val id = item.optString("id").ifBlank { "genre_${name.hashCode()}" }
                    val trackCount = item.optInt("trackCount", item.optInt("count", 0))
                    val coverPath = item.optString("coverPath")
                    val coverUrl = if (coverPath.isNotBlank()) {
                        val sampleSongId = "lemon_${md5(coverPath)}"
                        songIdToPathMap[sampleSongId] = coverPath
                        getCoverArtUrl(sampleSongId)
                    } else ""

                    genres.add(
                        UnifiedGenre(
                            id = id,
                            name = name,
                            trackCount = trackCount,
                            coverUrl = coverUrl
                        )
                    )
                }
                Result.success(genres)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch Lemon Music genres", e)
            Result.failure(e)
        }
    }

    fun getRecentlyAdded(limit: Int = 30): Result<List<UnifiedSong>> {
        val list = songIdToSongMap.values.toList().take(limit)
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
            ensureAuthenticated()
            val url = "$cleanBase/api/library/albums?page=1&limit=500"
            val req = newAuthRequest(url).get().build()

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
            ensureAuthenticated()
            val url = "$cleanBase/api/library/artists?page=1&limit=500"
            val req = newAuthRequest(url).get().build()

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
    /**
     * 获取歌单列表：包含用户资料库数据 (/api/library/user-data) 中的 playlists 与 favorites，
     * 以及用户自建歌单接口 (/api/library/playlists)
     */
    override suspend fun getPlaylists(): Result<List<UnifiedPlaylist>> = getPlaylists("lemon_music")

    suspend fun getPlaylists(targetServerId: String): Result<List<UnifiedPlaylist>> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val playlists = ArrayList<UnifiedPlaylist>()
            val seenIds = HashSet<String>()

            // 1. 优先拉取 /api/library/user-data (包含用户全量歌单、收藏与配置)
            try {
                val userDataRes = getLibraryUserData()
                if (userDataRes.isSuccess) {
                    val userData = userDataRes.getOrNull()
                    // A. 服务端「我的收藏」智能歌单 (放置在置顶首位)
                    val favArr = userData?.optJSONArray("favorites")
                    if (favArr != null && favArr.length() > 0) {
                        playlists.add(
                            UnifiedPlaylist(
                                id = "lemon_favorites",
                                name = "我的收藏",
                                coverUrl = "",
                                songCount = favArr.length(),
                                isOnline = true,
                                serverId = targetServerId,
                                isDiscover = false
                            )
                        )
                        seenIds.add("lemon_favorites")
                    }

                    // B. user-data 中的 playlists 自定义歌单
                    val plArr = userData?.optJSONArray("playlists")
                    if (plArr != null) {
                        for (i in 0 until plArr.length()) {
                            val pl = plArr.optJSONObject(i) ?: continue
                            val id = pl.optString("id", "lemon_pl_$i")
                            if (seenIds.add(id)) {
                                val name = pl.optString("name", "未命名歌单")
                                var coverUrl = pl.optString("coverUrl")
                                val tracks = pl.optJSONArray("trackKeys") ?: pl.optJSONArray("tracks") ?: pl.optJSONArray("paths") ?: JSONArray()
                                val snapshots = pl.optJSONObject("trackSnapshots")
                                if (coverUrl.isBlank() && snapshots != null) {
                                    val it = snapshots.keys()
                                    while (it.hasNext()) {
                                        val k = it.next()
                                        val sn = snapshots.optJSONObject(k)
                                        val p = sn?.optString("picUrl")?.ifBlank { sn.optString("img") } ?: ""
                                        if (p.isNotBlank()) {
                                            coverUrl = if (p.startsWith("http://") || p.startsWith("https://") || p.startsWith("data:")) p else "$cleanBase$p"
                                            break
                                        }
                                    }
                                } else if (coverUrl.isNotBlank() && !coverUrl.startsWith("http") && !coverUrl.startsWith("data:")) {
                                    coverUrl = "$cleanBase$coverUrl"
                                }
                                playlists.add(
                                    UnifiedPlaylist(
                                        id = id,
                                        name = name,
                                        coverUrl = coverUrl,
                                        songCount = tracks.length(),
                                        isOnline = true,
                                        serverId = targetServerId,
                                        isDiscover = false
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fetching /api/library/user-data for playlists error", e)
            }

            // 2. 兼容拉取 /api/library/playlists (旧版或特定端歌单接口)
            try {
                val customReq = newAuthRequest("$cleanBase/api/library/playlists").get().build()
                client.newCall(customReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val json = JSONObject(body)
                        val list = json.optJSONArray("data") ?: JSONArray()
                        for (i in 0 until list.length()) {
                            val pl = list.optJSONObject(i) ?: continue
                            val id = pl.optString("id", "lemon_pl_$i")
                            if (seenIds.add(id)) {
                                val name = pl.optString("name", "未命名歌单")
                                var coverUrl = pl.optString("coverUrl")
                                val tracks = pl.optJSONArray("trackKeys") ?: pl.optJSONArray("tracks") ?: pl.optJSONArray("paths") ?: JSONArray()
                                val snapshots = pl.optJSONObject("trackSnapshots")
                                if (coverUrl.isBlank() && snapshots != null) {
                                    val it = snapshots.keys()
                                    while (it.hasNext()) {
                                        val k = it.next()
                                        val sn = snapshots.optJSONObject(k)
                                        val p = sn?.optString("picUrl")?.ifBlank { sn.optString("img") } ?: ""
                                        if (p.isNotBlank()) {
                                            coverUrl = if (p.startsWith("http://") || p.startsWith("https://") || p.startsWith("data:")) p else "$cleanBase$p"
                                            break
                                        }
                                    }
                                } else if (coverUrl.isNotBlank() && !coverUrl.startsWith("http") && !coverUrl.startsWith("data:")) {
                                    coverUrl = "$cleanBase$coverUrl"
                                }
                                playlists.add(
                                    UnifiedPlaylist(
                                        id = id,
                                        name = name,
                                        coverUrl = coverUrl,
                                        songCount = tracks.length(),
                                        isOnline = true,
                                        serverId = targetServerId,
                                        isDiscover = false
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fetching /api/library/playlists fallback error", e)
            }

            Result.success(playlists)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch playlists", e)
            Result.failure(e)
        }
    }

    /**
     * 发现专属：获取在线推荐歌单 (/api/playlist/recommend)
     * 支持自动平滑回退多平台 (kw -> tx -> wy)
     */
    suspend fun getDiscoverRecommendPlaylists(
        source: String = "kw",
        page: Int = 1,
        limit: Int = 30
    ): Result<List<UnifiedPlaylist>> = withContext(Dispatchers.IO) {
        val cacheKey = "${cleanBase}_${source}_${page}_$limit"
        val cached = discoverPlaylistsCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first < DISCOVER_CACHE_TTL_MS) {
            return@withContext Result.success(cached.second)
        }

        val res = fetchSingleSourceRecommendPlaylists(source, page, limit)
        if (res.isSuccess && !res.getOrNull().isNullOrEmpty()) {
            discoverPlaylistsCache[cacheKey] = Pair(System.currentTimeMillis(), res.getOrNull()!!)
            return@withContext res
        }

        // 如果用户指定的源无数据，仅快速尝试默认的 kw，避免无谓的串行多源超时等待
        if (source != "kw") {
            val fallback = fetchSingleSourceRecommendPlaylists("kw", page, limit)
            if (fallback.isSuccess && !fallback.getOrNull().isNullOrEmpty()) {
                discoverPlaylistsCache[cacheKey] = Pair(System.currentTimeMillis(), fallback.getOrNull()!!)
                return@withContext fallback
            }
        }
        res
    }

    private suspend fun fetchSingleSourceRecommendPlaylists(
        source: String,
        page: Int,
        limit: Int
    ): Result<List<UnifiedPlaylist>> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val url = "$cleanBase/api/playlist/recommend?source=$source&sort=hot&page=$page&limit=$limit"
            val req = newAuthRequest(url).get().build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("获取推荐歌单失败 (HTTP ${resp.code})"))
                val json = JSONObject(body)
                val recData = json.optJSONObject("data")
                val list = recData?.optJSONArray("list") ?: json.optJSONArray("data") ?: JSONArray()
                val playlists = ArrayList<UnifiedPlaylist>(list.length())
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val id = item.optString("id").ifBlank { item.optString("play_id") }
                    val name = item.optString("name").ifBlank { item.optString("title", "精选推荐") }
                    val cover = item.optString("cover").ifBlank { item.optString("img").ifBlank { item.optString("pic", "") } }
                    val count = item.optInt("total", item.optInt("count", 30))
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
     * 支持自动平滑回退多平台 (kw -> tx -> wy)
     */
    suspend fun getDiscoverToplists(source: String = "kw"): Result<List<LemonToplist>> = withContext(Dispatchers.IO) {
        val cacheKey = "${cleanBase}_$source"
        val cached = discoverToplistsCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first < DISCOVER_CACHE_TTL_MS) {
            return@withContext Result.success(cached.second)
        }

        val res = fetchSingleSourceToplists(source)
        if (res.isSuccess && !res.getOrNull().isNullOrEmpty()) {
            discoverToplistsCache[cacheKey] = Pair(System.currentTimeMillis(), res.getOrNull()!!)
            return@withContext res
        }

        if (source != "kw") {
            val fallback = fetchSingleSourceToplists("kw")
            if (fallback.isSuccess && !fallback.getOrNull().isNullOrEmpty()) {
                discoverToplistsCache[cacheKey] = Pair(System.currentTimeMillis(), fallback.getOrNull()!!)
                return@withContext fallback
            }
        }
        res
    }

    private fun normalizeImageUrl(rawUrl: String?, cleanBase: String): String {
        if (rawUrl.isNullOrBlank()) return ""
        var url = rawUrl.trim()
        if (url.startsWith("//")) {
            url = "https:$url"
        } else if (url.startsWith("/") && !url.startsWith("/data")) {
            url = "$cleanBase$url"
        }
        return url
    }

    private suspend fun fetchSingleSourceToplists(source: String): Result<List<LemonToplist>> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val url = "$cleanBase/api/discover/toplists?source=$source"
            val req = newAuthRequest(url).get().build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("获取排行榜失败 (HTTP ${resp.code})"))
                val json = JSONObject(body)
                val dataObj = json.optJSONObject("data")
                val list = dataObj?.optJSONArray("list") ?: json.optJSONArray("data") ?: JSONArray()
                val toplists = ArrayList<LemonToplist>(list.length())
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val id = item.optString("id").ifBlank { item.optString("topId") }
                    val name = item.optString("name").ifBlank { item.optString("title", "热歌榜") }
                    var cover = item.optString("cover")
                        .ifBlank { item.optString("img") }
                        .ifBlank { item.optString("pic") }
                        .ifBlank { item.optString("picUrl") }
                        .ifBlank { item.optString("pic_url") }
                        .ifBlank { item.optString("coverUrl") }
                    cover = normalizeImageUrl(cover, cleanBase)
                    val updateFreq = item.optString("updateTime").ifBlank {
                        item.optString("updateFrequency").ifBlank { item.optString("period", "每日更新") }
                    }
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
        val cacheKey = "${cleanBase}_${source}_${region}_$limit"
        val cached = discoverNewSongsCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first < DISCOVER_CACHE_TTL_MS) {
            return@withContext Result.success(cached.second)
        }
        val url = "$cleanBase/api/discover/new-songs?source=$source&region=$region&limit=$limit"
        val res = fetchOnlineSongList(url, source)
        if (res.isSuccess && !res.getOrNull().isNullOrEmpty()) {
            discoverNewSongsCache[cacheKey] = Pair(System.currentTimeMillis(), res.getOrNull()!!)
        }
        res
    }

    /**
     * 在线单曲列表公共解析器
     */
    private suspend fun fetchOnlineSongList(url: String, source: String): Result<List<UnifiedSong>> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val req = newAuthRequest(url).get().build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("获取在线歌曲失败 (HTTP ${resp.code})"))
                val json = JSONObject(body)
                val dataObj = json.optJSONObject("data")
                val list = dataObj?.optJSONArray("list") ?: json.optJSONArray("data") ?: JSONArray()
                val songs = ArrayList<UnifiedSong>(list.length())

                // 尝试提取专辑或榜单层级的兜底封面
                val infoObj = dataObj?.optJSONObject("info")
                var fallbackCover = infoObj?.optString("img")
                    ?.ifBlank { infoObj.optString("pic") }
                    ?.ifBlank { infoObj.optString("picUrl") }
                    ?.ifBlank { infoObj.optString("cover") }
                    ?: ""
                fallbackCover = normalizeImageUrl(fallbackCover, cleanBase)

                for (i in 0 until list.length()) {
                    val s = list.optJSONObject(i) ?: continue
                    val songId = s.optString("songmid").ifBlank { s.optString("id", "online_$i") }
                    val title = s.optString("name").ifBlank { s.optString("title", "未知曲目") }
                    val singer = s.optString("singer").ifBlank { s.optString("artist", "未知歌手") }
                    val albumName = s.optString("albumName").ifBlank { s.optString("album", "在线精选") }
                    val duration = s.optDouble("interval", s.optDouble("duration", 0.0))
                    var cover = s.optString("cover")
                        .ifBlank { s.optString("img") }
                        .ifBlank { s.optString("pic") }
                        .ifBlank { s.optString("picUrl") }
                        .ifBlank { s.optString("pic_url") }
                        .ifBlank { s.optString("albumpic") }
                        .ifBlank { s.optString("album_pic") }
                        .ifBlank { s.optString("albumPic") }
                        .ifBlank { s.optString("imgurl") }
                        .ifBlank { s.optString("coverUrl") }
                    cover = normalizeImageUrl(cover, cleanBase).ifBlank { fallbackCover }
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
                            format = "mp3",
                            relativeFolderPath = null,
                            rawMetaJson = s.toString()
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
     * 发现专属：获取新碟首发列表 (/api/discover/new-albums)
     */
    suspend fun getDiscoverNewAlbums(
        source: String = "tx",
        region: String = "",
        page: Int = 1,
        limit: Int = 20
    ): Result<List<UnifiedAlbum>> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val url = "$cleanBase/api/discover/new-albums?source=$source&region=$region&page=$page&limit=$limit"
            val req = newAuthRequest(url).get().build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("获取新碟失败 (HTTP ${resp.code})"))
                val json = JSONObject(body)
                val dataObj = json.optJSONObject("data")
                val list = dataObj?.optJSONArray("list") ?: json.optJSONArray("data") ?: JSONArray()
                val albums = ArrayList<UnifiedAlbum>(list.length())

                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val id = item.optString("id").ifBlank { item.optString("albumId", "album_$i") }
                    val name = item.optString("name").ifBlank { item.optString("title", "最新专辑") }
                    val artist = item.optString("artist").ifBlank { item.optString("singer", "未知歌手") }
                    var cover = item.optString("cover")
                        .ifBlank { item.optString("img") }
                        .ifBlank { item.optString("pic") }
                        .ifBlank { item.optString("picUrl") }
                        .ifBlank { item.optString("pic_url") }
                        .ifBlank { item.optString("album_pic") }
                        .ifBlank { item.optString("albumpic") }
                        .ifBlank { item.optString("albumPic") }
                        .ifBlank { item.optString("imgurl") }
                        .ifBlank { item.optString("coverUrl") }
                    cover = normalizeImageUrl(cover, cleanBase)
                    val count = item.optInt("total", item.optInt("count", item.optInt("songCount", 0)))
                    val yearStr = item.optString("publishTime").ifBlank { item.optString("year") }
                    val year = yearStr.take(4).toIntOrNull()

                    albums.add(
                        UnifiedAlbum(
                            id = "lemon_discover_album_${source}_$id",
                            title = name,
                            artist = artist,
                            coverUrl = cover,
                            songCount = count,
                            year = year
                        )
                    )
                }
                Result.success(albums)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 外部歌单/单曲链接解析 (/api/playlist?url=...)
     */
    suspend fun parseExternalPlaylist(
        urlOrId: String,
        source: String = "kw"
    ): Result<List<UnifiedSong>> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val enc = try { URLEncoder.encode(urlOrId, "UTF-8") } catch (_: Exception) { urlOrId }
            val url = "$cleanBase/api/playlist?source=$source&url=$enc"
            fetchOnlineSongList(url, source)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取指定歌单内的歌曲列表
     */
    override suspend fun getPlaylistSongs(playlistId: String): Result<List<UnifiedSong>> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
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

            // C. 若是发现新碟首发专辑：调用 /api/album?source=...&id=...
            if (playlistId.startsWith("lemon_discover_album_")) {
                val parts = playlistId.removePrefix("lemon_discover_album_").split("_", limit = 2)
                val source = parts.getOrNull(0) ?: "tx"
                val rawId = parts.getOrNull(1) ?: playlistId
                val url = "$cleanBase/api/album?source=$source&id=${URLEncoder.encode(rawId, "UTF-8")}"
                return@withContext fetchOnlineSongList(url, source)
            }

            val songs = ArrayList<UnifiedSong>()
            val missingPaths = ArrayList<String>()

            // C. 若是「我的收藏」智能歌单：直接从 /api/library/user-data 提取 favorites
            if (playlistId == "lemon_favorites") {
                try {
                    val userDataRes = getLibraryUserData()
                    val userData = userDataRes.getOrNull()
                    val favArr = userData?.optJSONArray("favorites")
                    if (favArr != null) {
                        for (j in 0 until favArr.length()) {
                            val item = favArr.opt(j)
                            if (item is JSONObject) {
                                val sName = item.optString("name").ifBlank { item.optString("title") }
                                val sSinger = item.optString("singer").ifBlank { item.optString("artist", "未知歌手") }
                                val sAlbum = item.optString("album", "未知专辑")
                                val sLocalPath = item.optString("localPath").ifBlank { item.optString("filePath") }
                                var sPic = item.optString("picUrl").ifBlank { item.optString("img") }
                                val sSource = item.optString("source").ifBlank { item.optString("platform") }
                                val sSongId = item.optString("songId").ifBlank { item.optString("id") }
                                val durMs = (item.optDouble("interval", item.optDouble("duration", 0.0)) * 1000).toLong()

                                if (sPic.isNotBlank() && !sPic.startsWith("http") && !sPic.startsWith("data:")) {
                                    sPic = "$cleanBase$sPic"
                                }

                                if (sLocalPath.isNotBlank() || item.optString("key").startsWith("local:")) {
                                    val cleanPath = (if (sLocalPath.isNotBlank()) sLocalPath else item.optString("key").removePrefix("local:")).trim()
                                    val songId = "lemon_${md5(cleanPath)}"
                                    val streamUrl = "$cleanBase/api/play/stream?path=${URLEncoder.encode(cleanPath, "UTF-8")}"
                                    val coverUrl = if (sPic.isNotBlank()) sPic else "$cleanBase/api/tag/cover?path=${URLEncoder.encode(cleanPath, "UTF-8")}"
                                    val song = UnifiedSong(
                                        id = songId,
                                        title = sName.ifBlank { cleanPath.substringAfterLast('/').substringBeforeLast('.') },
                                        artist = sSinger,
                                        artistId = "artist_${sSinger.hashCode()}",
                                        album = sAlbum,
                                        albumId = "album_${sAlbum.hashCode()}",
                                        durationMs = durMs,
                                        coverUrl = coverUrl,
                                        streamUrl = streamUrl,
                                        serverId = "lemon_music",
                                        localFilePath = null,
                                        downloadStatus = DownloadStatus.NOT_DOWNLOADED,
                                        bitRate = 320,
                                        format = cleanPath.substringAfterLast('.', "flac").lowercase(),
                                        isFavorite = true,
                                        relativeFolderPath = cleanPath
                                    )
                                    songIdToPathMap[songId] = cleanPath
                                    songIdToSongMap[songId] = song
                                    songs.add(song)
                                } else if (sSource.isNotBlank() && sSource != "local") {
                                    val platform = sSource.ifBlank { "kw" }
                                    val onlineId = if (sSongId.startsWith("lemon_online_")) sSongId else "lemon_online_${platform}_${sSongId.ifBlank { item.optString("key") }}"
                                    val song = UnifiedSong(
                                        id = onlineId,
                                        title = sName.ifBlank { "在线曲目" },
                                        artist = sSinger,
                                        artistId = "artist_${sSinger.hashCode()}",
                                        album = sAlbum,
                                        albumId = "album_${sAlbum.hashCode()}",
                                        durationMs = durMs,
                                        coverUrl = sPic,
                                        streamUrl = "lemon_online://$platform/${sSongId.ifBlank { item.optString("key") }}",
                                        serverId = "lemon_online",
                                        localFilePath = null,
                                        downloadStatus = DownloadStatus.NOT_DOWNLOADED,
                                        bitRate = 320,
                                        format = "mp3",
                                        isFavorite = true,
                                        rawMetaJson = item.toString()
                                    )
                                    songs.add(song)
                                }
                            } else {
                                val pathStr = item?.toString() ?: ""
                                val clean = pathStr.removePrefix("local:").trim()
                                if (clean.isNotBlank()) {
                                    missingPaths.add(clean)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get favorites from user-data", e)
                }
            } else {
                // D. 用户自建与云端歌单：优先查 user-data 中的 playlists
                var targetPl: JSONObject? = null
                try {
                    val userDataRes = getLibraryUserData()
                    val userData = userDataRes.getOrNull()
                    val plArr = userData?.optJSONArray("playlists")
                    if (plArr != null) {
                        for (i in 0 until plArr.length()) {
                            val pl = plArr.optJSONObject(i) ?: continue
                            if (pl.optString("id") == playlistId) {
                                targetPl = pl
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Search playlist in user-data failed", e)
                }

                // 若 user-data 中未匹配，回退从 /api/library/playlists 查询
                if (targetPl == null) {
                    try {
                        val customReq = newAuthRequest("$cleanBase/api/library/playlists").get().build()
                        client.newCall(customReq).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val body = resp.body?.string() ?: ""
                                val json = JSONObject(body)
                                val list = json.optJSONArray("data") ?: JSONArray()
                                for (i in 0 until list.length()) {
                                    val pl = list.optJSONObject(i) ?: continue
                                    if (pl.optString("id") == playlistId) {
                                        targetPl = pl
                                        break
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Search playlist in /api/library/playlists failed", e)
                    }
                }

                val finalPl = targetPl
                if (finalPl != null) {
                    val trackKeys = finalPl.optJSONArray("trackKeys") ?: finalPl.optJSONArray("paths") ?: finalPl.optJSONArray("tracks") ?: JSONArray()
                    val snapshots = finalPl.optJSONObject("trackSnapshots")

                    for (j in 0 until trackKeys.length()) {
                        val rawKey = when (val item = trackKeys.opt(j)) {
                            is String -> item
                            is JSONObject -> item.optString("key").ifBlank { item.optString("filePath").ifBlank { item.optString("id") } }
                            else -> ""
                        }
                        if (rawKey.isBlank()) continue

                        val snapshot = snapshots?.optJSONObject(rawKey)
                            ?: (if (trackKeys.opt(j) is JSONObject) trackKeys.opt(j) as JSONObject else null)

                        if (snapshot != null) {
                            val sName = snapshot.optString("name").ifBlank { snapshot.optString("title") }
                            val sSinger = snapshot.optString("singer").ifBlank { snapshot.optString("artist", "未知歌手") }
                            val sAlbum = snapshot.optString("album", "未知专辑")
                            val sLocalPath = snapshot.optString("localPath").ifBlank { snapshot.optString("filePath") }
                            val sSource = snapshot.optString("source").ifBlank { snapshot.optString("platform") }
                            var sPic = snapshot.optString("picUrl").ifBlank { snapshot.optString("img") }
                            val sSongId = snapshot.optString("songId").ifBlank { snapshot.optString("id") }
                            val interval = snapshot.optDouble("interval", snapshot.optDouble("duration", 0.0))
                            val durMs = (interval * 1000).toLong()

                            if (sPic.isNotBlank() && !sPic.startsWith("http") && !sPic.startsWith("data:")) {
                                sPic = "$cleanBase$sPic"
                            }

                            if (sLocalPath.isNotBlank() || rawKey.startsWith("local:")) {
                                val cleanLocalPath = (if (sLocalPath.isNotBlank()) sLocalPath else rawKey.removePrefix("local:")).trim()
                                val songId = "lemon_${md5(cleanLocalPath)}"
                                val streamUrl = "$cleanBase/api/play/stream?path=${URLEncoder.encode(cleanLocalPath, "UTF-8")}"
                                val coverUrl = if (sPic.isNotBlank()) sPic else "$cleanBase/api/tag/cover?path=${URLEncoder.encode(cleanLocalPath, "UTF-8")}"
                                val ext = cleanLocalPath.substringAfterLast('.', "flac").lowercase()
                                val song = UnifiedSong(
                                    id = songId,
                                    title = sName.ifBlank { cleanLocalPath.substringAfterLast('/').substringBeforeLast('.') },
                                    artist = sSinger,
                                    artistId = "artist_${sSinger.hashCode()}",
                                    album = sAlbum,
                                    albumId = "album_${sAlbum.hashCode()}",
                                    durationMs = durMs,
                                    coverUrl = coverUrl,
                                    streamUrl = streamUrl,
                                    serverId = "lemon_music",
                                    localFilePath = null,
                                    downloadStatus = DownloadStatus.NOT_DOWNLOADED,
                                    bitRate = 320,
                                    format = ext,
                                    isFavorite = false,
                                    relativeFolderPath = cleanLocalPath
                                )
                                songIdToPathMap[songId] = cleanLocalPath
                                songIdToSongMap[songId] = song
                                songs.add(song)
                            } else {
                                val platform = sSource.ifBlank { "kw" }
                                val onlineId = if (sSongId.startsWith("lemon_online_")) sSongId else "lemon_online_${platform}_${sSongId.ifBlank { rawKey }}"
                                val song = UnifiedSong(
                                    id = onlineId,
                                    title = sName.ifBlank { "在线曲目" },
                                    artist = sSinger,
                                    artistId = "artist_${sSinger.hashCode()}",
                                    album = sAlbum,
                                    albumId = "album_${sAlbum.hashCode()}",
                                    durationMs = durMs,
                                    coverUrl = sPic,
                                    streamUrl = "lemon_online://$platform/${sSongId.ifBlank { rawKey }}",
                                    serverId = "lemon_online",
                                    localFilePath = null,
                                    downloadStatus = DownloadStatus.NOT_DOWNLOADED,
                                    bitRate = 320,
                                    format = "mp3",
                                    isFavorite = false,
                                    rawMetaJson = snapshot.toString()
                                )
                                songs.add(song)
                            }
                        } else {
                            val clean = rawKey.removePrefix("local:").trim()
                            if (clean.isNotBlank()) {
                                missingPaths.add(clean)
                            }
                        }
                    }
                }
            }

            // 若有未通过 snapshots 补全的纯本地路径，向 /api/library/tracks/by-paths 批量请求
            if (missingPaths.isNotEmpty()) {
                try {
                    val payload = JSONObject().apply {
                        put("paths", JSONArray(missingPaths))
                    }
                    val byPathsReq = newAuthRequest("$cleanBase/api/library/tracks/by-paths")
                        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    client.newCall(byPathsReq).execute().use { resp ->
                        val body = resp.body?.string() ?: ""
                        if (resp.isSuccessful) {
                            val json = JSONObject(body)
                            val arr = json.optJSONArray("data") ?: JSONArray()
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
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to query tracks by paths fallback", e)
                }
            }

            Result.success(songs)
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
            ensureAuthenticated()
            val song = songIdToSongMap[songId]
            val path = songIdToPathMap[songId] ?: ""

            val payload = JSONObject().apply {
                put("name", song?.title ?: "")
                put("singer", song?.artist ?: "")
                put("filePath", path)
            }
            val req = newAuthRequest("$cleanBase/api/play/lyric")
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

    /**
     * 获取原始歌词文本 (/api/play/lyric)
     */
    suspend fun getRawLyrics(songId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val song = songIdToSongMap[songId]
            val path = songIdToPathMap[songId] ?: ""

            val payload = JSONObject().apply {
                put("name", song?.title ?: "")
                put("singer", song?.artist ?: "")
                put("filePath", path)
            }
            val req = newAuthRequest("$cleanBase/api/play/lyric")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("歌词获取失败"))
                val json = JSONObject(body)
                val lyricStr = json.optString("lyric").ifBlank { json.optString("ylyric") }
                if (lyricStr.isNotBlank()) {
                    return@withContext Result.success(lyricStr)
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
            ensureAuthenticated()
            val encKw = URLEncoder.encode(keyword, "UTF-8")
            val url = "$cleanBase/api/search?keyword=$encKw&source=$source&page=$page&limit=$limit"
            val req = newAuthRequest(url).get().build()

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
                    val cover = s.optString("cover").ifBlank { s.optString("img").ifBlank { s.optString("pic", "") } }
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
                            format = "mp3",
                            relativeFolderPath = null,
                            rawMetaJson = s.toString()
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
        quality: String = "128k",
        metaJson: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val cleanId = songId.removePrefix("lemon_online_")
            val actualSource = if (cleanId.contains("_")) cleanId.substringBefore("_") else source
            val rawId = if (cleanId.contains("_")) cleanId.substringAfter("_") else cleanId
            val payload = JSONObject()
            if (!metaJson.isNullOrBlank()) {
                try {
                    val metaObj = JSONObject(metaJson)
                    val keys = metaObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        payload.put(k, metaObj.opt(k))
                    }
                } catch (_: Exception) {}
            }
            if (!payload.has("source") || payload.optString("source").isBlank()) {
                payload.put("source", actualSource)
            }
            if (!payload.has("songId") || payload.optString("songId").isBlank()) {
                payload.put("songId", rawId)
            }
            if (!payload.has("songmid") && (actualSource == "tx" || actualSource == "kw")) {
                payload.put("songmid", rawId)
            }
            payload.put("quality", quality)

            val req = newAuthRequest("$cleanBase/api/play/url")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    val errMsg = runCatching { JSONObject(body).optString("error") }.getOrNull()
                    return@withContext Result.failure(Exception(errMsg?.ifBlank { null } ?: "获取在线播放地址失败 (HTTP ${resp.code})"))
                }
                val json = JSONObject(body)
                val streamUrl = json.optString("url")
                if (streamUrl.isNotBlank()) {
                    val finalUrl = if (streamUrl.startsWith("/")) "$cleanBase$streamUrl" else streamUrl
                    Result.success(finalUrl)
                } else {
                    val msg = json.optString("msg", json.optString("error", "未获得播放直链"))
                    Result.failure(Exception(msg))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 向柠檬音乐服务端添加下载任务 (/api/download/add)
     * 支持将歌曲直接缓存保存到服务器/NAS音乐库并自动刮削元数据
     */
    suspend fun addServerDownloadTasks(tasks: List<com.lm.player.core.model.LemonServerDownloadTask>): Result<Int> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val taskArray = JSONArray()
            tasks.forEach { t ->
                val obj = JSONObject()
                if (t.raw.isNotBlank()) {
                    try {
                        val rawObj = JSONObject(t.raw)
                        val keys = rawObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            obj.put(k, rawObj.opt(k))
                        }
                    } catch (_: Exception) {}
                }
                obj.put("name", t.name)
                obj.put("singer", t.singer)
                obj.put("source", if (t.source.isNotBlank() && t.source != "kw") t.source else t.platform.ifBlank { "kw" })
                if (t.album.isNotBlank()) obj.put("album", t.album)
                if (t.interval.isNotBlank()) obj.put("interval", t.interval)
                obj.put("quality", t.quality)
                if (t.songId.isNotBlank()) obj.put("songId", t.songId)
                if (t.songmid.isNotBlank()) obj.put("songmid", t.songmid)
                if (t.hash.isNotBlank()) obj.put("hash", t.hash)
                if (t.rid.isNotBlank()) obj.put("rid", t.rid)
                if (t.copyrightId.isNotBlank()) obj.put("copyrightId", t.copyrightId)
                if (t.img.isNotBlank()) obj.put("img", t.img)
                if (t.pic.isNotBlank() && !obj.has("pic")) obj.put("pic", t.pic)
                taskArray.put(obj)
            }
            val payload = JSONObject().apply {
                put("tasks", taskArray)
            }
            val req = newAuthRequest("$cleanBase/api/download/add")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    val errMsg = runCatching { JSONObject(body).optString("error") }.getOrNull()
                    return@withContext Result.failure(Exception(errMsg?.ifBlank { null } ?: "服务端添加下载任务失败 (${resp.code})"))
                }
                val json = JSONObject(body)
                val added = json.optJSONArray("ids")?.length() ?: tasks.size
                Result.success(added)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== 音源与脚本管理 API (LX Music Source Scripts) ====================

    /**
     * 获取服务端安装的所有音源脚本列表 (/api/source/list)
     */
    suspend fun fetchSourceList(): Result<List<LemonSourceScriptInfo>> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val url = "$cleanBase/api/source/list"
            val req = newAuthRequest(url).get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("获取音源列表失败 (HTTP ${resp.code})"))
                val trimmed = body.trim()
                val arr = when {
                    trimmed.startsWith("[") -> JSONArray(trimmed)
                    trimmed.startsWith("{") -> {
                        val json = JSONObject(trimmed)
                        json.optJSONArray("data") ?: json.optJSONArray("list") ?: json.optJSONArray("rows") ?: JSONArray()
                    }
                    else -> JSONArray()
                }

                val list = ArrayList<LemonSourceScriptInfo>(arr.length())
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val sourcesList = mutableListOf<String>()
                    val sourcesObj = item.optJSONObject("sources")
                    if (sourcesObj != null) {
                        val keys = sourcesObj.keys()
                        while (keys.hasNext()) {
                            sourcesList.add(keys.next())
                        }
                    } else {
                        val sourcesArr = item.optJSONArray("sources")
                        if (sourcesArr != null) {
                            for (k in 0 until sourcesArr.length()) {
                                sourcesList.add(sourcesArr.optString(k))
                            }
                        }
                    }
                    list.add(
                        LemonSourceScriptInfo(
                            id = item.optString("id").ifBlank { (i + 1).toString() },
                            name = item.optString("name", "自定义音源脚本"),
                            description = item.optString("description", "外部导入的音乐解析脚本"),
                            author = item.optString("author", "开源社区"),
                            version = item.optString("version", "1.0.0"),
                            supportedPlatforms = if (sourcesList.isNotEmpty()) sourcesList else listOf("kw", "wy", "tx"),
                            isActive = item.optBoolean("active", true),
                            healthSummary = item.optString("health", "就绪")
                        )
                    )
                }

                // 若服务端未导入第三方额外自定义脚本，自动展现服务器原生内置的 5 大就绪音源
                if (list.isEmpty()) {
                    list.add(LemonSourceScriptInfo(id = "builtin_kw", name = "酷我音乐 (服务端原生通道)", description = "服务端原生直连通道，支持 128K/320K/FLAC/Hi-Res 高解析解析与缓存", author = "柠檬音乐官方", version = "内置核心", supportedPlatforms = listOf("kw"), isActive = true, healthSummary = "连接正常"))
                    list.add(LemonSourceScriptInfo(id = "builtin_wy", name = "网易云音乐 (服务端原生通道)", description = "服务端原生直连通道，支持全网榜单、热歌推荐与歌单同步", author = "柠檬音乐官方", version = "内置核心", supportedPlatforms = listOf("wy"), isActive = true, healthSummary = "连接正常"))
                    list.add(LemonSourceScriptInfo(id = "builtin_tx", name = "QQ音乐 (服务端原生通道)", description = "服务端原生直连通道，支持海量正版流行曲目与新歌首发推荐", author = "柠檬音乐官方", version = "内置核心", supportedPlatforms = listOf("tx"), isActive = true, healthSummary = "连接正常"))
                    list.add(LemonSourceScriptInfo(id = "builtin_kg", name = "酷狗音乐 (服务端原生通道)", description = "服务端原生直连通道，支持全景音效及经典老歌极速检索", author = "柠檬音乐官方", version = "内置核心", supportedPlatforms = listOf("kg"), isActive = true, healthSummary = "连接正常"))
                    list.add(LemonSourceScriptInfo(id = "builtin_mg", name = "咪咕音乐 (服务端原生通道)", description = "服务端原生直连通道，无损超清品质与官方专属曲库通道", author = "柠檬音乐官方", version = "内置核心", supportedPlatforms = listOf("mg"), isActive = true, healthSummary = "连接正常"))
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch source list", e)
            Result.failure(e)
        }
    }

    /**
     * 从远程 URL 导入音源脚本 (/api/source/import-url)
     */
    suspend fun importSourceUrl(scriptUrl: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val payload = JSONObject().apply {
                put("url", scriptUrl)
            }
            val req = newAuthRequest("$cleanBase/api/source/import-url")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) Result.success(true)
                else Result.failure(Exception("导入失败 (HTTP ${resp.code})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 直接导入音源脚本内容 (/api/source/import)
     */
    suspend fun importSourceScript(scriptContent: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val payload = JSONObject().apply {
                put("script", scriptContent)
            }
            val req = newAuthRequest("$cleanBase/api/source/import")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) Result.success(true)
                else Result.failure(Exception("导入失败 (HTTP ${resp.code})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 启用指定音源脚本 (/api/source/activate/:id)
     */
    suspend fun activateSource(sourceId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val req = newAuthRequest("$cleanBase/api/source/activate/$sourceId")
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) Result.success(true)
                else Result.failure(Exception("激活音源失败 (HTTP ${resp.code})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 停用指定音源脚本 (/api/source/deactivate/:id)
     */
    suspend fun deactivateSource(sourceId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val req = newAuthRequest("$cleanBase/api/source/deactivate/$sourceId")
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) Result.success(true)
                else Result.failure(Exception("停用音源失败 (HTTP ${resp.code})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 删除指定音源脚本 (/api/source/:id)
     */
    suspend fun deleteSource(sourceId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val req = newAuthRequest("$cleanBase/api/source/$sourceId")
                .delete()
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) Result.success(true)
                else Result.failure(Exception("删除音源失败 (HTTP ${resp.code})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取可用平台音源列表 (/api/playlist/sources)
     */
    suspend fun fetchDisplaySources(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val req = newAuthRequest("$cleanBase/api/playlist/sources").get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("获取可用平台失败"))
                val json = JSONObject(body)
                val sourcesObj = json.optJSONObject("data")?.optJSONObject("sources")
                    ?: json.optJSONObject("sources")
                val keys = sourcesObj?.keys() ?: return@withContext Result.success(listOf("kw", "tx", "wy", "kg", "mg"))
                val list = ArrayList<String>()
                while (keys.hasNext()) {
                    list.add(keys.next())
                }
                Result.success(if (list.isEmpty()) listOf("kw", "tx", "wy", "kg", "mg") else list)
            }
        } catch (e: Exception) {
            Result.success(listOf("kw", "tx", "wy", "kg", "mg"))
        }
    }

    // ==================== 用户数据同步 (收藏、歌单) ====================

    /**
     * 获取用户资料库数据 (/api/library/user-data)
     * 返回包含 playlists, favorites (曲目物理路径列表或ID), recentPlays
     */
    suspend fun getLibraryUserData(): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val req = newAuthRequest("$cleanBase/api/library/user-data").get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("获取用户数据失败 (HTTP ${resp.code})"))
                val json = JSONObject(body)
                val data = json.optJSONObject("data") ?: json
                Result.success(data)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 保存/更新全部用户自定义歌单至服务器 (/api/library/playlists)
     */
    suspend fun saveCustomPlaylists(playlists: JSONArray): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val payload = JSONObject().apply {
                put("playlists", playlists)
            }
            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = newAuthRequest("$cleanBase/api/library/playlists").put(body).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("保存歌单失败 (HTTP ${resp.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 在服务器端创建全新自定义歌单
     */
    suspend fun createCustomPlaylist(name: String, coverUrl: String = ""): Result<UnifiedPlaylist> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val existingPlaylists = ArrayList<JSONObject>()
            val rawReq = newAuthRequest("$cleanBase/api/library/playlists").get().build()
            client.newCall(rawReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val rawBody = resp.body?.string() ?: ""
                    val json = JSONObject(rawBody)
                    val arr = json.optJSONArray("data") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { existingPlaylists.add(it) }
                    }
                }
            }

            val newId = "pl_${System.currentTimeMillis()}_${(1000..9999).random()}"
            val newPlObj = JSONObject().apply {
                put("id", newId)
                put("name", name)
                put("createdAt", System.currentTimeMillis())
                put("trackKeys", JSONArray())
                put("trackSnapshots", JSONObject())
                put("coverUrl", coverUrl)
                put("coverMode", if (coverUrl.isNotBlank()) "custom" else "auto")
                put("playlistType", "custom")
            }

            existingPlaylists.add(0, newPlObj)
            val saveArr = JSONArray()
            existingPlaylists.forEach { saveArr.put(it) }

            val saveRes = saveCustomPlaylists(saveArr)
            if (saveRes.isSuccess) {
                Result.success(
                    UnifiedPlaylist(
                        id = newId,
                        name = name,
                        coverUrl = coverUrl,
                        songCount = 0,
                        isOnline = true,
                        serverId = "lemon_music",
                        isDiscover = false
                    )
                )
            } else {
                Result.failure(saveRes.exceptionOrNull() ?: Exception("保存新建歌单失败"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 向服务器指定歌单追加曲目
     */
    suspend fun addTracksToCustomPlaylist(playlistId: String, songKeys: List<String>): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val existingPlaylists = ArrayList<JSONObject>()
            val rawReq = newAuthRequest("$cleanBase/api/library/playlists").get().build()
            client.newCall(rawReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val rawBody = resp.body?.string() ?: ""
                    val json = JSONObject(rawBody)
                    val arr = json.optJSONArray("data") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { existingPlaylists.add(it) }
                    }
                }
            }

            var found = false
            for (pl in existingPlaylists) {
                if (pl.optString("id") == playlistId) {
                    val trackKeysArr = pl.optJSONArray("trackKeys") ?: JSONArray()
                    val existingSet = HashSet<String>()
                    for (k in 0 until trackKeysArr.length()) {
                        existingSet.add(trackKeysArr.optString(k))
                    }
                    for (rawKey in songKeys) {
                        val key = when {
                            rawKey.startsWith("local:") || rawKey.contains(":") -> rawKey
                            rawKey.startsWith("/") -> "local:$rawKey"
                            else -> rawKey
                        }
                        if (!existingSet.contains(key)) {
                            trackKeysArr.put(key)
                            existingSet.add(key)
                        }
                    }
                    pl.put("trackKeys", trackKeysArr)
                    found = true
                    break
                }
            }

            if (!found) {
                return@withContext Result.failure(Exception("服务器未找到指定歌单: $playlistId"))
            }

            val saveArr = JSONArray()
            existingPlaylists.forEach { saveArr.put(it) }
            saveCustomPlaylists(saveArr)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 从服务器删除指定歌单
     */
    suspend fun deleteCustomPlaylist(playlistId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val existingPlaylists = ArrayList<JSONObject>()
            val rawReq = newAuthRequest("$cleanBase/api/library/playlists").get().build()
            client.newCall(rawReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val rawBody = resp.body?.string() ?: ""
                    val json = JSONObject(rawBody)
                    val arr = json.optJSONArray("data") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let {
                            if (it.optString("id") != playlistId) {
                                existingPlaylists.add(it)
                            }
                        }
                    }
                }
            }

            val saveArr = JSONArray()
            existingPlaylists.forEach { saveArr.put(it) }
            saveCustomPlaylists(saveArr)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取服务端音乐库曲目总数 (/api/library/tracks/count)
     */
    suspend fun getLibraryTracksCount(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val req = newAuthRequest("$cleanBase/api/library/tracks/count").get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("获取曲库总数失败 (HTTP ${resp.code})"))
                val json = JSONObject(body)
                val total = json.optInt("total", 0)
                Result.success(total)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 主动请求服务端对指定文件批量读取标签并写入索引缓存 (/api/library/scan-batch)
     * 在下载完成或文件更新后调用，使服务端无需等待定时轮询即可立即索引曲目
     */
    suspend fun scanServerBatch(filePaths: List<String>): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            if (filePaths.isEmpty()) return@withContext Result.success(true)
            val cleanList = filePaths.map { it.removePrefix("local:").trim() }.filter { it.isNotBlank() }.distinct()
            if (cleanList.isEmpty()) return@withContext Result.success(true)

            val chunks = cleanList.chunked(50)
            for (chunk in chunks) {
                val filesArr = JSONArray()
                chunk.forEach { path ->
                    filesArr.put(JSONObject().apply { put("filePath", path) })
                }
                val payload = JSONObject().apply { put("files", filesArr) }
                val req = newAuthRequest("$cleanBase/api/library/scan-batch")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "scanServerBatch failed with HTTP ${resp.code}")
                    }
                }
            }
            Result.success(true)
        } catch (e: Exception) {
            Log.w(TAG, "scanServerBatch exception", e)
            Result.failure(e)
        }
    }

    /**
     * 将曲目的喜欢/收藏状态双向保存至服务器 (/api/library/user-data)
     */
    suspend fun toggleFavoriteOnServer(serverFilePath: String, isFavorite: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val cleanPath = serverFilePath.removePrefix("local:").trim()
            if (cleanPath.isBlank()) return@withContext Result.success(false)

            val userDataRes = getLibraryUserData()
            val userData = userDataRes.getOrNull() ?: JSONObject()
            val favArr = userData.optJSONArray("favorites") ?: JSONArray()
            val favSet = LinkedHashSet<String>()
            for (i in 0 until favArr.length()) {
                val item = favArr.opt(i)
                when (item) {
                    is String -> favSet.add(item)
                    is JSONObject -> {
                        val p = item.optString("filePath").ifBlank { item.optString("id") }
                        if (p.isNotBlank()) favSet.add(p)
                    }
                }
            }

            val serverKey = "local:$cleanPath"
            if (isFavorite) {
                favSet.add(serverKey)
            } else {
                favSet.remove(serverKey)
                favSet.remove(cleanPath)
            }

            val newFavArr = JSONArray()
            favSet.forEach { newFavArr.put(it) }

            val payload = JSONObject().apply {
                put("favorites", newFavArr)
            }
            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val req = newAuthRequest("$cleanBase/api/library/user-data").put(body).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("保存收藏失败 (HTTP ${resp.code})"))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "toggleFavoriteOnServer failed", e)
            Result.failure(e)
        }
    }

    /**
     * 获取服务端下载保存路径及可用音乐目录 (/api/paths)
     */
    suspend fun getServerPaths(): Result<ServerPathConfig> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val req = newAuthRequest("$cleanBase/api/paths").get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("获取服务端路径失败 (HTTP ${resp.code})"))
                val json = JSONObject(body)
                val dataObj = json.optJSONObject("data")
                val dlPath = dataObj?.optString("downloadPath")
                    ?: json.optString("downloadPath", "")
                val pathsArr = dataObj?.optJSONArray("musicPaths")
                    ?: dataObj?.optJSONArray("paths")
                    ?: json.optJSONArray("data")
                    ?: json.optJSONArray("paths")
                    ?: JSONArray()
                val pathsList = ArrayList<String>()
                for (i in 0 until pathsArr.length()) {
                    val p = pathsArr.optString(i).trim()
                    if (p.isNotBlank()) pathsList.add(p)
                }
                // 若接口未返回候选目录，从已有曲目路径中提取目录兜底
                if (pathsList.isEmpty()) {
                    val fromSongs = songIdToPathMap.values.mapNotNull {
                        val norm = it.replace('\\', '/')
                        val parent = norm.substringBeforeLast('/', "")
                        if (parent.isNotBlank()) parent else null
                    }.distinct().sorted()
                    pathsList.addAll(fromSongs)
                }
                Result.success(ServerPathConfig(downloadPath = dlPath, availablePaths = pathsList))
            }
        } catch (e: Exception) {
            Log.w(TAG, "getServerPaths failed", e)
            Result.failure(e)
        }
    }

    /**
     * 更新服务端下载保存目录 (/api/paths/download)
     */
    suspend fun updateServerDownloadPath(newPath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val cleanPath = newPath.trim()
            if (cleanPath.isBlank()) return@withContext Result.failure(Exception("保存路径不能为空"))
            val payload = JSONObject().apply {
                put("dirPath", cleanPath)
                put("path", cleanPath)
            }
            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val putReq = newAuthRequest("$cleanBase/api/paths/download").put(body).build()
            client.newCall(putReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    Result.success(true)
                } else {
                    // 若服务端支持 POST，进行降级重试
                    val postReq = newAuthRequest("$cleanBase/api/paths/download").post(body).build()
                    client.newCall(postReq).execute().use { postResp ->
                        if (postResp.isSuccessful) Result.success(true)
                        else Result.failure(Exception("更新服务端下载路径失败 (HTTP ${resp.code})"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "updateServerDownloadPath failed", e)
            Result.failure(e)
        }
    }
}

/**
 * 柠檬音乐服务端路径配置模型
 */
data class ServerPathConfig(
    val downloadPath: String = "",
    val availablePaths: List<String> = emptyList()
)
