package com.lm.player.core.designsystem.component

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.lm.player.core.designsystem.glass.DampedDragAnimation
import com.lm.player.core.designsystem.theme.AppleRed
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 液态玻璃控件的「背后颜色」：控件放在纯色卡片 / 页面上时，按下后玻璃滑块折射这个颜色（示例里的 rememberCanvasBackdrop）。
 * 设置页的卡片提供卡片色；没提供时用页面底色。
 */
val LocalLiquidControlSurface = staticCompositionLocalOf<Color?> { null }

/**
 * 液态玻璃滑块 / 进度条 —— 照 Kyant0/AndroidLiquidGlass 示例的 LiquidSlider（Apache-2.0）适配：
 * 6dp 胶囊轨道 + 强调色填充；40×24 的玻璃滑块，平时是白色胶囊，按住 / 拖动时放大 1.5 倍变成透镜，
 * 里面看到的是被放大折射的轨道，带色散、环境高光与内阴影；拖动按速度横向拉长。
 *
 * 与示例的区别：
 * - 支持外部持续更新的值（播放进度）：拖动中及松手后片刻不跟外部值，避免进度回报滞后把滑块拽回去；
 * - [steps] 分档吸附；[onValueChangeFinished] 在松手 / 点按后回调；
 * - [backdrop] 为 null 时折射 [surfaceColor]（纯色），播放页等有玻璃背景时传入背景图层。
 */
