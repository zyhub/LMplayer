package com.lm.player.feature.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LyricTheme
import com.lm.player.core.model.LyricLine

@Composable
fun LyricsScrollingView(
    lyrics: List<LyricLine>,
    currentPositionMs: Long,
    onSeekToLyric: (Long) -> Unit,
    fontSizeSp: Float = 22f,
    lyricsOffsetMs: Long = 0L,
    lyricTheme: LyricTheme = LyricTheme.APPLE_MUSIC,
    onFontSizeChange: (Float) -> Unit = {},
    onOffsetChange: (Long) -> Unit = {},
    onThemeChange: (LyricTheme) -> Unit = {},
    showAdjustButton: Boolean = true,
    modifier: Modifier = Modifier
) {
    val cleanLyrics = remember(lyrics) {
        lyrics.filter { it.text.isNotBlank() && !it.text.trim().equals("null", ignoreCase = true) }
    }

    var showAdjustDialog by remember { mutableStateOf(false) }
    val isDark = isSystemInDarkTheme()

    val activeColor = if (isDark) lyricTheme.activeColorDark else lyricTheme.activeColorLight
    val inactiveColor = if (isDark) lyricTheme.inactiveColorDark else lyricTheme.inactiveColorLight

    if (cleanLyrics.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "♪ 纯音乐 / 暂无歌词 ♪",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = activeColor
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "享受纯净无损音乐时光",
                    fontSize = 12.sp,
                    color = inactiveColor
                )
            }
        }
        return
    }

    val listState = rememberLazyListState()

    // 结合用户快慢偏置时间计算有效播放时间戳
    val effectivePositionMs = (currentPositionMs + lyricsOffsetMs).coerceAtLeast(0L)

    // 计算当前处于哪一行歌词 (依据当前时间戳匹配对应行)
    val activeIndex = remember(cleanLyrics, effectivePositionMs) {
        val index = cleanLyrics.indexOfLast { it.timestampMs <= effectivePositionMs }
        if (index >= 0) index else 0
    }

    // 伴随播放进度自动丝滑居中滚动至当前歌词 (用户手动拖动歌词时不抢夺手势)
    LaunchedEffect(activeIndex) {
        if (cleanLyrics.isNotEmpty() && activeIndex in cleanLyrics.indices && !listState.isScrollInProgress) {
            listState.animateScrollToItem(
                index = (activeIndex - 1).coerceAtLeast(0),
                scrollOffset = 0
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy((fontSizeSp * 0.95f).dp)
        ) {
            itemsIndexed(
                items = cleanLyrics,
                key = { index, item -> "${item.timestampMs}_$index" },
                contentType = { _, _ -> "lyric_item" }
            ) { index, item ->
                val isActive = index == activeIndex
                val textColor by animateColorAsState(
                    targetValue = if (isActive) activeColor else inactiveColor,
                    animationSpec = tween(durationMillis = 280),
                    label = "lyric_color"
                )
                val currentSize = if (isActive) fontSizeSp.sp else (fontSizeSp * 0.74f).sp
                val fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal

                Text(
                    text = item.text,
                    style = TextStyle(
                        fontSize = currentSize,
                        fontWeight = fontWeight,
                        color = textColor,
                        lineHeight = (currentSize.value * 1.38f).sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSeekToLyric((item.timestampMs - lyricsOffsetMs).coerceAtLeast(0L)) }
                )
            }
        }

        // 右上角歌词悬浮调节按键 (Aa ⏱) - 音频共享式展出
        if (showAdjustButton) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isDark) Color(0xFF1E1E24).copy(alpha = 0.85f) else Color(0xFFE5E5EA).copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showAdjustDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "歌词微调",
                            tint = activeColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "歌词调节",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = activeColor
                        )
                    }
                }

                // 原位浮层音频共享展出卡片
                LyricsAdjustDropdownMenu(
                    expanded = showAdjustDialog,
                    onDismissRequest = { showAdjustDialog = false },
                    fontSizeSp = fontSizeSp,
                    lyricsOffsetMs = lyricsOffsetMs,
                    lyricTheme = lyricTheme,
                    onFontSizeChange = onFontSizeChange,
                    onOffsetChange = onOffsetChange,
                    onThemeChange = onThemeChange
                )
            }
        }
    }
}

/**
 * 原位音频共享展出方式的歌词微调菜单 (字号大小、时间偏置、6 套主题预设)
 */
