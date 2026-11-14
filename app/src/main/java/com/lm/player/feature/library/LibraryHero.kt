package com.lm.player.feature.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.glass.LiquidBottomTab
import com.lm.player.core.designsystem.glass.LiquidBottomTabs
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.model.UnifiedSong

/**
 * 其它页面（播放页点歌手 / 专辑）请求资料库打开某个歌手 / 专辑详情页。
 * 宿主先切到资料库，资料库读到请求后打开对应详情页并清空请求。
 */
object LibraryNavigator {
    sealed interface Request {
        data class Artist(val name: String) : Request
        data class Album(val title: String, val artist: String, val albumId: String) : Request
    }

    var pending by mutableStateOf<Request?>(null)
}

/** 歌手 / 专辑详情页顶部的大图信息 */
internal data class LibraryHero(
    val kind: Kind,
    val title: String,
    val imageUrl: String,
    val subtitle: String,
    val intro: String,
    /** 专辑页：所属歌手（点一下进歌手页）；歌手页：歌手名 */
    val artist: String,
    /** 从「歌手专辑」在线结果进入的专辑：在线曲目直接按这个专辑 id 拉取（lemon_discover_album_{平台}_{id}） */
    val onlineAlbumId: String = ""
) {
    enum class Kind { ARTIST, ALBUM }
}

private fun UnifiedSong.isLosslessTrack(): Boolean =
    format.lowercase() in setOf("flac", "wav", "alac", "ape", "dsd", "dsf") || bitRate >= 800

/**
 * 歌手简介：服务端和在线目录都没有歌手百科接口，这里用资料库里的数据整理一段介绍
 * （收录多少首、分布在哪些专辑、总时长、无损数量）。
 */
internal fun buildArtistIntro(name: String, songs: List<UnifiedSong>): String {
    if (songs.isEmpty()) return "资料库里还没有「$name」的歌曲。"
    val albums = songs.map { it.album.trim() }.filter { it.isNotBlank() }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
    val minutes = songs.sumOf { it.durationMs.coerceAtLeast(0L) } / 60_000L
    val lossless = songs.count { it.isLosslessTrack() }
    return buildString {
        append("资料库收录 $name 的 ${songs.size} 首歌曲")
        if (albums.isNotEmpty()) append("，分布在 ${albums.size} 张专辑")
        if (minutes > 0) append("，总时长约 $minutes 分钟")
        append("。")
        if (albums.isNotEmpty()) {
            append("收录最多的专辑：")
            append(albums.take(3).joinToString("、") { "《${it.key}》" })
            append("。")
        }
        if (lossless > 0) append("其中 $lossless 首为无损音质。")
    }
}

internal fun buildAlbumIntro(title: String, artist: String, songs: List<UnifiedSong>, year: Int?): String {
    val minutes = songs.sumOf { it.durationMs.coerceAtLeast(0L) } / 60_000L
    val lossless = songs.count { it.isLosslessTrack() }
    return buildString {
        append("《$title》")
        if (artist.isNotBlank()) append("是 $artist 的专辑")
        if (year != null && year > 0) append("，发行于 $year 年")
        append("。")
        if (songs.isEmpty()) {
            append("资料库里还没有这张专辑的歌曲。")
        } else {
            append("资料库收录 ${songs.size} 首")
            if (minutes > 0) append("，时长约 $minutes 分钟")
            append("。")
            if (lossless > 0) append(if (lossless == songs.size) "全部为无损音质。" else "其中 $lossless 首为无损音质。")
        }
    }
}

/**
 * 歌手 / 专辑详情页头部：左边图（歌手圆形、专辑圆角方形），右边名称 / 副标题 / 可展开的简介。
 * 列表往下滑后由 [LibraryHeroTopBar] 的小胶囊接替（只显示图、名称和歌曲数）。
 */
