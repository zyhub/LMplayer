package com.lm.player.feature.player

import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import com.lm.player.core.media.ShareProtocolType
import com.lm.player.core.model.*
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
    isShuffle: Boolean,
    isRepeat: Boolean,
    playbackSpeed: Float = 1.0f,
    allPlaylists: List<UnifiedPlaylist> = emptyList(),
    activeDownloadTasks: List<DownloadTask> = emptyList(),
    isServerConnected: Boolean = true,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSelectSongFromQueue: (UnifiedSong) -> Unit = {},
    onToggleLyricsMode: (Boolean) -> Unit = {},
    onToggleFavorite: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onChangePlaybackSpeed: (Float) -> Unit = {},
    onDownloadSong: (UnifiedSong) -> Unit = {},
    onDownloadSongWithOptions: (UnifiedSong, DownloadTarget, AudioQuality) -> Unit = { s, _, _ -> onDownloadSong(s) },
    onAddToPlaylist: (UnifiedPlaylist, UnifiedSong) -> Unit = { _, _ -> },
    onCreatePlaylistAndAddSong: (String, UnifiedSong) -> Unit = { _, _ -> },
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
    LaunchedEffect(song.id) {
        val targetDevice = activeCastDevice
        if (targetDevice != null) {
            AudioSharingManager.castSongToDevice(context, targetDevice, song, 0L)
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
    var activeTimerMinutes by remember { mutableStateOf(0) }
    var localIsFavorite by remember(song.id, song.isFavorite) { mutableStateOf(song.isFavorite) }
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
    var playerThemeStyle by remember {
        mutableStateOf(
            com.lm.player.core.designsystem.theme.PlayerThemeStyle.fromId(
                lyricsPrefs.getString("player_theme_style", com.lm.player.core.designsystem.theme.PlayerThemeStyle.MODERN.id)
                    ?: com.lm.player.core.designsystem.theme.PlayerThemeStyle.MODERN.id
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
    val updatePlayerThemeStyle: (com.lm.player.core.designsystem.theme.PlayerThemeStyle) -> Unit = { newStyle ->
        playerThemeStyle = newStyle
        lyricsPrefs.edit().putString("player_theme_style", newStyle.id).apply()
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
    val (displayQualityBadge, displayBitrateBadge, displaySizeBadge) = remember(
        song.id,
        song.localFilePath,
        song.format,
        song.bitRate,
        song.durationMs,
        song.downloadStatus,
        totalDurationMs,
        streamQualityVer
    ) {
        val hasLocalFile = !song.localFilePath.isNullOrBlank() &&
            (song.localFilePath.startsWith("content://") || runCatching {
                java.io.File(song.localFilePath).let { it.exists() && it.length() > 0L }
            }.getOrDefault(false))
        if (hasLocalFile) {
            val (realExt, realKbps, realSizeStr) = com.lm.player.feature.home.resolveRealLocalFormatAndSize(song)
            val isLossless = realExt in listOf("FLAC", "WAV", "ALAC", "APE", "DSD", "DSF") || realKbps >= 800
            val qLabel = when {
                isLossless && realKbps >= 1200 -> "Hi-Res $realExt"
                isLossless -> "$realExt 无损"
                realKbps >= 320 -> "$realExt 极高"
                else -> "$realExt 标准"
            }
            Triple(qLabel, "$realKbps kbps", realSizeStr)
        } else {
            val preferredQuality = AudioQuality.fromKey(
                com.lm.player.core.network.LemonMusicProtocol.getPreferredStreamQuality(context)
            )
            val qLabel = when (preferredQuality) {
                AudioQuality.Q_HIRES -> "Hi-Res FLAC"
                AudioQuality.Q_FLAC -> "FLAC 无损"
                AudioQuality.Q_320K -> "MP3 极高"
                AudioQuality.Q_128K -> "MP3 标准"
            }
            val kbps = when (preferredQuality) {
                AudioQuality.Q_HIRES -> if (song.bitRate > 1200) song.bitRate else 1640
                AudioQuality.Q_FLAC -> if (song.bitRate in 700..1200) song.bitRate else 960
                AudioQuality.Q_320K -> 320
                AudioQuality.Q_128K -> 128
            }
            val durSec = ((if (totalDurationMs > 0) totalDurationMs else song.durationMs) / 1000L).coerceAtLeast(180L)
            val estMb = (durSec * kbps * 1000L / 8L) / (1024.0 * 1024.0)
            val sizeStr = String.format(Locale.US, "%.1f MB", estMb)
            Triple(qLabel, "$kbps kbps", sizeStr)
        }
    }
    val bitrateBadgeColor = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)
    val sizeBadgeColor = if (isDark) Color(0xFF34D399) else Color(0xFF059669)

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
    val circleButtonBg = if (isDark) Color(0xFF25252A) else Color(0xFFF2F2F7)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, animatedOffsetY.roundToInt().coerceAtLeast(0)) }
            .background(playerBackdrop)
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
            // 横屏自适应布局：支持左右拖动调节比例 (左侧控制器 66.7%~100% + 中间手柄 + 右侧歌词/待播 0%~33.3%)
            // 针对手机横屏短垂直高度 (270dp~360dp) 动态分配各区块尺寸，确保底部播控与工具栏 100% 完整显示绝不裁切
            // =========================================================================
            val screenWidthPx = with(LocalDensity.current) { configuration.screenWidthDp.dp.toPx() }
            val effectiveLyricsRatio = landscapeLyricsRatio.coerceIn(0.0f, 0.333f)
            val playerWeight = (1.0f - effectiveLyricsRatio).coerceIn(0.667f, 1.0f)
            val isLyricsVisible = effectiveLyricsRatio > 0.04f

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧控制器面板 (自适应占比 66.7% ~ 100%)
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(playerWeight)
                        .fillMaxHeight()
                ) {
                    val availH = maxHeight
                    val isCompactLandscape = availH < 370.dp
                    val headerBtnSize = (availH * 0.10f).coerceIn(28.dp, 36.dp)
                    val headerIconSize = (headerBtnSize * 0.54f).coerceIn(15.dp, 20.dp)
                    val playBtnSize = (availH * 0.145f).coerceIn(38.dp, 52.dp)
                    val playIconSize = (playBtnSize * 0.54f).coerceIn(20.dp, 28.dp)
                    val skipBtnSize = (availH * 0.115f).coerceIn(32.dp, 42.dp)
                    val skipIconSize = (skipBtnSize * 0.72f).coerceIn(22.dp, 30.dp)
                    val modeBtnSize = (availH * 0.10f).coerceIn(28.dp, 38.dp)
                    val modeIconSize = (modeBtnSize * 0.58f).coerceIn(16.dp, 20.dp)
                    val toolbarBtnSize = (availH * 0.10f).coerceIn(28.dp, 38.dp)
                    val toolbarIconSize = (toolbarBtnSize * 0.58f).coerceIn(16.dp, 20.dp)

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // 1. 顶部 Header (确保左右各司其职，绝不重叠)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(150f),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(headerBtnSize)
                                    .clip(CircleShape)
                                    .background(circleButtonBg)
                                    .clickable(onClick = onDismiss),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = "最小化",
                                    tint = primaryTextColor,
                                    modifier = Modifier.size(headerIconSize)
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = circleButtonBg
                            ) {
                                Text(
                                    text = if (song.localFilePath != null) "本地高保真音频" else "在线高保真流媒体",
                                    fontSize = if (isCompactLandscape) 10.sp else 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryTextColor,
                                    modifier = Modifier.padding(
                                        horizontal = 8.dp,
                                        vertical = if (isCompactLandscape) 2.dp else 3.dp
                                    )
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(if (isCompactLandscape) 8.dp else 10.dp)
                            ) {
                                // 加入歌单 (音频共享式展出)
                                Box {
                                    Box(
                                        modifier = Modifier
                                            .size(headerBtnSize)
                                            .clip(CircleShape)
                                            .background(circleButtonBg)
                                            .clickable(onClick = { showLandscapeAddToPlaylistMenu = true }),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.PlaylistAdd,
                                            contentDescription = "加入歌单",
                                            tint = primaryTextColor,
                                            modifier = Modifier.size(headerIconSize)
                                        )
                                    }
                                    AddToPlaylistDropdownMenu(
                                        expanded = showLandscapeAddToPlaylistMenu,
                                        onDismissRequest = { showLandscapeAddToPlaylistMenu = false },
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

                                // 缓存与下载
                                val hasPhysicalLocal = !song.localFilePath.isNullOrBlank() && 
                                    (song.localFilePath!!.startsWith("content://") || runCatching { java.io.File(song.localFilePath!!).let { it.exists() && it.length() > 0L } }.getOrDefault(false))
                                Box {
                                    Box(
                                        modifier = Modifier
                                            .size(headerBtnSize)
                                            .clip(CircleShape)
                                            .background(circleButtonBg)
                                            .clickable(onClick = { showLandscapeDownloadMenu = true }),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val isDownloaded = hasPhysicalLocal && (song.downloadStatus == DownloadStatus.DOWNLOADED || !song.localFilePath.isNullOrBlank())
                                        val isServerCached = song.serverId.isNotBlank() && song.serverId != "local_storage" && song.serverId != "lemon_online"
                                        if (isDownloaded && isServerCached) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = "双端已同步", tint = Color(0xFF34C759), modifier = Modifier.size(headerIconSize))
                                        } else if (isDownloaded || isServerCached) {
                                            Icon(Icons.Default.CheckCircleOutline, contentDescription = "单端已缓存", tint = Color(0xFF34C759), modifier = Modifier.size(headerIconSize))
                                        } else {
                                            Icon(Icons.Default.FileDownload, contentDescription = "下载", tint = primaryTextColor, modifier = Modifier.size(headerIconSize))
                                        }
                                    }
                                    DownloadQualityDropdownMenu(
                                        expanded = showLandscapeDownloadMenu,
                                        onDismissRequest = { showLandscapeDownloadMenu = false },
                                        song = song,
                                        isServerConnected = isServerConnected,
                                        hasLocal = hasPhysicalLocal,
                                        hasServer = song.serverId.isNotBlank() && song.serverId != "local_storage" && song.serverId != "lemon_online",
                                        onConfirm = { target, quality ->
                                            showLandscapeDownloadMenu = false
                                            onDownloadSongWithOptions(song, target, quality)
                                        }
                                    )
                                }
                            }
                        }

                        // 2. 自适应专辑封面展示 + 歌名歌手 (使用 weight(1f) 动态吸收剩余高度，绝不挤压底部控件)
                        BoxWithConstraints(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            val dynamicCoverSize = minOf(maxHeight - 4.dp, maxWidth * 0.44f).coerceIn(76.dp, 220.dp)
                            val coverCorner = (dynamicCoverSize * 0.08f).coerceIn(10.dp, 18.dp)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(if (isCompactLandscape) 12.dp else 16.dp)
                            ) {
                                AlbumArtworkImage(
                                    model = song.coverUrl,
                                    seedId = song.id,
                                    targetSize = 480,
                                    modifier = Modifier
                                        .size(dynamicCoverSize)
                                        .clip(RoundedCornerShape(coverCorner))
                                        .shadow(12.dp, RoundedCornerShape(coverCorner)),
                                    cornerRadius = coverCorner
                                )

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = song.title,
                                        style = TextStyle(
                                            fontSize = if (isCompactLandscape) 17.sp else 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = primaryTextColor
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(if (isCompactLandscape) 2.dp else 4.dp))
                                    Text(
                                        text = song.artist,
                                        style = TextStyle(
                                            fontSize = if (isCompactLandscape) 13.sp else 14.sp,
                                            color = secondaryTextColor
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = song.album.ifBlank { "单曲精选" },
                                        style = TextStyle(
                                            fontSize = if (isCompactLandscape) 11.sp else 12.sp,
                                            color = secondaryTextColor.copy(alpha = 0.8f)
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(if (isCompactLandscape) 4.dp else 6.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = AppleRed.copy(alpha = 0.16f),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { showAudioSpecsPanel = true }
                                        ) {
                                            Text(
                                                text = displayQualityBadge,
                                                fontSize = if (isCompactLandscape) 9.5.sp else 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = AppleRed,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = bitrateBadgeColor.copy(alpha = 0.16f),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { showAudioSpecsPanel = true }
                                        ) {
                                            Text(
                                                text = displayBitrateBadge,
                                                fontSize = if (isCompactLandscape) 9.5.sp else 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = bitrateBadgeColor,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = sizeBadgeColor.copy(alpha = 0.16f),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { showAudioSpecsPanel = true }
                                        ) {
                                            Text(
                                                text = displaySizeBadge,
                                                fontSize = if (isCompactLandscape) 9.5.sp else 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = sizeBadgeColor,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 3. 进度条与时间
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Slider(
                                value = if (totalDurationMs > 0) (progressMs.toFloat() / totalDurationMs).coerceIn(0f, 1f) else 0f,
                                onValueChange = { ratio -> onSeekTo((ratio * totalDurationMs).toLong()) },
                                colors = SliderDefaults.colors(
                                    thumbColor = primaryTextColor,
                                    activeTrackColor = primaryTextColor,
                                    inactiveTrackColor = primaryTextColor.copy(alpha = 0.2f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(if (isCompactLandscape) 14.dp else 18.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = formatDuration(progressMs), fontSize = if (isCompactLandscape) 10.sp else 11.sp, color = secondaryTextColor)
                                Text(text = formatDuration(totalDurationMs), fontSize = if (isCompactLandscape) 10.sp else 11.sp, color = secondaryTextColor)
                            }
                        }

                        // 4. 5 大主控播放按键 (采用自适应紧凑触控盒，避免默认 48dp IconButton 撑破小高度屏幕)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(modeBtnSize)
                                    .clip(CircleShape)
                                    .clickable(onClick = onToggleShuffle),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Shuffle, contentDescription = "随机", tint = if (isShuffle) AppleRed else secondaryTextColor, modifier = Modifier.size(modeIconSize))
                            }
                            Box(
                                modifier = Modifier
                                    .size(skipBtnSize)
                                    .clip(CircleShape)
                                    .clickable(onClick = onPrevious),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.SkipPrevious, contentDescription = "上一首", tint = primaryTextColor, modifier = Modifier.size(skipIconSize))
                            }
                            Box(
                                modifier = Modifier
                                    .size(playBtnSize)
                                    .clip(CircleShape)
                                    .background(primaryTextColor)
                                    .clickable(onClick = onTogglePlayPause),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = "播放/暂停",
                                    tint = if (isDark) Color.Black else Color.White,
                                    modifier = Modifier.size(playIconSize)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(skipBtnSize)
                                    .clip(CircleShape)
                                    .clickable(onClick = onNext),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.SkipNext, contentDescription = "下一首", tint = primaryTextColor, modifier = Modifier.size(skipIconSize))
                            }
                            Box(
                                modifier = Modifier
                                    .size(modeBtnSize)
                                    .clip(CircleShape)
                                    .clickable(onClick = onToggleRepeat),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Repeat, contentDescription = "循环", tint = if (isRepeat) AppleRed else secondaryTextColor, modifier = Modifier.size(modeIconSize))
                            }
                        }

                        // 5. 底部功能工具栏 (自适应紧凑高度，完整露出)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp)),
                            color = surfaceGlassColor
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = if (isCompactLandscape) 2.dp else 4.dp),
                                horizontalArrangement = Arrangement.SpaceAround,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(toolbarBtnSize)
                                        .clip(CircleShape)
                                        .clickable {
                                            localIsFavorite = !localIsFavorite
                                            onToggleFavorite()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (localIsFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                        contentDescription = "红心喜欢",
                                        tint = if (localIsFavorite) AppleRed else secondaryTextColor,
                                        modifier = Modifier.size(toolbarIconSize)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(toolbarBtnSize)
                                        .clip(CircleShape)
                                        .clickable {
                                            if (!isLyricsVisible) {
                                                landscapeLyricsRatio = 0.333f
                                                lyricsPrefs.edit().putFloat("landscape_lyrics_width_ratio", 0.333f).apply()
                                                landscapeRightPaneMode = 1
                                            } else {
                                                landscapeRightPaneMode = if (landscapeRightPaneMode == 1) 0 else 1
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                        contentDescription = "待播列表",
                                        tint = if (isLyricsVisible && landscapeRightPaneMode == 1) AppleRed else secondaryTextColor,
                                        modifier = Modifier.size(toolbarIconSize)
                                    )
                                }
                                Box {
                                    Box(
                                        modifier = Modifier
                                            .size(toolbarBtnSize)
                                            .clip(CircleShape)
                                            .clickable { showSleepTimerPanel = true },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Timer,
                                            contentDescription = "定时关闭",
                                            tint = if (activeTimerMinutes > 0) AppleRed else secondaryTextColor,
                                            modifier = Modifier.size(toolbarIconSize)
                                        )
                                    }
                                    SleepTimerDropdownMenu(
                                        expanded = showSleepTimerPanel,
                                        onDismissRequest = { showSleepTimerPanel = false },
                                        activeTimerMinutes = activeTimerMinutes,
                                        onSelectTimer = { min ->
                                            activeTimerMinutes = min
                                            if (min > 0) {
                                                Toast.makeText(context, "已设置：${min}分钟后停止播放", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "已关闭定时器", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                }

                                Box {
                                    Box(
                                        modifier = Modifier
                                            .size(toolbarBtnSize)
                                            .clip(CircleShape)
                                            .clickable {
                                                AudioSharingManager.refreshLocalAudioRoutes(context)
                                                AudioSharingManager.startLanDiscovery(context)
                                                showAudioOutputPanel = true
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Cast,
                                            contentDescription = "AirPlay / DLNA / 音频输出",
                                            tint = if (activeCastDevice != null || showAudioOutputPanel) AppleRed else secondaryTextColor,
                                            modifier = Modifier.size(toolbarIconSize)
                                        )
                                    }
                                    AudioOutputDropdownMenu(
                                        expanded = showAudioOutputPanel,
                                        onDismissRequest = { showAudioOutputPanel = false },
                                        song = song,
                                        progressMs = progressMs
                                    )
                                }

                                Box {
                                    Box(
                                        modifier = Modifier
                                            .size(toolbarBtnSize)
                                            .clip(CircleShape)
                                            .clickable { showAudioSpecsPanel = true },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Outlined.Info,
                                            contentDescription = "参数详情",
                                            tint = secondaryTextColor,
                                            modifier = Modifier.size(toolbarIconSize)
                                        )
                                    }
                                    AudioSpecsDropdownMenu(
                                        expanded = showAudioSpecsPanel,
                                        onDismissRequest = { showAudioSpecsPanel = false },
                                        song = song
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

                // 右侧共用视窗：[ 实时歌词 ⇄ 待播列表 ] (自适应占比 0% ~ 33.3%)
                if (isLyricsVisible) {
                    Surface(
                        modifier = Modifier
                            .weight(effectiveLyricsRatio)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(20.dp)),
                        color = surfaceGlassColor
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp)
                        ) {
                            // 右侧视窗 Header 与右上角切换胶囊
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (landscapeRightPaneMode == 0) "实时歌词" else "待播 (${playlist.size})",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryTextColor
                                )

                                // 右上角一键切换胶囊按键 [ 歌词 | 列表 ]
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isDark) Color(0xFF1E1E22) else Color(0xFFE2E2E8),
                                    modifier = Modifier
                                        .height(28.dp)
                                        .width(108.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (landscapeRightPaneMode == 0) (if (isDark) Color(0xFF35353C) else Color.White) else Color.Transparent)
                                                .clickable { landscapeRightPaneMode = 0 },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "歌词",
                                                fontSize = 11.sp,
                                                fontWeight = if (landscapeRightPaneMode == 0) FontWeight.Bold else FontWeight.Normal,
                                                color = if (landscapeRightPaneMode == 0) primaryTextColor else secondaryTextColor
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (landscapeRightPaneMode == 1) (if (isDark) Color(0xFF35353C) else Color.White) else Color.Transparent)
                                                .clickable { landscapeRightPaneMode = 1 },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "列表",
                                                fontSize = 11.sp,
                                                fontWeight = if (landscapeRightPaneMode == 1) FontWeight.Bold else FontWeight.Normal,
                                                color = if (landscapeRightPaneMode == 1) primaryTextColor else secondaryTextColor
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // 右侧共用视窗内容展示
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                if (landscapeRightPaneMode == 0) {
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
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    if (playlist.isEmpty()) {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text("待播队列为空", color = secondaryTextColor, fontSize = 13.sp)
                                        }
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            itemsIndexed(playlist, key = { _, item -> item.id }) { index, item ->
                                                val isCurrent = item.id == song.id
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(if (isCurrent) AppleRed.copy(alpha = 0.15f) else Color.Transparent)
                                                        .clickable { onSelectSongFromQueue(item) }
                                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text("${index + 1}", color = if (isCurrent) AppleRed else secondaryTextColor, fontSize = 12.sp, modifier = Modifier.width(24.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(item.title, color = if (isCurrent) AppleRed else primaryTextColor, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
                                                        Text(item.artist, color = secondaryTextColor, fontSize = 11.sp, maxLines = 1)
                                                    }
                                                    if (isCurrent) {
                                                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = AppleRed, modifier = Modifier.size(16.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
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
            // 竖屏标准布局：滑动指示器 + 分段切换 + 大图/歌词 Pager + 底部控制器与 3 大工具按键
            // =========================================================================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. 顶部操作栏 (左侧最小化 + 居中 [ 歌曲 | 歌词 ] 胶囊切换 + 右侧收藏按键)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
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

                    // 顶部居中：[ 歌曲 | 歌词 ] 分段切换胶囊
                    val isOnLyricsPage = pagerState.currentPage == 1
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = circleButtonBg,
                        modifier = Modifier
                            .height(34.dp)
                            .width(136.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(
                                        if (!isOnLyricsPage) (if (isDark) Color(0xFF3A3A42) else Color.White)
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        coroutineScope.launch { pagerState.animateScrollToPage(0) }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "歌曲",
                                    fontSize = 13.sp,
                                    fontWeight = if (!isOnLyricsPage) FontWeight.Bold else FontWeight.Medium,
                                    color = if (!isOnLyricsPage) primaryTextColor else secondaryTextColor
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(
                                        if (isOnLyricsPage) (if (isDark) Color(0xFF3A3A42) else Color.White)
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        coroutineScope.launch { pagerState.animateScrollToPage(1) }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "歌词",
                                    fontSize = 13.sp,
                                    fontWeight = if (isOnLyricsPage) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isOnLyricsPage) primaryTextColor else secondaryTextColor
                                )
                            }
                        }
                    }

                    // 顶部右侧：对称占位以保证中央 [ 歌曲 | 歌词 ] 胶囊严格居中
                    Spacer(modifier = Modifier.size(36.dp))
                }

                // 2. 中间 Pager (支持左右手势直接切换 [ 歌曲封面大图 + 5行滚动渐变歌词 ⇄ 实时全屏歌词 ])
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
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
                                verticalArrangement = Arrangement.Center
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
                            showAdjustButton = true,
                            modifier = Modifier.fillMaxSize()
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

                        // 音质、码率、文件大小三色区分徽章
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = AppleRed.copy(alpha = 0.18f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .clickable { showAudioSpecsPanel = true }
                            ) {
                                Text(
                                    text = displayQualityBadge,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppleRed,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = bitrateBadgeColor.copy(alpha = 0.16f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .clickable { showAudioSpecsPanel = true }
                            ) {
                                Text(
                                    text = displayBitrateBadge,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = bitrateBadgeColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = sizeBadgeColor.copy(alpha = 0.16f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .clickable { showAudioSpecsPanel = true }
                            ) {
                                Text(
                                    text = displaySizeBadge,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = sizeBadgeColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "${song.artist} — ${if (song.album.isNotBlank()) song.album else "精选单曲"}",
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = secondaryTextColor
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 加入歌单按钮 (音频共享式展出)
                        Box {
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
                        val hasPhysicalLocal = !song.localFilePath.isNullOrBlank() && 
                            (song.localFilePath!!.startsWith("content://") || runCatching { java.io.File(song.localFilePath!!).let { it.exists() && it.length() > 0L } }.getOrDefault(false))
                        Box {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(circleButtonBg)
                                    .clickable { showDownloadMenu = true },
                                contentAlignment = Alignment.Center
                            ) {
                                val isDownloaded = hasPhysicalLocal && (song.downloadStatus == DownloadStatus.DOWNLOADED || !song.localFilePath.isNullOrBlank())
                                val isServerCached = song.serverId.isNotBlank() && song.serverId != "local_storage" && song.serverId != "lemon_online"
                                if (isDownloaded && isServerCached) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "双端已同步",
                                        tint = Color(0xFF34C759),
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else if (isDownloaded || isServerCached) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircleOutline,
                                        contentDescription = "单端已缓存",
                                        tint = Color(0xFF34C759),
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

                Spacer(modifier = Modifier.height(8.dp))

                // 4. 进度条与播放时间
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
                        modifier = Modifier.fillMaxWidth().height(20.dp)
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

                    // 大号实心主播放键
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

                Spacer(modifier = Modifier.height(10.dp))

                // 6. 底部功能工具栏: [ ♥ 喜欢收藏 ] [ ≡ 播放列表 ] [ ⏱ 定时关闭 ] [ 音频输出 ] [ ⓘ 参数详情 ]
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

                    IconButton(onClick = { showQueueSheet = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "播放列表",
                            tint = secondaryTextColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Box {
                        IconButton(onClick = { showSleepTimerPanel = true }) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = "定时关闭",
                                tint = if (activeTimerMinutes > 0) AppleRed else secondaryTextColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        SleepTimerDropdownMenu(
                            expanded = showSleepTimerPanel,
                            onDismissRequest = { showSleepTimerPanel = false },
                            activeTimerMinutes = activeTimerMinutes,
                            onSelectTimer = { min ->
                                activeTimerMinutes = min
                                if (min > 0) {
                                    Toast.makeText(context, "已设置：${min}分钟后停止播放", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "已关闭定时器", Toast.LENGTH_SHORT).show()
                                }
                            }
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

                    Box {
                        IconButton(onClick = { showAudioSpecsPanel = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = "音频参数详情",
                                tint = secondaryTextColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        AudioSpecsDropdownMenu(
                            expanded = showAudioSpecsPanel,
                            onDismissRequest = { showAudioSpecsPanel = false },
                            song = song
                        )
                    }
                }
            }
        }



        // 4. 待播队列弹窗 (竖屏)
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
                        text = "待播队列 (${playlist.size} 首)",
                        style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxHeight(0.6f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(playlist, key = { _, item -> item.id }) { index, item ->
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
                                if (isCurrent) {
                                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = AppleRed, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
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
    onSelectTimer: (Int) -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.widthIn(min = 220.dp, max = 280.dp)
    ) {
        Text(
            text = if (activeTimerMinutes > 0) "已设置：${activeTimerMinutes}分钟后停止" else "睡眠定时关闭",
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.5.dp)

        val presets = listOf(15 to "15 分钟", 30 to "30 分钟", 45 to "45 分钟", 60 to "60 分钟", 90 to "90 分钟", -1 to "播完当前曲")
        presets.forEach { (min, label) ->
            val isSelected = activeTimerMinutes == min
            DropdownMenuItem(
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.width(24.dp)) {
                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = AppleRed, modifier = Modifier.size(16.dp))
                            }
                        }
                        Text(
                            text = label,
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                },
                onClick = {
                    onSelectTimer(min)
                    onDismissRequest()
                },
                modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(10.dp))
            )
        }

        if (activeTimerMinutes > 0) {
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.5.dp)
            DropdownMenuItem(
                text = {
                    Text(
                        text = "关闭定时器",
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppleRed),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                },
                onClick = {
                    onSelectTimer(0)
                    onDismissRequest()
                },
                modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(10.dp))
            )
        }
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

    val locationText: String = if (isLocal) (song.localFilePath ?: "本地存储") else (song.streamUrl.takeIf { it.isNotBlank() } ?: "在线 NAS 媒体流")
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

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    val scope = rememberCoroutineScope()
    val localRoutes by AudioSharingManager.localRoutes.collectAsState()
    val lanDevices by AudioSharingManager.lanDevices.collectAsState()
    val activeCastDevice by AudioSharingManager.activeCastDevice.collectAsState()
    val isScanning by AudioSharingManager.isScanning.collectAsState()
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f

    LaunchedEffect(expanded) {
        if (expanded) {
            AudioSharingManager.refreshLocalAudioRoutes(context)
            AudioSharingManager.startLanDiscovery(context)
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = if (isDark) Color(0xFF1E1E26) else Color(0xFFF9F9FC),
            border = BorderStroke(1.dp, if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)),
            shadowElevation = 20.dp,
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .widthIn(max = 380.dp)
                .heightIn(max = 520.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp)
            ) {
                // 1. 顶部标题与刷新扫描按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "音频输出与 AirPlay / DLNA 共享",
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.weight(1f)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isScanning) "扫描中..." else "刷新局域网",
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppleRed),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = !isScanning) {
                                    AudioSharingManager.refreshLocalAudioRoutes(context)
                                    AudioSharingManager.startLanDiscovery(context)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        IconButton(onClick = onDismissRequest, modifier = Modifier.size(26.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.5.dp)

                // 2. 本机实时音频输出通道列表 (扬声器 / 蓝牙 A2DP / USB DAC / 有线耳机)
                localRoutes.forEach { route ->
                    val isCurrentActive = activeCastDevice == null && route.isActivePrimary
                    val routeIcon = when {
                        route.typeLabel.contains("蓝牙") -> Icons.Default.Bluetooth
                        route.typeLabel.contains("USB") -> Icons.Default.Usb
                        route.typeLabel.contains("耳机") -> Icons.Default.Headphones
                        else -> Icons.AutoMirrored.Filled.VolumeUp
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                if (activeCastDevice != null) {
                                    AudioSharingManager.stopActiveCastAsync(
                                        context = context,
                                        toastMessage = "已断开局域网投射，恢复本机输出: ${route.name}"
                                    )
                                } else {
                                    AudioSharingManager.launchSystemMediaOutputSwitcher(context)
                                }
                                onDismissRequest()
                            }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = routeIcon,
                                contentDescription = null,
                                tint = if (isCurrentActive) AppleRed else Color(0xFF007AFF),
                                modifier = Modifier.size(19.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = route.name,
                                    fontSize = 13.5.sp,
                                    fontWeight = if (isCurrentActive) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isCurrentActive) AppleRed else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val subText = buildString {
                                    append(if (isCurrentActive) "当前输出通道" else "点击切换系统音频路由")
                                    if (route.sampleRatesSummary.isNotBlank()) {
                                        append(" · 最高 ${route.sampleRatesSummary}")
                                    }
                                }
                                Text(subText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (isCurrentActive) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = AppleRed, modifier = Modifier.size(17.dp))
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))

                // 3. 局域网发现的 DLNA / UPnP MediaRenderer 与 AirPlay / RAOP 接收端
                Text(
                    text = if (isScanning) "局域网音响与接收器 (正在搜索 AirPlay / DLNA...)" else "局域网音响与接收器 (${lanDevices.size})",
                    style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )

                if (lanDevices.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { AudioSharingManager.startLanDiscovery(context) }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.WifiTethering,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isScanning) "正在扫描同一 Wi-Fi 下的设备..." else "暂未发现 DLNA / AirPlay 音响",
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "请确保音响/电视与手机处于同一局域网，点击重新扫描",
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            )
                        }
                    }
                } else {
                    lanDevices.forEach { device ->
                        val isCurrentCast = activeCastDevice?.id == device.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    if (isCurrentCast) {
                                        AudioSharingManager.stopActiveCastAsync(
                                            context = context,
                                            toastMessage = "已停止向 ${device.name} 投射音频"
                                        )
                                    } else {
                                        AudioSharingManager.castSongToDeviceAsync(
                                            context,
                                            device,
                                            song,
                                            progressMs
                                        )
                                    }
                                    onDismissRequest()
                                }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (device.protocol == ShareProtocolType.AIRPLAY_RAOP) Icons.Default.Airplay else Icons.Default.SpeakerGroup,
                                    contentDescription = null,
                                    tint = if (isCurrentCast) AppleRed else Color(0xFF5856D6),
                                    modifier = Modifier.size(19.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = device.name,
                                        fontSize = 13.5.sp,
                                        fontWeight = if (isCurrentCast) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isCurrentCast) AppleRed else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${device.protocol.badge} · ${device.host}${if (isCurrentCast) " (正在投射，点击断开)" else ""}",
                                        fontSize = 11.sp,
                                        color = if (isCurrentCast) AppleRed.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (isCurrentCast) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = AppleRed, modifier = Modifier.size(17.dp))
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))

                // 4. 系统媒体输出切换器 (Android 11+ Media Output Panel) 与系统无线投屏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            AudioSharingManager.launchSystemMediaOutputSwitcher(context)
                            onDismissRequest()
                        }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.SettingsInputComponent, contentDescription = null, tint = Color(0xFF007AFF), modifier = Modifier.size(19.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("系统音频输出切换面板", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                        Text("一键切换车载蓝牙、外置声卡与系统扬声器", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            AudioSharingManager.launchSystemCastSettings(context)
                            onDismissRequest()
                        }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CastConnected, contentDescription = null, tint = Color(0xFF5856D6), modifier = Modifier.size(19.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("系统无线投屏 (Cast / 镜像)", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                        Text("调用系统级屏幕与音频投射设置", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))

                // 5. 分享当前歌曲
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            try {
                                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, "正在播放: ${song.title}")
                                    putExtra(android.content.Intent.EXTRA_TEXT, "我正在使用 LMPlayer 收听 《${song.title}》 - ${song.artist}")
                                }
                                context.startActivity(android.content.Intent.createChooser(shareIntent, "分享歌曲至"))
                            } catch (_: Exception) {
                                Toast.makeText(context, "无法启动系统分享", Toast.LENGTH_SHORT).show()
                            }
                            onDismissRequest()
                        }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = AppleRed, modifier = Modifier.size(19.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("分享当前歌曲", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("${song.title} — ${song.artist}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

