package com.lm.player.core.designsystem.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * 列表页的液态玻璃顶栏（资料库二级页：歌手 / 专辑 / 歌单 / 文件夹 / 全部歌曲 / 本地下载…）。
 *
 * 玻璃不能采样它自己所在的 ComposeView，所以顶栏内容登记到这里，由上层（[GlassTopLayer]）里的
 * [GlassTopBarHost] 画出来；页面自己的列表铺满到屏幕顶部，顶部留出 [heightPx] 的内边距，
 * 滚动时列表内容从玻璃顶栏下面穿过。
 */
object GlassTopBars {
    class Spec(val key: Any, val content: @Composable ColumnScope.() -> Unit)

    var spec by mutableStateOf<Spec?>(null)
        internal set

    /** 顶栏实际高度（含状态栏） */
    var heightPx by mutableIntStateOf(0)
        internal set

    /**
     * 当前页面顶栏展开时的最大高度。页面用它给列表留顶部内边距：滚动后顶栏收成小胶囊变矮，
     * 内边距不跟着变，列表就不会跳（也不会因为跳动又把顶栏展开）。换页面时清零。
     */
    var expandedHeightPx by mutableIntStateOf(0)
        internal set

    /** [expandedHeightPx] 属于哪个页面（换页面时以新页面第一次测得的高度重新起算） */
    internal var expandedKey: Any? = null
}

/** 页面登记顶栏内容（离开页面自动撤销）。只在液态玻璃开启时调用 */
@Composable
fun RegisterGlassTopBar(key: Any, content: @Composable ColumnScope.() -> Unit) {
    // 玻璃顶栏从屏幕最顶端开始：状态栏透明、内容延伸到状态栏下，状态栏图标直接浮在玻璃上
    com.lm.player.core.designsystem.theme.DrawBehindStatusBar()
    val latest = rememberUpdatedState(content)
    val spec = remember(key) { GlassTopBars.Spec(key) { latest.value(this) } }
    DisposableEffect(spec) {
        GlassTopBars.spec = spec
        onDispose {
            if (GlassTopBars.spec === spec) {
                GlassTopBars.spec = null
                GlassTopBars.heightPx = 0
                GlassTopBars.expandedHeightPx = 0
            }
        }
    }
}

/**
 * 上层里的玻璃顶栏：铺满宽度、从屏幕顶端到内容底部，直角（不再有下沿圆角），高模糊保证标题清楚。
 * 高度随内容变化（列表滚动后收成小胶囊时变矮），玻璃跟着平滑收放。
 */
@Composable
fun GlassTopBarHost(modifier: Modifier = Modifier) {
    val spec = GlassTopBars.spec ?: return
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val style = panelGlassStyle(isDark)
    val density = LocalDensity.current
    val h = with(density) { GlassTopBars.heightPx.toDp() }
    Box(modifier = modifier.fillMaxWidth()) {
        if (h > 0.dp) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(h)
            ) {
                LiquidGlassLayer(
                    modifier = Modifier.matchParentSize(),
                    cornerRadius = 0.dp,
                    tint = style.tint,
                    blurAmount = style.blurAmount,
                    dispersion = style.dispersion,
                    material = style.material
                )
            }
        }
        // 换页面时重建测量节点：即使新旧顶栏一样高也会重新上报高度（展开高度在换页时已清零）
        androidx.compose.runtime.key(spec.key) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged {
                        GlassTopBars.heightPx = it.height
                        if (GlassTopBars.expandedKey != spec.key) {
                            GlassTopBars.expandedKey = spec.key
                            GlassTopBars.expandedHeightPx = it.height
                        } else if (it.height > GlassTopBars.expandedHeightPx) {
                            GlassTopBars.expandedHeightPx = it.height
                        }
                    }
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                content = spec.content
            )
        }
    }
}

// =====================================================================================
// 列表页底部悬浮条（多选操作条等）：同顶栏一样登记到上层，由上层的 [GlassFloatingBarHost] 画成玻璃
// =====================================================================================

object GlassFloatingBars {
    class Spec(
        val key: Any,
        val bottomPadding: androidx.compose.ui.unit.Dp,
        /** 胶囊：按内容宽度、两端全圆角（多选操作条）；否则铺满整行、22dp 圆角 */
        val capsule: Boolean = false,
        val content: @Composable () -> Unit
    )

    var spec by mutableStateOf<Spec?>(null)
        internal set
}

/**
 * 页面登记底部悬浮条（离开页面 / 不再调用时自动撤销）。
 * 液态玻璃开启时画在上层（玻璃）；关闭时由主界面里的 [GlassFloatingBarHost]（glass = false）画成实心卡片。
 * [bottomPadding] 不指定时贴在底栏上方（由宿主给出默认位置）。
 */
@Composable
fun RegisterGlassFloatingBar(
    key: Any,
    bottomPadding: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified,
    capsule: Boolean = false,
    content: @Composable () -> Unit
) {
    val latest = rememberUpdatedState(content)
    val spec = remember(key, bottomPadding, capsule) { GlassFloatingBars.Spec(key, bottomPadding, capsule) { latest.value() } }
    DisposableEffect(spec) {
        GlassFloatingBars.spec = spec
        onDispose {
            if (GlassFloatingBars.spec === spec) GlassFloatingBars.spec = null
        }
    }
}

/**
 * 底部悬浮条宿主：[glass] 时是面板玻璃（高模糊，文字清楚），否则是实心卡片 + 阴影；22dp 圆角，从下往上滑入。
 * [defaultBottomPadding]：页面没指定位置时用（贴在底栏上方）。
 */
@Composable
fun GlassFloatingBarHost(
    modifier: Modifier = Modifier,
    defaultBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    glass: Boolean = true
) {
    val spec = GlassFloatingBars.spec
    // 退出动画期间继续画最后一次的内容
    var last by remember { mutableStateOf<GlassFloatingBars.Spec?>(null) }
    if (spec != null) last = spec
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    androidx.compose.animation.AnimatedVisibility(
        visible = spec != null,
        enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }) + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it }) + androidx.compose.animation.fadeOut(),
        modifier = modifier
    ) {
        val s = last ?: return@AnimatedVisibility
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = if (s.bottomPadding == androidx.compose.ui.unit.Dp.Unspecified) defaultBottomPadding else s.bottomPadding
                ),
            contentAlignment = androidx.compose.ui.Alignment.BottomCenter
        ) {
            // 横屏 / 平板：不铺满整行，最宽 560dp 居中
            val barModifier = if (s.capsule) Modifier.widthIn(max = 560.dp) else Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
            val corner = if (s.capsule) 26.dp else 22.dp
            if (glass) {
                GlassSurface(
                    style = panelGlassStyle(isDark),
                    cornerRadius = corner,
                    modifier = barModifier
                ) {
                    s.content()
                }
            } else {
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(corner),
                    color = if (isDark) androidx.compose.ui.graphics.Color(0xFF1E1E26) else androidx.compose.ui.graphics.Color.White,
                    shadowElevation = 10.dp,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isDark) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.08f)
                    ),
                    modifier = barModifier
                ) {
                    s.content()
                }
            }
        }
    }
}