@Composable
fun LiquidSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    /**
     * true：拖动中只预览（[onPreview]），松手 / 点按才回调 [onValueChange]。播放进度条用它：
     * 边拖边跳转时播放器回报的位置有延迟，会把滑块往回拽，表现为「进度漂移」。
     */
    commitOnRelease: Boolean = false,
    /** 拖动中的预览值（松手后回调 null），用来实时显示拖到的时间 */
    onPreview: ((Float?) -> Unit)? = null,
    accentColor: Color = AppleRed,
    trackColor: Color? = null,
    backdrop: Backdrop? = null,
    surfaceColor: Color? = null
) {
    // 液态玻璃关闭（或设备不支持 / 省电）时退回经典滑块，不再画玻璃滑块
    if (!com.lm.player.core.designsystem.glass.rememberLiquidGlassFull()) {
        ClassicSliderFallback(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
            commitOnRelease = commitOnRelease,
            onPreview = onPreview,
            accentColor = accentColor,
            trackColor = trackColor
        )
        return
    }
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val track = trackColor ?: if (isLight) Color(0xFF787878).copy(0.2f) else Color(0xFF787880).copy(0.36f)
    val surface = surfaceColor ?: LocalLiquidControlSurface.current ?: MaterialTheme.colorScheme.background
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onValueChange)
    val latestOnFinished by rememberUpdatedState(onValueChangeFinished)
    val latestOnPreview by rememberUpdatedState(onPreview)
    val latestEnabled by rememberUpdatedState(enabled)
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    fun snap(v: Float): Float {
        val c = v.coerceIn(valueRange)
        if (steps <= 0) return c
        val step = span / (steps + 1)
        return (valueRange.start + ((c - valueRange.start) / step).roundToInt() * step).coerceIn(valueRange)
    }

    val trackBackdrop = rememberLayerBackdrop()
    val surfaceBackdrop = rememberCanvasBackdrop { drawRect(surface) }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.CenterStart
    ) {
        val trackWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val animationScope = rememberCoroutineScope()
        // 拖动中：不跟外部值；松手后：等外部值追上松手位置（或 3 秒）再跟，避免播放器回报滞后把滑块拽回去
        var interacting by remember { mutableStateOf(false) }
        var holdTarget by remember { mutableStateOf<Float?>(null) }
        var holdUntil by remember { mutableLongStateOf(0L) }
        val dampedDragAnimation = remember(animationScope, valueRange) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = latestValue.coerceIn(valueRange),
                valueRange = valueRange,
                visibilityThreshold = span * 0.0005f,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = {},
                onDragStopped = {},
                onDrag = { _, _ -> }
            )
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { latestValue }
                .collectLatest { v ->
                    if (interacting) return@collectLatest
                    val target = holdTarget
                    if (target != null) {
                        if (abs(v - target) > span * 0.015f && SystemClock.uptimeMillis() < holdUntil) return@collectLatest
                        holdTarget = null
                    }
                    if (dampedDragAnimation.targetValue != v.coerceIn(valueRange)) {
                        dampedDragAnimation.updateValue(v)
                    }
                }
        }
        fun valueAt(x: Float): Float = snap(valueRange.start + span * (x / trackWidth).coerceIn(0f, 1f))
        fun commit(v: Float) {
            holdTarget = v
            holdUntil = SystemClock.uptimeMillis() + 3000L
            latestOnChange(v)
            latestOnFinished?.invoke()
            latestOnPreview?.invoke(null)
        }

        // 手势放在整条滑块上：滑块跟手指在轨道上的绝对位置走（不累计增量，不会漂移）；
        // 竖向滑动不拦截，交给外层列表滚动
        val gestureModifier = Modifier.pointerInput(trackWidth, valueRange, steps) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!latestEnabled) return@awaitEachGesture
                val slop = viewConfiguration.touchSlop
                var decided = false
                var horizontal = false
                var last = valueAt(down.position.x)
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        if (!decided) {
                            // 点按：滑到点按位置
                            val v = valueAt(change.position.x)
                            dampedDragAnimation.animateToValue(v)
                            commit(v)
                        } else if (horizontal) {
                            commit(last)
                        }
                        break
                    }
                    if (!decided) {
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (abs(dx) > slop || abs(dy) > slop) {
                            decided = true
                            horizontal = abs(dx) >= abs(dy)
                            if (!horizontal) break
                            interacting = true
                            dampedDragAnimation.press()
                        }
                    }
                    if (horizontal) {
                        change.consume()
                        last = valueAt(change.position.x)
                        dampedDragAnimation.updateValue(last)
                        if (commitOnRelease) latestOnPreview?.invoke(last) else latestOnChange(last)
                    }
                }
                if (horizontal) {
                    interacting = false
                    dampedDragAnimation.release()
                }
            }
        }

        Box(Modifier.layerBackdrop(trackBackdrop)) {
            Box(
                Modifier
                    .clip(Capsule())
                    .background(track)
                    .height(6.dp)
                    .fillMaxWidth()
            )
            Box(
                Modifier
                    .clip(Capsule())
                    .background(accentColor)
                    .height(6.dp)
                    .layout { measurable, c ->
                        val placeable = measurable.measure(c)
                        val width = (c.maxWidth * dampedDragAnimation.progress.coerceIn(0f, 1f)).roundToInt()
                        layout(width, placeable.height) { placeable.place(0, 0) }
                    }
            )
        }

        Box(
            Modifier
                .graphicsLayer {
                    translationX = (-size.width / 2f + trackWidth * dampedDragAnimation.progress.coerceIn(0f, 1f))
                        .coerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f)
                }
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop ?: surfaceBackdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = dampedDragAnimation.pressProgress
                            val sx = lerpF(2f / 3f, 1f, progress)
                            val sy = lerpF(0f, 1f, progress)
                            scale(sx, sy) { drawBackdrop() }
                        }
                    ),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(8.dp.toPx() * (1f - progress))
                        lens(
                            10.dp.toPx() * progress,
                            14.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        if (progress <= 0.001f) null else Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress
                        )
                    },
                    shadow = {
                        Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.05f))
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        if (progress <= 0.001f) null else InnerShadow(radius = 4.dp * progress, alpha = progress)
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress))
                    }
                )
                .size(40.dp, 24.dp)
        )

        // 手势层盖在最上面（整条宽、与滑块同高），不画任何东西
        Box(
            Modifier
                .matchParentSize()
                .then(if (enabled) gestureModifier else Modifier)
        )
    }
}

/**
 * 液态玻璃开关 —— 照 Kyant0 示例的 LiquidToggle（Apache-2.0）适配：64×28 胶囊轨道（关：灰；开：强调色），
 * 40×24 玻璃滑块平时是白色胶囊，按住 / 拖动时放大 1.5 倍变成透镜，可拖动也可点按切换。
 */
