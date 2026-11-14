package com.lm.player.core.designsystem.component

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lm.player.core.designsystem.glass.GlassDialogPanel
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.model.PlaylistAddOutcome
import com.lm.player.core.model.PlaylistAddStatus
import com.lm.player.core.model.UnifiedPlaylist
import com.lm.player.core.model.UnifiedSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 多选「加入歌单」（与 TV 同一套流程，2026-11-14 移植到手机）：所有带多选的歌曲列表共用。
 *
 * 流程：选歌单（或新建）→ 进度条 → 结果。全部加入成功只提示一句；有「已存在（忽略）」或失败时弹出结果窗口，
 * 点各项数字可以看到对应的歌曲，失败的写明原因。
 *
 * 状态放在这里（进程级）、弹窗由 [PlaylistBatchOpsHost]（MainActivity 根部一次）显示：
 * 加入过程中切页 / 退出多选都不会打断正在进行的写入。真正的加入由 MainActivity 注册 [addHandler]。
 * 手机端弹窗是液态玻璃面板（高档）/ 实心卡片，宽度、列表高度随窗口大小（横屏、平板、分屏）自适应。
 */
object PlaylistBatchOps {

    sealed class Ui {
        data class Picking(val songs: List<UnifiedSong>) : Ui()
        data class Running(val playlistName: String, val progress: Float, val step: String) : Ui()
        data class Result(val playlistName: String, val outcomes: List<PlaylistAddOutcome>) : Ui()
    }

    val ui = mutableStateOf<Ui?>(null)

    /** 可加入的歌单（MainActivity 维护：不含发现页 / 推荐歌单） */
    val playlists = mutableStateOf<List<UnifiedPlaylist>>(emptyList())

    /** 配置了柠檬服务器（云端歌单是否可选还要看服务器是否在线） */
    val serverConfigured = mutableStateOf(false)

    /** 批量加入：(目标歌单, 歌曲, 进度回调(0~1, 步骤)) → 每首的结果。由 MainActivity 注册 */
    @Volatile
    var addHandler: (suspend (UnifiedPlaylist, List<UnifiedSong>, (Float, String) -> Unit) -> List<PlaylistAddOutcome>)? = null

    /** 新建歌单（有服务器建在服务器上，否则本地），返回新歌单；失败返回 null。由 MainActivity 注册 */
    @Volatile
    var createHandler: (suspend (String) -> UnifiedPlaylist?)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var appContext: Context? = null
    private var onFinished: (() -> Unit)? = null

    /** 多选栏「加入歌单」：打开歌单选择；[onFinished] 在加入完成后调用（一般用来退出多选） */
    fun startAdd(context: Context, songs: List<UnifiedSong>, onFinished: () -> Unit = {}) {
        if (songs.isEmpty()) return
        if (ui.value is Ui.Running) return
        appContext = context.applicationContext
        this.onFinished = onFinished
        ui.value = Ui.Picking(songs)
    }

    fun dismiss() {
        if (ui.value is Ui.Running) return
        ui.value = null
    }

    /** 从悬浮条的「加入歌单」面板直接选了歌单（不经选择弹窗） */
    fun pickWith(context: Context, target: UnifiedPlaylist, songs: List<UnifiedSong>, onFinished: () -> Unit) {
        if (songs.isEmpty() || ui.value is Ui.Running) return
        appContext = context.applicationContext
        this.onFinished = onFinished
        pick(target, songs)
    }

    /** 从悬浮条的「加入歌单」面板新建歌单并加入 */
    fun createWith(context: Context, name: String, songs: List<UnifiedSong>, onFinished: () -> Unit) {
        if (songs.isEmpty() || ui.value is Ui.Running) return
        appContext = context.applicationContext
        this.onFinished = onFinished
        createAndPick(name, songs)
    }

    fun pick(target: UnifiedPlaylist, songs: List<UnifiedSong>) {
        scope.launch { run(target, songs) }
    }

