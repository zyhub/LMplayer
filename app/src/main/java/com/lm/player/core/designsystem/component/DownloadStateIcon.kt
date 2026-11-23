package com.lm.player.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.DownloadTask

/**
 * 下载状态图标（2026-11-23，对齐 iOS 端 DownloadStateIcon）。
 *
 * 所有状态共用同一个圆圈（同样大小、同样粗细），只换中间的符号和颜色：
 * - 未下载：圆圈 + 下箭头，颜色跟随文字主色（黑 / 白）；
 * - 等待中（排队 / 取链接 / 换源）：红色圆圈，中间三个点依次亮起；
 * - 下载中：圆形进度环，中间百分比；
 * - 已暂停：红色圆圈 + 暂停符号；
 * - 下载失败：红色圆圈 + 感叹号；
 * - 仅本机：绿色圆圈 + 勾；仅服务器：蓝色云勾；双端：绿色实心圆 + 白勾。
 *
 * 外框固定为 [size] 的 1.4 倍，状态切换时行尾不会跳动；下载中的进度环占满外框。
 */
enum class DownloadIconState { IDLE, WAITING, DOWNLOADING, PAUSED, FAILED, LOCAL, SERVER, BOTH }

private val DownloadGreen = Color(0xFF34C759)
private val DownloadBlue = Color(0xFF0A84FF)

/**
 * 按下载任务与本地 / 服务器状态得出图标状态。
 * @param task 下载引擎里这首歌的任务（含已暂停的任务）
 * @param failed 本次运行里这首歌的本机下载失败了（[com.lm.player.core.media.SongStatusStore.rememberFailed]）
 * @param staleDownloading 歌曲标着「下载中」但引擎里已没有任务（旧数据），按下载中显示
 */
fun resolveDownloadIconState(
    task: DownloadTask?,
    failed: Boolean,
    hasLocal: Boolean,
    hasServer: Boolean,
    staleDownloading: Boolean = false
): DownloadIconState = when {
    task != null && task.status == DownloadStatus.PAUSED -> DownloadIconState.PAUSED
    task != null && task.progress <= 0f && task.bytesDownloaded == 0L && task.totalBytes == 0L -> DownloadIconState.WAITING
    task != null || staleDownloading -> DownloadIconState.DOWNLOADING
    failed && !hasLocal -> DownloadIconState.FAILED
    hasLocal && hasServer -> DownloadIconState.BOTH
    hasLocal -> DownloadIconState.LOCAL
    hasServer -> DownloadIconState.SERVER
    else -> DownloadIconState.IDLE
}

/** 等待 / 下载中 / 已暂停：点图标打开下载管理（不弹下载菜单） */
val DownloadIconState.isActive: Boolean
    get() = this == DownloadIconState.WAITING || this == DownloadIconState.DOWNLOADING || this == DownloadIconState.PAUSED

@Composable
fun DownloadStateIcon(
    state: DownloadIconState,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    size: Dp = 22.dp,
    /** 未下载时的颜色（跟随所在位置的文字主色） */
    idleColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val label = when (state) {
        DownloadIconState.IDLE -> "下载"
        DownloadIconState.WAITING -> "等待下载"
        DownloadIconState.DOWNLOADING -> "下载中"
        DownloadIconState.PAUSED -> "下载已暂停"
        DownloadIconState.FAILED -> "下载出错"
        DownloadIconState.LOCAL -> "已下载到本机"
        DownloadIconState.SERVER -> "服务器曲库已有"
        DownloadIconState.BOTH -> "本地与服务器都已保存"
    }
    Box(
        modifier = modifier
            .size(size * 1.4f)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        when (state) {
            DownloadIconState.DOWNLOADING -> DownloadProgressRing(progress, size, idleColor)
            DownloadIconState.WAITING -> WaitingDots(size)
            DownloadIconState.SERVER -> Icon(
                imageVector = Icons.Outlined.CloudDone,
                contentDescription = null,
                tint = DownloadBlue,
                modifier = Modifier.size(size * 1.05f)
            )
            else -> Canvas(modifier = Modifier.size(size)) {
                when (state) {
                    DownloadIconState.IDLE -> {
                        ring(idleColor)
                        arrowDown(idleColor)
                    }
                    DownloadIconState.PAUSED -> {
                        ring(AppleRed)
                        pauseBars(AppleRed)
                    }
                    DownloadIconState.FAILED -> {
                        ring(AppleRed)
                        exclamation(AppleRed)
                    }
                    DownloadIconState.LOCAL -> {
                        ring(DownloadGreen)
                        check(DownloadGreen)
                    }
                    DownloadIconState.BOTH -> {
                        drawCircle(DownloadGreen, radius = this.size.minDimension * 0.47f)
                        check(Color.White)
                    }
                    else -> Unit
                }
            }
        }
    }
}

