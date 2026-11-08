package com.lm.player.core.source

import android.app.ActivityManager
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地音源加速会话：管理每个脚本的 QuickJS 实例池，负责取链调度。
 *
 * - 脚本只在内存运行；成功加载后用 Keystore 加密缓存（[SourceScriptStore]），供服务器不可达时恢复；
 * - 每个脚本一个 [SourceEnginePool]（手机 2 个实例 / 低内存设备 1 个），请求按优先级排队；
 * - 选引擎：过滤服务器上关闭的平台、脚本未声明 musicUrl 的平台 → 按服务器健康度 + 本地运行统计排序；
 * - 脚本刷新为**增量**：脚本内容未变的实例池原样保留，只重建变化的；
 * - 超时只「停止等待」，不取消 native 求值（取消会破坏 QuickJS 状态）。
 */
@android.annotation.SuppressLint("StaticFieldLeak")
object SourceAccelerationSession {

    private const val TAG = "SourceAcceleration"

    /** 播放/下载链路单次本地解析的默认预算。 */
    const val DEFAULT_RESOLVE_BUDGET_MS = 3_500L

    /** 单个脚本初始化上限（服务端为 20s）。各脚本并行初始化。 */
    private const val INIT_TIMEOUT_MS = 15_000L

    /** bundle 拉取上限：服务器失联时不能把会话启动卡到共享客户端的 300 秒。 */
    private const val BOOTSTRAP_TIMEOUT_MS = 15_000L

    /** 缓存脚本在「列表版本一致」时直接复用；超过该时长强制完整刷新一次（防止脚本内容变了版本号没变）。 */
    private const val FULL_REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L

    val QUALITY_ORDER = listOf("flac24bit", "flac", "320k", "128k")

    enum class State { IDLE, LOADING, READY, FAILED }

    data class Status(
        val state: State = State.IDLE,
        val readyCount: Int = 0,
        val totalCount: Int = 0,
        val error: String? = null,
        val serverId: String = "",
        val serverHost: String = "",
        val updatedAt: Long = 0L,
        val loadedFromCache: Boolean = false,
        /** 服务器已删除/解绑，脚本独立运行 */
        val detached: Boolean = false,
        /** 正在从服务器拉取 / 初始化脚本（含已就绪后的后台刷新） */
        val pulling: Boolean = false,
        /** 拉取进度：已完成的脚本数 / 本次脚本总数（总数为 0 表示还在下载脚本包） */
        val pullDone: Int = 0,
        val pullTotal: Int = 0
    ) {
        val isReady: Boolean get() = state == State.READY && readyCount > 0

        /** 拉取进度 0~1：下载脚本包阶段固定 10%，之后按脚本初始化完成数推进 */
        val pullProgress: Float get() = when {
            !pulling -> if (isReady) 1f else 0f
            pullTotal <= 0 -> 0.1f
            else -> (0.1f + 0.9f * pullDone.toFloat() / pullTotal).coerceIn(0.1f, 1f)
        }
    }

    data class LocalResolution(val url: String, val qualityKey: String, val sourceId: String = "")

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val mutex = Mutex()
    private val resolveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pools = LinkedHashMap<String, SourceEnginePool>()

    /** 初始化超时/反复卡死的脚本（id|hash），本进程内不再加载，直到脚本变化。 */
    private val poisonedScripts = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var poolSnapshot: List<SourceEnginePool> = emptyList()

    /** 当前已安装 bundle（含每个脚本 sha256），用于增量对比。 */
    @Volatile
    private var currentBundle: SourceScriptBundle? = null

    /** 当前脚本若来自加密缓存：缓存写入时间；网络加载后为 0。 */
    @Volatile
    private var cachedSavedAt = 0L

    /** 服务器给出的平台健康分：sourceId → (platform → score) */
    @Volatile
    private var healthScores: Map<String, Map<String, Int>> = emptyMap()

    /** 服务器上用户关闭的平台：sourceId → platforms */
    @Volatile
    private var disabledPlatforms: Map<String, Set<String>> = emptyMap()

    private class RunStat(@Volatile var successEwma: Double = 0.5, @Volatile var samples: Int = 0, @Volatile var lastFailAt: Long = 0L)

    /** 本地运行统计：sourceId|platform → 成功率 EWMA */
    private val runStats = ConcurrentHashMap<String, RunStat>()

    private var contextRef: Context? = null

    fun init(context: Context) {
        contextRef = context.applicationContext
    }

