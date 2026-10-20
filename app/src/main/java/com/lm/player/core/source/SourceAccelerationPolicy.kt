package com.lm.player.core.source

import android.content.Context
import android.util.Log
import com.lm.player.core.network.ServerLatencyMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 内网/外网 + 60ms/40ms 回差策略。
 *
 * 外网：立即加速；
 * 内网：EWMA > 60ms 加速，< 40ms 且稳定 30s 才关闭；
 * 40–60ms：保持当前状态，避免抖动。
 */
object SourceAccelerationPolicy {

    private const val TAG = "AccelerationPolicy"
    const val ENABLE_LAN_MS = 60L
    const val DISABLE_LAN_MS = 40L
    private const val DISABLE_STABLE_MS = 30_000L
    private const val SCOPE_CACHE_MS = 60_000L

    data class State(
        val scope: NetworkScope = NetworkScope.UNKNOWN,
        val enabled: Boolean = false,
        val latencyMs: Long = 0L,
        val reason: String = "初始化",
        val updatedAt: Long = 0L
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()
    private var scope: NetworkScope = NetworkScope.UNKNOWN
    private var scopeAt = 0L
    private var lastEnabledAt = 0L
    private var lowLatencySince = 0L

    /**
     * 服务器是否处于 LOST。
     *
     * LOST 不再等于“关闭本地加速”：本地 QuickJS 已经加载在内存里，不依赖服务器控制面。
     * 此时服务器优先策略已经没有意义，应让本地音源接管；只有明确退出/切换服务器时才关闭会话。
     */
    @Volatile
    private var serverLost = false

    fun isEnabled(): Boolean = _state.value.enabled

    suspend fun evaluate(context: Context, serverUrl: String): State = withContext(Dispatchers.IO) {
        mutex.withLock {
            val host = NetworkScopeDetector.hostOf(serverUrl)
            val now = System.currentTimeMillis()
            // 无网络时本地脚本同样拿不到 CDN；提前判定可以避免播放/下载白等十几秒。
            if (!NetworkScopeDetector.isOnline(context)) {
                return@withLock publish(scope, false, ServerLatencyMonitor.latencyMs(host), "无网络连接")
            }
            if (scope == NetworkScope.UNKNOWN || now - scopeAt >= SCOPE_CACHE_MS) {
                scope = NetworkScopeDetector.detect(context, serverUrl)
                scopeAt = now
            }
            val latency = ServerLatencyMonitor.latencyMs(host)
            if (serverLost) {
                return@withLock publish(scope, true, latency, "服务器离线，本地音源接管")
            }
            val enabled = when (scope) {
                NetworkScope.WAN -> true
                NetworkScope.LAN -> decideLan(latency, now)
                NetworkScope.UNKNOWN -> latency >= ENABLE_LAN_MS
            }
            if (enabled) {
                lastEnabledAt = now
                lowLatencySince = 0L
            }
            publish(scope, enabled, latency, reasonFor(scope, enabled, latency))
        }
    }

    fun onServerLost() {
        serverLost = true
        _state.value = _state.value.copy(
            enabled = true,
            reason = "服务器离线，本地音源接管",
            updatedAt = System.currentTimeMillis()
        )
    }

    fun onServerRecovered() {
        serverLost = false
        lowLatencySince = 0L
    }

    fun reset() {
        serverLost = false
        scope = NetworkScope.UNKNOWN
        scopeAt = 0L
        lastEnabledAt = 0L
        lowLatencySince = 0L
        _state.value = State()
    }

    private fun decideLan(latency: Long, now: Long): Boolean {
        if (latency <= 0L) return false
        if (latency >= ENABLE_LAN_MS) {
            lowLatencySince = 0L
            return true
        }
        if (latency < DISABLE_LAN_MS) {
            if (lowLatencySince == 0L) lowLatencySince = now
            val stable = now - lowLatencySince >= DISABLE_STABLE_MS
            val minHold = lastEnabledAt == 0L || now - lastEnabledAt >= DISABLE_STABLE_MS
            if (stable && minHold) {
                lastEnabledAt = 0L
                return false
            }
        } else {
            lowLatencySince = 0L
        }
        return _state.value.enabled
    }

    private fun publish(scope: NetworkScope, enabled: Boolean, latency: Long, reason: String): State {
        val s = State(scope, enabled, latency, reason, System.currentTimeMillis())
        _state.value = s
        Log.i(TAG, "scope=${scope} enabled=${enabled} latency=${latency}ms reason=${reason}")
        return s
    }

    private fun reasonFor(scope: NetworkScope, enabled: Boolean, latency: Long): String = when {
        scope == NetworkScope.WAN && enabled -> "外网"
        scope == NetworkScope.LAN && enabled -> "内网延迟 ${latency}ms"
        scope == NetworkScope.LAN -> "内网服务器优先"
        enabled -> "延迟 ${latency}ms"
        else -> "服务器优先"
    }
}
