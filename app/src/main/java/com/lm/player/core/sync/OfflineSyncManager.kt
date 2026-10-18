package com.lm.player.core.sync

import android.content.Context
import android.util.Log
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.database.entity.DownloadEntity
import com.lm.player.core.model.LemonServerDownloadTask
import com.lm.player.core.network.LemonMusicProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 离线下载反向推送引擎 (OfflineSyncManager)
 *
 * 核心功能：
 * 1. 追踪离线或纯本地模式下下载到本机的歌曲 (serverPushStatus == 1，含失败重试 3)；
 * 2. 当检测到连接并登录柠檬音乐服务端后，支持一键或自动批量推送到 NAS 服务端下载 (/api/download/add)；
 * 3. 推送成功后自动标记已同步并触发服务端曲库扫描，实现 NAS 与手机本地曲库全链路闭环互通。
 */
@android.annotation.SuppressLint("StaticFieldLeak")
class OfflineSyncManager private constructor(private val context: Context) {

    private val TAG = "OfflineSyncManager"
    private val database = ZdsDatabase.getInstance(context)
    private val downloadDao = database.downloadDao()
    private val songDao = database.songDao()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val pushMutex = Mutex()

    private val _pendingPushCount = MutableStateFlow(0)
    val pendingPushCount: StateFlow<Int> = _pendingPushCount.asStateFlow()

    private val _pendingPushList = MutableStateFlow<List<DownloadEntity>>(emptyList())
    val pendingPushList: StateFlow<List<DownloadEntity>> = _pendingPushList.asStateFlow()

    private val _isPushing = MutableStateFlow(false)
    val isPushing: StateFlow<Boolean> = _isPushing.asStateFlow()

    init {
        scope.launch {
            // 上次推送进行到一半时进程被杀：把残留的「推送中」恢复为待推送，
            // 否则这些记录会永久卡在 2，既不在待同步口径里，也没有任何重试入口。
            runCatching { downloadDao.markAllServerPushStatus(2, 1) }
        }
        // 反应式监听待推送任务数量与任务列表变动
        scope.launch {
            downloadDao.getPendingServerPushCountFlow().collect { count ->
                _pendingPushCount.value = count
            }
        }
        scope.launch {
            downloadDao.getPendingServerPushFlow().collect { list ->
                _pendingPushList.value = list
            }
        }
    }

