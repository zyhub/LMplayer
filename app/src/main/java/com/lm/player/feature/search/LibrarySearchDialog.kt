package com.lm.player.feature.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.DownloadQualityChoiceDialog
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.media.SongMatchingResolver
import com.lm.player.core.model.AudioQuality
import com.lm.player.core.model.DownloadTarget
import com.lm.player.core.model.DownloadTask
import com.lm.player.core.model.OnlineMusicSource
import com.lm.player.core.model.SearchContentType
import com.lm.player.core.model.UnifiedAlbum
import com.lm.player.core.model.UnifiedPlaylist
import com.lm.player.core.model.UnifiedSong
import com.lm.player.feature.home.SongListItemRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 在线搜索结果每页条数（手机端）。
 * 手机竖屏一屏约能容纳 8~10 行，取 20 是为了"一页够看、翻页不至于太碎"；
 * TV 端大屏遥控器逐行移动更慢，那边用的是更小的页容量，见 LMPlayerTV 的同名文件。
 */
private const val ONLINE_SEARCH_PAGE_SIZE = 20

/**
 * 搜索结果面板 (已移除空状态下的「弹出式全局搜索」提示框)
 * - 当未输入关键词时，仅保留底栏左滑搜索框，不遮挡主界面
 * - 当输入关键词后，展示歌曲 / 专辑 / 歌单检索结果，支持点击专辑或歌单直接展开内部曲目播放与下载
 */
