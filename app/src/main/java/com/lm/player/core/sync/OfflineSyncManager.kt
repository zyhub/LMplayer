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
 * 1. 追踪离线或纯本地模式下下载到本机的歌曲 (serverPushStatus == 1)；
 * 2. 当检测到连接并登录柠檬音乐服务端后，支持一键或自动批量推送到 NAS 服务端下载 (/api/download/add)；
 * 3. 推送成功后自动标记已同步并触发服务端曲库扫描，实现 NAS 与手机本地曲库全链路闭环互通。
 */
class OfflineSyncManager private constructor(private val context: Context) {

    private val TAG = "OfflineSyncManager"
    private val database = ZdsDatabase.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val pushMutex = Mutex()

    private val _pendingPushCount = MutableStateFlow(0)
    val pendingPushCount: StateFlow<Int> = _pendingPushCount.asStateFlow()

    private val _pendingPushList = MutableStateFlow<List<DownloadEntity>>(emptyList())
    val pendingPushList: StateFlow<List<DownloadEntity>> = _pendingPushList.asStateFlow()

    private val _isPushing = MutableStateFlow(false)
    val isPushing: StateFlow<Boolean> = _isPushing.asStateFlow()

    init {
        // 反应式监听待推送任务数量与任务列表变动
        scope.launch {
            database.downloadDao().getPendingServerPushCountFlow().collect { count ->
                _pendingPushCount.value = count
            }
        }
        scope.launch {
            database.downloadDao().getPendingServerPushFlow().collect { list ->
                _pendingPushList.value = list
            }
        }
    }

    companion object {
        @Volatile
        private var instance: OfflineSyncManager? = null

        fun getInstance(context: Context): OfflineSyncManager {
            return instance ?: synchronized(this) {
                instance ?: OfflineSyncManager(context.applicationContext).also { instance = it }
            }
        }
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

            val pendingTasks = database.downloadDao().getPendingServerPushList()
            if (pendingTasks.isEmpty()) {
                return@withContext Result.success(0)
            }

            protocol.ensureAuthenticated()

            // 构造符合柠檬服务端规范的下载任务列表
            val serverTasks = pendingTasks.map { entity ->
                val rawJsonStr = entity.rawMetaJson.orEmpty()
                val rawObj = try {
                    if (rawJsonStr.isNotBlank()) JSONObject(rawJsonStr) else null
                } catch (_: Exception) { null }

                val platform = entity.sourcePlatform.ifBlank {
                    if (entity.songId.startsWith("lemon_online_")) {
                        entity.songId.removePrefix("lemon_online_").substringBefore("_")
                    } else "kw"
                }

                LemonServerDownloadTask(
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

            // 分批推送（每批最多 30 首，契合服务端负载）
            var totalPushed = 0
            val chunks = serverTasks.chunked(30)
            for (chunk in chunks) {
                val pushResult = protocol.addServerDownloadTasks(chunk)
                val chunkIds = chunk.map { it.id }
                val now = System.currentTimeMillis()
                if (pushResult.isSuccess) {
                    val count = pushResult.getOrDefault(chunk.size)
                    totalPushed += count
                    database.downloadDao().updateServerPushStatusBatch(
                        songIds = chunkIds,
                        status = 0,
                        pushTime = now,
                        errorMessage = null
                    )
                } else {
                    val err = pushResult.exceptionOrNull()?.message ?: "服务端添加下载任务失败"
                    Log.e(TAG, "Failed to push batch: $err")
                    database.downloadDao().updateServerPushStatusBatch(
                        songIds = chunkIds,
                        status = 3,
                        pushTime = now,
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

            val idSet = songIds.toSet()
            val allPending = database.downloadDao().getPendingServerPushList()
            val matchingTasks = allPending.filter { it.songId in idSet }.ifEmpty {
                idSet.mapNotNull { database.downloadDao().getDownloadRecord(it) }
            }

            if (matchingTasks.isEmpty()) {
                return@withContext Result.success(0)
            }

            protocol.ensureAuthenticated()

            val serverTasks = matchingTasks.map { entity ->
                val rawJsonStr = entity.rawMetaJson.orEmpty()
                val rawObj = try {
                    if (rawJsonStr.isNotBlank()) JSONObject(rawJsonStr) else null
                } catch (_: Exception) { null }

                val platform = entity.sourcePlatform.ifBlank {
                    if (entity.songId.startsWith("lemon_online_")) {
                        entity.songId.removePrefix("lemon_online_").substringBefore("_")
                    } else "kw"
                }

                LemonServerDownloadTask(
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

            var totalPushed = 0
            val chunks = serverTasks.chunked(30)
            for (chunk in chunks) {
                val pushResult = protocol.addServerDownloadTasks(chunk)
                val chunkIds = chunk.map { it.id }
                val now = System.currentTimeMillis()
                if (pushResult.isSuccess) {
                    val count = pushResult.getOrDefault(chunk.size)
                    totalPushed += count
                    database.downloadDao().updateServerPushStatusBatch(
                        songIds = chunkIds,
                        status = 0,
                        pushTime = now,
                        errorMessage = null
                    )
                } else {
                    val err = pushResult.exceptionOrNull()?.message ?: "服务端添加下载任务失败"
                    Log.e(TAG, "Failed to push selected batch: $err")
                    database.downloadDao().updateServerPushStatusBatch(
                        songIds = chunkIds,
                        status = 3,
                        pushTime = now,
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
            val protocol = LemonMusicProtocol.getActiveOrNew(context)
                ?: return@withContext Result.failure(Exception("未连接柠檬音乐服务器，请先在设置中登录"))

            val entity = database.downloadDao().getDownloadRecord(songId)
                ?: return@withContext Result.failure(Exception("未找到对应的本地下载记录"))

            protocol.ensureAuthenticated()

            val rawJsonStr = entity.rawMetaJson.orEmpty()
            val rawObj = try {
                if (rawJsonStr.isNotBlank()) JSONObject(rawJsonStr) else null
            } catch (_: Exception) { null }

            val platform = entity.sourcePlatform.ifBlank {
                if (entity.songId.startsWith("lemon_online_")) {
                    entity.songId.removePrefix("lemon_online_").substringBefore("_")
                } else "kw"
            }

            val task = LemonServerDownloadTask(
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

            val pushResult = protocol.addServerDownloadTasks(listOf(task))
            if (pushResult.isSuccess) {
                database.downloadDao().updateServerPushStatus(
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
                database.downloadDao().updateServerPushStatus(
                    songId = songId,
                    status = 3,
                    pushTime = System.currentTimeMillis(),
                    errorMessage = err
                )
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        } finally {
            pushMutex.unlock()
        }
    }
}
