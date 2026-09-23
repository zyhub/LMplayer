package com.lm.player.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.window.Dialog
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.model.AudioQuality
import com.lm.player.core.model.DownloadTarget
import com.lm.player.core.model.UnifiedSong
import androidx.compose.ui.text.TextStyle

/**
 * 仿音频输出与共享展出方式的浮动依附式下载菜单 (DropdownMenu)
 * 原位依附于歌曲下载按键，提供存储目标（本地/服务器/双端）与音质选择，一键启动下载
 */
@Composable
fun DownloadQualityDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    song: UnifiedSong,
    isServerConnected: Boolean = true,
    hasLocal: Boolean = false,
    hasServer: Boolean = false,
    initialTarget: DownloadTarget = if (hasServer && !hasLocal) DownloadTarget.LOCAL else if (hasLocal && !hasServer) DownloadTarget.SERVER else DownloadTarget.LOCAL,
    onConfirm: (target: DownloadTarget, quality: AudioQuality) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTarget by remember(expanded, hasLocal, hasServer) {
        mutableStateOf(
            if (hasServer && !hasLocal) DownloadTarget.LOCAL
            else if (hasLocal && !hasServer && isServerConnected) DownloadTarget.SERVER
            else DownloadTarget.LOCAL
        )
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 290.dp, max = 340.dp)
    ) {
        // 1. 顶部标题与歌曲信息
        Text(
            text = "下载与缓存设置",
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
        Text(
            text = "${song.title} · ${song.artist}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        // 2. 存储位置选择器 (已下载的一方置灰不可点，未下载的一方高亮可选)
        Text(
            text = "存储位置",
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val options = listOf(
                Triple(DownloadTarget.LOCAL, if (hasLocal) "📱 已在本地" else "📱 本地", !hasLocal),
                Triple(DownloadTarget.SERVER, if (hasServer) "☁️ 已存服务器" else "☁️ 服务器", isServerConnected && !hasServer),
                Triple(DownloadTarget.BOTH, if (hasLocal && hasServer) "🔄 已双端同步" else "🔄 双端同步", isServerConnected && (!hasLocal || !hasServer))
            )

            options.forEach { (target, label, enabled) ->
                val isSelected = selectedTarget == target && enabled
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                        isSelected -> AppleRed.copy(alpha = 0.15f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) AppleRed else Color.Transparent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled) {
                            selectedTarget = target
                        }
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 10.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                isSelected -> AppleRed
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1
                        )
                    }
                }
            }
        }

        if (!isServerConnected) {
            Text(
                text = "当前未连接柠檬服务器，仅支持缓存至本机",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
            )
        } else if (hasLocal && !hasServer) {
            Text(
                text = "本地已有离线文件，可点击缓存至服务器以双端同步",
                fontSize = 10.sp,
                color = Color(0xFF34C759),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
            )
        } else if (!hasLocal && hasServer) {
            Text(
                text = "服务器已有曲库存储，可点击下载至本地以双端同步",
                fontSize = 10.sp,
                color = Color(0xFF34C759),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
            )
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        // 3. 音质规格选择与一键下载
        Text(
            text = "选择音质并开始下载",
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
        )

        AudioQuality.entries.forEach { quality ->
            val isLossless = quality == AudioQuality.Q_FLAC || quality == AudioQuality.Q_HIRES
            val badgeColor = if (isLossless) AppleRed else Color(0xFF007AFF)

            DropdownMenuItem(
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = badgeColor.copy(alpha = 0.12f),
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text(
                                    text = quality.badge,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeColor,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = quality.label,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${quality.format} · ${quality.bitrate} kbps",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "下载",
                            tint = AppleRed,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                },
                onClick = {
                    onConfirm(selectedTarget, quality)
                    onDismissRequest()
                },
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 1.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        }
    }
}

/**
 * 仿平台切换方式的折叠展开式全局缓存下载对话框
 * 支持：
 * 1. 三种缓存目标（折叠展开选择）：
 *    - 缓存至本地
 *    - 缓存至服务器
 *    - 双方同步缓存
 * 2. 音质选择（128K / 320K / FLAC / Hi-Res）
 */
