package com.lm.player.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.model.UnifiedPlaylist
import com.lm.player.core.model.UnifiedSong

/**
 * 歌单里有哪些歌（内存登记）：服务器 / 云端歌单的歌是现拉的、不进本地 playlist_songs 表，
 * 只查表的话「从歌单里播放的歌」在加入歌单时不会显示「已加入」。打开歌单、加入歌单时在这里登记，
 * 按歌曲 id 与「歌名|歌手」两种键匹配（播放时歌曲 id 可能被匹配成本地 / 服务器曲目）。
 */
object PlaylistMembership {
    private val members = androidx.compose.runtime.mutableStateMapOf<String, Set<String>>()

    private fun norm(s: String) = s.lowercase().replace(Regex("""[\s·・()（）\[\]【】'"\-_]"""), "")
    private fun keysOf(song: UnifiedSong): List<String> = buildList {
        add("id:" + song.id)
        val t = norm(song.title)
        if (t.isNotBlank()) add("ta:" + t + "|" + norm(song.artist))
    }

    /** 打开歌单拿到歌曲列表时调用（整份替换） */
    fun record(playlistId: String, songs: List<UnifiedSong>) {
        if (playlistId.isBlank()) return
        members[playlistId] = songs.flatMapTo(HashSet()) { keysOf(it) }
    }

    /** 加入歌单成功后调用 */
    fun add(playlistId: String, song: UnifiedSong) {
        if (playlistId.isBlank()) return
        members[playlistId] = (members[playlistId] ?: emptySet()) + keysOf(song)
    }

    fun playlistsContaining(song: UnifiedSong): Set<String> {
        val keys = keysOf(song)
        return members.entries.filter { (_, v) -> keys.any { it in v } }.mapTo(HashSet()) { it.key }
    }
}

/** 已收录这首歌的歌单 id：本地表（实时）+ 内存登记的服务器歌单；只在弹窗 / 菜单展开时订阅 */
@Composable
private fun rememberJoinedPlaylistIds(song: UnifiedSong): Set<String> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val songId = song.id
    val flow = remember(songId) {
        com.lm.player.core.database.ZdsDatabase.getInstance(context.applicationContext)
            .playlistDao()
            .getPlaylistIdsContainingSongFlow(songId)
    }
    val ids by flow.collectAsState(initial = emptyList())
    // 本地表 + 内存登记（服务器歌单）
    val registered = PlaylistMembership.playlistsContaining(song)
    return remember(ids, registered) { ids.toSet() + registered }
}

/**
 * 添加歌曲至歌单弹窗（精简版，液态玻璃高档时是玻璃面板）：
 * 一行头部（小封面 + 标题 + 歌名）；首行「新建歌单」原位展开输入框；歌单多时出搜索；
 * 歌单行带小封面与云端标记，已收录的置灰并打勾。
 */
