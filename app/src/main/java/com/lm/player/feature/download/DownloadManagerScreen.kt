package com.lm.player.feature.download

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.DownloadTask
import com.lm.player.core.model.UnifiedSong
import java.io.File
import java.util.Locale

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
    downloadPath: String = "",
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(1) } // 0: 正在下载, 1: 已下载完成 (默认)
    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedSongIds = remember { mutableStateListOf<String>() }

    var isMultiSelectActiveMode by remember { mutableStateOf(false) }
    val selectedActiveTaskIds = remember { mutableStateListOf<String>() }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // 1. 顶部 Header 与操作工具栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBackIosNew, contentDescription = "返回", tint = AppleRed)
                    }
                    val isAnyMultiSelect = if (selectedTab == 0) isMultiSelectActiveMode else isMultiSelectMode
                    val currentSelectCount = if (selectedTab == 0) selectedActiveTaskIds.size else selectedSongIds.size
                    Text(
                        text = if (isAnyMultiSelect) "批量管理 (${currentSelectCount})" else "下载管理",
                        style = TextStyle(
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    )
                }

                // 正在下载 Tab 批量管理操作
                if (selectedTab == 0 && activeTasks.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isMultiSelectActiveMode) {
                            TextButton(
                                onClick = {
                                    if (selectedActiveTaskIds.size == activeTasks.size) {
                                        selectedActiveTaskIds.clear()
                                    } else {
                                        selectedActiveTaskIds.clear()
                                        selectedActiveTaskIds.addAll(activeTasks.map { it.song.id })
                                    }
                                }
                            ) {
                                Text(
                                    text = if (selectedActiveTaskIds.size == activeTasks.size) "取消全选" else "全选",
                                    color = AppleRed,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Button(
                                onClick = {
                                    isMultiSelectActiveMode = false
                                    selectedActiveTaskIds.clear()
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("完成", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    isMultiSelectActiveMode = true
                                    selectedActiveTaskIds.clear()
                                },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, borderColor),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Checklist, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("批量管理", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }

                // 仅在已下载 Tab 展示批量管理操作
                if (selectedTab == 1 && completedSongs.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isMultiSelectMode) {
                            TextButton(
                                onClick = {
                                    if (selectedSongIds.size == completedSongs.size) {
                                        selectedSongIds.clear()
                                    } else {
                                        selectedSongIds.clear()
                                        selectedSongIds.addAll(completedSongs.map { it.id })
                                    }
                                }
                            ) {
                                Text(
                                    text = if (selectedSongIds.size == completedSongs.size) "取消全选" else "全选",
                                    color = AppleRed,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Button(
                                onClick = {
                                    isMultiSelectMode = false
                                    selectedSongIds.clear()
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("完成", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    isMultiSelectMode = true
                                    selectedSongIds.clear()
                                },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, borderColor),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Checklist, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("批量管理", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }

            // 2. 分段切换器 (正在下载 vs 已下载完成)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(17.dp))
                        .background(if (selectedTab == 0) MaterialTheme.colorScheme.surface else Color.Transparent)
                        .clickable {
                            selectedTab = 0
                            isMultiSelectMode = false
                            selectedSongIds.clear()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "正在下载 (${activeTasks.size})",
                        fontSize = 13.sp,
                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                        color = if (selectedTab == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(17.dp))
                        .background(if (selectedTab == 1) MaterialTheme.colorScheme.surface else Color.Transparent)
                        .clickable {
                            selectedTab = 1
                            isMultiSelectActiveMode = false
                            selectedActiveTaskIds.clear()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "已完成 (${completedSongs.size})",
                        fontSize = 13.sp,
                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                        color = if (selectedTab == 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3. 内容区域
            if (selectedTab == 0) {
                // ====== 正在下载列表 ======
                if (activeTasks.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 80.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Outlined.FileDownload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "当前没有正在下载的音乐",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 15.sp
                            )
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (!isMultiSelectActiveMode) {
                            val downloadingCount = activeTasks.count { it.status == DownloadStatus.DOWNLOADING }
                            val pausedCount = activeTasks.count { it.status == DownloadStatus.PAUSED }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = buildString {
                                        if (downloadingCount > 0) append("${downloadingCount} 首下载中")
                                        if (downloadingCount > 0 && pausedCount > 0) append("，")
                                        if (pausedCount > 0) append("${pausedCount} 首已暂停")
                                        if (downloadingCount == 0 && pausedCount == 0) append("${activeTasks.size} 个任务")
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (downloadingCount > 0) {
                                        OutlinedButton(
                                            onClick = onPauseAll,
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(28.dp),
                                            border = BorderStroke(0.8.dp, borderColor)
                                        ) {
                                            Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("全部暂停", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                                        }
                                    }
                                    if (pausedCount > 0) {
                                        Button(
                                            onClick = onResumeAll,
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(28.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed)
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("全部继续", fontSize = 11.sp, color = Color.White)
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = if (isMultiSelectActiveMode) 100.dp else 80.dp)
                        ) {
                            items(
                                items = activeTasks,
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
            } else {
                // ====== 已下载完成列表 ======
                if (completedSongs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 80.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "暂无已下载的离线音乐",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 15.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = if (isMultiSelectMode) 100.dp else 40.dp)
                    ) {
                        // 顶部存储信息看板
                        item {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                border = BorderStroke(1.dp, borderColor),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Storage, contentDescription = null, tint = AppleRed, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("本地下载存储看板", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (!isMultiSelectMode && completedSongs.isNotEmpty()) {
                                                OutlinedButton(
                                                    onClick = onReEmbedAll,
                                                    shape = RoundedCornerShape(10.dp),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(26.dp),
                                                    border = BorderStroke(0.8.dp, borderColor)
                                                ) {
                                                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(12.dp), tint = AppleRed)
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("补全全部标签", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                                                }
                                            }
                                            Text(
                                                text = if (isMultiSelectMode) "多选编辑中" else "正常模式",
                                                fontSize = 11.sp,
                                                color = if (isMultiSelectMode) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = "${completedSongs.size} 首",
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text("已下载曲目", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }

                                        Box(
                                            modifier = Modifier
                                                .width(1.dp)
                                                .height(30.dp)
                                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                        )

                                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = formatStorageSize(totalSizeBytes),
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = AppleRed
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text("音频总占用", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }

                                        Box(
                                            modifier = Modifier
                                                .width(1.dp)
                                                .height(30.dp)
                                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                        )

                                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = formatStorageSize(freeSizeBytes),
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text("设备剩余空间", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }

                                    if (currentPathDisplay.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "存储路径: $currentPathDisplay",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        // 已下载歌曲列表
                        if (completedSongs.isNotEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "已下载歌曲 (${completedSongs.size} 首)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Button(
                                        onClick = {
                                            completedSongs.firstOrNull()?.let { onSongClick(it, completedSongs) }
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("播放全部", fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        items(
                            items = completedSongs,
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
                                        onSongClick(song, completedSongs)
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

        // 4.1 正在下载多选模式下底部浮动批量操作条
        AnimatedVisibility(
            visible = isMultiSelectActiveMode && selectedTab == 0,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (isDark) Color(0xFF1E1E26) else Color.White,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, borderColor)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "已选 ${selectedActiveTaskIds.size} 项任务",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "共 ${activeTasks.size} 个正在进行",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val selectedTasks = activeTasks.filter { it.song.id in selectedActiveTaskIds }
                        val hasDownloading = selectedTasks.any { it.status == DownloadStatus.DOWNLOADING }
                        val hasPaused = selectedTasks.any { it.status == DownloadStatus.PAUSED }

                        if (hasDownloading) {
                            OutlinedButton(
                                onClick = {
                                    val toPause = selectedTasks.filter { it.status == DownloadStatus.DOWNLOADING }.map { it.song.id }.toSet()
                                    onPauseTasks(toPause)
                                },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                border = BorderStroke(1.dp, borderColor)
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("暂停", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }

                        if (hasPaused) {
                            Button(
                                onClick = {
                                    val toResume = selectedTasks.filter { it.status == DownloadStatus.PAUSED }.map { it.song.id }.toSet()
                                    onResumeTasks(toResume)
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("继续", fontSize = 12.sp, color = Color.White)
                            }
                        }

                        Button(
                            onClick = {
                                val toCancel = selectedActiveTaskIds.toSet()
                                onCancelTasks(toCancel)
                                selectedActiveTaskIds.clear()
                                isMultiSelectActiveMode = false
                            },
                            enabled = selectedActiveTaskIds.isNotEmpty(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("取消", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 4.2 已下载多选模式下底部浮动批量操作条
        AnimatedVisibility(
            visible = isMultiSelectMode && selectedTab == 1,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (isDark) Color(0xFF1E1E26) else Color.White,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, borderColor)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "已选 ${selectedSongIds.size} 首歌曲",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "占用空间: ${formatStorageSize(selectedTotalSizeBytes)}",
                            fontSize = 11.sp,
                            color = AppleRed
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { showBatchDeleteConfirmDialog = true },
                            enabled = selectedSongIds.isNotEmpty(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("批量删除 (${selectedSongIds.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
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
                            Text(song.artist, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(if (song.album.isNotBlank()) song.album else "单曲", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = borderColor, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    // 存储信息详情
                    val hasLrc = file?.let { File(it.parentFile, "${it.nameWithoutExtension}.lrc").exists() } ?: false
                    SettingDetailRow(label = "物理文件大小", value = fileSizeFormatted)
                    SettingDetailRow(label = "音频解码格式", value = song.format.uppercase())
                    SettingDetailRow(label = "音频规格码率", value = "${song.bitRate} kbps")
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
            .clickable(enabled = isMultiSelectMode, onClick = onToggleSelect)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isMultiSelectMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        colors = CheckboxDefaults.colors(checkedColor = AppleRed),
                        modifier = Modifier.size(24.dp)
                    )
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

            LinearProgressIndicator(
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
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(checkedColor = AppleRed),
                    modifier = Modifier.size(24.dp)
                )
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

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${song.artist} • ${if (song.album.isNotBlank()) song.album else "单曲"}",
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
                            text = if (song.format.uppercase() in listOf("FLAC", "WAV", "ALAC", "APE")) "Hi-Res" else "已离线",
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
