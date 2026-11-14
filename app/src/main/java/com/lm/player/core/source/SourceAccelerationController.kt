package com.lm.player.core.source

import android.content.Context
import android.util.Log
import com.lm.player.core.model.ServerConfig
import com.lm.player.core.network.LemonMusicProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 加速总控：网络范围策略 + 服务器链路状态 + QuickJS 会话 + 「已解绑」模式。
 *
 * 任何本地解析失败都只返回失败，由调用方回退；这里不抛到播放/下载主流程。
 */
@android.annotation.SuppressLint("StaticFieldLeak")
object SourceAccelerationController {
    private const val TAG = "AccelerationControl"
    private const val SESSION_RETRY_MS = 60_000L

    /**
     * 取链 / 目录请求的调度模式：
     * - SERVER_ONLY：本地不可用（脚本失效 / 被清除 / 未拉取），只能走服务器；
     * - SERVER_FIRST：手机没有可用外网（本地拿不到平台 CDN），服务器优先，失败再本地；
     * - LOCAL_FIRST：本地音源可用（默认），本地优先，本地失败或 3 秒仍无结果再由服务器兜底；
     * - LOCAL_ONLY：服务器离线或已删除，只走本地。
     */
    enum class Mode { SERVER_ONLY, SERVER_FIRST, LOCAL_FIRST, LOCAL_ONLY }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var context: Context? = null
    @Volatile private var server: ServerConfig? = null
    @Volatile private var protocol: LemonMusicProtocol? = null
    @Volatile private var serverHost: String = ""
    @Volatile private var lastPolicyEvalAt = 0L
    @Volatile private var lastSessionStartAttemptAt = 0L
    @Volatile private var restoring = false

    /** 「已解绑」状态（服务器在 App 内被删除，本地音源独立运行），供界面决定是否继续显示发现页/在线搜索。 */
    private val _detached = MutableStateFlow(false)
    val detached: StateFlow<Boolean> = _detached.asStateFlow()

    val isLost: Boolean get() = ServerLinkState.isLost

    /** 应用级 Context（下载降级提示等无 Context 的调用方使用） */
    fun appContext(): Context? = context

    // ----------------------------------------------------------------------
    // 按需启动（TV 端 2026-11-08 启动提速）
    // ----------------------------------------------------------------------
    //
    // 音源脚本引擎（每个脚本一个 JS 运行时）不再在启动后自动加载，只在真正用到时才加载：
    // 进入发现 / 搜索、播放或下载需要取链的歌曲、发起目录请求、打开设置页的音源页。
    // 只听本地歌时整个进程都不加载引擎，启动与后台播放都省掉这部分 CPU 与内存。
    // 未被需要之前，冷启动缓存恢复、登录后的会话启动、链路恢复 / 请求成功后的自动重试都只记下、不执行。

    @Volatile private var enginesDemanded = false
    @Volatile private var pendingSessionStart = false

    /** 本轮引擎加载（缓存恢复 / 会话启动）是否还在进行中 */
    private val engineBooting = MutableStateFlow(false)

    /** 是否已经有功能需要过音源引擎 */
    val isEngineDemanded: Boolean get() = enginesDemanded

    /** 本机是否缓存了音源脚本（廉价的文件检查，用于在引擎加载前决定界面形态） */
    fun hasCachedScripts(context: Context): Boolean = SourceScriptStore.hasCache(context)

    /** 本机缓存了音源脚本（引擎可按需加载）；启动计数后、每轮引擎加载结束后、清除脚本时刷新 */
    private val _scriptsCached = MutableStateFlow(false)
    val scriptsCached: StateFlow<Boolean> = _scriptsCached.asStateFlow()

    private fun refreshScriptsCached(app: Context) {
        _scriptsCached.value = !purged && SourceScriptStore.hasCache(app)
    }

    /**
     * 本地音源「可用」：引擎已就绪 / 已解绑独立运行 / 本机缓存了脚本（引擎可随时按需加载）。
     * 搜索、发现、歌单、榜单走的本地目录服务是 Kotlin 移植、不需要 JS 引擎，
     * 所以只要本地音源可用就按「本地优先」走目录，不必为了目录先把引擎加载起来。
     */
    fun localSourceConfigured(): Boolean =
        SourceAccelerationSession.isReady() || _detached.value || _scriptsCached.value

