package com.lm.player.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.isActive
import androidx.compose.ui.graphics.Path

/** 流光动效的刷新间隔：约 30 帧/秒。流动很慢，每帧位移很小，30 帧已足够顺滑 */
private const val LIQUID_FRAME_INTERVAL_NS = 33_000_000L

/**
 * 液态流光相位（0..360°，每 [durationMillis] 毫秒转一圈）。
 *
 * - 限速约 30 帧：只在相位真正更新时才触发重绘，120Hz 屏幕的重绘次数降到约 1/4；
 * - [paused] 为 true（如列表滑动中）时停在当前位置，恢复后从原位置接着流动，不跳变；
 * - [enabled] 为 false（关闭动效、退到后台）时不再驱动帧。
 */
@Composable
fun rememberLiquidPhase(
    enabled: Boolean,
    durationMillis: Int = 20_000,
    paused: Boolean = false
): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    if (enabled && !paused) {
        LaunchedEffect(durationMillis) {
            val degreesPerNano = 360f / (durationMillis.coerceAtLeast(1000) * 1_000_000f)
            var lastFrame = -1L
            var pendingNanos = 0L
            while (isActive) {
                withFrameNanos { now ->
                    if (lastFrame < 0L) {
                        lastFrame = now
                    } else {
                        // 单帧间隔封顶 100ms，避免卡顿或恢复时相位突跳
                        pendingNanos += (now - lastFrame).coerceIn(0L, 100_000_000L)
                        lastFrame = now
                        if (pendingNanos >= LIQUID_FRAME_INTERVAL_NS) {
                            phase.floatValue = (phase.floatValue + pendingNanos * degreesPerNano) % 360f
                            pendingNanos = 0L
                        }
                    }
                }
            }
        }
    }
    return phase
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
                   // 第三项原为 2 倍相位速度（约 4 秒变形一轮，观感偏快），改为与流动同速
                   0.12f * kotlin.math.sin(h3 * angle + phaseRad)
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