    private fun poolSize(ctx: Context): Int = runCatching {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.isLowRamDevice || am.memoryClass < 128) 1 else 2
    }.getOrDefault(1)

    // ======================================================================
    // 启动 / 刷新
    // ======================================================================

    suspend fun start(
        server: ServerConfig,
        protocol: LemonMusicProtocol,
        force: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val current = _status.value
        val sameServerReady = current.isReady && current.serverId == server.id && poolSnapshot.isNotEmpty()
        if (!force && sameServerReady) {
            val now = System.currentTimeMillis()
            if (!current.loadedFromCache && !current.detached) return@withContext Result.success(Unit)
            // 冷启动从缓存恢复的会话：先用轻量的 /api/source/list 核对「激活脚本 id+版本」，
            // 没变就不下载、不重建引擎；缓存超过 24 小时才强制完整刷新一次。
            val cacheFresh = cachedSavedAt > 0L && now - cachedSavedAt < FULL_REFRESH_INTERVAL_MS
            if (cacheFresh) {
                val unchanged = withTimeoutOrNull(8_000L) {
                    runCatching { activeListMatchesCurrent(protocol) }.getOrDefault(false)
                } ?: false
                if (unchanged) {
                    _status.value = _status.value.copy(loadedFromCache = false, detached = false, updatedAt = now, error = null)
                    Log.i(TAG, "cached scripts verified against server list, skip reload")
                    return@withContext Result.success(Unit)
                }
            }
        }
        startInternal(server, force = force, fromCache = false) {
            try {
                protocol.fetchActiveScriptBundle()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private suspend fun activeListMatchesCurrent(protocol: LemonMusicProtocol): Boolean {
        val bundle = currentBundle ?: return false
        val list = protocol.fetchSourceList().getOrNull() ?: return false
        val active = list.filter { it.isActive }.associate { it.id to it.version }
        val installed = bundle.sources.associate { it.id to it.version }
        return active.isNotEmpty() && active == installed
    }

    /** 冷启动离线恢复：直接用加密缓存里的 bundle 初始化，不访问服务器。 */
    suspend fun startFromCache(
        server: ServerConfig,
        bundle: SourceScriptBundle,
        detached: Boolean = false,
        savedAt: Long = 0L
    ): Result<Unit> {
        if (bundle.sources.isEmpty()) return Result.failure(IllegalStateException("脚本缓存为空"))
        if (isReady()) return Result.success(Unit)
        val result = startInternal(server, force = true, fromCache = true) { Result.success(bundle) }
        if (result.isSuccess) {
            cachedSavedAt = savedAt
            if (detached) _status.value = _status.value.copy(detached = true)
        }
        return result
    }

    private suspend fun startInternal(
        server: ServerConfig,
        force: Boolean,
        fromCache: Boolean,
        bundleProvider: suspend () -> Result<SourceScriptBundle>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val ctx = contextRef ?: return@withContext Result.failure(IllegalStateException("加速会话未初始化"))
        try { mutex.withLock {
            val current = _status.value
            if (fromCache && current.isReady && poolSnapshot.isNotEmpty()) {
                return@withLock Result.success(Unit)
            }
            val hadReady = pools.isNotEmpty() && current.isReady
            if (!hadReady) {
                _status.value = Status(
                    state = State.LOADING,
                    serverId = server.id,
                    serverHost = serverHost(server.serverUrl),
                    updatedAt = System.currentTimeMillis(),
                    pulling = true
                )
            } else {
                _status.update { it.copy(pulling = true, pullDone = 0, pullTotal = 0) }
            }
            Log.i(TAG, "starting session for ${server.name} (force=$force, keepOld=$hadReady, fromCache=$fromCache)")

            val fetchDeferred = resolveScope.async { bundleProvider() }
            val fetched = withTimeoutOrNull(BOOTSTRAP_TIMEOUT_MS) { fetchDeferred.await() }
            val bundleResult = fetched
                ?: Result.failure(IllegalStateException("脚本加载超时(${BOOTSTRAP_TIMEOUT_MS}ms)"))
            if (bundleResult.isFailure) {
                return@withLock failStart(hadReady, bundleResult.exceptionOrNull() ?: IllegalStateException("脚本加载失败"), server)
            }
            val rawBundle = bundleResult.getOrThrow()
            if (rawBundle.sources.isEmpty()) {
                return@withLock failStart(hadReady, IllegalStateException("没有可用脚本"), server)
            }
            // 统一补上 sha256，用于增量对比与缓存校验
            val bundle = rawBundle.copy(sources = rawBundle.sources.map { e ->
                if (e.hash.isBlank()) e.copy(hash = SourceScriptStore.sha256(e.script)) else e
            }.distinctBy { it.id }) // 同 ID 的重复条目只取第一个：否则会多建一个实例池，且永远不会被关闭

            val maxSize = poolSize(ctx)
            val keep = LinkedHashMap<String, SourceEnginePool>()
            val toInit = ArrayList<SourceScriptEntry>()
            var lastError: String? = null
            for (entry in bundle.sources) {
                if (entry.id.isBlank()) {
                    lastError = "脚本缺少 ID: ${entry.name}"
                    continue
                }
                if (scriptKey(entry) in poisonedScripts) {
                    lastError = "脚本初始化超时，已停用: ${entry.name}"
                    continue
                }
                val existing = pools[entry.id]
                if (existing != null && !existing.closed && !existing.isPoisoned && existing.entry.hash == entry.hash) {
                    keep[entry.id] = existing
                } else {
                    toInit.add(entry)
                }
            }

            _status.update { it.copy(pulling = true, pullDone = keep.size, pullTotal = keep.size + toInit.size) }
            // 并行初始化变化的脚本：总耗时 ≈ 最慢的一个，而不是逐个累加
            val inited = toInit.map { entry ->
                resolveScope.async {
                    val pool = SourceEnginePool(ctx, entry, maxSize, resolveScope, INIT_TIMEOUT_MS)
                    val r = pool.start()
                    _status.update { it.copy(pullDone = (it.pullDone + 1).coerceAtMost(it.pullTotal)) }
                    if (r.isFailure) {
                        val msg = r.exceptionOrNull()?.message.orEmpty()
                        if (msg.contains("超时")) poisonedScripts.add(scriptKey(entry))
                        pool.close()
                        entry to (null to msg)
                    } else {
                        entry to (pool to null)
                    }
                }
            }.awaitAll()

            val newPools = LinkedHashMap<String, SourceEnginePool>()
            for (entry in bundle.sources) {
                val kept = keep[entry.id]
                if (kept != null) {
                    newPools[entry.id] = kept
                    continue
                }
                val (pool, err) = inited.firstOrNull { it.first.id == entry.id }?.second ?: continue
                if (pool != null) newPools[entry.id] = pool
                if (err != null) lastError = err
            }
            if (newPools.isEmpty()) {
                return@withLock failStart(hadReady, IllegalStateException(lastError ?: "脚本全部加载失败"), server)
            }

            val removed = pools.filterKeys { it !in newPools.keys || newPools[it] !== pools[it] }.values.toList()
            pools.clear()
            pools.putAll(newPools)
            poolSnapshot = pools.values.toList()
            currentBundle = bundle
            if (!fromCache) cachedSavedAt = 0L
            _status.value = Status(
                state = State.READY,
                readyCount = newPools.size,
                totalCount = bundle.sources.size,
                error = null,
                serverId = server.id,
                serverHost = serverHost(server.serverUrl),
                updatedAt = System.currentTimeMillis(),
                loadedFromCache = fromCache
            )
            removed.forEach { runCatching { it.close() } }
            Log.i(TAG, "session ready: ${newPools.size}/${bundle.sources.size} (kept=${keep.size}, inited=${toInit.size}) fromCache=$fromCache")
            if (!fromCache) {
                runCatching { SourceScriptStore.save(ctx, server.id, bundle) }
                    .onFailure { Log.w(TAG, "save script cache failed", it) }
            }
            refreshRanking(server.id)
            Result.success(Unit)
        } } finally {
            // 取消 / 异常退出时也要收起「拉取中」，否则状态环会一直停在黄色
            if (_status.value.pulling) _status.update { it.copy(pulling = false) }
        }
    }

    private fun scriptKey(entry: SourceScriptEntry): String =
        "${entry.id}|${entry.hash.ifBlank { SourceScriptStore.sha256(entry.script) }}|${entry.version}"

    private fun failStart(hadReady: Boolean, e: Throwable, server: ServerConfig): Result<Unit> {
        val now = System.currentTimeMillis()
        _status.value = if (hadReady) {
            // 旧引擎仍然可用：保留 READY，仅记录最近一次刷新错误。
            _status.value.copy(error = e.message, updatedAt = now, pulling = false)
        } else {
            _status.value.copy(
                state = State.FAILED,
                error = e.message,
                serverId = server.id,
                serverHost = serverHost(server.serverUrl),
                updatedAt = now,
                pulling = false
            )
        }
        return Result.failure(e)
    }

    /** 从已落库的服务器音源状态刷新健康分与平台开关（服务器删除后保留最后一次的结果）。 */
    suspend fun refreshRanking(serverId: String) {
        val entities = runCatching { ServerSourceRepository.getCachedSources(serverId) }.getOrDefault(emptyList())
        if (entities.isEmpty()) return
        val scores = HashMap<String, Map<String, Int>>()
        val disabled = HashMap<String, Set<String>>()
        entities.forEach { e ->
            val perPlatform = HashMap<String, Int>()
            ServerSourceRepository.parseHealthPlatforms(e.healthPlatformsJson).forEach { p ->
                perPlatform[p.id.lowercase()] = levelScore(p.level)
            }
            if (perPlatform.isEmpty() && e.healthLevel.equals("preview", true)) perPlatform["*"] = -30
            scores[e.sourceId] = perPlatform
            disabled[e.sourceId] = ServerSourceRepository.parseStringList(e.disabledPlatformsJson).map { it.lowercase() }.toSet()
        }
        healthScores = scores
        disabledPlatforms = disabled
    }

    private fun levelScore(level: String): Int = when (level.lowercase()) {
        "ok" -> 100
        "watch" -> -10
        "mixed" -> -20
        "preview" -> -100
        else -> 0
    }

    // ======================================================================
    // 取链
    // ======================================================================

    /** 该平台可用于 [action] 的实例池，按「服务器健康分 + 本地运行统计」排序（对齐服务端 resolveCandidates）。 */
    private fun candidates(platform: String, action: String): List<SourceEnginePool> {
        val p = platform.lowercase()
        val alias = if (p == "qq") "tx" else p
        val list = poolSnapshot.filter { pool ->
            !pool.closed && !pool.isPoisoned && pool.supports(alias, action) &&
                disabledPlatforms[pool.sourceId]?.let { alias in it || (alias == "tx" && "qq" in it) } != true
        }
        if (list.size <= 1) return list
        return list.withIndex().sortedWith(
            compareByDescending<IndexedValue<SourceEnginePool>> { score(it.value.sourceId, alias) }.thenBy { it.index }
        ).map { it.value }
    }

    private fun score(sourceId: String, platform: String): Double {
        val server = healthScores[sourceId]?.let { it[platform] ?: it["*"] } ?: 0
        val stat = runStats["$sourceId|$platform"]
        val local = if (stat == null || stat.samples < 2) 0.0 else (stat.successEwma - 0.5) * 120.0
        val recentFailPenalty = if (stat != null && System.currentTimeMillis() - stat.lastFailAt < 60_000L) -15.0 else 0.0
        return server + local + recentFailPenalty
    }

    private fun recordStat(sourceId: String, platform: String, success: Boolean) {
        val stat = runStats.getOrPut("$sourceId|${platform.lowercase()}") { RunStat() }
        stat.successEwma = stat.successEwma * 0.7 + (if (success) 0.3 else 0.0)
        stat.samples++
        if (!success) stat.lastFailAt = System.currentTimeMillis()
    }

    /** 是否有脚本支持该平台取链。 */
    fun canResolve(platform: String): Boolean = isReady() && candidates(platform, "musicUrl").isNotEmpty()

    /**
     * 在截止时间内依次尝试各脚本取链。每个脚本最多分到剩余预算的 60%（只剩一个时给全部），
     * 前一个卡住时不会把后面的脚本饿死。
     */
    suspend fun resolveMusicUrl(
        source: String,
        quality: String,
        musicInfoJson: String,
        priority: ResolvePriority = ResolvePriority.PLAY,
        deadline: Long = System.currentTimeMillis() + DEFAULT_RESOLVE_BUDGET_MS,
        /** 结果校验（音质/可播）：返回 false 时视为本脚本失败，继续试下一个脚本（对齐服务端「换音源」） */
        accept: (suspend (String) -> Boolean)? = null
    ): Result<LocalResolution> {
        if (poolSnapshot.isEmpty()) return Result.failure(IllegalStateException("音源加速未就绪"))
        val list = candidates(source, "musicUrl").filter { it.primary?.supportsQuality(source, quality) != false }
        if (list.isEmpty()) return Result.failure(IllegalStateException("没有支持平台 $source / $quality 的音源"))
        var last: Throwable? = null
        for ((index, pool) in list.withIndex()) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 250L) break
            val left = list.size - index
            val slice = if (left > 1) maxOf((remaining * 0.6).toLong(), minOf(remaining, 2_500L)) else remaining
            val deferred = resolveScope.async {
                pool.withEngine(priority, slice) { engine ->
                    engine.resolveMusicUrl(source, quality, musicInfoJson)
                } ?: Result.failure(IllegalStateException("音源实例繁忙"))
            }
            val result = withTimeoutOrNull(slice) { deferred.await() }
            if (result == null) {
                last = IllegalStateException("本地音源解析超时(${slice}ms)")
                recordStat(pool.sourceId, source, false)
                continue
            }
            if (result.isSuccess) {
                val url = result.getOrThrow()
                if (accept != null && !accept(url)) {
                    last = IllegalStateException("音源 ${pool.sourceName} 返回的链接不满足 $quality")
                    continue
                }
                recordStat(pool.sourceId, source, true)
                return Result.success(LocalResolution(url, quality, pool.sourceId))
            }
            last = result.exceptionOrNull()
            if (last?.message?.contains("繁忙") != true) recordStat(pool.sourceId, source, false)
        }
        return Result.failure(last ?: IllegalStateException("本地音源未返回地址"))
    }

    /** 兼容旧调用：带预算的单音质取链。 */
    suspend fun resolveMusicUrlWithBudget(
        source: String,
        quality: String,
        musicInfoJson: String,
        budgetMs: Long = DEFAULT_RESOLVE_BUDGET_MS,
        priority: ResolvePriority = ResolvePriority.PLAY
    ): Result<String> =
        resolveMusicUrl(source, quality, musicInfoJson, priority, System.currentTimeMillis() + budgetMs.coerceAtLeast(300L))
            .map { it.url }

    /**
     * 本地音质逐级回退：在总预算内依次尝试目标音质及更低档。
     */
    suspend fun resolveMusicUrlWithQualityFallback(
        source: String,
        preferredQuality: String,
        musicInfoJson: String,
        budgetMs: Long = DEFAULT_RESOLVE_BUDGET_MS,
        priority: ResolvePriority = ResolvePriority.PLAY
    ): Result<LocalResolution> {
        val deadline = System.currentTimeMillis() + budgetMs
        val normalized = AudioQuality.fromKey(preferredQuality).key
        var last: Throwable? = null
        for (qualityKey in qualitiesFrom(normalized)) {
            if (deadline - System.currentTimeMillis() <= 250L) break
            val result = resolveMusicUrl(source, qualityKey, musicInfoJson, priority, deadline)
            if (result.isSuccess) {
                if (!qualityKey.equals(normalized, ignoreCase = true)) Log.i(TAG, "local quality fallback: $normalized -> $qualityKey")
                return result
            }
            last = result.exceptionOrNull()
        }
        return Result.failure(last ?: IllegalStateException("本地音源未返回地址"))
    }

    fun qualitiesFrom(preferred: String): List<String> {
        val normalized = AudioQuality.fromKey(preferred).key
        val start = QUALITY_ORDER.indexOf(normalized).let { if (it < 0) 0 else it }
        return QUALITY_ORDER.drop(start)
    }

    /**
     * 调用脚本的其它动作（lyric / pic）。返回 handler 结果 JSON 文本。
     */
    suspend fun invokeAction(
        platform: String,
        action: String,
        musicInfo: JSONObject,
        budgetMs: Long,
        priority: ResolvePriority = ResolvePriority.PREFETCH
    ): Result<String> {
        val list = candidates(platform, action).filter { it.primary?.supports(platform, action) == true }
        if (list.isEmpty()) return Result.failure(IllegalStateException("没有支持 $action 的音源"))
        val deadline = System.currentTimeMillis() + budgetMs
        var last: Throwable? = null
        for (pool in list) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 250L) break
            val deferred = resolveScope.async {
                pool.withEngine(priority, remaining) { engine ->
                    engine.invoke(action, platform, JSONObject().put("musicInfo", musicInfo))
                } ?: Result.failure(IllegalStateException("音源实例繁忙"))
            }
            val result = withTimeoutOrNull(remaining) { deferred.await() } ?: break
            if (result.isSuccess) return result
            last = result.exceptionOrNull()
        }
        return Result.failure(last ?: IllegalStateException("音源未返回结果"))
    }

    fun isReady(): Boolean = _status.value.isReady

    fun markDetached(detached: Boolean) {
        val s = _status.value
        if (s.detached != detached) _status.value = s.copy(detached = detached, updatedAt = System.currentTimeMillis())
    }

    fun serverHost(serverUrl: String): String {
        val raw = serverUrl.trim()
        return raw.substringAfter("://", raw).substringBefore('/').substringBefore('?')
    }

    suspend fun stop(reason: String = "exit") = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old = pools.values.toList()
            pools.clear()
            poolSnapshot = emptyList()
            currentBundle = null
            cachedSavedAt = 0L
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