// ---------------- 画法：以图标边长 s 为基准，线宽与 iOS 的 SF Symbols semibold 圆圈接近 ----------------

private fun DrawScope.strokeWidth(): Float = size.minDimension * 0.085f

private fun DrawScope.ring(color: Color) {
    val s = size.minDimension
    drawCircle(color, radius = s * 0.43f, style = Stroke(width = strokeWidth()))
}

private fun DrawScope.arrowDown(color: Color) {
    val s = size.minDimension
    val c = center
    val w = strokeWidth()
    drawLine(color, Offset(c.x, c.y - s * 0.20f), Offset(c.x, c.y + s * 0.17f), strokeWidth = w, cap = StrokeCap.Round)
    val head = Path().apply {
        moveTo(c.x - s * 0.14f, c.y + s * 0.03f)
        lineTo(c.x, c.y + s * 0.17f)
        lineTo(c.x + s * 0.14f, c.y + s * 0.03f)
    }
    drawPath(head, color, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.check(color: Color) {
    val s = size.minDimension
    val c = center
    val path = Path().apply {
        moveTo(c.x - s * 0.17f, c.y + s * 0.01f)
        lineTo(c.x - s * 0.05f, c.y + s * 0.13f)
        lineTo(c.x + s * 0.18f, c.y - s * 0.12f)
    }
    drawPath(path, color, style = Stroke(width = strokeWidth(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.pauseBars(color: Color) {
    val s = size.minDimension
    val c = center
    val w = s * 0.09f
    for (dx in floatArrayOf(-0.075f, 0.075f)) {
        drawLine(color, Offset(c.x + s * dx, c.y - s * 0.13f), Offset(c.x + s * dx, c.y + s * 0.13f), strokeWidth = w, cap = StrokeCap.Round)
    }
}

private fun DrawScope.exclamation(color: Color) {
    val s = size.minDimension
    val c = center
    val w = strokeWidth()
    drawLine(color, Offset(c.x, c.y - s * 0.18f), Offset(c.x, c.y + s * 0.04f), strokeWidth = w, cap = StrokeCap.Round)
    drawCircle(color, radius = w * 0.65f, center = Offset(c.x, c.y + s * 0.165f))
}

/** 下载中：圆形进度环 + 中间百分比（与播放页原来的进度圈同一样式） */
@Composable
private fun DownloadProgressRing(progress: Float, size: Dp, textColor: Color) {
    val p = progress.coerceIn(0f, 1f)
    val track = textColor.copy(alpha = 0.15f)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = this.size.minDimension * 0.08f
            val inset = w / 2f
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w)
            drawArc(track, 0f, 360f, false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(width = w))
            drawArc(
                AppleRed, -90f, 360f * p, false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = w, cap = StrokeCap.Round)
            )
        }
        val fontSp = with(LocalDensity.current) { (size * 0.38f).toSp() }
        Text(
            text = "${(p * 100).toInt()}%",
            fontSize = fontSp,
            letterSpacing = (-0.3).sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * 等待下载：红色圆圈，中间三个点从左到右依次亮起（每 0.32 秒换一次）。
 * 计时只在处于等待状态的那几个图标里运行，离开等待状态即停止。
 */
@Composable
private fun WaitingDots(size: Dp) {
    val step by produceState(0) {
        while (true) {
            kotlinx.coroutines.delay(320L)
            value = (value + 1) % 3
        }
    }
    val a0 by animateFloatAsState(if (step == 0) 1f else 0.3f, tween(250), label = "dot0")
    val a1 by animateFloatAsState(if (step == 1) 1f else 0.3f, tween(250), label = "dot1")
    val a2 by animateFloatAsState(if (step == 2) 1f else 0.3f, tween(250), label = "dot2")
    Canvas(modifier = Modifier.size(size)) {
        ring(AppleRed)
        val s = this.size.minDimension
        val r = s * 0.075f
        val gap = s * 0.09f
        val alphas = floatArrayOf(a0, a1, a2)
        for (i in 0..2) {
            val x = center.x + (i - 1) * (2 * r + gap)
            val scale = if (i == step) 1.15f else 1f
            drawCircle(AppleRed.copy(alpha = alphas[i]), radius = r * scale, center = Offset(x, center.y))
        }
    }
}
