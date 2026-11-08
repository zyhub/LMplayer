package com.lm.player.core.podcast

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.media.AudioSharingManager
import com.lm.player.core.media.Media3Factory
import com.lm.player.core.media.PlaybackQueueManager
import com.lm.player.core.model.UnifiedSong
import com.lm.player.feature.player.AudioOutputDropdownMenu
import com.lm.player.feature.podcast.PodcastEpisodeSheet
import com.lm.player.feature.podcast.PodcastHomeScreen
import com.lm.player.feature.podcast.PodcastManageScreen
import com.lm.player.feature.podcast.PodcastPlayerScreen
import com.lm.player.feature.podcast.PodcastSearchScreen
import com.lm.player.feature.podcast.PodcastShowScreen
import kotlinx.coroutines.delay

/**
 * 播客页面宿主：把 PodcastController 的状态转成 *Ui 对象交给 Antigravity 的无状态页面，
 * 并负责页面栈切换动画与返回键。
 */
@Composable
fun PodcastScreenHost(
    contentPadding: PaddingValues,
    onExit: () -> Unit,
    onGoToServerSettings: () -> Unit,
    onOpenPlayer: () -> Unit
) {
    val s by PodcastController.state.collectAsState()
    val progressVersion by PodcastProgressStore.version.collectAsState()
    val currentSong by PlaybackQueueManager.currentSongFlow.collectAsState()
    val isPlaying by PlaybackQueueManager.isPlayingFlow.collectAsState()
    val np = PodcastController.nowPlaying(currentSong, isPlaying)

    val exit by rememberUpdatedState(onExit)
    val goSettings by rememberUpdatedState(onGoToServerSettings)
    val openPlayer by rememberUpdatedState(onOpenPlayer)
    val actions = remember {
        PodcastController.actions(
            onExit = { exit() },
            onGoToServerSettings = { goSettings() },
            onOpenPlayer = { openPlayer() }
        )
    }

    // 每次进入播客页都后台刷新一次（已有数据照常显示）
    LaunchedEffect(Unit) { PodcastController.loadHome(userRefresh = false) }
    // 通过底栏等方式离开播客页时，下次进入从播客主页开始
    DisposableEffect(Unit) { onDispose { PodcastController.resetToHome() } }

    BackHandler(enabled = true) { actions.onBack() }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = s.routes.last(),
            transitionSpec = {
                // 进入下一级：从右侧轻推入；返回：淡出
                val forward = s.routes.lastOrNull() == targetState && s.routes.size > 1 &&
                    s.routes.getOrNull(s.routes.size - 2) == initialState
                if (forward) {
                    (slideInHorizontally(tween(220)) { it / 4 } + fadeIn(tween(220))) togetherWith fadeOut(tween(150))
                } else {
                    fadeIn(tween(200)) togetherWith (slideOutHorizontally(tween(200)) { it / 4 } + fadeOut(tween(200)))
                }
            },
            label = "podcast_route"
        ) { route ->
            when (route) {
                PodcastController.Route.Home -> {
                    val ui = remember(s, progressVersion, np) { PodcastController.homeUi(s, np) }
                    PodcastHomeScreen(ui = ui, actions = actions, contentPadding = contentPadding)
                }
                is PodcastController.Route.Show -> {
                    val ui = remember(s, progressVersion, np, route.feedId) { PodcastController.showDetailUi(s, route.feedId, np) }
                    PodcastShowScreen(ui = ui, actions = actions, contentPadding = contentPadding)
                }
                PodcastController.Route.Manage -> {
                    val ui = remember(s) { PodcastController.manageUi(s) }
                    PodcastManageScreen(ui = ui, actions = actions, contentPadding = contentPadding)
                }
                PodcastController.Route.Search -> {
                    val ui = remember(s) { PodcastController.searchUi(s) }
                    // 搜索页没有 contentPadding 参数：整体让出底部悬浮播放栏的高度
                    PodcastSearchScreen(
                        ui = ui,
                        actions = actions,
                        modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding())
                    )
                }
            }
        }

        val sheetEpisode = remember(s, progressVersion, np) { PodcastController.sheetEpisodeUi(s, np) }
        if (sheetEpisode != null) {
            PodcastEpisodeSheet(
                episode = sheetEpisode,
                actions = actions,
                onDismiss = { actions.onCloseEpisode() }
            )
        }
    }
}

