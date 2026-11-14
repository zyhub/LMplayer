package com.lm.player.feature.player

import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.glass.GlassBox
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LyricTheme
import com.lm.player.core.designsystem.theme.SystemFontManager
import com.lm.player.core.model.LyricLine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 滚动歌词（原生 Compose 实现，未使用 AMLL 等 AGPL 项目的代码，只参照常见播放器的歌词展示方式）。
 * 两套展示主题（[LyricDisplayStyle]）共用同一套排版 / 弹簧滚动 / 逐字流光，只是参数不同：
 * - 流光（默认，即原来的样式）：同字号粗体左对齐，当前行全亮，其余行变暗略缩小并按距离景深模糊，换行时逐行波浪跟随；
 * - 卡拉 OK：居中两行（当前句 + 下一句），当前句逐字填成强调色。
 * 共同交互：上下边缘渐隐；手指拖动浏览，松手 3 秒后回到当前行；点任意一行跳转播放。
 *
 * 字体：苹果的 SF Pro / 苹方授权不允许随安卓 App 分发，用应用默认字体（或在「⋯」里勾选系统字体）的粗体 + 略收紧字距来接近。
 */
@Composable
internal fun AmllLyricsView(
    lyrics: List<LyricLine>,
    currentPositionMs: Long,
    onSeekToLyric: (Long) -> Unit,
    fontSizeSp: Float,
    lyricsOffsetMs: Long,
    lyricTheme: LyricTheme,
    onFontSizeChange: (Float) -> Unit,
    onOffsetChange: (Long) -> Unit,
    showAdjustButton: Boolean,
    glassBackdrop: com.kyant.backdrop.Backdrop?,
    modifier: Modifier = Modifier,
    /** 当前句锚点比例上限（null = 样式默认） */
    topAnchor: Float? = null
) {
    val context = LocalContext.current
    remember { LyricDisplaySettings.ensureLoaded(context) }
    val isKaraokeEnabled = LyricDisplaySettings.karaoke
    val isDepthBlurEnabled = LyricDisplaySettings.depthBlur
    val useSystemFont = LyricDisplaySettings.systemFont
    val displayStyle = LyricDisplaySettings.style
    val lyricFontFamily = remember(useSystemFont) { SystemFontManager.getSystemFontFamily(useSystemFont) }

    val cleanLyrics = remember(lyrics) {
        lyrics.filter { it.text.isNotBlank() && !it.text.trim().equals("null", ignoreCase = true) }
    }
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    // 配色不再单独选择：默认主题沿用原来的歌词颜色（之前选过的配色保留），其它主题在此基础上各自着色
    val activeColor = if (isDark) lyricTheme.activeColorDark else lyricTheme.activeColorLight
    val inactiveColor = if (isDark) lyricTheme.inactiveColorDark else lyricTheme.inactiveColorLight

    Box(modifier = modifier.fillMaxSize()) {
        if (cleanLyrics.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("♪ 纯音乐 / 暂无歌词 ♪", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = activeColor)
                Spacer(Modifier.height(6.dp))
                Text("享受纯净无损音乐时光", fontSize = 12.sp, color = inactiveColor)
            }
        } else {
            AmllLyricsBody(
                lyrics = cleanLyrics,
                currentPositionMs = currentPositionMs,
                onSeekToLyric = onSeekToLyric,
                fontSizeSp = fontSizeSp,
                lyricsOffsetMs = lyricsOffsetMs,
                activeColor = activeColor,
                isDark = isDark,
                isKaraokeEnabled = isKaraokeEnabled,
                isDepthBlurEnabled = isDepthBlurEnabled,
                fontFamily = lyricFontFamily,
                spec = remember(displayStyle) { LyricStyleSpec.of(displayStyle) },
                topAnchor = topAnchor
            )
        }

        // 竖屏播放页的「词」按钮在顶部「歌曲 | 歌词」切换栏右边（由播放页放置）；横屏时放在歌词区右上角
        if (showAdjustButton) {
            LyricsAdjustButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 10.dp),
                isDark = isDark,
                contentColor = if (isDark) Color.White.copy(alpha = 0.92f) else Color(0xFF1C1C1E),
                fallbackBackground = if (isDark) Color(0xFF1E1E24).copy(alpha = 0.80f) else Color(0xFFE5E5EA).copy(alpha = 0.85f),
                glassBackdrop = glassBackdrop,
                fontSizeSp = fontSizeSp,
                lyricsOffsetMs = lyricsOffsetMs,
                onFontSizeChange = onFontSizeChange,
                onOffsetChange = onOffsetChange
            )
        }
    }
}

