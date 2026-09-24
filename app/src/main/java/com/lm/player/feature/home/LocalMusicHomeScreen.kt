package com.lm.player.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.ArtistAvatarImage
import com.lm.player.core.designsystem.component.DownloadQualityChoiceDialog
import com.lm.player.core.designsystem.component.DownloadQualityDropdownMenu
import com.lm.player.core.designsystem.component.ServerSwitchDropdownButton
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.model.AudioQuality
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.DownloadTarget
import com.lm.player.core.model.DownloadTask
import com.lm.player.core.model.HomeScreenDisplayConfig
import com.lm.player.core.model.ServerConfig
import com.lm.player.core.model.ServerType
import com.lm.player.core.model.UnifiedAlbum
import com.lm.player.core.model.UnifiedArtist
import com.lm.player.core.model.UnifiedPlaylist
import com.lm.player.core.model.UnifiedSong

/**
 * 格式化已下载音频的技术参数与文件大小
 */
fun formatDownloadedSongSpecs(song: UnifiedSong): String {
    val formatStr = song.format.uppercase()
    val isLossless = formatStr in listOf("FLAC", "WAV", "ALAC", "APE", "DSD", "DSF") || song.bitRate >= 800
    val qualityTag = if (isLossless) "Hi-Res 无损" else if (song.bitRate >= 320) "极高音质" else "标准音质"
    val bitrateStr = if (song.bitRate > 0) "${song.bitRate} kbps" else if (isLossless) "920 kbps (无损)" else "320 kbps"

    val durationSec = (song.durationMs / 1000L).coerceAtLeast(180L)
    val rate = if (isLossless) 900 else song.bitRate.coerceAtLeast(320)
    val estMb = (durationSec * rate * 1024L / 8L) / (1024.0 * 1024.0)
    val sizeStr = "%.1f MB".format(java.util.Locale.US, estMb)

    return "$qualityTag • $formatStr • $bitrateStr • $sizeStr"
}

/**
 * 歌曲列表单行（已下载展示音质/码率/大小，末尾直显下载按键/进度）
 * 全局通用行组件，用于首页、资料库、搜索与歌单
 */
