package com.lm.player.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.model.UnifiedPlaylist

// =====================================================================================
// 「全部歌单」统一卡片：方形封面 + 下方标题 / 副标题；特色歌单、普通歌单、新建入口一个尺寸，网格整齐对齐
// =====================================================================================

private val TileShape = RoundedCornerShape(16.dp)

/** 通用歌单卡片：[artwork] 画在方形区域里 */
@Composable
internal fun PlaylistTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitleLeading: (@Composable () -> Unit)? = null,
    artwork: @Composable BoxScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(bottom = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(6.dp, TileShape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
                .clip(TileShape),
            content = artwork
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
            subtitleLeading?.invoke()
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 特色歌单（我喜欢 / 最近播放 / 本地下载）的封面：渐变底 + 居中大图标 + 左上柔光 */
@Composable
internal fun BoxScope.SpecialPlaylistArtwork(icon: ImageVector, gradient: List<Color>) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(Brush.linearGradient(gradient))
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                        center = Offset(size.width * 0.18f, size.height * 0.12f),
                        radius = size.minDimension * 0.75f
                    ),
                    radius = size.minDimension * 0.75f,
                    center = Offset(size.width * 0.18f, size.height * 0.12f)
                )
            }
    )
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier
            .align(Alignment.Center)
            .size(44.dp)
    )
}

/** 普通歌单封面：四宫格 + 云端角标 */
@Composable
internal fun BoxScope.PlaylistGridArtwork(playlist: UnifiedPlaylist, covers: List<String>) {
    val displayCovers = remember(covers, playlist.previewCovers) { covers.ifEmpty { playlist.previewCovers } }
    PlaylistFourGridCover(
        covers = displayCovers,
        fallbackCoverUrl = playlist.coverUrl,
        seedId = playlist.id,
        modifier = Modifier.matchParentSize(),
        cornerRadius = 0.dp
    )
    if (playlist.isOnline) {
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.42f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Cloud, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(3.dp))
            Text("云端", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 新建歌单入口的封面：虚线圆角框 + 加号 */
@Composable
internal fun BoxScope.CreatePlaylistArtwork() {
    val line = AppleRed.copy(alpha = 0.55f)
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(AppleRed.copy(alpha = 0.06f))
            .drawBehind {
                val inset = 1.5.dp.toPx()
                drawRoundRect(
                    color = line,
                    topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                    cornerRadius = CornerRadius(15.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                )
            }
    )
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .size(52.dp)
            .clip(CircleShape)
            .background(AppleRed),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Add, contentDescription = "新建歌单", tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

/** 全部歌单里的分组标题（占满一整行） */
@Composable
internal fun PlaylistSectionHeader(title: String, count: Int? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp, start = 2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text("$count", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 1.dp))
        }
    }
}

// =====================================================================================
// 新建歌单弹窗
// =====================================================================================

private const val NAME_MAX = 30

/**
 * 新建歌单（精简版，液态玻璃高档时是玻璃面板）：标题 → 名称（自动聚焦、回车创建）→「本地 | 云端」分段 → 取消 / 创建。
 * 没连服务器时「云端」置灰。
 */
@Composable
internal fun CreatePlaylistDialog(
    name: String,
    onNameChange: (String) -> Unit,
    online: Boolean,
    onOnlineChange: (Boolean) -> Unit,
    serverAvailable: Boolean,
    onDismiss: () -> Unit,
    onCreate: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val onSurface = MaterialTheme.colorScheme.onSurface
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    val fieldBg = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)
    val focus = remember { FocusRequester() }
    val canCreate = name.isNotBlank()
    val cloud = online && serverAvailable
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        com.lm.player.core.designsystem.glass.GlassDialogPanel(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .widthIn(max = 320.dp),
            cornerRadius = 22.dp
        ) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
                Text("新建歌单", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = onSurface)
                Spacer(Modifier.height(12.dp))
                // 名称
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(fieldBg)
                        .border(1.dp, if (canCreate) AppleRed.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (name.isEmpty()) Text("歌单名称", fontSize = 14.sp, color = sub.copy(alpha = 0.7f))
                        BasicTextField(
                            value = name,
                            onValueChange = { onNameChange(it.take(NAME_MAX)) },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 14.sp, color = onSurface, fontWeight = FontWeight.Medium),
                            cursorBrush = SolidColor(AppleRed),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (canCreate) onCreate() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus)
                        )
                    }
                    if (name.isNotEmpty()) Text("${name.length}/$NAME_MAX", fontSize = 10.sp, color = sub.copy(alpha = 0.7f))
                }

                Spacer(Modifier.height(10.dp))
                // 本地 | 云端
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(fieldBg)
                        .padding(3.dp)
                ) {
                    listOf(false to "本地歌单", true to (if (serverAvailable) "云端歌单" else "云端（未连接）")).forEach { (isCloud, label) ->
                        val selected = isCloud == cloud
                        val enabled = !isCloud || serverAvailable
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) AppleRed else Color.Transparent)
                                .then(if (enabled) Modifier.clickable { onOnlineChange(isCloud) } else Modifier),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isCloud) Icons.Default.Cloud else Icons.Default.PhoneAndroid,
                                contentDescription = null,
                                tint = when {
                                    selected -> Color.White
                                    !enabled -> sub.copy(alpha = 0.4f)
                                    else -> sub
                                },
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = when {
                                    selected -> Color.White
                                    !enabled -> sub.copy(alpha = 0.4f)
                                    else -> onSurface
                                },
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(19.dp))
                            .background(fieldBg)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("取消", fontSize = 14.sp, color = onSurface, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(19.dp))
                            .background(if (canCreate) AppleRed else AppleRed.copy(alpha = 0.3f))
                            .then(if (canCreate) Modifier.clickable(onClick = onCreate) else Modifier),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("创建", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
