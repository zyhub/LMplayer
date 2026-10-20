package com.lm.player.core.designsystem.component

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun AdaptiveFrostedSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    blurRadius: Dp = 25.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val isLowRam = remember { isLowEndDevice() }
    val apiLevel = Build.VERSION.SDK_INT

    if (apiLevel >= Build.VERSION_CODES.S && !isLowRam) {
        // Tier 1: 现代高端机型原生 RenderEffect 实时 GPU 模糊 (背景独立虚化，子视图内容保持清晰锐利)
        Box(
            modifier = modifier.clip(shape)
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(backgroundColor.copy(alpha = 0.70f))
                    .blur(radius = blurRadius)
            )
            content()
        }
    } else {
        // Tier 2 & Tier 3: 深度兼容老旧系统 (Android 6.0 ~ 11) 及低算力车机
        // 使用高性能双色渐变亚克力拟态底板 + 超细微光边框，0 CPU 渲染开销，保证 60fps 丝滑
        Box(
            modifier = modifier
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            backgroundColor.copy(alpha = 0.88f),
                            backgroundColor.copy(alpha = 0.96f)
                        )
                    )
                )
                .border(
                    width = 0.8.dp,
                    color = Color.White.copy(alpha = 0.18f),
                    shape = shape
                )
        ) {
            content()
        }
    }
}

/**
 * 顶部渐进模糊吸顶栏 (当列表内容向上滚动穿过状态栏与标题区域时，动态淡入柔和毛玻璃与纵向渐变虚化)
 */
@Composable
fun ProgressiveFrostedTopBar(
    isScrolled: Boolean,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    content: @Composable BoxScope.() -> Unit
) {
    val alphaAnim by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isScrolled) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 240),
        label = "progressive_top_alpha"
    )

    Box(modifier = modifier) {
        if (alphaAnim > 0.01f) {
            val apiLevel = Build.VERSION.SDK_INT
            val isLowRam = remember { isLowEndDevice() }
            if (apiLevel >= Build.VERSION_CODES.S && !isLowRam) {
                // Tier 1: 渐进 GPU 高斯模糊 + 纵向 Alpha 渐变羽化
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .blur(radius = 18.dp)
                        .background(
                            Brush.verticalGradient(
                                0.0f to backgroundColor.copy(alpha = 0.88f * alphaAnim),
                                0.75f to backgroundColor.copy(alpha = 0.75f * alphaAnim),
                                1.0f to Color.Transparent
                            )
                        )
                )
            } else {
                // Tier 2: 渐进双色微光亚克力层
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to backgroundColor.copy(alpha = 0.95f * alphaAnim),
                                0.70f to backgroundColor.copy(alpha = 0.85f * alphaAnim),
                                1.0f to Color.Transparent
                            )
                        )
                )
            }
        }
        content()
    }
}

/**
 * 底部渐进虚化衬底 (Progressive Scrim Layer：位于滚动内容上方、悬浮底栏下方，向上柔和淡化，解决内容生硬截断)
 */
@Composable
fun ProgressiveBlurBottomScrim(
    modifier: Modifier = Modifier,
    height: Dp = 110.dp,
    backgroundColor: Color = MaterialTheme.colorScheme.background
) {
    val apiLevel = Build.VERSION.SDK_INT
    val isLowRam = remember { isLowEndDevice() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        if (apiLevel >= Build.VERSION_CODES.S && !isLowRam) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .blur(radius = 20.dp)
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.35f to backgroundColor.copy(alpha = 0.45f),
                            0.75f to backgroundColor.copy(alpha = 0.85f),
                            1.0f to backgroundColor.copy(alpha = 0.96f)
                        )
                    )
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.30f to backgroundColor.copy(alpha = 0.50f),
                            0.70f to backgroundColor.copy(alpha = 0.88f),
                            1.0f to backgroundColor.copy(alpha = 0.98f)
                        )
                    )
            )
        }
    }
}

private fun isLowEndDevice(): Boolean {
    val maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024)
    return maxMemory < 192 || Build.VERSION.SDK_INT < Build.VERSION_CODES.P
}