@Composable
fun LiquidToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accentColor: Color = AppleRed,
    backdrop: Backdrop? = null,
    surfaceColor: Color? = null
) {
    // 液态玻璃关闭时退回系统开关（开：强调色轨道）
    if (!com.lm.player.core.designsystem.glass.rememberLiquidGlassFull()) {
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accentColor,
                checkedBorderColor = Color.Transparent
            )
        )
        return
    }
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val trackColor = if (isLight) Color(0xFF787878).copy(0.2f) else Color(0xFF787880).copy(0.36f)
    val surface = surfaceColor ?: LocalLiquidControlSurface.current ?: MaterialTheme.colorScheme.background
    val latestChecked by rememberUpdatedState(checked)
    val latestOnChange by rememberUpdatedState(onCheckedChange)
    val latestEnabled by rememberUpdatedState(enabled)

    val density = LocalDensity.current
    val dragWidth = with(density) { 20.dp.toPx() }
    val animationScope = rememberCoroutineScope()
    var didDrag by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (checked) 1f else 0f) }
    val dampedDragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {
                if (!latestEnabled) {
                    didDrag = false
                    return@DampedDragAnimation
                }
                if (didDrag) {
                    fraction = if (targetValue >= 0.5f) 1f else 0f
                    latestOnChange(fraction == 1f)
                    didDrag = false
                } else {
                    fraction = if (latestChecked) 0f else 1f
                    latestOnChange(fraction == 1f)
                }
            },
            onDrag = { _, dragAmount ->
                if (!latestEnabled) return@DampedDragAnimation
                if (!didDrag) didDrag = dragAmount.x != 0f
                fraction = (fraction + dragAmount.x / dragWidth).coerceIn(0f, 1f)
            }
        )
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { fraction }.collectLatest { f -> dampedDragAnimation.updateValue(f) }
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { latestChecked }.collectLatest { isChecked ->
            val target = if (isChecked) 1f else 0f
            if (target != fraction) {
                fraction = target
                dampedDragAnimation.animateToValue(target)
            }
        }
    }

    val trackBackdrop = rememberLayerBackdrop()
    val surfaceBackdrop = rememberCanvasBackdrop { drawRect(surface) }

    Box(
        modifier.alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(Capsule())
                .drawBehind {
                    drawRect(lerp(trackColor, accentColor, dampedDragAnimation.value.coerceIn(0f, 1f)))
                }
                .size(64.dp, 28.dp)
        )

        Box(
            Modifier
                .graphicsLayer {
                    val padding = 2.dp.toPx()
                    translationX = lerpF(padding, padding + dragWidth, dampedDragAnimation.value)
                }
                .semantics { role = Role.Switch }
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop ?: surfaceBackdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = dampedDragAnimation.pressProgress
                            val sx = lerpF(2f / 3f, 0.75f, progress)
                            val sy = lerpF(0f, 0.75f, progress)
                            scale(sx, sy) { drawBackdrop() }
                        }
                    ),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(8.dp.toPx() * (1f - progress))
                        lens(
                            5.dp.toPx() * progress,
                            10.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        if (progress <= 0.001f) null else Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress
                        )
                    },
                    shadow = {
                        Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.05f))
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        if (progress <= 0.001f) null else InnerShadow(radius = 4.dp * progress, alpha = progress)
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 50f
                        scaleX /= 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress))
                    }
                )
                .size(40.dp, 24.dp)
        )
    }
}

/**
 * 未开液态玻璃时的经典滑块：参数语义与 [LiquidSlider] 一致。
 * [commitOnRelease] 时拖动只预览、松手才提交；提交后先停在松手的位置，等外部值追上（差 1.5% 以内）或 3 秒后再跟外部值，
 * 避免播放器回报滞后把滑块拽回去（进度漂移）。
 */
@Composable
private fun ClassicSliderFallback(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    onValueChangeFinished: (() -> Unit)?,
    commitOnRelease: Boolean,
    onPreview: ((Float?) -> Unit)?,
    accentColor: Color,
    trackColor: Color?
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    var dragValue by remember { mutableStateOf<Float?>(null) }
    var holdValue by remember { mutableStateOf<Float?>(null) }
    val latestOnChange by rememberUpdatedState(onValueChange)
    val latestOnFinished by rememberUpdatedState(onValueChangeFinished)
    val latestOnPreview by rememberUpdatedState(onPreview)
    LaunchedEffect(value, holdValue) {
        val held = holdValue ?: return@LaunchedEffect
        if (abs(value - held) <= span * 0.015f) {
            holdValue = null
        } else {
            kotlinx.coroutines.delay(3000)
            holdValue = null
        }
    }
    val shown = (dragValue ?: holdValue ?: value).coerceIn(valueRange)
    ClassicSlider(
        value = shown,
        onValueChange = { v ->
            if (commitOnRelease) {
                dragValue = v
                latestOnPreview?.invoke(v)
            } else {
                latestOnChange(v)
            }
        },
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = {
            if (commitOnRelease) {
                val v = dragValue
                if (v != null) {
                    holdValue = v
                    latestOnChange(v)
                }
                dragValue = null
                latestOnPreview?.invoke(null)
            }
            latestOnFinished?.invoke()
        },
        colors = androidx.compose.material3.SliderDefaults.colors(
            thumbColor = accentColor,
            activeTrackColor = accentColor,
            inactiveTrackColor = trackColor ?: accentColor.copy(alpha = 0.24f),
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent
        )
    )
}

private fun lerpF(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction
