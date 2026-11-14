package com.lm.player.core.source

import android.content.Context
import android.util.Log
import com.lm.player.core.model.ServerConfig
import com.lm.player.core.model.SourceScriptBundle
import com.lm.player.core.network.LemonMusicProtocol
import com.lm.player.core.network.ServerLatencyMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 本地加速音源的后台拉取 / 更新（2026-11-13，用户规则「拉取的音源下次启动生效」）。
 *
 * - 本次已登录、服务器链路**连续在线满 2 分钟且期间没有请求失败**后才开始；中途离线，恢复后重新计时；
 * - 每次启动只做一次（失败了等下一个「稳定 2 分钟」再试，最多 3 次）；设置页刷新按钮可以立即拉取；
 * - 先用轻量的音源列表核对「已激活脚本 ID + 版本」：与本机最新一份一致、且拉取于 24 小时以内 → 不下载；
 * - 否则下载整包、校验，存为**待用版**，下次冷启动才转正装载。全程不碰正在运行的引擎，本次启动一直用当前版；
 * - 本机一份都没有时（首次安装 / 超过 12 小时被清除 / 换了服务器）由控制器以「立即装载」方式调用。
 */
object SourceScriptUpdater {
    private const val TAG = "SourceScriptUpdater"

    /** 服务器连续在线多久才开始拉取 */
    const val STABLE_MS = 2 * 60_000L
    private const val MAX_AUTO_ATTEMPTS = 3
    /** 列表版本一致时仍然完整刷新一次的间隔（防止脚本内容变了版本号没变） */
    private const val FULL_REFRESH_MS = 24 * 60 * 60_000L
    /** 有歌正在取链时最多等多久再开始下载 */
    private const val YIELD_MAX_MS = 30_000L
    private const val LIST_TIMEOUT_MS = 10_000L
    private const val DOWNLOAD_TIMEOUT_MS = 90_000L

    enum class Phase { IDLE, WAITING, CHECKING, DOWNLOADING, SAVING }