// =====================================================================================
// 歌词展示主题
// =====================================================================================

internal enum class LyricDisplayStyle(val id: String, val title: String, val subtitle: String) {
    FLOW("amll", "流光", "默认"),
    KARAOKE("karaoke", "卡拉 OK", "两行逐字填色");

    companion object {
        fun fromId(id: String?): LyricDisplayStyle = entries.firstOrNull { it.id == id } ?: FLOW
    }
}

/** 各主题的排版 / 动效参数 */
private class LyricStyleSpec(
    val textAlign: TextAlign,
    val origin: TransformOrigin,
    /** 当前行在视口中的位置（自上而下比例） */
    val anchor: Float,
    val fontScale: Float,
    /** 行间距（相对字号） */
    val gapScale: Float,
    val weight: FontWeight,
    val activeScale: Float,
    val inactiveScale: Float,
    /** 每隔一行增加的景深模糊（dp，0 = 该主题不模糊） */
    val blurPerLine: Float,
    /** 带符号距离（0 = 当前行，正数在下方）→ 目标透明度 */
    val alphaFor: (Int) -> Float,
    /** 逐字填充用强调色（卡拉 OK） */
    val accentFill: Boolean,
    /** 当前行光晕（霓虹） */
    val glow: Boolean
) {
    companion object {
        private val START = TransformOrigin(0f, 0.5f)
        private val CENTER = TransformOrigin(0.5f, 0.5f)

        fun of(style: LyricDisplayStyle): LyricStyleSpec = when (style) {
            LyricDisplayStyle.FLOW -> LyricStyleSpec(
                TextAlign.Start, START, 0.32f, 1f, 0.62f, FontWeight.Bold, 1f, 0.95f, 1.1f,
                { d -> if (d == 0) 1f else (0.36f - (abs(d) - 1) * 0.03f).coerceAtLeast(0.20f) },
                accentFill = false, glow = false
            )
            LyricDisplayStyle.KARAOKE -> LyricStyleSpec(
                TextAlign.Center, CENTER, 0.42f, 1f, 0.95f, FontWeight.Bold, 1.08f, 0.88f, 0f,
                { d -> when (d) { 0 -> 1f; 1 -> 0.55f; -1 -> 0.20f; else -> 0.08f } },
                accentFill = true, glow = false
            )
        }
    }
}

