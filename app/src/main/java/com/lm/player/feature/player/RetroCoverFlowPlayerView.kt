package com.lm.player.feature.player

import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.lm.player.core.designsystem.component.AddToPlaylistDropdownMenu
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.DownloadQualityDropdownMenu
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LyricTheme
import com.lm.player.core.designsystem.theme.PlayerThemeStyle
import com.lm.player.core.designsystem.theme.SystemFontManager
import com.lm.player.core.media.AudioSharingManager
import com.lm.player.core.media.PlaybackQueueManager
import com.lm.player.core.model.AudioQuality
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.DownloadTarget
import com.lm.player.core.model.LyricLine
import com.lm.player.core.model.LyricResult
import com.lm.player.core.model.UnifiedPlaylist
import com.lm.player.core.model.UnifiedSong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 怀旧专辑 (iPod Cover Flow) 全屏播放界面主题
 * - 支持横屏下【左右拖动调节歌词与播放器展示区域】(歌词区域最大限制不超过 1/3，支持快速收起与无级拖动调节)；
 * - 纯物理线性连续 3D 动力学流 (20 预载池 + 16 核心展台 + 4 边缘透明冗余缓冲，彻底杜绝抽搐)；
 * - 下滑关闭手势支持 (顺畅退出全屏播放器)；
 * - 底部按键复用现代模式完整播放控制、进度条与 6 大功能栏（喜欢/队列/定时/投屏/参数/切回现代）。
 */
