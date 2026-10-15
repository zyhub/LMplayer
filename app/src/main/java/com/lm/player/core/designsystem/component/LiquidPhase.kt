package com.lm.player.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Path

@Composable
fun rememberLiquidPhase(enabled: Boolean, durationMillis: Int = 8000): State<Float> {
    if (!enabled) return remember { mutableFloatStateOf(0f) }
    val infiniteTransition = rememberInfiniteTransition(label = "liquid_phase")
    return infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
}

/**
 * 绘制有机非规则变形液态光斑 Path（复用预分配 Path 与 FloatArray 缓冲，零堆内存分配）
 */
fun updateOrganicLiquidPath(
    path: Path,
    ptsBuffer: FloatArray,
    cx: Float,
    cy: Float,
    baseRadius: Float,
    phaseRad: Float,
    h1: Float = 1.0f,
    h2: Float = 2.0f,
    h3: Float = 3.0f
) {
    path.reset()
    val pointsCount = 8
    val step = (2.0 * Math.PI / pointsCount).toFloat()

    for (i in 0 until pointsCount) {
        val angle = i * step
        val wave = 0.28f * kotlin.math.sin(h1 * angle + phaseRad) +
                   0.18f * kotlin.math.cos(h2 * angle - phaseRad) +
                   0.12f * kotlin.math.sin(h3 * angle + 2f * phaseRad)
        val r = baseRadius * (1f + wave)
        ptsBuffer[i * 2] = cx + r * kotlin.math.cos(angle)
        ptsBuffer[i * 2 + 1] = cy + r * kotlin.math.sin(angle)
    }

    val firstMidX = (ptsBuffer[0] + ptsBuffer[2]) / 2f
    val firstMidY = (ptsBuffer[1] + ptsBuffer[3]) / 2f
    path.moveTo(firstMidX, firstMidY)

    for (i in 0 until pointsCount) {
        val nextIdx = ((i + 1) % pointsCount) * 2
        val afterNextIdx = ((i + 2) % pointsCount) * 2
        val nextX = ptsBuffer[nextIdx]
        val nextY = ptsBuffer[nextIdx + 1]
        val midX = (nextX + ptsBuffer[afterNextIdx]) / 2f
        val midY = (nextY + ptsBuffer[afterNextIdx + 1]) / 2f
        path.quadraticBezierTo(nextX, nextY, midX, midY)
    }
    path.close()
}