    fun createAndPick(name: String, songs: List<UnifiedSong>) {
        val clean = name.trim()
        if (clean.isBlank()) return
        scope.launch {
            ui.value = Ui.Running(clean, 0f, "新建歌单…")
            val created = try {
                createHandler?.invoke(clean)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (created == null) {
                ui.value = null
                toast("新建歌单「$clean」失败")
                return@launch
            }
            run(created, songs)
        }
    }

    private suspend fun run(target: UnifiedPlaylist, songs: List<UnifiedSong>) {
        ui.value = Ui.Running(target.name, 0f, "准备中…")
        val handler = addHandler
        val outcomes = if (handler == null) {
            songs.map { PlaylistAddOutcome(it, PlaylistAddStatus.FAILED, "加入功能还没准备好，请稍后再试") }
        } else try {
            handler(target, songs) { f, step ->
                ui.value = Ui.Running(target.name, f.coerceIn(0f, 1f), step)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            songs.map { PlaylistAddOutcome(it, PlaylistAddStatus.FAILED, e.message ?: "未知错误") }
        }
        onFinished?.invoke()
        onFinished = null
        val existed = outcomes.count { it.status == PlaylistAddStatus.EXISTED }
        val failed = outcomes.count { it.status == PlaylistAddStatus.FAILED }
        if (existed == 0 && failed == 0) {
            ui.value = null
            toast("已将 ${outcomes.size} 首加入「${target.name}」")
        } else {
            ui.value = Ui.Result(target.name, outcomes)
        }
    }

    private fun toast(text: String) {
        val ctx = appContext ?: return
        Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
    }
}

/** 放在 MainActivity 根部一次：显示多选「加入歌单」的选择、进度与结果窗口 */
@Composable
fun PlaylistBatchOpsHost() {
    when (val state = PlaylistBatchOps.ui.value) {
        is PlaylistBatchOps.Ui.Picking -> BatchPlaylistPicker(state.songs)
        is PlaylistBatchOps.Ui.Running -> BatchProgressDialog(
            title = "正在加入「${state.playlistName}」",
            progress = state.progress,
            step = state.step
        )
        is PlaylistBatchOps.Ui.Result -> BatchAddResultDialog(state.playlistName, state.outcomes)
        null -> Unit
    }
}

// =====================================================================================
// 弹窗外壳：液态玻璃面板 + 随窗口自适应（最宽 380dp；列表最高为窗口高度的一半）
// =====================================================================================

@Composable
private fun BatchDialogShell(
    onDismiss: () -> Unit,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible
        )
    ) {
        GlassDialogPanel(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .widthIn(max = 380.dp),
            cornerRadius = 22.dp
        ) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp), content = content)
        }
    }
}

/** 窗口高度的一定比例（横屏 / 分屏时列表不顶出窗口） */
@Composable
private fun windowFraction(fraction: Float, min: Dp, max: Dp): Dp {
    val h = LocalConfiguration.current.screenHeightDp.dp
    return (h * fraction).coerceIn(min, max)
}

@Composable
private fun fieldBg(): Color {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    return if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)
}

