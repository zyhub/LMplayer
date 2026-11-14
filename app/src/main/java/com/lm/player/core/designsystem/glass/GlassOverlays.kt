package com.lm.player.core.designsystem.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * 液态玻璃模式下把 [content] 放到一个上层（[GlassTopLayer]）里画，让其中的玻璃能折射主界面；
 * 未启用时原地直接画，行为与原来完全一致。
 */
@Composable
fun MaybeGlassTopLayer(
    hidden: Boolean,
    zOrder: Float,
    content: @Composable () -> Unit
) {
    // 悬浮按钮、搜索结果面板：高档才放上层画玻璃，低档原地画原样式
    if (!rememberLiquidGlassFull()) {
        content()
        return
    }
    GlassTopLayer(hidden = hidden, zOrder = zOrder) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

// =====================================================================================
// 下拉菜单：液态玻璃模式下换成玻璃面板（Material3 1.2 的 DropdownMenu 不能改容器颜色）
// =====================================================================================

/**
 * 与 [DropdownMenu] 同用法。启用液态玻璃时弹出一个玻璃面板，否则原样使用 Material3 下拉菜单。
 * 面板在独立的弹窗窗口里，玻璃跨窗口采样下面的界面（库按屏幕坐标对齐）：
 * 有 [LocalGlassBackdrop]（底栏 / 顶部固定按钮所在的上层）就采样它，否则采样弹出位置所在的视图（主界面 / 播放页）。
 *
 * @param alignStart 面板左边与锚点左边对齐（默认右对齐，适合右侧按钮）
 * @param scrollable 内容超高时整体滚动；内容自己带滚动列表时传 false
 * @param preferAbove 优先向上展开（放不下再向下），例如播放页下半部的「加入歌单」
 */
@Composable
fun AdaptiveDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    alignStart: Boolean = false,
    scrollable: Boolean = true,
    preferAbove: Boolean = false,
    /** 从锚点（如搜索栏）里液态「涌出」：紧贴锚点、纵向从细条弹性长开，横向略收后舒展 */
    emerge: Boolean = false,
    /** 面板水平居中对齐锚点（如播放页的定时按钮） */
    alignCenter: Boolean = false,
    /** 面板圆角（窄竖条面板用大圆角，像「词」调节展开那样） */
    cornerRadius: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    // 底栏搜索胶囊里涌出的音源面板属于播放栏，低档也用玻璃；其余下拉菜单只在高档用玻璃
    val glassActive = if (emerge) rememberLiquidGlassActive() else rememberLiquidGlassFull()
    // 有上层提供的背景（底栏 / 顶部按钮所在的上层）就用它；否则采样主界面整页（弹窗是独立窗口，按屏幕坐标对齐）
    val outerBackdrop = LocalGlassBackdrop.current
    if (!glassActive || (outerBackdrop == null && AppGlassBackdrop.layer == null)) {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, content = content)
        return
    }
    if (!expanded) return
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val style = panelGlassStyle(isDark)
    val gapPx = with(LocalDensity.current) { (if (emerge) 4.dp else 6.dp).roundToPx() }
    val positionProvider = remember(gapPx, alignStart, preferAbove, alignCenter) { DropdownLikePositionProvider(gapPx, alignStart, preferAbove, alignCenter) }
    // 液态展开：从锚点一侧（向上展开时从底边、向下时从顶边）弹性长出来
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        GlassRefreshController.keepLive()
        appear.animateTo(1f, if (emerge) spring(dampingRatio = 0.62f, stiffness = 300f) else spring(dampingRatio = 0.72f, stiffness = 380f))
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)
    ) {
        // 在弹窗自己的窗口里取背景（不能在主界面里取：那样图层会采样到包含自己的内容）
        val backdrop = outerBackdrop ?: rememberAppGlassBackdrop()
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
            GlassSurface(
                style = style,
                cornerRadius = cornerRadius,
                modifier = modifier
                    .then(
                        if (backdrop == null) Modifier.background(
                            style.tint.copy(alpha = maxOf(style.tint.alpha, 0.96f)),
                            androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius)
                        ) else Modifier
                    )
                    .graphicsLayer {
                        val p = appear.value
                        alpha = (p * (if (emerge) 2.4f else 1.6f)).coerceIn(0f, 1f)
                        scaleX = if (emerge) 0.94f + 0.06f * p else 0.90f + 0.10f * p
                        scaleY = if (emerge) 0.12f + 0.88f * p else 0.78f + 0.22f * p
                        transformOrigin = TransformOrigin(
                            if (emerge || alignCenter) 0.5f else if (alignStart) 0.1f else 0.9f,
                            if (positionProvider.placedAbove) 1f else 0f
                        )
                    }
                    .width(IntrinsicSize.Max)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        .padding(vertical = 6.dp),
                    content = content
                )
            }
        }
    }
}

/** 默认锚点下方放得下就放下方，否则放上方；[preferAbove] 时反过来 */
private class DropdownLikePositionProvider(
    private val gapPx: Int,
    private val alignStart: Boolean = false,
    private val preferAbove: Boolean = false,
    private val alignCenter: Boolean = false
) : PopupPositionProvider {
    /** 最近一次是否放在锚点上方（展开动画据此决定从哪条边长出来） */
    var placedAbove: Boolean = preferAbove
        private set

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        // 右对齐锚点（按钮多在右侧），放不下时向左挪
        val x = when {
            alignCenter -> anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
            alignStart -> anchorBounds.left
            else -> anchorBounds.right - popupContentSize.width
        }.coerceIn(0, maxX)
        val below = anchorBounds.bottom + gapPx
        val above = anchorBounds.top - gapPx - popupContentSize.height
        val fitsBelow = below + popupContentSize.height <= windowSize.height
        val fitsAbove = above >= 0
        placedAbove = if (preferAbove) fitsAbove || !fitsBelow else !fitsBelow
        val y = if (placedAbove) above.coerceAtLeast(0) else below
        return IntOffset(x, y)
    }
}


/**
 * 弹窗（Dialog）面板：液态玻璃高档时是面板玻璃（弹窗在独立窗口里，按屏幕坐标采样主界面），否则是普通实心卡片。
 * 内容按列排布，尺寸由内容决定。
 */
@Composable
fun GlassDialogPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val isDark = androidx.compose.material3.MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius)
    val glassOn = rememberLiquidGlassFull() && AppGlassBackdrop.layer != null
    val backdrop = if (glassOn) rememberAppGlassBackdrop() else null
    if (backdrop != null) {
        val style = panelGlassStyle(isDark)
        Box(modifier = modifier.clip(shape)) {
            LiquidGlassLayer(
                modifier = Modifier.matchParentSize(),
                cornerRadius = cornerRadius,
                tint = style.tint,
                blurAmount = style.blurAmount,
                dispersion = style.dispersion,
                material = style.material,
                backdropOverride = backdrop
            )
            androidx.compose.foundation.layout.Column(content = content)
        }
    } else {
        androidx.compose.material3.Surface(
            shape = shape,
            color = if (isDark) androidx.compose.ui.graphics.Color(0xFF1F1F25) else androidx.compose.ui.graphics.Color.White,
            shadowElevation = 16.dp,
            modifier = modifier
        ) {
            androidx.compose.foundation.layout.Column(content = content)
        }
    }
}
