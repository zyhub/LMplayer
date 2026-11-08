package com.lm.player.feature.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.podcast.EpisodePlayState
import com.lm.player.core.podcast.PodcastEpisodeUi

/**
 * 通用单集行组件
 *
 * 适用于：播客主页「最新单集」、节目详情页「单集列表」、播客全屏播放页「待播单集」。
 *
 * @param episode 单集数据模型
 * @param onPlayClick 点击播放/暂停
 * @param onClick 点击整行（打开单集详情/Shownotes 弹层）
 * @param showShowTitle 是否显示所属节目名（在节目详情页中通常设为 false）
 * @param onMarkPlayed 切换已听完/未听完状态的回调（非空时在溢出菜单中展示）
 * @param onOpenDetail 查看单集详情回调
 */
@Composable
fun PodcastEpisodeRow(
    episode: PodcastEpisodeUi,
    onPlayClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showShowTitle: Boolean = true,
    onMarkPlayed: ((Boolean) -> Unit)? = null,
    onOpenDetail: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    val isPlayed = episode.playState == EpisodePlayState.PLAYED
    val primaryTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isPlayed) 0.55f else 1f)
    val secondaryTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isPlayed) 0.45f else 0.85f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. 封面 (52dp 紧凑规格)
        Box(modifier = Modifier.size(52.dp)) {
            AlbumArtworkImage(
                model = episode.imageUrl,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp)),
                cornerRadius = 10.dp,
                seedId = episode.id,
                targetSize = 160
            )

            // 「新」标记角标
            if (episode.isNew && !isPlayed) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(3.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(AppleRed)
                        .padding(horizontal = 3.5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "新",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 2. 标题与元数据
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 4.dp)
        ) {
            // 单集标题 (最多2行)
            Text(
                text = episode.title,
                fontSize = 14.sp,
                fontWeight = if (episode.isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                color = if (episode.isCurrent) AppleRed else primaryTextColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(3.dp))

            // 节目名 · 发布日期 · 时长 · 剩余时间
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 已听完：绿色「已听」标志（Claude 按用户要求补充）
                if (isPlayed) {
                    Surface(shape = RoundedCornerShape(5.dp), color = Color(0xFF34C759).copy(alpha = 0.16f)) {
                        Row(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color(0xFF34C759),
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text("已听", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF34C759))
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
                if (showShowTitle && episode.showTitle.isNotBlank()) {
                    Text(
                        text = episode.showTitle,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (episode.isCurrent) AppleRed.copy(alpha = 0.85f) else secondaryTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = " · ",
                        fontSize = 11.5.sp,
                        color = secondaryTextColor
                    )
                }

                if (episode.publishedText.isNotBlank()) {
                    Text(
                        text = episode.publishedText,
                        fontSize = 11.sp,
                        color = secondaryTextColor
                    )
                    Text(
                        text = " · ",
                        fontSize = 11.sp,
                        color = secondaryTextColor
                    )
                }

                Text(
                    text = if (episode.playState == EpisodePlayState.IN_PROGRESS && episode.remainingText.isNotBlank()) {
                        episode.remainingText
                    } else if (episode.durationText.isNotBlank()) {
                        episode.durationText
                    } else if (isPlayed) {
                        "已听完"
                    } else {
                        "单集"
                    },
                    fontSize = 11.sp,
                    fontWeight = if (episode.playState == EpisodePlayState.IN_PROGRESS) FontWeight.Medium else FontWeight.Normal,
                    color = if (episode.playState == EpisodePlayState.IN_PROGRESS) AppleRed else secondaryTextColor
                )
            }

            // 收听中轻量进度条
            if (episode.playState == EpisodePlayState.IN_PROGRESS && episode.progress in 0.01f..0.99f) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { episode.progress },
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(2.5.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = AppleRed,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }

        // 3. 播放控制按键
        IconButton(
            onClick = onPlayClick,
            modifier = Modifier.size(36.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        if (episode.isPlaying) AppleRed
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (episode.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (episode.isPlaying) "暂停" else "播放",
                    tint = if (episode.isPlaying) Color.White else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // 4. 更多菜单 (如果提供了回调)
        if (onMarkPlayed != null || onOpenDetail != null) {
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "更多操作",
                        tint = secondaryTextColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    if (onOpenDetail != null) {
                        DropdownMenuItem(
                            text = { Text("查看详情 (Shownotes)") },
                            onClick = {
                                showMenu = false
                                onOpenDetail()
                            }
                        )
                    }
                    if (onMarkPlayed != null) {
                        DropdownMenuItem(
                            text = { Text(if (isPlayed) "标记为未听" else "标记为已听完") },
                            onClick = {
                                showMenu = false
                                onMarkPlayed(!isPlayed)
                            }
                        )
                    }
                }
            }
        }
    }
}