@Composable
private fun AmllLyricsBody(
    lyrics: List<LyricLine>,
    currentPositionMs: Long,
    onSeekToLyric: (Long) -> Unit,
    fontSizeSp: Float,
    lyricsOffsetMs: Long,
    activeColor: Color,
    isDark: Boolean,
    isKaraokeEnabled: Boolean,
    isDepthBlurEnabled: Boolean,
    fontFamily: androidx.compose.ui.text.font.FontFamily,
    spec: LyricStyleSpec,
    topAnchor: Float? = null
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // ---------- 播放时间：400ms 一次的进度在两次之间按帧插值 ----------
    val effectivePositionMs = (currentPositionMs + lyricsOffsetMs).coerceAtLeast(0L)
    var lastTickPos by remember { mutableLongStateOf(effectivePositionMs) }
    var lastTickUptime by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(effectivePositionMs) {
        lastTickPos = effectivePositionMs
        lastTickUptime = SystemClock.uptimeMillis()
    }
    val isPlaying by com.lm.player.core.media.PlaybackQueueManager.isPlayingFlow.collectAsState()
    var frameTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isKaraokeEnabled, isPlaying) {
        if (isKaraokeEnabled && isPlaying) {
            while (true) withFrameMillis { frameTick = it }
        }
    }

    val activeIndex = remember(lyrics, effectivePositionMs) {
        var low = 0
        var high = lyrics.lastIndex
        var best = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lyrics[mid].timestampMs <= effectivePositionMs) { best = mid; low = mid + 1 } else high = mid - 1
        }
        best
    }

    val scaledSize = fontSizeSp * spec.fontScale
    val textStyle = remember(scaledSize, fontFamily, spec) {
        TextStyle(
            fontSize = scaledSize.sp,
            lineHeight = (scaledSize * 1.28f).sp,
            fontWeight = spec.weight,
            fontFamily = fontFamily,
            letterSpacing = (-0.01f).em,
            textAlign = spec.textAlign
        )
    }
    // 卡拉 OK：底字半透明、逐字填成强调色；其它主题填成正文色
    val karaokeBase = if (spec.accentFill) activeColor.copy(alpha = 0.45f) else activeColor.copy(alpha = 0.38f)
    val karaokeFill = if (spec.accentFill) AppleRed else activeColor
    val glowShadow = if (spec.glow) {
        Shadow(color = if (isDark) activeColor.copy(alpha = 0.75f) else AppleRed.copy(alpha = 0.45f), blurRadius = 26f)
    } else null
    val lineGapPx = with(density) { (fontSizeSp * spec.gapScale).sp.toPx() }
    val textMeasurer = rememberTextMeasurer(cacheSize = 32)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            // 上下边缘渐隐（Apple Music 式）
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.10f to Color.Black,
                        0.82f to Color.Black,
                        1f to Color.Transparent
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
    ) {
        val sidePadPx = with(density) { 22.dp.toPx() }
        val textWidthPx = (constraints.maxWidth - sidePadPx * 2f).toInt().coerceAtLeast(1)
        val viewportHeightPx = constraints.maxHeight.toFloat()
        val anchorFraction: Float = if (topAnchor != null && topAnchor < spec.anchor) topAnchor else spec.anchor
        val anchorPx = viewportHeightPx * anchorFraction

        // ---------- 预先量出每一行的高度，算出每行顶部位置 ----------
        val tops = remember(lyrics, textStyle, textWidthPx, lineGapPx) {
            var y = 0f
            FloatArray(lyrics.size) { i ->
                val top = y
                val h = textMeasurer.measure(
                    text = lyrics[i].text,
                    style = textStyle,
                    constraints = Constraints(maxWidth = textWidthPx)
                ).size.height
                y += h + lineGapPx
                top
            }
        }

        // ---------- 每行独立的弹簧滚动（波浪跟随） ----------
        val scrollTarget = (tops.getOrElse(activeIndex) { 0f } - anchorPx)
        val lineScroll = remember(lyrics) { List(lyrics.size) { Animatable(scrollTarget) } }
        // 手动浏览偏移（松手 3 秒后弹回）
        val userScroll = remember { Animatable(0f) }
        var userResetJob by remember { mutableStateOf<Job?>(null) }

        LaunchedEffect(scrollTarget, lineScroll) {
            lineScroll.forEachIndexed { i, anim ->
                val distance = i - activeIndex
                if (abs(distance) > 14) {
                    anim.snapTo(scrollTarget)
                } else {
                    launch {
                        // 当前行与上方立即起步；越往下的行起步越晚（每行 35ms，最多 0.35s）
                        val delayMs = if (distance <= 0) 0L else (distance * 35L).coerceAtMost(350L)
                        if (delayMs > 0) delay(delayMs)
                        anim.animateTo(scrollTarget, spring(dampingRatio = 0.83f, stiffness = 100f))
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(lyrics) {
                    detectVerticalDragGestures(
                        onDragStart = { userResetJob?.cancel() },
                        onDragEnd = {
                            userResetJob = scope.launch {
                                delay(3000)
                                userScroll.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 120f))
                            }
                        },
                        onDragCancel = {
                            userResetJob = scope.launch {
                                delay(3000)
                                userScroll.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 120f))
                            }
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        scope.launch { userScroll.snapTo(userScroll.value - dragAmount) }
                    }
                }
        ) {
            // 只组合视口附近的行（含上下各一屏余量）
            val visibleRange = run {
                val top = scrollTarget - viewportHeightPx
                val bottom = scrollTarget + viewportHeightPx * 2f
                var first = 0
                while (first < tops.lastIndex && tops[first + 1] < top) first++
                var last = first
                while (last < tops.lastIndex && tops[last] < bottom) last++
                first..last
            }
            for (i in visibleRange) {
                val line = lyrics[i]
                val signed = i - activeIndex
                val distance = abs(signed)
                val isActive = i == activeIndex
                val alpha by animateFloatAsState(
                    targetValue = spec.alphaFor(signed),
                    animationSpec = spring(dampingRatio = 1f, stiffness = 300f),
                    label = "amll_alpha"
                )
                val scale by animateFloatAsState(
                    targetValue = if (isActive) spec.activeScale else spec.inactiveScale,
                    animationSpec = spring(dampingRatio = 0.75f, stiffness = 220f),
                    label = "amll_scale"
                )
                val blurDp by animateFloatAsState(
                    targetValue = if (!isDepthBlurEnabled || isActive || spec.blurPerLine <= 0f) 0f
                    else (distance.coerceAtMost(5) * spec.blurPerLine),
                    animationSpec = spring(dampingRatio = 1f, stiffness = 200f),
                    label = "amll_blur"
                )
                val nextTime = lyrics.getOrNull(i + 1)?.timestampMs ?: (line.timestampMs + 4000L)
                val lineDuration = (nextTime - line.timestampMs).coerceIn(800L, 12000L)

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                sidePadPx.roundToInt(),
                                (tops[i] - lineScroll[i].value - userScroll.value).roundToInt()
                            )
                        }
                        .width(with(density) { textWidthPx.toDp() })
                        .graphicsLayer {
                            this.alpha = alpha
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = spec.origin
                        }
                        .then(
                            if (blurDp > 0.2f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(blurDp.dp)
                            else Modifier
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            scope.launch { userScroll.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 200f)) }
                            onSeekToLyric((line.timestampMs - lyricsOffsetMs).coerceAtLeast(0L))
                        }
                ) {
                    if (isActive && isKaraokeEnabled) {
                        KaraokeLine(
                            text = line.text,
                            style = textStyle,
                            baseColor = karaokeBase,
                            fillColor = karaokeFill,
                            shadow = glowShadow,
                            progress = {
                                @Suppress("UNUSED_VARIABLE") val tick = frameTick
                                val elapsed = (SystemClock.uptimeMillis() - lastTickUptime).coerceIn(0L, 600L)
                                val smoothPos = lastTickPos + elapsed
                                ((smoothPos - line.timestampMs).toFloat() / lineDuration).coerceIn(0f, 1f)
                            }
                        )
                    } else {
                        Text(
                            text = line.text,
                            style = textStyle.copy(color = activeColor, shadow = if (isActive) glowShadow else null),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/** 逐字流光：底层为半透明字，上层亮字按视觉行依次揭开，前沿带柔和渐变 */
@Composable
private fun KaraokeLine(
    text: String,
    style: TextStyle,
    baseColor: Color,
    fillColor: Color,
    shadow: Shadow?,
    progress: () -> Float
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = style.copy(color = baseColor),
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = text,
            style = style.copy(color = fillColor, shadow = shadow),
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    val l = layout ?: return@drawWithContent
                    val p = progress()
                    val total = text.length.coerceAtLeast(1)
                    val sung = p * total
                    val edge = style.fontSize.toPx() * 0.9f
                    for (line in 0 until l.lineCount) {
                        val start = l.getLineStart(line)
                        val end = l.getLineEnd(line, visibleEnd = true)
                        val left = l.getLineLeft(line)
                        val right = l.getLineRight(line)
                        val fillX = when {
                            sung >= end -> right + edge
                            sung <= start -> left - edge
                            else -> {
                                val idx = sung.toInt().coerceIn(start, (end - 1).coerceAtLeast(start))
                                val x0 = l.getHorizontalPosition(idx, usePrimaryDirection = true)
                                val x1 = l.getHorizontalPosition((idx + 1).coerceAtMost(end), usePrimaryDirection = true)
                                x0 + (x1 - x0) * (sung - idx)
                            }
                        }
                        val top = l.getLineTop(line)
                        val bottom = l.getLineBottom(line)
                        // 本视觉行已唱部分保留，未唱部分抹掉；其它行（尚未唱到 / 已唱完）整行处理
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0f to Color.Black,
                                1f to Color.Transparent,
                                startX = fillX - edge / 2f,
                                endX = fillX + edge / 2f
                            ),
                            topLeft = Offset(0f, top),
                            size = androidx.compose.ui.geometry.Size(size.width, bottom - top),
                            blendMode = BlendMode.DstIn
                        )
                    }
                }
        )
    }
}

