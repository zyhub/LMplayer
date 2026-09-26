package com.lm.player.feature.library

import android.util.Log
import android.widget.Toast
import java.io.File
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.DownloadQualityChoiceDialog
import com.lm.player.core.designsystem.component.ServerSwitchDropdownButton
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.model.*
import com.lm.player.feature.home.SongListItemRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 现代轻奢音乐资料库 (对标柠檬音乐 Library.vue 架构重构)
 * 包含：
 * 1. 顶部检索与状态指示条 (快速过滤歌曲/歌手/专辑/歌单、刷新同步、新建歌单)
 * 2. 歌单画廊 (我喜欢的音乐、最近播放、自建与云端歌单)
 * 3. 音乐风格流派 (流派气泡筛选)
 * 4. 歌手胶囊流 (歌手头像、曲目数、即点即播)
 * 5. 专辑矩阵 (最新专辑卡片流)
 * 6. 全部歌曲高保真流 (带格式/音质标签、收藏、下载弹窗、多维排序)
 * 7. 页面内无缝下钻视图 (歌单/歌手/专辑/流派详情，不遮挡底部悬浮播放栏)
 */
@Composable
fun LocalLibraryScreen(
    allSongs: List<UnifiedSong>,
    downloadedSongs: List<UnifiedSong> = emptyList(),
    playlists: List<UnifiedPlaylist> = emptyList(),
    activeServerConfig: ServerConfig? = null,
    activeDownloadTasks: List<DownloadTask> = emptyList(),
    activeDownloadCount: Int = 0,
    onSongClick: (UnifiedSong, List<UnifiedSong>?) -> Unit = { song, _ -> },
    onDownloadSong: (UnifiedSong) -> Unit = {},
    onDownloadSongWithOptions: (UnifiedSong, DownloadTarget, AudioQuality) -> Unit = { song, _, _ -> onDownloadSong(song) },
    onOpenDownloads: () -> Unit = {},
    onRefreshPlaylists: () -> Unit = {},
    onCreatePlaylist: (name: String, isOnline: Boolean) -> Unit = { _, _ -> },
    onDeletePlaylist: (playlistId: String) -> Unit = {},
    onToggleFavorite: ((UnifiedSong) -> Unit)? = null,
    onFetchPlaylistSongs: (suspend (playlistId: String, isOnline: Boolean) -> List<UnifiedSong>)? = null,
    onFetchServerFolders: (suspend (parentId: String?) -> List<ServerFolderItem>)? = null,
    onFetchServerFolderSongs: (suspend (folderId: String) -> List<UnifiedSong>)? = null,
    onFetchServerGenres: (suspend () -> List<UnifiedGenre>)? = null,
    onFetchServerScanStatus: (suspend () -> LemonScanStatus?)? = null,
    onTriggerServerScan: (suspend () -> Unit)? = null,
    onDeleteLocalFilePath: (suspend (String) -> Unit)? = null,
    onDeleteDownloadedSongs: ((List<UnifiedSong>) -> Unit)? = null,
    initialCategory: LibraryCategory? = null,
    currentServerName: String = "本地模式",
    configuredServers: List<ServerConfig> = emptyList(),
    blurAlpha: Float = 0.85f,
    onSelectLocalServer: () -> Unit = {},
    onSelectServer: (ServerConfig) -> Unit = {},
    onSyncNow: () -> Unit = {},
    onGoToSettings: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val dimensions = LocalAppDimensions.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)

    // 排序模式
    var songSortMode by remember { mutableStateOf("default") } // default, name, artist, duration

    // 下钻视图状态：当前正在查看的集合详情 (歌单、歌手、专辑、流派)
    var activeSubViewTitle by remember { mutableStateOf<String?>(null) }
    var activeSubViewSubtitle by remember { mutableStateOf<String>("") }
    var activeSubViewSongs by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
    var isLoadingSubView by remember { mutableStateOf(false) }
    var isFromAllPlaylists by remember { mutableStateOf(false) }
    var isFromAllFolders by remember { mutableStateOf(false) }
    var isFromAllArtists by remember { mutableStateOf(false) }
    var isFromAllAlbums by remember { mutableStateOf(false) }

    // 本地文件夹目录结构动态聚合 (根据相对路径或本地物理路径聚合并提取目录名)
    val localFolders = remember(allSongs) {
        allSongs.mapNotNull { song ->
            val folderName = when {
                !song.relativeFolderPath.isNullOrBlank() -> {
                    val p = song.relativeFolderPath.trim().replace('\\', '/')
                    p.trimEnd('/').substringAfterLast('/')
                }
                !song.localFilePath.isNullOrBlank() -> {
                    try {
                        val f = File(song.localFilePath)
                        f.parentFile?.name
                    } catch (_: Exception) { null }
                }
                else -> null
            }?.trim()?.ifBlank { null }
            if (folderName != null && folderName !in listOf("0", "emulated", "sdcard", "storage")) {
                folderName to song
            } else null
        }.groupBy({ it.first }, { it.second })
        .map { (folderName, songs) ->
            UnifiedFolder(
                id = "folder_${folderName.hashCode()}",
                name = folderName,
                path = songs.firstOrNull()?.let { it.relativeFolderPath ?: it.localFilePath } ?: "",
                songCount = songs.size,
                songs = songs
            )
        }.sortedByDescending { it.songCount }
    }

    // 本地下载离线歌曲聚合：直接关联外部已下载全量曲目（与下载管理器对齐），若未传入则从 allSongs 兜底聚合
    val finalDownloadedSongs = remember(allSongs, downloadedSongs) {
        if (downloadedSongs.isNotEmpty()) {
            downloadedSongs
        } else {
            allSongs.filter { it.downloadStatus == DownloadStatus.DOWNLOADED || (!it.localFilePath.isNullOrBlank() && File(it.localFilePath).exists()) }
        }
    }
    var isDownloadManagementMode by remember { mutableStateOf(false) }
    val selectedDownloadSongIds = remember { mutableStateListOf<String>() }
    var showBatchDeleteLocalDialog by remember { mutableStateOf(false) }

    // 当处于本地下载视图时，若下载任务完成或变动，实时响应刷新
    LaunchedEffect(finalDownloadedSongs) {
        if (activeSubViewTitle == "本地下载") {
            activeSubViewSongs = finalDownloadedSongs
            activeSubViewSubtitle = "本机离线歌曲 · 共 ${finalDownloadedSongs.size} 首"
        }
    }

    // 动态聚合数据
    val artists = remember(allSongs) {
        val countMap = allSongs.groupingBy { it.artist.ifBlank { "未知歌手" } }.eachCount()
        allSongs.groupBy { it.artist.ifBlank { "未知歌手" } }
            .map { (artistName, songs) ->
                val sCount = countMap[artistName] ?: songs.size
                UnifiedArtist(
                    id = "artist_${artistName.hashCode()}",
                    name = artistName,
                    avatarUrl = songs.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl ?: "",
                    albumCount = songs.map { it.album }.distinct().size,
                    songCount = sCount
                )
            }.sortedByDescending { it.songCount }
    }

    val albums = remember(allSongs) {
        allSongs.groupBy { it.album.ifBlank { "单曲精选" } }
            .map { (albumName, songs) ->
                UnifiedAlbum(
                    id = "album_${albumName.hashCode()}",
                    title = albumName,
                    artist = songs.firstOrNull()?.artist ?: "各种艺术家",
                    coverUrl = songs.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl ?: "",
                    songCount = songs.size
                )
            }.sortedByDescending { it.songCount }
    }

    // 自动同步服务器歌单 (进入资料库或服务器配置就绪时自动拉取)
    LaunchedEffect(activeServerConfig?.id) {
        if (activeServerConfig != null && activeServerConfig.type == ServerType.LEMON_MUSIC) {
            onRefreshPlaylists()
        }
    }

    // 层级返回调度器：如果从「全部歌单」、「全部文件夹」、「全部歌手」或「全部专辑」进入下级详情，先返回上层网格，再返回资料库首页
    val handleSubViewBack: () -> Unit = {
        if (isDownloadManagementMode) {
            isDownloadManagementMode = false
            selectedDownloadSongIds.clear()
        } else if (activeSubViewTitle != "全部歌单" && isFromAllPlaylists) {
            activeSubViewTitle = "全部歌单"
            activeSubViewSubtitle = "共 ${playlists.size + 3} 个歌单"
            isFromAllPlaylists = false
        } else if (activeSubViewTitle != "全部文件夹" && isFromAllFolders) {
            activeSubViewTitle = "全部文件夹"
            activeSubViewSubtitle = "共 ${localFolders.size} 个本地文件夹"
            isFromAllFolders = false
        } else if (activeSubViewTitle != "全部歌手" && isFromAllArtists) {
            activeSubViewTitle = "全部歌手"
            activeSubViewSubtitle = "共 ${artists.size} 位歌手"
            isFromAllArtists = false
        } else if (activeSubViewTitle != "全部专辑" && isFromAllAlbums) {
            activeSubViewTitle = "全部专辑"
            activeSubViewSubtitle = "共 ${albums.size} 张专辑"
            isFromAllAlbums = false
        } else {
            activeSubViewTitle = null
            isFromAllPlaylists = false
            isFromAllFolders = false
            isFromAllArtists = false
            isFromAllAlbums = false
        }
    }

    // 触屏滑动返回或物理按键退出下钻视图与多选模式
    BackHandler(enabled = activeSubViewTitle != null) {
        handleSubViewBack()
    }

    // 下载选择弹窗
    var songForDownloadChoice by remember { mutableStateOf<UnifiedSong?>(null) }

    // 创建歌单弹窗
    var isCreatePlaylistDialogOpen by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var newPlaylistIsOnline by remember { mutableStateOf(activeServerConfig != null && !currentServerName.contains("本地") && !currentServerName.contains("已下载")) }
    val isServerOk = activeServerConfig != null && activeServerConfig.type == ServerType.LEMON_MUSIC

    // 监听新建歌单弹窗打开事件，动态同步在线开关
    LaunchedEffect(isCreatePlaylistDialogOpen) {
        if (isCreatePlaylistDialogOpen) {
            newPlaylistName = ""
            newPlaylistIsOnline = (activeServerConfig != null && !currentServerName.contains("本地") && !currentServerName.contains("已下载"))
        }
    }

    // 常用风格流派列表 (本地离线兜底)
    val defaultGenres = listOf("流行", "摇滚", "民谣", "电子", "爵士", "古典", "纯音乐", "ACG", "嘻哈", "华语", "欧美")

    var serverGenres by remember { mutableStateOf<List<UnifiedGenre>>(emptyList()) }
    var serverScanStatus by remember { mutableStateOf<LemonScanStatus?>(null) }
    var isTriggeringScan by remember { mutableStateOf(false) }

    LaunchedEffect(activeServerConfig) {
        if (activeServerConfig != null && activeServerConfig.type == ServerType.LEMON_MUSIC) {
            if (onFetchServerGenres != null) {
                coroutineScope.launch {
                    val g = runCatching { onFetchServerGenres() }.getOrDefault(emptyList())
                    if (g.isNotEmpty()) serverGenres = g
                }
            }
            if (onFetchServerScanStatus != null) {
                coroutineScope.launch {
                    val s = runCatching { onFetchServerScanStatus() }.getOrNull()
                    serverScanStatus = s
                }
            }
        } else {
            serverGenres = emptyList()
            serverScanStatus = null
        }
    }

    // 判定曲目的加入方式与来源归属
    fun getSongSourceTypeRank(song: UnifiedSong): Int {
        return when {
            song.serverId in listOf("local_folder", "local_storage", "local_saf") -> 1 // 本地扫描
            song.id.startsWith("lemon_online_") || (song.downloadStatus == DownloadStatus.DOWNLOADED && !song.localFilePath.isNullOrBlank() && song.serverId !in listOf("lemon_music") && !song.serverId.startsWith("srv_")) -> 2 // 在线下载
            song.serverId == "lemon_music" || song.serverId.startsWith("srv_") || (song.serverId.isNotBlank() && song.serverId != "lemon_online") -> 3 // 服务端同步
            else -> 4 // 外部歌单导入
        }
    }

    fun getSongSourceTypeName(song: UnifiedSong): String {
        return when {
            song.serverId in listOf("local_folder", "local_storage", "local_saf") -> "本地目录扫描"
            song.id.startsWith("lemon_online_") || (song.downloadStatus == DownloadStatus.DOWNLOADED && !song.localFilePath.isNullOrBlank() && song.serverId !in listOf("lemon_music") && !song.serverId.startsWith("srv_")) -> "在线下载缓存"
            song.serverId == "lemon_music" || song.serverId.startsWith("srv_") || (song.serverId.isNotBlank() && song.serverId != "lemon_online") -> "云端服务同步"
            else -> "外部歌单导入"
        }
    }

    // 排序后的歌曲列表
    val filteredSongs = remember(allSongs, songSortMode) {
        when (songSortMode) {
            "name" -> allSongs.sortedBy { it.title }
            "artist" -> allSongs.sortedBy { it.artist }
            "duration" -> allSongs.sortedByDescending { it.durationMs }
            "source" -> allSongs.sortedWith(
                compareBy<UnifiedSong> { getSongSourceTypeRank(it) }
                    .thenByDescending { it.addedTimestamp }
                    .thenBy { it.title }
            )
            else -> allSongs
        }
    }

    // 最近添加歌曲切片 (优先聚合已下载或有明确添加时间戳的曲目，按 addedTimestamp 倒序排序取前20首)
    val recentAddedSongs = remember(allSongs) {
        val downloadedOrTimestamped = allSongs.filter { it.downloadStatus == DownloadStatus.DOWNLOADED || it.addedTimestamp > 0 }
        if (downloadedOrTimestamped.isNotEmpty()) {
            downloadedOrTimestamped.sortedWith(
                compareByDescending<UnifiedSong> { it.addedTimestamp }
                    .thenByDescending { it.downloadStatus == DownloadStatus.DOWNLOADED }
            ).take(20)
        } else {
            allSongs.sortedByDescending { it.addedTimestamp }.take(20)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 主视图：浏览资料库各大板块 (当没有进入二级下钻时展示)
        if (activeSubViewTitle == null) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(
                    top = contentPadding.calculateTopPadding() + 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. 顶部 Header (大标题 + 刷新/新建歌单/离线下载按键)
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(top = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "资料库",
                                    style = TextStyle(
                                        fontSize = 32.sp * dimensions.fontScale,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                )
                                Text(
                                    text = if (currentServerName.contains("本地") || currentServerName.contains("已下载")) "本地离线曲库" else "已连接 · $currentServerName",
                                    style = TextStyle(
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                IconButton(
                                    onClick = { isCreatePlaylistDialogOpen = true },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "新建歌单",
                                        tint = AppleRed,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                IconButton(
                                    onClick = onOpenDownloads,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    BadgedBox(badge = {
                                        if (activeDownloadCount > 0) {
                                            Badge(containerColor = AppleRed) {
                                                Text(activeDownloadCount.toString(), fontSize = 10.sp)
                                            }
                                        }
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = "下载管理",
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                // 类似首页的本地/服务器切换按钮
                                ServerSwitchDropdownButton(
                                    currentServer = currentServerName,
                                    configuredServers = configuredServers,
                                    blurAlpha = blurAlpha,
                                    onSelectLocal = onSelectLocalServer,
                                    onSelectServer = onSelectServer,
                                    onSyncNow = onSyncNow,
                                    onGoToSettings = onGoToSettings
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // 柠檬服务端扫描与曲库概览卡片 (对标柠檬音乐 Library.vue 的 library-scan-summary)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = surfaceColor,
                            border = BorderStroke(1.dp, borderColor),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (serverScanStatus?.isScanning == true) AppleRed else Color(0xFF4CAF50))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = if (isServerOk) {
                                                if (serverScanStatus?.isScanning == true) {
                                                    "服务端正在扫描曲库 (${serverScanStatus?.cachedCount ?: 0} / ${serverScanStatus?.total ?: 0})"
                                                } else {
                                                    "已连接柠檬音乐 · 共 ${allSongs.size} 首歌曲"
                                                }
                                            } else {
                                                "本地离线曲库 · 共 ${allSongs.size} 首歌曲"
                                            },
                                            fontSize = 12.sp * dimensions.fontScale,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "${playlists.size} 个歌单 · ${artists.size} 位歌手 · ${albums.size} 张专辑",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (isServerOk && onTriggerServerScan != null) {
                                    TextButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                isTriggeringScan = true
                                                onTriggerServerScan()
                                                Toast.makeText(context, "已触发柠檬服务器后台扫描与刮削", Toast.LENGTH_SHORT).show()
                                                delay(1000L)
                                                isTriggeringScan = false
                                            }
                                        },
                                        enabled = !isTriggeringScan,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "扫描",
                                            tint = AppleRed,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isTriggeringScan) "请求中" else "刷新库",
                                            fontSize = 11.sp,
                                            color = AppleRed,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. 【歌单】板块 (Playlists Horizontal Row)
                item {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "歌单",
                                fontSize = 19.sp * dimensions.fontScale,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (activeServerConfig != null && activeServerConfig.type == ServerType.LEMON_MUSIC) {
                                    IconButton(
                                        onClick = { onRefreshPlaylists() },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "同步歌单",
                                            tint = AppleRed,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "全部 ${playlists.size + 3} 个",
                                    fontSize = 12.sp,
                                    color = AppleRed,
                                    modifier = Modifier.clickable {
                                        activeSubViewTitle = "全部歌单"
                                        activeSubViewSubtitle = "共 ${playlists.size + 3} 个歌单"
                                        isFromAllPlaylists = false
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp)
                        ) {
                            // A. 新建歌单快捷卡片 (突出展示自建歌单入口)
                            item {
                                CreatePlaylistActionCard(
                                    onClick = { isCreatePlaylistDialogOpen = true }
                                )
                            }

                            // B. 我喜欢的音乐 (Favorites Card)
                            item {
                                val favSongs = allSongs.filter { it.isFavorite }
                                PlaylistSpecialCard(
                                    title = "我喜欢的音乐",
                                    subtitle = "${favSongs.size} 首歌曲",
                                    icon = Icons.Default.Favorite,
                                    gradient = listOf(Color(0xFFFA233B), Color(0xFFFF5E3A)),
                                    onClick = {
                                        activeSubViewTitle = "我喜欢的音乐"
                                        activeSubViewSubtitle = "我的专属珍藏 · 共 ${favSongs.size} 首"
                                        activeSubViewSongs = favSongs
                                    }
                                )
                            }

                            // C. 最近播放 (Recent Card)
                            item {
                                val recentSongs = allSongs.take(30)
                                PlaylistSpecialCard(
                                    title = "最近播放",
                                    subtitle = "${recentSongs.size} 首歌曲",
                                    icon = Icons.Default.History,
                                    gradient = listOf(Color(0xFF5856D6), Color(0xFFAF52DE)),
                                    onClick = {
                                        activeSubViewTitle = "最近播放"
                                        activeSubViewSubtitle = "最近聆听足迹 · 共 ${recentSongs.size} 首"
                                        activeSubViewSongs = recentSongs
                                        isDownloadManagementMode = false
                                        selectedDownloadSongIds.clear()
                                    }
                                )
                            }

                            // D. 本地下载 (Downloaded Folder Card)
                            item {
                                PlaylistSpecialCard(
                                    title = "本地下载",
                                    subtitle = "${finalDownloadedSongs.size} 首歌曲",
                                    icon = Icons.Default.Folder,
                                    gradient = listOf(Color(0xFF007AFF), Color(0xFF5AC8FA)),
                                    onClick = {
                                        activeSubViewTitle = "本地下载"
                                        activeSubViewSubtitle = "本机离线歌曲 · 共 ${finalDownloadedSongs.size} 首"
                                        activeSubViewSongs = finalDownloadedSongs
                                        isDownloadManagementMode = false
                                        selectedDownloadSongIds.clear()
                                    }
                                )
                            }

                            // D. 自建与服务端歌单 (Custom & Server Playlists)
                            items(playlists, key = { it.id }) { pl ->
                                PlaylistCardItem(
                                    playlist = pl,
                                    onClick = {
                                        activeSubViewTitle = pl.name
                                        activeSubViewSubtitle = "${if (pl.isOnline) "云端歌单" else "本地歌单"} · ${pl.songCount} 首"
                                        if (onFetchPlaylistSongs != null) {
                                            isLoadingSubView = true
                                            coroutineScope.launch {
                                                activeSubViewSongs = onFetchPlaylistSongs(pl.id, pl.isOnline)
                                                isLoadingSubView = false
                                            }
                                        } else {
                                            activeSubViewSongs = allSongs.filter { it.album == pl.name }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // 3. 【最近添加】板块 (Recently Added Horizontal Flow)
                if (recentAddedSongs.isNotEmpty()) {
                    item {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "最近添加",
                                    fontSize = 19.sp * dimensions.fontScale,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "全部 ${recentAddedSongs.size} 首",
                                    fontSize = 12.sp,
                                    color = AppleRed,
                                    modifier = Modifier.clickable {
                                        activeSubViewTitle = "最近添加"
                                        activeSubViewSubtitle = "共 ${recentAddedSongs.size} 首曲目"
                                        activeSubViewSongs = recentAddedSongs
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(horizontal = 2.dp)
                            ) {
                                items(recentAddedSongs, key = { "recent_${it.id}" }) { song ->
                                    RecentAddedSongCard(
                                        song = song,
                                        onClick = { onSongClick(song, recentAddedSongs) }
                                    )
                                }
                            }
                        }
                    }
                }

                // 3.5 【本地文件夹】板块 (Local Folders Section)
                if (localFolders.isNotEmpty()) {
                    item {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "本地文件夹",
                                    fontSize = 19.sp * dimensions.fontScale,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "全部 ${localFolders.size} 个",
                                    fontSize = 12.sp,
                                    color = AppleRed,
                                    modifier = Modifier.clickable {
                                        activeSubViewTitle = "全部文件夹"
                                        activeSubViewSubtitle = "共 ${localFolders.size} 个本地文件夹"
                                        isFromAllFolders = false
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(horizontal = 2.dp)
                            ) {
                                items(localFolders.take(12), key = { it.id }) { folder ->
                                    FolderCardItem(
                                        folder = folder,
                                        onClick = {
                                            activeSubViewTitle = "文件夹 · ${folder.name}"
                                            activeSubViewSubtitle = "本地目录 · 共 ${folder.songCount} 首歌曲"
                                            activeSubViewSongs = folder.songs
                                            isFromAllFolders = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. 【音乐风格】板块 (Genres Flow)
                item {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "音乐风格",
                                fontSize = 19.sp * dimensions.fontScale,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        val displayGenres = remember(serverGenres, defaultGenres) {
                            if (serverGenres.isNotEmpty()) {
                                serverGenres.map { it.name to it.trackCount }
                            } else {
                                defaultGenres.map { it to 0 }
                            }
                        }

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp)
                        ) {
                            items(displayGenres, key = { it.first }) { (genreName, count) ->
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    border = BorderStroke(1.dp, borderColor),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable {
                                            activeSubViewTitle = "风格 · $genreName"
                                            activeSubViewSubtitle = if (count > 0) "共 $count 首歌曲" else "精选曲目流"
                                            activeSubViewSongs = allSongs.filter {
                                                it.title.contains(genreName, ignoreCase = true) ||
                                                it.artist.contains(genreName, ignoreCase = true) ||
                                                it.album.contains(genreName, ignoreCase = true) ||
                                                it.relativeFolderPath?.contains(genreName, ignoreCase = true) == true
                                            }.ifEmpty { allSongs.shuffled().take(20) }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (count > 0) "$genreName · $count" else genreName,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(12.dp),
                                            tint = AppleRed
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. 【歌手】板块 (Artists Row)
                if (artists.isNotEmpty()) {
                    item {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "歌手",
                                    fontSize = 19.sp * dimensions.fontScale,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "全部 ${artists.size} 位",
                                    fontSize = 12.sp,
                                    color = AppleRed,
                                    modifier = Modifier.clickable {
                                        activeSubViewTitle = "全部歌手"
                                        activeSubViewSubtitle = "共 ${artists.size} 位歌手"
                                        activeSubViewSongs = allSongs
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(horizontal = 2.dp)
                            ) {
                                items(artists.take(15), key = { it.id }) { artist ->
                                    Surface(
                                        shape = RoundedCornerShape(18.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                        border = BorderStroke(1.dp, borderColor),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(18.dp))
                                            .clickable {
                                                val artistSongs = allSongs.filter { it.artist == artist.name }
                                                activeSubViewTitle = artist.name
                                                activeSubViewSubtitle = "歌手专栏 · 共 ${artistSongs.size} 首歌曲"
                                                activeSubViewSongs = artistSongs
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            AlbumArtworkImage(
                                                model = artist.avatarUrl,
                                                seedId = artist.name,
                                                modifier = Modifier.size(32.dp),
                                                cornerRadius = 16.dp
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text = artist.name,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${artist.songCount} 首",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. 【最近添加专辑】板块 (Albums Row)
                if (albums.isNotEmpty()) {
                    item {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "最近添加专辑",
                                    fontSize = 19.sp * dimensions.fontScale,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "全部 ${albums.size} 张",
                                    fontSize = 12.sp,
                                    color = AppleRed,
                                    modifier = Modifier.clickable {
                                        activeSubViewTitle = "全部专辑"
                                        activeSubViewSubtitle = "共 ${albums.size} 张专辑"
                                        activeSubViewSongs = allSongs
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(horizontal = 2.dp)
                            ) {
                                items(albums.take(12), key = { it.id }) { album ->
                                    Column(
                                        modifier = Modifier
                                            .width(128.dp)
                                            .clickable {
                                                val albumSongs = allSongs.filter { it.album == album.title }
                                                activeSubViewTitle = album.title
                                                activeSubViewSubtitle = "${album.artist} · 共 ${albumSongs.size} 首"
                                                activeSubViewSongs = albumSongs
                                            }
                                    ) {
                                        AlbumArtworkImage(
                                            model = album.coverUrl,
                                            seedId = album.title,
                                            modifier = Modifier
                                                .size(128.dp)
                                                .shadow(2.dp, RoundedCornerShape(12.dp)),
                                            cornerRadius = 12.dp
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = album.title,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = album.artist,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 6. 【歌曲】板块 (Songs Header & Full List)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "全部歌曲",
                                fontSize = 19.sp * dimensions.fontScale,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${filteredSongs.size} 首",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        // 排序方式快捷切换
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = when (songSortMode) {
                                    "name" -> "按歌曲名"
                                    "artist" -> "按歌手"
                                    "duration" -> "按时长"
                                    "source" -> "按加入方式"
                                    else -> "默认排序"
                                },
                                fontSize = 12.sp,
                                color = AppleRed,
                                modifier = Modifier.clickable {
                                    songSortMode = when (songSortMode) {
                                        "default" -> "name"
                                        "name" -> "artist"
                                        "artist" -> "duration"
                                        "duration" -> "source"
                                        else -> "default"
                                    }
                                }
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Sort,
                                contentDescription = "排序",
                                tint = AppleRed,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                if (filteredSongs.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = surfaceColor,
                            border = BorderStroke(1.dp, borderColor),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MusicNote,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "音乐资料库暂无曲目",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "可通过上方「同步」拉取云端，或在「设置」中添加本地音乐目录",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    items(
                        items = filteredSongs,
                        key = { it.id },
                        contentType = { "library_song_item" }
                    ) { song ->
                        Column {
                            if (songSortMode == "source") {
                                val idx = filteredSongs.indexOf(song)
                                val prevSource = if (idx > 0) getSongSourceTypeName(filteredSongs[idx - 1]) else null
                                val currentSource = getSongSourceTypeName(song)
                                if (prevSource != currentSource) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                                    ) {
                                        Text(
                                            text = currentSource,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AppleRed,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            SongListItemRow(
                                song = song,
                                activeDownloadTasks = activeDownloadTasks,
                                isServerConnected = isServerOk,
                                onClick = { onSongClick(song, filteredSongs) },
                                onDownloadClick = { songForDownloadChoice = song },
                                onDownloadWithOptions = { s, target, quality ->
                                    onDownloadSongWithOptions(s, target, quality)
                                },
                                onOpenDownloads = onOpenDownloads
                            )
                        }
                    }
                }
            }
        }

        // 二级下钻详情视图 (页面内展示：歌单曲目、歌手曲目、专辑曲目，绝不遮挡底部播放栏)
        if (activeSubViewTitle != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp)
            ) {
                // 顶部返回与标题栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = handleSubViewBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeSubViewTitle ?: "",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = activeSubViewSubtitle,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 播放全部与管理按键
                    if (activeSubViewTitle == "全部歌单") {
                        Button(
                            onClick = { isCreatePlaylistDialogOpen = true },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("新建歌单", fontSize = 12.sp)
                        }
                    } else if (activeSubViewTitle == "本地下载" && activeSubViewSongs.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (!isDownloadManagementMode) {
                                OutlinedButton(
                                    onClick = { isDownloadManagementMode = true },
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    border = BorderStroke(1.dp, borderColor)
                                ) {
                                    Icon(Icons.Default.Checklist, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurface)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("管理", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                                }
                                Button(
                                    onClick = {
                                        activeSubViewSongs.firstOrNull()?.let { onSongClick(it, activeSubViewSongs) }
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("播放全部", fontSize = 12.sp)
                                }
                            } else {
                                TextButton(
                                    onClick = {
                                        isDownloadManagementMode = false
                                        selectedDownloadSongIds.clear()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("完成", fontSize = 13.sp, color = AppleRed, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else if (activeSubViewSongs.isNotEmpty()) {
                        Button(
                            onClick = {
                                activeSubViewSongs.firstOrNull()?.let { onSongClick(it, activeSubViewSongs) }
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("播放全部", fontSize = 12.sp)
                        }
                    }
                }

                if (isDownloadManagementMode && activeSubViewTitle == "本地下载") {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val isAllSelected = selectedDownloadSongIds.size == activeSubViewSongs.size && activeSubViewSongs.isNotEmpty()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable {
                                    if (isAllSelected) {
                                        selectedDownloadSongIds.clear()
                                    } else {
                                        selectedDownloadSongIds.clear()
                                        selectedDownloadSongIds.addAll(activeSubViewSongs.map { it.id })
                                    }
                                }
                            ) {
                                Checkbox(
                                    checked = isAllSelected,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            selectedDownloadSongIds.clear()
                                            selectedDownloadSongIds.addAll(activeSubViewSongs.map { it.id })
                                        } else {
                                            selectedDownloadSongIds.clear()
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = AppleRed)
                                )
                                Text(
                                    text = if (isAllSelected) "取消全选" else "全选",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "已选 ${selectedDownloadSongIds.size} 首",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Button(
                                onClick = { showBatchDeleteLocalDialog = true },
                                enabled = selectedDownloadSongIds.isNotEmpty(),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("删除 (${selectedDownloadSongIds.size})", fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                if (activeSubViewTitle == "全部歌单") {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 136.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            bottom = contentPadding.calculateBottomPadding() + 24.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // A. 新建歌单快捷卡片
                        item {
                            CreatePlaylistActionCard(
                                onClick = { isCreatePlaylistDialogOpen = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.85f)
                            )
                        }

                        // B. 我喜欢的音乐
                        item {
                            val favSongs = allSongs.filter { it.isFavorite }
                            PlaylistSpecialCard(
                                title = "我喜欢的音乐",
                                subtitle = "${favSongs.size} 首歌曲",
                                icon = Icons.Default.Favorite,
                                gradient = listOf(Color(0xFFFA233B), Color(0xFFFF5E3A)),
                                onClick = {
                                    isFromAllPlaylists = true
                                    activeSubViewTitle = "我喜欢的音乐"
                                    activeSubViewSubtitle = "我的专属珍藏 · 共 ${favSongs.size} 首"
                                    activeSubViewSongs = favSongs
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.85f)
                            )
                        }

                        // C. 最近播放
                        item {
                            val recentSongs = allSongs.take(30)
                            PlaylistSpecialCard(
                                title = "最近播放",
                                subtitle = "${recentSongs.size} 首歌曲",
                                icon = Icons.Default.History,
                                gradient = listOf(Color(0xFF5856D6), Color(0xFFAF52DE)),
                                onClick = {
                                    isFromAllPlaylists = true
                                    activeSubViewTitle = "最近播放"
                                    activeSubViewSubtitle = "最近聆听足迹 · 共 ${recentSongs.size} 首"
                                    activeSubViewSongs = recentSongs
                                    isDownloadManagementMode = false
                                    selectedDownloadSongIds.clear()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.85f)
                            )
                        }

                        // D. 本地下载
                        item {
                            PlaylistSpecialCard(
                                title = "本地下载",
                                subtitle = "${finalDownloadedSongs.size} 首歌曲",
                                icon = Icons.Default.Folder,
                                gradient = listOf(Color(0xFF007AFF), Color(0xFF5AC8FA)),
                                onClick = {
                                    isFromAllPlaylists = true
                                    activeSubViewTitle = "本地下载"
                                    activeSubViewSubtitle = "本机离线歌曲 · 共 ${finalDownloadedSongs.size} 首"
                                    activeSubViewSongs = finalDownloadedSongs
                                    isDownloadManagementMode = false
                                    selectedDownloadSongIds.clear()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.85f)
                            )
                        }

                        // E. 自建与服务端歌单
                        items(playlists, key = { it.id }) { pl ->
                            PlaylistCardItem(
                                playlist = pl,
                                onClick = {
                                    isFromAllPlaylists = true
                                    activeSubViewTitle = pl.name
                                    activeSubViewSubtitle = "${if (pl.isOnline) "云端歌单" else "本地歌单"} · ${pl.songCount} 首"
                                    if (onFetchPlaylistSongs != null) {
                                        isLoadingSubView = true
                                        coroutineScope.launch {
                                            activeSubViewSongs = onFetchPlaylistSongs(pl.id, pl.isOnline)
                                            isLoadingSubView = false
                                        }
                                    } else {
                                        activeSubViewSongs = allSongs.filter { it.album == pl.name }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                } else if (activeSubViewTitle == "全部文件夹") {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            bottom = contentPadding.calculateBottomPadding() + 24.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(localFolders, key = { it.id }) { folder ->
                            FolderCardItem(
                                folder = folder,
                                onClick = {
                                    isFromAllFolders = true
                                    activeSubViewTitle = "文件夹 · ${folder.name}"
                                    activeSubViewSubtitle = "本地目录 · 共 ${folder.songCount} 首歌曲"
                                    activeSubViewSongs = folder.songs
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                } else if (activeSubViewTitle == "全部歌手") {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            bottom = contentPadding.calculateBottomPadding() + 24.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(artists, key = { it.id }) { artist ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = BorderStroke(1.dp, borderColor),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        val artistSongs = allSongs.filter { it.artist == artist.name }
                                        isFromAllArtists = true
                                        activeSubViewTitle = artist.name
                                        activeSubViewSubtitle = "歌手专栏 · 共 ${artistSongs.size} 首歌曲"
                                        activeSubViewSongs = artistSongs
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AlbumArtworkImage(
                                        model = artist.avatarUrl,
                                        seedId = artist.name,
                                        modifier = Modifier.size(48.dp),
                                        cornerRadius = 24.dp
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = artist.name,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${artist.songCount} 首 · ${artist.albumCount} 张专辑",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (activeSubViewTitle == "全部专辑") {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 140.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            bottom = contentPadding.calculateBottomPadding() + 24.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(albums, key = { it.id }) { album ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        val albumSongs = allSongs.filter { it.album == album.title }
                                        isFromAllAlbums = true
                                        activeSubViewTitle = album.title
                                        activeSubViewSubtitle = "${album.artist} · 共 ${albumSongs.size} 首"
                                        activeSubViewSongs = albumSongs
                                    }
                            ) {
                                AlbumArtworkImage(
                                    model = album.coverUrl,
                                    seedId = album.title,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .shadow(2.dp, RoundedCornerShape(12.dp)),
                                    cornerRadius = 12.dp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = album.title,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${album.artist} · ${album.songCount} 首",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                } else if (isLoadingSubView) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 100.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), color = AppleRed)
                    }
                } else if (activeSubViewSongs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 100.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Text("暂无歌曲数据", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            bottom = contentPadding.calculateBottomPadding() + 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(
                            items = activeSubViewSongs,
                            key = { it.id },
                            contentType = { "subview_song_row" }
                        ) { song ->
                            if (isDownloadManagementMode && activeSubViewTitle == "本地下载") {
                                val isSelected = song.id in selectedDownloadSongIds
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) AppleRed.copy(alpha = 0.08f) else Color.Transparent,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (isSelected) selectedDownloadSongIds.remove(song.id) else selectedDownloadSongIds.add(song.id)
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { checked ->
                                                if (checked) selectedDownloadSongIds.add(song.id) else selectedDownloadSongIds.remove(song.id)
                                            },
                                            colors = CheckboxDefaults.colors(checkedColor = AppleRed)
                                        )
                                        Box(modifier = Modifier.weight(1f)) {
                                            SongListItemRow(
                                                song = song,
                                                activeDownloadTasks = activeDownloadTasks,
                                                isServerConnected = isServerOk,
                                                onClick = {
                                                    if (isSelected) selectedDownloadSongIds.remove(song.id) else selectedDownloadSongIds.add(song.id)
                                                },
                                                onDownloadClick = {},
                                                onDownloadWithOptions = { _, _, _ -> },
                                                onOpenDownloads = onOpenDownloads
                                            )
                                        }
                                    }
                                }
                            } else {
                                SongListItemRow(
                                    song = song,
                                    activeDownloadTasks = activeDownloadTasks,
                                    isServerConnected = isServerOk,
                                    onClick = { onSongClick(song, activeSubViewSongs) },
                                    onDownloadClick = { songForDownloadChoice = song },
                                    onDownloadWithOptions = { s, target, quality ->
                                        onDownloadSongWithOptions(s, target, quality)
                                    },
                                    onOpenDownloads = onOpenDownloads
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 确认删除本地下载歌曲弹窗
    if (showBatchDeleteLocalDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteLocalDialog = false },
            title = { Text("确认删除本地下载歌曲？", fontWeight = FontWeight.Bold) },
            text = {
                Text("您即将从设备中彻底删除选中的 ${selectedDownloadSongIds.size} 首已下载歌曲及伴随歌词文件。此操作将彻底释放手机空间，确定继续吗？")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBatchDeleteLocalDialog = false
                        val idsToDelete = selectedDownloadSongIds.toList()
                        coroutineScope.launch(Dispatchers.IO) {
                            val songsToDelete = idsToDelete.mapNotNull { id ->
                                activeSubViewSongs.find { it.id == id } ?: allSongs.find { it.id == id }
                            }
                            if (onDeleteDownloadedSongs != null && songsToDelete.isNotEmpty()) {
                                try {
                                    onDeleteDownloadedSongs.invoke(songsToDelete)
                                } catch (e: Exception) {
                                    Log.e("LocalLibraryScreen", "Failed to invoke onDeleteDownloadedSongs", e)
                                }
                            }
                            var deletedCount = 0
                            for (song in songsToDelete) {
                                val path = song.localFilePath
                                if (!path.isNullOrBlank()) {
                                    try {
                                        val f = File(path)
                                        if (f.exists()) f.delete()
                                        val lrc = File(f.parentFile, "${f.nameWithoutExtension}.lrc")
                                        if (lrc.exists()) lrc.delete()
                                        onDeleteLocalFilePath?.invoke(path)
                                        deletedCount++
                                    } catch (e: Exception) {
                                        Log.e("LocalLibraryScreen", "Failed to delete file $path", e)
                                    }
                                }
                            }
                            withContext(Dispatchers.Main) {
                                activeSubViewSongs = activeSubViewSongs.filter { it.id !in idsToDelete }
                                selectedDownloadSongIds.clear()
                                isDownloadManagementMode = false
                                val displayCount = if (deletedCount > 0) deletedCount else songsToDelete.size
                                Toast.makeText(context, "已成功删除 $displayCount 首歌曲并释放空间", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteLocalDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 全局下载音质与目标（服务器/本地）选择弹窗
    if (songForDownloadChoice != null) {
        DownloadQualityChoiceDialog(
            song = songForDownloadChoice!!,
            isServerConnected = isServerOk,
            onConfirm = { target, quality ->
                onDownloadSongWithOptions(songForDownloadChoice!!, target, quality)
            },
            onDismiss = { songForDownloadChoice = null }
        )
    }

    // 新建歌单弹窗
    if (isCreatePlaylistDialogOpen) {
        Dialog(onDismissRequest = { isCreatePlaylistDialogOpen = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isDark) Color(0xFF222228) else Color.White,
                border = BorderStroke(1.dp, borderColor),
                shadowElevation = 20.dp,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "新建歌单",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        label = { Text("歌单名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("同步至服务端", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                text = if (activeServerConfig != null) "与柠檬音乐/NAS曲库双向同步" else "未连接服务端，仅保存在本地",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = newPlaylistIsOnline && activeServerConfig != null,
                            onCheckedChange = { newPlaylistIsOnline = it },
                            enabled = activeServerConfig != null
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { isCreatePlaylistDialogOpen = false },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("取消")
                        }
                        Button(
                            onClick = {
                                if (newPlaylistName.isNotBlank()) {
                                    onCreatePlaylist(newPlaylistName.trim(), newPlaylistIsOnline && activeServerConfig != null)
                                    newPlaylistName = ""
                                    isCreatePlaylistDialogOpen = false
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("创建")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 本地文件夹卡片 (极简优雅，展示目录名与歌曲数量)
 */
@Composable
private fun FolderCardItem(
    folder: UnifiedFolder,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFFF9500), Color(0xFFFF5E3A)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = folder.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${folder.songCount} 首歌曲",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 特色歌单卡片 (我喜欢的音乐 / 最近播放)
 */
@Composable
private fun PlaylistSpecialCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    gradient: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
        .width(148.dp)
        .height(148.dp)
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shadowElevation = 4.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(gradient))
                .padding(14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .size(32.dp)
                    .align(Alignment.TopStart)
            )

            Column(modifier = Modifier.align(Alignment.BottomStart)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }
    }
}

/**
 * 标准自建/云端歌单卡片
 */
@Composable
private fun PlaylistCardItem(
    playlist: UnifiedPlaylist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(136.dp)
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
    ) {
        AlbumArtworkImage(
            model = playlist.coverUrl,
            seedId = playlist.id,
            modifier = Modifier
                .aspectRatio(1f)
                .fillMaxWidth()
                .shadow(3.dp, RoundedCornerShape(14.dp)),
            cornerRadius = 14.dp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = playlist.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (playlist.isOnline) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = AppleRed.copy(alpha = 0.12f),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Text(
                        text = "云端",
                        fontSize = 9.sp,
                        color = AppleRed,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            Text(
                text = if (playlist.songCount > 0) "${playlist.songCount} 首" else "歌单",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 歌单栏首位新建歌单快捷卡片
 */
@Composable
private fun CreatePlaylistActionCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
        .width(136.dp)
        .height(180.dp)
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.5.dp, AppleRed.copy(alpha = 0.45f)),
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(AppleRed.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "新建歌单",
                    tint = AppleRed,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "新建歌单",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "创建专属集合",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 最近添加歌曲卡片
 */
@Composable
private fun RecentAddedSongCard(
    song: UnifiedSong,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(124.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(124.dp)
                .shadow(2.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
        ) {
            AlbumArtworkImage(
                model = song.coverUrl,
                seedId = song.id,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 12.dp
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(AppleRed),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "播放",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = song.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = song.artist,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