@Composable
internal fun LibraryHeroHeader(
    hero: LibraryHero,
    onArtistClick: (String) -> Unit,
    /** 歌手页：点「N 张专辑」进入歌手专辑页 */
    onAlbumsClick: (() -> Unit)? = null,
    albumCount: Int = 0,
    /** 顶栏已显示名称时为 false，这里不再重复大标题 */
    showTitle: Boolean = true
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val isArtist = hero.kind == LibraryHero.Kind.ARTIST
    var expanded by remember(hero.title) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        AlbumArtworkImage(
            model = hero.imageUrl,
            seedId = hero.title,
            cornerRadius = if (isArtist) 60.dp else 16.dp,
            targetSize = 480,
            modifier = Modifier.size(120.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isArtist) "歌手" else "专辑",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppleRed
            )
            if (showTitle) Text(
                text = hero.title,
                fontSize = 20.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold,
                color = onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (hero.subtitle.isNotBlank()) {
                Text(
                    text = hero.subtitle,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (!isArtist && hero.artist.isNotBlank()) AppleRed.copy(alpha = 0.9f) else onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (!isArtist && hero.artist.isNotBlank()) {
                        Modifier
                            .padding(top = 2.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onArtistClick(hero.artist) }
                    } else Modifier.padding(top = 2.dp)
                )
            }
            if (isArtist && onAlbumsClick != null) {
                Row(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .height(26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(AppleRed.copy(alpha = 0.12f))
                        .clickable(onClick = onAlbumsClick)
                        .padding(start = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (albumCount > 0) "$albumCount 张专辑" else "查看专辑",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppleRed
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = AppleRed,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (hero.intro.isNotBlank()) {
                Text(
                    text = hero.intro,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { expanded = !expanded }
                )
            }
        }
    }
}

/**
 * 歌手 / 专辑详情页的顶栏：返回键常驻；头部滑出屏幕后，中间收成一颗小胶囊（图 + 名称 + 歌曲数），
 * 与首页顶部收缩的方式一致。[collapse] 0 = 头部完整可见，1 = 已滑走。
 */
@Composable
internal fun LibraryHeroTopBar(
    hero: LibraryHero,
    songCount: Int,
    collapse: () -> Float,
    onBack: () -> Unit,
    /** 点胶囊：回到顶部 */
    onTapCapsule: (() -> Unit)? = null,
    /** 顶栏右侧（返回键对面的空白处）的按钮，如音乐平台选择 */
    trailing: (@Composable () -> Unit)? = null,
    /** 顶栏居中内容（资料库 / 在线切换）：未滑动时显示，滑动后淡出、换成居中的小胶囊 */
    center: (@Composable () -> Unit)? = null,
    showCenter: Boolean = false
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val isArtist = hero.kind == LibraryHero.Kind.ARTIST
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = onSurface)
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .graphicsLayer {
                        val c = collapse()
                        alpha = c
                        val s = 0.82f + 0.18f * c
                        scaleX = s
                        scaleY = s
                        translationY = (1f - c) * 10.dp.toPx()
                    }
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(onSurface.copy(alpha = 0.07f))
                    .then(if (onTapCapsule != null && !(center != null && showCenter)) Modifier.clickable(onClick = onTapCapsule) else Modifier)
                    .padding(start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AlbumArtworkImage(
                    model = hero.imageUrl,
                    seedId = hero.title,
                    cornerRadius = if (isArtist) 14.dp else 7.dp,
                    targetSize = 120,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = hero.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 170.dp)
                )
                Text(
                    text = " · $songCount 首",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            if (center != null && showCenter) {
                Box(
                    modifier = Modifier.graphicsLayer {
                        val c = collapse()
                        alpha = (1f - c / 0.6f).coerceIn(0f, 1f)
                        val s = 1f - 0.12f * c
                        scaleX = s
                        scaleY = s
                    },
                    contentAlignment = Alignment.Center
                ) { center() }
            }
        }
        if (trailing != null) {
            trailing()
        } else {
            // 与左侧返回键等宽，让小胶囊居中
            Spacer(Modifier.width(40.dp))
        }
    }
}

/**
 * 资料库列表页（全部歌曲 / 歌单 / 歌手 / 专辑 / 文件夹 / 本地下载 / 歌单与文件夹详情…）滚动后的收缩顶栏：
 * 与歌手页一样，返回键 + 居中一颗小胶囊（标题 · 数量）；点胶囊回到顶部并展开完整顶栏。
 */
@Composable
internal fun LibraryListCompactBar(
    title: String,
    info: String,
    onBack: () -> Unit,
    onTapCapsule: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = onSurface)
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(onSurface.copy(alpha = 0.07f))
                    .clickable(onClick = onTapCapsule)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 190.dp)
                )
                if (info.isNotBlank()) {
                    Text(
                        text = " · $info",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
        // 与左侧返回键等宽，让小胶囊居中
        Spacer(Modifier.width(40.dp))
    }
}

/** 二级页右上角的小放大镜 */
@Composable
internal fun LibrarySearchIconButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Search, contentDescription = "搜索", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
    }
}

/** 点放大镜后从右侧展开的搜索框（只过滤当前列表，不走网络） */
@Composable
internal fun LibrarySubViewSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        appear.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 420f))
    }
    Row(
        modifier = modifier
            .graphicsLayer {
                val p = appear.value
                alpha = p.coerceIn(0f, 1f)
                scaleX = 0.6f + 0.4f * p
                transformOrigin = TransformOrigin(1f, 0.5f)
            }
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(onSurface.copy(alpha = 0.07f))
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = sub, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(placeholder, fontSize = 13.5.sp, color = sub.copy(alpha = 0.7f), maxLines = 1)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = onSurface),
                cursorBrush = SolidColor(AppleRed),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, contentDescription = "关闭搜索", tint = sub, modifier = Modifier.size(16.dp))
        }
    }
}

