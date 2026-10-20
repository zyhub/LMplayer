package com.lm.player.core.source

import android.content.Context
import android.util.Log
import com.lm.player.core.model.ServerConfig
import com.lm.player.core.network.LemonMusicProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 加速会话总控：网络范围策略 + QuickJS 会话 + 服务器 LOST/恢复。
 *
 * 任何本地解析失败都只返回失败，由调用方回退服务器；这里不抛到播放/下载主流程。
 */
@android.annotation.SuppressLint("StaticFieldLeak")
object SourceAccelerationController {
    private const val TAG = "AccelerationControl"
    private const val SERVER_TIMEOUT_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: kotlinx.coroutines.Job? = null
    private var context: Context? = null
    private var server: ServerConfig? = null
    private var protocol: LemonMusicProtocol? = null
    private var serverHost: String = ""

    @Volatile private var lost = false
    @Volatile private var consecutiveFailures = 0
    @Volatile private var lastPolicyEvalAt = 0L
    @Volatile private var lastSessionStartAttemptAt = 0L

    val isLost: Boolean get() = lost

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    fun start(context: Context, server: ServerConfig, protocol: LemonMusicProtocol) {
        this.context = context.applicationContext
        this.server = server
        this.protocol = protocol
        serverHost = NetworkScopeDetector.hostOf(server.serverUrl)
        lost = false
        consecutiveFailures = 0
        lastSessionStartAttemptAt = System.currentTimeMillis()
        SourceAccelerationPolicy.onServerRecovered()
        scope.launch {
            try {
                SourceAccelerationPolicy.evaluate(context, server.serverUrl)
                SourceAccelerationSession.start(server, protocol)
            } catch (e: Exception) {
                Log.w(TAG, "start failed", e)
            }
        }
        startHealthMonitor(protocol)
    }

    private fun startHealthMonitor(protocol: LemonMusicProtocol) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(60_000L)
                val started = System.currentTimeMillis()
                val result = protocol.healthCheck()
                val elapsed = System.currentTimeMillis() - started
                onServerSample(
                    serverHost,
                    elapsed,
                    result.getOrNull() == true && elapsed < 10_000L
                )
            }
        }
    }

    /**
     * 冷启动离线恢复：从加密缓存加载上次成功脚本，不依赖服务器。
     *
     * 必须在 SourceAccelerationSession.init / Room 初始化之后调用。若网络会话已经就绪，
     * 直接保留网络会话（网络脚本更新）。
     */
    fun restoreFromCache(context: Context) {
        scope.launch {
            if (SourceAccelerationSession.isReady()) return@launch
            runCatching {
                val db = com.lm.player.core.database.ZdsDatabase.getInstance(context)
                val entity = db.serverDao().getActiveServer()
                    ?: db.serverDao().getAllServers().firstOrNull {
                        it.type == com.lm.player.core.model.ServerType.LEMON_MUSIC
                    }
                    ?: return@launch
                val server = ServerConfig(
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
                val cached = SourceScriptStore.load(context) ?: return@launch
                if (cached.serverId.isNotBlank() && cached.serverId != server.id) return@launch
                this@SourceAccelerationController.context = context.applicationContext
                this@SourceAccelerationController.server = server
                serverHost = NetworkScopeDetector.hostOf(server.serverUrl)
                SourceAccelerationPolicy.evaluate(context, server.serverUrl)
                val result = SourceAccelerationSession.startFromCache(server, cached.bundle)
                Log.i(
                    TAG,
                    "cache restore: success=${result.isSuccess} sources=${cached.bundle.sources.size}"
                )
            }.onFailure { Log.w(TAG, "cache restore failed", it) }
        }
    }

    suspend fun prepare(context: Context, server: ServerConfig, protocol: LemonMusicProtocol): Boolean {
        this.context = context.applicationContext
        this.server = server
        this.protocol = protocol
        serverHost = NetworkScopeDetector.hostOf(server.serverUrl)
        SourceAccelerationPolicy.onServerRecovered()
        SourceAccelerationPolicy.evaluate(context, server.serverUrl)
        return SourceAccelerationSession.start(server, protocol).isSuccess
    }

    fun shouldAccelerate(host: String = serverHost): Boolean {
        // LOST 不再禁用本地加速：服务器控制面不可达时，本地 QuickJS 反而是唯一可用链路。
        if (!SourceAccelerationPolicy.isEnabled()) return false
        if (!SourceAccelerationSession.isReady()) return false
        val ctx = context ?: return false
        if (!NetworkScopeDetector.isOnline(ctx)) return false
        return host.isBlank() || NetworkScopeDetector.hostOf(host) == serverHost
    }

    fun onServerSample(host: String, elapsedMs: Long, success: Boolean) {
        if (serverHost.isBlank() || NetworkScopeDetector.hostOf(host) != serverHost) return
        if (success) {
            consecutiveFailures = 0
            val now = System.currentTimeMillis()
            if (now - lastPolicyEvalAt >= 5_000L) {
                lastPolicyEvalAt = now
                val c = context
                val s = server
                if (c != null && s != null) {
                    scope.launch { runCatching { SourceAccelerationPolicy.evaluate(c, s.serverUrl) } }
                }
            }
            if (lost) {
                lost = false
                SourceAccelerationPolicy.onServerRecovered()
                val s = server
                val p = protocol
                val c = context
                if (s != null && p != null && c != null) {
                    lastSessionStartAttemptAt = now
                    scope.launch {
                        SourceAccelerationPolicy.evaluate(c, s.serverUrl)
                        // 服务器恢复时不要强制重拉脚本：会话仍就绪就继续用，避免恢复瞬间出现
                        // “先关闭旧引擎、再等待新 bundle”的空窗。
                        SourceAccelerationSession.start(s, p)
                    }
                }
            }
            ensureSessionReady(now)
            return
        }
        consecutiveFailures++
        val hardTimeout = elapsedMs >= SERVER_TIMEOUT_MS
        if (hardTimeout || consecutiveFailures >= 2) {
            if (!lost) {
                lost = true
                SourceAccelerationPolicy.onServerLost()
                // 关键：只标记 LOST，不关闭本地 QuickJS。服务器控制面不可达时，
                // 已加载的本地音源仍可从公网 CDN 取链，是最后的播放兜底。
                Log.w(TAG, "server lost: keep local source session alive")
            }
        }
    }

    /**
     * 会话未就绪时按 60 秒节流重试。解决两类问题：
     * 1. 启动时脚本接口偶发失败，后续没有任何重试；
     * 2. 冷启动时服务器短暂不可达，恢复后需要自动补加载。
     */
    private fun ensureSessionReady(now: Long) {
        if (lost) return
        if (SourceAccelerationSession.isReady()) return
        val s = server ?: return
        val p = protocol ?: return
        if (now - lastSessionStartAttemptAt < 60_000L) return
        lastSessionStartAttemptAt = now
        scope.launch {
            runCatching { SourceAccelerationSession.start(s, p) }
                .onFailure { Log.w(TAG, "session retry failed", it) }
        }
    }

    suspend fun stop(reason: String = "exit") {
        monitorJob?.cancel()
        monitorJob = null
        SourceAccelerationSession.stop(reason)
        SourceAccelerationPolicy.reset()
        lost = false
        consecutiveFailures = 0
        lastSessionStartAttemptAt = 0L
    }

    fun stopAsync(reason: String) {
        scope.launch { stop(reason) }
    }
}
