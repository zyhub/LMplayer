package com.lm.player.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
 * 简洁美观的浮动依附式下载菜单 (DropdownMenu)
 * 原位依附于歌曲后方下载按键，提供存储目标（本地下载/服务器下载/双端下载）与音质规格一键下载
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val savedDefaultTarget = remember(context, expanded) {
        val raw = context.getSharedPreferences("lemon_settings_prefs", android.content.Context.MODE_PRIVATE)
            .getString("default_download_target", DownloadTarget.LOCAL.name) ?: DownloadTarget.LOCAL.name
        runCatching { DownloadTarget.valueOf(raw) }.getOrDefault(DownloadTarget.LOCAL)
    }
    val isOnlyOneDownloaded = hasLocal xor hasServer
    var selectedTarget by remember(expanded, hasLocal, hasServer, isServerConnected, savedDefaultTarget) {
        mutableStateOf(
            when {
                hasServer && !hasLocal -> DownloadTarget.LOCAL
                hasLocal && !hasServer && isServerConnected -> DownloadTarget.SERVER
                !isServerConnected -> DownloadTarget.LOCAL
                else -> savedDefaultTarget
            }
        )
    }

    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val menuBg = if (isDark) Color(0xFF1F1F26) else Color(0xFFFCFCFE)
    val subtleCardBg = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)

    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            surface = menuBg,
            surfaceVariant = menuBg
        ),
        shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(18.dp))
    ) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier
                .width(276.dp)
                .background(menuBg, RoundedCornerShape(18.dp))
                .border(0.8.dp, borderColor, RoundedCornerShape(18.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                // 1. 顶部紧凑歌曲信息标题栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = song.title,
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = song.artist,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 96.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 2. 存储目标分段胶囊栏：
                // 当有一端已经下载时，仅显示未下载的那一端 + 保持灰色的「双端下载」
                val targetOptions = remember(hasLocal, hasServer, isServerConnected, isOnlyOneDownloaded) {
                    when {
                        hasLocal && !hasServer -> listOf(
                            Triple(DownloadTarget.SERVER, "☁️ 服务器下载", isServerConnected),
                            Triple(DownloadTarget.BOTH, "🔄 双端下载", false)
                        )
                        !hasLocal && hasServer -> listOf(
                            Triple(DownloadTarget.LOCAL, "📱 本地下载", true),
                            Triple(DownloadTarget.BOTH, "🔄 双端下载", false)
                        )
                        else -> listOf(
                            Triple(DownloadTarget.LOCAL, "📱 本地下载", true),
                            Triple(DownloadTarget.SERVER, "☁️ 服务器下载", isServerConnected),
                            Triple(DownloadTarget.BOTH, "🔄 双端下载", isServerConnected)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = subtleCardBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        targetOptions.forEach { (target, label, enabled) ->
                            val isSelected = selectedTarget == target && enabled
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = when {
                                    !enabled -> Color.Transparent
                                    isSelected -> AppleRed
                                    else -> Color.Transparent
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = enabled) {
                                        selectedTarget = target
                                    }
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = when {
                                            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                            isSelected -> Color.White
                                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 3. 简洁音质选择列表（括号内显示预计下载大小，去除下方重复的格式-码率行）
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    AudioQuality.entries.forEach { quality ->
                        val isLossless = quality == AudioQuality.Q_FLAC || quality == AudioQuality.Q_HIRES
                        val badgeColor = if (isLossless) AppleRed else Color(0xFF007AFF)

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = subtleCardBg,
                            border = BorderStroke(0.5.dp, borderColor),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    onConfirm(selectedTarget, quality)
                                    onDismissRequest()
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(5.dp),
                                        color = badgeColor.copy(alpha = 0.14f),
                                        modifier = Modifier.width(46.dp)
                                    ) {
                                        Text(
                                            text = quality.badge,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = badgeColor,
                                            maxLines = 1,
                                            modifier = Modifier
                                                .padding(vertical = 2.dp)
                                                .wrapContentWidth(Alignment.CenterHorizontally)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = quality.labelWithSize(song),
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "下载",
                                    tint = AppleRed,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 全局缓存下载对话框
 * 支持：
 * 1. 存储目标（本地下载 / 服务器下载 / 双端下载；当有一端已下载时，双端下载保持灰色，仅显示未下载的一端）
 * 2. 音质选择（括号内显示文件大小，无冗余重复码率行）
 */
@Composable
fun DownloadQualityChoiceDialog(
    song: UnifiedSong,
    isServerConnected: Boolean = true,
    hasLocal: Boolean = (song.downloadStatus == com.lm.player.core.model.DownloadStatus.DOWNLOADED) ||
            (!song.localFilePath.isNullOrBlank()) ||
            (song.serverId in listOf("local_storage", "local_folder", "local_saf")),
    hasServer: Boolean = (song.serverId == "lemon_music" ||
            (isServerConnected && song.serverId.isNotBlank() && song.serverId !in listOf("local_storage", "local_folder", "local_saf", "lemon_online"))),
    initialTarget: DownloadTarget = if (hasServer && !hasLocal) DownloadTarget.LOCAL else if (hasLocal && !hasServer && isServerConnected) DownloadTarget.SERVER else DownloadTarget.LOCAL,
    initialQuality: AudioQuality = AudioQuality.Q_320K,
    onConfirm: (target: DownloadTarget, quality: AudioQuality) -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val surfaceBg = if (isDark) Color(0xFF222228) else Color.White
    val borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)

    val context = androidx.compose.ui.platform.LocalContext.current
    val (savedDefaultTarget, savedDefaultQuality) = remember(context) {
        val prefs = context.getSharedPreferences("lemon_settings_prefs", android.content.Context.MODE_PRIVATE)
        val rawTarget = prefs.getString("default_download_target", initialTarget.name) ?: initialTarget.name
        val rawQuality = prefs.getString("default_download_quality", initialQuality.key) ?: initialQuality.key
        val t = runCatching { DownloadTarget.valueOf(rawTarget) }.getOrDefault(initialTarget)
        val q = AudioQuality.fromKey(rawQuality)
        t to q
    }

    var selectedTarget by remember(hasLocal, hasServer, isServerConnected, savedDefaultTarget) {
        mutableStateOf(
            when {
                hasServer && !hasLocal -> DownloadTarget.LOCAL
                hasLocal && !hasServer && isServerConnected -> DownloadTarget.SERVER
                !isServerConnected -> DownloadTarget.LOCAL
                else -> savedDefaultTarget
            }
        )
    }
    var selectedQuality by remember(savedDefaultQuality) { mutableStateOf(savedDefaultQuality) }

    val visibleTargets = remember(hasLocal, hasServer) {
        when {
            hasLocal && !hasServer -> listOf(DownloadTarget.SERVER, DownloadTarget.BOTH)
            !hasLocal && hasServer -> listOf(DownloadTarget.LOCAL, DownloadTarget.BOTH)
            else -> DownloadTarget.entries.toList()
        }
    }

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
                            text = "下载与音质选择",
                            fontSize = 17.sp,
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

                Spacer(modifier = Modifier.height(14.dp))

                // 2. 存储位置选择
                Text(
                    text = "存储位置",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    visibleTargets.forEach { target ->
                        val isServerRelated = target == DownloadTarget.SERVER || target == DownloadTarget.BOTH
                        val isBothDisabledBecauseOneSideDone = (target == DownloadTarget.BOTH) && (hasLocal xor hasServer)
                        val isEnabled = (!isServerRelated || isServerConnected) && !isBothDisabledBecauseOneSideDone
                        val isSelected = selectedTarget == target && isEnabled

                        val icon = when (target) {
                            DownloadTarget.LOCAL -> Icons.Default.PhoneAndroid
                            DownloadTarget.SERVER -> Icons.Default.CloudDownload
                            DownloadTarget.BOTH -> Icons.Default.SyncAlt
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = when {
                                !isEnabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                                isSelected -> AppleRed.copy(alpha = 0.12f)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            },
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) AppleRed else borderColor.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = isEnabled) {
                                    selectedTarget = target
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = when {
                                        !isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                        isSelected -> AppleRed
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = target.displayName,
                                    fontSize = 12.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = when {
                                        !isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                        isSelected -> AppleRed
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 3. 音质规格选择（括号内显示预计文件大小，去除下方重复音质-码率）
                Text(
                    text = "音质规格",
                    fontSize = 12.sp,
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
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isSelected) AppleRed else MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier
                                            .padding(end = 10.dp)
                                            .width(48.dp)
                                    ) {
                                        Text(
                                            text = quality.badge,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .padding(vertical = 2.dp)
                                                .wrapContentWidth(Alignment.CenterHorizontally)
                                        )
                                    }
                                    Text(
                                        text = quality.labelWithSize(song),
                                        fontSize = 13.5.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurface
                                    )
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
                                DownloadTarget.SERVER -> "开始服务器下载"
                                DownloadTarget.BOTH -> "开始双端下载"
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

/**
 * 批量下载与缓存选择对话框 (支持多选、全选歌曲一键下载到本地/服务器/双端同步)
 */
@Composable
fun BatchDownloadQualityChoiceDialog(
    selectedCount: Int,
    isServerConnected: Boolean = true,
    initialTarget: DownloadTarget = DownloadTarget.LOCAL,
    initialQuality: AudioQuality = AudioQuality.Q_320K,
    onConfirm: (target: DownloadTarget, quality: AudioQuality) -> Unit,
    onDismiss: () -> Unit
) {
    val dummyBatchSong = remember(selectedCount) {
        UnifiedSong(
            id = "batch_download_$selectedCount",
            title = "已选中 $selectedCount 首歌曲",
            artist = "批量下载队列",
            album = "",
            durationMs = 215_000L,
            coverUrl = "",
            streamUrl = "",
            serverId = "lemon_online"
        )
    }
    DownloadQualityChoiceDialog(
        song = dummyBatchSong,
        isServerConnected = isServerConnected,
        hasLocal = false,
        hasServer = false,
        initialTarget = initialTarget,
        initialQuality = initialQuality,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

/**
 * 判断列表项歌曲是否为当前正在播放的歌曲 (支持 ID 精确匹配及跨端同版本精确匹配，杜绝 Live/黑胶/伴奏/不同版本误高亮)
 */
fun isSamePlayingSong(song: UnifiedSong, currentPlayingSong: UnifiedSong?): Boolean {
    if (currentPlayingSong == null) return false
    if (song.id == currentPlayingSong.id) return true
    if (!song.localFilePath.isNullOrBlank() && song.localFilePath == currentPlayingSong.localFilePath) {
        return com.lm.player.core.media.SongMatchingResolver.isSongMatch(
            title1 = song.title,
            artist1 = song.artist,
            durationMs1 = song.durationMs,
            title2 = currentPlayingSong.title,
            artist2 = currentPlayingSong.artist,
            durationMs2 = currentPlayingSong.durationMs,
            album1 = song.album,
            album2 = currentPlayingSong.album
        )
    }
    if (!song.streamUrl.isNullOrBlank() &&
        !song.streamUrl.startsWith("lemon_online://") &&
        song.streamUrl == currentPlayingSong.streamUrl
    ) {
        return com.lm.player.core.media.SongMatchingResolver.isSongMatch(
            title1 = song.title,
            artist1 = song.artist,
            durationMs1 = song.durationMs,
            title2 = currentPlayingSong.title,
            artist2 = currentPlayingSong.artist,
            durationMs2 = currentPlayingSong.durationMs,
            album1 = song.album,
            album2 = currentPlayingSong.album
        )
    }
    // 若两首均为在线搜索/发现曲目但 ID 不同，或同属一个服务端曲库但 ID 不同，则必定为列表中的不同条目，绝不交叉高亮
    val bothOnline = song.id.startsWith("lemon_online_") && currentPlayingSong.id.startsWith("lemon_online_")
    if (bothOnline) return false
    val bothSameServer = song.serverId.isNotBlank() &&
            song.serverId == currentPlayingSong.serverId &&
            song.serverId != "lemon_online"
    if (bothSameServer) return false

    return com.lm.player.core.media.SongMatchingResolver.isSongMatch(
        title1 = song.title,
        artist1 = song.artist,
        durationMs1 = song.durationMs,
        title2 = currentPlayingSong.title,
        artist2 = currentPlayingSong.artist,
        durationMs2 = currentPlayingSong.durationMs,
        album1 = song.album,
        album2 = currentPlayingSong.album
    )
}

/**
 * 歌曲后方正在播放动态跳动音波标志 (NowPlayingWaveIndicator)
 */
@Composable
fun NowPlayingWaveIndicator(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    color: Color = AppleRed
) {
    // 后台时不必继续跑动画：rememberInfiniteTransition 一旦创建就会持续逐帧驱动，
    // 此前它**无条件创建**且不看 isPlaying —— 暂停播放或应用退到后台后仍有 4 条
    // 无限动画在跑（420/360/480/390ms 各一条），纯属耗电。
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var isAppResumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> isAppResumed = true
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> isAppResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val shouldAnimate = isPlaying && isAppResumed
    if (!shouldAnimate) {
        // 静止态：直接画固定高度，不创建任何动画
        StaticWaveIndicator(color = color, modifier = modifier)
        return
    }
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "now_playing_wave")
    val bar1 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "bar1"
    )
    val bar2 by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 0.30f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(360, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "bar2"
    )
    val bar3 by infiniteTransition.animateFloat(
        initialValue = 0.40f,
        targetValue = 0.95f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(480, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "bar3"
    )
    val bar4 by infiniteTransition.animateFloat(
        initialValue = 0.75f,
        targetValue = 0.25f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(390, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "bar4"
    )

    val heights = listOf(bar1, bar2, bar3, bar4)
    WaveBars(heights = heights, color = color, modifier = modifier)
}

/** 静止态音波（未播放或应用在后台）：直接画固定高度，不创建任何无限动画 */
@Composable
private fun StaticWaveIndicator(color: Color, modifier: Modifier = Modifier) {
    WaveBars(heights = listOf(0.35f, 0.65f, 0.45f, 0.30f), color = color, modifier = modifier)
}

@Composable
private fun WaveBars(heights: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .height(14.dp),
            horizontalArrangement = Arrangement.spacedBy(2.2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            heights.forEach { ratio ->
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .width(2.4.dp)
                        .fillMaxHeight(ratio.coerceIn(0.2f, 1f))
                        .clip(RoundedCornerShape(1.2.dp))
                        .background(color)
                )
            }
        }
    }
}