/** 搜索匹配：忽略大小写，歌名 / 歌手 / 专辑任一包含即可 */
internal fun UnifiedSong.matchesQuery(q: String): Boolean =
    title.contains(q, ignoreCase = true) || artist.contains(q, ignoreCase = true) || album.contains(q, ignoreCase = true)

// =====================================================================================
// 文件夹 / 歌单歌曲排序
// =====================================================================================

/** [defaultDescending] 为该方式默认的方向；再点一次同一方式就反过来 */
internal enum class SubSongSort(val label: String, val defaultDescending: Boolean) {
    DATE("日期", true),
    INDEX("序号", false),
    TITLE("歌名", false),
    ARTIST("歌手", false),
    DURATION("时长", true),
    SIZE("大小", true)
}

/** 歌曲占用大小：本地文件取真实大小，其余按码率 × 时长估算 */
internal fun estimateSongBytes(song: UnifiedSong): Long {
    val path = song.localFilePath
    if (!path.isNullOrBlank() && !path.startsWith("content://")) {
        val len = runCatching { java.io.File(path).length() }.getOrDefault(0L)
        if (len > 0L) return len
    }
    return song.bitRate.coerceAtLeast(0).toLong() * 125L * (song.durationMs.coerceAtLeast(0L) / 1000L)
}

/**
 * 排序：
 * - 日期（默认，新 → 旧）：按加入时间；整张列表都没有时间时按原顺序倒过来（歌单 / 文件夹一般按添加先后排列，倒过来即最新在上）；
 * - 序号：原顺序；歌名 / 歌手：按中文拼音；时长 / 大小：大的在前。
 */
internal fun sortSubViewSongs(
    songs: List<UnifiedSong>,
    mode: SubSongSort,
    reversed: Boolean,
    sizes: Map<String, Long>
): List<UnifiedSong> {
    val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
    val base: List<UnifiedSong> = when (mode) {
        SubSongSort.DATE -> if (songs.none { it.addedTimestamp > 0L }) {
            songs.asReversed().toList()
        } else {
            songs.withIndex()
                .sortedWith(compareByDescending<IndexedValue<UnifiedSong>> { it.value.addedTimestamp }.thenByDescending { it.index })
                .map { it.value }
        }
        SubSongSort.INDEX -> songs
        SubSongSort.TITLE -> songs.sortedWith(compareBy(collator) { it.title })
        SubSongSort.ARTIST -> songs.sortedWith(compareBy<UnifiedSong, String>(collator) { it.artist }.thenBy(collator) { it.title })
        SubSongSort.DURATION -> songs.sortedByDescending { it.durationMs }
        SubSongSort.SIZE -> songs.sortedByDescending { sizes[it.id] ?: (it.bitRate.toLong() * it.durationMs) }
    }
    return if (reversed) base.asReversed().toList() else base
}

