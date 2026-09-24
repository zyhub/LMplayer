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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.lm.player.core.designsystem.component.DownloadQualityChoiceDialog
import com.lm.player.core.designsystem.component.ServerSwitchDropdownButton
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.media.SongMatchingResolver
import com.lm.player.core.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    allCachedSongs: List<UnifiedSong> = emptyList(),
    activeDownloadTasks: List<DownloadTask> = emptyList(),
    activeDownloadCount: Int = 0,
    onSongClick: (UnifiedSong, List<UnifiedSong>?) -> Unit = { song, _ -> },
    onDownloadSong: (UnifiedSong) -> Unit = {},
    onDownloadSongWithOptions: (UnifiedSong, DownloadTarget, AudioQuality) -> Unit = { song, _, _ -> onDownloadSong(song) },
    onSelectLocalServer: () -> Unit,
    onSelectServer: (ServerConfig) -> Unit,
    onSyncNow: () -> Unit,
    onOpenDownloads: () -> Unit = {},
    onGoToSettings: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onFetchDiscoverPlaylists: suspend (OnlineMusicSource) -> List<UnifiedPlaylist>,
    onFetchDiscoverToplists: suspend (OnlineMusicSource) -> List<LemonToplist>,
    onFetchDiscoverNewSongs: suspend (OnlineMusicSource) -> List<UnifiedSong>,
    onFetchDiscoverNewAlbums: (suspend (OnlineMusicSource) -> List<UnifiedAlbum>)? = null,
    onParseExternalPlaylist: (suspend (url: String, source: OnlineMusicSource) -> List<UnifiedSong>)? = null,
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
    var newAlbums by remember { mutableStateOf<List<UnifiedAlbum>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var reloadTrigger by remember { mutableStateOf(0) }

    // 音源切换选择弹窗
    var isSourceSelectorOpen by remember { mutableStateOf(false) }

    // 缓存下载音质与目标选择弹窗
    var songForDownloadChoice by remember { mutableStateOf<UnifiedSong?>(null) }

    // 歌单/榜单下钻曲目抽屉
    var activeCollectionTitle by remember { mutableStateOf<String?>(null) }
    var activeCollectionCover by remember { mutableStateOf("") }
    var activeCollectionSongs by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
    var isLoadingCollection by remember { mutableStateOf(false) }

    val isServerOk = configuredServers.any { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }

    // 结合本地缓存曲库与下载中任务动态解析匹配状态（全绿勾双端、半截绿勾单端）
    val resolvedNewSongs = remember(newSongs, allCachedSongs, activeDownloadTasks) {
        if (allCachedSongs.isEmpty()) newSongs
        else SongMatchingResolver.resolveSongList(newSongs, allCachedSongs, activeDownloadTasks)
    }

    val resolvedCollectionSongs = remember(activeCollectionSongs, allCachedSongs, activeDownloadTasks, activeCollectionCover) {
        val songsWithCover = activeCollectionSongs.map { s ->
            if (s.coverUrl.isNullOrBlank() && !activeCollectionCover.isNullOrBlank()) {
                s.copy(coverUrl = activeCollectionCover)
            } else {
                s
            }
        }
        if (allCachedSongs.isEmpty()) songsWithCover
        else SongMatchingResolver.resolveSongList(songsWithCover, allCachedSongs, activeDownloadTasks)
    }

    // 动态拉取发现页内容：分块独立异步加载与流式渐进呈现，大幅提速首页载入
    LaunchedEffect(currentSource, reloadTrigger) {
        if (recommendPlaylists.isEmpty() && toplists.isEmpty() && newSongs.isEmpty() && newAlbums.isEmpty()) {
            isLoading = true
        }
        coroutineScope {
            launch {
                val pl = runCatching { onFetchDiscoverPlaylists(currentSource) }.getOrDefault(emptyList())
                if (pl.isNotEmpty()) {
                    recommendPlaylists = pl
                    isLoading = false
                }
            }
            launch {
                val tl = runCatching { onFetchDiscoverToplists(currentSource) }.getOrDefault(emptyList())
                if (tl.isNotEmpty()) {
                    toplists = tl
                    isLoading = false
                }
            }
            launch {
                val ns = runCatching { onFetchDiscoverNewSongs(currentSource) }.getOrDefault(emptyList())
                if (ns.isNotEmpty()) {
                    newSongs = ns
                    isLoading = false
                }
            }
            if (onFetchDiscoverNewAlbums != null) {
                launch {
                    val na = runCatching { onFetchDiscoverNewAlbums(currentSource) }.getOrDefault(emptyList())
                    if (na.isNotEmpty()) {
                        newAlbums = na
                        isLoading = false
                    }
                }
            }
        }
        isLoading = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (activeCollectionTitle == null) {
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
                    // 音源切换胶囊按钮 (操作音源 - 仿音频输出与共享展开风格)
                    Box {
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

                        OnlineSourceDropdownMenu(
                            expanded = isSourceSelectorOpen,
                            onDismissRequest = { isSourceSelectorOpen = false },
                            currentSource = currentSource,
                            onSourceSelect = { src ->
                                onSourceChange(src)
                                isSourceSelectorOpen = false
                            }
                        )
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

            // C. 【新碟首发】 (New Albums)
            if (newAlbums.isNotEmpty()) {
                item {
                    Column {
                        SectionHeader(
                            title = "新碟首发",
                            subtitle = "${currentSource.displayName} 最新潮流专辑"
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(
                                items = newAlbums,
                                key = { it.id },
                                contentType = { "discover_album" }
                            ) { album ->
                                DiscoverAlbumCard(
                                    album = album,
                                    onClick = {
                                        activeCollectionTitle = album.title
                                        activeCollectionCover = album.coverUrl
                                        isLoadingCollection = true
                                        coroutineScope.launch {
                                            activeCollectionSongs = onFetchCollectionSongs(album.id, currentSource)
                                            isLoadingCollection = false
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // D. 【新歌首发】
            if (resolvedNewSongs.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "新歌首发",
                        subtitle = "今日全网新单，即点即播"
                    )
                }

                items(
                    items = resolvedNewSongs,
                    key = { it.id },
                    contentType = { "new_song_row" }
                ) { song ->
                    SongListItemRow(
                        song = song,
                        activeDownloadTasks = activeDownloadTasks,
                        isServerConnected = isServerOk,
                        onClick = { onSongClick(song, resolvedNewSongs) },
                        onDownloadClick = { songForDownloadChoice = song },
                        onDownloadWithOptions = { s, target, quality ->
                            onDownloadSongWithOptions(s, target, quality)
                        },
                        onOpenDownloads = onOpenDownloads
                    )
                }
            }

            // D. 当各版块均未拉取到内容时的友好空状态与重试引导
            if (recommendPlaylists.isEmpty() && toplists.isEmpty() && newSongs.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = surfaceColor,
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MusicOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "暂未载入发现内容",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "当前音源（${currentSource.displayName}）暂无推荐或服务端音源脚本未就绪",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { reloadTrigger++ },
                                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("刷新重试", fontSize = 13.sp)
                                }
                                OutlinedButton(
                                    onClick = onGoToSettings,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("音源设置", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}



    // 歌单/榜单下钻曲目视图 (页面内全屏呈现，不遮挡底部全局悬浮播放栏)
    if (activeCollectionTitle != null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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

                // 一键播放全部按键 (并将当前歌单全部曲目同步注入播放队列)
                if (resolvedCollectionSongs.isNotEmpty()) {
                    Button(
                        onClick = {
                            resolvedCollectionSongs.firstOrNull()?.let { onSongClick(it, resolvedCollectionSongs) }
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
            } else if (resolvedCollectionSongs.isEmpty()) {
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
                    contentPadding = PaddingValues(
                        bottom = contentPadding.calculateBottomPadding() + 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        items = resolvedCollectionSongs,
                        key = { it.id },
                        contentType = { "collection_song_row" }
                    ) { song ->
                        SongListItemRow(
                            song = song,
                            activeDownloadTasks = activeDownloadTasks,
                            isServerConnected = isServerOk,
                            onClick = { onSongClick(song, resolvedCollectionSongs) },
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

/**
 * 新碟首发专辑卡片
 */
@Composable
private fun DiscoverAlbumCard(
    album: UnifiedAlbum,
    onClick: () -> Unit
) {
    val dimensions = LocalAppDimensions.current
    Column(
        modifier = Modifier
            .width(136.dp)
            .clickable(onClick = onClick)
    ) {
        AlbumArtworkImage(
            model = album.coverUrl,
            seedId = album.id,
            modifier = Modifier
                .size(136.dp)
                .shadow(3.dp, RoundedCornerShape(14.dp)),
            cornerRadius = 14.dp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = album.title,
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
            text = album.artist.ifBlank { "最新专辑" },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

/**
 * 仿音频输出与共享展出方式的在线音源平台切换下拉菜单
 */
@Composable
fun OnlineSourceDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    currentSource: OnlineMusicSource,
    onSourceSelect: (OnlineMusicSource) -> Unit,
    modifier: Modifier = Modifier
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 250.dp, max = 310.dp)
    ) {
        Text(
            text = "在线音源平台",
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
        Text(
            text = "发现主页推荐、榜单及全网检索切换源",
            style = TextStyle(
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            ),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        OnlineMusicSource.entries.forEach { src ->
            val isSelected = src == currentSource
            val (iconColor, desc) = when (src) {
                OnlineMusicSource.KUWO -> Pair(Color(0xFFFF9500), "酷我无损与车载特色源")
                OnlineMusicSource.NETEASE -> Pair(Color(0xFFE53935), "网易云海量歌单与热评曲库")
                OnlineMusicSource.QQ -> Pair(Color(0xFF34C759), "QQ音乐海量正版与流行金曲")
                OnlineMusicSource.KUGOU -> Pair(Color(0xFF007AFF), "酷狗流行榜单与热门歌曲")
                OnlineMusicSource.MIGU -> Pair(Color(0xFFFF2D55), "咪咕高保真正版无损源")
            }

            DropdownMenuItem(
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = if (isSelected) AppleRed else iconColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = src.displayName,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = desc,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "当前选中",
                                tint = AppleRed,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                onClick = {
                    onSourceSelect(src)
                },
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        }
    }
}

