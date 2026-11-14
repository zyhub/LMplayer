package com.lm.player.feature.player

import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.DialogProperties
import com.lm.player.core.designsystem.component.AddToPlaylistDialog
import com.lm.player.core.designsystem.component.AddToPlaylistDropdownMenu
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.DownloadQualityDropdownMenu
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.media.AudioSharingManager
import com.lm.player.core.media.PlaybackQueueManager
import com.lm.player.core.media.ShareProtocolType
import com.lm.player.core.model.*
import java.io.File
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 现代全屏音乐播放界面 (全面支持横竖屏自适应与界面内嵌融合面板)
 * - 竖屏模式：顶部 Segmented 切换 [ 歌曲 | 歌词 ]、支持左右滑动无缝切换歌词、下拉手势丝滑最小化
 * - 横屏模式：超大专辑封面展示 + 左侧全功能控制器与工具栏 + 右侧视窗支持 [ 歌词 ⇄ 待播列表 ] 顶部右上角一键无缝切换
 * - 底部工具条：[ ≡ 待播列表 ] [ ⏱ 定时关闭 ] [ 📡 AirPlay/DLNA/输出路由 ] [ ⓘ 音频参数详情 ]
 * - 工具面板：定时器、音频参数、音频输出共享均使用界面内嵌浮层弹出，与播放界面完美融合
 */