/** 二级页右上角的排序按钮 + 玻璃下拉菜单（再点同一方式切换升 / 降序） */
@Composable
internal fun LibrarySortButton(
    mode: SubSongSort,
    reversed: Boolean,
    onSelect: (SubSongSort) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(onSurface.copy(alpha = 0.06f))
                .clickable { open = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序", tint = onSurface, modifier = Modifier.size(18.dp))
        }
        com.lm.player.core.designsystem.glass.AdaptiveDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.width(184.dp)
        ) {
            Text(
                text = "排序方式（再点切换方向）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )
            SubSongSort.entries.forEach { m ->
                val selected = m == mode
                val descending = m.defaultDescending != (selected && reversed)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clickable {
                            onSelect(m)
                            open = false
                        }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = m.label + if (m == SubSongSort.DATE) "（默认）" else "",
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) AppleRed else onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (selected) {
                        Icon(
                            imageVector = if (descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                            contentDescription = if (descending) "降序" else "升序",
                            tint = AppleRed,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「本地下载」页的操作条（与「全部歌曲」的「播放全部 + 多选」同一种布局）：
 * 平时：播放全部（N） + 管理；管理中：全选 / 删除（n） / 完成。
 */
@Composable
internal fun DownloadedSongsActionBar(
    totalCount: Int,
    managing: Boolean,
    selectedCount: Int,
    allSelected: Boolean,
    onPlayAll: () -> Unit,
    onEnterManage: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onExitManage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val borderColor = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!managing) {
            androidx.compose.material3.Button(
                onClick = onPlayAll,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = AppleRed),
                shape = RoundedCornerShape(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(androidx.compose.material.icons.Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("播放全部 ($totalCount)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            androidx.compose.material3.OutlinedButton(
                onClick = onEnterManage,
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(androidx.compose.material.icons.Icons.Default.Checklist, contentDescription = null, tint = AppleRed, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("管理", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            }
        } else {
            androidx.compose.material3.OutlinedButton(
                onClick = onToggleSelectAll,
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (allSelected) AppleRed else borderColor),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(
                    imageVector = if (allSelected) androidx.compose.material.icons.Icons.Default.CheckBox else androidx.compose.material.icons.Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = AppleRed,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (allSelected) "取消全选" else "全选 ($totalCount)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (allSelected) AppleRed else MaterialTheme.colorScheme.onSurface
                )
            }
            androidx.compose.material3.Button(
                onClick = onDelete,
                enabled = selectedCount > 0,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(androidx.compose.material.icons.Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("删除 ($selectedCount)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            androidx.compose.material3.TextButton(
                onClick = onExitManage,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text("完成", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 歌手 / 专辑页的歌曲来源 */
internal enum class HeroDataSource { LIBRARY, ONLINE }

/** 歌手 / 专辑页在线结果一页（每页 99 首）：total = 平台给的总数（上限 999，-1 = 平台没给），hasMore = 还能继续往下加载 */
data class HeroOnlinePage(
    val songs: List<UnifiedSong>,
    val total: Int,
    val hasMore: Boolean
)

/** 在线结果每页条数 / 最多条数 */
const val HERO_ONLINE_PAGE_SIZE = 99
const val HERO_ONLINE_MAX = 999

/**
 * 资料库 / 在线左右切换（与播放页「歌曲 | 歌词」同一套）：液态玻璃时是 Kyant0 示例标签栏（两格、34dp 高），
 * 否则是普通分段胶囊。每格文字带数量，如「资料库 23」「在线 156」。
 */
@Composable
internal fun HeroSourceToggle(
    source: HeroDataSource,
    libraryLabel: String,
    onlineLabel: String,
    onSelect: (HeroDataSource) -> Unit
) {
    val labels = listOf(libraryLabel, onlineLabel)
    val selectedIndex = if (source == HeroDataSource.ONLINE) 1 else 0
    val backdrop = com.lm.player.core.designsystem.glass.LocalGlassBackdrop.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val latestSelected = androidx.compose.runtime.rememberUpdatedState(selectedIndex)
    if (backdrop != null) {
        Box(modifier = Modifier.size(width = 196.dp, height = 34.dp)) {
            LiquidBottomTabs(
                selectedTabIndex = { latestSelected.value },
                onTabSelected = { i -> onSelect(if (i == 1) HeroDataSource.ONLINE else HeroDataSource.LIBRARY) },
                backdrop = backdrop,
                tabsCount = 2,
                accentColor = AppleRed,
                barHeight = 34.dp,
                innerPadding = 3.dp,
                modifier = Modifier.fillMaxSize()
            ) {
                labels.forEachIndexed { i, label ->
                    LiquidBottomTab(
                        onClick = { onSelect(if (i == 1) HeroDataSource.ONLINE else HeroDataSource.LIBRARY) }
                    ) {
                        Text(text = label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = onSurface, maxLines = 1)
                    }
                }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(onSurface.copy(alpha = 0.07f))
                .padding(3.dp)
        ) {
            labels.forEachIndexed { i, label ->
                val selected = i == selectedIndex
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(min = 92.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                        .clickable { onSelect(if (i == 1) HeroDataSource.ONLINE else HeroDataSource.LIBRARY) }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 12.5.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) AppleRed else onSurface,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** 在线模式的音乐平台选择（顶栏右侧小胶囊 + 下拉） */
@Composable
internal fun HeroPlatformPicker(
    platform: com.lm.player.core.model.OnlineMusicSource,
    loading: Boolean,
    onPlatformChange: (com.lm.player.core.model.OnlineMusicSource) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (loading) {
            androidx.compose.material3.CircularProgressIndicator(color = AppleRed, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Box {
            HeroPill(text = platform.shortName, highlighted = false, onClick = { menu = true })
            com.lm.player.core.designsystem.glass.AdaptiveDropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false }
            ) {
                com.lm.player.core.model.OnlineMusicSource.entries.forEach { src ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Text(
                                text = src.displayName,
                                color = if (src == platform) AppleRed else onSurface,
                                fontWeight = if (src == platform) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            menu = false
                            onPlatformChange(src)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroPill(text: String, highlighted: Boolean, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (highlighted) AppleRed.copy(alpha = 0.14f) else onSurface.copy(alpha = 0.07f))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (highlighted) AppleRed else onSurface,
            maxLines = 1
        )
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = if (highlighted) AppleRed else onSurface.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp)
        )
    }
}
