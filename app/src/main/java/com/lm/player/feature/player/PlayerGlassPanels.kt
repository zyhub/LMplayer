package com.lm.player.feature.player

import com.lm.player.core.designsystem.glass.LiquidBottomTab
import android.content.Context
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.offset
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.glass.AdaptiveDropdownMenu
import com.lm.player.core.designsystem.glass.GlassBox
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.media.AudioSharingManager
import com.lm.player.core.model.AudioQuality
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.source.PlaybackSourceTracker
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

// =====================================================================================
// 音质标签：歌名下方一颗液态玻璃标签（原来的「音质 / 码率 / 大小」三个徽章合并），
// 平时只显示音质，点开弹出音频参数面板（原底部工具栏「ⓘ 音频参数详情」按钮已去掉）。
// =====================================================================================

/** 当前歌曲的音质信息（标签与参数面板共用一份，保证两处一致） */
internal data class AudioBadgeInfo(
    /** 标签文字，例如「FLAC 无损」「Hi-Res FLAC」「MP3 极高」 */
    val quality: String,
    val format: String,
    val bitrate: String,
    val size: String,
    /** 3 = Hi-Res，2 = 无损，1 = 极高，0 = 标准 */
    val tier: Int
)

internal fun computeAudioBadgeInfo(context: Context, song: UnifiedSong, totalDurationMs: Long): AudioBadgeInfo {
    val hasLocalFile = !song.localFilePath.isNullOrBlank() &&
        (song.localFilePath.startsWith("content://") || runCatching {
            File(song.localFilePath).let { it.exists() && it.length() > 0L }
        }.getOrDefault(false))
    return if (hasLocalFile) {
        val (realExt, realKbps, realSizeStr) = com.lm.player.feature.home.resolveRealLocalFormatAndSize(song)
        val isLossless = realExt in listOf("FLAC", "WAV", "ALAC", "APE", "DSD", "DSF") || realKbps >= 800
        val tier = when {
            isLossless && realKbps >= 1200 -> 3
            isLossless -> 2
            realKbps >= 320 -> 1
            else -> 0
        }
        val label = when (tier) {
            3 -> "Hi-Res $realExt"
            2 -> "$realExt 无损"
            1 -> "$realExt 极高"
            else -> "$realExt 标准"
        }
        AudioBadgeInfo(label, realExt, "$realKbps kbps", realSizeStr, tier)
    } else {
        val serverFile = com.lm.player.feature.home.resolveServerSongQuality(song)
        val preferredRaw = AudioQuality.fromKey(
            com.lm.player.core.network.LemonMusicProtocol.getPreferredStreamQuality(context)
        )
        // 服务器曲库歌曲：播放音质不会高于服务器上的源文件；源文件音质不高于试听音质时，直接显示源文件的真实参数
        if (serverFile != null && serverFile.first.ordinal <= preferredRaw.ordinal) {
            val q = serverFile.first
            val tier = when (q) {
                AudioQuality.Q_HIRES -> 3
                AudioQuality.Q_FLAC -> 2
                AudioQuality.Q_320K -> 1
                AudioQuality.Q_128K -> 0
            }
            val ext = serverFile.second
            val label = when (tier) {
                3 -> "Hi-Res $ext"
                2 -> "$ext 无损"
                1 -> "$ext 极高"
                else -> "$ext 标准"
            }
            return AudioBadgeInfo(label, ext, "${song.bitRate} kbps", serverFile.third, tier)
        }
        val preferred = preferredRaw
        val (label, tier) = when (preferred) {
            AudioQuality.Q_HIRES -> "Hi-Res FLAC" to 3
            AudioQuality.Q_FLAC -> "FLAC 无损" to 2
            AudioQuality.Q_320K -> "MP3 极高" to 1
            AudioQuality.Q_128K -> "MP3 标准" to 0
        }
        val kbps = when (preferred) {
            AudioQuality.Q_HIRES -> if (song.bitRate > 1200) song.bitRate else 1640
            AudioQuality.Q_FLAC -> if (song.bitRate in 700..1200) song.bitRate else 960
            AudioQuality.Q_320K -> 320
            AudioQuality.Q_128K -> 128
        }
        val durSec = ((if (totalDurationMs > 0) totalDurationMs else song.durationMs) / 1000L).coerceAtLeast(180L)
        val estMb = (durSec * kbps * 1000L / 8L) / (1024.0 * 1024.0)
        AudioBadgeInfo(label, preferred.format.uppercase(), "$kbps kbps", String.format(Locale.US, "%.1f MB", estMb), tier)
    }
}

