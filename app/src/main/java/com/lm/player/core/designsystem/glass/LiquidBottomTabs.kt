package com.lm.player.core.designsystem.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asComposeShader
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.isRuntimeShaderSupported
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/*
 * 底栏标签 —— 原样移植 Kyant0/AndroidLiquidGlass 示例 catalog/components 下的
 * LiquidBottomTabs.kt / LiquidBottomTab.kt 与 utils/InteractiveHighlight.kt（Apache-2.0，
 * https://github.com/Kyant0/AndroidLiquidGlass）。尺寸、弹簧、玻璃效果、水滴形状与示例完全一致：
 * 栏高 64dp、内边距 4dp、水滴 56dp 高、宽 = 一格（(栏宽 − 8dp) / 格数），按下放大 78/56，
 * 栏玻璃 vibrancy + blur 8dp + lens 24dp/24dp，水滴透镜 10dp/14dp 带色散、高光、投影、内阴影。
 * 本项目改动：强调色用应用主色；栏底色浓淡与模糊跟随设置里两个滑块（默认值时与示例相同）。
 */

internal val LocalLiquidBottomTabScale = staticCompositionLocalOf { { 1f } }

/** 标签栏是否可交互（常驻组合但收起 / 隐藏时为 false：格子不可点） */
internal val LocalLiquidBottomTabsActive = staticCompositionLocalOf { true }

