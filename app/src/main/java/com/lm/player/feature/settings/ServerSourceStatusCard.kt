package com.lm.player.feature.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.database.entity.ServerSourceEntity
import com.lm.player.core.database.entity.ServerSourceMetaEntity
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import com.lm.player.core.network.ServerLatencyMonitor
import com.lm.player.core.source.SourceAccelerationSession
import com.lm.player.core.source.SourceScriptStore
import com.lm.player.core.source.SourceScriptUpdater
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val RingGreen = Color(0xFF34C759)
private val RingRed = Color(0xFFFF3B30)
private val RingOrange = Color(0xFFFF9F0A)
private val RingGray = Color(0xFF8E8E93)
private val RingYellow = Color(0xFFFFCC00)
private val RingBlue = Color(0xFF0A84FF)

/** 4 环极简看板：在线 / 延迟 / 激活源 / 音源加速（蓝 = 本机装载、黄 = 服务器拉取；点第 4 个环看详情）。 */
@Composable
fun ServerSourceStatusCard(
    serverName: String,
    isConnected: Boolean,
    serverHost: String,
    onlineLatencyMs: Long? = null,
    sources: List<ServerSourceEntity>,
    meta: ServerSourceMetaEntity?,
    isRefreshing: Boolean,
    error: String?,
    onRefresh: () -> Unit
) {
    val session by SourceAccelerationSession.status.collectAsState()
    val online = isConnected
    val rawLatency = onlineLatencyMs?.takeIf { it > 0L }
        ?: meta?.latencyMs?.takeIf { it > 0L }
        ?: ServerLatencyMonitor.latencyMs(serverHost)
    val latency = if (online) rawLatency else 0L
    val active = if (online) (meta?.activeSourceCount ?: sources.count { it.isActive }).coerceAtLeast(0) else 0
    val total = if (online) (meta?.totalSourceCount ?: sources.size).coerceAtLeast(0) else 0
    val updater by SourceScriptUpdater.state.collectAsState()
    val accelReady = session.isReady
    // 2026-11-13：蓝 = 从本机装载引擎；黄 = 从服务器拉取新版本（下次启动生效）；绿 = 可用；红 = 没有可用脚本 / 失败。
    // 两件事同时发生时蓝色优先（装载决定能不能用）。进行中圆环中间显示百分比
    val accelLoadingLocal = session.isLoading
    val accelPulling = updater.isPulling
    val accelInProgress = accelLoadingLocal || accelPulling
    val accelColor = when {
        accelLoadingLocal -> RingBlue
        accelPulling -> RingYellow
        accelReady -> RingGreen
        else -> RingRed
    }
    val accelProgress = when {
        accelLoadingLocal -> session.loadProgress
        accelPulling -> updater.progress
        accelReady -> 1f
        else -> 0f
    }
    // 有消息（新版本已下载待生效 / 最近拉取失败 / 本次有脚本没装上）：圆环中间的闪电换成感叹号，点开看详情
    val accelNotice = updater.pendingReady || updater.lastError != null ||
        session.loadIssues.isNotEmpty() || session.state == SourceAccelerationSession.State.FAILED
    var showAccelDetail by remember { mutableStateOf(false) }
    val accelLabel = when {
        accelLoadingLocal -> "装载中 ${(accelProgress * 100).roundToInt()}%"
        accelPulling -> "拉取中 ${(accelProgress * 100).roundToInt()}%"
        accelReady -> "正常"
        else -> "未拉取"
    }
    val onlineColor = if (online) RingGreen else RingRed
    val latencyColor = when {
        !online -> RingRed
        latency <= 0L -> RingGray
        latency <= 60L -> RingGreen
        latency <= 150L -> RingOrange
        else -> RingRed
    }
    val activeColor = if (!online) RingRed else if (active > 0 && total > 0) RingGreen else RingGray

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "在线:${if (online) "正常" else "未连接"}; 延迟:${if (latency > 0L) "${latency}毫秒" else "未知"}; 激活源:${active}/${total}; 音源加速:$accelLabel" + (error?.let { "; ${it}" } ?: "")
        }
    ) {
        Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("音源加速与状态看板", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (serverName.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "($serverName)",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = onRefresh,
                    enabled = !isRefreshing,
                    modifier = Modifier
                        .size(36.dp)
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新音源状态", modifier = Modifier.size(22.dp))
                    }
                }
            }
            if (!error.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top
            ) {
                StatusRingColumn(
                    label = "在线",
                    progress = if (online) 1f else 0f,
                    color = onlineColor,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (online) Icons.Default.Check else Icons.Default.Close,
                        contentDescription = null,
                        tint = onlineColor,
                        modifier = Modifier.size(28.dp)
                    )
                }
                StatusRingColumn(
                    label = "延迟",
                    progress = if (latency > 0L) (latency / 200f).coerceIn(0.05f, 1f) else 0f,
                    color = latencyColor,
                    modifier = Modifier.weight(1f)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (latency > 0L) "${latency}" else "—", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("ms", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                StatusRingColumn(
                    label = "激活源",
                    progress = if (total > 0) active.toFloat() / total else 0f,
                    color = activeColor,
                    segments = total.coerceIn(1, 8),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (total > 0) "${active}/${total}" else "—", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                StatusRingColumn(
                    label = "音源加速",
                    progress = accelProgress,
                    color = accelColor,
                    modifier = Modifier.weight(1f),
                    onClick = { showAccelDetail = true }
                ) {
                    when {
                        accelInProgress -> Text(
                            "${(accelProgress * 100).roundToInt()}%",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = accelColor
                        )
                        accelNotice -> Text("!", fontSize = 32.sp, fontWeight = FontWeight.Black, color = accelColor)
                        else -> Icon(Icons.Default.Bolt, contentDescription = null, tint = accelColor, modifier = Modifier.size(30.dp))
                    }
                }
            }
        }
    }
    if (showAccelDetail) {
        AccelDetailDialog(session = session, updater = updater, onDismiss = { showAccelDetail = false })
    }
}

