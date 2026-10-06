package com.lm.player

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.database.entity.ServerEntity
import com.lm.player.core.database.entity.SongEntity
import androidx.room.invalidationTrackerFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.platform.LocalDensity
import com.lm.player.core.designsystem.theme.AppThemeMode
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.component.BackgroundIslandPermissionDialog
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.designsystem.theme.UiScaleMode
import com.lm.player.core.designsystem.theme.ZDSPlayerTheme
import com.lm.player.core.designsystem.theme.rememberAdaptiveDensity
import com.lm.player.core.designsystem.theme.rememberAppDimensions
import com.lm.player.core.media.DownloadEngine
import com.lm.player.core.media.DownloadRequestPlanner
import com.lm.player.core.media.DynamicIslandManager
import com.lm.player.core.media.LocalMediaScanner
import com.lm.player.core.media.LyricsManager
import com.lm.player.core.media.Media3Factory
import com.lm.player.core.media.PlaybackQueueManager
import com.lm.player.core.media.PlaybackRouter
import com.lm.player.core.media.PlaybackService
import com.lm.player.core.media.SongMatchingResolver
import com.lm.player.core.model.*
import com.lm.player.core.network.LemonMusicProtocol
import com.lm.player.core.network.NetworkClientFactory
import com.lm.player.core.update.AppUpdateManager
import com.lm.player.core.update.UpdateInfo
import com.lm.player.feature.download.DownloadManagerScreen
import com.lm.player.feature.home.LemonDiscoverHomeScreen
import com.lm.player.feature.home.LocalMusicHomeScreen
import com.lm.player.feature.library.LocalLibraryScreen
import com.lm.player.feature.player.FullscreenPlayerSheet
import com.lm.player.feature.search.LibrarySearchDialog
import com.lm.player.feature.settings.AppUpdateDialog
import com.lm.player.feature.settings.SettingsScreen
import com.lm.player.ui.AdaptiveAppScaffold
import com.lm.player.ui.SplashScreenView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private var exoPlayer: ExoPlayer? = null
    private lateinit var database: ZdsDatabase
    private lateinit var playbackRouter: PlaybackRouter
    private lateinit var downloadEngine: DownloadEngine

    // 方向盘按键与 MediaSession 回调动作句柄
    private var playNextAction: (() -> Unit)? = null
    private var playPreviousAction: (() -> Unit)? = null
    private var togglePlayAction: (() -> Unit)? = null
    private var mediaCommandReceiver: BroadcastReceiver? = null

    // 运行时权限申请器 (兼容 Android 6.0 ~ Android 14+)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    private var onChooseDownloadFolderResult: ((android.net.Uri) -> Unit)? = null
    private val chooseDownloadDirectoryLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            onChooseDownloadFolderResult?.invoke(it)
        }
    }

    private var onImportFolderResult: ((android.net.Uri) -> Unit)? = null
    private val importFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            onImportFolderResult?.invoke(it)
        }
    }

    @kotlin.OptIn(ExperimentalMaterial3WindowSizeClassApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 系统底层接管：将实体按键音量控制通道绑定为媒体音量 (解决应用内音量键无效问题)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        // 1. 初始化核心数据库、路由与下载引擎
        database = ZdsDatabase.getInstance(this)
        playbackRouter = PlaybackRouter(database.downloadDao(), this)
        downloadEngine = DownloadEngine(this, database.downloadDao(), database.songDao(), lifecycleScope)

        // 2. 获取全局唯一共享 ExoPlayer 实例并启动前台播放服务（默认关闭在线边听边存）
        val initSettingsPrefs = getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE)
        Media3Factory.setCacheEnabled(initSettingsPrefs.getBoolean("stream_cache_enabled_v2", false))
        PlaybackQueueManager.initFromPrefs(this)
        exoPlayer = Media3Factory.getSharedExoPlayer(this)
        val initialSpeed = initSettingsPrefs.getFloat("playback_speed", 1.0f).coerceIn(0.5f, 2.0f)
        if (initialSpeed != 1.0f) {
            exoPlayer?.playbackParameters = androidx.media3.common.PlaybackParameters(initialSpeed)
        }
        startPlaybackService()

        // 3. 注册方向盘按键与车载控制广播监听器
        registerMediaCommandReceiver()

        // 4. 申请 Android 6.0 / 13+ 运行时权限
        requestAppPermissions()

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val uiPrefs = remember { getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE) }
            val savedScaleName = remember { uiPrefs.getString("ui_scale_mode", UiScaleMode.AUTO.name) ?: UiScaleMode.AUTO.name }
            var currentScaleMode by remember {
                mutableStateOf(
                    try { UiScaleMode.valueOf(savedScaleName) } catch (_: Exception) { UiScaleMode.AUTO }
                )
            }
            val appDimensions = rememberAppDimensions(currentScaleMode)
            val adaptiveDensity = rememberAdaptiveDensity(currentScaleMode)

            // 启动过渡状态 (默认 false 确保 0ms 瞬间秒开呈现主屏)
            var isSplashVisible by remember { mutableStateOf(false) }

            // 外观主题、毛玻璃与动效状态（从 SharedPreferences 持久化恢复）
            val savedThemeName = remember {
                uiPrefs.getString("app_theme_mode", AppThemeMode.FOLLOW_SYSTEM.name) ?: AppThemeMode.FOLLOW_SYSTEM.name
            }
            var currentThemeMode by remember {
                mutableStateOf(
                    try { AppThemeMode.valueOf(savedThemeName) } catch (_: Exception) { AppThemeMode.FOLLOW_SYSTEM }
                )
            }
            var blurAlpha by remember {
                mutableStateOf(uiPrefs.getFloat("ui_blur_alpha", 0.85f).coerceIn(0.2f, 1.0f))
            }
            var enableBottomBarAnimation by remember {
                mutableStateOf(uiPrefs.getBoolean("enable_bottom_bar_anim", true))
            }

            // 启动自动播放与在线容灾配置
            val autoPlayPrefs = remember { getSharedPreferences("zds_auto_play_prefs", Context.MODE_PRIVATE) }
            var autoPlayOnStartup by remember { mutableStateOf(autoPlayPrefs.getBoolean("auto_play_on_startup", true)) }
            var autoFallbackToLocal by remember { mutableStateOf(autoPlayPrefs.getBoolean("auto_fallback_to_local", true)) }
            var autoLaunchOnBoot by remember { mutableStateOf(com.lm.player.core.media.BootCompletedReceiver.isAutoLaunchOnBootEnabled(this@MainActivity)) }
            var hasAutoPlayedOnStartup by remember { mutableStateOf(false) }
            var hasAutoCheckedServerOnStartup by remember { mutableStateOf(false) }
            var showBgIslandOverlayPrompt by remember {
                DynamicIslandManager.ensureInitialized(this@MainActivity)
                mutableStateOf(
                    DynamicIslandManager.shouldUseOverlayIsland() &&
                        !DynamicIslandManager.hasOverlayPermission(this@MainActivity) &&
                        !uiPrefs.getBoolean("bg_island_overlay_prompted_v167", false)
                )
            }

            // 启动 10s 延迟自动检查软件更新状态
            var startupUpdateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
            var showStartupUpdateDialog by remember { mutableStateOf(false) }
            var isDownloadingStartupApk by remember { mutableStateOf(false) }
            var downloadStartupProgress by remember { mutableStateOf(0f) }
            var downloadedStartupApkFile by remember { mutableStateOf<java.io.File?>(null) }

            // 启动 10 秒后自动后台检查版本更新 (支持稍后、永不与立即更新)
            LaunchedEffect(Unit) {
                delay(10000L)
                launch(Dispatchers.IO) {
                    try {
                        val res = AppUpdateManager.checkForUpdates(this@MainActivity)
                        if (res.isSuccess) {
                            val info = res.getOrNull()
                            if (info != null && info.hasUpdate) {
                                val isIgnored = AppUpdateManager.isUpdateIgnored(this@MainActivity, info.latestVersion)
                                if (!isIgnored) {
                                    withContext(Dispatchers.Main) {
                                        startupUpdateInfo = info
                                        showStartupUpdateDialog = true
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Startup 10s auto update check failed", e)
                    }
                }
            }

            // UI 状态机与页面历史回退栈 (支持安卓系统返回键逐层回退直至首页双击退出)
            var currentScreen by remember { mutableStateOf(Screen.HOME) }
            val screenBackStack = remember { mutableStateListOf<Screen>() }
            var favoritesRefreshTrigger by remember { mutableStateOf(0) }
            val refreshServerFavorites: () -> Unit = { favoritesRefreshTrigger++ }
            // 服务器下载任务完成信号：轮询检测到任务转为 completed 时自增，
            // 驱动曲库同步（Room 行升级）与收藏列表刷新，让「已下载到云端」状态立即反映到各列表
            var serverDownloadSyncTrigger by remember { mutableStateOf(0) }

            // 通知栏/灵动岛收藏成功后由 PlaybackService 广播驱动收藏列表刷新，
            // 保证卡片与歌单两个入口立即看到最新收藏与下载状态
            DisposableEffect(Unit) {
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                        // 通知栏收藏成功（含服务器下载提交）后，立即驱动曲库同步，
                        // 把服务器上的已下载文件路径拉取到本地，否则卡片/歌单永远显示在线状态
                        serverDownloadSyncTrigger++
                    }
                }
                val filter = android.content.IntentFilter("com.lm.player.action.FAVORITES_CHANGED")
                androidx.core.content.ContextCompat.registerReceiver(
                    this@MainActivity, receiver, filter,
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
                )
                onDispose { runCatching { this@MainActivity.unregisterReceiver(receiver) } }
            }
            val navigateToScreen: (Screen) -> Unit = { target ->
                if (target == Screen.LIBRARY &&
                    uiPrefs.getBoolean("library_refresh_on_switch", true)
                ) {
                    // 「切换到资料库时自动刷新」开启时才顺带触发收藏拉取；
                    // 关闭后收藏仅由下拉刷新 / 定时刷新触发。
                    favoritesRefreshTrigger++
                }
                if (target != currentScreen) {
                    if (target == Screen.HOME) {
                        screenBackStack.clear()
                    } else {
                        screenBackStack.remove(target)
                        screenBackStack.add(currentScreen)
                    }
                    currentScreen = target
                }
            }
            val popScreenOrHome: () -> Unit = {
                currentScreen = if (screenBackStack.isNotEmpty()) {
                    screenBackStack.removeAt(screenBackStack.lastIndex)
                } else {
                    Screen.HOME
                }
            }
            var activeServerName by remember { mutableStateOf("本地 · 已下载") }
            var activeServerId by remember { mutableStateOf("") }
            var serversList by remember { mutableStateOf<List<ServerConfig>>(emptyList()) }
            var isSearchDialogOpen by remember { mutableStateOf(false) }
            
            // 首页展示自定义配置 (从 SharedPreferences 持久化恢复)
            var homeDisplayConfig by remember {
                mutableStateOf(
                    HomeScreenDisplayConfig(
                        showRecentlyPlayed = uiPrefs.getBoolean("home_show_recently_played", true),
                        showRecentlyAdded = uiPrefs.getBoolean("home_show_recently_added", true),
                        showAlbums = uiPrefs.getBoolean("home_show_albums", false),
                        showArtists = uiPrefs.getBoolean("home_show_artists", false),
                        showFavorites = uiPrefs.getBoolean("home_show_favorites", false)
                    )
                )
            }

            // 在线模式操作音源偏好 (酷我/网易云/QQ音乐/酷狗/咪咕)
            val onlinePrefs = remember { getSharedPreferences("zds_online_prefs", Context.MODE_PRIVATE) }
            val savedSourceName = remember { onlinePrefs.getString("selected_source", OnlineMusicSource.KUWO.name) ?: OnlineMusicSource.KUWO.name }
            var currentOnlineSource by remember {
                mutableStateOf(try { OnlineMusicSource.valueOf(savedSourceName) } catch (_: Exception) { OnlineMusicSource.KUWO })
            }

            // 实时下载状态与设置
            val activeDownloadTasks by downloadEngine.activeTasksFlow.collectAsState(initial = emptyList())
            val downloadSettings by downloadEngine.downloadSettings.collectAsState()

            // 服务器下载任务列表（轮询 /api/download/list）
            var serverDownloadTasks by remember { mutableStateOf<List<DownloadTask>>(emptyList()) }
            // 资料库下拉刷新指示器状态
            var isRefreshingLibrary by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                var prevStatuses = mapOf<String, DownloadStatus>()
                // 首次轮询只记录基线，避免冷启动时把所有历史已完成任务当成"新完成"触发全量同步
                var firstPoll = true
                while (true) {
                    val active = database.serverDao().getAllServers()
                        .firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                    if (active != null) {
                        try {
                            val proto = LemonMusicProtocol(
                                NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                active.serverUrl, active.username, active.tokenOrApiKey
                            )
                            proto.getServerDownloadTasks().onSuccess { tasks ->
                                // 诊断日志：每轮都打印任务状态分布，方便定位轮询是否执行
                                val statusCounts = tasks.groupingBy { it.status }.eachCount()
                                Log.d("MainActivity", "Download poll: ${tasks.size} tasks, statuses=$statusCounts, firstPoll=$firstPoll, prevStatusesSize=${prevStatuses.size}")
                                serverDownloadTasks = tasks
                                // 新出现的已完成任务也要算：收藏提交下载后服务器秒级完成，
                                // 15 秒轮询首次看到该任务时它已是 completed，prevStatuses 中无记录
                                val newlyCompleted = if (firstPoll) 0 else tasks.count { t ->
                                    t.status == DownloadStatus.DOWNLOADED &&
                                        prevStatuses[t.song.id] != DownloadStatus.DOWNLOADED
                                }
                                if (newlyCompleted > 0) {
                                    Log.i("MainActivity", "Detected $newlyCompleted newly completed server download task(s), incremental upgrade")
                                    // 增量升级：只更新新完成的歌曲，不走全量同步（1.8s → ~0.1s）
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val completed = tasks.filter { t ->
                                            t.status == DownloadStatus.DOWNLOADED &&
                                                prevStatuses[t.song.id] != DownloadStatus.DOWNLOADED
                                        }
                                        val dao = database.songDao()
                                        for (task in completed) {
                                            // 任务 song.id 是 svrdl_xxx 前缀，需要匹配 Room 中标题+歌手的行
                                            val cleanId = task.song.id.removePrefix("svrdl_")
                                            val row = dao.getAllSongsList().firstOrNull { entity ->
                                                entity.title.equals(task.song.title, ignoreCase = true) &&
                                                    entity.artist.equals(task.song.artist, ignoreCase = true) &&
                                                    (entity.localFilePath.isNullOrBlank() ||
                                                        entity.id.startsWith("lemon_online_") ||
                                                        entity.id == cleanId)
                                            }
                                            if (row != null) {
                                                // 拿到服务器文件路径：优先从任务的 file_path 取，否则从 streamUrl 解
                                                val serverPath = task.serverFilePath
                                                    ?: task.song.streamUrl?.let { url ->
                                                        if (url.contains("path=")) {
                                                            try {
                                                                java.net.URLDecoder.decode(
                                                                    url.substringAfter("path=").substringBefore("&"), "UTF-8"
                                                                ).trim()
                                                            } catch (_: Exception) { null }
                                                        } else null
                                                    }
                                                if (!serverPath.isNullOrBlank()) {
                                                    // 保留原 ID 不变，只更新状态字段，避免歌单关联丢失
                                                    // updateDownloadStatus 会同时更新 localFilePath
                                                    dao.updateDownloadStatus(row.id, DownloadStatus.DOWNLOADED, serverPath)
                                                    dao.updateServerId(row.id, "lemon_music")
                                                    Log.i("MainActivity", "Incrementally upgraded '${task.song.title}' to downloaded status")
                                                }
                                            }
                                        }
                                        // 升级收藏/歌单（批量，仅处理刚完成的歌曲）
                                        val unifiedList = completed.mapNotNull { task ->
                                            val cleanId = task.song.id.removePrefix("svrdl_")
                                            val serverPath = task.serverFilePath
                                                ?: task.song.streamUrl?.let { url ->
                                                    if (url.contains("path=")) {
                                                        try {
                                                            java.net.URLDecoder.decode(
                                                                url.substringAfter("path=").substringBefore("&"), "UTF-8"
                                                            ).trim()
                                                        } catch (_: Exception) { null }
                                                    } else null
                                                }
                                            if (serverPath.isNullOrBlank()) null
                                            else UnifiedSong(
                                                id = "lemon_${java.security.MessageDigest.getInstance("MD5").digest(serverPath.toByteArray()).joinToString("") { "%02x".format(it) }}",
                                                title = task.song.title,
                                                artist = task.song.artist,
                                                album = task.song.album,
                                                durationMs = task.song.durationMs,
                                                streamUrl = proto.getStreamUrlForPath(serverPath),
                                                serverId = "lemon_music",
                                                localFilePath = serverPath,
                                                downloadStatus = DownloadStatus.DOWNLOADED
                                            )
                                        }
                                        if (unifiedList.isNotEmpty()) {
                                            proto.upgradeDownloadedTracksLocalPaths(unifiedList)
                                            Log.i("MainActivity", "Incrementally upgraded ${unifiedList.size} favorite/playlist entries")
                                        }
                                        withContext(Dispatchers.Main) {
                                            // 刷新收藏列表（resolveSongList 会匹配到刚升级的 Room 行）
                                            favoritesRefreshTrigger++
                                        }
                                    }
                                }
                                prevStatuses = tasks.associate { it.song.id to it.status }
                                firstPoll = false
                            }.onFailure { e ->
                                Log.w("MainActivity", "Download poll failed", e)
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Download poll exception", e)
                        }
                    } else {
                        Log.d("MainActivity", "Download poll: no active LEMON_MUSIC server")
                        serverDownloadTasks = emptyList()
                        prevStatuses = emptyMap()
                    }
                    // 下载页打开或有进行中任务时 3 秒一轮；空闲时 15 秒一轮降低开销
                    val hasActive = serverDownloadTasks.any {
                        it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PAUSED
                    }
                    kotlinx.coroutines.delay(if (currentScreen == Screen.DOWNLOADS || hasActive) 3000L else 15000L)
                }
            }

            // 歌曲列表与专项「最近添加」「最近播放」「播放列表」数据集
            var songList by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
            var playlistsList by remember { mutableStateOf<List<UnifiedPlaylist>>(emptyList()) }
            var recentlyAddedSongs by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
            // 在线模式由服务器 /api/library/albums 下发的专辑聚合，本地模式为空（回落本地聚合）
            var serverAlbums by remember { mutableStateOf<List<UnifiedAlbum>>(emptyList()) }
            // 「我喜欢的音乐」的口径来源：柠檬服务器收藏 (/api/library/user-data 的 favorites)。
            // 本地 Room 的 isFavorite 只是这份服务器收藏在本机的镜像，用它来枚举收藏会漏掉
            // 「在别的设备/网页端收藏、本机没同步过」的曲目，所以收藏列表一律以服务器为准。
            var serverFavoriteSongs by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
            // 最近播放足迹来自 PlaybackQueueManager 的持久化记录（真实播放行为驱动），
            // 不再用「曲库前 N 首」冒充 —— 那正是资料库「最近播放」卡片歌曲不对的根因
            val recentlyPlayedSongs by PlaybackQueueManager.recentPlayedSongsFlow.collectAsState()

            val allDownloads by database.downloadDao().getAllDownloadsFlow().collectAsState(initial = emptyList())
            val completedDownloadedSongs by produceState(
                initialValue = emptyList<UnifiedSong>(),
                key1 = songList,
                key2 = allDownloads
            ) {
                value = withContext(Dispatchers.IO) {
                    val downloadedFromSongList = songList.filter {
                        (it.downloadStatus == DownloadStatus.DOWNLOADED ||
                         it.serverId in listOf("local_storage", "local_folder", "local_saf") ||
                         !it.localFilePath.isNullOrBlank()) &&
                        (it.localFilePath?.let { p -> p.startsWith("content://") || File(p).exists() } ?: (it.downloadStatus == DownloadStatus.DOWNLOADED))
                    }

                    val songIdsInList = downloadedFromSongList.map { it.id }.toSet()
                    val localPathsInList = downloadedFromSongList.mapNotNull { it.localFilePath }.toSet()

                    val complementaryFromDownloads = allDownloads.filter { record ->
                        record.status == DownloadStatus.DOWNLOADED &&
                        !songIdsInList.contains(record.songId) &&
                        (record.localFilePath == null || !localPathsInList.contains(record.localFilePath)) &&
                        (record.localFilePath != null && File(record.localFilePath).exists())
                    }.map { record ->
                        val file = record.localFilePath?.let { File(it) }
                        val ext = file?.extension?.ifBlank { "mp3" } ?: "mp3"
                        UnifiedSong(
                            id = record.songId,
                            title = record.title,
                            artist = record.artist,
                            artistId = "artist_${record.artist.hashCode()}",
                            album = "已下载歌曲",
                            albumId = "album_downloaded",
                            durationMs = 0L,
                            coverUrl = record.coverUrl,
                            streamUrl = record.localFilePath ?: record.remoteUrl,
                            serverId = "local_storage",
                            localFilePath = record.localFilePath,
                            downloadStatus = DownloadStatus.DOWNLOADED,
                            bitRate = 320,
                            format = ext,
                            isFavorite = false
                        )
                    }

                    // 下载管理「已完成」按下载完成时间倒序：最新下载的排在最前。
                    // 下载记录（downloads 表）是完成时间的唯一来源；没有记录时回落到歌曲自身的加入时间。
                    val completedTsBySongId = allDownloads.associate { it.songId to it.completedTimestamp }
                    (downloadedFromSongList + complementaryFromDownloads).sortedWith(
                        compareByDescending<UnifiedSong> { song ->
                            completedTsBySongId[song.id]?.takeIf { it > 0L } ?: song.addedTimestamp
                        }
                    )
                }
            }

            // 全局播放状态委托至 PlaybackQueueManager
            val currentSong by PlaybackQueueManager.currentSongFlow.collectAsState()
            val isPlaying by PlaybackQueueManager.isPlayingFlow.collectAsState()
            val isShuffle by PlaybackQueueManager.isShuffleFlow.collectAsState()
            val isRepeat by PlaybackQueueManager.isRepeatFlow.collectAsState()
            val currentQueue by PlaybackQueueManager.playlistFlow.collectAsState()

            var currentLyrics by remember { mutableStateOf(LyricResult()) }
            var isFullPlayerVisible by remember { mutableStateOf(false) }
            var isLyricsMode by remember { mutableStateOf(false) }

            // 正在播放歌曲定位悬浮按钮状态（记录歌曲所属列表页面 + 仅在播放中且列表滑动时显示，播放界面不显示）
            var playingListScreen by remember { mutableStateOf(Screen.HOME) }
            var locateSongTrigger by remember { mutableStateOf(0) }
            var isSongListScrolling by remember { mutableStateOf(false) }
            var isLocateButtonVisible by remember { mutableStateOf(false) }
            // 悬浮按钮动作轮换：false=定位当前播放歌曲，true=回到列表顶部（不离开当前页面）
            var locateNextIsTop by remember { mutableStateOf(false) }
            var scrollToTopTrigger by remember { mutableStateOf(0) }

            // 切歌后第一次点击始终回到「定位当前歌曲」
            LaunchedEffect(currentSong?.id) {
                locateNextIsTop = false
            }

            LaunchedEffect(isSongListScrolling, isPlaying, isFullPlayerVisible, currentScreen, isSearchDialogOpen) {
                if (!isPlaying || isFullPlayerVisible || isSearchDialogOpen || currentScreen !in listOf(Screen.HOME, Screen.LIBRARY)) {
                    isLocateButtonVisible = false
                } else if (isSongListScrolling) {
                    isLocateButtonVisible = true
                } else if (isLocateButtonVisible) {
                    delay(1800L)
                    if (!isSongListScrolling) {
                        isLocateButtonVisible = false
                    }
                }
            }

            // 安卓系统返回键逐层回退与首页双击退出软件逻辑
            var lastBackPressTime by remember { mutableStateOf(0L) }
            var isChildSubViewActive by remember { mutableStateOf(false) }
            LaunchedEffect(currentScreen) {
                isChildSubViewActive = false
            }

            BackHandler(
                enabled = isFullPlayerVisible || isSearchDialogOpen || (!isChildSubViewActive && currentScreen != Screen.HOME)
            ) {
                when {
                    isFullPlayerVisible -> isFullPlayerVisible = false
                    isSearchDialogOpen -> isSearchDialogOpen = false
                    currentScreen != Screen.HOME -> popScreenOrHome()
                }
            }

            BackHandler(
                enabled = !isFullPlayerVisible && !isSearchDialogOpen && !isChildSubViewActive && currentScreen == Screen.HOME
            ) {
                val stopPlaybackOnExit = try {
                    getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE).getBoolean("stop_playback_on_exit", true)
                } catch (_: Exception) { true }

                if (stopPlaybackOnExit) {
                    val now = System.currentTimeMillis()
                    if (now - lastBackPressTime < 2000) {
                        exitAppCompletely()
                    } else {
                        lastBackPressTime = now
                        Toast.makeText(this@MainActivity, "再按一次彻底退出程序并停止播放", Toast.LENGTH_SHORT).show()
                    }
                } else if (isPlaying) {
                    // 正在播放音乐时按返回键：平滑退至后台桌面/导航页面，播放交由前台播放服务按常规策略维持
                    DynamicIslandManager.onAppBackgroundStateChanged(this@MainActivity, inBackground = true)
                    moveTaskToBack(true)
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastBackPressTime < 2000) {
                        exitAppCompletely()
                    } else {
                        lastBackPressTime = now
                        Toast.makeText(this@MainActivity, "再按一次彻底退出程序（播放中按返回键将转入后台保活播放）", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // 极速同步服务器歌单 (轻量级秒级同步，完全解耦于大体量歌曲全量同步)
            val syncServerPlaylists: (ServerConfig) -> Unit = { config ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                        val protocol = LemonMusicProtocol(client, config.serverUrl, config.username, config.tokenOrApiKey)
                        val authRes = protocol.authenticate(config)
                        val effectiveToken = authRes.getOrNull() ?: config.tokenOrApiKey
                        val activeProto = if (effectiveToken.isNotBlank() && effectiveToken != config.tokenOrApiKey) {
                            LemonMusicProtocol(client, config.serverUrl, config.username, effectiveToken)
                        } else protocol

                        val playlistRes = activeProto.getPlaylists(targetServerId = config.id)
                        if (playlistRes.isSuccess) {
                            val plList = playlistRes.getOrNull() ?: emptyList()
                            val incomingIds = plList.map { it.id }.toSet()
                            val existingPlaylists = database.playlistDao().getAllPlaylists()
                                .filter { it.serverId == config.id || it.serverId == "lemon_music" || it.serverId == config.serverUrl }
                            for (oldPl in existingPlaylists) {
                                if (!incomingIds.contains(oldPl.id) && oldPl.isOnline) {
                                    database.playlistDao().deletePlaylist(oldPl.id)
                                }
                            }
                            val plEntities = plList.map { pl ->
                                val joinedCovers = if (pl.previewCovers.isNotEmpty()) {
                                    pl.previewCovers.joinToString("|")
                                } else {
                                    pl.coverUrl
                                }
                                com.lm.player.core.database.entity.PlaylistEntity(
                                    id = pl.id,
                                    name = pl.name,
                                    coverUrl = joinedCovers,
                                    serverId = config.id,
                                    isOnline = true,
                                    songCount = pl.songCount
                                )
                            }
                            database.playlistDao().insertPlaylists(plEntities)
                            Log.i("MainActivity", "Successfully synced ${plEntities.size} server playlists for server ${config.id}")
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "syncServerPlaylists failed", e)
                    }
                }
            }

            // 同步远程柠檬音乐歌曲至本地 Room 数据库 (并行异步加速，全量同步，支持静默后台刷新与显式 Toast 提示)
            fun syncServerSongsWithToast(
                config: ServerConfig,
                showToast: Boolean,
                onComplete: (() -> Unit)? = null
            ) {
                lifecycleScope.launch(Dispatchers.IO) {
                    // 同步属于后台对账，任何异常（数据库/网络/解析）都不能外泄成闪退
                    val tSyncStart = System.currentTimeMillis()
                    try {
                    val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                    val protocol = LemonMusicProtocol(client, config.serverUrl, config.username, config.tokenOrApiKey)
                    val authRes = protocol.authenticate(config)
                    if (authRes.isSuccess) {
                        val token = authRes.getOrNull() ?: ""
                        val activeProto = if (token.isNotBlank() && token != config.tokenOrApiKey) {
                            val updatedConfig = config.copy(tokenOrApiKey = token)
                            database.serverDao().insertServer(
                                ServerEntity(
                                    id = updatedConfig.id,
                                    name = updatedConfig.name,
                                    type = updatedConfig.type,
                                    serverUrl = updatedConfig.serverUrl,
                                    username = updatedConfig.username,
                                    tokenOrApiKey = updatedConfig.tokenOrApiKey,
                                    saltOrSecret = updatedConfig.saltOrSecret,
                                    syncMode = updatedConfig.syncMode,
                                    isCurrentActive = updatedConfig.isCurrentActive
                                )
                            )
                            LemonMusicProtocol(client, updatedConfig.serverUrl, updatedConfig.username, updatedConfig.tokenOrApiKey)
                        } else protocol

                        // 优先瞬时同步服务器歌单 (不等大体积曲库请求，秒级完成并更新界面)
                        try {
                            val playlistRes = activeProto.getPlaylists(targetServerId = config.id)
                            if (playlistRes.isSuccess) {
                                val plList = playlistRes.getOrNull() ?: emptyList()
                                val incomingIds = plList.map { it.id }.toSet()
                                val existingPlaylists = database.playlistDao().getAllPlaylists()
                                    .filter { it.serverId == config.id || it.serverId == "lemon_music" || it.serverId == config.serverUrl }
                                for (oldPl in existingPlaylists) {
                                    if (!incomingIds.contains(oldPl.id) && oldPl.isOnline) {
                                        database.playlistDao().deletePlaylist(oldPl.id)
                                    }
                                }
                                val plEntities = plList.map { pl ->
                                    val joinedCovers = if (pl.previewCovers.isNotEmpty()) {
                                        pl.previewCovers.joinToString("|")
                                    } else {
                                        pl.coverUrl
                                    }
                                    com.lm.player.core.database.entity.PlaylistEntity(
                                        id = pl.id,
                                        name = pl.name,
                                        coverUrl = joinedCovers,
                                        serverId = config.id,
                                        isOnline = true,
                                        songCount = pl.songCount
                                    )
                                }
                                database.playlistDao().insertPlaylists(plEntities)
                            }
                        } catch (e: Exception) {
                            Log.w("MainActivity", "Pre-sync playlists failed", e)
                        }

                        // 极速轻量化同步全量歌曲与最近添加、最近播放
                        val tTracks = System.currentTimeMillis()
                        val songsRes = activeProto.getSongList(offset = 0, limit = 0)
                        if (songsRes.isSuccess) {
                            val list = songsRes.getOrNull() ?: emptyList()
                            Log.i("MainActivity", "Fetch tracks (${list.size}) took ${System.currentTimeMillis() - tTracks}ms")
                            if (list.isNotEmpty()) {
                                val entities = list.map {
                                    SongEntity(
                                        id = it.id,
                                        title = it.title,
                                        artist = it.artist,
                                        artistId = it.artistId,
                                        album = it.album,
                                        albumId = it.albumId,
                                        durationMs = it.durationMs,
                                        coverUrl = it.coverUrl,
                                        streamUrl = it.streamUrl,
                                        serverId = config.id,
                                        localFilePath = it.localFilePath,
                                        downloadStatus = it.downloadStatus,
                                        bitRate = it.bitRate,
                                        format = it.format,
                                        isFavorite = it.isFavorite,
                                        relativeFolderPath = it.relativeFolderPath,
                                        // 服务器文件时间 (getSongList 里由 mtime 推得)：在线模式「按加入时间」
                                        // 排序与「最近添加」都以它为准。此前这里漏传，全部退化成 SongEntity 的
                                        // 默认值 = 同步那一刻，所有歌时间并列，排序自然看不出"最近添加"。
                                        addedTimestamp = it.addedTimestamp
                                    )
                                }
                                // 智能保护性同步：保留已有本地已下载路径、收藏与层级，杜绝覆盖重置，并进行差量清理
                                val tUpsert = System.currentTimeMillis()
                                val mergedCount = SongMatchingResolver.syncAndUpsertServerSongs(
                                    database = database,
                                    incomingServerSongs = entities,
                                    downloadDir = downloadEngine.getDownloadDir(),
                                    targetServerId = config.id
                                )
                                Log.i("MainActivity", "Upsert ${entities.size} tracks took ${System.currentTimeMillis() - tUpsert}ms, merged=$mergedCount")
                            }
                        }

                        // 同步用户收藏与用户数据 (/api/library/user-data)
                        try {
                            val userDataRes = activeProto.getLibraryUserData()
                            if (userDataRes.isSuccess) {
                                val userDataObj = userDataRes.getOrNull()
                                val favArr = userDataObj?.optJSONArray("favorites")
                                // 先清空本地所有非本地扫描曲目的收藏标记，再以服务端收藏为准回写。
                                // 否则服务端已取消收藏的曲目（如「心墙」）会因本地 isFavorite=true 残留，
                                // 通过 favExtras 混入收藏列表，造成「歌单16首、列表17首」的幽灵条目。
                                database.songDao().clearServerFavorites()
                                if (favArr != null && favArr.length() > 0) {
                                    for (k in 0 until favArr.length()) {
                                        val favItem = favArr.opt(k)
                                        val favStr = when (favItem) {
                                            is String -> favItem
                                            is org.json.JSONObject -> favItem.optString("filePath").ifBlank { favItem.optString("id") }
                                            else -> ""
                                        }
                                        if (favStr.isNotBlank()) {
                                            val cleanPath = favStr.removePrefix("local:").trim()
                                            val directId = if (cleanPath.startsWith("lemon_")) cleanPath else "lemon_${LemonMusicProtocol.md5(cleanPath)}"
                                            database.songDao().updateFavorite(directId, true)
                                            database.songDao().updateFavoriteByIdOrPath(cleanPath, true)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("MainActivity", "Syncing user favorites failed", e)
                        }

                        // 收藏升级 + 歌单快照升级：批量一次完成（避免原先 N+1 网络风暴：
                        // 原先每首已下载歌曲都会 GET user-data + GET playlists，100 首 = 200+ 次串行请求）
                        val tUpgrade = System.currentTimeMillis()
                        try {
                            val downloadedWithLocal = database.songDao().getDownloadedSongsWithLocalPath()
                            val unifiedList = downloadedWithLocal.map { entity ->
                                UnifiedSong(
                                    id = entity.id, title = entity.title, artist = entity.artist,
                                    artistId = entity.artistId, album = entity.album, albumId = entity.albumId,
                                    durationMs = entity.durationMs, coverUrl = entity.coverUrl,
                                    streamUrl = entity.streamUrl, serverId = entity.serverId,
                                    localFilePath = entity.localFilePath, downloadStatus = entity.downloadStatus,
                                    bitRate = entity.bitRate, format = entity.format,
                                    isFavorite = entity.isFavorite,
                                    relativeFolderPath = entity.relativeFolderPath
                                )
                            }
                            val (favUp, plUp) = activeProto.upgradeDownloadedTracksLocalPaths(unifiedList).getOrNull() ?: (0 to 0)
                            Log.i("MainActivity", "Batch upgrade done in ${System.currentTimeMillis() - tUpgrade}ms (fav=$favUp, playlist=$plUp, downloaded=${unifiedList.size})")
                            // 升级后重新拉取收藏，确保「我的收藏」卡片显示最新的已下载状态
                            // 注意：这里拉的是服务端原始数据，resolveSongList 匹配需要曲库已同步完成
                            val refreshedFavs = activeProto.getPlaylistSongs("lemon_favorites").getOrNull()
                            if (refreshedFavs != null) {
                                withContext(Dispatchers.Main) {
                                    serverFavoriteSongs = refreshedFavs
                                    // 升级完成后立即触发收藏刷新，不等后面的慢请求（专辑/最近添加等）
                                    favoritesRefreshTrigger++
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("MainActivity", "Upgrade favorite/playlist local path failed", e)
                        }

                        // 后面的请求（最近添加/最近播放/专辑）与 UI 状态无关，可以并行加速
                        // 但保持串行以避免并发修改同一 state 的竞态问题
                        val t2 = System.currentTimeMillis()

                        // 在线模式的「最近添加」「最近播放」「最近添加专辑」一律以服务器数据为准，
                        // 本地 Room 只是服务器曲库的镜像，按它推断"最近添加"会把同步那一刻当成加入时间。
                        val addedList = activeProto.getRecentlyAdded(limit = 50).getOrNull() ?: emptyList()
                        if (addedList.isNotEmpty()) recentlyAddedSongs = addedList

                        val playedList = activeProto.getRecentlyPlayed(limit = 50).getOrNull() ?: emptyList()
                        if (playedList.isNotEmpty()) PlaybackQueueManager.rememberRecentPlayed(this@MainActivity, playedList)

                        // 专辑聚合同样取服务器口径（/api/library/albums），失败时回落到本地聚合
                        val albumList = try {
                            activeProto.getAlbums(offset = 0, limit = 500).getOrNull() ?: emptyList()
                        } catch (e: Exception) {
                            Log.w("MainActivity", "Fetching server albums failed", e)
                            emptyList()
                        }
                        if (albumList.isNotEmpty()) withContext(Dispatchers.Main) { serverAlbums = albumList }

                        Log.i("MainActivity", "Post-upgrade slow ops took ${System.currentTimeMillis() - t2}ms")

                        // 再次对齐服务器播放列表至数据库（含差量清理）
                        val playlistRes = activeProto.getPlaylists(targetServerId = config.id)
                        if (playlistRes.isSuccess) {
                            val plList = playlistRes.getOrNull() ?: emptyList()
                            val incomingIds = plList.map { it.id }.toSet()
                            val existingPlaylists = database.playlistDao().getAllPlaylists()
                                .filter { it.serverId == config.id || it.serverId == "lemon_music" || it.serverId == config.serverUrl }
                            for (oldPl in existingPlaylists) {
                                if (!incomingIds.contains(oldPl.id) && oldPl.isOnline && !oldPl.id.startsWith("pl_")) {
                                    database.playlistDao().deletePlaylist(oldPl.id)
                                }
                            }
                            val plEntities = plList.map { pl ->
                                val joinedCovers = if (pl.previewCovers.isNotEmpty()) {
                                    pl.previewCovers.joinToString("|")
                                } else {
                                    pl.coverUrl
                                }
                                com.lm.player.core.database.entity.PlaylistEntity(
                                    id = pl.id,
                                    name = pl.name,
                                    coverUrl = joinedCovers,
                                    serverId = config.id,
                                    isOnline = true,
                                    songCount = pl.songCount
                                )
                            }
                            database.playlistDao().insertPlaylists(plEntities)
                        }

                        Log.i("MainActivity", "Full sync finished in ${System.currentTimeMillis() - tSyncStart}ms")
                        if (showToast) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "已成功从 NAS 同步全量媒体数据与歌单", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else if (showToast) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "同步失败: ${authRes.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "syncServerSongsWithToast failed", e)
                        if (showToast) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "同步出错: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    } finally {
                        val cb = onComplete
                        if (cb != null) {
                            withContext(Dispatchers.Main) { cb() }
                        }
                    }
                }
            }

            val syncServerSongs: (ServerConfig) -> Unit = { config ->
                syncServerSongsWithToast(config, true)
            }

            // 服务器下载任务完成后的自动对账：Room 行升级（在线条目→NAS 已下载条目）+ 收藏刷新，
            // 让「我的收藏」卡片、歌单列表中的下载状态在下载完成数秒内自动更新，无需手动下拉
            // 关键：favoritesRefreshTrigger 必须在 syncServerSongsWithToast 完成后才触发，
            // 否则收藏刷新时曲库尚未同步，resolveSongList 匹配不到已下载记录，卡片永远显示在线状态
            LaunchedEffect(serverDownloadSyncTrigger) {
                if (serverDownloadSyncTrigger <= 0) return@LaunchedEffect
                val active = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                if (active != null) {
                    syncServerSongsWithToast(active, false) {
                        favoritesRefreshTrigger++
                    }
                }
            }

            // 资料库统一刷新入口：在线模式同步服务器歌单/曲库/收藏；本地模式扫描已下载文件状态。
            // manual=true 表示用户下拉刷新，需要驱动刷新指示器收起。
            fun triggerLibraryRefresh(manual: Boolean) {
                val isLocalMode = activeServerName.contains("本地") || activeServerName.contains("已下载")
                val active = if (isLocalMode) null else serversList.firstOrNull { it.isCurrentActive }
                if (active != null) {
                    // 与冷启动同一套同步逻辑：syncServerSongsWithToast 内部已包含
                    // 歌单同步、全量曲库同步、收藏同步，外部不再重复调用
                    syncServerSongsWithToast(active, true) {
                        favoritesRefreshTrigger++
                        isRefreshingLibrary = false
                    }
                } else {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val dlDir = downloadEngine.getDownloadDir()
                        LocalMediaScanner.verifyAndSyncAllServerSongDownloadStatus(database, dlDir)
                        withContext(Dispatchers.Main) { isRefreshingLibrary = false }
                    }
                }
                // 兜底：15 秒后强制收起指示器，避免网络异常时一直转圈
                if (manual) {
                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(15000)
                        isRefreshingLibrary = false
                    }
                }
            }

            // 资料库定时刷新：仅当「切换时自动刷新」关闭且间隔 > 0 时生效。
            // 循环每分钟醒来重新读设置，保证设置改完不用重启即可生效。
            LaunchedEffect(Unit) {
                while (true) {
                    val switchOn = uiPrefs.getBoolean("library_refresh_on_switch", true)
                    val intervalMin = uiPrefs.getInt("library_refresh_interval_min", 0)
                    kotlinx.coroutines.delay(
                        if (!switchOn && intervalMin > 0) intervalMin * 60_000L else 60_000L
                    )
                    val nowSwitchOn = uiPrefs.getBoolean("library_refresh_on_switch", true)
                    val nowInterval = uiPrefs.getInt("library_refresh_interval_min", 0)
                    val isLocalMode = activeServerName.contains("本地") || activeServerName.contains("已下载")
                    if (!nowSwitchOn && nowInterval > 0 && !isLocalMode) {
                        triggerLibraryRefresh(manual = false)
                    }
                }
            }

            // 监听本地数据库中的服务器、播放列表与歌曲
            LaunchedEffect(Unit) {
                database.playlistDao().getAllPlaylistsFlow().collect { entities ->
                    val mapped = withContext(Dispatchers.IO) {
                        entities.map { entity ->
                            val previewUrls = database.playlistDao().getPlaylistCoverUrls(entity.id)
                            val storedUrls = if (entity.coverUrl.contains("|")) {
                                entity.coverUrl.split("|").filter { it.isNotBlank() }
                            } else if (entity.coverUrl.isNotBlank()) {
                                listOf(entity.coverUrl)
                            } else {
                                emptyList()
                            }
                            val effectivePreviews = if (previewUrls.isNotEmpty()) previewUrls else storedUrls
                            val effectiveCover = entity.coverUrl.substringBefore("|").ifBlank { effectivePreviews.firstOrNull() ?: "" }
                            UnifiedPlaylist(
                                id = entity.id,
                                name = entity.name,
                                coverUrl = effectiveCover,
                                songCount = entity.songCount,
                                isOnline = entity.isOnline,
                                serverId = entity.serverId,
                                previewCovers = effectivePreviews,
                                isDiscover = entity.id.startsWith("discover_"),
                                updatedTimestamp = entity.updatedTimestamp
                            )
                        }
                    }
                    playlistsList = mapped
                }
            }

            // 最近播放足迹同步到服务器：足迹变化后延迟 3 秒合并写入，避免频繁请求。
            // 仅在有在线柠檬服务器时同步，失败静默。
            LaunchedEffect(recentlyPlayedSongs) {
                if (recentlyPlayedSongs.isEmpty()) return@LaunchedEffect
                val active = database.serverDao().getAllServers()
                    .firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                    ?: return@LaunchedEffect
                kotlinx.coroutines.delay(3000)
                try {
                    val proto = LemonMusicProtocol(
                        NetworkClientFactory.createOkHttpClient(this@MainActivity),
                        active.serverUrl, active.username, active.tokenOrApiKey
                    )
                    proto.syncRecentPlaysToServer(recentlyPlayedSongs.take(30))
                } catch (_: Exception) {}
            }

            // 启动时自动深度清理遗留残留数据、孤立记录与物理文件核对
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    try {
                        val validServers = database.serverDao().getAllServers().map { it.id }.toList()
                        val purged = LocalMediaScanner.purgeLegacyResidualData(database, validServers)
                        if (purged > 0) {
                            Log.i("MainActivity", "Startup purged $purged residual legacy records")
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Startup legacy data purge error", e)
                    }
                }
            }

            LaunchedEffect(Unit) {
                database.serverDao().getAllServersFlow().collect { entities ->
                    serversList = entities.map {
                        ServerConfig(
                            id = it.id,
                            name = it.name,
                            type = it.type,
                            serverUrl = it.serverUrl,
                            username = it.username,
                            tokenOrApiKey = it.tokenOrApiKey,
                            saltOrSecret = it.saltOrSecret,
                            syncMode = it.syncMode,
                            isCurrentActive = it.isCurrentActive
                        )
                    }
                    val active = serversList.firstOrNull { it.isCurrentActive }
                    if (active != null) {
                        activeServerId = active.id

                        // 启动时优先展示本地离线界面 (activeServerName 默认为 "本地 · 已下载")
                        // 在后台异步检测服务器在线与认证状态，连接就绪后直接无缝切换至服务器首页并触发全量同步
                        if (!hasAutoCheckedServerOnStartup) {
                            hasAutoCheckedServerOnStartup = true
                            lifecycleScope.launch(Dispatchers.IO) {
                                delay(1200L)
                                try {
                                    val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                    val protocol = LemonMusicProtocol(client, active.serverUrl, active.username, active.tokenOrApiKey)
                                    val authRes = protocol.authenticate(active)
                                    if (authRes.isSuccess) {
                                        Log.i("MainActivity", "Startup server check passed: ${active.name} online. Switching to server home...")
                                        withContext(Dispatchers.Main) {
                                            activeServerName = active.name
                                        }
                                        syncServerPlaylists(active)
                                        syncServerSongs(active)
                                    } else {
                                        Log.w("MainActivity", "Startup server check: ${active.name} is unreachable. Remaining in local mode.")
                                        withContext(Dispatchers.Main) {
                                            activeServerName = "本地 · 已下载"
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Startup auto server connection check failed", e)
                                    withContext(Dispatchers.Main) {
                                        activeServerName = "本地 · 已下载"
                                    }
                                }
                            }
                        }
                    } else {
                        activeServerName = "本地 · 已下载"
                    }
                }
            }

            LaunchedEffect(Unit) {
                // 不再直接用 Room 的 getAllSongsFlow()（SELECT * 一次返回全表，
                // 大曲库下 CursorWindow 2MB 放不下会抛 IllegalStateException 闪退），
                // 改为 Room 官方 invalidationTrackerFlow 监听 songs 表变化 + 分页读取聚合
                database.invalidationTrackerFlow("songs")
                    .map { database.songDao().getAllSongsList() }
                    .collect { songEntities ->
                    val (mappedSongs, recAdded) = withContext(Dispatchers.IO) {
                        val downloadsMap = try {
                            database.downloadDao().getAllDownloadsList().associateBy { it.songId }
                        } catch (_: Exception) {
                            emptyMap()
                        }
                        val mapped = songEntities.map { entity ->
                            val dlRecord = downloadsMap[entity.id]
                            val resolvedTimestamp = when {
                                entity.addedTimestamp > 0 -> entity.addedTimestamp
                                dlRecord != null && dlRecord.completedTimestamp > 0 -> dlRecord.completedTimestamp
                                entity.downloadStatus == DownloadStatus.DOWNLOADED -> System.currentTimeMillis()
                                else -> 0L
                            }
                            val effectiveLocalPath = entity.localFilePath?.takeIf { it.isNotBlank() }
                                ?: dlRecord?.localFilePath?.takeIf { it.isNotBlank() }
                            val localExt = effectiveLocalPath
                                ?.substringAfterLast('.', "")
                                ?.substringBefore('?')
                                ?.lowercase()
                                ?.takeIf { it in setOf("flac", "wav", "ape", "alac", "m4a", "aac", "ogg", "opus", "mp3", "wma", "dsf", "dff") }
                            val resolvedFormat = localExt?.uppercase() ?: entity.format
                            val resolvedBitRate = when (localExt) {
                                "flac", "wav", "ape", "alac", "dsf", "dff" -> if (entity.bitRate >= 900) entity.bitRate else 1000
                                "m4a", "aac", "ogg", "opus", "mp3", "wma" -> if (entity.bitRate in 64..512) entity.bitRate else 320
                                else -> entity.bitRate
                            }
                            UnifiedSong(
                                id = entity.id,
                                title = entity.title,
                                artist = entity.artist,
                                artistId = entity.artistId,
                                album = entity.album,
                                albumId = entity.albumId,
                                durationMs = entity.durationMs,
                                coverUrl = entity.coverUrl,
                                streamUrl = entity.streamUrl,
                                serverId = entity.serverId,
                                localFilePath = effectiveLocalPath ?: entity.localFilePath,
                                downloadStatus = entity.downloadStatus,
                                bitRate = resolvedBitRate,
                                format = resolvedFormat,
                                isFavorite = entity.isFavorite,
                                relativeFolderPath = entity.relativeFolderPath,
                                addedTimestamp = resolvedTimestamp
                            )
                        }
                        val rec = mapped.filter { it.downloadStatus == DownloadStatus.DOWNLOADED || it.addedTimestamp > 0 }
                            .sortedByDescending { it.addedTimestamp }
                            .take(20)
                            .ifEmpty { mapped.sortedByDescending { it.addedTimestamp }.take(20) }
                        Pair(mapped, rec)
                    }
                    songList = mappedSongs
                    PlaybackQueueManager.updateMetadata(mappedSongs)
                    // 本地库只是在线服务器曲库的镜像：在线模式的「最近添加」必须用服务器下发的数据，
                    // 若这里继续用本地 Room 重新推算，服务器结果会被每次数据库变更覆盖掉。
                    val onlineModeNow = !(activeServerName.contains("本地") || activeServerName.contains("已下载"))
                    if (!onlineModeNow) {
                        recentlyAddedSongs = recAdded
                        serverAlbums = emptyList()
                    }
                    // 注意：此处不再用「曲库前 10 首」冒充最近播放，
                    // 真实足迹来自 PlaybackQueueManager 的本地持久化记录 (见下方 currentSong 监听)
                    if (currentSong == null) {
                        val savedSong = PlaybackQueueManager.getSavedLastSong(this@MainActivity)
                        val savedQueue = PlaybackQueueManager.getSavedQueue(this@MainActivity)
                        val lastPlayedSongId = autoPlayPrefs.getString("last_played_song_id", "")?.trim().orEmpty()
                        val targetSong = savedSong
                            ?: (if (lastPlayedSongId.isNotEmpty()) mappedSongs.firstOrNull { it.id == lastPlayedSongId } else null)
                            ?: (if (lastPlayedSongId.isEmpty()) mappedSongs.firstOrNull() else null)
                        if (targetSong != null) {
                            PlaybackQueueManager.setInitialSongIfAbsent(
                                targetSong,
                                savedQueue.ifEmpty { mappedSongs }
                            )
                        }
                    }
                }
            }

            // 统一解析上次关闭前正在播放的歌曲与所属播放列表（兼容本地曲库、已下载列表、发现页/搜索在线曲目及云端歌单）
            val resolveSavedSongAndQueue: (List<UnifiedSong>) -> Pair<UnifiedSong?, List<UnifiedSong>> = { candidates ->
                val savedSong = PlaybackQueueManager.getSavedLastSong(this@MainActivity)
                val savedQueue = PlaybackQueueManager.getSavedQueue(this@MainActivity)
                val lastPlayedId = autoPlayPrefs.getString("last_played_song_id", "")?.trim().orEmpty()
                    .ifEmpty { savedSong?.id.orEmpty() }
                val lastPlayedTitle = autoPlayPrefs.getString("last_played_song_title", "")?.trim().orEmpty()
                    .ifEmpty { savedSong?.title.orEmpty() }
                val lastPlayedArtist = autoPlayPrefs.getString("last_played_song_artist", "")?.trim().orEmpty()
                    .ifEmpty { savedSong?.artist.orEmpty() }

                val matchedById = if (lastPlayedId.isNotEmpty()) {
                    candidates.firstOrNull { it.id == lastPlayedId }
                } else null

                val matchedByMeta = if (matchedById == null && lastPlayedTitle.isNotEmpty()) {
                    val allMatches = candidates.filter { s ->
                        SongMatchingResolver.isSongMatch(
                            s.title, s.artist, s.durationMs,
                            lastPlayedTitle, lastPlayedArtist, savedSong?.durationMs ?: 0L,
                            s.album, savedSong?.album ?: ""
                        )
                    }
                    allMatches.firstOrNull { s ->
                        !s.localFilePath.isNullOrBlank() &&
                        (s.localFilePath.startsWith("content://") || File(s.localFilePath).exists())
                    } ?: allMatches.firstOrNull()
                } else null

                val matchedCandidate = matchedById ?: matchedByMeta

                val resolvedTarget: UnifiedSong? = when {
                    savedSong != null && matchedCandidate != null -> {
                        val validCandidateLocal = matchedCandidate.localFilePath?.takeIf {
                            it.isNotBlank() && (it.startsWith("content://") || File(it).exists())
                        }
                        val validSavedLocal = savedSong.localFilePath?.takeIf {
                            it.isNotBlank() && (it.startsWith("content://") || File(it).exists())
                        }
                        val effectiveLocal = validCandidateLocal ?: validSavedLocal
                        val effectiveStream = when {
                            !effectiveLocal.isNullOrBlank() -> effectiveLocal
                            matchedCandidate.streamUrl.isNotBlank() && !matchedCandidate.streamUrl.startsWith("lemon_online://") -> matchedCandidate.streamUrl
                            savedSong.streamUrl.isNotBlank() -> savedSong.streamUrl
                            else -> matchedCandidate.streamUrl
                        }
                        savedSong.copy(
                            localFilePath = effectiveLocal,
                            streamUrl = effectiveStream,
                            downloadStatus = if (!effectiveLocal.isNullOrBlank()) DownloadStatus.DOWNLOADED else matchedCandidate.downloadStatus,
                            coverUrl = savedSong.coverUrl.ifBlank { matchedCandidate.coverUrl },
                            durationMs = if (savedSong.durationMs > 0L) savedSong.durationMs else matchedCandidate.durationMs,
                            rawMetaJson = savedSong.rawMetaJson ?: matchedCandidate.rawMetaJson
                        )
                    }
                    savedSong != null -> savedSong
                    matchedCandidate != null -> matchedCandidate
                    lastPlayedId.isEmpty() -> candidates.firstOrNull()
                    else -> candidates.firstOrNull()
                }

                val resolvedQueue: List<UnifiedSong> = when {
                    savedQueue.isNotEmpty() -> {
                        val enriched = if (candidates.isNotEmpty()) {
                            SongMatchingResolver.resolveSongList(
                                incomingSongs = savedQueue,
                                allCachedSongs = candidates
                            )
                        } else {
                            savedQueue
                        }
                        if (resolvedTarget != null && enriched.none { it.id == resolvedTarget.id }) {
                            listOf(resolvedTarget) + enriched
                        } else if (resolvedTarget != null) {
                            enriched.map { if (it.id == resolvedTarget.id) resolvedTarget else it }
                        } else {
                            enriched
                        }
                    }
                    candidates.isNotEmpty() -> {
                        if (resolvedTarget != null && candidates.none { it.id == resolvedTarget.id }) {
                            listOf(resolvedTarget) + candidates
                        } else {
                            candidates
                        }
                    }
                    resolvedTarget != null -> listOf(resolvedTarget)
                    else -> emptyList()
                }

                Pair(resolvedTarget, resolvedQueue)
            }

            // 核心业务函数：播放指定歌曲 (委托至 PlaybackQueueManager 调度，支持动态上下文队列、断点进度与全局本地优先调用)
            val playSongWithQueueAndPosition: (UnifiedSong, List<UnifiedSong>?, Long) -> Unit = { targetSong, contextQueue, startPositionMs ->
                if (currentScreen == Screen.HOME || currentScreen == Screen.LIBRARY) {
                    playingListScreen = currentScreen
                }
                val activeQueue = when {
                    !contextQueue.isNullOrEmpty() -> contextQueue
                    PlaybackQueueManager.playlistFlow.value.isNotEmpty() -> PlaybackQueueManager.playlistFlow.value
                    else -> songList
                }

                // 核心调度：无论歌曲来自线上模式、全网搜索还是资料库，播放前统一优先核验本地物理文件
                val validDirectPath = if (!targetSong.localFilePath.isNullOrBlank() && java.io.File(targetSong.localFilePath).let { it.exists() && it.length() > 0 }) {
                    targetSong.localFilePath
                } else null

                // 若未直接携带本地路径，快速从当前已收录歌曲库与已下载列表匹配（严格校验 Live/伴奏/黑胶等版本与时长）
                val resolvedLocalPath = validDirectPath ?: run {
                    val allLocalCandidates = songList + completedDownloadedSongs
                    val matchedLocal = allLocalCandidates.firstOrNull {
                        val hasFile = !it.localFilePath.isNullOrBlank() && java.io.File(it.localFilePath).let { f -> f.exists() && f.length() > 0 }
                        hasFile && (it.id == targetSong.id || SongMatchingResolver.isSongMatch(
                            it.title, it.artist, it.durationMs,
                            targetSong.title, targetSong.artist, targetSong.durationMs,
                            it.album, targetSong.album
                        ))
                    }
                    matchedLocal?.localFilePath
                }

                if (resolvedLocalPath != null) {
                    val localSong = targetSong.copy(
                        localFilePath = resolvedLocalPath,
                        streamUrl = resolvedLocalPath,
                        downloadStatus = DownloadStatus.DOWNLOADED
                    )
                    // 播放足迹由 PlaybackQueueManager.playSong 统一记录，此处无需再记一次
                    val resolvedQueue = activeQueue.map { if (it.id == localSong.id) localSong else it }
                    PlaybackQueueManager.playSong(
                        targetSong = localSong,
                        context = this@MainActivity,
                        newPlaylist = resolvedQueue,
                        startPositionMs = startPositionMs
                    )
                } else if (
                    (targetSong.serverId == "lemon_online" || targetSong.id.startsWith("lemon_online_")) &&
                    !targetSong.streamUrl.contains("/api/play/local")
                ) {
                    // 立即同步当前歌曲状态与持久化，确保异步解析流地址期间界面与状态一致
                    PlaybackQueueManager.updateCurrentSong(targetSong)
                    if (!contextQueue.isNullOrEmpty()) {
                        PlaybackQueueManager.setQueue(contextQueue)
                    }
                    lifecycleScope.launch(Dispatchers.IO) {
                        val active = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                            ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }
                            ?: run {
                                val entity = try {
                                    database.serverDao().getActiveServer()
                                        ?: database.serverDao().getAllServers().firstOrNull { it.type == ServerType.LEMON_MUSIC }
                                } catch (_: Exception) { null }
                                entity?.let {
                                    ServerConfig(
                                        id = it.id,
                                        name = it.name,
                                        type = it.type,
                                        serverUrl = it.serverUrl,
                                        username = it.username,
                                        tokenOrApiKey = it.tokenOrApiKey,
                                        saltOrSecret = it.saltOrSecret,
                                        syncMode = it.syncMode,
                                        isCurrentActive = it.isCurrentActive
                                    )
                                }
                            }
                        if (active != null) {
                            val protocol = LemonMusicProtocol(
                                NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                active.serverUrl,
                                active.username,
                                active.tokenOrApiKey
                            )
                            val cleanId = targetSong.id.removePrefix("lemon_online_")
                            val source = if (cleanId.contains("_")) cleanId.substringBefore("_") else "kw"
                            val meta = targetSong.rawMetaJson?.takeIf { it.trim().startsWith("{") }
                                ?: targetSong.relativeFolderPath?.takeIf { it.trim().startsWith("{") }

                            // 依据当前网络状态 (Wi-Fi/VPN vs 移动流量) 智能选择试听音质并阶梯探测
                            val preferredQuality = LemonMusicProtocol.getPreferredStreamQuality(this@MainActivity)
                            val resolvedStream = protocol.resolveOnlineStreamWithQuality(
                                songId = targetSong.id,
                                source = source,
                                preferredQuality = preferredQuality,
                                metaJson = meta,
                                fallbackTitle = targetSong.title,
                                fallbackArtist = targetSong.artist
                            ).getOrNull()

                            var realUrl = resolvedStream?.url

                            if (realUrl.isNullOrBlank()) {
                                // 若第三方在线源暂时无法返回直链，自动回退匹配资料库中完全同版本的服务端歌曲有效流地址
                                val matchedServerSong = songList.firstOrNull {
                                    (it.streamUrl.startsWith("http://") || it.streamUrl.startsWith("https://")) &&
                                    SongMatchingResolver.isSongMatch(
                                        it.title, it.artist, it.durationMs,
                                        targetSong.title, targetSong.artist, targetSong.durationMs,
                                        it.album, targetSong.album
                                    )
                                }
                                if (matchedServerSong != null) {
                                    val srvPath = LemonMusicProtocol.getServerFilePath(
                                        matchedServerSong.id,
                                        matchedServerSong.streamUrl,
                                        matchedServerSong.coverUrl
                                    )
                                    realUrl = if (!srvPath.isNullOrBlank()) {
                                        protocol.ensureAuthenticated()
                                        protocol.getStreamUrlForPath(srvPath)
                                    } else {
                                        matchedServerSong.streamUrl
                                    }
                                }
                            }

                            if (!realUrl.isNullOrBlank()) {
                                val resolvedSong = targetSong.copy(
                                    streamUrl = realUrl,
                                    format = resolvedStream?.format ?: targetSong.format,
                                    bitRate = resolvedStream?.bitRate ?: targetSong.bitRate
                                )
                                withContext(Dispatchers.Main) {
                                    val resolvedQueue = activeQueue.map { if (it.id == resolvedSong.id) resolvedSong else it }
                                    PlaybackQueueManager.playSong(
                                        targetSong = resolvedSong,
                                        context = this@MainActivity,
                                        newPlaylist = resolvedQueue,
                                        startPositionMs = startPositionMs
                                    )
                                }
                                return@launch
                            }
                        }
                        withContext(Dispatchers.Main) {
                            PlaybackQueueManager.playSong(
                                targetSong = targetSong,
                                context = this@MainActivity,
                                newPlaylist = activeQueue,
                                startPositionMs = startPositionMs
                            )
                        }
                    }
                } else {
                    PlaybackQueueManager.playSong(
                        targetSong = targetSong,
                        context = this@MainActivity,
                        newPlaylist = activeQueue,
                        startPositionMs = startPositionMs
                    )
                }
            }

            val playSongWithQueue: (UnifiedSong, List<UnifiedSong>?) -> Unit = { targetSong, contextQueue ->
                playSongWithQueueAndPosition(targetSong, contextQueue, 0L)
            }

            val playSong: (UnifiedSong) -> Unit = { targetSong ->
                playSongWithQueueAndPosition(targetSong, null, 0L)
            }

            // 多选音质与下载端点调度 (支持缓存至本地 / 缓存至服务器 / 双方同步缓存)
            val handleDownloadWithOptions: (UnifiedSong, DownloadTarget, AudioQuality) -> Unit = { songToDownload, target, quality ->
                val activeServer = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                    ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }

                // 1. 服务端缓存调度 (SERVER 或 BOTH)
                if (target == DownloadTarget.SERVER || target == DownloadTarget.BOTH) {
                    if (activeServer == null) {
                        Toast.makeText(this@MainActivity, "未配置柠檬音乐服务端，无法推送至服务器曲库", Toast.LENGTH_SHORT).show()
                    } else {
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val protocol = LemonMusicProtocol(
                                    NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                    activeServer.serverUrl,
                                    activeServer.username,
                                    activeServer.tokenOrApiKey
                                )
                                val rawJsonStr = songToDownload.rawMetaJson ?: songToDownload.relativeFolderPath
                                val platform = DownloadRequestPlanner.resolveOnlineSource(songToDownload)
                                // 任务构造与批量下载共用同一份实现 (DownloadRequestPlanner)
                                val serverTask = DownloadRequestPlanner.buildServerDownloadTask(songToDownload, quality)
                                val res = protocol.addServerDownloadTasks(listOf(serverTask))
                                withContext(Dispatchers.Main) {
                                    if (res.isSuccess) {
                                        Toast.makeText(this@MainActivity, "已提交缓存至服务器 [${quality.badge}]: ${songToDownload.title}", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(this@MainActivity, "缓存至服务器失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                                if (res.isSuccess) {
                                    // 1. 立即在本地 Room 数据库中为该歌曲建档（标记 serverId 为 activeServer.id），使其即时在程序资料库显现
                                    // 同时确保 streamUrl 不为空，防止在服务端完成扫描前同名搜索结果被匹配后无法播放
                                    val existingEntity = database.songDao().getSongById(songToDownload.id)
                                    val resolvedStreamForRecord = songToDownload.streamUrl.takeIf { it.isNotBlank() && !it.startsWith("lemon_online://") }
                                        ?: existingEntity?.streamUrl?.takeIf { it.isNotBlank() && !it.startsWith("lemon_online://") }
                                        ?: protocol.resolveOnlineStreamUrl(
                                            songId = songToDownload.id,
                                            source = platform,
                                            quality = quality.key,
                                            metaJson = rawJsonStr,
                                            fallbackTitle = songToDownload.title,
                                            fallbackArtist = songToDownload.artist
                                        ).getOrNull().orEmpty()
                                    val serverSongEntity = SongEntity(
                                        id = songToDownload.id,
                                        title = songToDownload.title.ifBlank { "未知曲目" },
                                        artist = songToDownload.artist.ifBlank { "未知歌手" },
                                        artistId = songToDownload.artistId.ifBlank { "artist_${songToDownload.artist.hashCode()}" },
                                        album = songToDownload.album.ifBlank { "单曲精选" },
                                        albumId = songToDownload.albumId.ifBlank { "album_${songToDownload.album.hashCode()}" },
                                        durationMs = songToDownload.durationMs,
                                        coverUrl = songToDownload.coverUrl,
                                        streamUrl = resolvedStreamForRecord,
                                        serverId = activeServer.id,
                                        localFilePath = existingEntity?.localFilePath ?: songToDownload.localFilePath,
                                        downloadStatus = existingEntity?.downloadStatus ?: songToDownload.downloadStatus,
                                        bitRate = quality.bitrate,
                                        format = quality.format.lowercase(),
                                        isFavorite = existingEntity?.isFavorite ?: songToDownload.isFavorite,
                                        relativeFolderPath = songToDownload.relativeFolderPath?.takeIf { !it.startsWith("{") && !it.contains("\"") && !it.contains("_id__") && it.length <= 100 },
                                        addedTimestamp = System.currentTimeMillis()
                                    )
                                    database.songDao().insertSongs(listOf(serverSongEntity))

                                    // 2. 触发后台异步自动对账：延迟触发服务端扫描与本地曲库双向同步，无缝挂载物理文件
                                    // 对账失败（如大曲库同步异常）不应影响下载结果，更不能闪退
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        try {
                                            delay(1500L)
                                            protocol.triggerServerScan()
                                            delay(2500L)
                                            syncServerSongsWithToast(activeServer, false)
                                            delay(5000L)
                                            syncServerSongsWithToast(activeServer, false)
                                        } catch (e: Exception) {
                                            Log.w("MainActivity", "Post-download server sync failed", e)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "网络错误: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }

                // 2. 本地离线下载调度 (LOCAL 或 BOTH)
                if (target == DownloadTarget.LOCAL || target == DownloadTarget.BOTH) {
                    val safeFormat = quality.format.lowercase()
                    val preparedSong = songToDownload.copy(format = safeFormat, bitRate = quality.bitrate)

                    // 有柠檬服务端、且歌曲能按指定音质重新获取时，一律走音质决策解析器。
                    // 关键修复：服务器曲库歌曲的 streamUrl 是 /api/play/local?path=…&token=…，
                    // **URL 里没有音质参数**，以前被当作"直链歌曲"直接下载，拉到的其实是服务器上的
                    // 原文件（可能是无损）——选 320K 却下到无损就是这么来的。
                    if (activeServer != null && DownloadRequestPlanner.hasRemoteSource(songToDownload)) {
                        val dlPriority = downloadEngine.downloadSettings.value.downloadSourcePriority
                        downloadEngine.startDownload(
                            song = preparedSong,
                            urlResolver = { resolveDownloadUrlsFor(this@MainActivity, activeServer, songToDownload, quality, dlPriority) }
                        )
                        Toast.makeText(this@MainActivity, "已加入下载队列 [${quality.badge}]: ${songToDownload.title}", Toast.LENGTH_SHORT).show()
                    } else {
                        // 纯本地文件：没有可换音质的来源，沿用本地直链下载
                        downloadEngine.startDownload(preparedSong)
                        Toast.makeText(this@MainActivity, "已加入本地下载: ${songToDownload.title}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // 批量下载调度 (对齐柠檬音乐服务器端 POST /api/download/tasks 批量数组接口 + 本地并发下载池)
            val handleBatchDownloadWithOptions: (List<UnifiedSong>, DownloadTarget, AudioQuality) -> Unit = { songsToDownload, target, quality ->
                val distinctSongs = songsToDownload.distinctBy { it.id }
                if (distinctSongs.isNotEmpty()) {
                    val activeServer = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                        ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }

                    // 1. 服务端批量缓存调度 (SERVER 或 BOTH)
                    if (target == DownloadTarget.SERVER || target == DownloadTarget.BOTH) {
                        if (activeServer == null) {
                            Toast.makeText(this@MainActivity, "未配置柠檬音乐服务端，无法推送至服务器曲库", Toast.LENGTH_SHORT).show()
                        } else {
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val protocol = LemonMusicProtocol(
                                        NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                        activeServer.serverUrl,
                                        activeServer.username,
                                        activeServer.tokenOrApiKey
                                    )
                                    // 任务构造与单首下载共用同一份实现 (DownloadRequestPlanner)
                                    val serverTasks = distinctSongs.map {
                                        DownloadRequestPlanner.buildServerDownloadTask(it, quality)
                                    }

                                    var successChunks = 0
                                    for (chunk in serverTasks.chunked(50)) {
                                        if (protocol.addServerDownloadTasks(chunk).isSuccess) {
                                            successChunks++
                                        }
                                    }

                                    withContext(Dispatchers.Main) {
                                        if (successChunks > 0) {
                                            Toast.makeText(this@MainActivity, "已批量提交 ${distinctSongs.size} 首歌曲至服务器缓存 [${quality.badge}]", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(this@MainActivity, "批量提交服务器缓存失败", Toast.LENGTH_LONG).show()
                                        }
                                    }

                                    if (successChunks > 0) {
                                        val nowTs = System.currentTimeMillis()
                                        val entities = distinctSongs.mapIndexed { idx, s ->
                                            val existingEntity = database.songDao().getSongById(s.id)
                                            SongEntity(
                                                id = s.id,
                                                title = s.title.ifBlank { "未知曲目" },
                                                artist = s.artist.ifBlank { "未知歌手" },
                                                artistId = s.artistId.ifBlank { "artist_${s.artist.hashCode()}" },
                                                album = s.album.ifBlank { "单曲精选" },
                                                albumId = s.albumId.ifBlank { "album_${s.album.hashCode()}" },
                                                durationMs = s.durationMs,
                                                coverUrl = s.coverUrl,
                                                streamUrl = s.streamUrl.takeIf { it.isNotBlank() && !it.startsWith("lemon_online://") }
                                                    ?: existingEntity?.streamUrl.orEmpty(),
                                                serverId = activeServer.id,
                                                localFilePath = existingEntity?.localFilePath ?: s.localFilePath,
                                                downloadStatus = existingEntity?.downloadStatus ?: s.downloadStatus,
                                                bitRate = quality.bitrate,
                                                format = quality.format.lowercase(),
                                                isFavorite = existingEntity?.isFavorite ?: s.isFavorite,
                                                relativeFolderPath = s.relativeFolderPath?.takeIf { !it.startsWith("{") && !it.contains("\"") && !it.contains("_id__") && it.length <= 100 },
                                                addedTimestamp = nowTs + idx
                                            )
                                        }
                                        database.songDao().insertSongs(entities)

                                        lifecycleScope.launch(Dispatchers.IO) {
                                            delay(2500L)
                                            protocol.triggerServerScan()
                                            delay(3000L)
                                            syncServerSongsWithToast(activeServer, false)
                                            delay(6000L)
                                            syncServerSongsWithToast(activeServer, false)
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@MainActivity, "批量缓存网络错误: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }

                    // 2. 本地批量离线下载调度 (LOCAL 或 BOTH)
                    if (target == DownloadTarget.LOCAL || target == DownloadTarget.BOTH) {
                        val safeFormat = quality.format.lowercase()
                        var enqueuedCount = 0
                        var skippedCount = 0
                        distinctSongs.forEach { songToDownload ->
                            val localPath = songToDownload.localFilePath
                            val hasLocalFile = !localPath.isNullOrBlank() &&
                                (localPath.startsWith("content://") || File(localPath).exists())
                            // 能按指定音质重新获取的来源（在线曲目/服务器曲库曲目）：已有文件的音质档次
                            // 与目标不符时不能算"已下载"，否则选 320K 会一直命中本地那份无损而被跳过
                            val serverForRefetch = activeServer?.takeIf {
                                DownloadRequestPlanner.hasRemoteSource(songToDownload)
                            }
                            val alreadyHaveTarget = hasLocalFile && (
                                serverForRefetch == null ||
                                    DownloadRequestPlanner.existingLocalFileSatisfies(songToDownload, quality)
                                )
                            if (alreadyHaveTarget) {
                                skippedCount++
                                return@forEach
                            }
                            enqueuedCount++
                            val preparedSong = songToDownload.copy(format = safeFormat, bitRate = quality.bitrate)
                            if (serverForRefetch != null) {
                                val dlPriority = downloadEngine.downloadSettings.value.downloadSourcePriority
                                downloadEngine.startDownload(
                                    song = preparedSong,
                                    urlResolver = { resolveDownloadUrlsFor(this@MainActivity, serverForRefetch, songToDownload, quality, dlPriority) }
                                )
                            } else {
                                downloadEngine.startDownload(preparedSong)
                            }
                        }
                        val skipTip = if (skippedCount > 0) "，跳过 $skippedCount 首（已存在该音质）" else ""
                        Toast.makeText(
                            this@MainActivity,
                            if (enqueuedCount > 0) "已将 $enqueuedCount 首歌曲加入本地下载队列 [${quality.badge}]$skipTip" else "所选 $skippedCount 首歌曲均已下载至本地",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            val handleDownloadSong: (UnifiedSong) -> Unit = { songToDownload ->
                val savedTargetName = uiPrefs.getString("default_download_target", DownloadTarget.LOCAL.name) ?: DownloadTarget.LOCAL.name
                val defaultTarget = try { DownloadTarget.valueOf(savedTargetName) } catch (_: Exception) { DownloadTarget.LOCAL }
                val savedQualityKey = uiPrefs.getString("default_download_quality", AudioQuality.Q_320K.key) ?: AudioQuality.Q_320K.key
                val defaultQuality = AudioQuality.entries.firstOrNull { it.key == savedQualityKey } ?: AudioQuality.Q_320K
                handleDownloadWithOptions(songToDownload, defaultTarget, defaultQuality)
            }

            /**
             * 收藏/取消收藏的**唯一**实现（资料库、播放页、首页迷你条共用）。
             *
             * 之前每个入口各写一份 `updateFavorite(id, flag)`，而它只是
             * `UPDATE songs SET isFavorite=… WHERE id=…`：对**尚未入库**的歌曲
             * （在线试听、发现页里的曲目）影响 0 行，数据库里什么都没留下，
             * 「我喜欢的音乐」自然永远是空的 —— 这就是"加入喜欢后没有歌曲"的根因。
             * 现在先 get-or-insert 补齐歌曲行，再同步 lemon_favorites 歌单关联。
             */
            val handleToggleFavorite: (UnifiedSong) -> Unit = { songToFav ->
                val newFav = !songToFav.isFavorite
                val updatedSong = songToFav.copy(isFavorite = newFav)
                songList = if (songList.any { it.id == songToFav.id }) {
                    songList.map { if (it.id == songToFav.id) updatedSong else it }
                } else {
                    listOf(updatedSong) + songList
                }
                // 「我喜欢的音乐」以服务器收藏为准，这里先把本机这份镜像改掉，
                // 确保用户点击红心后即时生效
                serverFavoriteSongs = if (newFav) {
                    (listOf(updatedSong) + serverFavoriteSongs).distinctBy { it.id }
                } else {
                    serverFavoriteSongs.filterNot { it.id == songToFav.id }
                }
                if (PlaybackQueueManager.currentSongFlow.value?.id == songToFav.id) {
                    PlaybackQueueManager.updateCurrentSong(updatedSong)
                }
                Toast.makeText(
                    this@MainActivity,
                    if (newFav) "已加入「我喜欢的音乐」" else "已从「我喜欢的音乐」移除",
                    Toast.LENGTH_SHORT
                ).show()
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val existing = database.songDao().getSongById(songToFav.id)
                        if (existing == null) {
                            val activeSrv = serversList.firstOrNull { it.isCurrentActive } ?: serversList.firstOrNull()
                            val effectiveServerId = when {
                                songToFav.serverId.isNotBlank() && songToFav.serverId != "lemon_online" -> songToFav.serverId
                                activeSrv != null -> activeSrv.id
                                else -> "local_storage"
                            }
                            database.songDao().insertSongs(
                                listOf(
                                    SongEntity(
                                        id = updatedSong.id,
                                        title = updatedSong.title.ifBlank { "未知曲目" },
                                        artist = updatedSong.artist.ifBlank { "未知歌手" },
                                        artistId = updatedSong.artistId.ifBlank { "artist_${updatedSong.artist.hashCode()}" },
                                        album = updatedSong.album.ifBlank { "单曲精选" },
                                        albumId = updatedSong.albumId.ifBlank { "album_${updatedSong.album.hashCode()}" },
                                        durationMs = updatedSong.durationMs,
                                        coverUrl = updatedSong.coverUrl,
                                        streamUrl = updatedSong.streamUrl,
                                        serverId = effectiveServerId,
                                        localFilePath = updatedSong.localFilePath,
                                        downloadStatus = updatedSong.downloadStatus,
                                        bitRate = updatedSong.bitRate,
                                        format = updatedSong.format,
                                        isFavorite = newFav,
                                        relativeFolderPath = updatedSong.rawMetaJson ?: updatedSong.relativeFolderPath,
                                        addedTimestamp = System.currentTimeMillis()
                                    )
                                )
                            )
                        } else {
                            database.songDao().updateFavorite(songToFav.id, newFav)
                        }
                        if (newFav) {
                            database.playlistDao().addSongToPlaylist(
                                com.lm.player.core.database.entity.PlaylistSongEntity(
                                    playlistId = "lemon_favorites",
                                    songId = songToFav.id
                                )
                            )
                        } else {
                            database.playlistDao().removeSongFromPlaylist("lemon_favorites", songToFav.id)
                        }
                        database.playlistDao().updateSongCount("lemon_favorites")

                        val activeServer = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                            ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }
                        if (activeServer != null) {
                            val protocol = LemonMusicProtocol(
                                NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                activeServer.serverUrl,
                                activeServer.username,
                                activeServer.tokenOrApiKey
                            )
                            val favToggleRes = protocol.toggleFavoriteSongOnServer(updatedSong, newFav)
                            // 收藏后自动缓存到服务器（设置-下载偏好中开启，默认关闭）。
                            // fire-and-forget：失败只记日志，不影响收藏本身；音质复用默认下载音质
                            if (newFav && favToggleRes.isSuccess &&
                                uiPrefs.getBoolean("favorite_auto_server_download", false)
                            ) {
                                try {
                                    val favQualityKey = uiPrefs.getString("default_download_quality", AudioQuality.Q_320K.key)
                                        ?: AudioQuality.Q_320K.key
                                    val favQuality = AudioQuality.entries.firstOrNull { it.key == favQualityKey } ?: AudioQuality.Q_320K
                                    val favTask = DownloadRequestPlanner.buildServerDownloadTask(updatedSong, favQuality)
                                    val dlRes = protocol.addServerDownloadTasks(listOf(favTask))
                                    if (dlRes.isSuccess) {
                                        // 提交服务器下载任务成功后，把歌曲标记为已缓存到服务器，
                                        // 播放页下载按钮立即显示「已下载到服务器」(CloudDone) 图标。
                                        // 否则在线歌曲 serverId 始终为 lemon_online，按钮永远显示未下载。
                                        try {
                                            database.songDao().updateServerId(songToFav.id, "lemon_music")
                                        } catch (_: Exception) {}
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@MainActivity, "已同步缓存至服务器: ${updatedSong.title}", Toast.LENGTH_SHORT).show()
                                        }
                                        // 主动检测：延迟 5 秒等服务器下载完成，然后立即执行增量升级
                                        // 不依赖轮询（15 秒间隔太长），收藏后 5 秒内状态更新
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            kotlinx.coroutines.delay(5000)
                                            try {
                                                val tasks = protocol.getServerDownloadTasks().getOrNull() ?: return@launch
                                                val completedTask = tasks.firstOrNull { t ->
                                                    t.status == DownloadStatus.DOWNLOADED &&
                                                        t.song.title.equals(updatedSong.title, ignoreCase = true) &&
                                                        t.song.artist.equals(updatedSong.artist, ignoreCase = true)
                                                }
                                                if (completedTask != null) {
                                                    Log.i("MainActivity", "Favorite download completed: ${updatedSong.title}, triggering incremental upgrade")
                                                    val serverPath = completedTask.serverFilePath
                                                        ?: completedTask.song.streamUrl?.let { url ->
                                                            if (url.contains("path=")) {
                                                                try {
                                                                    java.net.URLDecoder.decode(
                                                                        url.substringAfter("path=").substringBefore("&"), "UTF-8"
                                                                    ).trim()
                                                                } catch (_: Exception) { null }
                                                            } else null
                                                        }
                                                    if (!serverPath.isNullOrBlank()) {
                                                        val dao = database.songDao()
                                                        val row = dao.getSongById(songToFav.id)
                                                        if (row != null) {
                                                            dao.updateDownloadStatus(row.id, DownloadStatus.DOWNLOADED, serverPath)
                                                            dao.updateServerId(row.id, "lemon_music")
                                                            Log.i("MainActivity", "Incrementally upgraded favorite '${updatedSong.title}' to downloaded status")
                                                        }
                                                        // 升级收藏/歌单
                                                        val unified = UnifiedSong(
                                                            id = "lemon_${java.security.MessageDigest.getInstance("MD5").digest(serverPath.toByteArray()).joinToString("") { "%02x".format(it) }}",
                                                            title = updatedSong.title,
                                                            artist = updatedSong.artist,
                                                            album = updatedSong.album,
                                                            durationMs = updatedSong.durationMs,
                                                            streamUrl = protocol.getStreamUrlForPath(serverPath),
                                                            serverId = "lemon_music",
                                                            localFilePath = serverPath,
                                                            downloadStatus = DownloadStatus.DOWNLOADED
                                                        )
                                                        protocol.upgradeDownloadedTracksLocalPaths(listOf(unified))
                                                        withContext(Dispatchers.Main) {
                                                            favoritesRefreshTrigger++
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Log.w("MainActivity", "Post-favorite download check failed", e)
                                            }
                                        }
                                    } else {
                                        Log.w("MainActivity", "Auto server download for favorite rejected: ${dlRes.exceptionOrNull()?.message}")
                                    }
                                } catch (e: Exception) {
                                    Log.w("MainActivity", "Auto server download for favorite failed", e)
                                }
                            }
                            syncServerPlaylists(activeServer)
                            val favs = protocol.getPlaylistSongs("lemon_favorites").getOrNull()
                            if (favs != null) {
                                withContext(Dispatchers.Main) {
                                    serverFavoriteSongs = favs
                                    // 收藏成功（含服务器下载提交）后立即驱动全量曲库同步，
                                    // 把服务器上的文件路径 / downloadStatus 拉取到本地，
                                    // 否则收藏列表永远显示在线状态（resolveSongList 匹配不到已下载记录）
                                    serverDownloadSyncTrigger++
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "handleToggleFavorite error", e)
                    }
                }
            }

            val handleAddToPlaylist: (UnifiedPlaylist, UnifiedSong) -> Unit = { targetPlaylist, songToAdd ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        // 1. 确保歌曲在本地 song 表中持久化
                        val existing = database.songDao().getSongById(songToAdd.id)
                        if (existing == null) {
                            database.songDao().insertSongs(
                                listOf(
                                    SongEntity(
                                        id = songToAdd.id,
                                        title = songToAdd.title.ifBlank { "未知曲目" },
                                        artist = songToAdd.artist.ifBlank { "未知歌手" },
                                        artistId = songToAdd.artistId.ifBlank { "artist_${songToAdd.artist.hashCode()}" },
                                        album = songToAdd.album.ifBlank { "单曲精选" },
                                        albumId = songToAdd.albumId.ifBlank { "album_${songToAdd.album.hashCode()}" },
                                        durationMs = songToAdd.durationMs,
                                        coverUrl = songToAdd.coverUrl,
                                        streamUrl = songToAdd.streamUrl,
                                        serverId = songToAdd.serverId,
                                        localFilePath = songToAdd.localFilePath,
                                        downloadStatus = songToAdd.downloadStatus,
                                        bitRate = songToAdd.bitRate,
                                        format = songToAdd.format,
                                        isFavorite = songToAdd.isFavorite,
                                        relativeFolderPath = songToAdd.relativeFolderPath,
                                        addedTimestamp = System.currentTimeMillis()
                                    )
                                )
                            )
                        }

                        // 2. 本地插入 playlist_songs 关联
                        database.playlistDao().addSongToPlaylist(
                            com.lm.player.core.database.entity.PlaylistSongEntity(
                                playlistId = targetPlaylist.id,
                                songId = songToAdd.id
                            )
                        )
                        database.playlistDao().updateSongCount(targetPlaylist.id)

                        // 3. 若为云端在线歌单且配置了服务端，同步至服务器 customPlaylists
                        val active = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                        if (targetPlaylist.isOnline && active != null) {
                            val protocol = LemonMusicProtocol(
                                NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                active.serverUrl,
                                active.username,
                                active.tokenOrApiKey
                            )
                            val serverPath = LemonMusicProtocol.getServerFilePath(songToAdd.id, songToAdd.streamUrl, songToAdd.coverUrl)
                            val key = when {
                                !serverPath.isNullOrBlank() -> "local:$serverPath"
                                songToAdd.id.startsWith("lemon_") && songToAdd.streamUrl.contains("path=") -> {
                                    val decoded = try {
                                        java.net.URLDecoder.decode(songToAdd.streamUrl.substringAfter("path=").substringBefore("&"), "UTF-8")
                                    } catch (_: Exception) { null }
                                    if (decoded != null) "local:$decoded" else songToAdd.id
                                }
                                else -> songToAdd.id
                            }
                            protocol.addTracksToCustomPlaylist(targetPlaylist.id, listOf(key))

                            // 添加到云端歌单后自动缓存到服务器（设置-下载偏好中开启，默认关闭）。
                            // fire-and-forget：失败只记日志，不影响添加歌单本身；音质复用默认下载音质
                            if (uiPrefs.getBoolean("playlist_auto_server_download", false)) {
                                try {
                                    val plQualityKey = uiPrefs.getString("default_download_quality", AudioQuality.Q_320K.key)
                                        ?: AudioQuality.Q_320K.key
                                    val plQuality = AudioQuality.entries.firstOrNull { it.key == plQualityKey } ?: AudioQuality.Q_320K
                                    val plTask = DownloadRequestPlanner.buildServerDownloadTask(songToAdd, plQuality)
                                    val plDlRes = protocol.addServerDownloadTasks(listOf(plTask))
                                    if (plDlRes.isSuccess) {
                                        try {
                                            database.songDao().updateServerId(songToAdd.id, "lemon_music")
                                        } catch (_: Exception) {}
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@MainActivity, "已同步缓存至服务器: ${songToAdd.title}", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        Log.w("MainActivity", "Auto server download for playlist rejected: ${plDlRes.exceptionOrNull()?.message}")
                                    }
                                } catch (e: Exception) {
                                    Log.w("MainActivity", "Auto server download for playlist failed", e)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "handleAddToPlaylist error", e)
                    }
                }
            }

            val handleCreatePlaylistAndAddSong: (String, UnifiedSong) -> Unit = { playlistName, songToAdd ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val isCurrentLocalMode = activeServerName.contains("本地") || activeServerName.contains("已下载")
                        val active = if (isCurrentLocalMode) null else serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                        var newPlId = "pl_${System.currentTimeMillis()}"
                        var isOnlinePl = active != null

                        if (isOnlinePl && active != null) {
                            try {
                                val protocol = LemonMusicProtocol(
                                    NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                    active.serverUrl,
                                    active.username,
                                    active.tokenOrApiKey
                                )
                                val res = protocol.createCustomPlaylist(playlistName)
                                if (res.isSuccess) {
                                    res.getOrNull()?.let {
                                        newPlId = it.id
                                        isOnlinePl = true
                                    }
                                } else {
                                    isOnlinePl = false
                                }
                            } catch (e: Exception) {
                                Log.w("MainActivity", "Create server playlist failed, fallback to local", e)
                                isOnlinePl = false
                            }
                        }

                        database.playlistDao().insertPlaylist(
                            com.lm.player.core.database.entity.PlaylistEntity(
                                id = newPlId,
                                name = playlistName,
                                serverId = if (isOnlinePl && active != null) active.id else "local_storage",
                                isOnline = isOnlinePl,
                                songCount = 1
                            )
                        )

                        val createdPlaylist = UnifiedPlaylist(
                            id = newPlId,
                            name = playlistName,
                            isOnline = isOnlinePl,
                            serverId = if (isOnlinePl && active != null) active.id else "local_storage",
                            songCount = 1
                        )
                        handleAddToPlaylist(createdPlaylist, songToAdd)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "handleCreatePlaylistAndAddSong error", e)
                    }
                }
            }

            val playNext: () -> Unit = {
                PlaybackQueueManager.playNext(this@MainActivity)
            }

            val playPrevious: () -> Unit = {
                PlaybackQueueManager.playPrevious(this@MainActivity)
            }

            val togglePlayPause: () -> Unit = {
                PlaybackQueueManager.togglePlay(this@MainActivity)
            }

            // 挂载全局方向盘控制与按键动作
            LaunchedEffect(Unit) {
                playNextAction = playNext
                playPreviousAction = playPrevious
                togglePlayAction = togglePlayPause
            }

            // 启动自动播放与断点恢复逻辑 (启动时精准恢复并继续播放上一次关闭前的那一首歌曲与进度)
            LaunchedEffect(songList, completedDownloadedSongs, autoPlayOnStartup) {
                if (!autoPlayOnStartup || hasAutoPlayedOnStartup) return@LaunchedEffect
                if (exoPlayer?.isPlaying == true && currentSong != null) {
                    hasAutoPlayedOnStartup = true
                    return@LaunchedEffect
                }
                if (songList.isEmpty() && completedDownloadedSongs.isEmpty()) {
                    delay(300L)
                }
                if (hasAutoPlayedOnStartup) return@LaunchedEffect
                val combinedCandidates = (songList + completedDownloadedSongs).distinctBy { it.id }
                val (targetSong, targetQueue) = resolveSavedSongAndQueue(combinedCandidates)
                if (targetSong != null) {
                    hasAutoPlayedOnStartup = true
                    val savedPos = PlaybackQueueManager.getSavedPositionMs(this@MainActivity)
                    val resumePos = if (targetSong.durationMs > 0L && savedPos >= targetSong.durationMs - 2000L) {
                        0L
                    } else {
                        savedPos.coerceAtLeast(0L)
                    }
                    playSongWithQueueAndPosition(
                        targetSong,
                        targetQueue.ifEmpty { listOf(targetSong) },
                        resumePos
                    )
                }
            }

            // 歌曲切换时动态从音乐文件/NAS提取解析歌词 (支持秒级内存预载与防并发竞争)
            LaunchedEffect(currentSong?.id) {
                DynamicIslandManager.ensureInitialized(this@MainActivity)
                DynamicIslandManager.clearLyrics()
                val targetSong = currentSong ?: return@LaunchedEffect
                val cached = LyricsManager.getCachedLyrics(targetSong.id)
                if (cached != null && cached.lines.isNotEmpty()) {
                    currentLyrics = cached
                } else {
                    currentLyrics = LyricResult(emptyList())
                    val activeServer = serversList.firstOrNull { it.isCurrentActive }
                    val loaded = LyricsManager.loadLyrics(targetSong, this@MainActivity, activeServer)
                    if (currentSong?.id == targetSong.id) {
                        currentLyrics = loaded
                    }
                }
            }

            // 监听 ExoPlayer 在线容灾回退与中途断流无缝断点续播及音质降级
            var lastStreamRetrySongId by remember { mutableStateOf("") }
            var lastStreamRetryTimeMs by remember { mutableStateOf(0L) }
            var lastStreamRetryQualityIdx by remember { mutableStateOf(0) }
            DisposableEffect(exoPlayer, currentSong, songList, autoFallbackToLocal) {
                val listener = object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("MainActivity", "Player Error encountered: ${error.message}", error)
                        val targetSong = currentSong
                        val resumePos = (exoPlayer?.currentPosition ?: 0L).coerceAtLeast(0L)
                        if (targetSong != null) {
                            if (autoFallbackToLocal) {
                                // 1. 优先尝试切换至本地离线音频文件并保留当前播放进度（严格核对版本与时长）
                                val matchedLocalSong = (songList + completedDownloadedSongs).firstOrNull {
                                    val hasFile = !it.localFilePath.isNullOrBlank() && java.io.File(it.localFilePath).let { f -> f.exists() && f.length() > 0 }
                                    hasFile && (it.id == targetSong.id || SongMatchingResolver.isSongMatch(
                                        it.title, it.artist, it.durationMs,
                                        targetSong.title, targetSong.artist, targetSong.durationMs,
                                        it.album, targetSong.album
                                    ))
                                }
                                if (matchedLocalSong != null && matchedLocalSong.localFilePath != targetSong.localFilePath) {
                                    Toast.makeText(this@MainActivity, "在线音频缓冲受阻，已无缝切换至本地离线版本", Toast.LENGTH_SHORT).show()
                                    val localSong = targetSong.copy(
                                        localFilePath = matchedLocalSong.localFilePath,
                                        streamUrl = matchedLocalSong.localFilePath ?: "",
                                        downloadStatus = DownloadStatus.DOWNLOADED
                                    )
                                    PlaybackQueueManager.playSong(
                                        targetSong = localSong,
                                        context = this@MainActivity,
                                        startPositionMs = resumePos
                                    )
                                    return
                                }
                            }

                            // 2. 音质降级与换源重试：若在线流或高音质(Hi-Res/FLAC)播放中途断流/无法缓冲，
                            // 参考柠檬音乐机制按音质候选链 (flac24bit -> flac -> 320k -> 128k) 依次换源降低音质重试并提示用户
                            val preferredQuality = LemonMusicProtocol.getPreferredStreamQuality(this@MainActivity)
                            val fallbackQualities = LemonMusicProtocol.getFallbackQualities(preferredQuality)
                            val currentDowngradeIdx = if (lastStreamRetrySongId == targetSong.id) lastStreamRetryQualityIdx else 0
                            val nextQualityIdx = currentDowngradeIdx + 1
                            if (nextQualityIdx < fallbackQualities.size) {
                                lastStreamRetrySongId = targetSong.id
                                lastStreamRetryTimeMs = System.currentTimeMillis()
                                lastStreamRetryQualityIdx = nextQualityIdx
                                val nextQualityKey = fallbackQualities[nextQualityIdx]
                                val nextQualityLabel = AudioQuality.fromKey(nextQualityKey).label
                                Log.i("MainActivity", "Auto-downgrading quality for ${targetSong.title} to $nextQualityKey ($nextQualityLabel) at ${resumePos}ms")
                                Toast.makeText(this@MainActivity, "当前音质无法缓冲，已自动为您换源降至【$nextQualityLabel】播放", Toast.LENGTH_SHORT).show()
                                PlaybackQueueManager.playSong(
                                    targetSong = targetSong,
                                    context = this@MainActivity,
                                    startPositionMs = resumePos,
                                    forceRefresh = true,
                                    overrideQuality = nextQualityKey
                                )
                                return
                            }

                            if (autoFallbackToLocal) {
                                // 3. 若降级仍失败，尝试回退至服务器资料库内同版本已归档曲目并保留播放进度
                                val matchedServerSong = songList.firstOrNull {
                                    it.id != targetSong.id &&
                                    it.streamUrl != targetSong.streamUrl &&
                                    (it.streamUrl.contains("/api/play/local") || it.streamUrl.startsWith("http")) &&
                                    SongMatchingResolver.isSongMatch(
                                        it.title, it.artist, it.durationMs,
                                        targetSong.title, targetSong.artist, targetSong.durationMs,
                                        it.album, targetSong.album
                                    )
                                }
                                if (matchedServerSong != null) {
                                    Toast.makeText(this@MainActivity, "在线音源缓冲受阻，已自动切换至服务器资料库同名版本", Toast.LENGTH_SHORT).show()
                                    PlaybackQueueManager.playSong(
                                        targetSong = matchedServerSong,
                                        context = this@MainActivity,
                                        startPositionMs = resumePos
                                    )
                                    return
                                }
                            }
                        }
                        Toast.makeText(this@MainActivity, "当前歌曲《${currentSong?.title ?: "未知"}》音频无法缓冲，请检查网络或服务端连接", Toast.LENGTH_SHORT).show()
                    }
                }
                exoPlayer?.addListener(listener)
                onDispose {
                    exoPlayer?.removeListener(listener)
                }
            }

            CompositionLocalProvider(
                LocalDensity provides adaptiveDensity,
                LocalAppDimensions provides appDimensions
            ) {
                ZDSPlayerTheme(themeMode = currentThemeMode) {
                    // 全局触屏边缘向右滑动返回手势监听 (适配现代全面屏返回手势)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    val edgeThreshold = 44.dp.toPx()
                                    while (true) {
                                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        if (down.position.x <= edgeThreshold) {
                                            var totalDx = 0f
                                            var totalDy = 0f
                                            var triggered = false
                                            while (true) {
                                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                                val drag = event.changes.firstOrNull { it.id == down.id } ?: break
                                                totalDx += (drag.position.x - drag.previousPosition.x)
                                                totalDy += (drag.position.y - drag.previousPosition.y)

                                                if (!triggered && totalDx > 65.dp.toPx() && totalDx > kotlin.math.abs(totalDy) * 1.4f) {
                                                    triggered = true
                                                    drag.consume()
                                                    onBackPressedDispatcher.onBackPressed()
                                                    break
                                                }
                                                if (drag.changedToUp() || !drag.pressed) break
                                            }
                                        }
                                    }
                                }
                            }
                    ) {
                        var globalSearchQuery by remember { mutableStateOf("") }
                        var currentSearchType by remember { mutableStateOf(SearchContentType.SONG) }
                        val activeLemonServerForSearch = remember(serversList) {
                            serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                                ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }
                        }

                        // 「我喜欢的音乐」以柠檬服务器收藏为准：
                        // 仅在 favoritesRefreshTrigger 变化时拉取（切换自动刷新/下拉刷新/定时刷新都会驱动它），
                        // 不再因「仅仅进入资料库标签」就自动请求。
                        LaunchedEffect(activeLemonServerForSearch?.id, activeLemonServerForSearch?.tokenOrApiKey, favoritesRefreshTrigger) {
                            val srv = activeLemonServerForSearch ?: return@LaunchedEffect
                            val fetched = withContext(Dispatchers.IO) {
                                try {
                                    val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                    val protocol = LemonMusicProtocol(client, srv.serverUrl, srv.username, srv.tokenOrApiKey)
                                    protocol.getPlaylistSongs("lemon_favorites").getOrNull()
                                } catch (e: Exception) {
                                    Log.w("MainActivity", "Fetching server favorites failed", e)
                                    null
                                }
                            }
                            if (fetched != null) {
                                // 与本地 Room 曲库即时比对：服务器已下载完成的收藏条目升级为已下载状态，
                                // 保证「我的收藏」卡片与歌单入口显示真实的下载状态而非永远的在线状态
                                serverFavoriteSongs = SongMatchingResolver.resolveSongList(
                                    incomingSongs = fetched,
                                    allCachedSongs = songList,
                                    activeTasks = activeDownloadTasks,
                                    downloadDir = downloadEngine.getDownloadDir()
                                )
                            }
                        }

                        // 进入资料库时自动同步服务端歌单（含删除同步）：
                        // 仅当「切换到资料库时自动刷新」开启时执行；关闭后由下拉刷新 / 定时刷新驱动。
                        var lastLibrarySyncTs by remember { mutableStateOf(0L) }
                        LaunchedEffect(currentScreen) {
                            if (currentScreen == Screen.LIBRARY &&
                                uiPrefs.getBoolean("library_refresh_on_switch", true)
                            ) {
                                val now = System.currentTimeMillis()
                                if (now - lastLibrarySyncTs > 3000) { // 3 秒节流，避免反复切页刷接口
                                    lastLibrarySyncTs = now
                                    val active = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                                    if (active != null) syncServerPlaylists(active)
                                }
                            }
                        }

                        // 响应式脚手架 (横屏大屏与竖屏手机统一使用底部悬浮一体化播放导航栏 + 弹出式左滑搜索框)
                        AdaptiveAppScaffold(
                        windowSizeClass = windowSizeClass.widthSizeClass,
                        currentScreen = if (currentScreen == Screen.DOWNLOADS) Screen.LIBRARY else currentScreen,
                        currentPlayingSong = currentSong,
                        isPlaying = isPlaying,
                        isSearchActive = isSearchDialogOpen,
                        searchQuery = globalSearchQuery,
                        onSearchQueryChange = { globalSearchQuery = it },
                        selectedOnlineSource = currentOnlineSource,
                        onOnlineSourceChange = { newSrc ->
                            currentOnlineSource = newSrc
                            onlinePrefs.edit().putString("selected_source", newSrc.name).apply()
                        },
                        selectedSearchType = currentSearchType,
                        onSearchTypeChange = { newType ->
                            currentSearchType = newType
                        },
                        showOnlineSourceSelector = (activeLemonServerForSearch != null),
                        blurAlpha = blurAlpha,
                        enableBottomBarAnimation = enableBottomBarAnimation,
                        useLinearAnimation = true,
                        onNavigate = { targetNavScreen ->
                            navigateToScreen(targetNavScreen)
                            isSearchDialogOpen = false
                            globalSearchQuery = ""
                            if (targetNavScreen == Screen.LIBRARY) {
                                // 仅当「切换到资料库时自动刷新」开启时，切页才自动同步；
                                // 关闭后由资料库页面的下拉刷新 / 定时刷新驱动。
                                val refreshOnSwitch = uiPrefs.getBoolean("library_refresh_on_switch", true)
                                if (refreshOnSwitch) {
                                    triggerLibraryRefresh(manual = false)
                                }
                            }
                        },
                        onDismissSearch = {
                            isSearchDialogOpen = false
                            globalSearchQuery = ""
                        },
                        onPlayPauseToggle = togglePlayPause,
                        onPrevious = playPrevious,
                        onNext = playNext,
                        onOpenFullPlayer = {
                            isSearchDialogOpen = false
                            globalSearchQuery = ""
                            isFullPlayerVisible = true
                        },
                        onSearchClick = {
                            if (isSearchDialogOpen) {
                                isSearchDialogOpen = false
                                globalSearchQuery = ""
                            } else {
                                isSearchDialogOpen = true
                            }
                        }
                    ) { innerPadding ->
                        Box(modifier = Modifier.fillMaxSize()) {
                            // 主标签：首页 / 资料库 / 设置 —— 支持左右滑动切换。
                            // 页面内部的横向滑动（如首页热门推荐歌单画廊）由子 LazyRow 优先消费手势，
                            // 只有横滑到子列表边界时 Pager 才接管，两者不冲突。
                            val mainTabs = remember { listOf(Screen.HOME, Screen.LIBRARY, Screen.SETTINGS) }
                            val mainPagerState = rememberPagerState(
                                initialPage = mainTabs.indexOf(currentScreen).coerceIn(0, mainTabs.lastIndex)
                            ) { mainTabs.size }

                            // 底栏/按钮导航 -> 驱动 Pager 滑动
                            LaunchedEffect(currentScreen) {
                                val idx = mainTabs.indexOf(currentScreen)
                                if (idx >= 0 && idx != mainPagerState.currentPage) {
                                    mainPagerState.animateScrollToPage(idx)
                                }
                            }
                            // 手势滑动翻页 -> 同步全局 currentScreen
                            LaunchedEffect(mainPagerState) {
                                snapshotFlow { mainPagerState.settledPage }
                                    .distinctUntilChanged()
                                    .collect { page ->
                                        val target = mainTabs[page]
                                        if (target != currentScreen) {
                                            navigateToScreen(target)
                                            // 与底栏点击保持一致：开关开启时滑到资料库才自动同步
                                            if (target == Screen.LIBRARY &&
                                                uiPrefs.getBoolean("library_refresh_on_switch", true)
                                            ) {
                                                triggerLibraryRefresh(manual = false)
                                            }
                                        }
                                    }
                            }

                            // 四个屏幕的内容统一入口（下载管理不是标签页，以覆盖层方式显示在 Pager 之上）
                            val renderScreen: @Composable (Screen) -> Unit = render@ { targetScreen ->
                            when (targetScreen) {
                                Screen.HOME -> {
                                    val activeServer = serversList.firstOrNull { it.isCurrentActive }
                                    val isLemonMusic = activeServer?.type == ServerType.LEMON_MUSIC &&
                                            !activeServerName.contains("本地") && !activeServerName.contains("已下载")

                                    if (isLemonMusic && activeServer != null) {
                                        LemonDiscoverHomeScreen(
                                            serverName = activeServerName,
                                            configuredServers = serversList,
                                            currentSource = currentOnlineSource,
                                            onSourceChange = { newSrc ->
                                                currentOnlineSource = newSrc
                                                onlinePrefs.edit().putString("selected_source", newSrc.name).apply()
                                            },
                                            blurAlpha = blurAlpha,
                                            allCachedSongs = songList,
                                            activeDownloadTasks = activeDownloadTasks,
                                            activeDownloadCount = activeDownloadTasks.size,
                                            currentPlayingSong = currentSong,
                                            isPlaying = isPlaying,
                                            locateSongTrigger = locateSongTrigger,
                                            scrollToTopTrigger = scrollToTopTrigger,
                                            onListScrollingChange = { isSongListScrolling = it },
                                            onSongClick = { song, queue -> playSongWithQueue(song, queue) },
                                            onDownloadSong = handleDownloadSong,
                                            onDownloadSongWithOptions = handleDownloadWithOptions,
                                            onBatchDownloadSongsWithOptions = handleBatchDownloadWithOptions,
                                            onSelectLocalServer = {
                                                activeServerName = "本地 · 已下载"
                                                navigateToScreen(Screen.HOME)
                                            },
                                            onSelectServer = { selectedServer ->
                                                activeServerName = selectedServer.name
                                                activeServerId = selectedServer.id
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    database.serverDao().setActiveServer(selectedServer.id)
                                                    syncServerPlaylists(selectedServer)
                                                    syncServerSongs(selectedServer)
                                                }
                                            },
                                            onSyncNow = {
                                                Toast.makeText(this@MainActivity, "正在从柠檬音乐同步全量曲库...", Toast.LENGTH_SHORT).show()
                                                syncServerSongs(activeServer)
                                            },
                                            onOpenDownloads = { navigateToScreen(Screen.DOWNLOADS) },
                                            onGoToSettings = { navigateToScreen(Screen.SETTINGS) },
                                            onSearchClick = { isSearchDialogOpen = true },
                                            onFetchDiscoverPlaylists = { src ->
                                                val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                val proto = LemonMusicProtocol(client, activeServer.serverUrl, activeServer.username, activeServer.tokenOrApiKey)
                                                proto.getDiscoverRecommendPlaylists(source = src.key, page = 1, limit = 20).getOrNull() ?: emptyList()
                                            },
                                            onFetchDiscoverToplists = { src ->
                                                val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                val proto = LemonMusicProtocol(client, activeServer.serverUrl, activeServer.username, activeServer.tokenOrApiKey)
                                                proto.getDiscoverToplists(source = src.key).getOrNull() ?: emptyList()
                                            },
                                            onFetchDiscoverNewSongs = { src ->
                                                val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                val proto = LemonMusicProtocol(client, activeServer.serverUrl, activeServer.username, activeServer.tokenOrApiKey)
                                                proto.getDiscoverNewSongs(source = src.key, limit = 30).getOrNull() ?: emptyList()
                                            },
                                            onFetchDiscoverNewAlbums = { src ->
                                                val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                val proto = LemonMusicProtocol(client, activeServer.serverUrl, activeServer.username, activeServer.tokenOrApiKey)
                                                proto.getDiscoverNewAlbums(source = src.key).getOrNull() ?: emptyList()
                                            },
                                            onParseExternalPlaylist = { url, src ->
                                                val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                val proto = LemonMusicProtocol(client, activeServer.serverUrl, activeServer.username, activeServer.tokenOrApiKey)
                                                proto.parseExternalPlaylist(urlOrId = url, source = src.key).getOrNull() ?: emptyList()
                                            },
                                            onFetchCollectionSongs = { collectionId, src ->
                                                val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                val proto = LemonMusicProtocol(client, activeServer.serverUrl, activeServer.username, activeServer.tokenOrApiKey)
                                                if (collectionId.startsWith("lemon_toplist_")) {
                                                    val rawId = collectionId.substringAfterLast('_')
                                                    proto.getDiscoverToplistSongs(toplistId = rawId, source = src.key).getOrNull() ?: emptyList()
                                                } else {
                                                    proto.getPlaylistSongs(collectionId).getOrNull() ?: emptyList()
                                                }
                                            },
                                            onSubViewActiveChange = { isChildSubViewActive = it },
                                            contentPadding = innerPadding
                                        )
                                    } else {
                                        LocalMusicHomeScreen(
                                            serverName = activeServerName,
                                            recentSongs = songList,
                                            configuredServers = serversList,
                                            blurAlpha = blurAlpha,
                                            recentlyAddedSongs = recentlyAddedSongs,
                                            recentlyPlayedSongs = recentlyPlayedSongs,
                                            discoverPlaylists = playlistsList.filter { it.isDiscover || it.id.startsWith("discover_") },
                                            homeDisplayConfig = homeDisplayConfig,
                                            activeDownloadTasks = activeDownloadTasks,
                                            activeDownloadCount = activeDownloadTasks.size,
                                            currentPlayingSong = currentSong,
                                            isPlaying = isPlaying,
                                            locateSongTrigger = locateSongTrigger,
                                            scrollToTopTrigger = scrollToTopTrigger,
                                            onListScrollingChange = { isSongListScrolling = it },
                                            onSongClick = { song, queue -> playSongWithQueue(song, queue) },
                                            onDownloadSong = handleDownloadSong,
                                            onDownloadSongWithOptions = handleDownloadWithOptions,
                                            onBatchDownloadSongsWithOptions = handleBatchDownloadWithOptions,
                                            onSelectLocalServer = {
                                                activeServerName = "本地模式"
                                                navigateToScreen(Screen.HOME)
                                            },
                                            onSelectServer = { selectedServer ->
                                                activeServerName = selectedServer.name
                                                activeServerId = selectedServer.id
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    database.serverDao().setActiveServer(selectedServer.id)
                                                    syncServerSongs(selectedServer)
                                                }
                                            },
                                            onSyncNow = {
                                                val active = serversList.firstOrNull { it.isCurrentActive }
                                                if (active != null) {
                                                    Toast.makeText(this@MainActivity, "正在从柠檬音乐同步曲库...", Toast.LENGTH_SHORT).show()
                                                    syncServerSongs(active)
                                                } else {
                                                    Toast.makeText(this@MainActivity, "当前为本地模式，可前往设置扫描本地文件", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            onOpenDownloads = {
                                                navigateToScreen(Screen.DOWNLOADS)
                                            },
                                            onGoToSettings = {
                                                navigateToScreen(Screen.SETTINGS)
                                            },
                                            onPlaylistClick = {
                                                navigateToScreen(Screen.LIBRARY)
                                            },
                                            onScanLocalMedia = {
                                                navigateToScreen(Screen.SETTINGS)
                                            },
                                            onSubViewActiveChange = { isChildSubViewActive = it },
                                            contentPadding = innerPadding
                                        )
                                    }
                                }

                                Screen.LIBRARY -> {
                                    val isLocalMode = activeServerName.contains("本地") || activeServerName.contains("已下载")
                                    val activeConfig = if (isLocalMode) null else serversList.firstOrNull { it.isCurrentActive }
                                    val librarySongs = remember(songList, completedDownloadedSongs, activeConfig, isLocalMode) {
                                        if (isLocalMode || activeConfig == null) {
                                            // 本地·离线曲库：只显示手机上**真实存在物理文件**的歌曲。
                                            // 不能用 downloadStatus==DOWNLOADED 或 localFilePath 非空判断 ——
                                            // 从 NAS 同步下来的云端歌曲也被标了 DOWNLOADED、localFilePath=/vol1/...，
                                            // 但那些路径在手机上不存在，旧逻辑因此把 2000 多首云端歌误显示进本地库。
                                            // completedDownloadedSongs 已在 IO 线程做过 content:// 与 File.exists() 校验。
                                            completedDownloadedSongs
                                        } else {
                                            // 在线模式：展示属于当前服务器的真实曲目**以及所有已收藏曲目**。
                                            // 「我喜欢的音乐」是跨服务器的用户资产：收藏一首别的服务器的曲目后，
                                            // 若这里只按 serverId 过滤，它会被整条丢弃，喜欢列表里永远见不到它。
                                            songList.filter {
                                                it.serverId == activeConfig.id ||
                                                it.serverId == activeConfig.serverUrl ||
                                                it.isFavorite ||
                                                (activeConfig.type == ServerType.LEMON_MUSIC && (it.serverId == "lemon_music" || it.serverId == activeConfig.id))
                                            }
                                        }
                                    }
                                    // 「我喜欢的音乐」的数据口径：
                                    //   在线模式 —— 直接以柠檬服务器收藏 (/api/library/user-data 的 favorites) 为准，
                                    //               能对上库内曲目的用库内数据（带本地路径/封面），其余沿用服务器记录；
                                    //   本地/已下载 —— 从服务器收藏中枚举出"已下载"的曲目，没下载的不显示。
                                    // 「我喜欢的音乐」卡片数据源统一采用服务端收藏 (/api/library/user-data 的 favorites)，
                                    // 与「我的收藏」歌单点进去的 getPlaylistSongs("lemon_favorites") 完全一致，
                                    // 避免用 librarySongs 同 ID 覆盖导致下载状态错乱。
                                    // 拉不到服务器收藏（列表为空，例如断网）时回落到本地 isFavorite 标记。
                                    val favoriteSongs = remember(
                                        serverFavoriteSongs, librarySongs, completedDownloadedSongs, isLocalMode
                                    ) {
                                        when {
                                            serverFavoriteSongs.isEmpty() -> librarySongs.filter { it.isFavorite }
                                            !isLocalMode -> serverFavoriteSongs
                                            else -> serverFavoriteSongs.mapNotNull { fav ->
                                                completedDownloadedSongs.firstOrNull { local ->
                                                    local.id == fav.id || SongMatchingResolver.isSongMatch(
                                                        title1 = local.title,
                                                        artist1 = local.artist,
                                                        durationMs1 = local.durationMs,
                                                        title2 = fav.title,
                                                        artist2 = fav.artist,
                                                        durationMs2 = fav.durationMs,
                                                        album1 = local.album,
                                                        album2 = fav.album
                                                    )
                                                }
                                            }.distinctBy { it.id }
                                        }
                                    }
                                    LibraryPullRefresh(
                                        isRefreshing = isRefreshingLibrary,
                                        onRefresh = {
                                            if (!isRefreshingLibrary) {
                                                isRefreshingLibrary = true
                                                triggerLibraryRefresh(manual = true)
                                            }
                                        },
                                        topPadding = innerPadding.calculateTopPadding()
                                    ) {
                                    LocalLibraryScreen(
                                        allSongs = librarySongs,
                                        favoriteSongs = favoriteSongs,
                                        downloadedSongs = completedDownloadedSongs,
                                        recentlyPlayedSongs = recentlyPlayedSongs,
                                        recentlyAddedSongs = if (isLocalMode) emptyList() else recentlyAddedSongs,
                                        serverAlbums = if (isLocalMode) emptyList() else serverAlbums,
                                        playlists = if (isLocalMode) {
                                            playlistsList.filter { !it.isDiscover && !it.id.startsWith("discover_") && !it.id.startsWith("lemon_rec_") }
                                        } else {
                                            playlistsList.filter {
                                                !it.isDiscover && !it.id.startsWith("discover_") && !it.id.startsWith("lemon_rec_") &&
                                                (it.serverId == activeConfig?.id || it.serverId == "lemon_music" || it.serverId.startsWith("lemon_") || it.serverId == activeConfig?.serverUrl || !it.isOnline)
                                            }
                                        },
                                        activeServerConfig = activeConfig,
                                        activeDownloadTasks = activeDownloadTasks,
                                        activeDownloadCount = activeDownloadTasks.size,
                                        currentServerName = activeServerName,
                                        configuredServers = serversList,
                                        blurAlpha = blurAlpha,
                                        currentPlayingSong = currentSong,
                                        isPlaying = isPlaying,
                                        locateSongTrigger = locateSongTrigger,
                                        scrollToTopTrigger = scrollToTopTrigger,
                                        onListScrollingChange = { isSongListScrolling = it },
                                        onSelectLocalServer = {
                                            activeServerName = "本地模式"
                                        },
                                        onSelectServer = { selectedServer ->
                                            activeServerName = selectedServer.name
                                            activeServerId = selectedServer.id
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                database.serverDao().setActiveServer(selectedServer.id)
                                                syncServerPlaylists(selectedServer)
                                                syncServerSongs(selectedServer)
                                            }
                                        },
                                        onSyncNow = {
                                            val active = serversList.firstOrNull { it.isCurrentActive }
                                            if (active != null) {
                                                Toast.makeText(this@MainActivity, "正在从柠檬音乐同步曲库与收藏...", Toast.LENGTH_SHORT).show()
                                                syncServerPlaylists(active)
                                                syncServerSongs(active)
                                                refreshServerFavorites()
                                            } else {
                                                Toast.makeText(this@MainActivity, "当前为本地模式，可前往设置扫描本地文件", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onGoToSettings = { navigateToScreen(Screen.SETTINGS) },
                                        onSongClick = { song, queue -> playSongWithQueue(song, queue) },
                                        onDownloadSong = handleDownloadSong,
                                        onDownloadSongWithOptions = handleDownloadWithOptions,
                                        onBatchDownloadSongsWithOptions = handleBatchDownloadWithOptions,
                                        onDeleteDownloadedSongs = { songsToDelete ->
                                            val deletedIds = songsToDelete.map { it.id }.toSet()
                                            downloadEngine.deleteDownloadedSongs(songsToDelete)
                                            songList = songList.map { if (it.id in deletedIds) it.copy(localFilePath = null, downloadStatus = DownloadStatus.NOT_DOWNLOADED) else it }
                                        },
                                        onToggleFavorite = handleToggleFavorite,
                                        onOpenDownloads = { navigateToScreen(Screen.DOWNLOADS) },
                                        onRefreshPlaylists = {
                                            if (activeConfig != null) {
                                                Toast.makeText(this@MainActivity, "正在同步在线播放列表与收藏...", Toast.LENGTH_SHORT).show()
                                                syncServerPlaylists(activeConfig)
                                                refreshServerFavorites()
                                            } else {
                                                Toast.makeText(this@MainActivity, "当前处于本地模式，暂无在线歌单", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onCreatePlaylist = { name, isOnline ->
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                var finalId = "pl_${System.currentTimeMillis()}"
                                                var finalIsOnline = isOnline && activeConfig != null
                                                if (finalIsOnline && activeConfig != null) {
                                                    try {
                                                        val protocol = LemonMusicProtocol(
                                                            NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                                            activeConfig.serverUrl,
                                                            activeConfig.username,
                                                            activeConfig.tokenOrApiKey
                                                        )
                                                        val res = protocol.createCustomPlaylist(name)
                                                        if (res.isSuccess) {
                                                            val pl = res.getOrNull()
                                                            if (pl != null) {
                                                                finalId = pl.id
                                                                finalIsOnline = true
                                                            }
                                                        } else {
                                                            finalIsOnline = false
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.w("MainActivity", "Create server playlist failed", e)
                                                        finalIsOnline = false
                                                    }
                                                }
                                                database.playlistDao().insertPlaylist(
                                                    com.lm.player.core.database.entity.PlaylistEntity(
                                                        id = finalId,
                                                        name = name,
                                                        serverId = if (finalIsOnline && activeConfig != null) activeConfig.id else "local_storage",
                                                        isOnline = finalIsOnline,
                                                        songCount = 0
                                                    )
                                                )
                                                if (finalIsOnline && activeConfig != null) {
                                                    syncServerPlaylists(activeConfig)
                                                }
                                            }
                                        },
                                        onDeletePlaylist = { playlistId ->
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                if (activeConfig != null) {
                                                    try {
                                                        val protocol = LemonMusicProtocol(
                                                            NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                                            activeConfig.serverUrl,
                                                            activeConfig.username,
                                                            activeConfig.tokenOrApiKey
                                                        )
                                                        protocol.deleteCustomPlaylist(playlistId)
                                                    } catch (e: Exception) {
                                                        Log.w("MainActivity", "Delete server playlist failed", e)
                                                    }
                                                }
                                                database.playlistDao().deletePlaylist(playlistId)
                                                if (activeConfig != null) {
                                                    syncServerPlaylists(activeConfig)
                                                }
                                            }
                                        },
                                        onFetchPlaylistSongs = { playlistId, isOnline ->
                                            // 「我的收藏」与资料库收藏卡片共用同一份数据源（serverFavoriteSongs），
                                            // 保证两个入口看到的曲目与下载状态完全一致；拿不到时回退网络拉取。
                                            val rawList = if (playlistId == "lemon_favorites" && isOnline && serverFavoriteSongs.isNotEmpty()) {
                                                serverFavoriteSongs
                                            } else if (isOnline && activeConfig != null) {
                                                val protocol = LemonMusicProtocol(NetworkClientFactory.createOkHttpClient(this@MainActivity), activeConfig.serverUrl, activeConfig.username, activeConfig.tokenOrApiKey)
                                                val fetched = protocol.getPlaylistSongs(playlistId).getOrNull() ?: emptyList()
                                                if (fetched.isNotEmpty()) {
                                                    lifecycleScope.launch(Dispatchers.IO) {
                                                        try {
                                                            database.playlistDao().getPlaylistById(playlistId)?.let { entity ->
                                                                if (entity.songCount != fetched.size) {
                                                                    database.playlistDao().insertPlaylist(entity.copy(songCount = fetched.size))
                                                                }
                                                            }
                                                        } catch (_: Exception) { }
                                                    }
                                                }
                                                fetched
                                            } else {
                                                database.playlistDao().getSongsForPlaylist(playlistId).map { entity ->
                                                    UnifiedSong(
                                                        id = entity.id,
                                                        title = entity.title,
                                                        artist = entity.artist,
                                                        artistId = entity.artistId,
                                                        album = entity.album,
                                                        albumId = entity.albumId,
                                                        durationMs = entity.durationMs,
                                                        coverUrl = entity.coverUrl,
                                                        streamUrl = entity.streamUrl,
                                                        serverId = entity.serverId,
                                                        localFilePath = entity.localFilePath,
                                                        downloadStatus = entity.downloadStatus,
                                                        bitRate = entity.bitRate,
                                                        format = entity.format,
                                                        isFavorite = entity.isFavorite,
                                                        relativeFolderPath = entity.relativeFolderPath
                                                    )
                                                }
                                            }
                                            // 「我的收藏」是跨服务器的用户资产：把本地已收藏的曲目一并并入，
                                            // 避免服务端往返尚未反映时，刚点下的「喜欢」在收藏歌单里看不见
                                            val mergedList = if (playlistId == "lemon_favorites") {
                                                // 本地/已下载模式下的收藏口径同样是「服务器收藏 ∩ 已下载」：
                                                // 没下载的服务器收藏不显示，避免与资料库收藏卡片两处口径打架。
                                                val favExtras = if (isLocalMode) {
                                                    if (serverFavoriteSongs.isEmpty()) {
                                                        completedDownloadedSongs.filter { it.isFavorite }
                                                    } else {
                                                        completedDownloadedSongs.filter { local ->
                                                            serverFavoriteSongs.any { fav ->
                                                                fav.id == local.id || SongMatchingResolver.isSongMatch(
                                                                    title1 = local.title,
                                                                    artist1 = local.artist,
                                                                    durationMs1 = local.durationMs,
                                                                    title2 = fav.title,
                                                                    artist2 = fav.artist,
                                                                    durationMs2 = fav.durationMs,
                                                                    album1 = local.album,
                                                                    album2 = fav.album
                                                                )
                                                            }
                                                        }
                                                    }
                                                } else songList.filter { it.isFavorite }
                                                // 先按 id 去重，再按「标题|歌手」去重：
                                                // 下载到 NAS 后歌曲 id 会从 lemon_online_xxx 变为 lemon_md5(path)，
                                                // 导致同一首歌在 rawList（服务端收藏）与 favExtras（本地收藏）里 id 不同，
                                                // 仅 distinctBy id 会残留两份（如「心墙」同时出现在线版与本地版）。
                                                // 按标题+歌手去重时优先保留已下载（downloadStatus==DOWNLOADED）的版本。
                                                val idDeduped = (rawList + favExtras).distinctBy { it.id }
                                                val titleDedupMap = LinkedHashMap<String, UnifiedSong>()
                                                for (song in idDeduped) {
                                                    val key = "${song.title.trim().lowercase()}|${song.artist.trim().lowercase()}"
                                                    val existing = titleDedupMap[key]
                                                    if (existing == null) {
                                                        titleDedupMap[key] = song
                                                    } else {
                                                        val existingDownloaded = existing.downloadStatus == DownloadStatus.DOWNLOADED
                                                        val newDownloaded = song.downloadStatus == DownloadStatus.DOWNLOADED
                                                        if (newDownloaded && !existingDownloaded) {
                                                            titleDedupMap[key] = song
                                                        }
                                                    }
                                                }
                                                titleDedupMap.values.toList()
                                            } else rawList

                                            // 全局统一匹配：将歌单曲目与本地缓存/下载物理文件即时比对并挂载
                                            SongMatchingResolver.resolveSongList(
                                                incomingSongs = mergedList,
                                                allCachedSongs = songList,
                                                activeTasks = activeDownloadTasks,
                                                downloadDir = downloadEngine.getDownloadDir()
                                            )
                                        },
                                        onFetchServerFolders = { parentId ->
                                            if (activeConfig != null) {
                                                val protocol = LemonMusicProtocol(NetworkClientFactory.createOkHttpClient(this@MainActivity), activeConfig.serverUrl, activeConfig.username, activeConfig.tokenOrApiKey)
                                                protocol.getFolders(parentId).getOrNull() ?: emptyList()
                                            } else {
                                                emptyList()
                                            }
                                        },
                                        onFetchServerFolderSongs = { folderId ->
                                            val rawFolderSongs = if (activeConfig != null) {
                                                val protocol = LemonMusicProtocol(NetworkClientFactory.createOkHttpClient(this@MainActivity), activeConfig.serverUrl, activeConfig.username, activeConfig.tokenOrApiKey)
                                                protocol.getFolderSongs(folderId).getOrNull() ?: emptyList()
                                            } else {
                                                emptyList()
                                            }
                                            // 全局统一匹配：将服务器文件夹内的曲目与本地缓存即时比对并挂载
                                            SongMatchingResolver.resolveSongList(
                                                incomingSongs = rawFolderSongs,
                                                allCachedSongs = songList,
                                                activeTasks = activeDownloadTasks,
                                                downloadDir = downloadEngine.getDownloadDir()
                                            )
                                        },
                                        onDeleteLocalFilePath = { path ->
                                            LocalMediaScanner.deleteLocalAudioFile(database, path)
                                        },
                                        onFetchServerGenres = {
                                            if (activeConfig != null) {
                                                val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                val proto = LemonMusicProtocol(client, activeConfig.serverUrl, activeConfig.username, activeConfig.tokenOrApiKey)
                                                proto.getGenres().getOrNull() ?: emptyList()
                                            } else {
                                                emptyList()
                                            }
                                        },
                                        onFetchServerScanStatus = {
                                            if (activeConfig != null) {
                                                val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                val proto = LemonMusicProtocol(client, activeConfig.serverUrl, activeConfig.username, activeConfig.tokenOrApiKey)
                                                proto.getServerScanStatus().getOrNull()
                                            } else {
                                                null
                                            }
                                        },
                                        onTriggerServerScan = {
                                            if (activeConfig != null) {
                                                val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                                val proto = LemonMusicProtocol(client, activeConfig.serverUrl, activeConfig.username, activeConfig.tokenOrApiKey)
                                                proto.triggerServerScan()
                                                syncServerSongs(activeConfig)
                                            }
                                        },
                                        onSubViewActiveChange = { isChildSubViewActive = it },
                                        contentPadding = innerPadding
                                        )
                                    }
                                }

                                Screen.DOWNLOADS -> {
                                    DownloadManagerScreen(
                                        activeTasks = activeDownloadTasks,
                                        completedSongs = completedDownloadedSongs,
                                        serverTasks = serverDownloadTasks,
                                        onSongClick = { song, queue -> playSongWithQueue(song, queue) },
                                        onCancelTask = { downloadEngine.cancelTask(it) },
                                        onPauseTask = { downloadEngine.pauseTask(it) },
                                        onResumeTask = { downloadEngine.resumeTask(it) },
                                        onPauseTasks = { downloadEngine.pauseTasks(it) },
                                        onResumeTasks = { downloadEngine.resumeTasks(it) },
                                        onCancelTasks = { downloadEngine.cancelTasks(it) },
                                        onPauseAll = { downloadEngine.pauseAll() },
                                        onResumeAll = { downloadEngine.resumeAll() },
                                        onDeleteDownloadedSong = { songToDelete ->
                                            val deletedIds = setOf(songToDelete.id)
                                            downloadEngine.deleteDownloadedSong(songToDelete)
                                            songList = songList.map { if (it.id in deletedIds) it.copy(localFilePath = null, downloadStatus = DownloadStatus.NOT_DOWNLOADED) else it }
                                        },
                                        onDeleteDownloadedSongs = { songsToDelete ->
                                            val deletedIds = songsToDelete.map { it.id }.toSet()
                                            downloadEngine.deleteDownloadedSongs(songsToDelete)
                                            songList = songList.map { if (it.id in deletedIds) it.copy(localFilePath = null, downloadStatus = DownloadStatus.NOT_DOWNLOADED) else it }
                                        },
                                        onReEmbedSong = { songToFix ->
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                val ok = downloadEngine.reEmbedSongMetadata(songToFix)
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(this@MainActivity, if (ok) "已成功为《${songToFix.title}》重新嵌入封面与歌词" else "重新嵌入失败，文件未找到", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        onReEmbedAll = {
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(this@MainActivity, "正在批量为所有已下载歌曲补全封面与歌词标签...", Toast.LENGTH_SHORT).show()
                                                }
                                                val (success, fail) = downloadEngine.reEmbedAllDownloadedSongs()
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(this@MainActivity, "标签补全完成：成功 $success 首，失败 $fail 首", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        downloadPath = downloadSettings.customDownloadPath.ifBlank { getExternalFilesDir(null)?.absolutePath ?: "" },
                                        onBack = { popScreenOrHome() },
                                        contentPadding = innerPadding,
                                        onPlayServerTask = { task ->
                                            val path = task.serverFilePath
                                            if (path.isNullOrBlank()) {
                                                Toast.makeText(this@MainActivity, "该任务暂无可用文件路径", Toast.LENGTH_SHORT).show()
                                                return@DownloadManagerScreen
                                            }
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                try {
                                                    val srv = database.serverDao().getAllServers()
                                                        .firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                                                    if (srv == null) {
                                                        withContext(Dispatchers.Main) {
                                                            Toast.makeText(this@MainActivity, "未连接柠檬音乐服务器", Toast.LENGTH_SHORT).show()
                                                        }
                                                        return@launch
                                                    }
                                                    val proto = LemonMusicProtocol(
                                                        NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                                        srv.serverUrl, srv.username, srv.tokenOrApiKey
                                                    )
                                                    val streamUrl = proto.getStreamUrlForPath(path)
                                                    val playSong = task.song.copy(streamUrl = streamUrl)
                                                    withContext(Dispatchers.Main) {
                                                        playSongWithQueue(playSong, listOf(playSong))
                                                    }
                                                } catch (e: Exception) {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(this@MainActivity, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        },
                                        onDeleteServerTask = { task ->
                                            val rawId = task.song.id.removePrefix("svrdl_")
                                            val filePath = task.serverFilePath?.trim().orEmpty()
                                            // 先从本地列表移除，提升响应速度；服务端删除失败时轮询会自动补回
                                            serverDownloadTasks = serverDownloadTasks.filter { it.song.id != task.song.id }
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                val srv = database.serverDao().getAllServers()
                                                    .firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                                                if (srv == null) {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(this@MainActivity, "已从列表移除", Toast.LENGTH_SHORT).show()
                                                    }
                                                    return@launch
                                                }
                                                val proto = LemonMusicProtocol(
                                                    NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                                    srv.serverUrl, srv.username, srv.tokenOrApiKey
                                                )

                                                // 1) 先删 NAS 物理文件 (POST /library/delete-files，与网页端删除一致)。
                                                //    这是此前「任务删了歌还在」的根因：DELETE /api/download/{id} 只删任务记录，不碰文件。
                                                var fileDeleted = false
                                                var fileError: String? = null
                                                if (filePath.isNotBlank()) {
                                                    val fileResult = proto.deleteServerLibraryFiles(listOf(filePath))
                                                    fileDeleted = fileResult.isSuccess
                                                    if (!fileDeleted) {
                                                        fileError = fileResult.exceptionOrNull()?.message
                                                        Log.w("MainActivity", "Delete server library file failed: $filePath", fileResult.exceptionOrNull())
                                                    }
                                                }

                                                // 2) 再删下载任务记录
                                                val taskResult = proto.deleteServerDownloadTask(rawId, alsoDeleteFile = true)

                                                // 2.5) 清理服务端用户数据中的幽灵引用（收藏/最近播放/自建歌单），
                                                //      否则刷新同步时「我喜欢的音乐」会把已删曲目重新拉回
                                                if (fileDeleted) {
                                                    runCatching { proto.removeDeletedFilesFromUserData(setOf(filePath)) }
                                                        .onFailure { Log.w("MainActivity", "Cleanup server user-data failed: ${it.message}") }
                                                }

                                                // 3) 文件删除成功后立即级联更新本机所有关联状态（不等下一次同步）：
                                                //    Room 曲目行、歌单关联、下载索引、内存中的曲库/最近添加/收藏列表、播放队列与当前播放。
                                                if (fileDeleted) {
                                                    val librarySongId = "lemon_${LemonMusicProtocol.md5(filePath)}"
                                                    val matchedEntity = database.songDao().getSongById(librarySongId)
                                                        ?: database.songDao().getSongByLocalPath(filePath)
                                                    val removedIds = listOfNotNull(
                                                        librarySongId,
                                                        matchedEntity?.id
                                                    ).distinct()
                                                    for (sid in removedIds) {
                                                        database.playlistDao().removeSongFromAllPlaylists(sid)
                                                        database.downloadDao().deleteDownload(sid)
                                                        database.songDao().deleteSongById(sid)
                                                    }
                                                    // 兜底：按标题+歌手精确匹配，清除同一首歌的其他服务器 ID 残留
                                                    val allSongs = database.songDao().getAllSongsList()
                                                    val matchedByMeta = allSongs.filter {
                                                        it.title.equals(task.song.title, true) &&
                                                        it.artist.equals(task.song.artist, true) &&
                                                        (it.serverId.startsWith("srv_") || it.serverId == "lemon_music" || it.id.startsWith("lemon_"))
                                                    }
                                                    for (m in matchedByMeta) {
                                                        database.playlistDao().removeSongFromAllPlaylists(m.id)
                                                        database.downloadDao().deleteDownload(m.id)
                                                        database.songDao().deleteSongById(m.id)
                                                    }
                                                    val removedIdSet = (removedIds + matchedByMeta.map { it.id }).toSet()
                                                    withContext(Dispatchers.Main) {
                                                        songList = songList.filter { it.id !in removedIdSet && it.localFilePath != filePath }
                                                        recentlyAddedSongs = recentlyAddedSongs.filter { it.id !in removedIdSet && it.localFilePath != filePath }
                                                        serverFavoriteSongs = serverFavoriteSongs.filter { it.id !in removedIdSet && it.localFilePath != filePath }
                                                        PlaybackQueueManager.onServerSongsPermanentlyDeleted(removedIdSet, this@MainActivity)
                                                    }
                                                }

                                                // 4) 先给用户即时反馈，再在后台做服务端重扫与全量对账
                                                withContext(Dispatchers.Main) {
                                                    val msg = when {
                                                        fileDeleted && taskResult.isSuccess ->
                                                            "已删除音乐文件并移除下载任务"
                                                        filePath.isNotBlank() && !fileDeleted && taskResult.isSuccess ->
                                                            "下载任务已移除，但音乐文件删除失败：${fileError ?: "未知错误"}"
                                                        taskResult.isSuccess ->
                                                            "已从服务器移除下载任务"
                                                        else ->
                                                            "服务端删除失败（已仅从列表移除）: ${taskResult.exceptionOrNull()?.message}"
                                                    }
                                                    Toast.makeText(this@MainActivity, msg, if (taskResult.isSuccess) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                                                }

                                                // 5) 后台异步：触发服务端重新扫描 + 静默全量对账，
                                                //    确保云端曲库 / 歌单 / 收藏刷新后全部反映删除（差量清理会兜底移除本地僵尸曲目）
                                                if (fileDeleted) {
                                                    lifecycleScope.launch(Dispatchers.IO) {
                                                        try {
                                                            proto.triggerServerScan()
                                                            val cfg = ServerConfig(
                                                                id = srv.id,
                                                                name = srv.name,
                                                                type = srv.type,
                                                                serverUrl = srv.serverUrl,
                                                                username = srv.username,
                                                                tokenOrApiKey = srv.tokenOrApiKey,
                                                                saltOrSecret = srv.saltOrSecret,
                                                                syncMode = srv.syncMode,
                                                                isCurrentActive = srv.isCurrentActive
                                                            )
                                                            delay(2500L)
                                                            syncServerSongsWithToast(cfg, false) {
                                                                favoritesRefreshTrigger++
                                                            }
                                                        } catch (e: Exception) {
                                                            Log.w("MainActivity", "Post-delete server sync failed", e)
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        onShowServerTaskDetails = { task ->
                                            val info = buildString {
                                                appendLine("标题: ${task.song.title}")
                                                appendLine("歌手: ${task.song.artist}")
                                                appendLine("专辑: ${task.song.album}")
                                                appendLine("状态: ${task.status}")
                                                appendLine("文件路径: ${task.serverFilePath ?: "（无）"}")
                                                appendLine("格式: ${task.song.format}")
                                                if (task.totalBytes > 0) {
                                                    val mb = task.totalBytes / (1024.0 * 1024.0)
                                                    appendLine(String.format(java.util.Locale.getDefault(), "大小: %.2f MB", mb))
                                                }
                                            }
                                            android.app.AlertDialog.Builder(this@MainActivity)
                                                .setTitle("下载任务详情")
                                                .setMessage(info)
                                                .setPositiveButton("确定", null)
                                                .show()
                                        }
                                    )
                                }

                                Screen.SETTINGS -> {
                                    SettingsScreen(
                                        servers = serversList,
                                        activeServerId = activeServerId,
                                        downloadSettings = downloadSettings,
                                        onDownloadSettingsChange = { newSettings ->
                                            val oldPath = downloadSettings.customDownloadPath
                                            val pathChanged = newSettings.customDownloadPath != oldPath && newSettings.customDownloadPath.isNotBlank()
                                            downloadEngine.updateSettings(newSettings)

                                            if (pathChanged) {
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(this@MainActivity, "正在扫描离线目录并与线上曲库比对同步...", Toast.LENGTH_SHORT).show()
                                                    }
                                                    // 1. 递归扫描该离线目录中的所有音频文件
                                                    val scanned = LocalMediaScanner.scanCustomDirectory(this@MainActivity, newSettings.customDownloadPath, database)
                                                    // 2. 与线上曲库进行物理文件真实性对比与同步
                                                    val updated = LocalMediaScanner.verifyAndSyncAllServerSongDownloadStatus(database, java.io.File(newSettings.customDownloadPath))
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(this@MainActivity, "离线目录同步完成：扫描 $scanned 首，匹配同步 $updated 首", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            }
                                        },
                                        homeDisplayConfig = homeDisplayConfig,
                                        onHomeDisplayConfigChange = { newConfig ->
                                            homeDisplayConfig = newConfig
                                            uiPrefs.edit()
                                                .putBoolean("home_show_recently_played", newConfig.showRecentlyPlayed)
                                                .putBoolean("home_show_recently_added", newConfig.showRecentlyAdded)
                                                .putBoolean("home_show_albums", newConfig.showAlbums)
                                                .putBoolean("home_show_artists", newConfig.showArtists)
                                                .putBoolean("home_show_favorites", newConfig.showFavorites)
                                                .apply()
                                        },
                                        currentScaleMode = currentScaleMode,
                                        onScaleModeChange = { newMode ->
                                            currentScaleMode = newMode
                                            uiPrefs.edit().putString("ui_scale_mode", newMode.name).apply()
                                        },
                                        themeMode = currentThemeMode,
                                        onThemeModeChange = { newThemeMode ->
                                            currentThemeMode = newThemeMode
                                            uiPrefs.edit().putString("app_theme_mode", newThemeMode.name).apply()
                                        },
                                        blurAlpha = blurAlpha,
                                        onBlurAlphaChange = { newAlpha ->
                                            blurAlpha = newAlpha
                                            uiPrefs.edit().putFloat("ui_blur_alpha", newAlpha).apply()
                                        },
                                        enableBottomBarAnimation = enableBottomBarAnimation,
                                        onEnableBottomBarAnimationChange = { isEnabled ->
                                            enableBottomBarAnimation = isEnabled
                                            uiPrefs.edit().putBoolean("enable_bottom_bar_anim", isEnabled).apply()
                                        },
                                        autoPlayOnStartup = autoPlayOnStartup,
                                        onAutoPlayOnStartupChange = { isAuto ->
                                            autoPlayOnStartup = isAuto
                                            autoPlayPrefs.edit().putBoolean("auto_play_on_startup", isAuto).apply()
                                        },
                                        autoFallbackToLocal = autoFallbackToLocal,
                                        onAutoFallbackToLocalChange = { isFallback ->
                                            autoFallbackToLocal = isFallback
                                            autoPlayPrefs.edit().putBoolean("auto_fallback_to_local", isFallback).apply()
                                        },
                                        autoLaunchOnBoot = autoLaunchOnBoot,
                                        onAutoLaunchOnBootChange = { enabled ->
                                            autoLaunchOnBoot = enabled
                                            com.lm.player.core.media.BootCompletedReceiver.setAutoLaunchOnBootEnabled(this@MainActivity, enabled)
                                            Toast.makeText(
                                                this@MainActivity,
                                                if (enabled) "已开启安卓系统开机自动启动软件" else "已关闭安卓系统开机自动启动软件",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        },
                                        onStreamQualityChanged = {
                                            LemonMusicProtocol.notifyStreamQualityConfigChanged()
                                            val activeSong = currentSong
                                            if (
                                                activeSong != null &&
                                                (activeSong.serverId == "lemon_online" || activeSong.id.startsWith("lemon_online_") || activeSong.streamUrl.contains("/api/play/proxy"))
                                            ) {
                                                val resumePos = (exoPlayer?.currentPosition ?: 0L).coerceAtLeast(0L)
                                                Toast.makeText(this@MainActivity, "试听音质已更新，正在按新音质无缝重载当前歌曲...", Toast.LENGTH_SHORT).show()
                                                PlaybackQueueManager.playSong(
                                                    targetSong = activeSong,
                                                    context = this@MainActivity,
                                                    startPositionMs = resumePos,
                                                    forceRefresh = true
                                                )
                                            }
                                        },
                                        currentOnlineSource = currentOnlineSource,
                                        onOnlineSourceChange = { newSrc ->
                                            currentOnlineSource = newSrc
                                            onlinePrefs.edit().putString("selected_source", newSrc.name).apply()
                                        },
                                        onSelectServer = { server ->
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                database.serverDao().setActiveServer(server.id)
                                                syncServerSongs(server)
                                            }
                                        },
                                        onAddOrUpdateServer = { serverConfig ->
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                database.serverDao().insertServer(
                                                    ServerEntity(
                                                        id = serverConfig.id,
                                                        name = serverConfig.name,
                                                        type = serverConfig.type,
                                                        serverUrl = serverConfig.serverUrl,
                                                        username = serverConfig.username,
                                                        tokenOrApiKey = serverConfig.tokenOrApiKey,
                                                        saltOrSecret = serverConfig.saltOrSecret,
                                                        syncMode = serverConfig.syncMode,
                                                        isCurrentActive = true
                                                    )
                                                )
                                                database.serverDao().setActiveServer(serverConfig.id)
                                                syncServerSongs(serverConfig)
                                            }
                                        },
                                        onDeleteServer = { serverIdToDelete ->
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                database.serverDao().deleteServer(serverIdToDelete)
                                            }
                                        },
                                        onOpenDownloads = {
                                            navigateToScreen(Screen.DOWNLOADS)
                                        },
                                        onLocalScanCompleted = {
                                            // 触发歌曲刷新
                                        },
                                        onChooseDownloadDirectory = {
                                            onChooseDownloadFolderResult = { uri ->
                                                val resolvedPath = DownloadEngine.resolveFilesystemPath(uri.toString())
                                                val updated = downloadEngine.downloadSettings.value.copy(customDownloadPath = resolvedPath)
                                                downloadEngine.updateSettings(updated)
                                                Toast.makeText(this@MainActivity, "已成功设定并保存下载存储目录：$resolvedPath", Toast.LENGTH_SHORT).show()
                                            }
                                            chooseDownloadDirectoryLauncher.launch(null)
                                        },
                                        onImportCustomFolder = {
                                            onImportFolderResult = { uri ->
                                                val resolvedFolder = DownloadEngine.resolveFilesystemPath(uri.toString())
                                                if (resolvedFolder.isNotBlank()) {
                                                    val existingFolders = uiPrefs.getStringSet("local_music_scan_folders", emptySet())?.toSet() ?: emptySet()
                                                    val updatedFolders = existingFolders + resolvedFolder
                                                    uiPrefs.edit().remove("local_music_scan_folders").apply()
                                                    uiPrefs.edit().putStringSet("local_music_scan_folders", updatedFolders).apply()
                                                }
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    val count = LocalMediaScanner.scanDocumentTree(this@MainActivity, uri, database)
                                                    val dlDir = downloadEngine.getDownloadDir()
                                                    val matched = LocalMediaScanner.verifyAndSyncAllServerSongDownloadStatus(database, dlDir)
                                                    LocalMediaScanner.matchAndMergeLocalWithServer(database)
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(this@MainActivity, "扫描完成！成功导入 $count 首本地歌曲，比对匹配 $matched 首服务器歌曲已标为本地已下载", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            }
                                            importFolderLauncher.launch(null)
                                        },
                                        onExitAppCompletely = {
                                            exitAppCompletely()
                                        },
                                        contentPadding = innerPadding
                                    )
                                }
                            }
                            }

                            // 三个主标签页横滑容器
                            HorizontalPager(
                                state = mainPagerState,
                                modifier = Modifier.fillMaxSize(),
                                beyondBoundsPageCount = 1
                            ) { page ->
                                renderScreen(mainTabs[page])
                            }

                            // 下载管理页（非标签页）：覆盖在 Pager 之上，带淡入淡出
                            AnimatedVisibility(
                                visible = currentScreen == Screen.DOWNLOADS,
                                enter = fadeIn(animationSpec = tween(180)),
                                exit = fadeOut(animationSpec = tween(150)),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                renderScreen(Screen.DOWNLOADS)
                            }

                        // 正在播放歌曲定位悬浮按钮（小圆形背景仅显示图标；消除半透明背景与动画期间阴影穿透重叠问题）
                        AnimatedVisibility(
                            visible = isLocateButtonVisible &&
                                isPlaying &&
                                currentSong != null &&
                                !isFullPlayerVisible &&
                                !isSearchDialogOpen &&
                                (currentScreen == Screen.HOME || currentScreen == Screen.LIBRARY),
                            enter = fadeIn(animationSpec = tween(200)) + scaleIn(animationSpec = tween(200), initialScale = 0.85f),
                            exit = fadeOut(animationSpec = tween(220)) + scaleOut(animationSpec = tween(220), targetScale = 0.85f),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(
                                    end = 18.dp,
                                    bottom = innerPadding.calculateBottomPadding() + 14.dp
                                )
                        ) {
                            Surface(
                                onClick = {
                                    if (currentSong == null) return@Surface
                                    // 动作轮换：定位当前播放歌曲 ↔ 回到列表顶部。
                                    // 不再强制切换页面（之前会跳回首页/资料库），只在当前页面内滚动
                                    if (locateNextIsTop) {
                                        scrollToTopTrigger++
                                    } else {
                                        locateSongTrigger++
                                    }
                                    locateNextIsTop = !locateNextIsTop
                                },
                                modifier = Modifier.size(40.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                shadowElevation = 5.dp,
                                tonalElevation = 0.dp,
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.26f))
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (locateNextIsTop) Icons.Default.VerticalAlignTop else Icons.Default.MyLocation,
                                        contentDescription = if (locateNextIsTop) "回到列表顶部" else "定位当前播放歌曲",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        // 弹出式搜索浮窗面板 (悬浮于当前页面之上，底部配合左滑搜索框与 X 退出按钮)
                        AnimatedVisibility(
                            visible = isSearchDialogOpen,
                            enter = fadeIn(animationSpec = tween(220)) + slideInVertically(
                                animationSpec = tween(260, easing = FastOutSlowInEasing),
                                initialOffsetY = { it / 12 }
                            ),
                            exit = fadeOut(animationSpec = tween(180)) + slideOutVertically(
                                animationSpec = tween(220, easing = FastOutSlowInEasing),
                                targetOffsetY = { it / 12 }
                            )
                        ) {
                            val localAlbumsForSearch = remember(songList) {
                                songList.filter { it.album.isNotBlank() }
                                    .groupBy { it.album }
                                    .map { (albumTitle, songs) ->
                                        val first = songs.first()
                                        UnifiedAlbum(
                                            id = first.albumId.ifBlank { "album_${albumTitle.hashCode()}" },
                                            title = albumTitle,
                                            artist = first.artist,
                                            coverUrl = first.coverUrl,
                                            songCount = songs.size
                                        )
                                    }
                            }
                            val onlineSearchCallback: (suspend (String, OnlineMusicSource, Int, Int) -> List<UnifiedSong>)? = remember(activeLemonServerForSearch) {
                                val srv = activeLemonServerForSearch
                                if (srv != null) {
                                    { keyword, source, page, limit ->
                                        val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                        val protocol = LemonMusicProtocol(client, srv.serverUrl, srv.username, srv.tokenOrApiKey)
                                        protocol.searchOnline(keyword, source = source.key, page = page, limit = limit).getOrNull() ?: emptyList()
                                    }
                                } else null
                            }
                            val onlineSearchAlbumsCallback: (suspend (String, OnlineMusicSource) -> List<UnifiedAlbum>)? = remember(activeLemonServerForSearch) {
                                val srv = activeLemonServerForSearch
                                if (srv != null) {
                                    { keyword, source ->
                                        val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                        val protocol = LemonMusicProtocol(client, srv.serverUrl, srv.username, srv.tokenOrApiKey)
                                        protocol.searchOnlineAlbums(keyword, source = source.key).getOrNull() ?: emptyList()
                                    }
                                } else null
                            }
                            val onlineSearchPlaylistsCallback: (suspend (String, OnlineMusicSource) -> List<UnifiedPlaylist>)? = remember(activeLemonServerForSearch) {
                                val srv = activeLemonServerForSearch
                                if (srv != null) {
                                    { keyword, source ->
                                        val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                        val protocol = LemonMusicProtocol(client, srv.serverUrl, srv.username, srv.tokenOrApiKey)
                                        protocol.searchOnlinePlaylists(keyword, source = source.key).getOrNull() ?: emptyList()
                                    }
                                } else null
                            }
                            val fetchCollectionSongsCallback: (suspend (String) -> List<UnifiedSong>) = remember(activeLemonServerForSearch, songList) {
                                { itemId ->
                                    val srv = activeLemonServerForSearch
                                    val localAlbumSongs = if (!itemId.startsWith("lemon_")) {
                                        songList.filter {
                                            it.albumId == itemId ||
                                            "album_${it.album.hashCode()}" == itemId ||
                                            "local_album_${it.album.trim().hashCode()}" == itemId ||
                                            it.album.equals(itemId, ignoreCase = true)
                                        }
                                    } else emptyList()

                                    if (localAlbumSongs.isNotEmpty()) {
                                        localAlbumSongs
                                    } else if (srv != null) {
                                        val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                        val protocol = LemonMusicProtocol(client, srv.serverUrl, srv.username, srv.tokenOrApiKey)
                                        val fetched = protocol.getPlaylistSongs(itemId).getOrNull().orEmpty()
                                        if (fetched.isNotEmpty()) {
                                            fetched
                                        } else {
                                            withContext(Dispatchers.IO) {
                                                database.playlistDao().getSongsForPlaylist(itemId).map { entity ->
                                                    UnifiedSong(
                                                        id = entity.id,
                                                        title = entity.title,
                                                        artist = entity.artist,
                                                        artistId = entity.artistId,
                                                        album = entity.album,
                                                        albumId = entity.albumId,
                                                        durationMs = entity.durationMs,
                                                        coverUrl = entity.coverUrl,
                                                        streamUrl = entity.streamUrl,
                                                        serverId = entity.serverId,
                                                        localFilePath = entity.localFilePath,
                                                        downloadStatus = entity.downloadStatus,
                                                        bitRate = entity.bitRate,
                                                        format = entity.format,
                                                        isFavorite = entity.isFavorite,
                                                        relativeFolderPath = entity.relativeFolderPath
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        withContext(Dispatchers.IO) {
                                            database.playlistDao().getSongsForPlaylist(itemId).map { entity ->
                                                UnifiedSong(
                                                    id = entity.id,
                                                    title = entity.title,
                                                    artist = entity.artist,
                                                    artistId = entity.artistId,
                                                    album = entity.album,
                                                    albumId = entity.albumId,
                                                    durationMs = entity.durationMs,
                                                    coverUrl = entity.coverUrl,
                                                    streamUrl = entity.streamUrl,
                                                    serverId = entity.serverId,
                                                    localFilePath = entity.localFilePath,
                                                    downloadStatus = entity.downloadStatus,
                                                    bitRate = entity.bitRate,
                                                    format = entity.format,
                                                    isFavorite = entity.isFavorite,
                                                    relativeFolderPath = entity.relativeFolderPath
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            val parsePlaylistCallback: (suspend (String, OnlineMusicSource) -> List<UnifiedSong>)? = remember(activeLemonServerForSearch) {
                                val srv = activeLemonServerForSearch
                                if (srv != null) {
                                    { url, source ->
                                        val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                        val protocol = LemonMusicProtocol(client, srv.serverUrl, srv.username, srv.tokenOrApiKey)
                                        protocol.parseExternalPlaylist(urlOrId = url, source = source.key).getOrNull() ?: emptyList()
                                    }
                                } else null
                            }
                            LibrarySearchDialog(
                                allSongs = songList,
                                allAlbums = localAlbumsForSearch,
                                allPlaylists = playlistsList,
                                activeDownloadTasks = activeDownloadTasks,
                                currentPlayingSong = currentSong,
                                isPlaying = isPlaying,
                                query = globalSearchQuery,
                                onQueryChange = { globalSearchQuery = it },
                                selectedSearchType = currentSearchType,
                                onSearchTypeChanged = { currentSearchType = it },
                                onSongClick = { targetSong, queue ->
                                    playSongWithQueue(targetSong, queue)
                                },
                                onDownloadSong = handleDownloadSong,
                                onDownloadSongWithOptions = handleDownloadWithOptions,
                                initialOnlineSource = currentOnlineSource,
                                onOnlineSourceChanged = { newSrc ->
                                    currentOnlineSource = newSrc
                                    onlinePrefs.edit().putString("selected_source", newSrc.name).apply()
                                },
                                onOnlineSearch = onlineSearchCallback,
                                onOnlineSearchAlbums = onlineSearchAlbumsCallback,
                                onOnlineSearchPlaylists = onlineSearchPlaylistsCallback,
                                onFetchCollectionSongs = fetchCollectionSongsCallback,
                                onParseExternalPlaylist = parsePlaylistCallback,
                                isServerConnected = (activeLemonServerForSearch != null),
                                hasMiniPlayer = (currentSong != null),
                                contentPadding = innerPadding,
                                onDismiss = {
                                    isSearchDialogOpen = false
                                    globalSearchQuery = ""
                                }
                            )
                        }
                        }
                    }

                    // 首次开启挂后台手机顶部灵动岛悬浮窗权限引导弹窗 (应用内不显示灵动岛，仅在退到后台时呈现)
                    if (showBgIslandOverlayPrompt && currentSong != null && !DynamicIslandManager.hasOverlayPermission(this@MainActivity)) {
                        BackgroundIslandPermissionDialog(
                            onGrantClick = {
                                showBgIslandOverlayPrompt = false
                                uiPrefs.edit().putBoolean("bg_island_overlay_prompted_v167", true).apply()
                                DynamicIslandManager.requestOverlayPermission(this@MainActivity)
                            },
                            onDismiss = {
                                showBgIslandOverlayPrompt = false
                                uiPrefs.edit().putBoolean("bg_island_overlay_prompted_v167", true).apply()
                            }
                        )
                    }

                    // 全屏现代高保真音乐播放器弹窗 (支持横屏分屏歌词与多主题联动)
                    AnimatedVisibility(
                        visible = isFullPlayerVisible && currentSong != null,
                        enter = slideInVertically(initialOffsetY = { it }),
                        exit = slideOutVertically(targetOffsetY = { it })
                    ) {
                        val activeSong = currentSong
                        if (activeSong != null) {
                            val song = activeSong
                            var localProgressMs by remember { mutableStateOf(exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L) }
                            var localTotalDurationMs by remember { mutableStateOf(exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L) }
                            var prebufferedSongId by remember { mutableStateOf("") }

                            LaunchedEffect(isPlaying, song.id) {
                                while (isActive && isPlaying) {
                                    val pos = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
                                    localProgressMs = pos
                                    val dur = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L
                                    if (dur > 0L) {
                                        localTotalDurationMs = dur
                                        // 智能切歌预热：播放进度达到 85% 且本曲尚未预热时，后台异步预热下一首歌曲解析与歌词
                                        if (pos.toFloat() / dur > 0.85f && prebufferedSongId != song.id && songList.isNotEmpty()) {
                                            prebufferedSongId = song.id
                                            val currentQueueList = currentQueue.ifEmpty { songList }
                                            launch(Dispatchers.Default) {
                                                val nextIndex = (currentQueueList.indexOfFirst { it.id == song.id } + 1).takeIf { it < currentQueueList.size } ?: 0
                                                val nextCandidate = if (isShuffle) (currentQueueList.filter { it.id != song.id }.randomOrNull() ?: song) else currentQueueList[nextIndex]
                                                try {
                                                    val activeServer = serversList.firstOrNull { it.isCurrentActive }
                                                    LyricsManager.loadLyrics(nextCandidate, this@MainActivity, activeServer)
                                                } catch (_: Exception) {}
                                            }
                                        }
                                    }
                                    delay(400)
                                }
                            }

                            var playbackSpeed by remember {
                                mutableStateOf(uiPrefs.getFloat("playback_speed", 1.0f).coerceIn(0.5f, 2.0f))
                            }

                            FullscreenPlayerSheet(
                                song = song,
                                playlist = currentQueue.ifEmpty { songList },
                                isPlaying = isPlaying,
                                progressMs = localProgressMs,
                                totalDurationMs = localTotalDurationMs,
                                lyrics = currentLyrics,
                                isLyricsMode = isLyricsMode,
                                isShuffle = isShuffle,
                                isRepeat = isRepeat,
                                playbackSpeed = playbackSpeed,
                                allPlaylists = playlistsList,
                                activeDownloadTasks = activeDownloadTasks,
                                isServerConnected = (serversList.any { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }),
                                onTogglePlayPause = togglePlayPause,
                                onNext = playNext,
                                onPrevious = playPrevious,
                                onSeekTo = { seekPosition ->
                                    exoPlayer?.seekTo(seekPosition)
                                    localProgressMs = seekPosition
                                    com.lm.player.core.media.AudioSharingManager.onPhoneSeekIfCasting(this@MainActivity, seekPosition)
                                },
                                onSelectSongFromQueue = { queueSong ->
                                    playSongWithQueue(queueSong, currentQueue.ifEmpty { songList })
                                },
                                onToggleLyricsMode = { isLyricsMode = it },
                                onToggleFavorite = { handleToggleFavorite(song) },
                                onToggleShuffle = { PlaybackQueueManager.setShuffle(!isShuffle) },
                                onToggleRepeat = { PlaybackQueueManager.setRepeat(!isRepeat) },
                                onChangePlaybackSpeed = { speed ->
                                    playbackSpeed = speed
                                    uiPrefs.edit().putFloat("playback_speed", speed).apply()
                                    exoPlayer?.playbackParameters = androidx.media3.common.PlaybackParameters(speed)
                                },
                                onDownloadSong = handleDownloadSong,
                                onDownloadSongWithOptions = handleDownloadWithOptions,
                                onAddToPlaylist = handleAddToPlaylist,
                                onCreatePlaylistAndAddSong = handleCreatePlaylistAndAddSong,
                                onDismiss = { isFullPlayerVisible = false }
                            )
                        }
                    }

                    // 启动 Logo 过渡动画图层 (开屏丝滑淡出)
                    SplashScreenView(
                        visible = isSplashVisible,
                        onSplashFinished = { isSplashVisible = false }
                    )

                    // 启动后 10 秒检测到新版本的全屏弹窗 (支持滑动说明与 3 选交互)
                    if (showStartupUpdateDialog && startupUpdateInfo != null) {
                        val info = startupUpdateInfo!!
                        AppUpdateDialog(
                            updateInfo = info,
                            isDownloading = isDownloadingStartupApk,
                            downloadProgress = downloadStartupProgress,
                            isDownloaded = downloadedStartupApkFile != null && downloadedStartupApkFile!!.exists(),
                            onDismiss = { showStartupUpdateDialog = false },
                            onNeverUpdate = {
                                AppUpdateManager.setSkipVersion(this@MainActivity, info.latestVersion)
                                Toast.makeText(this@MainActivity, "已记录，不再提示 v${info.latestVersion} 更新", Toast.LENGTH_SHORT).show()
                                showStartupUpdateDialog = false
                            },
                            onStartDownload = {
                                if (info.downloadUrl.isNotBlank() && !isDownloadingStartupApk) {
                                    isDownloadingStartupApk = true
                                    downloadStartupProgress = 0f
                                    lifecycleScope.launch {
                                        AppUpdateManager.downloadApk(
                                            context = this@MainActivity,
                                            downloadUrl = info.downloadUrl,
                                            onProgress = { progress, _, _ -> downloadStartupProgress = progress }
                                        ).onSuccess { apkFile ->
                                            isDownloadingStartupApk = false
                                            downloadedStartupApkFile = apkFile
                                            Toast.makeText(this@MainActivity, "安装包下载完成，正在调起安装...", Toast.LENGTH_SHORT).show()
                                            AppUpdateManager.installApk(this@MainActivity, apkFile)
                                        }.onFailure { error ->
                                            isDownloadingStartupApk = false
                                            Toast.makeText(this@MainActivity, "下载更新失败: ${error.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            onInstall = {
                                downloadedStartupApkFile?.let { apkFile ->
                                    AppUpdateManager.installApk(this@MainActivity, apkFile)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

    private var isUserExplicitExit = false

    /**
     * 彻底关闭程序与播放服务（仅在用户主动触发退出时调用）
     */
    private fun exitAppCompletely() {
        isUserExplicitExit = true
        try {
            val currentPos = exoPlayer?.currentPosition?.takeIf { it > 0L }
            PlaybackQueueManager.savePlaybackState(this, positionMs = currentPos, commitSync = true)
            // 进程马上就要被销毁，异步写盘的最近播放足迹有丢失风险，这里强制同步落盘
            PlaybackQueueManager.flushRecentPlayed(this)
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            PlaybackService.stopServiceAndPlayback(this)
            finishAffinity()
            finishAndRemoveTask()
        } catch (e: Exception) {
            finish()
        }
    }

    private fun registerMediaCommandReceiver() {
        try {
            mediaCommandReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val cmd = intent?.getStringExtra(PlaybackService.EXTRA_COMMAND)
                    // 服务端正已移除 ACTION_MEDIA_COMMAND 广播分发，此处仅作兜底日志，
                    // 绝不能再执行动作（历史上每次广播都导致重复执行一次）
                    Log.d("MainActivity", "mediaCommandReceiver ignored cmd=$cmd")
                }
            }
            val filter = IntentFilter(PlaybackService.ACTION_MEDIA_COMMAND)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(mediaCommandReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(mediaCommandReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to register mediaCommandReceiver", e)
        }
    }

    private var lastActivityKeyTimestamp = 0L

    // 针对车载中控硬件方向盘按键与蓝牙多功能键的硬件按键分发 (支持全量车机键值与防抖，严禁拦截系统返回键 KEYCODE_BACK)
    private fun isMediaOrVolumeKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_NAVIGATE_NEXT,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_NAVIGATE_PREVIOUS,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE -> true
            else -> false
        }
    }

    private fun handleMediaKeyEvent(keyCode: Int): Boolean {
        if (!isMediaOrVolumeKey(keyCode)) return false
        val now = System.currentTimeMillis()
        if (now - lastActivityKeyTimestamp < 250) return true
        lastActivityKeyTimestamp = now
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_NAVIGATE_NEXT,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                PlaybackQueueManager.playNext(this@MainActivity)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_NAVIGATE_PREVIOUS,
            KeyEvent.KEYCODE_PAGE_UP -> {
                PlaybackQueueManager.playPrevious(this@MainActivity)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_BUTTON_START -> {
                PlaybackQueueManager.togglePlay(this@MainActivity)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                val p = Media3Factory.getSharedExoPlayer(this@MainActivity)
                if (!p.isPlaying) PlaybackQueueManager.togglePlay(this@MainActivity)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                val p = Media3Factory.getSharedExoPlayer(this@MainActivity)
                if (p.isPlaying) PlaybackQueueManager.togglePlay(this@MainActivity)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                audioManager?.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_RAISE,
                    android.media.AudioManager.FLAG_SHOW_UI
                )
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                audioManager?.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_LOWER,
                    android.media.AudioManager.FLAG_SHOW_UI
                )
                return true
            }
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                audioManager?.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_TOGGLE_MUTE,
                    android.media.AudioManager.FLAG_SHOW_UI
                )
                return true
            }
        }
        return false
    }

    override fun onStart() {
        super.onStart()
        isUserExplicitExit = false
        DynamicIslandManager.onAppBackgroundStateChanged(this, inBackground = false)
    }

    override fun onResume() {
        super.onResume()
        isUserExplicitExit = false
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        DynamicIslandManager.onAppBackgroundStateChanged(this, inBackground = false)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isChangingConfigurations && !isUserExplicitExit) {
            DynamicIslandManager.onAppBackgroundStateChanged(this, inBackground = true)
        }
    }

    override fun onPause() {
        val currentPos = exoPlayer?.currentPosition?.takeIf { it > 0L }
        PlaybackQueueManager.savePlaybackState(this, positionMs = currentPos, commitSync = true)
        super.onPause()
    }

    override fun onStop() {
        val currentPos = exoPlayer?.currentPosition?.takeIf { it > 0L }
        PlaybackQueueManager.savePlaybackState(this, positionMs = currentPos, commitSync = true)
        if (!isChangingConfigurations && !isUserExplicitExit) {
            DynamicIslandManager.onAppBackgroundStateChanged(this, inBackground = true)
        }
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (handleMediaKeyEvent(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return super.onKeyUp(keyCode, event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (handleMediaKeyEvent(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun startPlaybackService() {
        PlaybackService.startPlaybackService(this)
    }

    private fun requestAppPermissions() {
        try {
            val permissions = mutableListOf<String>()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }

            if (permissions.isNotEmpty()) {
                permissionLauncher.launch(permissions.toTypedArray())
            }

            // 后台常驻保活已按需求移除，不再引导用户申请「忽略电池优化」白名单
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        mediaCommandReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        val currentPos = exoPlayer?.currentPosition?.takeIf { it > 0L }
        PlaybackQueueManager.savePlaybackState(this, positionMs = currentPos, commitSync = true)
        val stopPlaybackOnExit = try {
            getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE).getBoolean("stop_playback_on_exit", true)
        } catch (_: Exception) { true }
        if (isUserExplicitExit || (stopPlaybackOnExit && isFinishing)) {
            // 仅在用户主动选择彻底退出软件或开启了「退出应用时停止播放」且界面退出时停止播放器与后台服务
            try {
                exoPlayer?.stop()
                exoPlayer?.clearMediaItems()
                PlaybackService.stopServiceAndPlayback(this)
            } catch (_: Exception) {}
        }
        // 非主动退出（切桌面/导航、系统回收 Activity）时不干预播放：
        // 前台播放服务按系统常规策略存活，已不再强制拉起保活
        super.onDestroy()
    }
}

/**
 * 资料库下拉刷新包装层。
 *
 * 单独抽成一个小 Composable，避免给形参已经很多的 LocalLibraryScreen 再加参数——
 * 那会让 Compose 编译器生成的巨型方法寄存器数量越过 ART 校验阈值，在部分 ROM 上抛 VerifyError。
 * nestedScroll 挂在外层 Box：只有内部 LazyColumn 滚到顶部继续下拉时手势才经嵌套滚动冒泡到这里，
 * 因此不拦截首页歌单画廊等内部横滑，也不影响二级下钻页自身的滚动。
 */
@Composable
private fun LibraryPullRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    topPadding: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    val pullState = rememberPullToRefreshState()
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) pullState.startRefresh() else pullState.endRefresh()
    }
    LaunchedEffect(pullState) {
        snapshotFlow { pullState.isRefreshing }
            .distinctUntilChanged()
            .collect { refreshing -> if (refreshing) onRefresh() }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(pullState.nestedScrollConnection)
    ) {
        content()
        PullToRefreshContainer(
            state = pullState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = topPadding)
        )
    }
}

/**
 * 下载链路的音质决策入口 (单首与多选批量共用)。
 *
 * 把播放链路 PlaybackRouter 的音质决策原样搬到下载路径上，这是"选 320K 却下到无损"的根治点：
 * 服务器曲库歌曲的 streamUrl 形如 `/api/play/local?path=…&token=…`，**URL 里没有音质参数**，
 * 以前被当作直链歌曲直接下载，拉到的永远是该曲目的**服务器原文件**（可能是无损）。
 * 现在统一按目标音质解析：服务器原文件音质匹配就用原文件，否则先向音源取对应音质
 * （音源内部自带 flac24bit→flac→320k→128k 逐级降级与同名搜索回退），
 * 仍未命中再退回服务器本地流并带上 quality 参数让服务端给对应音质。
 */
private suspend fun resolveDownloadUrlsFor(
    context: android.content.Context,
    server: com.lm.player.core.model.ServerConfig,
    song: UnifiedSong,
    quality: AudioQuality,
    priority: com.lm.player.core.model.DownloadSourcePriority = com.lm.player.core.model.DownloadSourcePriority.CLOUD_FIRST
): List<String> {
    return try {
        val protocol = LemonMusicProtocol(
            NetworkClientFactory.createOkHttpClient(context),
            server.serverUrl,
            server.username,
            server.tokenOrApiKey
        )
        DownloadRequestPlanner.resolveStreamCandidates(protocol, song, quality, priority)
            .map { it.url }
    } catch (e: Exception) {
        android.util.Log.w("MainActivity", "Resolve download streams failed for ${song.title}", e)
        emptyList()
    }
}
