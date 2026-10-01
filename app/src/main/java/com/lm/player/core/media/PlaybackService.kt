package com.lm.player.core.media

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.lm.player.MainActivity
import com.lm.player.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 车机/手机后台强力保活锁与静音音轨句柄
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var silentKeepAliveAudioTrack: AudioTrack? = null
    private var keepAliveWatchdogJob: Job? = null

    // 车载导航混音压音 (Ducking) 与通话焦点恢复管理
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isAudioFocusRegistered = false
    private var pausedByPhoneCall = false
    private var duckRestoreJob: Job? = null

    companion object {
        private const val TAG = "PlaybackService"
        const val CHANNEL_ID = "zds_player_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_MEDIA_COMMAND = "com.lm.player.ACTION_MEDIA_COMMAND"
        const val ACTION_STOP_SERVICE = "com.lm.player.ACTION_STOP_SERVICE"
        const val ACTION_KEEP_ALIVE_REVIVE = "com.lm.player.ACTION_KEEP_ALIVE_REVIVE"
        const val EXTRA_COMMAND = "EXTRA_COMMAND"
        const val CMD_PLAY = "CMD_PLAY"
        const val CMD_PAUSE = "CMD_PAUSE"
        const val CMD_TOGGLE = "CMD_TOGGLE"
        const val CMD_NEXT = "CMD_NEXT"
        const val CMD_PREV = "CMD_PREV"

        // 仅在手机端关闭「启用挂后台手机灵动岛」时屏蔽的手机系统通知中心/状态栏胶囊包名（不影响车机桌面与蓝牙播控）
        private val PHONE_SYSTEM_ISLAND_PACKAGES = setOf(
            "com.android.systemui",
            "com.miui.notification",
            "com.vivo.systemuiplugin",
            "com.oplus.systemui",
            "com.huawei.systemserver"
        )

        @Volatile
        private var activeInstance: PlaybackService? = null

        @Volatile
        private var isExplicitStopping: Boolean = false

        /**
         * 确保后台播放服务处于活跃前台状态（车机切后台、切歌、Activity 回收时调用）
         */
        fun ensureServiceAlive(context: Context) {
            try {
                isExplicitStopping = false
                val appCtx = context.applicationContext
                val serviceIntent = Intent(appCtx, PlaybackService::class.java).apply {
                    action = ACTION_KEEP_ALIVE_REVIVE
                }
                ContextCompat.startForegroundService(appCtx, serviceIntent)
            } catch (e: Throwable) {
                Log.w(TAG, "ensureServiceAlive startForegroundService warning: ${e.message}")
            }
        }

        /**
         * 当用户在设置中切换「启用挂后台手机灵动岛」总开关时，实时同步系统通知样式与状态，
         * 确保关闭开关后小米澎湃超级岛、vivo原子岛、OPPO流体云、荣耀灵动胶囊、华为实况窗立即下岛且不再复现，
         * 同时保持 PlaybackService 的前台保活状态不中断。
         */
        fun syncSystemIslandMasterSwitch(context: Context, enabled: Boolean) {
            try {
                if (!enabled) {
                    BackgroundIslandOverlayController.destroy()
                }
                activeInstance?.onSystemIslandMasterSwitchChanged(enabled)
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing system island switch", e)
            }
        }

        /**
         * 仅在用户主动触发「彻底退出程序」时调用：停止音乐播放、释放保活锁并关闭服务
         */
        fun stopServiceAndPlayback(context: Context) {
            try {
                isExplicitStopping = true
                BackgroundIslandOverlayController.destroy()
                val player = Media3Factory.getSharedExoPlayer(context)
                val currentPos = player.currentPosition.takeIf { it > 0L }
                PlaybackQueueManager.savePlaybackState(context, positionMs = currentPos, commitSync = true)
                player.stop()
                player.clearMediaItems()
                val stopIntent = Intent(context, PlaybackService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
                context.startService(stopIntent)
                context.stopService(Intent(context, PlaybackService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping PlaybackService", e)
            }
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val player = exoPlayer ?: return@OnAudioFocusChangeListener
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                duckRestoreJob?.cancel()
                if (AudioSharingManager.activeCastDevice.value == null) {
                    player.volume = 1.0f
                }
                if (pausedByPhoneCall) {
                    pausedByPhoneCall = false
                    player.play()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 车机高德/百度导航播报、倒车雷达提示音：平滑压低音乐音量至 25%，绝不暂停播放
                if (AudioSharingManager.activeCastDevice.value == null && player.isPlaying) {
                    player.volume = 0.25f
                    scheduleDuckAutoRestore(player, delayMs = 6000L)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS -> {
                val mode = audioManager?.mode ?: AudioManager.MODE_NORMAL
                val isPhoneCall = mode == AudioManager.MODE_IN_CALL ||
                        mode == AudioManager.MODE_IN_COMMUNICATION ||
                        mode == AudioManager.MODE_RINGTONE

                if (isPhoneCall) {
                    // 真实电话/语音通话接入：暂停音乐，待挂断后自动恢复
                    if (player.isPlaying) {
                        pausedByPhoneCall = true
                        player.pause()
                    }
                } else {
                    // 车机导航/车载语音助手/雷达强抢焦点：采用压音混音策略保活，防止车机后台音乐被永久掐断
                    if (AudioSharingManager.activeCastDevice.value == null && player.isPlaying) {
                        player.volume = 0.25f
                        scheduleDuckAutoRestore(player, delayMs = 4500L)
                    }
                }
            }
        }
    }

    private fun scheduleDuckAutoRestore(player: ExoPlayer, delayMs: Long) {
        duckRestoreJob?.cancel()
        duckRestoreJob = serviceScope.launch {
            delay(delayMs)
            val mode = audioManager?.mode ?: AudioManager.MODE_NORMAL
            val inCall = mode == AudioManager.MODE_IN_CALL ||
                    mode == AudioManager.MODE_IN_COMMUNICATION ||
                    mode == AudioManager.MODE_RINGTONE
            if (!inCall && AudioSharingManager.activeCastDevice.value == null) {
                player.volume = 1.0f
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isExplicitStopping = false
        activeInstance = this
        try {
            createNotificationChannel()
            acquireKeepAliveLocks()
            startSilentAudioKeepAliveTrack()

            // 1. 获取全局单例 ExoPlayer 并确保后台播放队列监听器已挂载
            val rawPlayer = Media3Factory.getSharedExoPlayer(this)
            exoPlayer = rawPlayer
            PlaybackQueueManager.ensurePlayerListener(applicationContext)
            registerCarSmartAudioFocus()

            // 2. 使用 ForwardingPlayer 包装 ExoPlayer，向系统 MediaSession 声明始终支持上一首/下一首/拖拽与元数据指令
            val forwardingPlayer = object : ForwardingPlayer(rawPlayer) {
                override fun getAvailableCommands(): Player.Commands {
                    return super.getAvailableCommands().buildUpon()
                        .add(Player.COMMAND_SEEK_TO_NEXT)
                        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .add(Player.COMMAND_PLAY_PAUSE)
                        .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                        .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                        .add(Player.COMMAND_GET_METADATA)
                        .add(Player.COMMAND_GET_TIMELINE)
                        .build()
                }

                override fun isCommandAvailable(command: Int): Boolean {
                    return when (command) {
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_METADATA,
                        Player.COMMAND_GET_TIMELINE -> true
                        else -> super.isCommandAvailable(command)
                    }
                }

                override fun seekToNext() {
                    PlaybackQueueManager.playNext(this@PlaybackService)
                    dispatchBroadcast(CMD_NEXT)
                }

                override fun seekToNextMediaItem() {
                    PlaybackQueueManager.playNext(this@PlaybackService)
                    dispatchBroadcast(CMD_NEXT)
                }

                override fun seekToPrevious() {
                    PlaybackQueueManager.playPrevious(this@PlaybackService)
                    dispatchBroadcast(CMD_PREV)
                }

                override fun seekToPreviousMediaItem() {
                    PlaybackQueueManager.playPrevious(this@PlaybackService)
                    dispatchBroadcast(CMD_PREV)
                }
            }

            // 3. 建立 MediaSession 并挂载车载方向盘按键回调
            val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val sessionActivityPendingIntent = PendingIntent.getActivity(
                this,
                0,
                sessionActivityIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val sessionCallback = object : MediaSession.Callback {
                private var lastKeyTimestamp = 0L

                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(CMD_NEXT, Bundle.EMPTY))
                        .add(SessionCommand(CMD_PREV, Bundle.EMPTY))
                        .add(SessionCommand(CMD_TOGGLE, Bundle.EMPTY))
                        .add(SessionCommand(CMD_PLAY, Bundle.EMPTY))
                        .add(SessionCommand(CMD_PAUSE, Bundle.EMPTY))
                        .build()
                    val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .add(Player.COMMAND_SEEK_TO_NEXT)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .add(Player.COMMAND_PLAY_PAUSE)
                        .add(Player.COMMAND_PREPARE)
                        .add(Player.COMMAND_STOP)
                        .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                        .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                        .add(Player.COMMAND_GET_METADATA)
                        .add(Player.COMMAND_GET_TIMELINE)
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .setAvailablePlayerCommands(playerCommands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        CMD_NEXT -> {
                            PlaybackQueueManager.playNext(this@PlaybackService)
                            dispatchBroadcast(CMD_NEXT)
                        }
                        CMD_PREV -> {
                            PlaybackQueueManager.playPrevious(this@PlaybackService)
                            dispatchBroadcast(CMD_PREV)
                        }
                        CMD_TOGGLE -> {
                            PlaybackQueueManager.togglePlay(this@PlaybackService)
                            dispatchBroadcast(CMD_TOGGLE)
                        }
                        CMD_PLAY -> {
                            val p = Media3Factory.getSharedExoPlayer(this@PlaybackService)
                            if (!p.isPlaying) PlaybackQueueManager.togglePlay(this@PlaybackService)
                            dispatchBroadcast(CMD_PLAY)
                        }
                        CMD_PAUSE -> {
                            val p = Media3Factory.getSharedExoPlayer(this@PlaybackService)
                            if (p.isPlaying) PlaybackQueueManager.togglePlay(this@PlaybackService)
                            dispatchBroadcast(CMD_PAUSE)
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                override fun onMediaButtonEvent(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    intent: Intent
                ): Boolean {
                    val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }

                    if (keyEvent != null) {
                        val now = System.currentTimeMillis()
                        if (now - lastKeyTimestamp < 250) {
                            return true
                        }
                        if (keyEvent.action == KeyEvent.ACTION_DOWN || (keyEvent.action == KeyEvent.ACTION_UP && keyEvent.repeatCount == 0)) {
                            lastKeyTimestamp = now
                            Log.i(TAG, "Received MediaButton KeyEvent: ${keyEvent.keyCode}, action: ${keyEvent.action}")
                            when (keyEvent.keyCode) {
                                KeyEvent.KEYCODE_MEDIA_NEXT,
                                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
                                KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
                                KeyEvent.KEYCODE_BUTTON_R1,
                                KeyEvent.KEYCODE_CHANNEL_UP,
                                KeyEvent.KEYCODE_NAVIGATE_NEXT,
                                KeyEvent.KEYCODE_PAGE_DOWN -> {
                                    PlaybackQueueManager.playNext(this@PlaybackService)
                                    dispatchBroadcast(CMD_NEXT)
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                                KeyEvent.KEYCODE_MEDIA_REWIND,
                                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
                                KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
                                KeyEvent.KEYCODE_BUTTON_L1,
                                KeyEvent.KEYCODE_CHANNEL_DOWN,
                                KeyEvent.KEYCODE_NAVIGATE_PREVIOUS,
                                KeyEvent.KEYCODE_PAGE_UP -> {
                                    PlaybackQueueManager.playPrevious(this@PlaybackService)
                                    dispatchBroadcast(CMD_PREV)
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                                KeyEvent.KEYCODE_HEADSETHOOK,
                                KeyEvent.KEYCODE_BUTTON_START -> {
                                    PlaybackQueueManager.togglePlay(this@PlaybackService)
                                    dispatchBroadcast(CMD_TOGGLE)
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                    val p = Media3Factory.getSharedExoPlayer(this@PlaybackService)
                                    if (!p.isPlaying) PlaybackQueueManager.togglePlay(this@PlaybackService)
                                    dispatchBroadcast(CMD_PLAY)
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_PAUSE,
                                KeyEvent.KEYCODE_MEDIA_STOP -> {
                                    val p = Media3Factory.getSharedExoPlayer(this@PlaybackService)
                                    if (p.isPlaying) PlaybackQueueManager.togglePlay(this@PlaybackService)
                                    dispatchBroadcast(CMD_PAUSE)
                                    return true
                                }
                            }
                        }
                    }
                    return super.onMediaButtonEvent(session, controllerInfo, intent)
                }

                @Deprecated("Deprecated in Java")
                override fun onPlayerCommandRequest(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    playerCommand: Int
                ): Int {
                    when (playerCommand) {
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                            PlaybackQueueManager.playNext(this@PlaybackService)
                            dispatchBroadcast(CMD_NEXT)
                            return SessionResult.RESULT_SUCCESS
                        }
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                            PlaybackQueueManager.playPrevious(this@PlaybackService)
                            dispatchBroadcast(CMD_PREV)
                            return SessionResult.RESULT_SUCCESS
                        }
                        Player.COMMAND_PLAY_PAUSE -> {
                            PlaybackQueueManager.togglePlay(this@PlaybackService)
                            dispatchBroadcast(CMD_TOGGLE)
                            return SessionResult.RESULT_SUCCESS
                        }
                    }
                    return super.onPlayerCommandRequest(session, controllerInfo, playerCommand)
                }
            }

            val session = MediaSession.Builder(this, forwardingPlayer)
                .setSessionActivity(sessionActivityPendingIntent)
                .setCallback(sessionCallback)
                .build()
            mediaSession = session
            DynamicIslandManager.bindMediaSession(session)
            DynamicIslandManager.ensureInitialized(this)

            // 4. 挂载全品牌安卓灵动岛 MediaNotification.Provider 并始终注册 Session 以保障车机方向盘与后台前台服务存活
            setMediaNotificationProvider(DynamicIslandManager.createMediaNotificationProvider(this))
            addSession(session)

            // 5. 立即发布初始前台通知并启动后台常驻保活巡检看门狗
            startImmediateForeground()
            startKeepAliveWatchdog()

            // 6. 监听播放状态与曲目切换，平滑同步系统原生媒体通知、音频焦点与悬浮胶囊
            rawPlayer.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        registerCarSmartAudioFocus()
                        ensureKeepAliveLocksHeld()
                        ensureSilentAudioTrackPlaying()
                    }
                    startImmediateForeground()
                    updateForegroundNotification(isPlaying)
                    BackgroundIslandOverlayController.refreshVisibilityAndState(this@PlaybackService)
                }

                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    DynamicIslandManager.clearLyrics()
                    ensureKeepAliveLocksHeld()
                    startImmediateForeground()
                    updateForegroundNotification(rawPlayer.isPlaying)
                    BackgroundIslandOverlayController.refreshVisibilityAndState(this@PlaybackService)
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize PlaybackService", e)
        }
    }

    /**
     * 接管 Media3 的通知更新回调，严禁 Media3 内部 MediaNotificationManager 在切歌缓冲或暂停时调用 stopForeground 降级服务，
     * 确保车机与手机挂后台期间 100% 维持 FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK 前台优先级。
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (isExplicitStopping) return
        startImmediateForeground()
    }

    /**
     * 当用户在设置中开启/关闭「启用挂后台手机灵动岛」时：
     * - 关闭时：先清除旧 MediaStyle 通知再重发无 EXTRA_MEDIA_SESSION 的纯前台保活通知，强制手机系统超级岛/原子岛/流体云立刻下岛，同时保持前台服务不掉线；
     * - 开启时：重新推送绑定 MediaSession 的 MediaStyle 通知，立即恢复手机原生上岛。
     */
    fun onSystemIslandMasterSwitchChanged(enabled: Boolean) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!enabled) {
                BackgroundIslandOverlayController.destroy()
                runCatching { manager.cancel(NOTIFICATION_ID) }
                startImmediateForeground()
            } else {
                startImmediateForeground()
                updateForegroundNotification(exoPlayer?.isPlaying == true)
                BackgroundIslandOverlayController.refreshVisibilityAndState(this)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error handling island master switch change", e)
        }
    }

    /**
     * 申请 CPU 唤醒锁 (PARTIAL_WAKE_LOCK) 与高性能 Wi-Fi 锁，防止车机切入导航后台或手机息屏后 CPU/网卡休眠断流
     */
    private fun acquireKeepAliveLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "LMPlayer:CarMediaKeepAliveWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to acquire PARTIAL_WAKE_LOCK", e)
        }

        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(lockMode, "LMPlayer:CarMediaKeepAliveWifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to acquire WifiLock", e)
        }
    }

    private fun ensureKeepAliveLocksHeld() {
        try {
            if (wakeLock?.isHeld != true || wifiLock?.isHeld != true) {
                acquireKeepAliveLocks()
            }
        } catch (_: Throwable) {}
    }

    private fun releaseKeepAliveLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Throwable) {}
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Throwable) {}
    }

    /**
     * 启动零 CPU 占用的硬件级静音 AudioTrack 循环（MODE_STATIC）：
     * 许多安卓车机系统（如比亚迪 DiLink、吉利银河/Flyme Auto、方易通/掌讯/8155车机）通过检测 AudioFlinger 是否有活跃音轨判定后台音乐应用活性。
     * 在切歌缓冲或短暂暂停间隙维持一条 0 音量的静态 PCM 静音环路，可彻底阻止车机杀后台进程，且不占用 CPU、不发出任何底噪、不抢占其他应用音频焦点。
     */
    private fun startSilentAudioKeepAliveTrack() {
        if (silentKeepAliveAudioTrack != null) return
        try {
            val sampleRate = 8000
            val frameCount = 800 // 0.1 秒静音帧
            val bufferSizeBytes = frameCount * 2 // 16-bit mono = 2 bytes/frame
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(bufferSizeBytes)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    PlatformAudioAttributes.Builder()
                        .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                        .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            val silentBytes = ByteArray(minBuf)
            val totalFrames = minBuf / 2
            track.write(silentBytes, 0, silentBytes.size)
            track.setVolume(0f)
            track.setLoopPoints(0, totalFrames, -1)
            track.play()
            silentKeepAliveAudioTrack = track
            Log.i(TAG, "Car silent keep-alive AudioTrack started (MODE_STATIC)")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to start silent keep-alive AudioTrack: ${e.message}")
        }
    }

    private fun ensureSilentAudioTrackPlaying() {
        try {
            val track = silentKeepAliveAudioTrack
            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                silentKeepAliveAudioTrack = null
                startSilentAudioKeepAliveTrack()
            } else if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.setVolume(0f)
                track.play()
            }
        } catch (_: Throwable) {}
    }

    private fun releaseSilentAudioKeepAliveTrack() {
        try {
            silentKeepAliveAudioTrack?.let { track ->
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    runCatching { track.stop() }
                }
                runCatching { track.release() }
            }
        } catch (_: Throwable) {}
        silentKeepAliveAudioTrack = null
    }

    private fun registerCarSmartAudioFocus() {
        try {
            val am = audioManager ?: (getSystemService(Context.AUDIO_SERVICE) as? AudioManager).also {
                audioManager = it
            } ?: return
            if (isAudioFocusRegistered) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        PlatformAudioAttributes.Builder()
                            .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                            .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setWillPauseWhenDucked(false)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
                audioFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
            isAudioFocusRegistered = true
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to register audio focus", e)
        }
    }

    private fun abandonCarSmartAudioFocus() {
        try {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(audioFocusChangeListener)
            }
            isAudioFocusRegistered = false
        } catch (_: Throwable) {}
    }

    /**
     * 后台常驻保活巡检看门狗：
     * 每 8 秒刷新一次前台服务绑定、唤醒锁状态、静音保活音轨与播放进度持久化，防止车机系统定时清理器回收后台服务
     */
    private fun startKeepAliveWatchdog() {
        keepAliveWatchdogJob?.cancel()
        keepAliveWatchdogJob = serviceScope.launch {
            while (isActive && !isExplicitStopping) {
                delay(8000L)
                if (isExplicitStopping) break
                try {
                    ensureKeepAliveLocksHeld()
                    ensureSilentAudioTrackPlaying()
                    startImmediateForeground()
                    val player = exoPlayer
                    if (player != null && player.isPlaying) {
                        val pos = player.currentPosition.coerceAtLeast(0L)
                        if (pos > 0L) {
                            PlaybackQueueManager.savePlaybackState(
                                context = applicationContext,
                                positionMs = pos,
                                commitSync = false
                            )
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    private fun scheduleSelfRevivalAlarm() {
        if (isExplicitStopping) return
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val reviveIntent = Intent(applicationContext, PlaybackService::class.java).apply {
                action = ACTION_KEEP_ALIVE_REVIVE
            }
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(applicationContext, 9001, reviveIntent, flags)
            } else {
                PendingIntent.getService(applicationContext, 9001, reviveIntent, flags)
            }
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1200L,
                pendingIntent
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to schedule self-revival alarm: ${e.message}")
        }
    }

    private fun dispatchBroadcast(command: String) {
        try {
            val intent = Intent(ACTION_MEDIA_COMMAND).apply {
                putExtra(EXTRA_COMMAND, command)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send media command broadcast: $command", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            isExplicitStopping = true
            try {
                keepAliveWatchdogJob?.cancel()
                BackgroundIslandOverlayController.destroy()
                releaseSilentAudioKeepAliveTrack()
                abandonCarSmartAudioFocus()
                releaseKeepAliveLocks()
                val currentPos = exoPlayer?.currentPosition?.takeIf { it > 0L }
                PlaybackQueueManager.savePlaybackState(this, positionMs = currentPos, commitSync = true)
                exoPlayer?.stop()
                exoPlayer?.clearMediaItems()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            } catch (_: Exception) {}
            return START_NOT_STICKY
        }

        isExplicitStopping = false
        ensureKeepAliveLocksHeld()
        ensureSilentAudioTrackPlaying()

        if (intent?.action == ACTION_MEDIA_COMMAND) {
            val cmd = intent.getStringExtra(EXTRA_COMMAND)
            when (cmd) {
                CMD_NEXT -> {
                    PlaybackQueueManager.playNext(this)
                    dispatchBroadcast(CMD_NEXT)
                }
                CMD_PREV -> {
                    PlaybackQueueManager.playPrevious(this)
                    dispatchBroadcast(CMD_PREV)
                }
                CMD_TOGGLE -> {
                    PlaybackQueueManager.togglePlay(this)
                    dispatchBroadcast(CMD_TOGGLE)
                }
                CMD_PLAY -> {
                    val p = Media3Factory.getSharedExoPlayer(this)
                    if (!p.isPlaying) PlaybackQueueManager.togglePlay(this)
                    dispatchBroadcast(CMD_PLAY)
                }
                CMD_PAUSE -> {
                    val p = Media3Factory.getSharedExoPlayer(this)
                    if (p.isPlaying) PlaybackQueueManager.togglePlay(this)
                    dispatchBroadcast(CMD_PAUSE)
                }
            }
            startImmediateForeground()
            updateForegroundNotification(exoPlayer?.isPlaying == true)
            return START_STICKY
        }

        startImmediateForeground()
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    /**
     * 关键修复：车机系统切换到高德地图/倒车影像/车机桌面时经常自动清理后台 Activity 任务栈并触发 onTaskRemoved。
     * 非用户主动退出时严禁调用 super.onTaskRemoved 或停止 ExoPlayer，而是立即加固前台服务与自唤醒闹钟，确保车机后台持续保活播放！
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (isExplicitStopping) {
            super.onTaskRemoved(rootIntent)
            return
        }
        try {
            val currentPos = exoPlayer?.currentPosition?.takeIf { it > 0L }
            PlaybackQueueManager.savePlaybackState(this, positionMs = currentPos, commitSync = true)
            ensureKeepAliveLocksHeld()
            ensureSilentAudioTrackPlaying()
            startImmediateForeground()
            scheduleSelfRevivalAlarm()
            Log.i(TAG, "onTaskRemoved intercepted: keeping PlaybackService alive in background for car/phone")
        } catch (e: Throwable) {
            Log.e(TAG, "Error in onTaskRemoved keep-alive", e)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        if (!DynamicIslandManager.systemIslandEnabledFlow.value &&
            controllerInfo.packageName in PHONE_SYSTEM_ISLAND_PACKAGES
        ) {
            return null
        }
        return mediaSession
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return DynamicIslandManager.buildIslandNotification(
            context = this,
            mediaSession = mediaSession,
            exoPlayer = exoPlayer
        )
    }

    private fun startImmediateForeground() {
        if (isExplicitStopping) return
        try {
            val notification = buildNotification()
            val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundServiceType)
        } catch (e: Throwable) {
            Log.e(TAG, "Error starting foreground service", e)
        }
    }

    private fun updateForegroundNotification(isPlaying: Boolean) {
        if (isExplicitStopping) return
        try {
            DynamicIslandManager.notifySystemIsland(
                context = this,
                mediaSession = mediaSession,
                exoPlayer = exoPlayer,
                force = true
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error updating foreground notification", e)
        }
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        if (!isExplicitStopping) {
            // 若被车机系统极端内存回收意外销毁，保存当前进度并立即触发自唤醒拉起服务
            val currentPos = exoPlayer?.currentPosition?.takeIf { it > 0L }
            PlaybackQueueManager.savePlaybackState(this, positionMs = currentPos, commitSync = true)
            scheduleSelfRevivalAlarm()
        }
        try {
            keepAliveWatchdogJob?.cancel()
            duckRestoreJob?.cancel()
            BackgroundIslandOverlayController.destroy()
            releaseSilentAudioKeepAliveTrack()
            abandonCarSmartAudioFocus()
            releaseKeepAliveLocks()
            DynamicIslandManager.bindMediaSession(null)
            serviceScope.cancel()
            mediaSession?.run {
                release()
                mediaSession = null
            }
            exoPlayer = null
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        super.onDestroy()
    }
}