@Composable
fun LyricsAdjustDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    fontSizeSp: Float,
    lyricsOffsetMs: Long,
    lyricTheme: LyricTheme,
    onFontSizeChange: (Float) -> Unit,
    onOffsetChange: (Long) -> Unit,
    onThemeChange: (LyricTheme) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val primaryText = if (isDark) Color.White else Color.Black
    val secondaryText = if (isDark) Color(0xFFAAAAAE) else Color(0xFF6C6C70)

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.widthIn(min = 270.dp, max = 320.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth()
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = AppleRed,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "歌词调节与外观",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryText
                    )
                }
                IconButton(onClick = onDismissRequest, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = secondaryText, modifier = Modifier.size(14.dp))
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // 1. 字号大小调节
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("字体大小", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = secondaryText)
                Text("${fontSizeSp.toInt()} sp", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppleRed)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onFontSizeChange((fontSizeSp - 2f).coerceAtLeast(16f)) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Text("A-", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = primaryText)
                }
                Slider(
                    value = fontSizeSp,
                    onValueChange = { onFontSizeChange(it) },
                    valueRange = 16f..36f,
                    steps = 9,
                    colors = SliderDefaults.colors(thumbColor = AppleRed, activeTrackColor = AppleRed),
                    modifier = Modifier.weight(1f).height(24.dp)
                )
                IconButton(
                    onClick = { onFontSizeChange((fontSizeSp + 2f).coerceAtMost(36f)) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Text("A+", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = primaryText)
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(vertical = 6.dp)
            )

            // 2. 歌词时间偏置快慢
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("时间同步", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = secondaryText)
                val offsetSec = lyricsOffsetMs / 1000.0
                Text(
                    text = if (lyricsOffsetMs > 0) "+%.1fs (提前)".format(offsetSec) else if (lyricsOffsetMs < 0) "%.1fs (延后)".format(offsetSec) else "0.0s (标准)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (lyricsOffsetMs != 0L) AppleRed else secondaryText
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = { onOffsetChange((lyricsOffsetMs - 500L).coerceAtLeast(-5000L)) },
                    modifier = Modifier.weight(1f).height(28.dp),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("延后 0.5s", fontSize = 10.sp)
                }
                OutlinedButton(
                    onClick = { onOffsetChange(0L) },
                    modifier = Modifier.weight(0.7f).height(28.dp),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("归零", fontSize = 10.sp)
                }
                OutlinedButton(
                    onClick = { onOffsetChange((lyricsOffsetMs + 500L).coerceAtMost(5000L)) },
                    modifier = Modifier.weight(1f).height(28.dp),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("提前 0.5s", fontSize = 10.sp)
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(vertical = 6.dp)
            )

            // 3. 歌词主题颜色
            Text("主题配色", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = secondaryText)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LyricTheme.entries.forEach { theme ->
                    val isSelected = theme == lyricTheme
                    val themeColor = if (isDark) theme.activeColorDark else theme.activeColorLight
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) AppleRed.copy(alpha = 0.2f) else (if (isDark) Color(0xFF2C2C34) else Color(0xFFF2F2F7)),
                        border = if (isSelected) BorderStroke(1.5.dp, AppleRed) else null,
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onThemeChange(theme) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(themeColor)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${lyricTheme.displayName} • ${lyricTheme.description}",
                fontSize = 10.sp,
                color = secondaryText,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * 现代毛玻璃歌词微调控制浮窗 (字号大小、时间快慢偏置、6 套主题预设)
 */
@Composable
fun LyricsAdjustDialog(
    fontSizeSp: Float,
    lyricsOffsetMs: Long,
    lyricTheme: LyricTheme,
    onFontSizeChange: (Float) -> Unit,
    onOffsetChange: (Long) -> Unit,
    onThemeChange: (LyricTheme) -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val primaryText = if (isDark) Color.White else Color.Black
    val secondaryText = if (isDark) Color(0xFFAAAAAE) else Color(0xFF6C6C70)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = if (isDark) Color(0xFF1E1E24).copy(alpha = 0.96f) else Color(0xFFFFFFFF).copy(alpha = 0.96f),
            border = BorderStroke(1.dp, if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f)),
            shadowElevation = 24.dp,
            modifier = Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth(0.92f)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AppleRed.copy(alpha = 0.15f),
                            modifier = Modifier.size(30.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Tune, contentDescription = null, tint = AppleRed, modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("歌词效果与微调", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = primaryText)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = secondaryText, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // 1. 歌词字号大小调节 (16sp ~ 36sp)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("歌词字号大小", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = primaryText)
                        Text("${fontSizeSp.toInt()} sp", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppleRed)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { onFontSizeChange((fontSizeSp - 2f).coerceAtLeast(16f)) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Text("A-", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = primaryText)
                        }
                        Slider(
                            value = fontSizeSp,
                            onValueChange = { onFontSizeChange(it) },
                            valueRange = 16f..36f,
                            steps = 9,
                            colors = SliderDefaults.colors(thumbColor = AppleRed, activeTrackColor = AppleRed),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { onFontSizeChange((fontSizeSp + 2f).coerceAtMost(36f)) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Text("A+", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = primaryText)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 2. 歌词时间快慢偏置 (-5.0s ~ +5.0s)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("歌词时间快慢偏置", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = primaryText)
                        val offsetSec = lyricsOffsetMs / 1000.0
                        Text(
                            text = if (lyricsOffsetMs > 0) "+%.1fs (提前)".format(offsetSec) else if (lyricsOffsetMs < 0) "%.1fs (延后)".format(offsetSec) else "0.0s (标准)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (lyricsOffsetMs != 0L) AppleRed else secondaryText
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onOffsetChange((lyricsOffsetMs - 500L).coerceAtLeast(-5000L)) },
                            modifier = Modifier.weight(1f).height(34.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("延后 0.5s", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { onOffsetChange(0L) },
                            modifier = Modifier.weight(0.8f).height(34.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("归零", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { onOffsetChange((lyricsOffsetMs + 500L).coerceAtMost(5000L)) },
                            modifier = Modifier.weight(1f).height(34.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("提前 0.5s", fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 3. 歌词主题颜色快速切换
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("歌词主题配色", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = primaryText)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LyricTheme.entries.forEach { theme ->
                            val isSelected = theme == lyricTheme
                            val themeColor = if (isDark) theme.activeColorDark else theme.activeColorLight
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) AppleRed.copy(alpha = 0.2f) else (if (isDark) Color(0xFF2C2C34) else Color(0xFFF2F2F7)),
                                border = if (isSelected) BorderStroke(1.5.dp, AppleRed) else null,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onThemeChange(theme) }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(themeColor)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "当前主题：${lyricTheme.displayName} • ${lyricTheme.description}",
                        fontSize = 11.sp,
                        color = secondaryText,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                ) {
                    Text("完成", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
