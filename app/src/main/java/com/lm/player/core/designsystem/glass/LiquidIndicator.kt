package com.lm.player.core.designsystem.glass

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import androidx.compose.animation.core.EaseOut
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * iOS 26 式「液态玻璃滴」的物理状态 —— 照搬 Kyant0/AndroidLiquidGlass 示例的 DampedDragAnimation
 *（Apache-2.0，https://github.com/Kyant0/AndroidLiquidGlass），参数与示例一致：
 * - 位置 [value]（以「格」为单位，0 = 第一项）：拖动、点选、吸附都用临界阻尼、刚度 1000 的弹簧（紧跟手指、不过冲）；
 * - 按下 [pressProgress] → 1：玻璃滴放大到 [pressedScale]（示例为 78/56；横向弹簧 0.6、纵向 0.7，先横后竖带一点 Q 弹）；
 * - 拖动速度 [velocity] 让玻璃滴横向拉长、纵向压扁（各最多 ±20%）；
 * - 点选：按下 → 滑到目标格 → 接近目标（剩 2.5%）后抬起；
 * - 拖动时整条栏跟着手指方向做最多 4dp 的橡皮筋偏移（[panelOffset]），松手弹回。
 */
class LiquidIndicatorState internal constructor(
    private val scope: CoroutineScope,
    initialValue: Float,
    private val maxValue: Float,
    private val pressedScale: Float
) {
    private val range: Float get() = maxValue.coerceAtLeast(1f)
    private val valueAnim = Animatable(initialValue, 0.001f)
    private val velocityAnim = Animatable(0f, 5f)
    private val pressAnim = Animatable(0f, 0.001f)
    private val scaleXAnim = Animatable(1f, 0.001f)
    private val scaleYAnim = Animatable(1f, 0.001f)
    private val panelAnim = Animatable(0f)
    private val tracker = VelocityTracker()
    private var releaseToken = 0

    /** 最近一次要求的目标（同步记录；协程里的动画可能还没开始） */
    var intendedValue: Float = initialValue
        private set

    val value: Float get() = valueAnim.value
    /** 兼容旧接口：以前的「液体拖尾」已去掉（示例没有），与 [value] 相同 */
    val trailValue: Float get() = valueAnim.value
    val targetValue: Float get() = intendedValue
    val pressProgress: Float get() = pressAnim.value
    val velocity: Float get() = velocityAnim.value
    val scaleX: Float get() = scaleXAnim.value
    val scaleY: Float get() = scaleYAnim.value

    /** 还在滑向目标（滑动模式据此等玻璃滴到位后再收起轨道） */
    val isSettling: Boolean get() = abs(valueAnim.value - intendedValue) > 0.02f

    /** 速度形变后的横向缩放（示例：scaleX /= 1 - clamp(v·0.75, ±0.2)，v = velocity / 10） */
    val deformedScaleX: Float
        get() = scaleX / (1f - (velocity / 10f * 0.75f).coerceIn(-0.2f, 0.2f))

    /** 速度形变后的纵向缩放（示例：scaleY *= 1 - clamp(v·0.25, ±0.2)） */
    val deformedScaleY: Float
        get() = scaleY * (1f - (velocity / 10f * 0.25f).coerceIn(-0.2f, 0.2f))

    fun press() {
        releaseToken++
        tracker.resetTracking()
        scope.launch { pressAnim.animateTo(1f, PRESS_SPEC) }
        scope.launch { scaleXAnim.animateTo(pressedScale, SCALE_X_SPEC) }
        scope.launch { scaleYAnim.animateTo(pressedScale, SCALE_Y_SPEC) }
    }

    /** 抬起：等滑到目标附近（剩余 < 2.5%）再缩回 */
    fun release() {
        val token = ++releaseToken
        scope.launch {
            withFrameNanos { }
            if (token != releaseToken) return@launch
            if (abs(valueAnim.value - intendedValue) >= range * 0.025f) {
                snapshotFlow { valueAnim.value }
                    .filter { abs(it - intendedValue) < range * 0.025f }
                    .first()
            }
            if (token != releaseToken) return@launch
            launch { pressAnim.animateTo(0f, PRESS_SPEC) }
            launch { scaleXAnim.animateTo(1f, SCALE_X_SPEC) }
            launch { scaleYAnim.animateTo(1f, SCALE_Y_SPEC) }
        }
    }

    /** 拖动中：更新目标位置（示例 updateValue：临界阻尼弹簧跟手，同时记录速度） */
    fun dragTo(newValue: Float) {
        val target = newValue.coerceIn(0f, maxValue)
        intendedValue = target
        scope.launch {
            valueAnim.animateTo(target, VALUE_SPEC) { updateVelocity() }
        }
    }

    /** 点选 / 松手吸附（示例 animateToValue）：按下 → 滑到目标格 → 抬起 */
    fun animateTo(index: Float, withPress: Boolean = true) {
        val target = index.coerceIn(0f, maxValue)
        intendedValue = target
        scope.launch {
            if (withPress) press()
            launch { valueAnim.animateTo(target, VALUE_SPEC) }
            if (velocityAnim.value != 0f) launch { velocityAnim.animateTo(0f, VELOCITY_SPEC) }
            if (withPress) release()
        }
    }

    /** 不带动画直接就位（例如页面外部切换导致的同步） */
    fun snapTo(index: Float) {
        val target = index.coerceIn(0f, maxValue)
        intendedValue = target
        scope.launch {
            valueAnim.snapTo(target)
            velocityAnim.snapTo(0f)
        }
    }

    /** 外部驱动位置（例如 Pager 的滚动进度），同样计算速度形变 */
    fun follow(externalValue: Float) {
        val target = externalValue.coerceIn(0f, maxValue)
        intendedValue = target
        scope.launch {
            valueAnim.snapTo(target)
            updateVelocity()
        }
    }

    /** 拖动时整条栏的橡皮筋偏移：累计手指位移（px） */
    fun dragPanel(dxPx: Float) {
        scope.launch { panelAnim.snapTo(panelAnim.value + dxPx) }
    }

    /** 松手：橡皮筋偏移弹回 */
    fun releasePanel() {
        scope.launch { panelAnim.animateTo(0f, spring(1f, 300f, 0.5f)) }
    }

    /** 栏的橡皮筋偏移（示例：最多 [maxOffsetPx]，按「位移 / 栏宽」做 EaseOut 饱和） */
    fun panelOffset(widthPx: Float, maxOffsetPx: Float): Float {
        if (widthPx <= 0f) return 0f
        val fraction = (panelAnim.value / widthPx).coerceIn(-1f, 1f)
        return maxOffsetPx * sign(fraction) * EaseOut.transform(abs(fraction))
    }

    private fun updateVelocity() {
        tracker.addPosition(SystemClock.uptimeMillis(), Offset(valueAnim.value, 0f))
        val v = tracker.calculateVelocity().x / range
        scope.launch { velocityAnim.animateTo(v, VELOCITY_SPEC) }
    }

    private companion object {
        val VALUE_SPEC = spring<Float>(1f, 1000f, 0.001f)
        val VELOCITY_SPEC = spring<Float>(0.5f, 300f, 0.01f)
        val PRESS_SPEC = spring<Float>(1f, 1000f, 0.001f)
        val SCALE_X_SPEC = spring<Float>(0.6f, 250f, 0.001f)
        val SCALE_Y_SPEC = spring<Float>(0.7f, 250f, 0.001f)
    }
}

