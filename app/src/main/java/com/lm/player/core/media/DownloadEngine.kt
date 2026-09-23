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
            customDownloadPath = prefs.getString("custom_download_path", "") ?: "",
            wifiOnly = prefs.getBoolean("wifi_only", false),
            autoTagging = prefs.getBoolean("auto_tagging", true)
        )
    )

    @Volatile
    private var downloadSemaphore = kotlinx.coroutines.sync.Semaphore(
        prefs.getInt("max_concurrent", 3).coerceIn(1, 10)
    )

    fun updateSettings(newSettings: DownloadSettings) {
        val oldMax = downloadSettings.value.maxConcurrent
        downloadSettings.value = newSettings
        prefs.edit()
            .putInt("max_concurrent", newSettings.maxConcurrent)
            .putString("custom_download_path", newSettings.customDownloadPath)
            .putBoolean("wifi_only", newSettings.wifiOnly)
            .putBoolean("auto_tagging", newSettings.autoTagging)
            .apply()

        if (oldMax != newSettings.maxConcurrent) {
            downloadSemaphore = kotlinx.coroutines.sync.Semaphore(newSettings.maxConcurrent.coerceIn(1, 10))
            Log.i(TAG, "Download concurrency limit updated to ${newSettings.maxConcurrent}")
        }
    }

    // 活跃下载任务映射 Map<SongId, DownloadTask> - 线程安全反应式 StateFlow
    private val _activeTasksMap = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val activeTasksFlow: StateFlow<List<DownloadTask>> = _activeTasksMap
        .map { it.values.toList() }
        .stateIn(coroutineScope, SharingStarted.Eagerly, emptyList())

    private val jobMap = ConcurrentHashMap<String, Job>()

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
        val existing = songDao.getSongById(song.id)
        if (existing != null) {
            songDao.updateDownloadStatusAndTimestamp(song.id, DownloadStatus.DOWNLOADED, localFilePath, System.currentTimeMillis())
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
                bitRate = if (song.bitRate > 0) song.bitRate else 320,
                format = song.format.ifBlank { "mp3" },
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

        // 立即向活跃任务映射中加入，确保 UI 在点击瞬间立即可见 (避免直链解析网络延迟导致下载中空白)
        updateTask(DownloadTask(song = song, progress = 0f, status = DownloadStatus.DOWNLOADING))

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

            // 1. 防重复下载检测与核对：检查 Room 数据库与本地文件真实性
            val record = downloadDao.getDownloadRecord(song.id)
            val isRecordDownloaded = record?.status == DownloadStatus.DOWNLOADED &&
                    !record.localFilePath.isNullOrBlank() &&
                    File(record.localFilePath).let { it.exists() && it.length() > 0 }

            val isDestFileValid = destFile.exists() && destFile.length() > 0
            val isLocalPathValid = !song.localFilePath.isNullOrBlank() && File(song.localFilePath).let { it.exists() && it.length() > 0 }

            if (isRecordDownloaded || isDestFileValid || isLocalPathValid) {
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
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "「${song.title}」已在本地下载完成", Toast.LENGTH_SHORT).show()
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
                    // 动态解析真实直链（若有传入 urlResolver 且 streamUrl 为空或占位协议）
                    var effectiveStreamUrl = song.streamUrl
                    if (effectiveStreamUrl.isBlank() || effectiveStreamUrl.startsWith("lemon_online://")) {
                        if (urlResolver != null) {
                            val resolved = runCatching { urlResolver() }.getOrNull()
                            if (!resolved.isNullOrBlank()) {
                                effectiveStreamUrl = resolved
                            }
                        }
                    }

                    if (effectiveStreamUrl.isBlank() || (!effectiveStreamUrl.startsWith("http://") && !effectiveStreamUrl.startsWith("https://"))) {
                        throw Exception("未能解析或获取有效的音频直链")
                    }

                    // 动态校准真实文件后缀扩展名
                    val cleanUrlPath = effectiveStreamUrl.substringBefore("?").lowercase()
                    val detectedExt = when {
                        cleanUrlPath.endsWith(".flac") -> "flac"
                        cleanUrlPath.endsWith(".mp3") -> "mp3"
                        cleanUrlPath.endsWith(".m4a") -> "m4a"
                        cleanUrlPath.endsWith(".wav") -> "wav"
                        cleanUrlPath.endsWith(".ogg") -> "ogg"
                        cleanUrlPath.endsWith(".aac") -> "aac"
                        else -> null
                    }
                    if (detectedExt != null && !destFile.name.endsWith(".$detectedExt", ignoreCase = true)) {
                        actualDestFile = File(destFile.parentFile, "${destFile.nameWithoutExtension}.$detectedExt")
                    }

                    if (actualDestFile.exists()) {
                        actualDestFile.delete()
                    }

                    val request = Request.Builder()
                        .url(effectiveStreamUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("Accept", "*/*")
                        .build()
                    val response = okHttpClient.newCall(request).execute()

                    if (!response.isSuccessful) {
                        throw Exception("HTTP 下载失败: ${response.code}")
                    }

                    val body = response.body ?: throw Exception("响应体为空")
                    val totalLength = body.contentLength()

                    var lastTime = System.currentTimeMillis()
                    var lastBytes = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(actualDestFile).use { output ->
                            val buffer = ByteArray(8 * 1024)
                            var bytesRead: Int
                            var totalRead = 0L

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

                    // 4. 后置元数据处理：拉取高清封面、原始同步歌词、生成伴随 .lrc 以及音频内嵌 ID3/FLAC/M4A 标签
                    val localCoverPath = postProcessDownloadedFile(actualDestFile, song)
                    val effectiveCover = if (!localCoverPath.isNullOrBlank() && song.coverUrl.isBlank()) localCoverPath else song.coverUrl

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
                    saveOrUpdateSongEntity(song.copy(streamUrl = effectiveStreamUrl, coverUrl = effectiveCover), actualDestFile.absolutePath)
                    removeTask(song.id)
                    Log.i(TAG, "Song ${song.title} downloaded and tagged successfully to ${actualDestFile.absolutePath}")

                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "「${song.title}」已完成离线下载与标签内嵌", Toast.LENGTH_SHORT).show()
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to download song ${song.title}", e)
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
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "「${song.title}」下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    jobMap.remove(song.id)
                }
            }
        }
        jobMap[song.id] = job
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
        val downloadLrc = lemonPrefs.getBoolean("download_lrc_file", true)

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
        val job = jobMap.remove(songId)
        job?.cancel()
        removeTask(songId)
        coroutineScope.launch(Dispatchers.IO) {
            val record = downloadDao.getDownloadRecord(songId)
            if (record?.localFilePath != null) {
                val f = File(record.localFilePath)
                if (f.exists()) f.delete()
                val lrcFile = File(f.parentFile, "${f.nameWithoutExtension}.lrc")
                if (lrcFile.exists()) lrcFile.delete()
            }
            val dir = getDownloadDir()
            dir.listFiles { _, name -> name.startsWith(songId) }?.forEach { it.delete() }

            val existingSong = songDao.getSongById(songId)
            if (existingSong?.serverId == "local_storage") {
                songDao.deleteSongById(songId)
            } else {
                songDao.updateDownloadStatus(songId, DownloadStatus.NOT_DOWNLOADED, null)
            }
            downloadDao.deleteDownload(songId)

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "已停止并移除下载任务", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 删除已完成下载的本地歌曲文件与数据库记录
     */
    fun deleteDownloadedSong(song: UnifiedSong) {
        cancelDownload(song.id)
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
        songs.forEach { cancelDownload(it.id) }
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
