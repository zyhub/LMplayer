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
import kotlinx.coroutines.launch

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

    fun init(context: Context) {
        this.context = context.applicationContext
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
        scope.launch {
            try {
                SourceAccelerationPolicy.evaluate(app, server.serverUrl)
                SourceAccelerationSession.start(server, protocol)
            } catch (e: Exception) {
                Log.w(TAG, "start failed", e)
            }
        }
    }

    suspend fun prepare(context: Context, server: ServerConfig, protocol: LemonMusicProtocol): Boolean {
        start(context, server, protocol)
        return SourceAccelerationSession.start(server, protocol).isSuccess
    }

    /**
     * 冷启动离线恢复：从加密缓存加载上次成功的脚本，不依赖服务器。
     * 服务器已被删除（孤儿脚本）时以「已解绑」模式恢复：重启后不立即删除，见 [SourceScriptStore.cleanupOrphanIfExpired]。
     */
    fun restoreFromCache(context: Context) {
        init(context)
        if (!handleProcessStart(context.applicationContext)) return
        // LMApplication（IO 线程）与 MainActivity.ensureRestored（主线程）冷启动时几乎同时调用：检查与置位必须原子
        synchronized(this) {
            if (restoring || SourceAccelerationSession.isReady()) return
            restoring = true
        }
        scope.launch {
            try {
                val app = context.applicationContext
                val cached = SourceScriptStore.load(app) ?: return@launch
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
                    val result = SourceAccelerationSession.startFromCache(cfg, cached.bundle, savedAt = cached.savedAt)
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
                    val result = SourceAccelerationSession.startFromCache(cfg, cached.bundle, detached = true, savedAt = cached.savedAt)
                    if (result.isSuccess) _detached.value = true
                    Log.i(TAG, "orphan cache restore (detached): success=${result.isSuccess}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "cache restore failed", e)
            } finally {
                restoring = false
            }
        }
    }

    /** 本进程内是否登录过服务器（Controller.start 只在登录成功后调用） */
    @Volatile private var loggedInThisProcess = false
    @Volatile private var processStartHandled = false
    @Volatile private var purged = false

    /** 第 2 次连续未登录的启动：给自动登录留出的等待时间 */
    private const val LOGIN_GRACE_MS = 45_000L

    /**
     * 冷启动只处理一次「连续启动未登录」计数（需求：连续两次启动都未登录服务器 → 彻底清除本地音源脚本）。
     * @return false 表示脚本已被清除，不应再从缓存恢复
     */
    // 同步：两个入口并发调用时若各记一次启动，「连续未登录」会多算一次，只启动一次就把脚本清掉
    @Synchronized
    private fun handleProcessStart(app: Context): Boolean {
        if (processStartHandled) return !purged
        processStartHandled = true
        val previousNoLogin = SourceScriptStore.recordLaunch(app)
        if (previousNoLogin >= 2) {
            // 上一次（第 2 次）启动在检查前就被关掉了：直接清除
            purgeLocalSources(app, "连续启动未登录")
            return false
        }
        if (previousNoLogin >= 1) {
            scope.launch {
                kotlinx.coroutines.delay(LOGIN_GRACE_MS)
                if (!loggedInThisProcess) purgeLocalSources(app, "连续两次启动均未登录服务器")
            }
        }
        return true
    }

    /** 彻底清除本地音源脚本：本地音源加速失效，直到下次登录服务器重新拉取。 */
    private fun purgeLocalSources(app: Context, reason: String) {
        if (loggedInThisProcess) return
        purged = true
        Log.w(TAG, "purge local sources: $reason")
        SourceScriptStore.purgeAll(app)
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
        return if (SourceAccelerationSession.isReady()) Mode.LOCAL_FIRST else Mode.SERVER_FIRST
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
            }
            ServerLinkState.Link.ONLINE -> if (old.link == ServerLinkState.Link.LOST) {
                SourceAccelerationPolicy.onServerRecovered()
                val s = server
                val p = protocol
                val c = context
                if (s != null && c != null) {
                    lastSessionStartAttemptAt = System.currentTimeMillis()
                    scope.launch {
                        runCatching { SourceAccelerationPolicy.evaluate(c, s.serverUrl) }
                        // 恢复时不强制重拉：start 内部按 /api/source/list 增量核对
                        if (p != null) runCatching { SourceAccelerationSession.start(s, p) }
                    }
                }
            }
            else -> Unit
        }
    }

    /** 会话未就绪时按 60 秒节流重试（启动时脚本接口偶发失败 / 冷启动服务器短暂不可达）。 */
    private fun ensureSessionReady(now: Long) {
        if (ServerLinkState.isLost) return
        if (SourceAccelerationSession.isReady()) return
        val s = server ?: return
        val p = protocol ?: return
        if (now - lastSessionStartAttemptAt < SESSION_RETRY_MS) return
        lastSessionStartAttemptAt = now
        scope.launch {
            runCatching { SourceAccelerationSession.start(s, p) }
                .onFailure { Log.w(TAG, "session retry failed", it) }
        }
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
