package com.lm.player.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.media.DynamicIslandManager
import com.lm.player.core.media.IslandDisplayMode
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.UnifiedSong
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * 全局内置灵动岛 (Dynamic Island) 悬浮交互胶囊与展开播控面板
 *
 * 特性：
 * - 紧凑胶囊态 (Compact Pill)：左侧旋转黑胶封面 + 中部实时同步歌词/曲目平滑翻滚 + 右侧动态音频律动波纹
 * - 展开大卡态 (Expanded Card)：高清专辑图 + Hi-Res 音质徽章 + 实时双行同步歌词 + 进度条 + 喜欢/上曲/播放/下曲/全屏歌词操控
 * - 手势交互：单击胶囊展开/收起面板，左右滑动胶囊快速切歌
 */
@Composable
fun DynamicIslandOverlay(
    currentSong: UnifiedSong?,
    isPlaying: Boolean,
    progressMs: Long,
    totalDurationMs: Long,
    isVisibleAllowed: Boolean,
    onPlayPauseToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleFavorite: (UnifiedSong) -> Unit,
    onOpenFullPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayMode by DynamicIslandManager.islandDisplayModeFlow.collectAsState()
    val showLyricsInPill by DynamicIslandManager.showLyricsInPillFlow.collectAsState()
    val currentLyric by DynamicIslandManager.currentLyricLineFlow.collectAsState()
    val nextLyric by DynamicIslandManager.nextLyricLineFlow.collectAsState()
    val manualExpandTrigger by DynamicIslandManager.manualExpandTriggerFlow.collectAsState()

    var isExpanded by remember { mutableStateOf(false) }
    var lastAutoExpandSongId by remember { mutableStateOf("") }

    // 手动触发预览时自动展开灵动岛
    LaunchedEffect(manualExpandTrigger) {
        if (manualExpandTrigger > 0L) {
            isExpanded = true
        }
    }

    // 智能上岛模式下，切歌时短暂呈现展开态或胶囊动效提醒，展开态无操作 6 秒后平滑收回胶囊态
    LaunchedEffect(currentSong?.id) {
        val id = currentSong?.id.orEmpty()
        if (id.isNotBlank() && lastAutoExpandSongId.isNotBlank() && id != lastAutoExpandSongId) {
            // 切歌时保持轻盈胶囊态并重置展开计时
        }
        if (id.isNotBlank()) {
            lastAutoExpandSongId = id
        }
    }

    LaunchedEffect(isExpanded, currentSong?.id, isPlaying) {
        if (isExpanded) {
            delay(6500L)
            isExpanded = false
        }
    }

    val shouldShowIsland = isVisibleAllowed &&
            currentSong != null &&
            displayMode != IslandDisplayMode.SYSTEM_ONLY &&
            (displayMode == IslandDisplayMode.ALWAYS_ON || isPlaying || isExpanded || manualExpandTrigger > 0L)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 4.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        AnimatedVisibility(
            visible = shouldShowIsland,
            enter = fadeIn(tween(220)) +
                    scaleIn(initialScale = 0.82f, animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f)) +
                    expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut(tween(180)) +
                    scaleOut(targetScale = 0.85f) +
                    shrinkVertically(shrinkTowards = Alignment.Top)
        ) {
            val song = currentSong ?: return@AnimatedVisibility
            var dragDeltaX by remember { mutableFloatStateOf(0f) }

            val islandShape = RoundedCornerShape(if (isExpanded) 28.dp else 50.dp)
            Surface(
                shape = islandShape,
                color = Color(0xFF0A0A0E).copy(alpha = 0.95f),
                border = BorderStroke(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.18f),
                            AppleRed.copy(alpha = if (isPlaying) 0.42f else 0.16f),
                            Color.White.copy(alpha = 0.18f)
                        )
                    )
                ),
                shadowElevation = if (isExpanded) 18.dp else 10.dp,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
                    .pointerInput(song.id) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragDeltaX = 0f },
                            onHorizontalDrag = { _, dragAmount ->
                                dragDeltaX += dragAmount
                            },
                            onDragEnd = {
                                if (dragDeltaX > 72f) {
                                    onPrevious()
                                } else if (dragDeltaX < -72f) {
                                    onNext()
                                }
                                dragDeltaX = 0f
                            }
                        )
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        isExpanded = !isExpanded
                    }
            ) {
                if (!isExpanded) {
                    CompactIslandPill(
                        song = song,
                        isPlaying = isPlaying,
                        currentLyric = if (showLyricsInPill) currentLyric else "",
                        onTogglePlay = onPlayPauseToggle
                    )
                } else {
                    ExpandedIslandPanel(
                        song = song,
                        isPlaying = isPlaying,
                        currentLyric = currentLyric,
                        nextLyric = nextLyric,
                        progressMs = progressMs,
                        totalDurationMs = totalDurationMs,
                        onPlayPauseToggle = onPlayPauseToggle,
                        onPrevious = onPrevious,
                        onNext = onNext,
                        onToggleFavorite = { onToggleFavorite(song) },
                        onOpenFullPlayer = {
                            isExpanded = false
                            onOpenFullPlayer()
                        },
                        onCollapse = { isExpanded = false }
                    )
                }
            }
        }
    }
}

/**
 * 紧凑灵动胶囊态 (Compact Pill)
 */