// =====================================================================================
// 歌词显示设置（主题 / 逐字流光 / 景深模糊 / 系统字体）：歌曲页五行歌词、歌词页、横屏歌词共用同一份状态，
// 在任意一处切换，所有歌词显示立刻同步；怀旧主题仍直接读写同一组 SharedPreferences，这里监听变化保持一致。
// =====================================================================================

internal object LyricDisplaySettings {
    private const val PREFS_NAME = "lemon_settings_prefs"
    private const val KEY_KARAOKE = "lyric_karaoke_enabled"
    private const val KEY_DEPTH_BLUR = "lyric_depth_blur_enabled"
    private const val KEY_SYSTEM_FONT = "lyric_use_system_font"
    private const val KEY_STYLE = "lyric_display_style"
    /** 2026-11：「系统字体」默认改为关闭；只迁移一次，之后尊重用户自己的选择 */
    private const val KEY_SYSTEM_FONT_DEFAULT_OFF = "lyric_system_font_default_off_v1"

    private var prefs: android.content.SharedPreferences? = null

    var karaoke by mutableStateOf(true)
        private set
    var depthBlur by mutableStateOf(true)
        private set
    var systemFont by mutableStateOf(false)
        private set
    var style by mutableStateOf(LyricDisplayStyle.FLOW)
        private set

    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        when (key) {
            KEY_KARAOKE -> karaoke = sp.getBoolean(KEY_KARAOKE, true)
            KEY_DEPTH_BLUR -> depthBlur = sp.getBoolean(KEY_DEPTH_BLUR, true)
            KEY_SYSTEM_FONT -> systemFont = sp.getBoolean(KEY_SYSTEM_FONT, false)
            KEY_STYLE -> style = LyricDisplayStyle.fromId(sp.getString(KEY_STYLE, null))
        }
    }

    fun ensureLoaded(context: android.content.Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_SYSTEM_FONT_DEFAULT_OFF, false)) {
            p.edit()
                .putBoolean(KEY_SYSTEM_FONT, false)
                .putBoolean(KEY_SYSTEM_FONT_DEFAULT_OFF, true)
                .apply()
        }
        karaoke = p.getBoolean(KEY_KARAOKE, true)
        depthBlur = p.getBoolean(KEY_DEPTH_BLUR, true)
        systemFont = p.getBoolean(KEY_SYSTEM_FONT, false)
        style = LyricDisplayStyle.fromId(p.getString(KEY_STYLE, null))
        p.registerOnSharedPreferenceChangeListener(listener)
        prefs = p
    }

    fun updateKaraoke(value: Boolean) {
        karaoke = value
        prefs?.edit()?.putBoolean(KEY_KARAOKE, value)?.apply()
    }

    fun updateDepthBlur(value: Boolean) {
        depthBlur = value
        prefs?.edit()?.putBoolean(KEY_DEPTH_BLUR, value)?.apply()
    }

    fun updateSystemFont(value: Boolean) {
        systemFont = value
        prefs?.edit()?.putBoolean(KEY_SYSTEM_FONT, value)?.apply()
    }

    fun updateStyle(value: LyricDisplayStyle) {
        style = value
        prefs?.edit()?.putString(KEY_STYLE, value.id)?.apply()
    }
}