/** 点「音源加速」圆环弹出的详情：当前版、待用版、后台拉取、最近一次拉取（含失败原因）、装载问题 */
@Composable
private fun AccelDetailDialog(
    session: SourceAccelerationSession.Status,
    updater: SourceScriptUpdater.State,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val current = remember(session.state, session.versionSavedAt) {
        SourceScriptStore.summary(context, SourceScriptStore.Slot.CURRENT)
    }
    val pending = remember(updater.pendingReady, updater.pendingSavedAt) {
        SourceScriptStore.summary(context, SourceScriptStore.Slot.PENDING)
    }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
    fun time(at: Long): String = if (at > 0L) fmt.format(Date(at)) else "未知"
    val errorColor = MaterialTheme.colorScheme.error

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("音源加速详情", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DetailSection("当前版（本次启动使用）")
                DetailLine("拉取于", time(session.versionSavedAt.takeIf { it > 0L } ?: current?.savedAt ?: 0L))
                DetailLine(
                    "装载",
                    when (session.state) {
                        SourceAccelerationSession.State.LOADING -> "从本机装载中 ${session.loadDone}/${session.loadTotal}"
                        SourceAccelerationSession.State.READY -> "已装载 ${session.readyCount}/${session.totalCount} 个脚本"
                        SourceAccelerationSession.State.FAILED -> "装载失败：${session.error.orEmpty()}"
                        SourceAccelerationSession.State.IDLE -> "尚未装载（用到加速时才装载）"
                    },
                    color = if (session.state == SourceAccelerationSession.State.FAILED) errorColor else null
                )
                current?.sources?.forEach { (_, name, version) ->
                    DetailLine("", if (version.isNotBlank()) "$name  v$version" else name)
                }

                DetailSection("待用版（下次启动生效）")
                if (updater.pendingReady && pending != null) {
                    DetailLine("状态", "新版本已下载（${time(pending.savedAt)}），下次启动生效")
                    pending.sources.forEach { (_, name, version) ->
                        DetailLine("", if (version.isNotBlank()) "$name  v$version" else name)
                    }
                } else {
                    DetailLine("状态", "无")
                }

                DetailSection("后台拉取")
                DetailLine(
                    "状态",
                    when (updater.phase) {
                        SourceScriptUpdater.Phase.WAITING ->
                            "等待服务器稳定（还差 ${(updater.stableRemainingMs + 999) / 1000} 秒）"
                        SourceScriptUpdater.Phase.CHECKING -> "核对版本中"
                        SourceScriptUpdater.Phase.DOWNLOADING -> "下载中 ${(updater.progress * 100).roundToInt()}%"
                        SourceScriptUpdater.Phase.SAVING -> "校验、保存中"
                        SourceScriptUpdater.Phase.IDLE -> when {
                            updater.doneThisLaunch -> "本次启动已完成"
                            updater.autoAttempts >= 3 -> "自动拉取失败 3 次，本次启动不再自动重试（可点刷新按钮手动拉取）"
                            else -> "未开始（登录服务器、连续在线满 2 分钟后自动开始）"
                        }
                    }
                )

                DetailSection("最近一次拉取")
                if (updater.lastResultAt > 0L) {
                    DetailLine("时间", time(updater.lastResultAt))
                    DetailLine("结果", updater.lastResult)
                    updater.lastError?.let { DetailLine("失败原因", it, color = errorColor) }
                } else {
                    DetailLine("结果", "本次启动还没有拉取过")
                }

                if (session.loadIssues.isNotEmpty()) {
                    DetailSection("装载问题")
                    session.loadIssues.forEach { DetailLine("", it, color = errorColor) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss
            ) { Text("关闭") }
        }
    )
}

@Composable
private fun DetailSection(title: String) {
    Text(
        title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun DetailLine(label: String, value: String, color: Color? = null) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = color ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatusRingColumn(
    label: String,
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    segments: Int = 0,
    /** 可点击：点开详情 */
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val animated by animateFloatAsState(targetValue = progress.coerceIn(0f, 1f), label = "ring")
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(76.dp)
                .then(
                    if (onClick != null) Modifier.clip(CircleShape).clickable(onClick = onClick)
                    else Modifier
                )
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 8.dp.toPx()
                val inset = stroke / 2f + 1.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                val topLeft = Offset(inset, inset)
                val bg = color.copy(alpha = 0.12f)
                drawArc(
                    color = bg,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                if (segments > 1) {
                    val gap = 7f
                    val sweep = 360f / segments
                    val filled = (animated * segments).toInt()
                    for (i in 0 until segments) {
                        drawArc(
                            color = if (i < filled) color else color.copy(alpha = 0.18f),
                            startAngle = -90f + i * sweep + gap / 2f,
                            sweepAngle = sweep - gap,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }
                } else {
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = 360f * animated,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }
            content()
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
    }
}