    fun demandEngines(reason: String) {
        val c = context ?: return
        demandEngines(c, reason)
    }

    /** 声明需要音源引擎：第一次调用时按当前状态加载（已登录 → 启动会话；未登录 → 从缓存恢复），之后再调用无副作用 */
    fun demandEngines(context: Context, reason: String) {
        init(context)
        synchronized(this) {
            if (enginesDemanded) return
            enginesDemanded = true
        }
        Log.i(TAG, "source engines demanded: $reason")
        val s = server
        val p = protocol
        if (pendingSessionStart && s != null && p != null) {
            pendingSessionStart = false
            launchSessionStart(context.applicationContext, s, p)
        } else {
            restoreFromCache(context)
        }
    }

    /**
     * 需要引擎、并且最多愿意等 [timeoutMs]：已就绪立即返回 true；否则按需加载并等到就绪或本轮加载结束
     * （失败 / 没有缓存脚本）。用于没有可用服务器、只能走本地的场景。
     */
    suspend fun awaitEngines(timeoutMs: Long): Boolean {
        if (SourceAccelerationSession.isReady()) return true
        demandEngines("await")
        withTimeoutOrNull(timeoutMs) {
            combine(SourceAccelerationSession.status, engineBooting) { st, booting ->
                st.isReady || (!booting && st.state != SourceAccelerationSession.State.LOADING)
            }.first { it }
        }
        return SourceAccelerationSession.isReady()
    }

    /**
     * 取链前调用：引擎未就绪则按需加载；有可用服务器时不等（本次走服务器，引擎在后台加载好后下次本地优先），
     * 没有可用服务器（未配置 / 离线）时等引擎就绪，最多 [timeoutMs]。返回引擎此刻是否可用。
     */
    suspend fun ensureEnginesForResolve(timeoutMs: Long = 8_000L): Boolean {
        if (SourceAccelerationSession.isReady()) return true
        demandEngines("resolve")
        val serverUsable = server != null && !ServerLinkState.isLost
        if (serverUsable) return false
        return awaitEngines(timeoutMs)
    }

    /** 本机当前版脚本可以给这台服务器用（没有概要的旧缓存先当作可用，装载时再按服务器核对） */
    private fun localUsableFor(app: Context, serverId: String): Boolean {
        if (!SourceScriptStore.hasSlot(app, SourceScriptStore.Slot.CURRENT)) return false
        val sid = SourceScriptStore.summary(app, SourceScriptStore.Slot.CURRENT)?.serverId
        return sid.isNullOrBlank() || sid == serverId
    }

    /**
     * 已登录时装载引擎（2026-11-13 起）：一律用本机当前版装载，**不再联网下载**（拉取由 [SourceScriptUpdater]
     * 在服务器稳定 2 分钟后进行、下次启动生效）。本机一份可用的都没有时（首次安装 / 超过 12 小时被清除 / 换了服务器），
     * 只能先拉取、再立即装载。
     */
    private fun launchSessionStart(app: Context, server: ServerConfig, protocol: LemonMusicProtocol) {
        lastSessionStartAttemptAt = System.currentTimeMillis()
        if (localUsableFor(app, server.id)) {
            restoreFromCache(app)
            return
        }
        synchronized(this) {
            if (restoring || SourceAccelerationSession.isReady()) return
            restoring = true
            engineBooting.value = true
        }
        SourceScriptUpdater.onLoggedIn(app, server, protocol)
        scope.launch {
            try {
                SourceAccelerationPolicy.evaluate(app, server.serverUrl)
                purged = false
                if (SourceScriptUpdater.pull(manual = false, applyNow = true)) {
                    loadLocal(app)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "first pull + load failed", e)
            } finally {
                restoring = false
                refreshScriptsCached(app)
                engineBooting.value = false
            }
        }
    }

