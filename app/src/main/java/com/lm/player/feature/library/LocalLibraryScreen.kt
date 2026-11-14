package com.lm.player.feature.library

import android.util.Log
import android.widget.Toast
import java.io.File
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.lm.player.feature.home.ALL_RANDOM_LIQUID_PALETTES
import com.lm.player.core.designsystem.component.rememberLiquidPhase
import com.lm.player.core.designsystem.component.updateOrganicLiquidPath
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.BatchDownloadQualityChoiceDialog
import com.lm.player.core.designsystem.component.DownloadQualityChoiceDialog
import com.lm.player.core.designsystem.component.ServerSwitchDropdownButton
import com.lm.player.core.designsystem.component.isSamePlayingSong
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.model.*
import com.lm.player.feature.home.SongListItemRow
import com.lm.player.feature.home.SongListPlayAndBatchDownloadBar
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
 * 6. 全部歌曲高保真流 (带格式/音质标签、收藏、下载弹窗、多维排序、多选/全选下载)
 * 7. 页面内无缝下钻视图 (歌单/歌手/专辑/流派详情，不遮挡底部悬浮播放栏)
 */
data class LocalLibraryCallbacks(
    val onScrollPositionChange: (Boolean) -> Unit = {},
    val onListScrollingChange: (Boolean) -> Unit = {},
    val onSongClick: (UnifiedSong, List<UnifiedSong>?) -> Unit = { _, _ -> },
    val onDownloadSong: (UnifiedSong) -> Unit = {},
    val onDownloadSongWithOptions: (UnifiedSong, DownloadTarget, AudioQuality) -> Unit = { _, _, _ -> },
    val onBatchDownloadSongsWithOptions: (List<UnifiedSong>, DownloadTarget, AudioQuality) -> Unit = { _, _, _ -> },
    val onOpenDownloads: () -> Unit = {},
    val onRefreshPlaylists: () -> Unit = {},
    /** 进入资料库 / 切换服务器时的自动刷新（静默）；null = 用 onRefreshPlaylists */
    val onAutoRefreshPlaylists: (() -> Unit)? = null,
    val onCreatePlaylist: (name: String, isOnline: Boolean) -> Unit = { _, _ -> },
    val onDeletePlaylist: (playlistId: String) -> Unit = {},
    val onToggleFavorite: ((UnifiedSong) -> Unit)? = null,
    val onFetchPlaylistSongs: (suspend (playlistId: String, isOnline: Boolean) -> List<UnifiedSong>)? = null,
    /**
     * 把歌曲移出歌单（与 TV 一致）：服务器歌单同步写回服务器，本地歌单只改本机。
     * 返回实际移出的条数；失败时返回 failure（界面不动、提示原因）
     */
    val onRemoveSongsFromPlaylist: (suspend (playlistId: String, isOnline: Boolean, songs: List<UnifiedSong>, onProgress: (Float, String) -> Unit) -> Result<Int>)? = null,
    val onFetchServerFolders: (suspend (parentId: String?) -> List<ServerFolderItem>)? = null,
    val onFetchServerFolderSongs: (suspend (folderId: String) -> List<UnifiedSong>)? = null,
    val onFetchServerGenres: (suspend () -> List<UnifiedGenre>)? = null,
    /** 歌手 / 专辑页在线搜索：本地音源加速优先、服务器兜底；isArtist=false 时按专辑名 + 歌手搜专辑 */
    /** 歌手 / 专辑页在线结果：按页取（page 从 1 开始，每页 HERO_ONLINE_PAGE_SIZE 首） */
    val onSearchOnlineForHero: (suspend (isArtist: Boolean, title: String, artist: String, source: OnlineMusicSource, onlineAlbumId: String, page: Int) -> HeroOnlinePage)? = null,
    /** 歌手专辑页在线搜索：按歌手名搜专辑，只留该歌手的 */
    val onSearchOnlineArtistAlbums: (suspend (artist: String, source: OnlineMusicSource) -> List<UnifiedAlbum>)? = null,
    /** 在线搜索默认平台（与全局搜索当前平台一致） */
    val defaultOnlineSource: OnlineMusicSource = OnlineMusicSource.KUWO,
    val onFetchServerScanStatus: (suspend () -> LemonScanStatus?)? = null,
    val onTriggerServerScan: (suspend () -> Unit)? = null,
    val onDeleteLocalFilePath: (suspend (String) -> Unit)? = null,
    val onDeleteDownloadedSongs: ((List<UnifiedSong>) -> Unit)? = null,
    val onSelectLocalServer: () -> Unit = {},
    val onSelectServer: (ServerConfig) -> Unit = {},
    val onSyncNow: () -> Unit = {},
    val onGoToSettings: () -> Unit = {},
    val onSubViewActiveChange: (Boolean) -> Unit = {}
)