@androidx.compose.foundation.ExperimentalFoundationApi
@Composable
fun RetroCoverFlowPlayerView(
    song: UnifiedSong,
    playlist: List<UnifiedSong> = emptyList(),
    isPlaying: Boolean,
    progressMs: Long,
    totalDurationMs: Long,
    lyrics: LyricResult,
    isShuffle: Boolean,
    isRepeat: Boolean,
    activeTimerMinutes: Int = 0,
    fontSizeSp: Float = 22f,
    lyricsOffsetMs: Long = 0L,
    lyricTheme: LyricTheme = LyricTheme.APPLE_MUSIC,
    allPlaylists: List<UnifiedPlaylist> = emptyList(),
    isServerConnected: Boolean = false,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSelectSongFromQueue: (UnifiedSong) -> Unit = {},
    onToggleFavorite: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onAddToPlaylist: (UnifiedPlaylist, UnifiedSong) -> Unit = { _, _ -> },
    onCreatePlaylistAndAddSong: (String, UnifiedSong) -> Unit = { _, _ -> },
    onDownloadSongWithOptions: (UnifiedSong, DownloadTarget, AudioQuality) -> Unit = { _, _, _ -> },
    onSelectTimer: (Int) -> Unit = {},
    onFontSizeChange: (Float) -> Unit = {},
    onOffsetChange: (Long) -> Unit = {},
    onThemeChange: (LyricTheme) -> Unit = {},
    onSwitchPlayerTheme: (PlayerThemeStyle) -> Unit = {},
    onOpenSleepTimer: () -> Unit = {},
    onOpenAudioSpecs: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val screenWidth = configuration.screenWidthDp
    val screenHeight = configuration.screenHeightDp
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f

    val primaryTextColor = if (isDark) Color.White else Color.Black
    val secondaryTextColor = if (isDark) Color(0xFFAAAAAE) else Color(0xFF6C6C70)

    val currentPlaylist = remember(playlist, song) {
        if (playlist.isNotEmpty()) playlist else listOf(song)
    }

    val initialIndex = remember(currentPlaylist, song.id) {
        currentPlaylist.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { currentPlaylist.size }
    )

    // 横屏歌词宽度占比偏好 (默认 1/3 = 0.333f，最大范围不超过 1/3)
    val lyricsPrefs = remember { context.getSharedPreferences("zds_lyrics_prefs", Context.MODE_PRIVATE) }
    var landscapeLyricsRatio by remember {
        mutableStateOf(lyricsPrefs.getFloat("landscape_lyrics_width_ratio", 0.333f).coerceIn(0.0f, 0.333f))
    }
    var isDraggingSplitter by remember { mutableStateOf(false) }

    // 经典 iPod Cover Flow 物理动力学参数 (高线性弹簧阻尼，丝滑连贯如真实转轮)
    val currentSpringSpec = remember {
        spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    }
    val pagerFlingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        snapAnimationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        )
    )

    // 用户在 Pager 滑动选歌标记，杜绝反向触发 animateScrollToPage 导致手势拉扯抽动
    var isPageSelectedBySwipe by remember { mutableStateOf(false) }

    // 监听用户手势滑动停止后切歌 (settledPage 确定目标后再触发)
    LaunchedEffect(pagerState.settledPage) {
        val selected = currentPlaylist.getOrNull(pagerState.settledPage)
        if (selected != null && selected.id != song.id) {
            isPageSelectedBySwipe = true
            onSelectSongFromQueue(selected)
        }
    }

    // 监听外部切歌联动 Pager (若为当前 Pager 手势滑动触发则直接跳过，杜绝双向死锁与抽动)
    LaunchedEffect(song.id) {
        if (isPageSelectedBySwipe) {
            isPageSelectedBySwipe = false
            return@LaunchedEffect
        }
        val targetIdx = currentPlaylist.indexOfFirst { it.id == song.id }
        if (targetIdx >= 0 && !pagerState.isScrollInProgress && targetIdx != pagerState.currentPage) {
            pagerState.animateScrollToPage(
                targetIdx,
                animationSpec = currentSpringSpec
            )
        }
    }

    // 播放/暂停毛玻璃高透图标交互状态 (无操作 2 秒自动隐藏)
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showPlayOverlay by remember { mutableStateOf(true) }

    // 监听滑动交互自动唤醒图标
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress) {
            showPlayOverlay = true
            lastInteractionTime = System.currentTimeMillis()
        }
    }

    // 自动 2 秒无操作平滑淡出隐藏
    LaunchedEffect(lastInteractionTime, showPlayOverlay) {
        if (showPlayOverlay) {
            delay(2000L)
            showPlayOverlay = false
        }
    }

    var showLyricsAdjustDialog by remember { mutableStateOf(false) }
    var showSleepTimerMenu by remember { mutableStateOf(false) }
    var showAudioSpecsMenu by remember { mutableStateOf(false) }
    var showAudioOutputMenu by remember { mutableStateOf(false) }
    var showAddToPlaylistMenu by remember { mutableStateOf(false) }
    var showDownloadMenu by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }

    val activeCastDevice by AudioSharingManager.activeCastDevice.collectAsState()
    val sleepTimerRemainingSec by PlaybackQueueManager.sleepTimerRemainingSecFlow.collectAsState()

    val circleButtonBg = if (isDark) Color(0xFF25252A) else Color(0xFFF2F2F7)

    // 磁盘与缓存状态缓存 (remember 避免主线程高频 I/O)
    val hasPhysicalLocal = remember(song.id, song.localFilePath) {
        val path = song.localFilePath
        !path.isNullOrBlank() && (path.startsWith("content://") ||
            runCatching { java.io.File(path).let { it.exists() && it.length() > 0L } }.getOrDefault(false))
    }
    val isDownloaded = hasPhysicalLocal && (song.downloadStatus == DownloadStatus.DOWNLOADED || !song.localFilePath.isNullOrBlank())
    val isServerCached = song.serverId.isNotBlank() && song.serverId != "local_storage" && song.serverId != "lemon_online"

    // 向下滑动退出全屏播放器位移与弹性动画
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val animatedOffsetY by animateFloatAsState(
        targetValue = dragOffsetY,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "retroDragOffsetY"
    )

    // 全局歌词设置与逐字适配
    val globalPrefs = remember { context.getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE) }
    var isKaraokeEnabled by remember {
        mutableStateOf(globalPrefs.getBoolean("lyric_karaoke_enabled", true))
    }
    var useSystemFont by remember {
        mutableStateOf(globalPrefs.getBoolean("lyric_use_system_font", true))
    }
    val lyricFontFamily = remember(useSystemFont) {
        SystemFontManager.getSystemFontFamily(useSystemFont)
    }

    val activeColor = if (isDark) lyricTheme.activeColorDark else lyricTheme.activeColorLight
    val inactiveColor = if (isDark) lyricTheme.inactiveColorDark else lyricTheme.inactiveColorLight
    val karaokeBaseColor = lyricTheme.getKaraokeBaseColor(isDark)
    val karaokeOverlayColor = lyricTheme.getKaraokeOverlayColor(isDark)

    // 经典暗黑黑胶与水晶镜面渐变背景 (remember 缓存画刷，杜绝每秒高频重组重复分配内存)
    val backdropBrush = remember(isDark) {
        if (isDark) {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF07080A),
                    Color(0xFF0D0F14),
                    Color(0xFF040507)
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFE2E0E6),
                    Color(0xFFEFEFF3),
                    Color(0xFFDAD7DE)
                )
            )
        }
    }

    // 缓存倒影渐变画刷，彻底杜绝绘制阶段每帧分配对象
    val reflectionGradient = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color.Black.copy(alpha = 0.12f),
                Color.Black.copy(alpha = 0.45f),
                Color.Black.copy(alpha = 0.85f),
                Color.Black
            )
        )
    }

    val cleanLyrics = remember(lyrics) {
        lyrics.lines.filter { it.text.isNotBlank() && !it.text.trim().equals("null", ignoreCase = true) }
    }
    val effectiveTimeMs = (progressMs + lyricsOffsetMs).coerceAtLeast(0L)

    // 逐字歌词高刷帧级平滑时间插值，消除刷新周期产生的逐字跳跃
    var lastTickPos by remember { mutableLongStateOf(effectiveTimeMs) }
    var lastTickUptime by remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }

    LaunchedEffect(effectiveTimeMs) {
        lastTickPos = effectiveTimeMs
        lastTickUptime = android.os.SystemClock.uptimeMillis()
    }

    val isPlayingState by com.lm.player.core.media.PlaybackQueueManager.isPlayingFlow.collectAsState()
    var frameTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isKaraokeEnabled, isPlayingState) {
        if (isKaraokeEnabled && isPlayingState) {
            while (true) {
                withFrameMillis { frameTick = it }
            }
        }
    }

    val activeLyricIdx = remember(cleanLyrics, effectiveTimeMs) {
        var low = 0
        var high = cleanLyrics.lastIndex
        var best = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cleanLyrics[mid].timestampMs <= effectiveTimeMs) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        best
    }
    val lyricsListState = rememberLazyListState()

    LaunchedEffect(activeLyricIdx) {
        if (cleanLyrics.isNotEmpty() && activeLyricIdx in cleanLyrics.indices && !lyricsListState.isScrollInProgress) {
            lyricsListState.animateScrollToItem(
                index = (activeLyricIdx - 2).coerceAtLeast(0),
                scrollOffset = 0
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, animatedOffsetY.roundToInt().coerceAtLeast(0)) }
            .background(backdropBrush)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount ->
                        if (dragAmount > 0 || dragOffsetY > 0) {
                            dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                        }
                    },
                    onDragEnd = {
                        if (dragOffsetY > 160f) {
                            onDismiss()
                        }
                        dragOffsetY = 0f
                    },
                    onDragCancel = {
                        dragOffsetY = 0f
                    }
                )
            }
    ) {
        if (isLandscape) {
            // =========================================================================
            // 横屏布局：支持左右拖拽调节比例 (左侧播放器 66.7%~100% + 中间调节手柄 + 右侧歌词 0%~33.3%)
            // =========================================================================
            val screenWidthPx = with(density) { screenWidth.dp.toPx() }
            val effectiveLyricsRatio = landscapeLyricsRatio.coerceIn(0.0f, 0.333f)
            val playerWeight = (1.0f - effectiveLyricsRatio).coerceIn(0.667f, 1.0f)
            val isLyricsVisible = effectiveLyricsRatio > 0.04f

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧播放器区 (自适应占比 66.7% ~ 100%，高度自适应手机/平板横屏)
                val isCompactLandscape = screenHeight < 420
                val isUltraCompactLandscape = screenHeight < 365

                Column(
                    modifier = Modifier
                        .weight(playerWeight)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // 1. 顶部 Header (提升 zIndex 彻底避免被 3D 卡片遮挡)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(150f)
                            .padding(horizontal = 12.dp, vertical = if (isCompactLandscape) 0.dp else 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (isCompactLandscape) 30.dp else 36.dp)
                                .clip(CircleShape)
                                .background(if (isDark) Color(0x44FFFFFF) else Color(0x33000000))
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "最小化",
                                tint = primaryTextColor,
                                modifier = Modifier.size(if (isCompactLandscape) 20.dp else 22.dp)
                            )
                        }
                    }

                    // 2. 3D Cover Flow 展台 (使用 weight(1f) + BoxWithConstraints 动态测算剩余高度，彻底消除手机横屏底部遮挡)
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        val reflectionRatio = if (isCompactLandscape) 0.18f else 0.25f
                        val cardSizeDp = ((maxHeight.value - 4f) / (1f + reflectionRatio))
                            .coerceIn(88f, 245f)
                            .dp
                        val reflectionHeightDp = cardSizeDp * reflectionRatio
                        val horizontalPaddingDp = ((maxWidth.value - cardSizeDp.value) / 2f).coerceAtLeast(0f).dp
                        val beyondBoundsCount = 5
                        val cardSizePx = with(density) { cardSizeDp.toPx() }
                        HorizontalPager(
                            state = pagerState,
                            contentPadding = PaddingValues(horizontal = horizontalPaddingDp),
                            pageSize = PageSize.Fixed(cardSizeDp),
                            pageSpacing = 0.dp,
                            beyondBoundsPageCount = beyondBoundsCount,
                            flingBehavior = pagerFlingBehavior,
                            key = { currentPlaylist.getOrNull(it)?.id ?: it.toString() },
                            modifier = Modifier.fillMaxSize()
                        ) { index ->
                            val targetSong = currentPlaylist.getOrNull(index) ?: song
                            val zIndexVal = (1000 - abs(pagerState.currentPage - index)).toFloat()
                            val isCenter = pagerState.currentPage == index

                            Column(
                                modifier = Modifier
                                    .width(cardSizeDp)
                                    .zIndex(zIndexVal)
                                    .graphicsLayer {
                                        val pageOffset = (pagerState.currentPage - index) + pagerState.currentPageOffsetFraction
                                        val absOffset = abs(pageOffset)
                                        val sign = if (pageOffset > 0) 1f else -1f

                                        // -------------------------------------------------------------
                                        // 经典 iPod 专辑流连续线性过渡动力学 (黄金比例展开与平滑景深)
                                        // -------------------------------------------------------------
                                        // A. 绝对线性 3D 旋转翻折角 (在 [-1, 1] 内严格线性过渡，超出后严格恒定 62°)
                                        val rotationYVal = (pageOffset * 62f).coerceIn(-62f, 62f)

                                        // B. 线性分段位移 (中心 0.52W 展开，侧翼 0.22W 堆叠间隔)
                                        val offsetCenter = 0.52f
                                        val sideSpace = 0.22f
                                        val visualDistFactor = (Math.min(absOffset, 1.0f) * offsetCenter + Math.max(0.0f, absOffset - 1.0f) * sideSpace)
                                        val targetVisualX = -sign * visualDistFactor * cardSizePx

                                        val naturalX = -pageOffset * cardSizePx
                                        val translationXVal = targetVisualX - naturalX

                                        // C. 线性外凸景深缩放
                                        val scaleVal = (1.0f - Math.min(absOffset, 1.0f) * 0.16f - Math.max(0.0f, absOffset - 1.0f) * 0.015f).coerceIn(0.70f, 1.0f)

                                        // D. 边缘平滑渐隐包络 (在 2.8 到 4.2 之间优雅平滑渐变至 0，绝不在视窗边缘突变抽搐)
                                        val alphaVal = when {
                                            absOffset <= 2.8f -> 1.0f
                                            absOffset <= 4.2f -> ((4.2f - absOffset) / 1.4f).coerceIn(0f, 1.0f)
                                            else -> 0f
                                        }

                                        this.translationX = translationXVal
                                        this.rotationY = rotationYVal
                                        this.scaleX = scaleVal
                                        this.scaleY = scaleVal
                                        this.alpha = alphaVal
                                        this.transformOrigin = TransformOrigin(0.5f, 0.5f)
                                        this.cameraDistance = 24f * density.density
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFF14161C),
                                    shadowElevation = 14.dp,
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = Color.White.copy(alpha = 0.25f)
                                    ),
                                    modifier = Modifier
                                        .size(cardSizeDp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (pagerState.currentPage != index) {
                                                coroutineScope.launch {
                                                    pagerState.animateScrollToPage(
                                                        index,
                                                        animationSpec = currentSpringSpec
                                                    )
                                                }
                                            } else {
                                                showPlayOverlay = true
                                                lastInteractionTime = System.currentTimeMillis()
                                                onTogglePlayPause()
                                            }
                                        }
                                ) {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        AlbumArtworkImage(
                                            model = targetSong.coverUrl,
                                            seedId = targetSong.id,
                                            targetSize = 360,
                                            modifier = Modifier.fillMaxSize(),
                                            cornerRadius = 12.dp
                                        )

                                        // 中心活跃卡片毛玻璃高透播放/暂停图标 (静止时展示，滑动中隐藏避免闪现)
                                        if (isCenter && !pagerState.isScrollInProgress) {
                                            androidx.compose.animation.AnimatedVisibility(
                                                visible = showPlayOverlay,
                                                enter = fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.8f),
                                                exit = fadeOut(animationSpec = tween(300)) + scaleOut(targetScale = 0.8f),
                                                modifier = Modifier.align(Alignment.Center)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(if (isCompactLandscape) 44.dp else 54.dp)
                                                        .clip(CircleShape)
                                                        .background(Color.Black.copy(alpha = 0.45f))
                                                        .border(
                                                            width = 1.2.dp,
                                                            brush = Brush.linearGradient(
                                                                colors = listOf(
                                                                    Color.White.copy(alpha = 0.65f),
                                                                    Color.White.copy(alpha = 0.20f)
                                                                )
                                                            ),
                                                            shape = CircleShape
                                                        )
                                                        .clickable {
                                                            showPlayOverlay = true
                                                            lastInteractionTime = System.currentTimeMillis()
                                                            onTogglePlayPause()
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                                        contentDescription = if (isPlaying) "暂停" else "播放",
                                                        tint = Color.White,
                                                        modifier = Modifier.size(if (isCompactLandscape) 24.dp else 28.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // 倒影
                                Box(
                                    modifier = Modifier
                                        .width(cardSizeDp)
                                        .height(reflectionHeightDp)
                                        .graphicsLayer {
                                            scaleY = -1f
                                            this.alpha = 0.35f
                                        }
                                        .drawWithContent {
                                            drawContent()
                                            drawRect(brush = reflectionGradient)
                                        }
                                        .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                                ) {
                                    AlbumArtworkImage(
                                        model = targetSong.coverUrl,
                                        seedId = targetSong.id,
                                        modifier = Modifier.fillMaxSize(),
                                        cornerRadius = 8.dp
                                    )
                                }
                            }
                        }
                    }

                    // 3. 歌曲标题与歌手
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = song.title,
                            style = TextStyle(
                                fontSize = if (isUltraCompactLandscape) 14.sp else if (isCompactLandscape) 15.sp else 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = primaryTextColor
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(if (isCompactLandscape) 1.dp else 2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${song.artist} — ${if (song.album.isNotBlank()) song.album else "精选集"}",
                                style = TextStyle(fontSize = if (isCompactLandscape) 11.sp else 12.sp, color = secondaryTextColor),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(4.dp), color = AppleRed.copy(alpha = 0.15f)) {
                                Text(
                                    text = "${song.format.uppercase()} ${song.bitRate}k",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppleRed,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    // 4. 精简底栏 (无遮挡，随机/循环按键精巧布局)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val progressRatio = if (totalDurationMs > 0) (progressMs.toFloat() / totalDurationMs).coerceIn(0f, 1f) else 0f
                        Slider(
                            value = progressRatio,
                            onValueChange = { ratio -> onSeekTo((ratio * totalDurationMs).toLong()) },
                            colors = SliderDefaults.colors(
                                thumbColor = primaryTextColor,
                                activeTrackColor = primaryTextColor,
                                inactiveTrackColor = primaryTextColor.copy(alpha = 0.2f)
                            ),
                            modifier = Modifier.fillMaxWidth().height(14.dp)
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = formatDuration(progressMs), fontSize = 11.sp, color = secondaryTextColor)
                            Text(text = formatDuration(totalDurationMs), fontSize = 11.sp, color = secondaryTextColor)
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        // 底栏工具条
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isShuffle) AppleRed.copy(alpha = 0.18f) else if (isDark) Color(0x22FFFFFF) else Color(0x15000000),
                                border = BorderStroke(0.6.dp, if (isShuffle) AppleRed.copy(alpha = 0.6f) else Color.Transparent),
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onToggleShuffle)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Shuffle,
                                        contentDescription = "随机播放",
                                        tint = if (isShuffle) AppleRed else secondaryTextColor,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = if (isShuffle) "随机开启" else "随机",
                                        fontSize = 11.sp,
                                        fontWeight = if (isShuffle) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isShuffle) AppleRed else secondaryTextColor
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (activeTimerMinutes > 0) AppleRed.copy(alpha = 0.18f) else if (isDark) Color(0x22FFFFFF) else Color(0x15000000),
                                        border = BorderStroke(0.6.dp, if (activeTimerMinutes > 0) AppleRed.copy(alpha = 0.6f) else Color.Transparent),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { showSleepTimerMenu = true }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.Timer,
                                                contentDescription = null,
                                                tint = if (activeTimerMinutes > 0) AppleRed else secondaryTextColor,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = if (activeTimerMinutes > 0) "${activeTimerMinutes}分" else "定时",
                                                fontSize = 11.sp,
                                                fontWeight = if (activeTimerMinutes > 0) FontWeight.Bold else FontWeight.Normal,
                                                color = if (activeTimerMinutes > 0) AppleRed else secondaryTextColor
                                            )
                                        }
                                    }
                                    SleepTimerDropdownMenu(
                                        expanded = showSleepTimerMenu,
                                        onDismissRequest = { showSleepTimerMenu = false },
                                        activeTimerMinutes = activeTimerMinutes,
                                        onSelectTimer = onSelectTimer
                                    )
                                }

                                Box {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isDark) Color(0x22FFFFFF) else Color(0x15000000),
                                        border = BorderStroke(0.6.dp, Color.Transparent),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { showAudioSpecsMenu = true }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Outlined.Info, contentDescription = null, tint = secondaryTextColor, modifier = Modifier.size(13.dp))
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text("参数", fontSize = 11.sp, color = secondaryTextColor)
                                        }
                                    }
                                    AudioSpecsDropdownMenu(
                                        expanded = showAudioSpecsMenu,
                                        onDismissRequest = { showAudioSpecsMenu = false },
                                        song = song
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isRepeat) AppleRed.copy(alpha = 0.18f) else if (isDark) Color(0x22FFFFFF) else Color(0x15000000),
                                border = BorderStroke(0.6.dp, if (isRepeat) AppleRed.copy(alpha = 0.6f) else Color.Transparent),
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onToggleRepeat)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Repeat,
                                        contentDescription = "单曲循环",
                                        tint = if (isRepeat) AppleRed else secondaryTextColor,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = if (isRepeat) "单曲循环" else "循环",
                                        fontSize = 11.sp,
                                        fontWeight = if (isRepeat) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isRepeat) AppleRed else secondaryTextColor
                                    )
                                }
                            }
                        }
                    }
                }

                // 中间：可左右拖动手柄 (歌词区域最大限制不超过 1/3 屏幕)
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(18.dp)
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { isDraggingSplitter = true },
                                onDragEnd = { isDraggingSplitter = false },
                                onDragCancel = { isDraggingSplitter = false },
                                onHorizontalDrag = { _, dragAmount ->
                                    // 向左拖动增加歌词宽度 (delta 为负)，向右拖动缩小歌词宽度
                                    val deltaRatio = -dragAmount / screenWidthPx
                                    val newRatio = (landscapeLyricsRatio + deltaRatio).coerceIn(0.0f, 0.333f)
                                    landscapeLyricsRatio = newRatio
                                    lyricsPrefs.edit().putFloat("landscape_lyrics_width_ratio", newRatio).apply()
                                }
                            )
                        }
                        .clickable {
                            val newRatio = if (landscapeLyricsRatio > 0.05f) 0.0f else 0.333f
                            landscapeLyricsRatio = newRatio
                            lyricsPrefs.edit().putFloat("landscape_lyrics_width_ratio", newRatio).apply()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight(0.65f)
                            .background(if (isDraggingSplitter) AppleRed else if (isDark) Color(0x33FFFFFF) else Color(0x22000000))
                    )
                    Surface(
                        shape = RoundedCornerShape(2.dp),
                        color = if (isDraggingSplitter) AppleRed else if (isDark) Color(0x88FFFFFF) else Color(0x66000000),
                        shadowElevation = if (isDraggingSplitter) 4.dp else 0.dp,
                        modifier = Modifier
                            .width(if (isDraggingSplitter) 5.dp else 4.dp)
                            .height(if (isDraggingSplitter) 44.dp else 36.dp)
                    ) {}
                }

                // 右侧：歌词区 (自适应占比 0% ~ 33.3%)
                if (isLyricsVisible) {
                    Column(
                        modifier = Modifier
                            .weight(effectiveLyricsRatio)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.Top
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("实时歌词", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = primaryTextColor)

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isDark) Color(0x28FFFFFF) else Color(0x18000000),
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showLyricsAdjustDialog = true }
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Tune, contentDescription = null, tint = AppleRed, modifier = Modifier.size(11.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("调节", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AppleRed)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        if (cleanLyrics.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("♪ 纯音乐 / 暂无歌词 ♪", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (isDark) lyricTheme.activeColorDark else lyricTheme.activeColorLight)
                            }
                        } else {
                            LazyColumn(
                                state = lyricsListState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy((fontSizeSp * 0.75f).dp)
                            ) {
                                itemsIndexed(cleanLyrics, key = { index, item -> "${item.timestampMs}_$index" }) { index, item ->
                                    val isActive = index == activeLyricIdx
                                    RetroKaraokeLyricRow(
                                        item = item,
                                        index = index,
                                        cleanLyrics = cleanLyrics,
                                        isActive = isActive,
                                        isKaraokeEnabled = isKaraokeEnabled,
                                        effectiveTimeMs = effectiveTimeMs,
                                        lastTickPos = lastTickPos,
                                        lastTickUptime = lastTickUptime,
                                        frameTick = frameTick,
                                        fontSizeSp = fontSizeSp,
                                        lyricFontFamily = lyricFontFamily,
                                        activeColor = activeColor,
                                        inactiveColor = inactiveColor,
                                        karaokeBaseColor = karaokeBaseColor,
                                        karaokeOverlayColor = karaokeOverlayColor,
                                        lyricsOffsetMs = lyricsOffsetMs,
                                        textAlign = TextAlign.Start,
                                        alignment = Alignment.CenterStart,
                                        onSeekTo = onSeekTo
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // 当歌词折叠时，右侧提供优雅的展开恢复小胶囊
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isDark) Color(0x28FFFFFF) else Color(0x18000000),
                        border = BorderStroke(0.6.dp, if (isDark) Color(0x33FFFFFF) else Color(0x22000000)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                landscapeLyricsRatio = 0.333f
                                lyricsPrefs.edit().putFloat("landscape_lyrics_width_ratio", 0.333f).apply()
                            }
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Lyrics, contentDescription = "展开歌词", tint = AppleRed, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("歌\n词", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = primaryTextColor, lineHeight = 12.sp)
                        }
                    }
                }
            }
        } else {
            // =========================================================================
            // 竖屏布局：上半部大号 3D Cover Flow (【9 首歌曲】展台) + 下半部歌词 + 底部控制器
            // =========================================================================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. 顶部 Header (仅保留返回按钮，顶栏留白沉浸)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(34.dp).clip(CircleShape).background(if (isDark) Color(0x33FFFFFF) else Color(0x22000000))
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "最小化", tint = primaryTextColor, modifier = Modifier.size(20.dp))
                    }
                }

                // 2. 竖屏大号 3D Cover Flow 舞台
                val cardSizeDp = 240.dp
                val reflectionHeightDp = cardSizeDp * 0.32f
                val stageHeightDp = cardSizeDp + reflectionHeightDp + 6.dp
                val horizontalPaddingDp = ((screenWidth - cardSizeDp.value) / 2).dp

                Box(
                    modifier = Modifier.fillMaxWidth().height(stageHeightDp),
                    contentAlignment = Alignment.Center
                ) {
                    val beyondBoundsCount = 5
                    val cardSizePx = with(density) { cardSizeDp.toPx() }
                    HorizontalPager(
                        state = pagerState,
                        contentPadding = PaddingValues(horizontal = horizontalPaddingDp),
                        pageSize = PageSize.Fixed(cardSizeDp),
                        pageSpacing = 0.dp,
                        beyondBoundsPageCount = beyondBoundsCount,
                        flingBehavior = pagerFlingBehavior,
                        key = { currentPlaylist.getOrNull(it)?.id ?: it.toString() },
                        modifier = Modifier.fillMaxSize()
                    ) { index ->
                        val targetSong = currentPlaylist.getOrNull(index) ?: song
                        val zIndexVal = (1000 - abs(pagerState.currentPage - index)).toFloat()
                        val isCenter = pagerState.currentPage == index

                        Column(
                            modifier = Modifier
                                .width(cardSizeDp)
                                .zIndex(zIndexVal)
                                .graphicsLayer {
                                    val pageOffset = (pagerState.currentPage - index) + pagerState.currentPageOffsetFraction
                                    val absOffset = abs(pageOffset)
                                    val sign = if (pageOffset > 0) 1f else -1f

                                    // -------------------------------------------------------------
                                    // 竖屏经典 iPod 专辑流连续线性过渡动力学 (黄金比例展开与平滑景深)
                                    // -------------------------------------------------------------
                                    // A. 绝对线性 3D 旋转翻折角 (在 [-1, 1] 内严格线性过渡，超出后严格恒定 60°)
                                    val rotationYVal = (pageOffset * 60f).coerceIn(-60f, 60f)

                                    // B. 线性分段位移 (中心 0.55W 展开，侧翼 0.22W 堆叠间隔)
                                    val offsetCenter = 0.55f
                                    val sideSpace = 0.22f
                                    val visualDistFactor = (Math.min(absOffset, 1.0f) * offsetCenter + Math.max(0.0f, absOffset - 1.0f) * sideSpace)
                                    val targetVisualX = -sign * visualDistFactor * cardSizePx

                                    val naturalX = -pageOffset * cardSizePx
                                    val translationXVal = targetVisualX - naturalX

                                    // C. 线性外凸景深缩放
                                    val scaleVal = (1.0f - Math.min(absOffset, 1.0f) * 0.16f - Math.max(0.0f, absOffset - 1.0f) * 0.018f).coerceIn(0.72f, 1.0f)

                                    // D. 边缘平滑渐隐包络 (在 2.8 到 4.2 之间优雅平滑渐变至 0，绝不在视窗边缘突变抽搐)
                                    val alphaVal = when {
                                        absOffset <= 2.8f -> 1.0f
                                        absOffset <= 4.2f -> ((4.2f - absOffset) / 1.4f).coerceIn(0f, 1.0f)
                                        else -> 0f
                                    }

                                    this.translationX = translationXVal
                                    this.rotationY = rotationYVal
                                    this.scaleX = scaleVal
                                    this.scaleY = scaleVal
                                    this.alpha = alphaVal
                                    this.transformOrigin = TransformOrigin(0.5f, 0.5f)
                                    this.cameraDistance = 24f * density.density
                                },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF14161C),
                                shadowElevation = 14.dp,
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = Color.White.copy(alpha = 0.25f)
                                ),
                                modifier = Modifier
                                    .size(cardSizeDp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (pagerState.currentPage != index) {
                                            coroutineScope.launch {
                                                pagerState.animateScrollToPage(
                                                    index,
                                                    animationSpec = currentSpringSpec
                                                )
                                            }
                                        } else {
                                            showPlayOverlay = true
                                            lastInteractionTime = System.currentTimeMillis()
                                            onTogglePlayPause()
                                        }
                                    }
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    AlbumArtworkImage(
                                        model = targetSong.coverUrl,
                                        seedId = targetSong.id,
                                        targetSize = 360,
                                        modifier = Modifier.fillMaxSize(),
                                        cornerRadius = 12.dp
                                    )

                                    // 中心活跃卡片毛玻璃高透播放/暂停图标 (静止时展示，滑动中隐藏避免闪现)
                                    if (isCenter && !pagerState.isScrollInProgress) {
                                        androidx.compose.animation.AnimatedVisibility(
                                            visible = showPlayOverlay,
                                            enter = fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.8f),
                                            exit = fadeOut(animationSpec = tween(300)) + scaleOut(targetScale = 0.8f),
                                            modifier = Modifier.align(Alignment.Center)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .clip(CircleShape)
                                                    .background(Color.Black.copy(alpha = 0.45f))
                                                .border(
                                                    width = 1.2.dp,
                                                    brush = Brush.linearGradient(
                                                        colors = listOf(
                                                            Color.White.copy(alpha = 0.65f),
                                                            Color.White.copy(alpha = 0.20f)
                                                        )
                                                    ),
                                                    shape = CircleShape
                                                )
                                                .clickable {
                                                    showPlayOverlay = true
                                                    lastInteractionTime = System.currentTimeMillis()
                                                    onTogglePlayPause()
                                                },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                                    contentDescription = if (isPlaying) "暂停" else "播放",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(30.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 倒影
                            Box(
                                modifier = Modifier
                                    .width(cardSizeDp)
                                    .height(reflectionHeightDp)
                                    .graphicsLayer {
                                        scaleY = -1f
                                        this.alpha = 0.35f
                                    }
                                    .drawWithContent {
                                        drawContent()
                                        drawRect(brush = reflectionGradient)
                                    }
                                    .clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp))
                            ) {
                                AlbumArtworkImage(
                                    model = targetSong.coverUrl,
                                    seedId = targetSong.id,
                                    modifier = Modifier.fillMaxSize(),
                                    cornerRadius = 10.dp
                                )
                            }
                        }
                    }
                }

                // 3. 歌曲标题与歌手 + 加入歌单与下载功能键 (复用现代模式)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = primaryTextColor),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${song.artist} — ${if (song.album.isNotBlank()) song.album else "精选集"}",
                                style = TextStyle(fontSize = 12.sp, color = secondaryTextColor),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(4.dp), color = AppleRed.copy(alpha = 0.15f)) {
                                Text(
                                    text = "${song.format.uppercase()} ${song.bitRate}k",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppleRed,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 加入歌单按钮
                        Box {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(circleButtonBg)
                                    .clickable { showAddToPlaylistMenu = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                    contentDescription = "加入歌单",
                                    tint = primaryTextColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            AddToPlaylistDropdownMenu(
                                expanded = showAddToPlaylistMenu,
                                onDismissRequest = { showAddToPlaylistMenu = false },
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

                        // 下载按钮
                        Box {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(circleButtonBg)
                                    .clickable { showDownloadMenu = true },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isDownloaded && isServerCached) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "双端已同步",
                                        tint = Color(0xFF34C759),
                                        modifier = Modifier.size(19.dp)
                                    )
                                } else if (isDownloaded || isServerCached) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircleOutline,
                                        contentDescription = "单端已缓存",
                                        tint = Color(0xFF34C759),
                                        modifier = Modifier.size(19.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.FileDownload,
                                        contentDescription = "下载",
                                        tint = primaryTextColor,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }

                            DownloadQualityDropdownMenu(
                                expanded = showDownloadMenu,
                                onDismissRequest = { showDownloadMenu = false },
                                song = song,
                                isServerConnected = isServerConnected,
                                hasLocal = hasPhysicalLocal,
                                hasServer = song.serverId.isNotBlank() && song.serverId != "local_storage" && song.serverId != "lemon_online",
                                onConfirm = { target, quality ->
                                    showDownloadMenu = false
                                    onDownloadSongWithOptions(song, target, quality)
                                }
                            )
                        }
                    }
                }

                // 4. 竖屏无背景纯净滚动歌词
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 4.dp)
                ) {
                    if (cleanLyrics.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("♪ 纯音乐 / 暂无歌词 ♪", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (isDark) lyricTheme.activeColorDark else lyricTheme.activeColorLight)
                        }
                    } else {
                        LazyColumn(
                            state = lyricsListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy((fontSizeSp * 0.72f).dp)
                        ) {
                            itemsIndexed(cleanLyrics, key = { index, item -> "${item.timestampMs}_$index" }) { index, item ->
                                val isActive = index == activeLyricIdx
                                RetroKaraokeLyricRow(
                                    item = item,
                                    index = index,
                                    cleanLyrics = cleanLyrics,
                                    isActive = isActive,
                                    isKaraokeEnabled = isKaraokeEnabled,
                                    effectiveTimeMs = effectiveTimeMs,
                                    lastTickPos = lastTickPos,
                                    lastTickUptime = lastTickUptime,
                                    frameTick = frameTick,
                                    fontSizeSp = fontSizeSp,
                                    lyricFontFamily = lyricFontFamily,
                                    activeColor = activeColor,
                                    inactiveColor = inactiveColor,
                                    karaokeBaseColor = karaokeBaseColor,
                                    karaokeOverlayColor = karaokeOverlayColor,
                                    lyricsOffsetMs = lyricsOffsetMs,
                                    textAlign = TextAlign.Center,
                                    alignment = Alignment.Center,
                                    onSeekTo = onSeekTo
                                )
                            }
                        }
                    }

                    // 歌词调节悬浮小徽标
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isDark) Color(0xFF22242C).copy(alpha = 0.85f) else Color(0xFFE5E5EA).copy(alpha = 0.85f),
                        border = BorderStroke(0.6.dp, if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.1f)),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 4.dp, end = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { showLyricsAdjustDialog = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = AppleRed, modifier = Modifier.size(11.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("歌词微调", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AppleRed)
                        }
                    }
                }

                // 5. 底部播放控制与进度条 (完全复用现代模式 3 层布局与功能)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    // 第一层: 进度条与播放时间
                    val progressRatio = if (totalDurationMs > 0) (progressMs.toFloat() / totalDurationMs).coerceIn(0f, 1f) else 0f
                    Slider(
                        value = progressRatio,
                        onValueChange = { ratio -> onSeekTo((ratio * totalDurationMs).toLong()) },
                        colors = SliderDefaults.colors(
                            thumbColor = primaryTextColor,
                            activeTrackColor = primaryTextColor,
                            inactiveTrackColor = primaryTextColor.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.fillMaxWidth().height(18.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = formatDuration(progressMs), style = TextStyle(fontSize = 12.sp, color = secondaryTextColor))
                        Text(text = formatDuration(totalDurationMs), style = TextStyle(fontSize = 12.sp, color = secondaryTextColor))
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 第二层: 核心 5 大主控播放按键 (复用现代模式 68dp 实心大按键)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onToggleShuffle) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = "随机播放",
                                tint = if (isShuffle) AppleRed else primaryTextColor.copy(alpha = 0.8f),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        IconButton(onClick = onPrevious) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "上一首",
                                tint = primaryTextColor,
                                modifier = Modifier.size(38.dp)
                            )
                        }

                        // 大号高对比实心主播放/暂停键 (68dp)
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .background(primaryTextColor)
                                .clickable(onClick = onTogglePlayPause),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = "播放/暂停",
                                tint = if (isDark) Color.Black else Color.White,
                                modifier = Modifier.size(38.dp)
                            )
                        }

                        IconButton(onClick = onNext) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "下一首",
                                tint = primaryTextColor,
                                modifier = Modifier.size(38.dp)
                            )
                        }

                        IconButton(onClick = onToggleRepeat) {
                            Icon(
                                imageVector = Icons.Default.Repeat,
                                contentDescription = "单曲循环",
                                tint = if (isRepeat) AppleRed else primaryTextColor.copy(alpha = 0.8f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // 第三层: 底部 6 项现代功能工具栏: [喜欢] [待播队列] [定时] [音频输出] [参数] [切回现代]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. 喜欢收藏
                        IconButton(onClick = onToggleFavorite) {
                            Icon(
                                imageVector = if (song.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = "红心喜欢",
                                tint = if (song.isFavorite) AppleRed else secondaryTextColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // 2. 待播队列
                        IconButton(onClick = { showQueueSheet = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = "待播队列",
                                tint = secondaryTextColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // 3. 定时关闭
                        Box {
                            IconButton(onClick = { showSleepTimerMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.Timer,
                                    contentDescription = "定时关闭",
                                    tint = if (activeTimerMinutes != 0) AppleRed else secondaryTextColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            SleepTimerDropdownMenu(
                                expanded = showSleepTimerMenu,
                                onDismissRequest = { showSleepTimerMenu = false },
                                activeTimerMinutes = activeTimerMinutes,
                                remainingSeconds = sleepTimerRemainingSec,
                                onSelectTimer = onSelectTimer
                            )
                        }

                        // 4. 音频输出 / 投屏
                        Box {
                            IconButton(onClick = {
                                AudioSharingManager.refreshLocalAudioRoutes(context)
                                AudioSharingManager.startLanDiscovery(context)
                                showAudioOutputMenu = true
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Cast,
                                    contentDescription = "AirPlay / DLNA / 音频输出",
                                    tint = if (activeCastDevice != null || showAudioOutputMenu) AppleRed else secondaryTextColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            AudioOutputDropdownMenu(
                                expanded = showAudioOutputMenu,
                                onDismissRequest = { showAudioOutputMenu = false },
                                song = song,
                                progressMs = progressMs
                            )
                        }

                        // 5. 参数详情
                        Box {
                            IconButton(onClick = { showAudioSpecsMenu = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = "音频参数详情",
                                    tint = secondaryTextColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            AudioSpecsDropdownMenu(
                                expanded = showAudioSpecsMenu,
                                onDismissRequest = { showAudioSpecsMenu = false },
                                song = song
                            )
                        }

                        // 6. 主题切换 (切回现代模式)
                        IconButton(onClick = { onSwitchPlayerTheme(PlayerThemeStyle.MODERN) }) {
                            Icon(
                                imageVector = Icons.Default.Style,
                                contentDescription = "切回现代模式",
                                tint = secondaryTextColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // 歌词大小与快慢调节浮层
    if (showLyricsAdjustDialog) {
        LyricsAdjustDialog(
            fontSizeSp = fontSizeSp,
            lyricsOffsetMs = lyricsOffsetMs,
            lyricTheme = lyricTheme,
            isKaraokeEnabled = isKaraokeEnabled,
            useSystemFont = useSystemFont,
            onFontSizeChange = onFontSizeChange,
            onOffsetChange = onOffsetChange,
            onThemeChange = onThemeChange,
            onKaraokeChange = { enabled ->
                isKaraokeEnabled = enabled
                globalPrefs.edit().putBoolean("lyric_karaoke_enabled", enabled).apply()
            },
            onFontModeChange = { useSys ->
                useSystemFont = useSys
                globalPrefs.edit().putBoolean("lyric_use_system_font", useSys).apply()
            },
            onDismiss = { showLyricsAdjustDialog = false }
        )
    }

    // 待播队列半屏弹窗 (复用现代模式)
    if (showQueueSheet) {
        ModalBottomSheet(
            onDismissRequest = { showQueueSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "待播队列 (${currentPlaylist.size} 首)",
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                )
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxHeight(0.6f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(currentPlaylist, key = { index, item -> "retro_queue_${item.id}_$index" }) { index, item ->
                        val isCurrent = item.id == song.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isCurrent) AppleRed.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable {
                                    onSelectSongFromQueue(item)
                                    showQueueSheet = false
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${index + 1}", color = if (isCurrent) AppleRed else secondaryTextColor, fontSize = 13.sp, modifier = Modifier.width(28.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, color = if (isCurrent) AppleRed else primaryTextColor, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
                                Text(item.artist, color = secondaryTextColor, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }

    // 系统返回键分层适配：优先关闭内嵌弹窗与菜单，最后关闭播放页
    BackHandler(enabled = true) {
        when {
            showQueueSheet -> showQueueSheet = false
            showSleepTimerMenu -> showSleepTimerMenu = false
            showAudioSpecsMenu -> showAudioSpecsMenu = false
            showAudioOutputMenu -> showAudioOutputMenu = false
            showAddToPlaylistMenu -> showAddToPlaylistMenu = false
            showDownloadMenu -> showDownloadMenu = false
            showLyricsAdjustDialog -> showLyricsAdjustDialog = false
            else -> onDismiss()
        }
    }

}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val remainingSeconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, remainingSeconds)
}

@Composable
private fun RetroKaraokeLyricRow(
    item: LyricLine,
    index: Int,
    cleanLyrics: List<LyricLine>,
    isActive: Boolean,
    isKaraokeEnabled: Boolean,
    effectiveTimeMs: Long,
    lastTickPos: Long,
    lastTickUptime: Long,
    frameTick: Long,
    fontSizeSp: Float,
    lyricFontFamily: androidx.compose.ui.text.font.FontFamily?,
    activeColor: Color,
    inactiveColor: Color,
    karaokeBaseColor: Color,
    karaokeOverlayColor: Color,
    lyricsOffsetMs: Long,
    textAlign: TextAlign,
    alignment: Alignment,
    onSeekTo: (Long) -> Unit
) {
    val currentFontSize = if (isActive) fontSizeSp.coerceIn(16f, 26f).sp else (fontSizeSp * 0.74f).coerceIn(12f, 18f).sp
    val currentLineHeight = (currentFontSize.value * 1.38f).sp
    val fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal

    val textColor by animateColorAsState(
        targetValue = if (isActive) activeColor else inactiveColor,
        animationSpec = tween(durationMillis = 260),
        label = "retro_lyric_color"
    )

    if (isActive && isKaraokeEnabled) {
        val nextItemTime = cleanLyrics.getOrNull(index + 1)?.timestampMs ?: (item.timestampMs + 4000L)
        val lineDuration = (nextItemTime - item.timestampMs).coerceIn(1000L, 12000L)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSeekTo((item.timestampMs - lyricsOffsetMs).coerceAtLeast(0L)) },
            contentAlignment = alignment
        ) {
            Box(modifier = Modifier.wrapContentWidth()) {
                Text(
                    text = item.text,
                    style = TextStyle(
                        fontSize = currentFontSize,
                        fontWeight = fontWeight,
                        fontFamily = lyricFontFamily,
                        color = karaokeBaseColor,
                        lineHeight = currentLineHeight,
                        textAlign = textAlign
                    )
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .drawWithContent {
                            // 建立对高刷帧时钟的绘制阶段依赖，平滑自增且无重组开销
                            val _tick = frameTick
                            val elapsed = (android.os.SystemClock.uptimeMillis() - lastTickUptime).coerceIn(0L, 600L)
                            val smoothPos = lastTickPos + elapsed
                            val lineProgress = ((smoothPos - item.timestampMs).toFloat() / lineDuration.toFloat()).coerceIn(0f, 1f)

                            if (lineProgress > 0f) {
                                clipRect(right = size.width * lineProgress) {
                                    this@drawWithContent.drawContent()
                                }
                            }
                        }
                ) {
                    Text(
                        text = item.text,
                        style = TextStyle(
                            fontSize = currentFontSize,
                            fontWeight = fontWeight,
                            fontFamily = lyricFontFamily,
                            color = karaokeOverlayColor,
                            lineHeight = currentLineHeight,
                            textAlign = textAlign
                        )
                    )
                }
            }
        }
    } else {
        Text(
            text = item.text,
            style = TextStyle(
                fontSize = currentFontSize,
                fontWeight = fontWeight,
                fontFamily = lyricFontFamily,
                color = textColor,
                lineHeight = currentLineHeight,
                textAlign = textAlign
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSeekTo((item.timestampMs - lyricsOffsetMs).coerceAtLeast(0L)) }
        )
    }
}
