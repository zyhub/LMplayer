package com.lm.player.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Path

/** 流光动效的刷新间隔：约 30 帧/秒。流动很慢，每帧位移很小，30 帧已足够顺滑 */
private const val LIQUID_FRAME_INTERVAL_NS = 33_000_000L

/**
 * 主界面是否被整屏盖住（全屏播放页完全展开、启动页显示）。盖住时流光卡片等装饰动效停走：
 * 下面的页面看不见，之前仍每秒重绘 30 次（主界面每次重绘，采样它的玻璃也跟着重绘）。
 */
object MainContentCover {
    var covered by mutableStateOf(false)
}

/**
 * 低频动画节拍（约 30 帧/秒）：每拍在帧回调里调用 [onTick]（参数为距上一拍的纳秒数，封顶 100ms），然后 delay 睡到下一个拍点。
 * - 拍点按帧时间对齐到全局 33ms 栅格：多个动效（几张流光卡片、横屏氛围层）落在同一帧更新，合并成一次重绘；
 *   之前各自累计 33ms，相位错开，120Hz 屏上几乎每一帧都有一个在更新，主界面和玻璃因此每帧重绘；
 * - 两拍之间不再每个 vsync 都唤醒（之前每帧 withFrameNanos 一次）。
 */
suspend fun runThrottledFrames(onTick: (dtNanos: Long) -> Unit): Nothing {
    var last = -1L
    while (true) {
        var frameNow = 0L
        withFrameNanos { now ->
            if (last >= 0L) onTick((now - last).coerceIn(0L, 100_000_000L))
            last = now
            frameNow = now
        }
        val next = (frameNow / LIQUID_FRAME_INTERVAL_NS + 1) * LIQUID_FRAME_INTERVAL_NS
        delay(((next - frameNow) / 1_000_000L).coerceAtLeast(1L))
    }
}

/**
 * 液态流光相位（0..360°，每 [durationMillis] 毫秒转一圈）。
 *
 * - 约 30 帧且与其它动效同帧更新（[runThrottledFrames]），只在相位真正更新时才触发重绘；
 * - [paused] 为 true（如列表滑动中）或主界面被全屏播放页盖住时停在当前位置，恢复后从原位置接着流动，不跳变；
 * - [enabled] 为 false（关闭动效、退到后台）时不再驱动帧。
 */
@Composable
fun rememberLiquidPhase(
    enabled: Boolean,
    durationMillis: Int = 20_000,
    paused: Boolean = false
): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    if (enabled && !paused && !MainContentCover.covered) {
        LaunchedEffect(durationMillis) {
            val degreesPerNano = 360f / (durationMillis.coerceAtLeast(1000) * 1_000_000f)
            runThrottledFrames { dt ->
                phase.floatValue = (phase.floatValue + dt * degreesPerNano) % 360f
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