/**
 * @param pressedScale 按下放大倍数，默认与示例一致（78 / 56）
 * @param pressedScaleY / [deform] 旧参数，已不再使用（按示例统一等比放大 + 速度形变）
 */
@Composable
fun rememberLiquidIndicatorState(
    initialIndex: Int,
    tabCount: Int,
    pressedScale: Float = 78f / 56f,
    @Suppress("UNUSED_PARAMETER") pressedScaleY: Float = pressedScale,
    @Suppress("UNUSED_PARAMETER") deform: Boolean = true
): LiquidIndicatorState {
    val scope = rememberCoroutineScope()
    return remember(scope, tabCount) {
        LiquidIndicatorState(scope, initialIndex.toFloat(), (tabCount - 1).coerceAtLeast(0).toFloat(), pressedScale)
    }
}

/** 玻璃珠按下时，珠子里看到的按钮放大倍数（示例 LocalLiquidBottomTabScale：1 → 1.2） */
val LocalBarDropletContentScale = androidx.compose.runtime.staticCompositionLocalOf { { 1f } }

/**
 * 播放栏本体对玻璃珠的反馈（示例 LiquidBottomTabs 的栏）：
 * 按下时整条栏微微放大（+16dp）、拖动时橡皮筋偏移（最多 4dp）、玻璃珠所在位置一圈柔光（交互高光）。
 *
 * @param slotWidthPx 每格宽度；[startPadPx] 第 0 格左边距（用来算柔光中心）
 */
