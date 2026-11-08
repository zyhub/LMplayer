package com.lm.player.core.source

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 柠檬服务器链路状态机（需求②）。
 *
 * ONLINE ──探活失败──► LOST ──连续 2 次探活成功──► ONLINE
 *    └────删除服务器 / 解绑────► DETACHED（保留本地音源直到 App 重启/清理）
 *
 * 判定依据是 **语义探活** `GET /api/health`（服务端挂在 requireAuth 之前，免登录），
 * 只认 `{"ok":true}` 的 JSON；HTML、502、404、DNS/连接失败都算失败。
 * 这样「服务器删了但反代/域名还在」的情况也能被识别为离线（旧逻辑把任何 HTTP 响应都当成可达）。
 *
 * 真实请求出现 IO 失败或「非柠檬响应」时通过 [reportFailure] 立刻触发一次探活，不再干等 60 秒。
 */
object ServerLinkState {
    private const val TAG = "ServerLinkState"
    private const val ONLINE_PROBE_INTERVAL_MS = 60_000L
    private const val CONFIRM_DELAY_MS = 3_000L
    private const val MIN_PROBE_GAP_MS = 2_500L
    /** 连续多少次探活失败才判定离线 */
    private const val FAIL_THRESHOLD = 3
    /** 网络切换后等待链路稳定再探活 */
    private const val NETWORK_SETTLE_MS = 2_000L
    /** 探活请求标记：LocalCatalogGateway 熔断时放行带此头的请求 */
    const val PROBE_HEADER = LemonServerProbe.PROBE_HEADER
    private val LOST_BACKOFF_MS = longArrayOf(15_000L, 15_000L, 30_000L, 60_000L)

    enum class Link { NONE, ONLINE, LOST, DETACHED }

    data class State(
        val link: Link = Link.NONE,
        val serverId: String = "",
        val serverHost: String = "",
        val reason: String = "",
        val lastOkAt: Long = 0L,
        val changedAt: Long = 0L
    ) {
        val isLost: Boolean get() = link == Link.LOST || link == Link.DETACHED
        val isServerUsable: Boolean get() = link == Link.ONLINE
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var appContext: Context? = null

    @Volatile private var baseUrl: String = ""
    @Volatile private var consecutiveFailures = 0
    @Volatile private var consecutiveSuccesses = 0
    @Volatile private var lastProbeAt = 0L
    @Volatile private var probing = false

    /** 状态变化回调（控制器用来刷新策略、恢复后增量同步脚本）。 */
    @Volatile
    var listener: ((old: State, new: State) -> Unit)? = null

    private val probeClient: OkHttpClient by lazy {
        val base = appContext?.let { runCatching { com.lm.player.core.network.NetworkClientFactory.createOkHttpClient(it) }.getOrNull() }
            ?: OkHttpClient()
        // 继承共享客户端的自签证书/Conscrypt 配置与 UA；超时按「公网 + 反向代理」放宽，不经过共享 callTimeout(300s)。
        // 不使用 OkHttp 磁盘缓存，避免拿到缓存里的旧探活结果。
        base.newBuilder()
            .cache(null)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(9, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Volatile private var networkCallbackRegistered = false

    fun init(context: Context) {
        appContext = context.applicationContext
        registerNetworkCallback(context.applicationContext)
    }

    /**
     * 手机切换 Wi-Fi / 移动网络 / VPN 时，反代域名解析、链路都会变：此前的失败计数作废，切换稳定后重新探活，
     * 避免把「网络切换中的瞬时失败」误判为服务器离线，也让离线状态在新网络下尽快恢复。
     */
    private fun registerNetworkCallback(app: Context) {
        if (networkCallbackRegistered) return
        networkCallbackRegistered = true
        runCatching {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    consecutiveFailures = 0
                    consecutiveSuccesses = 0
                    lastProbeAt = 0L
                    probeSoon(NETWORK_SETTLE_MS)
                }
            })
        }.onFailure {
            networkCallbackRegistered = false
            Log.w(TAG, "register network callback failed", it)
        }
    }

    val isLost: Boolean get() = _state.value.isLost
    val isDetached: Boolean get() = _state.value.link == Link.DETACHED

    /** 当前服务器是否可用；host 为空时只看状态。 */
    fun isServerUsable(host: String = ""): Boolean {
        val s = _state.value
        if (!s.isServerUsable) return false
        return host.isBlank() || NetworkScopeDetector.hostOf(host).equals(s.serverHost, true)
    }

    /** 绑定（或重新绑定）服务器：登录成功 / 冷启动恢复时调用。默认先视为在线，随即探活确认。 */
    fun bind(serverId: String, serverUrl: String, assumeOnline: Boolean = true) {
        val host = NetworkScopeDetector.hostOf(serverUrl)
        baseUrl = com.lm.player.core.network.ServerUrlPolicy.canonicalize(serverUrl).trimEnd('/')
        LemonServerProbe.resetCache(baseUrl)
        val old = _state.value
        consecutiveFailures = 0
        consecutiveSuccesses = 0
        val sameServer = old.serverId == serverId && old.link != Link.DETACHED
        val link = when {
            sameServer && old.link == Link.LOST && !assumeOnline -> Link.LOST
            assumeOnline -> Link.ONLINE
            else -> old.link.takeIf { sameServer } ?: Link.ONLINE
        }
        publish(old.copy(link = link, serverId = serverId, serverHost = host, reason = "已绑定", lastOkAt = if (assumeOnline) System.currentTimeMillis() else old.lastOkAt))
        startLoop()
        probeSoon(0L)
    }

