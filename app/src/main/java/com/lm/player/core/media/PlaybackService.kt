package com.lm.player.core.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.app.ServiceCompat
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

    companion object {
        private const val TAG = "PlaybackService"
        const val CHANNEL_ID = "zds_player_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_MEDIA_COMMAND = "com.lm.player.ACTION_MEDIA_COMMAND"
        const val ACTION_STOP_SERVICE = "com.lm.player.ACTION_STOP_SERVICE"
        const val EXTRA_COMMAND = "EXTRA_COMMAND"
        const val CMD_PLAY = "CMD_PLAY"
        const val CMD_PAUSE = "CMD_PAUSE"
        const val CMD_TOGGLE = "CMD_TOGGLE"
        const val CMD_NEXT = "CMD_NEXT"
        const val CMD_PREV = "CMD_PREV"

        /**
         * 彻底关闭播放服务并停止音乐播放与后台灵动岛
         */
        fun stopServiceAndPlayback(context: Context) {
            try {
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

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()

            // 1. 获取全局单例 ExoPlayer
            val rawPlayer = Media3Factory.getSharedExoPlayer(this)
            exoPlayer = rawPlayer

            // 2. 使用 ForwardingPlayer 包装 ExoPlayer，向系统 MediaSession 声明始终支持上一首/下一首/拖拽与元数据指令
            //    确保小米澎湃超级岛、vivo原子岛、OPPO流体云、荣耀灵动胶囊及锁屏媒体卡持续稳定识别完整播控与曲目信息
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

            // 3. 建立 MediaSession 并挂载车载按键回调
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

            // 关键：将 MediaSession 注册至 MediaSessionService，确保系统级媒体控制中心与厂商灵动岛识别
            addSession(session)

            // 4. 挂载全品牌安卓灵动岛 MediaNotification.Provider
            DynamicIslandManager.ensureInitialized(this)
            setMediaNotificationProvider(DynamicIslandManager.createMediaNotificationProvider(this))

            // 5. 立即发布初始前台通知（彻底杜绝 Android 8.0+ 5秒启动超时闪退）
            startImmediateForeground()

            // 6. 监听播放状态与曲目切换，平滑同步系统原生媒体通知与悬浮胶囊
            rawPlayer.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateForegroundNotification(isPlaying)
                    BackgroundIslandOverlayController.refreshVisibilityAndState(this@PlaybackService)
                }

                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    DynamicIslandManager.clearLyrics()
                    updateForegroundNotification(rawPlayer.isPlaying)
                    BackgroundIslandOverlayController.refreshVisibilityAndState(this@PlaybackService)
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize PlaybackService", e)
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
            try {
                BackgroundIslandOverlayController.destroy()
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
            updateForegroundNotification(exoPlayer?.isPlaying == true)
            return START_STICKY
        }
        startImmediateForeground()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            BackgroundIslandOverlayController.destroy()
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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
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

    private fun buildNotification(isPlaying: Boolean): Notification {
        return DynamicIslandManager.buildIslandNotification(
            context = this,
            mediaSession = mediaSession,
            exoPlayer = exoPlayer
        )
    }

    private fun startImmediateForeground() {
        try {
            val notification = buildNotification(exoPlayer?.isPlaying == true)
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
        try {
            BackgroundIslandOverlayController.destroy()
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