fun Modifier.liquidBarResponse(
    state: LiquidIndicatorState,
    slotWidthPx: Float,
    startPadPx: Float,
    enabled: Boolean = true
): Modifier = if (!enabled) this else this
    .graphicsLayer {
        translationX = state.panelOffset(size.width, 4.dp.toPx())
        val s = 1f + (16.dp.toPx() / size.width.coerceAtLeast(1f)) * state.pressProgress
        scaleX = s
        scaleY = s
    }
    .drawWithContent {
        drawContent()
        val p = state.pressProgress
        if (p <= 0.001f) return@drawWithContent
        val radius = size.height / 2f
        val path = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    0f, 0f, size.width, size.height,
                    androidx.compose.ui.geometry.CornerRadius(radius, radius)
                )
            )
        }
        clipPath(path) {
            drawRect(Color.White.copy(alpha = 0.08f * p), blendMode = androidx.compose.ui.graphics.BlendMode.Plus)
            val cx = (startPadPx + (state.value + 0.5f) * slotWidthPx).coerceIn(0f, size.width)
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.15f * p), Color.Transparent),
                    center = Offset(cx, size.height / 2f),
                    radius = size.minDimension * 1.5f
                ),
                blendMode = androidx.compose.ui.graphics.BlendMode.Plus
            )
        }
    }

/**
 * 玻璃滴本体：放在一个以「第 0 格左上角」为原点的容器里，按 [state] 平移 / 缩放。
 * 静止时是淡色胶囊；按下 / 拖动时淡入一块折射 + 色散的透镜玻璃（[backdrop] 为 null 时只有胶囊）。
 * 缩放放在玻璃的 layerBlock 里，库会反向抵消，背景不跟着放大，只有边缘折射。
 *
 * @param slotWidthPx 每一格宽度（px），玻璃滴中心对齐格中心
 */
@Composable
fun LiquidDroplet(
    state: LiquidIndicatorState,
    slotWidthPx: Float,
    width: Dp,
    height: Dp,
    isDark: Boolean,
    backdrop: com.kyant.backdrop.Backdrop?,
    modifier: Modifier = Modifier,
    restTint: Color = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)
) {
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier = modifier
            .graphicsLayer {
                val offsetToCenter = (slotWidthPx - width.toPx()) / 2f
                translationX = state.value * slotWidthPx + offsetToCenter
            }
            .size(width, height)
    ) {
        // 静止态：淡色胶囊（按下后淡出）
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = state.deformedScaleX
                    scaleY = state.deformedScaleY
                    alpha = 1f - state.pressProgress
                }
                .clip(shape)
                .background(restTint)
        )
        // 按下 / 拖动态：透镜玻璃（始终在组合里，只改透明度）
        if (backdrop != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = state.pressProgress }
                    .liquidGlass(
                        backdrop = backdrop,
                        shape = shape,
                        tint = Color.White.copy(alpha = (if (isDark) 0.06f else 0.18f) * (0.35f + 0.65f * GlassStyleState.opacityLevel)),
                        blurAmount = 0f,
                        dispersion = 0.28f,
                        material = GlassMaterial.CLEAR,
                        layerBlock = {
                            scaleX = state.deformedScaleX
                            scaleY = state.deformedScaleY
                        }
                    )
            )
        }
    }
}

