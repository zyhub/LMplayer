package com.lm.player.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.ServerSwitchDropdownButton
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.model.*
import kotlinx.coroutines.launch

/**
 * 柠檬音乐专属：现代轻奢发现主页 (Discover Home)
 * 包含：
 * 1. 顶部操作音源快捷切换 (酷我/网易/QQ/酷狗/咪咕) 与服务器下拉
 * 2. 热门推荐歌单横向画廊
 * 3. 官方权威榜单卡片
 * 4. 新歌首发曲目流 (即点即播 & 一键离线)
 * 5. 歌单与榜单曲目下钻浮层
 */
@Composable
fun LemonDiscoverHomeScreen(
    serverName: String,
    configuredServers: List<ServerConfig> = emptyList(),
    currentSource: OnlineMusicSource,
    onSourceChange: (OnlineMusicSource) -> Unit,
    blurAlpha: Float = 0.85f,
    activeDownloadTasks: List<DownloadTask> = emptyList(),
    activeDownloadCount: Int = 0,
    onSongClick: (UnifiedSong) -> Unit,
    onDownloadSong: (UnifiedSong) -> Unit,
    onSelectLocalServer: () -> Unit,
    onSelectServer: (ServerConfig) -> Unit,
    onSyncNow: () -> Unit,
    onOpenDownloads: () -> Unit = {},
    onGoToSettings: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onFetchDiscoverPlaylists: suspend (OnlineMusicSource) -> List<UnifiedPlaylist>,
    onFetchDiscoverToplists: suspend (OnlineMusicSource) -> List<LemonToplist>,
    onFetchDiscoverNewSongs: suspend (OnlineMusicSource) -> List<UnifiedSong>,
    onFetchCollectionSongs: suspend (id: String, source: OnlineMusicSource) -> List<UnifiedSong>,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val dimensions = LocalAppDimensions.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = blurAlpha)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.10f)
    val coroutineScope = rememberCoroutineScope()

    var recommendPlaylists by remember { mutableStateOf<List<UnifiedPlaylist>>(emptyList()) }
    var toplists by remember { mutableStateOf<List<LemonToplist>>(emptyList()) }
    var newSongs by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // 音源切换选择弹窗
    var isSourceSelectorOpen by remember { mutableStateOf(false) }

    // 歌单/榜单下钻曲目抽屉
    var activeCollectionTitle by remember { mutableStateOf<String?>(null) }
    var activeCollectionCover by remember { mutableStateOf("") }
    var activeCollectionSongs by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
    var isLoadingCollection by remember { mutableStateOf(false) }

    // 动态拉取发现页内容
    LaunchedEffect(currentSource) {
        isLoading = true
        try {
            recommendPlaylists = onFetchDiscoverPlaylists(currentSource)
            toplists = onFetchDiscoverToplists(currentSource)
            newSongs = onFetchDiscoverNewSongs(currentSource)
        } catch (_: Exception) {
        } finally {
            isLoading = false
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 1. 顶部 Header (发现标题 + 音源切换胶囊 + 搜索 + 下载 + 服务器切换)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "发现",
                        style = TextStyle(
                            fontSize = 32.sp * dimensions.fontScale,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    )
                    Text(
                        text = "在线云端曲库 · 柠檬音乐",
                        style = TextStyle(
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 音源切换胶囊按钮 (操作音源)
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = AppleRed.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, AppleRed.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { isSourceSelectorOpen = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "操作音源",
                                tint = AppleRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = currentSource.displayName.replace("音乐", "").replace("云", ""),
                                color = AppleRed,
                                fontSize = 12.sp * dimensions.fontScale,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = AppleRed,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // 搜索按钮
                    Surface(
                        shape = CircleShape,
                        color = surfaceColor,
                        shadowElevation = 2.dp,
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onSearchClick)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "全网搜索",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // 下载管理胶囊
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = surfaceColor,
                        shadowElevation = 2.dp,
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(onClick = onOpenDownloads)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (activeDownloadCount > 0) Icons.Default.FileDownload else Icons.Outlined.FileDownload,
                                contentDescription = "下载管理",
                                tint = if (activeDownloadCount > 0) AppleRed else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                            if (activeDownloadCount > 0) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (activeDownloadCount > 99) "99+" else "$activeDownloadCount",
                                    color = AppleRed,
                                    fontSize = 12.sp * dimensions.fontScale,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // 服务器切换下拉按键
                    ServerSwitchDropdownButton(
                        currentServer = serverName,
                        configuredServers = configuredServers,
                        blurAlpha = blurAlpha,
                        onSelectLocal = onSelectLocalServer,
                        onSelectServer = onSelectServer,
                        onSyncNow = onSyncNow,
                        onGoToSettings = onGoToSettings
                    )
                }
            }
        }

        // 2. 音源快捷切换选项行 (Chip Row)
        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                items(OnlineMusicSource.entries) { src ->
                    val isSelected = src == currentSource
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) AppleRed else surfaceColor,
                        border = BorderStroke(1.dp, if (isSelected) AppleRed else borderColor),
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSourceChange(src) }
                    ) {
                        Text(
                            text = src.displayName,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp * dimensions.fontScale,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }

        if (isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), color = AppleRed)
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "正在连接 ${currentSource.displayName} 发现曲库...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        } else {
            // A. 【热门推荐歌单】
            if (recommendPlaylists.isNotEmpty()) {
                item {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SectionHeader(
                                title = "热门推荐歌单",
                                subtitle = "来自 ${currentSource.displayName} 亿万乐友精选"
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(
                                items = recommendPlaylists,
                                key = { it.id },
                                contentType = { "discover_playlist" }
                            ) { playlist ->
                                DiscoverPlaylistCard(
                                    playlist = playlist,
                                    onClick = {
                                        activeCollectionTitle = playlist.name
                                        activeCollectionCover = playlist.coverUrl
                                        isLoadingCollection = true
                                        val plId = playlist.id
                                        coroutineScope.launch {
                                            activeCollectionSongs = onFetchCollectionSongs(plId, currentSource)
                                            isLoadingCollection = false
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // B. 【官方权威榜单】
            if (toplists.isNotEmpty()) {
                item {
                    Column {
                        SectionHeader(
                            title = "官方权威排行榜",
                            subtitle = "${currentSource.displayName} 潮流热播榜单"
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(
                                items = toplists,
                                key = { it.id },
                                contentType = { "discover_toplist" }
                            ) { toplist ->
                                DiscoverToplistCard(
                                    toplist = toplist,
                                    onClick = {
                                        activeCollectionTitle = toplist.name
                                        activeCollectionCover = toplist.coverUrl
                                        isLoadingCollection = true
                                        val tlId = "lemon_toplist_${toplist.source}_${toplist.id}"
                                        coroutineScope.launch {
                                            activeCollectionSongs = onFetchCollectionSongs(tlId, currentSource)
                                            isLoadingCollection = false
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // C. 【新歌首发】
            if (newSongs.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "新歌首发",
                        subtitle = "今日全网新单，即点即播"
                    )
                }

                items(
                    items = newSongs,
                    key = { it.id },
                    contentType = { "new_song_row" }
                ) { song ->
                    SongListItemRow(
                        song = song,
                        activeDownloadTasks = activeDownloadTasks,
                        onClick = { onSongClick(song) },
                        onDownloadClick = { onDownloadSong(song) },
                        onOpenDownloads = onOpenDownloads
                    )
                }
            }
        }
    }

    // 音源切换选择弹窗
    if (isSourceSelectorOpen) {
        Dialog(onDismissRequest = { isSourceSelectorOpen = false }) {
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "选择在线操作音源",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(onClick = { isSourceSelectorOpen = false }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "切换音源后，发现主页推荐、排行榜单及全网检索将立即无缝切换至对应平台源：",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    OnlineMusicSource.entries.forEach { src ->
                        val isSelected = src == currentSource
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) AppleRed.copy(alpha = 0.12f) else Color.Transparent,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) AppleRed else borderColor.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onSourceChange(src)
                                    isSourceSelectorOpen = false
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.GraphicEq,
                                        contentDescription = null,
                                        tint = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = src.displayName,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "当前选中",
                                        tint = AppleRed,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 歌单/榜单下钻曲目浮层
    if (activeCollectionTitle != null) {
        Dialog(
            onDismissRequest = { activeCollectionTitle = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    // 浮层顶部返回与标题
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { activeCollectionTitle = null }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeCollectionTitle ?: "",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${currentSource.displayName} · ${activeCollectionSongs.size} 首歌曲",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // 一键播放全部按键
                        if (activeCollectionSongs.isNotEmpty()) {
                            Button(
                                onClick = {
                                    activeCollectionSongs.firstOrNull()?.let { onSongClick(it) }
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

                    Spacer(modifier = Modifier.height(8.dp))

                    if (isLoadingCollection) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 100.dp),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(modifier = Modifier.size(36.dp), color = AppleRed)
                                Spacer(modifier = Modifier.height(14.dp))
                                Text("正在拉取榜单/歌单曲目...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            }
                        }
                    } else if (activeCollectionSongs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 100.dp),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Text("暂无曲目数据", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                items = activeCollectionSongs,
                                key = { it.id },
                                contentType = { "collection_song_row" }
                            ) { song ->
                                SongListItemRow(
                                    song = song,
                                    activeDownloadTasks = activeDownloadTasks,
                                    onClick = { onSongClick(song) },
                                    onDownloadClick = { onDownloadSong(song) },
                                    onOpenDownloads = onOpenDownloads
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 分区大标题与副标题组件
 */
@Composable
private fun SectionHeader(
    title: String,
    subtitle: String = ""
) {
    val dimensions = LocalAppDimensions.current
    Column {
        Text(
            text = title,
            style = TextStyle(
                fontSize = 20.sp * dimensions.fontScale,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        )
        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = TextStyle(
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

/**
 * 推荐歌单卡片
 */
@Composable
private fun DiscoverPlaylistCard(
    playlist: UnifiedPlaylist,
    onClick: () -> Unit
) {
    val dimensions = LocalAppDimensions.current
    Column(
        modifier = Modifier
            .width(136.dp)
            .clickable(onClick = onClick)
    ) {
        AlbumArtworkImage(
            model = playlist.coverUrl,
            seedId = playlist.id,
            modifier = Modifier
                .size(136.dp)
                .shadow(3.dp, RoundedCornerShape(14.dp)),
            cornerRadius = 14.dp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = playlist.name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = 13.sp * dimensions.fontScale,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 16.sp
            )
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = if (playlist.songCount > 0) "${playlist.songCount} 首" else "精选推荐",
            maxLines = 1,
            style = TextStyle(
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

/**
 * 权威排行榜卡片
 */
@Composable
private fun DiscoverToplistCard(
    toplist: LemonToplist,
    onClick: () -> Unit
) {
    val dimensions = LocalAppDimensions.current
    Column(
        modifier = Modifier
            .width(136.dp)
            .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.size(136.dp)) {
            AlbumArtworkImage(
                model = toplist.coverUrl,
                seedId = toplist.id,
                modifier = Modifier
                    .fillMaxSize()
                    .shadow(3.dp, RoundedCornerShape(14.dp)),
                cornerRadius = 14.dp
            )
            if (toplist.updateFrequency.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(bottomStart = 10.dp, topEnd = 14.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Text(
                        text = toplist.updateFrequency,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = toplist.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = 13.sp * dimensions.fontScale,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "官方排行榜",
            style = TextStyle(
                fontSize = 11.sp,
                color = AppleRed
            )
        )
    }
}