    /**
     * 设置页刷新按钮：立即从服务器拉取（不等 2 分钟），存为待用版、下次启动生效；
     * 本机一份可用的都没有时拉取后立即装载。
     */
    fun manualPull(context: Context) {
        init(context)
        val app = context.applicationContext
        val s = server
        val p = protocol
        if (s == null || p == null) {
            SourceScriptUpdater.pullManually() // 会记下「还没有登录服务器」
            return
        }
        if (!SourceAccelerationSession.isReady() && !localUsableFor(app, s.id)) {
            synchronized(this) { enginesDemanded = true }
            launchSessionStart(app, s, p)
            return
        }
        SourceScriptUpdater.pullManually()
    }

    fun init(context: Context) {
        this.context = context.applicationContext
        SourceScriptUpdater.init(context)
        ServerLinkState.init(context)
        ServerLinkState.listener = { old, new -> onLinkChanged(old, new) }
        ServerLibraryAccelerationResolver.init(context)
        com.lm.player.core.source.catalog.CatalogDiskCache.init(context)
        ServerPresence.start()
    }

    /**
     * 冷启动、登录之前调用：先绑定服务器并开始探活（不依赖本地有没有缓存脚本），
     * 让链路状态、目录网关和资料库模式判定尽快有依据。已绑定（已登录 / 已从缓存恢复）时不重复绑定。
     */
    fun observeServer(context: Context, server: ServerConfig) {
        init(context)
        if (this.server != null) return
        this.server = server
        serverHost = NetworkScopeDetector.hostOf(server.serverUrl)
        ServerLinkState.bind(server.id, server.serverUrl, assumeOnline = true)
    }

    /** 冷启动登录失败：立刻探活一次，并让资料库直接进入本地模式（本进程还没在线过，不需要等待判定时间）。 */
    fun onStartupLoginFailed(server: ServerConfig) {
        ServerPresence.onStartupLoginFailed()
        ServerLinkState.reportFailure(NetworkScopeDetector.hostOf(server.serverUrl), "启动登录失败")
    }

    /** 登录成功后调用：绑定服务器、启动/刷新脚本会话（本地没有脚本时会立即拉取）。 */
    fun start(context: Context, server: ServerConfig, protocol: LemonMusicProtocol) {
        init(context)
        ServerPresence.onLoginSucceeded()
        val app = context.applicationContext
        this.server = server
        this.protocol = protocol
        serverHost = NetworkScopeDetector.hostOf(server.serverUrl)
        lastSessionStartAttemptAt = System.currentTimeMillis()
        // 检测到登录连接：孤儿脚本计时作废（新服务器的脚本会覆盖缓存），连续未登录计数清零
        loggedInThisProcess = true
        purged = false
        SourceScriptStore.markLoginConnected(app)
        SourceScriptStore.clearOrphan(app)
        _detached.value = false
        SourceAccelerationSession.markDetached(false)
        ServerLinkState.bind(server.id, server.serverUrl, assumeOnline = true)
        SourceAccelerationPolicy.onServerRecovered()
        // 2026-11-13：登录后只「拉取」（服务器稳定 2 分钟后、存为待用版、下次启动生效），不再当场替换正在用的脚本
        SourceScriptUpdater.onLoggedIn(app, server, protocol)
        // 按需启动：还没有功能需要引擎时只记下，等第一次需要时再装载
        if (enginesDemanded) {
            if (!SourceAccelerationSession.isReady()) launchSessionStart(app, server, protocol)
        } else {
            pendingSessionStart = true
        }
    }

    suspend fun prepare(context: Context, server: ServerConfig, protocol: LemonMusicProtocol): Boolean {
        demandEngines(context, "prepare")
        start(context, server, protocol)
        return awaitEngines(20_000L)
    }

    /**
     * 启动提速：冷启动时立即调用，只做「连续未登录启动」计数（一次很小的配置写入），不加载脚本引擎；
     * 引擎改由 [restoreFromCache] 在启动闸门（StartupGate）放行后再加载，不和首帧、续播抢 CPU。
     * 计数必须早于本次登录成功，否则会把「本次已登录」误记成一次未登录启动。
     */
    fun recordProcessStart(context: Context) {
        init(context)
        promotePendingOnce(context.applicationContext)
        handleProcessStart(context.applicationContext)
        refreshScriptsCached(context.applicationContext)
    }

    @Volatile private var promoteHandled = false

