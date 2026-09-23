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
import androidx.compose.foundation.shape.CircleShape
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
import com.lm.player.core.model.*
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 现代全屏音乐播放界面 (全面支持横竖屏自适应与界面内嵌融合面板)
 * - 竖屏模式：顶部 Segmented 切换 [ 歌曲 | 歌词 ]、支持左右滑动无缝切换歌词、下拉手势丝滑最小化
 * - 横屏模式：超大专辑封面展示 + 左侧全功能控制器与工具栏 + 右侧视窗支持 [ 歌词 ⇄ 待播列表 ] 顶部右上角一键无缝切换
 * - 底部工具条：[ ≡ 待播列表 ] [ ⏱ 定时关闭 ] [ ⓘ 音频参数详情 ] (取消倍速展示，界面融为一体)
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
            // 横屏布局：支持左右拖动调节比例 (左侧控制器 66.7%~100% + 中间手柄 + 右侧歌词/待播 0%~33.3%)
            // =========================================================================
            val screenWidthPx = with(LocalDensity.current) { configuration.screenWidthDp.dp.toPx() }
            val effectiveLyricsRatio = landscapeLyricsRatio.coerceIn(0.0f, 0.333f)
            val playerWeight = (1.0f - effectiveLyricsRatio).coerceIn(0.667f, 1.0f)
            val isLyricsVisible = effectiveLyricsRatio > 0.04f

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧控制器面板 (自适应占比 66.7% ~ 100%)
                Column(
                    modifier = Modifier
                        .weight(playerWeight)
                        .fillMaxHeight(),
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
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(circleButtonBg)
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "最小化", tint = primaryTextColor, modifier = Modifier.size(22.dp))
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = circleButtonBg
                        ) {
                            Text(
                                text = if (song.localFilePath != null) "本地高保真音频" else "在线高保真流媒体",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = primaryTextColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 加入歌单 (音频共享式展出)
                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(circleButtonBg)
                                        .clickable(onClick = { showLandscapeAddToPlaylistMenu = true }),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "加入歌单", tint = primaryTextColor, modifier = Modifier.size(19.dp))
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
                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(circleButtonBg)
                                        .clickable(onClick = { showLandscapeDownloadMenu = true }),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val isDownloaded = song.downloadStatus == DownloadStatus.DOWNLOADED || song.localFilePath != null
                                    val isServerCached = song.serverId.isNotBlank() && song.serverId != "local_storage" && song.serverId != "lemon_online"
                                    if (isDownloaded && isServerCached) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = "双端已同步", tint = Color(0xFF34C759), modifier = Modifier.size(19.dp))
                                    } else if (isDownloaded || isServerCached) {
                                        Icon(Icons.Default.CheckCircleOutline, contentDescription = "单端已缓存", tint = Color(0xFF34C759), modifier = Modifier.size(19.dp))
                                    } else {
                                        Icon(Icons.Default.FileDownload, contentDescription = "下载", tint = primaryTextColor, modifier = Modifier.size(19.dp))
                                    }
                                }
                                DownloadQualityDropdownMenu(
                                    expanded = showLandscapeDownloadMenu,
                                    onDismissRequest = { showLandscapeDownloadMenu = false },
                                    song = song,
                                    isServerConnected = isServerConnected,
                                    hasLocal = song.downloadStatus == DownloadStatus.DOWNLOADED || song.localFilePath != null,
                                    hasServer = song.serverId.isNotBlank() && song.serverId != "local_storage" && song.serverId != "lemon_online",
                                    onConfirm = { target, quality ->
                                        showLandscapeDownloadMenu = false
                                        onDownloadSongWithOptions(song, target, quality)
                                    }
                                )
                            }

                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(circleButtonBg)
                                        .clickable(onClick = { showAudioOutputPanel = true }),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.SurroundSound, contentDescription = "音频输出与共享", tint = primaryTextColor, modifier = Modifier.size(19.dp))
                                }
                                AudioOutputDropdownMenu(
                                    expanded = showAudioOutputPanel,
                                    onDismissRequest = { showAudioOutputPanel = false },
                                    song = song
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(circleButtonBg)
                                    .clickable {
                                        localIsFavorite = !localIsFavorite
                                        onToggleFavorite()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (localIsFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = "喜欢",
                                    tint = if (localIsFavorite) AppleRed else primaryTextColor,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }

                    // 2. 超大专辑封面展示 + 歌名歌手
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        AlbumArtworkImage(
                            model = song.coverUrl,
                            seedId = song.id,
                            targetSize = 480,
                            modifier = Modifier
                                .size(230.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .shadow(16.dp, RoundedCornerShape(18.dp)),
                            cornerRadius = 18.dp
                        )

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = song.title,
                                style = TextStyle(
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryTextColor
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = song.artist,
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    color = secondaryTextColor
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = song.album.ifBlank { "单曲精选" },
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    color = secondaryTextColor.copy(alpha = 0.8f)
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = AppleRed.copy(alpha = 0.15f),
                                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            ) {
                                Text(
                                    text = "${song.format.uppercase()} ${song.bitRate} kbps",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppleRed,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
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
                            modifier = Modifier.fillMaxWidth().height(18.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = formatDuration(progressMs), fontSize = 11.sp, color = secondaryTextColor)
                            Text(text = formatDuration(totalDurationMs), fontSize = 11.sp, color = secondaryTextColor)
                        }
                    }

                    // 4. 5 大主控播放按键
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onToggleShuffle) {
                            Icon(Icons.Default.Shuffle, contentDescription = "随机", tint = if (isShuffle) AppleRed else secondaryTextColor, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = onPrevious) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "上一首", tint = primaryTextColor, modifier = Modifier.size(32.dp))
                        }
                        IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(52.dp).clip(CircleShape).background(primaryTextColor)) {
                            Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "播放/暂停", tint = if (isDark) Color.Black else Color.White, modifier = Modifier.size(28.dp))
                        }
                        IconButton(onClick = onNext) {
                            Icon(Icons.Default.SkipNext, contentDescription = "下一首", tint = primaryTextColor, modifier = Modifier.size(32.dp))
                        }
                        IconButton(onClick = onToggleRepeat) {
                            Icon(Icons.Default.Repeat, contentDescription = "循环", tint = if (isRepeat) AppleRed else secondaryTextColor, modifier = Modifier.size(20.dp))
                        }
                    }

                    // 5. 底部功能工具栏
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp)),
                        color = surfaceGlassColor
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                landscapeRightPaneMode = if (landscapeRightPaneMode == 1) 0 else 1
                            }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = "待播列表",
                                    tint = if (landscapeRightPaneMode == 1) AppleRed else secondaryTextColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Box {
                                IconButton(onClick = { showSleepTimerPanel = true }) {
                                    Icon(
                                        imageVector = Icons.Default.Timer,
                                        contentDescription = "定时关闭",
                                        tint = if (activeTimerMinutes > 0) AppleRed else secondaryTextColor,
                                        modifier = Modifier.size(20.dp)
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
                                IconButton(onClick = { showAudioSpecsPanel = true }) {
                                    Icon(Icons.Outlined.Info, contentDescription = "参数详情", tint = secondaryTextColor, modifier = Modifier.size(20.dp))
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
                // 1. 顶部操作栏
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

                    // 顶部 Segmented Control 胶囊切换: [ 歌曲 | 歌词 ]
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = circleButtonBg,
                        modifier = Modifier
                            .height(34.dp)
                            .width(116.dp)
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
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (pagerState.currentPage == 0) (if (isDark) Color(0xFF3A3A3C) else Color.White) else Color.Transparent)
                                    .clickable {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(0)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "歌曲",
                                    style = TextStyle(
                                        fontSize = 12.sp,
                                        fontWeight = if (pagerState.currentPage == 0) FontWeight.Bold else FontWeight.Normal,
                                        color = if (pagerState.currentPage == 0) primaryTextColor else secondaryTextColor
                                    )
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (pagerState.currentPage == 1) (if (isDark) Color(0xFF3A3A3C) else Color.White) else Color.Transparent)
                                    .clickable {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(1)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "歌词",
                                    style = TextStyle(
                                        fontSize = 12.sp,
                                        fontWeight = if (pagerState.currentPage == 1) FontWeight.Bold else FontWeight.Normal,
                                        color = if (pagerState.currentPage == 1) primaryTextColor else secondaryTextColor
                                    )
                                )
                            }
                        }
                    }

                    // 顶部右侧：怀旧模式 + 音频共享输出 + 收藏按键
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(circleButtonBg)
                                    .clickable(onClick = { showAudioOutputPanel = true }),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SurroundSound,
                                    contentDescription = "音频共享输出",
                                    tint = primaryTextColor,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                            AudioOutputDropdownMenu(
                                expanded = showAudioOutputPanel,
                                onDismissRequest = { showAudioOutputPanel = false },
                                song = song
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(circleButtonBg)
                                .clickable {
                                    localIsFavorite = !localIsFavorite
                                    onToggleFavorite()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (localIsFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = "红心喜欢",
                                tint = if (localIsFavorite) AppleRed else primaryTextColor,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }

                // 2. 中间 Pager (支持左右手势直接切换 [ 歌曲封面大图 ⇄ 实时全屏歌词 ])
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 14.dp)
                ) { page ->
                    if (page == 0) {
                        // 页面 0: 居中大封面展示
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            val artworkSize = (configuration.screenWidthDp - 48).dp.coerceIn(280.dp, 400.dp)
                            AlbumArtworkImage(
                                model = song.coverUrl,
                                seedId = song.id,
                                targetSize = 512,
                                modifier = Modifier
                                    .size(artworkSize)
                                    .shadow(20.dp, RoundedCornerShape(24.dp)),
                                cornerRadius = 24.dp
                            )
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

                // 3. 歌曲元数据：标题、歌手与专辑 + 加入歌单与下载按钮
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
                                fontSize = 22.sp * dimensions.fontScale,
                                fontWeight = FontWeight.Bold,
                                color = primaryTextColor
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "${song.artist} — ${if (song.album.isNotBlank()) song.album else "精选单曲"}",
                            style = TextStyle(
                                fontSize = 15.sp,
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
                        Box {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(circleButtonBg)
                                    .clickable { showDownloadMenu = true },
                                contentAlignment = Alignment.Center
                            ) {
                                val isDownloaded = song.downloadStatus == DownloadStatus.DOWNLOADED || song.localFilePath != null
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
                                hasLocal = song.downloadStatus == DownloadStatus.DOWNLOADED || song.localFilePath != null,
                                hasServer = song.serverId.isNotBlank() && song.serverId != "local_storage" && song.serverId != "lemon_online",
                                onConfirm = { target, quality ->
                                    showDownloadMenu = false
                                    onDownloadSongWithOptions(song, target, quality)
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

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

                Spacer(modifier = Modifier.height(12.dp))

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

                Spacer(modifier = Modifier.height(18.dp))

                // 6. 底部 3 大功能工具栏: [ ≡ 播放列表 ] [ ⏱ 定时关闭 ] [ ⓘ 参数详情 ]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
    val formatStr = song.format.uppercase()
    val isLossless = formatStr in listOf("FLAC", "WAV", "ALAC", "APE", "DSD", "DSF") || song.bitRate >= 800
    val qualityTag = if (isLossless) "Hi-Res 无损母带" else if (song.bitRate >= 320) "极高品质音频" else "标准音频"
    val isLocal = !song.localFilePath.isNullOrBlank()

    val localSize: String? = if (isLocal && !song.localFilePath.orEmpty().startsWith("content://")) {
        try {
            val f = java.io.File(song.localFilePath!!)
            if (f.exists()) "%.1f MB".format(java.util.Locale.US, f.length() / (1024.0 * 1024.0)) else null
        } catch (_: Exception) {
            null
        }
    } else {
        null
    }

    val sizeText: String = localSize ?: run {
        val durationSec = (song.durationMs / 1000L).coerceAtLeast(180L)
        val rate = if (isLossless) 900 else song.bitRate.coerceAtLeast(320)
        val estMb = (durationSec * rate * 1024L / 8L) / (1024.0 * 1024.0)
        "%.1f MB (预估)".format(java.util.Locale.US, estMb)
    }

    val locationText: String = if (isLocal) (song.localFilePath ?: "本地存储") else (song.streamUrl.takeIf { it.isNotBlank() } ?: "在线 NAS 媒体流")

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier
            .widthIn(min = 270.dp, max = 330.dp)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFD4AF37).copy(alpha = 0.2f)) {
                Text("Hi-Res", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD4AF37), modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("音频参数详情", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SpecRowItem("音质等级", qualityTag, valueColor = if (isLossless) Color(0xFFD4AF37) else AppleRed)
            SpecRowItem("编码格式", formatStr, isBold = true)
            SpecRowItem("音频码率", "${if (song.bitRate > 0) song.bitRate else (if (isLossless) 920 else 320)} kbps")
            SpecRowItem("文件大小", sizeText, isBold = true, valueColor = AppleRed)
            SpecRowItem("存储位置", locationText, isPath = true)
            SpecRowItem("播放通道", if (isLocal) "本地硬件直解" else "NAS 无损推流", valueColor = AppleRed)
        }
    }
}

@Composable
fun AudioOutputDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    song: UnifiedSong
) {
    val context = LocalContext.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.widthIn(min = 240.dp, max = 300.dp)
    ) {
        Text(
            text = "音频输出与共享",
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.5.dp)

        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = AppleRed, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("本机扬声器 (当前通道)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("高保真硬件直出", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            onClick = {
                Toast.makeText(context, "当前已在使用设备扬声器输出", Toast.LENGTH_SHORT).show()
                onDismissRequest()
            },
            modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(10.dp))
        )

        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null, tint = Color(0xFF007AFF), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("车载蓝牙 / 无线耳机", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                        Text("随系统音频路由自动切换", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            onClick = {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                    context.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(context, "请在系统设置中连接车载蓝牙", Toast.LENGTH_SHORT).show()
                }
                onDismissRequest()
            },
            modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(10.dp))
        )

        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Cast, contentDescription = null, tint = Color(0xFF5856D6), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("无线投屏 (Cast / AirPlay)", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                        Text("投送至车机大屏或家庭音响", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            onClick = {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_CAST_SETTINGS)
                    context.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(context, "请在系统控制中心选择投屏设备", Toast.LENGTH_SHORT).show()
                }
                onDismissRequest()
            },
            modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(10.dp))
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.5.dp)

        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = AppleRed, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("分享当前歌曲", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("${song.title} — ${song.artist}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            onClick = {
                try {
                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "正在播放: ${song.title}")
                        putExtra(android.content.Intent.EXTRA_TEXT, "我正在使用 ZDS 车载音乐播放器收听 《${song.title}》 - ${song.artist}")
                    }
                    context.startActivity(android.content.Intent.createChooser(shareIntent, "分享歌曲至"))
                } catch (_: Exception) {
                    Toast.makeText(context, "无法启动系统分享", Toast.LENGTH_SHORT).show()
                }
                onDismissRequest()
            },
            modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(10.dp))
        )
    }
}