@Composable
fun RowScope.LiquidBottomTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalLiquidBottomTabScale.current
    // 看不见（常驻但收起）时整个去掉 clickable：禁用状态的 clickable 仍会消费按下 / 抬起，
    // 会把落在播放栏圆钮上的点击吃掉（圆钮点了没反应）
    val active = LocalLiquidBottomTabsActive.current
    Column(
        modifier
            .clip(Capsule())
            .then(
                if (active) Modifier.clickable(
                    interactionSource = null,
                    indication = null,
                    role = Role.Tab,
                    onClick = onClick
                ) else Modifier
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

@Composable
fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFFFF2D55),
    /** 栏高（示例 64dp）与内边距（示例 4dp）；水滴高 = 栏高 − 2 × 内边距，透镜等效果按栏高等比缩放 */
    barHeight: Dp = 64.dp,
    innerPadding: Dp = 4.dp,
    /** 栏底色；null = 示例的浅色 #FAFAFA / 深色 #121212（40%，随设置滑块） */
    containerColorOverride: Color? = null,
    /** 外部手势驱动水滴（播放栏左侧圆钮按下拖出时），null = 只用示例自带的手势 */
    controller: LiquidBottomTabsController? = null,
    /**
     * false：组件常驻组合但当前看不见（播放栏轨道收起 / 切换胶囊收起）—— 水滴与格子不响应触摸，
     * 外部切页时水滴直接就位而不是滑过去（否则「还在滑」会把轨道撑开）。
     */
    active: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val latestActive = androidx.compose.runtime.rememberUpdatedState(active)
    // 组件常驻组合后下面的 LaunchedEffect 不再重启：必须读最新的回调，否则一直用第一次组合时的
    // （它记着当时的页面 = 首页，之后从设置 / 资料库拖回首页会被当成「已在首页」而不切页）
    val latestOnTabSelected = androidx.compose.runtime.rememberUpdatedState(onTabSelected)
    val k = barHeight / 64.dp
    val dropletHeight = barHeight - innerPadding * 2
    val isLightTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
    // 示例：浅色 #FAFAFA 40%、深色 #121212 40%；本项目跟随设置滑块（默认 0.85 / 16dp 时与示例一致）
    val opacityFactor = (GlassStyleState.opacityLevel / 0.8333f).coerceIn(0f, 1.6f)
    val blurFactor = (GlassStyleState.blurLevel / 0.5333f).coerceIn(0f, 1.8f)
    val containerColor = containerColorOverride ?:
        if (isLightTheme) Color(0xFFFAFAFA).copy((0.4f * opacityFactor).coerceIn(0f, 0.9f))
        else Color(0xFF121212).copy((0.4f * opacityFactor).coerceIn(0f, 0.9f))

    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - (innerPadding * 2).toPx()) / tabsCount
        }

        // 栏宽会变（播放栏里的轨道从圆钮展开），拖动换算始终用当前格宽
        val latestTabWidth = androidx.compose.runtime.rememberUpdatedState(tabWidth)
        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).coerceIn(-1f, 1f)
                with(density) {
                    4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val animationScope = rememberCoroutineScope()
        // 常驻组合：不随 selectedTabIndex 这个 lambda 实例重建（拖动动画里的松手回调记着的是这一份状态）
        val latestSelectedTabIndex = androidx.compose.runtime.rememberUpdatedState(selectedTabIndex)
        var currentIndex by remember {
            mutableIntStateOf(selectedTabIndex())
        }
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue((targetValue + dragAmount.x / latestTabWidth.value).coerceIn(0f, (tabsCount - 1).toFloat()))
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            ).also { it.gestureEnabled = { latestActive.value } }
        }
        // 标签栏离开组合（播放栏轨道收起）时解绑，否则下次从圆钮直接拖出时「按下」会发给已经销毁的旧实例，新水滴不浮起
        if (controller != null) {
            androidx.compose.runtime.DisposableEffect(controller, dampedDragAnimation) {
                onDispose {
                    if (controller.dda === dampedDragAnimation) controller.unbind()
                }
            }
        }
        if (controller != null) {
            androidx.compose.runtime.SideEffect {
                controller.dda = dampedDragAnimation
                controller.begin = { index ->
                    // 水滴先直接跳到手指所在格再浮起（不从原来那格滑过来）
                    dampedDragAnimation.snapToValue(index)
                    dampedDragAnimation.press()
                }
                controller.dragToIndex = { index, dx ->
                    dampedDragAnimation.updateValue(index.coerceIn(0f, (tabsCount - 1).toFloat()))
                    animationScope.launch { offsetAnimation.snapTo(offsetAnimation.value + dx) }
                }
                controller.stop = {
                    dampedDragAnimation.onDragStopped.invoke(dampedDragAnimation)
                    dampedDragAnimation.release()
                }
                if (controller.pendingPress) {
                    controller.pendingPress = false
                    controller.begin?.invoke(controller.pendingIndex ?: dampedDragAnimation.value)
                    controller.pendingIndex = null
                }
                controller.pendingIndex?.let { index ->
                    controller.pendingIndex = null
                    controller.dragToIndex?.invoke(index, 0f)
                }
            }
        }
        // 外部同步来的选中格（切页、Pager 左右滑）：只移动水滴，不再回调 onTabSelected（只有用户点选 / 拖动松手才回调）
        val syncedIndex = remember { intArrayOf(selectedTabIndex()) }
        LaunchedEffect(Unit) {
            snapshotFlow { latestSelectedTabIndex.value() }
                .collectLatest { index ->
                    syncedIndex[0] = index
                    currentIndex = index
                }
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    if (latestActive.value) dampedDragAnimation.animateToValue(index.toFloat())
                    else dampedDragAnimation.snapToValue(index.toFloat())
                    if (index != syncedIndex[0]) latestOnTabSelected.value(index)
                }
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                enabled = { latestActive.value },
                position = { size, _ ->
                    Offset(
                        (dampedDragAnimation.value + 0.5f) * latestTabWidth.value + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        // 可见的栏
        CompositionLocalProvider(LocalLiquidBottomTabsActive provides active) {
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(8.dp.toPx() * blurFactor)
                        lens(24.dp.toPx() * k, 24.dp.toPx() * k)
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerpTab(1f, 1f + 16.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(interactiveHighlight.modifier)
                .height(barHeight)
                .fillMaxWidth()
                .padding(innerPadding),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
        }

        // 隐藏的强调色副本：屏幕上透明，只录成图层给水滴采样（水滴到哪一格，哪一格显示强调色并放大）
        CompositionLocalProvider(
            LocalLiquidBottomTabsActive provides active,
            LocalLiquidBottomTabScale provides {
                lerpTab(1f, 1.2f, dampedDragAnimation.pressProgress)
            }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            blur(8.dp.toPx() * blurFactor)
                            lens(24.dp.toPx() * k * progress, 24.dp.toPx() * k * progress)
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            if (progress <= 0.001f) null else Highlight.Default.copy(alpha = progress)
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(dropletHeight)
                    .fillMaxWidth()
                    .padding(horizontal = innerPadding)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        // 水滴
        Box(
            Modifier
                .padding(horizontal = innerPadding)
                .graphicsLayer {
                    translationX = dampedDragAnimation.value * tabWidth + panelOffset
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        lens(
                            10.dp.toPx() * k * progress,
                            14.dp.toPx() * k * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        if (progress <= 0.001f) null else Highlight.Default.copy(alpha = progress)
                    },
                    shadow = {
                        val progress = dampedDragAnimation.pressProgress
                        if (progress <= 0.001f) null else Shadow(alpha = progress)
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        if (progress <= 0.001f) null else InnerShadow(radius = 8.dp * k * progress, alpha = progress)
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
                        drawRect(
                            if (isLightTheme) Color.Black.copy(0.1f) else Color.White.copy(0.1f),
                            alpha = 1f - progress
                        )
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    }
                )
                .height(dropletHeight)
                .fillMaxWidth(1f / tabsCount)
        )
    }
}

/** 原样移植示例的 InteractiveHighlight：按住时玻璃上跟随手指的一圈柔光 */
class InteractiveHighlight(
    val animationScope: CoroutineScope,
    /** false 时不响应按压（组件常驻但看不见） */
    val enabled: () -> Boolean = { true },
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset }
) {

    private val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero
    private var gestureOn = false
    val pressProgress: Float get() = pressProgressAnimation.value
    val offset: Offset get() = positionAnimation.value - startPosition

    private val shader =
        if (isRuntimeShaderSupported()) {
            RuntimeShader(
                """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}"""
            )
        } else {
            null
        }

    val modifier: Modifier =
        Modifier.drawWithContent {
            val progress = pressProgressAnimation.value
            if (progress > 0f) {
                if (shader != null) {
                    drawRect(Color.White.copy(0.08f * progress), blendMode = BlendMode.Plus)
                    shader.apply {
                        val position = position(size, positionAnimation.value)
                        setFloatUniform("size", size.width, size.height)
                        setColorUniform("color", Color.White.copy(0.15f * progress))
                        setFloatUniform("radius", size.minDimension * 1.5f)
                        setFloatUniform(
                            "position",
                            position.x.coerceIn(0f, size.width),
                            position.y.coerceIn(0f, size.height)
                        )
                    }
                    drawRect(ShaderBrush(shader.asComposeShader()), blendMode = BlendMode.Plus)
                } else {
                    drawRect(Color.White.copy(0.25f * progress), blendMode = BlendMode.Plus)
                }
            }
            drawContent()
        }

    val gestureModifier: Modifier =
        Modifier.pointerInput(animationScope) {
            inspectDragGestures(
                onDragStart = { down ->
                    gestureOn = enabled()
                    if (!gestureOn) return@inspectDragGestures
                    startPosition = down.position
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                        launch { positionAnimation.snapTo(startPosition) }
                    }
                },
                onDragEnd = {
                    if (!gestureOn) return@inspectDragGestures
                    gestureOn = false
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                },
                onDragCancel = {
                    if (!gestureOn) return@inspectDragGestures
                    gestureOn = false
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                }
            ) { change, _ ->
                if (gestureOn) animationScope.launch { positionAnimation.snapTo(change.position) }
            }
        }
}

