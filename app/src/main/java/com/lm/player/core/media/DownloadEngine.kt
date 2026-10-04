package com.lm.player.core.media

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.lm.player.core.database.dao.DownloadDao
import com.lm.player.core.database.dao.SongDao
import com.lm.player.core.database.entity.DownloadEntity
import com.lm.player.core.database.entity.SongEntity
import com.lm.player.core.model.DownloadSettings
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.DownloadTask
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.network.NetworkClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class DownloadEngine(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val songDao: SongDao,
    private val coroutineScope: CoroutineScope
) {
    private val TAG = "DownloadEngine"
    private val okHttpClient = NetworkClientFactory.createOkHttpClient(context)

    private val prefs = context.getSharedPreferences("zds_download_prefs", Context.MODE_PRIVATE)

    // 下载设置 (持久化存储与读取)
    var downloadSettings = MutableStateFlow(
        DownloadSettings(
            maxConcurrent = prefs.getInt("max_concurrent", 3),
            customDownloadPath = resolveFilesystemPath(prefs.getString("custom_download_path", "") ?: ""),
            wifiOnly = prefs.getBoolean("wifi_only", false),
            autoTagging = prefs.getBoolean("auto_tagging", true)
        )
    )

    @Volatile
    private var downloadSemaphore = kotlinx.coroutines.sync.Semaphore(
        prefs.getInt("max_concurrent", 3).coerceIn(1, 10)
    )

    companion object {
        /** 无损音频容器：凡属于此集合的音质档次与有损档次互不兼容 */
        private val LOSSLESS_AUDIO_EXTS = setOf("flac", "wav", "ape", "alac", "dsf", "dff")

        /**
         * 实际下载音质的展示文案。
         * 直链里带 320/128 标记时以它为准 —— 音源缺目标音质会逐级降级
         * (flac24bit→flac→320k→128k)，此时请求的 320k 与实际到手的 128k 不同，
         * 必须把降级结果如实告诉用户。
         */
        private fun actualQualityLabel(url: String?, format: String, bitRate: Int): String {
            val ext = format.lowercase()
            if (ext in LOSSLESS_AUDIO_EXTS) {
                return if (bitRate >= 1200) "Hi-Res 无损" else "无损 ${ext.uppercase()}"
            }
            val lowerUrl = url?.lowercase().orEmpty()
            val effectiveBitRate = when {
                lowerUrl.contains("320") -> 320
                lowerUrl.contains("128") -> 128
                else -> bitRate
            }
            return if (effectiveBitRate > 0) "${effectiveBitRate}K" else ext.uppercase()
        }

        /**
         * 将 SAF Tree URI (如 content://com.android.externalstorage.documents/tree/primary%3AMusic)
         * 解析为真实文件系统绝对路径 (如 /storage/emulated/0/Music)
         */
        fun resolveFilesystemPath(rawPathOrUri: String): String {
            val trimmed = rawPathOrUri.trim()
            if (trimmed.isBlank()) return ""
            if (!trimmed.startsWith("content://") && !trimmed.startsWith("file://")) {
                return trimmed
            }
            return try {
                val decoded = java.net.URLDecoder.decode(trimmed, "UTF-8")
                when {
                    decoded.contains("primary:") -> {
                        val rel = decoded.substringAfterLast("primary:").trimStart('/')
                        if (rel.isBlank()) "/storage/emulated/0" else "/storage/emulated/0/$rel"
                    }
                    decoded.contains("/tree/") -> {
                        val treePart = decoded.substringAfterLast("/tree/")
                        if (treePart.contains(":")) {
                            val vol = treePart.substringBefore(":")
                            val rel = treePart.substringAfter(":").trimStart('/')
                            if (vol.equals("primary", ignoreCase = true) || vol.equals("home", ignoreCase = true)) {
                                if (rel.isBlank()) "/storage/emulated/0" else "/storage/emulated/0/$rel"
                            } else {
                                "/storage/$vol/$rel".trimEnd('/')
                            }
                        } else {
                            trimmed
                        }
                    }
                    decoded.startsWith("file://") -> decoded.removePrefix("file://")
                    else -> trimmed
                }
            } catch (_: Exception) {
                trimmed
            }
        }
    }

    fun updateSettings(newSettings: DownloadSettings) {
        val normalizedPath = resolveCustomPathIfNeeded(newSettings.customDownloadPath)
        val normalizedSettings = newSettings.copy(customDownloadPath = normalizedPath)
        val oldMax = downloadSettings.value.maxConcurrent
        downloadSettings.value = normalizedSettings
        prefs.edit()
            .putInt("max_concurrent", normalizedSettings.maxConcurrent)
            .putString("custom_download_path", normalizedSettings.customDownloadPath)
            .putBoolean("wifi_only", normalizedSettings.wifiOnly)
            .putBoolean("auto_tagging", normalizedSettings.autoTagging)
            .apply()

        if (oldMax != normalizedSettings.maxConcurrent) {
            downloadSemaphore = kotlinx.coroutines.sync.Semaphore(normalizedSettings.maxConcurrent.coerceIn(1, 10))
            Log.i(TAG, "Download concurrency limit updated to ${normalizedSettings.maxConcurrent}")
        }
    }

    private fun resolveCustomPathIfNeeded(raw: String): String {
        return resolveFilesystemPath(raw)
    }

    // 活跃下载任务映射 Map<SongId, DownloadTask> - 线程安全反应式 StateFlow
    private val _activeTasksMap = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val activeTasksFlow: StateFlow<List<DownloadTask>> = _activeTasksMap
        .map { it.values.toList() }
        .stateIn(coroutineScope, SharingStarted.Eagerly, emptyList())

    private val jobMap = ConcurrentHashMap<String, Job>()
    private val urlResolverMap = ConcurrentHashMap<String, suspend () -> String?>()

    /**
     * 从文件魔数头 (Magic Bytes)、Content-Type 或 URL 参数精准识别音频真实封装扩展名
     */
    fun detectAudioExtension(file: File?, streamUrl: String = "", contentType: String = ""): String? {
        if (file != null && file.exists() && file.length() >= 12L) {
            try {
                val header = ByteArray(12)
                java.io.FileInputStream(file).use { it.read(header) }
                // 1. FLAC: "fLaC" (0x66 0x4C 0x61 0x43)
                if (header[0] == 0x66.toByte() && header[1] == 0x4C.toByte() &&
                    header[2] == 0x61.toByte() && header[3] == 0x43.toByte()
                ) {
                    return "flac"
                }
                // 2. WAV: "RIFF" ... "WAVE"
                if (header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                    header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
                    header[8] == 'W'.code.toByte() && header[9] == 'A'.code.toByte() &&
                    header[10] == 'V'.code.toByte() && header[11] == 'E'.code.toByte()
                ) {
                    return "wav"
                }
                // 3. OGG: "OggS"
                if (header[0] == 'O'.code.toByte() && header[1] == 'g'.code.toByte() &&
                    header[2] == 'g'.code.toByte() && header[3] == 'S'.code.toByte()
                ) {
                    return "ogg"
                }
                // 4. MP4/M4A: "ftyp" at offset 4
                if (header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
                ) {
                    return "m4a"
                }
                // 5. MP3: "ID3" or MPEG frame sync (0xFF 0xE0)
                if ((header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) ||
                    (header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0)
                ) {
                    return "mp3"
                }
            } catch (_: Exception) {}
        }

        val ct = contentType.lowercase()
        when {
            ct.contains("flac") -> return "flac"
            ct.contains("wav") || ct.contains("wave") -> return "wav"
            ct.contains("ogg") -> return "ogg"
            ct.contains("mp4") || ct.contains("m4a") -> return "m4a"
            ct.contains("aac") -> return "aac"
            ct.contains("mpeg") || ct.contains("mp3") -> return "mp3"
        }

        val decodedCandidate = try {
            if (streamUrl.contains("url=")) {
                java.net.URLDecoder.decode(streamUrl.substringAfter("url=").substringBefore("&"), "UTF-8")
            } else if (streamUrl.contains("path=")) {
                java.net.URLDecoder.decode(streamUrl.substringAfter("path=").substringBefore("&"), "UTF-8")
            } else streamUrl
        } catch (_: Exception) { streamUrl }

        val cleanUrlPath = decodedCandidate.substringBefore("?").lowercase()
        return when {
            cleanUrlPath.endsWith(".flac") -> "flac"
            cleanUrlPath.endsWith(".mp3") -> "mp3"
            cleanUrlPath.endsWith(".m4a") -> "m4a"
            cleanUrlPath.endsWith(".wav") -> "wav"
            cleanUrlPath.endsWith(".ogg") -> "ogg"
            cleanUrlPath.endsWith(".aac") -> "aac"
            else -> null
        }
    }

    fun getDownloadDir(): File {
        val customPath = downloadSettings.value.customDownloadPath
        if (customPath.isNotBlank()) {
            val customDir = File(customPath)
            if (customDir.exists() || customDir.mkdirs()) {
                return customDir
            }
        }
        val dir = File(context.getExternalFilesDir(null), "music")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun sanitizeSegment(name: String): String {
        val cleaned = name.trim()
            .replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim('.', ' ')
        val truncated = if (cleaned.length > 60) cleaned.substring(0, 60).trimEnd() else cleaned
        return truncated.ifBlank { "未知" }
    }

    /**
     * 根据在线文件夹层级结构或歌手/专辑规范结构获取下载存储的目标文件
     * 优先使用传入的 online folder hierarchy 或 relativeFolderPath，自动过滤 JSON 字符串
     */
    fun getTargetDownloadFile(song: UnifiedSong, folderHierarchy: String? = null): File {
        val baseDir = getDownloadDir()

        val candidate = (folderHierarchy ?: song.relativeFolderPath)?.trim()
        val isCandidateValidHierarchy = !candidate.isNullOrBlank() &&
                !candidate.startsWith("{") &&
                !candidate.startsWith("[") &&
                !candidate.contains("\"") &&
                !candidate.contains(":") &&
                !candidate.contains("_id__") &&
                !candidate.contains("_name__") &&
                candidate.length <= 100

        val subFolder = when {
            isCandidateValidHierarchy -> {
                candidate!!.split('/', '\\')
                    .map { sanitizeSegment(it) }
                    .filter { it.isNotBlank() }
                    .joinToString("/")
            }
            song.artist.isNotBlank() && song.album.isNotBlank() &&
                    !song.artist.contains("未知") && !song.album.contains("未知") -> {
                "${sanitizeSegment(song.artist)}/${sanitizeSegment(song.album)}"
            }
            song.artist.isNotBlank() && !song.artist.contains("未知") -> {
                sanitizeSegment(song.artist)
            }
            else -> "Music"
        }

        val parentDir = File(baseDir, subFolder)
        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }

        val safeTitle = sanitizeSegment(song.title.ifBlank { song.id })
        val ext = when {
            song.format.contains("flac", ignoreCase = true) -> "flac"
            song.format.contains("wav", ignoreCase = true) -> "wav"
            song.format.contains("ogg", ignoreCase = true) -> "ogg"
            song.format.contains("m4a", ignoreCase = true) -> "m4a"
            song.format.contains("aac", ignoreCase = true) -> "aac"
            else -> "mp3"
        }
        return File(parentDir, "$safeTitle.$ext")
    }

    private suspend fun saveOrUpdateSongEntity(song: UnifiedSong, localFilePath: String) {
        val localFile = File(localFilePath)
        val detectedExt = detectAudioExtension(localFile) ?: localFile.extension.lowercase().ifBlank { song.format.lowercase().ifBlank { "mp3" } }
        val isLosslessExt = detectedExt in listOf("flac", "wav", "ape", "alac")
        val computedBitRate = run {
            val durSec = (song.durationMs / 1000L)
            if (localFile.exists() && localFile.length() > 0 && durSec in 15..3600) {
                ((localFile.length() * 8L) / (durSec * 1000L)).toInt().coerceIn(64, 4608)
            } else if (isLosslessExt) {
                song.bitRate.coerceAtLeast(960)
            } else {
                if (song.bitRate > 0) song.bitRate else 320
            }
        }

        val existing = songDao.getSongById(song.id)
        if (existing != null) {
            songDao.updateDownloadStatusSpecsAndTimestamp(
                songId = song.id,
                status = DownloadStatus.DOWNLOADED,
                localPath = localFilePath,
                format = detectedExt,
                bitRate = computedBitRate,
                timestamp = System.currentTimeMillis()
            )
        } else {
            val safeRelativePath = song.relativeFolderPath?.takeIf {
                !it.startsWith("{") && !it.contains("\"") && !it.contains("_id__") && it.length <= 100
            }
            val newEntity = SongEntity(
                id = song.id,
                title = song.title.ifBlank { "未知曲目" },
                artist = song.artist.ifBlank { "未知歌手" },
                artistId = song.artistId.ifBlank { "artist_${song.artist.hashCode()}" },
                album = song.album.ifBlank { "单曲精选" },
                albumId = song.albumId.ifBlank { "album_${song.album.hashCode()}" },
                durationMs = song.durationMs,
                coverUrl = song.coverUrl,
                streamUrl = localFilePath,
                serverId = if (song.serverId == "lemon_online" || song.serverId.isBlank()) "local_storage" else song.serverId,
                localFilePath = localFilePath,
                downloadStatus = DownloadStatus.DOWNLOADED,
                bitRate = computedBitRate,
                format = detectedExt,
                isFavorite = song.isFavorite,
                relativeFolderPath = safeRelativePath,
                addedTimestamp = System.currentTimeMillis()
            )
            songDao.insertSongs(listOf(newEntity))
        }
    }

    /**
     * 启动歌曲下载 (集成在线目录层级建立、防重复核对、实时入队与断点续传检测)
     */
    fun startDownload(
        song: UnifiedSong,
        folderHierarchy: String? = null,
        urlResolver: (suspend () -> String?)? = null
    ) {
        if (jobMap.containsKey(song.id)) {
            coroutineScope.launch(Dispatchers.Main) {
                Toast.makeText(context, "「${song.title}」正在下载中，请稍候...", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (urlResolver != null) {
            urlResolverMap[song.id] = urlResolver
        }

        // 立即向活跃任务映射中加入，确保 UI 在点击瞬间立即可见 (避免直链解析网络延迟导致下载中空白)
        updateTask(DownloadTask(song = song, progress = 0f, status = DownloadStatus.DOWNLOADING))

        var currentJob: Job? = null
        val job = coroutineScope.launch(Dispatchers.IO) {
            val dbRelativePath = if (song.relativeFolderPath.isNullOrBlank()) {
                try {
                    songDao.getSongById(song.id)?.relativeFolderPath
                } catch (_: Exception) { null }
            } else null

            val cleanDbPath = dbRelativePath?.takeIf {
                !it.startsWith("{") && !it.contains("\"") && !it.contains("_id__") && it.length <= 100
            }
            val safeSongRelPath = song.relativeFolderPath?.takeIf {
                !it.startsWith("{") && !it.contains("\"") && !it.contains("_id__") && it.length <= 100
            }

            val effectiveFolderHierarchy = folderHierarchy ?: safeSongRelPath ?: cleanDbPath
            val songToDownload = if (song.relativeFolderPath != safeSongRelPath) {
                song.copy(relativeFolderPath = safeSongRelPath)
            } else song

            val destFile = getTargetDownloadFile(songToDownload, effectiveFolderHierarchy)
            val tempFile = File("${destFile.absolutePath}.download")

            // 1. 防重复下载检测与核对：仅当目标同名**同音质档次**的物理文件已存在且非空时才跳过下载
            val record = downloadDao.getDownloadRecord(song.id)
            val requestedLossless = song.format.equals("flac", ignoreCase = true) || song.bitRate >= 800
            fun isExistingFileCompatible(path: String?): Boolean {
                if (path.isNullOrBlank()) return false
                val f = File(path)
                if (!f.exists() || f.length() <= 0L || f.name.endsWith(".download")) return false
                val realExt = (detectAudioExtension(f) ?: f.extension.lowercase()).lowercase()
                return if (requestedLossless) {
                    realExt in LOSSLESS_AUDIO_EXTS
                } else {
                    // 以前这里对任何有损请求都无条件放行 → 本地已有 FLAC 时选 320K 会被判为
                    // "已下载完成"直接跳过，用户永远拿不到 320K。现在要求已有文件也必须是有损的。
                    realExt !in LOSSLESS_AUDIO_EXTS
                }
            }

            val isDestFileValid = destFile.exists() && destFile.length() > 0 && !tempFile.exists() && isExistingFileCompatible(destFile.absolutePath)
            val isRecordDownloaded = record?.status == DownloadStatus.DOWNLOADED && isExistingFileCompatible(record.localFilePath)
            val isLocalPathValid = isExistingFileCompatible(song.localFilePath)

            if (isDestFileValid || isRecordDownloaded || isLocalPathValid) {
                val validPath = when {
                    isDestFileValid -> destFile.absolutePath
                    isRecordDownloaded -> record!!.localFilePath!!
                    else -> song.localFilePath!!
                }
                saveOrUpdateSongEntity(song, validPath)
                downloadDao.insertOrUpdate(
                    DownloadEntity(
                        songId = song.id,
                        title = song.title,
                        artist = song.artist,
                        coverUrl = song.coverUrl,
                        localFilePath = validPath,
                        remoteUrl = song.streamUrl,
                        status = DownloadStatus.DOWNLOADED,
                        bytesDownloaded = File(validPath).length(),
                        totalBytes = File(validPath).length(),
                        completedTimestamp = System.currentTimeMillis()
                    )
                )
                removeTask(song.id)
                urlResolverMap.remove(song.id)
                withContext(Dispatchers.Main) {
                    // 说清楚是"哪个音质档次"已有，用户才不会以为选 320K 被无视了。
                    // 取已有文件自身的扩展名，而不是本次请求的格式
                    val existingExt = File(validPath).extension.lowercase().ifBlank { song.format.lowercase() }
                    val existingQuality = actualQualityLabel(validPath, existingExt, song.bitRate)
                    Toast.makeText(context, "「${song.title}」已存在 $existingQuality 版本，无需重复下载", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // 2. 初始化下载任务与数据库状态 (队列排队状态)
            val initialEntity = DownloadEntity(
                songId = song.id,
                title = song.title,
                artist = song.artist,
                coverUrl = song.coverUrl,
                localFilePath = destFile.absolutePath,
                remoteUrl = song.streamUrl,
                status = DownloadStatus.DOWNLOADING,
                bytesDownloaded = 0L,
                totalBytes = 0L
            )
            downloadDao.insertOrUpdate(initialEntity)
            
            updateTask(DownloadTask(song = song, progress = 0f, status = DownloadStatus.DOWNLOADING))

            // 3. 进入并发信号量等待队列 (超出并发上限自动排队)
            downloadSemaphore.withPermit {
                var actualDestFile = destFile
                try {
                    // 动态解析真实直链：**只要调用方传了解析器就以解析结果为准**。
                    // 旧判断是"仅当 streamUrl 为空或为 lemon_online:// 占位时才解析"，而服务器曲库
                    // 歌曲的 streamUrl 是 /api/play/local?path=…&token=…，两个条件都不满足 →
                    // 解析器永远不会被调用 → 拖回的永远是服务器上的**原文件**（常见为无损），
                    // 与用户所选的下载音质完全无关。这是"选低音质仍下到无损"的第二个根因。
                    var effectiveStreamUrl = song.streamUrl
                    val activeResolver = urlResolver ?: urlResolverMap[song.id]
                    if (activeResolver != null) {
                        val resolved = runCatching { activeResolver() }.getOrNull()
                        if (!resolved.isNullOrBlank()) {
                            effectiveStreamUrl = resolved
                        }
                    }
                    if (effectiveStreamUrl.isBlank() && !record?.remoteUrl.isNullOrBlank() && record?.remoteUrl?.startsWith("http") == true) {
                        effectiveStreamUrl = record.remoteUrl
                    }

                    if (effectiveStreamUrl.isBlank() || (!effectiveStreamUrl.startsWith("http://") && !effectiveStreamUrl.startsWith("https://"))) {
                        throw Exception("未能解析或获取有效的音频直链")
                    }

                    // 初步根据 URL 校准后缀扩展名
                    val preDetectedExt = detectAudioExtension(null, effectiveStreamUrl, "")
                    if (preDetectedExt != null && !destFile.name.endsWith(".$preDetectedExt", ignoreCase = true)) {
                        actualDestFile = File(destFile.parentFile, "${destFile.nameWithoutExtension}.$preDetectedExt")
                    }

                    val tempDestFile = File("${actualDestFile.absolutePath}.download")
                    var existingBytes = if (tempDestFile.exists()) tempDestFile.length() else 0L

                    fun buildDownloadRequest(rangeStart: Long): Request {
                        val rb = Request.Builder()
                            .url(effectiveStreamUrl)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .header("Accept", "*/*")
                        if (rangeStart > 0L) {
                            rb.header("Range", "bytes=$rangeStart-")
                        }
                        return rb.build()
                    }

                    var response = okHttpClient.newCall(buildDownloadRequest(existingBytes)).execute()
                    if (response.code == 416 && existingBytes > 0L) {
                        response.close()
                        tempDestFile.delete()
                        existingBytes = 0L
                        response = okHttpClient.newCall(buildDownloadRequest(0L)).execute()
                    }

                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            throw Exception("HTTP 下载失败: ${resp.code}")
                        }

                        val isRangeOk = resp.code == 206
                        val append = isRangeOk && existingBytes > 0L
                        val body = resp.body ?: throw Exception("响应体为空")
                        val contentTypeHeader = resp.header("Content-Type").orEmpty()
                        val totalLength = if (isRangeOk) {
                            existingBytes + body.contentLength()
                        } else {
                            if (!append && tempDestFile.exists()) tempDestFile.delete()
                            body.contentLength()
                        }

                        var lastTime = System.currentTimeMillis()
                        var lastBytes = if (append) existingBytes else 0L
                        var totalRead = if (append) existingBytes else 0L

                        body.byteStream().use { input ->
                            FileOutputStream(tempDestFile, append).use { output ->
                                val buffer = ByteArray(8 * 1024)
                                var bytesRead: Int

                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    totalRead += bytesRead

                                    val now = System.currentTimeMillis()
                                    if (now - lastTime >= 300 || totalRead == totalLength) {
                                        val speed = if (now > lastTime) ((totalRead - lastBytes) * 1000L / (now - lastTime)) / 1024L else 0L
                                        lastTime = now
                                        lastBytes = totalRead
                                        val progress = if (totalLength > 0) (totalRead.toFloat() / totalLength).coerceIn(0f, 1f) else 0f
                                        updateTask(
                                            DownloadTask(
                                                song = song.copy(streamUrl = effectiveStreamUrl),
                                                progress = progress,
                                                bytesDownloaded = totalRead,
                                                totalBytes = totalLength,
                                                speedKbps = speed,
                                                status = DownloadStatus.DOWNLOADING
                                            )
                                        )
                                    }
                                }
                                output.flush()
                            }
                        }

                        // 下载完成后，通过文件魔数头 (fLaC / ID3 / RIFF / ftyp) 与 Content-Type 二次精准校准真实文件扩展名
                        val realAudioExt = detectAudioExtension(tempDestFile, effectiveStreamUrl, contentTypeHeader)
                        if (realAudioExt != null && !actualDestFile.name.endsWith(".$realAudioExt", ignoreCase = true)) {
                            actualDestFile = File(actualDestFile.parentFile, "${actualDestFile.nameWithoutExtension}.$realAudioExt")
                        }
                    }

                    // 原子化重命名临时文件为正式音频文件
                    if (actualDestFile.exists()) {
                        actualDestFile.delete()
                    }
                    if (!tempDestFile.renameTo(actualDestFile)) {
                        tempDestFile.copyTo(actualDestFile, overwrite = true)
                        tempDestFile.delete()
                    }

                    val finalExt = actualDestFile.extension.lowercase().ifBlank { song.format.lowercase() }
                    val finalSong = song.copy(
                        streamUrl = effectiveStreamUrl,
                        format = finalExt,
                        bitRate = if (finalExt in listOf("flac", "wav")) song.bitRate.coerceAtLeast(960) else song.bitRate
                    )

                    // 4. 后置元数据处理：拉取高清封面、原始同步歌词、生成伴随 .lrc 以及音频内嵌 ID3/FLAC/M4A 标签
                    val localCoverPath = postProcessDownloadedFile(actualDestFile, finalSong)
                    val effectiveCover = if (!localCoverPath.isNullOrBlank() && finalSong.coverUrl.isBlank()) localCoverPath else finalSong.coverUrl

                    // 5. 下载成功完成 - 持久化到 downloads 表与 songs 表
                    val completedEntity = initialEntity.copy(
                        status = DownloadStatus.DOWNLOADED,
                        coverUrl = effectiveCover,
                        localFilePath = actualDestFile.absolutePath,
                        remoteUrl = effectiveStreamUrl,
                        bytesDownloaded = actualDestFile.length(),
                        totalBytes = actualDestFile.length(),
                        completedTimestamp = System.currentTimeMillis()
                    )
                    downloadDao.insertOrUpdate(completedEntity)
                    saveOrUpdateSongEntity(finalSong.copy(coverUrl = effectiveCover), actualDestFile.absolutePath)
                    removeTask(song.id)
                    urlResolverMap.remove(song.id)
                    Log.i(TAG, "Song ${song.title} downloaded and tagged successfully to ${actualDestFile.absolutePath}")

                    withContext(Dispatchers.Main) {
                        // 如实告知**实际**拿到的音质：音源缺目标音质时会逐级降级
                        // (flac24bit→flac→320k→128k)，用户选了 320K 实际下到 128K 时必须能看见
                        val actualQuality = actualQualityLabel(effectiveStreamUrl, finalSong.format, finalSong.bitRate)
                        Toast.makeText(
                            context,
                            "「${song.title}」已完成离线下载 [实际音质: $actualQuality]",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        Log.i(TAG, "Download job for ${song.title} cancelled/paused by user")
                    } else {
                        Log.e(TAG, "Failed to download song ${song.title}", e)
                        val tempDestFile = File("${actualDestFile.absolutePath}.download")
                        if (tempDestFile.exists() && tempDestFile.length() == 0L) {
                            tempDestFile.delete()
                        }
                        if (actualDestFile.exists() && actualDestFile.length() == 0L) {
                            actualDestFile.delete()
                        }
                        val failedEntity = initialEntity.copy(
                            status = DownloadStatus.FAILED,
                            errorMessage = e.message
                        )
                        downloadDao.insertOrUpdate(failedEntity)
                        songDao.updateDownloadStatus(song.id, DownloadStatus.FAILED, null)
                        removeTask(song.id)
                        urlResolverMap.remove(song.id)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "「${song.title}」下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } finally {
                    if (currentJob != null) {
                        jobMap.remove(song.id, currentJob)
                    } else {
                        jobMap.remove(song.id)
                    }
                }
            }
        }
        currentJob = job
        jobMap[song.id] = job
    }

    /**
     * 暂停单曲下载任务 (保留断点文件，更新任务状态)
     */
    fun pauseTask(songId: String) {
        val task = _activeTasksMap.value[songId] ?: return
        jobMap.remove(songId)?.cancel()
        updateTask(task.copy(status = DownloadStatus.PAUSED, speedKbps = 0L))
        coroutineScope.launch(Dispatchers.IO) {
            downloadDao.getDownloadRecord(songId)?.let {
                downloadDao.insertOrUpdate(it.copy(status = DownloadStatus.PAUSED))
            }
        }
    }

    /**
     * 恢复/继续已暂停的下载任务 (基于 HTTP Range 断点续传，自动复用缓存的直链解析器)
     */
    fun resumeTask(songId: String) {
        val task = _activeTasksMap.value[songId] ?: return
        if (task.status == DownloadStatus.PAUSED || !jobMap.containsKey(songId)) {
            startDownload(task.song, urlResolver = urlResolverMap[songId])
        }
    }

    /**
     * 取消单曲下载任务 (彻底取消协程、清理残余临时文件并删除任务)
     */
    fun cancelTask(songId: String) {
        val task = _activeTasksMap.value[songId]
        jobMap.remove(songId)?.cancel()
        urlResolverMap.remove(songId)
        removeTask(songId)
        coroutineScope.launch(Dispatchers.IO) {
            val record = downloadDao.getDownloadRecord(songId)
            if (record?.localFilePath != null) {
                val f = File(record.localFilePath)
                if (f.exists()) f.delete()
                val lrcFile = File(f.parentFile, "${f.nameWithoutExtension}.lrc")
                if (lrcFile.exists()) lrcFile.delete()
                val tempFile = File("${f.absolutePath}.download")
                if (tempFile.exists()) tempFile.delete()
            }
            val existingSong = songDao.getSongById(songId)
            if (existingSong?.serverId == "local_storage") {
                songDao.deleteSongById(songId)
            } else {
                songDao.updateDownloadStatus(songId, DownloadStatus.NOT_DOWNLOADED, null)
            }
            downloadDao.deleteDownload(songId)
            task?.let {
                val f = getTargetDownloadFile(it.song)
                if (f.exists()) f.delete()
                val tempFile = File("${f.absolutePath}.download")
                if (tempFile.exists()) tempFile.delete()
            }
            val dir = getDownloadDir()
            dir.listFiles { _, name -> name.startsWith(songId) }?.forEach { it.delete() }
        }
    }

    /**
     * 批量暂停下载任务
     */
    fun pauseTasks(ids: Set<String>) {
        ids.forEach { pauseTask(it) }
    }

    /**
     * 批量继续下载任务
     */
    fun resumeTasks(ids: Set<String>) {
        ids.forEach { resumeTask(it) }
    }

    /**
     * 批量取消下载任务
     */
    fun cancelTasks(ids: Set<String>) {
        ids.forEach { cancelTask(it) }
    }

    /**
     * 全部暂停
     */
    fun pauseAll() {
        _activeTasksMap.value.values
            .filter { it.status == DownloadStatus.DOWNLOADING }
            .forEach { pauseTask(it.song.id) }
    }

    /**
     * 全部继续
     */
    fun resumeAll() {
        _activeTasksMap.value.values
            .filter { it.status == DownloadStatus.PAUSED }
            .forEach { resumeTask(it.song.id) }
    }

    /**
     * 全部取消
     */
    fun cancelAll() {
        _activeTasksMap.value.keys.toList().forEach { cancelTask(it) }
    }

    /**
     * 音频下载后置处理管道：
     * 1. 根据设置拉取高清封面图片二进制字节；
     * 2. 根据设置拉取高精度同步 LRC 歌词文本；
     * 3. 若开启 download_lrc_file：在音频同级目录输出同名 .lrc 文件；
     * 4. 在专辑同级目录保存 cover.jpg 方便车载系统与文件管理器显示；
     * 5. 若开启 download_embed_cover 或 download_embed_lyric：调用 AudioMetadataEmbedder 写入 ID3v2.3/FLAC/M4A 标签；
     * 6. 返回可能落盘的本地封面路径。
     */
    suspend fun postProcessDownloadedFile(
        file: File,
        song: UnifiedSong
    ): String? = withContext(Dispatchers.IO) {
        val lemonPrefs = context.getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE)
        val embedCover = lemonPrefs.getBoolean("download_embed_cover", true)
        val embedLyric = lemonPrefs.getBoolean("download_embed_lyric", true)
        val downloadLrc = lemonPrefs.getBoolean("download_lrc_file_v2", false)

        var localCoverPath: String? = null
        var coverBytes: ByteArray? = null

        // 1. 获取封面字节
        if (embedCover || downloadLrc) {
            try {
                if (song.coverUrl.isNotBlank()) {
                    if (song.coverUrl.startsWith("http://") || song.coverUrl.startsWith("https://")) {
                        val req = Request.Builder().url(song.coverUrl).build()
                        okHttpClient.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                coverBytes = resp.body?.bytes()
                            }
                        }
                    } else {
                        val localCover = File(song.coverUrl)
                        if (localCover.exists() && localCover.isFile) {
                            coverBytes = localCover.readBytes()
                            localCoverPath = localCover.absolutePath
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch cover bytes for ${song.title}: ${e.message}")
            }
        }

        // 保存 cover.jpg 到歌曲所在专辑目录（若不存在）
        if (coverBytes != null && coverBytes!!.isNotEmpty()) {
            try {
                val albumDir = file.parentFile
                if (albumDir != null && albumDir.exists()) {
                    val coverJpg = File(albumDir, "cover.jpg")
                    if (!coverJpg.exists()) {
                        coverJpg.writeBytes(coverBytes!!)
                    }
                    localCoverPath = coverJpg.absolutePath
                }
            } catch (_: Exception) {}
        }

        // 2. 获取原始歌词文本
        var rawLrc: String? = null
        if (embedLyric || downloadLrc) {
            try {
                rawLrc = LyricsManager.fetchRawLyrics(song, context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch raw lyrics for ${song.title}: ${e.message}")
            }
        }

        // 3. 伴随 .lrc 独立歌词文件生成
        if (downloadLrc && !rawLrc.isNullOrBlank()) {
            try {
                val lrcFile = File(file.parentFile, "${file.nameWithoutExtension}.lrc")
                lrcFile.writeText(rawLrc!!, Charsets.UTF_8)
                Log.i(TAG, "Saved companion .lrc: ${lrcFile.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write companion .lrc: ${e.message}")
            }
        }

        // 4. 音频文件内部标签内嵌 (ID3v2.3 / FLAC / M4A)
        if (embedCover || embedLyric) {
            try {
                val success = AudioMetadataEmbedder.embedMetadata(
                    file = file,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    coverBytes = if (embedCover) coverBytes else null,
                    lyrics = if (embedLyric) rawLrc else null
                )
                if (success) {
                    Log.i(TAG, "Successfully embedded metadata into ${file.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to embed metadata into ${file.name}", e)
            }
        }

        localCoverPath
    }

    /**
     * 为单首已下载歌曲重新嵌入元数据、封面与歌词标签
     */
    suspend fun reEmbedSongMetadata(song: UnifiedSong): Boolean = withContext(Dispatchers.IO) {
        val targetPath = song.localFilePath ?: downloadDao.getDownloadRecord(song.id)?.localFilePath
        if (targetPath.isNullOrBlank()) return@withContext false
        val file = File(targetPath)
        if (!file.exists() || file.length() < 32) return@withContext false

        val localCover = postProcessDownloadedFile(file, song)
        if (localCover != null && song.coverUrl.isBlank()) {
            songDao.updateDownloadStatusAndTimestamp(song.id, DownloadStatus.DOWNLOADED, file.absolutePath, System.currentTimeMillis())
        }
        true
    }

    /**
     * 批量为所有已下载的歌曲重新补全内嵌封面与歌词标签
     */
    suspend fun reEmbedAllDownloadedSongs(
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val records = downloadDao.getAllDownloadsList().filter { it.status == DownloadStatus.DOWNLOADED }
        var successCount = 0
        var failCount = 0
        val total = records.size

        for ((index, r) in records.withIndex()) {
            onProgress(index + 1, total)
            if (r.localFilePath.isNullOrBlank()) {
                failCount++
                continue
            }
            val f = File(r.localFilePath)
            if (!f.exists() || f.length() < 32) {
                failCount++
                continue
            }
            val songEntity = songDao.getSongById(r.songId)
            val unifiedSong = UnifiedSong(
                id = r.songId,
                title = r.title,
                artist = r.artist,
                album = songEntity?.album ?: "精选专辑",
                durationMs = songEntity?.durationMs ?: 0L,
                coverUrl = r.coverUrl ?: songEntity?.coverUrl ?: "",
                streamUrl = r.remoteUrl ?: "",
                localFilePath = r.localFilePath,
                format = f.extension
            )
            val ok = reEmbedSongMetadata(unifiedSong)
            if (ok) successCount++ else failCount++
        }
        Pair(successCount, failCount)
    }

    /**
     * 取消/停止正在进行的下载，并彻底清理未完成的临时文件
     */
    fun cancelDownload(songId: String) {
        cancelTask(songId)
        coroutineScope.launch(Dispatchers.Main) {
            Toast.makeText(context, "已停止并移除下载任务", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 删除已完成下载的本地歌曲文件与数据库记录
     */
    fun deleteDownloadedSong(song: UnifiedSong) {
        cancelTask(song.id)
        coroutineScope.launch(Dispatchers.IO) {
            val record = downloadDao.getDownloadRecord(song.id)
            if (record?.localFilePath != null) {
                val f = File(record.localFilePath)
                if (f.exists()) f.delete()
                val lrcFile = File(f.parentFile, "${f.nameWithoutExtension}.lrc")
                if (lrcFile.exists()) lrcFile.delete()
            }
            if (!song.localFilePath.isNullOrBlank()) {
                val f = File(song.localFilePath)
                if (f.exists()) f.delete()
                val lrcFile = File(f.parentFile, "${f.nameWithoutExtension}.lrc")
                if (lrcFile.exists()) lrcFile.delete()
            }
            val defaultDest = File(getDownloadDir(), "${song.id}.${song.format}")
            if (defaultDest.exists()) defaultDest.delete()

            downloadDao.deleteDownload(song.id)
            if (song.serverId == "local_storage" || song.id.startsWith("lemon_online_")) {
                songDao.deleteSongById(song.id)
            } else {
                songDao.updateDownloadStatus(song.id, DownloadStatus.NOT_DOWNLOADED, null)
            }
            PlaybackQueueManager.onSongsDownloadDeleted(setOf(song.id))

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "已删除本地离线歌曲: ${song.title}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 批量删除本地已下载歌曲（物理文件 + 数据库关联记录）
     */
    fun deleteDownloadedSongs(songs: List<UnifiedSong>) {
        if (songs.isEmpty()) return
        songs.forEach { cancelTask(it.id) }
        coroutineScope.launch(Dispatchers.IO) {
            var freedBytes = 0L
            val downloadDir = getDownloadDir()
            for (song in songs) {
                val record = downloadDao.getDownloadRecord(song.id)
                if (record?.localFilePath != null) {
                    val f = File(record.localFilePath)
                    if (f.exists()) {
                        freedBytes += f.length()
                        f.delete()
                    }
                    val lrcFile = File(f.parentFile, "${f.nameWithoutExtension}.lrc")
                    if (lrcFile.exists()) lrcFile.delete()
                }
                if (!song.localFilePath.isNullOrBlank()) {
                    val f = File(song.localFilePath)
                    if (f.exists()) {
                        freedBytes += f.length()
                        f.delete()
                    }
                    val lrcFile = File(f.parentFile, "${f.nameWithoutExtension}.lrc")
                    if (lrcFile.exists()) lrcFile.delete()
                }
                val defaultDest = File(downloadDir, "${song.id}.${song.format}")
                if (defaultDest.exists()) {
                    freedBytes += defaultDest.length()
                    defaultDest.delete()
                }

                downloadDao.deleteDownload(song.id)
                if (song.serverId == "local_storage" || song.id.startsWith("lemon_online_")) {
                    songDao.deleteSongById(song.id)
                } else {
                    songDao.updateDownloadStatus(song.id, DownloadStatus.NOT_DOWNLOADED, null)
                }
            }
            PlaybackQueueManager.onSongsDownloadDeleted(songs.map { it.id }.toSet())

            val freedFormatted = if (freedBytes >= 1024 * 1024 * 1024) {
                String.format(java.util.Locale.getDefault(), "%.2f GB", freedBytes / (1024.0 * 1024.0 * 1024.0))
            } else {
                String.format(java.util.Locale.getDefault(), "%.1f MB", freedBytes / (1024.0 * 1024.0))
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "已成功删除 ${songs.size} 首本地歌曲，释放了 $freedFormatted 空间", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateTask(task: DownloadTask) {
        val map = _activeTasksMap.value.toMutableMap()
        map[task.song.id] = task
        _activeTasksMap.value = map
    }

    private fun removeTask(songId: String) {
        val map = _activeTasksMap.value.toMutableMap()
        map.remove(songId)
        _activeTasksMap.value = map
    }
}
