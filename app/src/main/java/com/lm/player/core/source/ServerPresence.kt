package com.lm.player.core.source

import android.content.Context
import android.content.SharedPreferences
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
 * 服务器「可用性」判定（带防抖 + 持续高延迟/断联自动锁定本地模式），只决定**资料库在线 / 本地模式**与服务器专属功能是否显示。
 *
 * 取链、搜索、发现页等数据在本地音源可用时始终走本地（见 [SourceAccelerationController.playbackMode]），
 * 不受这里影响：
 * - 开启自动锁定（默认 30 秒，可配置 关闭 / 15s / 30s / 60s / 120s）：
 *   当服务器持续离线（LOST）或持续高延迟（≥ 3000ms）/ 连续失败满设定阈值，资料库自动锁定到本地模式（LOCKED_LOCAL），
 *   不再因反代时通时断来回抖动，直到用户手动点击「恢复在线」解锁；
 * - 关闭自动锁定（0 秒）时沿用原防抖逻辑：连续离线 60 秒切 OFFLINE，恢复稳定 30 秒自动切回 ONLINE；
 * - 冷启动时服务器就连不上（本进程从未在线过）：不等待，直接本地。
 */
object ServerPresence {
    private const val TAG = "ServerPresence"
    const val OFFLINE_CONFIRM_MS = 60_000L
    const val ONLINE_CONFIRM_MS = 30_000L
    const val HIGH_LATENCY_MS = 3_000L
    const val HEALTHY_LATENCY_MS = 2_000L
    const val DEFAULT_LOCK_SECONDS = 30
    val LOCK_SECOND_OPTIONS = listOf(0, 15, 30, 60, 120)

    private const val PREFS_NAME = "lemon_settings_prefs"
    private const val KEY_LOCK_SECONDS = "server_disconnect_lock_seconds"
    private const val KEY_AUTO_LOCKED = "library_auto_locked"

    enum class Mode {
        /** 冷启动还没确认（登录 / 探活结果未出） */
        UNKNOWN,
        ONLINE,
        OFFLINE,
        /** 服务器长时间断联 / 高延迟不稳定后自动锁定本地，需手动「恢复在线」解锁 */
        LOCKED_LOCAL
    }

    private val _mode = MutableStateFlow(Mode.UNKNOWN)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _disconnectLockSeconds = MutableStateFlow(DEFAULT_LOCK_SECONDS)
    val disconnectLockSeconds: StateFlow<Int> = _disconnectLockSeconds.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var prefs: SharedPreferences? = null
    private var pendingJob: Job? = null
    private var pendingTarget: Mode? = null
    private var unstableTimerJob: Job? = null
    private var unstableSince: Long = 0L
    private var consecutiveUnstable: Int = 0

    @Volatile
    private var started = false

    val isOffline: Boolean get() = _mode.value == Mode.OFFLINE || _mode.value == Mode.LOCKED_LOCAL
    val isAutoLocked: Boolean get() = _mode.value == Mode.LOCKED_LOCAL

