package com.lm.player.core.source

import android.content.Context
import android.util.Log
import com.lm.player.core.model.AudioQuality
import com.lm.player.core.model.ServerConfig
import com.lm.player.core.model.SourceScriptBundle
import com.lm.player.core.model.SourceScriptEntry
import com.lm.player.core.network.LemonMusicProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 本地音源加速会话。
 *
 * 脚本仅在内存中运行；成功加载后会把 bundle 用 Android Keystore 加密缓存到
 * noBackupFilesDir，供“冷启动时服务器不可达但公网正常”恢复使用。
 *
 * 并发模型：
 * - start/stop 由 [mutex] 串行化，保证引擎表与状态一致；
 * - 取链只读 [engineSnapshot]，不再持全局锁，因此不同引擎/不同歌曲可以并行；
 * - 单个引擎的并发由 [LocalSourceEngine] 的 busy 标记拒绝，避免慢脚本排队拖死所有人。
 */
@android.annotation.SuppressLint("StaticFieldLeak")
object SourceAccelerationSession {

    private const val TAG = "SourceAcceleration"

    /** 播放/下载链路单次本地解析的默认预算。超时只停止等待，不取消 native evaluate。 */
    const val DEFAULT_RESOLVE_BUDGET_MS = 2_500L

    /** 单个脚本初始化上限；只放弃卡住的引擎，不阻塞整个会话启动。 */
    private const val INIT_TIMEOUT_MS = 8_000L

    /** bundle 拉取上限：服务器失联时不能把会话启动协程卡到共享客户端的 300 秒。 */
    private const val BOOTSTRAP_TIMEOUT_MS = 15_000L

    private val QUALITY_ORDER = listOf("flac24bit", "flac", "320k", "128k")

    enum class State { IDLE, LOADING, READY, FAILED }

    data class Status(
        val state: State = State.IDLE,
        val readyCount: Int = 0,
        val totalCount: Int = 0,
        val error: String? = null,
        val serverId: String = "",
        val serverHost: String = "",
        val updatedAt: Long = 0L,
        val loadedFromCache: Boolean = false
    ) {
        val isReady: Boolean get() = state == State.READY && readyCount > 0
    }

    data class LocalResolution(val url: String, val qualityKey: String)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val mutex = Mutex()
    private val resolveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engines = LinkedHashMap<String, LocalSourceEngine>()

    /**
     * 初始化超时（通常是脚本死循环）的脚本 key。
     *
     * 不能只 close：native evaluate 无法安全取消，线程可能继续跑。若控制器每 60 秒重试，
     * 会不断创建新线程。这里把该脚本在本会话内标记为停用，直到 App 重启或脚本 hash/版本变化。
     */
    private val poisonedScripts = mutableSetOf<String>()

    /** 引擎快照：取链路径只读该字段，避免持有全局锁。 */
    @Volatile
    private var engineSnapshot: List<LocalSourceEngine> = emptyList()

    private var contextRef: Context? = null

    fun init(context: Context) {
        contextRef = context.applicationContext
    }