private fun tierColor(tier: Int, isDark: Boolean): Color = when (tier) {
    3 -> Color(0xFFD4AF37)
    2 -> AppleRed
    1 -> if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)
    else -> if (isDark) Color.White.copy(alpha = 0.70f) else Color(0xFF636366)
}

/** 歌名下方的音质玻璃标签；点开 / 收起参数面板 */
@Composable
internal fun AudioQualityTag(
    info: AudioBadgeInfo,
    glassBackdrop: com.kyant.backdrop.Backdrop?,
    isDark: Boolean,
    opened: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val color = tierColor(info.tier, isDark)
    val h = if (compact) 20.dp else 22.dp
    val shape = RoundedCornerShape(h / 2)
    GlassBox(
        backdrop = glassBackdrop,
        isDark = isDark,
        cornerRadius = h / 2,
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(h)
            .clip(shape)
            .then(if (glassBackdrop == null) Modifier.background(color.copy(alpha = 0.15f)) else Modifier)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(start = 7.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = color, modifier = Modifier.size(if (compact) 11.dp else 12.dp))
            Spacer(Modifier.width(3.dp))
            Text(
                text = info.quality,
                fontSize = if (compact) 9.5.sp else 10.5.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1
            )
            Icon(
                imageVector = if (opened) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (opened) "收起音频参数" else "查看音频参数",
                tint = color.copy(alpha = 0.8f),
                modifier = Modifier.size(if (compact) 12.dp else 14.dp)
            )
        }
    }
}

/**
 * 音频参数面板（玻璃弹出层，锚在音质标签下方 / 上方，左对齐）：
 * 顶部「音质等级」，中间格式 / 码率 / 大小三格，下面是来源（实际取链的音源，过长省略）、位置、输出。
 */
@Composable
internal fun AudioSpecsGlassPanel(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    song: UnifiedSong,
    info: AudioBadgeInfo
) {
    AdaptiveDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.width(288.dp),
        alignStart = true
    ) {
        val isDark = MaterialTheme.colorScheme.background.red < 0.5f
        val onSurface = MaterialTheme.colorScheme.onSurface
        val sub = MaterialTheme.colorScheme.onSurfaceVariant
        val color = tierColor(info.tier, isDark)
        val isLocal = !song.localFilePath.isNullOrBlank()

        // ---------- 解析来源：优先用本次实际取链记录（具体音源脚本名） ----------
        val tracked by PlaybackSourceTracker.state.collectAsState()
        val isOnlineSong = song.id.startsWith("lemon_online_") ||
            song.serverId == "lemon_online" ||
            song.streamUrl.startsWith("lemon_online://") ||
            (com.lm.player.core.media.DownloadRequestPlanner.hasRemoteSource(song) && !isLocal)
        val sourceLabel: String? = if (!isOnlineSong) null else {
            val rawJson = song.rawMetaJson ?: song.relativeFolderPath
            val meta = runCatching { if (!rawJson.isNullOrBlank()) org.json.JSONObject(rawJson) else null }.getOrNull()
            val platformKey = when {
                song.id.startsWith("lemon_online_") -> song.id.removePrefix("lemon_online_").substringBefore("_")
                else -> meta?.optString("source")?.ifBlank { meta.optString("platform") }.orEmpty()
            }.ifBlank { "kw" }
            val t = tracked[song.id]
            val name = t?.sourceName
                ?: meta?.optString("sourceName")?.ifBlank { meta.optString("scriptName") }?.ifBlank { null }
                ?: "柠檬服务器"
            val platform = PlaybackSourceTracker.platformDisplayName(t?.platform ?: platformKey)
            if (platform.isBlank()) name else "$name · $platform"
        }

        val locationText: String = if (isLocal) {
            File(song.localFilePath!!).parent ?: song.localFilePath
        } else {
            var nasPath = ""
            if (song.streamUrl.contains("path=")) {
                runCatching {
                    val pathParam = android.net.Uri.parse(song.streamUrl).getQueryParameter("path")
                    if (!pathParam.isNullOrBlank()) {
                        val decoded = java.net.URLDecoder.decode(pathParam, "UTF-8")
                        nasPath = if (decoded.contains("/")) decoded.substringBeforeLast("/") + "/" else decoded
                    }
                }
            }
            if (nasPath.isBlank() && !song.relativeFolderPath.isNullOrBlank() && !song.relativeFolderPath.trim().startsWith("{")) {
                nasPath = song.relativeFolderPath.trim().trimEnd('/') + "/"
            }
            when {
                isOnlineSong && nasPath.isBlank() -> "在线流媒体"
                nasPath.isBlank() -> "服务器曲库"
                else -> "服务器：" + (if (nasPath.startsWith("/")) nasPath else "/$nasPath")
            }
        }

        val activeRouteName by AudioSharingManager.activeLocalRouteName.collectAsState()
        val activeCast by AudioSharingManager.activeCastDevice.collectAsState()
        val channel = activeCast?.let { "${it.name}（${it.protocol.badge}）" } ?: activeRouteName

        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(color.copy(alpha = 0.18f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = when (info.tier) { 3 -> "Hi-Res"; 2 -> "无损"; 1 -> "极高"; else -> "标准" },
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("音频参数", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = onSurface)
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatCell("格式", info.format, onSurface, sub)
                StatCell("码率", info.bitrate, onSurface, sub)
                StatCell("大小", info.size, onSurface, sub)
            }
            Spacer(Modifier.height(8.dp))
            if (sourceLabel != null) SpecLine("来源", sourceLabel, onSurface, sub, valueColor = AppleRed)
            SpecLine("位置", locationText, onSurface, sub, maxLines = 2)
            SpecLine("输出", channel, onSurface, sub)
        }
    }
}