    companion object {
        @Volatile
        private var instance: OfflineSyncManager? = null

        /** 非「服务器曲库」的 serverId：本机扫描、在线占位等 */
        private val NON_SERVER_LIBRARY_IDS = setOf("local_storage", "local_folder", "local_saf", "lemon_online")

        fun getInstance(context: Context): OfflineSyncManager {
            return instance ?: synchronized(this) {
                instance ?: OfflineSyncManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /** 该歌曲是否已经在服务器曲库（serverId 为服务器实例 id） */
    private suspend fun isServerLibrarySong(songId: String): Boolean {
        val serverId = songDao.getSongById(songId)?.serverId
        return !serverId.isNullOrBlank() && serverId !in NON_SERVER_LIBRARY_IDS
    }

    /**
     * 把下载记录转换成服务端 /api/download/add 需要的任务结构。
     * 单曲/多选/一键共用，避免三份实现各自演化出字段缺失。
     */
    private fun toServerTask(entity: DownloadEntity): LemonServerDownloadTask {
        val rawJsonStr = entity.rawMetaJson.orEmpty()
        val rawObj = try {
            if (rawJsonStr.isNotBlank()) JSONObject(rawJsonStr) else null
        } catch (_: Exception) { null }

        val platform = entity.sourcePlatform.ifBlank {
            if (entity.songId.startsWith("lemon_online_")) {
                entity.songId.removePrefix("lemon_online_").substringBefore("_")
            } else "kw"
        }

        return LemonServerDownloadTask(
            id = entity.songId,
            name = entity.title,
            singer = entity.artist,
            source = platform,
            album = rawObj?.optString("album", "").orEmpty(),
            pic = entity.coverUrl,
            platform = platform,
            quality = entity.chosenQuality.ifBlank { "320k" },
            songId = entity.platformSongId.ifBlank {
                rawObj?.optString("songId")?.ifBlank { null }
                    ?: rawObj?.optString("id")
                    ?: entity.songId.removePrefix("lemon_online_${platform}_")
            },
            songmid = rawObj?.optString("songmid", "").orEmpty(),
            hash = rawObj?.optString("hash", "").orEmpty(),
            rid = rawObj?.optString("rid", "").orEmpty(),
            copyrightId = rawObj?.optString("copyrightId", "").orEmpty(),
            img = entity.coverUrl,
            raw = rawJsonStr
        )
    }

    /**
     * 将所有离线期间本地下载的歌曲一键批量推送到柠檬音乐服务端
     *
     * @return Result<Int> 成功推送的任务数
     */
    suspend fun pushAllPendingTasksToServer(): Result<Int> = withContext(Dispatchers.IO) {
        if (!pushMutex.tryLock()) {
            return@withContext Result.failure(Exception("正在同步中，请稍候..."))
        }

        try {
            _isPushing.value = true

            val protocol = LemonMusicProtocol.getActiveOrNew(context)
                ?: return@withContext Result.failure(Exception("未连接柠檬音乐服务器，请先在设置中登录"))

            val pendingTasks = downloadDao.getPendingServerPushList()
            if (pendingTasks.isEmpty()) {
                return@withContext Result.success(0)
            }

            if (!protocol.ensureAuthenticated()) {
                return@withContext Result.failure(Exception("鉴权失败：请检查服务器地址/账号，或重新登录"))
            }

            // 服务器曲库已经有的歌绝不能再推：服务端会为「同名文件已存在」创建
            // await_exist 任务并一直等待确认，手机端只看到提交成功，实际永远排队。
            val alreadyOnServer = pendingTasks.filter { isServerLibrarySong(it.songId) }
            if (alreadyOnServer.isNotEmpty()) {
                downloadDao.updateServerPushStatusBatch(
                    songIds = alreadyOnServer.map { it.songId },
                    status = 0,
                    pushTime = System.currentTimeMillis(),
                    errorMessage = null
                )
                Log.i(TAG, "Skip ${alreadyOnServer.size} pending item(s) already present on server")
            }

            val pushable = pendingTasks.filterNot { it in alreadyOnServer }
            if (pushable.isEmpty()) {
                return@withContext Result.success(0)
            }

            // 分批推送（每批最多 30 首，契合服务端负载）
            var totalPushed = 0
            for (chunk in pushable.chunked(30)) {
                val chunkIds = chunk.map { it.songId }
                val now = System.currentTimeMillis()
                // 先标「推送中」：进程中途被杀时，下次启动会由 init 恢复成待推送；
                // 同一首歌也不会在慢请求期间被第二次点击重复提交。
                downloadDao.updateServerPushStatusBatch(chunkIds, 2, now, null)

                val pushResult = protocol.addServerDownloadTasks(chunk.map { toServerTask(it) })
                if (pushResult.isSuccess) {
                    val count = pushResult.getOrDefault(chunk.size)
                    totalPushed += count
                    downloadDao.updateServerPushStatusBatch(
                        songIds = chunkIds,
                        status = 0,
                        pushTime = System.currentTimeMillis(),
                        errorMessage = null
                    )
                } else {
                    val err = pushResult.exceptionOrNull()?.message ?: "服务端添加下载任务失败"
                    Log.e(TAG, "Failed to push batch: $err")
                    downloadDao.updateServerPushStatusBatch(
                        songIds = chunkIds,
                        status = 3,
                        pushTime = System.currentTimeMillis(),
                        errorMessage = err
                    )
                }
            }

            if (totalPushed > 0) {
                // 触发服务端曲库扫描
                try {
                    protocol.triggerServerScan()
                } catch (e: Exception) {
                    Log.w(TAG, "Trigger server scan failed", e)
                }
            }

            Result.success(totalPushed)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Push all pending tasks failed", e)
            // 异常中断时把仍处于「推送中」的批次落成失败，看板可见、可重试；
            // 进程被杀的情况由 init 的 2->1 恢复兜底。
            runCatching { downloadDao.markAllServerPushStatus(2, 3) }
            Result.failure(e)
        } finally {
            _isPushing.value = false
            pushMutex.unlock()
        }
    }

    /**
     * 将指定的一组离线下载歌曲批量精细化推送到柠檬音乐服务端
     *
     * @param songIds 需要同步推送的歌曲 ID 集合
     * @return Result<Int> 成功推送的任务数
     */
    suspend fun pushSelectedTasksToServer(songIds: Collection<String>): Result<Int> = withContext(Dispatchers.IO) {
        if (songIds.isEmpty()) {
            return@withContext Result.success(0)
        }
        if (!pushMutex.tryLock()) {
            return@withContext Result.failure(Exception("正在同步中，请稍候..."))
        }

        try {
            _isPushing.value = true

            val protocol = LemonMusicProtocol.getActiveOrNew(context)
                ?: return@withContext Result.failure(Exception("未连接柠檬音乐服务器，请先在设置中登录"))

            // 精确按用户勾选的 ID 取记录。旧实现先只查「待同步(1)」再 filter，
            // 勾选里混有已完成/失败记录时会静默丢掉大部分选择。
            val idSet = songIds.distinct()
            val records = idSet.mapNotNull { downloadDao.getDownloadRecord(it) }
            if (records.isEmpty()) {
                return@withContext Result.failure(
                    Exception("所选歌曲没有可推送的在线下载记录（本机扫描/纯本地文件无法自动同步到服务器）")
                )
            }

            if (!protocol.ensureAuthenticated()) {
                return@withContext Result.failure(Exception("鉴权失败：请检查服务器地址/账号，或重新登录"))
            }

            val alreadyOnServer = records.filter { isServerLibrarySong(it.songId) }
            if (alreadyOnServer.isNotEmpty()) {
                downloadDao.updateServerPushStatusBatch(
                    songIds = alreadyOnServer.map { it.songId },
                    status = 0,
                    pushTime = System.currentTimeMillis(),
                    errorMessage = null
                )
            }
            val pushable = records.filterNot { it in alreadyOnServer }
            if (pushable.isEmpty()) {
                return@withContext Result.failure(
                    Exception("所选歌曲均已在服务器曲库，无需重复推送")
                )
            }

            var totalPushed = 0
            val chunks = pushable.chunked(30)
            for (chunk in chunks) {
                val chunkIds = chunk.map { it.songId }
                val now = System.currentTimeMillis()
                downloadDao.updateServerPushStatusBatch(chunkIds, 2, now, null)

                val pushResult = protocol.addServerDownloadTasks(chunk.map { toServerTask(it) })
                if (pushResult.isSuccess) {
                    val count = pushResult.getOrDefault(chunk.size)
                    totalPushed += count
                    downloadDao.updateServerPushStatusBatch(
                        songIds = chunkIds,
                        status = 0,
                        pushTime = System.currentTimeMillis(),
                        errorMessage = null
                    )
                } else {
                    val err = pushResult.exceptionOrNull()?.message ?: "服务端添加下载任务失败"
                    Log.e(TAG, "Failed to push selected batch: $err")
                    downloadDao.updateServerPushStatusBatch(
                        songIds = chunkIds,
                        status = 3,
                        pushTime = System.currentTimeMillis(),
                        errorMessage = err
                    )
                }
            }

            if (totalPushed > 0) {
                try {
                    protocol.triggerServerScan()
                } catch (e: Exception) {
                    Log.w(TAG, "Trigger server scan failed", e)
                }
            }

            Result.success(totalPushed)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Push selected tasks failed", e)
            runCatching { downloadDao.markAllServerPushStatus(2, 3) }
            Result.failure(e)
        } finally {
            _isPushing.value = false
            pushMutex.unlock()
        }
    }

    /**
     * 单曲推送到服务端
     */
    suspend fun pushSingleTaskToServer(songId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!pushMutex.tryLock()) {
            return@withContext Result.failure(Exception("正在同步中，请稍候..."))
        }

        try {
            _isPushing.value = true

            val protocol = LemonMusicProtocol.getActiveOrNew(context)
                ?: return@withContext Result.failure(Exception("未连接柠檬音乐服务器，请先在设置中登录"))

            val entity = downloadDao.getDownloadRecord(songId)
                ?: return@withContext Result.failure(Exception("未找到对应的本地下载记录"))

            if (isServerLibrarySong(songId)) {
                downloadDao.updateServerPushStatus(
                    songId = songId,
                    status = 0,
                    pushTime = System.currentTimeMillis(),
                    errorMessage = null
                )
                return@withContext Result.success(true)
            }

            if (!protocol.ensureAuthenticated()) {
                return@withContext Result.failure(Exception("鉴权失败：请检查服务器地址/账号，或重新登录"))
            }

            downloadDao.updateServerPushStatus(
                songId = songId,
                status = 2,
                pushTime = System.currentTimeMillis(),
                errorMessage = null
            )

            val pushResult = protocol.addServerDownloadTasks(listOf(toServerTask(entity)))
            if (pushResult.isSuccess) {
                downloadDao.updateServerPushStatus(
                    songId = songId,
                    status = 0,
                    pushTime = System.currentTimeMillis(),
                    errorMessage = null
                )
                try {
                    protocol.triggerServerScan()
                } catch (_: Exception) {}
                Result.success(true)
            } else {
                val err = pushResult.exceptionOrNull()?.message ?: "服务端接收失败"
                downloadDao.updateServerPushStatus(
                    songId = songId,
                    status = 3,
                    pushTime = System.currentTimeMillis(),
                    errorMessage = err
                )
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            runCatching {
                downloadDao.updateServerPushStatus(
                    songId = songId,
                    status = 3,
                    pushTime = System.currentTimeMillis(),
                    errorMessage = e.message ?: "推送异常"
                )
            }
            Result.failure(e)
        } finally {
            _isPushing.value = false
            pushMutex.unlock()
        }
    }
}
