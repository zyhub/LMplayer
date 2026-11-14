package com.lm.player.feature.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.podcast.EpisodePlayState
import com.lm.player.core.podcast.PodcastActions
import com.lm.player.core.podcast.PodcastEpisodeUi

/**
 * 单集详情 / Shownotes 全屏抽屉弹层
 *
 * 包含：单集大标题、所属节目快捷入口、时长与进度、播放/暂停、标记已听完以及支持自由划词复制的 Shownotes 全文。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastEpisodeSheet(
    episode: PodcastEpisodeUi,
    actions: PodcastActions,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPlayed = episode.playState == EpisodePlayState.PLAYED

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            // 头部：封面 + 节目名 + 单集名 + 关闭按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                AlbumArtworkImage(
                    model = episode.imageUrl,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    cornerRadius = 12.dp,
                    seedId = episode.id,
                    targetSize = 200
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // 所属节目入口 (点击可跳往节目详情)
                    Text(
                        text = "${episode.showTitle} ›",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppleRed,
                        modifier = Modifier.clickable {
                            onDismiss()
                            actions.onOpenShow(episode.showId)
                        }
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = episode.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 20.sp
                    )

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = listOf(episode.publishedText, episode.durationText)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 进度条与剩余时间 (听了一部分时展示)
            if (episode.playState == EpisodePlayState.IN_PROGRESS && episode.progress in 0.01f..0.99f) {
                Spacer(modifier = Modifier.height(14.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "收听进度 ${(episode.progress * 100).toInt()}%",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (episode.remainingText.isNotBlank()) {
                            Text(
                                text = episode.remainingText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = AppleRed
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    com.lm.player.core.designsystem.component.ClassicLinearProgress(
                        progress = { episode.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = AppleRed,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 快捷操作按钮行：[播放/暂停] + [标记已听完]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        actions.onPlayEpisode(episode)
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (episode.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (episode.isPlaying) "暂停"
                        else if (episode.playState == EpisodePlayState.IN_PROGRESS) "继续收听"
                        else "播放单集",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                OutlinedButton(
                    onClick = {
                        actions.onMarkPlayed(episode, !isPlayed)
                    },
                    shape = RoundedCornerShape(16.dp),
                    // 已听完时淡绿色（Claude 按用户要求修改），再点取消
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isPlayed) Color(0xFF34C759).copy(alpha = 0.16f) else Color.Transparent,
                        contentColor = if (isPlayed) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = if (isPlayed) Icons.Default.CheckCircle else Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isPlayed) "已听完" else "标记已听完",
                        fontSize = 12.5.sp,
                        fontWeight = if (isPlayed) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                thickness = 0.8.dp
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Shownotes 全文展示区 (支持划词长按复制)
            Text(
                text = "单集简介 / Shownotes",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            SelectionContainer(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = episode.description.ifBlank { "本单集暂无文字简介。" },
                    fontSize = 13.5.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
                )
            }
        }
    }
}

// ==========================================
// 4 组预览
// ==========================================

@Preview(name = "单集弹层·竖屏浅色", showBackground = true)
@Composable
private fun PodcastEpisodeSheetPreviewLight() {
    MaterialTheme {
        PodcastEpisodeSheet(
            episode = PodcastPreviewData.sampleEpisode1,
            actions = PodcastActions(),
            onDismiss = {}
        )
    }
}

@Preview(name = "单集弹层·竖屏深色", showBackground = true, backgroundColor = 0xFF101014)
@Composable
private fun PodcastEpisodeSheetPreviewDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        PodcastEpisodeSheet(
            episode = PodcastPreviewData.sampleEpisode1,
            actions = PodcastActions(),
            onDismiss = {}
        )
    }
}

@Preview(name = "单集弹层·横屏浅色", widthDp = 800, heightDp = 360, showBackground = true)
@Composable
private fun PodcastEpisodeSheetPreviewLandscapeLight() {
    MaterialTheme {
        PodcastEpisodeSheet(
            episode = PodcastPreviewData.sampleEpisode1,
            actions = PodcastActions(),
            onDismiss = {}
        )
    }
}

@Preview(name = "单集弹层·横屏深色", widthDp = 800, heightDp = 360, showBackground = true, backgroundColor = 0xFF101014)
@Composable
private fun PodcastEpisodeSheetPreviewLandscapeDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        PodcastEpisodeSheet(
            episode = PodcastPreviewData.sampleEpisode1,
            actions = PodcastActions(),
            onDismiss = {}
        )
    }
}
