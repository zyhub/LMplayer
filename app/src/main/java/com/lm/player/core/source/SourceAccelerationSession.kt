package com.lm.player.core.source

import android.content.Context
import android.util.Log
import com.lm.player.core.model.ServerConfig
import com.lm.player.core.model.SourceScriptEntry
import com.lm.player.core.network.LemonMusicProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 本地音源加速会话（仅内存）。
 *
 * 每次冷启动抓取 active-bundle，初始化 QuickJS；退出/服务器 LOST 时 close()。
 * 脚本永不写入 Room/磁盘。多个音源各自一个 LocalSourceEngine，失败单源隔离。
 */
@android.annotation.SuppressLint("StaticFieldLeak")
object SourceAccelerationSession {

    private const val TAG = "SourceAcceleration"

    enum class State { IDLE, LOADING, READY, FAILED }

    data class Status(
        val state: State = State.IDLE,
        val readyCount: Int = 0,
        val totalCount: Int = 0,
        val error: String? = null,
        val serverId: String = "",
        val serverHost: String = "",
        val updatedAt: Long = 0L
    ) {
        val isReady: Boolean get() = state == State.READY && readyCount > 0
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val mutex = Mutex()
    private val engines = LinkedHashMap<String, LocalSourceEngine>()
    private var contextRef: Context? = null

    fun init(context: Context) {
        contextRef = context.applicationContext
    }

    suspend fun start(
        server: ServerConfig,
        protocol: LemonMusicProtocol,
        force: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val ctx = contextRef ?: return@withContext Result.failure(IllegalStateException("加速会话未初始化"))
        mutex.withLock {
            if (!force && _status.value.isReady && _status.value.serverId == server.id) {
                return@withLock Result.success(Unit)
            }
            closeLocked()
            _status.value = Status(
                state = State.LOADING,
                serverId = server.id,
                serverHost = serverHost(server.serverUrl),
                updatedAt = System.currentTimeMillis()
            )
            Log.i(TAG, "starting session for ${server.name}")
            try {
                val bundle = protocol.fetchActiveScriptBundle().getOrElse { e ->
                    _status.value = _status.value.copy(state = State.FAILED, error = e.message, updatedAt = System.currentTimeMillis())
                    return@withLock Result.failure(e)
                }
                Log.i(TAG, "bundle sources=${bundle.sources.size}")
                if (bundle.sources.isEmpty()) {
                    val e = IllegalStateException("没有可用脚本")
                    _status.value = _status.value.copy(state = State.FAILED, error = e.message, updatedAt = System.currentTimeMillis())
                    return@withLock Result.failure(e)
                }
                var ready = 0
                var lastError: String? = null
                for (entry in bundle.sources) {
                    if (entry.hash.isNotBlank()) {
                        val actualHash = java.security.MessageDigest.getInstance("SHA-256")
                            .digest(entry.script.toByteArray(Charsets.UTF_8))
                            .joinToString("") { "%02x".format(it) }
                        if (!actualHash.equals(entry.hash.trim(), ignoreCase = true)) {
                            lastError = "脚本哈希校验失败: ${entry.name}"
                            Log.w(TAG, lastError)
                            continue
                        }
                    }
                    val engine = LocalSourceEngine(ctx, entry)
                    val result = engine.initialize()
                    if (result.isSuccess) {
                        engines[entry.id] = engine
                        ready++
                    } else {
                        lastError = result.exceptionOrNull()?.message
                        engine.close()
                    }
                }
                _status.value = _status.value.copy(
                    state = if (ready > 0) State.READY else State.FAILED,
                    readyCount = ready,
                    totalCount = bundle.sources.size,
                    error = if (ready > 0) null else lastError,
                    updatedAt = System.currentTimeMillis()
                )
                if (ready > 0) Result.success(Unit) else Result.failure(IllegalStateException(lastError ?: "脚本全部加载失败"))
            } catch (e: Exception) {
                _status.value = _status.value.copy(state = State.FAILED, error = e.message, updatedAt = System.currentTimeMillis())
                Result.failure(e)
            }
        }
    }

    suspend fun resolveMusicUrl(
        source: String,
        quality: String,
        musicInfoJson: String
    ): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (engines.isEmpty()) return@withLock Result.failure(IllegalStateException("音源加速未就绪"))
            var last: Throwable? = null
            // 支持该平台的引擎优先；不支持也作为最后兜底尝试（脚本内部会判断）
            val ordered = engines.values.sortedByDescending { it.supports(source) }
            for (engine in ordered) {
                val r = engine.resolveMusicUrl(source, quality, musicInfoJson)
                if (r.isSuccess) return@withLock r
                last = r.exceptionOrNull()
            }
            Result.failure(last ?: IllegalStateException("本地音源未返回地址"))
        }
    }

    fun isReady(): Boolean = _status.value.isReady

    fun serverHost(serverUrl: String): String {
        val raw = serverUrl.trim()
        return raw.substringAfter("://", raw).substringBefore('/').substringBefore('?')
    }

    suspend fun stop(reason: String = "exit") = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeLocked()
            _status.value = Status(
                state = State.IDLE,
                serverHost = _status.value.serverHost,
                serverId = _status.value.serverId,
                error = reason,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private fun closeLocked() {
        engines.values.forEach { runCatching { it.close() } }
        engines.clear()
    }

}