/**
 * 播客全屏播放页宿主：当前播放的是播客单集时，代替音乐播放页显示。
 */
@Composable
fun PodcastPlayerHost(
    song: UnifiedSong,
    isPlaying: Boolean,
    onDismiss: () -> Unit,
    onOpenShow: (feedId: String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val s by PodcastController.state.collectAsState()
    val progressVersion by PodcastProgressStore.version.collectAsState()
    val queue by PlaybackQueueManager.playlistFlow.collectAsState()
    val sleepMinutes by PlaybackQueueManager.sleepTimerMinutesFlow.collectAsState()
    val sleepRemaining by PlaybackQueueManager.sleepTimerRemainingSecFlow.collectAsState()
    val castDevice by AudioSharingManager.activeCastDevice.collectAsState()

    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isBuffering by remember { mutableStateOf(false) }

    // 进度轮询（仅在前台），与音乐播放页一致 400ms
    LaunchedEffect(song.id) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val player = Media3Factory.peekSharedExoPlayer()
                if (player != null) {
                    positionMs = player.currentPosition.coerceAtLeast(0L)
                    durationMs = player.duration.takeIf { it > 0L } ?: 0L
                    isBuffering = player.playbackState == Player.STATE_BUFFERING
                }
                delay(400L)
            }
        }
    }

    // 重启后恢复的队列里没有简介：补拉一次
    LaunchedEffect(song.id) { PodcastController.ensureDescription(song) }

    BackHandler(enabled = true) { onDismiss() }

    val ui = remember(song, isPlaying, isBuffering, durationMs, sleepMinutes, sleepRemaining, queue, castDevice, s.cacheVersion, progressVersion) {
        PodcastController.playerUi(
            context = context,
            song = song,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            durationMs = durationMs,
            sleepTimerMinutes = sleepMinutes,
            sleepTimerRemainingSec = sleepRemaining,
            queue = queue,
            isCasting = castDevice != null
        )
    }

    val actions = remember(song.id) {
        PodcastPlayerActions(
            onTogglePlayPause = { PlaybackQueueManager.togglePlay(context) },
            onSeekTo = { ms ->
                Media3Factory.peekSharedExoPlayer()?.seekTo(ms)
                positionMs = ms
                AudioSharingManager.onPhoneSeekIfCasting(context, ms)
            },
            onSkipBack = { PodcastController.skipBy(-PodcastPlayerDefaults.SKIP_BACK_MS) },
            onSkipForward = { PodcastController.skipBy(PodcastPlayerDefaults.SKIP_FORWARD_MS) },
            onSetSpeed = { PodcastController.setSpeed(it) },
            onSetSleepTimer = { PlaybackQueueManager.setSleepTimer(it, context) },
            onPlayFromUpNext = { ep ->
                PlaybackQueueManager.playlistFlow.value.firstOrNull {
                    PodcastSongs.isPodcast(it) && PodcastSongs.episodeId(it) == ep.id
                }?.let { target ->
                    PodcastController.prepareReplay(ep.id)
                    PlaybackQueueManager.playSong(target, context, startPositionMs = PodcastController.resumePositionFor(target))
                }
            },
            onMarkPlayed = { played ->
                val cur = PlaybackQueueManager.currentSongFlow.value
                if (cur != null && PodcastSongs.isPodcast(cur)) {
                    PodcastController.markEpisodePlayed(PodcastSongs.episodeId(cur), played)
                }
            },
            onOpenShow = { feedId -> onOpenShow(feedId) },
            onDismiss = onDismiss
        )
    }

    PodcastPlayerScreen(
        ui = ui,
        positionMs = positionMs,
        actions = actions,
        castButton = {
            var showOutput by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = {
                    AudioSharingManager.refreshLocalAudioRoutes(context)
                    AudioSharingManager.startLanDiscovery(context)
                    showOutput = true
                }) {
                    Icon(
                        imageVector = Icons.Default.Cast,
                        contentDescription = "AirPlay / DLNA / 音频输出",
                        tint = if (castDevice != null || showOutput) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AudioOutputDropdownMenu(
                    expanded = showOutput,
                    onDismissRequest = { showOutput = false },
                    song = song,
                    progressMs = positionMs
                )
            }
        }
    )
}