// =====================================================================================
// 歌词调节「词」按钮：圆形玻璃按钮 → 向下液态拉长成竖条（Aa / ⏱ / ⋯）。
// - 字号、快慢：仍是这条竖条，内容从下往上滑入替换成竖排的「＋ / 数值 / －」，竖条只变长不变宽；
// - 主题与选项（⋯）：文字多，竖条原地向左下扩成玻璃方块，勾选式选项；
// - 点「词」或点竖条 / 方块以外的任意位置收回（播放页根部统一拦截，见 [PlayerOutsideTap]）。
// =====================================================================================

/**
 * 播放页「点外围收回」：展开中的浮层登记自己的窗口区域，播放页根部在按下时检查，
 * 按在区域外就收回并吃掉这次手势（不会顺带触发下面的按钮）。
 */
internal object PlayerOutsideTap {
    private val entries = LinkedHashMap<Any, Pair<() -> androidx.compose.ui.geometry.Rect?, () -> Unit>>()

    fun register(key: Any, bounds: () -> androidx.compose.ui.geometry.Rect?, onOutside: () -> Unit) {
        entries[key] = bounds to onOutside
    }

    fun unregister(key: Any) {
        entries.remove(key)
    }

    /** 返回 true 表示这次按下用来收回浮层 */
    fun dispatchDown(windowPos: Offset): Boolean {
        if (entries.isEmpty()) return false
        var handled = false
        for ((bounds, onOutside) in entries.values.toList()) {
            val r = bounds() ?: continue
            if (!r.contains(windowPos)) {
                onOutside()
                handled = true
            }
        }
        return handled
    }
}