    /** 解绑：服务器在 App 内被删除。本地音源继续可用，不再探活。 */
    fun detach(serverId: String) {
        val old = _state.value
        if (serverId.isNotBlank() && old.serverId.isNotBlank() && old.serverId != serverId) return
        loopJob?.cancel()
        loopJob = null
        baseUrl = ""
        publish(old.copy(link = Link.DETACHED, reason = "服务器已删除", serverHost = ""))
    }

    fun reset() {
        loopJob?.cancel()
        loopJob = null
        baseUrl = ""
        publish(State())
    }

    /** 真实请求成功（拿到了柠檬 JSON 响应）。 */
    fun reportSuccess(host: String) {
        val s = _state.value
        if (s.serverHost.isBlank() || !NetworkScopeDetector.hostOf(host).equals(s.serverHost, true)) return
        if (s.link == Link.ONLINE) {
            consecutiveFailures = 0
            // 只更新时间戳且以最新状态为准：不能用旧快照覆盖掉并发发布的 LOST（否则状态回到 ONLINE 却不通知监听者）
            _state.update { if (it.link == Link.ONLINE) it.copy(lastOkAt = System.currentTimeMillis()) else it }
        } else if (s.link == Link.LOST) {
            // 离线期间真实请求成功：立即确认一次
            probeSoon(0L)
        }
    }

    /** 真实请求 IO 失败 / 返回非柠檬响应（反代 502、HTML 等）。 */
    fun reportFailure(host: String, reason: String) {
        val s = _state.value
        if (s.serverHost.isBlank() || !NetworkScopeDetector.hostOf(host).equals(s.serverHost, true)) return
        if (s.link == Link.ONLINE) {
            Log.i(TAG, "request failure reported: $reason → probe")
            probeSoon(0L)
        }
    }

    private fun probeSoon(delayMs: Long) {
        if (baseUrl.isBlank()) return
        scope.launch {
            if (delayMs > 0L) delay(delayMs)
            probeOnce()
        }
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            var lostRound = 0
            while (isActive) {
                val s = _state.value
                val wait = when (s.link) {
                    Link.LOST -> LOST_BACKOFF_MS[lostRound.coerceAtMost(LOST_BACKOFF_MS.size - 1)].also { lostRound++ }
                    Link.ONLINE -> { lostRound = 0; ONLINE_PROBE_INTERVAL_MS }
                    else -> return@launch
                }
                delay(wait)
                probeOnce()
            }
        }
    }

    /** 立即探活一次；返回服务器是否在线。 */
    suspend fun probeNow(): Boolean {
        lastProbeAt = 0L
        probeOnce()
        return _state.value.link == Link.ONLINE
    }

    private suspend fun probeOnce() {
        val base = baseUrl
        if (base.isBlank()) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (probing || now - lastProbeAt < MIN_PROBE_GAP_MS) return
            probing = true
            lastProbeAt = now
        }
        try {
            val ctx = appContext
            if (ctx != null && !NetworkScopeDetector.isOnline(ctx)) {
                // 手机自身断网：本地音源同样不可用，不改变服务器判定，避免恢复网络后误判
                return
            }
            val token = com.lm.player.core.network.NetworkClientFactory.getActiveAuthToken()
            val r = runCatching { LemonServerProbe.alive(probeClient, base, token) }
                .getOrElse { LemonServerProbe.Outcome(false, it.message ?: it.javaClass.simpleName) }
            onProbeResult(ProbeOutcome(r.alive, r.reason))
        } finally {
            probing = false
        }
    }

    private data class ProbeOutcome(val ok: Boolean, val reason: String)

    private fun onProbeResult(outcome: ProbeOutcome) {
        val s = _state.value
        if (s.link == Link.DETACHED || s.link == Link.NONE) return
        val now = System.currentTimeMillis()
        if (outcome.ok) {
            consecutiveFailures = 0
            consecutiveSuccesses++
            if (s.link == Link.LOST) {
                if (consecutiveSuccesses >= 2) {
                    publish(s.copy(link = Link.ONLINE, reason = "服务器已恢复", lastOkAt = now))
                } else {
                    probeSoon(CONFIRM_DELAY_MS + 100L)
                }
            } else {
                _state.update { if (it.link == Link.ONLINE) it.copy(lastOkAt = now) else it }
            }
            return
        }
        consecutiveSuccesses = 0
        consecutiveFailures++
        Log.w(TAG, "probe failed (${consecutiveFailures}): ${outcome.reason}")
        if (s.link == Link.ONLINE) {
            // 反代场景常有瞬时 502 / 超时 / 后端冷启动：不再「一次失败即离线」，
            // 必须连续 FAIL_THRESHOLD 次（间隔约 3 秒，主 + 备用探活都失败）才判定离线。
            if (consecutiveFailures >= FAIL_THRESHOLD) {
                publish(s.copy(link = Link.LOST, reason = outcome.reason))
            } else {
                probeSoon(CONFIRM_DELAY_MS + 100L)
            }
        }
    }

    private fun publish(newState: State) {
        val old = _state.value
        val stamped = if (old.link != newState.link) newState.copy(changedAt = System.currentTimeMillis()) else newState
        _state.value = stamped
        if (old.link != stamped.link) {
            Log.i(TAG, "link ${old.link} -> ${stamped.link} (${stamped.reason}) host=${stamped.serverHost}")
            runCatching { listener?.invoke(old, stamped) }.onFailure { Log.w(TAG, "listener failed", it) }
        }
    }
}