// =====================================================================================
// 播放栏玻璃珠（滑动模式导航条 / 展开式导航胶囊）
//
// 做法来自 Kyant0/AndroidLiquidGlass 示例里的 LiquidBottomTabs（Apache-2.0）：
// 把「播放栏玻璃 + 强调色按钮」再画一份（屏幕上透明，只录成图层 tabsLayer），
// 玻璃珠采样「主界面 + tabsLayer」的叠加并做透镜折射 —— 珠子在播放栏里面看到的是播放栏自己的按钮，
// 按下放大后超出播放栏的那一圈才透出栏下面的内容；静止时就是一块带淡色的胶囊，里面的按钮呈强调色。
//
// [BarDropletBase] / [hideUnderBarDroplet] 保留原调用（玻璃模式下不再需要额外绘制），
// 实际绘制都在 [BarDropletMagnifier]（放在按钮行之后，叠在最上面，不挡点击）。
// =====================================================================================

/** 播放栏玻璃珠的液体形状：中心（以格为单位）与横 / 纵缩放（含按下放大） */
class BarDropShape(val centerValue: Float, val scaleX: Float, val scaleY: Float)

/** 玻璃珠当前形状：位置 = [LiquidIndicatorState.value]，缩放 = 按压放大 × 示例的速度形变 */
@Suppress("UNUSED_PARAMETER")
fun LiquidIndicatorState.barDropShape(slotWidthPx: Float, widthPx: Float): BarDropShape =
    BarDropShape(value, deformedScaleX, deformedScaleY)

/** 玻璃珠在播放栏坐标系里的矩形（随动画逐帧变化，在 draw 阶段调用） */
fun LiquidIndicatorState.dropletRectInBar(
    slotWidthPx: Float,
    startPadPx: Float,
    widthPx: Float,
    heightPx: Float,
    barHeightPx: Float
): androidx.compose.ui.geometry.Rect {
    val shape = barDropShape(slotWidthPx, widthPx)
    val tx = shape.centerValue * slotWidthPx + (slotWidthPx - widthPx) / 2f
    val cx = startPadPx + tx + widthPx / 2f
    val cy = barHeightPx / 2f
    val hw = widthPx / 2f * shape.scaleX
    val hh = heightPx / 2f * shape.scaleY
    return androidx.compose.ui.geometry.Rect(cx - hw, cy - hh, cx + hw, cy + hh)
}

/**
 * 珠子底层。玻璃模式（有 [backdrop]）下静止胶囊改由 [BarDropletMagnifier] 里的玻璃珠一起画，这里不再绘制；
 * 没有玻璃时画原来的淡色胶囊。
 */
@Composable
fun androidx.compose.foundation.layout.BoxScope.BarDropletBase(
    state: LiquidIndicatorState,
    slotWidthPx: Float,
    startPad: Dp,
    width: Dp,
    height: Dp,
    @Suppress("UNUSED_PARAMETER") barWidthPx: Float,
    @Suppress("UNUSED_PARAMETER") barHeightPx: Float,
    @Suppress("UNUSED_PARAMETER") barStyle: BarGlassStyle,
    isDark: Boolean,
    backdrop: com.kyant.backdrop.Backdrop?,
    restTint: Color = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)
) {
    if (backdrop != null) return
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier = Modifier
            .align(androidx.compose.ui.Alignment.CenterStart)
            .offset(x = startPad)
            .graphicsLayer {
                val drop = state.barDropShape(slotWidthPx, width.toPx())
                translationX = drop.centerValue * slotWidthPx + (slotWidthPx - width.toPx()) / 2f
                scaleX = drop.scaleX
                scaleY = drop.scaleY
            }
            .size(width, height)
            .clip(shape)
            .background(restTint)
    )
}

/** 旧实现里用来在珠子范围内隐藏原按钮；新做法玻璃珠直接盖在按钮上面，不再需要 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.hideUnderBarDroplet(
    state: LiquidIndicatorState,
    slotWidthPx: Float,
    startPad: Dp,
    width: Dp,
    height: Dp
): Modifier = this

/**
 * 播放栏玻璃珠（叠在按钮行最上面，不挡点击）。
 *
 * @param content 强调色版本的按钮行（与可见按钮行同布局）：录进 tabsLayer，珠子覆盖到哪一格，那一格就显示强调色
 */