/** 播放页根部：转发按下事件给 [PlayerOutsideTap]；收回浮层时吃掉整次手势 */
internal fun Modifier.playerOutsideTapHost(): Modifier = composed {
    val coords = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    this
        .onGloballyPositioned { coords[0] = it }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val c = coords[0]
                val windowPos = if (c != null && c.isAttached) c.localToWindow(down.position) else down.position
                if (PlayerOutsideTap.dispatchDown(windowPos)) {
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
        }
}

@Composable
internal fun LyricsAdjustButton(
    modifier: Modifier = Modifier,
    isDark: Boolean,
    contentColor: Color,
    fallbackBackground: Color,
    glassBackdrop: com.kyant.backdrop.Backdrop?,
    fontSizeSp: Float,
    lyricsOffsetMs: Long,
    onFontSizeChange: (Float) -> Unit,
    onOffsetChange: (Long) -> Unit,
    size: Dp = 36.dp
) {
    val context = LocalContext.current
    remember { LyricDisplaySettings.ensureLoaded(context) }
    var expanded by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(0) } // 0 竖条 / 1 字号 / 2 快慢 / 3 主题与选项
    val accent = AppleRed

    // 点外围收回：登记展开后的整块区域
    val tapKey = remember { Any() }
    val capsuleRect = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
    DisposableEffect(expanded) {
        if (expanded) {
            PlayerOutsideTap.register(tapKey, { capsuleRect[0] }) {
                expanded = false
                panel = 0
            }
        }
        onDispose { PlayerOutsideTap.unregister(tapKey) }
    }

    // ---------- 各形态的目标尺寸（字号 / 快慢只加长竖条，不加宽） ----------
    val iconSize = 34.dp
    val iconGap = 4.dp
    val rowH = 32.dp
    val targetW = if (expanded && panel == 3) 216.dp else size
    val targetH = when {
        !expanded -> size
        panel == 1 -> size + 140.dp
        panel == 2 -> size + 176.dp
        panel == 3 -> size + 10.dp + rowH * LyricDisplayStyle.entries.size + 17.dp + rowH * 3
        else -> size + (iconSize + iconGap) * 3 + 4.dp
    }
    // 液态形变：宽高各自带一点过冲的弹簧
    val w by animateDpAsState(targetW, spring(dampingRatio = 0.66f, stiffness = 300f), label = "ci_w")
    val h by animateDpAsState(targetH, spring(dampingRatio = 0.62f, stiffness = 300f), label = "ci_h")
    LaunchedEffect(expanded, panel) { com.lm.player.core.designsystem.glass.GlassRefreshController.keepLive() }
    val shape = RoundedCornerShape(size / 2)

    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                // 布局上只占按钮大小，展开部分向下（主题方块再向左）画到内容之上
                .wrapContentSize(align = Alignment.TopEnd, unbounded = true)
                .width(w.coerceAtLeast(size))
                .height(h.coerceAtLeast(size))
                .onGloballyPositioned { c ->
                    capsuleRect[0] = androidx.compose.ui.geometry.Rect(
                        c.positionInWindow(),
                        androidx.compose.ui.geometry.Size(c.size.width.toFloat(), c.size.height.toFloat())
                    )
                }
                .clip(shape)
        ) {
            GlassBox(
                backdrop = glassBackdrop,
                isDark = isDark,
                cornerRadius = size / 2,
                modifier = Modifier
                    .matchParentSize()
                    .then(if (glassBackdrop == null) Modifier.background(fallbackBackground) else Modifier),
                // 展开成竖条 / 方块后是带文字的面板：换高模糊面板玻璃
                panel = expanded
            ) { }
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部：主题方块时左侧显示返回 + 标题；右侧「词」
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(size),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (expanded && panel == 3) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { panel = 0 }
                                    .padding(start = 6.dp, end = 8.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    contentDescription = "返回",
                                    tint = contentColor.copy(alpha = 0.8f),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "主题与选项",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = contentColor,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(size)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = if (glassBackdrop != null) null else androidx.compose.foundation.LocalIndication.current
                            ) {
                                if (expanded) {
                                    expanded = false
                                    panel = 0
                                } else {
                                    expanded = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "词",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (expanded) accent else contentColor
                        )
                    }
                }

                // 内容：竖条 ⇄ 具体设置，向上滑入替换（返回竖条时向下）
                AnimatedContent(
                    targetState = if (expanded) panel else -1,
                    transitionSpec = {
                        val forward = targetState > initialState
                        val transform = if (forward) {
                            (slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(200, delayMillis = 60))) togetherWith
                                (slideOutVertically(tween(220)) { -it / 2 } + fadeOut(tween(140)))
                        } else {
                            (slideInVertically(tween(260)) { -it / 2 } + fadeIn(tween(200, delayMillis = 60))) togetherWith
                                (slideOutVertically(tween(220)) { it / 2 } + fadeOut(tween(140)))
                        }
                        transform using SizeTransform(clip = false)
                    },
                    contentAlignment = Alignment.TopEnd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    label = "ci_panel"
                ) { p ->
                    when (p) {
                        -1 -> Spacer(Modifier.size(1.dp))
                        0 -> Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.End
                        ) {
                            val items = listOf(
                                Triple(Icons.Filled.TextFields, "字号", contentColor),
                                Triple(Icons.Filled.Timer, "快慢", if (lyricsOffsetMs != 0L) accent else contentColor),
                                Triple(Icons.Filled.MoreHoriz, "主题与选项", contentColor)
                            )
                            items.forEachIndexed { i, (icon, label, tint) ->
                                Spacer(Modifier.height(iconGap))
                                Box(
                                    modifier = Modifier
                                        .padding(end = (size - iconSize) / 2)
                                        .size(iconSize)
                                        .clip(CircleShape)
                                        .clickable { panel = i + 1 },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(17.dp))
                                }
                            }
                        }
                        1 -> VerticalStepper(
                            valueText = "${fontSizeSp.toInt()}",
                            caption = "字号",
                            contentColor = contentColor,
                            onPlus = { onFontSizeChange((fontSizeSp + 2f).coerceAtMost(40f)) },
                            onMinus = { onFontSizeChange((fontSizeSp - 2f).coerceAtLeast(16f)) },
                            onBack = { panel = 0 }
                        )
                        2 -> {
                            val sec = lyricsOffsetMs / 1000.0
                            VerticalStepper(
                                valueText = when {
                                    lyricsOffsetMs > 0 -> "+%.1f".format(sec)
                                    lyricsOffsetMs < 0 -> "%.1f".format(sec)
                                    else -> "0"
                                },
                                caption = when {
                                    lyricsOffsetMs > 0 -> "提前"
                                    lyricsOffsetMs < 0 -> "延后"
                                    else -> "同步"
                                },
                                contentColor = if (lyricsOffsetMs != 0L) accent else contentColor,
                                onPlus = { onOffsetChange((lyricsOffsetMs + 500L).coerceAtMost(5000L)) },
                                onMinus = { onOffsetChange((lyricsOffsetMs - 500L).coerceAtLeast(-5000L)) },
                                onBack = { panel = 0 },
                                extra = {
                                    Spacer(Modifier.height(4.dp))
                                    StripIconButton(
                                        icon = Icons.Filled.Replay,
                                        description = "恢复同步",
                                        tint = if (lyricsOffsetMs != 0L) accent else contentColor.copy(alpha = 0.3f),
                                        enabled = lyricsOffsetMs != 0L
                                    ) { onOffsetChange(0L) }
                                }
                            )
                        }
                        else -> Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 8.dp)
                        ) {
                            LyricDisplayStyle.entries.forEach { style ->
                                CheckRow(
                                    title = style.title,
                                    subtitle = style.subtitle,
                                    checked = LyricDisplaySettings.style == style,
                                    radio = true,
                                    height = rowH,
                                    contentColor = contentColor,
                                    accent = accent
                                ) { LyricDisplaySettings.updateStyle(style) }
                            }
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(contentColor.copy(alpha = 0.10f))
                            )
                            CheckRow("逐字流光", null, LyricDisplaySettings.karaoke, false, rowH, contentColor, accent) {
                                LyricDisplaySettings.updateKaraoke(!LyricDisplaySettings.karaoke)
                            }
                            CheckRow("景深模糊", null, LyricDisplaySettings.depthBlur, false, rowH, contentColor, accent) {
                                LyricDisplaySettings.updateDepthBlur(!LyricDisplaySettings.depthBlur)
                            }
                            CheckRow("系统字体", null, LyricDisplaySettings.systemFont, false, rowH, contentColor, accent) {
                                LyricDisplaySettings.updateSystemFont(!LyricDisplaySettings.systemFont)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 竖条里的竖排调节：＋ / 数值 / －（可附加一个按钮）/ 收回 */
@Composable
private fun VerticalStepper(
    valueText: String,
    caption: String,
    contentColor: Color,
    onPlus: () -> Unit,
    onMinus: () -> Unit,
    onBack: () -> Unit,
    extra: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(4.dp))
        StripIconButton(Icons.Filled.Add, "加", contentColor, onClick = onPlus)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = valueText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = caption,
                    fontSize = 8.5.sp,
                    color = contentColor.copy(alpha = 0.55f),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        StripIconButton(Icons.Filled.Remove, "减", contentColor, onClick = onMinus)
        extra?.invoke()
        Spacer(Modifier.height(4.dp))
        StripIconButton(Icons.Filled.KeyboardArrowUp, "收回到竖条", contentColor.copy(alpha = 0.6f), size = 28.dp, filled = false, onClick = onBack)
    }
}