@Composable
private fun RowScope.StatCell(caption: String, value: String, onSurface: Color, sub: Color) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(onSurface.copy(alpha = 0.06f))
            .padding(vertical = 7.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(text = caption, fontSize = 10.sp, color = sub, maxLines = 1)
    }
}

@Composable
private fun SpecLine(
    label: String,
    value: String,
    onSurface: Color,
    sub: Color,
    maxLines: Int = 1,
    valueColor: Color? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = if (maxLines > 1) Alignment.Top else Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 12.sp, color = sub, modifier = Modifier.width(36.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: onSurface,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

// =====================================================================================
// 待播列表：竖屏改为播放页内的悬浮玻璃卡片（从底部滑上来，封面缩略图 + 当前播放高亮，
// 打开时自动滚到正在播放的那首；点空白处 / 下拉标题栏 / 返回键关闭）。
// =====================================================================================

@Composable
internal fun PlayerQueueOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    playlist: List<UnifiedSong>,
    currentSongId: String,
    glassBackdrop: com.kyant.backdrop.Backdrop?,
    isDark: Boolean,
    primaryTextColor: Color,
    secondaryTextColor: Color,
    onSelect: (UnifiedSong) -> Unit
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (isDark) 0.35f else 0.22f))
                // 遮罩上的上下拖动不要传给播放页（否则会拖动整个播放页下滑关闭）
                .pointerInput(Unit) { detectVerticalDragGestures { change, _ -> change.consume() } }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 420f)) { it } + fadeIn(tween(160)),
        exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(180)),
        modifier = Modifier.fillMaxSize()
    ) {
        val scope = rememberCoroutineScope()
        val dragOffset = remember { Animatable(0f) }
        LaunchedEffect(Unit) { com.lm.player.core.designsystem.glass.GlassRefreshController.keepLive() }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            GlassBox(
                backdrop = glassBackdrop,
                isDark = isDark,
                cornerRadius = 28.dp,
                emphasized = true,
                panel = true,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(0.64f)
                    .graphicsLayer { translationY = dragOffset.value.coerceAtLeast(0f) }
                    .clip(RoundedCornerShape(28.dp))
                    .then(
                        if (glassBackdrop == null) Modifier.background(if (isDark) Color(0xF2202026) else Color(0xF7FFFFFF))
                        else Modifier
                    )
                    // 吃掉卡片上的点击，避免穿透到下面的遮罩把卡片关掉
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 标题栏（可向下拖动关闭）
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onDragEnd = {
                                        if (dragOffset.value > 140f) onDismiss()
                                        scope.launch { dragOffset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f)) }
                                    },
                                    onDragCancel = { scope.launch { dragOffset.animateTo(0f) } }
                                ) { change, dy ->
                                    change.consume()
                                    scope.launch { dragOffset.snapTo((dragOffset.value + dy).coerceAtLeast(0f)) }
                                }
                            }
                            .padding(top = 8.dp, start = 18.dp, end = 10.dp, bottom = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .size(width = 36.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(primaryTextColor.copy(alpha = 0.22f))
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("待播", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = primaryTextColor)
                            Spacer(Modifier.width(8.dp))
                            Text("${playlist.size} 首", fontSize = 12.sp, color = secondaryTextColor, modifier = Modifier.weight(1f))
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(primaryTextColor.copy(alpha = 0.08f))
                                    .clickable(onClick = onDismiss),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = primaryTextColor.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    if (playlist.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("待播队列为空", color = secondaryTextColor, fontSize = 13.sp)
                        }
                    } else {
                        val currentIndex = playlist.indexOfFirst { it.id == currentSongId }.coerceAtLeast(0)
                        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 8.dp, bottom = 12.dp)
                        ) {
                            itemsIndexed(playlist, key = { _, item -> item.id }) { index, item ->
                                QueueSongRow(
                                    index = index,
                                    song = item,
                                    isCurrent = item.id == currentSongId,
                                    primaryTextColor = primaryTextColor,
                                    secondaryTextColor = secondaryTextColor,
                                    onClick = {
                                        onSelect(item)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 待播列表的一行：封面 + 歌名 / 歌手，正在播放的一行用强调色并显示跳动图标（竖屏卡片与横屏右侧共用） */
@Composable
internal fun QueueSongRow(
    index: Int,
    song: UnifiedSong,
    isCurrent: Boolean,
    primaryTextColor: Color,
    secondaryTextColor: Color,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val cover = if (compact) 34.dp else 42.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isCurrent) AppleRed.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = if (compact) 5.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.Center) {
            com.lm.player.core.designsystem.component.AlbumArtworkImage(
                model = song.coverUrl,
                seedId = song.id,
                cornerRadius = 8.dp,
                targetSize = 120,
                modifier = Modifier.size(cover)
            )
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .size(cover)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = "正在播放", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = if (isCurrent) AppleRed else primaryTextColor,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                fontSize = if (compact) 13.sp else 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist.ifBlank { "未知歌手" },
                color = secondaryTextColor,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!isCurrent) {
            Text(
                text = "${index + 1}",
                color = secondaryTextColor.copy(alpha = 0.7f),
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}

// =====================================================================================
// 播放页「歌曲 | 歌词」切换：平时缩成两颗小水珠（当前页那颗更大更亮），
// 点按或左右滑动时像水滴一样展开成分段胶囊（选中段是会跟随滑动的玻璃水滴），最后一次操作 2 秒后自动缩回。
// =====================================================================================

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SongLyricsToggle(
    pagerState: androidx.compose.foundation.pager.PagerState,
    glassBackdrop: com.kyant.backdrop.Backdrop?,
    isDark: Boolean,
    primaryTextColor: Color,
    secondaryTextColor: Color,
    collapsedBackground: Color,
    onSelectPage: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var interactionTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // 玻璃形态的「歌曲 | 歌词」用示例标签栏：按住 / 拖动水滴期间保持展开，松手后重新计时
    val toggleTabsController = remember { com.lm.player.core.designsystem.glass.LiquidBottomTabsController() }
    LaunchedEffect(toggleTabsController) {
        androidx.compose.runtime.snapshotFlow { toggleTabsController.isInteracting }.collect { pressing ->
            if (pressing) expanded = true
            interactionTick++
        }
    }
    // 左右滑动换页：展开
    LaunchedEffect(pagerState) {
        androidx.compose.runtime.snapshotFlow { pagerState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                expanded = true
                interactionTick++
            }
        }
    }
    // 最后一次操作 2 秒后收起（还在滑动时等滑完）
    LaunchedEffect(interactionTick, expanded) {
        if (!expanded) return@LaunchedEffect
        kotlinx.coroutines.delay(2000)
        androidx.compose.runtime.snapshotFlow { pagerState.isScrollInProgress || toggleTabsController.isInteracting }.first { !it }
        expanded = false
    }
    val e by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 240f),
        label = "song_lyrics_toggle"
    )
    LaunchedEffect(expanded) { com.lm.player.core.designsystem.glass.GlassRefreshController.keepLive() }


    val fullW = 136.dp
    val fullH = 34.dp
    val dotsW = 34.dp
    val dotsH = 16.dp
    val w = androidx.compose.ui.unit.lerp(dotsW, fullW, e)
    val h = androidx.compose.ui.unit.lerp(dotsH, fullH, e)
    val isOnLyricsPage = pagerState.currentPage == 1

    // 布局上始终占展开后的大小，顶部一栏保持居中
    Box(modifier = Modifier.size(fullW, fullH), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(w, h)
                // 玻璃时展开到位后不裁切：按住的玻璃水滴要能鼓出胶囊
                .then(if (glassBackdrop == null || e < 0.98f) Modifier.clip(RoundedCornerShape(h / 2)) else Modifier)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    expanded = true
                    interactionTick++
                },
            contentAlignment = Alignment.Center
        ) {
            // 展开后的胶囊底（无玻璃时的底色），随展开淡入；玻璃时胶囊由下面的示例组件自己画
            if (glassBackdrop == null) Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = e.coerceIn(0f, 1f) }
            ) {
                GlassBox(
                    backdrop = glassBackdrop,
                    isDark = isDark,
                    cornerRadius = 17.dp,
                    modifier = Modifier
                        .matchParentSize()
                        .then(if (glassBackdrop == null) Modifier.background(collapsedBackground) else Modifier)
                ) { }
            }
            // 展开内容：玻璃水滴 + 两个分段（按展开后的尺寸排版，收缩时被胶囊裁掉并淡出）
            if (glassBackdrop != null) {
                // 液态玻璃：Kyant0 示例的 LiquidBottomTabs（两格、34dp 高），水滴采样「背景 + 胶囊自己的内容」，不会叠出暗影
                // 性能：常驻组合（展开时不再新建玻璃、编译着色器），收起时不绘制、不响应触摸
                Box(
                    modifier = Modifier
                        .wrapContentSize(unbounded = true)
                        .size(fullW, fullH)
                        .graphicsLayer { alpha = e.coerceIn(0f, 1f) }
                        .drawWithContent { if (e > 0.02f) drawContent() }
                ) {
                    com.lm.player.core.designsystem.glass.LiquidBottomTabs(
                        selectedTabIndex = { pagerState.currentPage },
                        onTabSelected = { i ->
                            if (i != pagerState.currentPage) onSelectPage(i)
                            expanded = true
                            interactionTick++
                        },
                        backdrop = glassBackdrop,
                        tabsCount = 2,
                        accentColor = com.lm.player.core.designsystem.theme.AppleRed,
                        barHeight = fullH,
                        innerPadding = 3.dp,
                        controller = toggleTabsController,
                        active = expanded,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        listOf("歌曲", "歌词").forEachIndexed { i, label ->
                            LiquidBottomTab(
                                onClick = {
                                    onSelectPage(i)
                                    expanded = true
                                    interactionTick++
                                }
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = primaryTextColor,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
            if (e > 0.02f && glassBackdrop == null) {
                Box(
                    modifier = Modifier
                        .wrapContentSize(unbounded = true)
                        .size(fullW, fullH)
                        .graphicsLayer { alpha = ((e - 0.35f) / 0.65f).coerceIn(0f, 1f) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf("歌曲", "歌词").forEachIndexed { i, label ->
                            val selected = (i == 1) == isOnLyricsPage
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(
                                        if (selected && glassBackdrop == null) {
                                            if (isDark) Color(0xFF3A3A42) else Color.White
                                        } else Color.Transparent
                                    )
                                    .clickable {
                                        onSelectPage(i)
                                        expanded = true
                                        interactionTick++
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) primaryTextColor else secondaryTextColor
                                )
                            }
                        }
                    }
                }
            }
            // 收起后的两颗小水珠：从两个分段的位置收拢过来；当前页那颗更大更亮
            if (e < 0.98f) {
                val pos = (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, 1f)
                val dotsAlpha = (1f - e * 1.6f).coerceIn(0f, 1f)
                androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
                    val cy = size.height / 2f
                    val spread = androidx.compose.ui.unit.lerp(6.dp, 34.dp, e).toPx()
                    listOf(0, 1).forEach { i ->
                        val cx = size.width / 2f + (if (i == 0) -spread else spread)
                        val weight = if (i == 0) 1f - pos else pos
                        val radius = (2.6f + 1.4f * weight).dp.toPx()
                        val base = primaryTextColor.copy(alpha = (0.35f + 0.6f * weight) * dotsAlpha)
                        drawCircle(color = base, radius = radius, center = androidx.compose.ui.geometry.Offset(cx, cy))
                        // 水珠高光
                        drawCircle(
                            color = Color.White.copy(alpha = 0.55f * dotsAlpha * (0.4f + 0.6f * weight)),
                            radius = radius * 0.38f,
                            center = androidx.compose.ui.geometry.Offset(cx - radius * 0.32f, cy - radius * 0.32f)
                        )
                    }
                }
            }
        }
    }
}
