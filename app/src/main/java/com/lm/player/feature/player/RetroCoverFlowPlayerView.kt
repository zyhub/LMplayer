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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.pager.PagerSnapDistance
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.lm.player.core.designsystem.component.AddToPlaylistDropdownMenu
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.DownloadQualityDropdownMenu
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LyricTheme
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
import kotlin.math.sin

/**
 * 怀旧专辑流 (iPod Cover Flow) —— 横屏时的全屏播放界面（竖屏统一用现代主题，不再提供主题切换）
 * - 封面模糊氛围底图（切歌交叉淡化）+ 舞台聚光 / 光尘 + 3D 唱片架（smoothstep 缓动翻转、扫光、地面投影、倒影）；
 * - 轻扫可连翻多张，停下柔和吸附；歌名 / 歌手顺着翻页方向滑入；
 * - 播控行 [列表][定时] [上一首][播放][下一首] [播放模式]，细进度条按住加粗；
 * - 右侧歌词 / 待播侧栏可拖动宽度（最大 1/3），点手柄收起 / 展开；下滑关闭。
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
    /** 三态播放模式（列表循环 / 单曲循环 / 随机播放） */
    playMode: com.lm.player.core.media.PlayMode,
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
    /** 点击三态按钮：切换到下一个播放模式 */
    onCyclePlayMode: () -> Unit,
    onAddToPlaylist: (UnifiedPlaylist, UnifiedSong) -> Unit = { _, _ -> },
    onCreatePlaylistAndAddSong: (String, UnifiedSong) -> Unit = { _, _ -> },
    onDownloadSongWithOptions: (UnifiedSong, DownloadTarget, AudioQuality) -> Unit = { _, _, _ -> },
    onSelectTimer: (Int) -> Unit = {},
    onFontSizeChange: (Float) -> Unit = {},
    onOffsetChange: (Long) -> Unit = {},
    onThemeChange: (LyricTheme) -> Unit = {},
    /** 点歌手名：跳转资料库歌手页 */
    onOpenArtist: (String) -> Unit = {},
    /** 点专辑名：跳转资料库专辑页（专辑名、歌手、专辑 ID） */
    onOpenAlbum: (String, String, String) -> Unit = { _, _, _ -> },
    onLyricsUpdated: () -> Unit = {},
    onMetadataSaved: (com.lm.player.feature.library.TagSavedMetadata) -> Unit = {},
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
    // 程序切歌（外部切歌 / 点侧边封面 / 点待播）时的滑动：略带阻尼的柔和弹簧，多张跳转也不生硬
    val currentSpringSpec = remember {
        spring<Float>(dampingRatio = 0.88f, stiffness = 170f)
    }
    // 手势：轻扫可以连翻多张（像真实唱片架），停下时柔和吸附
    val pagerFlingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        pagerSnapDistance = PagerSnapDistance.atMost(8),
        snapAnimationSpec = spring(dampingRatio = 0.86f, stiffness = 190f)
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
    var showTagEditor by remember { mutableStateOf(false) }
    // 在线修标签弹窗（独立窗口）
    if (showTagEditor) {
        com.lm.player.feature.library.TagEditorDialog(
            song = song,
            onDismiss = { showTagEditor = false },
            onLyricsUpdated = onLyricsUpdated,
            onMetadataSaved = onMetadataSaved
        )
    }

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
    val isServerCached = song.isServerLibrarySong /* 与列表徽标同一口径（PR #7 合入时统一） */
    // 音质标签（与现代主题同一套：未展开显示音质，点开显示音频参数）
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

    // 系统状态栏 / 导航栏与怀旧播放页背景渐变的首尾色一致（离开播放页自动恢复应用底色）
    com.lm.player.core.designsystem.theme.SystemBarsColor(
        statusBar = if (isDark) Color(0xFF07080A) else Color(0xFFE2E0E6),
        navigationBar = if (isDark) Color(0xFF040507) else Color(0xFFDAD7DE)
    )
    // 横屏播放页沉浸式：隐藏系统状态栏与导航栏，画面不再被一条纯色状态栏割开（从屏幕边缘滑动可临时呼出）
    com.lm.player.core.designsystem.theme.ImmersiveSystemBars()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, animatedOffsetY.roundToInt().coerceAtLeast(0)) }
            .background(backdropBrush)
            // 「词」歌词调节展开后，点外围任意位置收回（与竖屏一致）
            .playerOutsideTapHost()
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
        // =========================================================================
        // 横屏专用：怀旧专辑流（Cover Flow）。竖屏统一走现代主题，这里不再有竖屏布局。
        // 封面模糊氛围底图 + 舞台聚光 / 光尘 + 3D 唱片架（大封面）+ 歌名 + 液态玻璃小条（列表 / 进度 / 播放顺序）
        // + 右侧歌词（与竖屏同一套 AMLL 歌词与「词」调节）/ 待播侧栏（可拖动宽度）
        // =========================================================================
        val screenWidthPx = with(density) { screenWidth.dp.toPx() }
        val isCompactLandscape = screenHeight < 420
        val isUltraCompactLandscape = screenHeight < 365
        // 侧栏宽度：拖动时跟手，点击展开 / 收起时用弹簧过渡
        val animatedLyricsRatio by animateFloatAsState(
            targetValue = landscapeLyricsRatio.coerceIn(0.0f, 0.333f),
            animationSpec = if (isDraggingSplitter) snap() else spring(dampingRatio = 0.86f, stiffness = 260f),
            label = "retro_side_ratio"
        )
        val playerWeight = (1.0f - animatedLyricsRatio).coerceIn(0.667f, 1.0f)
        val isSidePaneVisible = animatedLyricsRatio > 0.04f
        // 进场：唱片架放大淡入，信息区 / 侧栏随后滑入
        val enterAnim = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            enterAnim.animateTo(1f, tween(durationMillis = 760, easing = FastOutSlowInEasing))
        }
        val chipBg = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.06f)

        // 液态玻璃：背景（封面模糊底图 + 聚光光尘）录成图层，玻璃小条与「词」按钮折射它
        val retroGlassActive = com.lm.player.core.designsystem.glass.rememberLiquidGlassFull()
        val retroCoverLayer = rememberLayerBackdrop()
        val retroGlass: com.kyant.backdrop.Backdrop? = if (retroGlassActive) retroCoverLayer else null

        // 1. 背景：当前封面的模糊大图（切歌交叉淡化）+ 舞台聚光、暗角与缓慢上浮的光尘（暂停时静止）
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(if (retroGlassActive) Modifier.layerBackdrop(retroCoverLayer) else Modifier)
        ) {
            Crossfade(
                targetState = song.coverUrl to song.id,
                animationSpec = tween(durationMillis = 900, easing = LinearOutSlowInEasing),
                modifier = Modifier.fillMaxSize(),
                label = "retro_ambient_cover"
            ) { (cover, seed) ->
                com.lm.player.core.designsystem.glass.CoverBackdrop(
                    coverUrl = cover,
                    seedId = seed,
                    isDark = isDark,
                    baseBottomColor = if (isDark) Color(0xFF040507) else Color(0xFFDAD7DE),
                    modifier = Modifier.fillMaxSize()
                )
            }
            RetroAmbientLayer(
                isPlaying = isPlaying,
                isDark = isDark,
                spotCenterX = playerWeight / 2f,
                modifier = Modifier.fillMaxSize()
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ===================== 左：唱片架 + 歌名 + 玻璃小条 =====================
            Column(
                modifier = Modifier
                    .weight(playerWeight)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ---- 3D 唱片架（占满剩余高度，封面尽量大；左上角叠一个收起按钮） ----
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .graphicsLayer {
                            val p = enterAnim.value
                            alpha = p
                            val s = 0.86f + 0.14f * p
                            scaleX = s
                            scaleY = s
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val reflectionRatio = if (isCompactLandscape) 0.12f else 0.16f
                    val cardSizeDp = ((maxHeight.value - 4f) / (1f + reflectionRatio))
                        .coerceAtMost(maxWidth.value * 0.52f)
                        .coerceIn(96f, 320f)
                        .dp
                    val reflectionHeightDp = cardSizeDp * reflectionRatio
                    val horizontalPaddingDp = ((maxWidth.value - cardSizeDp.value) / 2f).coerceAtLeast(0f).dp
                    val cardSizePx = with(density) { cardSizeDp.toPx() }
                    val floorShadow = if (isDark) Color.Black.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.16f)

                    // 唱片架地面：中心封面脚下的椭圆投影，倒影落在上面像一块亮面地板
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .drawBehind {
                                val cardTop = (size.height - cardSizePx * (1f + reflectionRatio)) / 2f
                                val floorY = cardTop + cardSizePx
                                val r = cardSizePx * 0.95f
                                val cx = size.width / 2f
                                withTransform({ scale(1f, 0.16f, Offset(cx, floorY)) }) {
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            colors = listOf(floorShadow, Color.Transparent),
                                            center = Offset(cx, floorY),
                                            radius = r
                                        ),
                                        radius = r,
                                        center = Offset(cx, floorY)
                                    )
                                }
                            }
                    )

                    HorizontalPager(
                        state = pagerState,
                        contentPadding = PaddingValues(horizontal = horizontalPaddingDp),
                        pageSize = PageSize.Fixed(cardSizeDp),
                        pageSpacing = 0.dp,
                        beyondViewportPageCount = 5,
                        flingBehavior = pagerFlingBehavior,
                        key = { currentPlaylist.getOrNull(it)?.id ?: it.toString() },
                        modifier = Modifier.fillMaxSize()
                    ) { index ->
                        val targetSong = currentPlaylist.getOrNull(index) ?: song
                        val zIndexVal = (1000 - abs(pagerState.currentPage - index)).toFloat()
                        val isCenter = pagerState.currentPage == index
                        val cardInteraction = remember { MutableInteractionSource() }
                        val cardPressed by cardInteraction.collectIsPressedAsState()
                        val pressScale by animateFloatAsState(
                            targetValue = if (cardPressed && isCenter) 0.965f else 1f,
                            animationSpec = spring(dampingRatio = 0.6f, stiffness = 700f),
                            label = "retro_card_press"
                        )

                        Column(
                            modifier = Modifier
                                .width(cardSizeDp)
                                .zIndex(zIndexVal)
                                .graphicsLayer {
                                    val pageOffset = (pagerState.currentPage - index) + pagerState.currentPageOffsetFraction
                                    val absOffset = abs(pageOffset)
                                    val sign = if (pageOffset > 0) 1f else -1f
                                    // 中心一格内用 smoothstep 缓动：离开 / 回到中心时先慢后快再慢，翻转更像真实唱片架
                                    val near = absOffset.coerceAtMost(1f)
                                    val eased = near * near * (3f - 2f * near)
                                    val far = (absOffset - 1f).coerceAtLeast(0f)
                                    // 中心两侧先展开 0.54 张，再往外每张叠 0.21 张
                                    val visualDist = eased * 0.54f + far * 0.21f
                                    val targetVisualX = -sign * visualDist * cardSizePx
                                    val naturalX = -pageOffset * cardSizePx
                                    translationX = targetVisualX - naturalX
                                    rotationY = sign * eased * 60f
                                    val s = (1f - eased * 0.15f - far * 0.018f).coerceIn(0.70f, 1f) * pressScale
                                    scaleX = s
                                    scaleY = s
                                    // 边缘 2.8～4.2 张之间平滑渐隐，不在视窗边缘突变
                                    alpha = when {
                                        absOffset <= 2.8f -> 1f
                                        absOffset <= 4.2f -> ((4.2f - absOffset) / 1.4f).coerceIn(0f, 1f)
                                        else -> 0f
                                    }
                                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                                    cameraDistance = 24f * density.density
                                },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF14161C),
                                shadowElevation = 14.dp,
                                border = BorderStroke(width = 1.dp, color = Color.White.copy(alpha = 0.22f)),
                                modifier = Modifier
                                    .size(cardSizeDp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(interactionSource = cardInteraction, indication = null) {
                                        if (pagerState.currentPage != index) {
                                            coroutineScope.launch {
                                                pagerState.animateScrollToPage(index, animationSpec = currentSpringSpec)
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
                                        targetSize = 512,
                                        modifier = Modifier.fillMaxSize(),
                                        cornerRadius = 12.dp
                                    )
                                    // 侧边封面随距离压暗；转动时一道高光从封面上扫过
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .drawBehind {
                                                val signed = (pagerState.currentPage - index) + pagerState.currentPageOffsetFraction
                                                val off = abs(signed)
                                                val dim = (off.coerceAtMost(1f) * 0.36f + (off - 1f).coerceAtLeast(0f) * 0.10f)
                                                    .coerceAtMost(0.60f)
                                                if (dim > 0.005f) drawRect(Color.Black.copy(alpha = dim))
                                                val shift = signed.coerceIn(-1.5f, 1.5f) * size.width * 0.7f
                                                drawRect(
                                                    brush = Brush.linearGradient(
                                                        colors = listOf(Color.White.copy(alpha = 0.20f), Color.Transparent),
                                                        start = Offset(shift - size.width * 0.1f, 0f),
                                                        end = Offset(shift + size.width * 0.55f, size.height * 0.6f)
                                                    )
                                                )
                                            }
                                    )

                                    // 中心封面：播放 / 暂停浮标（点封面后显示，2 秒后淡出；滑动中隐藏）
                                    if (isCenter && !pagerState.isScrollInProgress) {
                                        androidx.compose.animation.AnimatedVisibility(
                                            visible = showPlayOverlay,
                                            enter = fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.8f),
                                            exit = fadeOut(animationSpec = tween(300)) + scaleOut(targetScale = 0.8f),
                                            modifier = Modifier.align(Alignment.Center)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(if (isCompactLandscape) 48.dp else 58.dp)
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
                                                    modifier = Modifier.size(if (isCompactLandscape) 26.dp else 30.dp)
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

                    // 收起（叠在唱片架左上角，不再单独占一行）
                    RetroCircleButton(
                        icon = Icons.Default.KeyboardArrowDown,
                        description = "最小化",
                        size = if (isCompactLandscape) 30.dp else 34.dp,
                        background = chipBg,
                        tint = primaryTextColor,
                        iconSize = 22.dp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .zIndex(2000f),
                        onClick = onDismiss
                    )
                }

                // ---- 歌名 / 歌手 — 专辑 + 音质标签（进场时从下方滑入） ----
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                        .graphicsLayer {
                            val p = enterAnim.value
                            alpha = p
                            translationY = (1f - p) * 26.dp.toPx()
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 歌名：切歌时顺着翻页方向滑入
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    ) {
                        RetroEqualizerBars(isPlaying = isPlaying, color = AppleRed)
                        Spacer(modifier = Modifier.width(7.dp))
                        AnimatedContent(
                            targetState = song,
                            contentKey = { it.id },
                            transitionSpec = {
                                val from = currentPlaylist.indexOfFirst { it.id == initialState.id }
                                val to = currentPlaylist.indexOfFirst { it.id == targetState.id }
                                retroSwapTransform(if (to >= from) 1 else -1, 0)
                            },
                            modifier = Modifier.weight(1f, fill = false),
                            label = "retro_title"
                        ) { s ->
                            Text(
                                text = s.title,
                                style = TextStyle(
                                    fontSize = if (isUltraCompactLandscape) 15.sp else if (isCompactLandscape) 16.sp else 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryTextColor
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    // 歌手 — 专辑（可点，跳资料库）+ 音质标签（点开音频参数）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp, start = 16.dp, end = 16.dp)
                    ) {
                        AnimatedContent(
                            targetState = song,
                            contentKey = { it.id },
                            transitionSpec = {
                                val from = currentPlaylist.indexOfFirst { it.id == initialState.id }
                                val to = currentPlaylist.indexOfFirst { it.id == targetState.id }
                                retroSwapTransform(if (to >= from) 1 else -1, 50)
                            },
                            modifier = Modifier.weight(1f, fill = false),
                            label = "retro_artist"
                        ) { s ->
                            val metaStyle = TextStyle(
                                fontSize = if (isCompactLandscape) 11.sp else 12.sp,
                                color = secondaryTextColor
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = s.artist.ifBlank { "未知歌手" },
                                    style = metaStyle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(secondaryTextColor.copy(alpha = 0.14f))
                                        .clickable(enabled = s.artist.isNotBlank()) { onOpenArtist(s.artist) }
                                        .padding(horizontal = 7.dp, vertical = 1.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = s.album.ifBlank { "精选集" },
                                    style = metaStyle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(secondaryTextColor.copy(alpha = 0.14f))
                                        .clickable(enabled = s.album.isNotBlank()) { onOpenAlbum(s.album, s.artist, s.albumId) }
                                        .padding(horizontal = 7.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Box {
                            AudioQualityTag(
                                info = audioBadge,
                                glassBackdrop = null,
                                isDark = isDark,
                                opened = showAudioSpecsMenu,
                                compact = true,
                                onClick = { showAudioSpecsMenu = !showAudioSpecsMenu }
                            )
                            AudioSpecsGlassPanel(
                                expanded = showAudioSpecsMenu,
                                onDismissRequest = { showAudioSpecsMenu = false },
                                song = song,
                                info = audioBadge
                            )
                        }
                    }
                }

                // ---- 液态玻璃小条：平时缩成一条进度胶囊；点一下展开 [待播列表] 0:32 ━●━ 3:45 [播放顺序]，3 秒无操作自动收回 ----
                var miniBarExpanded by remember { mutableStateOf(false) }
                var miniBarPoke by remember { mutableIntStateOf(0) }
                LaunchedEffect(miniBarExpanded, miniBarPoke) {
                    if (miniBarExpanded) {
                        delay(3000L)
                        miniBarExpanded = false
                    }
                }
                val miniBarProgress by animateFloatAsState(
                    targetValue = if (miniBarExpanded) 1f else 0f,
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 320f),
                    label = "retro_mini_bar"
                )
                val progressRatio = if (totalDurationMs > 0) (progressMs.toFloat() / totalDurationMs).coerceIn(0f, 1f) else 0f
                // 拖动进度条时的预览位置（松手前不跳转，时间显示拖到的位置）
                var seekPreview by remember { mutableStateOf<Float?>(null) }
                val shownProgressMs = seekPreview?.let { (it * totalDurationMs).toLong() } ?: progressMs
                val miniBarMaxHeight = if (isCompactLandscape) 40.dp else 44.dp
                BoxWithConstraints(
                    modifier = Modifier
                        .padding(top = if (isCompactLandscape) 4.dp else 8.dp, bottom = 2.dp)
                        .widthIn(max = 560.dp)
                        .fillMaxWidth(0.94f)
                        .height(miniBarMaxHeight)
                        .graphicsLayer {
                            val p = enterAnim.value
                            alpha = p
                            translationY = (1f - p) * 32.dp.toPx()
                        }
                        // 收起时整块（含上下留白）都能点开，细胶囊本身太细不好点
                        .then(
                            if (!miniBarExpanded) Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                miniBarExpanded = true
                                miniBarPoke++
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val p = miniBarProgress
                    val collapsedWidth = minOf(maxWidth * 0.62f, 300.dp)
                    val barWidth = androidx.compose.ui.unit.lerp(collapsedWidth, maxWidth, p)
                    val barHeight = androidx.compose.ui.unit.lerp(24.dp, miniBarMaxHeight, p)
                    Box(modifier = Modifier.size(barWidth, barHeight)) {
                        if (retroGlass != null) {
                            com.lm.player.core.designsystem.glass.LiquidGlassLayer(
                                modifier = Modifier.matchParentSize(),
                                cornerRadius = barHeight / 2,
                                tint = if (isDark) Color(0xFF15151C).copy(alpha = 0.10f + 0.25f * com.lm.player.core.designsystem.glass.GlassStyleState.opacityLevel)
                                else Color.White.copy(alpha = 0.18f + 0.35f * com.lm.player.core.designsystem.glass.GlassStyleState.opacityLevel),
                                blurAmount = 0.12f + 0.30f * com.lm.player.core.designsystem.glass.GlassStyleState.blurLevel,
                                dispersion = 0.16f,
                                material = com.lm.player.core.designsystem.glass.GlassMaterial.REGULAR,
                                backdropOverride = retroGlass
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(barHeight / 2))
                                    .background(chipBg)
                            )
                        }
                        // 收起态：胶囊里「已播时间 ━━━ 总时长」
                        if (p < 0.99f) {
                            val miniTimeStyle = TextStyle(fontSize = 9.5.sp, color = secondaryTextColor, fontFeatureSettings = "tnum")
                            Row(
                                modifier = Modifier
                                    .matchParentSize()
                                    .graphicsLayer { alpha = 1f - p }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = formatDuration(shownProgressMs), style = miniTimeStyle, maxLines = 1)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .padding(horizontal = 8.dp)
                                        .drawBehind {
                                            val th = 3.dp.toPx()
                                            val y = size.height / 2f
                                            if (size.width > th) {
                                                drawLine(primaryTextColor.copy(alpha = 0.25f), Offset(0f, y), Offset(size.width, y), strokeWidth = th, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                                drawLine(primaryTextColor, Offset(0f, y), Offset(size.width * progressRatio, y), strokeWidth = th, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                            }
                                        }
                                )
                                Text(text = formatDuration(totalDurationMs), style = miniTimeStyle, maxLines = 1)
                            }
                        }
                        // 展开态：待播 / 时间 / 液态玻璃进度条 / 播放顺序
                        if (p > 0.01f) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = p }
                                    .padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RetroCircleButton(
                                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                                    description = "待播列表",
                                    size = 32.dp,
                                    background = if (showQueueSheet) AppleRed.copy(alpha = 0.18f) else Color.Transparent,
                                    tint = if (showQueueSheet) AppleRed else primaryTextColor,
                                    iconSize = 19.dp,
                                    onClick = {
                                        miniBarPoke++
                                        showQueueSheet = !showQueueSheet
                                        if (showQueueSheet && landscapeLyricsRatio < 0.05f) landscapeLyricsRatio = 0.333f
                                    }
                                )
                                val timeStyle = TextStyle(fontSize = 10.5.sp, color = secondaryTextColor, fontFeatureSettings = "tnum")
                                Text(
                                    text = formatDuration(shownProgressMs),
                                    style = timeStyle,
                                    textAlign = TextAlign.End,
                                    maxLines = 1,
                                    modifier = Modifier.width(38.dp)
                                )
                                com.lm.player.core.designsystem.component.LiquidSlider(
                                    value = progressRatio,
                                    onValueChange = { ratio ->
                                        miniBarPoke++
                                        onSeekTo((ratio * totalDurationMs).toLong())
                                    },
                                    commitOnRelease = true,
                                    onPreview = { p ->
                                        seekPreview = p
                                        miniBarPoke++
                                    },
                                    accentColor = primaryTextColor,
                                    trackColor = primaryTextColor.copy(alpha = 0.2f),
                                    backdrop = retroGlass,
                                    surfaceColor = if (isDark) Color(0xFF0D0F14) else Color(0xFFEFEFF3),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(28.dp)
                                        .padding(horizontal = 16.dp)
                                )
                                Text(
                                    text = formatDuration(totalDurationMs),
                                    style = timeStyle,
                                    maxLines = 1,
                                    modifier = Modifier.width(38.dp)
                                )
                                // 播放顺序（列表循环 / 单曲循环 / 随机）
                                com.lm.player.feature.player.controls.PlayModeIconButton(
                                    mode = playMode,
                                    onClick = {
                                        miniBarPoke++
                                        onCyclePlayMode()
                                    },
                                    tint = primaryTextColor,
                                    buttonSize = 32.dp,
                                    iconSize = 19.dp
                                )
                            }
                        }
                    }
                }
            }

            // ===================== 中：拖动手柄（点一下收起 / 展开侧栏） =====================
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(18.dp)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { isDraggingSplitter = true },
                            onDragEnd = {
                                isDraggingSplitter = false
                                // 拖得很窄时直接收起
                                if (landscapeLyricsRatio < 0.08f) landscapeLyricsRatio = 0f
                                lyricsPrefs.edit().putFloat("landscape_lyrics_width_ratio", landscapeLyricsRatio).apply()
                            },
                            onDragCancel = { isDraggingSplitter = false },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                // 向左拖加宽侧栏，向右拖收窄
                                val deltaRatio = -dragAmount / screenWidthPx
                                landscapeLyricsRatio = (landscapeLyricsRatio + deltaRatio).coerceIn(0.0f, 0.333f)
                            }
                        )
                    }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        val newRatio = if (landscapeLyricsRatio > 0.05f) 0.0f else 0.333f
                        landscapeLyricsRatio = newRatio
                        lyricsPrefs.edit().putFloat("landscape_lyrics_width_ratio", newRatio).apply()
                    },
                contentAlignment = Alignment.Center
            ) {
                val handleHeight by animateDpAsState(
                    targetValue = if (isDraggingSplitter) 52.dp else 34.dp,
                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
                    label = "retro_handle_height"
                )
                Box(
                    modifier = Modifier
                        .width(if (isDraggingSplitter) 5.dp else 4.dp)
                        .height(handleHeight)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            if (isDraggingSplitter) AppleRed
                            else if (isDark) Color.White.copy(alpha = 0.45f)
                            else Color.Black.copy(alpha = 0.30f)
                        )
                )
            }

            // ===================== 右：歌词（竖屏同款 AMLL 歌词 +「词」调节）/ 待播侧栏 =====================
            if (isSidePaneVisible) {
                Box(
                    modifier = Modifier
                        .weight(animatedLyricsRatio)
                        .fillMaxHeight()
                        .padding(vertical = 4.dp)
                        .graphicsLayer {
                            val p = enterAnim.value
                            alpha = p
                            translationX = (1f - p) * 40.dp.toPx()
                        }
                ) {
                    AnimatedContent(
                        targetState = showQueueSheet,
                        transitionSpec = {
                            val dir = if (targetState) 1 else -1
                            (slideInVertically(tween(420, easing = FastOutSlowInEasing)) { h -> dir * h / 8 } +
                                fadeIn(tween(320, delayMillis = 60)))
                                .togetherWith(
                                    slideOutVertically(tween(300, easing = FastOutSlowInEasing)) { h -> -dir * h / 8 } +
                                        fadeOut(tween(200))
                                )
                        },
                        modifier = Modifier.fillMaxSize(),
                        label = "retro_side_pane"
                    ) { queueMode ->
                        if (queueMode) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(if (isDark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.45f))
                                    .border(
                                        0.6.dp,
                                        if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f),
                                        RoundedCornerShape(22.dp)
                                    )
                            ) {
                                RetroQueuePane(
                                    playlist = currentPlaylist,
                                    currentSong = song,
                                    isPlaying = isPlaying,
                                    primaryTextColor = primaryTextColor,
                                    secondaryTextColor = secondaryTextColor,
                                    onSelect = onSelectSongFromQueue,
                                    onClose = { showQueueSheet = false }
                                )
                            }
                        } else {
                            // 与竖屏歌词页同一个组件：同样的歌词样式、景深模糊，右上角「词」按钮调字号 / 快慢 / 主题与开关，设置互通
                            LyricsScrollingView(
                                lyrics = lyrics.lines,
                                currentPositionMs = progressMs,
                                onSeekToLyric = onSeekTo,
                                fontSizeSp = fontSizeSp,
                                lyricsOffsetMs = lyricsOffsetMs,
                                lyricTheme = lyricTheme,
                                onFontSizeChange = onFontSizeChange,
                                onOffsetChange = onOffsetChange,
                                onThemeChange = onThemeChange,
                                showAdjustButton = true,
                                modifier = Modifier.fillMaxSize(),
                                glassBackdrop = retroGlass
                            )
                        }
                    }
                }
            } else {
                // 侧栏收起时，右侧留一个展开小胶囊
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = chipBg,
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

    // 系统返回键分层适配：优先关闭内嵌弹窗与菜单，最后关闭播放页
    BackHandler(enabled = true) {
        when {
            showQueueSheet -> showQueueSheet = false
            showTagEditor -> showTagEditor = false
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
    /** 与当前行相隔几行：越远越淡（景深） */
    distance: Int = 0,
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
    // 当前行字号放大用缓动过渡，不是一下跳大
    val targetFontSize = if (isActive) fontSizeSp.coerceIn(16f, 26f) else (fontSizeSp * 0.74f).coerceIn(12f, 18f)
    val animatedFontSize by animateFloatAsState(
        targetValue = targetFontSize,
        animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
        label = "retro_lyric_size"
    )
    val currentFontSize = animatedFontSize.sp
    // 离当前行越远越淡，像景深一样把视线聚到当前行
    val depthAlpha by animateFloatAsState(
        targetValue = if (isActive) 1f else (1f - distance.coerceAtMost(6) * 0.11f).coerceAtLeast(0.34f),
        animationSpec = tween(durationMillis = 420),
        label = "retro_lyric_depth"
    )
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
                .graphicsLayer { alpha = depthAlpha }
                .clickable { onSeekTo((item.timestampMs - lyricsOffsetMs).coerceAtLeast(0L)) }
        )
    }
}

