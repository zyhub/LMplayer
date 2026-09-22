package com.lm.player.feature.library

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.ArtistAvatarImage
import com.lm.player.core.designsystem.component.MosaicArtworkCollage
import com.lm.player.core.designsystem.component.PlaylistArtworkImage
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.media.SongMatchingResolver
import com.lm.player.core.model.*
import com.lm.player.feature.home.SongListItemRow
import com.lm.player.feature.home.formatDownloadedSongSpecs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalLibraryScreen(
    allSongs: List<UnifiedSong>,
    playlists: List<UnifiedPlaylist> = emptyList(),
    activeServerConfig: ServerConfig? = null,
    activeDownloadTasks: List<DownloadTask> = emptyList(),
    activeDownloadCount: Int = 0,
    onSongClick: (UnifiedSong) -> Unit,
    onDownloadSong: (UnifiedSong) -> Unit,
    onOpenDownloads: () -> Unit = {},
    onRefreshPlaylists: () -> Unit = {},
    onCreatePlaylist: (name: String, isOnline: Boolean) -> Unit = { _, _ -> },
    onDeletePlaylist: (playlistId: String) -> Unit = {},
    onFetchPlaylistSongs: (suspend (playlistId: String, isOnline: Boolean) -> List<UnifiedSong>)? = null,
    onFetchServerFolders: (suspend (parentId: String?) -> List<ServerFolderItem>)? = null,
    onFetchServerFolderSongs: (suspend (folderId: String) -> List<UnifiedSong>)? = null,
    onDeleteLocalFilePath: (suspend (String) -> Unit)? = null,
    initialCategory: LibraryCategory? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val dimensions = LocalAppDimensions.current
    val listBottomPadding = PaddingValues(
        top = 4.dp,
        bottom = contentPadding.calculateBottomPadding() + 24.dp
    )

    // 当前选中的二级详情视图状态 (支持多级下钻与返回)
    var selectedCategory by remember { mutableStateOf(initialCategory) }
    var selectedAlbum by remember { mutableStateOf<UnifiedAlbum?>(null) }
    var selectedArtist by remember { mutableStateOf<UnifiedArtist?>(null) }
    var selectedPlaylist by remember { mutableStateOf<UnifiedPlaylist?>(null) }
    var selectedPlaylistSongs by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
    var isLoadingPlaylistSongs by remember { mutableStateOf(false) }

    // 歌单创建弹窗状态
    var isCreatePlaylistDialogOpen by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var newPlaylistIsOnline by remember { mutableStateOf(activeServerConfig != null) }

    // 歌单浏览模式：网格 vs 列表
    var isPlaylistGridView by remember { mutableStateOf(true) }
    // 歌单分类 Tab：在线歌单 (0) vs 离线歌单 (1)
    var playlistTabMode by remember { mutableStateOf(if (activeServerConfig != null) 0 else 1) }

    // 选中歌单时自动触发异步曲目拉取
    LaunchedEffect(selectedPlaylist?.id, activeServerConfig?.id) {
        val pl = selectedPlaylist
        if (pl != null && onFetchPlaylistSongs != null) {
            isLoadingPlaylistSongs = true
            selectedPlaylistSongs = onFetchPlaylistSongs(pl.id, pl.isOnline)
            isLoadingPlaylistSongs = false
        }
    }

    // 动态派生数据集合 (本地模式下仅展示本地已下载曲目数据)
    val downloadedSongs = remember(allSongs) {
        allSongs.filter {
            it.downloadStatus == DownloadStatus.DOWNLOADED ||
            it.serverId in listOf("local_storage", "local_folder", "local_saf") ||
            !it.localFilePath.isNullOrBlank()
        }
    }

    val isOnlineMode = activeServerConfig != null
    val effectiveLibrarySongs = remember(allSongs, downloadedSongs, isOnlineMode) {
        if (isOnlineMode) allSongs else downloadedSongs
    }

    val albums = remember(effectiveLibrarySongs) {
        effectiveLibrarySongs.filter { it.album.isNotBlank() }
            .groupBy { it.album }
            .map { (albumTitle, songs) ->
                val first = songs.first()
                UnifiedAlbum(
                    id = first.albumId.ifBlank { albumTitle },
                    title = albumTitle,
                    artist = first.artist,
                    coverUrl = first.coverUrl,
                    songCount = songs.size
                )
            }
    }

    val artists = remember(effectiveLibrarySongs) {
        effectiveLibrarySongs.filter { it.artist.isNotBlank() }
            .groupBy { it.artist }
            .map { (artistName, songs) ->
                UnifiedArtist(
                    id = artistName,
                    name = artistName,
                    avatarUrl = songs.firstOrNull()?.coverUrl ?: "",
                    albumCount = songs.map { it.album }.distinct().size
                )
            }
    }

    val onlinePlaylists = remember(playlists) {
        playlists.filter { it.isOnline }
    }

    val offlinePlaylists = remember(playlists) {
        playlists.filter { !it.isOnline }
    }

    val favoriteSongs = remember(effectiveLibrarySongs) {
        effectiveLibrarySongs.filter { it.isFavorite }
    }

    // 顶部当前页面标题判定
    val pageTitle = when {
        selectedAlbum != null -> selectedAlbum!!.title
        selectedArtist != null -> selectedArtist!!.name
        selectedPlaylist != null -> selectedPlaylist!!.name
        selectedCategory == LibraryCategory.PLAYLISTS -> "播放列表"
        selectedCategory == LibraryCategory.ARTISTS -> "艺术家"
        selectedCategory == LibraryCategory.ALBUMS -> "专辑"
        selectedCategory == LibraryCategory.SONGS -> "歌曲"
        selectedCategory == LibraryCategory.DOWNLOADED -> "已下载歌曲"
        selectedCategory == LibraryCategory.FAVORITES -> "我喜欢"
        selectedCategory == LibraryCategory.FOLDERS -> "文件夹"
        else -> "资料库"
    }

    val hasBackNav = selectedAlbum != null || selectedArtist != null || selectedPlaylist != null || selectedCategory != null

    val onBackClick: () -> Unit = {
        when {
            selectedAlbum != null -> selectedAlbum = null
            selectedArtist != null -> selectedArtist = null
            selectedPlaylist != null -> {
                selectedPlaylist = null
                selectedPlaylistSongs = emptyList()
            }
            selectedCategory != null -> selectedCategory = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // 1. 顶部 Header (大标题 / 返回按键 + 歌单操作 / 下载管理入口)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false)
            ) {
                if (hasBackNav) {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                }

                Text(
                    text = pageTitle,
                    style = TextStyle(
                        fontSize = (if (hasBackNav) 22.sp else 30.sp) * dimensions.fontScale,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 顶部操作区（宽裕 16dp 间距排开，彻底消除拥挤感）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(end = 4.dp)
            ) {
                // 歌单专区顶部操作按键
                if (selectedCategory == LibraryCategory.PLAYLISTS && selectedPlaylist == null) {
                    IconButton(
                        onClick = {
                            newPlaylistIsOnline = (playlistTabMode == 0 && activeServerConfig != null)
                            newPlaylistName = ""
                            isCreatePlaylistDialogOpen = true
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f), CircleShape)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "新建歌单", tint = AppleRed, modifier = Modifier.size(22.dp))
                    }

                    if (playlistTabMode == 0) {
                        IconButton(
                            onClick = onRefreshPlaylists,
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f), CircleShape)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "同步在线歌单", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                        }
                    }

                    IconButton(
                        onClick = { isPlaylistGridView = !isPlaylistGridView },
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f), CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isPlaylistGridView) Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = "切换视图",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // 顶部右上角下载管理入口 (统一 40dp 圆形按键与角标)
                Box(contentAlignment = Alignment.TopEnd) {
                    IconButton(
                        onClick = onOpenDownloads,
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f), CircleShape)
                    ) {
                        Icon(
                            imageVector = if (activeDownloadCount > 0) Icons.Default.FileDownload else Icons.Outlined.FileDownload,
                            contentDescription = "下载管理",
                            tint = if (activeDownloadCount > 0) AppleRed else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (activeDownloadCount > 0) {
                        val badgeText = if (activeDownloadCount > 99) "99+" else "$activeDownloadCount"
                        Box(
                            modifier = Modifier
                                .offset(x = 4.dp, y = (-4).dp)
                                .height(18.dp)
                                .widthIn(min = 18.dp)
                                .clip(CircleShape)
                                .background(AppleRed)
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = badgeText,
                                color = Color.White,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                style = TextStyle(
                                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                                        includeFontPadding = false
                                    ),
                                    lineHeight = 10.sp
                                )
                            )
                        }
                    }
                }
            }
        }

        // 2. 核心多级视图路由
        when {
            // ====== A. 专辑详情页面 (Album Detail) ======
            selectedAlbum != null -> {
                val album = selectedAlbum!!
                val albumSongs = remember(allSongs, album.title) { allSongs.filter { it.album == album.title } }
                AlbumDetailView(
                    album = album,
                    songs = albumSongs,
                    onSongClick = onSongClick,
                    onDownloadSong = onDownloadSong,
                    onOpenDownloads = onOpenDownloads,
                    activeDownloadTasks = activeDownloadTasks,
                    contentPadding = listBottomPadding
                )
            }

            // ====== B. 艺术家详情页面 (Artist Detail) ======
            selectedArtist != null -> {
                val artist = selectedArtist!!
                val artistSongs = remember(allSongs, artist.name) { allSongs.filter { it.artist == artist.name } }
                ArtistDetailView(
                    artist = artist,
                    songs = artistSongs,
                    onSongClick = onSongClick,
                    onDownloadSong = onDownloadSong,
                    onOpenDownloads = onOpenDownloads,
                    activeDownloadTasks = activeDownloadTasks,
                    contentPadding = listBottomPadding
                )
            }

            // ====== C. 播放列表详情页面 (Playlist Detail) ======
            selectedPlaylist != null -> {
                val playlist = selectedPlaylist!!
                PlaylistDetailView(
                    playlist = playlist,
                    songs = selectedPlaylistSongs,
                    isLoading = isLoadingPlaylistSongs,
                    onSongClick = onSongClick,
                    onDownloadSong = onDownloadSong,
                    onOpenDownloads = onOpenDownloads,
                    activeDownloadTasks = activeDownloadTasks,
                    contentPadding = listBottomPadding
                )
            }

            // ====== D. 专辑栅格页 (Albums Grid) ======
            selectedCategory == LibraryCategory.ALBUMS -> {
                if (albums.isEmpty()) {
                    EmptyCategoryMessage("暂无专辑信息")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(140.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = listBottomPadding,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = albums,
                            key = { it.id },
                            contentType = { "album_grid_item" }
                        ) { album ->
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { selectedAlbum = album }
                                    .padding(4.dp)
                            ) {
                                AlbumArtworkImage(
                                    model = album.coverUrl,
                                    seedId = album.id,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .shadow(4.dp, RoundedCornerShape(12.dp)),
                                    cornerRadius = 12.dp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = album.title,
                                    style = TextStyle(fontSize = 14.sp * dimensions.fontScale, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${album.artist} • ${album.songCount} 首",
                                    style = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // ====== E. 艺术家列表页 (Artists Grid) ======
            selectedCategory == LibraryCategory.ARTISTS -> {
                if (artists.isEmpty()) {
                    EmptyCategoryMessage("暂无艺术家信息")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(100.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = listBottomPadding,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = artists,
                            key = { it.id },
                            contentType = { "artist_grid_item" }
                        ) { artist ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { selectedArtist = artist }
                                    .padding(8.dp)
                            ) {
                                ArtistAvatarImage(
                                    model = artist.avatarUrl,
                                    seedId = artist.name,
                                    modifier = Modifier
                                        .size(90.dp)
                                        .shadow(3.dp, CircleShape)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = artist.name,
                                    style = TextStyle(fontSize = 14.sp * dimensions.fontScale, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${artist.albumCount} 张专辑",
                                    style = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }
                        }
                    }
                }
            }

            // ====== F. 播放列表页 (Playlists: 在线歌单 ☁️ vs 离线歌单 💾) ======
            selectedCategory == LibraryCategory.PLAYLISTS -> {
                val currentPlaylists = if (playlistTabMode == 0) onlinePlaylists else offlinePlaylists

                Column(modifier = Modifier.fillMaxSize()) {
                    // 顶部在线与离线选项卡
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (playlistTabMode == 0) AppleRed else Color.Transparent,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { playlistTabMode = 0 }
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Cloud,
                                        contentDescription = null,
                                        tint = if (playlistTabMode == 0) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "在线歌单 (${onlinePlaylists.size})",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (playlistTabMode == 0) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (playlistTabMode == 1) AppleRed else Color.Transparent,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { playlistTabMode = 1 }
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SaveAlt,
                                        contentDescription = null,
                                        tint = if (playlistTabMode == 1) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "离线歌单 (${offlinePlaylists.size})",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (playlistTabMode == 1) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (currentPlaylists.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 60.dp),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (playlistTabMode == 0) "暂无在线歌单 (点击右上角 ↺ 刷新同步)" else "暂无离线歌单",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        newPlaylistIsOnline = (playlistTabMode == 0)
                                        newPlaylistName = ""
                                        isCreatePlaylistDialogOpen = true
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(text = if (playlistTabMode == 0) "新建在线歌单" else "新建离线歌单")
                                }
                            }
                        }
                    } else if (isPlaylistGridView) {
                        // 网格展示：参考图一 2x2 四宫格卡片设计
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(130.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = listBottomPadding,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(
                                items = currentPlaylists,
                                key = { it.id },
                                contentType = { "playlist_grid_item" }
                            ) { playlist ->
                                val covers = if (playlist.previewCovers.isNotEmpty()) {
                                    playlist.previewCovers
                                } else if (playlist.coverUrl.isNotBlank()) {
                                    listOf(playlist.coverUrl)
                                } else {
                                    allSongs.filter { it.coverUrl.isNotBlank() }.shuffled().take(4).map { it.coverUrl }
                                }

                                Column(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            selectedPlaylist = playlist
                                            coroutineScope.launch {
                                                isLoadingPlaylistSongs = true
                                                selectedPlaylistSongs = onFetchPlaylistSongs?.invoke(playlist.id, playlist.isOnline) ?: emptyList()
                                                isLoadingPlaylistSongs = false
                                            }
                                        }
                                        .padding(4.dp)
                                ) {
                                    MosaicArtworkCollage(
                                        coverUrls = covers,
                                        seedId = playlist.id,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .shadow(4.dp, RoundedCornerShape(12.dp)),
                                        cornerRadius = 12.dp
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = playlist.name,
                                        style = TextStyle(
                                            fontSize = 14.sp * dimensions.fontScale,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${playlist.songCount} 首歌曲",
                                        style = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    )
                                }
                            }
                        }
                    } else {
                        // 列表展示
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = listBottomPadding,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                items = currentPlaylists,
                                key = { it.id },
                                contentType = { "playlist_list_item" }
                            ) { playlist ->
                                val covers = if (playlist.previewCovers.isNotEmpty()) {
                                    playlist.previewCovers
                                } else if (playlist.coverUrl.isNotBlank()) {
                                    listOf(playlist.coverUrl)
                                } else {
                                    allSongs.filter { it.coverUrl.isNotBlank() }.shuffled().take(4).map { it.coverUrl }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable {
                                            selectedPlaylist = playlist
                                            coroutineScope.launch {
                                                isLoadingPlaylistSongs = true
                                                selectedPlaylistSongs = onFetchPlaylistSongs?.invoke(playlist.id, playlist.isOnline) ?: emptyList()
                                                isLoadingPlaylistSongs = false
                                            }
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    MosaicArtworkCollage(
                                        coverUrls = covers,
                                        seedId = playlist.id,
                                        modifier = Modifier.size(60.dp),
                                        cornerRadius = 12.dp
                                    )
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = playlist.name,
                                            style = TextStyle(fontSize = 16.sp * dimensions.fontScale, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${if (playlist.isOnline) "云端歌单" else "离线歌单"} • ${playlist.songCount} 首歌曲",
                                            style = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        )
                                    }
                                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            // ====== G. 单曲全部列表 (Songs) ======
            selectedCategory == LibraryCategory.SONGS -> {
                SongsListView(
                    songs = effectiveLibrarySongs,
                    emptyMessage = if (isOnlineMode) "暂无歌曲" else "暂无本地已下载歌曲",
                    onSongClick = onSongClick,
                    onDownloadSong = onDownloadSong,
                    onOpenDownloads = onOpenDownloads,
                    activeDownloadTasks = activeDownloadTasks,
                    contentPadding = listBottomPadding
                )
            }

            // ====== H. 已下载歌曲 (Downloaded) ======
            selectedCategory == LibraryCategory.DOWNLOADED -> {
                SongsListView(
                    songs = downloadedSongs,
                    emptyMessage = "暂无已下载歌曲",
                    onSongClick = onSongClick,
                    onDownloadSong = onDownloadSong,
                    onOpenDownloads = onOpenDownloads,
                    activeDownloadTasks = activeDownloadTasks,
                    contentPadding = listBottomPadding
                )
            }

            // ====== I. 我的收藏 (Favorites) ======
            selectedCategory == LibraryCategory.FAVORITES -> {
                SongsListView(
                    songs = favoriteSongs,
                    emptyMessage = "暂无红心收藏歌曲",
                    onSongClick = onSongClick,
                    onDownloadSong = onDownloadSong,
                    onOpenDownloads = onOpenDownloads,
                    activeDownloadTasks = activeDownloadTasks,
                    contentPadding = listBottomPadding
                )
            }

            // ====== J. 文件夹 (Folders: 服务器下发原生目录 vs 本地文件夹) ======
            selectedCategory == LibraryCategory.FOLDERS -> {
                FolderBrowserView(
                    allSongs = allSongs,
                    activeServerConfig = activeServerConfig,
                    activeDownloadTasks = activeDownloadTasks,
                    onFetchServerFolders = onFetchServerFolders,
                    onFetchServerFolderSongs = onFetchServerFolderSongs,
                    onDeleteLocalFilePath = onDeleteLocalFilePath,
                    onSongClick = onSongClick,
                    onDownloadSong = onDownloadSong,
                    onOpenDownloads = onOpenDownloads,
                    contentPadding = listBottomPadding
                )
            }

            // ====== 根页面：标准分类入口列表 ======
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listBottomPadding,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 0.5.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                LibraryNavigationItem(
                                    icon = Icons.Default.QueueMusic,
                                    title = "播放列表",
                                    subtitle = "${playlists.size} 个歌单合集",
                                    onClick = { selectedCategory = LibraryCategory.PLAYLISTS }
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), thickness = 0.6.dp, modifier = Modifier.padding(start = 56.dp))
                                LibraryNavigationItem(
                                    icon = Icons.Default.Mic,
                                    title = "艺术家",
                                    subtitle = "${artists.size} 位音乐人",
                                    onClick = { selectedCategory = LibraryCategory.ARTISTS }
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), thickness = 0.6.dp, modifier = Modifier.padding(start = 56.dp))
                                LibraryNavigationItem(
                                    icon = Icons.Default.Album,
                                    title = "专辑",
                                    subtitle = "${albums.size} 张专辑",
                                    onClick = { selectedCategory = LibraryCategory.ALBUMS }
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), thickness = 0.6.dp, modifier = Modifier.padding(start = 56.dp))
                                val songsSubtitle = if (activeServerConfig != null) {
                                    "在线 ${allSongs.size} 首曲目 • 已下载 ${downloadedSongs.size} 首曲目"
                                } else {
                                    "已下载 ${downloadedSongs.size} 首曲目"
                                }
                                LibraryNavigationItem(
                                    icon = Icons.Default.MusicNote,
                                    title = "歌曲",
                                    subtitle = songsSubtitle,
                                    onClick = { selectedCategory = LibraryCategory.SONGS }
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), thickness = 0.6.dp, modifier = Modifier.padding(start = 56.dp))
                                LibraryNavigationItem(
                                    icon = Icons.Default.Folder,
                                    title = "文件夹",
                                    subtitle = "本地与服务器原生目录",
                                    onClick = { selectedCategory = LibraryCategory.FOLDERS }
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), thickness = 0.6.dp, modifier = Modifier.padding(start = 56.dp))
                                LibraryNavigationItem(
                                    icon = Icons.Default.FileDownloadDone,
                                    title = "已下载",
                                    subtitle = "${downloadedSongs.size} 首离线歌曲",
                                    tint = AppleRed,
                                    onClick = onOpenDownloads
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), thickness = 0.6.dp, modifier = Modifier.padding(start = 56.dp))
                                LibraryNavigationItem(
                                    icon = Icons.Default.FavoriteBorder,
                                    title = "我喜欢",
                                    subtitle = "${favoriteSongs.size} 首歌曲",
                                    tint = AppleRed,
                                    onClick = { selectedCategory = LibraryCategory.FAVORITES }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 新建歌单对话框
    if (isCreatePlaylistDialogOpen) {
        AlertDialog(
            onDismissRequest = { isCreatePlaylistDialogOpen = false },
            title = { Text(text = if (newPlaylistIsOnline) "新建在线歌单" else "新建离线歌单", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        label = { Text("歌单名称") },
                        placeholder = { Text("请输入歌单名称...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (activeServerConfig != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { newPlaylistIsOnline = !newPlaylistIsOnline }
                        ) {
                            Checkbox(
                                checked = newPlaylistIsOnline,
                                onCheckedChange = { newPlaylistIsOnline = it },
                                colors = CheckboxDefaults.colors(checkedColor = AppleRed)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "同步至在线服务器", fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            onCreatePlaylist(newPlaylistName.trim(), newPlaylistIsOnline)
                            isCreatePlaylistDialogOpen = false
                            Toast.makeText(context, "歌单「${newPlaylistName.trim()}」已成功创建", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed)
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { isCreatePlaylistDialogOpen = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 文件夹浏览器（包含服务器下发文件夹与本地存储目录）
 */
@Composable
private fun FolderBrowserView(
    allSongs: List<UnifiedSong>,
    activeServerConfig: ServerConfig?,
    activeDownloadTasks: List<DownloadTask> = emptyList(),
    onFetchServerFolders: (suspend (parentId: String?) -> List<ServerFolderItem>)?,
    onFetchServerFolderSongs: (suspend (folderId: String) -> List<UnifiedSong>)?,
    onDeleteLocalFilePath: (suspend (String) -> Unit)? = null,
    onSongClick: (UnifiedSong) -> Unit,
    onDownloadSong: (UnifiedSong) -> Unit,
    onOpenDownloads: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val dimensions = LocalAppDimensions.current
    var folderMode by remember { mutableStateOf(if (activeServerConfig != null) 0 else 1) } // 0: 服务器文件夹, 1: 本地文件夹

    // 服务器文件夹导航状态
    var serverFolderStack by remember { mutableStateOf<List<Pair<String?, String>>>(listOf(null to "根目录")) }
    var serverItems by remember { mutableStateOf<List<ServerFolderItem>>(emptyList()) }
    var isLoadingServerFolder by remember { mutableStateOf(false) }

    // 服务器多选下载状态
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedFolderIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedSongIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isBatchDownloading by remember { mutableStateOf(false) }

    // 本地文件夹导航状态
    var currentLocalDir by remember { mutableStateOf(File("/storage/emulated/0")) }

    // 本地文件夹多选与删除状态
    var isLocalSelectionMode by remember { mutableStateOf(false) }
    var selectedLocalPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isDeleteDialogOpen by remember { mutableStateOf(false) }
    var deleteTargetFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isDeleting by remember { mutableStateOf(false) }
    var localRefreshTrigger by remember { mutableStateOf(0) }

    // 切换目录或模式时清空多选状态
    LaunchedEffect(serverFolderStack, folderMode, currentLocalDir) {
        isSelectionMode = false
        selectedFolderIds = emptySet()
        selectedSongIds = emptySet()
        isLocalSelectionMode = false
        selectedLocalPaths = emptySet()
        deleteTargetFiles = emptyList()
        isDeleteDialogOpen = false
    }

    // 加载当前服务器目录
    LaunchedEffect(folderMode, serverFolderStack.lastOrNull()?.first, activeServerConfig?.id) {
        if (folderMode == 0 && onFetchServerFolders != null) {
            isLoadingServerFolder = true
            val parentId = serverFolderStack.lastOrNull()?.first
            serverItems = onFetchServerFolders(parentId)
            isLoadingServerFolder = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部切换模式选项卡
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (folderMode == 0) AppleRed else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { folderMode = 0 }
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudQueue,
                            contentDescription = null,
                            tint = if (folderMode == 0) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "服务器文件夹",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (folderMode == 0) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (folderMode == 1) AppleRed else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { folderMode = 1 }
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = if (folderMode == 1) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "本地存储文件夹",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (folderMode == 1) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (folderMode == 0) {
            // ====== 服务器原生文件夹 ======
            if (activeServerConfig == null) {
                EmptyCategoryMessage("当前未连接在线服务器，请前往设置连接 NAS 服务器")
            } else {
                val currentFolderHierarchy = if (serverFolderStack.size > 1) {
                    serverFolderStack.drop(1).joinToString("/") { it.second }
                } else null

                // 面包屑导航与操作栏 (支持单曲播放与多选批量下载)
                if (!isSelectionMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (serverFolderStack.size > 1) {
                                IconButton(
                                    onClick = {
                                        if (serverFolderStack.size > 1) {
                                            serverFolderStack = serverFolderStack.dropLast(1)
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.ArrowBackIosNew, contentDescription = "上级目录", modifier = Modifier.size(14.dp))
                                }
                            }
                            Text(
                                text = serverFolderStack.joinToString(" > ") { it.second },
                                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AppleRed),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 播放当前目录全部歌曲
                            val currentFolderId = serverFolderStack.lastOrNull()?.first
                            if (currentFolderId != null && onFetchServerFolderSongs != null && !isLoadingServerFolder) {
                                TextButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            val songs = onFetchServerFolderSongs(currentFolderId)
                                            if (songs.isNotEmpty()) {
                                                songs.firstOrNull()?.let(onSongClick)
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = AppleRed, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("播放", fontSize = 12.sp, color = AppleRed, fontWeight = FontWeight.Bold)
                                }
                            }

                            // 开启多选下载模式按钮
                            if (serverItems.isNotEmpty() && !isLoadingServerFolder) {
                                TextButton(
                                    onClick = {
                                        isSelectionMode = true
                                        selectedFolderIds = emptySet()
                                        selectedSongIds = emptySet()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Checklist, contentDescription = null, tint = AppleRed, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("多选下载", fontSize = 12.sp, color = AppleRed, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else {
                    // 多选操作栏
                    val totalSelected = selectedFolderIds.size + selectedSongIds.size
                    val allFolderIds = serverItems.filter { it.isFolder }.map { it.id }.toSet()
                    val allSongIds = serverItems.filter { !it.isFolder && it.song != null }.map { it.song!!.id }.toSet()
                    val isAllSelected = (allFolderIds.isNotEmpty() || allSongIds.isNotEmpty()) &&
                            selectedFolderIds.containsAll(allFolderIds) &&
                            selectedSongIds.containsAll(allSongIds)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "已选 $totalSelected 项",
                                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppleRed)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            TextButton(
                                onClick = {
                                    if (isAllSelected) {
                                        selectedFolderIds = emptySet()
                                        selectedSongIds = emptySet()
                                    } else {
                                        selectedFolderIds = allFolderIds
                                        selectedSongIds = allSongIds
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(if (isAllSelected) "取消全选" else "全选", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = {
                                    if (totalSelected > 0 && !isBatchDownloading) {
                                        coroutineScope.launch {
                                            isBatchDownloading = true
                                            var totalCount = 0
                                            // 1. 批量下载选中的单曲 (注入当前层级路径)
                                            for (songId in selectedSongIds) {
                                                val songItem = serverItems.firstOrNull { it.song?.id == songId }?.song
                                                if (songItem != null) {
                                                    val songWithFolder = songItem.copy(
                                                        relativeFolderPath = songItem.relativeFolderPath ?: currentFolderHierarchy
                                                    )
                                                    onDownloadSong(songWithFolder)
                                                    totalCount++
                                                }
                                            }
                                            // 2. 批量下载选中的文件夹 (递归拉取并保持相对层级)
                                            for (folderId in selectedFolderIds) {
                                                val folderItem = serverItems.firstOrNull { it.id == folderId }
                                                val folderName = folderItem?.name ?: ""
                                                val subHierarchy = if (!currentFolderHierarchy.isNullOrBlank()) "$currentFolderHierarchy/$folderName" else folderName
                                                if (onFetchServerFolderSongs != null) {
                                                    val songs = onFetchServerFolderSongs(folderId)
                                                    for (song in songs) {
                                                        val songWithFolder = song.copy(
                                                            relativeFolderPath = if (song.relativeFolderPath.isNullOrBlank()) subHierarchy else song.relativeFolderPath
                                                        )
                                                        onDownloadSong(songWithFolder)
                                                        totalCount++
                                                    }
                                                }
                                            }
                                            Toast.makeText(context, "已将 $totalCount 首曲目加入离线下载队列", Toast.LENGTH_SHORT).show()
                                            isSelectionMode = false
                                            selectedFolderIds = emptySet()
                                            selectedSongIds = emptySet()
                                            isBatchDownloading = false
                                        }
                                    }
                                },
                                enabled = totalSelected > 0 && !isBatchDownloading,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                if (isBatchDownloading) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("一键下载 ($totalSelected)", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(
                                onClick = { isSelectionMode = false },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "退出多选", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                if (isLoadingServerFolder) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AppleRed, modifier = Modifier.size(32.dp))
                    }
                } else if (serverItems.isEmpty()) {
                    EmptyCategoryMessage("该目录下暂无音频文件或子文件夹")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = contentPadding,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(serverItems, key = { it.id }) { item ->
                            if (item.isFolder) {
                                // 文件夹匹配检测：核对 allSongs 中该文件夹下的歌曲是否全部为 DOWNLOADED
                                val matchedFolderSongs = remember(allSongs, item.name, currentFolderHierarchy) {
                                    val fullPath = if (!currentFolderHierarchy.isNullOrBlank()) "$currentFolderHierarchy/${item.name}" else item.name
                                    val normFolderName = SongMatchingResolver.normalizeArtist(item.name)
                                    allSongs.filter {
                                        (it.relativeFolderPath != null && (it.relativeFolderPath.contains(fullPath) || it.relativeFolderPath.contains(item.name))) ||
                                                it.album.equals(item.name, ignoreCase = true) ||
                                                it.artist.equals(item.name, ignoreCase = true) ||
                                                SongMatchingResolver.normalizeArtist(it.artist) == normFolderName ||
                                                SongMatchingResolver.normalizeArtist(it.album) == normFolderName
                                    }
                                }
                                val isFolderFullyDownloaded = matchedFolderSongs.isNotEmpty() && matchedFolderSongs.all { it.downloadStatus == DownloadStatus.DOWNLOADED }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (isSelectionMode) {
                                                selectedFolderIds = if (selectedFolderIds.contains(item.id)) {
                                                    selectedFolderIds - item.id
                                                } else {
                                                    selectedFolderIds + item.id
                                                }
                                            } else {
                                                serverFolderStack = serverFolderStack + (item.id to item.name)
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isSelectionMode) {
                                        Checkbox(
                                            checked = selectedFolderIds.contains(item.id),
                                            onCheckedChange = { checked ->
                                                selectedFolderIds = if (checked) selectedFolderIds + item.id else selectedFolderIds - item.id
                                            },
                                            colors = CheckboxDefaults.colors(checkedColor = AppleRed),
                                            modifier = Modifier.padding(end = 4.dp)
                                        )
                                    }

                                    // 文件夹图标 (若已全部离线则显示绿色)
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = if (isFolderFullyDownloaded) Color(0xFF34C759) else AppleRed,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = item.name,
                                                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (isFolderFullyDownloaded) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = Color(0xFF34C759).copy(alpha = 0.15f)
                                                ) {
                                                    Text(
                                                        text = "已离线",
                                                        color = Color(0xFF34C759),
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = if (isFolderFullyDownloaded) {
                                                "${if (item.childCount > 0) "${item.childCount} 个项目 • " else ""}已全部离线"
                                            } else {
                                                if (item.childCount > 0) "${item.childCount} 个项目" else "文件夹"
                                            },
                                            style = TextStyle(
                                                fontSize = 12.sp,
                                                color = if (isFolderFullyDownloaded) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                    }

                                    if (isFolderFullyDownloaded && !isSelectionMode) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "已全部离线",
                                            tint = Color(0xFF34C759),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else if (!isSelectionMode) {
                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else if (item.song != null) {
                                // 单曲渲染：全局统一解析器匹配本地离线与下载状态
                                val rawSong = item.song
                                val effectiveSong = remember(rawSong, allSongs, activeDownloadTasks) {
                                    val resolved = SongMatchingResolver.resolveSingleSong(
                                        rawSong = rawSong,
                                        allCachedSongs = allSongs,
                                        activeTasks = activeDownloadTasks
                                    )
                                    resolved.copy(
                                        relativeFolderPath = rawSong.relativeFolderPath ?: currentFolderHierarchy
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isSelectionMode) {
                                        Checkbox(
                                            checked = selectedSongIds.contains(effectiveSong.id),
                                            onCheckedChange = { checked ->
                                                selectedSongIds = if (checked) selectedSongIds + effectiveSong.id else selectedSongIds - effectiveSong.id
                                            },
                                            colors = CheckboxDefaults.colors(checkedColor = AppleRed),
                                            modifier = Modifier.padding(end = 4.dp)
                                        )
                                    }
                                    Box(modifier = Modifier.weight(1f)) {
                                        SongListItemRow(
                                            song = effectiveSong,
                                            onClick = {
                                                if (isSelectionMode) {
                                                    selectedSongIds = if (selectedSongIds.contains(effectiveSong.id)) {
                                                        selectedSongIds - effectiveSong.id
                                                    } else {
                                                        selectedSongIds + effectiveSong.id
                                                    }
                                                } else {
                                                    onSongClick(effectiveSong)
                                                }
                                            },
                                            onDownloadClick = { onDownloadSong(effectiveSong) },
                                            onOpenDownloads = onOpenDownloads
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // ====== 本地文件系统目录 ======
            val audioExtensions = remember { listOf("flac", "mp3", "wav", "m4a", "aac", "ape", "dsf", "dff", "ogg") }
            val files = remember(currentLocalDir, localRefreshTrigger) {
                try {
                    currentLocalDir.listFiles()?.filter {
                        !it.name.startsWith(".") && (it.isDirectory || it.extension.lowercase() in audioExtensions)
                    }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                if (isLocalSelectionMode) {
                    // 多选模式工具栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "已选 ${selectedLocalPaths.size} 项",
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppleRed
                            )
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        val allSelected = files.isNotEmpty() && selectedLocalPaths.size == files.size
                        TextButton(
                            onClick = {
                                selectedLocalPaths = if (allSelected) {
                                    emptySet()
                                } else {
                                    files.map { it.absolutePath }.toSet()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (allSelected) "取消全选" else "全选",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppleRed
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        Button(
                            onClick = {
                                if (selectedLocalPaths.isNotEmpty()) {
                                    deleteTargetFiles = files.filter { selectedLocalPaths.contains(it.absolutePath) }
                                    isDeleteDialogOpen = true
                                }
                            },
                            enabled = selectedLocalPaths.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "删除 (${selectedLocalPaths.size})",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        IconButton(
                            onClick = {
                                isLocalSelectionMode = false
                                selectedLocalPaths = emptySet()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "取消多选",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                } else {
                    // 常规模式工具栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (currentLocalDir.parentFile != null && currentLocalDir.path != "/storage/emulated/0") {
                            IconButton(
                                onClick = { currentLocalDir = currentLocalDir.parentFile ?: currentLocalDir },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowBackIosNew,
                                    contentDescription = "上一级",
                                    tint = AppleRed,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = null,
                                tint = AppleRed,
                                modifier = Modifier.size(18.dp).padding(start = 4.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        Text(
                            text = currentLocalDir.path,
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        if (files.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = AppleRed.copy(alpha = 0.12f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        isLocalSelectionMode = true
                                        selectedLocalPaths = emptySet()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Checklist,
                                        contentDescription = "多选管理",
                                        tint = AppleRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "多选",
                                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppleRed)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (files.isEmpty()) {
                EmptyCategoryMessage("该文件夹为空")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(files, key = { it.absolutePath }) { file ->
                        if (file.isDirectory) {
                            val childCount = remember(file, localRefreshTrigger) {
                                try {
                                    file.listFiles()?.count { !it.name.startsWith(".") } ?: 0
                                } catch (_: Exception) { 0 }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (isLocalSelectionMode) {
                                            selectedLocalPaths = if (selectedLocalPaths.contains(file.absolutePath)) {
                                                selectedLocalPaths - file.absolutePath
                                            } else {
                                                selectedLocalPaths + file.absolutePath
                                            }
                                        } else {
                                            currentLocalDir = file
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isLocalSelectionMode) {
                                    Checkbox(
                                        checked = selectedLocalPaths.contains(file.absolutePath),
                                        onCheckedChange = { checked ->
                                            selectedLocalPaths = if (checked) selectedLocalPaths + file.absolutePath else selectedLocalPaths - file.absolutePath
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = AppleRed),
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }

                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = AppleRed,
                                    modifier = Modifier.size(34.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.name,
                                        style = TextStyle(fontSize = 15.sp * dimensions.fontScale, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "$childCount 个项目",
                                        style = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    )
                                }

                                if (!isLocalSelectionMode) {
                                    IconButton(
                                        onClick = {
                                            deleteTargetFiles = listOf(file)
                                            isDeleteDialogOpen = true
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Delete,
                                            contentDescription = "删除文件夹",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else if (file.extension.lowercase() in audioExtensions) {
                            val matchedSong = allSongs.firstOrNull { it.localFilePath == file.absolutePath }
                                ?: UnifiedSong(
                                    id = "local_file_${file.absolutePath.hashCode()}",
                                    title = file.nameWithoutExtension,
                                    artist = "本地文件",
                                    localFilePath = file.absolutePath,
                                    streamUrl = file.absolutePath,
                                    serverId = "local_storage",
                                    format = file.extension.lowercase(),
                                    downloadStatus = DownloadStatus.DOWNLOADED
                                )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (isLocalSelectionMode) {
                                            selectedLocalPaths = if (selectedLocalPaths.contains(file.absolutePath)) {
                                                selectedLocalPaths - file.absolutePath
                                            } else {
                                                selectedLocalPaths + file.absolutePath
                                            }
                                        } else {
                                            onSongClick(matchedSong)
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isLocalSelectionMode) {
                                    Checkbox(
                                        checked = selectedLocalPaths.contains(file.absolutePath),
                                        onCheckedChange = { checked ->
                                            selectedLocalPaths = if (checked) selectedLocalPaths + file.absolutePath else selectedLocalPaths - file.absolutePath
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = AppleRed),
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }

                                Box(modifier = Modifier.weight(1f)) {
                                    SongListItemRow(
                                        song = matchedSong,
                                        onClick = {
                                            if (isLocalSelectionMode) {
                                                selectedLocalPaths = if (selectedLocalPaths.contains(file.absolutePath)) {
                                                    selectedLocalPaths - file.absolutePath
                                                } else {
                                                    selectedLocalPaths + file.absolutePath
                                                }
                                            } else {
                                                onSongClick(matchedSong)
                                            }
                                        },
                                        onDownloadClick = {},
                                        onOpenDownloads = onOpenDownloads
                                    )
                                }

                                if (!isLocalSelectionMode) {
                                    IconButton(
                                        onClick = {
                                            deleteTargetFiles = listOf(file)
                                            isDeleteDialogOpen = true
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Delete,
                                            contentDescription = "删除文件",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ====== 二级确认提醒弹窗 (Secondary Delete Confirmation Dialog) ======
            if (isDeleteDialogOpen && deleteTargetFiles.isNotEmpty()) {
                AlertDialog(
                    onDismissRequest = { if (!isDeleting) isDeleteDialogOpen = false },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = AppleRed,
                            modifier = Modifier.size(36.dp)
                        )
                    },
                    title = {
                        Text(
                            text = if (deleteTargetFiles.size == 1) {
                                val target = deleteTargetFiles.first()
                                if (target.isDirectory) "确认删除文件夹？" else "确认删除文件？"
                            } else {
                                "确认批量删除选中的 ${deleteTargetFiles.size} 项？"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    },
                    text = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            val dirCount = deleteTargetFiles.count { it.isDirectory }
                            val fileCount = deleteTargetFiles.count { !it.isDirectory }

                            if (deleteTargetFiles.size == 1) {
                                val target = deleteTargetFiles.first()
                                Text(
                                    text = "项目名称：${target.name}",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (target.isDirectory) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val subCount = try { target.listFiles()?.size ?: 0 } catch (_: Exception) { 0 }
                                    Text(
                                        text = "该文件夹内包含 $subCount 个直接子项目，删除后将被一并清空。",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Text(
                                    text = "即将从车机本地存储中永久删除：",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                if (dirCount > 0) {
                                    Text(
                                        text = "• 文件夹：$dirCount 个 (包含其内部所有层级文件)",
                                        color = AppleRed,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                if (fileCount > 0) {
                                    Text(
                                        text = "• 音频单曲：$fileCount 首",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 13.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "⚠️ 此操作不可撤销，物理文件及离线库索引将被彻底删除。",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    isDeleting = true
                                    var successCount = 0
                                    for (target in deleteTargetFiles) {
                                        try {
                                            if (target.isDirectory) {
                                                val childFiles = target.walkTopDown().toList()
                                                for (child in childFiles) {
                                                    if (!child.isDirectory) {
                                                        onDeleteLocalFilePath?.invoke(child.absolutePath)
                                                    }
                                                }
                                                target.deleteRecursively()
                                                successCount++
                                            } else {
                                                onDeleteLocalFilePath?.invoke(target.absolutePath)
                                                if (target.exists()) target.delete()
                                                successCount++
                                            }
                                        } catch (e: Exception) {
                                            Log.e("FolderBrowserView", "Failed to delete ${target.absolutePath}", e)
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "已成功删除 $successCount 个项目", Toast.LENGTH_SHORT).show()
                                        isDeleting = false
                                        isDeleteDialogOpen = false
                                        deleteTargetFiles = emptyList()
                                        isLocalSelectionMode = false
                                        selectedLocalPaths = emptySet()
                                        localRefreshTrigger++
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                            enabled = !isDeleting
                        ) {
                            if (isDeleting) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("正在删除...")
                            } else {
                                Text("确认删除")
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                if (!isDeleting) {
                                    isDeleteDialogOpen = false
                                    deleteTargetFiles = emptyList()
                                }
                            },
                            enabled = !isDeleting
                        ) {
                            Text("取消")
                        }
                    }
                )
            }
        }
    }
}

/**
 * 仿 Apple Music 风格专辑详情页
 */
@Composable
private fun AlbumDetailView(
    album: UnifiedAlbum,
    songs: List<UnifiedSong>,
    onSongClick: (UnifiedSong) -> Unit,
    onDownloadSong: (UnifiedSong) -> Unit,
    onOpenDownloads: () -> Unit,
    activeDownloadTasks: List<DownloadTask> = emptyList(),
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val dimensions = LocalAppDimensions.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AlbumArtworkImage(
                    model = album.coverUrl,
                    seedId = album.id,
                    modifier = Modifier
                        .size(190.dp)
                        .shadow(8.dp, RoundedCornerShape(16.dp)),
                    cornerRadius = 16.dp
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = album.title,
                    style = TextStyle(fontSize = 20.sp * dimensions.fontScale, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${album.artist} • ${songs.size} 首歌曲",
                    style = TextStyle(fontSize = 14.sp, color = AppleRed, fontWeight = FontWeight.Medium)
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { songs.firstOrNull()?.let(onSongClick) },
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("全部播放", fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { songs.shuffled().firstOrNull()?.let(onSongClick) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Icon(Icons.Default.Shuffle, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("随机播放", fontWeight = FontWeight.Bold)
                }
            }
        }

        items(
            items = songs,
            key = { it.id },
            contentType = { "album_song_row" }
        ) { song ->
            SongListItemRow(
                song = song,
                onClick = { onSongClick(song) },
                onDownloadClick = { onDownloadSong(song) },
                onOpenDownloads = onOpenDownloads
            )
        }
    }
}

/**
 * 仿 Apple Music 风格艺术家详情页
 */
@Composable
private fun ArtistDetailView(
    artist: UnifiedArtist,
    songs: List<UnifiedSong>,
    onSongClick: (UnifiedSong) -> Unit,
    onDownloadSong: (UnifiedSong) -> Unit,
    onOpenDownloads: () -> Unit,
    activeDownloadTasks: List<DownloadTask> = emptyList(),
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val dimensions = LocalAppDimensions.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ArtistAvatarImage(
                    model = artist.avatarUrl,
                    seedId = artist.name,
                    modifier = Modifier
                        .size(140.dp)
                        .shadow(6.dp, CircleShape)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = artist.name,
                    style = TextStyle(fontSize = 22.sp * dimensions.fontScale, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                )
                Text(
                    text = "艺术家 • 共 ${songs.size} 首收录歌曲",
                    style = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }
        }

        item {
            Text(
                text = "精选热门歌曲",
                style = TextStyle(fontSize = 18.sp * dimensions.fontScale, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        items(
            items = songs,
            key = { it.id },
            contentType = { "song_row" }
        ) { song ->
            SongListItemRow(
                song = song,
                onClick = { onSongClick(song) },
                onDownloadClick = { onDownloadSong(song) },
                onOpenDownloads = onOpenDownloads
            )
        }
    }
}

/**
 * 仿 Apple Music 风格播放列表详情页
 */
@Composable
private fun PlaylistDetailView(
    playlist: UnifiedPlaylist,
    songs: List<UnifiedSong>,
    isLoading: Boolean = false,
    onSongClick: (UnifiedSong) -> Unit,
    onDownloadSong: (UnifiedSong) -> Unit,
    onOpenDownloads: () -> Unit,
    activeDownloadTasks: List<DownloadTask> = emptyList(),
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val dimensions = LocalAppDimensions.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val covers = if (playlist.previewCovers.isNotEmpty()) {
                    playlist.previewCovers
                } else if (playlist.coverUrl.isNotBlank()) {
                    listOf(playlist.coverUrl)
                } else {
                    songs.filter { it.coverUrl.isNotBlank() }.take(4).map { it.coverUrl }
                }

                MosaicArtworkCollage(
                    coverUrls = covers,
                    seedId = playlist.id,
                    modifier = Modifier
                        .size(170.dp)
                        .shadow(6.dp, RoundedCornerShape(16.dp)),
                    cornerRadius = 16.dp
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = playlist.name,
                    style = TextStyle(fontSize = 20.sp * dimensions.fontScale, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                )
                Text(
                    text = "${if (playlist.isOnline) "云端歌单" else "离线歌单"} • 共 ${songs.size} 首歌曲",
                    style = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { songs.firstOrNull()?.let(onSongClick) },
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("全部播放", fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { songs.shuffled().firstOrNull()?.let(onSongClick) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Icon(Icons.Default.Shuffle, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("随机播放", fontWeight = FontWeight.Bold)
                }
            }
        }

        if (isLoading) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppleRed, modifier = Modifier.size(32.dp))
                }
            }
        } else if (songs.isEmpty()) {
            item {
                EmptyCategoryMessage("歌单中暂无歌曲")
            }
        } else {
            items(
                items = songs,
                key = { it.id },
                contentType = { "song_row" }
            ) { song ->
                SongListItemRow(
                    song = song,
                    onClick = { onSongClick(song) },
                    onDownloadClick = { onDownloadSong(song) },
                    onOpenDownloads = onOpenDownloads
                )
            }
        }
    }
}

/**
 * 仿 Apple Music 规范单曲列表（支持 13 维全量排序与多选）
 */
@Composable
private fun SongsListView(
    songs: List<UnifiedSong>,
    emptyMessage: String = "暂无歌曲",
    onSongClick: (UnifiedSong) -> Unit,
    onDownloadSong: (UnifiedSong) -> Unit,
    onOpenDownloads: () -> Unit,
    activeDownloadTasks: List<DownloadTask> = emptyList(),
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val dimensions = LocalAppDimensions.current
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedSongIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 13 维排序状态管理（直接在按键下方/上方展出下拉菜单）
    var currentSortOption by remember { mutableStateOf(SongSortOption.DATE_ADDED) }
    var currentSortOrder by remember { mutableStateOf(SortOrder.DESCENDING) }
    var isSortMenuOpen by remember { mutableStateOf(false) }

    val sortedSongs = remember(songs, currentSortOption, currentSortOrder) {
        val comparator = when (currentSortOption) {
            SongSortOption.ALBUM -> compareBy<UnifiedSong> { it.album.lowercase() }
            SongSortOption.ALBUM_ARTIST -> compareBy<UnifiedSong> { it.artist.lowercase() }
            SongSortOption.ARTIST -> compareBy<UnifiedSong> { it.artist.lowercase() }
            SongSortOption.DATE_ADDED -> compareBy<UnifiedSong> { it.id }
            SongSortOption.RELEASE_DATE, SongSortOption.YEAR -> compareBy<UnifiedSong> { it.album }
            SongSortOption.FORMAT -> compareBy<UnifiedSong> { it.format.lowercase() }
            SongSortOption.RATING -> compareBy<UnifiedSong> { if (it.isFavorite) 1 else 0 }
            SongSortOption.DATE_PLAYED -> compareBy<UnifiedSong> { it.id }
            SongSortOption.DURATION -> compareBy<UnifiedSong> { it.durationMs }
            SongSortOption.PLAY_COUNT -> compareBy<UnifiedSong> { it.durationMs }
            SongSortOption.FILENAME -> compareBy<UnifiedSong> { it.title.lowercase() }
            SongSortOption.FILE_SIZE -> compareBy<UnifiedSong> { it.bitRate }
        }
        if (currentSortOrder == SortOrder.ASCENDING) {
            songs.sortedWith(comparator)
        } else {
            songs.sortedWith(comparator.reversed())
        }
    }

    if (songs.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 80.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Text(
                text = emptyMessage,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 顶部操作栏：普通模式显示播放/多选/排序入口；多选模式显示全选与批量下载按键
            item {
                if (isSelectionMode) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = {
                                        selectedSongIds = if (selectedSongIds.size == sortedSongs.size) emptySet() else sortedSongs.map { it.id }.toSet()
                                    }
                                ) {
                                    Text(
                                        text = if (selectedSongIds.size == sortedSongs.size) "取消全选" else "全选",
                                        color = AppleRed,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "已选 ${selectedSongIds.size} 首",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = {
                                        val songsToDownload = sortedSongs.filter { it.id in selectedSongIds && it.downloadStatus != DownloadStatus.DOWNLOADED }
                                        songsToDownload.forEach(onDownloadSong)
                                        isSelectionMode = false
                                        selectedSongIds = emptySet()
                                    },
                                    enabled = selectedSongIds.isNotEmpty(),
                                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.ArrowCircleDown, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("批量下载 (${selectedSongIds.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                IconButton(onClick = {
                                    isSelectionMode = false
                                    selectedSongIds = emptySet()
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "退出多选", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { sortedSongs.firstOrNull()?.let(onSongClick) },
                                colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("全部播放", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }

                            Button(
                                onClick = { sortedSongs.shuffled().firstOrNull()?.let(onSongClick) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = AppleRed),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                            ) {
                                Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("随机播放", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { isSelectionMode = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Checklist, contentDescription = "多选", tint = AppleRed, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("批量", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppleRed)
                                }
                            }
                        }

                        // 排序触发胶囊条（显示当前排序维度与箭头，点击调出图二弹窗）
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "共 ${sortedSongs.size} 首歌曲",
                                style = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )

                            Box {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { isSortMenuOpen = true }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Sort,
                                            contentDescription = "排序",
                                            tint = AppleRed,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            text = "${currentSortOption.displayName} ${if (currentSortOrder == SortOrder.ASCENDING) "↑" else "↓"}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = AppleRed
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = isSortMenuOpen,
                                    onDismissRequest = { isSortMenuOpen = false },
                                    modifier = Modifier
                                        .widthIn(min = 230.dp, max = 290.dp)
                                        .heightIn(max = 440.dp)
                                ) {
                                    Text(
                                        text = "排序方式 (点击切换升降序)",
                                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant),
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.5.dp)
                                    SongSortOption.entries.forEach { option ->
                                        val isSelected = option == currentSortOption
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Box(modifier = Modifier.width(24.dp)) {
                                                        if (isSelected) {
                                                            Icon(Icons.Default.Check, contentDescription = null, tint = AppleRed, modifier = Modifier.size(16.dp))
                                                        }
                                                    }
                                                    Text(
                                                        text = option.displayName,
                                                        style = TextStyle(
                                                            fontSize = 14.sp,
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                            color = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurface
                                                        ),
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    if (isSelected) {
                                                        Icon(
                                                            imageVector = if (currentSortOrder == SortOrder.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                                            contentDescription = null,
                                                            tint = AppleRed,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                if (isSelected) {
                                                    currentSortOrder = if (currentSortOrder == SortOrder.ASCENDING) SortOrder.DESCENDING else SortOrder.ASCENDING
                                                } else {
                                                    currentSortOption = option
                                                }
                                                isSortMenuOpen = false
                                            },
                                            modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(10.dp))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            itemsIndexed(
                items = sortedSongs,
                key = { _, song -> song.id },
                contentType = { _, _ -> "song_list_row" }
            ) { index, song ->
                val activeTask = activeDownloadTasks.firstOrNull { it.song.id == song.id }
                val isDownloaded = song.downloadStatus == DownloadStatus.DOWNLOADED
                val isDownloading = activeTask != null || song.downloadStatus == DownloadStatus.DOWNLOADING
                val currentProgress = activeTask?.progress ?: song.downloadProgress
                val isSelected = song.id in selectedSongIds

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) AppleRed.copy(alpha = 0.1f) else Color.Transparent)
                        .clickable {
                            if (isSelectionMode) {
                                selectedSongIds = if (isSelected) selectedSongIds - song.id else selectedSongIds + song.id
                            } else {
                                onSongClick(song)
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSelectionMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { checked ->
                                selectedSongIds = if (checked) selectedSongIds + song.id else selectedSongIds - song.id
                            },
                            colors = CheckboxDefaults.colors(checkedColor = AppleRed)
                        )
                    } else {
                        Text(
                            text = "${index + 1}",
                            style = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium),
                            modifier = Modifier.width(28.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = TextStyle(
                                fontSize = 15.sp * dimensions.fontScale,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        if (isDownloaded) {
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
                                    text = "${song.artist} • ${formatDownloadedSongSpecs(song)}",
                                    fontSize = 11.sp,
                                    color = Color(0xFF34C759),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            Text(
                                text = "${song.artist} • ${song.format.uppercase()}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }

                    if (!isSelectionMode) {
                        if (isDownloaded) {
                            IconButton(onClick = { onSongClick(song) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.CheckCircle, contentDescription = "已离线", tint = Color(0xFF34C759), modifier = Modifier.size(22.dp))
                            }
                        } else if (isDownloading) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = AppleRed.copy(alpha = 0.12f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onOpenDownloads() }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        progress = { currentProgress },
                                        modifier = Modifier.size(16.dp),
                                        color = AppleRed,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "${(currentProgress * 100).toInt()}%",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppleRed
                                    )
                                }
                            }
                        } else {
                            IconButton(onClick = { onDownloadSong(song) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.ArrowCircleDown, contentDescription = "下载到本地", tint = AppleRed, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyCategoryMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 100.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun LibraryNavigationItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color = AppleRed,
    onClick: () -> Unit
) {
    val dimensions = LocalAppDimensions.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = TextStyle(
                    fontSize = 17.sp * dimensions.fontScale,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }
        }

        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}
