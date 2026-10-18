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
        SourceAccelerationPolicy.onServerRecovered()
        scope.launch {
            try {
                SourceAccelerationPolicy.evaluate(context, server.serverUrl)
                SourceAccelerationSession.start(server, protocol)
            } catch (e: Exception) {
                Log.w(TAG, "start failed", e)
            }
        }
        startHealthMonitor(server, protocol)
    }

    private fun startHealthMonitor(server: ServerConfig, protocol: LemonMusicProtocol) {
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
        if (lost) return false
        if (!SourceAccelerationPolicy.isEnabled()) return false
        if (!SourceAccelerationSession.isReady()) return false
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
                    scope.launch {
                        SourceAccelerationPolicy.evaluate(c, s.serverUrl)
                        SourceAccelerationSession.start(s, p, force = true)
                    }
                }
            }
            return
        }
        consecutiveFailures++
        val hardTimeout = elapsedMs >= SERVER_TIMEOUT_MS
        if (hardTimeout || consecutiveFailures >= 2) {
            if (!lost) {
                lost = true
                SourceAccelerationPolicy.onServerLost()
                scope.launch {
                    SourceAccelerationSession.stop("server_lost")
                    Log.w(TAG, "server lost: control timeout/failures; acceleration stopped")
                }
            }
        }
    }

    suspend fun stop(reason: String = "exit") {
        monitorJob?.cancel()
        monitorJob = null
        SourceAccelerationSession.stop(reason)
        SourceAccelerationPolicy.reset()
        lost = false
        consecutiveFailures = 0
    }

    fun stopAsync(reason: String) {
        scope.launch { stop(reason) }
    }
}