@Composable
private fun DialogHeader(title: String, subtitle: String?, onClose: (() -> Unit)?) {
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onClose != null) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(fieldBg())
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, contentDescription = "关闭", tint = sub, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** 批量操作进度窗口（加入 / 移出歌单共用）：不可关闭，完成后由调用方关掉 */
@Composable
fun BatchProgressDialog(title: String, progress: Float, step: String) {
    BatchDialogShell(onDismiss = {}, dismissible = false) {
        DialogHeader(title, null, null)
        Spacer(Modifier.height(14.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            color = AppleRed,
            trackColor = fieldBg(),
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(step, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${(progress.coerceIn(0f, 1f) * 100).toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun BatchPlaylistPicker(songs: List<UnifiedSong>) {
    val all = PlaylistBatchOps.playlists.value
    val serverConfigured = PlaylistBatchOps.serverConfigured.value
    // 云端歌单要连着服务器才能改
    val serverUsable = serverConfigured && !com.lm.player.core.source.ServerLinkState.isLost
    val targets = remember(all, serverUsable) { all.filter { !it.isOnline || serverUsable } }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    val focus = remember { FocusRequester() }
    LaunchedEffect(creating) { if (creating) runCatching { focus.requestFocus() } }

    BatchDialogShell(onDismiss = { PlaylistBatchOps.dismiss() }) {
        DialogHeader("加入歌单", "已选 ${songs.size} 首", onClose = { PlaylistBatchOps.dismiss() })
        Spacer(Modifier.height(10.dp))
        LazyColumn(modifier = Modifier.heightIn(max = windowFraction(0.5f, 180.dp, 420.dp))) {
            // 新建歌单（原位展开输入框，回车 / ✓ 新建并加入）
            item(key = "__create") {
                if (!creating) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { creating = true }
                            .padding(horizontal = 4.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(AppleRed.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = AppleRed, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("新建歌单", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppleRed)
                            Text(if (serverUsable) "建在服务器上" else "建在本机", fontSize = 11.sp, color = sub)
                        }
                    }
                } else {
                    val ok = newName.isNotBlank()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(fieldBg())
                            .border(1.dp, AppleRed.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(start = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (newName.isEmpty()) Text("新歌单名称", fontSize = 13.sp, color = sub.copy(alpha = 0.7f))
                            BasicTextField(
                                value = newName,
                                onValueChange = { newName = it.take(40) },
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 13.sp, color = onSurface, fontWeight = FontWeight.Medium),
                                cursorBrush = SolidColor(AppleRed),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { if (ok) PlaylistBatchOps.createAndPick(newName, songs) }),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focus)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .clickable {
                                    creating = false
                                    newName = ""
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "取消", tint = sub, modifier = Modifier.size(16.dp))
                        }
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(if (ok) AppleRed else AppleRed.copy(alpha = 0.3f))
                                .then(if (ok) Modifier.clickable { PlaylistBatchOps.createAndPick(newName, songs) } else Modifier),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "新建并加入", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            if (targets.isEmpty()) {
                item(key = "__empty") {
                    Text("还没有可加入的歌单，先新建一个", fontSize = 12.sp, color = sub, modifier = Modifier.padding(horizontal = 6.dp, vertical = 14.dp))
                }
            }
            items(targets, key = { it.id }) { pl ->
                val cover = remember(pl.previewCovers, pl.coverUrl) {
                    pl.previewCovers.firstOrNull { it.isNotBlank() }
                        ?: pl.coverUrl.split("|").firstOrNull { it.isNotBlank() }.orEmpty()
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { PlaylistBatchOps.pick(pl, songs) }
                        .padding(horizontal = 4.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AlbumArtworkImage(
                        model = cover,
                        seedId = pl.id,
                        targetSize = 120,
                        cornerRadius = 8.dp,
                        modifier = Modifier.size(38.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(pl.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (pl.isOnline) {
                                Icon(Icons.Default.Cloud, contentDescription = "云端", tint = sub, modifier = Modifier.size(11.dp))
                                Spacer(Modifier.width(3.dp))
                            }
                            Text("${pl.songCount} 首", fontSize = 11.sp, color = sub)
                        }
                    }
                    Icon(Icons.Default.Add, contentDescription = "加入", tint = AppleRed, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (serverConfigured && !serverUsable) {
            Text(
                "服务器离线，云端歌单暂时不能加入",
                fontSize = 11.sp,
                color = sub,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp)
            )
        }
    }
}

@Composable
private fun BatchAddResultDialog(playlistName: String, outcomes: List<PlaylistAddOutcome>) {
    val added = remember(outcomes) { outcomes.filter { it.status == PlaylistAddStatus.ADDED } }
    val existed = remember(outcomes) { outcomes.filter { it.status == PlaylistAddStatus.EXISTED } }
    val failed = remember(outcomes) { outcomes.filter { it.status == PlaylistAddStatus.FAILED } }
    // 默认展开失败的明细（有的话）
    var shown by remember(outcomes) { mutableStateOf(if (failed.isNotEmpty()) PlaylistAddStatus.FAILED else null) }
    val sub = MaterialTheme.colorScheme.onSurfaceVariant

    BatchDialogShell(onDismiss = { PlaylistBatchOps.dismiss() }) {
        DialogHeader("加入「$playlistName」", "共 ${outcomes.size} 首", onClose = { PlaylistBatchOps.dismiss() })
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatChip("已添加", added.size, Color(0xFF34C759), shown == PlaylistAddStatus.ADDED, Modifier.weight(1f)) {
                shown = if (shown == PlaylistAddStatus.ADDED) null else PlaylistAddStatus.ADDED
            }
            StatChip("已存在", existed.size, Color(0xFFFF9F0A), shown == PlaylistAddStatus.EXISTED, Modifier.weight(1f)) {
                shown = if (shown == PlaylistAddStatus.EXISTED) null else PlaylistAddStatus.EXISTED
            }
            StatChip("失败", failed.size, Color(0xFFFF3B30), shown == PlaylistAddStatus.FAILED, Modifier.weight(1f)) {
                shown = if (shown == PlaylistAddStatus.FAILED) null else PlaylistAddStatus.FAILED
            }
        }
        Spacer(Modifier.height(10.dp))
        val list = when (shown) {
            PlaylistAddStatus.ADDED -> added
            PlaylistAddStatus.EXISTED -> existed
            PlaylistAddStatus.FAILED -> failed
            null -> emptyList()
        }
        when {
            shown == null -> Text("点上面的数字查看对应的歌曲（失败的写明原因；已存在的已忽略）", fontSize = 12.sp, color = sub)
            list.isEmpty() -> Text("没有", fontSize = 12.sp, color = sub)
            else -> LazyColumn(
                modifier = Modifier.heightIn(max = windowFraction(0.4f, 140.dp, 320.dp)),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(list) { o ->
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp)) {
                        Text(
                            "${o.song.title} · ${o.song.artist}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (o.reason.isNotBlank() && o.status != PlaylistAddStatus.ADDED) {
                            Text(
                                o.reason,
                                fontSize = 11.sp,
                                color = if (o.status == PlaylistAddStatus.FAILED) Color(0xFFFF3B30) else sub
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(AppleRed)
                .clickable { PlaylistBatchOps.dismiss() },
            contentAlignment = Alignment.Center
        ) {
            Text("完成", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatChip(label: String, count: Int, color: Color, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) color.copy(alpha = 0.18f) else color.copy(alpha = 0.08f))
            .border(1.dp, if (selected) color else color.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("$count", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

// =====================================================================================
// 多选底部悬浮操作条（胶囊）：下载 / 加入歌单 / 移出歌单。点「下载」「加入歌单」直接从按钮弹出选项面板（液态玻璃），不再开弹窗
// =====================================================================================

/**
 * 页面多选时调用（放在页面顶层，不要放在列表项里：列表项滑出屏幕会被回收，悬浮条也跟着消失）。
 * [visible] 为 false 时不显示；离开页面自动撤销。
 *
 * @param selectedSongs 当前选中的歌（点按钮时才取）
 * @param onDownload 选好下载位置与音质后调用（与单首下载同一个面板）
 * @param onFinished 加入歌单完成后调用（一般用来退出多选）
 */
@Composable
fun SongBatchFloatingBar(
    key: Any,
    visible: Boolean,
    selectedCount: Int,
    selectedSongs: () -> List<UnifiedSong>,
    isServerConnected: Boolean,
    onDownload: (List<UnifiedSong>, com.lm.player.core.model.DownloadTarget, com.lm.player.core.model.AudioQuality) -> Unit,
    onFinished: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null
) {
    if (!visible) return
    val latestSongs = rememberUpdatedState(selectedSongs)
    val latestDownload = rememberUpdatedState(onDownload)
    val latestFinished = rememberUpdatedState(onFinished)
    val latestRemove = rememberUpdatedState(onRemoveFromPlaylist)
    com.lm.player.core.designsystem.glass.RegisterGlassFloatingBar(key = key, capsule = true) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val hasSelection = selectedCount > 0
        var downloadMenu by remember { mutableStateOf(false) }
        var playlistMenu by remember { mutableStateOf(false) }
        // 下载面板标题行显示「已选 N 首」（与单首下载同一个面板，只是没有某首歌的本地 / 服务器状态）
        val batchSong = remember(selectedCount) {
            UnifiedSong(
                id = "batch_download_$selectedCount",
                title = "已选 $selectedCount 首",
                artist = "批量下载",
                album = "",
                durationMs = 0L,
                coverUrl = "",
                streamUrl = "",
                serverId = "lemon_online"
            )
        }
        Row(
            modifier = Modifier
                .height(52.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "已选 $selectedCount",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (hasSelection) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 10.dp, end = 6.dp)
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(22.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            )
            Box {
                CapsuleAction(Icons.Default.Download, "下载", enabled = hasSelection) { downloadMenu = true }
                DownloadQualityDropdownMenu(
                    expanded = downloadMenu,
                    onDismissRequest = { downloadMenu = false },
                    song = batchSong,
                    isServerConnected = isServerConnected,
                    hasLocal = false,
                    hasServer = false,
                    preferAbove = true,
                    alignCenter = true,
                    onConfirm = { target, quality ->
                        downloadMenu = false
                        val picked = latestSongs.value()
                        if (picked.isNotEmpty()) latestDownload.value(picked, target, quality)
                    }
                )
            }
            Box {
                CapsuleAction(Icons.AutoMirrored.Filled.PlaylistAdd, "加入歌单", enabled = hasSelection) { playlistMenu = true }
                PlaylistBatchAddMenu(
                    expanded = playlistMenu,
                    onDismissRequest = { playlistMenu = false },
                    selectedCount = selectedCount,
                    onPick = { target ->
                        playlistMenu = false
                        PlaylistBatchOps.pickWith(context, target, latestSongs.value()) { latestFinished.value() }
                    },
                    onCreate = { name ->
                        playlistMenu = false
                        PlaylistBatchOps.createWith(context, name, latestSongs.value()) { latestFinished.value() }
                    }
                )
            }
            val remove = latestRemove.value
            if (remove != null) {
                CapsuleAction(Icons.Default.PlaylistRemove, "移出", enabled = hasSelection, danger = true) { remove() }
            }
        }
    }
}

@Composable
private fun CapsuleAction(icon: ImageVector, label: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        danger -> MaterialTheme.colorScheme.error
        else -> AppleRed
    }
    Row(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else tint,
            maxLines = 1
        )
    }
}

/** 「加入歌单」面板（从按钮弹出、向上展开，液态玻璃）：首行原位新建，下面是可加入的歌单 */
@Composable
private fun PlaylistBatchAddMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    selectedCount: Int,
    onPick: (UnifiedPlaylist) -> Unit,
    onCreate: (String) -> Unit
) {
    val all = PlaylistBatchOps.playlists.value
    val serverConfigured = PlaylistBatchOps.serverConfigured.value
    val serverUsable = serverConfigured && !com.lm.player.core.source.ServerLinkState.isLost
    val targets = remember(all, serverUsable) { all.filter { !it.isOnline || serverUsable } }
    var creating by remember(expanded) { mutableStateOf(false) }
    var newName by remember(expanded) { mutableStateOf("") }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    val focus = remember { FocusRequester() }
    LaunchedEffect(creating) { if (creating) runCatching { focus.requestFocus() } }

    com.lm.player.core.designsystem.glass.AdaptiveDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.width(260.dp),
        scrollable = false,
        preferAbove = true,
        alignCenter = true
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 10.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("加入歌单", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = onSurface)
            Spacer(Modifier.width(8.dp))
            Text("已选 $selectedCount 首", fontSize = 11.sp, color = sub, maxLines = 1, modifier = Modifier.weight(1f))
        }
        if (!creating) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { creating = true }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppleRed.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = AppleRed, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("新建歌单", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = AppleRed)
                    Text(if (serverUsable) "建在服务器上" else "建在本机", fontSize = 10.5.sp, color = sub)
                }
            }
        } else {
            val ok = newName.isNotBlank()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(fieldBg())
                    .border(1.dp, AppleRed.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (newName.isEmpty()) Text("新歌单名称", fontSize = 13.sp, color = sub.copy(alpha = 0.7f))
                    BasicTextField(
                        value = newName,
                        onValueChange = { newName = it.take(40) },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 13.sp, color = onSurface, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(AppleRed),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (ok) onCreate(newName) }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (ok) AppleRed else AppleRed.copy(alpha = 0.3f))
                        .then(if (ok) Modifier.clickable { onCreate(newName) } else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, contentDescription = "新建并加入", tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }
        }
        if (targets.isEmpty()) {
            Text("还没有可加入的歌单", fontSize = 12.sp, color = sub, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        } else {
            // 不能用 LazyColumn：低档 / 关闭液态玻璃时面板退回系统下拉菜单，它会按内容测「固有宽度」，懒加载列表不支持会闪退
            Column(
                modifier = Modifier
                    .heightIn(max = windowFraction(0.42f, 160.dp, 340.dp))
                    .verticalScroll(rememberScrollState())
            ) {
                targets.forEach { pl ->
                    val cover = remember(pl.previewCovers, pl.coverUrl) {
                        pl.previewCovers.firstOrNull { it.isNotBlank() }
                            ?: pl.coverUrl.split("|").firstOrNull { it.isNotBlank() }.orEmpty()
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(pl) }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AlbumArtworkImage(
                            model = cover,
                            seedId = pl.id,
                            targetSize = 96,
                            cornerRadius = 7.dp,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(pl.name, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (pl.isOnline) {
                                    Icon(Icons.Default.Cloud, contentDescription = "云端", tint = sub, modifier = Modifier.size(10.dp))
                                    Spacer(Modifier.width(3.dp))
                                }
                                Text("${pl.songCount} 首", fontSize = 10.5.sp, color = sub)
                            }
                        }
                    }
                }
            }
        }
        if (serverConfigured && !serverUsable) {
            Text("服务器离线，云端歌单暂时不能加入", fontSize = 10.5.sp, color = sub, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        }
    }
}
