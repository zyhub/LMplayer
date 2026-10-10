package com.lm.player.feature.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.BatchDownloadQualityChoiceDialog
import com.lm.player.core.designsystem.component.DownloadQualityChoiceDialog
import com.lm.player.core.designsystem.component.ServerSwitchDropdownButton
import com.lm.player.core.designsystem.component.isSamePlayingSong
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.media.SongMatchingResolver
import com.lm.player.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LiquidCardColorPalette(
    val gradientColors: List<Color>,
    val glowColor: Color,
    val secondaryGlowColor: Color,
    val iconColor: Color
)

val ALL_RANDOM_LIQUID_PALETTES: List<LiquidCardColorPalette> = listOf(
    LiquidCardColorPalette(listOf(Color(0xFF0C3852), Color(0xFF061826)), Color(0xFF00D2FF), Color(0xFF38EF7D), Color(0xFF00D2FF)), // Ocean Cyan
    LiquidCardColorPalette(listOf(Color(0xFF5A250D), Color(0xFF2E1005)), Color(0xFFFF7A45), Color(0xFFFFD200), Color(0xFFFF9500)), // Sunset Amber
    LiquidCardColorPalette(listOf(Color(0xFF251A4A), Color(0xFF130D2E)), Color(0xFF8B5CF6), Color(0xFFC084FC), Color(0xFFA78BFA)), // Royal Violet
    LiquidCardColorPalette(listOf(Color(0xFF4A154B), Color(0xFF250B28)), Color(0xFFE040FB), Color(0xFFFF80AB), Color(0xFFE040FB)), // Cyber Magenta
    LiquidCardColorPalette(listOf(Color(0xFF0F3E33), Color(0xFF07211C)), Color(0xFF34D399), Color(0xFF10B981), Color(0xFF34D399)), // Emerald Mint
    LiquidCardColorPalette(listOf(Color(0xFF5C1D24), Color(0xFF2D0B10)), Color(0xFFFF4D4D), Color(0xFFFF758F), Color(0xFFFF5252)), // Ruby Crimson
    LiquidCardColorPalette(listOf(Color(0xFF1A2A4E), Color(0xFF0E172E)), Color(0xFF3B82F6), Color(0xFF60A5FA), Color(0xFF60A5FA)), // Electric Cobalt
    LiquidCardColorPalette(listOf(Color(0xFF4A3410), Color(0xFF281B08)), Color(0xFFFFB300), Color(0xFFFFE082), Color(0xFFFFC107)), // Solar Gold
    LiquidCardColorPalette(listOf(Color(0xFF1F3D3D), Color(0xFF0E1F1F)), Color(0xFF2DD4BF), Color(0xFF5EEAD4), Color(0xFF2DD4BF)), // Aurora Teal
    LiquidCardColorPalette(listOf(Color(0xFF38153A), Color(0xFF1D091F)), Color(0xFFF43F5E), Color(0xFFFB7185), Color(0xFFF43F5E))  // Rose Coral
)

/**
 * 柠檬音乐专属：现代轻奢发现主页 (Discover Home)
 * 包含：
 * 1. 顶部操作音源快捷切换 (酷我/网易/QQ/酷狗/咪咕) 与服务器下拉
 * 2. 热门推荐歌单横向画廊
 * 3. 官方权威榜单卡片
 * 4. 新歌首发曲目流 (即点即播 & 一键离线 & 多选批量下载)
 * 5. 歌单与榜单曲目下钻浮层 (支持播放全部与多选/全选下载)
 */