private fun lerpTab(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

/**
 * 让外部手势驱动示例标签栏的水滴（与直接拖水滴同一套物理）：
 * 播放栏左侧圆钮按下拖动时轨道才展开，标签栏还没组合出来就收到「按下」，先记下，组合好后补上。
 */
class LiquidBottomTabsController {
    internal var dda: DampedDragAnimation? by androidx.compose.runtime.mutableStateOf(null)
    internal var begin: ((Float) -> Unit)? = null
    internal var dragToIndex: ((Float, Float) -> Unit)? = null
    internal var stop: (() -> Unit)? = null
    internal var pendingPress = false
    internal var pendingIndex: Float? = null

    /** 开始拖动：水滴直接出现在 [startIndex]（格序号，可带小数）并浮起 */
    fun beginDrag(startIndex: Float) {
        val b = begin
        if (b != null) {
            b(startIndex)
        } else {
            pendingPress = true
            pendingIndex = startIndex
        }
    }

    /** 拖动中：水滴中心跟到 [index]（格序号，可带小数，由调用方按最终格宽换算）；[dxPx] 用于栏的橡皮筋偏移 */
    fun dragTo(index: Float, dxPx: Float) {
        val d = dragToIndex
        if (d != null) d(index, dxPx) else pendingIndex = index
    }

    /** 松手：吸附到最近一格（并回调 onTabSelected），水滴落下；标签栏还没组合出来就松手时返回 false（由调用方自己切页） */
    fun endDrag(): Boolean {
        pendingPress = false
        pendingIndex = null
        val s = stop ?: return false
        s()
        return true
    }

    internal fun unbind() {
        dda = null
        begin = null
        dragToIndex = null
        stop = null
    }

    /** 水滴正被按住 / 拖动 */
    val isInteracting: Boolean get() = (dda?.pressProgress ?: 0f) > 0.01f

    /** 水滴还在滑向目标格 */
    val isSettling: Boolean get() = dda?.let { abs(it.value - it.targetValue) > 0.02f } ?: false
}

