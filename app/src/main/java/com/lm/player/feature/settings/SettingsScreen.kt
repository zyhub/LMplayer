package com.lm.player.feature.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.designsystem.theme.*
import com.lm.player.core.media.LocalMediaScanner
import com.lm.player.core.media.Media3Factory
import com.lm.player.core.model.*
import com.lm.player.core.network.LemonMusicProtocol
import com.lm.player.core.network.NetworkClientFactory
import com.lm.player.core.update.AppUpdateManager
import com.lm.player.core.update.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置分类选项卡 (对标柠檬音乐 Settings.vue 架构)
 */
enum class SettingsTab(val label: String, val icon: ImageVector) {
    ACCOUNT("账号与服务", Icons.Default.CloudSync),
    SOURCES("音源与脚本", Icons.Default.GraphicEq),
    LIBRARY_PATHS("存储与路径", Icons.Default.Folder),
    DOWNLOAD("下载偏好", Icons.Default.Download),
    PLAYBACK_UI("播放与外观", Icons.Default.Palette)
}

/**
 * 现代轻奢设置中心 (全面对标柠檬音乐 Settings.vue 设计与功能)
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val dimensions = LocalAppDimensions.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val cardBg = if (isDark) Color(0xFF26262E) else Color.White
    val borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)

    // 当前选中的 Tab 栏
    var selectedTab by remember { mutableStateOf(SettingsTab.ACCOUNT) }

    // 偏好设置持久化
    val prefs = remember { context.getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE) }
    var defaultDownloadQuality by remember {
        mutableStateOf(AudioQuality.fromKey(prefs.getString("default_download_quality", "320k") ?: "320k"))
    }
    var defaultDownloadTarget by remember {
        mutableStateOf(DownloadTarget.valueOf(prefs.getString("default_download_target", DownloadTarget.LOCAL.name) ?: DownloadTarget.LOCAL.name))
    }
    var autoFallbackSource by remember {
        mutableStateOf(prefs.getBoolean("source_fallback_enabled", true))
    }
    var embedCoverMeta by remember {
        mutableStateOf(prefs.getBoolean("download_embed_cover", true))
    }
    var embedLyricMeta by remember {
        mutableStateOf(prefs.getBoolean("download_embed_lyric", true))
    }
    var downloadLrcFile by remember {
        mutableStateOf(prefs.getBoolean("download_lrc_file", true))
    }

    // 在线试听音质与试听缓存偏好设置
    var wifiStreamQuality by remember {
        mutableStateOf(AudioQuality.fromKey(prefs.getString("wifi_stream_quality", "320k") ?: "320k"))
    }
    var cellularStreamQuality by remember {
        mutableStateOf(AudioQuality.fromKey(prefs.getString("cellular_stream_quality", "128k") ?: "128k"))
    }
    var streamCacheEnabled by remember {
        mutableStateOf(prefs.getBoolean("stream_cache_enabled", true))
    }

    // 弹窗状态
    var showAddServerDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<ServerConfig?>(null) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var cacheSizeBytes by remember { mutableStateOf(Media3Factory.getCacheSizeBytes(context)) }
    var isPurgingLegacyData by remember { mutableStateOf(false) }
    var showPurgeConfirmDialog by remember { mutableStateOf(false) }

    // 软件在线更新状态 (更新源: 用户仓库 zyhub/LMplayer)
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showVersionNotesDialog by remember { mutableStateOf(false) }
    var activeUpdateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var updateDownloadProgress by remember { mutableFloatStateOf(0f) }
    var isUpdateDownloaded by remember { mutableStateOf(false) }
    var downloadedApkFile by remember { mutableStateOf<java.io.File?>(null) }

    // 本地存储使用信息统计状态
    var localSongCount by remember { mutableIntStateOf(0) }
    var localMusicSizeBytes by remember { mutableLongStateOf(0L) }
    var storageFreeSizeBytes by remember { mutableLongStateOf(0L) }
    var isCalculatingStorage by remember { mutableStateOf(false) }

    // 音源落雪脚本管理状态
    var sourceScripts by remember { mutableStateOf<List<LemonSourceScriptInfo>>(emptyList()) }
    var isLoadingScripts by remember { mutableStateOf(false) }
    var showImportScriptDialog by remember { mutableStateOf(false) }
    var importScriptUrl by remember { mutableStateOf("") }
    var importScriptCode by remember { mutableStateOf("") }
    var isImportingUrl by remember { mutableStateOf(true) }
    var scriptToDelete by remember { mutableStateOf<LemonSourceScriptInfo?>(null) }

    // 服务器保存路径管理状态
    var serverDownloadPath by remember { mutableStateOf("") }
    var serverAvailablePaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingServerPaths by remember { mutableStateOf(false) }
    var showServerPathDialog by remember { mutableStateOf(false) }
    var selectedServerPathChoice by remember { mutableStateOf("") }
    var customServerPathInput by remember { mutableStateOf("") }

    // 本地音乐路径管理状态
    val defaultLocalPaths = remember { setOf("/storage/emulated/0/Music", "/storage/emulated/0/Download") }
    var localMusicPaths by remember {
        mutableStateOf(prefs.getStringSet("local_music_scan_folders", defaultLocalPaths) ?: defaultLocalPaths)
    }
    var isScanningLocalAndServer by remember { mutableStateOf(false) }
    var showAddLocalPathDialog by remember { mutableStateOf(false) }
    var newLocalPathInput by remember { mutableStateOf("") }

    val activeServer = servers.firstOrNull { it.id == activeServerId } ?: servers.firstOrNull { it.isCurrentActive }

    // 当切换到音源脚本或存储路径 Tab 时，若连接了柠檬音乐则自动拉取
    LaunchedEffect(selectedTab, activeServer?.id, isScanningLocalAndServer, isPurgingLegacyData) {
        if (selectedTab == SettingsTab.SOURCES && activeServer?.type == ServerType.LEMON_MUSIC) {
            isLoadingScripts = true
            val client = NetworkClientFactory.createOkHttpClient(context)
            val proto = LemonMusicProtocol(client, activeServer.serverUrl, activeServer.username, activeServer.tokenOrApiKey)
            val result = proto.fetchSourceList()
            sourceScripts = result.getOrDefault(emptyList())
            isLoadingScripts = false
        }
        if (selectedTab == SettingsTab.LIBRARY_PATHS && activeServer?.type == ServerType.LEMON_MUSIC) {
            isLoadingServerPaths = true
            val client = NetworkClientFactory.createOkHttpClient(context)
            val proto = LemonMusicProtocol(client, activeServer.serverUrl, activeServer.username, activeServer.tokenOrApiKey)
            val result = proto.getServerPaths()
            if (result.isSuccess) {
                val cfg = result.getOrNull()
                if (cfg != null) {
                    serverDownloadPath = cfg.downloadPath
                    serverAvailablePaths = cfg.availablePaths
                    selectedServerPathChoice = cfg.downloadPath
                }
            }
            isLoadingServerPaths = false
        }
        if (selectedTab == SettingsTab.LIBRARY_PATHS) {
            isCalculatingStorage = true
            withContext(Dispatchers.IO) {
                val allSongs = database.songDao().getAllSongsList()
                var count = 0
                var size = 0L
                allSongs.forEach { song ->
                    if (!song.localFilePath.isNullOrBlank()) {
                        val file = java.io.File(song.localFilePath)
                        if (file.exists() && file.isFile) {
                            count++
                            size += file.length()
                        }
                    }
                }
                val dlDir = java.io.File(downloadSettings.customDownloadPath.ifBlank { context.getExternalFilesDir(null)?.absolutePath ?: "" })
                val freeBytes = try {
                    dlDir.freeSpace
                } catch (_: Exception) {
                    0L
                }
                withContext(Dispatchers.Main) {
                    localSongCount = count
                    localMusicSizeBytes = size
                    storageFreeSizeBytes = freeBytes
                    isCalculatingStorage = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 1. 顶部固定导航区 (大标题与 Tab 胶囊排)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "设置",
                style = TextStyle(
                    fontSize = 32.sp * dimensions.fontScale,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "个性化您的音乐播放与同步体验 · 对标柠檬音乐",
                style = TextStyle(
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 横向 Tab 胶囊切换排
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp)
            ) {
                items(SettingsTab.entries) { tab ->
                    val isSelected = selectedTab == tab
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) AppleRed else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) AppleRed else borderColor
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { selectedTab = tab }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = null,
                                tint = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tab.label,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 2. 主设置内容区域 (根据选中 Tab 渲染对应设置卡片)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                top = 6.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (selectedTab) {
                // ==================== 1. 账号与服务 ====================
                SettingsTab.ACCOUNT -> {
                    item {
                        SettingsCard(title = "当前连接服务", icon = Icons.Default.Dns) {
                            if (activeServer != null) {
                                SettingInfoRow(label = "服务名称", value = activeServer.name)
                                SettingInfoRow(label = "服务类型", value = activeServer.type.displayName)
                                SettingInfoRow(label = "服务器地址", value = activeServer.serverUrl)
                                SettingInfoRow(label = "登录账号", value = activeServer.username.ifBlank { "匿名/Token" })
                                SettingInfoRow(label = "鉴权状态", value = if (activeServer.tokenOrApiKey.isNotBlank()) "已授权 (Bearer/ApiKey)" else "未配置")

                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            isTestingConnection = true
                                            coroutineScope.launch {
                                                val client = NetworkClientFactory.createOkHttpClient(context)
                                                val proto = LemonMusicProtocol(client, activeServer.serverUrl, activeServer.username, activeServer.tokenOrApiKey)
                                                val res = proto.testConnection()
                                                isTestingConnection = false
                                                if (res.isSuccess) {
                                                    Toast.makeText(context, "连接成功！服务端响应正常", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "连接失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        enabled = !isTestingConnection,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (isTestingConnection) "测试中..." else "测试连通性", fontSize = 13.sp)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            editingServer = activeServer
                                            showAddServerDialog = true
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f),
                                        border = BorderStroke(1.dp, borderColor)
                                    ) {
                                        Text("编辑配置", fontSize = 13.sp)
                                    }
                                }
                            } else {
                                Text(
                                    text = "当前处于纯本地离线模式，未连接任何远程音乐服务器。",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    item {
                        SettingsCard(title = "已配置的服务列表", icon = Icons.Default.Storage) {
                            if (servers.isEmpty()) {
                                Text("暂无配置的服务器", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                servers.forEach { s ->
                                    val isCurrent = s.id == activeServerId || s.isCurrentActive
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isCurrent) AppleRed.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                        border = BorderStroke(1.dp, if (isCurrent) AppleRed else borderColor.copy(alpha = 0.4f)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { onSelectServer(s) }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(s.name, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                    if (isCurrent) {
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Surface(shape = RoundedCornerShape(4.dp), color = AppleRed) {
                                                            Text("当前使用", color = Color.White, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                                        }
                                                    }
                                                }
                                                Text(s.serverUrl, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }

                                            Row {
                                                IconButton(onClick = { editingServer = s; showAddServerDialog = true }, modifier = Modifier.size(28.dp)) {
                                                    Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(16.dp))
                                                }
                                                IconButton(onClick = { onDeleteServer(s.id) }, modifier = Modifier.size(28.dp)) {
                                                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = AppleRed, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = { editingServer = null; showAddServerDialog = true },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("添加新服务器 (飞牛/柠檬/Subsonic/Navidrome)", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                            }
                        }
                    }

                    // 3. 软件版本与在线更新 (更新源: 用户仓库 zyhub/LMplayer)
                    item {
                        SettingsCard(title = "软件版本与在线更新", icon = Icons.Default.SystemUpdate) {
                            val curVerName = AppUpdateManager.getAppVersionName(context)
                            val curVerCode = AppUpdateManager.getAppVersionCode(context)
                            SettingInfoRow(label = "当前版本", value = "v$curVerName (Build $curVerCode)")
                            SettingInfoRow(label = "更新来源", value = AppUpdateManager.DEFAULT_GITHUB_REPO)
                            SettingInfoRow(label = "开源地址", value = "https://github.com/${AppUpdateManager.DEFAULT_GITHUB_REPO}")

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = {
                                        isCheckingUpdate = true
                                        coroutineScope.launch {
                                            AppUpdateManager.resetUpdateIgnore(context)
                                            val res = AppUpdateManager.checkForUpdates(context)
                                            isCheckingUpdate = false
                                            if (res.isSuccess) {
                                                val info = res.getOrThrow()
                                                activeUpdateInfo = info
                                                if (info.hasUpdate) {
                                                    showUpdateDialog = true
                                                } else {
                                                    showVersionNotesDialog = true
                                                }
                                            } else {
                                                Toast.makeText(context, "检测更新失败: ${res.exceptionOrNull()?.message ?: "网络异常"}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    enabled = !isCheckingUpdate,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (isCheckingUpdate) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("检测中...", fontSize = 13.sp)
                                    } else {
                                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("检查新版本", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                OutlinedButton(
                                    onClick = {
                                        activeUpdateInfo = UpdateInfo(
                                            hasUpdate = false,
                                            latestVersion = curVerName,
                                            latestVersionCode = curVerCode.toInt(),
                                            releaseNotes = "【v${curVerName} 最新更新日志】\n\n" +
                                                "1. 资料库「歌手」与「最新添加专辑」点击全部优化：新增自适应网格画廊展示，告别直接显示歌曲列表，支持二级平滑返回\n" +
                                                "2. 服务端曲目流解析优化：修复服务端音乐流与鉴权 Token 绑定逻辑，根治部分服务端歌曲无法正常播放的问题\n" +
                                                "3. 播放容灾回退逻辑优化：严格限制仅在本地具有当前同名歌曲离线文件时才无缝降级，彻底解决跳过歌曲播放的异常\n" +
                                                "4. 全局网络与执行效率审计优化：引入全局连接池复用、HTTP 磁盘高速缓存、Dispatcher 请求调度与歌手单曲数预计算\n" +
                                                "5. 车载中控横屏体验增强：补充横屏专属视觉资产并优化自适应屏幕排版",
                                            downloadUrl = ""
                                        )
                                        showVersionNotesDialog = true
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f),
                                    border = BorderStroke(1.dp, borderColor)
                                ) {
                                    Icon(Icons.Default.Notes, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("更新日志", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }

                // ==================== 2. 音源与脚本 ====================
                SettingsTab.SOURCES -> {
                    item {
                        SettingsCard(title = "默认在线操作音源", icon = Icons.Default.GraphicEq) {
                            SettingDropdownRow(
                                title = "默认在线音源",
                                subtitle = "选择全网搜索、在线流式播放及发现推荐使用的首选音源",
                                selectedValue = currentOnlineSource,
                                options = OnlineMusicSource.entries,
                                getLabel = { it.displayName },
                                onSelect = { onOnlineSourceChange(it) }
                            )

                            Spacer(modifier = Modifier.height(14.dp))
                            SettingSwitchRow(
                                title = "自动音源容灾回退",
                                subtitle = "当首选音源取链失败时，自动按顺序尝试备用音源 (对标柠檬音乐 source.fallbackMode)",
                                checked = autoFallbackSource,
                                onCheckedChange = {
                                    autoFallbackSource = it
                                    prefs.edit().putBoolean("source_fallback_enabled", it).apply()
                                }
                            )
                        }
                    }

                    item {
                        SettingsCard(title = "落雪音源脚本管理 (LX Music)", icon = Icons.Default.Extension) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "服务端音源扩展 (${sourceScripts.size})",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Row {
                                    IconButton(
                                        onClick = {
                                            if (activeServer?.type == ServerType.LEMON_MUSIC) {
                                                isLoadingScripts = true
                                                coroutineScope.launch {
                                                    val proto = LemonMusicProtocol(
                                                        NetworkClientFactory.createOkHttpClient(context),
                                                        activeServer.serverUrl,
                                                        activeServer.username,
                                                        activeServer.tokenOrApiKey
                                                    )
                                                    sourceScripts = proto.fetchSourceList().getOrDefault(emptyList())
                                                    isLoadingScripts = false
                                                }
                                            } else {
                                                Toast.makeText(context, "请先连接柠檬音乐服务端", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = "刷新", modifier = Modifier.size(16.dp))
                                    }
                                    IconButton(
                                        onClick = {
                                            if (activeServer?.type == ServerType.LEMON_MUSIC) {
                                                showImportScriptDialog = true
                                            } else {
                                                Toast.makeText(context, "请先连接柠檬音乐服务端", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = "导入脚本", tint = AppleRed, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (isLoadingScripts) {
                                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AppleRed)
                                }
                            } else if (sourceScripts.isEmpty()) {
                                Text(
                                    text = if (activeServer?.type == ServerType.LEMON_MUSIC) "服务端暂未部署音源脚本，请点击右上角「+」导入落雪音源脚本" else "连接柠檬音乐服务端后可集中管理落雪音源脚本",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                sourceScripts.forEach { script ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                        border = BorderStroke(1.dp, borderColor.copy(alpha = 0.4f)),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(script.name, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                    if (script.version.isNotBlank()) {
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text("v${script.version}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                }
                                                if (script.author.isNotBlank()) {
                                                    Text("作者: ${script.author}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Switch(
                                                    checked = script.isActive,
                                                    onCheckedChange = { enable ->
                                                        if (activeServer != null) {
                                                            coroutineScope.launch {
                                                                val proto = LemonMusicProtocol(
                                                                    NetworkClientFactory.createOkHttpClient(context),
                                                                    activeServer.serverUrl,
                                                                    activeServer.username,
                                                                    activeServer.tokenOrApiKey
                                                                )
                                                                val res = if (enable) proto.activateSource(script.id) else proto.deactivateSource(script.id)
                                                                if (res.isSuccess) {
                                                                    sourceScripts = proto.fetchSourceList().getOrDefault(emptyList())
                                                                    Toast.makeText(context, if (enable) "音源已激活" else "音源已停用", Toast.LENGTH_SHORT).show()
                                                                }
                                                            }
                                                        }
                                                    }
                                                )
                                                IconButton(onClick = { scriptToDelete = script }, modifier = Modifier.size(28.dp)) {
                                                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = AppleRed.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ==================== 3. 存储与路径 ====================
                SettingsTab.LIBRARY_PATHS -> {
                    // A. 服务器保存路径 (顶部第一项，复用服务端的选择方式)
                    item {
                        SettingsCard(title = "服务器保存路径", icon = Icons.Default.Dns) {
                            Text(
                                text = "柠檬音乐服务端下载与存储音频文件的远端路径 (复用服务端目录配置)：",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("服务端当前下载保存路径", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (activeServer?.type == ServerType.LEMON_MUSIC) {
                                            if (isLoadingServerPaths) "正在从服务器获取路径..."
                                            else serverDownloadPath.ifBlank { "未指定路径 (使用柠檬服务端默认下载目录)" }
                                        } else {
                                            "当前未连接柠檬音乐服务器 (本地离线模式)"
                                        },
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            if (activeServer?.type == ServerType.LEMON_MUSIC) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        selectedServerPathChoice = serverDownloadPath
                                        customServerPathInput = ""
                                        showServerPathDialog = true
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("选择服务器保存路径", fontSize = 13.sp)
                                }
                            }
                        }
                    }

                    // B. 本地下载目录 (放置在服务器路径下方，本地音乐路径上方)
                    item {
                        SettingsCard(title = "本地下载目录", icon = Icons.Default.DownloadForOffline) {
                            Text(
                                text = "本机离线下载歌曲与临时缓存的保存位置 (当前应用私有/公共存储)：",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("当前本地离线保存路径", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = downloadSettings.customDownloadPath.ifBlank { "默认应用私有目录 (/Music)" },
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = onChooseDownloadDirectory,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("更改本地下载目录", fontSize = 13.sp)
                            }
                        }
                    }

                    // C. 本地音乐路径 (放置在本地下载目录下方，清晰区分)
                    item {
                        SettingsCard(title = "本地音乐路径", icon = Icons.Default.LibraryMusic) {
                            Text(
                                text = "本机音乐文件夹扫描路径。扫描到的歌曲将与柠檬服务器曲库自动比对，线上模式优先播放本地文件，离线模式直接显示为本地歌曲：",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            // 扫描目录列表
                            localMusicPaths.forEach { p ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    border = BorderStroke(1.dp, borderColor),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Folder, contentDescription = null, tint = AppleRed, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = p,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = {
                                                val updated = localMusicPaths.toMutableSet()
                                                updated.remove(p)
                                                localMusicPaths = updated
                                                prefs.edit().putStringSet("local_music_scan_folders", updated).apply()
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "移除路径", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = { showAddLocalPathDialog = true },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f),
                                    border = BorderStroke(1.dp, borderColor)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("添加扫描目录", fontSize = 13.sp)
                                }

                                Button(
                                    onClick = {
                                        if (!isScanningLocalAndServer) {
                                            isScanningLocalAndServer = true
                                            coroutineScope.launch {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(context, "正在全盘及配置目录扫描音频并与服务器比对...", Toast.LENGTH_SHORT).show()
                                                }
                                                var totalScanned = 0
                                                // 1. 扫描配置目录
                                                for (path in localMusicPaths) {
                                                    if (java.io.File(path).exists()) {
                                                        totalScanned += LocalMediaScanner.scanCustomDirectory(context, path, database)
                                                    }
                                                }
                                                // 2. 扫描系统媒体库
                                                totalScanned += LocalMediaScanner.scanSystemMediaStore(context, database)

                                                // 3. 与服务器曲库智能比对与真实性校验
                                                val dlDir = java.io.File(downloadSettings.customDownloadPath.ifBlank { context.getExternalFilesDir(null)?.absolutePath ?: "" })
                                                val matched = LocalMediaScanner.verifyAndSyncAllServerSongDownloadStatus(database, dlDir)
                                                LocalMediaScanner.matchAndMergeLocalWithServer(database)

                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(context, "本地扫描与比对完成！已收录 $totalScanned 首本地歌曲，比对匹配 $matched 首服务器歌曲已标为本地已下载", Toast.LENGTH_LONG).show()
                                                    onLocalScanCompleted()
                                                    isScanningLocalAndServer = false
                                                }
                                            }
                                        }
                                    },
                                    enabled = !isScanningLocalAndServer,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (isScanningLocalAndServer) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("比对中...", fontSize = 13.sp)
                                    } else {
                                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("立即扫描比对", fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }

                    item {
                        SettingsCard(title = "本地存储使用信息", icon = Icons.Default.Storage) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = BorderStroke(1.dp, borderColor),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "设备与曲库存储占用",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (isCalculatingStorage) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = AppleRed)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("统计中...", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // 3 列数据统计展示：歌曲数量 | 音乐总占用 | 设备剩余空间
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = "$localSongCount 首",
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "已收录本地歌曲",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .width(1.dp)
                                                .height(32.dp)
                                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                        )

                                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = formatStorageSize(localMusicSizeBytes),
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = AppleRed
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "本地音乐总占用",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .width(1.dp)
                                                .height(32.dp)
                                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                        )

                                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = formatStorageSize(storageFreeSizeBytes),
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "设备剩余可用",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            SettingActionRow(
                                title = "清除资料库遗留数据",
                                subtitle = "核对本地物理文件真实性，彻底移除已删除曲目、空专辑、孤立歌手及失效缓存记录",
                                actionText = if (isPurgingLegacyData) "清理中..." else "立即清理",
                                onAction = { showPurgeConfirmDialog = true }
                            )
                        }
                    }
                }

                // ==================== 4. 下载偏好 ====================
                SettingsTab.DOWNLOAD -> {
                    item {
                        SettingsCard(title = "默认下载配置 (对标柠檬音乐 download.*)", icon = Icons.Default.Download) {
                            SettingDropdownRow(
                                title = "默认下载音质",
                                subtitle = "歌曲与专辑下载时优先选取的音质规格",
                                selectedValue = defaultDownloadQuality,
                                options = AudioQuality.entries,
                                getLabel = { "${it.label} [${it.badge}]" },
                                getSubtitle = { "${it.bitrate} kbps · ${it.format}" },
                                onSelect = { q ->
                                    defaultDownloadQuality = q
                                    prefs.edit().putString("default_download_quality", q.key).apply()
                                }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            SettingDropdownRow(
                                title = "默认下载目标",
                                subtitle = "单曲与批量下载时的默认存储归属",
                                selectedValue = defaultDownloadTarget,
                                options = DownloadTarget.entries,
                                getLabel = { it.displayName },
                                onSelect = { target ->
                                    defaultDownloadTarget = target
                                    prefs.edit().putString("default_download_target", target.name).apply()
                                }
                            )

                            Spacer(modifier = Modifier.height(14.dp))
                            SettingSwitchRow(
                                title = "内嵌高清专辑封面",
                                subtitle = "下载音频文件时将封面写入 ID3 标签 (isEmbedPic)",
                                checked = embedCoverMeta,
                                onCheckedChange = {
                                    embedCoverMeta = it
                                    prefs.edit().putBoolean("download_embed_cover", it).apply()
                                }
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            SettingSwitchRow(
                                title = "内嵌歌词与翻译",
                                subtitle = "将逐字/逐句歌词嵌入音频文件内部标签 (isEmbedLyric)",
                                checked = embedLyricMeta,
                                onCheckedChange = {
                                    embedLyricMeta = it
                                    prefs.edit().putBoolean("download_embed_lyric", it).apply()
                                }
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            SettingSwitchRow(
                                title = "同时保存 .lrc 独立歌词文件",
                                subtitle = "在歌曲同级目录下保存独立同名 .lrc 歌词文件 (isDownloadLrc)",
                                checked = downloadLrcFile,
                                onCheckedChange = {
                                    downloadLrcFile = it
                                    prefs.edit().putBoolean("download_lrc_file", it).apply()
                                }
                            )
                        }
                    }
                }

                // ==================== 5. 播放与外观 ====================
                SettingsTab.PLAYBACK_UI -> {
                    item {
                        SettingsCard(title = "在线试听音质与缓存", icon = Icons.Default.HighQuality) {
                            // 1. Wi-Fi 状态试听音质
                            SettingDropdownRow(
                                icon = Icons.Default.Wifi,
                                title = "Wi-Fi 状态试听音质",
                                subtitle = "连接无线局域网时流式播放音质",
                                selectedValue = wifiStreamQuality,
                                options = AudioQuality.entries,
                                getLabel = { "${it.label} [${it.badge}]" },
                                getSubtitle = { "${it.bitrate} kbps · ${it.format}" },
                                onSelect = { q ->
                                    wifiStreamQuality = q
                                    prefs.edit().putString("wifi_stream_quality", q.key).apply()
                                }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // 2. 移动流量状态试听音质
                            SettingDropdownRow(
                                icon = Icons.Default.SignalCellularAlt,
                                title = "移动流量状态试听音质",
                                subtitle = "使用蜂窝网络时播放音质 (建议标准/极高)",
                                selectedValue = cellularStreamQuality,
                                options = AudioQuality.entries,
                                getLabel = { "${it.label} [${it.badge}]" },
                                getSubtitle = { "${it.bitrate} kbps · ${it.format}" },
                                onSelect = { q ->
                                    cellularStreamQuality = q
                                    prefs.edit().putString("cellular_stream_quality", q.key).apply()
                                }
                            )

                            Spacer(modifier = Modifier.height(14.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), thickness = 0.5.dp)
                            Spacer(modifier = Modifier.height(12.dp))

                            // 3. 试听本地缓存开关
                            SettingSwitchRow(
                                title = "在线试听边听边存",
                                subtitle = if (streamCacheEnabled) "已开启本地缓存：播放时边听边存，再次试听直接命中本地切片免流量" else "已彻底关闭本地缓存：纯在线内存流式试听，不向本地磁盘写入任何缓存文件",
                                checked = streamCacheEnabled,
                                onCheckedChange = { isEnabled ->
                                    streamCacheEnabled = isEnabled
                                    prefs.edit().putBoolean("stream_cache_enabled", isEnabled).apply()
                                    Media3Factory.setCacheEnabled(isEnabled)
                                }
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // 4. 清理试听缓存
                            val formattedCacheSize = remember(cacheSizeBytes) {
                                val mb = cacheSizeBytes.toDouble() / (1024 * 1024)
                                if (mb >= 0.1) "%.1f MB".format(mb) else "${(cacheSizeBytes / 1024)} KB"
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("当前试听缓存占用", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text(formattedCacheSize, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                OutlinedButton(
                                    onClick = {
                                        Media3Factory.clearStreamCache(context)
                                        cacheSizeBytes = Media3Factory.getCacheSizeBytes(context)
                                        Toast.makeText(context, "已成功清空在线试听缓存切片", Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("清理试听缓存", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    item {
                        SettingsCard(title = "播放与启动行为", icon = Icons.Default.PlayCircle) {
                            SettingSwitchRow(
                                title = "启动自动继续播放",
                                subtitle = "打开应用时自动恢复上次关闭前的曲目并继续播放",
                                checked = autoPlayOnStartup,
                                onCheckedChange = onAutoPlayOnStartupChange
                            )

                            Spacer(modifier = Modifier.height(10.dp))
                            SettingSwitchRow(
                                title = "网络容灾自动回退本地",
                                subtitle = "在线音频遇到网络超时或无法缓冲时，无缝切换至已下载的本地歌曲",
                                checked = autoFallbackToLocal,
                                onCheckedChange = onAutoFallbackToLocalChange
                            )
                        }
                    }

                    item {
                        SettingsCard(title = "界面外观与特效", icon = Icons.Default.Palette) {
                            SettingDropdownRow(
                                title = "主题外观",
                                subtitle = "选择系统全局视觉风格",
                                selectedValue = themeMode,
                                options = listOf(AppThemeMode.FOLLOW_SYSTEM, AppThemeMode.DARK, AppThemeMode.LIGHT),
                                getLabel = { mode ->
                                    when (mode) {
                                        AppThemeMode.FOLLOW_SYSTEM -> "跟随系统"
                                        AppThemeMode.DARK -> "深色模式"
                                        AppThemeMode.LIGHT -> "浅色模式"
                                    }
                                },
                                onSelect = { onThemeModeChange(it) }
                            )

                            Spacer(modifier = Modifier.height(16.dp))
                            Text("毛玻璃特效透明度 (${(blurAlpha * 100).toInt()}%)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Slider(
                                value = blurAlpha,
                                onValueChange = onBlurAlphaChange,
                                valueRange = 0.2f..1.0f,
                                colors = SliderDefaults.colors(thumbColor = AppleRed, activeTrackColor = AppleRed)
                            )

                            Spacer(modifier = Modifier.height(10.dp))
                            SettingSwitchRow(
                                title = "悬浮播放底栏自动收缩",
                                subtitle = "页面向上滚动时自动收起导航栏，为列表展示释放最大可视区域",
                                checked = enableBottomBarAnimation,
                                onCheckedChange = onEnableBottomBarAnimationChange
                            )
                        }
                    }

                    item {
                        Button(
                            onClick = onExitAppCompletely,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("彻底退出应用并停止后台服务", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // 清除资料库遗留数据二次确认弹窗
    if (showPurgeConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showPurgeConfirmDialog = false },
            title = { Text("确认清除资料库遗留数据？", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "此操作将深度核验本地歌曲的物理文件：\n" +
                    "1. 物理文件已被删除或丢失的歌曲记录将被清除；\n" +
                    "2. 没有任何关联歌曲的空专辑、空歌手及遗留临时榜单将被安全移除；\n" +
                    "3. 真实存在的已下载物理音频文件将完好保留。\n\n" +
                    "清理后资料库将完全恢复清爽整洁。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPurgeConfirmDialog = false
                        isPurgingLegacyData = true
                        coroutineScope.launch {
                            val purgedCount = LocalMediaScanner.purgeLegacyResidualData(database)
                            isPurgingLegacyData = false
                            onLocalScanCompleted()
                            Toast.makeText(context, "资料库清理完成！成功清理 $purgedCount 条遗留记录", Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed)
                ) {
                    Text("立即清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPurgeConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 服务器保存路径选择与切换弹窗 (复用柠檬服务端 /api/paths 与 /api/paths/download)
    if (showServerPathDialog && activeServer != null) {
        Dialog(onDismissRequest = { showServerPathDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cardBg,
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier.fillMaxWidth(0.95f).padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "选择服务器下载保存路径",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "复用柠檬服务端的音乐保存目录配置 (/api/paths)：",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    if (serverAvailablePaths.isNotEmpty()) {
                        Text(
                            text = "服务端已挂载音乐路径：",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 160.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(serverAvailablePaths) { pathItem ->
                                val isSelected = selectedServerPathChoice == pathItem
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) AppleRed.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    border = BorderStroke(1.dp, if (isSelected) AppleRed else Color.Transparent),
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                        selectedServerPathChoice = pathItem
                                        customServerPathInput = ""
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = {
                                                selectedServerPathChoice = pathItem
                                                customServerPathInput = ""
                                            },
                                            colors = RadioButtonDefaults.colors(selectedColor = AppleRed)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = pathItem,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Text(
                        text = "或手动输入服务器绝对路径：",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = customServerPathInput,
                        onValueChange = {
                            customServerPathInput = it
                            if (it.isNotBlank()) selectedServerPathChoice = it
                        },
                        placeholder = { Text("例如: /volume1/music/downloads", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    Spacer(modifier = Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showServerPathDialog = false }) {
                            Text("取消")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val target = customServerPathInput.trim().ifBlank { selectedServerPathChoice.trim() }
                                if (target.isNotBlank()) {
                                    coroutineScope.launch {
                                        val proto = LemonMusicProtocol(
                                            NetworkClientFactory.createOkHttpClient(context),
                                            activeServer.serverUrl,
                                            activeServer.username,
                                            activeServer.tokenOrApiKey
                                        )
                                        val res = proto.updateServerDownloadPath(target)
                                        if (res.isSuccess) {
                                            serverDownloadPath = target
                                            showServerPathDialog = false
                                            Toast.makeText(context, "已成功更新服务端下载路径: $target", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "更新失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed)
                        ) {
                            Text("确定保存")
                        }
                    }
                }
            }
        }
    }

    // 添加本地音乐扫描路径弹窗
    if (showAddLocalPathDialog) {
        Dialog(onDismissRequest = { showAddLocalPathDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cardBg,
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier.fillMaxWidth(0.95f).padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "添加本地音乐扫描目录",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "输入本机绝对路径或从常见音乐目录中快捷选择：",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    val quickPresets = listOf(
                        "/storage/emulated/0/Music",
                        "/storage/emulated/0/Download",
                        "/storage/emulated/0/Download/LMPlayer",
                        "/storage/emulated/0/Tencent/QQfile_recv",
                        "/storage/emulated/0/NetEase/CloudMusic"
                    )

                    Text(
                        text = "快捷预设目录：",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 130.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(quickPresets) { preset ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                                    newLocalPathInput = preset
                                }
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Folder, contentDescription = null, tint = AppleRed, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(text = preset, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newLocalPathInput,
                        onValueChange = { newLocalPathInput = it },
                        label = { Text("目录绝对路径", fontSize = 12.sp) },
                        placeholder = { Text("/storage/emulated/0/Music", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = {
                            showAddLocalPathDialog = false
                            onImportCustomFolder()
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, borderColor)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("打开系统文件夹选择器 (SAF)", fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showAddLocalPathDialog = false }) {
                            Text("取消")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val path = newLocalPathInput.trim()
                                if (path.isNotBlank()) {
                                    val updated = localMusicPaths.toMutableSet()
                                    updated.add(path)
                                    localMusicPaths = updated
                                    prefs.edit().putStringSet("local_music_scan_folders", updated).apply()
                                    showAddLocalPathDialog = false
                                    newLocalPathInput = ""
                                    Toast.makeText(context, "已添加扫描目录: $path", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed)
                        ) {
                            Text("添加")
                        }
                    }
                }
            }
        }
    }

    // 导入落雪脚本弹窗
    if (showImportScriptDialog) {
        Dialog(onDismissRequest = { showImportScriptDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cardBg,
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier.fillMaxWidth(0.94f).padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("导入落雪音源脚本", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        FilterChip(
                            selected = isImportingUrl,
                            onClick = { isImportingUrl = true },
                            label = { Text("URL 在线导入") }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilterChip(
                            selected = !isImportingUrl,
                            onClick = { isImportingUrl = false },
                            label = { Text("脚本代码直接粘贴") }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isImportingUrl) {
                        OutlinedTextField(
                            value = importScriptUrl,
                            onValueChange = { importScriptUrl = it },
                            label = { Text("脚本远程地址 (URL)") },
                            placeholder = { Text("https://example.com/source.js") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = importScriptCode,
                            onValueChange = { importScriptCode = it },
                            label = { Text("脚本 JS 源码") },
                            placeholder = { Text("粘贴落雪脚本 JavaScript 代码...") },
                            modifier = Modifier.fillMaxWidth().height(140.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { showImportScriptDialog = false }, modifier = Modifier.weight(1f)) {
                            Text("取消")
                        }
                        Button(
                            onClick = {
                                if (activeServer != null) {
                                    coroutineScope.launch {
                                        val proto = LemonMusicProtocol(
                                            NetworkClientFactory.createOkHttpClient(context),
                                            activeServer.serverUrl,
                                            activeServer.username,
                                            activeServer.tokenOrApiKey
                                        )
                                        val res = if (isImportingUrl) {
                                            proto.importSourceUrl(importScriptUrl.trim())
                                        } else {
                                            proto.importSourceScript(importScriptCode.trim())
                                        }
                                        if (res.isSuccess) {
                                            Toast.makeText(context, "导入成功！", Toast.LENGTH_SHORT).show()
                                            sourceScripts = proto.fetchSourceList().getOrDefault(emptyList())
                                            showImportScriptDialog = false
                                        } else {
                                            Toast.makeText(context, "导入失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("确定导入")
                        }
                    }
                }
            }
        }
    }

    // 删除脚本确认
    if (scriptToDelete != null) {
        val s = scriptToDelete!!
        AlertDialog(
            onDismissRequest = { scriptToDelete = null },
            title = { Text("确认删除该音源脚本？") },
            text = { Text("即将删除音源脚本「${s.name}」，删除后将无法从该脚本获取在线播放和搜索。") },
            confirmButton = {
                Button(
                    onClick = {
                        scriptToDelete = null
                        if (activeServer != null) {
                            coroutineScope.launch {
                                val proto = LemonMusicProtocol(
                                    NetworkClientFactory.createOkHttpClient(context),
                                    activeServer.serverUrl,
                                    activeServer.username,
                                    activeServer.tokenOrApiKey
                                )
                                proto.deleteSource(s.id)
                                sourceScripts = proto.fetchSourceList().getOrDefault(emptyList())
                                Toast.makeText(context, "脚本已删除", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed)
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { scriptToDelete = null }) { Text("取消") }
            }
        )
    }

    // 添加/编辑服务器配置弹窗
    if (showAddServerDialog) {
        var serverNameInput by remember { mutableStateOf(editingServer?.name ?: "") }
        var serverTypeInput by remember { mutableStateOf(editingServer?.type ?: ServerType.LEMON_MUSIC) }
        var serverUrlInput by remember { mutableStateOf(editingServer?.serverUrl ?: "") }
        var usernameInput by remember { mutableStateOf(editingServer?.username ?: "") }
        var tokenInput by remember { mutableStateOf(editingServer?.tokenOrApiKey ?: "") }
        var isTestingConnection by remember { mutableStateOf(false) }
        var testResultMsg by remember { mutableStateOf<String?>(null) }
        var isTestSuccess by remember { mutableStateOf<Boolean?>(null) }

        fun normalizeServerUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return ""
            val withProto = if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
                "http://$trimmed"
            } else {
                trimmed
            }
            return withProto.trimEnd('/')
        }

        Dialog(onDismissRequest = { showAddServerDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cardBg,
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier.fillMaxWidth(0.96f).padding(12.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(if (editingServer != null) "编辑服务器" else "添加新服务器", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = serverNameInput,
                        onValueChange = { serverNameInput = it },
                        label = { Text("服务器名称 (如 飞牛NAS/客厅柠檬)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = serverUrlInput,
                        onValueChange = { 
                            serverUrlInput = it
                            testResultMsg = null
                        },
                        label = { Text("服务器地址 (支持内网IP如 192.168.1.100:3000)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = usernameInput,
                        onValueChange = { usernameInput = it },
                        label = { Text("用户名 (可选)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        label = { Text("登录令牌 Token / 密码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (testResultMsg != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = testResultMsg ?: "",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isTestSuccess == true) Color(0xFF34C759) else MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 测试连接按钮
                        OutlinedButton(
                            onClick = {
                                val cleanUrl = normalizeServerUrl(serverUrlInput)
                                if (cleanUrl.isBlank()) {
                                    Toast.makeText(context, "请输入服务器地址", Toast.LENGTH_SHORT).show()
                                    return@OutlinedButton
                                }
                                serverUrlInput = cleanUrl
                                isTestingConnection = true
                                testResultMsg = null
                                isTestSuccess = null
                                coroutineScope.launch {
                                    val startTime = System.currentTimeMillis()
                                    try {
                                        val testConfig = ServerConfig(
                                            id = "test_conn",
                                            name = serverNameInput.ifBlank { "测试服务器" },
                                            type = serverTypeInput,
                                            serverUrl = cleanUrl,
                                            username = usernameInput.trim(),
                                            tokenOrApiKey = tokenInput.trim(),
                                            isCurrentActive = false
                                        )
                                        val client = NetworkClientFactory.createOkHttpClient(context)
                                        val proto = LemonMusicProtocol(client, cleanUrl, testConfig.username, testConfig.tokenOrApiKey)
                                        val authRes = proto.authenticate(testConfig)
                                        val latency = System.currentTimeMillis() - startTime
                                        if (authRes.isSuccess) {
                                            isTestSuccess = true
                                            testResultMsg = "✓ 连接成功 (耗时 ${latency}ms)"
                                            Toast.makeText(context, "连接成功 (耗时 ${latency}ms)", Toast.LENGTH_SHORT).show()
                                        } else {
                                            isTestSuccess = false
                                            val err = authRes.exceptionOrNull()?.message ?: "鉴权失败"
                                            testResultMsg = "✕ 连接失败: $err"
                                            Toast.makeText(context, "连接失败: $err", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        isTestSuccess = false
                                        val err = e.localizedMessage ?: "无法访问服务器"
                                        testResultMsg = "✕ 无法访问: $err"
                                        Toast.makeText(context, "无法访问: $err", Toast.LENGTH_SHORT).show()
                                    } finally {
                                        isTestingConnection = false
                                    }
                                }
                            },
                            enabled = !isTestingConnection,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isTestingConnection) {
                                CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = AppleRed)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("测试中", fontSize = 13.sp, maxLines = 1)
                            } else {
                                Text("测试连接", fontSize = 13.sp, maxLines = 1)
                            }
                        }

                        // 取消按钮
                        OutlinedButton(
                            onClick = { showAddServerDialog = false },
                            modifier = Modifier.weight(0.75f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("取消", fontSize = 13.sp, maxLines = 1)
                        }

                        // 保存按钮 (不分行，文字为保存)
                        Button(
                            onClick = {
                                val cleanUrl = normalizeServerUrl(serverUrlInput)
                                if (cleanUrl.isNotBlank()) {
                                    val newConfig = ServerConfig(
                                        id = editingServer?.id ?: "srv_${System.currentTimeMillis()}",
                                        name = serverNameInput.ifBlank { "我的音乐服务器" },
                                        type = serverTypeInput,
                                        serverUrl = cleanUrl,
                                        username = usernameInput.trim(),
                                        tokenOrApiKey = tokenInput.trim(),
                                        isCurrentActive = true
                                    )
                                    onAddOrUpdateServer(newConfig)
                                    showAddServerDialog = false
                                } else {
                                    Toast.makeText(context, "请输入服务器地址", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                            modifier = Modifier.weight(0.85f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("保存", fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    // 在线更新弹窗 (检测到新版本时展示)
    if (showUpdateDialog && activeUpdateInfo != null) {
        AppUpdateDialog(
            updateInfo = activeUpdateInfo!!,
            isDownloading = isDownloadingUpdate,
            downloadProgress = updateDownloadProgress,
            isDownloaded = isUpdateDownloaded,
            onDismiss = { showUpdateDialog = false },
            onNeverUpdate = {
                showUpdateDialog = false
                AppUpdateManager.setNeverUpdate(context, true)
                Toast.makeText(context, "已关闭自动更新提醒", Toast.LENGTH_SHORT).show()
            },
            onStartDownload = {
                if (activeUpdateInfo?.downloadUrl?.isNotBlank() == true && !isDownloadingUpdate) {
                    isDownloadingUpdate = true
                    updateDownloadProgress = 0f
                    coroutineScope.launch {
                        AppUpdateManager.downloadApk(
                            context = context,
                            downloadUrl = activeUpdateInfo!!.downloadUrl,
                            onProgress = { progress, _, _ -> updateDownloadProgress = progress }
                        ).onSuccess { apkFile ->
                            isDownloadingUpdate = false
                            isUpdateDownloaded = true
                            downloadedApkFile = apkFile
                            Toast.makeText(context, "安装包下载完成，正在调起安装...", Toast.LENGTH_SHORT).show()
                            AppUpdateManager.installApk(context, apkFile)
                        }.onFailure { error ->
                            isDownloadingUpdate = false
                            Toast.makeText(context, "下载更新失败: ${error.message ?: "网络异常"}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onInstall = {
                downloadedApkFile?.let { AppUpdateManager.installApk(context, it) }
            }
        )
    }

    // 版本更新日志与最新版本状态详情弹窗
    if (showVersionNotesDialog && activeUpdateInfo != null) {
        AlertDialog(
            onDismissRequest = { showVersionNotesDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = AppleRed, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("LMPlayer v${activeUpdateInfo?.latestVersion}", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = if (activeUpdateInfo?.hasUpdate == true) "发现新版本可用：" else "当前已是最新至臻发布版本，系统运行稳定！",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (activeUpdateInfo?.hasUpdate == true) AppleRed else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = activeUpdateInfo?.releaseNotes ?: "",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "更新来源：GitHub (@${AppUpdateManager.DEFAULT_GITHUB_REPO})",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showVersionNotesDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed)
                ) {
                    Text("确定")
                }
            }
        )
    }
}

/**
 * 设置卡片容器
 */