@androidx.compose.foundation.ExperimentalFoundationApi
@Composable
fun FullscreenPlayerSheet(
    song: UnifiedSong,
    playlist: List<UnifiedSong> = emptyList(),
    isPlaying: Boolean,
    progressMs: Long,
    totalDurationMs: Long,
    lyrics: LyricResult,
    isLyricsMode: Boolean,
    /** 三态播放模式（列表循环 / 单曲循环 / 随机播放） */
    playMode: com.lm.player.core.media.PlayMode,
    allPlaylists: List<UnifiedPlaylist> = emptyList(),
    isServerConnected: Boolean = true,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSelectSongFromQueue: (UnifiedSong) -> Unit = {},
    onToggleLyricsMode: (Boolean) -> Unit = {},
    onToggleFavorite: () -> Unit,
    /** 点击三态按钮：切换到下一个播放模式 */
    onCyclePlayMode: () -> Unit,
    /** 当前歌曲的本地下载进度（0~1）；null = 没在下载 */
    downloadProgress: Float? = null,
    onDownloadSong: (UnifiedSong) -> Unit = {},
    onDownloadSongWithOptions: (UnifiedSong, DownloadTarget, AudioQuality) -> Unit = { s, _, _ -> onDownloadSong(s) },
    onAddToPlaylist: (UnifiedPlaylist, UnifiedSong) -> Unit = { _, _ -> },
    onCreatePlaylistAndAddSong: (String, UnifiedSong) -> Unit = { _, _ -> },
    /** 在线修标签写回歌词后回调（宿主重新加载播放页歌词） */
    onLyricsUpdated: () -> Unit = {},
    /** 在线修标签写回成功后回调，携带本次写入的字段（宿主同步播放队列 / 本地库 / 封面） */
    onMetadataSaved: (com.lm.player.feature.library.TagSavedMetadata) -> Unit = {},
    /** 点歌手名：跳转资料库歌手页 */
    onOpenArtist: (String) -> Unit = {},
    /** 点专辑名：跳转资料库专辑页（专辑名、歌手、专辑 ID） */
    onOpenAlbum: (String, String, String) -> Unit = { _, _, _ -> },
    onDismiss: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val dimensions = LocalAppDimensions.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val activeCastDevice by AudioSharingManager.activeCastDevice.collectAsState()

    LaunchedEffect(Unit) {
        AudioSharingManager.observeLocalAudioRoutes(context)
    }

    // 若当前处于 DLNA / AirPlay 无线投射状态，切歌时自动将新曲目同步推送至远端接收器
    // key 必须包含 activeCastDevice?.id：此前只有 song.id，导致**播放中途才连上投屏设备时**
    // 不会补推当前曲目（用户看到「连上了但没声音」）；同时 castSongToDevice 需要 try/catch，
    // 否则投屏失败会抛异常影响该组合作用域的协程。
    LaunchedEffect(song.id, activeCastDevice?.id) {
        val targetDevice = activeCastDevice ?: return@LaunchedEffect
        try {
            AudioSharingManager.castSongToDevice(context, targetDevice, song, 0L)
        } catch (e: Exception) {
            android.util.Log.w("FullscreenPlayerSheet", "castSongToDevice failed", e)
        }
    }

    // 竖屏 Pager 状态：0 为歌曲封面大图，1 为实时滚动歌词
    val pagerState = rememberPagerState(initialPage = if (isLyricsMode) 1 else 0, pageCount = { 2 })

    // 横屏右侧视窗显示模式：0 为实时歌词，1 为待播队列
    var landscapeRightPaneMode by remember { mutableStateOf(0) }

    // 监听外部歌词模式变化并联动 Pager
    LaunchedEffect(isLyricsMode) {
        val targetPage = if (isLyricsMode) 1 else 0
        if (pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    // 监听 Pager 滑动并反向同步状态
    LaunchedEffect(pagerState.currentPage) {
        onToggleLyricsMode(pagerState.currentPage == 1)
    }

    // 下拉滑动最小化手势位移
    var dragOffsetY by remember { mutableStateOf(0f) }
    val animatedOffsetY by animateFloatAsState(targetValue = dragOffsetY, label = "dismiss_drag_offset")

    // 界面内嵌浮层状态 (融为一体，取消弹窗)
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSleepTimerPanel by remember { mutableStateOf(false) }
    var showAudioSpecsPanel by remember { mutableStateOf(false) }
    var showAudioOutputPanel by remember { mutableStateOf(false) }
    var showLandscapeAddToPlaylistMenu by remember { mutableStateOf(false) }
    var showPortraitAddToPlaylistMenu by remember { mutableStateOf(false) }
    var showDownloadMenu by remember { mutableStateOf(false) }
    var showLandscapeDownloadMenu by remember { mutableStateOf(false) }
    var showTagEditor by remember { mutableStateOf(false) }
    // 在线修标签弹窗（独立窗口，竖屏 / 横屏入口共用）
    if (showTagEditor) {
        com.lm.player.feature.library.TagEditorDialog(
            song = song,
            onDismiss = { showTagEditor = false },
            onLyricsUpdated = onLyricsUpdated,
            onMetadataSaved = onMetadataSaved
        )
    }
    val activeTimerMinutes by PlaybackQueueManager.sleepTimerMinutesFlow.collectAsState()
    val sleepTimerRemainingSec by PlaybackQueueManager.sleepTimerRemainingSecFlow.collectAsState()
    var localIsFavorite by remember(song.id, song.isFavorite) { mutableStateOf(song.isFavorite) }

    fun applySleepTimer(min: Int) {
        PlaybackQueueManager.setSleepTimer(min, context)
        when {
            min > 0 -> Toast.makeText(context, "已设置：${min}分钟后停止播放", Toast.LENGTH_SHORT).show()
            min == -1 -> Toast.makeText(context, "已设置：播完当前曲目后停止", Toast.LENGTH_SHORT).show()
            else -> Toast.makeText(context, "已关闭定时器", Toast.LENGTH_SHORT).show()
        }
    }
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f

    // 歌词偏好设置 (字号大小、时间快慢偏置、6 套主题预设)
    val lyricsPrefs = remember { context.getSharedPreferences("zds_lyrics_prefs", android.content.Context.MODE_PRIVATE) }
    var lyricsFontSize by remember { mutableStateOf(lyricsPrefs.getFloat("lyrics_font_size", 22f)) }
    var lyricsOffsetMs by remember { mutableStateOf(lyricsPrefs.getLong("lyrics_offset_ms", 0L)) }
    var currentLyricTheme by remember {
        mutableStateOf(
            com.lm.player.core.designsystem.theme.LyricTheme.fromId(
                lyricsPrefs.getString("lyrics_theme_id", com.lm.player.core.designsystem.theme.LyricTheme.APPLE_MUSIC.id)
                    ?: com.lm.player.core.designsystem.theme.LyricTheme.APPLE_MUSIC.id
            )
        )
    }
    var landscapeLyricsRatio by remember {
        mutableStateOf(lyricsPrefs.getFloat("landscape_lyrics_width_ratio", 0.333f).coerceIn(0.0f, 0.333f))
    }
    var isDraggingSplitter by remember { mutableStateOf(false) }

    val updateFontSize: (Float) -> Unit = { newSize ->
        lyricsFontSize = newSize
        lyricsPrefs.edit().putFloat("lyrics_font_size", newSize).apply()
    }
    val updateOffset: (Long) -> Unit = { newOffset ->
        lyricsOffsetMs = newOffset
        lyricsPrefs.edit().putLong("lyrics_offset_ms", newOffset).apply()
    }
    val updateTheme: (com.lm.player.core.designsystem.theme.LyricTheme) -> Unit = { newTheme ->
        currentLyricTheme = newTheme
        lyricsPrefs.edit().putString("lyrics_theme_id", newTheme.id).apply()
    }

    // 系统返回键适配：优先关闭播放页内嵌浮层面板，否则收起全屏播放页
    androidx.activity.compose.BackHandler(enabled = true) {
        when {
            showQueueSheet -> showQueueSheet = false
            showSleepTimerPanel -> showSleepTimerPanel = false
            showAudioSpecsPanel -> showAudioSpecsPanel = false
            showAudioOutputPanel -> showAudioOutputPanel = false
            else -> onDismiss()
        }
    }

    // 计算当前歌曲的音质、码率、文件大小三色徽章数据
    val streamQualityVer by com.lm.player.core.network.LemonMusicProtocol.streamQualityConfigVersion.collectAsState()
    val audioBadge = remember(
        song.id,
        song.localFilePath,
        song.format,
        song.bitRate,
        song.durationMs,
        song.downloadStatus,
        totalDurationMs,
        streamQualityVer
    ) {
        computeAudioBadgeInfo(context, song, totalDurationMs)
    }

    // 动态主题渐变背景 (深色沉浸黑曜石，浅色纯净白)
    val playerBackdrop = if (isDark) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF14171A),
                Color(0xFF0D1013),
                Color(0xFF08090B)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFFFFFF),
                Color(0xFFF7F7FA),
                Color(0xFFEDEDF2)
            )
        )
    }

    val primaryTextColor = if (isDark) Color.White else Color(0xFF111113)
    val secondaryTextColor = if (isDark) Color.White.copy(alpha = 0.65f) else Color(0xFF636366)
    val surfaceGlassColor = if (isDark) Color(0xFF2C2C30).copy(alpha = 0.75f) else Color(0xFFEAEAEE).copy(alpha = 0.85f)
    // 液态玻璃：开启后背景换成封面模糊底图，圆形按键 / 工具条 / 分段胶囊改为玻璃（原实心底色去掉）
    val glassPlayerActive = com.lm.player.core.designsystem.glass.rememberLiquidGlassFull()
    val circleButtonBg = if (glassPlayerActive) Color.Transparent else if (isDark) Color(0xFF25252A) else Color(0xFFF2F2F7)
    // 状态栏取封面底图顶部的实际颜色，与底图连成一片
    var glassStatusBarColor by remember(song.coverUrl, isDark) {
        mutableStateOf(com.lm.player.core.designsystem.glass.CoverBackdropCache.peekTopColor(song.coverUrl, isDark))
    }
    // 播放页从底部向上插入：导航栏一开始就被播放页盖住，直接用播放页底色；
    // 状态栏要等播放页滑到顶才被盖住，按播放页当前位置从应用底色渐变过去（下拉关闭时反向）
    var sheetTopPx by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    // 不触发下拉关闭的区域（屏幕坐标）：顶部「歌曲 | 歌词」切换、「词」按钮 —— 在它们上面拖动是切页 / 调节，不是关闭播放页
    val noDismissZones = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(2) }
    val rootHeightPx = androidx.compose.ui.platform.LocalView.current.height.toFloat().coerceAtLeast(1f)

    // 横屏统一用怀旧专辑流（Cover Flow），竖屏用现代主题（原「主题切换」按钮已去掉）
    if (isLandscape) {
        RetroCoverFlowPlayerView(
            song = song,
            playlist = playlist,
            isPlaying = isPlaying,
            progressMs = progressMs,
            totalDurationMs = totalDurationMs,
            lyrics = lyrics,
            playMode = playMode,
            activeTimerMinutes = activeTimerMinutes,
            fontSizeSp = lyricsFontSize,
            lyricsOffsetMs = lyricsOffsetMs,
            lyricTheme = currentLyricTheme,
            allPlaylists = allPlaylists,
            isServerConnected = isServerConnected,
            onTogglePlayPause = onTogglePlayPause,
            onNext = onNext,
            onPrevious = onPrevious,
            onSeekTo = onSeekTo,
            onSelectSongFromQueue = onSelectSongFromQueue,
            onToggleFavorite = onToggleFavorite,
            onCyclePlayMode = onCyclePlayMode,
            onAddToPlaylist = onAddToPlaylist,
            onCreatePlaylistAndAddSong = onCreatePlaylistAndAddSong,
            onDownloadSongWithOptions = onDownloadSongWithOptions,
            onSelectTimer = { min -> applySleepTimer(min) },
            onFontSizeChange = updateFontSize,
            onOffsetChange = updateOffset,
            onThemeChange = updateTheme,
            onOpenArtist = onOpenArtist,
            onOpenAlbum = onOpenAlbum,
            onLyricsUpdated = onLyricsUpdated,
            onMetadataSaved = onMetadataSaved,
            onOpenSleepTimer = { showSleepTimerPanel = true },
            onOpenAudioSpecs = { showAudioSpecsPanel = true },
            onDismiss = onDismiss
        )
        return
    }

    // 系统状态栏 / 导航栏与播放页背景渐变的首尾色一致（离开播放页自动恢复应用底色）
    val playerStatusColor = glassStatusBarColor?.takeIf { glassPlayerActive } ?: if (isDark) Color(0xFF14171A) else Color(0xFFFFFFFF)
    val sheetInsertFraction = (1f - sheetTopPx / rootHeightPx).coerceIn(0f, 1f)
    val statusBarBlend = ((sheetInsertFraction - 0.85f) / 0.15f).coerceIn(0f, 1f)
    com.lm.player.core.designsystem.theme.SystemBarsColor(
        statusBar = androidx.compose.ui.graphics.lerp(
            com.lm.player.core.designsystem.theme.SystemBarsStyler.backgroundFor(isDark),
            playerStatusColor,
            statusBarBlend
        ),
        navigationBar = if (isDark) Color(0xFF08090B) else Color(0xFFEDEDF2)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, if (PlayerDismissDrag.enabled) 0 else animatedOffsetY.roundToInt().coerceAtLeast(0)) }
            .onGloballyPositioned { sheetTopPx = it.positionInRoot().y }
            .background(playerBackdrop)
            // 点「词」浮层以外的任意位置：收回浮层（并吃掉这次点击）
            .playerOutsideTapHost()
            .pointerInput(Unit) {
                var dismissBlocked = false
                detectVerticalDragGestures(
                    onDragStart = { start ->
                        val p = androidx.compose.ui.geometry.Offset(start.x, start.y + sheetTopPx.coerceAtLeast(0f).let { if (it == Float.MAX_VALUE) 0f else it })
                        dismissBlocked = noDismissZones.any { it?.contains(p) == true }
                    },
                    onVerticalDrag = { _, dragAmount ->
                        if (dismissBlocked) return@detectVerticalDragGestures
                        if (dragAmount > 0 || dragOffsetY > 0) {
                            dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                            if (PlayerDismissDrag.enabled) {
                                PlayerDismissDrag.dragging = true
                                PlayerDismissDrag.progress = (dragOffsetY / (rootHeightPx * 0.6f)).coerceIn(0f, 1f)
                            }
                        }
                    },
                    onDragEnd = {
                        if (dismissBlocked) {
                            dismissBlocked = false
                            return@detectVerticalDragGestures
                        }
                        PlayerDismissDrag.dragging = false
                        // 下滑关闭：停在松手的位置不回弹，关闭动画（滑出 / 神奇效果）从这里接着走
                        if (dragOffsetY > 160f) {
                            onDismiss()
                        } else {
                            dragOffsetY = 0f
                            PlayerDismissDrag.progress = 0f
                        }
                    },
                    onDragCancel = {
                        dismissBlocked = false
                        dragOffsetY = 0f
                        PlayerDismissDrag.dragging = false
                        PlayerDismissDrag.progress = 0f
                    }
                )
            }
    ) {
        // 液态玻璃（竖屏 + 横屏）：背景换成封面模糊底图，按钮玻璃折射它；
        // 纯色渐变背景上玻璃看不出折射，所以只有开启玻璃时才换背景
        val playerCoverLayer = rememberLayerBackdrop()
        val glassBackdrop: com.kyant.backdrop.Backdrop? = if (glassPlayerActive) {
            com.lm.player.core.designsystem.glass.CoverBackdrop(
                coverUrl = song.coverUrl,
                seedId = song.id,
                isDark = isDark,
                baseBottomColor = if (isDark) Color(0xFF08090B) else Color(0xFFEDEDF2),
                modifier = Modifier
                    .matchParentSize()
                    // 封面底图录成图层：播放页的玻璃按钮 / 面板采样它（Kyant0 backdrop）
                    .layerBackdrop(playerCoverLayer),
                onTopColor = { glassStatusBarColor = it }
            )
            playerCoverLayer
        } else null

        // 竖屏主列（封面 / 歌名 / 进度 / 主控 / 工具栏）。横屏时作为右半边使用：
        // 参照 AMLL（Apple Music-like Lyrics）的横屏布局，左边整块滚动歌词，右边是竖屏的播放界面
        val playerMainColumn: @Composable (Modifier, Boolean) -> Unit = { columnModifier, landscapeLayout ->
            Column(
                modifier = columnModifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                // 横屏：封面固定大小，其余空间平均分给各行，按键不再挤在一起
                verticalArrangement = if (landscapeLayout) Arrangement.SpaceEvenly else Arrangement.SpaceBetween
            ) {
                // 1. 顶部操作栏 (左侧最小化 + 居中 [ 歌曲 | 歌词 ] 胶囊切换 + 右侧「词」歌词调节按钮)；横屏时不显示
                if (!landscapeLayout) Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(2f),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    com.lm.player.core.designsystem.glass.GlassCircle(null, 36.dp, isDark) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(circleButtonBg)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "最小化播放栏",
                            tint = primaryTextColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    }

                    // 顶部居中：[ 歌曲 | 歌词 ] 切换 —— 平时缩成两颗小水珠，切换时展开，2 秒后自动缩回
                    Box(modifier = Modifier.onGloballyPositioned { noDismissZones[0] = it.boundsInRoot().inflate(12.dp.toPxF(it)) }) {
                    SongLyricsToggle(
                        pagerState = pagerState,
                        glassBackdrop = glassBackdrop,
                        isDark = isDark,
                        primaryTextColor = primaryTextColor,
                        secondaryTextColor = secondaryTextColor,
                        collapsedBackground = if (isDark) Color(0xFF25252A) else Color(0xFFF2F2F7),
                        onSelectPage = { page -> coroutineScope.launch { pagerState.animateScrollToPage(page) } }
                    )
                    }

                    // 顶部右侧：「词」歌词调节按钮（与左侧最小化按钮同大小，中央切换胶囊仍严格居中）；
                    // 点一下向下液态划出 Aa 字号 / ⏱ 快慢 / ⋯ 配色与开关。只在歌词页显示；歌曲页留同宽空位，中央胶囊不偏
                    val onLyricsPage by remember(pagerState) { derivedStateOf { pagerState.currentPage == 1 } }
                    Box(modifier = Modifier.size(36.dp).onGloballyPositioned { noDismissZones[1] = it.boundsInRoot().inflate(12.dp.toPxF(it)) }) {
                        if (onLyricsPage) LyricsAdjustButton(
                        isDark = isDark,
                        contentColor = primaryTextColor,
                        fallbackBackground = circleButtonBg,
                        glassBackdrop = glassBackdrop,
                        fontSizeSp = lyricsFontSize,
                        lyricsOffsetMs = lyricsOffsetMs,
                        onFontSizeChange = updateFontSize,
                        onOffsetChange = updateOffset
                        )
                    }
                }

                // 2. 中间 Pager (支持左右手势直接切换 [ 歌曲封面大图 + 5行滚动渐变歌词 ⇄ 实时全屏歌词 ])
                if (landscapeLayout) {
                    // 横屏右半边：中间只放封面（滚动歌词在左半边）
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        val coverSide = minOf(
                            maxWidth * 0.62f,
                            LocalConfiguration.current.screenHeightDp.dp * 0.34f
                        ).coerceAtLeast(88.dp)
                        AlbumArtworkImage(
                            model = song.coverUrl,
                            seedId = song.id,
                            targetSize = 512,
                            modifier = Modifier
                                .size(coverSide)
                                .shadow(16.dp, RoundedCornerShape(18.dp)),
                            cornerRadius = 18.dp
                        )
                    }
                } else HorizontalPager(
                    state = pagerState,

                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 4.dp)
                ) { page ->
                    if (page == 0) {
                        // 页面 0: 居中大封面展示 + 下方 5 行滚动渐变歌词 (上一句、当前歌词、下一句、前后各一条滚动淡化)
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            val lyricsAreaHeight = 140.dp
                            val gapHeight = 10.dp
                            val availableForCover = (maxHeight - lyricsAreaHeight - gapHeight).coerceAtLeast(180.dp)
                            val artworkSize = minOf(maxWidth - 4.dp, availableForCover).coerceIn(180.dp, 360.dp)
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                // 置顶：封面紧贴顶栏，多出的高度给下面的歌词，不再整体居中在上方空出一块
                                verticalArrangement = Arrangement.Top
                            ) {
                                AlbumArtworkImage(
                                    model = song.coverUrl,
                                    seedId = song.id,
                                    targetSize = 512,
                                    modifier = Modifier
                                        .size(artworkSize)
                                        .shadow(20.dp, RoundedCornerShape(24.dp)),
                                    cornerRadius = 24.dp
                                )
                                Spacer(modifier = Modifier.height(gapHeight))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                CoverFiveLineGradientLyrics(
                                    lyrics = lyrics.lines,
                                    currentPositionMs = progressMs,
                                    lyricsOffsetMs = lyricsOffsetMs,
                                    lyricTheme = currentLyricTheme,
                                    onClickLyricsArea = {
                                        coroutineScope.launch { pagerState.animateScrollToPage(1) }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                }
                            }
                        }
                    } else {
                        // 页面 1: 实时滚动全屏歌词
                        LyricsScrollingView(
                            lyrics = lyrics.lines,
                            currentPositionMs = progressMs,
                            onSeekToLyric = onSeekTo,
                            fontSizeSp = lyricsFontSize,
                            lyricsOffsetMs = lyricsOffsetMs,
                            lyricTheme = currentLyricTheme,
                            onFontSizeChange = updateFontSize,
                            onOffsetChange = updateOffset,
                            onThemeChange = updateTheme,
                            showAdjustButton = false,
                            modifier = Modifier.fillMaxSize(),
                            glassBackdrop = glassBackdrop,
                            // 竖屏置顶：当前句放在靠上的位置（默认约 1/3 处），上方不再空出一大块
                            topAnchor = 0.18f
                        )
                    }
                }

                // 3. 歌曲元数据：标题、音质/码率/大小三色徽章、歌手与专辑 + 加入歌单与下载按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = song.title,
                            style = TextStyle(
                                fontSize = 21.sp * dimensions.fontScale,
                                fontWeight = FontWeight.Bold,
                                color = primaryTextColor
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(5.dp))

                        // 音质玻璃标签（原音质 / 码率 / 大小三个徽章合并）：点开弹出音频参数面板
                        Box {
                            AudioQualityTag(
                                info = audioBadge,
                                glassBackdrop = null,
                                isDark = isDark,
                                opened = showAudioSpecsPanel,
                                onClick = { showAudioSpecsPanel = !showAudioSpecsPanel }
                            )
                            AudioSpecsGlassPanel(
                                expanded = showAudioSpecsPanel,
                                onDismissRequest = { showAudioSpecsPanel = false },
                                song = song,
                                info = audioBadge
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // 歌手、专辑分别可点（浅色底的小胶囊，一眼看出能点）：跳到资料库对应的歌手页 / 专辑页
                        val metaStyle = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = secondaryTextColor
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = song.artist.ifBlank { "未知歌手" },
                                style = metaStyle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(primaryTextColor.copy(alpha = 0.10f))
                                    .clickable { onOpenArtist(song.artist) }
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (song.album.isNotBlank()) song.album else "精选单曲",
                                style = metaStyle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(primaryTextColor.copy(alpha = 0.10f))
                                    .clickable { onOpenAlbum(song.album, song.artist, song.albumId) }
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 在线修标签（PR #5，仅柠檬服务器曲库歌曲）：放在「加入歌单」左边
                        if (song.isLemonServerSong) {
                            com.lm.player.core.designsystem.glass.GlassCircle(null, 38.dp, isDark) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(circleButtonBg)
                                    .clickable { showTagEditor = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = "在线修标签",
                                    tint = primaryTextColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            }
                        }

                        // 加入歌单按钮 (音频共享式展出)
                        Box {
                            com.lm.player.core.designsystem.glass.GlassCircle(null, 38.dp, isDark) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(circleButtonBg)
                                    .clickable { showPortraitAddToPlaylistMenu = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                    contentDescription = "加入歌单",
                                    tint = primaryTextColor,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            }
                            AddToPlaylistDropdownMenu(
                                expanded = showPortraitAddToPlaylistMenu,
                                onDismissRequest = { showPortraitAddToPlaylistMenu = false },
                                song = song,
                                playlists = allPlaylists,
                                isServerConnected = isServerConnected,
                                onSelectPlaylist = { pl, s ->
                                    onAddToPlaylist(pl, s)
                                    Toast.makeText(context, "已添加至歌单: ${pl.name}", Toast.LENGTH_SHORT).show()
                                },
                                onCreatePlaylistAndAdd = { name, s ->
                                    onCreatePlaylistAndAddSong(name, s)
                                    Toast.makeText(context, "已创建歌单 \"$name\" 并添加歌曲", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }

                        // 下载与缓存选择按钮 (支持本地/服务器/双端同步选择)
                        // 播放页在播放期间会因进度刷新持续重组，这里的磁盘 stat 必须 remember，
                        // 否则播放中会在主线程不断做 File.exists()（U-4）。同时去掉 !! 脆弱写法。
                        // 下载完成 / 提交服务器后立刻刷新图标（不等数据库重读）
                        val liveSong = com.lm.player.core.media.SongStatusStore.rememberLive(song)
                        val hasPhysicalLocal = remember(liveSong.id, liveSong.localFilePath, liveSong.downloadStatus, com.lm.player.core.media.SongStatusStore.pageRefreshTick.intValue) {
                            val path = liveSong.localFilePath
                            !path.isNullOrBlank() && (path.startsWith("content://") ||
                                runCatching { java.io.File(path).let { it.exists() && it.length() > 0L } }.getOrDefault(false))
                        }
                        Box {
                            com.lm.player.core.designsystem.glass.GlassCircle(null, 38.dp, isDark) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(circleButtonBg)
                                    .clickable { showDownloadMenu = true },
                                contentAlignment = Alignment.Center
                            ) {
                                val isDownloaded = hasPhysicalLocal && (liveSong.downloadStatus == DownloadStatus.DOWNLOADED || !liveSong.localFilePath.isNullOrBlank())
                                val isServerCached = liveSong.isServerLibrarySong /* 与列表徽标同一口径（PR #7 合入时统一） */
                                if (downloadProgress != null) {
                                    // 下载中：进度圈 + 百分比
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(30.dp)) {
                                        CircularProgressIndicator(
                                            progress = { downloadProgress.coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxSize(),
                                            color = AppleRed,
                                            strokeWidth = 2.5.dp,
                                            trackColor = primaryTextColor.copy(alpha = 0.15f)
                                        )
                                        Text(
                                            text = "${(downloadProgress * 100).toInt().coerceIn(0, 100)}%",
                                            fontSize = 8.sp,
                                            letterSpacing = (-0.3).sp,
                                            fontWeight = FontWeight.Bold,
                                            color = primaryTextColor,
                                            maxLines = 1
                                        )
                                    }
                                } else if (isDownloaded && isServerCached) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "双端已同步",
                                        tint = Color(0xFF34C759),
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else if (isDownloaded || isServerCached) {
                                    Icon(
                                        imageVector = if (isDownloaded) Icons.Default.DownloadDone else Icons.Default.CloudDone,
                                        contentDescription = if (isDownloaded) "已下载到本机" else "服务器曲库已有",
                                        tint = if (isDownloaded) Color(0xFF34C759) else Color(0xFF0A84FF),
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.FileDownload,
                                        contentDescription = "下载",
                                        tint = primaryTextColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            }

                            DownloadQualityDropdownMenu(
                                expanded = showDownloadMenu,
                                onDismissRequest = { showDownloadMenu = false },
                                song = song,
                                isServerConnected = isServerConnected,
                                hasLocal = hasPhysicalLocal,
                                hasServer = song.isServerLibrarySong /* 与列表徽标同一口径（PR #7 合入时统一） */,
                                onConfirm = { target, quality ->
                                    showDownloadMenu = false
                                    onDownloadSongWithOptions(song, target, quality)
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 4. 进度条与播放时间
                Column(modifier = Modifier.fillMaxWidth()) {
                    val progressRatio = if (totalDurationMs > 0) (progressMs.toFloat() / totalDurationMs).coerceIn(0f, 1f) else 0f
                    // 液态玻璃进度条（Kyant0 示例 LiquidSlider）：按住滑块放大成透镜，玻璃折射封面底图
                    com.lm.player.core.designsystem.component.LiquidSlider(
                        value = progressRatio,
                        onValueChange = { ratio -> onSeekTo((ratio * totalDurationMs).toLong()) },
                        commitOnRelease = true,
                        accentColor = primaryTextColor,
                        trackColor = primaryTextColor.copy(alpha = 0.2f),
                        backdrop = glassBackdrop,
                        surfaceColor = if (isDark) Color(0xFF0D1013) else Color(0xFFF7F7FA),
                        modifier = Modifier.fillMaxWidth().height(28.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatDuration(progressMs),
                            style = TextStyle(fontSize = 12.sp, color = secondaryTextColor)
                        )
                        Text(
                            text = formatDuration(totalDurationMs),
                            style = TextStyle(fontSize = 12.sp, color = secondaryTextColor)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 5. 核心 5 大播放控制按键
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 液态玻璃模式下每个按键底下垫一颗玻璃圆（尺寸与原触控区一致，布局不变）
                    val skipIconSize = if (glassBackdrop != null) 30.dp else 38.dp

                    // 播放列表（原「随机」位置）
                    com.lm.player.core.designsystem.glass.GlassCircle(null, 48.dp, isDark) {
                        com.lm.player.feature.player.controls.QueueIconButton(
                            onClick = { showQueueSheet = true },
                            tint = primaryTextColor.copy(alpha = 0.8f)
                        )
                    }

                    com.lm.player.core.designsystem.glass.GlassCircle(null, 48.dp, isDark) {
                        IconButton(onClick = onPrevious) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "上一首",
                                tint = primaryTextColor,
                                modifier = Modifier.size(skipIconSize)
                            )
                        }
                    }

                    // 大号实心主播放键（液态玻璃模式下改为更通透的大玻璃圆，图标用正文色）
                    com.lm.player.core.designsystem.glass.GlassCircle(null, 68.dp, isDark, emphasized = true) {
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .then(if (glassBackdrop != null) Modifier else Modifier.background(primaryTextColor))
                                .clickable(onClick = onTogglePlayPause),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = "播放/暂停",
                                tint = when {
                                    glassBackdrop != null -> primaryTextColor
                                    isDark -> Color.Black
                                    else -> Color.White
                                },
                                modifier = Modifier.size(38.dp)
                            )
                        }
                    }

                    com.lm.player.core.designsystem.glass.GlassCircle(null, 48.dp, isDark) {
                        IconButton(onClick = onNext) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "下一首",
                                tint = primaryTextColor,
                                modifier = Modifier.size(skipIconSize)
                            )
                        }
                    }

                    // 三态播放模式（原「循环」位置）
                    com.lm.player.core.designsystem.glass.GlassCircle(null, 48.dp, isDark) {
                        com.lm.player.feature.player.controls.PlayModeIconButton(
                            mode = playMode,
                            onClick = onCyclePlayMode,
                            tint = primaryTextColor.copy(alpha = 0.8f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 6. 底部功能工具栏（播放列表已移到主控行左侧）: [ ♥ 喜欢收藏 ] [ ⏱ 定时关闭 ] [ 音频输出 ] [ ⓘ 参数详情 ]
                // 竖屏进出播放页时，这条玻璃工具条与 App 底栏交接上下滑（见 PlayerIslandTransition）
                com.lm.player.core.designsystem.glass.GlassBox(glassBackdrop, isDark, 28.dp, Modifier.fillMaxWidth().playerToolbarLink()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        localIsFavorite = !localIsFavorite
                        onToggleFavorite()
                    }) {
                        Icon(
                            imageVector = if (localIsFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = "红心喜欢",
                            tint = if (localIsFavorite) AppleRed else secondaryTextColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Box {
                        IconButton(onClick = { showSleepTimerPanel = true }) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = "定时关闭",
                                tint = if (activeTimerMinutes != 0) AppleRed else secondaryTextColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        SleepTimerDropdownMenu(
                            expanded = showSleepTimerPanel,
                            onDismissRequest = { showSleepTimerPanel = false },
                            activeTimerMinutes = activeTimerMinutes,
                            remainingSeconds = sleepTimerRemainingSec,
                            onSelectTimer = { min -> applySleepTimer(min) }
                        )
                    }

                    Box {
                        IconButton(onClick = {
                            AudioSharingManager.refreshLocalAudioRoutes(context)
                            AudioSharingManager.startLanDiscovery(context)
                            showAudioOutputPanel = true
                        }) {
                            Icon(
                                imageVector = Icons.Default.Cast,
                                contentDescription = "AirPlay / DLNA / 音频输出",
                                tint = if (activeCastDevice != null || showAudioOutputPanel) AppleRed else secondaryTextColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        AudioOutputDropdownMenu(
                            expanded = showAudioOutputPanel,
                            onDismissRequest = { showAudioOutputPanel = false },
                            song = song,
                            progressMs = progressMs
                        )
                    }
                }
                }
            }
        }

        if (isLandscape) {
            // =========================================================================
            // 横屏（AMLL 式）：左边滚动歌词（右上角「词」歌词调节），右边沿用竖屏布局
            // =========================================================================
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
            ) {
                // 左：播放界面（沿用竖屏布局），左上角收起
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    playerMainColumn(
                        Modifier
                            .fillMaxSize()
                            .padding(start = 24.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                        true
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 12.dp, top = 8.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(circleButtonBg)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "最小化播放栏",
                            tint = primaryTextColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                // 右：滚动歌词（右上角「词」歌词调节）
                Box(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxHeight()
                ) {
                    LyricsScrollingView(
                        lyrics = lyrics.lines,
                        currentPositionMs = progressMs,
                        onSeekToLyric = onSeekTo,
                        fontSizeSp = lyricsFontSize,
                        lyricsOffsetMs = lyricsOffsetMs,
                        lyricTheme = currentLyricTheme,
                        onFontSizeChange = updateFontSize,
                        onOffsetChange = updateOffset,
                        onThemeChange = updateTheme,
                        showAdjustButton = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 6.dp),
                        glassBackdrop = glassBackdrop
                    )
                }
            }
        } else {
            playerMainColumn(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 12.dp),
                false
            )
        }



        // 4. 待播列表：播放页内的悬浮玻璃卡片（从底部滑上来；点空白 / 下拉标题栏 / 返回键关闭）
        PlayerQueueOverlay(
            visible = showQueueSheet,
            onDismiss = { showQueueSheet = false },
            playlist = playlist,
            currentSongId = song.id,
            glassBackdrop = glassBackdrop,
            isDark = isDark,
            primaryTextColor = primaryTextColor,
            secondaryTextColor = secondaryTextColor,
            onSelect = onSelectSongFromQueue
        )


    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val remainingSeconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, remainingSeconds)
}

@Composable
private fun SpecRowItem(
    label: String,
    value: String,
    isBold: Boolean = false,
    isPath: Boolean = false,
    valueColor: Color? = null
) {
    val primaryTextColor = if (MaterialTheme.colorScheme.background.red < 0.5f) Color.White else Color.Black
    val secondaryTextColor = if (MaterialTheme.colorScheme.background.red < 0.5f) Color(0xFFAAAAAE) else Color(0xFF6C6C70)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = if (isPath) Alignment.Top else Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = secondaryTextColor,
            modifier = Modifier.width(68.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Medium,
            color = valueColor ?: primaryTextColor,
            textAlign = TextAlign.End,
            maxLines = if (isPath) 3 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun SleepTimerDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    activeTimerMinutes: Int,
    /** 剩余秒数：由宿主每秒更新，用于展示真实倒计时（0 表示未设置或已到期） */
    remainingSeconds: Long = 0L,
    onSelectTimer: (Int) -> Unit
) {
    // 窄竖条面板（与「词」调节展开后差不多大）：顶部数值 + 竖向滑条（0～120 分钟，5 分钟一档，松手生效）+ 本曲后 / 关闭
    val maxMinutes = 120
    val activeMinutes = when {
        activeTimerMinutes > 0 && remainingSeconds > 0L -> ((remainingSeconds + 59L) / 60L).toInt()
        activeTimerMinutes > 0 -> activeTimerMinutes
        else -> 0
    }.coerceIn(0, maxMinutes)
    var draftMinutes by remember(expanded) { mutableIntStateOf(activeMinutes) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(activeMinutes) { if (!dragging) draftMinutes = activeMinutes }

    com.lm.player.core.designsystem.glass.AdaptiveDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.width(64.dp),
        scrollable = false,
        preferAbove = true,
        alignCenter = true,
        cornerRadius = 32.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = when {
                    dragging -> if (draftMinutes == 0) "关" else "${draftMinutes}分"
                    activeTimerMinutes > 0 && remainingSeconds > 0L ->
                        "%d:%02d".format(java.util.Locale.US, remainingSeconds / 60, remainingSeconds % 60)
                    activeTimerMinutes > 0 -> "${activeTimerMinutes}分"
                    activeTimerMinutes == -1 -> "本曲"
                    else -> "关"
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (dragging || activeTimerMinutes != 0) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                style = TextStyle(fontFeatureSettings = "tnum")
            )
            Spacer(modifier = Modifier.height(6.dp))
            VerticalTimerSlider(
                fraction = draftMinutes / maxMinutes.toFloat(),
                onDrag = { f ->
                    dragging = true
                    draftMinutes = ((f * maxMinutes) / 5f).roundToInt() * 5
                },
                onRelease = {
                    dragging = false
                    onSelectTimer(draftMinutes)
                },
                modifier = Modifier.size(width = 40.dp, height = 150.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            TimerIconButton(
                icon = Icons.Default.MusicNote,
                description = "播完本曲后停止",
                selected = activeTimerMinutes == -1
            ) {
                draftMinutes = 0
                onSelectTimer(-1)
            }
            Spacer(modifier = Modifier.height(4.dp))
            TimerIconButton(
                icon = Icons.Default.Close,
                description = "关闭定时",
                selected = false
            ) {
                draftMinutes = 0
                onSelectTimer(0)
            }
        }
    }
}

/** 竖向定时滑条（控制中心式胶囊）：从下往上填充强调色，按住微微鼓起 */
@Composable
private fun VerticalTimerSlider(
    fraction: Float,
    onDrag: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val latestDrag by rememberUpdatedState(onDrag)
    val latestRelease by rememberUpdatedState(onRelease)
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 420f),
        label = "timer_slider_press"
    )
    val shown by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 1f, stiffness = 900f),
        label = "timer_slider_value"
    )
    val track = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = 1f + 0.08f * press
                scaleY = 1f + 0.03f * press
            }
            .clip(RoundedCornerShape(22.dp))
            .background(track)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressed = true
                    down.consume()
                    latestDrag((1f - down.position.y / size.height).coerceIn(0f, 1f))
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        latestDrag((1f - change.position.y / size.height).coerceIn(0f, 1f))
                    }
                    pressed = false
                    latestRelease()
                }
            }
            .drawBehind {
                val h = size.height * shown
                if (h > 0f) {
                    drawRect(
                        color = AppleRed,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h),
                        size = androidx.compose.ui.geometry.Size(size.width, h)
                    )
                }
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        Icon(
            imageVector = Icons.Default.Timer,
            contentDescription = "拖动设置定时",
            tint = if (shown > 0.12f) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(bottom = 12.dp)
                .size(20.dp)
        )
    }
}