@Composable
private fun StripIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color,
    size: Dp = 30.dp,
    filled: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) tint.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/** 一行勾选项：主题（单选，选中显示 ✓）与开关（多选，勾选框） */
@Composable
private fun CheckRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    radio: Boolean,
    height: Dp,
    contentColor: Color,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 13.5.sp,
            fontWeight = if (checked && radio) FontWeight.Bold else FontWeight.Medium,
            color = if (checked && radio) accent else contentColor,
            maxLines = 1
        )
        if (subtitle != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = subtitle,
                fontSize = 10.5.sp,
                color = contentColor.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (radio) {
            if (checked) Icon(Icons.Filled.Check, contentDescription = "已选", tint = accent, modifier = Modifier.size(16.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (checked) accent else contentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                if (checked) Icon(Icons.Filled.Check, contentDescription = "已开启", tint = Color.White, modifier = Modifier.size(13.dp))
            }
        }
    }
}

@Composable
private fun StepperRow(
    valueText: String,
    caption: String,
    contentColor: Color,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepButton(Icons.Filled.Remove, "减", contentColor, onMinus)
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = valueText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                maxLines = 1
            )
            Text(text = caption, fontSize = 10.sp, color = contentColor.copy(alpha = 0.5f), maxLines = 1)
        }
        StepButton(Icons.Filled.Add, "加", contentColor, onPlus)
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    contentColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(contentColor.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = contentColor, modifier = Modifier.size(18.dp))
    }
}