    data class State(
        val phase: Phase = Phase.IDLE,
        /** 拉取进度 0~1（CHECKING / DOWNLOADING / SAVING 时有效） */
        val progress: Float = 0f,
        /** 等待服务器稳定还差多少毫秒（WAITING 时有效） */
        val stableRemainingMs: Long = 0L,
        /** 最近一次拉取开始的时间 */
        val lastAttemptAt: Long = 0L,
        /** 最近一次结果的说明，例如「新版本已下载，下次启动生效」「已核对，脚本没有变化」 */
        val lastResult: String = "",
        val lastResultAt: Long = 0L,
        /** 最近一次拉取失败的原因；之后成功一次就清空 */
        val lastError: String? = null,
        val lastErrorAt: Long = 0L,
        /** 本次启动自动拉取失败的次数 */
        val autoAttempts: Int = 0,
        /** 本次启动已经拉取 / 核对成功过 */
        val doneThisLaunch: Boolean = false,
        /** 本机有待用版（已下载，下次启动生效） */
        val pendingReady: Boolean = false,
        val pendingSavedAt: Long = 0L
    ) {
        /** 正在从服务器拉取（状态环黄色） */
        val isPulling: Boolean get() = phase == Phase.CHECKING || phase == Phase.DOWNLOADING || phase == Phase.SAVING
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pullMutex = Mutex()

    @Volatile private var context: Context? = null
    @Volatile private var server: ServerConfig? = null
    @Volatile private var protocol: LemonMusicProtocol? = null
    private var watchJob: Job? = null

    fun init(context: Context) {
        if (this.context != null) return
        val app = context.applicationContext
        this.context = app
        refreshPending(app)
    }

    private fun refreshPending(app: Context) {
        val s = SourceScriptStore.summary(app, SourceScriptStore.Slot.PENDING)
        val has = SourceScriptStore.hasSlot(app, SourceScriptStore.Slot.PENDING)
        _state.update { it.copy(pendingReady = has, pendingSavedAt = s?.savedAt ?: 0L) }
    }

    /** 本地脚本被清除（超过 12 小时未登录）：待用版一起没了 */
    fun onPurged() {
        _state.update { it.copy(pendingReady = false, pendingSavedAt = 0L) }
    }

    /** 登录成功：开始等待「服务器稳定 2 分钟」后自动拉取（每次启动一次）；同一服务器重复调用不重新计时 */
    @Synchronized
    fun onLoggedIn(context: Context, server: ServerConfig, protocol: LemonMusicProtocol) {
        init(context)
        val sameServer = this.server?.id == server.id
        this.server = server
        this.protocol = protocol
        if (sameServer && (watchJob?.isActive == true || _state.value.doneThisLaunch)) return
        if (!sameServer) _state.update { it.copy(doneThisLaunch = false, autoAttempts = 0) }
        watchJob?.cancel()
        watchJob = scope.launch { watchLoop() }
    }

    private suspend fun watchLoop() {
        while (!_state.value.doneThisLaunch && _state.value.autoAttempts < MAX_AUTO_ATTEMPTS) {
            if (!waitStable()) break
            yieldToPlayback()
            if (_state.value.doneThisLaunch) break
            pull(manual = false, applyNow = false)
        }
        _state.update { if (it.phase == Phase.WAITING) it.copy(phase = Phase.IDLE, stableRemainingMs = 0L) else it }
    }

    /** 等到服务器连续在线满 2 分钟、期间没有请求失败；本次已成功拉取过（例如手动刷新）时返回 false */
    private suspend fun waitStable(): Boolean {
        var since = 0L
        while (true) {
            if (_state.value.doneThisLaunch) return false
            val s = server ?: return false
            val now = System.currentTimeMillis()
            val link = ServerLinkState.state.value
            if (link.link != ServerLinkState.Link.ONLINE) {
                since = 0L
                updateWaiting(STABLE_MS)
            } else {
                if (since == 0L) since = now
                // 期间有请求失败：从失败那一刻重新计时
                val lastFail = ServerLatencyMonitor.get(NetworkScopeDetector.hostOf(s.serverUrl))?.lastFailureAt ?: 0L
                if (lastFail > since) since = lastFail
                val remain = STABLE_MS - (now - since)
                if (remain <= 0L) return true
                updateWaiting(remain)
            }
            delay(5_000L)
        }
    }

    private fun updateWaiting(remainMs: Long) {
        _state.update { if (it.isPulling) it else it.copy(phase = Phase.WAITING, stableRemainingMs = remainMs) }
    }

    /** 有歌正在取链时先等它结束（最多 30 秒），不和取链抢带宽 */
    private suspend fun yieldToPlayback() {
        val start = System.currentTimeMillis()
        while (com.lm.player.core.media.PlaybackRouter.activeResolves.get() > 0 &&
            System.currentTimeMillis() - start < YIELD_MAX_MS
        ) {
            delay(500L)
        }
    }

    /** 设置页刷新按钮：立即拉取（不等 2 分钟），同样存为待用版、下次启动生效 */
    fun pullManually() {
        scope.launch { pull(manual = true, applyNow = false) }
    }

    /**
     * 拉取一次。
     * @param applyNow 本机一份可用的都没有：拉到后存为当前版，由调用方立即装载；否则存为待用版、下次启动生效
     * @return 拉取 / 核对成功
     */
    suspend fun pull(manual: Boolean, applyNow: Boolean): Boolean = pullMutex.withLock {
        val ctx = context ?: return@withLock false
        val s = server
        val p = protocol
        if (s == null || p == null) {
            fail("还没有登录服务器", manual)
            return@withLock false
        }
        _state.update { it.copy(phase = Phase.CHECKING, progress = 0f, lastAttemptAt = System.currentTimeMillis()) }
        try {
            // 1. 轻量核对：已激活脚本 ID + 版本与本机最新一份一致、且 24 小时内拉取过 → 不下载
            val latestSlot = when {
                applyNow -> null
                SourceScriptStore.summary(ctx, SourceScriptStore.Slot.PENDING)?.serverId == s.id -> SourceScriptStore.Slot.PENDING
                SourceScriptStore.hasSlot(ctx, SourceScriptStore.Slot.CURRENT) -> SourceScriptStore.Slot.CURRENT
                else -> null
            }
            // 当前版没有概要（旧版本留下的）：读一次就会补上
            if (latestSlot == SourceScriptStore.Slot.CURRENT && SourceScriptStore.summary(ctx, latestSlot) == null) {
                SourceScriptStore.load(ctx, latestSlot)
            }
            val latest = latestSlot?.let { SourceScriptStore.summary(ctx, it) }?.takeIf { it.serverId.isBlank() || it.serverId == s.id }
            if (latest != null) {
                val list = withTimeoutOrNull(LIST_TIMEOUT_MS) { p.fetchSourceList().getOrNull() }
                    ?: throw IllegalStateException("核对音源列表失败（服务器无响应）")
                val active = list.filter { it.isActive }.associate { it.id to it.version }
                val local = latest.sources.associate { it.first to it.third }
                val fresh = System.currentTimeMillis() - latest.savedAt < FULL_REFRESH_MS
                if (active.isNotEmpty() && active == local && fresh) {
                    succeed(ctx, "已核对，脚本没有变化")
                    return@withLock true
                }
            }
            _state.update { it.copy(phase = Phase.DOWNLOADING, progress = 0.05f) }

            // 2. 下载整包（进度 5% → 85%）
            var lastReported = 0f
            val fetched = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                p.fetchActiveScriptBundle { f ->
                    val v = 0.05f + 0.80f * f.coerceIn(0f, 1f)
                    // 每 1% 更新一次，不必每 16KB 刷一次界面
                    if (v - lastReported >= 0.01f || f >= 1f) {
                        lastReported = v
                        _state.update { st -> if (st.phase == Phase.DOWNLOADING) st.copy(progress = v) else st }
                    }
                }
            } ?: throw IllegalStateException("下载超时（${DOWNLOAD_TIMEOUT_MS / 1000} 秒）")
            val bundle = fetched.getOrElse { throw it }

            // 3. 校验并保存（进度 85% → 100%）
            _state.update { it.copy(phase = Phase.SAVING, progress = 0.88f) }
            val normalized = normalize(bundle)
            if (normalized.sources.isEmpty()) throw IllegalStateException("服务器没有返回可用的脚本")
            val target = if (applyNow) SourceScriptStore.Slot.CURRENT else SourceScriptStore.Slot.PENDING

            // 只是 24 小时例行刷新、内容与本机最新一份完全相同：只更新拉取时间，不提示「新版本」
            val sameContent = if (!applyNow && latestSlot != null) {
                val old = SourceScriptStore.load(ctx, latestSlot)?.bundle?.let { normalize(it) }
                old != null && old.sources.associate { it.id to it.hash } == normalized.sources.associate { it.id to it.hash }
            } else false
            val saveSlot = if (sameContent) latestSlot!! else target
            if (!SourceScriptStore.save(ctx, s.id, normalized, saveSlot)) throw IllegalStateException("保存脚本失败")
            _state.update { it.copy(progress = 1f) }
            SourceScriptStore.markLoginConnected(ctx)
            succeed(
                ctx,
                when {
                    applyNow -> "已下载并装载"
                    sameContent -> "已核对，脚本没有变化"
                    else -> "新版本已下载，下次启动生效"
                }
            )
            Log.i(TAG, "pull done: ${normalized.sources.size} script(s) → $saveSlot (manual=$manual)")
            return@withLock true
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.update { it.copy(phase = Phase.IDLE) }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "pull failed", e)
            fail(e.message ?: e.javaClass.simpleName, manual)
            return@withLock false
        }
    }

    private fun normalize(bundle: SourceScriptBundle): SourceScriptBundle = bundle.copy(
        sources = bundle.sources.filter { it.id.isNotBlank() && it.script.isNotBlank() }
            .map { if (it.hash.isBlank()) it.copy(hash = SourceScriptStore.sha256(it.script)) else it }
            .distinctBy { it.id }
    )

    private fun succeed(ctx: Context, message: String) {
        val now = System.currentTimeMillis()
        val pendingSummary = SourceScriptStore.summary(ctx, SourceScriptStore.Slot.PENDING)
        _state.update {
            it.copy(
                phase = Phase.IDLE,
                progress = 1f,
                stableRemainingMs = 0L,
                lastResult = message,
                lastResultAt = now,
                lastError = null,
                lastErrorAt = 0L,
                doneThisLaunch = true,
                pendingReady = SourceScriptStore.hasSlot(ctx, SourceScriptStore.Slot.PENDING),
                pendingSavedAt = pendingSummary?.savedAt ?: 0L
            )
        }
    }

    private fun fail(message: String, manual: Boolean) {
        val now = System.currentTimeMillis()
        _state.update {
            it.copy(
                phase = Phase.IDLE,
                progress = 0f,
                lastResult = "拉取失败",
                lastResultAt = now,
                lastError = message,
                lastErrorAt = now,
                autoAttempts = if (manual) it.autoAttempts else it.autoAttempts + 1
            )
        }
    }
}