@Composable
fun LibrarySearchDialog(
    allSongs: List<UnifiedSong>,
    allAlbums: List<UnifiedAlbum> = emptyList(),
    allPlaylists: List<UnifiedPlaylist> = emptyList(),
    activeDownloadTasks: List<DownloadTask> = emptyList(),
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    onSongClick: (UnifiedSong, List<UnifiedSong>?) -> Unit = { _, _ -> },
    onDownloadSong: (UnifiedSong) -> Unit = {},
    onDownloadSongWithOptions: (UnifiedSong, DownloadTarget, AudioQuality) -> Unit = { song, _, _ -> onDownloadSong(song) },
    initialOnlineSource: OnlineMusicSource = OnlineMusicSource.KUWO,
    onOnlineSourceChanged: ((OnlineMusicSource) -> Unit)? = null,
    selectedSearchType: SearchContentType = SearchContentType.SONG,
    onSearchTypeChanged: ((SearchContentType) -> Unit)? = null,
    // page/limit 透传给服务端 /api/search，实现搜索结果翻页；每页条数由调用方按客户端注入
    onOnlineSearch: (suspend (keyword: String, source: OnlineMusicSource, page: Int, limit: Int) -> List<UnifiedSong>)? = null,    onOnlineSearchAlbums: (suspend (keyword: String, source: OnlineMusicSource) -> List<UnifiedAlbum>)? = null,
    onOnlineSearchPlaylists: (suspend (keyword: String, source: OnlineMusicSource) -> List<UnifiedPlaylist>)? = null,
    onFetchCollectionSongs: (suspend (collectionId: String) -> List<UnifiedSong>)? = null,
    onParseExternalPlaylist: (suspend (url: String, source: OnlineMusicSource) -> List<UnifiedSong>)? = null,
    isServerConnected: Boolean = true,
    hasMiniPlayer: Boolean = true,
    currentPlayingSong: UnifiedSong? = null,
    isPlaying: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onDismiss: () -> Unit = {}
) {
    // 专辑/歌单二级详情钻取状态
    var drillDownTitle by remember { mutableStateOf<String?>(null) }
    var drillDownSubtitle by remember { mutableStateOf<String?>(null) }
    var drillDownSongs by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
    var isLoadingDrillDown by remember { mutableStateOf(false) }

    BackHandler {
        if (drillDownTitle != null) {
            drillDownTitle = null
            drillDownSubtitle = null
            drillDownSongs = emptyList()
        } else {
            onDismiss()
        }
    }

    // 切换搜索类型或清空关键词时，自动退出二级详情
    LaunchedEffect(query, selectedSearchType) {
        if (query.isBlank()) {
            drillDownTitle = null
            drillDownSubtitle = null
            drillDownSongs = emptyList()
        }
    }

    // 未输入关键词且未处于二级详情时，不显示任何「弹出式全局搜索」浮框，仅保留底部左滑搜索框
    if (query.isBlank() && drillDownTitle == null) {
        return
    }

    val dimensions = LocalAppDimensions.current
    var selectedSource by remember(initialOnlineSource) { mutableStateOf(initialOnlineSource) }
    var onlineSongResults by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
    var onlineAlbumResults by remember { mutableStateOf<List<UnifiedAlbum>>(emptyList()) }
    var onlinePlaylistResults by remember { mutableStateOf<List<UnifiedPlaylist>>(emptyList()) }
    var isSearchingOnline by remember { mutableStateOf(false) }
    var songForDownloadChoice by remember { mutableStateOf<UnifiedSong?>(null) }
    // 在线结果翻页：服务端 /api/search 原生支持 page/limit，这里只维护当前页码。
    // hasMore 用「本页返回条数是否已满」推断，因为服务端响应里没有 total/pages 字段。
    var onlinePage by remember { mutableStateOf(1) }
    var onlinePageHasMore by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // 关键词 / 音源 / 搜索类型一变就回到第 1 页。
    // 放在下面取数 effect 之前：两者同帧重启时，本 effect 先把页码归位，
    // 取数 effect 因页码变化被取消重启，280ms 防抖期内不会真的发出多余请求。
    LaunchedEffect(query, selectedSource, selectedSearchType) {
        if (onlinePage != 1) onlinePage = 1
    }

    LaunchedEffect(query, selectedSource, selectedSearchType, onlinePage) {
        if (query.isBlank()) {
            onlineSongResults = emptyList()
            onlineAlbumResults = emptyList()
            onlinePlaylistResults = emptyList()
            onlinePageHasMore = false
            isSearchingOnline = false
            return@LaunchedEffect
        }
        val trimmed = query.trim()
        if (onParseExternalPlaylist != null && (trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
            isSearchingOnline = true
            try {
                onlineSongResults = onParseExternalPlaylist(trimmed, selectedSource)
                onlinePageHasMore = false
            } catch (_: Exception) {
                onlineSongResults = emptyList()
            } finally {
                isSearchingOnline = false
            }
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(280)
        isSearchingOnline = true
        try {
            when (selectedSearchType) {
                SearchContentType.SONG -> {
                    val fetched = onOnlineSearch?.invoke(trimmed, selectedSource, onlinePage, ONLINE_SEARCH_PAGE_SIZE).orEmpty()
                    onlineSongResults = fetched
                    onlinePageHasMore = fetched.size >= ONLINE_SEARCH_PAGE_SIZE
                }
                SearchContentType.ALBUM -> {
                    onlineAlbumResults = onOnlineSearchAlbums?.invoke(trimmed, selectedSource).orEmpty()
                }
                SearchContentType.PLAYLIST -> {
                    onlinePlaylistResults = onOnlineSearchPlaylists?.invoke(trimmed, selectedSource).orEmpty()
                }
            }
        } catch (_: Exception) {
            when (selectedSearchType) {
                SearchContentType.SONG -> onlineSongResults = emptyList()
                SearchContentType.ALBUM -> onlineAlbumResults = emptyList()
                SearchContentType.PLAYLIST -> onlinePlaylistResults = emptyList()
            }
        } finally {
            isSearchingOnline = false
        }
    }

    // 后台线程执行在线搜索歌曲与本地/服务器曲库智能比对，消除主线程卡顿
    val resolvedOnlineSongs by produceState(initialValue = onlineSongResults, onlineSongResults, allSongs, activeDownloadTasks) {
        value = if (allSongs.isEmpty() || onlineSongResults.isEmpty()) {
            onlineSongResults
        } else {
            withContext(Dispatchers.Default) {
                SongMatchingResolver.resolveSongList(onlineSongResults, allSongs, activeDownloadTasks)
            }
        }
    }

    val resolvedDrillDownSongs by produceState(initialValue = drillDownSongs, drillDownSongs, allSongs, activeDownloadTasks) {
        value = if (allSongs.isEmpty() || drillDownSongs.isEmpty()) {
            drillDownSongs
        } else {
            withContext(Dispatchers.Default) {
                SongMatchingResolver.resolveSongList(drillDownSongs, allSongs, activeDownloadTasks)
            }
        }
    }

    // 本地/服务端已同步媒体库过滤结果 (后台计算)
    val localSongResults by produceState(initialValue = emptyList<UnifiedSong>(), query, allSongs) {
        val q = query.trim().lowercase()
        value = if (q.isEmpty()) emptyList() else withContext(Dispatchers.Default) {
            allSongs.filter {
                it.title.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q) ||
                    it.album.lowercase().contains(q)
            }
        }
    }

    val localAlbumResults by produceState(initialValue = emptyList<UnifiedAlbum>(), query, allAlbums, allSongs) {
        val q = query.trim().lowercase()
        value = if (q.isEmpty()) emptyList() else withContext(Dispatchers.Default) {
            if (allAlbums.isNotEmpty()) {
                allAlbums.filter {
                    it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
                }
            } else {
                allSongs.asSequence()
                    .filter { it.album.isNotBlank() && (it.album.lowercase().contains(q) || it.artist.lowercase().contains(q)) }
                    .groupBy { it.album.trim() }
                    .map { (albumName, tracks) ->
                        val first = tracks.first()
                        UnifiedAlbum(
                            id = first.albumId.ifBlank { "local_album_${albumName.hashCode()}" },
                            title = albumName,
                            artist = first.artist,
                            coverUrl = first.coverUrl,
                            songCount = tracks.size
                        )
                    }
                    .toList()
            }
        }
    }

    val localPlaylistResults by produceState(initialValue = emptyList<UnifiedPlaylist>(), query, allPlaylists) {
        val q = query.trim().lowercase()
        value = if (q.isEmpty()) emptyList() else withContext(Dispatchers.Default) {
            allPlaylists.filter { it.name.lowercase().contains(q) }
        }
    }

    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val cardBgColor = if (isDark) Color(0xFF1C1C22).copy(alpha = 0.96f) else Color(0xFFFAFAFC).copy(alpha = 0.97f)
    val cardBorderColor = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)
    val bottomSafeReservation = if (hasMiniPlayer) 142.dp else 78.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .background(Color.Black.copy(alpha = if (isDark) 0.40f else 0.22f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(
                start = 14.dp,
                end = 14.dp,
                top = 12.dp,
                bottom = bottomSafeReservation
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 580.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .shadow(18.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {} // 拦截内部点击，防止穿透关闭
                ),
            shape = RoundedCornerShape(24.dp),
            color = cardBgColor,
            border = BorderStroke(1.dp, cardBorderColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                if (drillDownTitle != null) {
                    // =========================================================================
                    // 二级视图：专辑 / 歌单 内部歌曲列表
                    // =========================================================================
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    drillDownTitle = null
                                    drillDownSubtitle = null
                                    drillDownSongs = emptyList()
                                }
                                .padding(vertical = 4.dp, horizontal = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回搜索结果",
                                tint = AppleRed,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = drillDownTitle.orEmpty(),
                                    fontSize = dimensions.itemTitleSize,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!drillDownSubtitle.isNullOrBlank()) {
                                    Text(
                                        text = drillDownSubtitle.orEmpty(),
                                        fontSize = dimensions.captionSize,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        if (resolvedDrillDownSongs.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = AppleRed,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        onSongClick(resolvedDrillDownSongs.first(), resolvedDrillDownSongs)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "播放全部",
                                        tint = Color.White,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "播放全部 (${resolvedDrillDownSongs.size})",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (isLoadingDrillDown) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.dp, color = AppleRed)
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "正在从柠檬音乐服务器加载曲目...",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = dimensions.bodySize
                                )
                            }
                        }
                    } else if (resolvedDrillDownSongs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "暂未获取到曲目列表，请尝试切换其他音源",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = dimensions.bodySize
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = resolvedDrillDownSongs,
                                key = { "drill_${it.id}" },
                                contentType = { "drill_song_item" }
                            ) { song ->
                                SongListItemRow(
                                    song = song,
                                    isServerConnected = isServerConnected,
                                    currentPlayingSong = currentPlayingSong,
                                    isPlaying = isPlaying,
                                    onClick = { onSongClick(song, resolvedDrillDownSongs) },
                                    onDownloadClick = { songForDownloadChoice = song },
                                    onDownloadWithOptions = { s, target, quality ->
                                        onDownloadSongWithOptions(s, target, quality)
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // =========================================================================
                    // 一级视图：根据 selectedSearchType 展示 [ 歌曲 / 专辑 / 歌单 ] 搜索结果
                    // =========================================================================
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = when (selectedSearchType) {
                                    SearchContentType.SONG -> Icons.Default.MusicNote
                                    SearchContentType.ALBUM -> Icons.Default.Album
                                    SearchContentType.PLAYLIST -> Icons.Default.QueueMusic
                                },
                                contentDescription = null,
                                tint = AppleRed,
                                modifier = Modifier.size(19.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "「$query」的${selectedSearchType.displayName}结果",
                                fontSize = dimensions.itemTitleSize,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (isSearchingOnline) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 1.8.dp,
                                color = AppleRed
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val isUrlQuery = query.trim().startsWith("http://") || query.trim().startsWith("https://")
                    val effectiveType = if (isUrlQuery) SearchContentType.SONG else selectedSearchType

                    when (effectiveType) {
                        SearchContentType.SONG -> {
                            if (localSongResults.isEmpty() && resolvedOnlineSongs.isEmpty()) {
                                SearchEmptyOrLoadingBox(
                                    isSearchingOnline = isSearchingOnline,
                                    emptyText = "未找到与「$query」匹配的歌曲或歌手"
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (localSongResults.isNotEmpty()) {
                                        item {
                                            Text(
                                                text = "媒体库歌曲 (${localSongResults.size})",
                                                fontSize = dimensions.bodySize,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(vertical = 4.dp)
                                            )
                                        }
                                        items(
                                            items = localSongResults,
                                            key = { "local_${it.id}" },
                                            contentType = { "search_song_item" }
                                        ) { song ->
                                            SongListItemRow(
                                                song = song,
                                                isServerConnected = isServerConnected,
                                                currentPlayingSong = currentPlayingSong,
                                                isPlaying = isPlaying,
                                                onClick = { onSongClick(song, localSongResults) },
                                                onDownloadClick = { songForDownloadChoice = song },
                                                onDownloadWithOptions = { s, target, quality ->
                                                    onDownloadSongWithOptions(s, target, quality)
                                                }
                                            )
                                        }
                                    }

                                    if (resolvedOnlineSongs.isNotEmpty()) {
                                        item {
                                            Text(
                                                text = "柠檬在线 · ${selectedSource.displayName} (${resolvedOnlineSongs.size})",
                                                fontSize = dimensions.bodySize,
                                                fontWeight = FontWeight.Bold,
                                                color = AppleRed,
                                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                            )
                                        }
                                        items(
                                            items = resolvedOnlineSongs,
                                            key = { "online_${it.id}" },
                                            contentType = { "search_online_song_item" }
                                        ) { song ->
                                            SongListItemRow(
                                                song = song,
                                                isServerConnected = isServerConnected,
                                                currentPlayingSong = currentPlayingSong,
                                                isPlaying = isPlaying,
                                                onClick = { onSongClick(song, resolvedOnlineSongs) },
                                                onDownloadClick = { songForDownloadChoice = song },
                                                onDownloadWithOptions = { s, target, quality ->
                                                    onDownloadSongWithOptions(s, target, quality)
                                                }
                                            )
                                        }
                                        // 翻页条：每页 ONLINE_SEARCH_PAGE_SIZE 首，由服务端按 page 取。
                                        // 只在「不在第一页」或「还有下一页」时出现，空结果不会挂一个没用的翻页条。
                                        if (onlinePage > 1 || onlinePageHasMore) {
                                            item(key = "online_pager") {
                                                SearchPagerBar(
                                                    page = onlinePage,
                                                    hasMore = onlinePageHasMore,
                                                    isLoading = isSearchingOnline,
                                                    accent = AppleRed,
                                                    onPrev = { if (onlinePage > 1) onlinePage -= 1 },
                                                    onNext = { if (onlinePageHasMore) onlinePage += 1 }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        SearchContentType.ALBUM -> {
                            if (localAlbumResults.isEmpty() && onlineAlbumResults.isEmpty()) {
                                SearchEmptyOrLoadingBox(
                                    isSearchingOnline = isSearchingOnline,
                                    emptyText = "未找到与「$query」匹配的专辑"
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (localAlbumResults.isNotEmpty()) {
                                        item {
                                            Text(
                                                text = "媒体库专辑 (${localAlbumResults.size})",
                                                fontSize = dimensions.bodySize,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(vertical = 4.dp)
                                            )
                                        }
                                        items(
                                            items = localAlbumResults,
                                            key = { "local_album_${it.id}_${it.title}" },
                                            contentType = { "search_album_item" }
                                        ) { album ->
                                            SearchAlbumListItem(
                                                album = album,
                                                onClick = {
                                                    drillDownTitle = album.title
                                                    drillDownSubtitle = album.artist
                                                    drillDownSongs = allSongs.filter {
                                                        it.albumId == album.id || it.album.equals(album.title, ignoreCase = true)
                                                    }
                                                }
                                            )
                                        }
                                    }

                                    if (onlineAlbumResults.isNotEmpty()) {
                                        item {
                                            Text(
                                                text = "柠檬在线专辑 · ${selectedSource.displayName} (${onlineAlbumResults.size})",
                                                fontSize = dimensions.bodySize,
                                                fontWeight = FontWeight.Bold,
                                                color = AppleRed,
                                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                            )
                                        }
                                        items(
                                            items = onlineAlbumResults,
                                            key = { "online_album_${it.id}" },
                                            contentType = { "search_online_album_item" }
                                        ) { album ->
                                            SearchAlbumListItem(
                                                album = album,
                                                onClick = {
                                                    drillDownTitle = album.title
                                                    drillDownSubtitle = album.artist
                                                    isLoadingDrillDown = true
                                                    drillDownSongs = emptyList()
                                                    kotlinx.coroutines.MainScope().let { _ -> }
                                                    coroutineScope.launch {
                                                        try {
                                                            drillDownSongs = onFetchCollectionSongs?.invoke(album.id).orEmpty()
                                                        } catch (_: Exception) {
                                                            drillDownSongs = emptyList()
                                                        } finally {
                                                            isLoadingDrillDown = false
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        SearchContentType.PLAYLIST -> {
                            if (localPlaylistResults.isEmpty() && onlinePlaylistResults.isEmpty()) {
                                SearchEmptyOrLoadingBox(
                                    isSearchingOnline = isSearchingOnline,
                                    emptyText = "未找到与「$query」匹配的歌单"
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (localPlaylistResults.isNotEmpty()) {
                                        item {
                                            Text(
                                                text = "媒体库歌单 (${localPlaylistResults.size})",
                                                fontSize = dimensions.bodySize,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(vertical = 4.dp)
                                            )
                                        }
                                        items(
                                            items = localPlaylistResults,
                                            key = { "local_pl_${it.id}" },
                                            contentType = { "search_playlist_item" }
                                        ) { pl ->
                                            SearchPlaylistListItem(
                                                playlist = pl,
                                                onClick = {
                                                    drillDownTitle = pl.name
                                                    drillDownSubtitle = "${pl.songCount} 首歌曲"
                                                    isLoadingDrillDown = true
                                                    drillDownSongs = emptyList()
                                                    coroutineScope.launch {
                                                        try {
                                                            drillDownSongs = onFetchCollectionSongs?.invoke(pl.id).orEmpty()
                                                        } catch (_: Exception) {
                                                            drillDownSongs = emptyList()
                                                        } finally {
                                                            isLoadingDrillDown = false
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }

                                    if (onlinePlaylistResults.isNotEmpty()) {
                                        item {
                                            Text(
                                                text = "柠檬在线歌单 · ${selectedSource.displayName} (${onlinePlaylistResults.size})",
                                                fontSize = dimensions.bodySize,
                                                fontWeight = FontWeight.Bold,
                                                color = AppleRed,
                                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                            )
                                        }
                                        items(
                                            items = onlinePlaylistResults,
                                            key = { "online_pl_${it.id}" },
                                            contentType = { "search_online_playlist_item" }
                                        ) { pl ->
                                            SearchPlaylistListItem(
                                                playlist = pl,
                                                onClick = {
                                                    drillDownTitle = pl.name
                                                    drillDownSubtitle = if (pl.songCount > 0) "${pl.songCount} 首歌曲" else selectedSource.displayName
                                                    isLoadingDrillDown = true
                                                    drillDownSongs = emptyList()
                                                    coroutineScope.launch {
                                                        try {
                                                            drillDownSongs = onFetchCollectionSongs?.invoke(pl.id).orEmpty()
                                                        } catch (_: Exception) {
                                                            drillDownSongs = emptyList()
                                                        } finally {
                                                            isLoadingDrillDown = false
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (songForDownloadChoice != null) {
                    DownloadQualityChoiceDialog(
                        song = songForDownloadChoice!!,
                        isServerConnected = isServerConnected,
                        onDismiss = { songForDownloadChoice = null },
                        onConfirm = { target, quality ->
                            onDownloadSongWithOptions(songForDownloadChoice!!, target, quality)
                            songForDownloadChoice = null
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchEmptyOrLoadingBox(
    isSearchingOnline: Boolean,
    emptyText: String
) {
    val dimensions = LocalAppDimensions.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 64.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        if (isSearchingOnline) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.dp, color = AppleRed)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "正在从柠檬音乐服务器检索...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = dimensions.bodySize
                )
            }
        } else {
            Text(
                text = emptyText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = dimensions.itemTitleSize
            )
        }
    }
}

@Composable
private fun SearchAlbumListItem(
    album: UnifiedAlbum,
    onClick: () -> Unit
) {
    val dimensions = LocalAppDimensions.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArtworkImage(
            model = album.coverUrl,
            seedId = album.id,
            modifier = Modifier.size(48.dp),
            cornerRadius = 10.dp
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.title,
                fontSize = dimensions.bodySize,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            val metaParts = buildList {
                if (album.artist.isNotBlank()) add(album.artist)
                if (album.songCount > 0) add("${album.songCount} 首")
                if (album.year != null && album.year > 0) add("${album.year}")
            }
            Text(
                text = metaParts.joinToString(" · "),
                fontSize = dimensions.captionSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = "查看专辑曲目",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SearchPlaylistListItem(
    playlist: UnifiedPlaylist,
    onClick: () -> Unit
) {
    val dimensions = LocalAppDimensions.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArtworkImage(
            model = playlist.coverUrl,
            seedId = playlist.id,
            modifier = Modifier.size(48.dp),
            cornerRadius = 10.dp
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                fontSize = dimensions.bodySize,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (playlist.songCount > 0) "${playlist.songCount} 首歌曲" else "精选歌单",
                fontSize = dimensions.captionSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = "查看歌单曲目",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 搜索结果翻页条。
 * 在线结果由服务端 /api/search 的 page/limit 分页，这里只负责切换页码与展示当前页。
 */
@Composable
private fun SearchPagerBar(
    page: Int,
    hasMore: Boolean,
    isLoading: Boolean,
    accent: Color,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    val dimensions = LocalAppDimensions.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SearchPagerChip(
            text = "上一页",
            enabled = page > 1 && !isLoading,
            accent = accent,
            onClick = onPrev
        )
        Text(
            text = if (isLoading) "第 $page 页 · 加载中" else "第 $page 页",
            fontSize = dimensions.bodySize,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp)
        )
        SearchPagerChip(
            text = "下一页",
            enabled = hasMore && !isLoading,
            accent = accent,
            onClick = onNext
        )
    }
}

@Composable
private fun SearchPagerChip(
    text: String,
    enabled: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    val dimensions = LocalAppDimensions.current
    Surface(
        shape = RoundedCornerShape(50),
        color = if (enabled) {
            accent.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        },
        modifier = Modifier.clickable(enabled = enabled) { onClick() }
    ) {
        Text(
            text = text,
            fontSize = dimensions.captionSize,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
        )
    }
}
