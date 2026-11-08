package com.lm.player.core.source

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 服务器「可用性」判定（带防抖），只决定**资料库在线 / 本地模式**与服务器专属功能是否显示。
 *
 * 取链、搜索、发现页等数据在本地音源可用时始终走本地（见 [SourceAccelerationController.playbackMode]），
 * 不受这里影响；这里的判定时间只是为了避免服务器短时波动让资料库来回切换（用户 2026-11-07 确认）：
 * - 在线 → 本地：链路连续离线满 [OFFLINE_CONFIRM_MS]；期间只要恢复一次就重新计时；
 * - 本地 → 在线：链路恢复（[ServerLinkState] 需连续 2 次探活成功）后再稳定 [ONLINE_CONFIRM_MS]；
 * - 冷启动时服务器就连不上（本进程从未在线过）：不等待，直接本地。
 */
object ServerPresence {
    private const val TAG = "ServerPresence"
    const val OFFLINE_CONFIRM_MS = 60_000L
    const val ONLINE_CONFIRM_MS = 30_000L

    enum class Mode {
        /** 冷启动还没确认（登录 / 探活结果未出） */
        UNKNOWN,
        ONLINE,
        OFFLINE
    }

    private val _mode = MutableStateFlow(Mode.UNKNOWN)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var pendingJob: Job? = null
    private var pendingTarget: Mode? = null

    @Volatile
    private var started = false

    val isOffline: Boolean get() = _mode.value == Mode.OFFLINE

    /** 开始跟随链路状态（由 SourceAccelerationController.init 调用，幂等） */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            ServerLinkState.state.collect { onLink(it) }
        }
    }

    /** 登录成功：服务器确认可用，立即在线 */
    fun onLoginSucceeded() {
        synchronized(lock) {
            cancelPendingLocked()
            set(Mode.ONLINE, "登录成功")
        }
    }

    /** 冷启动检测登录失败：本进程还没在线过，直接本地模式 */
    fun onStartupLoginFailed() {
        synchronized(lock) {
            if (_mode.value != Mode.UNKNOWN) return
            cancelPendingLocked()
            set(Mode.OFFLINE, "启动时服务器无法登录")
        }
    }

    /**
     * 判定恢复在线后自动登录却失败（服务器刚起来、鉴权暂不可用）：回到本地模式，
     * 链路仍在线的话 [ONLINE_CONFIRM_MS] 后再给一次机会。
     */
    fun onLoginFailed() {
        synchronized(lock) {
            cancelPendingLocked()
            set(Mode.OFFLINE, "自动登录失败")
            if (ServerLinkState.state.value.link == ServerLinkState.Link.ONLINE) {
                scheduleLocked(Mode.ONLINE, ONLINE_CONFIRM_MS)
            }
        }
    }

    private fun onLink(s: ServerLinkState.State) {
        synchronized(lock) {
            when (s.link) {
                ServerLinkState.Link.ONLINE -> when (_mode.value) {
                    // 离线中链路恢复：再稳定 30 秒才切回在线
                    Mode.OFFLINE -> if (pendingTarget != Mode.ONLINE) scheduleLocked(Mode.ONLINE, ONLINE_CONFIRM_MS)
                    // 在线期间的离线计时被一次恢复打断：重新计时
                    Mode.ONLINE -> if (pendingTarget == Mode.OFFLINE) cancelPendingLocked()
                    // 冷启动未确认：只有登录结果能确认在线（链路刚绑定时默认 ONLINE，不能当真）
                    Mode.UNKNOWN -> Unit
                }
                ServerLinkState.Link.LOST -> when (_mode.value) {
                    Mode.ONLINE -> if (pendingTarget != Mode.OFFLINE) scheduleLocked(Mode.OFFLINE, OFFLINE_CONFIRM_MS)
                    Mode.UNKNOWN -> {
                        cancelPendingLocked()
                        set(Mode.OFFLINE, "启动时服务器离线")
                    }
                    Mode.OFFLINE -> if (pendingTarget == Mode.ONLINE) cancelPendingLocked()
                }
                // 服务器在 App 内被删除：没有服务器可用
                ServerLinkState.Link.DETACHED -> {
                    cancelPendingLocked()
                    set(Mode.OFFLINE, "服务器已删除")
                }
                ServerLinkState.Link.NONE -> Unit
            }
        }
    }

    private fun scheduleLocked(target: Mode, delayMs: Long) {
        pendingJob?.cancel()
        pendingTarget = target
        pendingJob = scope.launch {
            delay(delayMs)
            synchronized(lock) {
                if (pendingTarget != target) return@launch
                val link = ServerLinkState.state.value.link
                val stillValid = when (target) {
                    Mode.ONLINE -> link == ServerLinkState.Link.ONLINE
                    Mode.OFFLINE -> link == ServerLinkState.Link.LOST || link == ServerLinkState.Link.DETACHED
                    Mode.UNKNOWN -> false
                }
                pendingTarget = null
                pendingJob = null
                if (stillValid) set(target, if (target == Mode.ONLINE) "服务器稳定在线 ${delayMs / 1000} 秒" else "服务器持续离线 ${delayMs / 1000} 秒")
            }
        }
    }

    private fun cancelPendingLocked() {
        pendingJob?.cancel()
        pendingJob = null
        pendingTarget = null
    }

    private fun set(mode: Mode, reason: String) {
        if (_mode.value == mode) return
        Log.i(TAG, "${_mode.value} -> $mode ($reason)")
        _mode.value = mode
    }
}
