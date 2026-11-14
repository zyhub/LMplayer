package com.lm.player.feature.download

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.lm.player.core.designsystem.glass.LiquidBottomTab
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.DownloadTask
import com.lm.player.core.model.LemonServerDownloadTaskRecord
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.network.LemonMusicProtocol
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 现代轻奢风格已下载音乐管理中心
 * 支持：多选批量删除、存储信息看板、单曲详细存储信息、复制路径与基础编辑
 */
@Composable
fun DownloadManagerScreen(
    activeTasks: List<DownloadTask>,
    completedSongs: List<UnifiedSong>,
    onSongClick: (UnifiedSong, List<UnifiedSong>?) -> Unit = { _, _ -> },
    onCancelTask: (String) -> Unit,
    onPauseTask: (String) -> Unit = {},
    onResumeTask: (String) -> Unit = {},
    onPauseTasks: (Set<String>) -> Unit = {},
    onResumeTasks: (Set<String>) -> Unit = {},
    onCancelTasks: (Set<String>) -> Unit = {},
    onPauseAll: () -> Unit = {},
    onResumeAll: () -> Unit = {},
    onDeleteDownloadedSong: (UnifiedSong) -> Unit,
    onDeleteDownloadedSongs: (List<UnifiedSong>) -> Unit = {},
    onReEmbedSong: (UnifiedSong) -> Unit = {},
    onReEmbedAll: () -> Unit = {},
    /** 多选「补全标签」：通过服务端标签匹配为所选本机文件补封面 / 歌词 / 缺失的歌手专辑 */
    onFillTagsFromServer: (List<UnifiedSong>) -> Unit = {},
    downloadPath: String = "",
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    val dimensions = LocalAppDimensions.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(if (activeTasks.isNotEmpty()) 0 else 2) } // 0: 本地下载, 1: 服务器下载, 2: 已完成
    val syncManager = remember { com.lm.player.core.sync.OfflineSyncManager.getInstance(context) }
    val pendingPushCount by syncManager.pendingPushCount.collectAsState()
    val pendingPushList by syncManager.pendingPushList.collectAsState()
    val pendingPushFailedCount = remember(pendingPushList) {
        pendingPushList.count { it.serverPushStatus == 3 }
    }
    val isPushingToServer by syncManager.isPushing.collectAsState()

    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedSongIds = remember { mutableStateListOf<String>() }
    var showFineGrainedSyncDialog by remember { mutableStateOf(false) }

    var isMultiSelectActiveMode by remember { mutableStateOf(false) }
    val selectedActiveTaskIds = remember { mutableStateListOf<String>() }
    // 服务器下载：多选删除
    var isMultiSelectServerMode by remember { mutableStateOf(false) }
    val selectedServerTaskIds = remember { mutableStateListOf<String>() }
    var showServerBatchDeleteDialog by remember { mutableStateOf(false) }

    // 搜索（只过滤当前分段的列表，不走网络）与三个分段各自的滚动位置
    var dmSearchOpen by remember { mutableStateOf(false) }
    // 「离线下载待同步」提示：用户关掉后不再显示（推送入口移到 ⋯ 菜单）
    val dmPrefs = remember { context.getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE) }
    var pushBannerHidden by remember { mutableStateOf(dmPrefs.getBoolean("dm_push_banner_hidden", false)) }
    var dmQuery by remember { mutableStateOf("") }
    val activeListState = rememberLazyListState()
    val serverListState = rememberLazyListState()
    val completedListState = rememberLazyListState()

    // 服务器下载任务列表与状态
    var serverDownloads by remember { mutableStateOf<List<LemonServerDownloadTaskRecord>>(emptyList()) }
    var isServerLoading by remember { mutableStateOf(false) }
    /** 服务器下载列表的错误信息（null 表示无错误），用于把「静默空白」变成可读提示 */
    var serverDownloadError by remember { mutableStateOf<String?>(null) }
    var taskPendingDelete by remember { mutableStateOf<LemonServerDownloadTaskRecord?>(null) }
    var taskPendingConfirm by remember { mutableStateOf<LemonServerDownloadTaskRecord?>(null) }

    fun refreshServerDownloads() {
        isServerLoading = true
        scope.launch {
            LemonMusicProtocol.fetchServerDownloadList(context)
                .onSuccess { list ->
                    serverDownloads = list.sortedByDescending { it.createdAt }
                    isServerLoading = false
                    serverDownloadError = null
                }
                .onFailure { e ->
                    // 此前失败被静默吞掉：列表空着、没有加载态、也没有任何提示，
                    // 用户只能看到「服务器下载 (0)」，无法判断是服务端确实没有记录、
                    // 还是请求失败、还是根本没连上服务器。这里把失败暴露出来。
                    isServerLoading = false
                    serverDownloadError = e.message ?: "未知错误"
                    android.util.Log.w("DownloadManagerScreen", "拉取服务器下载列表失败", e)
                    if (e.message?.contains("未连接") == true) {
                        Toast.makeText(context, "未连接柠檬音乐服务端，无法获取服务器下载列表", Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    fun deleteServerTask(taskId: String) {
        scope.launch {
            LemonMusicProtocol.deleteServerDownloadTask(taskId, context)
                .onSuccess {
                    Toast.makeText(context, "已删除服务器下载任务", Toast.LENGTH_SHORT).show()
                    refreshServerDownloads()
                }
                .onFailure {
                    Toast.makeText(context, "删除失败: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /** 批量删除服务器下载任务（服务端没有批量接口，逐个删，完成后统一刷新一次） */
    fun deleteServerTasks(taskIds: List<String>) {
        if (taskIds.isEmpty()) return
        scope.launch {
            var ok = 0
            var failed = 0
            for (id in taskIds) {
                if (LemonMusicProtocol.deleteServerDownloadTask(id, context).isSuccess) ok++ else failed++
            }
            Toast.makeText(
                context,
                if (failed == 0) "已删除 $ok 个服务器下载任务" else "已删除 $ok 个，$failed 个删除失败",
                Toast.LENGTH_SHORT
            ).show()
            refreshServerDownloads()
        }
    }

    fun clearCompletedServerTasks() {
        scope.launch {
            LemonMusicProtocol.clearCompletedServerDownloads(context)
                .onSuccess {
                    Toast.makeText(context, "已清理已完成任务", Toast.LENGTH_SHORT).show()
                    refreshServerDownloads()
                }
                .onFailure {
                    Toast.makeText(context, "清理失败: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    LaunchedEffect(Unit) {
        refreshServerDownloads()
    }

    LaunchedEffect(selectedTab, serverDownloads) {
        if (selectedTab == 1) {
            // 服务端排队状态是 waiting：此前只看 downloading，排队中的任务不会自动刷新出进度
            val hasActiveTasks = serverDownloads.any {
                val s = it.status.lowercase()
                s == "downloading" || s == "waiting" || s == "pending" || s == "running"
            }
            if (hasActiveTasks) {
                kotlinx.coroutines.delay(3000L)
                refreshServerDownloads()
            }
        }
    }

    // 安卓系统返回键逐层回退：先退出多选状态，否则返回上一级页面
    BackHandler {
        when {
            dmSearchOpen -> {
                dmSearchOpen = false
                dmQuery = ""
            }
            isMultiSelectMode -> {
                isMultiSelectMode = false
                selectedSongIds.clear()
            }
            isMultiSelectActiveMode -> {
                isMultiSelectActiveMode = false
                selectedActiveTaskIds.clear()
            }
            isMultiSelectServerMode -> {
                isMultiSelectServerMode = false
                selectedServerTaskIds.clear()
            }
            else -> onBack()
        }
    }

    // 弹窗状态
    var showBatchDeleteConfirmDialog by remember { mutableStateOf(false) }
    var singleSongToDelete by remember { mutableStateOf<UnifiedSong?>(null) }
    var songForDetailsDialog by remember { mutableStateOf<UnifiedSong?>(null) }

    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)

    // 本地存储信息统计 (实时计算已下载音频物理文件大小与可用空间)
    val storageStats = remember(completedSongs, downloadPath) {
        var totalBytes = 0L
        completedSongs.forEach { song ->
            val p = song.localFilePath
            if (!p.isNullOrBlank()) {
                val f = File(p)
                if (f.exists() && f.isFile) {
                    totalBytes += f.length()
                }
            }
        }
        val targetDir = if (downloadPath.isNotBlank()) File(downloadPath) else context.getExternalFilesDir(null)
        val freeBytes = try { targetDir?.freeSpace ?: 0L } catch (_: Exception) { 0L }
        Triple(totalBytes, freeBytes, targetDir?.absolutePath ?: "")
    }
    val totalSizeBytes = storageStats.first
    val freeSizeBytes = storageStats.second
    val currentPathDisplay = storageStats.third

    // 多选歌曲及总大小计算
    val selectedSongs = remember(selectedSongIds.toList(), completedSongs) {
        completedSongs.filter { it.id in selectedSongIds }
    }
    val selectedTotalSizeBytes = remember(selectedSongs) {
        selectedSongs.sumOf { song ->
            val p = song.localFilePath
            if (!p.isNullOrBlank()) {
                val f = File(p)
                if (f.exists()) f.length() else 0L
            } else 0L
        }
    }

    // 底栏高度：列表底部留白用它，而不是把整页往上缩（缩了以后底栏下面只剩背景色，像一条白条挡住列表）
    val bottomInset = contentPadding.calculateBottomPadding()
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // 搜索过滤（歌名 / 歌手 / 专辑）
        val dmQ = dmQuery.trim()
        val shownActiveTasks = if (dmQ.isEmpty()) activeTasks else activeTasks.filter { it.song.dmMatches(dmQ) }
        val shownCompletedSongs = if (dmQ.isEmpty()) completedSongs else completedSongs.filter { it.dmMatches(dmQ) }
        val shownServerDownloads = if (dmQ.isEmpty()) serverDownloads else serverDownloads.filter {
            it.name.contains(dmQ, ignoreCase = true) || it.singer.contains(dmQ, ignoreCase = true) || it.album.contains(dmQ, ignoreCase = true)
        }
        // ===== 精简后的下载管理：一行标题栏（返回 / 标题 / 搜索 / 更多）+ 三段切换（+ 搜索框）；
        // 液态玻璃时整块放进上层玻璃顶栏，列表从下面穿过，滚动后收成小胶囊 =====
        val dmGlass = com.lm.player.core.designsystem.glass.rememberLiquidGlassActive()
        val dmTopInset = com.lm.player.core.designsystem.glass.rememberGlassListTopInset()
        val dmListTop = if (dmGlass) dmTopInset + 8.dp else 8.dp
        val currentListState = when (selectedTab) {
            0 -> activeListState
            1 -> serverListState
            else -> completedListState
        }
        val isAnyMultiSelect = when (selectedTab) {
            0 -> isMultiSelectActiveMode
            1 -> isMultiSelectServerMode
            else -> isMultiSelectMode
        }
        val currentSelectIds = when (selectedTab) {
            0 -> selectedActiveTaskIds
            1 -> selectedServerTaskIds
            else -> selectedSongIds
        }
        val currentSelectCount = currentSelectIds.size
        val dmScrolledDown = com.lm.player.core.designsystem.glass.rememberTopBarCollapsed(selectedTab) {
            currentListState.firstVisibleItemIndex to currentListState.firstVisibleItemScrollOffset
        }
        val downloadingCount = activeTasks.count { it.status == DownloadStatus.DOWNLOADING }
        val pausedCount = activeTasks.count { it.status == DownloadStatus.PAUSED }
        val dmSubtitle = buildList {
            if (activeTasks.isNotEmpty()) add(if (downloadingCount > 0) "$downloadingCount 首下载中" else "${activeTasks.size} 个任务已暂停")
            add("已下载 ${completedSongs.size} 首")
            if (totalSizeBytes > 0L) add(formatStorageSize(totalSizeBytes))
        }.joinToString(" · ")
        var dmMenuOpen by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (dmGlass) Modifier else Modifier.statusBarsPadding())
                .padding(horizontal = 16.dp)
        ) {
            com.lm.player.core.designsystem.glass.GlassListHeader(
                key = "download_manager",
                title = if (isAnyMultiSelect) "已选 $currentSelectCount 项" else "下载管理",
                subtitle = dmSubtitle,
                onBack = {
                    when {
                        isMultiSelectMode -> {
                            isMultiSelectMode = false
                            selectedSongIds.clear()
                        }
                        isMultiSelectActiveMode -> {
                            isMultiSelectActiveMode = false
                            selectedActiveTaskIds.clear()
                        }
                        isMultiSelectServerMode -> {
                            isMultiSelectServerMode = false
                            selectedServerTaskIds.clear()
                        }
                        else -> onBack()
                    }
                },
                collapsed = dmScrolledDown && !dmSearchOpen && !isAnyMultiSelect,
                onTapCapsule = { scope.launch { currentListState.animateScrollToItem(0) } },
                capsuleInfo = when (selectedTab) {
                    0 -> "下载中 ${activeTasks.size}"
                    1 -> "服务器 ${serverDownloads.size}"
                    else -> "已完成 ${completedSongs.size}"
                },
                titleSize = dimensions.sectionTitleSize,
                subtitleSize = dimensions.captionSize,
                rowVerticalPadding = 6.dp,
                actions = {
                    if (isAnyMultiSelect) {
                        val selectable = when (selectedTab) {
                            0 -> shownActiveTasks.map { it.song.id }
                            1 -> shownServerDownloads.map { it.id }
                            else -> shownCompletedSongs.map { it.id }
                        }
                        val allSelected = selectable.isNotEmpty() && currentSelectIds.containsAll(selectable)
                        TextButton(
                            onClick = {
                                val target = currentSelectIds
                                target.clear()
                                if (!allSelected) target.addAll(selectable)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(if (allSelected) "取消全选" else "全选", color = AppleRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = {
                                isMultiSelectMode = false
                                selectedSongIds.clear()
                                isMultiSelectActiveMode = false
                                selectedActiveTaskIds.clear()
                                isMultiSelectServerMode = false
                                selectedServerTaskIds.clear()
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("完成", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        DmIconButton(Icons.Default.Search, "搜索") {
                            dmSearchOpen = !dmSearchOpen
                            if (!dmSearchOpen) dmQuery = ""
                        }
                        Spacer(Modifier.width(6.dp))
                        Box {
                            DmIconButton(Icons.Default.MoreHoriz, "更多") { dmMenuOpen = true }
                            com.lm.player.core.designsystem.glass.AdaptiveDropdownMenu(
                                expanded = dmMenuOpen,
                                onDismissRequest = { dmMenuOpen = false }
                            ) {
                                when (selectedTab) {
                                    0 -> {
                                        if (activeTasks.isNotEmpty()) DropdownMenuItem(
                                            text = { Text("多选管理") },
                                            leadingIcon = { Icon(Icons.Default.Checklist, contentDescription = null) },
                                            onClick = {
                                                dmMenuOpen = false
                                                isMultiSelectActiveMode = true
                                                selectedActiveTaskIds.clear()
                                            }
                                        )
                                        if (downloadingCount > 0) DropdownMenuItem(
                                            text = { Text("全部暂停") },
                                            leadingIcon = { Icon(Icons.Default.Pause, contentDescription = null) },
                                            onClick = {
                                                dmMenuOpen = false
                                                onPauseAll()
                                            }
                                        )
                                        if (pausedCount > 0) DropdownMenuItem(
                                            text = { Text("全部继续") },
                                            leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                                            onClick = {
                                                dmMenuOpen = false
                                                onResumeAll()
                                            }
                                        )
                                    }
                                    1 -> {
                                        if (serverDownloads.isNotEmpty()) DropdownMenuItem(
                                            text = { Text("多选管理") },
                                            leadingIcon = { Icon(Icons.Default.Checklist, contentDescription = null) },
                                            onClick = {
                                                dmMenuOpen = false
                                                isMultiSelectServerMode = true
                                                selectedServerTaskIds.clear()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("刷新") },
                                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                            onClick = {
                                                dmMenuOpen = false
                                                refreshServerDownloads()
                                            }
                                        )
                                        if (serverDownloads.any { it.status == "completed" }) DropdownMenuItem(
                                            text = { Text("清空已完成") },
                                            leadingIcon = { Icon(Icons.Default.ClearAll, contentDescription = null) },
                                            onClick = {
                                                dmMenuOpen = false
                                                clearCompletedServerTasks()
                                            }
                                        )
                                    }
                                    else -> {
                                        if (completedSongs.isNotEmpty()) DropdownMenuItem(
                                            text = { Text("多选管理") },
                                            leadingIcon = { Icon(Icons.Default.Checklist, contentDescription = null) },
                                            onClick = {
                                                dmMenuOpen = false
                                                isMultiSelectMode = true
                                                selectedSongIds.clear()
                                            }
                                        )
                                        if (pendingPushCount > 0) DropdownMenuItem(
                                            text = { Text("推送离线下载 ($pendingPushCount)") },
                                            leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null) },
                                            onClick = {
                                                dmMenuOpen = false
                                                showFineGrainedSyncDialog = true
                                            }
                                        )
                                        if (completedSongs.isNotEmpty()) DropdownMenuItem(
                                            text = { Text("补全全部标签") },
                                            leadingIcon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null) },
                                            onClick = {
                                                dmMenuOpen = false
                                                onReEmbedAll()
                                            }
                                        )
                                    }
                                }
                                if (currentPathDisplay.isNotBlank()) DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("存储位置")
                                            Text(
                                                text = "剩余 ${formatStorageSize(freeSizeBytes)} · $currentPathDisplay",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null) },
                                    onClick = { dmMenuOpen = false }
                                )
                            }
                        }
                    }
                },
                searchField = if (dmSearchOpen) {
                    {
                        com.lm.player.feature.library.LibrarySubViewSearchField(
                            query = dmQuery,
                            onQueryChange = { dmQuery = it },
                            onClose = {
                                dmSearchOpen = false
                                dmQuery = ""
                            },
                            placeholder = "搜索歌名、歌手、专辑",
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else null,
                below = {
                    // 三段切换：下载中 / 服务器 / 已完成。液态玻璃时与播放页「歌曲 | 歌词」同一套玻璃标签栏
                    val selectTab: (Int) -> Unit = { idx ->
                        selectedTab = idx
                        isMultiSelectMode = false
                        selectedSongIds.clear()
                        isMultiSelectActiveMode = false
                        selectedActiveTaskIds.clear()
                        isMultiSelectServerMode = false
                        selectedServerTaskIds.clear()
                    }
                    val tabs = listOf(
                        Triple(0, "下载中", activeTasks.size),
                        Triple(1, "服务器", serverDownloads.size),
                        Triple(2, "已完成", completedSongs.size)
                    )
                    val tabBackdrop = com.lm.player.core.designsystem.glass.LocalGlassBackdrop.current
                    if (tabBackdrop != null) {
                        val latestTab = rememberUpdatedState(selectedTab)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                        ) {
                            com.lm.player.core.designsystem.glass.LiquidBottomTabs(
                                selectedTabIndex = { latestTab.value },
                                onTabSelected = { selectTab(it) },
                                backdrop = tabBackdrop,
                                tabsCount = 3,
                                accentColor = AppleRed,
                                barHeight = 36.dp,
                                innerPadding = 3.dp,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                tabs.forEach { (idx, label, count) ->
                                    LiquidBottomTab(onClick = { selectTab(idx) }) {
                                        Text(
                                            text = if (count > 0) "$label $count" else label,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    } else Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                            .padding(3.dp)
                    ) {
                        tabs.forEach { (idx, label, count) ->
                            val selected = selectedTab == idx
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                                    .clickable { selectTab(idx) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (count > 0) "$label $count" else label,
                                    fontSize = 12.5.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            )

            // 内容区域
            when (selectedTab) {
                0 -> {
                    if (shownActiveTasks.isEmpty()) {
                        DmEmptyState(
                            icon = Icons.Outlined.FileDownload,
                            text = if (dmQuery.isNotBlank() && activeTasks.isNotEmpty()) "没有匹配「${dmQuery.trim()}」的下载任务" else "当前没有正在下载的音乐",
                            topPadding = dmListTop
                        )
                    } else {
                        LazyColumn(
                            state = activeListState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(top = dmListTop, bottom = bottomInset + if (isMultiSelectActiveMode) 132.dp else 24.dp)
                        ) {
                            items(
                                items = shownActiveTasks,
                                key = { it.song.id },
                                contentType = { "active_download_task" }
                            ) { task ->
                                val isSelected = task.song.id in selectedActiveTaskIds
                                ActiveDownloadTaskRow(
                                    task = task,
                                    isMultiSelectMode = isMultiSelectActiveMode,
                                    isSelected = isSelected,
                                    onToggleSelect = {
                                        if (isSelected) selectedActiveTaskIds.remove(task.song.id) else selectedActiveTaskIds.add(task.song.id)
                                    },
                                    onPause = { onPauseTask(task.song.id) },
                                    onResume = { onResumeTask(task.song.id) },
                                    onCancel = { onCancelTask(task.song.id) }
                                )
                            }
                        }
                    }
                }
                1 -> {
                    ServerDownloadTasksContent(
                        serverDownloads = shownServerDownloads,
                        isLoading = isServerLoading,
                        errorMessage = serverDownloadError,
                        onRefresh = { refreshServerDownloads() },
                        onDeleteTask = { taskPendingDelete = it },
                        onHandleConfirm = { taskPendingConfirm = it },
                        listState = serverListState,
                        topPadding = dmListTop,
                        bottomPadding = bottomInset + if (isMultiSelectServerMode) 132.dp else 24.dp,
                        isMultiSelectMode = isMultiSelectServerMode,
                        selectedIds = selectedServerTaskIds,
                        onToggleSelect = { id -> if (id in selectedServerTaskIds) selectedServerTaskIds.remove(id) else selectedServerTaskIds.add(id) },
                        emptyText = if (dmQuery.isNotBlank() && serverDownloads.isNotEmpty()) "没有匹配「${dmQuery.trim()}」的服务器任务" else null
                    )
                }
                else -> {
                    if (shownCompletedSongs.isEmpty() && (pendingPushCount == 0 || pushBannerHidden)) {
                        DmEmptyState(
                            icon = Icons.Outlined.CheckCircle,
                            text = if (dmQuery.isNotBlank() && completedSongs.isNotEmpty()) "没有匹配「${dmQuery.trim()}」的歌曲" else "暂无已下载的离线音乐",
                            topPadding = dmListTop
                        )
                    } else {
                        LazyColumn(
                            state = completedListState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(top = dmListTop, bottom = bottomInset + if (isMultiSelectMode) 132.dp else 24.dp)
                        ) {
                            // 离线期间下载、待推送到服务器：一行提示 + 两个小按钮
                            if (pendingPushCount > 0 && !isMultiSelectMode && !pushBannerHidden) {
                                item(key = "dm_pending_push") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(AppleRed.copy(alpha = 0.08f))
                                            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.CloudUpload, contentDescription = null, tint = AppleRed, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = if (pendingPushFailedCount > 0) "$pendingPushCount 首待同步到服务器（$pendingPushFailedCount 首上次失败）" else "$pendingPushCount 首离线下载待同步到服务器",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 2,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = { showFineGrainedSyncDialog = true },
                                            enabled = !isPushingToServer,
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) { Text("选择", fontSize = 12.sp, color = AppleRed) }
                                        TextButton(
                                            onClick = {
                                                scope.launch {
                                                    val res = syncManager.pushAllPendingTasksToServer()
                                                    if (res.isSuccess) {
                                                        val count = res.getOrDefault(0)
                                                        val msg = if (count > 0) "已成功推送 $count 首歌曲至服务器下载队列" else "没有需要推送的歌曲（可能已在服务器曲库）"
                                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        Toast.makeText(context, "推送失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            enabled = !isPushingToServer,
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            if (isPushingToServer) {
                                                CircularProgressIndicator(color = AppleRed, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                            } else {
                                                Text("全部推送", fontSize = 12.sp, color = AppleRed, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .clickable {
                                                    pushBannerHidden = true
                                                    dmPrefs.edit().putBoolean("dm_push_banner_hidden", true).apply()
                                                    Toast.makeText(context, "已关闭提示，之后可在右上角 ⋯ 菜单里推送", Toast.LENGTH_SHORT).show()
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "关闭提示", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                            // 播放全部
                            if (shownCompletedSongs.isNotEmpty() && !isMultiSelectMode) {
                                item(key = "dm_play_all") {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .height(32.dp)
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(AppleRed)
                                                .clickable { shownCompletedSongs.firstOrNull()?.let { onSongClick(it, shownCompletedSongs) } }
                                                .padding(horizontal = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("播放全部", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = if (dmQuery.isNotBlank()) "找到 ${shownCompletedSongs.size} 首" else "共 ${completedSongs.size} 首 · ${formatStorageSize(totalSizeBytes)}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            items(
                                items = shownCompletedSongs,
                                key = { it.id },
                                contentType = { "completed_download_song" }
                            ) { song ->
                                val isSelected = song.id in selectedSongIds
                                CompletedDownloadSongRow(
                                    song = song,
                                    isMultiSelectMode = isMultiSelectMode,
                                    isSelected = isSelected,
                                    onToggleSelect = {
                                        if (isSelected) selectedSongIds.remove(song.id) else selectedSongIds.add(song.id)
                                    },
                                    onClick = {
                                        if (isMultiSelectMode) {
                                            if (isSelected) selectedSongIds.remove(song.id) else selectedSongIds.add(song.id)
                                        } else {
                                            onSongClick(song, shownCompletedSongs)
                                        }
                                    },
                                    onDelete = { singleSongToDelete = song },
                                    onShowDetails = { songForDetailsDialog = song }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. 多选悬浮操作条（下载中 / 服务器 / 已完成）：液态玻璃时登记到上层画成玻璃，折射下面的列表
        val selectionBarVisible = when (selectedTab) {
            0 -> isMultiSelectActiveMode
            1 -> isMultiSelectServerMode
            else -> isMultiSelectMode
        }
        val selectionBar: @Composable (Boolean) -> Unit = { glass ->
            when (selectedTab) {
                0 -> {
                    val selectedTasks = activeTasks.filter { it.song.id in selectedActiveTaskIds }
                    DmSelectionBar(
                        title = "已选 ${selectedActiveTaskIds.size} 项",
                        subtitle = "共 ${activeTasks.size} 个任务",
                        isDark = isDark,
                        borderColor = borderColor,
                        glass = glass,
                        actions = listOf(
                            DmBarAction(Icons.Default.Pause, "暂停", enabled = selectedTasks.any { it.status == DownloadStatus.DOWNLOADING }) {
                                onPauseTasks(selectedTasks.filter { it.status == DownloadStatus.DOWNLOADING }.map { it.song.id }.toSet())
                            },
                            DmBarAction(Icons.Default.PlayArrow, "继续", enabled = selectedTasks.any { it.status == DownloadStatus.PAUSED }) {
                                onResumeTasks(selectedTasks.filter { it.status == DownloadStatus.PAUSED }.map { it.song.id }.toSet())
                            },
                            DmBarAction(Icons.Default.Close, "取消下载", enabled = selectedActiveTaskIds.isNotEmpty(), danger = true) {
                                onCancelTasks(selectedActiveTaskIds.toSet())
                                selectedActiveTaskIds.clear()
                                isMultiSelectActiveMode = false
                            }
                        )
                    )
                }
                1 -> {
                    val hasSelection = selectedServerTaskIds.isNotEmpty()
                    val doneCount = serverDownloads.count { it.id in selectedServerTaskIds && it.status == "completed" }
                    DmSelectionBar(
                        title = "已选 ${selectedServerTaskIds.size} 项",
                        subtitle = if (hasSelection) "其中已完成 $doneCount 项 · 只删除任务记录" else "点任务进行选择",
                        isDark = isDark,
                        borderColor = borderColor,
                        glass = glass,
                        actions = listOf(
                            DmBarAction(Icons.Default.Refresh, "刷新") { refreshServerDownloads() },
                            DmBarAction(Icons.Default.Delete, "删除任务", enabled = hasSelection, danger = true) {
                                showServerBatchDeleteDialog = true
                            }
                        )
                    )
                }
                else -> {
                    val hasSelection = selectedSongIds.isNotEmpty()
                    DmSelectionBar(
                        title = "已选 ${selectedSongIds.size} 首",
                        subtitle = if (hasSelection) "占用 ${formatStorageSize(selectedTotalSizeBytes)}" else "点歌曲进行选择",
                        isDark = isDark,
                        borderColor = borderColor,
                        glass = glass,
                        actions = listOf(
                            DmBarAction(Icons.Default.AutoFixHigh, "补全标签", enabled = hasSelection) {
                                onFillTagsFromServer(selectedSongs)
                                selectedSongIds.clear()
                                isMultiSelectMode = false
                            },
                            DmBarAction(Icons.Default.CloudUpload, if (isPushingToServer) "同步中…" else "同步到服务器", enabled = hasSelection && !isPushingToServer) {
                                val requestedCount = selectedSongIds.size
                                scope.launch {
                                    val res = syncManager.pushSelectedTasksToServer(selectedSongIds.toList())
                                    if (res.isSuccess) {
                                        val count = res.getOrDefault(0)
                                        val skipped = (requestedCount - count).coerceAtLeast(0)
                                        val msg = if (count > 0) {
                                            "已推送 $count 首已选歌曲至服务器" + if (skipped > 0) "（$skipped 首已在服务器或缺少在线来源，已跳过）" else ""
                                        } else {
                                            "没有可推送的歌曲（所选歌曲可能已在服务器曲库）"
                                        }
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        selectedSongIds.clear()
                                        isMultiSelectMode = false
                                    } else {
                                        Toast.makeText(context, "推送失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            DmBarAction(androidx.compose.material.icons.Icons.AutoMirrored.Filled.PlaylistAdd, "加入歌单", enabled = hasSelection) {
                                com.lm.player.core.designsystem.component.PlaylistBatchOps.startAdd(context, selectedSongs) {
                                    selectedSongIds.clear()
                                    isMultiSelectMode = false
                                }
                            },
                            DmBarAction(Icons.Default.Delete, "删除", enabled = hasSelection, danger = true) {
                                showBatchDeleteConfirmDialog = true
                            }
                        )
                    )
                }
            }
        }
        if (dmGlass) {
            if (selectionBarVisible) {
                com.lm.player.core.designsystem.glass.RegisterGlassFloatingBar(
                    key = "download_manager_selection",
                    bottomPadding = bottomInset + 8.dp
                ) { selectionBar(true) }
            }
        } else {
            AnimatedVisibility(
                visible = selectionBarVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = bottomInset + 8.dp)
            ) {
                selectionBar(false)
            }
        }
    }

    // 4.3 服务器下载：批量删除确认
    if (showServerBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showServerBatchDeleteDialog = false },
            title = { Text("删除 ${selectedServerTaskIds.size} 个服务器下载任务？", fontWeight = FontWeight.Bold) },
            text = { Text("只删除服务器上的下载任务记录；已下载进服务器曲库的歌曲文件不受影响。此操作无法撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        showServerBatchDeleteDialog = false
                        deleteServerTasks(selectedServerTaskIds.toList())
                        selectedServerTaskIds.clear()
                        isMultiSelectServerMode = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showServerBatchDeleteDialog = false }) { Text("取消") }
            }
        )
    }

    // 5. 批量删除二次确认弹窗
    if (showBatchDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirmDialog = false },
            title = { Text("确认批量删除已选歌曲？", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "您即将物理删除选中的 ${selectedSongs.size} 首已下载歌曲：\n\n" +
                    "• 预计释放磁盘空间：${formatStorageSize(selectedTotalSizeBytes)}\n" +
                    "• 本机存储文件将被彻底清除\n" +
                    "• 云端歌曲将重置为在线未下载状态\n\n" +
                    "此操作无法撤销，确定继续吗？"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBatchDeleteConfirmDialog = false
                        onDeleteDownloadedSongs(selectedSongs)
                        selectedSongIds.clear()
                        isMultiSelectMode = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("彻底删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 5.1 精细化多选同步到服务器弹窗
    if (showFineGrainedSyncDialog) {
        val pendingList by syncManager.pendingPushList.collectAsState()
        val selectedSyncSongIds = remember(pendingList) {
            mutableStateListOf<String>().apply { addAll(pendingList.map { it.songId }) }
        }
        AlertDialog(
            onDismissRequest = { showFineGrainedSyncDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, tint = AppleRed, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("精细化同步至服务器", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    if (pendingList.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                if (selectedSyncSongIds.size == pendingList.size) {
                                    selectedSyncSongIds.clear()
                                } else {
                                    selectedSyncSongIds.clear()
                                    selectedSyncSongIds.addAll(pendingList.map { it.songId })
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (selectedSyncSongIds.size == pendingList.size) "取消全选" else "全选",
                                color = AppleRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "请勾选需要推送到柠檬 NAS 服务器下载归档的离线歌曲 (已选 ${selectedSyncSongIds.size}/${pendingList.size} 首)：",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (pendingList.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("暂无待同步的离线下载记录", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Box(modifier = Modifier.heightIn(max = 320.dp)) {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(pendingList, key = { it.songId }) { item ->
                                    val isChecked = item.songId in selectedSyncSongIds
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isChecked) AppleRed.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                        border = BorderStroke(1.dp, if (isChecked) AppleRed.copy(alpha = 0.4f) else borderColor),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable {
                                                if (isChecked) selectedSyncSongIds.remove(item.songId) else selectedSyncSongIds.add(item.songId)
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = isChecked,
                                                onCheckedChange = { chk ->
                                                    if (chk) selectedSyncSongIds.add(item.songId) else selectedSyncSongIds.remove(item.songId)
                                                },
                                                colors = CheckboxDefaults.colors(checkedColor = AppleRed),
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            AlbumArtworkImage(
                                                model = item.coverUrl,
                                                seedId = item.songId,
                                                modifier = Modifier.size(36.dp),
                                                cornerRadius = 6.dp
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(item.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text("${item.artist} · ${item.chosenQuality.ifBlank { "320k" }}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                if (item.serverPushStatus == 3 && !item.pushErrorMessage.isNullOrBlank()) {
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = "上次推送失败：${item.pushErrorMessage}",
                                                        fontSize = 9.5.sp,
                                                        color = MaterialTheme.colorScheme.error,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toSync = selectedSyncSongIds.toList()
                        showFineGrainedSyncDialog = false
                        scope.launch {
                            val res = syncManager.pushSelectedTasksToServer(toSync)
                            if (res.isSuccess) {
                                val count = res.getOrDefault(0)
                                val skipped = (toSync.size - count).coerceAtLeast(0)
                                val msg = if (count > 0) {
                                    "已推送 $count 首歌曲至服务器" + if (skipped > 0) "（$skipped 首已在服务器或缺少在线来源，已跳过）" else ""
                                } else {
                                    "没有可推送的歌曲（所选歌曲可能已在服务器曲库）"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "推送失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = selectedSyncSongIds.isNotEmpty() && !isPushingToServer,
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("开始同步 (${selectedSyncSongIds.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showFineGrainedSyncDialog = false },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("取消", fontSize = 12.sp)
                }
            }
        )
    }

    // 6. 单曲删除二次确认弹窗
    if (singleSongToDelete != null) {
        val s = singleSongToDelete!!
        AlertDialog(
            onDismissRequest = { singleSongToDelete = null },
            title = { Text("确认删除本地音频文件？", fontWeight = FontWeight.Bold) },
            text = {
                Text("确定从本机物理删除《${s.title}》吗？删除后可随时重新在线试听或下载。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        singleSongToDelete = null
                        onDeleteDownloadedSong(s)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { singleSongToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 7. 单曲存储详情与基础编辑弹窗
    if (songForDetailsDialog != null) {
        val song = songForDetailsDialog!!
        val file = song.localFilePath?.let { File(it) }
        val fileSizeFormatted = file?.let { if (it.exists()) formatStorageSize(it.length()) else "物理文件未找到" } ?: "未知大小"

        Dialog(onDismissRequest = { songForDetailsDialog = null }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("歌曲存储与信息详情", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { songForDetailsDialog = null }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(16.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AlbumArtworkImage(
                            model = song.coverUrl,
                            seedId = song.id,
                            targetSize = 160,
                            modifier = Modifier.size(54.dp),
                            cornerRadius = 10.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(song.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(song.artist, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(if (song.album.isNotBlank()) song.album else "单曲", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = borderColor, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    // 存储信息详情
                    val (realFmt, realBr, _) = com.lm.player.feature.home.resolveRealLocalFormatAndSize(song)
                    val hasLrc = file?.let { File(it.parentFile, "${it.nameWithoutExtension}.lrc").exists() } ?: false
                    SettingDetailRow(label = "物理文件大小", value = fileSizeFormatted)
                    SettingDetailRow(label = "音频解码格式", value = realFmt)
                    SettingDetailRow(label = "音频规格码率", value = "$realBr kbps")
                    SettingDetailRow(label = "伴随歌词文件", value = if (hasLrc) "已生成 (.lrc)" else "未生成")
                    SettingDetailRow(label = "专辑封面状态", value = if (song.coverUrl.isNotBlank()) "已关联封面" else "默认底图")

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("本地物理文件绝对路径:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = song.localFilePath ?: "未配置本地路径",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(8.dp),
                            lineHeight = 15.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 重新内嵌按钮
                    OutlinedButton(
                        onClick = {
                            val targetSong = song
                            songForDetailsDialog = null
                            onReEmbedSong(targetSong)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, AppleRed.copy(alpha = 0.6f))
                    ) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = AppleRed, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("重新嵌入封面与歌词标签", fontSize = 12.sp, color = AppleRed, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val p = song.localFilePath ?: ""
                                if (p.isNotBlank()) {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("FilePath", p))
                                    Toast.makeText(context, "已复制完整路径到剪贴板", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, borderColor)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("复制路径", fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                val toDelete = song
                                songForDetailsDialog = null
                                singleSongToDelete = toDelete
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("删除文件", fontSize = 12.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }


    // 服务端「待确认」下载任务决策弹窗：降档 (await_confirm) / 换源 (await_source) / 同名文件 (await_exist)
    // 严格对齐 server/routes/download.js 的 confirm-*/reject-*/skip-exist/:id 接口
    if (taskPendingConfirm != null) {
        val targetTask = taskPendingConfirm!!
        val pendingStatus = targetTask.status.lowercase()
        fun runTaskAction(successMsg: String, block: suspend () -> Result<Boolean>) {
            taskPendingConfirm = null
            scope.launch {
                block()
                    .onSuccess {
                        Toast.makeText(context, successMsg, Toast.LENGTH_SHORT).show()
                        refreshServerDownloads()
                    }
                    .onFailure {
                        Toast.makeText(context, "处理失败: ${it.message}", Toast.LENGTH_SHORT).show()
                        refreshServerDownloads()
                    }
            }
        }
        val dialogTitle = when (pendingStatus) {
            "await_source" -> "音源切换确认"
            "await_exist" -> "同名文件已存在"
            else -> "音质降档确认"
        }
        val dialogDesc = when (pendingStatus) {
            "await_source" -> {
                val failed = targetTask.sourceFailedName.ifBlank { "当前音源" }
                if (targetTask.sourceAlternatives.isEmpty()) {
                    "音源「$failed」取链失败，且没有其他已激活的音源可切换。可在设置中激活更多音源后重试，或放弃该任务。"
                } else {
                    "音源「$failed」取链失败，可切换到以下已激活音源重新下载："
                }
            }
            "await_exist" -> {
                val local = targetTask.existLocalLabel.ifBlank { "已有文件" }
                val wanted = targetTask.existRequestedLabel.ifBlank { targetTask.quality.uppercase() }
                "服务器上已有同名文件（$local），本次请求下载 $wanted。请选择处理方式："
            }
            else -> {
                val from = targetTask.downgradeFromLabel.ifBlank { targetTask.quality.uppercase() }
                val to = targetTask.downgradeToLabel.ifBlank { "低一档音质" }
                "$from 多次下载失败，可改用 $to 继续下载。确认后，本批其余等待降档的任务也会自动降档。"
            }
        }
        Dialog(onDismissRequest = { taskPendingConfirm = null }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                border = BorderStroke(1.dp, AppleRed.copy(alpha = 0.25f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = AppleRed,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = dialogTitle,
                        style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${targetTask.name} - ${targetTask.singer}",
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AppleRed),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = dialogDesc,
                        style = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    when (pendingStatus) {
                        "await_source" -> {
                            // 每个备用音源一个按钮：服务端要求 sourceApiId 必须取自 alternatives
                            targetTask.sourceAlternatives.forEach { alt ->
                                Button(
                                    onClick = {
                                        runTaskAction("已切换到「${alt.name}」重新下载") {
                                            LemonMusicProtocol.confirmDownloadSource(targetTask.id, alt.id, context)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().height(42.dp)
                                ) {
                                    Text(
                                        "切换到「${alt.name}」",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            OutlinedButton(
                                onClick = {
                                    runTaskAction("已放弃换源，任务标记为失败") {
                                        LemonMusicProtocol.runDownloadTaskAction(targetTask.id, "reject-source", context = context)
                                    }
                                },
                                border = BorderStroke(1.dp, AppleRed.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(42.dp)
                            ) {
                                Text("不切换（标记为失败）", fontSize = 13.sp, color = AppleRed, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        "await_exist" -> {
                            // 服务端 confirm-exist / skip-exist 支持 applyToRest：对本批其余同名文件任务套用同一选择
                            var existApplyToRest by remember(targetTask.id) { mutableStateOf(false) }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { existApplyToRest = !existApplyToRest }
                                    .padding(vertical = 2.dp)
                            ) {
                                androidx.compose.material3.Checkbox(
                                    checked = existApplyToRest,
                                    onCheckedChange = null,
                                    colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = AppleRed)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "本批其余同名文件也按此处理",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    runTaskAction("已跳过，沿用服务器上的已有文件") {
                                        LemonMusicProtocol.runDownloadTaskAction(targetTask.id, "skip-exist", applyToRest = existApplyToRest, context = context)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(42.dp)
                            ) {
                                Text("跳过，沿用已有文件", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    runTaskAction("已确认继续下载") {
                                        LemonMusicProtocol.runDownloadTaskAction(targetTask.id, "confirm-exist", applyToRest = existApplyToRest, context = context)
                                    }
                                },
                                border = BorderStroke(1.dp, AppleRed.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(42.dp)
                            ) {
                                Text("仍然下载（与已有文件并存）", fontSize = 13.sp, color = AppleRed, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        else -> {
                            val toLabel = targetTask.downgradeToLabel.ifBlank { "低一档音质" }
                            Button(
                                onClick = {
                                    runTaskAction("已确认改用 $toLabel 下载") {
                                        LemonMusicProtocol.confirmDownloadDowngrade(targetTask.id, context)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(42.dp)
                            ) {
                                Text("✔ 改用 $toLabel 下载", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    runTaskAction("已拒绝降档，任务标记为失败") {
                                        LemonMusicProtocol.runDownloadTaskAction(targetTask.id, "reject-downgrade", context = context)
                                    }
                                },
                                border = BorderStroke(1.dp, AppleRed.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(42.dp)
                            ) {
                                Text("不降档（标记为失败）", fontSize = 13.sp, color = AppleRed, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 取消任务
                    TextButton(
                        onClick = {
                            scope.launch {
                                LemonMusicProtocol.deleteServerDownloadTask(targetTask.id, context)
                                refreshServerDownloads()
                            }
                            taskPendingConfirm = null
                        },
                        modifier = Modifier.fillMaxWidth().height(38.dp)
                    ) {
                        Text("✕ 取消并从下载队列移除", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

        if (taskPendingDelete != null) {
        val task = taskPendingDelete!!
        AlertDialog(
            onDismissRequest = { taskPendingDelete = null },
            title = {
                Text(
                    text = "确认删除服务器下载？",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "确定要从服务端删除歌曲「${task.name}」的下载记录吗？如果文件正在下载，下载进程将被立即终止。",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = task.id
                        taskPendingDelete = null
                        deleteServerTask(id)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed)
                ) {
                    Text("确认删除", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { taskPendingDelete = null }) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

@Composable
private fun SettingDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ActiveDownloadTaskRow(
    task: DownloadTask,
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onCancel: () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) AppleRed.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        border = if (isSelected) BorderStroke(1.dp, AppleRed) else null,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isMultiSelectMode) Modifier.clickable(onClick = onToggleSelect) else Modifier)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isMultiSelectMode) {
                    DmSelectIndicator(selected = isSelected)
                    Spacer(modifier = Modifier.width(10.dp))
                }

                AlbumArtworkImage(
                    model = task.song.coverUrl,
                    seedId = task.song.id,
                    targetSize = 160,
                    modifier = Modifier.size(50.dp),
                    cornerRadius = 8.dp
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.song.title,
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = task.song.artist,
                        style = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.status == DownloadStatus.PAUSED) {
                        IconButton(onClick = onResume, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "继续下载", tint = AppleRed, modifier = Modifier.size(22.dp))
                        }
                    } else {
                        IconButton(onClick = onPause, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Pause, contentDescription = "暂停下载", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                    }
                    IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "取消下载", tint = Color.Gray, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            com.lm.player.core.designsystem.component.ClassicLinearProgress(
                progress = { task.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = if (task.status == DownloadStatus.PAUSED) MaterialTheme.colorScheme.outlineVariant else AppleRed,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (task.status == DownloadStatus.PAUSED) {
                    val downloadedMb = task.bytesDownloaded.toFloat() / (1024 * 1024)
                    val totalMb = task.totalBytes.toFloat() / (1024 * 1024)
                    val progressPercent = (task.progress * 100).toInt()
                    if (task.totalBytes > 0) {
                        Text(
                            text = String.format(Locale.getDefault(), "已暂停 • %.1f MB / %.1f MB (%d%%)", downloadedMb, totalMb, progressPercent),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(text = "已暂停", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        text = "继续下载",
                        fontSize = 11.sp,
                        color = AppleRed,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { onResume() }
                    )
                } else if (task.totalBytes == 0L && task.bytesDownloaded == 0L && task.speedKbps == 0L) {
                    Text(text = "排队等待中...", fontSize = 11.sp, color = AppleRed, fontWeight = FontWeight.Medium)
                    Text(text = "等待队列", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val downloadedMb = task.bytesDownloaded.toFloat() / (1024 * 1024)
                    val totalMb = task.totalBytes.toFloat() / (1024 * 1024)
                    val progressPercent = (task.progress * 100).toInt()

                    Text(
                        text = String.format(Locale.getDefault(), "%.1f MB / %.1f MB (%d%%)", downloadedMb, totalMb, progressPercent),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (task.speedKbps > 0) {
                        Text(
                            text = if (task.speedKbps >= 1024) {
                                String.format(Locale.getDefault(), "%.1f MB/s", task.speedKbps / 1024f)
                            } else {
                                "${task.speedKbps} KB/s"
                            },
                            fontSize = 11.sp,
                            color = AppleRed,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompletedDownloadSongRow(
    song: UnifiedSong,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onShowDetails: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) AppleRed.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        border = if (isSelected) BorderStroke(1.dp, AppleRed) else null,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 多选复选框
            if (isMultiSelectMode) {
                DmSelectIndicator(selected = isSelected)
                Spacer(modifier = Modifier.width(10.dp))
            }

            AlbumArtworkImage(
                model = song.coverUrl,
                seedId = song.id,
                targetSize = 160,
                modifier = Modifier.size(48.dp),
                cornerRadius = 8.dp
            )

            Spacer(modifier = Modifier.width(12.dp))

            val (realFormatStr, realBitRate, realSizeStr) = remember(song.id, song.localFilePath, song.format, song.bitRate) {
                com.lm.player.feature.home.resolveRealLocalFormatAndSize(song)
            }
            val isLossless = realFormatStr in listOf("FLAC", "WAV", "ALAC", "APE", "DSD", "DSF") || realBitRate >= 800

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${song.artist} • ${if (song.album.isNotBlank()) song.album else "单曲"} ($realFormatStr · $realSizeStr)",
                    style = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF34C759).copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = if (isLossless && realBitRate >= 1200) "Hi-Res" else if (isLossless) "无损" else "已离线",
                            color = Color(0xFF34C759),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = com.lm.player.feature.home.formatDownloadedSongSpecs(song),
                        style = TextStyle(fontSize = 11.sp, color = Color(0xFF34C759)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!isMultiSelectMode) {
                // 查看存储详情
                IconButton(onClick = onShowDetails) {
                    Icon(Icons.Default.MoreVert, contentDescription = "存储信息与操作", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                // 单曲删除
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除已下载文件", tint = Color.Gray.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

private fun formatStorageSize(bytes: Long): String {
    if (bytes <= 0) return "0.0 MB"
    val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        String.format(Locale.getDefault(), "%.2f GB", gb)
    } else {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        String.format(Locale.getDefault(), "%.1f MB", mb)
    }
}

@Composable
private fun ServerDownloadTasksContent(
    serverDownloads: List<LemonServerDownloadTaskRecord>,
    isLoading: Boolean,
    /** 拉取失败时的错误信息；null 表示「确实没有记录」而不是「请求失败」 */
    errorMessage: String?,
    onRefresh: () -> Unit,
    onDeleteTask: (LemonServerDownloadTaskRecord) -> Unit,
    onHandleConfirm: ((LemonServerDownloadTaskRecord) -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    /** 列表顶部留白（液态玻璃顶栏的高度） */
    topPadding: Dp = 0.dp,
    /** 搜索没有结果时的提示；null = 默认「暂无服务器端下载任务」 */
    emptyText: String? = null,
    bottomPadding: Dp = 60.dp,
    isMultiSelectMode: Boolean = false,
    selectedIds: List<String> = emptyList(),
    onToggleSelect: (String) -> Unit = {}
) {
    if (isLoading && serverDownloads.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 80.dp + topPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = AppleRed, modifier = Modifier.size(36.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text("正在拉取服务器下载记录...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
        }
    } else if (serverDownloads.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 80.dp + topPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Outlined.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                // 关键：区分「服务端确实没有记录」与「请求失败」。
                // 此前两种情况都显示「暂无服务器端下载任务」，用户无法判断该去服务端查、
                // 还是该检查网络/服务端连接 —— 这正是「条目数一直不对」难以定位的原因。
                if (errorMessage != null) {
                    Text(
                        text = "获取失败：$errorMessage",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "请检查服务端连接与登录状态后重试",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                } else {
                    Text(emptyText ?: "暂无服务器端下载任务", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onRefresh,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("刷新状态", fontSize = 13.sp)
                }
            }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding)
        ) {
            items(
                items = serverDownloads,
                key = { it.id }
            ) { task ->
                ServerDownloadTaskRow(
                    task = task,
                    onDelete = { onDeleteTask(task) },
                    onHandleConfirm = onHandleConfirm,
                    isMultiSelectMode = isMultiSelectMode,
                    isSelected = task.id in selectedIds,
                    onToggleSelect = { onToggleSelect(task.id) }
                )
            }
        }
    }
}

@Composable
private fun ServerDownloadTaskRow(
    task: LemonServerDownloadTaskRecord,
    onDelete: () -> Unit,
    onHandleConfirm: ((LemonServerDownloadTaskRecord) -> Unit)? = null,
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {}
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val borderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)

    val statusText = when (task.status.lowercase()) {
        "downloading" -> "下载中 ${task.progress}%"
        "completed" -> "已完成"
        "waiting" -> "排队中"
        "error" -> if (task.error.isNotBlank()) "失败: ${task.error}" else "下载失败"
        "paused" -> "已暂停"
        "await_confirm" -> "待确认降档"
        "await_source" -> "待切换音源"
        "await_exist" -> "同名文件待处理"
        else -> task.status
    }

    val statusColor = when (task.status.lowercase()) {
        "downloading" -> AppleRed
        "completed" -> Color(0xFF34C759)
        "waiting" -> Color(0xFFFF9500)
        "error" -> Color(0xFFFF3B30)
        "paused" -> Color.Gray
        "await_confirm", "await_source", "await_exist" -> Color(0xFFFF9500)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val needsDecision = task.status.lowercase() in setOf("await_confirm", "await_source", "await_exist")

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) AppleRed.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, if (isSelected) AppleRed else borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isMultiSelectMode) Modifier.clickable(onClick = onToggleSelect) else Modifier)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isMultiSelectMode) {
                    DmSelectIndicator(selected = isSelected)
                    Spacer(modifier = Modifier.width(10.dp))
                }
                // 歌曲封面与状态角标
                Box(modifier = Modifier.size(48.dp)) {
                    AlbumArtworkImage(
                        model = task.coverUrl,
                        seedId = task.id,
                        targetSize = 160,
                        modifier = Modifier.size(48.dp),
                        cornerRadius = 8.dp
                    )
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(1.dp)
                            .size(16.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = when (task.status.lowercase()) {
                                    "completed" -> Icons.Default.CheckCircle
                                    "downloading" -> Icons.Default.Downloading
                                    "error" -> Icons.Default.ErrorOutline
                                    "waiting" -> Icons.Default.HourglassTop
                                    else -> Icons.Default.CloudDownload
                                },
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 歌曲信息
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.name,
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${task.singer}${if (task.album.isNotBlank()) " • ${task.album}" else ""}",
                        style = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 音质标签
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = AppleRed.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = task.quality.uppercase(),
                                color = AppleRed,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        // 状态标签
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = statusColor.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = statusText,
                                color = statusColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                        if (task.createdAt > 0L) {
                            Spacer(modifier = Modifier.width(8.dp))
                            val timeStr = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(task.createdAt))
                            Text(
                                text = timeStr,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                // 删除按钮（多选时隐藏，用底部操作条删除）
                if (!isMultiSelectMode) IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "删除任务",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 服务端待确认任务（降档 / 换源 / 同名文件）：展示原因与决策入口，否则任务会一直卡在队列里
            if (needsDecision) {
                if (task.error.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = task.error,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onHandleConfirm?.invoke(task) },
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when (task.status.lowercase()) {
                            "await_source" -> "选择备用音源"
                            "await_exist" -> "处理同名文件"
                            else -> "确认是否降档"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 若在下载中，展示进度条
            if (task.status.lowercase() == "downloading") {
                Spacer(modifier = Modifier.height(8.dp))
                com.lm.player.core.designsystem.component.ClassicLinearProgress(
                    progress = { (task.progress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = AppleRed,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
            }
        }
    }
}

private fun UnifiedSong.dmMatches(q: String): Boolean =
    title.contains(q, ignoreCase = true) || artist.contains(q, ignoreCase = true) || album.contains(q, ignoreCase = true)

/** 顶栏右侧的小圆钮（搜索 / 更多） */
@Composable
private fun DmIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun DmEmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, topPadding: Dp) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 72.dp + topPadding),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
        }
    }
}

private class DmBarAction(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

/**
 * 下载管理多选悬浮条：上面一行「已选 N · 补充信息」，下面一排等宽的「图标 + 文字」按钮，
 * 按钮再多也只是平分宽度，不会像以前几个长按钮挤在一行里被压扁。
 */
@Composable
private fun DmSelectionBar(
    title: String,
    subtitle: String,
    isDark: Boolean,
    borderColor: Color,
    actions: List<DmBarAction>,
    /** 画在上层玻璃里：不要自己的底色 / 阴影 / 描边 */
    glass: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = if (glass) Color.Transparent else if (isDark) Color(0xFF1E1E26) else Color.White,
        shadowElevation = if (glass) 0.dp else 10.dp,
        border = if (glass) null else BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                actions.forEach { action ->
                    val tint = when {
                        !action.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        action.danger -> MaterialTheme.colorScheme.error
                        else -> AppleRed
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = action.enabled, onClick = action.onClick)
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(action.icon, contentDescription = action.label, tint = tint, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = action.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (action.enabled) MaterialTheme.colorScheme.onSurface else tint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** 多选时的圆形选择标记（选中：红底白勾；未选：细圆圈） */
@Composable
private fun DmSelectIndicator(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .then(
                if (selected) Modifier.background(AppleRed)
                else Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f), CircleShape)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) Icon(Icons.Default.Check, contentDescription = "已选", tint = Color.White, modifier = Modifier.size(15.dp))
    }
}