@Composable
private fun TimerIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(
                if (selected) AppleRed.copy(alpha = 0.18f)
                else if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (selected) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
fun AudioSpecsDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    song: UnifiedSong
) {
    if (!expanded) return

    val isLocal = !song.localFilePath.isNullOrBlank()
    val (formatStr, realBitRate, realLocalSizeStr) = if (isLocal) {
        com.lm.player.feature.home.resolveRealLocalFormatAndSize(song)
    } else {
        val context = androidx.compose.ui.platform.LocalContext.current
        val q = AudioQuality.fromKey(com.lm.player.core.network.LemonMusicProtocol.getPreferredStreamQuality(context))
        Triple(q.format.uppercase(), q.bitrate, q.estimateSizeText(song))
    }
    val isLossless = formatStr in listOf("FLAC", "WAV", "ALAC", "APE", "DSD", "DSF") || realBitRate >= 800
    val qualityTag = if (isLossless && realBitRate >= 1200) "Hi-Res 无损母带" else if (isLossless) "无损品质音频" else if (realBitRate >= 320) "极高品质音频" else "标准音频"
    val sizeText: String = realLocalSizeStr

    val locationText: String = if (isLocal && !song.localFilePath.isNullOrBlank()) {
        val f = File(song.localFilePath)
        f.parent ?: song.localFilePath
    } else {
        val stream = song.streamUrl
        var nasPath = ""
        if (stream.contains("path=")) {
            try {
                val uri = android.net.Uri.parse(stream)
                val pathParam = uri.getQueryParameter("path")
                if (!pathParam.isNullOrBlank()) {
                    val decoded = java.net.URLDecoder.decode(pathParam, "UTF-8")
                    val parentDir = if (decoded.contains("/")) decoded.substringBeforeLast("/") + "/" else decoded
                    nasPath = parentDir
                }
            } catch (_: Exception) {}
        }
        if (nasPath.isBlank() && !song.relativeFolderPath.isNullOrBlank()) {
            val folder = song.relativeFolderPath.trim().trimEnd('/')
            nasPath = "$folder/"
        }
        if (nasPath.isBlank()) {
            val artist = song.artist.ifBlank { "未知歌手" }
            val album = song.album.ifBlank { "单曲" }
            nasPath = "/音乐/$artist/$album/"
        }
        if (!nasPath.startsWith("/")) nasPath = "/$nasPath"
        "NAS: $nasPath"
    }
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = if (isDark) Color(0xFF1E1E26) else Color(0xFFF9F9FC),
            border = BorderStroke(1.dp, if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)),
            shadowElevation = 18.dp,
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .widthIn(max = 370.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFD4AF37).copy(alpha = 0.2f)) {
                            Text("Hi-Res", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD4AF37), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("音频参数详情", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = onDismissRequest, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(10.dp))

                val activeRouteName by AudioSharingManager.activeLocalRouteName.collectAsState()
                val activeCast by AudioSharingManager.activeCastDevice.collectAsState()
                val activeChannelSummary = when {
                    activeCast != null -> "${activeCast!!.name} (${activeCast!!.protocol.badge})"
                    isLocal -> "$activeRouteName · 本地硬件直解"
                    else -> "$activeRouteName · NAS 无损推流"
                }

                val isOnlineSong = song.id.startsWith("lemon_online_") ||
                    song.serverId == "lemon_online" ||
                    song.streamUrl.startsWith("lemon_online://") ||
                    (com.lm.player.core.media.DownloadRequestPlanner.hasRemoteSource(song) && !isLocal)

                val platformKey = when {
                    song.id.startsWith("lemon_online_") -> song.id.removePrefix("lemon_online_").substringBefore("_")
                    else -> {
                        val rawJson = song.rawMetaJson ?: song.relativeFolderPath
                        if (!rawJson.isNullOrBlank()) {
                            runCatching {
                                val obj = org.json.JSONObject(rawJson)
                                obj.optString("source").ifBlank { obj.optString("platform") }
                            }.getOrNull().orEmpty()
                        } else ""
                    }
                }.ifBlank { "kw" }

                val platformName = when (platformKey.lowercase()) {
                    "tx" -> "QQ音乐"
                    "kw" -> "酷我音乐"
                    "wy" -> "网易云音乐"
                    "kg" -> "酷狗音乐"
                    "mg" -> "咪咕音乐"
                    "bd" -> "百度音乐"
                    else -> platformKey.uppercase()
                }

                val sourceName = runCatching {
                    val rawJson = song.rawMetaJson ?: song.relativeFolderPath
                    if (!rawJson.isNullOrBlank()) {
                        val obj = org.json.JSONObject(rawJson)
                        obj.optString("sourceName").ifBlank { obj.optString("scriptName") }
                    } else ""
                }.getOrNull()?.ifBlank { null } ?: "官方音源"

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isOnlineSong) {
                        SpecRowItem("解析来源", "$sourceName / ${platformName}解析", isBold = true, valueColor = AppleRed)
                    }
                    SpecRowItem("音质等级", qualityTag, valueColor = if (isLossless) Color(0xFFD4AF37) else AppleRed)
                    SpecRowItem("编码格式", formatStr, isBold = true)
                    SpecRowItem("音频码率", "${if (song.bitRate > 0) song.bitRate else (if (isLossless) 920 else 320)} kbps")
                    SpecRowItem("文件大小", sizeText, isBold = true, valueColor = AppleRed)
                    SpecRowItem("存储位置", locationText, isPath = true)
                    SpecRowItem("播放通道", activeChannelSummary, valueColor = AppleRed)
                }
            }
        }
    }
}

