package com.lm.player.feature.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.designsystem.theme.*
import com.lm.player.core.media.LocalMediaScanner
import com.lm.player.core.media.Media3Factory
import com.lm.player.core.media.SongMatchingResolver
import com.lm.player.core.model.DownloadSettings
import com.lm.player.core.model.HomeScreenDisplayConfig
import com.lm.player.core.model.OnlineMusicSource
import com.lm.player.core.model.ServerConfig
import com.lm.player.core.model.ServerType
import com.lm.player.core.model.SyncMode
import com.lm.player.core.network.LemonMusicProtocol
import com.lm.player.core.network.NetworkClientFactory
import com.lm.player.core.update.AppUpdateManager
import com.lm.player.core.update.UpdateInfo
import kotlinx.coroutines.launch
import java.io.File

/**
 * 现代高保真设置中心 (精细化分类折叠面板与全模块归类设计)
 */
@Composable
fun SettingsScreen(
    servers: List<ServerConfig>,
    activeServerId: String?,
    homeDisplayConfig: HomeScreenDisplayConfig,
    downloadSettings: DownloadSettings,
    themeMode: AppThemeMode,
    currentScaleMode: UiScaleMode,
    blurAlpha: Float,
    enableBottomBarAnimation: Boolean,
    autoPlayOnStartup: Boolean = true,
    autoFallbackToLocal: Boolean = true,
    currentOnlineSource: OnlineMusicSource = OnlineMusicSource.KUWO,
    onOnlineSourceChange: (OnlineMusicSource) -> Unit = {},
    onSelectServer: (ServerConfig) -> Unit,
    onAddOrUpdateServer: (ServerConfig) -> Unit,
    onDeleteServer: (String) -> Unit,
    onHomeDisplayConfigChange: (HomeScreenDisplayConfig) -> Unit,
    onDownloadSettingsChange: (DownloadSettings) -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onScaleModeChange: (UiScaleMode) -> Unit,
    onBlurAlphaChange: (Float) -> Unit,
    onEnableBottomBarAnimationChange: (Boolean) -> Unit,
    onAutoPlayOnStartupChange: (Boolean) -> Unit = {},
    onAutoFallbackToLocalChange: (Boolean) -> Unit = {},
    onChooseDownloadDirectory: () -> Unit = {},
    onImportCustomFolder: () -> Unit = {},
    onLocalScanCompleted: () -> Unit = {},
    onOpenDownloads: () -> Unit,
    onExitAppCompletely: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val database = remember { ZdsDatabase.getInstance(context) }

    var showAddServerDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<ServerConfig?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var cacheSizeBytes by remember { mutableStateOf(Media3Factory.getCacheSizeBytes(context)) }
    var showOnlineSourceDialog by remember { mutableStateOf(false) }

    // 本地扫描二级折叠工作台状态
    var expandLocalScanner by remember { mutableStateOf(false) }
    var isScanningLocal by remember { mutableStateOf(false) }
    var scannedFolderMap by remember { mutableStateOf<Map<String, List<com.lm.player.core.database.entity.SongEntity>>>(emptyMap()) }
    var selectedFolderPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedFolderPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showScanFolderPickerDialog by remember { mutableStateOf(false) }

    // 关于与更新弹窗状态
    var showAboutDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showFolderPickerDialog by remember { mutableStateOf(false) }
    var updateInfoState by remember { mutableStateOf<UpdateInfo?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var isDownloadingApk by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadedApkFile by remember { mutableStateOf<File?>(null) }

    // 播放主题状态
    val lyricsPrefs = remember { context.getSharedPreferences("zds_lyrics_prefs", Context.MODE_PRIVATE) }
    var playerThemeStyle by remember {
        mutableStateOf(
            PlayerThemeStyle.fromId(lyricsPrefs.getString("player_theme_style", PlayerThemeStyle.MODERN.id) ?: PlayerThemeStyle.MODERN.id)
        )
    }

    var preferOfflineFirst by remember { mutableStateOf(true) }
    var enableAudioDucking by remember { mutableStateOf(true) }
    var pauseOnUnplug by remember { mutableStateOf(true) }

    // 6 大分类卡片折叠/展开状态 (默认前两项展开，其余收敛，界面清爽整洁)
    var expandMedia by remember { mutableStateOf(true) }
    var expandTheme by remember { mutableStateOf(true) }
    var expandPlayback by remember { mutableStateOf(false) }
    var expandScale by remember { mutableStateOf(false) }
    var expandStorage by remember { mutableStateOf(false) }
    var expandAbout by remember { mutableStateOf(false) }

    val isAllExpanded = expandMedia && expandTheme && expandPlayback && expandScale && expandStorage && expandAbout

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 120.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. 顶部 Header 与展开/折叠快捷按键
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 10.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "设置",
                        style = TextStyle(
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    )
                    Text(
                        text = "系统与车载视听参数配置",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val target = !isAllExpanded
                            expandMedia = target
                            expandTheme = target
                            expandPlayback = target
                            expandScale = target
                            expandStorage = target
                            expandAbout = target
                        }
                    ) {
                        Text(if (isAllExpanded) "收起全部" else "展开全部", fontSize = 12.sp, color = AppleRed)
                    }

                    IconButton(onClick = onOpenDownloads) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = "下载管理", tint = AppleRed)
                    }
                }
            }
        }

        // =========================================================================
        // 分类 1: ☁️ 媒体库与 NAS 服务器
        // =========================================================================
        item {
            val serverCount = servers.size
            val mediaSummary = if (serverCount > 0) "已连接 $serverCount 个服务器 • 本地音乐库深度索引" else "未添加服务器 • 本地音频全盘扫描"
            val effectiveDownloadDir = remember(downloadSettings.customDownloadPath) {
                if (downloadSettings.customDownloadPath.isNotBlank()) File(downloadSettings.customDownloadPath) else context.getExternalFilesDir("MusicDownloads") ?: File("/storage/emulated/0/Music")
            }

            SettingsCollapsibleCard(
                badgeColor = Color(0xFF34C759),
                icon = Icons.Default.CloudQueue,
                title = "媒体库与 NAS 服务器",
                summary = mediaSummary,
                isExpanded = expandMedia,
                onToggleExpand = { expandMedia = !expandMedia }
            ) {
                // 1. 本地曲库与深度扫描 (二级折叠工作台)
                SettingsSubSectionHeader(title = "本地音乐库管理与扫描")

                val totalDiscoveredSongs = remember(scannedFolderMap) { scannedFolderMap.values.sumOf { it.size } }
                val selectedSongsCount = remember(scannedFolderMap, selectedFolderPaths) {
                    scannedFolderMap.filterKeys { selectedFolderPaths.contains(it) }.values.sumOf { it.size }
                }

                val scannerSummary = when {
                    isScanningLocal -> "正在深度检索设备内部与存储卡音频文件..."
                    scannedFolderMap.isNotEmpty() -> "已检索到 $totalDiscoveredSongs 首歌曲，分布于 ${scannedFolderMap.size} 个文件夹"
                    else -> "自动发现设备内 MP3 / FLAC / WAV 等离线音频，支持按文件夹预览与勾选"
                }

                SettingsActionRow(
                    badgeColor = Color(0xFFFA2D48),
                    icon = if (isScanningLocal) Icons.Default.HourglassTop else Icons.Default.FolderOpen,
                    title = "一键全盘扫描本地歌曲",
                    subtitle = scannerSummary,
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isScanningLocal) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AppleRed)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Icon(
                                imageVector = if (expandLocalScanner) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (expandLocalScanner) "收起扫描工作台" else "展开扫描工作台",
                                tint = AppleRed,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    },
                    onClick = {
                        expandLocalScanner = !expandLocalScanner
                        if (expandLocalScanner && scannedFolderMap.isEmpty() && !isScanningLocal) {
                            isScanningLocal = true
                            coroutineScope.launch {
                                val res = LocalMediaScanner.discoverLocalAudioFilesGrouped(context)
                                scannedFolderMap = res
                                selectedFolderPaths = res.keys.toSet()
                                isScanningLocal = false
                            }
                        }
                    }
                )

                // 二级折叠展开区域 (扫描工作台)
                AnimatedVisibility(
                    visible = expandLocalScanner,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 顶部快捷动作栏
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (!isScanningLocal) {
                                            isScanningLocal = true
                                            coroutineScope.launch {
                                                val res = LocalMediaScanner.discoverLocalAudioFilesGrouped(context)
                                                scannedFolderMap = res
                                                selectedFolderPaths = res.keys.toSet()
                                                isScanningLocal = false
                                                Toast.makeText(context, "全盘扫描完成！发现 ${res.values.sumOf { it.size }} 首歌曲", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    enabled = !isScanningLocal,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("全盘重新检索", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = { showScanFolderPickerDialog = true },
                                    enabled = !isScanningLocal,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                                    border = BorderStroke(1.dp, AppleRed.copy(alpha = 0.5f)),
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = AppleRed, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("选择指定文件夹", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }

                            // 扫描状态提示
                            if (isScanningLocal) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(color = AppleRed, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("正在深度遍历存储目录并提取音轨元数据...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else if (scannedFolderMap.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("暂未检索到音频文件，可点击上方「选择指定文件夹」手动选取", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                // 统计与多选/批量导入操作栏
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val isAllSelected = selectedFolderPaths.size == scannedFolderMap.size
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable {
                                            selectedFolderPaths = if (isAllSelected) emptySet() else scannedFolderMap.keys.toSet()
                                        }
                                    ) {
                                        Checkbox(
                                            checked = isAllSelected,
                                            onCheckedChange = { checked ->
                                                selectedFolderPaths = if (checked) scannedFolderMap.keys.toSet() else emptySet()
                                            },
                                            colors = CheckboxDefaults.colors(checkedColor = AppleRed),
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "全选 (${selectedFolderPaths.size}/${scannedFolderMap.size})",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (selectedFolderPaths.isNotEmpty()) {
                                            FilledTonalButton(
                                                onClick = {
                                                    val songsToSave = scannedFolderMap.filterKeys { selectedFolderPaths.contains(it) }.values.flatten().distinctBy { it.localFilePath ?: it.id }
                                                    coroutineScope.launch {
                                                        val saved = LocalMediaScanner.saveScannedSongsToDatabase(database, songsToSave, effectiveDownloadDir)
                                                        Toast.makeText(context, "成功导入 $saved 首本地歌曲！", Toast.LENGTH_LONG).show()
                                                        onLocalScanCompleted()
                                                    }
                                                },
                                                shape = RoundedCornerShape(8.dp),
                                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = AppleRed.copy(alpha = 0.15f), contentColor = AppleRed),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier.height(32.dp)
                                            ) {
                                                Text("导入已选 ($selectedSongsCount 首)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        Button(
                                            onClick = {
                                                val allSongsToSave = scannedFolderMap.values.flatten().distinctBy { it.localFilePath ?: it.id }
                                                coroutineScope.launch {
                                                    val saved = LocalMediaScanner.saveScannedSongsToDatabase(database, allSongsToSave, effectiveDownloadDir)
                                                    Toast.makeText(context, "成功全量导入 $saved 首本地歌曲！", Toast.LENGTH_LONG).show()
                                                    onLocalScanCompleted()
                                                }
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("一键添加全部 ($totalDiscoveredSongs 首)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                                // 文件夹及歌曲列表 (折叠面板)
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for ((folderPath, songs) in scannedFolderMap) {
                                        val isChecked = selectedFolderPaths.contains(folderPath)
                                        val isExpanded = expandedFolderPaths.contains(folderPath)
                                        val folderFile = File(folderPath)
                                        val folderName = folderFile.name.ifBlank { folderPath }

                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            border = BorderStroke(0.5.dp, if (isChecked) AppleRed.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            expandedFolderPaths = if (isExpanded) expandedFolderPaths - folderPath else expandedFolderPaths + folderPath
                                                        }
                                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Checkbox(
                                                        checked = isChecked,
                                                        onCheckedChange = { checked ->
                                                            selectedFolderPaths = if (checked) selectedFolderPaths + folderPath else selectedFolderPaths - folderPath
                                                        },
                                                        colors = CheckboxDefaults.colors(checkedColor = AppleRed),
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Icon(
                                                        imageVector = Icons.Default.Folder,
                                                        contentDescription = null,
                                                        tint = if (isChecked) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(22.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = folderName,
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Text(
                                                            text = folderPath,
                                                            fontSize = 10.5.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = AppleRed.copy(alpha = 0.12f)
                                                    ) {
                                                        Text(
                                                            text = "${songs.size} 首",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = AppleRed,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Icon(
                                                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }

                                                // 展开展示歌曲列表
                                                if (isExpanded) {
                                                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        songs.forEachIndexed { idx, s ->
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Text(
                                                                    text = "${idx + 1}.",
                                                                    fontSize = 11.sp,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                    modifier = Modifier.width(22.dp)
                                                                )
                                                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = AppleRed.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                                                                Spacer(modifier = Modifier.width(6.dp))
                                                                Text(
                                                                    text = s.title,
                                                                    fontSize = 12.sp,
                                                                    fontWeight = FontWeight.Medium,
                                                                    color = MaterialTheme.colorScheme.onSurface,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                    modifier = Modifier.weight(1f)
                                                                )
                                                                Spacer(modifier = Modifier.width(6.dp))
                                                                Text(
                                                                    text = s.artist,
                                                                    fontSize = 11.sp,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                                Spacer(modifier = Modifier.width(6.dp))
                                                                Surface(
                                                                    shape = RoundedCornerShape(3.dp),
                                                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                                                ) {
                                                                    Text(
                                                                        text = s.format.uppercase(),
                                                                        fontSize = 9.sp,
                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
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
                            }
                        }
                    }
                }

                SettingsDivider()

                // 2. 已添加的 NAS 服务器列表
                SettingsSubSectionHeader(title = "已连接的 NAS 媒体服务器")
                if (servers.isNotEmpty()) {
                    servers.forEach { server ->
                        val isCurrent = server.id == activeServerId || server.isCurrentActive
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectServer(server) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SettingsIconBadge(
                                badgeColor = Color(0xFFF59E0B),
                                icon = Icons.Default.MusicNote
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(server.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    if (isCurrent) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = AppleRed.copy(alpha = 0.15f)
                                        ) {
                                            Text("当前连接", color = AppleRed, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "柠檬音乐 • ${server.serverUrl}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        editingServer = server
                                        showAddServerDialog = true
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "编辑服务器",
                                        tint = AppleRed,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }

                                IconButton(onClick = { onDeleteServer(server.id) }) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }
                        SettingsDivider()
                    }
                }

                // 3. 添加柠檬音乐服务器
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            editingServer = null
                            showAddServerDialog = true
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsIconBadge(badgeColor = AppleRed.copy(alpha = 0.15f), icon = Icons.Default.Add, iconTint = AppleRed)
                    Spacer(modifier = Modifier.width(14.dp))
                    Text("连接柠檬音乐服务端 (Lemon Music)", color = AppleRed, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }

                SettingsDivider()

                // 4. 在线模式音源与服务配置
                SettingsSubSectionHeader(title = "在线模式与音源偏好")
                SettingsActionRow(
                    badgeColor = Color(0xFFF59E0B),
                    icon = Icons.Default.Audiotrack,
                    title = "在线模式默认操作音源",
                    subtitle = "当前音源: ${currentOnlineSource.displayName} (${currentOnlineSource.key})，点击可自由切换",
                    trailingContent = {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = AppleRed.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, AppleRed.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = currentOnlineSource.displayName,
                                color = AppleRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    },
                    onClick = {
                        showOnlineSourceDialog = true
                    }
                )
            }
        }

        // =========================================================================
        // 分类 2: 🎨 界面与播放器主题
        // =========================================================================
        item {
            val themeSummary = "播放风格: ${playerThemeStyle.displayName} • 外观: ${when(themeMode) { AppThemeMode.DARK -> "深色"; AppThemeMode.LIGHT -> "浅色"; else -> "跟随系统" }}"
            SettingsCollapsibleCard(
                badgeColor = Color(0xFFFA2D48),
                icon = Icons.Default.Palette,
                title = "界面与播放器主题",
                summary = themeSummary,
                isExpanded = expandTheme,
                onToggleExpand = { expandTheme = !expandTheme }
            ) {
                // 1. 播放界面主题风格 (现代极简 vs 怀旧专辑 iPod Cover Flow)
                SettingsSubSectionHeader(title = "播放界面与主题风格")
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsIconBadge(badgeColor = AppleRed, icon = Icons.Default.Album)
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text("播放界面主题风格", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("横竖屏定制：现代极简分屏 vs 经典 iPod 3D 专辑流", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PlayerThemeStyle.entries.forEach { style ->
                            val isSelected = playerThemeStyle == style
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) AppleRed.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                border = if (isSelected) BorderStroke(1.5.dp, AppleRed) else BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        playerThemeStyle = style
                                        lyricsPrefs.edit().putString("player_theme_style", style.id).apply()
                                    }
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (style == PlayerThemeStyle.IPOD_RETRO) Icons.Default.Album else Icons.Default.MusicNote,
                                            contentDescription = null,
                                            tint = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = style.displayName,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = style.description,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        lineHeight = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }

                SettingsDivider()

                // 2. 应用外观主题 (深色 / 浅色 / 跟随系统)
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsIconBadge(
                            badgeColor = Color(0xFFFF9500),
                            icon = if (themeMode == AppThemeMode.LIGHT) Icons.Default.LightMode else Icons.Default.DarkMode
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text("应用界面外观", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("播放器与全屏背景全局联动，高对比度防眩光", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf(
                                AppThemeMode.DARK to "纯黑深色",
                                AppThemeMode.LIGHT to "纯白浅色",
                                AppThemeMode.FOLLOW_SYSTEM to "跟随系统"
                            ).forEach { (mode, label) ->
                                val isSelected = themeMode == mode
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) AppleRed else Color.Transparent,
                                    shadowElevation = if (isSelected) 2.dp else 0.dp,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onThemeModeChange(mode) }
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                SettingsDivider()

                // 3. 首页展示内容定制
                SettingsSubSectionHeader(title = "首页内容展示定制")
                SettingsSwitchItem(
                    badgeColor = Color(0xFFFF2D55),
                    icon = Icons.Default.History,
                    title = "最近播放 (Recently Played)",
                    subtitle = "在首页展示最近播放过的曲目",
                    checked = homeDisplayConfig.showRecentlyPlayed,
                    onCheckedChange = { onHomeDisplayConfigChange(homeDisplayConfig.copy(showRecentlyPlayed = it)) }
                )
                SettingsDivider()
                SettingsSwitchItem(
                    badgeColor = Color(0xFFFF9500),
                    icon = Icons.Default.NewReleases,
                    title = "最近添加 (Recently Added)",
                    subtitle = "在首页展示最新入库的音轨与专辑",
                    checked = homeDisplayConfig.showRecentlyAdded,
                    onCheckedChange = { onHomeDisplayConfigChange(homeDisplayConfig.copy(showRecentlyAdded = it)) }
                )
                SettingsDivider()
                SettingsSwitchItem(
                    badgeColor = Color(0xFF34C759),
                    icon = Icons.Default.Album,
                    title = "专辑列表 (Albums)",
                    subtitle = "在首页展示热门专辑大图横向展台",
                    checked = homeDisplayConfig.showAlbums,
                    onCheckedChange = { onHomeDisplayConfigChange(homeDisplayConfig.copy(showAlbums = it)) }
                )
                SettingsDivider()
                SettingsSwitchItem(
                    badgeColor = Color(0xFF5856D6),
                    icon = Icons.Default.Mic,
                    title = "歌手列表 (Artists)",
                    subtitle = "在首页展示推荐歌手圆形头像展台",
                    checked = homeDisplayConfig.showArtists,
                    onCheckedChange = { onHomeDisplayConfigChange(homeDisplayConfig.copy(showArtists = it)) }
                )

                SettingsDivider()

                // 4. 毛玻璃透明度与底栏动效
                SettingsSubSectionHeader(title = "底栏视觉与动效控制")
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SettingsIconBadge(badgeColor = Color(0xFF32ADE6), icon = Icons.Default.BlurOn)
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text("底栏毛玻璃透明效果", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("调节悬浮播放栏与导航底栏的磨砂透光度", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text("${(blurAlpha * 100).toInt()}%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppleRed)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = blurAlpha,
                        onValueChange = onBlurAlphaChange,
                        valueRange = 0.40f..0.98f,
                        colors = SliderDefaults.colors(thumbColor = AppleRed, activeTrackColor = AppleRed)
                    )
                }

                SettingsDivider()

                SettingsSwitchItem(
                    badgeColor = Color(0xFFAF52DE),
                    icon = Icons.Default.Animation,
                    title = "底栏线性滑动缩放动效",
                    subtitle = "滑动歌曲列表时平滑收缩，点按展开；关闭后常显完整底栏",
                    checked = enableBottomBarAnimation,
                    onCheckedChange = onEnableBottomBarAnimationChange
                )
            }
        }

        // =========================================================================
        // 分类 3: 🎵 播放与车载音频控制
        // =========================================================================
        item {
            SettingsCollapsibleCard(
                badgeColor = Color(0xFF007AFF),
                icon = Icons.Default.PlayCircleFilled,
                title = "播放与车载音频控制",
                summary = "开机自启 • 弱网容灾切本地 • 导航压音 • 蓝牙断开暂停",
                isExpanded = expandPlayback,
                onToggleExpand = { expandPlayback = !expandPlayback }
            ) {
                SettingsSubSectionHeader(title = "智能启播与离线调度")
                SettingsSwitchItem(
                    badgeColor = Color(0xFFFA2D48),
                    icon = Icons.Default.PlayCircleFilled,
                    title = "启动应用时自动继续播放",
                    subtitle = "打开应用时优先自动起播上次关闭界面时的歌曲并恢复进度",
                    checked = autoPlayOnStartup,
                    onCheckedChange = onAutoPlayOnStartupChange
                )
                SettingsDivider()
                SettingsSwitchItem(
                    badgeColor = Color(0xFF32ADE6),
                    icon = Icons.Default.SyncProblem,
                    title = "在线歌曲缓冲失败自动切本地",
                    subtitle = "当检测到服务器断开或在线音源无法缓冲时，自动无缝切换至本地已下载歌曲",
                    checked = autoFallbackToLocal,
                    onCheckedChange = onAutoFallbackToLocalChange
                )
                SettingsDivider()
                SettingsSwitchItem(
                    badgeColor = Color(0xFF34C759),
                    icon = Icons.Default.FileDownloadDone,
                    title = "离线优先无缝起播",
                    subtitle = "若本地存在已下载文件，优先使用本地存储播放以节约流量",
                    checked = preferOfflineFirst,
                    onCheckedChange = { preferOfflineFirst = it }
                )
                SettingsDivider()
                SettingsSubSectionHeader(title = "车机音频焦点与输出响应")
                SettingsSwitchItem(
                    badgeColor = Color(0xFFFF9500),
                    icon = Icons.Default.DirectionsCar,
                    title = "车机音频焦点与导航混音压音",
                    subtitle = "导航提示音播报时自动降低音乐音量 (Audio Ducking)",
                    checked = enableAudioDucking,
                    onCheckedChange = { enableAudioDucking = it }
                )
                SettingsDivider()
                SettingsSwitchItem(
                    badgeColor = Color(0xFF5856D6),
                    icon = Icons.Default.Headphones,
                    title = "耳机拔出/蓝牙断开自动暂停",
                    subtitle = "设备断开音频输出时自动停止播放",
                    checked = pauseOnUnplug,
                    onCheckedChange = { pauseOnUnplug = it }
                )
            }
        }

        // =========================================================================
        // 分类 4: 📐 屏幕分辨率与触控缩放
        // =========================================================================
        item {
            val scaleLabel = when (currentScaleMode) {
                UiScaleMode.AUTO -> "智能自适应"
                UiScaleMode.STANDARD_PHONE -> "标准手机"
                UiScaleMode.CAR_LARGE -> "车机大号"
                UiScaleMode.CAR_EXTRA_LARGE -> "车机超大"
            }
            SettingsCollapsibleCard(
                badgeColor = Color(0xFF32ADE6),
                icon = Icons.Default.AspectRatio,
                title = "屏幕分辨率与触控缩放",
                summary = "当前模式: $scaleLabel",
                isExpanded = expandScale,
                onToggleExpand = { expandScale = !expandScale }
            ) {
                SettingsSubSectionHeader(title = "车载大屏触控与分辨率适配")
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("触控模式与字体大小", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("针对车机大屏与各类设备分辨率自适应优化", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ScaleOptionChip("智能自适应", currentScaleMode == UiScaleMode.AUTO) { onScaleModeChange(UiScaleMode.AUTO) }
                        ScaleOptionChip("标准手机", currentScaleMode == UiScaleMode.STANDARD_PHONE) { onScaleModeChange(UiScaleMode.STANDARD_PHONE) }
                        ScaleOptionChip("车机大号", currentScaleMode == UiScaleMode.CAR_LARGE) { onScaleModeChange(UiScaleMode.CAR_LARGE) }
                        ScaleOptionChip("车机超大", currentScaleMode == UiScaleMode.CAR_EXTRA_LARGE) { onScaleModeChange(UiScaleMode.CAR_EXTRA_LARGE) }
                    }
                }
            }
        }

        // =========================================================================
        // 分类 5: 📥 离线缓存与存储设置
        // =========================================================================
        item {
            SettingsCollapsibleCard(
                badgeColor = Color(0xFF5856D6),
                icon = Icons.Default.FolderSpecial,
                title = "离线缓存与存储设置",
                summary = "并发数: ${downloadSettings.maxConcurrent}个 • 自定义存储目录 • 临时缓存清理",
                isExpanded = expandStorage,
                onToggleExpand = { expandStorage = !expandStorage }
            ) {
                // 1. 同时下载缓存并发数量设置 (选项：1, 3, 5, 7, 10，默认 3)
                SettingsSubSectionHeader(title = "下载队列与并发控制")
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SettingsIconBadge(badgeColor = Color(0xFFFF9500), icon = Icons.Default.Download)
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = "下载并发缓存数量",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "同时下载的最大任务数，超出部分自动排队下载",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            text = "${downloadSettings.maxConcurrent} 个任务",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppleRed
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    val concurrentOptions = listOf(1, 3, 5, 7, 10)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        concurrentOptions.forEach { count ->
                            val isSelected = downloadSettings.maxConcurrent == count
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) AppleRed else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = if (isSelected) null else BorderStroke(0.6.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        onDownloadSettingsChange(downloadSettings.copy(maxConcurrent = count))
                                    }
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${count}个",
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                SettingsDivider()

                // 基于仿资料库本地目录选择器选择文件夹存储
                SettingsSubSectionHeader(title = "下载存储路径与元数据")
                SettingsActionRow(
                    badgeColor = Color(0xFFFF2D55),
                    icon = Icons.Default.Folder,
                    title = "离线歌曲下载存储目录",
                    subtitle = if (downloadSettings.customDownloadPath.isNotBlank()) downloadSettings.customDownloadPath else "应用默认内部存储目录 (Android/data/.../music)",
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("更改目录", color = AppleRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AppleRed, modifier = Modifier.size(18.dp))
                        }
                    },
                    onClick = { showFolderPickerDialog = true }
                )

                SettingsDivider()

                SettingsSwitchItem(
                    badgeColor = Color(0xFF007AFF),
                    icon = Icons.Default.Wifi,
                    title = "仅在 Wi-Fi 下下载",
                    subtitle = "连接移动蜂窝数据时暂停下载任务",
                    checked = downloadSettings.wifiOnly,
                    onCheckedChange = { onDownloadSettingsChange(downloadSettings.copy(wifiOnly = it)) }
                )
                SettingsDivider()
                SettingsSwitchItem(
                    badgeColor = Color(0xFF5856D6),
                    icon = Icons.Default.Tag,
                    title = "下载时自动写入封面与 ID3 标签",
                    subtitle = "保证离线文件在其他播放器中也可正确显示歌名与封面",
                    checked = downloadSettings.autoTagging,
                    onCheckedChange = { onDownloadSettingsChange(downloadSettings.copy(autoTagging = it)) }
                )

                SettingsDivider()

                // 流媒体临时缓存清理
                SettingsSubSectionHeader(title = "临时流媒体播放缓存")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            Media3Factory.clearStreamCache(context)
                            cacheSizeBytes = Media3Factory.getCacheSizeBytes(context)
                            Toast.makeText(context, "流媒体播放缓存已清理", Toast.LENGTH_SHORT).show()
                        }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsIconBadge(badgeColor = Color(0xFFFF3B30), icon = Icons.Default.CleaningServices)
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text("流媒体临时播放缓存", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("已缓存: ${(cacheSizeBytes / (1024 * 1024))} MB", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("立即清理", color = AppleRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // =========================================================================
        // 分类 6: ℹ️ 关于程序与系统
        // =========================================================================
        item {
            SettingsCollapsibleCard(
                badgeColor = Color(0xFFFF9500),
                icon = Icons.Default.Info,
                title = "关于程序与系统",
                summary = "版本 v${AppUpdateManager.CURRENT_VERSION_NAME} • 检查更新 • 彻底退出程序",
                isExpanded = expandAbout,
                onToggleExpand = { expandAbout = !expandAbout }
            ) {
                // 1. 关于程序与开发者
                SettingsSubSectionHeader(title = "开发者与应用信息")
                SettingsActionRow(
                    badgeColor = Color(0xFF007AFF),
                    icon = Icons.Default.Info,
                    title = "关于程序与开发者",
                    subtitle = "LMPlayer v${AppUpdateManager.CURRENT_VERSION_NAME} • 作者: Zhou • 邮箱: 1390999045@qq.com",
                    trailingContent = {
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    },
                    onClick = { showAboutDialog = true }
                )

                SettingsDivider()

                // 2. 检查新版本更新 (原生直连 GitHub Releases)
                SettingsSubSectionHeader(title = "云端更新与维护")
                SettingsActionRow(
                    badgeColor = Color(0xFF34C759),
                    icon = if (isCheckingUpdate) Icons.Default.HourglassTop else Icons.Default.SystemUpdate,
                    title = "检查新版本更新",
                    subtitle = if (isCheckingUpdate) "正在请求云端最新版本信息..." else "在线检测升级并支持一键下载覆盖安装",
                    trailingContent = {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AppleRed)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("检查更新", color = AppleRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AppleRed, modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    onClick = {
                        if (!isCheckingUpdate) {
                            isCheckingUpdate = true
                            coroutineScope.launch {
                                val result = AppUpdateManager.checkForUpdates(context)
                                isCheckingUpdate = false
                                result.onSuccess { updateInfo ->
                                    updateInfoState = updateInfo
                                    showUpdateDialog = true
                                }.onFailure { error ->
                                    Toast.makeText(context, "检查更新失败: ${error.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                )

                SettingsDivider()

                // 3. 彻底退出程序
                SettingsSubSectionHeader(title = "系统资源管理")
                SettingsActionRow(
                    badgeColor = Color(0xFFFF3B30),
                    icon = Icons.Default.PowerSettingsNew,
                    title = "彻底退出程序",
                    subtitle = "停止所有前台音频播放服务并彻底关闭释放系统内存",
                    trailingContent = {
                        Text("退出", color = Color(0xFFFF3B30), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    },
                    onClick = {
                        Toast.makeText(context, "正在彻底关闭程序...", Toast.LENGTH_SHORT).show()
                        onExitAppCompletely()
                    }
                )
            }
        }
    }

    // 弹窗：添加/编辑服务器
    if (showAddServerDialog) {
        ServerConfigDialog(
            server = editingServer,
            onDismiss = { showAddServerDialog = false },
            onSave = { server ->
                onAddOrUpdateServer(server)
                showAddServerDialog = false
            }
        )
    }

    // 弹窗：在线模式操作音源切换
    if (showOnlineSourceDialog) {
        Dialog(onDismissRequest = { showOnlineSourceDialog = false }) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(8.dp),
                modifier = Modifier.fillMaxWidth(0.92f).padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "选择在线模式操作音源",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "切换全网音乐搜索、每日推荐歌单与官方榜单的目标音源平台",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    OnlineMusicSource.entries.forEach { src ->
                        val isSelected = src == currentOnlineSource
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) AppleRed.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = if (isSelected) BorderStroke(1.5.dp, AppleRed) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    onOnlineSourceChange(src)
                                    showOnlineSourceDialog = false
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = src.displayName,
                                        fontSize = 15.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "音源代号: ${src.key}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "当前选中",
                                        tint = AppleRed,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showOnlineSourceDialog = false }) {
                            Text("完成", color = AppleRed, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // 弹窗：关于程序
    if (showAboutDialog) {
        AboutProgramDialog(
            onDismiss = { showAboutDialog = false },
            onCheckUpdate = {
                showAboutDialog = false
                isCheckingUpdate = true
                coroutineScope.launch {
                    val result = AppUpdateManager.checkForUpdates(context)
                    isCheckingUpdate = false
                    result.onSuccess { updateInfo ->
                        updateInfoState = updateInfo
                        showUpdateDialog = true
                    }.onFailure { error ->
                        Toast.makeText(context, "检查更新失败: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    // 弹窗：新版本更新与下载安装
    if (showUpdateDialog && updateInfoState != null) {
        val info = updateInfoState!!
        AppUpdateDialog(
            updateInfo = info,
            isDownloading = isDownloadingApk,
            downloadProgress = downloadProgress,
            isDownloaded = downloadedApkFile != null && downloadedApkFile!!.exists(),
            onDismiss = { showUpdateDialog = false },
            onNeverUpdate = {
                AppUpdateManager.setSkipVersion(context, info.latestVersion)
                Toast.makeText(context, "已记录，不再提示 v${info.latestVersion} 更新", Toast.LENGTH_SHORT).show()
                showUpdateDialog = false
            },
            onStartDownload = {
                if (info.downloadUrl.isNotBlank() && !isDownloadingApk) {
                    isDownloadingApk = true
                    downloadProgress = 0f
                    coroutineScope.launch {
                        AppUpdateManager.downloadApk(
                            context = context,
                            downloadUrl = info.downloadUrl,
                            onProgress = { progress, _, _ -> downloadProgress = progress }
                        ).onSuccess { apkFile ->
                            isDownloadingApk = false
                            downloadedApkFile = apkFile
                            Toast.makeText(context, "安装包下载完成，正在调起安装...", Toast.LENGTH_SHORT).show()
                            AppUpdateManager.installApk(context, apkFile)
                        }.onFailure { err ->
                            isDownloadingApk = false
                            Toast.makeText(context, "下载安装包失败: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onInstall = {
                downloadedApkFile?.let { AppUpdateManager.installApk(context, it) }
            }
        )
    }

    // 弹窗：选择指定文件夹进行本地扫描 (复用本地文件夹选择器)
    if (showScanFolderPickerDialog) {
        LocalFolderPickerDialog(
            initialPath = "",
            onDismiss = { showScanFolderPickerDialog = false },
            onConfirm = { selectedPath ->
                showScanFolderPickerDialog = false
                if (selectedPath.isNotBlank()) {
                    isScanningLocal = true
                    expandLocalScanner = true
                    coroutineScope.launch {
                        val res = LocalMediaScanner.discoverLocalAudioFilesGrouped(context, selectedPath)
                        scannedFolderMap = scannedFolderMap + res
                        selectedFolderPaths = selectedFolderPaths + res.keys
                        isScanningLocal = false
                        Toast.makeText(context, "指定文件夹扫描完成！发现 ${res.values.sumOf { it.size }} 首歌曲", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    // 弹窗：本地存储目录选择器 (仿资料库本地文件夹交互)
    if (showFolderPickerDialog) {
        LocalFolderPickerDialog(
            initialPath = downloadSettings.customDownloadPath,
            onDismiss = { showFolderPickerDialog = false },
            onConfirm = { selectedPath ->
                onDownloadSettingsChange(downloadSettings.copy(customDownloadPath = selectedPath))
                showFolderPickerDialog = false
                if (selectedPath.isNotBlank()) {
                    val targetDir = File(selectedPath)
                    if (targetDir.exists() && targetDir.isDirectory) {
                        coroutineScope.launch {
                            Toast.makeText(context, "已设定离线目录: $selectedPath，正在深度匹配本地歌曲...", Toast.LENGTH_SHORT).show()
                            val count = LocalMediaScanner.scanCustomDirectory(context, targetDir.absolutePath, database)
                            val merged = SongMatchingResolver.autoMatchAndSyncServer(database, targetDir, null)
                            Toast.makeText(context, "离线目录扫描完成！导入 $count 首歌曲，自动匹配 $merged 首线上歌曲", Toast.LENGTH_LONG).show()
                            onLocalScanCompleted()
                        }
                    } else {
                        Toast.makeText(context, "已设定离线下载目录: $selectedPath", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "已恢复默认离线存储目录", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

/**
 * 模块内部子分类小标题栏 (清晰区分下级子设置项与父级模块)
 */
@Composable
fun SettingsSubSectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(3.dp),
            color = AppleRed,
            modifier = Modifier
                .width(3.5.dp)
                .height(13.dp)
        ) {}
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
            )
        )
    }
}

/**
 * 现代分类折叠卡片组件 (带彩色徽标、摘要提示、微反差背景头部与旋转动画箭头)
 */
@Composable
fun SettingsCollapsibleCard(
    badgeColor: Color,
    icon: ImageVector,
    title: String,
    summary: String,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val cardColor = MaterialTheme.colorScheme.surface
    val headerBgColor = if (isDark) {
        Color.White.copy(alpha = 0.04f)
    } else {
        Color.Black.copy(alpha = 0.025f)
    }
    val cardBorder = BorderStroke(0.6.dp, if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f))
    val rotationAngle by animateFloatAsState(targetValue = if (isExpanded) 180f else 0f, label = "chevron_rotate")

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cardColor,
        shadowElevation = 1.5.dp,
        border = cardBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // 卡片头部 (带微反差背景，突出模块标题层级)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (isExpanded) 0.dp else 18.dp, bottomEnd = if (isExpanded) 0.dp else 18.dp))
                    .background(headerBgColor)
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 16.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    SettingsIconBadge(badgeColor = badgeColor, icon = icon)
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = summary,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "折叠" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { rotationZ = rotationAngle }
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    SettingsDivider()
                    content()
                }
            }
        }
    }
}

@Composable
fun SettingsIconBadge(badgeColor: Color, icon: ImageVector, iconTint: Color = Color.White) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = badgeColor,
        modifier = Modifier.size(34.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun SettingsActionRow(
    badgeColor: Color,
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailingContent: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            SettingsIconBadge(badgeColor = badgeColor, icon = icon)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailingContent()
    }
}

@Composable
fun SettingsSwitchItem(
    badgeColor: Color,
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            SettingsIconBadge(badgeColor = badgeColor, icon = icon)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, lineHeight = 15.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppleRed)
        )
    }
}

@Composable
fun ScaleOptionChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) AppleRed else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    )
}

/**
 * 关于程序弹窗
 */
@Composable
fun AboutProgramDialog(
    onDismiss: () -> Unit,
    onCheckUpdate: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val primaryText = if (isDark) Color.White else Color.Black
    val secondaryText = if (isDark) Color(0xFFAAAAAE) else Color(0xFF6C6C70)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = if (isDark) Color(0xFF1E1E24) else Color.White,
            border = BorderStroke(1.dp, if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f)),
            shadowElevation = 24.dp,
            modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth(0.92f).padding(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = AppleRed.copy(alpha = 0.15f),
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = AppleRed, modifier = Modifier.size(36.dp))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text("LMPlayer", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = primaryText)
                Text("柠檬音乐车机客户端 (Lemon Music Edition)", fontSize = 12.sp, color = secondaryText)
                Spacer(modifier = Modifier.height(6.dp))
                Surface(shape = RoundedCornerShape(6.dp), color = AppleRed.copy(alpha = 0.15f)) {
                    Text("v${AppUpdateManager.CURRENT_VERSION_NAME} (Build ${AppUpdateManager.CURRENT_VERSION_CODE})", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppleRed, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))
                Surface(shape = RoundedCornerShape(12.dp), color = if (isDark) Color(0xFF282830) else Color(0xFFF2F2F7), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(AppUpdateManager.APP_DESCRIPTION, fontSize = 12.sp, color = primaryText, lineHeight = 16.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("作者：", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = primaryText)
                            Text(AppUpdateManager.AUTHOR_NAME, fontSize = 12.sp, color = secondaryText)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("联系邮箱：", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = primaryText)
                            Text(AppUpdateManager.AUTHOR_EMAIL, fontSize = 12.sp, color = secondaryText)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(12.dp)) {
                        Text("关闭", fontSize = 13.sp)
                    }
                    Button(onClick = onCheckUpdate, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = AppleRed)) {
                        Text("检查更新", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * 新版本更新弹窗 (带滑动说明区、下载进度条与稍后/永不/立即更新 3 选操作)
 */
@Composable
fun AppUpdateDialog(
    updateInfo: UpdateInfo,
    isDownloading: Boolean,
    downloadProgress: Float,
    isDownloaded: Boolean,
    onDismiss: () -> Unit,
    onNeverUpdate: () -> Unit = {},
    onStartDownload: () -> Unit,
    onInstall: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val primaryText = if (isDark) Color.White else Color.Black
    val secondaryText = if (isDark) Color(0xFFAAAAAE) else Color(0xFF6C6C70)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = if (isDark) Color(0xFF1E1E24) else Color.White,
            border = BorderStroke(1.dp, if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f)),
            shadowElevation = 24.dp,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(0.92f).padding(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF34C759).copy(alpha = 0.15f), modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = Color(0xFF34C759), modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(if (updateInfo.hasUpdate) "发现新版本！" else "已是最新版本", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = primaryText)
                        Text("当前版本: v${AppUpdateManager.CURRENT_VERSION_NAME} • 最新: v${updateInfo.latestVersion}", fontSize = 12.sp, color = secondaryText)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 滑动展示更新说明，彻底杜绝内容过长挤压遮挡按键
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isDark) Color(0xFF282830) else Color(0xFFF2F2F7),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("【更新内容说明】", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppleRed)
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 60.dp, max = 160.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = updateInfo.releaseNotes.ifBlank { "包含性能优化、歌词微调、怀旧专辑主题与稳定性提升。" },
                                fontSize = 12.sp,
                                color = primaryText,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }

                if (isDownloading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = AppleRed,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("正在下载更新包: ${(downloadProgress * 100).toInt()}%", fontSize = 11.sp, color = secondaryText, modifier = Modifier.align(Alignment.CenterHorizontally))
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 操作按钮区：支持「稍后更新」「永不更新」「立即更新 / 安装」
                if (updateInfo.hasUpdate) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier.weight(1f).height(40.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("稍后更新", fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = onNeverUpdate,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier.weight(1f).height(40.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = secondaryText)
                        ) {
                            Text("永不更新", fontSize = 11.sp)
                        }

                        Button(
                            onClick = { if (isDownloaded) onInstall() else onStartDownload() },
                            enabled = !isDownloading,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier.weight(1.2f).height(40.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed)
                        ) {
                            Text(
                                text = if (isDownloaded) "立即安装" else if (isDownloading) "下载中..." else "立即更新",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("关闭", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/**
 * 添加/编辑服务器弹窗 (支持连通性与鉴权一键实时测试)
 */
@Composable
fun ServerConfigDialog(
    server: ServerConfig?,
    onDismiss: () -> Unit,
    onSave: (ServerConfig) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var name by remember { mutableStateOf(server?.name ?: "") }
    var url by remember { mutableStateOf(server?.serverUrl ?: "") }
    var username by remember { mutableStateOf(server?.username ?: "") }
    var password by remember { mutableStateOf(server?.tokenOrApiKey ?: "") }
    val serverType = ServerType.LEMON_MUSIC

    // 连通性测试状态
    var isTesting by remember { mutableStateOf(false) }
    var testResultText by remember { mutableStateOf<String?>(null) }
    var testResultSuccess by remember { mutableStateOf<Boolean?>(null) }

    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val primaryText = if (isDark) Color.White else Color.Black

    val onTestConnection: () -> Unit = {
        val trimmedUrl = url.trim().trimEnd('/')
        if (trimmedUrl.isBlank()) {
            Toast.makeText(context, "请输入柠檬音乐服务器地址 (URL)", Toast.LENGTH_SHORT).show()
        } else {
            isTesting = true
            testResultText = null
            testResultSuccess = null
            val testConfig = ServerConfig(
                id = server?.id ?: "test_server_id",
                name = name.trim().ifBlank { "柠檬音乐" },
                type = serverType,
                serverUrl = trimmedUrl,
                username = username.trim(),
                tokenOrApiKey = password.trim(),
                saltOrSecret = "",
                syncMode = SyncMode.DIRECT,
                isCurrentActive = false
            )
            coroutineScope.launch {
                val startTime = System.currentTimeMillis()
                try {
                    val client = NetworkClientFactory.createOkHttpClient(context)
                    val protocol = LemonMusicProtocol(client, testConfig.serverUrl, testConfig.username, testConfig.tokenOrApiKey)
                    val authRes = protocol.authenticate(testConfig)
                    val latency = System.currentTimeMillis() - startTime
                    if (authRes.isSuccess) {
                        testResultSuccess = true
                        testResultText = "✓ 柠檬音乐服务端连接成功！响应延迟: ${latency}ms"
                    } else {
                        testResultSuccess = false
                        val error = authRes.exceptionOrNull()?.message ?: "鉴权失败或服务不可达"
                        testResultText = "✗ 连接失败: $error"
                    }
                } catch (e: Exception) {
                    testResultSuccess = false
                    testResultText = "✗ 连接异常: ${e.message ?: "网络超时"}"
                } finally {
                    isTesting = false
                }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = if (isDark) Color(0xFF1E1E24) else Color.White,
            border = BorderStroke(1.dp, if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f)),
            shadowElevation = 24.dp,
            modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(0.92f).padding(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (server == null) "连接柠檬音乐服务端" else "编辑柠檬音乐配置",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryText
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("服务器备注名称") },
                    placeholder = { Text("例如：我的柠檬音乐") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        testResultText = null
                    },
                    label = { Text("服务器地址 (URL)") },
                    placeholder = { Text("例如：http://192.168.1.100:7983") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        testResultText = null
                    },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        testResultText = null
                    },
                    label = { Text("登录密码 / Session Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 连通性测试结果提示条
                if (testResultText != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (testResultSuccess == true) Color(0xFF34C759).copy(alpha = 0.15f) else Color(0xFFFF3B30).copy(alpha = 0.15f),
                        border = BorderStroke(0.6.dp, if (testResultSuccess == true) Color(0xFF34C759).copy(alpha = 0.6f) else Color(0xFFFF3B30).copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (testResultSuccess == true) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = if (testResultSuccess == true) Color(0xFF34C759) else Color(0xFFFF3B30),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = testResultText ?: "",
                                fontSize = 12.sp,
                                color = if (testResultSuccess == true) (if (isDark) Color(0xFF70FF8A) else Color(0xFF1E8233)) else (if (isDark) Color(0xFFFF7A7A) else Color(0xFFD32F2F)),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // 操作按钮区 (取消 / 测试连接 / 保存)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("取消", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = onTestConnection,
                        enabled = !isTesting,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AppleRed.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppleRed)
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = AppleRed)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("测试中", fontSize = 11.sp)
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("测试", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Button(
                        onClick = {
                            if (name.isNotBlank() && url.isNotBlank()) {
                                onSave(
                                    ServerConfig(
                                        id = server?.id ?: java.util.UUID.randomUUID().toString(),
                                        name = name.trim(),
                                        type = serverType,
                                        serverUrl = url.trim().trimEnd('/'),
                                        username = username.trim(),
                                        tokenOrApiKey = password.trim(),
                                        saltOrSecret = "",
                                        syncMode = SyncMode.DIRECT,
                                        isCurrentActive = server?.isCurrentActive ?: true
                                    )
                                )
                            } else {
                                Toast.makeText(context, "请填写完整服务器备注与地址", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1.3f).height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppleRed)
                    ) {
                        Text(if (server == null) "保存添加" else "保存修改", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