@Composable
private fun CompactIslandPill(
    song: UnifiedSong,
    isPlaying: Boolean,
    currentLyric: String,
    onTogglePlay: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "island_vinyl_spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "vinyl_angle"
    )

    val displayLine = remember(currentLyric, song.title, song.artist) {
        if (currentLyric.isNotBlank()) {
            currentLyric
        } else {
            "${song.title} · ${song.artist}"
        }
    }

    Row(
        modifier = Modifier
            .widthIn(min = 170.dp, max = 290.dp)
            .height(36.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧旋转黑胶圆盘封面
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .rotate(if (isPlaying) rotation else 0f),
            contentAlignment = Alignment.Center
        ) {
            AlbumArtworkImage(
                model = song.coverUrl,
                seedId = song.id,
                modifier = Modifier.size(24.dp),
                cornerRadius = 12.dp,
                targetSize = 96
            )
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0A0A0E))
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 中部实时同步歌词 / 歌名平滑翻滚动画
        Box(
            modifier = Modifier.weight(1f, fill = false),
            contentAlignment = Alignment.CenterStart
        ) {
            AnimatedContent(
                targetState = displayLine,
                transitionSpec = {
                    (slideInVertically { height -> height } + fadeIn(tween(200)))
                        .togetherWith(slideOutVertically { height -> -height } + fadeOut(tween(160)))
                        .using(SizeTransform(clip = false))
                },
                label = "island_lyric_flip"
            ) { targetText ->
                Text(
                    text = targetText,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 右侧动态频谱律动波纹 (点击可直接暂停/播放)
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTogglePlay
                )
                .padding(horizontal = 2.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            DynamicIslandWaveform(isPlaying = isPlaying)
        }
    }
}

/**
 * 展开态灵动岛大卡片 (Expanded Island Card)
 */
@Composable
private fun ExpandedIslandPanel(
    song: UnifiedSong,
    isPlaying: Boolean,
    currentLyric: String,
    nextLyric: String,
    progressMs: Long,
    totalDurationMs: Long,
    onPlayPauseToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenFullPlayer: () -> Unit,
    onCollapse: () -> Unit
) {
    val effectiveDuration = if (totalDurationMs > 0L) totalDurationMs else song.durationMs.coerceAtLeast(1L)
    val progressFraction = if (effectiveDuration > 0L) {
        (progressMs.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val isLossless = song.format.uppercase(Locale.US) in listOf("FLAC", "WAV", "APE", "ALAC", "DSD", "DSF") || song.bitRate >= 800
    val badgeText = when {
        isLossless -> "Hi-Res 无损"
        song.downloadStatus == DownloadStatus.DOWNLOADED -> "本地离线"
        song.serverId == "lemon_music" -> "柠檬私有云"
        else -> "在线畅听"
    }

    Column(
        modifier = Modifier
            .widthIn(min = 310.dp, max = 380.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        // 1. 顶部曲目信息与操作角标
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlbumArtworkImage(
                model = song.coverUrl,
                seedId = song.id,
                modifier = Modifier
                    .size(52.dp)
                    .shadow(6.dp, RoundedCornerShape(14.dp))
                    .clickable(onClick = onOpenFullPlayer),
                cornerRadius = 14.dp,
                targetSize = 160
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = song.title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = AppleRed.copy(alpha = 0.22f)
                    ) {
                        Text(
                            text = badgeText,
                            color = Color(0xFFFF6B81),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (song.album.isNotBlank()) "${song.artist} · ${song.album}" else song.artist,
                    color = Color.White.copy(alpha = 0.68f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            DynamicIslandWaveform(isPlaying = isPlaying)
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2. 灵动岛内嵌实时双行歌词视窗 (点击直接进入全屏歌词页)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.White.copy(alpha = 0.06f),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onOpenFullPlayer)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = currentLyric.ifBlank { "♪ ${song.title} - ${song.artist} ♪" },
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (nextLyric.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = nextLyric,
                        color = Color.White.copy(alpha = 0.48f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 3. 实时进度条与时间戳
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatIslandTime(progressMs),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 10.sp
            )
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = AppleRed,
                trackColor = Color.White.copy(alpha = 0.16f),
                strokeCap = StrokeCap.Round
            )
            Text(
                text = formatIslandTime(effectiveDuration),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 10.sp
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 4. 底部灵动岛播控按钮组 (喜欢 / 上一首 / 播放暂停 / 下一首 / 展开全屏 / 收起)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "收藏喜欢",
                    tint = if (song.isFavorite) AppleRed else Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(19.dp)
                )
            }

            IconButton(
                onClick = onPrevious,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "上一首",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Surface(
                shape = CircleShape,
                color = AppleRed,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onPlayPauseToggle)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            IconButton(
                onClick = onNext,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "下一首",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onOpenFullPlayer,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = "打开全屏播放器",
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(
                    onClick = onCollapse,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ExpandLess,
                        contentDescription = "收起灵动岛",
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * 灵动岛右侧四柱彩色音频律动指示器
 */
@Composable
fun DynamicIslandWaveform(
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "island_wave")
    val barDurations = listOf(420, 560, 380, 490)
    val barColors = listOf(
        Color(0xFFFA233B),
        Color(0xFFFF5E3A),
        Color(0xFFFF9500),
        Color(0xFFFA233B)
    )

    Row(
        modifier = modifier.height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        barDurations.forEachIndexed { index, duration ->
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = duration,
                        delayMillis = index * 70,
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "wave_bar_$index"
            )
            val heightFraction = if (isPlaying) scale else 0.22f
            Box(
                modifier = Modifier
                    .width(2.8.dp)
                    .height((15f * heightFraction).dp.coerceAtLeast(3.dp))
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColors[index])
            )
        }
    }
}

private fun formatIslandTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", min, sec)
}