@Composable
fun AudioOutputDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    song: UnifiedSong,
    progressMs: Long = 0L
) {
    if (!expanded) return

    val context = LocalContext.current
    val localRoutes by AudioSharingManager.localRoutes.collectAsState()
    val lanDevices by AudioSharingManager.lanDevices.collectAsState()
    val activeCastDevice by AudioSharingManager.activeCastDevice.collectAsState()
    val isScanning by AudioSharingManager.isScanning.collectAsState()

    LaunchedEffect(expanded) {
        if (expanded) {
            AudioSharingManager.refreshLocalAudioRoutes(context)
            AudioSharingManager.startLanDiscovery(context)
        }
    }

    // 精简版「播放到」：一张列表（本机输出 + 局域网 AirPlay / DLNA），当前输出打勾；底部两个系统入口
    com.lm.player.core.designsystem.glass.AdaptiveDropdownMenu(
        expanded = true,
        onDismissRequest = onDismissRequest,
        modifier = Modifier
            .width(264.dp)
            .heightIn(max = 420.dp),
        preferAbove = true
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "播放到",
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !isScanning) {
                            AudioSharingManager.refreshLocalAudioRoutes(context)
                            AudioSharingManager.startLanDiscovery(context)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = AppleRed
                        )
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "重新搜索", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // 本机输出（扬声器 / 蓝牙 / USB / 耳机）
            localRoutes.forEach { route ->
                OutputTargetRow(
                    icon = when {
                        route.typeLabel.contains("蓝牙") -> Icons.Default.Bluetooth
                        route.typeLabel.contains("USB") -> Icons.Default.Usb
                        route.typeLabel.contains("耳机") -> Icons.Default.Headphones
                        else -> Icons.AutoMirrored.Filled.VolumeUp
                    },
                    name = route.name,
                    badge = null,
                    selected = activeCastDevice == null && route.isActivePrimary,
                    onClick = {
                        if (activeCastDevice != null) {
                            AudioSharingManager.stopActiveCastAsync(
                                context = context,
                                toastMessage = "已断开投射，恢复本机输出：${route.name}"
                            )
                        } else {
                            AudioSharingManager.launchSystemMediaOutputSwitcher(context)
                        }
                        onDismissRequest()
                    }
                )
            }

            // 局域网 AirPlay / DLNA 接收端
            lanDevices.forEach { device ->
                val isCurrentCast = activeCastDevice?.id == device.id
                OutputTargetRow(
                    icon = if (device.protocol == ShareProtocolType.AIRPLAY_RAOP) Icons.Default.Airplay else Icons.Default.SpeakerGroup,
                    name = device.name,
                    badge = device.protocol.badge,
                    selected = isCurrentCast,
                    onClick = {
                        if (isCurrentCast) {
                            AudioSharingManager.stopActiveCastAsync(
                                context = context,
                                toastMessage = "已停止向 ${device.name} 投射"
                            )
                        } else {
                            AudioSharingManager.castSongToDeviceAsync(context, device, song, progressMs)
                        }
                        onDismissRequest()
                    }
                )
            }
            if (lanDevices.isEmpty()) {
                Text(
                    text = if (isScanning) "正在搜索同一 Wi-Fi 下的音响…" else "未发现 AirPlay / DLNA 设备",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutputShortcut(icon = Icons.Default.SettingsInputComponent, label = "系统输出", modifier = Modifier.weight(1f)) {
                    AudioSharingManager.launchSystemMediaOutputSwitcher(context)
                    onDismissRequest()
                }
                OutputShortcut(icon = Icons.Default.CastConnected, label = "系统投屏", modifier = Modifier.weight(1f)) {
                    AudioSharingManager.launchSystemCastSettings(context)
                    onDismissRequest()
                }
            }
        }
    }
}

@Composable
private fun OutputTargetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    badge: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AppleRed.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = name,
            fontSize = 13.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) AppleRed else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (badge != null) {
            Text(
                text = badge,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        if (selected) {
            Spacer(modifier = Modifier.width(6.dp))
            Icon(Icons.Default.Check, contentDescription = null, tint = AppleRed, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun OutputShortcut(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
        Spacer(modifier = Modifier.width(5.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}


/** 布局回调里没有 Density：用坐标的像素换算（dp × 屏幕密度） */
private fun androidx.compose.ui.unit.Dp.toPxF(@Suppress("UNUSED_PARAMETER") c: androidx.compose.ui.layout.LayoutCoordinates): Float =
    value * android.content.res.Resources.getSystem().displayMetrics.density