@Composable
fun AddToPlaylistDialog(
    song: UnifiedSong,
    playlists: List<UnifiedPlaylist>,
    isServerConnected: Boolean = false,
    onSelectPlaylist: (UnifiedPlaylist, UnifiedSong) -> Unit,
    onCreatePlaylistAndAdd: (String, UnifiedSong) -> Unit,
    onDismiss: () -> Unit
) {
    var isCreatingNew by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val joinedIds = rememberJoinedPlaylistIds(song)

    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val onSurface = MaterialTheme.colorScheme.onSurface
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    val fieldBg = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)
    val createFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val shown = remember(playlists, query) {
        val q = query.trim()
        if (q.isEmpty()) playlists else playlists.filter { it.name.contains(q, ignoreCase = true) }
    }
    val createNow = {
        val name = newPlaylistName.trim()
        if (name.isNotEmpty()) {
            onCreatePlaylistAndAdd(name, song)
            onDismiss()
        }
    }
    LaunchedEffect(isCreatingNew) { if (isCreatingNew) runCatching { createFocus.requestFocus() } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        com.lm.player.core.designsystem.glass.GlassDialogPanel(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .widthIn(max = 320.dp),
            cornerRadius = 22.dp
        ) {
            // 1. 头部
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AlbumArtworkImage(
                    model = song.coverUrl,
                    seedId = song.id,
                    targetSize = 120,
                    cornerRadius = 8.dp,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("添加到歌单", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = onSurface)
                    Text(song.title, fontSize = 11.sp, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(fieldBg)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = sub, modifier = Modifier.size(14.dp))
                }
            }

            // 2. 歌单多时：搜索
            if (playlists.size > 8) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 6.dp)
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(fieldBg)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = sub, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("搜索歌单", fontSize = 12.sp, color = sub.copy(alpha = 0.7f))
                        androidx.compose.foundation.text.BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = onSurface),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(AppleRed),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp)
            ) {
                // 3. 新建歌单（原位展开输入）
                item(key = "__create") {
                    if (!isCreatingNew) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { isCreatingNew = true }
                                .padding(horizontal = 6.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AppleRed.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = AppleRed, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Text("新建歌单", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppleRed)
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                                .height(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(fieldBg)
                                .border(1.dp, AppleRed.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .padding(start = 10.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (newPlaylistName.isEmpty()) Text("新歌单名称", fontSize = 13.sp, color = sub.copy(alpha = 0.7f))
                                androidx.compose.foundation.text.BasicTextField(
                                    value = newPlaylistName,
                                    onValueChange = { newPlaylistName = it.take(30) },
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = onSurface, fontWeight = FontWeight.Medium),
                                    cursorBrush = androidx.compose.ui.graphics.SolidColor(AppleRed),
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { createNow() }),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(createFocus)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        isCreatingNew = false
                                        newPlaylistName = ""
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "取消", tint = sub, modifier = Modifier.size(16.dp))
                            }
                            val ok = newPlaylistName.isNotBlank()
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(if (ok) AppleRed else AppleRed.copy(alpha = 0.3f))
                                    .then(if (ok) Modifier.clickable { createNow() } else Modifier),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Check, contentDescription = "创建并添加", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                if (shown.isEmpty()) {
                    item(key = "__empty") {
                        Text(
                            text = if (playlists.isEmpty()) "还没有歌单" else "没有匹配的歌单",
                            fontSize = 12.sp,
                            color = sub.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 16.dp)
                        )
                    }
                }

                // 4. 现有歌单
                items(shown, key = { it.id }) { pl ->
                    val joined = pl.id in joinedIds
                    val cover = remember(pl.previewCovers, pl.coverUrl) {
                        pl.previewCovers.firstOrNull { it.isNotBlank() }
                            ?: pl.coverUrl.split("|").firstOrNull { it.isNotBlank() }.orEmpty()
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            // 已收录这首歌的歌单：置灰、不可再点
                            .graphicsLayer { alpha = if (joined) 0.45f else 1f }
                            .then(
                                if (joined) Modifier else Modifier.clickable {
                                    onSelectPlaylist(pl, song)
                                    onDismiss()
                                }
                            )
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AlbumArtworkImage(
                            model = cover,
                            seedId = pl.id,
                            targetSize = 120,
                            cornerRadius = 8.dp,
                            modifier = Modifier.size(38.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = pl.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (pl.isOnline) {
                                    Icon(Icons.Default.Cloud, contentDescription = "云端", tint = sub, modifier = Modifier.size(11.dp))
                                    Spacer(Modifier.width(3.dp))
                                }
                                Text("${pl.songCount} 首", fontSize = 11.sp, color = sub)
                            }
                        }
                        Icon(
                            imageVector = if (joined) Icons.Default.Check else Icons.Default.Add,
                            contentDescription = if (joined) "已加入" else "添加",
                            tint = if (joined) sub else AppleRed,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「加入歌单」原位弹出菜单（精简版，液态玻璃）：
 * 顶部标题 + 右侧「新建」小胶囊；点新建在原位出现一行输入框（回车 / ✓ 创建并收录）；下面是可滚动的歌单列表，点一下即加入。
 */
@Composable
fun AddToPlaylistDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    song: UnifiedSong,
    playlists: List<UnifiedPlaylist>,
    isServerConnected: Boolean = false,
    onSelectPlaylist: (UnifiedPlaylist, UnifiedSong) -> Unit,
    onCreatePlaylistAndAdd: (String, UnifiedSong) -> Unit,
    modifier: Modifier = Modifier
) {
    var isCreatingNew by remember(expanded) { mutableStateOf(false) }
    var newPlaylistName by remember(expanded) { mutableStateOf("") }
    val joinedIds = if (expanded) rememberJoinedPlaylistIds(song) else emptySet()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    val createNow = {
        if (newPlaylistName.isNotBlank()) {
            onCreatePlaylistAndAdd(newPlaylistName.trim(), song)
            onDismissRequest()
        }
    }

    com.lm.player.core.designsystem.glass.AdaptiveDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.width(256.dp),
        scrollable = false,
        // 播放页「加入歌单」按钮在下半部：向上展开
        preferAbove = true
    ) {
        // 1. 标题行：加入歌单 + 歌名；右侧「新建」
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 10.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("加入歌单", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = onSurface)
                Text(
                    text = song.title,
                    fontSize = 11.sp,
                    color = sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!isCreatingNew) {
                Row(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(AppleRed.copy(alpha = 0.14f))
                        .clickable { isCreatingNew = true }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = AppleRed, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("新建", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppleRed)
                }
            }
        }

        // 2. 新建：原位一行输入框
        if (isCreatingNew) {
            val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp)
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(onSurface.copy(alpha = 0.07f))
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (newPlaylistName.isEmpty()) {
                        Text(
                            text = if (isServerConnected) "新歌单名称（双端同步）" else "新歌单名称",
                            fontSize = 13.sp,
                            color = sub.copy(alpha = 0.7f),
                            maxLines = 1
                        )
                    }
                    androidx.compose.foundation.text.BasicTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = onSurface),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(AppleRed),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { createNow() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (newPlaylistName.isNotBlank()) AppleRed else onSurface.copy(alpha = 0.12f))
                        .clickable(enabled = newPlaylistName.isNotBlank()) { createNow() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, contentDescription = "创建并收录", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
            LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
            Spacer(Modifier.height(4.dp))
        }

        Box(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .fillMaxWidth()
                .height(0.5.dp)
                .background(onSurface.copy(alpha = 0.10f))
        )

        // 3. 歌单列表（超过高度内部滚动）
        if (playlists.isEmpty()) {
            Text(
                text = "还没有歌单，点右上角「新建」",
                fontSize = 12.sp,
                color = sub,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 264.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                playlists.forEach { pl ->
                    val joined = pl.id in joinedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .padding(horizontal = 6.dp)
                            // 已收录这首歌的歌单：置灰、不可再点，右侧显示「已加入」
                            .graphicsLayer { alpha = if (joined) 0.45f else 1f }
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !joined) {
                                onSelectPlaylist(pl, song)
                                onDismissRequest()
                            }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (pl.isOnline) Icons.Default.Cloud else Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = if (pl.isOnline) "云歌单" else "本地歌单",
                            tint = if (pl.isOnline) AppleRed else sub,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = pl.name,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(text = if (joined) "已加入" else "${pl.songCount}", fontSize = 11.sp, color = sub)
                    }
                }
            }
        }
    }
}
