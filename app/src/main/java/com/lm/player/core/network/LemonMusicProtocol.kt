package com.lm.player.core.network

import android.util.Log
import com.lm.player.core.media.EmbeddedLyricsExtractor
import com.lm.player.core.media.LrcParser
import com.lm.player.core.media.SmartCharsetDecoder
import com.lm.player.core.media.SongMatchingResolver
import com.lm.player.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
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
    private val authCacheKey = "${cleanBase}_${username.trim()}"
    private var authToken: String = sharedAuthTokens[authCacheKey].orEmpty()

    companion object {
        private const val TAG = "LemonMusicProtocol"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // 跨实例共享的已认证 Bearer Token 缓存 (消除多组件并发实例化时的重复 /api/auth/login 请求)
        private val sharedAuthTokens = ConcurrentHashMap<String, String>()

        // 内存高速全局映射缓存：songId <-> NAS 物理绝对路径、稳定 trackId 与曲目实体 (跨实例共享)
        private val songIdToPathMap = ConcurrentHashMap<String, String>()
        private val songIdToTrackIdMap = ConcurrentHashMap<String, String>()
        private val songIdToSongMap = ConcurrentHashMap<String, UnifiedSong>()

        // 发现页短期内存快取 (缓存 5 分钟，显著提速主页切换与展示)
        private val discoverPlaylistsCache = ConcurrentHashMap<String, Pair<Long, List<UnifiedPlaylist>>>()
        private val discoverToplistsCache = ConcurrentHashMap<String, Pair<Long, List<LemonToplist>>>()
        private val discoverNewSongsCache = ConcurrentHashMap<String, Pair<Long, List<UnifiedSong>>>()
        private val discoverNewAlbumsCache = ConcurrentHashMap<String, Pair<Long, List<UnifiedAlbum>>>()
        private const val DISCOVER_CACHE_TTL_MS = 5 * 60 * 1000L

        private val HEX_CHARS = "0123456789abcdef".toCharArray()
        private val md5MemoCache = ConcurrentHashMap<String, String>(2048)

        fun md5(input: String): String {
            md5MemoCache[input]?.let { return it }
            val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            val hexChars = CharArray(bytes.size * 2)
            for (j in bytes.indices) {
                val v = bytes[j].toInt() and 0xFF
                hexChars[j * 2] = HEX_CHARS[v ushr 4]
                hexChars[j * 2 + 1] = HEX_CHARS[v and 0x0F]
            }
            val result = String(hexChars)
            if (md5MemoCache.size > 8192) md5MemoCache.clear()
            md5MemoCache[input] = result
            return result
        }

        fun getServerFilePath(songId: String, streamUrl: String? = null, coverUrl: String? = null): String? {
            if (!songId.startsWith("lemon_online_")) {
                songIdToPathMap[songId]?.let { return it }
            }
            val song = if (!songId.startsWith("lemon_online_")) songIdToSongMap[songId] else null
            val candidateUrls = listOfNotNull(streamUrl, coverUrl, song?.streamUrl, song?.coverUrl)
            for (url in candidateUrls) {
                if (url.contains("path=")) {
                    try {
                        val enc = url.substringAfter("path=").substringBefore("&")
                        val decoded = java.net.URLDecoder.decode(enc, "UTF-8").trim()
                        if (decoded.isNotBlank()) {
                            if (!songId.startsWith("lemon_online_")) {
                                songIdToPathMap[songId] = decoded
                            }
                            return decoded
                        }
                    } catch (_: Exception) {}
                }
            }
            return null
        }

        fun getServerTrackId(songId: String): String? = songIdToTrackIdMap[songId]

        fun registerServerFilePath(songId: String, filePath: String, trackId: String? = null) {
            if (songId.isNotBlank() && filePath.isNotBlank() && !songId.startsWith("lemon_online_")) {
                songIdToPathMap[songId] = filePath
            }
            if (songId.isNotBlank() && !trackId.isNullOrBlank() && !songId.startsWith("lemon_online_")) {
                songIdToTrackIdMap[songId] = trackId
            }
        }

        /**
         * 精准判定当前是否处于 Wi-Fi / 以太网 / 非计费网络环境：
         * 解决开启 VPN / 代理软件时 activeNetwork 仅报告 TRANSPORT_VPN 导致误判为蜂窝流量 (128k) 的问题
         */
        fun isWifiOrUnmeteredConnected(context: android.content.Context): Boolean {
            return try {
                val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                    ?: return true
                val activeCaps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                if (activeCaps != null) {
                    if (activeCaps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                        activeCaps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        return true
                    }
                }
                for (nw in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(nw) ?: continue
                    if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                         caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))) {
                        return true
                    }
                }
                if (activeCaps != null &&
                    !activeCaps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    activeCaps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                    return true
                }
                false
            } catch (_: Exception) {
                true
            }
        }

        val streamQualityConfigVersion = kotlinx.coroutines.flow.MutableStateFlow(0)

        fun notifyStreamQualityConfigChanged() {
            streamQualityConfigVersion.value += 1
        }

        /**
         * 读取用户在设置中配置的在线试听首选音质 (wifi_stream_quality / cellular_stream_quality)
         */
        fun getPreferredStreamQuality(context: android.content.Context): String {
            val prefs = context.getSharedPreferences("lemon_settings_prefs", android.content.Context.MODE_PRIVATE)
            val isWifi = isWifiOrUnmeteredConnected(context)
            val raw = if (isWifi) {
                prefs.getString("wifi_stream_quality", "320k") ?: "320k"
            } else {
                prefs.getString("cellular_stream_quality", "128k") ?: "128k"
            }
            return AudioQuality.fromKey(raw).key
        }

        /**
         * 逐档降级音质序列 (flac24bit -> flac -> 320k -> 128k)，避免首选音质不可用时直接跳降为 128k
         */
        fun getFallbackQualities(preferredQuality: String): List<String> {
            val order = listOf("flac24bit", "flac", "320k", "128k")
            val normalized = AudioQuality.fromKey(preferredQuality).key
            val startIdx = order.indexOfFirst { it.equals(normalized, ignoreCase = true) }
            return if (startIdx >= 0) {
                order.subList(startIdx, order.size)
            } else {
                listOf(normalized, "320k", "128k").distinct()
            }
        }
    }

    init {
        // 如果外部传入的本身就是 Token (形如 session-xxx 或大于 20 位的字符且不包含常规弱密码特征)，可作为初始 token
        if (authToken.isBlank() && (tokenOrPasswordPlain.startsWith("lemon-") || tokenOrPasswordPlain.length >= 32)) {
            authToken = tokenOrPasswordPlain
            sharedAuthTokens[authCacheKey] = authToken
            NetworkClientFactory.setActiveAuthToken(authToken)
        } else if (authToken.isNotBlank()) {
            NetworkClientFactory.setActiveAuthToken(authToken)
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
            val candidateToken = authToken.ifBlank { sharedAuthTokens[authCacheKey].orEmpty() }.ifBlank { tokenOrPasswordPlain }
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
                            sharedAuthTokens[authCacheKey] = authToken
                            NetworkClientFactory.setActiveAuthToken(authToken)
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
                    sharedAuthTokens[authCacheKey] = token
                    NetworkClientFactory.setActiveAuthToken(token)
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
        if (token.isNotBlank()) {
            authToken = token
            sharedAuthTokens[authCacheKey] = token
            NetworkClientFactory.setActiveAuthToken(token)
        }
    }

    suspend fun ensureAuthenticated(): Boolean = withContext(Dispatchers.IO) {
        if (authToken.isNotBlank()) return@withContext true
        val cached = sharedAuthTokens[authCacheKey]
        if (!cached.isNullOrBlank()) {
            authToken = cached
            NetworkClientFactory.setActiveAuthToken(cached)
            return@withContext true
        }
        if (tokenOrPasswordPlain.startsWith("lemon-") || tokenOrPasswordPlain.length >= 32) {
            authToken = tokenOrPasswordPlain
            sharedAuthTokens[authCacheKey] = authToken
            NetworkClientFactory.setActiveAuthToken(authToken)
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
                    val title = SongMatchingResolver.unescapeMusicText(
                        rawTitle.ifBlank { fileName.substringBeforeLast('.', fileName) }
                    )
                    val rawArtist = item.optString("artist").ifBlank { item.optString("parsedArtist") }
                    val artist = SongMatchingResolver.unescapeMusicText(rawArtist.ifBlank { "未知歌手" })
                    val album = SongMatchingResolver.unescapeMusicText(item.optString("album").ifBlank { "未知专辑" })
                    val durationSec = item.optDouble("duration", 0.0)
                    val durationMs = (durationSec * 1000).toLong()
                    val format = item.optString("format", "flac").lowercase()
                    val sizeBytes = item.optLong("size", 0L)
                    val bitRate = if (durationSec > 0 && sizeBytes > 0) {
                        ((sizeBytes * 8) / durationSec / 1000).toInt().coerceIn(128, 1411)
                    } else {
                        if (format in listOf("flac", "ape", "wav")) 960 else 320
                    }

                    val trackId = item.optString("trackId").ifBlank { item.optString("id") }
                    val mtime = item.optLong("mtime", 0L)
                    val addedTs = when {
                        mtime > 10_000_000_000L -> mtime
                        mtime > 0L -> mtime * 1000L
                        else -> 0L
                    }

                    val songId = "lemon_${md5(filePath)}"
                    songIdToPathMap[songId] = filePath
                    if (trackId.isNotBlank()) {
                        songIdToTrackIdMap[songId] = trackId
                    }
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
                        relativeFolderPath = extractRelativeFolderPath(filePath, artist, album),
                        addedTimestamp = addedTs
                    )
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
                                    val name = SongMatchingResolver.unescapeMusicText(dlItem.optString("name", "未命名歌曲"))
                                    val singer = SongMatchingResolver.unescapeMusicText(dlItem.optString("singer", "未知歌手"))
                                    val album = SongMatchingResolver.unescapeMusicText(dlItem.optString("album", "未知专辑"))
                                    val songId = "lemon_${md5(filePath)}"
                                    songIdToPathMap[songId] = filePath
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
                                        relativeFolderPath = extractRelativeFolderPath(filePath, singer, album),
                                        // 这些是通过 /api/download/list 补全的条目，曲库接口里没有它们，
                                        // 也就没有 mtime；给个时间戳而不是 0，否则「按加入时间」会把它们
                                        // 一律甩到末尾，与"刚在服务器下载完 = 最近添加"的事实相反。
                                        addedTimestamp = dlItem.optLong("mtime", 0L).let { m ->
                                            when {
                                                m > 10_000_000_000L -> m
                                                m > 0L -> m * 1000L
                                                else -> System.currentTimeMillis()
                                            }
                                        }
                                    )
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

    /**
     * 服务器「最近添加」曲目。
     *
     * 这里按 addedTimestamp（曲库解析时由服务器文件的 mtime 推得）倒序取前 limit 条，
     * 与曲库「按加入时间」排序共用同一口径。此前是直接遍历 ConcurrentHashMap 取前 N 条，
     * 拿到的是哈希顺序，既不等于服务器顺序也不等于时间顺序，导致"最近添加"卡片内容随机。
     */
    fun getRecentlyAdded(limit: Int = 30): Result<List<UnifiedSong>> {
        if (limit <= 0) return Result.success(emptyList())
        val sorted = songIdToSongMap.values
            .asSequence()
            .filter { it.addedTimestamp > 0L }
            .sortedByDescending { it.addedTimestamp }
            .take(limit)
            .toMutableList()
        // 没有时间戳的条目（极少：服务器未提供 mtime）补位到末尾，不占掉有名额的时间序条目
        if (sorted.size < limit) {
            for (song in songIdToSongMap.values) {
                if (sorted.size >= limit) break
                if (song.addedTimestamp <= 0L) sorted.add(song)
            }
        }
        return Result.success(sorted)
    }

    /**
     * 读取服务端「最近播放」足迹 (/api/library/user-data 的 recentPlays 字段)。
     * 与服务端本地曲目、全网在线曲目两种记录形态兼容，返回可供「我的 → 最近播放」直接渲染的歌曲列表。
     *
     * 此前这个函数只是把曲库哈希表原样 take(30)，既不是播放历史也不是时间序，
     * 于是资料库"最近播放"卡片内容与实际听过什么完全无关 —— 这就是该卡片"歌曲不对"的根因。
     */
    suspend fun getRecentlyPlayed(limit: Int = 30): Result<List<UnifiedSong>> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val userData = getLibraryUserData().getOrNull()
                ?: return@withContext Result.success(emptyList())
            val arr = userData.optJSONArray("recentPlays")
                ?: userData.optJSONArray("recent_plays")
                ?: userData.optJSONArray("recents")
                ?: return@withContext Result.success(emptyList())

            val list = ArrayList<UnifiedSong>(arr.length())
            val seenIds = HashSet<String>()
            for (i in 0 until arr.length()) {
                if (list.size >= limit) break
                val song = parseUserDataTrack(arr.opt(i)) ?: continue
                if (seenIds.add(song.id)) list.add(song)
            }
            Result.success(list)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch server recent plays", e)
            Result.failure(e)
        }
    }

    /**
     * 将 /api/library/user-data 中的单条曲目记录 (favorites / recentPlays 通用结构) 映射为 UnifiedSong。
     * 同时兼容「服务端本地文件路径」与「全网在线音源」两种记录形态，无法识别时返回 null。
     */
    private fun parseUserDataTrack(item: Any?): UnifiedSong? {
        val obj = item as? JSONObject ?: return null
        val sName = SongMatchingResolver.unescapeMusicText(obj.optString("name").ifBlank { obj.optString("title") })
        val sSinger = SongMatchingResolver.unescapeMusicText(obj.optString("singer").ifBlank { obj.optString("artist", "未知歌手") })
        val sAlbum = SongMatchingResolver.unescapeMusicText(obj.optString("album", "未知专辑"))
        val sLocalPath = obj.optString("localPath").ifBlank { obj.optString("filePath") }
        val sSource = obj.optString("source").ifBlank { obj.optString("platform") }
        val sSongId = obj.optString("songId").ifBlank { obj.optString("id") }
        val durMs = (obj.optDouble("interval", obj.optDouble("duration", 0.0)) * 1000).toLong()
        val key = obj.optString("key")
        var sPic = obj.optString("picUrl").ifBlank { obj.optString("img") }
        if (sPic.isNotBlank() && !sPic.startsWith("http") && !sPic.startsWith("data:")) {
            sPic = "$cleanBase$sPic"
        }

        if (sLocalPath.isNotBlank() || key.startsWith("local:")) {
            val cleanPath = (if (sLocalPath.isNotBlank()) sLocalPath else key.removePrefix("local:")).trim()
            if (cleanPath.isBlank()) return null
            val songId = "lemon_${md5(cleanPath)}"
            return UnifiedSong(
                id = songId,
                title = sName.ifBlank { cleanPath.substringAfterLast('/').substringBeforeLast('.') },
                artist = sSinger,
                artistId = "artist_${sSinger.hashCode()}",
                album = sAlbum,
                albumId = "album_${sAlbum.hashCode()}",
                durationMs = durMs,
                coverUrl = if (sPic.isNotBlank()) sPic else getCoverArtUrlForPath(cleanPath),
                streamUrl = getStreamUrlForPath(cleanPath),
                serverId = "lemon_music",
                localFilePath = null,
                downloadStatus = DownloadStatus.NOT_DOWNLOADED,
                bitRate = 320,
                format = cleanPath.substringAfterLast('.', "flac").lowercase(),
                isFavorite = false,
                relativeFolderPath = extractRelativeFolderPath(cleanPath, sSinger, sAlbum)
            )
        }

        if (sSource.isNotBlank() && sSource != "local") {
            val platform = sSource.ifBlank { "kw" }
            val rawId = sSongId.ifBlank { key }
            if (rawId.isBlank()) return null
            val onlineId = if (sSongId.startsWith("lemon_online_")) sSongId else "lemon_online_${platform}_$rawId"
            return UnifiedSong(
                id = onlineId,
                title = sName.ifBlank { "在线曲目" },
                artist = sSinger,
                artistId = "artist_${sSinger.hashCode()}",
                album = sAlbum,
                albumId = "album_${sAlbum.hashCode()}",
                durationMs = durMs,
                coverUrl = sPic,
                streamUrl = "lemon_online://$platform/$rawId",
                serverId = "lemon_online",
                localFilePath = null,
                downloadStatus = DownloadStatus.NOT_DOWNLOADED,
                bitRate = 320,
                format = "mp3",
                isFavorite = false,
                rawMetaJson = obj.toString()
            )
        }
        return null
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
                    // 代表曲目路径的字段名各版本服务端不一致，逐个兜底，
                    // 否则专辑卡片拿不到 /api/tag/cover 地址，全部显示成占位图。
                    val samplePath = sequenceOf("samplePath", "sample", "path", "filePath", "cover")
                        .map { item.optString(it) }
                        .firstOrNull { it.isNotBlank() }
                        .orEmpty()
                    val yearStr = item.optString("year")
                    val year = yearStr.toIntOrNull()

                    val sampleSongId = if (samplePath.isNotBlank()) "lemon_${md5(samplePath)}" else ""
                    if (samplePath.isNotBlank()) songIdToPathMap[sampleSongId] = samplePath

                    // 服务端直接给了封面地址时优先用它（自定义封面/外链封面）；
                    // 否则用代表曲目路径反查 /api/tag/cover 内嵌封面。
                    val serverCover = sequenceOf("coverUrl", "cover_url", "picUrl", "img")
                        .map { item.optString(it) }
                        .firstOrNull { it.startsWith("http") }
                        .orEmpty()
                    val cover = when {
                        serverCover.isNotBlank() -> serverCover
                        sampleSongId.isNotBlank() -> getCoverArtUrl(sampleSongId)
                        else -> ""
                    }

                    albums.add(
                        UnifiedAlbum(
                            id = "lemon_album_${md5("$artist/$name")}",
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
                    val name = SongMatchingResolver.unescapeMusicText(item.optString("name").ifBlank { item.optString("title", "精选推荐") })
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
                    val name = SongMatchingResolver.unescapeMusicText(item.optString("name").ifBlank { item.optString("title", "热歌榜") })
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
     * 解析柠檬服务端返回的时长字段（支持 "03:45" 格式与秒/毫秒数值）
     */
    private fun parseDurationMs(s: JSONObject): Long {
        val rawInterval = s.optString("interval").trim()
        if (rawInterval.contains(":")) {
            val parts = rawInterval.split(":").mapNotNull { it.trim().toLongOrNull() }
            if (parts.size == 2) return (parts[0] * 60 + parts[1]) * 1000L
            if (parts.size == 3) return (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
        }
        val numVal = s.optDouble("duration", Double.NaN).let {
            if (it.isNaN() || it <= 0.0) s.optDouble("interval", 0.0) else it
        }
        if (numVal.isNaN() || numVal <= 0.0) return 0L
        return if (numVal > 10000.0) numVal.toLong() else (numVal * 1000.0).toLong()
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
                    val title = SongMatchingResolver.unescapeMusicText(
                        s.optString("name").ifBlank { s.optString("title", "未知曲目") }
                    )
                    val singer = SongMatchingResolver.unescapeMusicText(
                        s.optString("singer").ifBlank { s.optString("artist", "未知歌手") }
                    )
                    val albumName = SongMatchingResolver.unescapeMusicText(
                        s.optString("albumName").ifBlank { s.optString("album", "在线精选") }
                    )
                    val durationMs = parseDurationMs(s)
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
                            durationMs = durationMs,
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
        val cacheKey = "${cleanBase}_${source}_${region}_${page}_$limit"
        val cached = discoverNewAlbumsCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first < DISCOVER_CACHE_TTL_MS) {
            return@withContext Result.success(cached.second)
        }
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
                    val name = SongMatchingResolver.unescapeMusicText(item.optString("name").ifBlank { item.optString("title", "最新专辑") })
                    val artist = SongMatchingResolver.unescapeMusicText(item.optString("artist").ifBlank { item.optString("singer", "未知歌手") })
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
                if (albums.isNotEmpty()) {
                    discoverNewAlbumsCache[cacheKey] = Pair(System.currentTimeMillis(), albums)
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

            // C. 若是发现新碟首发专辑：调用 /api/album?source=...&id=...（回退 /api/search/album/detail）
            if (playlistId.startsWith("lemon_discover_album_")) {
                val parts = playlistId.removePrefix("lemon_discover_album_").split("_", limit = 2)
                val source = parts.getOrNull(0) ?: "tx"
                val rawId = parts.getOrNull(1) ?: playlistId
                val encId = URLEncoder.encode(rawId, "UTF-8")
                val url = "$cleanBase/api/album?source=$source&id=$encId"
                val res = fetchOnlineSongList(url, source)
                if (res.isSuccess && !res.getOrNull().isNullOrEmpty()) {
                    return@withContext res
                }
                val fallbackUrl = "$cleanBase/api/search/album/detail?source=$source&id=$encId"
                return@withContext fetchOnlineSongList(fallbackUrl, source)
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
                                val sName = SongMatchingResolver.unescapeMusicText(item.optString("name").ifBlank { item.optString("title") })
                                val sSinger = SongMatchingResolver.unescapeMusicText(item.optString("singer").ifBlank { item.optString("artist", "未知歌手") })
                                val sAlbum = SongMatchingResolver.unescapeMusicText(item.optString("album", "未知专辑"))
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
                                    val streamUrl = getStreamUrlForPath(cleanPath)
                                    val coverUrl = if (sPic.isNotBlank()) sPic else getCoverArtUrlForPath(cleanPath)
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
                                        relativeFolderPath = extractRelativeFolderPath(cleanPath, sSinger, sAlbum)
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
                            val sName = SongMatchingResolver.unescapeMusicText(snapshot.optString("name").ifBlank { snapshot.optString("title") })
                            val sSinger = SongMatchingResolver.unescapeMusicText(snapshot.optString("singer").ifBlank { snapshot.optString("artist", "未知歌手") })
                            val sAlbum = SongMatchingResolver.unescapeMusicText(snapshot.optString("album", "未知专辑"))
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
                                val streamUrl = getStreamUrlForPath(cleanLocalPath)
                                val coverUrl = if (sPic.isNotBlank()) sPic else getCoverArtUrlForPath(cleanLocalPath)
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
                                    relativeFolderPath = extractRelativeFolderPath(cleanLocalPath, sSinger, sAlbum)
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
                                val title = SongMatchingResolver.unescapeMusicText(item.optString("title").ifBlank { fileName.substringBeforeLast('.', fileName) })
                                val artist = SongMatchingResolver.unescapeMusicText(item.optString("artist", "未知歌手"))
                                val album = SongMatchingResolver.unescapeMusicText(item.optString("album", "未知专辑"))
                                val durationMs = (item.optDouble("duration", 0.0) * 1000).toLong()
                                val songId = "lemon_${md5(filePath)}"
                                songIdToPathMap[songId] = filePath

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
     * 获取指定路径的音频 Range 流媒体播放链接
     * 自动识别 .ape 格式并路由至服务端 FFmpeg 实时 WAV 转码流端点 (/api/play/local-ape)
     */
    fun getStreamUrlForPath(path: String, quality: String? = null): String {
        if (path.isBlank()) return ""
        val cleanPath = path.removePrefix("local:").trim()
        val isApe = cleanPath.substringAfterLast('.', "").lowercase() == "ape"
        val endpoint = if (isApe) "/api/play/local-ape" else "/api/play/local"
        val enc = try { URLEncoder.encode(cleanPath, "UTF-8").replace("+", "%20") } catch (_: Exception) { cleanPath }
        val qParam = if (!quality.isNullOrBlank()) "&quality=$quality" else ""
        return if (authToken.isNotBlank()) {
            "$cleanBase$endpoint?path=$enc&token=$authToken$qParam"
        } else {
            "$cleanBase$endpoint?path=$enc$qParam"
        }
    }

    /**
     * 通过服务端官方 POST /api/play/url 换取带 HMAC 签名票据 (?ticket=) 的服务器本地播放流链接
     */
    suspend fun resolveServerLocalPlayUrl(filePath: String?, trackId: String? = null, quality: String = "320k"): Result<String> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val cleanPath = filePath?.removePrefix("local:")?.trim().orEmpty()
            if (cleanPath.isBlank() && trackId.isNullOrBlank()) {
                return@withContext Result.failure(Exception("缺少有效的文件路径或 trackId"))
            }
            val payload = JSONObject().apply {
                put("source", "local")
                if (cleanPath.isNotBlank()) {
                    put("filePath", cleanPath)
                    put("path", cleanPath)
                }
                if (!trackId.isNullOrBlank()) {
                    put("trackId", trackId)
                }
                put("quality", quality)
                put("type", quality)
            }
            val req = newAuthRequest("$cleanBase/api/play/url")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    val json = JSONObject(body)
                    val url = json.optString("url").trim()
                    if (url.isNotBlank() && !url.equals("null", ignoreCase = true)) {
                        var fullUrl = if (url.startsWith("/")) "$cleanBase$url" else url
                        if (fullUrl.startsWith(cleanBase) && authToken.isNotBlank() && !fullUrl.contains("token=") && !fullUrl.contains("ticket=")) {
                            val sep = if (fullUrl.contains("?")) "&" else "?"
                            fullUrl = "${fullUrl}${sep}token=$authToken"
                        }
                        if (quality.isNotBlank() && !fullUrl.contains("quality=")) {
                            val sep = if (fullUrl.contains("?")) "&" else "?"
                            fullUrl = "${fullUrl}${sep}quality=$quality"
                        }
                        return@withContext Result.success(fullUrl)
                    }
                }
                if (cleanPath.isNotBlank()) {
                    Result.success(getStreamUrlForPath(cleanPath, quality))
                } else {
                    Result.failure(Exception("无法解析服务器本地曲目播放地址"))
                }
            }
        } catch (e: Exception) {
            val cleanPath = filePath?.removePrefix("local:")?.trim().orEmpty()
            if (cleanPath.isNotBlank()) {
                Result.success(getStreamUrlForPath(cleanPath, quality))
            } else {
                Result.failure(e)
            }
        }
    }

    override fun getStreamUrl(songId: String, maxBitrate: Int?): String {
        val path = songIdToPathMap[songId] ?: ""
        return if (path.isNotBlank()) {
            getStreamUrlForPath(path)
        } else ""
    }

    /**
     * 获取指定路径的内嵌专辑封面图链接
     */
    fun getCoverArtUrlForPath(path: String): String {
        if (path.isBlank()) return ""
        val cleanPath = path.removePrefix("local:").trim()
        val enc = try { URLEncoder.encode(cleanPath, "UTF-8").replace("+", "%20") } catch (_: Exception) { cleanPath }
        return if (authToken.isNotBlank()) {
            "$cleanBase/api/tag/cover?path=$enc&token=$authToken"
        } else {
            "$cleanBase/api/tag/cover?path=$enc"
        }
    }

    override fun getCoverArtUrl(mediaId: String, size: Int): String {
        val path = songIdToPathMap[mediaId] ?: ""
        return if (path.isNotBlank()) {
            getCoverArtUrlForPath(path)
        } else ""
    }

    /**
     * 构造符合服务端 /api/play/lyric OpenAPI 规范的完整请求载荷
     */
    private fun buildLyricPayload(songId: String, songOverride: UnifiedSong? = null): JSONObject {
        val cachedSong = songIdToSongMap[songId]
        val song = songOverride ?: cachedSong
        val path = getServerFilePath(
            songId = songId,
            streamUrl = song?.streamUrl ?: cachedSong?.streamUrl,
            coverUrl = song?.coverUrl ?: cachedSong?.coverUrl
        ).orEmpty()
        val trackId = getServerTrackId(songId).orEmpty()

        val payload = JSONObject()
        val metaJson = song?.rawMetaJson?.takeIf { it.isNotBlank() }
            ?: cachedSong?.rawMetaJson?.takeIf { it.isNotBlank() }
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

        val effectiveId = song?.id ?: songId
        if (effectiveId.startsWith("lemon_online_")) {
            val cleanId = effectiveId.removePrefix("lemon_online_")
            val src = if (cleanId.contains("_")) cleanId.substringBefore("_") else "kw"
            val rawId = if (cleanId.contains("_")) cleanId.substringAfter("_") else cleanId
            val resolvedSongId = payload.optString("songId").ifBlank { payload.optString("id") }.ifBlank { rawId }
            if (path.isBlank() && trackId.isBlank()) {
                payload.remove("id")
                payload.remove("trackId")
            }
            if (!payload.has("source") || payload.optString("source").isBlank()) payload.put("source", src)
            if (resolvedSongId.isNotBlank()) payload.put("songId", resolvedSongId)
            if (!payload.has("songmid") && (src == "tx" || src == "kw")) payload.put("songmid", resolvedSongId)
            if (!payload.has("hash") && src == "kg") payload.put("hash", resolvedSongId)
            if (!payload.has("copyrightId") && src == "mg") payload.put("copyrightId", resolvedSongId)
        }

        val title = song?.title?.takeIf { it.isNotBlank() } ?: cachedSong?.title.orEmpty()
        val artist = song?.artist?.takeIf { it.isNotBlank() } ?: cachedSong?.artist.orEmpty()
        val album = song?.album?.takeIf { it.isNotBlank() } ?: cachedSong?.album.orEmpty()
        val durationSec = ((song?.durationMs ?: cachedSong?.durationMs ?: 0L) / 1000L).toInt()

        if (title.isNotBlank()) payload.put("name", title)
        if (artist.isNotBlank()) payload.put("singer", artist)
        if (album.isNotBlank()) payload.put("album", album)
        if (durationSec > 0 && !payload.has("interval")) payload.put("interval", durationSec)
        if (path.isNotBlank()) {
            payload.put("filePath", path)
            payload.put("path", path)
        }
        if (trackId.isNotBlank()) {
            payload.put("trackId", trackId)
        }
        return payload
    }

    /**
     * 读取服务器曲库音频文件的内嵌歌词与伴随 .lrc 歌词（双通道保障 + 编码自动修复）：
     * 1. 优先调用柠檬音乐服务端 POST /api/tag/read (读取音频内嵌标签与服务端磁盘同名 .lrc) 并自动修复 latin1/GBK/UTF-16 乱码；
     * 2. 若服务端 /api/tag/read 未返回或存在编码替换损坏 (\uFFFD)，通过 HTTP Range 拉取音频文件前 384KB 头部字节，
     *    使用本地 EmbeddedLyricsExtractor + SmartCharsetDecoder 直接从原始字节流提取内嵌歌词（支持 ID3v2 USLT/TXXX、FLAC Vorbis、M4A ©lyr、APEv2）。
     */
    suspend fun getEmbeddedLyricsFromServer(
        songId: String,
        songOverride: UnifiedSong? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val cachedSong = songIdToSongMap[songId]
            val song = songOverride ?: cachedSong
            val rawPath = getServerFilePath(
                songId = songId,
                streamUrl = song?.streamUrl ?: cachedSong?.streamUrl,
                coverUrl = song?.coverUrl ?: cachedSong?.coverUrl
            )?.removePrefix("local:")?.trim().orEmpty()
            if (rawPath.isBlank()) return@withContext null

            // 通道 A：请求服务端 /api/tag/read 接口读取内嵌标签与服务端同名 .lrc
            var tagApiLyric: String? = null
            try {
                val tagPayload = JSONObject().apply {
                    put("filePath", rawPath)
                    put("path", rawPath)
                }
                val tagReq = newAuthRequest("$cleanBase/api/tag/read")
                    .post(tagPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                client.newCall(tagReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        if (body.startsWith("{")) {
                            val json = JSONObject(body)
                            val dataObj = json.optJSONObject("data")
                            val rawLyric = dataObj?.optString("lyric", "")
                                ?.ifBlank { json.optString("lyric", "") }
                                .orEmpty()
                            val repaired = SmartCharsetDecoder.repairMojibakeIfNeeded(rawLyric)
                            if (repaired.isNotBlank() && !repaired.equals("null", ignoreCase = true)) {
                                tagApiLyric = repaired
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Server /api/tag/read lyric query skipped: ${e.message}")
            }

            if (!tagApiLyric.isNullOrBlank() && !SmartCharsetDecoder.looksSuspiciousOrGarbled(tagApiLyric)) {
                return@withContext tagApiLyric
            }

            // 通道 B：HTTP Range 直接读取服务器音频文件头部字节进行多编码无损内嵌歌词提取
            try {
                val streamUrl = getStreamUrlForPath(rawPath)
                if (streamUrl.isNotBlank()) {
                    val rangeReq = newAuthRequest(streamUrl)
                        .header("Range", "bytes=0-393215")
                        .get()
                        .build()
                    client.newCall(rangeReq).execute().use { resp ->
                        if (resp.isSuccessful || resp.code == 206) {
                            val bytes = resp.body?.bytes()
                            if (bytes != null && bytes.size > 32) {
                                val extracted = EmbeddedLyricsExtractor.extractFromBytes(bytes, rawPath)
                                if (!extracted.isNullOrBlank() && !extracted.equals("null", ignoreCase = true)) {
                                    return@withContext extracted
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Server HTTP Range embedded lyric extraction skipped: ${e.message}")
            }

            return@withContext tagApiLyric
        } catch (_: Exception) {
            return@withContext null
        }
    }

    /**
     * 获取歌词 (优先读取资料库服务器音频内嵌歌词，无内嵌歌词时请求 /api/play/lyric)
     */
    override suspend fun getLyrics(songId: String): Result<LyricResult> = getLyricsForSong(songId, null)

    suspend fun getLyricsForSong(song: UnifiedSong): Result<LyricResult> = getLyricsForSong(song.id, song)

    suspend fun getLyricsForSong(songId: String, songOverride: UnifiedSong? = null): Result<LyricResult> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            // 1. 若为服务器资料库本地曲目，优先读取服务器音频内嵌歌词与同名 .lrc
            val embeddedLyric = getEmbeddedLyricsFromServer(songId, songOverride)
            if (!embeddedLyric.isNullOrBlank()) {
                val parsedEmbedded = LrcParser.parse(embeddedLyric)
                if (parsedEmbedded.lines.isNotEmpty()) {
                    return@withContext Result.success(parsedEmbedded)
                }
            }

            // 2. 在线歌词接口 (/api/play/lyric)
            val payload = buildLyricPayload(songId, songOverride)
            val req = newAuthRequest("$cleanBase/api/play/lyric")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("歌词获取失败"))
                val json = JSONObject(body)
                val lyricStr = json.optString("lxlyric")
                    .ifBlank { json.optString("lyric") }
                    .ifBlank { json.optString("ylyric") }
                val repaired = SmartCharsetDecoder.repairMojibakeIfNeeded(lyricStr)
                if (repaired.isNotBlank()) {
                    val parsed = LrcParser.parse(repaired)
                    if (parsed.lines.isNotEmpty()) {
                        return@withContext Result.success(parsed)
                    }
                }
                Result.failure(Exception("歌词为空"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取原始歌词文本 (优先读取资料库服务器音频内嵌歌词，无内嵌歌词时请求 /api/play/lyric)
     */
    suspend fun getRawLyrics(songId: String): Result<String> = getRawLyricsForSong(songId, null)

    suspend fun getRawLyricsForSong(song: UnifiedSong): Result<String> = getRawLyricsForSong(song.id, song)

    suspend fun getRawLyricsForSong(songId: String, songOverride: UnifiedSong? = null): Result<String> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            // 1. 优先读取服务器曲库音频内嵌歌词与伴随 .lrc
            val embeddedLyric = getEmbeddedLyricsFromServer(songId, songOverride)
            if (!embeddedLyric.isNullOrBlank()) {
                return@withContext Result.success(embeddedLyric)
            }

            // 2. 在线歌词接口 (/api/play/lyric)
            val payload = buildLyricPayload(songId, songOverride)
            val req = newAuthRequest("$cleanBase/api/play/lyric")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("歌词获取失败"))
                val json = JSONObject(body)
                val lyricStr = json.optString("lxlyric")
                    .ifBlank { json.optString("lyric") }
                    .ifBlank { json.optString("ylyric") }
                val repaired = SmartCharsetDecoder.repairMojibakeIfNeeded(lyricStr)
                if (repaired.isNotBlank()) {
                    return@withContext Result.success(repaired)
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
     * 全网在线检索歌曲（柠檬音乐服务端 /api/search）
     * 支持酷我、酷狗、QQ音乐、网易云、咪咕等平台聚合
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
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("在线搜索失败 (HTTP ${resp.code})"))
                val json = JSONObject(body)
                val dataObj = json.optJSONObject("data")
                val list = dataObj?.optJSONArray("list") ?: json.optJSONArray("data") ?: JSONArray()
                val songs = ArrayList<UnifiedSong>(list.length())

                for (i in 0 until list.length()) {
                    val s = list.optJSONObject(i) ?: continue
                    val songId = s.optString("songmid")
                        .ifBlank { s.optString("songId") }
                        .ifBlank { s.optString("hash") }
                        .ifBlank { s.optString("copyrightId") }
                        .ifBlank { s.optString("id", "s_$i") }
                    val name = SongMatchingResolver.unescapeMusicText(
                        s.optString("name").ifBlank { s.optString("title", "") }
                    )
                    if (name.isBlank()) continue
                    val singer = SongMatchingResolver.unescapeMusicText(
                        s.optString("singer").ifBlank { s.optString("artist", "未知歌手") }
                    )
                    val albumName = SongMatchingResolver.unescapeMusicText(
                        s.optString("albumName").ifBlank { s.optString("album", "") }
                    )
                    val durationMs = parseDurationMs(s)
                    var cover = s.optString("cover")
                        .ifBlank { s.optString("img") }
                        .ifBlank { s.optString("pic") }
                        .ifBlank { s.optString("picUrl") }
                        .ifBlank { s.optString("albumpic") }
                        .ifBlank { s.optString("imgurl") }
                    cover = normalizeImageUrl(cover, cleanBase)
                    val sSource = s.optString("source", source)

                    val unifiedId = "lemon_online_${sSource}_$songId"
                    songs.add(
                        UnifiedSong(
                            id = unifiedId,
                            title = name,
                            artist = singer,
                            album = albumName,
                            durationMs = durationMs,
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
     * 全网在线检索专辑（柠檬音乐服务端 /api/search/album & /api/album/search）
     */
    suspend fun searchOnlineAlbums(
        keyword: String,
        source: String = "kw",
        page: Int = 1,
        limit: Int = 30
    ): Result<List<UnifiedAlbum>> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val encKw = URLEncoder.encode(keyword, "UTF-8")
            val endpoints = listOf(
                "$cleanBase/api/search/album?keyword=$encKw&source=$source&page=$page&limit=$limit",
                "$cleanBase/api/album/search?keyword=$encKw&source=$source&page=$page&limit=$limit"
            )
            for (url in endpoints) {
                val req = newAuthRequest(url).get().build()
                val parsed = runCatching {
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string() ?: ""
                        if (!resp.isSuccessful) return@use null
                        val json = JSONObject(body)
                        val dataObj = json.optJSONObject("data")
                        val list = dataObj?.optJSONArray("list") ?: json.optJSONArray("data") ?: JSONArray()
                        val albums = ArrayList<UnifiedAlbum>(list.length())
                        for (i in 0 until list.length()) {
                            val item = list.optJSONObject(i) ?: continue
                            val id = item.optString("id")
                                .ifBlank { item.optString("albumMid") }
                                .ifBlank { item.optString("albumId", "album_$i") }
                            val name = SongMatchingResolver.unescapeMusicText(
                                item.optString("name").ifBlank { item.optString("title", "") }
                            )
                            if (name.isBlank()) continue
                            val artist = SongMatchingResolver.unescapeMusicText(
                                item.optString("artist").ifBlank { item.optString("singer", "未知歌手") }
                            )
                            var cover = item.optString("img")
                                .ifBlank { item.optString("cover") }
                                .ifBlank { item.optString("pic") }
                                .ifBlank { item.optString("picUrl") }
                            cover = normalizeImageUrl(cover, cleanBase)
                            val count = item.optInt("count", item.optInt("total", item.optInt("songCount", 0)))
                            val yearStr = item.optString("publishTime").ifBlank { item.optString("year") }
                            val year = yearStr.take(4).toIntOrNull()
                            val sSource = item.optString("source", source)
                            albums.add(
                                UnifiedAlbum(
                                    id = "lemon_discover_album_${sSource}_$id",
                                    title = name,
                                    artist = artist,
                                    coverUrl = cover,
                                    songCount = count,
                                    year = year
                                )
                            )
                        }
                        albums
                    }
                }.getOrNull()
                if (parsed != null) {
                    return@withContext Result.success(parsed)
                }
            }
            Result.failure(Exception("在线专辑搜索失败"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search online albums", e)
            Result.failure(e)
        }
    }

    /**
     * 全网在线检索歌单（柠檬音乐服务端 /api/search/playlist）
     */
    suspend fun searchOnlinePlaylists(
        keyword: String,
        source: String = "kw",
        page: Int = 1,
        limit: Int = 30
    ): Result<List<UnifiedPlaylist>> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val encKw = URLEncoder.encode(keyword, "UTF-8")
            val url = "$cleanBase/api/search/playlist?keyword=$encKw&source=$source&page=$page&limit=$limit"
            val req = newAuthRequest(url).get().build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("在线歌单搜索失败 (HTTP ${resp.code})"))
                val json = JSONObject(body)
                val dataObj = json.optJSONObject("data")
                val list = dataObj?.optJSONArray("list") ?: json.optJSONArray("data") ?: JSONArray()
                val playlists = ArrayList<UnifiedPlaylist>(list.length())

                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val id = item.optString("id").ifBlank { item.optString("play_id") }
                    val name = SongMatchingResolver.unescapeMusicText(
                        item.optString("name").ifBlank { item.optString("title", "") }
                    )
                    if (id.isBlank() || name.isBlank()) continue
                    var cover = item.optString("img")
                        .ifBlank { item.optString("cover") }
                        .ifBlank { item.optString("pic") }
                        .ifBlank { item.optString("picUrl") }
                    cover = normalizeImageUrl(cover, cleanBase)
                    val count = item.optInt("total", item.optInt("count", 0))
                    val sSource = item.optString("source", source)
                    playlists.add(
                        UnifiedPlaylist(
                            id = "lemon_rec_${sSource}_$id",
                            name = name,
                            coverUrl = cover,
                            songCount = count,
                            isOnline = true,
                            serverId = "lemon_music",
                            isDiscover = true
                        )
                    )
                }
                Result.success(playlists)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search online playlists", e)
            Result.failure(e)
        }
    }

    /**
     * 换取在线曲目播放流地址 (/api/play/url)
     * 严格对齐柠檬音乐服务端 (server/routes/play.js & server/utils/musicInfo.js)：
     * 1. 移除 id / trackId / filePath 等仅用于本地曲库定位的字段，避免服务端误判为本地曲目缺失而返回 404 TRACK_NOT_FOUND
     * 2. 完整传递 songId / songmid / hash / copyrightId / types 等字段，并补全请求音质对应的 types / _types 声明，防止落雪脚本因列表项未带高音质标识而拒绝取链
     * 3. 当服务端因 source.fallbackMode='ask' 返回 409 SOURCE_FALLBACK_REQUIRED 时，自动遍历 sourceFallbackOffer.alternatives 切换备用音源获取目标高音质
     */
    suspend fun resolveOnlineStreamUrl(
        songId: String,
        source: String = "kw",
        quality: String = "128k",
        metaJson: String? = null,
        fallbackTitle: String? = null,
        fallbackArtist: String? = null,
        refresh: Boolean = false,
        allowSearchFallback: Boolean = true
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val cleanId = songId.removePrefix("lemon_online_")
            val actualSource = if (cleanId.contains("_")) cleanId.substringBefore("_") else source
            val rawId = if (cleanId.contains("_")) cleanId.substringAfter("_") else cleanId
            val normalizedQuality = AudioQuality.fromKey(quality).key

            fun parsePlayUrlFromBody(body: String): String? {
                if (body.isBlank()) return null
                return runCatching {
                    val json = JSONObject(body)
                    val streamUrl = json.optString("url").ifBlank {
                        json.optJSONObject("data")?.optString("url").orEmpty()
                    }
                    if (streamUrl.isNotBlank()) {
                        if (streamUrl.startsWith("/")) "$cleanBase$streamUrl" else streamUrl
                    } else null
                }.getOrNull()
            }

            suspend fun requestPlayUrlOnce(
                targetSource: String,
                targetRawId: String,
                targetMetaJson: String?,
                sourceApiId: String? = null
            ): Pair<Int, String> {
                val payload = JSONObject()
                var extractedMetaId = ""
                if (!targetMetaJson.isNullOrBlank()) {
                    try {
                        val metaObj = JSONObject(targetMetaJson)
                        extractedMetaId = metaObj.optString("songId")
                            .ifBlank { metaObj.optString("id") }
                            .ifBlank { metaObj.optString("songmid") }
                            .ifBlank { metaObj.optString("hash") }
                            .ifBlank { metaObj.optString("copyrightId") }
                        val keys = metaObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            payload.put(k, metaObj.opt(k))
                        }
                    } catch (_: Exception) {}
                }
                // 关键修复：柠檬服务端 POST /api/play/url 将 id 与 trackId 视为本地 SQLite 曲目 ID，
                // 在线歌曲请求必须剔除 id / trackId / filePath，改用 songId 标识在线曲目
                payload.remove("id")
                payload.remove("trackId")
                payload.remove("filePath")
                payload.remove("path")
                payload.remove("localPath")

                val effectiveSongId = extractedMetaId.ifBlank { targetRawId }
                if (!payload.has("source") || payload.optString("source").isBlank() || payload.optString("source") == "local") {
                    payload.put("source", targetSource)
                }
                if (effectiveSongId.isNotBlank()) {
                    payload.put("songId", effectiveSongId)
                }
                if (!payload.has("songmid") && (targetSource == "tx" || targetSource == "kw")) {
                    payload.put("songmid", effectiveSongId)
                }
                if (!payload.has("hash") && targetSource == "kg") {
                    payload.put("hash", effectiveSongId)
                }
                if (!payload.has("copyrightId") && targetSource == "mg") {
                    payload.put("copyrightId", effectiveSongId)
                }
                if (!fallbackTitle.isNullOrBlank() && (!payload.has("name") || payload.optString("name").isBlank())) {
                    payload.put("name", fallbackTitle)
                }
                if (!fallbackArtist.isNullOrBlank() && (!payload.has("singer") || payload.optString("singer").isBlank())) {
                    payload.put("singer", fallbackArtist)
                }

                // 补全 types 与 _types 声明，确保落雪音源脚本不会因推荐/榜单接口未返回高音质列表而拒绝 320k/flac/flac24bit
                try {
                    val typesArr = payload.optJSONArray("types") ?: JSONArray()
                    var hasRequestedQuality = false
                    for (i in 0 until typesArr.length()) {
                        val item = typesArr.optJSONObject(i)
                        if (item != null && item.optString("type").equals(normalizedQuality, ignoreCase = true)) {
                            hasRequestedQuality = true
                            break
                        }
                    }
                    if (!hasRequestedQuality) {
                        typesArr.put(JSONObject().apply {
                            put("type", normalizedQuality)
                            put("size", "")
                        })
                    }
                    payload.put("types", typesArr)

                    val underTypes = payload.optJSONObject("_types") ?: JSONObject()
                    if (!underTypes.has(normalizedQuality)) {
                        underTypes.put(normalizedQuality, JSONObject().apply { put("size", "") })
                    }
                    payload.put("_types", underTypes)
                } catch (_: Exception) {}

                payload.put("quality", normalizedQuality)
                payload.put("type", normalizedQuality)
                if (!sourceApiId.isNullOrBlank()) {
                    payload.put("sourceApiId", sourceApiId)
                }
                if (refresh) {
                    payload.put("refresh", true)
                }

                val req = newAuthRequest("$cleanBase/api/play/url")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    return Pair(resp.code, body)
                }
            }

            suspend fun requestPlayUrlWithSourceFallback(
                targetSource: String,
                targetRawId: String,
                targetMetaJson: String?
            ): Pair<Int, String> {
                val (code, body) = requestPlayUrlOnce(targetSource, targetRawId, targetMetaJson, null)
                if (code in 200..299 && parsePlayUrlFromBody(body) != null) {
                    return Pair(code, body)
                }
                // 当服务端返回 409 SOURCE_FALLBACK_REQUIRED 时，自动尝试候选备用音源脚本
                if (code == 409 && body.isNotBlank()) {
                    val altIds = runCatching {
                        val json = JSONObject(body)
                        val offer = json.optJSONObject("sourceFallbackOffer")
                        val alts = offer?.optJSONArray("alternatives") ?: JSONArray()
                        buildList {
                            for (i in 0 until alts.length()) {
                                val id = alts.optJSONObject(i)?.optString("id").orEmpty()
                                if (id.isNotBlank()) add(id)
                            }
                        }
                    }.getOrDefault(emptyList())

                    for (altId in altIds) {
                        val (altCode, altBody) = requestPlayUrlOnce(targetSource, targetRawId, targetMetaJson, altId)
                        if (altCode in 200..299 && parsePlayUrlFromBody(altBody) != null) {
                            return Pair(altCode, altBody)
                        }
                    }
                }
                return Pair(code, body)
            }

            val (firstCode, firstBody) = requestPlayUrlWithSourceFallback(actualSource, rawId, metaJson)
            if (firstCode in 200..299) {
                parsePlayUrlFromBody(firstBody)?.let { return@withContext Result.success(it) }
            }

            // 仅在允许搜索回退且该歌曲自身的所有音质尝试均失败时，才通过搜索补全同版本曲目元数据重试（严格校验版本一致性，绝不回退到非匹配的首个搜索结果）
            if (allowSearchFallback) {
                val keyword = listOfNotNull(
                    fallbackTitle?.takeIf { it.isNotBlank() },
                    fallbackArtist?.takeIf { it.isNotBlank() && it != "未知歌手" && !it.equals("Unknown Artist", ignoreCase = true) }
                ).joinToString(" ").trim()

                if (keyword.isNotBlank()) {
                    val candidateSources = listOf(actualSource, "kw", "tx", "wy", "kg", "mg").distinct()
                    for (candSource in candidateSources) {
                        val searchRes = searchOnline(keyword = keyword, source = candSource, page = 1, limit = 10).getOrNull().orEmpty()
                        val bestMatch = searchRes.firstOrNull { it.id == songId }
                            ?: searchRes.firstOrNull {
                                fallbackTitle != null && SongMatchingResolver.isSongMatch(
                                    it.title, it.artist, 0L,
                                    fallbackTitle, fallbackArtist ?: "", 0L
                                )
                            }

                        if (bestMatch != null && !bestMatch.rawMetaJson.isNullOrBlank()) {
                            val candCleanId = bestMatch.id.removePrefix("lemon_online_")
                            val candSrc = if (candCleanId.contains("_")) candCleanId.substringBefore("_") else candSource
                            val candRawId = if (candCleanId.contains("_")) candCleanId.substringAfter("_") else candCleanId
                            val (retryCode, retryBody) = requestPlayUrlWithSourceFallback(candSrc, candRawId, bestMatch.rawMetaJson)
                            if (retryCode in 200..299) {
                                parsePlayUrlFromBody(retryBody)?.let { return@withContext Result.success(it) }
                            }
                        }
                    }
                }
            }

            val errMsg = runCatching {
                val json = JSONObject(firstBody)
                json.optString("error").ifBlank { json.optString("msg") }
            }.getOrNull()
            Result.failure(Exception(errMsg?.ifBlank { null } ?: "获取在线播放地址失败 (HTTP $firstCode)"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 按用户设置的试听音质及逐级回退策略 (flac24bit -> flac -> 320k -> 128k) 解析在线播放地址，
     * 并返回实际生效的音质、格式与比特率，以便播放器界面实时展示准确音质标签。
     * 优先在当前歌曲自身的 ID/metaJson 上完成全音质阶梯回退（避免因高音质不可用而过早跨源搜索串歌至录音室版），
     * 仅当自身所有音质均失败时才启用版本严格匹配的搜索回退。
     */
    suspend fun resolveOnlineStreamWithQuality(
        songId: String,
        source: String = "kw",
        preferredQuality: String = "320k",
        metaJson: String? = null,
        fallbackTitle: String? = null,
        fallbackArtist: String? = null,
        refresh: Boolean = false
    ): Result<ResolvedOnlineStream> = withContext(Dispatchers.IO) {
        val candidateQualities = getFallbackQualities(preferredQuality)
        var lastError: Throwable? = null

        fun buildResolvedStream(url: String, qKey: String): ResolvedOnlineStream {
            val decodedUpstream = runCatching {
                if (url.contains("url=")) {
                    java.net.URLDecoder.decode(url.substringAfter("url=").substringBefore("&"), "UTF-8")
                } else url
            }.getOrDefault(url).lowercase()
            val cleanPath = decodedUpstream.substringBefore("?")
            val qEnum = AudioQuality.fromKey(qKey)
            val detectedFormat = when {
                cleanPath.endsWith(".flac") -> "flac"
                cleanPath.endsWith(".wav") -> "wav"
                cleanPath.endsWith(".m4a") || cleanPath.endsWith(".aac") -> "m4a"
                cleanPath.endsWith(".ogg") || cleanPath.endsWith(".opus") -> "ogg"
                cleanPath.endsWith(".mp3") -> "mp3"
                else -> qEnum.format.lowercase()
            }
            val detectedBitRate = when {
                detectedFormat == "flac" && qEnum == AudioQuality.Q_HIRES -> 1411
                detectedFormat == "flac" -> 960
                detectedFormat == "mp3" && (cleanPath.contains("128") && !cleanPath.contains("320") && qEnum != AudioQuality.Q_320K) -> 128
                else -> qEnum.bitrate
            }
            Log.i(TAG, "Resolved online stream [$songId] requested=$preferredQuality -> active=$qKey ($detectedFormat ${detectedBitRate}kbps)")
            return ResolvedOnlineStream(
                url = url,
                qualityKey = qKey,
                format = detectedFormat,
                bitRate = detectedBitRate
            )
        }

        // 第一轮：仅针对当前歌曲自身的 songId 与 metaJson 逐级尝试音质，禁止跨曲搜索替换，确保 Live/黑胶等特定版本原汁原味播放
        for ((index, qKey) in candidateQualities.withIndex()) {
            val res = resolveOnlineStreamUrl(
                songId = songId,
                source = source,
                quality = qKey,
                metaJson = metaJson,
                fallbackTitle = fallbackTitle,
                fallbackArtist = fallbackArtist,
                refresh = refresh || index > 0,
                allowSearchFallback = false
            )
            val url = res.getOrNull()
            if (!url.isNullOrBlank()) {
                return@withContext Result.success(buildResolvedStream(url, qKey))
            } else {
                lastError = res.exceptionOrNull()
            }
        }

        // 第二轮：若当前歌曲自身 ID 在所有音质下均无法取链，启用严格版本匹配的搜索回退
        val fallbackQuality = candidateQualities.firstOrNull { it == AudioQuality.Q_320K.key } ?: candidateQualities.last()
        val fallbackRes = resolveOnlineStreamUrl(
            songId = songId,
            source = source,
            quality = fallbackQuality,
            metaJson = metaJson,
            fallbackTitle = fallbackTitle,
            fallbackArtist = fallbackArtist,
            refresh = true,
            allowSearchFallback = true
        )
        val fallbackUrl = fallbackRes.getOrNull()
        if (!fallbackUrl.isNullOrBlank()) {
            return@withContext Result.success(buildResolvedStream(fallbackUrl, fallbackQuality))
        } else {
            lastError = fallbackRes.exceptionOrNull() ?: lastError
        }

        Result.failure(lastError ?: Exception("未能获取可用的在线播放流地址"))
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
     * 严格对齐 OpenAPI 规范：优先采用 multipart/form-data (字段名 file) 上传 JS 脚本，同时兼容 JSON 回退
     */
    suspend fun importSourceScript(scriptContent: String, fileName: String = "custom_source.js"): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val jsMediaType = "application/javascript; charset=utf-8".toMediaType()
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, scriptContent.toRequestBody(jsMediaType))
                .build()

            val multipartReq = newAuthRequest("$cleanBase/api/source/import")
                .post(multipartBody)
                .build()

            client.newCall(multipartReq).execute().use { resp ->
                if (resp.isSuccessful) return@withContext Result.success(true)
            }

            // 兼容旧版服务端 JSON 格式
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
     * 读取服务端现有自定义歌单列表（优先读取 /api/library/user-data，兼容 /api/library/playlists）
     */
    private suspend fun fetchExistingPlaylistsRaw(): ArrayList<JSONObject> {
        val existingPlaylists = ArrayList<JSONObject>()
        val userData = getLibraryUserData().getOrNull()
        val userPlaylists = userData?.optJSONArray("playlists")
        if (userPlaylists != null && userPlaylists.length() > 0) {
            for (i in 0 until userPlaylists.length()) {
                userPlaylists.optJSONObject(i)?.let { existingPlaylists.add(it) }
            }
            return existingPlaylists
        }
        runCatching {
            val rawReq = newAuthRequest("$cleanBase/api/library/playlists").get().build()
            client.newCall(rawReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val rawBody = resp.body?.string() ?: ""
                    val json = JSONObject(rawBody)
                    val arr = json.optJSONArray("data") ?: json.optJSONArray("playlists") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { existingPlaylists.add(it) }
                    }
                }
            }
        }
        return existingPlaylists
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
            val existingPlaylists = fetchExistingPlaylistsRaw()

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
            val existingPlaylists = fetchExistingPlaylistsRaw()

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
            val existingPlaylists = fetchExistingPlaylistsRaw().filter { it.optString("id") != playlistId }

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

    /**
     * 获取柠檬音乐服务端全局与用户设置 (/api/settings)
     * 对齐服务端 server/routes/settings.js (包含 download.maxDownloadNum, download.isDownloadLrc 等)
     */
    suspend fun getServerSettings(): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val req = newAuthRequest("$cleanBase/api/settings").get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("获取服务端设置失败 (HTTP ${resp.code})"))
                Result.success(JSONObject(body))
            }
        } catch (e: Exception) {
            Log.w(TAG, "getServerSettings failed", e)
            Result.failure(e)
        }
    }

    /**
     * 更新柠檬音乐服务端全局与用户设置 (PUT /api/settings)
     * 支持同步 download.maxDownloadNum (同时下载缓存数)、download.isDownloadLrc、source.fallbackMode 等
     */
    suspend fun updateServerSettings(entries: Map<String, String>): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            ensureAuthenticated()
            val payload = JSONObject()
            entries.forEach { (k, v) -> payload.put(k, v) }
            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val putReq = newAuthRequest("$cleanBase/api/settings").put(body).build()
            client.newCall(putReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("更新服务端设置失败 (HTTP ${resp.code})"))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "updateServerSettings failed", e)
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