@Composable
private fun SettingsCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val cardColor = if (isDark) Color(0xFF24242C) else Color.White
    val borderCol = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cardColor,
        border = BorderStroke(1.dp, borderCol),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AppleRed,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

/**
 * 信息行
 */
@Composable
private fun SettingInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 开关行
 */
@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/**
 * 动作行
 */
@Composable
private fun SettingActionRow(
    title: String,
    subtitle: String,
    actionText: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp)
        }
        OutlinedButton(
            onClick = onAction,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(actionText, fontSize = 12.sp)
        }
    }
}

/**
 * 现代轻奢原生下拉选择行
 */
@Composable
private fun <T> SettingDropdownRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    selectedValue: T,
    options: List<T>,
    getLabel: (T) -> String,
    getSubtitle: ((T) -> String)? = null,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = AppleRed, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Box {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isDark) Color(0xFF2C2C34) else Color(0xFFF2F2F7),
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { expanded = true }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = getLabel(selectedValue),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppleRed
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "展开下拉选择",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 180.dp, max = 280.dp)
            ) {
                options.forEach { option ->
                    val isSelected = option == selectedValue
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = getLabel(option),
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurface
                                    )
                                    getSubtitle?.invoke(option)?.let { sub ->
                                        if (sub.isNotBlank()) {
                                            Text(sub, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "已选",
                                        tint = AppleRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                        modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(8.dp))
                    )
                }
            }
        }
    }
}

private fun formatStorageSize(bytes: Long): String {
    if (bytes <= 0) return "0.0 MB"
    val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        String.format(java.util.Locale.getDefault(), "%.2f GB", gb)
    } else {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        String.format(java.util.Locale.getDefault(), "%.1f MB", mb)
    }
}