    /**
     * 冷启动、装载引擎之前：上次运行里下载好的待用版转正为当前版（只改文件名，不耗时）。
     * 每个进程只做一次 —— 本次运行中新下载的待用版要等下一次启动。
     */
    private fun promotePendingOnce(app: Context) {
        synchronized(this) {
            if (promoteHandled) return
            promoteHandled = true
        }
        if (SourceScriptStore.promotePendingIfAny(app)) Log.i(TAG, "pending source scripts promoted at launch")
    }

    /**
     * 冷启动离线恢复：从加密缓存加载上次成功的脚本，不依赖服务器。
     * 服务器已被删除（孤儿脚本）时以「已解绑」模式恢复：重启后不立即删除，见 [SourceScriptStore.cleanupOrphanIfExpired]。
     */
    fun restoreFromCache(context: Context) {
        init(context)
        promotePendingOnce(context.applicationContext)
        if (!handleProcessStart(context.applicationContext)) return
        // 按需启动：还没有功能需要引擎时不加载，第一次需要时由 demandEngines 再调用本函数
        if (!enginesDemanded) return
        // LMApplication（IO 线程）与 MainActivity.ensureRestored（主线程）冷启动时几乎同时调用：检查与置位必须原子
        synchronized(this) {
            if (restoring || SourceAccelerationSession.isReady()) return
            restoring = true
            engineBooting.value = true
        }
        scope.launch {
            try {
                loadLocal(context.applicationContext)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "cache restore failed", e)
            } finally {
                restoring = false
                refreshScriptsCached(context.applicationContext)
                engineBooting.value = false
            }
        }
    }

    /**
     * 用本机当前版装载引擎（上一版作为逐个脚本的退回备用），不访问服务器。
     * 服务器已被删除（孤儿脚本）时以「已解绑」模式装载。
     */
    private suspend fun loadLocal(app: Context) {
        run<Unit> {
                val cached = SourceScriptStore.load(app) ?: return
                val previous = SourceScriptStore.load(app, SourceScriptStore.Slot.PREVIOUS)
                    ?.takeIf { it.serverId.isBlank() || cached.serverId.isBlank() || it.serverId == cached.serverId }
                val db = com.lm.player.core.database.ZdsDatabase.getInstance(app)
                val entity = db.serverDao().getActiveServer()
                    ?.takeIf { it.type == com.lm.player.core.model.ServerType.LEMON_MUSIC }
                    ?: db.serverDao().getAllServers().firstOrNull {
                        it.type == com.lm.player.core.model.ServerType.LEMON_MUSIC &&
                            (cached.serverId.isBlank() || it.id == cached.serverId)
                    }
                val orphan = SourceScriptStore.orphanInfo(app)
                if (entity != null && (cached.serverId.isBlank() || cached.serverId == entity.id)) {
                    val cfg = ServerConfig(
                        id = entity.id,
                        name = entity.name,
                        type = entity.type,
                        serverUrl = entity.serverUrl,
                        username = entity.username,
                        tokenOrApiKey = entity.tokenOrApiKey,
                        saltOrSecret = entity.saltOrSecret,
                        syncMode = entity.syncMode,
                        isCurrentActive = entity.isCurrentActive
                    )
                    if (server == null) {
                        server = cfg
                        serverHost = NetworkScopeDetector.hostOf(cfg.serverUrl)
                        // 冷启动尚未登录：先绑定并立即探活，离线时很快进入 LOST
                        ServerLinkState.bind(cfg.id, cfg.serverUrl, assumeOnline = true)
                    }
                    SourceAccelerationPolicy.evaluate(app, cfg.serverUrl)
                    val result = SourceAccelerationSession.startFromCache(
                        cfg, cached.bundle, savedAt = cached.savedAt, fallback = previous?.bundle
                    )
                    Log.i(TAG, "cache restore: success=${result.isSuccess} sources=${cached.bundle.sources.size}")
                } else if (orphan != null && (cached.serverId.isBlank() || cached.serverId == orphan.first)) {
                    // 服务器已删除：独立运行
                    val cfg = ServerConfig(
                        id = orphan.first,
                        name = "本地音源",
                        type = com.lm.player.core.model.ServerType.LEMON_MUSIC,
                        serverUrl = "",
                        username = "",
                        tokenOrApiKey = ""
                    )
                    ServerLinkState.detach("")
                    SourceAccelerationPolicy.onServerLost()
                    val result = SourceAccelerationSession.startFromCache(
                        cfg, cached.bundle, detached = true, savedAt = cached.savedAt, fallback = previous?.bundle
                    )
                    if (result.isSuccess) _detached.value = true
                    Log.i(TAG, "orphan cache restore (detached): success=${result.isSuccess}")
                }
        }
    }

    /** 本进程内是否登录过服务器（Controller.start 只在登录成功后调用） */
    @Volatile private var loggedInThisProcess = false
    @Volatile private var processStartHandled = false
    @Volatile private var purged = false

    /** 过期后启动时给自动登录留出的等待时间 */
    private const val LOGIN_GRACE_MS = 45_000L

    /**
     * 本地音源脚本的保留期（2026-11-13 用户新规则，取代「连续两次启动未登录就清除」）：
     * 脚本拉取到本地后，只要 12 小时之内再次登录（或服务器在线可达）就不会被清理；超过 12 小时没有登录才清除。
     * 车机经常在没网 / 服务器不可达时启动，旧规则两次就清，本地音源加速太容易丢。
     */
    private const val SCRIPT_KEEP_MS = 12 * 60 * 60 * 1000L

    /** 运行中每小时复查一次（车机可能一直开着不重启） */
    private const val EXPIRY_CHECK_MS = 60 * 60 * 1000L

    /** 上次登录 / 服务器在线的时间已超过保留期 */
    private fun scriptsExpired(app: Context, now: Long = System.currentTimeMillis()): Boolean {
        val last = SourceScriptStore.lastConnectedAt(app)
        return last > 0L && now - last > SCRIPT_KEEP_MS
    }

    /**
     * 冷启动只处理一次保留期检查。
     * @return false 表示脚本已被清除，不应再从缓存恢复
     */
    @Synchronized
    private fun handleProcessStart(app: Context): Boolean {
        if (processStartHandled) return !purged
        processStartHandled = true
        scope.launch {
            // 先给自动登录 45 秒；之后每小时复查一次
            kotlinx.coroutines.delay(LOGIN_GRACE_MS)
            while (!purged) {
                // 服务器此刻在线：视为一次登录连接，续上保留期
                if (server != null && ServerLinkState.state.value.link == ServerLinkState.Link.ONLINE) {
                    SourceScriptStore.markLoginConnected(app)
                } else if (SourceScriptStore.hasCache(app) && scriptsExpired(app)) {
                    purgeLocalSources(app, "超过 12 小时未登录服务器")
                    break
                }
                kotlinx.coroutines.delay(EXPIRY_CHECK_MS)
            }
        }
        return true
    }

    /** 彻底清除本地音源脚本：本地音源加速失效，直到下次登录服务器重新拉取。 */
    private fun purgeLocalSources(app: Context, reason: String) {
        // 12 小时之内登录过（含本进程刚登录）就不清
        if (!scriptsExpired(app)) return
        purged = true
        _scriptsCached.value = false
        Log.w(TAG, "purge local sources: $reason")
        SourceScriptStore.purgeAll(app)
        SourceScriptUpdater.onPurged()
        ResolvedUrlCache.clear()
        _detached.value = false
        scope.launch {
            // 清除与登录几乎同时发生时，不能把登录后新拉起的会话关掉
            if (!loggedInThisProcess) runCatching { SourceAccelerationSession.stop(reason) }
        }
    }

    /** 热启动兜底：划掉任务后进程未被回收、再次打开时会话可能已被 stop，补一次缓存恢复（幂等）。 */
    fun ensureRestored(context: Context) {
        val s = SourceAccelerationSession.status.value
        if (s.isReady || s.state == SourceAccelerationSession.State.LOADING) return
        restoreFromCache(context)
    }

    /**
     * 服务器在 App 内被删除：保留本地音源（不 stop 引擎），解除与服务器的绑定。
     */
    fun detach(context: Context, serverId: String) {
        init(context)
        val app = context.applicationContext
        val boundId = server?.id ?: SourceAccelerationSession.status.value.serverId
        if (serverId.isNotBlank() && boundId.isNotBlank() && boundId != serverId) return
        server = null
        protocol = null
        serverHost = ""
        ServerLinkState.detach("")
        SourceAccelerationPolicy.onServerLost()
        SourceScriptStore.markOrphan(app, serverId.ifBlank { boundId })
        SourceAccelerationSession.markDetached(true)
        _detached.value = SourceAccelerationSession.isReady()
        Log.i(TAG, "detached from server $serverId; local sources kept until cleanup")
    }

    /** 程序关闭时调用（主动退出 / 划掉任务）：孤儿脚本超时未登录则清理。 */
    fun onAppClosing(context: Context) {
        runCatching { SourceScriptStore.cleanupOrphanIfExpired(context.applicationContext) }
            .onFailure { Log.w(TAG, "orphan cleanup failed", it) }
    }

    // ----------------------------------------------------------------------
    // 模式判定
    // ----------------------------------------------------------------------

    /**
     * 取链模式（需要 QuickJS 会话）。
     *
     * 规则（用户 2026-11-07 确认）：本地音源可用时，解析 / 播放 / 下载**一律本地优先、服务器兜底**，
     * 不再区分内网外网、延迟高低；服务器离线 / 已删除时只走本地；本地不可用时只走服务器。
     * 手机本身没有可用网络时（例如只连着没有外网的内网 Wi-Fi），本地拿不到平台 CDN，改为服务器优先。
     */
    fun playbackMode(): Mode {
        val ready = SourceAccelerationSession.isReady()
        val link = ServerLinkState.state.value
        if (!ready) return Mode.SERVER_ONLY
        if (link.isLost) return Mode.LOCAL_ONLY
        val ctx = context
        if (ctx != null && !NetworkScopeDetector.isOnline(ctx)) return Mode.SERVER_FIRST
        return Mode.LOCAL_FIRST
    }

    /**
     * 目录（搜索 / 发现页 / 歌单 / 榜单 / 歌词）模式：与取链同一规则 —— 本地音源可用就本地优先，
     * 本地失败或空结果再问服务器；服务器离线时只走本地。
     */
    fun catalogMode(hasServer: Boolean): Mode {
        if (!hasServer) return Mode.LOCAL_ONLY
        val link = ServerLinkState.state.value
        if (link.isLost) return Mode.LOCAL_ONLY
        val ctx = context
        if (ctx != null && !NetworkScopeDetector.isOnline(ctx)) return Mode.SERVER_FIRST
        // TV 按需启动：目录服务不需要 JS 引擎，本地音源可用（含仅缓存了脚本、引擎尚未加载）即本地优先
        return if (localSourceConfigured()) Mode.LOCAL_FIRST else Mode.SERVER_FIRST
    }

    /** 兼容旧调用：是否应先尝试本地取链。 */
    fun shouldAccelerate(host: String = serverHost): Boolean {
        val mode = playbackMode()
        if (mode != Mode.LOCAL_FIRST && mode != Mode.LOCAL_ONLY) return false
        val ctx = context ?: return false
        if (!NetworkScopeDetector.isOnline(ctx)) return false
        if (mode == Mode.LOCAL_ONLY || serverHost.isBlank()) return true
        return host.isBlank() || NetworkScopeDetector.hostOf(host) == serverHost
    }

    /**
     * 服务器弱网（2026-11-13 服务器歌曲加速）：服务器离线、请求平均耗时超过 800 毫秒、或 2 分钟内最近一次请求失败。
     * 弱网时歌词、封面直接优先用平台的，不等服务器。没有绑定服务器时返回 false。
     */
    fun isServerWeak(): Boolean {
        if (ServerLinkState.isLost) return true
        val host = serverHost
        if (host.isBlank()) return false
        val s = com.lm.player.core.network.ServerLatencyMonitor.get(host) ?: return false
        if (s.ewmaMs > WEAK_LATENCY_MS) return true
        val now = System.currentTimeMillis()
        return s.lastFailureAt > s.lastSuccessAt && now - s.lastFailureAt < 2 * 60_000L
    }

    private const val WEAK_LATENCY_MS = 800L

    /** 不论模式，本地会话是否能取链（服务器路径失败后的兜底用）。 */
    fun canResolveLocally(): Boolean {
        val ctx = context ?: return false
        return SourceAccelerationSession.isReady() && NetworkScopeDetector.isOnline(ctx)
    }

    /**
     * 服务器请求采样（NetworkClientFactory 拦截器 / 设置页探活）。
     * success=false 表示 IO 失败或非柠檬响应，会立即触发一次语义探活。
     */
    fun onServerSample(host: String, elapsedMs: Long, success: Boolean) {
        if (serverHost.isBlank() || NetworkScopeDetector.hostOf(host) != serverHost) return
        if (success) {
            ServerLinkState.reportSuccess(host)
            val now = System.currentTimeMillis()
            if (now - lastPolicyEvalAt >= 5_000L) {
                lastPolicyEvalAt = now
                val c = context
                val s = server
                if (c != null && s != null) {
                    scope.launch { runCatching { SourceAccelerationPolicy.evaluate(c, s.serverUrl) } }
                }
            }
            ensureSessionReady(now)
        } else {
            ServerLinkState.reportFailure(host, if (elapsedMs >= 10_000L) "请求超时" else "请求失败")
        }
    }

    private fun onLinkChanged(old: ServerLinkState.State, new: ServerLinkState.State) {
        when (new.link) {
            ServerLinkState.Link.LOST -> {
                // 只标记，不关闭本地 QuickJS：服务器不可达时本地音源是唯一可用链路
                SourceAccelerationPolicy.onServerLost()
                Log.w(TAG, "server lost (${new.reason}): local sources take over")
                // 2026-11-13：服务器离线后本地音源是唯一出路 —— 本机缓存了脚本就立刻在后台加载引擎，
                // 不等到点歌 / 下载时才加载（按需启动下，服务器歌曲以前根本不会触发加载，只能去等离线服务器超时）
                val c = context
                if (c != null && !SourceAccelerationSession.isReady() && (_scriptsCached.value || SourceScriptStore.hasCache(c))) {
                    demandEngines(c, "server_lost")
                }
            }
            ServerLinkState.Link.ONLINE -> if (old.link == ServerLinkState.Link.LOST) {
                SourceAccelerationPolicy.onServerRecovered()
                val s = server
                val p = protocol
                val c = context
                // 按需启动：引擎还没被需要过时，服务器恢复也不自动启动会话。
                // 2026-11-13：已装载的不重载（更新由 SourceScriptUpdater 在稳定 2 分钟后拉取、下次启动生效）；
                // 还没装载上的，用本机当前版装载（本机没有才拉取后立即装载）
                if (s != null && c != null && enginesDemanded) {
                    scope.launch { runCatching { SourceAccelerationPolicy.evaluate(c, s.serverUrl) } }
                    if (!SourceAccelerationSession.isReady()) {
                        if (p != null) launchSessionStart(c, s, p) else restoreFromCache(c)
                    }
                }
            }
            else -> Unit
        }
    }

    /** 会话未就绪时按 60 秒节流重试（启动时脚本接口偶发失败 / 冷启动服务器短暂不可达）。 */
    private fun ensureSessionReady(now: Long) {
        // 按需启动：引擎还没被需要过时，服务器请求成功也不自动重试启动会话
        if (!enginesDemanded) return
        if (ServerLinkState.isLost) return
        if (SourceAccelerationSession.isReady()) return
        val s = server ?: return
        val p = protocol ?: return
        if (now - lastSessionStartAttemptAt < SESSION_RETRY_MS) return
        // 本机脚本装载过但失败了（脚本本身的问题）：反复重试只会白耗 CPU，等下次启动 / 新版本
        if (SourceAccelerationSession.status.value.state == SourceAccelerationSession.State.FAILED) return
        val c = context ?: return
        launchSessionStart(c, s, p)
    }

    suspend fun stop(reason: String = "exit") {
        SourceAccelerationSession.stop(reason)
        SourceAccelerationPolicy.reset()
        ServerLinkState.reset()
        _detached.value = false
        lastSessionStartAttemptAt = 0L
    }

    fun stopAsync(reason: String) {
        context?.let { onAppClosing(it) }
        scope.launch { stop(reason) }
    }
}