@Composable
fun DownloadQualityChoiceDialog(
    song: UnifiedSong,
    isServerConnected: Boolean = true,
    initialTarget: DownloadTarget = DownloadTarget.LOCAL,
    initialQuality: AudioQuality = AudioQuality.Q_320K,
    onConfirm: (target: DownloadTarget, quality: AudioQuality) -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val surfaceBg = if (isDark) Color(0xFF222228) else Color.White
    val borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)

    var selectedTarget by remember { mutableStateOf(initialTarget) }
    var selectedQuality by remember { mutableStateOf(initialQuality) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = surfaceBg,
            border = BorderStroke(1.dp, borderColor),
            shadowElevation = 24.dp,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // 1. 顶部 Header (标题 + 歌曲信息 + 关闭按钮)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "选择缓存方式与音质",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${song.title} · ${song.artist}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 2. 缓存目标列表 (如同切换平台方式，支持折叠展开)
                Text(
                    text = "缓存模式",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DownloadTarget.entries.forEach { target ->
                        val isSelected = selectedTarget == target
                        val isServerRelated = target == DownloadTarget.SERVER || target == DownloadTarget.BOTH
                        val isEnabled = !isServerRelated || isServerConnected

                        val icon = when (target) {
                            DownloadTarget.LOCAL -> Icons.Default.PhoneAndroid
                            DownloadTarget.SERVER -> Icons.Default.CloudDownload
                            DownloadTarget.BOTH -> Icons.Default.SyncAlt
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) AppleRed.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) AppleRed else borderColor.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(enabled = isEnabled) {
                                    selectedTarget = target
                                }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 11.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = target.displayName,
                                                fontSize = 14.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = if (isServerRelated && !isServerConnected) "需连接柠檬音乐服务器" else target.desc,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Icon(
                                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 3. 音质规格选择 (折叠展开展示)
                Text(
                    text = "音质规格",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AudioQuality.entries.forEach { quality ->
                        val isSelected = selectedQuality == quality
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) AppleRed.copy(alpha = 0.10f) else Color.Transparent,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) AppleRed else borderColor.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { selectedQuality = quality }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isSelected) AppleRed else MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.padding(end = 10.dp)
                                    ) {
                                        Text(
                                            text = quality.badge,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = quality.label,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "封装格式: ${quality.format} · ${quality.bitrate} kbps",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "已选择",
                                        tint = AppleRed,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // 4. 底部动作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        border = BorderStroke(1.dp, borderColor)
                    ) {
                        Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    }

                    Button(
                        onClick = {
                            onConfirm(selectedTarget, selectedQuality)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                        modifier = Modifier
                            .weight(1.4f)
                            .height(44.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when (selectedTarget) {
                                DownloadTarget.LOCAL -> "开始本地下载"
                                DownloadTarget.SERVER -> "缓存至服务器"
                                DownloadTarget.BOTH -> "双方同步缓存"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/**
 * 半截绿色勾图标 (代表仅单方已存/部分已同步)
 */
@Composable
fun HalfGreenCheckIcon(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 18.dp,
    color: Color = Color(0xFF34C759)
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeW = 1.8.dp.toPx()
            // 绘制绿色半圈 (代表单边同步)
            drawArc(
                color = color,
                startAngle = 90f,
                sweepAngle = 180f,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW)
            )
            drawArc(
                color = color.copy(alpha = 0.25f),
                startAngle = 270f,
                sweepAngle = 180f,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW)
            )
        }
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = "单方已存",
            tint = color,
            modifier = Modifier.size(size * 0.72f)
        )
    }
}

/**
 * 统一的歌曲末尾同步与下载状态操作栏：
 * 1. 双方同步：绿色勾并显示 [服务器 · 本地]
 * 2. 单方下载：半截绿色勾并显示 [仅本地] 或 [仅服务器]，点击弹出置灰单方并展示未下载方的补全下载菜单
 * 3. 双方未下载：常规下载图标，点击展开三端下载菜单
 * 4. 下载中：旋转进度环与百分比
 */
@Composable
fun SongSyncStatusTrailing(
    song: UnifiedSong,
    isDownloading: Boolean,
    downloadProgress: Float,
    isServerConnected: Boolean,
    hasLocal: Boolean,
    hasServer: Boolean,
    onOpenDownloads: () -> Unit,
    onDownloadWithOptions: (UnifiedSong, DownloadTarget, AudioQuality) -> Unit,
    modifier: Modifier = Modifier
) {
    val isBoth = hasLocal && hasServer
    val isOnlyOne = hasLocal xor hasServer
    var isMenuOpen by remember { mutableStateOf(false) }

    if (isDownloading) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = AppleRed.copy(alpha = 0.12f),
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { onOpenDownloads() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.size(16.dp),
                    color = AppleRed,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "${(downloadProgress * 100).toInt()}%",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppleRed
                )
            }
        }
    } else if (isBoth) {
        // 双端同步：绿色勾，并显示存储位置（服务器、本地）
        Box(modifier = modifier) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { isMenuOpen = true }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF34C759).copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "服务器 · 本地",
                        color = Color(0xFF34C759),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "双端同步已存储",
                    tint = Color(0xFF34C759),
                    modifier = Modifier.size(20.dp)
                )
            }

            DownloadQualityDropdownMenu(
                expanded = isMenuOpen,
                onDismissRequest = { isMenuOpen = false },
                song = song,
                isServerConnected = isServerConnected,
                hasLocal = true,
                hasServer = true,
                onConfirm = { target, quality ->
                    isMenuOpen = false
                    onDownloadWithOptions(song, target, quality)
                }
            )
        }
    } else if (isOnlyOne) {
        // 仅存在一方下载：半截绿色勾，并显示存储位置；点击后显示未下载的一方，已下载的一方显示灰色
        Box(modifier = modifier) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { isMenuOpen = true }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF34C759).copy(alpha = 0.10f)
                ) {
                    Text(
                        text = if (hasLocal) "仅本地" else "仅服务器",
                        color = Color(0xFF34C759),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                HalfGreenCheckIcon(
                    size = 18.dp,
                    color = Color(0xFF34C759)
                )
            }

            DownloadQualityDropdownMenu(
                expanded = isMenuOpen,
                onDismissRequest = { isMenuOpen = false },
                song = song,
                isServerConnected = isServerConnected,
                hasLocal = hasLocal,
                hasServer = hasServer,
                onConfirm = { target, quality ->
                    isMenuOpen = false
                    onDownloadWithOptions(song, target, quality)
                }
            )
        }
    } else {
        // 双方均未下载：显示下载按键
        Box(modifier = modifier) {
            IconButton(
                onClick = { isMenuOpen = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowCircleDown,
                    contentDescription = "下载歌曲",
                    tint = AppleRed,
                    modifier = Modifier.size(22.dp)
                )
            }

            DownloadQualityDropdownMenu(
                expanded = isMenuOpen,
                onDismissRequest = { isMenuOpen = false },
                song = song,
                isServerConnected = isServerConnected,
                hasLocal = false,
                hasServer = false,
                onConfirm = { target, quality ->
                    isMenuOpen = false
                    onDownloadWithOptions(song, target, quality)
                }
            )
        }
    }
}
