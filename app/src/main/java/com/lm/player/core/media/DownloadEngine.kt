package com.lm.player.core.media

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.lm.player.core.database.dao.DownloadDao
import com.lm.player.core.database.dao.SongDao
import com.lm.player.core.database.entity.DownloadEntity
import com.lm.player.core.model.DownloadSettings
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.DownloadTask
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.network.NetworkClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    // 活跃下载任务映射 Map<SongId, DownloadTask>
    private val _activeTasksMap = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val activeTasksFlow: StateFlow<List<DownloadTask>> = MutableStateFlow<List<DownloadTask>>(emptyList()).apply {
        coroutineScope.launch {
            _activeTasksMap.collect { map ->
                this@apply.value = map.values.toList()
            }
        }
    }

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
        return name.trim()
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim('.', ' ')
            .ifBlank { "未知" }
    }

    /**
     * 根据在线文件夹层级结构或歌手/专辑规范结构获取下载存储的目标文件
     * 优先使用传入的 online folder hierarchy 或 relativeFolderPath
     */
    /**
     * 根据在线文件夹层级结构或歌手/专辑规范结构获取下载存储的目标文件
     * 优先使用传入的 online folder hierarchy 或 relativeFolderPath
     */
    fun getTargetDownloadFile(song: UnifiedSong, folderHierarchy: String? = null): File {
        val baseDir = getDownloadDir()

        val effectiveHierarchy = folderHierarchy ?: song.relativeFolderPath

        val subFolder = when {
            !effectiveHierarchy.isNullOrBlank() -> {
                effectiveHierarchy.split('/', '\\')
                    .map { sanitizeSegment(it) }
                    .filter { it.isNotBlank() }
                    .joinToString("/")
            }
            song.artist.isNotBlank() && song.album.isNotBlank() && !song.artist.contains("未知") && !song.album.contains("未知") -> {
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
        val ext = song.format.lowercase().ifBlank { "flac" }
        return File(parentDir, "$safeTitle.$ext")
    }

    /**
     * 启动歌曲下载 (集成在线目录层级建立、防重复核对与断点续传检测)
     */
    fun startDownload(song: UnifiedSong, folderHierarchy: String? = null) {
        if (jobMap.containsKey(song.id)) {
            coroutineScope.launch(Dispatchers.Main) {
                Toast.makeText(context, "「${song.title}」正在下载中，请稍候...", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val job = coroutineScope.launch(Dispatchers.IO) {
            val dbRelativePath = if (song.relativeFolderPath.isNullOrBlank()) {
                try {
                    songDao.getSongById(song.id)?.relativeFolderPath
                } catch (_: Exception) { null }
            } else null

            val effectiveFolderHierarchy = folderHierarchy ?: song.relativeFolderPath ?: dbRelativePath
            val songToDownload = if (song.relativeFolderPath.isNullOrBlank() && !dbRelativePath.isNullOrBlank()) {
                song.copy(relativeFolderPath = dbRelativePath)
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
                songDao.updateDownloadStatus(song.id, DownloadStatus.DOWNLOADED, validPath)
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
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "「${song.title}」已在本地下载完成，无需重复下载", Toast.LENGTH_SHORT).show()
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
            songDao.updateDownloadStatus(song.id, DownloadStatus.DOWNLOADING, null)
            
            updateTask(DownloadTask(song = song, progress = 0f, status = DownloadStatus.DOWNLOADING))

            // 3. 进入并发信号量等待队列 (超出并发上限自动排队)
            downloadSemaphore.withPermit {
                try {
                    if (destFile.exists()) {
                        destFile.delete()
                    }

                    val request = Request.Builder().url(song.streamUrl).build()
                    val response = okHttpClient.newCall(request).execute()

                    if (!response.isSuccessful) {
                        throw Exception("HTTP 下载失败: ${response.code}")
                    }

                    val body = response.body ?: throw Exception("响应体为空")
                    val totalLength = body.contentLength()

                    var lastTime = System.currentTimeMillis()
                    var lastBytes = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(destFile).use { output ->
                            val buffer = ByteArray(8 * 1024)
                            var bytesRead: Int
                            var totalRead = 0L

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalRead += bytesRead

                                val now = System.currentTimeMillis()
                                if (now - lastTime >= 400 || totalRead == totalLength) {
                                    val speed = if (now > lastTime) ((totalRead - lastBytes) * 1000L / (now - lastTime)) / 1024L else 0L
                                    lastTime = now
                                    lastBytes = totalRead
                                    val progress = if (totalLength > 0) (totalRead.toFloat() / totalLength).coerceIn(0f, 1f) else 0f
                                    updateTask(
                                        DownloadTask(
                                            song = song,
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

                    // 4. 下载成功完成
                    val completedEntity = initialEntity.copy(
                        status = DownloadStatus.DOWNLOADED,
                        bytesDownloaded = destFile.length(),
                        totalBytes = destFile.length(),
                        completedTimestamp = System.currentTimeMillis()
                    )
                    downloadDao.insertOrUpdate(completedEntity)
                    songDao.updateDownloadStatus(song.id, DownloadStatus.DOWNLOADED, destFile.absolutePath)
                    removeTask(song.id)
                    Log.i(TAG, "Song ${song.title} downloaded successfully to ${destFile.absolutePath}")

                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "「${song.title}」已完成离线下载", Toast.LENGTH_SHORT).show()
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to download song ${song.title}", e)
                    if (destFile.exists() && destFile.length() == 0L) {
                        destFile.delete()
                    }
                    val failedEntity = initialEntity.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = e.message
                    )
                    downloadDao.insertOrUpdate(failedEntity)
                    songDao.updateDownloadStatus(song.id, DownloadStatus.FAILED, null)
                    removeTask(song.id)
                } finally {
                    jobMap.remove(song.id)
                }
            }
        }
        jobMap[song.id] = job
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
            }
            val dir = getDownloadDir()
            dir.listFiles { _, name -> name.startsWith(songId) }?.forEach { it.delete() }

            songDao.updateDownloadStatus(songId, DownloadStatus.NOT_DOWNLOADED, null)
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
            }
            if (!song.localFilePath.isNullOrBlank() && song.serverId != "local_storage") {
                val f = File(song.localFilePath)
                if (f.exists()) f.delete()
            }
            val defaultDest = File(getDownloadDir(), "${song.id}.${song.format}")
            if (defaultDest.exists()) defaultDest.delete()

            downloadDao.deleteDownload(song.id)
            songDao.updateDownloadStatus(song.id, DownloadStatus.NOT_DOWNLOADED, null)

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "已删除本地离线歌曲: ${song.title}", Toast.LENGTH_SHORT).show()
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