@Composable
fun SongListItemRow(
    song: UnifiedSong,
    isLocalOfflineMode: Boolean = false,
    activeDownloadTasks: List<DownloadTask> = emptyList(),
    isServerConnected: Boolean = true,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit = {},
    onDownloadWithOptions: ((UnifiedSong, DownloadTarget, AudioQuality) -> Unit)? = null,
    onOpenDownloads: () -> Unit = {}
) {
    val dimensions = LocalAppDimensions.current
    val activeTask = activeDownloadTasks.firstOrNull { it.song.id == song.id }
    val isDownloaded = song.downloadStatus == DownloadStatus.DOWNLOADED
    val isDownloading = activeTask != null || song.downloadStatus == DownloadStatus.DOWNLOADING
    val currentProgress = activeTask?.progress ?: song.downloadProgress

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArtworkImage(
            model = song.coverUrl,
            seedId = song.id,
            modifier = Modifier.size(48.dp),
            cornerRadius = 10.dp
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontSize = 15.sp * dimensions.fontScale,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )

            Spacer(modifier = Modifier.height(2.dp))

            if (isDownloaded) {
                // 已下载歌曲：展示丰富音质、码率、大小参数
                Text(
                    text = "${song.artist} • ${if (song.album.isNotBlank()) song.album else "单曲"}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                        text = formatDownloadedSongSpecs(song),
                        fontSize = 11.sp,
                        color = Color(0xFF34C759),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    text = "${song.artist} • ${if (song.album.isNotBlank()) song.album else "单曲"} (${song.format.uppercase()})",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        val hasLocal = (song.downloadStatus == DownloadStatus.DOWNLOADED) ||
                (!song.localFilePath.isNullOrBlank()) ||
                (song.serverId in listOf("local_storage", "local_folder", "local_saf"))
        val hasServer = (song.serverId == "lemon_music" ||
                (isServerConnected && song.serverId.isNotBlank() && song.serverId !in listOf("local_storage", "local_folder", "local_saf", "lemon_online")))

        if (onDownloadWithOptions != null) {
            com.lm.player.core.designsystem.component.SongSyncStatusTrailing(
                song = song,
                isDownloading = isDownloading,
                downloadProgress = currentProgress,
                isServerConnected = isServerConnected,
                hasLocal = hasLocal,
                hasServer = hasServer,
                onOpenDownloads = onOpenDownloads,
                onDownloadWithOptions = onDownloadWithOptions
            )
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
        } else if (isDownloaded) {
            IconButton(
                onClick = onClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "已离线",
                    tint = Color(0xFF34C759),
                    modifier = Modifier.size(22.dp)
                )
            }
        } else {
            IconButton(
                onClick = onDownloadClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowCircleDown,
                    contentDescription = "下载歌曲",
                    tint = AppleRed,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * LMPlayer 本地离线模式首页
 */
@Composable
fun LocalMusicHomeScreen(
    serverName: String = "本地模式",
    recentSongs: List<UnifiedSong>,
    configuredServers: List<ServerConfig> = emptyList(),
    blurAlpha: Float = 0.85f,
    recentlyAddedSongs: List<UnifiedSong> = emptyList(),
    recentlyPlayedSongs: List<UnifiedSong> = emptyList(),
    discoverPlaylists: List<UnifiedPlaylist> = emptyList(),
    homeDisplayConfig: HomeScreenDisplayConfig = HomeScreenDisplayConfig(),
    activeDownloadTasks: List<DownloadTask> = emptyList(),
    activeDownloadCount: Int = 0,
    onSongClick: (UnifiedSong, List<UnifiedSong>?) -> Unit = { _, _ -> },
    onDownloadSong: (UnifiedSong) -> Unit = {},
    onDownloadSongWithOptions: (UnifiedSong, DownloadTarget, AudioQuality) -> Unit = { song, _, _ -> onDownloadSong(song) },
    onSelectLocalServer: () -> Unit = {},
    onSelectServer: (ServerConfig) -> Unit = {},
    onSyncNow: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onGoToSettings: () -> Unit = {},
    onPlaylistClick: ((UnifiedPlaylist) -> Unit)? = null,
    onScanLocalMedia: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val dimensions = LocalAppDimensions.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = blurAlpha)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.10f)

    var songForDownloadChoice by remember { mutableStateOf<UnifiedSong?>(null) }

    // 本地模式仅筛选真正已下载到本地或扫描导入且物理文件存在的离线音频
    val localDownloadedSongs = remember(recentSongs) {
        recentSongs.filter {
            val hasValidFile = !it.localFilePath.isNullOrBlank() && java.io.File(it.localFilePath).let { f -> f.exists() && f.length() > 0 }
            (it.downloadStatus == DownloadStatus.DOWNLOADED && hasValidFile) ||
            (it.serverId in listOf("local_storage", "local_saf", "local_folder") && hasValidFile)
        }
    }

    // 严密约束：本地已下载界面绝不回退至包含全量在线服务器曲目的 recentSongs
    val activeSongSource = localDownloadedSongs

    val effectiveRecentlyAdded = remember(activeSongSource) {
        activeSongSource.sortedByDescending { it.addedTimestamp }.take(20)
    }


    // 从真实歌曲列表中动态提取专辑集合
    val albums = remember(activeSongSource) {
        activeSongSource.filter { it.album.isNotBlank() }
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

    // 从真实歌曲列表中动态提取艺术家集合
    val artists = remember(activeSongSource) {
        activeSongSource.filter { it.artist.isNotBlank() }
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

    Box(modifier = Modifier.fillMaxSize()) {
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
        // 1. 顶部 Header (大标题 + 下载管理胶囊 + 音源下拉切换)
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
                        text = "本地音乐",
                        style = TextStyle(
                            fontSize = 32.sp * dimensions.fontScale,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    )
                    Text(
                        text = if (activeSongSource.isEmpty()) "暂无已下载歌曲" else "已收录 ${activeSongSource.size} 首已下载歌曲 · ${albums.size} 张专辑",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 顶部下载管理胶囊按键 (毛玻璃效果)
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

        if (activeSongSource.isEmpty()) {
            item {
                EmptyStateWelcomeCard(
                    onGoToSettings = onGoToSettings,
                    onScanLocalMedia = onScanLocalMedia
                )
            }
        } else {
            // A. 最近添加
            if (homeDisplayConfig.showRecentlyAdded && effectiveRecentlyAdded.isNotEmpty()) {
                item {
                    Column {
                        SectionHeader(
                            title = "最近添加",
                            subtitle = "本机最新入库音频"
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(
                                items = effectiveRecentlyAdded,
                                key = { it.id },
                                contentType = { "recent_song_card" }
                            ) { song ->
                                RecentlyAddedSongCard(
                                    song = song,
                                    isLocalOfflineMode = true,
                                    onClick = { onSongClick(song, recentSongs.take(20)) }
                                )
                            }
                        }
                    }
                }
            }

            // B. 专辑合集
            if (homeDisplayConfig.showAlbums && albums.isNotEmpty()) {
                item {
                    Column {
                        SectionHeader(
                            title = "本地专辑",
                            subtitle = "已收录唱片合集"
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(
                                items = albums,
                                key = { it.id },
                                contentType = { "album_card" }
                            ) { album ->
                                AlbumCardItem(
                                    album = album,
                                    onClick = {
                                        val albumSongs = activeSongSource.filter { it.album == album.title }
                                        albumSongs.firstOrNull()?.let { onSongClick(it, albumSongs) }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // C. 歌手合集
            if (homeDisplayConfig.showArtists && artists.isNotEmpty()) {
                item {
                    Column {
                        SectionHeader(
                            title = "本地歌手",
                            subtitle = "本地音乐人"
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(
                                items = artists,
                                key = { it.id },
                                contentType = { "artist_circle" }
                            ) { artist ->
                                ArtistCircleItem(
                                    artist = artist,
                                    onClick = {
                                        val artistSongs = activeSongSource.filter { it.artist == artist.name }
                                        artistSongs.firstOrNull()?.let { onSongClick(it, artistSongs) }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // D. 所有曲目
            item {
                SectionHeader(
                    title = "所有曲目",
                    subtitle = "共 ${activeSongSource.size} 首本地音频"
                )
            }

            items(
                items = activeSongSource,
                key = { it.id },
                contentType = { "song_row" }
            ) { song ->
                val isServerOk = configuredServers.any { it.type == ServerType.LEMON_MUSIC }
                SongListItemRow(
                    song = song,
                    isLocalOfflineMode = true,
                    activeDownloadTasks = activeDownloadTasks,
                    isServerConnected = isServerOk,
                    onClick = { onSongClick(song, activeSongSource) },
                    onDownloadClick = { songForDownloadChoice = song },
                    onDownloadWithOptions = { s, target, quality ->
                        onDownloadSongWithOptions(s, target, quality)
                    },
                    onOpenDownloads = onOpenDownloads
                )
            }
        }
    }

    if (songForDownloadChoice != null) {
            DownloadQualityChoiceDialog(
                song = songForDownloadChoice!!,
                onDismiss = { songForDownloadChoice = null },
                onConfirm = { target, quality ->
                    onDownloadSongWithOptions(songForDownloadChoice!!, target, quality)
                    songForDownloadChoice = null
                }
            )
        }
    }
}

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

@Composable
private fun RecentlyAddedSongCard(
    song: UnifiedSong,
    isLocalOfflineMode: Boolean = false,
    onClick: () -> Unit
) {
    val dimensions = LocalAppDimensions.current
    val isDownloaded = song.downloadStatus == DownloadStatus.DOWNLOADED

    Column(
        modifier = Modifier
            .width(135.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(135.dp)
                .shadow(3.dp, RoundedCornerShape(14.dp))
        ) {
            AlbumArtworkImage(
                model = song.coverUrl,
                seedId = song.id,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 14.dp
            )

            Surface(
                shape = RoundedCornerShape(topStart = 0.dp, bottomEnd = 0.dp, topEnd = 14.dp, bottomStart = 8.dp),
                color = Color.Black.copy(alpha = 0.65f),
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Text(
                    text = if (isDownloaded) "本地已存" else song.format.uppercase(),
                    color = if (isDownloaded) AppleRed else Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = song.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = 14.sp * dimensions.fontScale,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = song.artist,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

@Composable
private fun AlbumCardItem(
    album: UnifiedAlbum,
    onClick: () -> Unit
) {
    val dimensions = LocalAppDimensions.current
    Column(
        modifier = Modifier
            .width(130.dp)
            .clickable(onClick = onClick)
    ) {
        AlbumArtworkImage(
            model = album.coverUrl,
            seedId = album.id,
            modifier = Modifier
                .size(130.dp)
                .shadow(3.dp, RoundedCornerShape(12.dp)),
            cornerRadius = 12.dp
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
            text = "${album.artist} • ${album.songCount} 首",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

@Composable
private fun ArtistCircleItem(
    artist: UnifiedArtist,
    onClick: () -> Unit
) {
    val dimensions = LocalAppDimensions.current
    Column(
        modifier = Modifier
            .width(85.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ArtistAvatarImage(
            model = artist.avatarUrl,
            seedId = artist.name,
            modifier = Modifier
                .size(75.dp)
                .shadow(2.dp, CircleShape)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = artist.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = 12.sp * dimensions.fontScale,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        )
    }
}

@Composable
private fun EmptyStateWelcomeCard(
    onGoToSettings: () -> Unit,
    onScanLocalMedia: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(id = com.lm.player.R.drawable.app_logo),
                contentDescription = "LMPlayer Logo",
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "欢迎使用 柠檬音乐 (LMPlayer)",
                style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "当前处于本地模式。您可以扫描本机存储中的音乐文件，或前往设置登录柠檬音乐服务器获取海量在线乐库与推荐歌单。",
                style = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center),
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (onScanLocalMedia != null) {
                Button(
                    onClick = onScanLocalMedia,
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("一键扫描本地音乐", fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            OutlinedButton(
                onClick = onGoToSettings,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(46.dp)
            ) {
                Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("前往设置登录柠檬音乐", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