// =============================================================================
// 怀旧专辑流（横屏）的小部件
// =============================================================================

/** 切歌时歌名 / 歌手顺着翻页方向滑入（direction：1 = 下一首，-1 = 上一首） */
private fun retroSwapTransform(direction: Int, delayMs: Int): ContentTransform =
    ContentTransform(
        targetContentEnter = slideInHorizontally(tween(durationMillis = 440, delayMillis = delayMs, easing = FastOutSlowInEasing)) { w -> direction * w / 5 } +
            fadeIn(tween(durationMillis = 360, delayMillis = delayMs + 60)),
        initialContentExit = slideOutHorizontally(tween(durationMillis = 300, easing = FastOutSlowInEasing)) { w -> -direction * w / 5 } +
            fadeOut(tween(durationMillis = 200)),
        sizeTransform = SizeTransform(clip = false)
    )

/**
 * 背景氛围层：唱片架背后的聚光（播放时轻微呼吸）、四周暗角、缓慢上浮的光尘。
 * 时间只在播放时推进，暂停时画面静止；全部在绘制阶段读取，不触发重组。
 */
@Composable
private fun RetroAmbientLayer(
    isPlaying: Boolean,
    isDark: Boolean,
    spotCenterX: Float,
    modifier: Modifier = Modifier
) {
    // 每颗光尘：x、y、半径(dp)、上浮速度(屏高/秒)、摆动相位、亮度
    val particles = remember {
        val rnd = java.util.Random(20261110L)
        List(18) {
            floatArrayOf(
                rnd.nextFloat(),
                rnd.nextFloat(),
                0.8f + rnd.nextFloat() * 1.8f,
                0.012f + rnd.nextFloat() * 0.022f,
                rnd.nextFloat() * 6.2832f,
                0.3f + rnd.nextFloat() * 0.7f
            )
        }
    }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        // 约 30 帧且与其它动效同帧更新：光尘 / 聚光移动很慢，30 帧足够顺滑；玻璃小条采样这一层，随之从每帧重绘降到每秒 30 次
        com.lm.player.core.designsystem.component.runThrottledFrames { dt -> time += dt / 1_000_000_000f }
    }
    val spotColor = if (isDark) Color.White.copy(alpha = 0.11f) else Color.White.copy(alpha = 0.50f)
    val vignette = if (isDark) Color.Black.copy(alpha = 0.50f) else Color.Black.copy(alpha = 0.08f)
    val dustAlpha = if (isDark) 0.20f else 0.65f
    Canvas(modifier = modifier) {
        if (size.minDimension <= 0f) return@Canvas
        val t = time
        val breathe = 1f + 0.05f * sin(t * 1.3f)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(spotColor, Color.Transparent),
                center = Offset(size.width * spotCenterX, size.height * 0.42f),
                radius = size.height * 0.78f * breathe
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, vignette),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = size.maxDimension * 0.62f
            )
        )
        for (p in particles) {
            val y = (((p[1] - t * p[3]) % 1f) + 1f) % 1f
            val x = p[0] + sin(t * 0.35f + p[4]) * 0.018f
            // 靠近上下边缘时淡出
            val edge = (minOf(y, 1f - y) / 0.18f).coerceIn(0f, 1f)
            drawCircle(
                color = Color.White.copy(alpha = dustAlpha * p[5] * edge),
                radius = p[2] * 1.6f * density,
                center = Offset(x * size.width, y * size.height)
            )
        }
    }
}

