package com.lm.player.core.designsystem.component

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
        // Tier 1: 现代高端机型原生 RenderEffect 实时 GPU 模糊
        Box(
            modifier = modifier
                .clip(shape)
                .background(backgroundColor.copy(alpha = 0.70f))
                .blur(radius = blurRadius)
        ) {
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

private fun isLowEndDevice(): Boolean {
    val maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024)
    return maxMemory < 192 || Build.VERSION.SDK_INT < Build.VERSION_CODES.P
}