@Composable
fun androidx.compose.foundation.layout.BoxScope.BarDropletMagnifier(
    state: LiquidIndicatorState,
    slotWidthPx: Float,
    startPad: Dp,
    width: Dp,
    height: Dp,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    val backdrop = LocalGlassBackdrop.current ?: return
    val isDark = androidx.compose.material3.MaterialTheme.colorScheme.background.luminance() < 0.5f
    val barStyle = GlassStyleState.barStyle ?: barGlassStyle(isDark, GlassStyleState.opacity, GlassStyleState.blurDp)
    // 静止胶囊底色与示例一致：浅色 10% 黑、深色 10% 白
    val restTint = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.10f)
    val capsule = RoundedCornerShape(50)
    val tabsLayer = rememberLayerBackdrop()

    // 1) 播放栏再画一份（玻璃底 + 强调色按钮）：屏幕上完全透明，只录成图层给玻璃珠采样
    Box(
        modifier = Modifier
            .matchParentSize()
            .clearAndSetSemantics { }
            .alpha(0f)
            .layerBackdrop(tabsLayer)
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { capsule },
                    effects = {
                        val p = state.pressProgress
                        when (barStyle.material) {
                            GlassMaterial.REGULAR -> vibrancy()
                            GlassMaterial.CLEAR -> colorControls(saturation = 1.25f)
                        }
                        if (barStyle.blurAmount > 0f) blur((barStyle.blurAmount * 24f).dp.toPx())
                        // 示例：隐藏栏的透镜随按压从 0 增强到 24dp，珠子里看到的栏有明显的边缘折射
                        lens(24.dp.toPx() * p, 24.dp.toPx() * p)
                    },
                    highlight = { Highlight.Default.copy(alpha = state.pressProgress) },
                    shadow = null,
                    onDrawSurface = { if (barStyle.tint.alpha > 0f) drawRect(barStyle.tint) }
                )
        )
        androidx.compose.runtime.CompositionLocalProvider(
            LocalBarDropletContentScale provides { 1f + 0.2f * state.pressProgress }
        ) {
            Box(
                modifier = Modifier.matchParentSize(),
                contentAlignment = androidx.compose.ui.Alignment.CenterStart,
                content = content
            )
        }
    }

    // 2) 玻璃珠：静止 = 淡色胶囊（里面按钮呈强调色）；按下 / 拖动 = 放大的透镜，带色散、高光、投影与内阴影
    val combined = rememberCombinedBackdrop(backdrop, tabsLayer)
    Box(
        modifier = Modifier
            .align(androidx.compose.ui.Alignment.CenterStart)
            .offset(x = startPad)
            .graphicsLayer {
                val drop = state.barDropShape(slotWidthPx, width.toPx())
                translationX = drop.centerValue * slotWidthPx + (slotWidthPx - width.toPx()) / 2f
            }
            .size(width, height)
            .drawBackdrop(
                backdrop = combined,
                shape = { capsule },
                effects = {
                    val p = state.pressProgress
                    lens(
                        10.dp.toPx() * p,
                        14.dp.toPx() * p,
                        chromaticAberration = true
                    )
                },
                highlight = { Highlight.Default.copy(alpha = state.pressProgress) },
                shadow = { Shadow(alpha = state.pressProgress) },
                innerShadow = {
                    val p = state.pressProgress
                    InnerShadow(radius = 8.dp * p, alpha = p)
                },
                layerBlock = {
                    val drop = state.barDropShape(slotWidthPx, width.toPx())
                    scaleX = drop.scaleX
                    scaleY = drop.scaleY
                },
                onDrawSurface = {
                    val p = state.pressProgress
                    drawRect(restTint, alpha = 1f - p)
                    drawRect(Color.Black.copy(alpha = 0.03f * p))
                }
            )
    )
}
