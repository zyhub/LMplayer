package com.lm.player.feature.podcast

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.podcast.*
import com.lm.player.feature.player.SleepTimerDropdownMenu
import java.util.Locale

/**
 * 播客独立全屏播放页
 *
 * 适用于正在播放播客单集时的全屏播放场景（替代常规音乐播放页）。
 *
 * @param ui 播放器状态模型
 * @param positionMs 实时已播毫秒数（单独传入避免频繁重组 ui 整体）
 * @param actions 播客播放页交互操作
 * @param castButton 投屏 / 音频输出按钮插槽
 */
@Composable
fun PodcastPlayerScreen(
    ui: PodcastPlayerUi,
    positionMs: Long,
    actions: PodcastPlayerActions,
    modifier: Modifier = Modifier,
    castButton: @Composable () -> Unit = {}
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 下滑手势收起
    var dragAccumulatedY by remember { mutableFloatStateOf(0f) }
    val draggableState = rememberDraggableState { delta ->
        dragAccumulatedY += delta
        if (dragAccumulatedY > 150f) {
            dragAccumulatedY = 0f
            actions.onDismiss()
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier
            .fillMaxSize()
            .draggable(
                state = draggableState,
                orientation = Orientation.Vertical,
                onDragStopped = { dragAccumulatedY = 0f }
            )
    ) {
        if (isLandscape) {
            PodcastPlayerLandscapeLayout(
                ui = ui,
                positionMs = positionMs,
                actions = actions,
                castButton = castButton
            )
        } else {
            PodcastPlayerPortraitLayout(
                ui = ui,
                positionMs = positionMs,
                actions = actions,
                castButton = castButton
            )
        }
    }
}

/** 已听完的标识色（Claude 按用户要求补充：绿色已听标志 / 淡绿色已听完按钮） */
private val PlayedGreen = Color(0xFF34C759)

/**
 * 竖屏播放页布局
 *
 * Claude 修改（用户真机反馈「底部单集简介、待播清单点了没反应」）：
 * 原底部面板只能占用封面以外剩下的空间，多数手机上被挤得只剩标签一行，切换后内容区高度为 0。
 * 现在封面在空间不足时自动缩小，底部改为一行入口按钮，点开后以大半屏弹层显示简介 / 待播。
 */
@Composable
private fun PodcastPlayerPortraitLayout(
    ui: PodcastPlayerUi,
    positionMs: Long,
    actions: PodcastPlayerActions,
    castButton: @Composable () -> Unit
) {
    // null = 弹层关闭；0 = 单集简介；1 = 待播清单
    var sheetTab by remember { mutableStateOf<Int?>(null) }
    val isPlayed = ui.episode.playState == EpisodePlayState.PLAYED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. 顶部操作栏 (收起按钮 + 节目入口 + 投屏按键)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = actions.onDismiss) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "收起",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clickable { actions.onOpenShow(ui.episode.showId) }
            ) {
                Text(
                    text = "正在播放播客",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = ui.showTitle,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Box(contentAlignment = Alignment.Center) {
                castButton()
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2. 1:1 大号单集封面：空间不足时按剩余高度缩小，保证下方控件完整可见
        Box(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth(0.82f)
                .aspectRatio(1f, matchHeightConstraintsFirst = true)
                .shadow(elevation = 16.dp, shape = RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center
        ) {
            AlbumArtworkImage(
                model = ui.coverUrl,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 22.dp,
                seedId = ui.episode.id,
                targetSize = 500
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. 单集标题与所属信息（已听完时显示绿色「已听」标志）
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = ui.episode.title,
                fontSize = 17.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                lineHeight = 23.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPlayed) {
                    PodcastPlayedBadge()
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = listOf(ui.showTitle, ui.episode.publishedText)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 4. 进度条与时间 (可拖动 Seek)
        Column(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = if (ui.durationMs > 0) (positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f) else 0f,
                onValueChange = { ratio ->
                    if (ui.durationMs > 0) {
                        actions.onSeekTo((ratio * ui.durationMs).toLong())
                    }
                },
                enabled = ui.durationMs > 0,
                colors = SliderDefaults.colors(
                    thumbColor = AppleRed,
                    activeTrackColor = AppleRed,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth().height(18.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTime(positionMs),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (ui.durationMs > 0) {
                        val remaining = (ui.durationMs - positionMs).coerceAtLeast(0L)
                        "-${formatTime(remaining)}"
                    } else {
                        "--:--"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 5. 核心 5 大主控播放按键 [倍速] [后退15s] [播放/暂停] [前进30s] [定时关闭]
        PodcastMainControlsRow(
            ui = ui,
            actions = actions,
            playBtnSize = 68.dp,
            skipBtnSize = 44.dp,
            modeBtnSize = 40.dp
        )

        Spacer(modifier = Modifier.height(14.dp))

        // 6. 底部入口行：[单集简介] [待播] …… [标记已听完 / 已听完]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PodcastPillButton(
                icon = Icons.AutoMirrored.Filled.Notes,
                text = "单集简介",
                onClick = { sheetTab = 0 }
            )
            PodcastPillButton(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                text = "待播 ${ui.upNext.size}",
                onClick = { sheetTab = 1 }
            )
            Spacer(modifier = Modifier.weight(1f))
            PodcastMarkPlayedButton(isPlayed = isPlayed, onToggle = { actions.onMarkPlayed(!isPlayed) })
        }
    }

    sheetTab?.let { tab ->
        PodcastNotesQueueSheet(
            ui = ui,
            initialTab = tab,
            actions = actions,
            onDismiss = { sheetTab = null }
        )
    }
}

/**
 * 横屏矮屏播放页布局 (自适应高度 300~400dp，主控与进度条绝不裁切)
 * Claude 修改：右栏增加「简介 / 待播」切换与已听完按钮（原横屏没有待播与标记入口）。
 */
@Composable
private fun PodcastPlayerLandscapeLayout(
    ui: PodcastPlayerUi,
    positionMs: Long,
    actions: PodcastPlayerActions,
    castButton: @Composable () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val isPlayed = ui.episode.playState == EpisodePlayState.PLAYED

    Row(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // 左栏：封面 + 标题 + 进度条 + 5 大主控 (52% 宽)
        Column(
            modifier = Modifier
                .weight(0.52f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = actions.onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "收起", modifier = Modifier.size(24.dp))
                }
                Text(
                    text = ui.showTitle,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clickable { actions.onOpenShow(ui.episode.showId) }
                )
                Box(contentAlignment = Alignment.Center) { castButton() }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AlbumArtworkImage(
                    model = ui.coverUrl,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    cornerRadius = 12.dp,
                    seedId = ui.episode.id,
                    targetSize = 200
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ui.episode.title,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isPlayed) {
                            PodcastPlayedBadge()
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = ui.episode.publishedText,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = if (ui.durationMs > 0) (positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f) else 0f,
                    onValueChange = { ratio ->
                        if (ui.durationMs > 0) actions.onSeekTo((ratio * ui.durationMs).toLong())
                    },
                    enabled = ui.durationMs > 0,
                    colors = SliderDefaults.colors(thumbColor = AppleRed, activeTrackColor = AppleRed),
                    modifier = Modifier.fillMaxWidth().height(14.dp)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(positionMs), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatTime(ui.durationMs), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            PodcastMainControlsRow(
                ui = ui,
                actions = actions,
                playBtnSize = 50.dp,
                skipBtnSize = 36.dp,
                modeBtnSize = 32.dp
            )
        }

        VerticalDivider(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            thickness = 0.8.dp,
            modifier = Modifier.fillMaxHeight().padding(vertical = 4.dp)
        )

        // 右栏：简介 / 待播切换 + 已听完按钮 (48% 宽)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .weight(0.48f)
                .fillMaxHeight()
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PodcastTabText("单集简介", selectedTab == 0) { selectedTab = 0 }
                    Spacer(modifier = Modifier.width(14.dp))
                    PodcastTabText("待播 ${ui.upNext.size}", selectedTab == 1) { selectedTab = 1 }
                    Spacer(modifier = Modifier.weight(1f))
                    PodcastMarkPlayedButton(isPlayed = isPlayed, onToggle = { actions.onMarkPlayed(!isPlayed) })
                }
                Spacer(modifier = Modifier.height(6.dp))
                if (selectedTab == 0) {
                    PodcastShownotesText(ui.episode.description, Modifier.fillMaxSize())
                } else {
                    PodcastUpNextList(ui.upNext, actions, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** 绿色「已听」小标签 */
@Composable
private fun PodcastPlayedBadge() {
    Surface(shape = RoundedCornerShape(6.dp), color = PlayedGreen.copy(alpha = 0.16f)) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = PlayedGreen, modifier = Modifier.size(11.dp))
            Spacer(modifier = Modifier.width(2.dp))
            Text("已听", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = PlayedGreen)
        }
    }
}

/** 标记已听完按钮：已听完时为淡绿色「已听完」，再点取消 */
@Composable
private fun PodcastMarkPlayedButton(isPlayed: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isPlayed) PlayedGreen.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isPlayed) Icons.Default.CheckCircle else Icons.Default.Check,
                contentDescription = null,
                tint = if (isPlayed) PlayedGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isPlayed) "已听完" else "标记已听完",
                fontSize = 12.sp,
                fontWeight = if (isPlayed) FontWeight.Bold else FontWeight.Normal,
                color = if (isPlayed) PlayedGreen else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 竖屏底部入口胶囊 */
@Composable
private fun PodcastPillButton(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun PodcastTabText(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp)
    )
}

@Composable
private fun PodcastShownotesText(description: String, modifier: Modifier) {
    SelectionContainer(modifier = modifier.verticalScroll(rememberScrollState())) {
        Text(
            text = description.ifBlank { "本单集暂无文字简介。" },
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
        )
    }
}

@Composable
private fun PodcastUpNextList(upNext: List<PodcastEpisodeUi>, actions: PodcastPlayerActions, modifier: Modifier) {
    if (upNext.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "同一节目中暂无更多待播单集",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(upNext, key = { it.id }) { nextEp ->
            val played = nextEp.playState == EpisodePlayState.PLAYED
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { actions.onPlayFromUpNext(nextEp) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = nextEp.title,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (played) 0.55f else 1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (played) {
                    Spacer(modifier = Modifier.width(6.dp))
                    PodcastPlayedBadge()
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = nextEp.durationText,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 竖屏：单集简介 / 待播清单 大半屏弹层 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PodcastNotesQueueSheet(
    ui: PodcastPlayerUi,
    initialTab: Int,
    actions: PodcastPlayerActions,
    onDismiss: () -> Unit
) {
    var tab by remember(initialTab) { mutableIntStateOf(initialTab) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PodcastTabText("单集简介", tab == 0) { tab = 0 }
                Spacer(modifier = Modifier.width(14.dp))
                PodcastTabText("待播清单 (${ui.upNext.size})", tab == 1) { tab = 1 }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = ui.episode.title,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(10.dp))
            if (tab == 0) {
                PodcastShownotesText(ui.episode.description, Modifier.fillMaxSize())
            } else {
                PodcastUpNextList(ui.upNext, actions, Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * 5 大主控播放按键行组件
 */
@Composable
private fun PodcastMainControlsRow(
    ui: PodcastPlayerUi,
    actions: PodcastPlayerActions,
    playBtnSize: Dp,
    skipBtnSize: Dp,
    modeBtnSize: Dp
) {
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showSleepMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. 倍速按钮
        Box {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showSpeedMenu = true }
            ) {
                Text(
                    text = PodcastPlayerDefaults.speedText(ui.speed),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (ui.speed != 1.0f) AppleRed else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }

            DropdownMenu(
                expanded = showSpeedMenu,
                onDismissRequest = { showSpeedMenu = false }
            ) {
                PodcastPlayerDefaults.SPEED_OPTIONS.forEach { speedVal ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = PodcastPlayerDefaults.speedText(speedVal),
                                fontWeight = if (ui.speed == speedVal) FontWeight.Bold else FontWeight.Normal,
                                color = if (ui.speed == speedVal) AppleRed else Color.Unspecified
                            )
                        },
                        onClick = {
                            showSpeedMenu = false
                            actions.onSetSpeed(speedVal)
                        }
                    )
                }
            }
        }

        // 2. 后退 15 秒按钮 (组合 Replay 图标 + 居中 15 数字)
        IconButton(
            onClick = actions.onSkipBack,
            modifier = Modifier.size(skipBtnSize)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Replay,
                    contentDescription = "后退15秒",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(skipBtnSize * 0.75f)
                )
                Text(
                    text = "15",
                    fontSize = (skipBtnSize.value * 0.22f).sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        // 3. 实心大播放键 (缓冲时旋转指示器)
        Box(
            modifier = Modifier
                .size(playBtnSize)
                .clip(CircleShape)
                .background(AppleRed)
                .clickable(onClick = actions.onTogglePlayPause),
            contentAlignment = Alignment.Center
        ) {
            if (ui.isBuffering) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(playBtnSize * 0.5f)
                )
            } else {
                Icon(
                    imageVector = if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (ui.isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(playBtnSize * 0.55f)
                )
            }
        }

        // 4. 前进 30 秒按钮
        IconButton(
            onClick = actions.onSkipForward,
            modifier = Modifier.size(skipBtnSize)
        ) {
            Icon(
                imageVector = Icons.Default.Forward30,
                contentDescription = "快进30秒",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(skipBtnSize * 0.75f)
            )
        }

        // 5. 定时关闭按钮
        Box {
            IconButton(
                onClick = { showSleepMenu = true },
                modifier = Modifier.size(modeBtnSize)
            ) {
                Icon(
                    imageVector = Icons.Default.Timer,
                    contentDescription = "定时关闭",
                    tint = if (ui.sleepTimerMinutes != 0) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(modeBtnSize * 0.65f)
                )
            }

            SleepTimerDropdownMenu(
                expanded = showSleepMenu,
                onDismissRequest = { showSleepMenu = false },
                activeTimerMinutes = ui.sleepTimerMinutes,
                remainingSeconds = ui.sleepTimerRemainingSec,
                onSelectTimer = { minutes ->
                    showSleepMenu = false
                    actions.onSetSleepTimer(minutes)
                }
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000
    val sec = totalSec % 60
    val min = (totalSec / 60) % 60
    val hr = totalSec / 3600
    return if (hr > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hr, min, sec)
    } else {
        String.format(Locale.US, "%02d:%02d", min, sec)
    }
}

// ==========================================
// 4 组预览
// ==========================================

@Preview(name = "播客播放·竖屏浅色", showBackground = true)
@Composable
private fun PodcastPlayerScreenPreviewLight() {
    MaterialTheme {
        PodcastPlayerScreen(
            ui = PodcastPreviewData.samplePlayerUi,
            positionMs = 1104000L, // 18分24秒
            actions = PodcastPlayerActions()
        )
    }
}

@Preview(name = "播客播放·竖屏深色", showBackground = true, backgroundColor = 0xFF101014)
@Composable
private fun PodcastPlayerScreenPreviewDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        PodcastPlayerScreen(
            ui = PodcastPreviewData.samplePlayerUi,
            positionMs = 1104000L,
            actions = PodcastPlayerActions()
        )
    }
}

@Preview(name = "播客播放·横屏浅色", widthDp = 800, heightDp = 360, showBackground = true)
@Composable
private fun PodcastPlayerScreenPreviewLandscapeLight() {
    MaterialTheme {
        PodcastPlayerScreen(
            ui = PodcastPreviewData.samplePlayerUi,
            positionMs = 1104000L,
            actions = PodcastPlayerActions()
        )
    }
}

@Preview(name = "播客播放·横屏深色", widthDp = 800, heightDp = 360, showBackground = true, backgroundColor = 0xFF101014)
@Composable
private fun PodcastPlayerScreenPreviewLandscapeDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        PodcastPlayerScreen(
            ui = PodcastPreviewData.samplePlayerUi,
            positionMs = 1104000L,
            actions = PodcastPlayerActions()
        )
    }
}
