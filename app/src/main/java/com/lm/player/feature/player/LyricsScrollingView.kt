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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LyricTheme
import com.lm.player.core.designsystem.theme.SystemFontManager
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("lemon_settings_prefs", android.content.Context.MODE_PRIVATE) }
    var isKaraokeEnabled by remember {
        mutableStateOf(prefs.getBoolean("lyric_karaoke_enabled", true))
    }
    var useSystemFont by remember {
        mutableStateOf(prefs.getBoolean("lyric_use_system_font", true))
    }
    val lyricFontFamily = remember(useSystemFont) {
        SystemFontManager.getSystemFontFamily(useSystemFont)
    }

    val cleanLyrics = remember(lyrics) {
        lyrics.filter { it.text.isNotBlank() && !it.text.trim().equals("null", ignoreCase = true) }
    }

    var showAdjustDialog by remember { mutableStateOf(false) }
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f

    val activeColor = if (isDark) lyricTheme.activeColorDark else lyricTheme.activeColorLight
    val inactiveColor = if (isDark) lyricTheme.inactiveColorDark else lyricTheme.inactiveColorLight
    val karaokeBaseColor = lyricTheme.getKaraokeBaseColor(isDark)
    val karaokeOverlayColor = lyricTheme.getKaraokeOverlayColor(isDark)

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

    // 逐字歌词高刷帧级平滑时间插值，消除 400ms 刷新周期产生的逐字流光顿挫跳跃
    var lastTickPos by remember { mutableLongStateOf(effectivePositionMs) }
    var lastTickUptime by remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }

    LaunchedEffect(effectivePositionMs) {
        lastTickPos = effectivePositionMs
        lastTickUptime = android.os.SystemClock.uptimeMillis()
    }

    val isPlaying by com.lm.player.core.media.PlaybackQueueManager.isPlayingFlow.collectAsState()
    var frameTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isKaraokeEnabled, isPlaying) {
        if (isKaraokeEnabled && isPlaying) {
            while (true) {
                withFrameMillis { frameTick = it }
            }
        }
    }

    // 计算当前处于哪一行歌词 (采用 O(log N) 二分查找匹配对应行，降低每 400ms 进度刷新的 CPU 开销)
    val activeIndex = remember(cleanLyrics, effectivePositionMs) {
        var low = 0
        var high = cleanLyrics.lastIndex
        var best = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cleanLyrics[mid].timestampMs <= effectivePositionMs) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        best
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

    val activeFontSize = remember(fontSizeSp) { fontSizeSp.sp }
    val inactiveFontSize = remember(fontSizeSp) { (fontSizeSp * 0.74f).sp }
    val activeLineHeight = remember(fontSizeSp) { (fontSizeSp * 1.38f).sp }
    val inactiveLineHeight = remember(fontSizeSp) { (fontSizeSp * 0.74f * 1.38f).sp }

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
                val isNearActive = kotlin.math.abs(index - activeIndex) <= 1
                val textColor = if (isNearActive) {
                    val animated by animateColorAsState(
                        targetValue = if (isActive) activeColor else inactiveColor,
                        animationSpec = tween(durationMillis = 260),
                        label = "lyric_color"
                    )
                    animated
                } else {
                    inactiveColor
                }
                val currentSize = if (isActive) activeFontSize else inactiveFontSize
                val currentLineHeight = if (isActive) activeLineHeight else inactiveLineHeight
                val fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal

                if (isActive && isKaraokeEnabled) {
                    val nextItemTime = cleanLyrics.getOrNull(index + 1)?.timestampMs ?: (item.timestampMs + 4000L)
                    val lineDuration = (nextItemTime - item.timestampMs).coerceIn(1000L, 12000L)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSeekToLyric((item.timestampMs - lyricsOffsetMs).coerceAtLeast(0L)) }
                    ) {
                        Box(modifier = Modifier.wrapContentWidth()) {
                            Text(
                                text = item.text,
                                style = TextStyle(
                                    fontSize = currentSize,
                                    fontWeight = fontWeight,
                                    fontFamily = lyricFontFamily,
                                    color = karaokeBaseColor,
                                    lineHeight = currentLineHeight
                                )
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .drawWithContent {
                                        // 建立对高刷帧时钟的绘制阶段依赖，平滑自增且无重组开销
                                        val _tick = frameTick
                                        val elapsed = (android.os.SystemClock.uptimeMillis() - lastTickUptime).coerceIn(0L, 600L)
                                        val smoothPos = lastTickPos + elapsed
                                        val lineProgress = ((smoothPos - item.timestampMs).toFloat() / lineDuration.toFloat()).coerceIn(0f, 1f)

                                        if (lineProgress > 0f) {
                                            clipRect(right = size.width * lineProgress) {
                                                this@drawWithContent.drawContent()
                                            }
                                        }
                                    }
                            ) {
                                Text(
                                    text = item.text,
                                    style = TextStyle(
                                        fontSize = currentSize,
                                        fontWeight = fontWeight,
                                        fontFamily = lyricFontFamily,
                                        color = karaokeOverlayColor,
                                        lineHeight = currentLineHeight
                                    )
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = item.text,
                        style = TextStyle(
                            fontSize = currentSize,
                            fontWeight = fontWeight,
                            fontFamily = lyricFontFamily,
                            color = textColor,
                            lineHeight = currentLineHeight
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSeekToLyric((item.timestampMs - lyricsOffsetMs).coerceAtLeast(0L)) }
                    )
                }
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
                    isKaraokeEnabled = isKaraokeEnabled,
                    useSystemFont = useSystemFont,
                    onFontSizeChange = onFontSizeChange,
                    onOffsetChange = onOffsetChange,
                    onThemeChange = onThemeChange,
                    onKaraokeChange = {
                        isKaraokeEnabled = it
                        prefs.edit().putBoolean("lyric_karaoke_enabled", it).apply()
                    },
                    onFontModeChange = {
                        useSystemFont = it
                        prefs.edit().putBoolean("lyric_use_system_font", it).apply()
                    }
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
    isKaraokeEnabled: Boolean = true,
    useSystemFont: Boolean = true,
    onFontSizeChange: (Float) -> Unit,
    onOffsetChange: (Long) -> Unit,
    onThemeChange: (LyricTheme) -> Unit,
    onKaraokeChange: (Boolean) -> Unit = {},
    onFontModeChange: (Boolean) -> Unit = {}
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val primaryText = if (isDark) Color.White else Color.Black
    val secondaryText = if (isDark) Color(0xFFAAAAAE) else Color(0xFF6C6C70)

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier
            .background(if (isDark) Color(0xFF24242C) else Color.White)
            .widthIn(min = 270.dp, max = 320.dp)
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
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(vertical = 6.dp)
            )

            // 4. 显示动效 (逐字流光 / 逐条整行)
            Text("显示动效", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = secondaryText)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isKaraokeEnabled) AppleRed.copy(alpha = 0.2f) else (if (isDark) Color(0xFF2C2C34) else Color(0xFFF2F2F7)),
                    border = if (isKaraokeEnabled) BorderStroke(1.2.dp, AppleRed) else null,
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onKaraokeChange(true) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "逐字流光",
                            fontSize = 11.sp,
                            fontWeight = if (isKaraokeEnabled) FontWeight.Bold else FontWeight.Normal,
                            color = if (isKaraokeEnabled) AppleRed else primaryText
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (!isKaraokeEnabled) AppleRed.copy(alpha = 0.2f) else (if (isDark) Color(0xFF2C2C34) else Color(0xFFF2F2F7)),
                    border = if (!isKaraokeEnabled) BorderStroke(1.2.dp, AppleRed) else null,
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onKaraokeChange(false) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "逐条整行",
                            fontSize = 11.sp,
                            fontWeight = if (!isKaraokeEnabled) FontWeight.Bold else FontWeight.Normal,
                            color = if (!isKaraokeEnabled) AppleRed else primaryText
                        )
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(vertical = 6.dp)
            )

            // 5. 歌词字体 (系统本机字体 / 默认软件字体)
            Text("歌词字体", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = secondaryText)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (useSystemFont) AppleRed.copy(alpha = 0.2f) else (if (isDark) Color(0xFF2C2C34) else Color(0xFFF2F2F7)),
                    border = if (useSystemFont) BorderStroke(1.2.dp, AppleRed) else null,
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onFontModeChange(true) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "系统本机字体",
                            fontSize = 11.sp,
                            fontWeight = if (useSystemFont) FontWeight.Bold else FontWeight.Normal,
                            color = if (useSystemFont) AppleRed else primaryText
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (!useSystemFont) AppleRed.copy(alpha = 0.2f) else (if (isDark) Color(0xFF2C2C34) else Color(0xFFF2F2F7)),
                    border = if (!useSystemFont) BorderStroke(1.2.dp, AppleRed) else null,
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onFontModeChange(false) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "默认软件字体",
                            fontSize = 11.sp,
                            fontWeight = if (!useSystemFont) FontWeight.Bold else FontWeight.Normal,
                            color = if (!useSystemFont) AppleRed else primaryText
                        )
                    }
                }
            }
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
    isKaraokeEnabled: Boolean = true,
    useSystemFont: Boolean = true,
    onFontSizeChange: (Float) -> Unit,
    onOffsetChange: (Long) -> Unit,
    onThemeChange: (LyricTheme) -> Unit,
    onKaraokeChange: (Boolean) -> Unit = {},
    onFontModeChange: (Boolean) -> Unit = {},
    onDismiss: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
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

                Spacer(modifier = Modifier.height(16.dp))

                // 4. 显示动效 (逐字流光 / 逐条整行)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("显示动效", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = primaryText)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isKaraokeEnabled) AppleRed.copy(alpha = 0.2f) else (if (isDark) Color(0xFF2C2C34) else Color(0xFFF2F2F7)),
                            border = if (isKaraokeEnabled) BorderStroke(1.5.dp, AppleRed) else null,
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onKaraokeChange(true) }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "逐字流光",
                                    fontSize = 12.sp,
                                    fontWeight = if (isKaraokeEnabled) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isKaraokeEnabled) AppleRed else primaryText
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (!isKaraokeEnabled) AppleRed.copy(alpha = 0.2f) else (if (isDark) Color(0xFF2C2C34) else Color(0xFFF2F2F7)),
                            border = if (!isKaraokeEnabled) BorderStroke(1.5.dp, AppleRed) else null,
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onKaraokeChange(false) }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "逐条整行",
                                    fontSize = 12.sp,
                                    fontWeight = if (!isKaraokeEnabled) FontWeight.Bold else FontWeight.Medium,
                                    color = if (!isKaraokeEnabled) AppleRed else primaryText
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 5. 歌词字体 (系统本机字体 / 默认软件字体)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("歌词字体", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = primaryText)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (useSystemFont) AppleRed.copy(alpha = 0.2f) else (if (isDark) Color(0xFF2C2C34) else Color(0xFFF2F2F7)),
                            border = if (useSystemFont) BorderStroke(1.5.dp, AppleRed) else null,
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onFontModeChange(true) }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "系统本机字体",
                                    fontSize = 12.sp,
                                    fontWeight = if (useSystemFont) FontWeight.Bold else FontWeight.Medium,
                                    color = if (useSystemFont) AppleRed else primaryText
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (!useSystemFont) AppleRed.copy(alpha = 0.2f) else (if (isDark) Color(0xFF2C2C34) else Color(0xFFF2F2F7)),
                            border = if (!useSystemFont) BorderStroke(1.5.dp, AppleRed) else null,
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onFontModeChange(false) }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "默认软件字体",
                                    fontSize = 12.sp,
                                    fontWeight = if (!useSystemFont) FontWeight.Bold else FontWeight.Medium,
                                    color = if (!useSystemFont) AppleRed else primaryText
                                )
                            }
                        }
                    }
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