private val RETRO_EQ_IDLE = floatArrayOf(0.35f, 0.6f, 0.25f)

/** 「正在播放」三根跳动小竖条；暂停时停成静止的高低条 */
@Composable
private fun RetroEqualizerBars(isPlaying: Boolean, color: Color, modifier: Modifier = Modifier) {
    if (isPlaying) {
        val transition = rememberInfiniteTransition(label = "retro_eq")
        val a by transition.animateFloat(
            initialValue = 0.25f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(460, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "retro_eq_a"
        )
        val b by transition.animateFloat(
            initialValue = 0.95f, targetValue = 0.30f,
            animationSpec = infiniteRepeatable(tween(580, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "retro_eq_b"
        )
        val c by transition.animateFloat(
            initialValue = 0.40f, targetValue = 0.90f,
            animationSpec = infiniteRepeatable(tween(380, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "retro_eq_c"
        )
        RetroBarsCanvas(color, modifier) { floatArrayOf(a, b, c) }
    } else {
        RetroBarsCanvas(color, modifier) { RETRO_EQ_IDLE }
    }
}

@Composable
private fun RetroBarsCanvas(color: Color, modifier: Modifier, levels: () -> FloatArray) {
    Canvas(modifier = modifier.size(width = 13.dp, height = 12.dp)) {
        val lv = levels()
        val barW = size.width / 5f
        for (i in 0 until 3) {
            val h = size.height * lv[i].coerceIn(0.15f, 1f)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * 2f * barW, size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
        }
    }
}

/** 圆形按键：按下缩一下再弹回 */
@Composable
private fun RetroCircleButton(
    icon: ImageVector,
    description: String,
    size: Dp,
    background: Color,
    tint: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 20.dp,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 600f),
        label = "retro_btn_press"
    )
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** 侧栏里的待播列表：带小封面，当前曲目高亮并显示跳动竖条，打开时定位到当前曲目 */
@Composable
private fun RetroQueuePane(
    playlist: List<UnifiedSong>,
    currentSong: UnifiedSong,
    isPlaying: Boolean,
    primaryTextColor: Color,
    secondaryTextColor: Color,
    onSelect: (UnifiedSong) -> Unit,
    onClose: () -> Unit
) {
    val currentIdx = remember(playlist, currentSong.id) {
        playlist.indexOfFirst { it.id == currentSong.id }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIdx - 2).coerceAtLeast(0))
    LaunchedEffect(currentIdx) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isNotEmpty() && visible.none { it.index == currentIdx }) {
            listState.animateScrollToItem((currentIdx - 2).coerceAtLeast(0))
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("待播列表", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = primaryTextColor)
            Spacer(modifier = Modifier.width(6.dp))
            Text("${playlist.size} 首", fontSize = 11.sp, color = secondaryTextColor, modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, contentDescription = "返回歌词", tint = secondaryTextColor, modifier = Modifier.size(16.dp))
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(playlist, key = { i, item -> "retro_q_${item.id}_$i" }) { i, item ->
                val isCurrent = item.id == currentSong.id
                val rowBg by animateColorAsState(
                    targetValue = if (isCurrent) AppleRed.copy(alpha = 0.16f) else Color.Transparent,
                    animationSpec = tween(300),
                    label = "retro_queue_row_bg"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(rowBg)
                        .clickable { onSelect(item) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(38.dp)) {
                        AlbumArtworkImage(
                            model = item.coverUrl,
                            seedId = item.id,
                            targetSize = 96,
                            modifier = Modifier.fillMaxSize(),
                            cornerRadius = 6.dp
                        )
                        if (isCurrent) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black.copy(alpha = 0.40f)),
                                contentAlignment = Alignment.Center
                            ) {
                                RetroEqualizerBars(isPlaying = isPlaying, color = Color.White)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            color = if (isCurrent) AppleRed else primaryTextColor,
                            fontSize = 13.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = item.artist,
                            color = secondaryTextColor,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "${i + 1}",
                        fontSize = 10.sp,
                        color = secondaryTextColor.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
    }
}
