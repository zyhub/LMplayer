package com.lm.player.feature.search

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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.model.OnlineMusicSource
import com.lm.player.core.model.UnifiedSong
import com.lm.player.feature.home.SongListItemRow

/**
 * 仿 Apple Music 沉浸式全局搜索面板
 */
@Composable
fun LibrarySearchDialog(
    allSongs: List<UnifiedSong>,
    onSongClick: (UnifiedSong) -> Unit,
    onDownloadSong: (UnifiedSong) -> Unit = {},
    initialOnlineSource: OnlineMusicSource = OnlineMusicSource.KUWO,
    onOnlineSourceChanged: ((OnlineMusicSource) -> Unit)? = null,
    onOnlineSearch: (suspend (keyword: String, source: OnlineMusicSource) -> List<UnifiedSong>)? = null,
    onDismiss: () -> Unit
) {
    val dimensions = LocalAppDimensions.current
    var query by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf(initialOnlineSource) }
    var onlineResults by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
    var isSearchingOnline by remember { mutableStateOf(false) }

    LaunchedEffect(query, selectedSource) {
        if (query.isBlank() || onOnlineSearch == null) {
            onlineResults = emptyList()
            isSearchingOnline = false
            return@LaunchedEffect
        }
        val trimmed = query.trim()
        kotlinx.coroutines.delay(350)
        isSearchingOnline = true
        try {
            onlineResults = onOnlineSearch(trimmed, selectedSource)
        } catch (e: Exception) {
            onlineResults = emptyList()
        } finally {
            isSearchingOnline = false
        }
    }

    val searchResults = remember(query, allSongs) {
        if (query.isBlank()) {
            emptyList()
        } else {
            val q = query.trim().lowercase()
            allSongs.filter {
                it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
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
                // 1. 顶部搜索栏与取消按键
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = {
                            Text(
                                text = if (onOnlineSearch != null) "搜索媒体库及在线全网音乐..." else "搜索歌曲、歌手、专辑...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                fontSize = 15.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "搜索",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "清空",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        textStyle = TextStyle(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "取消",
                            color = AppleRed,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (onOnlineSearch != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "音源:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OnlineMusicSource.entries.forEach { src ->
                            val isSelected = src == selectedSource
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) AppleRed.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, AppleRed) else null,
                                modifier = Modifier.clickable {
                                    selectedSource = src
                                    onOnlineSourceChanged?.invoke(src)
                                }
                            ) {
                                Text(
                                    text = src.displayName,
                                    color = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 2. 搜索结果列表
                if (query.isBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 80.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (onOnlineSearch != null)
                                    "输入关键词，搜索 NAS 媒体库 (${allSongs.size} 首) 及在线全网曲库"
                                else
                                    "输入关键词搜索 NAS 媒体库中的全部 ${allSongs.size} 首歌曲",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else if (searchResults.isEmpty() && onlineResults.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 80.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        if (isSearchingOnline) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.dp, color = AppleRed)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "正在搜索媒体库及在线曲库...",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp
                                )
                            }
                        } else {
                            Text(
                                text = "未找到与「$query」匹配的歌曲或歌手",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 15.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 媒体库结果
                        if (searchResults.isNotEmpty()) {
                            item {
                                Text(
                                    text = "媒体库歌曲 (${searchResults.size})",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            }
                            items(
                                items = searchResults,
                                key = { "local_${it.id}" },
                                contentType = { "search_song_item" }
                            ) { song ->
                                SongListItemRow(
                                    song = song,
                                    onClick = {
                                        onSongClick(song)
                                        onDismiss()
                                    },
                                    onDownloadClick = { onDownloadSong(song) }
                                )
                            }
                        }

                        // 在线搜索结果
                        if (onlineResults.isNotEmpty() || isSearchingOnline) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 12.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "全网在线发现 · ${selectedSource.displayName} (${onlineResults.size})",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppleRed
                                    )
                                    if (isSearchingOnline) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 1.8.dp, color = AppleRed)
                                    }
                                }
                            }
                            items(
                                items = onlineResults,
                                key = { "online_${it.id}" },
                                contentType = { "search_online_song_item" }
                            ) { song ->
                                SongListItemRow(
                                    song = song,
                                    onClick = {
                                        onSongClick(song)
                                        onDismiss()
                                    },
                                    onDownloadClick = { onDownloadSong(song) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