@Composable
fun LemonDiscoverHomeScreen(
    serverName: String,
    configuredServers: List<ServerConfig> = emptyList(),
    currentSource: OnlineMusicSource,
    onSourceChange: (OnlineMusicSource) -> Unit,
    blurAlpha: Float = 0.85f,
    dynamicCardEffects: Boolean = true,
    allCachedSongs: List<UnifiedSong> = emptyList(),
    activeDownloadTasks: List<DownloadTask> = emptyList(),
    activeDownloadCount: Int = 0,
    currentPlayingSong: UnifiedSong? = null,
    isPlaying: Boolean = false,
    locateSongTrigger: Int = 0,
    scrollToTopTrigger: Int = 0,
    onListScrollingChange: (Boolean) -> Unit = {},
    onScrollPositionChange: (Boolean) -> Unit = {},
    onSongClick: (UnifiedSong, List<UnifiedSong>?) -> Unit = { song, _ -> },
    onDownloadSong: (UnifiedSong) -> Unit = {},
    onDownloadSongWithOptions: (UnifiedSong, DownloadTarget, AudioQuality) -> Unit = { song, _, _ -> onDownloadSong(song) },
    onBatchDownloadSongsWithOptions: (List<UnifiedSong>, DownloadTarget, AudioQuality) -> Unit = { songs, target, quality ->
        songs.forEach { onDownloadSongWithOptions(it, target, quality) }
    },
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
    onSubViewActiveChange: (Boolean) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    val dimensions = LocalAppDimensions.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val isCompactHeader = screenWidthDp < 390
    val isUltraCompactHeader = screenWidthDp < 350
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = blurAlpha)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.10f)
    val coroutineScope = rememberCoroutineScope()

    // 监听前后台生命周期，退到后台或锁屏时暂停光斑帧时钟驱动，降低功耗
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var isAppResumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isAppResumed = true
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                isAppResumed = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val isLiquidAnimated = dynamicCardEffects && isAppResumed

    var recommendPlaylists by remember { mutableStateOf<List<UnifiedPlaylist>>(emptyList()) }
    var toplists by remember { mutableStateOf<List<LemonToplist>>(emptyList()) }
    var newSongs by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
    var newAlbums by remember { mutableStateOf<List<UnifiedAlbum>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var reloadTrigger by remember { mutableStateOf(0) }
    var isPullRefreshing by remember { mutableStateOf(false) }

    // 官方权威排行榜独立全景下钻层
    var isToplistsOverviewOpen by remember { mutableStateOf(false) }

    // 最新专辑首发全景下钻层
    var isAlbumsOverviewOpen by remember { mutableStateOf(false) }

    // 换一批轮换种子 (保证今日推荐与列表每次点击呈现新曲目)
    var recommendSeed by remember { mutableStateOf(0) }

    // 私人漫游播放状态追踪 (仅当点击私人漫游触发的播放且正在播放漫游池中曲目时，才显示波形动画)
    var isRoamingPlaying by remember { mutableStateOf(false) }
    var roamingSongIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val playNonRoamingSong: (UnifiedSong, List<UnifiedSong>?) -> Unit = { song, list ->
        isRoamingPlaying = false
        onSongClick(song, list)
    }

    LaunchedEffect(currentPlayingSong?.id) {
        if (currentPlayingSong != null && !roamingSongIds.contains(currentPlayingSong.id)) {
            isRoamingPlaying = false
        }
    }


    // 音源切换选择弹窗
    var isSourceSelectorOpen by remember { mutableStateOf(false) }

    // 缓存下载音质与目标选择弹窗（单曲 & 批量）
    var songForDownloadChoice by remember { mutableStateOf<UnifiedSong?>(null) }
    var songsForBatchDownloadChoice by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }

    // 新歌首发多选状态
    var isNewSongsMultiSelect by remember { mutableStateOf(false) }
    var selectedNewSongIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 歌单/榜单下钻多选状态
    var isCollectionMultiSelect by remember { mutableStateOf(false) }
    var selectedCollectionSongIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 歌单/榜单下钻曲目抽屉
    var activeCollectionTitle by remember { mutableStateOf<String?>(null) }
    var lastCollectionTitle by remember { mutableStateOf<String?>(null) }
    var activeCollectionCover by remember { mutableStateOf("") }
    var activeCollectionSongs by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
    var isLoadingCollection by remember { mutableStateOf(false) }

    // 安卓系统返回键逐级回退：多选状态 -> 歌单/榜单/专辑详情抽屉 -> 榜单/专辑全景 -> 首页
    val hasActiveSubView = isCollectionMultiSelect || activeCollectionTitle != null || isNewSongsMultiSelect || isToplistsOverviewOpen || isAlbumsOverviewOpen
    LaunchedEffect(hasActiveSubView) {
        onSubViewActiveChange(hasActiveSubView)
    }
    DisposableEffect(Unit) {
        onDispose { onSubViewActiveChange(false) }
    }
    if (hasActiveSubView) {
        androidx.activity.compose.BackHandler(enabled = true) {
            when {
                isCollectionMultiSelect -> {
                    isCollectionMultiSelect = false
                    selectedCollectionSongIds = emptySet()
                }
                activeCollectionTitle != null -> {
                    activeCollectionTitle = null
                    isCollectionMultiSelect = false
                    selectedCollectionSongIds = emptySet()
                }
                isToplistsOverviewOpen -> {
                    isToplistsOverviewOpen = false
                }
                isAlbumsOverviewOpen -> {
                    isAlbumsOverviewOpen = false
                }
                isNewSongsMultiSelect -> {
                    isNewSongsMultiSelect = false
                    selectedNewSongIds = emptySet()
                }
            }
        }
    }

    val discoverListState = rememberLazyListState()
    val collectionListState = rememberLazyListState()
    val isAnyScrolling = discoverListState.isScrollInProgress || collectionListState.isScrollInProgress
    LaunchedEffect(isAnyScrolling) {
        onListScrollingChange(isAnyScrolling)
    }

    val dynamicPalettes = remember { ALL_RANDOM_LIQUID_PALETTES.shuffled().take(5) }

    val isScrolledAway = (activeCollectionTitle != null && (collectionListState.firstVisibleItemIndex > 0 || collectionListState.firstVisibleItemScrollOffset > 0)) ||
        (activeCollectionTitle == null && (discoverListState.firstVisibleItemIndex > 0 || discoverListState.firstVisibleItemScrollOffset > 0))
    LaunchedEffect(isScrolledAway) {
        onScrollPositionChange(isScrolledAway)
    }

    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) {
            if (activeCollectionTitle != null) {
                collectionListState.scrollToItem(0)
            } else {
                discoverListState.scrollToItem(0)
            }
        }
    }

    val isServerOk = configuredServers.any { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }

    // 结合本地缓存曲库与下载中任务在后台线程动态解析匹配状态，彻底避免主线程重组卡顿
    val resolvedNewSongs by produceState(
        initialValue = newSongs,
        key1 = newSongs,
        key2 = allCachedSongs,
        key3 = activeDownloadTasks
    ) {
        value = if (allCachedSongs.isEmpty() || newSongs.isEmpty()) {
            newSongs
        } else {
            withContext(Dispatchers.Default) {
                SongMatchingResolver.resolveSongList(newSongs, allCachedSongs, activeDownloadTasks)
            }
        }
    }

    val resolvedCollectionSongs by produceState(
        initialValue = activeCollectionSongs,
        key1 = activeCollectionSongs,
        key2 = allCachedSongs,
        key3 = activeDownloadTasks
    ) {
        value = withContext(Dispatchers.Default) {
            val songsWithCover = activeCollectionSongs.map { s ->
                if (s.coverUrl.isNullOrBlank() && activeCollectionCover.isNotBlank()) {
                    s.copy(coverUrl = activeCollectionCover)
                } else {
                    s
                }
            }
            if (allCachedSongs.isEmpty() || songsWithCover.isEmpty()) {
                songsWithCover
            } else {
                SongMatchingResolver.resolveSongList(songsWithCover, allCachedSongs, activeDownloadTasks)
            }
        }
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
                    recommendPlaylists = if (reloadTrigger > 0) pl.shuffled() else pl
                    isLoading = false
                }
            }
            launch {
                val tl = runCatching { onFetchDiscoverToplists(currentSource) }.getOrDefault(emptyList())
                if (tl.isNotEmpty()) {
                    toplists = if (reloadTrigger > 0) tl.shuffled() else tl
                    isLoading = false
                }
            }
            launch {
                val ns = runCatching { onFetchDiscoverNewSongs(currentSource) }.getOrDefault(emptyList())
                if (ns.isNotEmpty()) {
                    newSongs = if (reloadTrigger > 0) ns.shuffled() else ns
                    isLoading = false
                }
            }
            if (onFetchDiscoverNewAlbums != null) {
                launch {
                    val na = runCatching { onFetchDiscoverNewAlbums(currentSource) }.getOrDefault(emptyList())
                    if (na.isNotEmpty()) {
                        newAlbums = if (reloadTrigger > 0) na.shuffled() else na
                        isLoading = false
                    }
                }
            }
        }
        isLoading = false
    }

    // 今日推荐最少 3 首曲目池计算（换一批时通过 recommendSeed 偏移轮转，保证每次呈现新推荐）
    val todaySongs = remember(resolvedNewSongs, allCachedSongs, recommendSeed) {
        val pool = if (resolvedNewSongs.isNotEmpty()) {
            resolvedNewSongs
        } else {
            allCachedSongs
        }
        if (pool.isEmpty()) {
            emptyList()
        } else {
            val offset = (recommendSeed * 3) % pool.size
            val rotated = pool.drop(offset) + pool.take(offset)
            if (rotated.size < 3 && allCachedSongs.isNotEmpty()) {
                (rotated + allCachedSongs).distinctBy { it.id }.take(3)
            } else {
                rotated.take(3)
            }
        }
    }

    // 最新专辑最少 3 张专辑池计算（换一批时通过 recommendSeed 偏移轮转，保证展示至少 3 张精选专辑）
    val todayAlbums = remember(newAlbums, allCachedSongs, recommendSeed) {
        val pool = if (newAlbums.isNotEmpty()) {
            newAlbums
        } else {
            allCachedSongs.filter { it.album.isNotBlank() }
                .groupBy { it.album }
                .map { (albumTitle, songs) ->
                    val first = songs.first()
                    UnifiedAlbum(
                        id = "cached_${albumTitle}",
                        title = albumTitle,
                        artist = first.artist,
                        coverUrl = first.coverUrl ?: "",
                        songCount = songs.size
                    )
                }
        }
        if (pool.isEmpty()) {
            listOf(
                UnifiedAlbum(id = "album_def_1", title = "流行新碟速递", artist = "群星新单", coverUrl = "", songCount = 10),
                UnifiedAlbum(id = "album_def_2", title = "热播原声精选", artist = "原声音轨", coverUrl = "", songCount = 8),
                UnifiedAlbum(id = "album_def_3", title = "风向潮流首发", artist = "新碟典藏", coverUrl = "", songCount = 12)
            )
        } else {
            val offset = (recommendSeed * 3) % pool.size
            val rotated = pool.drop(offset) + pool.take(offset)
            if (rotated.size < 3) {
                val fallback = listOf(
                    UnifiedAlbum(id = "album_def_1", title = "流行新碟速递", artist = "群星新单", coverUrl = "", songCount = 10),
                    UnifiedAlbum(id = "album_def_2", title = "热播原声精选", artist = "原声音轨", coverUrl = "", songCount = 8),
                    UnifiedAlbum(id = "album_def_3", title = "风向潮流首发", artist = "新碟典藏", coverUrl = "", songCount = 12)
                )
                (rotated + fallback).distinctBy { it.title }.take(3)
            } else {
                rotated.take(3)
            }
        }
    }

    // 定位正在播放的歌曲（自动切回所属歌单下钻列表或新歌首发列表并滚动定位）
    LaunchedEffect(locateSongTrigger) {
        if (locateSongTrigger > 0 && currentPlayingSong != null) {
            val colIdx = resolvedCollectionSongs.indexOfFirst { isSamePlayingSong(it, currentPlayingSong) }
            val newIdx = resolvedNewSongs.indexOfFirst { isSamePlayingSong(it, currentPlayingSong) }
            if (colIdx >= 0 && (activeCollectionTitle != null || lastCollectionTitle != null)) {
                if (activeCollectionTitle == null) {
                    activeCollectionTitle = lastCollectionTitle
                    kotlinx.coroutines.delay(80)
                }
                runCatching { collectionListState.scrollToItem(colIdx) }
            } else if (newIdx >= 0) {
                if (activeCollectionTitle != null) {
                    activeCollectionTitle = null
                    kotlinx.coroutines.delay(80)
                }
                isToplistsOverviewOpen = false
                isAlbumsOverviewOpen = false
                var headerItems = 2 // 顶部 Header + 2大3小卡片矩阵
                if (recommendPlaylists.isNotEmpty()) headerItems++ // 热门推荐歌单
                headerItems++ // 新歌首发标题栏与选项卡
                runCatching { discoverListState.scrollToItem(headerItems + newIdx) }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (activeCollectionTitle == null && !isToplistsOverviewOpen && !isAlbumsOverviewOpen) {
            com.lm.player.core.designsystem.component.PullToRefreshLayout(
                isRefreshing = isPullRefreshing,
                onRefresh = {
                    isPullRefreshing = true
                    coroutineScope.launch {
                        runCatching {
                            onSyncNow()
                            reloadTrigger++
                            kotlinx.coroutines.delay(800)
                        }
                        isPullRefreshing = false
                    }
                }
            ) {
                LazyColumn(
                    state = discoverListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(
                    top = contentPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding() + 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
        // 1. 顶部 Header (发现标题 + 音源切换胶囊 + 下载 + 服务器切换，自适应不同分辨率)
        item {
            val pillHorizontalPad = if (isUltraCompactHeader) 7.dp else if (isCompactHeader) 8.dp else 10.dp
            val pillVerticalPad = if (isCompactHeader) 5.dp else 6.dp
            val buttonGap = if (isUltraCompactHeader) 4.dp else 6.dp

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(end = 6.dp)
                ) {
                    Text(
                        text = "发现",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontSize = dimensions.pageTitleSize,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    )
                    Text(
                        text = "在线云端曲库 · 柠檬音乐",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontSize = dimensions.captionSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(buttonGap)
                ) {
                    // 音源切换胶囊按钮 (操作音源)
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
                                modifier = Modifier.padding(horizontal = pillHorizontalPad, vertical = pillVerticalPad),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = "操作音源",
                                    tint = AppleRed,
                                    modifier = Modifier.size(if (isCompactHeader) 14.dp else 16.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = currentSource.shortName,
                                    color = AppleRed,
                                    fontSize = dimensions.captionSize,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Spacer(modifier = Modifier.width(1.dp))
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = AppleRed,
                                    modifier = Modifier.size(if (isCompactHeader) 14.dp else 16.dp)
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
                            modifier = Modifier.padding(horizontal = pillHorizontalPad, vertical = pillVerticalPad),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (activeDownloadCount > 0) Icons.Default.FileDownload else Icons.Outlined.FileDownload,
                                contentDescription = "下载管理",
                                tint = if (activeDownloadCount > 0) AppleRed else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(if (isCompactHeader) 16.dp else 18.dp)
                            )
                            if (activeDownloadCount > 0) {
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = if (activeDownloadCount > 99) "99+" else "$activeDownloadCount",
                                    color = AppleRed,
                                    fontSize = dimensions.captionSize,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

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
            // ==================== 2 大 3 小动态液态光影卡片矩阵 ====================
            item(key = "discover_bento_hero") {
                val topRank = toplists.firstOrNull()
                val topAlbum = newAlbums.firstOrNull()

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 第一行：2 大卡片【今日推荐】(最少推荐3首) + 【最新专辑】(最少展示3条专辑)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 大卡 1：今日推荐 (Ocean Cyan 动态液态流光大卡，展示至少 3 首推荐曲目)
                        DiscoverLiquidCard(
                            modifier = Modifier
                                .weight(1f)
                                .height(196.dp),
                            title = "今日推荐",
                            badgeText = "精选 3首",
                            icon = Icons.Default.AutoAwesome,
                            iconColor = dynamicPalettes[0].iconColor,
                            gradientColors = dynamicPalettes[0].gradientColors,
                            glowColor = dynamicPalettes[0].glowColor,
                            secondaryGlowColor = dynamicPalettes[0].secondaryGlowColor,
                            isAnimated = isLiquidAnimated,
                            onClick = {
                                todaySongs.firstOrNull()?.let {
                                    playNonRoamingSong(it, if (resolvedNewSongs.isNotEmpty()) resolvedNewSongs else allCachedSongs)
                                }
                            }
                        ) {
                            if (todaySongs.isNotEmpty()) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    todaySongs.take(3).forEach { song ->
                                        val isThisPlaying = isSamePlayingSong(song, currentPlayingSong)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    playNonRoamingSong(song, if (resolvedNewSongs.isNotEmpty()) resolvedNewSongs else allCachedSongs)
                                                }
                                                .padding(vertical = 2.dp, horizontal = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            AlbumArtworkImage(
                                                model = song.coverUrl,
                                                seedId = song.id,
                                                modifier = Modifier.size(32.dp),
                                                cornerRadius = 6.dp
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = song.title,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isThisPlaying) FontWeight.ExtraBold else FontWeight.Bold,
                                                    color = if (isThisPlaying) Color(0xFF00D2FF) else Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = song.artist,
                                                    fontSize = 9.sp,
                                                    color = if (isThisPlaying) Color(0xFF00D2FF).copy(alpha = 0.85f) else Color.White.copy(alpha = 0.75f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            if (isThisPlaying) {
                                                com.lm.player.core.designsystem.component.NowPlayingWaveIndicator(
                                                    isPlaying = isPlaying,
                                                    color = Color.White
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.PlayArrow,
                                                    contentDescription = null,
                                                    tint = Color.White.copy(alpha = 0.85f),
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "今日精选好歌 · 点击即播",
                                        fontSize = 11.sp,
                                        color = Color.White.copy(alpha = 0.70f)
                                    )
                                }
                            }
                        }

                        // 大卡 2：最新专辑 (Sunset Amber 动态液态流光大卡，展示至少 3 张精选专辑并可下钻全览)
                        DiscoverLiquidCard(
                            modifier = Modifier
                                .weight(1f)
                                .height(196.dp),
                            title = "最新专辑",
                            badgeText = "新碟首发",
                            icon = Icons.Default.Album,
                            iconColor = dynamicPalettes[1].iconColor,
                            gradientColors = dynamicPalettes[1].gradientColors,
                            glowColor = dynamicPalettes[1].glowColor,
                            secondaryGlowColor = dynamicPalettes[1].secondaryGlowColor,
                            isAnimated = isLiquidAnimated,
                            onClick = {
                                isAlbumsOverviewOpen = true
                            }
                        ) {
                            if (todayAlbums.isNotEmpty()) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    todayAlbums.take(3).forEach { album ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    isAlbumsOverviewOpen = true
                                                }
                                                .padding(vertical = 2.dp, horizontal = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            AlbumArtworkImage(
                                                model = album.coverUrl,
                                                seedId = album.id,
                                                modifier = Modifier.size(32.dp),
                                                cornerRadius = 6.dp
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = album.title,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = album.artist.ifBlank { "最新专辑" },
                                                    fontSize = 9.sp,
                                                    color = Color.White.copy(alpha = 0.75f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Icon(
                                                imageVector = Icons.Default.ChevronRight,
                                                contentDescription = null,
                                                tint = Color.White.copy(alpha = 0.80f),
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "最新热碟速递 · 点击全览",
                                        fontSize = 11.sp,
                                        color = Color.White.copy(alpha = 0.70f)
                                    )
                                }
                            }
                        }
                    }

                    // 第二行：【权威排行榜】(左侧卡片) + 【私人漫游】与【换一批】(右侧2张小长方形卡片)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(154.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 左侧：权威排行榜 (Royal Indigo 动态液态流光卡片)
                        DiscoverLiquidCard(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            title = "权威排行榜",
                            badgeText = "官方热榜",
                            icon = Icons.Default.Leaderboard,
                            iconColor = dynamicPalettes[2].iconColor,
                            gradientColors = dynamicPalettes[2].gradientColors,
                            glowColor = dynamicPalettes[2].glowColor,
                            secondaryGlowColor = dynamicPalettes[2].secondaryGlowColor,
                            isAnimated = isLiquidAnimated,
                            onClick = {
                                isToplistsOverviewOpen = true
                            }
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = topRank?.name ?: "热歌榜 · 飙升榜",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "各大音乐官方风向标 · 共 ${toplists.size} 个榜单",
                                    fontSize = 10.sp,
                                    color = Color.White.copy(alpha = 0.75f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "点击浏览全榜",
                                    fontSize = 10.sp,
                                    color = Color.White.copy(alpha = 0.70f)
                                )
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // 右侧：垂直排列的 2 张小长方形卡片【私人漫游】+【换一批】
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 小长方形卡片 1：私人漫游 (音乐播放时显示跳动动态波形)
                            DiscoverLiquidSmallCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                title = "私人漫游",
                                subtitle = "随心而动 · 智能雷达",
                                badgeText = "心动",
                                icon = Icons.Default.Shuffle,
                                iconColor = dynamicPalettes[3].iconColor,
                                trailingIcon = Icons.Default.PlayArrow,
                                isCurrentlyPlaying = isRoamingPlaying && isPlaying && currentPlayingSong != null && roamingSongIds.contains(currentPlayingSong.id),
                                gradientColors = dynamicPalettes[3].gradientColors,
                                glowColor = dynamicPalettes[3].glowColor,
                                secondaryGlowColor = dynamicPalettes[3].secondaryGlowColor,
                                isAnimated = isLiquidAnimated,
                                onClick = {
                                    val pool = (resolvedNewSongs + allCachedSongs).distinctBy { it.id }.shuffled()
                                    if (pool.isNotEmpty()) {
                                        roamingSongIds = pool.map { it.id }.toSet()
                                        isRoamingPlaying = true
                                        onSongClick(pool.first(), pool)
                                    }
                                }
                            )

                            // 小长方形卡片 2：换一批
                            DiscoverLiquidSmallCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                title = "换一批",
                                subtitle = "探索全网全新好歌",
                                badgeText = "刷新",
                                icon = Icons.Default.Refresh,
                                iconColor = dynamicPalettes[4].iconColor,
                                trailingIcon = Icons.Default.AutoAwesome,
                                gradientColors = dynamicPalettes[4].gradientColors,
                                glowColor = dynamicPalettes[4].glowColor,
                                secondaryGlowColor = dynamicPalettes[4].secondaryGlowColor,
                                isAnimated = isLiquidAnimated,
                                onClick = {
                                    com.lm.player.core.network.LemonMusicProtocol.clearDiscoverCache()
                                    recommendSeed++
                                    if (recommendPlaylists.size > 1) {
                                        recommendPlaylists = recommendPlaylists.shuffled()
                                    }
                                    if (toplists.size > 1) {
                                        toplists = toplists.shuffled()
                                    }
                                    if (newSongs.size > 1) {
                                        newSongs = newSongs.shuffled()
                                    }
                                    if (newAlbums.size > 1) {
                                        newAlbums = newAlbums.shuffled()
                                    }
                                    reloadTrigger++
                                    android.widget.Toast.makeText(context, "已为您换一批推荐内容", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }

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
                                        lastCollectionTitle = playlist.name
                                        activeCollectionCover = playlist.coverUrl
                                        isCollectionMultiSelect = false
                                        selectedCollectionSongIds = emptySet()
                                        isLoadingCollection = true
                                        val plId = playlist.id
                                        coroutineScope.launch {
                                                try {
                                                    activeCollectionSongs = onFetchCollectionSongs(plId, currentSource)
                                                } catch (e: kotlinx.coroutines.CancellationException) {
                                                    throw e
                                                } catch (_: Exception) {
                                                    activeCollectionSongs = emptyList()
                                                } finally {
                                                    isLoadingCollection = false
                                                }
                                            }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // B. 【新歌首发】 (新单即点即播，新碟已整合移动至“最新专辑”大卡及全览专栏)
            if (resolvedNewSongs.isNotEmpty()) {
                item {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SectionHeader(
                                title = "新歌首发",
                                subtitle = "今日全网新单，即点即播"
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        SongListPlayAndBatchDownloadBar(
                            totalCount = resolvedNewSongs.size,
                            isMultiSelectMode = isNewSongsMultiSelect,
                            selectedCount = selectedNewSongIds.size,
                            isAllSelected = resolvedNewSongs.isNotEmpty() && selectedNewSongIds.size == resolvedNewSongs.size,
                            onPlayAll = {
                                resolvedNewSongs.firstOrNull()?.let { playNonRoamingSong(it, resolvedNewSongs) }
                            },
                            onEnterMultiSelect = {
                                isNewSongsMultiSelect = true
                                selectedNewSongIds = emptySet()
                            },
                            onExitMultiSelect = {
                                isNewSongsMultiSelect = false
                                selectedNewSongIds = emptySet()
                            },
                            onToggleSelectAll = {
                                selectedNewSongIds = if (selectedNewSongIds.size == resolvedNewSongs.size) {
                                    emptySet()
                                } else {
                                    resolvedNewSongs.map { it.id }.toSet()
                                }
                            },
                            onBatchDownloadClick = {
                                val selectedSongs = resolvedNewSongs.filter { selectedNewSongIds.contains(it.id) }
                                if (selectedSongs.isNotEmpty()) {
                                    songsForBatchDownloadChoice = selectedSongs
                                }
                            }
                        )
                    }
                }

                items(
                    items = resolvedNewSongs,
                    key = { it.id },
                    contentType = { "new_song_row" }
                ) { song ->
                    val isSelected = selectedNewSongIds.contains(song.id)
                    SongListItemRow(
                        song = song,
                        activeDownloadTasks = activeDownloadTasks,
                        isServerConnected = isServerOk,
                        currentPlayingSong = currentPlayingSong,
                        isPlaying = isPlaying,
                        isMultiSelectMode = isNewSongsMultiSelect,
                        isSelected = isSelected,
                        onToggleSelect = {
                            selectedNewSongIds = if (isSelected) {
                                selectedNewSongIds - song.id
                            } else {
                                selectedNewSongIds + song.id
                            }
                        },
                        onClick = { playNonRoamingSong(song, resolvedNewSongs) },
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
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, borderColor),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onSurface
                                    )
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("音源设置", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

        // 官方权威排行榜全景下钻层 (点击【权威排行榜】大卡时展开，减少主页重复列表)
        if (isToplistsOverviewOpen && activeCollectionTitle == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { isToplistsOverviewOpen = false }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "官方权威排行榜",
                            fontSize = dimensions.sectionTitleSize,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${currentSource.displayName} · 共 ${toplists.size} 个官方热榜",
                            fontSize = dimensions.captionSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)
                ) {
                    items(
                        items = toplists,
                        key = { it.id }
                    ) { toplist ->
                        DiscoverToplistCard(
                            toplist = toplist,
                            onClick = {
                                activeCollectionTitle = toplist.name
                                lastCollectionTitle = toplist.name
                                activeCollectionCover = toplist.coverUrl
                                isCollectionMultiSelect = false
                                selectedCollectionSongIds = emptySet()
                                isLoadingCollection = true
                                val tlId = "lemon_toplist_${toplist.source}_${toplist.id}"
                                coroutineScope.launch {
                                                try {
                                                    activeCollectionSongs = onFetchCollectionSongs(tlId, currentSource)
                                                } catch (e: kotlinx.coroutines.CancellationException) {
                                                    throw e
                                                } catch (_: Exception) {
                                                    activeCollectionSongs = emptyList()
                                                } finally {
                                                    isLoadingCollection = false
                                                }
                                            }
                            }
                        )
                    }
                }
            }
        }

        // 最新专辑全景下钻层 (点击【最新专辑】大卡时展开，集中展示新碟首发)
        if (isAlbumsOverviewOpen && activeCollectionTitle == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { isAlbumsOverviewOpen = false }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "最新专辑",
                            fontSize = dimensions.sectionTitleSize,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${currentSource.displayName} · 共 ${newAlbums.size} 张新碟首发",
                            fontSize = dimensions.captionSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (newAlbums.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无最新专辑",
                            fontSize = dimensions.bodySize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 140.dp),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)
                    ) {
                        items(
                            items = newAlbums,
                            key = { it.id }
                        ) { album ->
                            DiscoverAlbumCard(
                                album = album,
                                onClick = {
                                    activeCollectionTitle = album.title
                                    lastCollectionTitle = album.title
                                    activeCollectionCover = album.coverUrl
                                    isCollectionMultiSelect = false
                                    selectedCollectionSongIds = emptySet()
                                    isLoadingCollection = true
                                    coroutineScope.launch {
                                                try {
                                                    activeCollectionSongs = onFetchCollectionSongs(album.id, currentSource)
                                                } catch (e: kotlinx.coroutines.CancellationException) {
                                                    throw e
                                                } catch (_: Exception) {
                                                    activeCollectionSongs = emptyList()
                                                } finally {
                                                    isLoadingCollection = false
                                                }
                                            }
                                }
                            )
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
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    activeCollectionTitle = null
                    isCollectionMultiSelect = false
                    selectedCollectionSongIds = emptySet()
                }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeCollectionTitle ?: "",
                        fontSize = dimensions.sectionTitleSize,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${currentSource.displayName} · ${activeCollectionSongs.size} 首歌曲",
                        fontSize = dimensions.captionSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 播放全部 + 多选/全选下载操作栏（放置在播放全部按钮旁边）
            if (resolvedCollectionSongs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                SongListPlayAndBatchDownloadBar(
                    totalCount = resolvedCollectionSongs.size,
                    isMultiSelectMode = isCollectionMultiSelect,
                    selectedCount = selectedCollectionSongIds.size,
                    isAllSelected = resolvedCollectionSongs.isNotEmpty() && selectedCollectionSongIds.size == resolvedCollectionSongs.size,
                    onPlayAll = {
                        resolvedCollectionSongs.firstOrNull()?.let { playNonRoamingSong(it, resolvedCollectionSongs) }
                    },
                    onEnterMultiSelect = {
                        isCollectionMultiSelect = true
                        selectedCollectionSongIds = emptySet()
                    },
                    onExitMultiSelect = {
                        isCollectionMultiSelect = false
                        selectedCollectionSongIds = emptySet()
                    },
                    onToggleSelectAll = {
                        selectedCollectionSongIds = if (selectedCollectionSongIds.size == resolvedCollectionSongs.size) {
                            emptySet()
                        } else {
                            resolvedCollectionSongs.map { it.id }.toSet()
                        }
                    },
                    onBatchDownloadClick = {
                        val selectedSongs = resolvedCollectionSongs.filter { selectedCollectionSongIds.contains(it.id) }
                        if (selectedSongs.isNotEmpty()) {
                            songsForBatchDownloadChoice = selectedSongs
                        }
                    }
                )
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
                        Text("正在拉取榜单/歌单曲目...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = dimensions.bodySize)
                    }
                }
            } else if (resolvedCollectionSongs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 100.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Text("暂无曲目数据", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = dimensions.bodySize)
                }
            } else {
                LazyColumn(
                    state = collectionListState,
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
                        val isSelected = selectedCollectionSongIds.contains(song.id)
                        SongListItemRow(
                            song = song,
                            activeDownloadTasks = activeDownloadTasks,
                            isServerConnected = isServerOk,
                            currentPlayingSong = currentPlayingSong,
                            isPlaying = isPlaying,
                            isMultiSelectMode = isCollectionMultiSelect,
                            isSelected = isSelected,
                            onToggleSelect = {
                                selectedCollectionSongIds = if (isSelected) {
                                    selectedCollectionSongIds - song.id
                                } else {
                                    selectedCollectionSongIds + song.id
                                }
                            },
                            onClick = { playNonRoamingSong(song, resolvedCollectionSongs) },
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

    // 批量多选下载音质与目标选择弹窗
    if (songsForBatchDownloadChoice.isNotEmpty()) {
        BatchDownloadQualityChoiceDialog(
            selectedCount = songsForBatchDownloadChoice.size,
            isServerConnected = isServerOk,
            onConfirm = { target, quality ->
                val batch = songsForBatchDownloadChoice
                songsForBatchDownloadChoice = emptyList()
                isNewSongsMultiSelect = false
                selectedNewSongIds = emptySet()
                isCollectionMultiSelect = false
                selectedCollectionSongIds = emptySet()
                onBatchDownloadSongsWithOptions(batch, target, quality)
            },
            onDismiss = { songsForBatchDownloadChoice = emptyList() }
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
                fontSize = dimensions.sectionTitleSize,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        )
        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = TextStyle(
                    fontSize = dimensions.captionSize,
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
                fontSize = dimensions.bodySize,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = (dimensions.bodySize.value * 1.25f).sp
            )
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = if (playlist.songCount > 0) "${playlist.songCount} 首" else "精选推荐",
            maxLines = 1,
            style = TextStyle(
                fontSize = dimensions.captionSize,
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
                        fontSize = dimensions.badgeSize,
                        fontWeight = FontWeight.Bold,
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
                fontSize = dimensions.bodySize,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "官方排行榜",
            style = TextStyle(
                fontSize = dimensions.captionSize,
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
                fontSize = dimensions.bodySize,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = album.artist.ifBlank { "最新专辑" },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = dimensions.captionSize,
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
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val menuBg = if (isDark) Color(0xFF1F1F26) else Color.White
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
            .widthIn(min = 250.dp, max = 310.dp)
            .background(menuBg)
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

@Composable
private fun rememberLiquidPhase(enabled: Boolean, durationMillis: Int = 8000): State<Float> {
    if (!enabled) return remember { mutableFloatStateOf(0f) }
    val infiniteTransition = rememberInfiniteTransition(label = "liquid_phase")
    return infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
}

/**
 * 绘制有机非规则变形液态光斑 Path（复用预分配 Path 与 FloatArray 缓冲，零堆内存分配）
 */
private fun updateOrganicLiquidPath(
    path: Path,
    ptsBuffer: FloatArray,
    cx: Float,
    cy: Float,
    baseRadius: Float,
    phaseRad: Float,
    h1: Float = 1.0f,
    h2: Float = 2.0f,
    h3: Float = 3.0f
) {
    path.reset()
    val pointsCount = 8
    val step = (2.0 * Math.PI / pointsCount).toFloat()

    for (i in 0 until pointsCount) {
        val angle = i * step
        val wave = 0.28f * kotlin.math.sin(h1 * angle + phaseRad) +
                   0.18f * kotlin.math.cos(h2 * angle - phaseRad) +
                   0.12f * kotlin.math.sin(h3 * angle + 2f * phaseRad)
        val r = baseRadius * (1f + wave)
        ptsBuffer[i * 2] = cx + r * kotlin.math.cos(angle)
        ptsBuffer[i * 2 + 1] = cy + r * kotlin.math.sin(angle)
    }

    val firstMidX = (ptsBuffer[0] + ptsBuffer[2]) / 2f
    val firstMidY = (ptsBuffer[1] + ptsBuffer[3]) / 2f
    path.moveTo(firstMidX, firstMidY)

    for (i in 0 until pointsCount) {
        val nextIdx = ((i + 1) % pointsCount) * 2
        val afterNextIdx = ((i + 2) % pointsCount) * 2
        val nextX = ptsBuffer[nextIdx]
        val nextY = ptsBuffer[nextIdx + 1]
        val midX = (nextX + ptsBuffer[afterNextIdx]) / 2f
        val midY = (nextY + ptsBuffer[afterNextIdx + 1]) / 2f
        path.quadraticBezierTo(nextX, nextY, midX, midY)
    }
    path.close()
}

/**
 * 发现页动态液态光影大卡/正方卡 (仅在 Draw 阶段自绘光影与边框，彻底阻断 Compose 重组开销)
 */
@Composable
private fun DiscoverLiquidCard(
    modifier: Modifier = Modifier,
    title: String,
    badgeText: String,
    icon: ImageVector,
    iconColor: Color,
    gradientColors: List<Color>,
    glowColor: Color,
    secondaryGlowColor: Color = Color.White.copy(alpha = 0.28f),
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    isAnimated: Boolean = true,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val phaseState = rememberLiquidPhase(enabled = isAnimated, durationMillis = 8000)
    val path1 = remember { Path() }
    val path2 = remember { Path() }
    val ptsBuffer = remember { FloatArray(16) }
    val cornerRadius = 18.dp

    Surface(
        shape = shape,
        color = Color.Transparent,
        shadowElevation = 4.dp,
        modifier = modifier
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(Brush.linearGradient(colors = gradientColors))

                    if (isAnimated) {
                        val phase = phaseState.value
                        val rad1 = Math.toRadians(phase.toDouble()).toFloat()
                        val rad2 = rad1 + Math.PI.toFloat()
                        val w = size.width
                        val h = size.height

                        // 1. 第一主液态流光核
                        val cx1 = w * (0.50f + 0.22f * kotlin.math.sin(rad1) + 0.08f * kotlin.math.cos(2f * rad1))
                        val cy1 = h * (0.50f + 0.20f * kotlin.math.cos(rad1) + 0.06f * kotlin.math.sin(2f * rad1))
                        updateOrganicLiquidPath(path1, ptsBuffer, cx1, cy1, size.maxDimension * 0.60f, rad1)
                        drawPath(
                            path = path1,
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    glowColor.copy(alpha = 0.62f),
                                    glowColor.copy(alpha = 0.22f),
                                    Color.Transparent
                                ),
                                center = Offset(cx1, cy1),
                                radius = size.maxDimension * 0.75f
                            )
                        )

                        // 2. 第二辅液态对流核
                        val cx2 = w * (0.50f - 0.20f * kotlin.math.cos(rad2) + 0.07f * kotlin.math.sin(2f * rad2))
                        val cy2 = h * (0.50f + 0.18f * kotlin.math.sin(rad2) - 0.06f * kotlin.math.cos(2f * rad2))
                        updateOrganicLiquidPath(path2, ptsBuffer, cx2, cy2, size.maxDimension * 0.45f, rad2, 1.2f, 2.0f, 1.5f)
                        drawPath(
                            path = path2,
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    secondaryGlowColor.copy(alpha = 0.42f),
                                    secondaryGlowColor.copy(alpha = 0.12f),
                                    Color.Transparent
                                ),
                                center = Offset(cx2, cy2),
                                radius = size.maxDimension * 0.60f
                            )
                        )
                    }
                }
                .drawWithContent {
                    drawContent()
                    val strokeWidth = 1.dp.toPx()
                    val cr = cornerRadius.toPx()
                    if (isAnimated) {
                        val phase = phaseState.value
                        val rad1 = Math.toRadians(phase.toDouble()).toFloat()
                        val cosP = kotlin.math.cos(rad1)
                        val sinP = kotlin.math.sin(rad1)
                        drawRoundRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    glowColor.copy(alpha = 0.85f),
                                    Color.White.copy(alpha = 0.45f),
                                    glowColor.copy(alpha = 0.20f),
                                    glowColor.copy(alpha = 0.85f)
                                ),
                                start = Offset((0.5f + 0.5f * cosP) * 300f, (0.5f + 0.5f * sinP) * 300f),
                                end = Offset((0.5f - 0.5f * cosP) * 300f, (0.5f - 0.5f * sinP) * 300f)
                            ),
                            cornerRadius = CornerRadius(cr, cr),
                            style = Stroke(width = strokeWidth)
                        )
                    } else {
                        drawRoundRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    glowColor.copy(alpha = 0.50f),
                                    Color.White.copy(alpha = 0.25f),
                                    glowColor.copy(alpha = 0.35f)
                                )
                            ),
                            cornerRadius = CornerRadius(cr, cr),
                            style = Stroke(width = strokeWidth)
                        )
                    }
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 顶部标题与角标
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.White.copy(alpha = 0.18f)
                    ) {
                        Text(
                            text = badgeText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.90f),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // 卡片主体与底部内容
                content()
            }
        }
    }
}

/**
 * 发现页动态液态光影小长方形卡片 (如私人漫游、换一批，专为小长方形定制的横向布局，支持播放中动态音波动画)
 */
@Composable
private fun DiscoverLiquidSmallCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    badgeText: String,
    icon: ImageVector,
    iconColor: Color,
    trailingIcon: ImageVector,
    isCurrentlyPlaying: Boolean = false,
    gradientColors: List<Color>,
    glowColor: Color,
    secondaryGlowColor: Color = Color.White.copy(alpha = 0.28f),
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    isAnimated: Boolean = true,
    onClick: () -> Unit
) {
    val phaseState = rememberLiquidPhase(enabled = isAnimated, durationMillis = 7500)
    val path1 = remember { Path() }
    val path2 = remember { Path() }
    val ptsBuffer = remember { FloatArray(16) }
    val cornerRadius = 16.dp

    Surface(
        shape = shape,
        color = Color.Transparent,
        shadowElevation = 3.dp,
        modifier = modifier
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(Brush.linearGradient(colors = gradientColors))

                    if (isAnimated) {
                        val phase = phaseState.value
                        val rad1 = Math.toRadians(phase.toDouble()).toFloat()
                        val rad2 = rad1 + Math.PI.toFloat()
                        val w = size.width
                        val h = size.height

                        val cx1 = w * (0.50f + 0.24f * kotlin.math.sin(rad1))
                        val cy1 = h * (0.50f + 0.20f * kotlin.math.cos(rad1))
                        updateOrganicLiquidPath(path1, ptsBuffer, cx1, cy1, size.maxDimension * 0.55f, rad1)
                        drawPath(
                            path = path1,
                            brush = Brush.radialGradient(
                                colors = listOf(glowColor.copy(alpha = 0.58f), glowColor.copy(alpha = 0.20f), Color.Transparent),
                                center = Offset(cx1, cy1),
                                radius = size.maxDimension * 0.70f
                            )
                        )

                        val cx2 = w * (0.50f - 0.22f * kotlin.math.cos(rad2))
                        val cy2 = h * (0.50f + 0.18f * kotlin.math.sin(rad2))
                        updateOrganicLiquidPath(path2, ptsBuffer, cx2, cy2, size.maxDimension * 0.42f, rad2, 1.2f, 2.0f, 1.5f)
                        drawPath(
                            path = path2,
                            brush = Brush.radialGradient(
                                colors = listOf(secondaryGlowColor.copy(alpha = 0.40f), Color.Transparent),
                                center = Offset(cx2, cy2),
                                radius = size.maxDimension * 0.55f
                            )
                        )
                    }
                }
                .drawWithContent {
                    drawContent()
                    val strokeWidth = 1.dp.toPx()
                    val cr = cornerRadius.toPx()
                    if (isAnimated) {
                        val phase = phaseState.value
                        val rad1 = Math.toRadians(phase.toDouble()).toFloat()
                        val cosP = kotlin.math.cos(rad1)
                        val sinP = kotlin.math.sin(rad1)
                        drawRoundRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    glowColor.copy(alpha = 0.85f),
                                    Color.White.copy(alpha = 0.45f),
                                    glowColor.copy(alpha = 0.20f),
                                    glowColor.copy(alpha = 0.85f)
                                ),
                                start = Offset((0.5f + 0.5f * cosP) * 300f, (0.5f + 0.5f * sinP) * 300f),
                                end = Offset((0.5f - 0.5f * cosP) * 300f, (0.5f - 0.5f * sinP) * 300f)
                            ),
                            cornerRadius = CornerRadius(cr, cr),
                            style = Stroke(width = strokeWidth)
                        )
                    } else {
                        drawRoundRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    glowColor.copy(alpha = 0.50f),
                                    Color.White.copy(alpha = 0.25f),
                                    glowColor.copy(alpha = 0.35f)
                                )
                            ),
                            cornerRadius = CornerRadius(cr, cr),
                            style = Stroke(width = strokeWidth)
                        )
                    }
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.20f),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = iconColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.White.copy(alpha = 0.20f)
                            ) {
                                Text(
                                    text = badgeText,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White.copy(alpha = 0.92f),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            text = subtitle,
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (isCurrentlyPlaying) {
                    com.lm.player.core.designsystem.component.NowPlayingWaveIndicator(
                        isPlaying = true,
                        color = Color.White
                    )
                } else {
                    Icon(
                        imageVector = trailingIcon,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}