/**
 * 封面下方 5 行滚动渐变歌词组件
 * 默认展示 5 行：上上句(顶部淡出)、上一句、当前歌词(高亮放大)、下一句、下下句(底部淡出)
 */
@Composable
fun CoverFiveLineGradientLyrics(
    lyrics: List<LyricLine>,
    currentPositionMs: Long,
    lyricsOffsetMs: Long = 0L,
    lyricTheme: LyricTheme = LyricTheme.APPLE_MUSIC,
    onClickLyricsArea: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val activeColor = if (isDark) lyricTheme.activeColorDark else lyricTheme.activeColorLight
    val baseInactiveColor = if (isDark) {
        Color.White.copy(alpha = 0.78f)
    } else {
        Color(0xFF1C1C1E).copy(alpha = 0.75f)
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("lemon_settings_prefs", android.content.Context.MODE_PRIVATE) }
    val useSystemFont = prefs.getBoolean("lyric_use_system_font", true)
    val lyricFontFamily = remember(useSystemFont) {
        SystemFontManager.getSystemFontFamily(useSystemFont)
    }

    val cleanLyrics = remember(lyrics) {
        lyrics.filter { it.text.isNotBlank() && !it.text.trim().equals("null", ignoreCase = true) }
    }

    val effectivePositionMs = (currentPositionMs + lyricsOffsetMs).coerceAtLeast(0L)
    val activeIndex = remember(cleanLyrics, effectivePositionMs) {
        if (cleanLyrics.isEmpty()) 0
        else {
            var low = 0
            var high = cleanLyrics.lastIndex
            var best = 0
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (cleanLyrics[mid].timestampMs <= effectivePositionMs) {
                    best = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            best
        }
    }

    val animatedCenterIndex by androidx.compose.animation.core.animateFloatAsState(
        targetValue = activeIndex.toFloat(),
        animationSpec = tween(
            durationMillis = 360,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        label = "five_line_lyric_scroll"
    )

    val dimensions = com.lm.player.core.designsystem.theme.LocalAppDimensions.current
    val rowHeightDp = 28.dp
    val totalHeightDp = 140.dp // 5 * 28.dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rowHeightPx = with(density) { rowHeightDp.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeightDp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClickLyricsArea)
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                // 上下边缘平滑滚动渐变遮罩 (前后各一条做滚动淡化)
                drawRect(
                    brush = Brush.verticalGradient(
                        0.00f to Color.Transparent,
                        0.18f to Color.Black.copy(alpha = 0.55f),
                        0.32f to Color.Black,
                        0.68f to Color.Black,
                        0.82f to Color.Black.copy(alpha = 0.55f),
                        1.00f to Color.Transparent
                    ),
                    blendMode = BlendMode.DstIn
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (cleanLyrics.isEmpty()) {
            val placeholderLines = listOf(
                "",
                "· · ·",
                "♪ 纯音乐 / 暂无歌词 ♪",
                "点击切换至完整歌词视图",
                ""
            )
            placeholderLines.forEachIndexed { idx, text ->
                val rel = idx - 2 // -2, -1, 0, +1, +2
                val absRel = kotlin.math.abs(rel)
                val lineAlpha = when (absRel) {
                    0 -> 0.92f
                    1 -> 0.48f
                    else -> 0.20f
                }
                Text(
                    text = text,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        fontFamily = lyricFontFamily,
                        fontSize = (if (absRel == 0) 16.5.sp else 14.5.sp) * dimensions.fontScale,
                        fontWeight = if (absRel == 0) FontWeight.Bold else FontWeight.Normal,
                        color = (if (absRel == 0) activeColor else baseInactiveColor).copy(alpha = lineAlpha)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .graphicsLayer {
                            translationY = rel * rowHeightPx
                        }
                )
            }
        } else {
            val minIdx = (kotlin.math.floor(animatedCenterIndex).toInt() - 3).coerceAtLeast(-2)
            val maxIdx = (kotlin.math.ceil(animatedCenterIndex).toInt() + 3).coerceAtMost(cleanLyrics.lastIndex + 2)

            for (idx in minIdx..maxIdx) {
                val delta = idx - animatedCenterIndex
                val absDelta = kotlin.math.abs(delta)
                if (absDelta > 2.65f) continue

                val lineText = when {
                    idx in cleanLyrics.indices -> cleanLyrics[idx].text
                    else -> ""
                }
                if (lineText.isEmpty()) continue

                // 根据距离中心行的偏移量平滑计算透明度、缩放与色彩权重
                val lineAlpha = when {
                    absDelta <= 0.5f -> 1.0f - (absDelta * 0.42f)
                    absDelta <= 1.5f -> 0.79f - ((absDelta - 0.5f) * 0.45f)
                    else -> (0.34f - ((absDelta - 1.5f) * 0.26f)).coerceIn(0f, 0.34f)
                }.coerceIn(0f, 1f)

                val lineScale = (1.08f - (absDelta * 0.065f)).coerceIn(0.90f, 1.08f)
                val isCurrentCenter = absDelta < 0.45f

                Text(
                    text = lineText,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        fontFamily = lyricFontFamily,
                        fontSize = (if (isCurrentCenter) 17.5.sp else 15.sp) * dimensions.fontScale,
                        fontWeight = if (isCurrentCenter) FontWeight.Bold else FontWeight.Medium,
                        color = if (isCurrentCenter) activeColor else baseInactiveColor
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .graphicsLayer {
                            translationY = delta * rowHeightPx
                            alpha = lineAlpha
                            scaleX = lineScale
                            scaleY = lineScale
                        }
                )
            }
        }
    }
}