    fun init(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = sp
            val savedSec = sp.getInt(KEY_LOCK_SECONDS, DEFAULT_LOCK_SECONDS)
            _disconnectLockSeconds.value = if (savedSec in LOCK_SECOND_OPTIONS) savedSec else DEFAULT_LOCK_SECONDS
            val wasLocked = sp.getBoolean(KEY_AUTO_LOCKED, false)
            if (wasLocked && _disconnectLockSeconds.value > 0) {
                setLocked(Mode.LOCKED_LOCAL, "沿用上次网络不稳定锁定状态")
            }
        }
    }

    fun setDisconnectLockSeconds(seconds: Int) {
        val coerced = if (seconds in LOCK_SECOND_OPTIONS) seconds else DEFAULT_LOCK_SECONDS
        synchronized(lock) {
            _disconnectLockSeconds.value = coerced
            prefs?.edit()?.putInt(KEY_LOCK_SECONDS, coerced)?.apply()
            if (coerced <= 0) {
                clearUnstableLocked()
                if (_mode.value == Mode.LOCKED_LOCAL) {
                    persistAutoLocked(false)
                    val link = ServerLinkState.state.value.link
                    setLocked(
                        if (link == ServerLinkState.Link.ONLINE) Mode.ONLINE else Mode.OFFLINE,
                        "已关闭断联自动锁定"
                    )
                }
            } else if (unstableSince > 0L && _mode.value != Mode.LOCKED_LOCAL) {
                val elapsed = System.currentTimeMillis() - unstableSince
                val thresholdMs = coerced * 1_000L
                if (elapsed >= thresholdMs && (consecutiveUnstable >= 2 || ServerLinkState.isLost)) {
                    engageAutoLockLocked("服务器持续不稳定已满 ${coerced} 秒")
                } else {
                    scheduleUnstableCheckLocked((thresholdMs - elapsed).coerceAtLeast(1_000L))
                }
            }
        }
    }

    fun formatLockSeconds(seconds: Int): String = when (seconds) {
        0 -> "关闭"
        15 -> "15 秒"
        30 -> "30 秒（推荐）"
        60 -> "1 分钟"
        120 -> "2 分钟"
        else -> "${seconds} 秒"
    }

    /** 开始跟随链路状态（由 SourceAccelerationController.init 调用，幂等） */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            ServerLinkState.state.collect { onLink(it) }
        }
    }

    /** 手动点击「恢复在线」或切换在线服务器时解锁，允许重新连接与同步 */
    fun unlockToOnline(reason: String = "用户手动恢复在线") {
        synchronized(lock) {
            clearUnstableLocked()
            cancelPendingLocked()
            persistAutoLocked(false)
            if (_mode.value == Mode.LOCKED_LOCAL) {
                val link = ServerLinkState.state.value.link
                setLocked(if (link == ServerLinkState.Link.ONLINE) Mode.ONLINE else Mode.OFFLINE, reason)
            }
        }
    }

    /** 用户主动切到纯本地模式时清除自动锁定标记 */
    fun clearAutoLockForManualLocal() {
        synchronized(lock) {
            clearUnstableLocked()
            cancelPendingLocked()
            persistAutoLocked(false)
            if (_mode.value == Mode.LOCKED_LOCAL) {
                setLocked(Mode.OFFLINE, "用户手动切换至本地模式")
            }
        }
    }

    /** 登录成功：若未处于自动锁定，确认在线；显式解锁后再登录同样切回在线 */
    fun onLoginSucceeded() {
        synchronized(lock) {
            if (_mode.value == Mode.LOCKED_LOCAL) return
            clearUnstableLocked()
            cancelPendingLocked()
            persistAutoLocked(false)
            setLocked(Mode.ONLINE, "登录成功")
        }
    }

    /** 冷启动检测登录失败：本进程还没在线过，直接本地模式 */
    fun onStartupLoginFailed() {
        synchronized(lock) {
            if (_mode.value != Mode.UNKNOWN) return
            cancelPendingLocked()
            setLocked(Mode.OFFLINE, "启动时服务器无法登录")
        }
    }

    /**
     * 判定恢复在线后自动登录却失败（服务器刚起来、鉴权暂不可用）：回到本地模式，
     * 链路仍在线的话 [ONLINE_CONFIRM_MS] 后再给一次机会。
     */
    fun onLoginFailed() {
        synchronized(lock) {
            if (_mode.value == Mode.LOCKED_LOCAL) return
            cancelPendingLocked()
            setLocked(Mode.OFFLINE, "自动登录失败")
            if (ServerLinkState.state.value.link == ServerLinkState.Link.ONLINE) {
                scheduleLocked(Mode.ONLINE, ONLINE_CONFIRM_MS)
            }
        }
    }

    /**
     * 接收来自控制面 /api/ 请求的耗时与成败采样：
     * 持续高延迟（≥ 3000ms）或连续失败满 [disconnectLockSeconds] 自动锁定资料库为本地模式。
     */
    fun onServerSample(elapsedMs: Long, success: Boolean) {
        val lockSec = _disconnectLockSeconds.value
        if (lockSec <= 0) return
        synchronized(lock) {
            if (_mode.value == Mode.LOCKED_LOCAL) return
            if (success && elapsedMs < HEALTHY_LATENCY_MS) {
                clearUnstableLocked()
                return
            }
            if (!success || elapsedMs >= HIGH_LATENCY_MS) {
                val now = System.currentTimeMillis()
                consecutiveUnstable++
                if (unstableSince == 0L) {
                    unstableSince = now
                    scheduleUnstableCheckLocked(lockSec * 1_000L)
                }
                val elapsedUnstable = now - unstableSince
                if (elapsedUnstable >= lockSec * 1_000L && (consecutiveUnstable >= 2 || ServerLinkState.isLost)) {
                    val cause = if (!success) "服务器连续请求失败" else "服务器持续高延迟 (${elapsedMs}ms)"
                    engageAutoLockLocked("$cause 已满 ${lockSec} 秒")
                }
            }
        }
    }

    private fun onLink(s: ServerLinkState.State) {
        synchronized(lock) {
            val lockSec = _disconnectLockSeconds.value
            when (s.link) {
                ServerLinkState.Link.ONLINE -> when (_mode.value) {
                    // 已自动锁定本地：保持锁定直到用户手动点击「恢复在线」
                    Mode.LOCKED_LOCAL -> Unit
                    // 离线中链路恢复：再稳定 30 秒才切回在线
                    Mode.OFFLINE -> {
                        clearUnstableLocked()
                        if (pendingTarget != Mode.ONLINE) scheduleLocked(Mode.ONLINE, ONLINE_CONFIRM_MS)
                    }
                    // 在线期间的离线/锁定计时被恢复打断：重新计时
                    Mode.ONLINE -> {
                        clearUnstableLocked()
                        if (pendingTarget == Mode.OFFLINE || pendingTarget == Mode.LOCKED_LOCAL) cancelPendingLocked()
                    }
                    // 冷启动未确认：只有登录结果能确认在线（链路刚绑定时默认 ONLINE，不能当真）
                    Mode.UNKNOWN -> Unit
                }
                ServerLinkState.Link.LOST -> when (_mode.value) {
                    Mode.LOCKED_LOCAL -> Unit
                    Mode.ONLINE -> {
                        if (lockSec > 0) {
                            if (unstableSince == 0L) unstableSince = System.currentTimeMillis()
                            if (pendingTarget != Mode.LOCKED_LOCAL) {
                                scheduleLocked(Mode.LOCKED_LOCAL, lockSec * 1_000L)
                            }
                        } else if (pendingTarget != Mode.OFFLINE) {
                            scheduleLocked(Mode.OFFLINE, OFFLINE_CONFIRM_MS)
                        }
                    }
                    Mode.UNKNOWN -> {
                        cancelPendingLocked()
                        setLocked(Mode.OFFLINE, "启动时服务器离线")
                        if (lockSec > 0) {
                            if (unstableSince == 0L) unstableSince = System.currentTimeMillis()
                            scheduleLocked(Mode.LOCKED_LOCAL, lockSec * 1_000L)
                        }
                    }
                    Mode.OFFLINE -> {
                        if (pendingTarget == Mode.ONLINE) cancelPendingLocked()
                        if (lockSec > 0 && pendingTarget != Mode.LOCKED_LOCAL) {
                            if (unstableSince == 0L) unstableSince = System.currentTimeMillis()
                            scheduleLocked(Mode.LOCKED_LOCAL, lockSec * 1_000L)
                        }
                    }
                }
                // 服务器在 App 内被删除：没有服务器可用
                ServerLinkState.Link.DETACHED -> {
                    clearUnstableLocked()
                    cancelPendingLocked()
                    persistAutoLocked(false)
                    setLocked(Mode.OFFLINE, "服务器已删除")
                }
                ServerLinkState.Link.NONE -> Unit
            }
        }
    }

    private fun engageAutoLockLocked(reason: String) {
        cancelPendingLocked()
        unstableTimerJob?.cancel()
        unstableTimerJob = null
        persistAutoLocked(true)
        setLocked(Mode.LOCKED_LOCAL, reason)
    }

    private fun scheduleUnstableCheckLocked(delayMs: Long) {
        unstableTimerJob?.cancel()
        unstableTimerJob = scope.launch {
            delay(delayMs)
            synchronized(lock) {
                val lockSec = _disconnectLockSeconds.value
                if (lockSec <= 0 || _mode.value == Mode.LOCKED_LOCAL) return@launch
                if (unstableSince == 0L) return@launch
                val elapsed = System.currentTimeMillis() - unstableSince
                if (elapsed >= lockSec * 1_000L && (ServerLinkState.isLost || consecutiveUnstable >= 2)) {
                    engageAutoLockLocked("服务器持续断联/不稳定已满 ${lockSec} 秒")
                }
            }
        }
    }

    private fun clearUnstableLocked() {
        unstableSince = 0L
        consecutiveUnstable = 0
        unstableTimerJob?.cancel()
        unstableTimerJob = null
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
                    Mode.ONLINE -> link == ServerLinkState.Link.ONLINE && _mode.value != Mode.LOCKED_LOCAL
                    Mode.OFFLINE -> link == ServerLinkState.Link.LOST || link == ServerLinkState.Link.DETACHED
                    Mode.LOCKED_LOCAL -> _disconnectLockSeconds.value > 0 &&
                        (link == ServerLinkState.Link.LOST || unstableSince > 0L)
                    Mode.UNKNOWN -> false
                }
                pendingTarget = null
                pendingJob = null
                if (stillValid) {
                    if (target == Mode.LOCKED_LOCAL) {
                        engageAutoLockLocked("服务器持续离线/不稳定满 ${delayMs / 1000} 秒，资料库已锁定本地")
                    } else {
                        setLocked(
                            target,
                            if (target == Mode.ONLINE) "服务器稳定在线 ${delayMs / 1000} 秒"
                            else "服务器持续离线 ${delayMs / 1000} 秒"
                        )
                    }
                }
            }
        }
    }

    private fun cancelPendingLocked() {
        pendingJob?.cancel()
        pendingJob = null
        pendingTarget = null
    }

    private fun persistAutoLocked(locked: Boolean) {
        prefs?.edit()?.putBoolean(KEY_AUTO_LOCKED, locked)?.apply()
    }

    private fun setLocked(mode: Mode, reason: String) {
        if (_mode.value == mode) return
        Log.i(TAG, "${_mode.value} -> $mode ($reason)")
        _mode.value = mode
    }
}
