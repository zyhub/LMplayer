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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

    // 下载专用客户端：给 DNS 解析加 5 秒硬超时。
    // 某些网络/模拟器解析第三方 CDN 域名会无限挂起，OkHttp 的 connectTimeout 管不到
    // DNS 阶段，表现为下载永远停在 0%。超时后由下载逻辑回退到服务端代理。
    private val dnsExecutor = java.util.concurrent.Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "lm-download-dns").apply { isDaemon = true }
    }
    private val downloadClient = okHttpClient.newBuilder()
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                val future = dnsExecutor.submit<List<java.net.InetAddress>> {
                    okhttp3.Dns.SYSTEM.lookup(hostname)
                }
                return try {
                    future.get(5, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: Exception) {
                    future.cancel(true)
                    throw java.net.UnknownHostException("DNS timeout: $hostname")
                }
            }
        })
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        // 慢速网络/限速源上单个 1MB 分片可能持续 1~2 分钟，60s 的默认 readTimeout
        // 会在仍有数据时误判失败；放宽到 180s，callTimeout 仍由共享客户端限制在 300s。
        .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
        // 强制 HTTP/1.1：服务端（Express/反代）在大文件流上对 HTTP/2 流控处理不稳定，
        // 实测 OkHttp 的 h2 连接读到约 500KB 后停止收到 WINDOW_UPDATE，下载永久卡住；
        // 同一地址用 HTTP/1.1 可完整读完。下载不需要多路复用，降级没有副作用。
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .build()

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

    private class DynamicSemaphore(initialPermits: Int) {
        @Volatile
        private var permits = initialPermits
        @Volatile
        private var running = 0
        private val lock = Any()

        fun setPermits(newPermits: Int) {
            synchronized(lock) {
                permits = newPermits
                (lock as java.lang.Object).notifyAll()
            }
        }

        suspend fun <T> withPermit(action: suspend () -> T): T {
            acquire()
            try {
                return action()
            } finally {
                release()
            }
        }

        private suspend fun acquire() {
            while (true) {
                synchronized(lock) {
                    if (running < permits) {
                        running++
                        return
                    }
                }
                delay(80)
            }
        }

        private fun release() {
            synchronized(lock) {
                running = (running - 1).coerceAtLeast(0)
                (lock as java.lang.Object).notifyAll()
            }
        }
    }

    private val downloadSemaphore = DynamicSemaphore(
        prefs.getInt("max_concurrent", 3).coerceIn(1, 10)
    )

    companion object {
        /** 无损音频容器：凡属于此集合的音质档次与有损档次互不兼容 */
        private val LOSSLESS_AUDIO_EXTS = setOf("flac", "wav", "ape", "alac", "dsf", "dff")

        /** 非「服务器曲库」的 serverId：本机扫描、在线占位等；其余非空值都视为服务器曲库 */
        private val NON_SERVER_LIBRARY_IDS = setOf("local_storage", "local_folder", "local_saf", "lemon_online")

        /**
         * 实际下载音质的展示文案。
         * 直链里带 320/128 标记时以它为准 —— 音源缺目标音质会逐级降级
         * (flac24bit→flac→320k→128k)，此时请求的 320k 与实际到手的 128k 不同，
         * 必须把降级结果如实告诉用户。
         */
        private fun readAudioBitRate(file: File): Int = try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val bitRate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull()?.let { (it / 1000L).toInt() } ?: 0
            retriever.release()
            bitRate
        } catch (_: Throwable) {
            0
        }

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
            downloadSemaphore.setPermits(normalizedSettings.maxConcurrent.coerceIn(1, 10))
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
    private val fallbackResolverMap = ConcurrentHashMap<String, suspend (Int) -> String?>()

    /** 该 serverId 是否代表「柠檬服务端曲库」中的一首歌（而不是本机扫描/在线占位） */
    private fun isServerLibraryId(serverId: String?): Boolean =
        !serverId.isNullOrBlank() && serverId !in NON_SERVER_LIBRARY_IDS

    /**
     * 服务端在线音源返回的是 /api/play/proxy?url=<真实CDN>&ticket=…。
     * 实测该代理在播放/下载 1MB 后会出现严重限速（2MB 花了 156 秒），而内层 CDN
     * 直链 9MB 只需约 1 秒。下载时优先解出内层直链；失败再由调用方回退到代理。
     */
    private fun unwrapServerProxyUrl(url: String): String {
        if (!url.contains("/api/play/proxy") || !url.contains("url=")) return url
        return try {
            val encoded = url.substringAfter("url=").substringBefore("&")
            if (encoded.isBlank()) return url
            val decoded = java.net.URLDecoder.decode(encoded, "UTF-8").trim()
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) decoded else url
        } catch (_: Exception) {
            url
        }
    }

    /** 解析 Content-Range: "bytes 0-1048575/28007147" -> (start, end, total)；total 为 * 时返回 -1 */
    private fun parseContentRange(header: String?): Triple<Long, Long, Long>? {
        if (header.isNullOrBlank()) return null
        val m = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""").find(header) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val end = m.groupValues[2].toLongOrNull() ?: return null
        val total = m.groupValues[3].toLongOrNull() ?: -1L
        return Triple(start, end, total)
    }

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
        // 目录穿越防护：整段恰为 ".."（或 "."）时上面的 trim 会把它清空，但形如
        // "Album/.." 这类组合里内层 ".." 会被保留，而 File(baseDir, "Album/../../x")
        // 会解析到应用私有目录之外的任意路径。relativeFolderPath 来自柠檬服务端返回的
        // 文件夹层级或数据库历史值 —— 即服务端可控，必须在此彻底阻断。
        // 只有「整段恰为 . 或 ..」才是穿越意图，必须丢弃；
        // 正常名称里出现连续两个点（如 "Live 2024..2025"）不应被误伤 ——
        // 绝对路径安全由 safeChildFile 的 canonicalFile 前缀校验兜底。
        if (cleaned == "." || cleaned == "..") return ""
        val truncated = if (cleaned.length > 60) cleaned.substring(0, 60).trimEnd() else cleaned
        return truncated.ifBlank { "未知" }
    }

    /**
     * 校验最终落盘路径必须位于允许的下载根目录之内（防目录穿越的兜底闸门）。
     * 返回 null 表示路径非法，调用方应放弃使用该层级并回退到默认目录。
     */
    private fun safeChildFile(baseDir: File, relative: String): File? {
        return try {
            val base = baseDir.canonicalFile
            val target = File(base, relative).canonicalFile
            val basePath = base.path + File.separator
            if (target.path == base.path || target.path.startsWith(basePath)) target else null
        } catch (_: Exception) {
            null
        }
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

        // 兜底闸门：即使 subFolder 通过了逐段净化，也再校验一次最终规范化路径必须落在 baseDir 之内
        val parentDir = safeChildFile(baseDir, subFolder) ?: baseDir
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
        val isArtistUnknown = song.artist.isBlank() || song.artist.contains("未知")
        val fileName = if (isArtistUnknown && subFolder == "Music") {
            "${safeTitle}_${song.id.takeLast(6)}.$ext"
        } else {
            "$safeTitle.$ext"
        }
        return File(parentDir, fileName)
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

        val existingByPath = songDao.getSongByLocalPath(localFilePath)
        val existingById = songDao.getSongById(song.id)
        val isExistingPathServerLinked = existingByPath != null && isServerLibraryId(existingByPath.serverId)

        // 若数据库中已有另一条不同 ID 的记录指向同一物理文件：
        // 优先保留**已关联服务器**的曲目记录，清理冗余的本地/在线临时 ID 记录，防止
        // 已下载列表重复出现两条相同歌曲；也避免把服务器曲库记录删掉后只留下在线 ID
        // （后者会在下一次服务端同步/差量清理时被当作孤儿删掉，下载记录随之悬空）。
        if (existingByPath != null && existingByPath.id != song.id) {
            if (isExistingPathServerLinked) {
                songDao.updateDownloadStatusSpecsAndTimestamp(
                    songId = existingByPath.id,
                    status = DownloadStatus.DOWNLOADED,
                    localPath = localFilePath,
                    format = detectedExt,
                    bitRate = computedBitRate,
                    timestamp = System.currentTimeMillis()
                )
                // 同 ID 的旧记录若只是同一首歌的在线占位，删掉它避免双份；
                // 绝不删除 existingByPath（服务器曲库记录就是 canonical owner）。
                if (existingById != null && existingById.id != existingByPath.id) {
                    songDao.deleteSongById(song.id)
                }
                downloadDao.deleteDownload(song.id)
                return
            } else {
                songDao.deleteSongById(existingByPath.id)
                downloadDao.deleteDownload(existingByPath.id)
            }
        }

        if (existingById != null) {
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
                addedTimestamp = System.currentTimeMillis(),
                rawMetaJson = song.rawMetaJson
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
        urlResolver: (suspend () -> String?)? = null,
        fallbackResolver: (suspend (Int) -> String?)? = null
    ): Boolean {
        // 关键：Job 必须用 LAZY 创建、先放入 jobMap 再 start；整段协程体（含所有提前 return）
        // 都必须在 try/finally 内。旧实现先 put 一个 placeholder、启动协程后再把真实 Job
        // 覆盖进 map，存在两个必现问题：
        //  1) 「目标文件已存在」等提前 return 的分支没有 finally 清理，map 里永久残留一个
        //     已完成的 Job，此后同一首歌再点下载永远提示「正在下载中，请稍候」；
        //  2) 协程先结束、map 后写入时还会把旧 Job 写回去，所以服务器已下载的歌曲（本地
        //     通常也有文件，走的正是提前 return 分支）最容易触发。
        // 现在 map 中始终是真实 Job，且 finally 用 remove(key, selfJob) 只清理自己，
        // 不会误删暂停后立刻续传产生的新任务。
        val job = coroutineScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val selfJob = coroutineContext[Job]
            var initialEntityRef: DownloadEntity? = null
            try {
            Log.i(TAG, "Download job started: ${song.title} (id=${song.id})")
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
            suspend fun isExistingFileCompatible(path: String?): Boolean {
                if (path.isNullOrBlank()) return false
                val f = File(path)
                if (!f.exists() || f.length() <= 0L || f.name.endsWith(".download")) return false
                val realExt = (detectAudioExtension(f) ?: f.extension.lowercase()).lowercase()
                return if (requestedLossless) {
                    realExt in LOSSLESS_AUDIO_EXTS
                } else {
                    // 本地已有无损文件时，它对任何有损目标都属于「更高规格已存在」。
                    // 旧逻辑返回 false，会为了 320K 再下载一份同名 FLAC（服务器曲库歌曲
                    // 在 preferServerFile 下拿到的仍是 FLAC），既浪费时间又占双倍空间。
                    if (realExt in LOSSLESS_AUDIO_EXTS) return true
                    if (song.bitRate >= 320) {
                        val existingBitRate = songDao.getSongByLocalPath(f.absolutePath)?.bitRate
                            ?: record?.let { if (it.localFilePath == f.absolutePath) song.bitRate else 0 }
                            ?: 0
                        if (existingBitRate in 1..255) return false
                    }
                    true
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
                val canonicalOwner = songDao.getSongByLocalPath(validPath)
                val targetRecordSongId = if (canonicalOwner != null && canonicalOwner.id != song.id &&
                    isServerLibraryId(canonicalOwner.serverId)
                ) {
                    canonicalOwner.id
                } else {
                    song.id
                }
                downloadDao.deleteDownloadByLocalPath(validPath)
                saveOrUpdateSongEntity(song, validPath)
                val platformInitial = if (song.id.startsWith("lemon_online_")) {
                    song.id.removePrefix("lemon_online_").substringBefore("_")
                } else "kw"
                val rawMetaInitial = song.rawMetaJson ?: song.relativeFolderPath
                // 以实际 canonical 记录为准：若同一物理文件已属于服务器曲库，则不能标成待推送
                val targetServerLinked = isServerLibraryId(songDao.getSongById(targetRecordSongId)?.serverId) ||
                    isServerLibraryId(song.serverId)
                downloadDao.insertOrUpdate(
                    DownloadEntity(
                        songId = targetRecordSongId,
                        title = song.title,
                        artist = song.artist,
                        coverUrl = song.coverUrl,
                        localFilePath = validPath,
                        remoteUrl = song.streamUrl,
                        status = DownloadStatus.DOWNLOADED,
                        bytesDownloaded = File(validPath).length(),
                        totalBytes = File(validPath).length(),
                        completedTimestamp = System.currentTimeMillis(),
                        sourcePlatform = platformInitial,
                        platformSongId = song.id.removePrefix("lemon_online_${platformInitial}_"),
                        chosenQuality = if (song.bitRate >= 800) "flac" else if (song.bitRate >= 320) "320k" else "128k",
                        rawMetaJson = rawMetaInitial,
                        serverPushStatus = if (targetServerLinked) 0 else 1
                    )
                )
                removeTask(song.id)
                urlResolverMap.remove(song.id)
                    fallbackResolverMap.remove(song.id)
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
            val platform = if (song.id.startsWith("lemon_online_")) {
                song.id.removePrefix("lemon_online_").substringBefore("_")
            } else "kw"
            val rawMeta = song.rawMetaJson ?: song.relativeFolderPath
            val isServerSong = song.serverId.isNotBlank() && song.serverId !in listOf("local_storage", "local_folder", "local_saf", "lemon_online")
            val initialEntity = DownloadEntity(
                songId = song.id,
                title = song.title,
                artist = song.artist,
                coverUrl = song.coverUrl,
                localFilePath = destFile.absolutePath,
                remoteUrl = song.streamUrl,
                status = DownloadStatus.DOWNLOADING,
                bytesDownloaded = 0L,
                totalBytes = 0L,
                sourcePlatform = platform,
                platformSongId = song.id.removePrefix("lemon_online_${platform}_"),
                chosenQuality = if (song.bitRate >= 800) "flac" else if (song.bitRate >= 320) "320k" else "128k",
                rawMetaJson = rawMeta,
                serverPushStatus = if (isServerSong) 0 else 1
            )
            initialEntityRef = initialEntity
            downloadDao.insertOrUpdate(initialEntity)
            
            updateTask(DownloadTask(song = song, progress = 0f, status = DownloadStatus.DOWNLOADING))

            // 3. 进入并发信号量等待队列 (超出并发上限自动排队)
            Log.i(TAG, "Waiting for download permit: ${song.title}")
            // 下载完成后的元数据内嵌放到 withPermit 之外执行：它会发网络请求（封面/歌词），
            // 若占着并发许可，用户看到「已完成」后再下下一首就会一直「排队等待中」。
            var completedFileForMetadata: File? = null
            var completedSongForMetadata: UnifiedSong? = null
            downloadSemaphore.withPermit {
                Log.i(TAG, "Download permit acquired: ${song.title}")
                var actualDestFile = destFile
                try {
                    // 动态解析真实直链：**只要调用方传了解析器就以解析结果为准**。
                    // 旧判断是"仅当 streamUrl 为空或为 lemon_online:// 占位时才解析"，而服务器曲库
                    // 歌曲的 streamUrl 是 /api/play/local?path=…&token=…，两个条件都不满足 →
                    // 解析器永远不会被调用 → 拖回的永远是服务器上的**原文件**（常见为无损），
                    // 与用户所选的下载音质完全无关。这是"选低音质仍下到无损"的第二个根因。
                    var effectiveStreamUrl = song.streamUrl
                    val activeResolver = urlResolver ?: urlResolverMap[song.id]
                    val activeFallback = fallbackResolver ?: fallbackResolverMap[song.id]
                    if (activeResolver != null) {
                        Log.i(TAG, "Resolving download stream: ${song.title}")
                        val resolved = runCatching { activeResolver() }.getOrNull()
                        Log.i(TAG, "Resolved download stream: ${song.title} -> ${if (resolved.isNullOrBlank()) "null" else "ok"}")
                        if (!resolved.isNullOrBlank()) {
                            effectiveStreamUrl = resolved
                        }
                    }
                    // 保存服务端代理原地址用于失败回退；实际下载优先走解出的 CDN 直链
                    val proxyStreamUrl = effectiveStreamUrl
                    effectiveStreamUrl = unwrapServerProxyUrl(effectiveStreamUrl)
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

                    var tempDestFile = File("${actualDestFile.absolutePath}.download")
                    var existingBytes = if (tempDestFile.exists()) tempDestFile.length() else 0L

                    // **分片下载**：服务端 /api/play/local 与 /api/play/proxy 的长时间整段流
                    // 在 0.5~1.5MB 左右会停住（实测），只有单次 1MB 左右的 Range 请求稳定。
                    // 因此每 1MB 发一次 Range，读完立刻关连接再续下一段，既避开服务端长连接
                    // 的限速/断流，也让断点续传的偏移校验变得精确。
                    val rangeChunkBytes = 1024L * 1024L
                    var totalLength = -1L
                    var totalRead = existingBytes
                    var lastTime = System.currentTimeMillis()
                    var lastBytes = existingBytes
                    var contentTypeHeader = ""
                    var downloadCompleted = false
                    var loopCount = 0
                    var resetCount = 0

                    fun buildDownloadRequest(
                        rangeStart: Long,
                        rangeEnd: Long,
                        targetUrl: String = effectiveStreamUrl
                    ): Request {
                        val rb = Request.Builder()
                            .url(targetUrl)
                        val cdnHeaders = com.lm.player.core.network.MusicCdnHeaders.buildHeadersForUrl(targetUrl)
                        cdnHeaders.forEach { (k, v) -> rb.header(k, v) }
                        rb.header("Range", "bytes=$rangeStart-$rangeEnd")
                        return rb.build()
                    }

                    var canFallbackToProxy = effectiveStreamUrl != proxyStreamUrl &&
                        proxyStreamUrl.startsWith("http") && proxyStreamUrl.contains("/api/play/proxy")

                    var fallbackAttempt = 0
                    var maxProgressSeen = 0f

                    // 403/超时后按“服务器原文件 → 降一级音质 → 再降一级”的候选链切换。
                    // 切换时丢弃旧临时文件，避免把 Hi-Res 的半截数据拼进 FLAC。
                    suspend fun switchToFallback(): Boolean {
                        val fallback = activeFallback ?: return false
                        while (fallbackAttempt < 8) {
                            val next = runCatching { fallback(fallbackAttempt++) }.getOrNull()
                            if (next.isNullOrBlank() || next == effectiveStreamUrl) continue
                            val nextExt = detectAudioExtension(null, next, "")
                            if (nextExt != null && !actualDestFile.name.endsWith(".$nextExt", true)) {
                                actualDestFile = File(destFile.parentFile, "${destFile.nameWithoutExtension}.$nextExt")
                            }
                            runCatching { tempDestFile.delete() }
                            effectiveStreamUrl = next
                            tempDestFile = File("${actualDestFile.absolutePath}.download")
                            existingBytes = 0L
                            totalLength = -1L
                            totalRead = 0L
                            lastBytes = 0L
                            lastTime = System.currentTimeMillis()
                            contentTypeHeader = ""
                            resetCount = 0
                            canFallbackToProxy = false
                            // 新候选是另一份文件，进度不能沿用旧候选的 100%
                            maxProgressSeen = 0f
                            Log.w(TAG, "Download fallback #${fallbackAttempt}: ${song.title} -> ${next.substringBefore('?')}")
                            return true
                        }
                        return false
                    }

                    fun executeChunk(rangeStart: Long, rangeEnd: Long): okhttp3.Response {
                        return try {
                            downloadClient.newCall(buildDownloadRequest(rangeStart, rangeEnd)).execute()
                        } catch (e: java.io.IOException) {
                            if (!canFallbackToProxy) throw e
                            Log.w(TAG, "Direct CDN connect failed (${e.message}), fallback to server proxy: ${song.title}")
                            effectiveStreamUrl = proxyStreamUrl
                            canFallbackToProxy = false
                            downloadClient.newCall(buildDownloadRequest(rangeStart, rangeEnd)).execute()
                        }
                    }

                    while (!downloadCompleted) {
                        if (!coroutineContext.isActive) {
                            Log.i(TAG, "Download cancelled/paused: ${song.title}")
                            return@withPermit
                        }
                        loopCount++
                        if (loopCount > 50_000) throw Exception("下载分片次数异常，请重试")

                        if (tempDestFile.exists()) existingBytes = tempDestFile.length()
                        if (totalLength > 0 && existingBytes >= totalLength) {
                            downloadCompleted = true
                            break
                        }
                        if (existingBytes < 0L) existingBytes = 0L
                        val rangeStart = existingBytes
                        val rangeEnd = if (totalLength > 0) {
                            minOf(rangeStart + rangeChunkBytes - 1, totalLength - 1)
                        } else {
                            rangeStart + rangeChunkBytes - 1
                        }
                        if (rangeEnd < rangeStart) {
                            downloadCompleted = true
                            break
                        }

                        var response = try {
                            executeChunk(rangeStart, rangeEnd)
                        } catch (e: java.io.IOException) {
                            if (!switchToFallback()) throw e
                            continue
                        }
                        if (!response.isSuccessful) {
                            Log.w(TAG, "Download chunk rejected (${response.code}): ${song.title}")
                            response.close()
                            if (switchToFallback()) continue
                            throw Exception("HTTP 下载失败: ${response.code}")
                        }
                        Log.i(
                            TAG,
                            "Download chunk $rangeStart-$rangeEnd -> ${response.code} (${response.request.url.host})"
                        )
                        if ((response.body?.contentLength() ?: -1L) == 0L) {
                            Log.w(TAG, "Empty audio body: ${song.title}")
                            response.close()
                            if (switchToFallback()) continue
                            throw Exception("响应体为空")
                        }

                        if (response.code == 416) {
                            response.close()
                            // 文件恰好是 chunk 整数倍时，最后一段之后会返回 416；
                            // 服务端没给过总长度时按「已到末尾」处理，避免反复删档重下。
                            if (rangeStart > 0L && totalLength <= 0L) {
                                downloadCompleted = true
                                break
                            }
                            if (rangeStart > 0L && resetCount < 1) {
                                tempDestFile.delete()
                                existingBytes = 0L
                                totalLength = -1L
                                totalRead = 0L
                                resetCount++
                                continue
                            }
                            if (switchToFallback()) continue
                            throw Exception("HTTP 下载失败: 416")
                        }

                        var restartFromZero = false
                        response.use { resp ->
                            val body = resp.body ?: throw Exception("响应体为空")
                            contentTypeHeader = resp.header("Content-Type").orEmpty()

                            val isRangeOk = resp.code == 206
                            val parsedRange = parseContentRange(resp.header("Content-Range"))
                            val serverStart = parsedRange?.first
                            val serverTotal = parsedRange?.third ?: -1L
                            if (serverTotal > 0L) totalLength = serverTotal

                            if (isRangeOk && serverStart != null && serverStart != rangeStart) {
                                Log.w(
                                    TAG,
                                    "断点偏移不匹配 (本地=$rangeStart, 服务端=${resp.header("Content-Range")})，丢弃残片重新下载: ${song.title}"
                                )
                                restartFromZero = true
                                return@use
                            }

                            val append = isRangeOk && rangeStart > 0L
                            var baseRead = rangeStart
                            if (!isRangeOk) {
                                // 个别源忽略 Range，返回 200 整段；丢弃残片从头写，避免拼接错乱
                                if (tempDestFile.exists()) tempDestFile.delete()
                                existingBytes = 0L
                                totalLength = body.contentLength().takeIf { it > 0L } ?: -1L
                                baseRead = 0L
                                totalRead = 0L
                            } else {
                                totalRead = rangeStart
                            }

                            val bodyLength = body.contentLength()
                            var chunkRead = 0L
                            body.byteStream().use { input ->
                                FileOutputStream(tempDestFile, append).use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    var bytesRead: Int
                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        if (!coroutineContext.isActive) break
                                        output.write(buffer, 0, bytesRead)
                                        chunkRead += bytesRead
                                        totalRead = baseRead + chunkRead

                                        val now = System.currentTimeMillis()
                                        if (now - lastTime >= 300 || (totalLength > 0 && totalRead >= totalLength)) {
                                            val speed = if (now > lastTime) {
                                                ((totalRead - lastBytes) * 1000L / (now - lastTime)) / 1024L
                                            } else 0L
                                            lastTime = now
                                            lastBytes = totalRead
                                            val rawProgress = if (totalLength > 0) {
                                                (totalRead.toFloat() / totalLength).coerceIn(0f, 1f)
                                            } else 0f
                                            maxProgressSeen = maxOf(maxProgressSeen, rawProgress)
                                            updateTask(
                                                DownloadTask(
                                                    song = song.copy(streamUrl = effectiveStreamUrl),
                                                    progress = maxProgressSeen,
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

                            if (!coroutineContext.isActive) return@use
                            if (bodyLength >= 0L && chunkRead < bodyLength) {
                                throw Exception("分片不完整（${chunkRead}/${bodyLength} 字节），请重试")
                            }
                            val newSize = if (tempDestFile.exists()) tempDestFile.length() else 0L
                            existingBytes = newSize
                            totalRead = newSize

                            downloadCompleted = if (!isRangeOk) {
                                true
                            } else {
                                val requested = rangeEnd - rangeStart + 1L
                                (totalLength > 0L && existingBytes >= totalLength) ||
                                    (bodyLength in 0L until requested) ||
                                    (bodyLength < 0L && newSize - rangeStart < requested)
                            }
                        }

                        if (!coroutineContext.isActive) return@withPermit
                        if (restartFromZero) {
                            if (resetCount >= 1) throw Exception("服务端 Range 偏移异常")
                            resetCount++
                            tempDestFile.delete()
                            existingBytes = 0L
                            totalLength = -1L
                            totalRead = 0L
                            continue
                        }
                        if (!downloadCompleted && tempDestFile.length() == rangeStart) {
                            throw Exception("下载停滞，请重试")
                        }
                    }

                    if (tempDestFile.length() <= 0L) {
                        throw Exception("服务端返回了空文件")
                    }
                    if (totalLength > 0L && tempDestFile.length() < totalLength) {
                        throw Exception("下载不完整（已接收 ${tempDestFile.length()} / $totalLength 字节），请重试")
                    }

                    // 下载完成后，通过文件魔数头 (fLaC / ID3 / RIFF / ftyp) 与 Content-Type 二次精准校准真实文件扩展名
                    val realAudioExt = detectAudioExtension(tempDestFile, effectiveStreamUrl, contentTypeHeader)
                    if (realAudioExt != null && !actualDestFile.name.endsWith(".$realAudioExt", ignoreCase = true)) {
                        actualDestFile = File(actualDestFile.parentFile, "${actualDestFile.nameWithoutExtension}.$realAudioExt")
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
                    val probedBitRate = readAudioBitRate(actualDestFile)
                    val actualBitRate = probedBitRate.takeIf { it > 0 } ?: when {
                        finalExt in LOSSLESS_AUDIO_EXTS -> 960
                        effectiveStreamUrl.contains("320") -> 320
                        effectiveStreamUrl.contains("128") -> 128
                        else -> song.bitRate
                    }
                    val finalSong = song.copy(
                        streamUrl = effectiveStreamUrl,
                        format = finalExt,
                        bitRate = actualBitRate
                    )

                    // ===== 收尾顺序是本文件最容易踩坑的地方，务必看清以下分工 =====
                    //
                    // 曾经的写法是「先把封面/歌词全部处理完，再写库 + 移除任务」，导致了一个
                    // 被用户反复反馈的现象：**进度条卡在 100% 很久才变成已完成**。
                    //
                    // 根因：进度条由 `totalRead / totalLength` 计算，读完最后一个字节就显示 100%；
                    // 而进度条什么时候消失、任务什么时候变「已完成」，取决于 `removeTask` + 写库，
                    // 它们被排在 postProcessDownloadedFile 之后。而 postProcessDownloadedFile 里有
                    // **两次同步网络请求**（拉取封面图 + 拉取歌词），即使失败也会各自走完超时，
                    // 于是「100% → 已完成」之间凭空插入了几十秒的纯等待。
                    // 这也解释了用户观察到的另一个现象：暂停后恢复会立刻完成 ——
                    // 恢复时字节已完整，跳过了下载循环，收尾网络请求也往往已缓存/变快。
                    //
                    // 现在的分工：
                    //   1) 快速取一次本地封面（仅本地文件读取，无网络）—— 只为让列表立即有封面；
                    //   2) **立刻**写库 + removeTask，让进度条马上消失、状态变「已完成」；
                    //   3) 元数据内嵌（封面下载、歌词拉取、ID3/FLAC 标签写入）放到后台继续，
                    //      用 NonCancellable 保证即使用户此时暂停/取消也不会写出半截标签。
                    val quickCover = postProcessLocalCoverOnly(actualDestFile)
                    val effectiveCover = if (!quickCover.isNullOrBlank() && finalSong.coverUrl.isBlank()) quickCover else finalSong.coverUrl

                    // 5. 下载成功完成 - 持久化到 downloads 表与 songs 表（先清理同一物理路径的冗余旧记录）
                    val canonicalOwner = songDao.getSongByLocalPath(actualDestFile.absolutePath)
                    val targetRecordSongId = if (canonicalOwner != null && canonicalOwner.id != song.id &&
                        isServerLibraryId(canonicalOwner.serverId)
                    ) {
                        canonicalOwner.id
                    } else {
                        song.id
                    }
                    downloadDao.deleteDownloadByLocalPath(actualDestFile.absolutePath)
                    if (targetRecordSongId != song.id) {
                        downloadDao.deleteDownload(song.id)
                    }
                    // 顺序很重要：saveOrUpdateSongEntity 在处理「同一物理文件已属于服务器曲库记录」时
                    // 会清理 downloads 表中的冗余记录。必须**先**写 songs 表、再写 completedEntity，
                    // 否则刚插入的完成/待同步记录会被它删掉，表现为「下载完成但下载管理里没有记录」。
                    saveOrUpdateSongEntity(finalSong.copy(coverUrl = effectiveCover), actualDestFile.absolutePath)
                    val targetServerLinked = isServerLibraryId(songDao.getSongById(targetRecordSongId)?.serverId) ||
                        isServerLibraryId(song.serverId)
                    val completedEntity = initialEntity.copy(
                        songId = targetRecordSongId,
                        status = DownloadStatus.DOWNLOADED,
                        coverUrl = effectiveCover,
                        localFilePath = actualDestFile.absolutePath,
                        remoteUrl = effectiveStreamUrl,
                        bytesDownloaded = actualDestFile.length(),
                        totalBytes = actualDestFile.length(),
                        completedTimestamp = System.currentTimeMillis(),
                        // 以实际落库的 canonical 记录为准：服务器曲库已有此歌时不能再标成待推送，
                        // 否则离线同步会把它再次推到服务端，形成「已有同名文件」的 await_exist 死队列。
                        serverPushStatus = if (targetServerLinked) 0 else 1
                    )
                    downloadDao.insertOrUpdate(completedEntity)
                    removeTask(song.id)
                    urlResolverMap.remove(song.id)
                    fallbackResolverMap.remove(song.id)
                    // 许可释放后再做元数据；这里只登记，不占用 withPermit
                    completedFileForMetadata = actualDestFile
                    completedSongForMetadata = finalSong
                    Log.i(TAG, "Song ${song.title} downloaded to ${actualDestFile.absolutePath}（元数据将在后台继续内嵌）")

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
                    fallbackResolverMap.remove(song.id)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "「${song.title}」下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            // 6. 后台补全元数据（封面下载、歌词拉取、标签内嵌）—— 在 withPermit 之外执行，
            //    不占用下载并发许可；标签写入用 NonCancellable，避免留下半截音频头。
            completedFileForMetadata?.let { metaFile ->
                val metaSong = completedSongForMetadata
                if (metaSong != null) {
                    try {
                        val localCoverPath = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                            postProcessDownloadedFile(metaFile, metaSong)
                        }
                        Log.i(TAG, "Song ${metaSong.title} 元数据内嵌完成（封面=${localCoverPath ?: "无"}）")
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.w(TAG, "元数据内嵌失败（不影响已下载文件）: ${metaSong.title}", e)
                    }
                }
            }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    // 用户暂停/取消：交给 pauseTask/cancelTask 维护状态，这里只记录
                    Log.i(TAG, "Download job for ${song.title} cancelled before transfer loop")
                } else {
                    Log.e(TAG, "Download task failed before transfer loop for ${song.title}", e)
                    initialEntityRef?.let { pending ->
                        runCatching {
                            downloadDao.insertOrUpdate(
                                pending.copy(status = DownloadStatus.FAILED, errorMessage = e.message)
                            )
                        }
                    }
                    runCatching { songDao.updateDownloadStatus(song.id, DownloadStatus.FAILED, null) }
                    removeTask(song.id)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "「${song.title}」下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                if (selfJob != null) jobMap.remove(song.id, selfJob)
                urlResolverMap.remove(song.id)
                    fallbackResolverMap.remove(song.id)
            }
        }
        val existingJob = jobMap.putIfAbsent(song.id, job)
        if (existingJob != null) {
            job.cancel()
            coroutineScope.launch(Dispatchers.Main) {
                Toast.makeText(context, "「${song.title}」正在下载中，请稍候...", Toast.LENGTH_SHORT).show()
            }
            return false
        }
        if (urlResolver != null) {
            urlResolverMap[song.id] = urlResolver
        }
        if (fallbackResolver != null) {
            fallbackResolverMap[song.id] = fallbackResolver
        }
        // 立即向活跃任务映射中加入，确保 UI 在点击瞬间立即可见 (避免直链解析网络延迟导致下载中空白)
        updateTask(DownloadTask(song = song, progress = 0f, status = DownloadStatus.DOWNLOADING))
        job.start()
        Log.i(TAG, "Download enqueued: ${song.title} (id=${song.id}, resolver=${urlResolver != null})")
        return true
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
            startDownload(
                task.song,
                urlResolver = urlResolverMap[songId],
                fallbackResolver = fallbackResolverMap[songId]
            )
        }
    }

    /**
     * 取消单曲下载任务 (彻底取消协程、清理残余临时文件并删除任务)
     */
    fun cancelTask(songId: String) {
        val task = _activeTasksMap.value[songId]
        jobMap.remove(songId)?.cancel()
        urlResolverMap.remove(songId)
        fallbackResolverMap.remove(songId)
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
    /**
     * **只做本地封面探测**（无任何网络请求），用于下载完成瞬间先给列表一个封面。
     *
     * 抽出来的原因见调用点的长注释：真正的元数据内嵌含同步网络请求，
     * 绝不能挡在「写库 + 移除任务」之前，否则进度条会在 100% 停留很久。
     *
     * @return 本地专辑目录下 cover.jpg 的绝对路径；不存在则 null
     */
    private fun postProcessLocalCoverOnly(file: File): String? = try {
        val coverJpg = file.parentFile?.let { File(it, "cover.jpg") }
        if (coverJpg != null && coverJpg.exists() && coverJpg.length() > 0L) coverJpg.absolutePath else null
    } catch (_: Exception) {
        null
    }

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
                if (e is kotlinx.coroutines.CancellationException) throw e
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
                if (e is kotlinx.coroutines.CancellationException) throw e
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
                if (e is kotlinx.coroutines.CancellationException) throw e
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
                if (e is kotlinx.coroutines.CancellationException) throw e
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
        _activeTasksMap.update { it + (task.song.id to task) }
    }

    private fun removeTask(songId: String) {
        _activeTasksMap.update { it - songId }
    }
}