    suspend fun start(
        server: ServerConfig,
        protocol: LemonMusicProtocol,
        force: Boolean = false
    ): Result<Unit> = startInternal(server, force = force, fromCache = false) {
        try {
            protocol.fetchActiveScriptBundle()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 冷启动离线恢复：直接用加密缓存里的 bundle 初始化 QuickJS，不访问服务器。
     */
    suspend fun startFromCache(
        server: ServerConfig,
        bundle: SourceScriptBundle
    ): Result<Unit> {
        if (bundle.sources.isEmpty()) {
            return Result.failure(IllegalStateException("脚本缓存为空"))
        }
        if (isReady()) return Result.success(Unit)
        return startInternal(server, force = true, fromCache = true) {
            Result.success(bundle)
        }
    }

    private suspend fun startInternal(
        server: ServerConfig,
        force: Boolean,
        fromCache: Boolean,
        bundleProvider: suspend () -> Result<SourceScriptBundle>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val ctx = contextRef ?: return@withContext Result.failure(IllegalStateException("加速会话未初始化"))
        mutex.withLock {
            val current = _status.value
            // 缓存恢复时若网络会话已经就绪，直接让网络会话继续用。
            if (fromCache && current.isReady && engineSnapshot.isNotEmpty()) {
                return@withLock Result.success(Unit)
            }
            if (!force && !fromCache && current.isReady && current.serverId == server.id &&
                engineSnapshot.isNotEmpty() && !current.loadedFromCache
            ) {
                return@withLock Result.success(Unit)
            }
            // 刷新时保留旧引擎，等新 bundle 真正就绪后再原子替换；避免一次失败的刷新
            // 把原本可用的 QuickJS 全部清空。
            val hadReady = engines.isNotEmpty() && current.isReady
            if (!hadReady) {
                _status.value = Status(
                    state = State.LOADING,
                    serverId = server.id,
                    serverHost = serverHost(server.serverUrl),
                    updatedAt = System.currentTimeMillis()
                )
            }
            Log.i(TAG, "starting session for ${server.name} (force=$force, keepOld=$hadReady, fromCache=$fromCache)")

            val newEngines = LinkedHashMap<String, LocalSourceEngine>()
            var installed = false
            try {
                // bundleProvider 内部可能是阻塞 OkHttp；放到独立 scope，超时只停止等待，
                // 不让 start 被共享客户端的 300 秒 callTimeout 卡死。
                val fetchDeferred = resolveScope.async { bundleProvider() }
                val fetched = withTimeoutOrNull(BOOTSTRAP_TIMEOUT_MS) { fetchDeferred.await() }
                if (fetched == null) {
                    Log.w(TAG, "bundle fetch timeout after ${BOOTSTRAP_TIMEOUT_MS}ms")
                }
                val bundleResult = fetched
                    ?: Result.failure(IllegalStateException("脚本加载超时(${BOOTSTRAP_TIMEOUT_MS}ms)"))
                if (bundleResult.isFailure) {
                    return@withLock failStart(
                        hadReady,
                        bundleResult.exceptionOrNull() ?: IllegalStateException("脚本加载失败"),
                        server
                    )
                }
                val bundle = bundleResult.getOrThrow()
                Log.i(TAG, "bundle sources=${bundle.sources.size} fromCache=$fromCache")
                if (bundle.sources.isEmpty()) {
                    return@withLock failStart(hadReady, IllegalStateException("没有可用脚本"), server)
                }

                var ready = 0
                var lastError: String? = null
                for (entry in bundle.sources) {
                    if (entry.id.isBlank()) {
                        lastError = "脚本缺少 ID: ${entry.name}"
                        Log.w(TAG, lastError)
                        continue
                    }
                    val entryKey = scriptKey(entry)
                    if (entryKey in poisonedScripts) {
                        lastError = "脚本初始化超时，已停用: ${entry.name}"
                        continue
                    }
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
                    // 不在当前协程直接 withTimeout：取消 native evaluate 会破坏 QuickJS 状态。
                    // 改为独立 scope 求值 + 只停止等待；超时后放弃该引擎并继续加载其他源。
                    val initDeferred = resolveScope.async {
                        runCatching { engine.initialize() }
                            .getOrElse { Result.failure(it) }
                    }
                    val initResult = withTimeoutOrNull(INIT_TIMEOUT_MS) { initDeferred.await() }
                    if (initResult == null) {
                        lastError = "脚本初始化超时: ${entry.name}"
                        Log.w(TAG, lastError)
                        poisonedScripts.add(entryKey)
                        engine.close()
                    } else if (initResult.isSuccess) {
                        newEngines[entry.id] = engine
                        ready++
                    } else {
                        lastError = initResult.exceptionOrNull()?.message
                        engine.close()
                    }
                }
                if (newEngines.isEmpty()) {
                    return@withLock failStart(
                        hadReady,
                        IllegalStateException(lastError ?: "脚本全部加载失败"),
                        server
                    )
                }

                val old = engines.values.toList()
                engines.clear()
                engines.putAll(newEngines)
                engineSnapshot = engines.values.toList()
                _status.value = Status(
                    state = State.READY,
                    readyCount = ready,
                    totalCount = bundle.sources.size,
                    error = null,
                    serverId = server.id,
                    serverHost = serverHost(server.serverUrl),
                    updatedAt = System.currentTimeMillis(),
                    loadedFromCache = fromCache
                )
                installed = true
                old.forEach { runCatching { it.close() } }
                Log.i(TAG, "session ready: $ready/${bundle.sources.size} fromCache=$fromCache")
                if (!fromCache) {
                    runCatching { SourceScriptStore.save(ctx, server.id, bundle) }
                        .onFailure { Log.w(TAG, "save script cache failed", it) }
                }
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "start failed", e)
                failStart(hadReady, e, server)
            } finally {
                if (!installed) {
                    newEngines.values.forEach { runCatching { it.close() } }
                }
            }
        }
    }

    private fun scriptKey(entry: SourceScriptEntry): String =
        "${entry.id}|${entry.hash}|${entry.version}"

    private fun failStart(hadReady: Boolean, e: Throwable, server: ServerConfig): Result<Unit> {
        val now = System.currentTimeMillis()
        _status.value = if (hadReady) {
            // 旧引擎仍然可用：保留 READY，仅记录最近一次刷新错误。
            _status.value.copy(error = e.message, updatedAt = now)
        } else {
            _status.value.copy(
                state = State.FAILED,
                error = e.message,
                serverId = server.id,
                serverHost = serverHost(server.serverUrl),
                updatedAt = now
            )
        }
        return Result.failure(e)
    }

    /**
     * 本地取链（不带预算）。调用方若需要非取消式超时，使用 [resolveMusicUrlWithBudget]。
     */
    suspend fun resolveMusicUrl(
        source: String,
        quality: String,
        musicInfoJson: String
    ): Result<String> {
        val snapshot = engineSnapshot
        if (snapshot.isEmpty()) {
            return Result.failure(IllegalStateException("音源加速未就绪"))
        }
        // 只尝试声明支持该平台的脚本；旧 bundle 的 sources 为 "{}" 时 supports 返回 true。
        val candidates = snapshot.filter { it.supports(source) }
        if (candidates.isEmpty()) {
            return Result.failure(IllegalStateException("没有支持平台 $source 的音源"))
        }
        var last: Throwable? = null
        for (engine in candidates) {
            val r = engine.resolveMusicUrl(source, quality, musicInfoJson)
            if (r.isSuccess) return r
            last = r.exceptionOrNull()
        }
        return Result.failure(last ?: IllegalStateException("本地音源未返回地址"))
    }

    /**
     * 带预算的本地取链。
     *
     * 超时只停止等待，不取消原生 QuickJS 求值（取消会破坏 context，下一次 evaluate 可能 SIGSEGV）。
     * 被放弃的求值会继续占住对应引擎的 busy 标记，后续请求对该引擎立即失败，不会排队 25 秒。
     */
    suspend fun resolveMusicUrlWithBudget(
        source: String,
        quality: String,
        musicInfoJson: String,
        budgetMs: Long = DEFAULT_RESOLVE_BUDGET_MS
    ): Result<String> {
        if (budgetMs <= 0L) return resolveMusicUrl(source, quality, musicInfoJson)
        val deferred = resolveScope.async {
            runCatching { resolveMusicUrl(source, quality, musicInfoJson) }
                .getOrElse { Result.failure(it) }
        }
        val result = withTimeoutOrNull(budgetMs) { deferred.await() }
        if (result == null) {
            Log.w(TAG, "local resolve timeout: source=$source quality=$quality budget=${budgetMs}ms")
            return Result.failure(IllegalStateException("本地音源解析超时(${budgetMs}ms)"))
        }
        return result
    }

    /**
     * 本地音质逐级回退：在总预算内依次尝试目标音质及更低档。
     *
     * 只有“快速失败”才会继续下一档；若上一档是超时/引擎忙，直接停止，避免同一引擎
     * 排队等待。回退只影响本地加速，服务器路径仍各自有完整降级逻辑。
     */
    suspend fun resolveMusicUrlWithQualityFallback(
        source: String,
        preferredQuality: String,
        musicInfoJson: String,
        budgetMs: Long = DEFAULT_RESOLVE_BUDGET_MS
    ): Result<LocalResolution> {
        val normalized = AudioQuality.fromKey(preferredQuality).key
        val startIndex = QUALITY_ORDER.indexOf(normalized).let { if (it < 0) 0 else it }
        val deadline = System.currentTimeMillis() + budgetMs
        var last: Throwable? = null
        for (qualityKey in QUALITY_ORDER.drop(startIndex)) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 250L) break
            val result = resolveMusicUrlWithBudget(source, qualityKey, musicInfoJson, remaining)
            if (result.isSuccess) {
                val url = result.getOrThrow()
                if (!qualityKey.equals(normalized, ignoreCase = true)) {
                    Log.i(TAG, "local quality fallback: $normalized -> $qualityKey")
                }
                return Result.success(LocalResolution(url, qualityKey))
            }
            last = result.exceptionOrNull()
            val message = last?.message.orEmpty()
            if (message.contains("超时") || message.contains("正在解析")) break
        }
        return Result.failure(last ?: IllegalStateException("本地音源未返回地址"))
    }

    fun isReady(): Boolean = _status.value.isReady

    fun serverHost(serverUrl: String): String {
        val raw = serverUrl.trim()
        return raw.substringAfter("://", raw).substringBefore('/').substringBefore('?')
    }

    suspend fun stop(reason: String = "exit") = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old = engines.values.toList()
            engines.clear()
            engineSnapshot = emptyList()
            poisonedScripts.clear()
            _status.value = Status(
                state = State.IDLE,
                serverHost = _status.value.serverHost,
                serverId = _status.value.serverId,
                error = reason,
                updatedAt = System.currentTimeMillis()
            )
            old.forEach { runCatching { it.close() } }
        }
    }
}
