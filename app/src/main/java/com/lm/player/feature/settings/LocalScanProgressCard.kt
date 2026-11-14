package com.lm.player.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.media.LocalScanEngine

/**
 * 本地扫描 / 曲库比对进度卡片 (第二十节，移植自 TV 端)。
 * 扫描进行中显示阶段、进度与当前文件，可取消；结束后显示结果摘要 (含用时)，点「知道了」关闭。
 * 没有扫描时，如果正在进行登录服务器后的曲库比对，也在这里显示。
 */
@Composable
fun LocalScanProgressCard() {
    val state by LocalScanEngine.state.collectAsState()
    val match by LocalScanEngine.matchProgress.collectAsState()
    val s = state

    if (s == null) {
        val m = match ?: return
        ProgressSurface {
            Text("正在与服务器曲库比对：${m.stage}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            if (m.total > 0) {
                com.lm.player.core.designsystem.component.ClassicLinearProgress(
                    progress = { (m.processed.toFloat() / m.total).coerceIn(0f, 1f) },
                    color = AppleRed,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text("${m.processed} / ${m.total}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                com.lm.player.core.designsystem.component.ClassicLinearProgress(color = AppleRed, modifier = Modifier.fillMaxWidth())
            }
        }
        return
    }

    ProgressSurface {
        if (s.isRunning) {
            val title = when (s.phase) {
                LocalScanEngine.Phase.DISCOVER -> "正在查找音频文件（已找到 ${s.foundFiles} 个）"
                LocalScanEngine.Phase.METADATA -> "正在读取歌曲信息"
                LocalScanEngine.Phase.MEDIASTORE -> "正在补充系统媒体库中的歌曲"
                LocalScanEngine.Phase.COVERS -> "正在提取内嵌封面"
                LocalScanEngine.Phase.MATCHING -> "正在与服务器曲库比对" + (match?.let { "：${it.stage}" } ?: "")
                else -> ""
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { LocalScanEngine.cancel() }
                ) { Text("取消扫描", fontSize = 12.sp) }
            }
            Spacer(modifier = Modifier.height(6.dp))
            val (done, total) = when {
                s.phase == LocalScanEngine.Phase.MATCHING && match != null -> match!!.processed to match!!.total
                else -> s.processed to s.total
            }
            if (total > 0) {
                com.lm.player.core.designsystem.component.ClassicLinearProgress(
                    progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                    color = AppleRed,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text("$done / $total", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                com.lm.player.core.designsystem.component.ClassicLinearProgress(color = AppleRed, modifier = Modifier.fillMaxWidth())
            }
            val detail = buildString {
                if (s.newSongs > 0) append("已入库 ${s.newSongs} 首")
                if (s.skippedUnchanged > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("${s.skippedUnchanged} 首未变化已跳过")
                }
                if (s.coversDone > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("封面 ${s.coversDone} 张")
                }
            }
            if (detail.isNotEmpty()) {
                Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s.currentName.isNotBlank()) {
                Text(
                    s.currentName,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "扫描在后台进行，离开设置页不会中断",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.summary,
                    fontSize = 12.sp,
                    color = if (s.phase == LocalScanEngine.Phase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                TextButton(
                    onClick = { LocalScanEngine.dismiss() }
                ) { Text("知道了", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun ProgressSurface(content: @Composable () -> Unit) {
    Spacer(modifier = Modifier.height(10.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AppleRed.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, AppleRed.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) { content() }
    }
}