@Composable
fun LocalLibraryScreen(
    allSongs: List<UnifiedSong>,
    // 「我喜欢的音乐」的数据源由宿主注入：在线模式=服务器收藏，本地/已下载模式=服务器收藏 ∩ 已下载。
    // 为空时回落到 allSongs.filter { isFavorite }，保证断网等异常场景下收藏卡片不会整块消失。
    favoriteSongs: List<UnifiedSong> = emptyList(),
    downloadedSongs: List<UnifiedSong> = emptyList(),
    recentlyPlayedSongs: List<UnifiedSong> = emptyList(),
    recentlyAddedSongs: List<UnifiedSong> = emptyList(),
    serverAlbums: List<UnifiedAlbum> = emptyList(),
    playlists: List<UnifiedPlaylist> = emptyList(),
    activeServerConfig: ServerConfig? = null,
    activeDownloadTasks: List<DownloadTask> = emptyList(),
    activeDownloadCount: Int = 0,
    currentPlayingSong: UnifiedSong? = null,
    isPlaying: Boolean = false,
    locateSongTrigger: Int = 0,
    scrollToTopTrigger: Int = 0,
    initialCategory: LibraryCategory? = null,
    currentServerName: String = "本地模式",
    configuredServers: List<ServerConfig> = emptyList(),
    blurAlpha: Float = 0.85f,
    dynamicCardEffects: Boolean = true,
    callbacks: LocalLibraryCallbacks = LocalLibraryCallbacks(),
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val onScrollPositionChange = callbacks.onScrollPositionChange
    val onListScrollingChange = callbacks.onListScrollingChange
    val onSongClick = callbacks.onSongClick
    val onDownloadSong = callbacks.onDownloadSong
    val onDownloadSongWithOptions = callbacks.onDownloadSongWithOptions
    val onBatchDownloadSongsWithOptions = callbacks.onBatchDownloadSongsWithOptions
    val onOpenDownloads = callbacks.onOpenDownloads
    val onRefreshPlaylists = callbacks.onRefreshPlaylists
    val onAutoRefreshPlaylists = callbacks.onAutoRefreshPlaylists ?: callbacks.onRefreshPlaylists
    val onCreatePlaylist = callbacks.onCreatePlaylist
    val onDeletePlaylist = callbacks.onDeletePlaylist
    val onToggleFavorite = callbacks.onToggleFavorite
    val onFetchPlaylistSongs = callbacks.onFetchPlaylistSongs
    val onFetchServerFolders = callbacks.onFetchServerFolders
    val onFetchServerFolderSongs = callbacks.onFetchServerFolderSongs
    val onFetchServerGenres = callbacks.onFetchServerGenres
    val onFetchServerScanStatus = callbacks.onFetchServerScanStatus
    val onTriggerServerScan = callbacks.onTriggerServerScan
    val onDeleteLocalFilePath = callbacks.onDeleteLocalFilePath
    val onDeleteDownloadedSongs = callbacks.onDeleteDownloadedSongs
    val onSelectLocalServer = callbacks.onSelectLocalServer
    val onSelectServer = callbacks.onSelectServer
    val onSyncNow = callbacks.onSyncNow
    val onGoToSettings = callbacks.onGoToSettings
    val onSubViewActiveChange = callbacks.onSubViewActiveChange

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val dimensions = LocalAppDimensions.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val isLandscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val isCompactHeader = screenWidthDp < 390
    val isUltraCompactHeader = screenWidthDp < 350
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)

    // 每次打开资料库随机选取5组不同光影配色，确保5张卡片视觉各异
    val libPalettes = remember { ALL_RANDOM_LIQUID_PALETTES.shuffled().take(5) }

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

    // 排序模式
    // 程序启动默认按「加入时间」：在线模式 = 服务器文件的 mtime（服务器「最近添加」先后），
    // 本地模式 = 本地下载完成时间。与 TV 车机版保持一致，"刚入库的排在前面"才是资料库第一直觉。
    var songSortMode by remember { mutableStateOf("added") } // added, default, name, artist, duration, source
    var isPullRefreshing by remember { mutableStateOf(false) }

    // 下钻视图状态：当前正在查看的集合详情 (歌单、歌手、专辑、流派)
    var activeSubViewTitle by remember { mutableStateOf<String?>(null) }
    var activeSubViewSubtitle by remember { mutableStateOf<String>("") }
    var activePlaylistId by remember { mutableStateOf<String?>(null) }
    var lastSubViewTitle by remember { mutableStateOf<String?>(null) }
    var lastSubViewSubtitle by remember { mutableStateOf<String>("") }
    var activeSubViewSongs by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
    var isLoadingSubView by remember { mutableStateOf(false) }
    var isFromAllPlaylists by remember { mutableStateOf(false) }
    var isFromAllFolders by remember { mutableStateOf(false) }
    var isFromAllArtists by remember { mutableStateOf(false) }
    var isFromAllAlbums by remember { mutableStateOf(false) }
    // 歌手 / 专辑详情页的大图头部（从歌手 / 专辑网格进入，或播放页点歌手 / 专辑跳转时设置）
    var subViewHero by remember { mutableStateOf<LibraryHero?>(null) }
    // 二级页右上角小放大镜：展开后只过滤当前列表
    var subSearchOpen by remember { mutableStateOf(false) }
    var subSearchQuery by remember { mutableStateOf("") }
    // 文件夹 / 歌单歌曲排序（默认按日期，最新在上；再点同一方式切换方向）
    var subSortMode by remember { mutableStateOf(SubSongSort.DATE) }
    var subSortReversed by remember { mutableStateOf(false) }
    // 当前二级页实际显示的顺序（排序 / 搜索后），供「定位正在播放」使用
    val shownSongsHolder = remember { arrayOfNulls<List<UnifiedSong>>(1) }

    // 资料库主列表与二级子列表多选下载状态
    var isMainSongsMultiSelect by remember { mutableStateOf(false) }
    var selectedMainSongIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isSubViewMultiSelect by remember { mutableStateOf(false) }
    var selectedSubViewSongIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    /** 等待确认移出歌单的歌曲（多选） */
    var songsPendingRemoval by remember { mutableStateOf<List<UnifiedSong>?>(null) }
    var isRemovingFromPlaylist by remember { mutableStateOf(false) }
    var removalProgress by remember { mutableStateOf(0f) }
    var removalStep by remember { mutableStateOf("") }
    var songsForBatchDownloadChoice by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }

    val libraryListState = rememberLazyListState()
    val subViewListState = rememberLazyListState()

    // 歌手 / 专辑页：数据来源（资料库 / 在线）与在线平台；资料库没有这位歌手 / 这张专辑的歌时自动切到在线
    var heroSource by remember { mutableStateOf(HeroDataSource.LIBRARY) }
    var heroPlatform by remember { mutableStateOf(callbacks.defaultOnlineSource) }
    var heroLibrarySongs by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
    var heroOnlineLoading by remember { mutableStateOf(false) }
    // 在线结果（进页面就在后台搜，切换开关时即时显示，开关上也能显示在线数量）；null = 还没搜完
    var heroOnlineSongs by remember { mutableStateOf<List<UnifiedSong>?>(null) }
    // 在线分页：平台给的总数（-1 = 不知道）、已取到第几页、是否还有下一页、是否正在加载下一页
    var heroOnlineTotal by remember { mutableIntStateOf(-1) }
    var heroOnlinePage by remember { mutableIntStateOf(0) }
    var heroOnlineHasMore by remember { mutableStateOf(false) }
    var heroOnlineMoreLoading by remember { mutableStateOf(false) }
    // 每次打开歌手 / 专辑页 +1：同一个歌手重新打开（如从歌手专辑页返回）也要重新取数
    var heroOpenNonce by remember { mutableIntStateOf(0) }
    val heroKey = subViewHero?.let { "${it.kind}|${it.title}|${it.artist}|${it.onlineAlbumId}|$heroOpenNonce" }

    // 双端下载完成后刷新当前页面：把页面里的歌曲换成数据库重读后的最新数据（本地路径 / 服务器归属 / 音质）
    val pageRefreshTick = com.lm.player.core.media.SongStatusStore.pageRefreshTick.intValue
    val latestAllSongs by rememberUpdatedState(allSongs)
    LaunchedEffect(pageRefreshTick) {
        if (pageRefreshTick == 0) return@LaunchedEffect
        val byId = latestAllSongs.associateBy { it.id }
        fun fresh(list: List<UnifiedSong>): List<UnifiedSong> = list.map { byId[it.id] ?: it }
        activeSubViewSongs = fresh(activeSubViewSongs)
        heroLibrarySongs = fresh(heroLibrarySongs)
        heroOnlineSongs = heroOnlineSongs?.let { fresh(it) }
    }

    // 歌手专辑页（歌手页「N 张专辑」进入）：资料库 / 在线两种来源，点专辑进入专辑页
    var artistAlbumsFor by remember { mutableStateOf<String?>(null) }
    var artistAlbumsFromAll by remember { mutableStateOf(false) }
    var isFromArtistAlbums by remember { mutableStateOf(false) }
    var artistAlbumsSource by remember { mutableStateOf(HeroDataSource.LIBRARY) }
    var artistOnlineAlbums by remember { mutableStateOf<List<UnifiedAlbum>?>(null) }
    var artistAlbumsLoading by remember { mutableStateOf(false) }
    var artistLibraryAlbums by remember { mutableStateOf<List<UnifiedAlbum>>(emptyList()) }
    var reopenArtistRequest by remember { mutableStateOf<String?>(null) }
    val isAnyScrolling = libraryListState.isScrollInProgress || subViewListState.isScrollInProgress
    LaunchedEffect(isAnyScrolling) {
        onListScrollingChange(isAnyScrolling)
    }

    // 监听是否离开顶部，联动通知外部显示置顶按钮
    val isScrolledAway = remember {
        derivedStateOf {
            if (activeSubViewTitle != null) {
                subViewListState.firstVisibleItemIndex > 0 || subViewListState.firstVisibleItemScrollOffset > 80
            } else {
                libraryListState.firstVisibleItemIndex > 0 || libraryListState.firstVisibleItemScrollOffset > 80
            }
        }
    }
    LaunchedEffect(isScrolledAway.value) {
        onScrollPositionChange(isScrolledAway.value)
    }

    // 触发置顶
    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) {
            if (activeSubViewTitle != null) {
                runCatching { subViewListState.scrollToItem(0) }
            } else {
                runCatching { libraryListState.scrollToItem(0) }
            }
        }
    }

    LaunchedEffect(activeSubViewTitle) {
        isSubViewMultiSelect = false
        selectedSubViewSongIds = emptySet()
        subSearchOpen = false
        subSearchQuery = ""
        if (subViewHero?.title != activeSubViewTitle) subViewHero = null
        if (activeSubViewTitle != null && activeSubViewTitle !in listOf("全部歌单", "全部文件夹", "全部歌手", "全部专辑")) {
            lastSubViewTitle = activeSubViewTitle
            lastSubViewSubtitle = activeSubViewSubtitle
        }
    }

    // 本地文件夹目录结构动态聚合 (根据相对路径或本地物理路径聚合并提取目录名，过滤服务端 JSON 元数据)
    val localFolders = remember(allSongs) {
        allSongs.mapNotNull { song ->
            val rawRelPath = song.relativeFolderPath?.trim()
            val validRelPath = if (
                !rawRelPath.isNullOrBlank() &&
                !rawRelPath.startsWith("{") &&
                !rawRelPath.contains("\"") &&
                !rawRelPath.contains("_id__") &&
                !rawRelPath.startsWith("http")
            ) rawRelPath else null

            val folderName = when {
                validRelPath != null -> {
                    val p = validRelPath.replace('\\', '/')
                    p.trimEnd('/').substringAfterLast('/')
                }
                !song.localFilePath.isNullOrBlank() -> {
                    val p = song.localFilePath!!.replace('\\', '/')
                    val parent = p.substringBeforeLast('/', "")
                    if (parent.isNotBlank()) parent.substringAfterLast('/', "") else null
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
                path = songs.firstOrNull()?.let { s ->
                    val rp = s.relativeFolderPath?.trim()
                    if (!rp.isNullOrBlank() && !rp.startsWith("{") && !rp.contains("\"")) rp else s.localFilePath
                } ?: "",
                songCount = songs.size,
                songs = songs
            )
        }.sortedByDescending { it.songCount }
    }

    // 本地下载离线歌曲聚合：直接关联外部已下载全量曲目（与下载管理器对齐），若未传入则从 allSongs 兜底聚合
    // 增加物理本地文件存在性校验，避免 NAS 远程路径被误归入本地离线。
    //
    // **必须在 IO 线程做**：候选集是整个曲库（自述 2000+ 首），逐条 File.exists() 是 O(n) 磁盘 stat。
    // 原先放在 remember 中执行 —— 即组合期在主线程打满磁盘调用，曲库越大首帧越卡；
    // 而且 remember 的 key 是整个 List，每次重组都要对上千条做 O(n) 深比较。
    // 现在改为 produceState + Dispatchers.IO，并先用 id 列表做轻量 key 以降低重组开销。
    val downloadedSongsKey = remember(downloadedSongs) { downloadedSongs.joinToString(",") { it.id } }
    val allSongsKey = remember(allSongs) { allSongs.size }
    val finalDownloadedSongs by produceState(
        initialValue = downloadedSongs,
        key1 = allSongsKey,
        key2 = downloadedSongsKey
    ) {
        value = withContext(Dispatchers.IO) {
            val validLocal = { s: UnifiedSong ->
                val path = s.localFilePath
                !path.isNullOrBlank() && (path.startsWith("content://") || java.io.File(path).exists())
            }
            val rawList = if (downloadedSongs.isNotEmpty()) {
                downloadedSongs.filter(validLocal)
            } else {
                allSongs.filter { (it.downloadStatus == DownloadStatus.DOWNLOADED || !it.localFilePath.isNullOrBlank()) && validLocal(it) }
            }
            // 按物理文件路径 + 歌曲 ID 双重去重（若同一文件同时存在服务器曲目与 local_storage 临时记录，优先保留服务器曲目）
            val byPath = LinkedHashMap<String, UnifiedSong>(rawList.size)
            for (song in rawList) {
                val key = song.localFilePath?.trim()?.replace('\\', '/')?.lowercase()?.takeIf { it.isNotBlank() }
                    ?: "id:${song.id}"
                val existing = byPath[key]
                if (existing == null) {
                    byPath[key] = song
                } else {
                    val existingIsLocalOnly = existing.serverId in listOf("local_storage", "local_folder", "local_saf", "lemon_online")
                    val incomingIsServer = song.serverId.isNotBlank() && song.serverId !in listOf("local_storage", "local_folder", "local_saf", "lemon_online")
                    if (existingIsLocalOnly && incomingIsServer) {
                        byPath[key] = song
                    }
                }
            }
            byPath.values.distinctBy { it.id }
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

    // 我喜欢的音乐实时聚合：卡片计数与下钻视图共用同一份记忆化数据源。
    // 优先用宿主注入的服务器收藏口径，只有宿主拿不到时才退回本地 isFavorite 标记。
    val favSongs = remember(allSongs, favoriteSongs) {
        (if (favoriteSongs.isNotEmpty()) favoriteSongs else allSongs.filter { it.isFavorite }).distinctBy { it.id }
    }

    // 我喜欢的音乐下钻视图联动刷新。
    // 此前下钻视图只在点击卡片那一刻快照一次，已经进入列表后再点「喜欢」，
    // 新增的歌曲不会出现 —— 这正是"加入喜欢后没有歌曲"的观感来源。
    LaunchedEffect(favSongs) {
        if (activeSubViewTitle == "我喜欢的音乐") {
            activeSubViewSongs = favSongs
            activeSubViewSubtitle = "我的专属珍藏 · 共 ${favSongs.size} 首"
        }
    }

    // 最近播放聚合：优先使用宿主注入的真实播放足迹（在线模式取服务器播放记录，
    // 离线模式取本地持久化记录），为空时才退回「曲库前 30 首」兜底展示。
    // 此前这里直接拿 allSongs.take(30)，既不是播放历史也不是时间序，
    // 与用户实际听过什么完全无关 —— 这正是"最近播放卡片歌曲不对"的根因。
    val recentPlaySongs = remember(recentlyPlayedSongs, allSongs) {
        if (recentlyPlayedSongs.isNotEmpty()) recentlyPlayedSongs else allSongs.take(30)
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

    // 在线模式使用服务器 /api/library/albums 的专辑聚合（宿主注入），本地模式回落本地聚合。
    // 服务器专辑 id 与曲目 albumId 同源 (lemon_album_${md5("$artist/$name")})，可直接做最近添加排序。
    val albums = remember(allSongs, serverAlbums) {
        if (serverAlbums.isNotEmpty()) {
            // 服务器专辑接口若没给出封面或歌曲数量，用曲库里同专辑歌曲兜底补齐
            val coverByAlbumId = HashMap<String, String>(allSongs.size)
            val coverByAlbumTitle = HashMap<String, String>(allSongs.size)
            val countByAlbumId = HashMap<String, Int>(allSongs.size)
            val countByAlbumTitle = HashMap<String, Int>(allSongs.size)
            for (song in allSongs) {
                if (song.albumId.isNotBlank()) {
                    countByAlbumId[song.albumId] = (countByAlbumId[song.albumId] ?: 0) + 1
                    if (song.coverUrl.isNotBlank() && !coverByAlbumId.containsKey(song.albumId)) {
                        coverByAlbumId[song.albumId] = song.coverUrl
                    }
                }
                val titleKey = song.album.ifBlank { "未知专辑" }
                countByAlbumTitle[titleKey] = (countByAlbumTitle[titleKey] ?: 0) + 1
                val singleKey = song.album.ifBlank { "单曲精选" }
                if (singleKey != titleKey) {
                    countByAlbumTitle[singleKey] = (countByAlbumTitle[singleKey] ?: 0) + 1
                }
                if (song.coverUrl.isNotBlank() && !coverByAlbumTitle.containsKey(titleKey)) {
                    coverByAlbumTitle[titleKey] = song.coverUrl
                }
            }
            serverAlbums.map { album ->
                val resolvedCover = if (album.coverUrl.isNotBlank()) {
                    album.coverUrl
                } else {
                    coverByAlbumId[album.id] ?: coverByAlbumTitle[album.title] ?: ""
                }
                val resolvedCount = maxOf(
                    album.songCount,
                    countByAlbumId[album.id] ?: 0,
                    countByAlbumTitle[album.title] ?: 0
                )
                if (resolvedCover == album.coverUrl && resolvedCount == album.songCount) {
                    album
                } else {
                    album.copy(coverUrl = resolvedCover, songCount = resolvedCount)
                }
            }
        } else {
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
    }

    // 「最近添加专辑」按专辑内最新一首歌的加入时间倒序：新入库的专辑排在最前。
    // 时间取服务器文件 mtime（在线）或下载完成时间（本地），与「按加入时间」同一口径。
    val recentAddedAlbums = remember(albums, allSongs) {
        if (albums.isEmpty()) {
            emptyList()
        } else {
            val newestByAlbumId = HashMap<String, Long>(albums.size)
            val newestByAlbumTitle = HashMap<String, Long>(allSongs.size)
            for (song in allSongs) {
                if (song.addedTimestamp <= 0L) continue
                if (song.albumId.isNotBlank()) {
                    val cur = newestByAlbumId[song.albumId] ?: 0L
                    if (song.addedTimestamp > cur) newestByAlbumId[song.albumId] = song.addedTimestamp
                }
                val titleKey = song.album.ifBlank { "单曲精选" }
                val curTitle = newestByAlbumTitle[titleKey] ?: 0L
                if (song.addedTimestamp > curTitle) newestByAlbumTitle[titleKey] = song.addedTimestamp
            }
            albums.sortedWith(
                compareByDescending<UnifiedAlbum> {
                    newestByAlbumId[it.id] ?: newestByAlbumTitle[it.title] ?: 0L
                }.thenByDescending { it.songCount }
            )
        }
    }

    // 歌单排序：最新加入数据的歌单在最前，依次递减显示
    val sortedPlaylists = remember(playlists) {
        playlists.sortedByDescending { it.updatedTimestamp }
    }

    // 动态提取/加载歌单内部歌曲图片 (前 4 首) 用于 4 格子展示，优化高量曲目下(2000+)的检索开销
    val localPlaylistCovers by produceState<Map<String, List<String>>>(
        initialValue = emptyMap(),
        key1 = sortedPlaylists,
        key2 = allSongs.size,
        key3 = "${activeServerConfig?.id}_${currentServerName}"
    ) {
        withContext(Dispatchers.IO) {
            val db = com.lm.player.core.database.ZdsDatabase.getInstance(context)
            val map = HashMap<String, List<String>>()
            
            // 建立 O(1) 预索引，避免千级歌曲反复线性遍历与卡顿
            val songById = HashMap<String, UnifiedSong>(allSongs.size)
            val songByPath = HashMap<String, UnifiedSong>(allSongs.size)
            val songsByAlbum = HashMap<String, MutableList<UnifiedSong>>()
            val songsByFolder = HashMap<String, MutableList<UnifiedSong>>()
            for (s in allSongs) {
                if (s.id.isNotBlank()) songById[s.id] = s
                if (!s.localFilePath.isNullOrBlank()) songByPath[s.localFilePath!!] = s
                if (s.album.isNotBlank()) {
                    songsByAlbum.getOrPut(s.album.lowercase()) { ArrayList() }.add(s)
                }
                val rp = s.relativeFolderPath?.trim()?.replace('\\', '/')
                if (!rp.isNullOrBlank()) {
                    songsByFolder.getOrPut(rp.lowercase()) { ArrayList() }.add(s)
                    val folderName = rp.trimEnd('/').substringAfterLast('/')
                    if (folderName.isNotBlank()) {
                        songsByFolder.getOrPut(folderName.lowercase()) { ArrayList() }.add(s)
                    }
                }
            }
            val diskFolderCoverCache = HashMap<String, String?>()

            fun isUsableCover(url: String?): Boolean {
                return !url.isNullOrBlank() && !com.lm.player.core.network.LemonMusicProtocol.isKnownMissingEmbeddedCoverUrl(url)
            }

            // 需要网络补齐封面的云端歌单（循环内只收集，循环后并发拉取，避免 N 次串行往返）
            val pendingCoverFetches = ArrayList<Pair<UnifiedPlaylist, MutableList<String>>>()
            for (pl in sortedPlaylists) {
                val covers = ArrayList<String>()

                // 1. 如果已有 previewCovers，先放入
                if (pl.previewCovers.isNotEmpty()) {
                    for (c in pl.previewCovers) {
                        if (isUsableCover(c) && !covers.contains(c) && covers.size < 4) {
                            covers.add(c)
                        }
                    }
                }

                // 2. 如果 coverUrl 包含多张 "|" 切割的图片
                if (covers.size < 4 && pl.coverUrl.contains("|")) {
                    for (c in pl.coverUrl.split("|")) {
                        val tc = c.trim()
                        if (isUsableCover(tc) && !covers.contains(tc) && covers.size < 4) {
                            covers.add(tc)
                        }
                    }
                }

                // 3. 查本地数据库 playlist_songs 关联的封面与音频文件
                if (covers.size < 4) {
                    val dbCovers = db.playlistDao().getPlaylistCoverUrls(pl.id)
                    for (c in dbCovers) {
                        if (isUsableCover(c) && !covers.contains(c) && covers.size < 4) {
                            covers.add(c)
                        }
                    }
                }

                // 4. 若仍不足 4 首，遍历歌单内具体歌曲，并核查 allSongs 或歌曲所在文件夹 cover/folder 图片
                if (covers.size < 4) {
                    val plSongs = db.playlistDao().getSongsForPlaylist(pl.id)
                    for (s in plSongs) {
                        if (covers.size >= 4) break
                        var c = s.coverUrl
                        if (!isUsableCover(c)) {
                            val matched = songById[s.id] ?: (if (!s.localFilePath.isNullOrBlank()) songByPath[s.localFilePath!!] else null)
                            c = matched?.coverUrl ?: ""
                        }
                        if (!isUsableCover(c) && !s.localFilePath.isNullOrBlank()) {
                            val parentPath = s.localFilePath!!.replace('\\', '/').substringBeforeLast('/', "")
                            if (parentPath.isNotBlank()) {
                                val diskCover = diskFolderCoverCache.getOrPut(parentPath) {
                                    try {
                                        val parent = java.io.File(parentPath)
                                        if (parent.exists() && parent.isDirectory) {
                                            val files = parent.listFiles { f ->
                                                val ext = f.extension.lowercase()
                                                ext in listOf("jpg", "jpeg", "png", "webp")
                                            }
                                            val targetImg = files?.firstOrNull {
                                                val name = it.nameWithoutExtension.lowercase()
                                                name in listOf("cover", "folder", "front", "album", "artwork")
                                            } ?: files?.firstOrNull()
                                            targetImg?.let { android.net.Uri.fromFile(it).toString() }
                                        } else null
                                    } catch (_: Exception) { null }
                                }
                                if (!diskCover.isNullOrBlank()) c = diskCover
                            }
                        }
                        if (isUsableCover(c) && !covers.contains(c)) {
                            covers.add(c!!)
                        }
                    }
                }

                // 5. 模糊对齐本地 allSongs 中同名专辑或同名文件夹的歌曲封面
                if (covers.size < 4) {
                    val plNameLower = pl.name.lowercase()
                    val matchedFromAll = songsByAlbum[plNameLower] ?: songsByFolder[plNameLower] ?: emptyList()
                    for (s in matchedFromAll) {
                        if (covers.size >= 4) break
                        val c = s.coverUrl
                        if (isUsableCover(c) && !covers.contains(c)) {
                            covers.add(c)
                        }
                    }
                }

                // 6. 如果是云端歌单且仍不足 4 首（或含有尚未核验 hasPicture 的 /api/tag/cover 链接），拉取一次歌单歌曲核验并补齐真实封面
                val targetCoverCount = minOf(4, pl.songCount.coerceAtLeast(1))
                if (pl.isOnline && onFetchPlaylistSongs != null && (covers.size < targetCoverCount || covers.any { it.contains("/api/tag/cover") })) {
                    pendingCoverFetches.add(pl to covers)
                }

                // 7. 回退 fallbackCoverUrl
                if (covers.isEmpty() && isUsableCover(pl.coverUrl) && !pl.coverUrl.contains("|")) {
                    covers.add(pl.coverUrl)
                }

                if (covers.isNotEmpty()) {
                    map[pl.id] = covers.take(4)
                }
            }
            // 先立即发布一轮已有封面，避免等待网络补齐期间卡片空白
            value = HashMap(map)

            // 并发补齐封面：每个歌单一次网络请求，彼此独立，用 async 并行发出。
            if (pendingCoverFetches.isNotEmpty()) {
                val deferred = pendingCoverFetches.map { (pl, covers) ->
                    async {
                        try {
                            val fetched = onFetchPlaylistSongs?.invoke(pl.id, true).orEmpty()
                            val orderedCovers = ArrayList<String>(4)
                            for (song in fetched) {
                                if (orderedCovers.size >= 4) break
                                val c = song.coverUrl
                                if (isUsableCover(c) && !orderedCovers.contains(c)) {
                                    orderedCovers.add(c)
                                }
                            }
                            for (c in covers) {
                                if (orderedCovers.size >= 4) break
                                if (isUsableCover(c) && !orderedCovers.contains(c)) {
                                    orderedCovers.add(c)
                                }
                            }
                            if (orderedCovers.isNotEmpty()) {
                                covers.clear()
                                covers.addAll(orderedCovers)
                            }
                        } catch (_: Exception) {
                            // 单个歌单失败不影响其它歌单，封面拿不到就沿用已有回退
                        }
                    }
                }
                deferred.forEach {
                    try {
                        it.await()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e   // 与全工程约定一致：取消必须重抛，不能被 runCatching 吞掉
                    } catch (_: Exception) {
                        // 单个歌单封面拉取失败不影响其它歌单
                    }
                }
                for ((pl, covers) in pendingCoverFetches) {
                    if (covers.isNotEmpty()) map[pl.id] = covers.take(4)
                }
                value = HashMap(map)
            }
        }
    }

    // 切换本地曲库与在线服务器时：自动复位旧下钻视图状态并刷新歌单数据，防止跨模式数据残留与状态冲突
    LaunchedEffect(activeServerConfig?.id, currentServerName) {
        activeSubViewTitle = null
        activePlaylistId = null
        activeSubViewSongs = emptyList()
        isMainSongsMultiSelect = false
        selectedMainSongIds = emptySet()
        isSubViewMultiSelect = false
        selectedSubViewSongIds = emptySet()
        if (activeServerConfig != null && activeServerConfig.type == ServerType.LEMON_MUSIC) {
            onAutoRefreshPlaylists()
        }
    }

    // 层级返回调度器：如果从「全部歌单」、「全部文件夹」、「全部歌手」或「全部专辑」进入下级详情，先返回上层网格，再返回资料库首页
    LaunchedEffect(heroKey) {
        val hero = subViewHero ?: return@LaunchedEffect
        heroLibrarySongs = activeSubViewSongs
        heroOnlineSongs = null
        // 从在线专辑进入：平台跟着那张专辑
        hero.onlineAlbumId.removePrefix("lemon_discover_album_").substringBefore('_', "")
            .takeIf { hero.onlineAlbumId.isNotBlank() && it.isNotBlank() }
            ?.let { heroPlatform = OnlineMusicSource.fromKey(it) }
        heroSource = if (activeSubViewSongs.isEmpty() && callbacks.onSearchOnlineForHero != null) HeroDataSource.ONLINE else HeroDataSource.LIBRARY
    }
    // 在线结果：进页面只取第一页（99 首 + 平台给的总数），往下滑到底部附近再一页一页加载，最多 999 首
    LaunchedEffect(heroKey, heroPlatform) {
        val hero = subViewHero ?: return@LaunchedEffect
        val search = callbacks.onSearchOnlineForHero ?: return@LaunchedEffect
        heroOnlineSongs = null
        heroOnlineTotal = -1
        heroOnlinePage = 0
        heroOnlineHasMore = false
        heroOnlineMoreLoading = false
        heroOnlineLoading = true
        val first = try {
            search(hero.kind == LibraryHero.Kind.ARTIST, hero.title, hero.artist, heroPlatform, hero.onlineAlbumId, 1)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            HeroOnlinePage(emptyList(), 0, false)
        }
        heroOnlineLoading = false
        if (subViewHero !== hero) return@LaunchedEffect
        heroOnlineSongs = first.songs.distinctBy { it.id }.take(HERO_ONLINE_MAX)
        heroOnlineTotal = first.total
        heroOnlinePage = 1
        heroOnlineHasMore = first.hasMore && heroOnlineSongs!!.size < HERO_ONLINE_MAX
    }
    // 滑动分批加载：在线模式下列表滑到倒数 15 行以内就取下一页（一次只取一页，取完若仍在底部会接着取）
    LaunchedEffect(heroKey, heroPlatform, heroSource) {
        val hero = subViewHero ?: return@LaunchedEffect
        val search = callbacks.onSearchOnlineForHero ?: return@LaunchedEffect
        if (heroSource != HeroDataSource.ONLINE) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow {
            val info = subViewListState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val nearEnd = info.totalItemsCount > 0 && last >= info.totalItemsCount - 15
            Triple(nearEnd && heroOnlineHasMore && !heroOnlineLoading, heroOnlinePage, heroOnlineSongs?.size ?: -1)
        }.collect { (shouldLoad, page, _) ->
            if (!shouldLoad || page <= 0 || subViewHero !== hero) return@collect
            heroOnlineMoreLoading = true
            val next = try {
                search(hero.kind == LibraryHero.Kind.ARTIST, hero.title, hero.artist, heroPlatform, hero.onlineAlbumId, page + 1)
            } catch (e: kotlinx.coroutines.CancellationException) {
                heroOnlineMoreLoading = false
                throw e
            } catch (_: Exception) {
                null
            }
            heroOnlineMoreLoading = false
            if (subViewHero !== hero) return@collect
            if (next == null) {
                heroOnlineHasMore = false
                return@collect
            }
            val old = heroOnlineSongs.orEmpty()
            val seen = old.mapTo(HashSet()) { it.id }
            val merged = old + next.songs.filter { seen.add(it.id) }
            heroOnlineSongs = merged.take(HERO_ONLINE_MAX)
            if (next.total > 0) heroOnlineTotal = next.total
            heroOnlinePage = page + 1
            heroOnlineHasMore = next.hasMore && merged.size < HERO_ONLINE_MAX
        }
    }
    // 显示的列表跟着开关走（只在仍停留在这一页时写，离开后到达的结果不会写到别的列表上）
    LaunchedEffect(heroKey, heroSource, heroOnlineSongs) {
        val hero = subViewHero ?: return@LaunchedEffect
        if (activeSubViewTitle != hero.title) return@LaunchedEffect
        val target = if (heroSource == HeroDataSource.ONLINE) heroOnlineSongs.orEmpty() else heroLibrarySongs
        if (activeSubViewSongs !== target) activeSubViewSongs = target
    }
    // 歌手专辑页：在线专辑后台预取
    LaunchedEffect(artistAlbumsFor, heroPlatform) {
        val name = artistAlbumsFor ?: return@LaunchedEffect
        val search = callbacks.onSearchOnlineArtistAlbums ?: return@LaunchedEffect
        artistOnlineAlbums = null
        artistAlbumsLoading = true
        val found = try {
            search(name, heroPlatform)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        artistAlbumsLoading = false
        if (artistAlbumsFor == name) artistOnlineAlbums = found.take(999)
    }

    val handleSubViewBack: () -> Unit = {
        if (isDownloadManagementMode) {
            isDownloadManagementMode = false
            selectedDownloadSongIds.clear()
        } else if (isSubViewMultiSelect) {
            isSubViewMultiSelect = false
            selectedSubViewSongIds = emptySet()
        } else if (isFromArtistAlbums && artistAlbumsFor != null && activeSubViewTitle != "${artistAlbumsFor} · 专辑") {
            // 专辑页 → 回到歌手专辑页
            activeSubViewTitle = "${artistAlbumsFor} · 专辑"
            activeSubViewSubtitle = "歌手专辑"
            isFromArtistAlbums = false
        } else if (artistAlbumsFor != null && activeSubViewTitle == "${artistAlbumsFor} · 专辑") {
            // 歌手专辑页 → 回到歌手页
            reopenArtistRequest = artistAlbumsFor
            artistAlbumsFor = null
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
            activePlaylistId = null
            isFromAllPlaylists = false
            isFromAllFolders = false
            isFromAllArtists = false
            isFromAllAlbums = false
            artistAlbumsFor = null
            isFromArtistAlbums = false
        }
    }

    // 歌手名匹配：完全一致，或多人合唱（A / B、A、B）里包含该歌手
    fun artistMatches(songArtist: String, name: String): Boolean {
        val a = songArtist.ifBlank { "未知歌手" }
        if (a == name) return true
        return a.split('/', '、', ',', '，', '&', ';', '；').any { it.trim().equals(name, ignoreCase = true) }
    }

    // 打开歌手详情页（大图 + 简介 + 歌曲列表）
    val openArtistPage: (String, Boolean) -> Unit = { rawName, fromAll ->
        val name = rawName.ifBlank { "未知歌手" }
        val exact = allSongs.filter { it.artist.ifBlank { "未知歌手" } == name }
        val songs = exact.ifEmpty { allSongs.filter { artistMatches(it.artist, name) } }
        val artist = artists.firstOrNull { it.name == name }
        val albumCount = songs.map { it.album.trim() }.filter { it.isNotBlank() }.distinct().size
        isFromAllArtists = fromAll
        activePlaylistId = null
        subViewHero = LibraryHero(
            kind = LibraryHero.Kind.ARTIST,
            title = name,
            imageUrl = artist?.avatarUrl?.ifBlank { null } ?: songs.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty(),
            subtitle = "${songs.size} 首歌曲 · $albumCount 张专辑",
            intro = buildArtistIntro(name, songs),
            artist = name
        )
        heroOpenNonce++
        activeSubViewTitle = name
        activeSubViewSubtitle = "歌手 · 共 ${songs.size} 首歌曲"
        activeSubViewSongs = songs
    }

    // 打开专辑详情页（专辑大图 + 简介 + 曲目列表）
    val openAlbumPage: (String, String, String, Boolean) -> Unit = { rawTitle, artistName, albumId, fromAll ->
        val title = rawTitle.ifBlank { "单曲精选" }
        val album = albums.firstOrNull { (albumId.isNotBlank() && it.id == albumId) || it.title == title }
        val songs = allSongs.filter {
            it.album == title ||
                (albumId.isNotBlank() && it.albumId == albumId) ||
                (album != null && album.id.isNotBlank() && it.albumId == album.id) ||
                (title in listOf("未知专辑", "单曲精选") && it.album.isBlank())
        }
        val artist = album?.artist?.ifBlank { null } ?: artistName.ifBlank { songs.firstOrNull()?.artist.orEmpty() }
        isFromAllAlbums = fromAll
        activePlaylistId = null
        subViewHero = LibraryHero(
            kind = LibraryHero.Kind.ALBUM,
            title = title,
            imageUrl = album?.coverUrl?.ifBlank { null } ?: songs.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty(),
            subtitle = listOfNotNull(artist.ifBlank { null }, album?.year?.takeIf { it > 0 }?.let { "$it 年" }).joinToString(" · "),
            intro = buildAlbumIntro(title, artist, songs, album?.year),
            artist = artist
        )
        heroOpenNonce++
        activeSubViewTitle = title
        activeSubViewSubtitle = "$artist · 共 ${maxOf(album?.songCount ?: 0, songs.size)} 首"
        activeSubViewSongs = songs
    }

    // 歌手专辑页：资料库里该歌手的专辑（按歌曲的专辑分组，封面 / id 优先取资料库专辑）
    val openArtistAlbums: (String) -> Unit = { name ->
        val artistSongs = allSongs.filter { artistMatches(it.artist, name) }
        artistLibraryAlbums = artistSongs.filter { it.album.isNotBlank() }
            .groupBy { it.album.trim() }
            .map { (title, songs) ->
                val known = albums.firstOrNull { it.title == title }
                UnifiedAlbum(
                    id = known?.id ?: songs.first().albumId.ifBlank { "local_album_${title.hashCode()}" },
                    title = title,
                    artist = known?.artist?.ifBlank { null } ?: name,
                    coverUrl = known?.coverUrl?.ifBlank { null } ?: songs.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty(),
                    songCount = songs.size
                )
            }
            .sortedByDescending { it.songCount }
        artistAlbumsFromAll = isFromAllArtists
        artistAlbumsFor = name
        artistOnlineAlbums = null
        artistAlbumsSource = if (artistLibraryAlbums.isEmpty() && callbacks.onSearchOnlineArtistAlbums != null) HeroDataSource.ONLINE else HeroDataSource.LIBRARY
        activePlaylistId = null
        activeSubViewTitle = "$name · 专辑"
        activeSubViewSubtitle = "歌手专辑"
        activeSubViewSongs = emptyList()
    }
    LaunchedEffect(reopenArtistRequest) {
        val name = reopenArtistRequest ?: return@LaunchedEffect
        reopenArtistRequest = null
        openArtistPage(name, artistAlbumsFromAll)
    }

    // 其它页面（播放页点歌手 / 专辑）请求打开详情页：放在「切换服务器复位」之后，首次进入资料库时不会被复位清掉
    LaunchedEffect(LibraryNavigator.pending) {
        val request = LibraryNavigator.pending ?: return@LaunchedEffect
        isFromAllPlaylists = false
        isFromAllFolders = false
        isFromAllArtists = false
        isFromAllAlbums = false
        isMainSongsMultiSelect = false
        when (request) {
            is LibraryNavigator.Request.Artist -> openArtistPage(request.name, false)
            is LibraryNavigator.Request.Album -> openAlbumPage(request.title, request.artist, request.albumId, false)
        }
        LibraryNavigator.pending = null
        runCatching { subViewListState.scrollToItem(0) }
    }

    // 触屏滑动返回或物理按键退出下钻视图与多选模式
    val hasActiveSubView = activeSubViewTitle != null || isMainSongsMultiSelect || isDownloadManagementMode || isSubViewMultiSelect
    LaunchedEffect(hasActiveSubView) {
        onSubViewActiveChange(hasActiveSubView)
    }
    DisposableEffect(Unit) {
        onDispose { onSubViewActiveChange(false) }
    }
    if (hasActiveSubView) {
        BackHandler(enabled = true) {
            if (activeSubViewTitle != null) {
                handleSubViewBack()
            } else if (isMainSongsMultiSelect) {
                isMainSongsMultiSelect = false
                selectedMainSongIds = emptySet()
            }
        }
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
            // 按加入时间倒序（新加入的在前）。时间戳缺失(0)的极少数条目自然排在末尾，属预期。
            // addedTimestamp 的口径由调用方按模式注入，这里不区分：
            //   在线模式 = 服务器文件的 mtime（即服务器「最近添加」的先后）
            //   本地模式 = 本地下载完成时间（纯扫描入库的文件回落到文件修改时间）
            "added" -> allSongs.sortedWith(
                compareByDescending<UnifiedSong> { it.addedTimestamp }.thenBy { it.title }
            )
            else -> allSongs
        }
    }

    // 最近添加歌曲切片：在线模式直接用宿主注入的服务器「最近添加」数据（服务器文件 mtime 倒序，
    // 含尚未同步进本地库的新曲目），本地模式才从本地曲库按加入时间倒序推算。
    val recentAddedSongs = remember(recentlyAddedSongs, allSongs) {
        if (recentlyAddedSongs.isNotEmpty()) {
            recentlyAddedSongs.take(20)
        } else {
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
    }

    // 定位正在播放的歌曲（自动切回所属子列表或全部歌曲主列表并瞬间定位到位）
    LaunchedEffect(locateSongTrigger) {
        if (locateSongTrigger > 0 && currentPlayingSong != null) {
            val isGridSubView = activeSubViewTitle in listOf("全部歌单", "全部文件夹", "全部歌手", "全部专辑")
            val subIdx = (shownSongsHolder[0]?.takeIf { activeSubViewTitle != null } ?: activeSubViewSongs).indexOfFirst { isSamePlayingSong(it, currentPlayingSong) }
            val mainIdx = filteredSongs.indexOfFirst { isSamePlayingSong(it, currentPlayingSong) }
            if (activeSubViewTitle != null && !isGridSubView && subIdx >= 0) {
                runCatching { subViewListState.scrollToItem(subIdx + (if (subViewHero != null && subViewHero?.title == activeSubViewTitle) 2 else 0)) }
            } else if (mainIdx >= 0) {
                if (activeSubViewTitle != null) {
                    activeSubViewTitle = null
                    delay(80)
                }
                // 必须与上方 LazyColumn 的 **实际 item 顺序**一一对应，任何一处新增/删除板块
                // 都要同步改这里，否则「定位正在播放歌曲」会滚到错误位置。
                // 实测顺序（见 LOCAL_LIBRARY_SECTION_* 常量与各 item 前的注释）：
                //   0 头部标题栏      —— 始终存在
                //   1 Bento 核心卡片  —— 始终存在（key = "lib_top_bento_cards"）
                //   2 自建与云端歌单  —— **始终存在**（原先漏算，导致定位永远偏一行）
                //   3 最近添加        —— 条件：recentAddedSongs 非空
                // 基础固定项目共 4 项（头部标题栏、Bento核心卡片、自建与云端歌单、全部歌曲标题栏）
                // 动态项目：最近添加、本地文件夹
                var headerCount = 4
                if (recentAddedSongs.isNotEmpty()) headerCount++
                if (localFolders.isNotEmpty()) headerCount++
                runCatching { libraryListState.animateScrollToItem(headerCount + mainIdx) }
            } else if (subIdx >= 0 && lastSubViewTitle != null) {
                activeSubViewTitle = lastSubViewTitle
                activeSubViewSubtitle = lastSubViewSubtitle
                delay(80)
                runCatching { subViewListState.scrollToItem(subIdx + (if (subViewHero != null && subViewHero?.title == activeSubViewTitle) 2 else 0)) }
            }
        }
    }

    // 头部右侧「下载管理 + 服务器切换」：普通模式画在头部；液态玻璃模式登记到上层固定显示
    val pinnedHeaderActions: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isUltraCompactHeader) 4.dp else 6.dp)
        ) {
            com.lm.player.core.designsystem.component.HeaderDownloadPill(
                activeDownloadCount = activeDownloadCount,
                onClick = onOpenDownloads,
                isCompactHeader = isCompactHeader,
                horizontalPad = if (isUltraCompactHeader) 7.dp else if (isCompactHeader) 8.dp else 10.dp,
                verticalPad = if (isCompactHeader) 5.dp else 6.dp,
                surfaceColor = surfaceColor,
                borderColor = borderColor,
                blurAlpha = blurAlpha
            )
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

    Box(modifier = Modifier.fillMaxSize()) {
        // 主视图：浏览资料库各大板块 (当没有进入二级下钻时展示)
        if (activeSubViewTitle == null) {
            com.lm.player.core.designsystem.component.PullToRefreshLayout(
                isRefreshing = isPullRefreshing,
                onRefresh = {
                    isPullRefreshing = true
                    coroutineScope.launch {
                        runCatching {
                            onRefreshPlaylists()
                            onSyncNow()
                            kotlinx.coroutines.delay(800)
                        }
                        isPullRefreshing = false
                    }
                }
            ) {
                LazyColumn(
                    state = libraryListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(
                    top = contentPadding.calculateTopPadding() + 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. 顶部 Header (大标题 + 新建歌单/离线下载/服务器切换按键，自适应不同分辨率)
                item {
                    val pillHorizontalPad = if (isUltraCompactHeader) 7.dp else if (isCompactHeader) 8.dp else 10.dp
                    val pillVerticalPad = if (isCompactHeader) 5.dp else 6.dp
                    val buttonGap = if (isUltraCompactHeader) 4.dp else 6.dp

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(top = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .padding(end = 6.dp)
                            ) {
                                Text(
                                    text = "资料库",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(
                                        fontSize = dimensions.pageTitleSize,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                )
                                Text(
                                    text = if (currentServerName.contains("本地") || currentServerName.contains("已下载")) "本地离线曲库" else "已连接 · $currentServerName",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(
                                        fontSize = dimensions.captionSize,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }

 pinnedHeaderActions()                         }
                    }
                }

                // ==================== 1.5 核心入口流光卡片 (动态液态光影设计) ====================
                item(key = "lib_top_bento_cards") {
                    val topAlbum = recentAddedAlbums.firstOrNull() ?: albums.firstOrNull()

                    @Composable
                    fun ServerStatusCard(cardModifier: Modifier = Modifier) {
                        LibraryLiquidCard(
                            modifier = cardModifier,
                            gradientColors = if (isServerOk) libPalettes[0].gradientColors else listOf(Color(0xFF332410), Color(0xFF1C1308)),
                            glowColor = if (isServerOk) libPalettes[0].glowColor else Color(0xFFFF9500),
                            secondaryGlowColor = if (isServerOk) libPalettes[0].secondaryGlowColor else Color(0xFFFFB340),
                            isAnimated = isLiquidAnimated,
                            isPaused = isAnyScrolling,
                            onClick = {
                                val active = configuredServers.firstOrNull { it.isCurrentActive } ?: configuredServers.firstOrNull()
                                if (active != null) onSelectServer(active) else onGoToSettings()
                            }
                        ) {
                            // 顶部图标与状态
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.White.copy(alpha = 0.22f),
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (isServerOk) Icons.Default.CloudDone else Icons.Default.FolderSpecial,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.White.copy(alpha = 0.20f)
                                ) {
                                    Text(
                                        text = if (isServerOk) "云端服务" else "本地离线",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            // 中间服务名与曲库概览
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = if (isServerOk) currentServerName else "本地离线曲库",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (isServerOk) "全库 ${allSongs.size} 首 · 已存 ${finalDownloadedSongs.size} 首" else "本地共 ${allSongs.size} 首歌曲",
                                    fontSize = 10.sp,
                                    color = Color.White.copy(alpha = 0.82f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // 底部快捷操作微胶囊 (同步 / 模式切换)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isServerOk) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color.White.copy(alpha = 0.18f),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onSyncNow() }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Icon(Icons.Default.Sync, contentDescription = null, tint = Color(0xFFFFC947), modifier = Modifier.size(11.dp))
                                            Text("同步", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                        }
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.White.copy(alpha = 0.18f),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            if (isServerOk) onSelectLocalServer() else {
                                                val active = configuredServers.firstOrNull { it.isCurrentActive } ?: configuredServers.firstOrNull()
                                                if (active != null) onSelectServer(active) else onGoToSettings()
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isServerOk) Icons.Default.Storage else Icons.Default.CloudQueue,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(11.dp)
                                        )
                                        Text(if (isServerOk) "切本地" else "连服务", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    }
                                }
                            }
                        }
                    }

                    @Composable
                    fun RecentAlbumCard(cardModifier: Modifier = Modifier) {
                        LibraryLiquidCard(
                            modifier = cardModifier,
                            gradientColors = libPalettes[1].gradientColors,
                            glowColor = libPalettes[1].glowColor,
                            secondaryGlowColor = libPalettes[1].secondaryGlowColor,
                            isAnimated = isLiquidAnimated,
                            isPaused = isAnyScrolling,
                            onClick = {
                                isFromAllAlbums = false
                                activeSubViewTitle = "全部专辑"
                                activeSubViewSubtitle = "共 ${albums.size} 张专辑"
                                activeSubViewSongs = allSongs
                            }
                        ) {
                            // 顶部图标与专辑计数
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.White.copy(alpha = 0.22f),
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Album,
                                            contentDescription = null,
                                            tint = Color(0xFFFF9500),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.White.copy(alpha = 0.20f)
                                ) {
                                    Text(
                                        text = "共 ${albums.size} 张",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            // 中间封面与专辑信息（展示专辑名称、歌手及专辑下歌曲数量）
                            if (topAlbum != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    AlbumArtworkImage(
                                        model = topAlbum.coverUrl,
                                        seedId = topAlbum.title,
                                        modifier = Modifier.size(36.dp),
                                        cornerRadius = 8.dp
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = topAlbum.title,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${topAlbum.artist} · ${topAlbum.songCount} 首",
                                            fontSize = 10.sp,
                                            color = Color.White.copy(alpha = 0.80f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color.White.copy(alpha = 0.18f)
                                    ) {
                                        Text(
                                            text = "${topAlbum.songCount} 首",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = "最近专辑",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "精选数字专辑集锦",
                                        fontSize = 10.sp,
                                        color = Color.White.copy(alpha = 0.82f)
                                    )
                                }
                            }

                            // 底部全览引导
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "查看全部专辑",
                                    fontSize = 10.sp,
                                    color = Color.White.copy(alpha = 0.75f)
                                )
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    @Composable
                    fun FavoriteSongsCard(cardModifier: Modifier = Modifier) {
                        LibraryLiquidCard(
                            modifier = cardModifier,
                            gradientColors = libPalettes[2].gradientColors,
                            glowColor = libPalettes[2].glowColor,
                            secondaryGlowColor = libPalettes[2].secondaryGlowColor,
                            isAnimated = isLiquidAnimated,
                            isPaused = isAnyScrolling,
                            onClick = {
                                isFromAllPlaylists = false
                                activeSubViewTitle = "我喜欢的音乐"
                                activeSubViewSubtitle = "我的专属珍藏 · 共 ${favSongs.size} 首"
                                activeSubViewSongs = favSongs
                            }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.White.copy(alpha = 0.22f),
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Favorite,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                if (favSongs.isNotEmpty()) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.White.copy(alpha = 0.28f),
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                onSongClick(favSongs.first(), favSongs)
                                            }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "播放",
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "我喜欢的音乐",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${favSongs.size} 首专属珍藏",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.85f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    @Composable
                    fun AllSongsCard(cardModifier: Modifier = Modifier) {
                        LibraryLiquidSmallCard(
                            modifier = cardModifier,
                            title = "全部歌曲",
                            subtitle = "${filteredSongs.size} 首资料库曲目",
                            badgeText = if (isTriggeringScan || serverScanStatus?.isScanning == true) "扫描中" else "全库",
                            icon = Icons.Default.MusicNote,
                            iconColor = libPalettes[3].iconColor,
                            gradientColors = libPalettes[3].gradientColors,
                            glowColor = libPalettes[3].glowColor,
                            secondaryGlowColor = libPalettes[3].secondaryGlowColor,
                            isAnimated = isLiquidAnimated,
                            isPaused = isAnyScrolling,
                            onClick = {
                                activeSubViewTitle = "全部歌曲"
                                activeSubViewSubtitle = "资料库曲目 · 共 ${filteredSongs.size} 首"
                                activeSubViewSongs = filteredSongs
                            }
                        )
                    }

                    @Composable
                    fun DownloadedSongsCard(cardModifier: Modifier = Modifier) {
                        LibraryLiquidSmallCard(
                            modifier = cardModifier,
                            title = "已下载",
                            subtitle = "${finalDownloadedSongs.size} 首本机离线",
                            badgeText = "离线",
                            icon = Icons.Default.FileDownload,
                            iconColor = libPalettes[4].iconColor,
                            gradientColors = libPalettes[4].gradientColors,
                            glowColor = libPalettes[4].glowColor,
                            secondaryGlowColor = libPalettes[4].secondaryGlowColor,
                            isAnimated = isLiquidAnimated,
                            isPaused = isAnyScrolling,
                            onClick = {
                                isFromAllPlaylists = false
                                activeSubViewTitle = "本地下载"
                                activeSubViewSubtitle = "本机离线歌曲 · 共 ${finalDownloadedSongs.size} 首"
                                activeSubViewSongs = finalDownloadedSongs
                                isDownloadManagementMode = false
                                selectedDownloadSongIds.clear()
                            }
                        )
                    }

                    @Composable
                    fun RecentPlaySongsCard(cardModifier: Modifier = Modifier) {
                        LibraryLiquidSmallCard(
                            modifier = cardModifier,
                            title = "最近播放",
                            subtitle = "${recentPlaySongs.size} 首聆听足迹",
                            badgeText = "历史",
                            icon = Icons.Default.History,
                            iconColor = Color(0xFFA78BFA),
                            gradientColors = listOf(Color(0xFF6D28D9), Color(0xFF4C1D95)),
                            glowColor = Color(0xFFA78BFA),
                            isAnimated = isLiquidAnimated,
                            isPaused = isAnyScrolling,
                            onClick = {
                                isFromAllPlaylists = false
                                activeSubViewTitle = "最近播放"
                                activeSubViewSubtitle = "最近聆听足迹 · 共 ${recentPlaySongs.size} 首"
                                activeSubViewSongs = recentPlaySongs
                                isDownloadManagementMode = false
                                selectedDownloadSongIds.clear()
                            }
                        )
                    }

                    @Composable
                    fun ArtistsCard(cardModifier: Modifier = Modifier) {
                        LibraryLiquidSmallCard(
                            modifier = cardModifier,
                            title = "歌手",
                            subtitle = "${artists.size} 位唱作歌手",
                            badgeText = "专栏",
                            icon = Icons.Default.Person,
                            iconColor = Color(0xFFFBBF24),
                            gradientColors = listOf(Color(0xFFD97706), Color(0xFF78350F)),
                            glowColor = Color(0xFFFBBF24),
                            isAnimated = isLiquidAnimated,
                            isPaused = isAnyScrolling,
                            onClick = {
                                isFromAllArtists = false
                                activeSubViewTitle = "全部歌手"
                                activeSubViewSubtitle = "共 ${artists.size} 位歌手"
                                activeSubViewSongs = allSongs
                            }
                        )
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isLandscape) {
                            // 横屏模式：优化卡片高度，避免 1:1 aspectRatio 在横向大宽度下撑得巨大
                            // 第一行：3 张卡片【在线服务状态】 + 【最近专辑】 + 【我喜欢的音乐】
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(125.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                ServerStatusCard(Modifier.weight(1f).fillMaxHeight())
                                RecentAlbumCard(Modifier.weight(1f).fillMaxHeight())
                                FavoriteSongsCard(Modifier.weight(1f).fillMaxHeight())
                            }
                            // 第二行：4 张小长方形卡片【全部歌曲】 + 【已下载】 + 【最近播放】 + 【歌手】
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(68.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                AllSongsCard(Modifier.weight(1f).fillMaxHeight())
                                DownloadedSongsCard(Modifier.weight(1f).fillMaxHeight())
                                RecentPlaySongsCard(Modifier.weight(1f).fillMaxHeight())
                                ArtistsCard(Modifier.weight(1f).fillMaxHeight())
                            }
                        } else {
                            // 竖屏模式：固定高度 154.dp（替代 aspectRatio(1f)），杜绝宽屏下过高
                            // 第一行：2 张卡片【在线服务状态】 + 【最近专辑】
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(154.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                ServerStatusCard(Modifier.weight(1f).fillMaxHeight())
                                RecentAlbumCard(Modifier.weight(1f).fillMaxHeight())
                            }
                            // 第二行：【我喜欢的音乐】 + 【全部歌曲】与【已下载】
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(154.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                FavoriteSongsCard(Modifier.weight(1f).fillMaxHeight())
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AllSongsCard(Modifier.fillMaxWidth().weight(1f))
                                    DownloadedSongsCard(Modifier.fillMaxWidth().weight(1f))
                                }
                            }
                            // 第三行：2 张小长方形卡片【最近播放】 + 【歌手】
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(73.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                RecentPlaySongsCard(Modifier.weight(1f).fillMaxHeight())
                                ArtistsCard(Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                    }
                }

                // 2. 【自建与云端歌单】板块 (Playlists Horizontal Row)
                item {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "自建与云端歌单",
                                fontSize = dimensions.sectionTitleSize,
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
                                    text = "全部 ${playlists.size} 个",
                                    fontSize = dimensions.captionSize,
                                    fontWeight = FontWeight.Medium,
                                    color = AppleRed,
                                    modifier = Modifier.clickable {
                                        activeSubViewTitle = "全部歌单"
                                        activeSubViewSubtitle = "共 ${playlists.size} 个歌单"
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
                            // A. 自建与服务端歌单（最新加入数据的歌单在最前，依次递减显示）
                            items(sortedPlaylists, key = { it.id }) { pl ->
                                PlaylistCardItem(
                                    playlist = pl,
                                    covers = localPlaylistCovers[pl.id] ?: pl.previewCovers,
                                    onClick = {
                                        isFromAllPlaylists = false
                                        activeSubViewTitle = pl.name
                                        activePlaylistId = pl.id
                                        activeSubViewSubtitle = "${if (pl.isOnline) "云端歌单" else "本地歌单"} · ${pl.songCount} 首"
                                        if (onFetchPlaylistSongs != null) {
                                            isLoadingSubView = true
                                            coroutineScope.launch {
                                                // 必须 try/finally 复位 loading：回调抛异常（网络抖动、DB 异常）时
                                                // 若不复位，isLoadingSubView 会永远为 true → 页面停在永久转圈，
                                                // 用户只能杀进程重开。
                                                try {
                                                    val fetched = onFetchPlaylistSongs(pl.id, pl.isOnline)
                                                    activeSubViewSongs = fetched
                                                    activeSubViewSubtitle = "${if (pl.isOnline) "云端歌单" else "本地歌单"} · ${maxOf(pl.songCount, fetched.size)} 首"
                                                } catch (e: kotlinx.coroutines.CancellationException) {
                                                    throw e
                                                } catch (_: Exception) {
                                                    activeSubViewSongs = emptyList()
                                                } finally {
                                                    isLoadingSubView = false
                                                }
                                            }
                                        } else {
                                            activeSubViewSongs = allSongs.filter { it.album == pl.name }
                                        }
                                    }
                                )
                            }

                            // B. 新建歌单快捷卡片 (放置在最后面)
                            item {
                                CreatePlaylistActionCard(
                                    onClick = { isCreatePlaylistDialogOpen = true }
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
                                    fontSize = dimensions.sectionTitleSize,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "全部 ${recentAddedSongs.size} 首",
                                    fontSize = dimensions.captionSize,
                                    fontWeight = FontWeight.Medium,
                                    color = AppleRed,
                                    modifier = Modifier.clickable {
                                        isFromAllPlaylists = false
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
                                    fontSize = dimensions.sectionTitleSize,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "全部 ${localFolders.size} 个",
                                    fontSize = dimensions.captionSize,
                                    fontWeight = FontWeight.Medium,
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



                // 6. 【歌曲】板块 (Songs Header & Full List)
                item {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "全部歌曲",
                                    fontSize = dimensions.sectionTitleSize,
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
                                        fontSize = dimensions.badgeSize,
                                        fontWeight = FontWeight.Bold,
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
                                        "added" -> "按加入时间"
                                        else -> "默认排序"
                                    },
                                    fontSize = dimensions.captionSize,
                                    fontWeight = FontWeight.Medium,
                                    color = AppleRed,
                                    modifier = Modifier.clickable {
                                        songSortMode = when (songSortMode) {
                                            "default" -> "name"
                                            "name" -> "artist"
                                            "artist" -> "duration"
                                            "duration" -> "source"
                                            "source" -> "added"
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

                        if (filteredSongs.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            SongListPlayAndBatchDownloadBar(
                                totalCount = filteredSongs.size,
                                isMultiSelectMode = isMainSongsMultiSelect,
                                selectedCount = selectedMainSongIds.size,
                                isAllSelected = filteredSongs.isNotEmpty() && selectedMainSongIds.size == filteredSongs.size,
                                onPlayAll = {
                                    filteredSongs.firstOrNull()?.let { onSongClick(it, filteredSongs) }
                                },
                                onEnterMultiSelect = {
                                    isMainSongsMultiSelect = true
                                    selectedMainSongIds = emptySet()
                                },
                                onExitMultiSelect = {
                                    isMainSongsMultiSelect = false
                                    selectedMainSongIds = emptySet()
                                },
                                onToggleSelectAll = {
                                    selectedMainSongIds = if (selectedMainSongIds.size == filteredSongs.size) {
                                        emptySet()
                                    } else {
                                        filteredSongs.map { it.id }.toSet()
                                    }
                                },
                                onBatchDownloadClick = {
                                    val selectedSongs = filteredSongs.filter { selectedMainSongIds.contains(it.id) }
                                    if (selectedSongs.isNotEmpty()) {
                                        songsForBatchDownloadChoice = selectedSongs
                                    }
                                }
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
                    itemsIndexed(
                        items = filteredSongs,
                        key = { idx, song -> "${song.id}_$idx" },
                        contentType = { _, _ -> "library_song_item" }
                    ) { idx, song ->
                        val isSelected = selectedMainSongIds.contains(song.id)
                        Column {
                            if (songSortMode == "source") {
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
                                currentPlayingSong = currentPlayingSong,
                                isPlaying = isPlaying,
                                isMultiSelectMode = isMainSongsMultiSelect,
                                isSelected = isSelected,
                                onToggleSelect = {
                                    selectedMainSongIds = if (isSelected) {
                                        selectedMainSongIds - song.id
                                    } else {
                                        selectedMainSongIds + song.id
                                    }
                                },
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

        // 顶部渐进毛玻璃条已删除：应用不是 edge-to-edge，它只会在状态栏下方形成一条白 / 灰条
    }

        // 二级下钻详情视图 (页面内展示：歌单曲目、歌手曲目、专辑曲目，绝不遮挡底部播放栏)
        if (activeSubViewTitle != null) {
            val isArtistAlbumsPage = artistAlbumsFor != null && activeSubViewTitle == "${artistAlbumsFor} · 专辑"
            val isGridSubView = activeSubViewTitle in listOf("全部歌单", "全部文件夹", "全部歌手", "全部专辑") || isArtistAlbumsPage
            val heroMode = subViewHero != null && subViewHero?.title == activeSubViewTitle && !isGridSubView
            val searchQ = subSearchQuery.trim()
            // 文件夹 / 歌单（含「我喜欢的音乐」）支持排序，默认按日期最新在上
            val sortableSubView = !isGridSubView && !heroMode &&
                (activePlaylistId != null || activeSubViewTitle?.startsWith("文件夹 · ") == true || activeSubViewTitle == "我喜欢的音乐" || activeSubViewTitle == "本地下载")
            val sizeSortActive = sortableSubView && subSortMode == SubSongSort.SIZE
            val subSongSizes by androidx.compose.runtime.produceState(emptyMap<String, Long>(), activeSubViewSongs, sizeSortActive) {
                value = if (sizeSortActive) {
                    withContext(Dispatchers.IO) { activeSubViewSongs.associate { it.id to estimateSongBytes(it) } }
                } else emptyMap()
            }
            val sortedSubViewSongs = remember(activeSubViewSongs, sortableSubView, subSortMode, subSortReversed, subSongSizes) {
                if (sortableSubView) sortSubViewSongs(activeSubViewSongs, subSortMode, subSortReversed, subSongSizes) else activeSubViewSongs
            }
            val shownSubViewSongs = if (searchQ.isEmpty()) sortedSubViewSongs else sortedSubViewSongs.filter { it.matchesQuery(searchQ) }
            shownSongsHolder[0] = shownSubViewSongs
            // 液态玻璃：标题栏在上层玻璃里，列表铺满到屏幕顶部，顶部留出玻璃顶栏的高度
            val subGlassBars = com.lm.player.core.designsystem.glass.rememberLiquidGlassActive()
            val subTopInset = if (subGlassBars) {
                with(androidx.compose.ui.platform.LocalDensity.current) { com.lm.player.core.designsystem.glass.GlassTopBars.expandedHeightPx.toDp() } + 4.dp
            } else 0.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .then(if (subGlassBars) Modifier else Modifier.statusBarsPadding())
                    .padding(horizontal = 16.dp)
            ) {
                // 普通歌曲子列表：播放全部 + 多选/全选下载操作栏（放置在播放全部按钮旁边）
                val playBatchBar: @Composable () -> Unit = {
                    SongListPlayAndBatchDownloadBar(
                        totalCount = activeSubViewSongs.size,
                        isMultiSelectMode = isSubViewMultiSelect,
                        selectedCount = selectedSubViewSongIds.size,
                        isAllSelected = activeSubViewSongs.isNotEmpty() && selectedSubViewSongIds.size == activeSubViewSongs.size,
                        onPlayAll = {
                            shownSubViewSongs.firstOrNull()?.let { onSongClick(it, shownSubViewSongs) }
                        },
                        onEnterMultiSelect = {
                            isSubViewMultiSelect = true
                            selectedSubViewSongIds = emptySet()
                        },
                        onExitMultiSelect = {
                            isSubViewMultiSelect = false
                            selectedSubViewSongIds = emptySet()
                        },
                        onToggleSelectAll = {
                            selectedSubViewSongIds = if (selectedSubViewSongIds.size == activeSubViewSongs.size) {
                                emptySet()
                            } else {
                                activeSubViewSongs.map { it.id }.toSet()
                            }
                        },
                        onBatchDownloadClick = {
                            val selectedSongs = activeSubViewSongs.filter { selectedSubViewSongIds.contains(it.id) }
                            if (selectedSongs.isNotEmpty()) {
                                songsForBatchDownloadChoice = selectedSongs
                            }
                        },
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                // 网格页（全部歌单 / 歌手 / 专辑 / 文件夹）的滚动状态：每个网格页各用一个
                val subGridState = remember(activeSubViewTitle) { androidx.compose.foundation.lazy.grid.LazyGridState() }
                // 液态玻璃顶栏滚动收缩（与歌手页一致）：往下滚过一小段收成「返回 + 标题 · 数量」小胶囊，回到顶部才展开
                val listCollapsePx = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
                val listExpandPx = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
                var listCollapsed by remember(activeSubViewTitle) { mutableStateOf(false) }
                LaunchedEffect(activeSubViewTitle, isGridSubView, subGridState) {
                    snapshotFlow {
                        if (isGridSubView) subGridState.firstVisibleItemIndex to subGridState.firstVisibleItemScrollOffset
                        else subViewListState.firstVisibleItemIndex to subViewListState.firstVisibleItemScrollOffset
                    }.collect { (idx, off) ->
                        if (!listCollapsed && (idx > 0 || off > listCollapsePx)) listCollapsed = true
                        else if (listCollapsed && idx == 0 && off < listExpandPx) listCollapsed = false
                    }
                }
                val showCompactBar = !heroMode && listCollapsed &&
                    !subSearchOpen && !isSubViewMultiSelect && !isDownloadManagementMode

                // 固定头部（标题栏 / 播放全部 / 管理条）：液态玻璃模式下放进上层的玻璃顶栏，列表从它下面穿过
                val subHeaderBlock: @Composable ColumnScope.() -> Unit = {
                AnimatedVisibility(
                    visible = showCompactBar,
                    enter = fadeIn(tween(220)) + androidx.compose.animation.expandVertically(tween(260)),
                    exit = fadeOut(tween(160)) + androidx.compose.animation.shrinkVertically(tween(240))
                ) {
                    val scrollSubViewToTop: () -> Unit = {
                        coroutineScope.launch {
                            if (isGridSubView) subGridState.animateScrollToItem(0) else subViewListState.animateScrollToItem(0)
                        }
                    }
                    if (heroMode) {
                        // 歌手 / 专辑页：收起后胶囊带头像 / 封面
                        LibraryHeroTopBar(
                            hero = subViewHero!!,
                            songCount = activeSubViewSongs.size,
                            collapse = { 1f },
                            onBack = handleSubViewBack,
                            onTapCapsule = scrollSubViewToTop
                        )
                    } else {
                        LibraryListCompactBar(
                            title = activeSubViewTitle ?: "",
                            info = activeSubViewSubtitle.substringAfterLast("·").trim(),
                            onBack = handleSubViewBack,
                            onTapCapsule = scrollSubViewToTop
                        )
                    }
                }
                AnimatedVisibility(
                    visible = !showCompactBar,
                    enter = fadeIn(tween(220)) + androidx.compose.animation.expandVertically(tween(260)),
                    exit = fadeOut(tween(160)) + androidx.compose.animation.shrinkVertically(tween(240))
                ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                // 歌手 / 专辑页：返回键 + 头部滑走后出现的小胶囊（图 + 名称 + 歌曲数），右侧空白处是「资料库 / 在线」与平台选择
                if (heroMode) {
                    val heroCollapsePx = with(androidx.compose.ui.platform.LocalDensity.current) { 110.dp.toPx() }
                    val heroCollapse = remember(heroCollapsePx) {
                        derivedStateOf {
                            if (subViewListState.firstVisibleItemIndex > 0) 1f
                            else (subViewListState.firstVisibleItemScrollOffset / heroCollapsePx).coerceIn(0f, 1f)
                        }
                    }
                    val heroCenterVisible by remember(heroCollapse) { derivedStateOf { heroCollapse.value < 0.6f } }
                    LibraryHeroTopBar(
                        hero = subViewHero!!,
                        songCount = activeSubViewSongs.size,
                        collapse = { heroCollapse.value },
                        onBack = handleSubViewBack,
                        onTapCapsule = { coroutineScope.launch { subViewListState.animateScrollToItem(0) } },
                        trailing = if (heroSource == HeroDataSource.ONLINE && callbacks.onSearchOnlineForHero != null) {
                            { HeroPlatformPicker(platform = heroPlatform, loading = heroOnlineLoading, onPlatformChange = { heroPlatform = it }) }
                        } else null,
                        center = if (callbacks.onSearchOnlineForHero != null) {
                            {
                                HeroSourceToggle(
                                    source = heroSource,
                                    libraryLabel = "资料库 ${heroLibrarySongs.size}",
                                    onlineLabel = when {
                                        heroOnlineSongs == null -> "在线 …"
                                        heroOnlineHasMore && heroOnlineTotal > 0 -> "在线 ${minOf(heroOnlineTotal, HERO_ONLINE_MAX)}"
                                        heroOnlineHasMore -> "在线 ${heroOnlineSongs!!.size}+"
                                        else -> "在线 ${heroOnlineSongs!!.size}"
                                    },
                                    onSelect = { heroSource = it }
                                )
                            }
                        } else null,
                        showCenter = heroCenterVisible
                    )
                }
                // 顶部返回与标题栏（歌手 / 专辑大图页由上面的顶栏 + 列表顶部的大图头部代替）
                if (!heroMode) Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
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
                    if (subSearchOpen) {
                        LibrarySubViewSearchField(
                            query = subSearchQuery,
                            onQueryChange = { subSearchQuery = it },
                            onClose = {
                                subSearchOpen = false
                                subSearchQuery = ""
                            },
                            placeholder = when (activeSubViewTitle) {
                                "全部歌单" -> "搜索歌单"
                                "全部歌手" -> "搜索歌手"
                                "全部文件夹" -> "搜索文件夹"
                                "全部专辑" -> "搜索专辑或歌手"
                                else -> "搜索歌名、歌手、专辑"
                            },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
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

                    if (sortableSubView) {
                        LibrarySortButton(
                            mode = subSortMode,
                            reversed = subSortReversed,
                            onSelect = { m ->
                                if (m == subSortMode) {
                                    subSortReversed = !subSortReversed
                                } else {
                                    subSortMode = m
                                    subSortReversed = false
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    LibrarySearchIconButton { subSearchOpen = true }
                    Spacer(modifier = Modifier.width(6.dp))
                    }

                    // 特殊页面头部按键（全部歌单新建 / 本地下载管理与播放全部）；搜索展开时让位给搜索框
                    if (!subSearchOpen && activeSubViewTitle == "全部歌单") {
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
                    } else if (!subSearchOpen && activePlaylistId != null) {
                        var showDeleteConfirm by remember { mutableStateOf(false) }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "删除歌单", tint = Color(0xFFFF3B30))
                        }
                        if (showDeleteConfirm) {
                            AlertDialog(
                                onDismissRequest = { showDeleteConfirm = false },
                                title = { Text("删除歌单") },
                                text = { Text("确定要删除歌单「$activeSubViewTitle」吗？此操作无法撤销。") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        showDeleteConfirm = false
                                        val plId = activePlaylistId
                                        activePlaylistId = null
                                        activeSubViewTitle = null
                                        if (plId != null) onDeletePlaylist(plId)
                                    }) { Text("删除", color = Color(0xFFFF3B30)) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
                                }
                            )
                        }
                    }
                }

                if (isArtistAlbumsPage && callbacks.onSearchOnlineArtistAlbums != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(Modifier.weight(1f))
                        HeroSourceToggle(
                            source = artistAlbumsSource,
                            libraryLabel = "资料库 ${artistLibraryAlbums.size}",
                            onlineLabel = if (artistOnlineAlbums == null) "在线 …" else "在线 ${artistOnlineAlbums!!.size}",
                            onSelect = { artistAlbumsSource = it }
                        )
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            if (artistAlbumsSource == HeroDataSource.ONLINE) {
                                HeroPlatformPicker(platform = heroPlatform, loading = artistAlbumsLoading, onPlatformChange = { heroPlatform = it })
                            }
                        }
                    }
                }
                if (!heroMode && !isGridSubView && activeSubViewTitle != "本地下载" && activeSubViewSongs.isNotEmpty()) {
                    playBatchBar()
                }

                // 本地下载：与「全部歌曲」同样布局的操作条（播放全部 + 管理；管理中 全选 / 删除 / 完成）
                if (activeSubViewTitle == "本地下载" && activeSubViewSongs.isNotEmpty()) {
                    DownloadedSongsActionBar(
                        totalCount = shownSubViewSongs.size,
                        managing = isDownloadManagementMode,
                        selectedCount = selectedDownloadSongIds.size,
                        allSelected = shownSubViewSongs.isNotEmpty() && selectedDownloadSongIds.size == shownSubViewSongs.size,
                        onPlayAll = { shownSubViewSongs.firstOrNull()?.let { onSongClick(it, shownSubViewSongs) } },
                        onEnterManage = { isDownloadManagementMode = true },
                        onToggleSelectAll = {
                            if (selectedDownloadSongIds.size == shownSubViewSongs.size) {
                                selectedDownloadSongIds.clear()
                            } else {
                                selectedDownloadSongIds.clear()
                                selectedDownloadSongIds.addAll(shownSubViewSongs.map { it.id })
                            }
                        },
                        onDelete = { showBatchDeleteLocalDialog = true },
                        onExitManage = {
                            isDownloadManagementMode = false
                            selectedDownloadSongIds.clear()
                        },
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                }
                }
                }
                if (subGlassBars) {
                    com.lm.player.core.designsystem.glass.RegisterGlassTopBar(key = activeSubViewTitle ?: "", content = subHeaderBlock)
                } else {
                    subHeaderBlock()
                }

                Spacer(modifier = Modifier.height(4.dp))

                if (activeSubViewTitle == "全部歌单") {
                    val shownPlaylists = sortedPlaylists.filter { searchQ.isEmpty() || it.name.contains(searchQ, ignoreCase = true) }
                    val showFav = searchQ.isEmpty() || "我喜欢的音乐".contains(searchQ, ignoreCase = true)
                    val showRecent = searchQ.isEmpty() || "最近播放".contains(searchQ, ignoreCase = true)
                    val showDownloads = searchQ.isEmpty() || "本地下载".contains(searchQ, ignoreCase = true)
                    val fullSpan: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope) -> androidx.compose.foundation.lazy.grid.GridItemSpan =
                        { androidx.compose.foundation.lazy.grid.GridItemSpan(it.maxLineSpan) }
                    LazyVerticalGrid(
                        state = subGridState,
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 8.dp + subTopInset,
                            bottom = contentPadding.calculateBottomPadding() + 24.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // A. 我的收藏：我喜欢 / 最近播放 / 本地下载
                        if (showFav || showRecent || showDownloads) item(key = "pl_sec_mine", span = fullSpan) {
                            PlaylistSectionHeader("我的收藏")
                        }
                        if (showFav) item(key = "pl_fav") {
                            PlaylistTile(
                                title = "我喜欢的音乐",
                                subtitle = "${favSongs.size} 首",
                                onClick = {
                                    isFromAllPlaylists = true
                                    activeSubViewTitle = "我喜欢的音乐"
                                    activeSubViewSubtitle = "我的专属珍藏 · 共 ${favSongs.size} 首"
                                    activeSubViewSongs = favSongs
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { SpecialPlaylistArtwork(Icons.Default.Favorite, listOf(Color(0xFFFA233B), Color(0xFFFF5E3A))) }
                        }
                        if (showRecent) item(key = "pl_recent") {
                            PlaylistTile(
                                title = "最近播放",
                                subtitle = "${recentPlaySongs.size} 首",
                                onClick = {
                                    isFromAllPlaylists = true
                                    activeSubViewTitle = "最近播放"
                                    activeSubViewSubtitle = "最近聆听足迹 · 共 ${recentPlaySongs.size} 首"
                                    activeSubViewSongs = recentPlaySongs
                                    isDownloadManagementMode = false
                                    selectedDownloadSongIds.clear()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { SpecialPlaylistArtwork(Icons.Default.History, listOf(Color(0xFF5856D6), Color(0xFFAF52DE))) }
                        }
                        if (showDownloads) item(key = "pl_downloads") {
                            PlaylistTile(
                                title = "本地下载",
                                subtitle = "${finalDownloadedSongs.size} 首",
                                onClick = {
                                    isFromAllPlaylists = true
                                    activeSubViewTitle = "本地下载"
                                    activeSubViewSubtitle = "本机离线歌曲 · 共 ${finalDownloadedSongs.size} 首"
                                    activeSubViewSongs = finalDownloadedSongs
                                    isDownloadManagementMode = false
                                    selectedDownloadSongIds.clear()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { SpecialPlaylistArtwork(Icons.Default.DownloadForOffline, listOf(Color(0xFF007AFF), Color(0xFF5AC8FA))) }
                        }

                        // B. 自建与云端歌单（最新加入数据的歌单在最前）+ 新建入口
                        item(key = "pl_sec_lists", span = fullSpan) {
                            PlaylistSectionHeader("我的歌单", shownPlaylists.size)
                        }
                        items(shownPlaylists, key = { it.id }) { pl ->
                            PlaylistTile(
                                title = pl.name,
                                subtitle = if (pl.songCount > 0) "${pl.songCount} 首" else "空歌单",
                                onClick = {
                                    isFromAllPlaylists = true
                                    activeSubViewTitle = pl.name
                                    activePlaylistId = pl.id
                                    activeSubViewSubtitle = "${if (pl.isOnline) "云端歌单" else "本地歌单"} · ${pl.songCount} 首"
                                    if (onFetchPlaylistSongs != null) {
                                        isLoadingSubView = true
                                        coroutineScope.launch {
                                            try {
                                                val fetched = onFetchPlaylistSongs(pl.id, pl.isOnline)
                                                activeSubViewSongs = fetched
                                                activeSubViewSubtitle = "${if (pl.isOnline) "云端歌单" else "本地歌单"} · ${maxOf(pl.songCount, fetched.size)} 首"
                                            } catch (e: kotlinx.coroutines.CancellationException) {
                                                throw e
                                            } catch (_: Exception) {
                                                activeSubViewSongs = emptyList()
                                            } finally {
                                                isLoadingSubView = false
                                            }
                                        }
                                    } else {
                                        activeSubViewSongs = allSongs.filter { it.album == pl.name }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { PlaylistGridArtwork(pl, localPlaylistCovers[pl.id] ?: pl.previewCovers) }
                        }
                        if (searchQ.isEmpty()) item(key = "pl_create") {
                            PlaylistTile(
                                title = "新建歌单",
                                subtitle = "本地或云端",
                                onClick = { isCreatePlaylistDialogOpen = true },
                                modifier = Modifier.fillMaxWidth()
                            ) { CreatePlaylistArtwork() }
                        }
                    }
                } else if (activeSubViewTitle == "全部文件夹") {
                    LazyVerticalGrid(
                        state = subGridState,
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 8.dp + subTopInset,
                            bottom = contentPadding.calculateBottomPadding() + 24.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(localFolders.filter { searchQ.isEmpty() || it.name.contains(searchQ, ignoreCase = true) }, key = { it.id }) { folder ->
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
                        state = subGridState,
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 8.dp + subTopInset,
                            bottom = contentPadding.calculateBottomPadding() + 24.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(artists.filter { searchQ.isEmpty() || it.name.contains(searchQ, ignoreCase = true) }, key = { it.id }) { artist ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = BorderStroke(1.dp, borderColor),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { openArtistPage(artist.name, true) }
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
                } else if (isArtistAlbumsPage) {
                    val shownAlbums = (if (artistAlbumsSource == HeroDataSource.ONLINE) artistOnlineAlbums.orEmpty() else artistLibraryAlbums)
                        .filter { searchQ.isEmpty() || it.title.contains(searchQ, ignoreCase = true) }
                    if (shownAlbums.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 100.dp + subTopInset),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Text(
                                text = when {
                                    artistAlbumsSource == HeroDataSource.ONLINE && artistOnlineAlbums == null -> "正在通过本地音源搜索 ${heroPlatform.displayName}…"
                                    artistAlbumsSource == HeroDataSource.ONLINE -> "${heroPlatform.displayName}没有找到这位歌手的专辑，可换一个平台"
                                    else -> "资料库里没有这位歌手的专辑，可切到「在线」"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }
                    } else LazyVerticalGrid(
                        state = subGridState,
                        columns = GridCells.Adaptive(minSize = 140.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 8.dp + subTopInset,
                            bottom = contentPadding.calculateBottomPadding() + 24.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(shownAlbums, key = { "artist_album_" + it.id }) { album ->
                            val isOnlineAlbum = artistAlbumsSource == HeroDataSource.ONLINE
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        isFromArtistAlbums = true
                                        if (isOnlineAlbum) {
                                            openAlbumPage(album.title, album.artist, "", false)
                                            val collectionId = if (album.id.startsWith("lemon_discover_album_")) album.id
                                                else "lemon_discover_album_${heroPlatform.key}_${album.id}"
                                            subViewHero = subViewHero?.copy(
                                                onlineAlbumId = collectionId,
                                                imageUrl = subViewHero?.imageUrl?.ifBlank { null } ?: album.coverUrl
                                            )
                                        } else {
                                            openAlbumPage(album.title, album.artist, album.id, false)
                                        }
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
                                    text = if (album.songCount > 0) "${album.artist} · ${album.songCount} 首" else album.artist,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                } else if (activeSubViewTitle == "全部专辑") {
                    LazyVerticalGrid(
                        state = subGridState,
                        columns = GridCells.Adaptive(minSize = 140.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 8.dp + subTopInset,
                            bottom = contentPadding.calculateBottomPadding() + 24.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(recentAddedAlbums.ifEmpty { albums }.filter { searchQ.isEmpty() || it.title.contains(searchQ, ignoreCase = true) || it.artist.contains(searchQ, ignoreCase = true) }, key = { it.id }) { album ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { openAlbumPage(album.title, album.artist, album.id, true) }
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
                            .padding(top = 100.dp + subTopInset),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), color = AppleRed)
                    }
                } else if (activeSubViewSongs.isEmpty() && !heroMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 100.dp + subTopInset),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Text("暂无歌曲数据", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    }
                } else {
                    LazyColumn(
                        state = subViewListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = subTopInset,
                            bottom = contentPadding.calculateBottomPadding() + 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (heroMode) {
                            item(key = "lib_hero_header") {
                                LibraryHeroHeader(
                                    hero = subViewHero!!,
                                    onArtistClick = { name -> openArtistPage(name, false) },
                                    onAlbumsClick = if (subViewHero?.kind == LibraryHero.Kind.ARTIST) {
                                        { subViewHero?.title?.let { openArtistAlbums(it) } }
                                    } else null,
                                    albumCount = heroLibrarySongs.map { it.album.trim() }.filter { it.isNotBlank() }.distinct().size
                                )
                            }
                            if (activeSubViewSongs.isNotEmpty()) item(key = "lib_hero_bar") { playBatchBar() }
                            if (shownSubViewSongs.isEmpty()) {
                                item(key = "lib_hero_empty") {
                                    Text(
                                        text = when {
                                            heroSource == HeroDataSource.ONLINE && heroOnlineLoading -> "正在通过本地音源搜索 ${heroPlatform.displayName}…"
                                            heroSource == HeroDataSource.ONLINE -> "${heroPlatform.displayName}没有找到相关歌曲，可在右上角换一个平台"
                                            else -> "资料库里暂无相关歌曲，可在右上角切到「在线」"
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(vertical = 24.dp)
                                    )
                                }
                            }
                        }
                        itemsIndexed(
                            items = shownSubViewSongs,
                            key = { idx, song -> "${song.id}_$idx" },
                            contentType = { _, _ -> "subview_song_row" }
                        ) { _, song ->
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
                                                currentPlayingSong = currentPlayingSong,
                                                isPlaying = isPlaying,
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
                                val isSelected = selectedSubViewSongIds.contains(song.id)
                                SongListItemRow(
                                    song = song,
                                    activeDownloadTasks = activeDownloadTasks,
                                    isServerConnected = isServerOk,
                                    currentPlayingSong = currentPlayingSong,
                                    isPlaying = isPlaying,
                                    isMultiSelectMode = isSubViewMultiSelect && activeSubViewTitle != "本地下载",
                                    isSelected = isSelected,
                                    onToggleSelect = {
                                        selectedSubViewSongIds = if (isSelected) {
                                            selectedSubViewSongIds - song.id
                                        } else {
                                            selectedSubViewSongIds + song.id
                                        }
                                    },
                                    onClick = { onSongClick(song, shownSubViewSongs) },
                                    onDownloadClick = { songForDownloadChoice = song },
                                    onDownloadWithOptions = { s, target, quality ->
                                        onDownloadSongWithOptions(s, target, quality)
                                    },
                                    onOpenDownloads = onOpenDownloads
                                )
                            }
                        }
                        if (heroMode && heroSource == HeroDataSource.ONLINE && !heroOnlineSongs.isNullOrEmpty()) {
                            item(key = "lib_hero_more") {
                                val loaded = heroOnlineSongs?.size ?: 0
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (heroOnlineMoreLoading) {
                                        CircularProgressIndicator(color = AppleRed, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        text = when {
                                            heroOnlineMoreLoading -> "正在加载第 ${heroOnlinePage + 1} 页…"
                                            heroOnlineHasMore -> "已加载 $loaded" + (if (heroOnlineTotal > 0) " / ${minOf(heroOnlineTotal, HERO_ONLINE_MAX)}" else "") + " 首 · 继续上滑加载下一页"
                                            else -> "已全部加载 · 共 $loaded 首"
                                        },
                                        fontSize = 12.sp,
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

    // 批量多选下载音质与目标选择弹窗
    if (songsForBatchDownloadChoice.isNotEmpty()) {
        BatchDownloadQualityChoiceDialog(
            selectedCount = songsForBatchDownloadChoice.size,
            isServerConnected = isServerOk,
            onConfirm = { target, quality ->
                val batch = songsForBatchDownloadChoice
                songsForBatchDownloadChoice = emptyList()
                isMainSongsMultiSelect = false
                selectedMainSongIds = emptySet()
                isSubViewMultiSelect = false
                selectedSubViewSongIds = emptySet()
                onBatchDownloadSongsWithOptions(batch, target, quality)
            },
            onDismiss = { songsForBatchDownloadChoice = emptyList() }
        )
    }

    // ==================== 多选：底部悬浮操作条（下载 / 加入歌单 / 移出歌单，与 TV 同一套功能） ====================
    // 放在页面顶层：列表头（播放全部 / 全选）滑出屏幕被回收时悬浮条也不会消失
    val batchBarContext = LocalContext.current
    val libIsLocalMode = activeServerConfig == null || currentServerName.contains("本地") || currentServerName.contains("已下载")
    val activePlaylistIsOnline = playlists.firstOrNull { it.id == activePlaylistId }?.isOnline == true
    // 歌单详情页才能「移出歌单」；服务器歌单要连着服务器才能改（本地模式下看到的是缓存，只读）
    val canRemoveFromPlaylist = callbacks.onRemoveSongsFromPlaylist != null && activePlaylistId != null &&
        (!activePlaylistIsOnline || !libIsLocalMode)
    com.lm.player.core.designsystem.component.SongBatchFloatingBar(
        key = "library_all_songs",
        visible = isMainSongsMultiSelect && activeSubViewTitle == null,
        selectedCount = selectedMainSongIds.size,
        selectedSongs = { filteredSongs.filter { selectedMainSongIds.contains(it.id) } },
        isServerConnected = isServerOk,
        onDownload = { picked, target, quality ->
            isMainSongsMultiSelect = false
            selectedMainSongIds = emptySet()
            onBatchDownloadSongsWithOptions(picked, target, quality)
        },
        onFinished = {
            isMainSongsMultiSelect = false
            selectedMainSongIds = emptySet()
        }
    )
    com.lm.player.core.designsystem.component.SongBatchFloatingBar(
        key = "library_sub_view",
        visible = isSubViewMultiSelect && activeSubViewTitle != null,
        selectedCount = selectedSubViewSongIds.size,
        selectedSongs = { activeSubViewSongs.filter { selectedSubViewSongIds.contains(it.id) } },
        isServerConnected = isServerOk,
        onDownload = { picked, target, quality ->
            isSubViewMultiSelect = false
            selectedSubViewSongIds = emptySet()
            onBatchDownloadSongsWithOptions(picked, target, quality)
        },
        onFinished = {
            isSubViewMultiSelect = false
            selectedSubViewSongIds = emptySet()
        },
        onRemoveFromPlaylist = if (canRemoveFromPlaylist) {
            {
                val picked = activeSubViewSongs.filter { selectedSubViewSongIds.contains(it.id) }
                if (picked.isNotEmpty()) songsPendingRemoval = picked
            }
        } else null
    )

    // 移出歌单：确认 → 进度 → 完成（服务器歌单同步写回服务器；歌曲本身不会被删除）
    val pendingRemoval = songsPendingRemoval
    val removalPlaylistId = activePlaylistId
    val removeHandler = callbacks.onRemoveSongsFromPlaylist
    if (pendingRemoval != null && removalPlaylistId != null && removeHandler != null && isRemovingFromPlaylist) {
        com.lm.player.core.designsystem.component.BatchProgressDialog(
            title = "正在从「${activeSubViewTitle.orEmpty()}」移出 ${pendingRemoval.size} 首",
            progress = removalProgress,
            step = removalStep
        )
    } else if (pendingRemoval != null && removalPlaylistId != null && removeHandler != null) {
        val removalIsOnline = playlists.firstOrNull { it.id == removalPlaylistId }?.isOnline == true
        val playlistTitle = activeSubViewTitle.orEmpty()
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { songsPendingRemoval = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            com.lm.player.core.designsystem.glass.GlassDialogPanel(
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .widthIn(max = 380.dp),
                cornerRadius = 22.dp
            ) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
                    Text("移出歌单", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "确定把选中的 ${pendingRemoval.size} 首歌从「$playlistTitle」移出吗？" +
                            (if (removalIsOnline) "服务器上的歌单会同步更新，" else "") + "歌曲本身不会被删除。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    val btnBg = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(19.dp))
                                .background(btnBg)
                                .clickable { songsPendingRemoval = null },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("取消", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(19.dp))
                                .background(Color(0xFFFF3B30))
                                .clickable {
                                    val songsToRemove = pendingRemoval
                                    coroutineScope.launch {
                                        removalProgress = 0f
                                        removalStep = "准备中…"
                                        isRemovingFromPlaylist = true
                                        val result = try {
                                            removeHandler(removalPlaylistId, removalIsOnline, songsToRemove) { f, step ->
                                                removalProgress = f
                                                removalStep = step
                                            }
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Result.failure(e)
                                        }
                                        isRemovingFromPlaylist = false
                                        songsPendingRemoval = null
                                        result.onSuccess { _ ->
                                            val ids = songsToRemove.map { it.id }.toSet()
                                            if (activePlaylistId == removalPlaylistId) {
                                                activeSubViewSongs = activeSubViewSongs.filter { it.id !in ids }
                                                activeSubViewSubtitle = "${if (removalIsOnline) "云端歌单" else "本地歌单"} · ${activeSubViewSongs.size} 首"
                                                com.lm.player.core.designsystem.component.PlaylistMembership.record(removalPlaylistId, activeSubViewSongs)
                                            }
                                            isSubViewMultiSelect = false
                                            selectedSubViewSongIds = emptySet()
                                            android.widget.Toast.makeText(batchBarContext, "已从「$playlistTitle」移出 ${songsToRemove.size} 首", android.widget.Toast.LENGTH_SHORT).show()
                                        }.onFailure { e ->
                                            android.widget.Toast.makeText(batchBarContext, "移出失败：${e.message ?: "未知错误"}", android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("移出", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }

    // 新建歌单弹窗
    if (isCreatePlaylistDialogOpen) {
        CreatePlaylistDialog(
            name = newPlaylistName,
            onNameChange = { newPlaylistName = it },
            online = newPlaylistIsOnline,
            onOnlineChange = { newPlaylistIsOnline = it },
            serverAvailable = activeServerConfig != null,
            onDismiss = { isCreatePlaylistDialogOpen = false },
            onCreate = {
                if (newPlaylistName.isNotBlank()) {
                    onCreatePlaylist(newPlaylistName.trim(), newPlaylistIsOnline && activeServerConfig != null)
                    newPlaylistName = ""
                    isCreatePlaylistDialogOpen = false
                }
            }
        )
    }
}

/**
 * 本地文件夹卡片 (极简优雅，展示目录名与歌曲数量)
 */
@Composable
private fun FolderCardItem(
    folder: UnifiedFolder,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(176.dp)
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
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
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = folder.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${folder.songCount} 首歌曲",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
 * 歌单 4 格子歌曲封面组件 (2x2 展示歌单内部曲目封面，少于 4 张优雅降级)
 */
@Composable
fun PlaylistFourGridCover(
    covers: List<String>,
    fallbackCoverUrl: String = "",
    seedId: String = "",
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp
) {
    val validCovers = remember(covers, fallbackCoverUrl) {
        val list = covers.filter { it.isNotBlank() }.toMutableList()
        if (list.isEmpty() && fallbackCoverUrl.isNotBlank()) {
            if (fallbackCoverUrl.contains("|")) {
                list.addAll(fallbackCoverUrl.split("|").filter { it.isNotBlank() })
            } else {
                list.add(fallbackCoverUrl)
            }
        }
        list
    }

    Surface(
        shape = RoundedCornerShape(cornerRadius),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shadowElevation = 3.dp,
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(cornerRadius))
    ) {
        val (c0, c1, c2, c3) = remember(validCovers) {
            when {
                validCovers.size >= 4 -> listOf(validCovers[0], validCovers[1], validCovers[2], validCovers[3])
                validCovers.size == 3 -> listOf(validCovers[0], validCovers[1], validCovers[2], validCovers[0])
                validCovers.size == 2 -> listOf(validCovers[0], validCovers[1], validCovers[1], validCovers[0])
                validCovers.size == 1 -> listOf(validCovers[0], validCovers[0], validCovers[0], validCovers[0])
                else -> listOf("", "", "", "")
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AlbumArtworkImage(
                        model = c0,
                        seedId = "${seedId}_0",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        cornerRadius = 0.dp
                    )
                    Spacer(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color.Black.copy(alpha = 0.25f)))
                    AlbumArtworkImage(
                        model = c1,
                        seedId = "${seedId}_1",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        cornerRadius = 0.dp
                    )
                }
                Spacer(modifier = Modifier.height(1.dp).fillMaxWidth().background(Color.Black.copy(alpha = 0.25f)))
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AlbumArtworkImage(
                        model = c2,
                        seedId = "${seedId}_2",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        cornerRadius = 0.dp
                    )
                    Spacer(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color.Black.copy(alpha = 0.25f)))
                    AlbumArtworkImage(
                        model = c3,
                        seedId = "${seedId}_3",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        cornerRadius = 0.dp
                    )
                }
            }

            if (validCovers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

/**
 * 标准自建/云端歌单卡片（4 格子展示歌单歌曲图片）
 */
@Composable
private fun PlaylistCardItem(
    playlist: UnifiedPlaylist,
    covers: List<String> = emptyList(),
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(136.dp)
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
    ) {
        val displayCovers = remember(covers, playlist.previewCovers) {
            if (covers.isNotEmpty()) covers else playlist.previewCovers
        }
        PlaylistFourGridCover(
            covers = displayCovers,
            fallbackCoverUrl = playlist.coverUrl,
            seedId = playlist.id,
            modifier = Modifier
                .aspectRatio(1f)
                .fillMaxWidth(),
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
 * 歌单栏首位新建歌单快捷卡片（与旁边 148x148 歌单卡片保持完全一致尺寸）
 */
@Composable
private fun CreatePlaylistActionCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
        .width(148.dp)
        .height(148.dp)
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.5.dp, AppleRed.copy(alpha = 0.45f)),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
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
                    .size(46.dp)
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
            Spacer(modifier = Modifier.height(10.dp))
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

/**
 * 资料库液态光影流动大卡/正方卡 (仅在 Draw 阶段自绘光影与边框，彻底阻断 Compose 重组开销)
 */
@Composable
private fun LibraryLiquidCard(
    modifier: Modifier = Modifier,
    gradientColors: List<Color>,
    glowColor: Color,
    secondaryGlowColor: Color = Color.White.copy(alpha = 0.28f),
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    isAnimated: Boolean = true,
    isPaused: Boolean = false,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val phaseState = rememberLiquidPhase(enabled = isAnimated, durationMillis = 20_000, paused = isPaused)
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
                    drawRect(Brush.linearGradient(gradientColors))

                    if (isAnimated) {
                        val phase = phaseState.value
                        val rad1 = Math.toRadians(phase.toDouble()).toFloat()
                        val rad2 = rad1 + Math.PI.toFloat()
                        val w = size.width
                        val h = size.height

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
                content()
            }
        }
    }
}

/**
 * 资料库动态液态光影小长方形卡片 (已下载、全部歌曲、最近播放、歌手，连贯无缝循环)
 */
@Composable
private fun LibraryLiquidSmallCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    badgeText: String,
    icon: ImageVector,
    iconColor: Color,
    trailingIcon: ImageVector = Icons.Default.ChevronRight,
    gradientColors: List<Color>,
    glowColor: Color,
    secondaryGlowColor: Color = Color.White.copy(alpha = 0.28f),
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    isAnimated: Boolean = true,
    isPaused: Boolean = false,
    onClick: () -> Unit
) {
    val phaseState = rememberLiquidPhase(enabled = isAnimated, durationMillis = 17_000, paused = isPaused)
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
