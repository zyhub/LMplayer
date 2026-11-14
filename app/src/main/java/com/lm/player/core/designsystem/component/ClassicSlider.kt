package com.lm.player.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 旧样式的滑块 / 进度条（还原 Material3 1.2 的外观）。
 *
 * 2026-11-10 升级到 Compose 1.12（Material3 1.4）后，系统 Slider 变成「竖条滑块 + 带缺口的粗轨道」，
 * LinearProgressIndicator 变成「圆头 + 缺口 + 末端圆点」。这里用 Slider 的 thumb / track 插槽
 * 画回原来的 20dp 圆形滑块与 4dp 圆头细轨道，进度条去掉缺口与末端圆点、恢复平头和原轨道色。
 * 参数与系统 Slider / LinearProgressIndicator 同名，调用处直接替换函数名即可。
 */
@Composable
fun ClassicSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    thumbSize: Dp = 20.dp,
    trackHeight: Dp = 4.dp
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interactionSource,
        steps = steps,
        thumb = {
            val color = if (enabled) colors.thumbColor else colors.disabledThumbColor
            Spacer(
                modifier = Modifier
                    .size(thumbSize)
                    .indication(interactionSource, ripple(bounded = false, radius = thumbSize))
                    .hoverable(interactionSource, enabled)
                    .shadow(if (enabled) 1.dp else 0.dp, CircleShape, clip = false)
                    .background(color, CircleShape)
            )
        },
        track = { state ->
            val active = if (enabled) colors.activeTrackColor else colors.disabledActiveTrackColor
            val inactive = if (enabled) colors.inactiveTrackColor else colors.disabledInactiveTrackColor
            val activeTick = if (enabled) colors.activeTickColor else colors.disabledActiveTickColor
            val inactiveTick = if (enabled) colors.inactiveTickColor else colors.disabledInactiveTickColor
            val range = state.valueRange
            val span = range.endInclusive - range.start
            val fraction = if (span > 0f) ((state.value - range.start) / span).coerceIn(0f, 1f) else 0f
            val tickCount = state.steps
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
            ) {
                val y = size.height / 2f
                val stroke = size.height
                drawLine(inactive, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke, cap = StrokeCap.Round)
                if (fraction > 0f) {
                    drawLine(active, Offset(0f, y), Offset(size.width * fraction, y), strokeWidth = stroke, cap = StrokeCap.Round)
                }
                if (tickCount > 0) {
                    val r = 1.dp.toPx()
                    for (i in 0..(tickCount + 1)) {
                        val f = i.toFloat() / (tickCount + 1)
                        drawCircle(
                            color = if (f <= fraction) activeTick else inactiveTick,
                            radius = r,
                            center = Offset(size.width * f, y)
                        )
                    }
                }
            }
        },
        valueRange = valueRange
    )
}

/** 旧样式确定进度条：平头、无缺口、无末端圆点 */
@Composable
fun ClassicLinearProgress(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.linearColor,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    LinearProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp,
        drawStopIndicator = {}
    )
}

/** 旧样式不确定进度条：平头、无缺口 */
@Composable
fun ClassicLinearProgress(
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.linearColor,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    LinearProgressIndicator(
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp
    )
}
