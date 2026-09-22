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
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
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
import com.lm.player.core.designsystem.theme.AppThemeMode
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.designsystem.theme.UiScaleMode
import com.lm.player.core.designsystem.theme.ZDSPlayerTheme
import com.lm.player.core.designsystem.theme.rememberAppDimensions
import com.lm.player.core.media.DownloadEngine
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

        // 1. 初始化核心数据库、路由与下载引擎
        database = ZdsDatabase.getInstance(this)
        playbackRouter = PlaybackRouter(database.downloadDao(), this)
        downloadEngine = DownloadEngine(this, database.downloadDao(), database.songDao(), lifecycleScope)

        // 2. 获取全局唯一共享 ExoPlayer 实例并启动前台播放服务
        exoPlayer = Media3Factory.getSharedExoPlayer(this)
        startPlaybackService()

        // 3. 注册方向盘按键与车载控制广播监听器
        registerMediaCommandReceiver()

        // 4. 申请 Android 6.0 / 13+ 运行时权限
        requestAppPermissions()

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            var currentScaleMode by remember { mutableStateOf(UiScaleMode.AUTO) }
            val appDimensions = rememberAppDimensions(currentScaleMode)

            // 启动过渡状态 (默认 false 确保 0ms 瞬间秒开呈现主屏)
            var isSplashVisible by remember { mutableStateOf(false) }

            // 外观主题、毛玻璃与动效状态
            var currentThemeMode by remember { mutableStateOf(AppThemeMode.FOLLOW_SYSTEM) }
            var blurAlpha by remember { mutableStateOf(0.85f) }
            var enableBottomBarAnimation by remember { mutableStateOf(true) }

            // 启动自动播放与在线容灾配置
            val autoPlayPrefs = remember { getSharedPreferences("zds_auto_play_prefs", Context.MODE_PRIVATE) }
            var autoPlayOnStartup by remember { mutableStateOf(autoPlayPrefs.getBoolean("auto_play_on_startup", true)) }
            var autoFallbackToLocal by remember { mutableStateOf(autoPlayPrefs.getBoolean("auto_fallback_to_local", true)) }
            var hasAutoPlayedOnStartup by remember { mutableStateOf(false) }
            var hasAutoCheckedServerOnStartup by remember { mutableStateOf(false) }

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

            // UI 状态机 (启动时默认优先显示本地已下载界面)
            var currentScreen by remember { mutableStateOf(Screen.HOME) }
            var activeServerName by remember { mutableStateOf("本地 · 已下载") }
            var activeServerId by remember { mutableStateOf("") }
            var serversList by remember { mutableStateOf<List<ServerConfig>>(emptyList()) }
            var isSearchDialogOpen by remember { mutableStateOf(false) }
            
            // 首页展示自定义配置 (默认显示最近播放和最近添加)
            var homeDisplayConfig by remember { mutableStateOf(HomeScreenDisplayConfig()) }

            // 在线模式操作音源偏好 (酷我/网易云/QQ音乐/酷狗/咪咕)
            val onlinePrefs = remember { getSharedPreferences("zds_online_prefs", Context.MODE_PRIVATE) }
            val savedSourceName = remember { onlinePrefs.getString("selected_source", OnlineMusicSource.KUWO.name) ?: OnlineMusicSource.KUWO.name }
            var currentOnlineSource by remember {
                mutableStateOf(try { OnlineMusicSource.valueOf(savedSourceName) } catch (_: Exception) { OnlineMusicSource.KUWO })
            }

            // 实时下载状态与设置
            val activeDownloadTasks by downloadEngine.activeTasksFlow.collectAsState(initial = emptyList())
            val downloadSettings by downloadEngine.downloadSettings.collectAsState()

            // 歌曲列表与专项「最近添加」「最近播放」「播放列表」数据集
            var songList by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
            var playlistsList by remember { mutableStateOf<List<UnifiedPlaylist>>(emptyList()) }
            var recentlyAddedSongs by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }
            var recentlyPlayedSongs by remember { mutableStateOf<List<UnifiedSong>>(emptyList()) }

            // 全局播放状态委托至 PlaybackQueueManager
            val currentSong by PlaybackQueueManager.currentSongFlow.collectAsState()
            val isPlaying by PlaybackQueueManager.isPlayingFlow.collectAsState()
            val isShuffle by PlaybackQueueManager.isShuffleFlow.collectAsState()
            val isRepeat by PlaybackQueueManager.isRepeatFlow.collectAsState()

            var currentLyrics by remember { mutableStateOf(LyricResult()) }
            var isFullPlayerVisible by remember { mutableStateOf(false) }
            var isLyricsMode by remember { mutableStateOf(false) }

            // 双击返回退出程序并彻底停止播放
            var lastBackPressTime by remember { mutableStateOf(0L) }

            BackHandler(enabled = isFullPlayerVisible || currentScreen != Screen.HOME || isSearchDialogOpen) {
                if (isFullPlayerVisible) {
                    isFullPlayerVisible = false
                } else if (isSearchDialogOpen) {
                    isSearchDialogOpen = false
                } else if (currentScreen != Screen.HOME) {
                    currentScreen = Screen.HOME
                }
            }

            BackHandler(enabled = !isFullPlayerVisible && !isSearchDialogOpen && currentScreen == Screen.HOME) {
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000) {
                    exitAppCompletely()
                } else {
                    lastBackPressTime = now
                    Toast.makeText(this@MainActivity, "再按一次彻底退出程序并停止播放", Toast.LENGTH_SHORT).show()
                }
            }

            // 同步远程柠檬音乐歌曲至本地 Room 数据库 (并行异步加速，全量同步)
            val syncServerSongs: (ServerConfig) -> Unit = { config ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                    val protocol = LemonMusicProtocol(client, config.serverUrl, config.username, config.tokenOrApiKey)
                    val authRes = protocol.authenticate(config)
                    if (authRes.isSuccess) {
                        // 极速轻量化同步全量歌曲与最近添加、最近播放
                        val songsRes = protocol.getSongList(offset = 0, limit = 0)
                        if (songsRes.isSuccess) {
                            val list = songsRes.getOrNull() ?: emptyList()
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
                                        relativeFolderPath = it.relativeFolderPath
                                    )
                                }
                                // 智能保护性同步：保留已有本地已下载路径、收藏与层级，杜绝覆盖重置
                                val mergedCount = SongMatchingResolver.syncAndUpsertServerSongs(
                                    database = database,
                                    incomingServerSongs = entities,
                                    downloadDir = downloadEngine.getDownloadDir()
                                )
                                Log.i("MainActivity", "Server sync completed. Merged & verified $mergedCount local tracks.")
                            }
                        }

                        val addedList = protocol.getRecentlyAdded(limit = 30).getOrNull() ?: emptyList()
                        if (addedList.isNotEmpty()) recentlyAddedSongs = addedList

                        val playedList = protocol.getRecentlyPlayed(limit = 30).getOrNull() ?: emptyList()
                        if (playedList.isNotEmpty()) recentlyPlayedSongs = playedList

                        // 同步服务器播放列表至数据库
                        val playlistRes = protocol.getPlaylists()
                        if (playlistRes.isSuccess) {
                            val plList = playlistRes.getOrNull() ?: emptyList()
                            val plEntities = plList.map { pl ->
                                com.lm.player.core.database.entity.PlaylistEntity(
                                    id = pl.id,
                                    name = pl.name,
                                    coverUrl = pl.coverUrl,
                                    serverId = config.id,
                                    isOnline = true,
                                    songCount = pl.songCount
                                )
                            }
                            database.playlistDao().insertPlaylists(plEntities)
                        }

                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "已成功从 NAS 同步全量媒体数据", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "同步失败: ${authRes.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            // 监听本地数据库中的服务器、播放列表与歌曲
            LaunchedEffect(Unit) {
                database.playlistDao().getAllPlaylistsFlow().collect { entities ->
                    playlistsList = entities.map { entity ->
                        UnifiedPlaylist(
                            id = entity.id,
                            name = entity.name,
                            coverUrl = entity.coverUrl,
                            songCount = entity.songCount,
                            isOnline = entity.isOnline,
                            serverId = entity.serverId,
                            isDiscover = entity.id.startsWith("discover_")
                        )
                    }
                }
            }

            // 启动时自动清理此前测试版本可能混入数据库资料库的推荐歌单数据
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    try {
                        val allPls = database.playlistDao().getAllPlaylists()
                        val dirtyIds = allPls.filter { it.id.startsWith("discover_") || it.id.startsWith("lemon_rec_") }.map { it.id }
                        dirtyIds.forEach { id -> database.playlistDao().deletePlaylist(id) }
                    } catch (_: Exception) {}
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
                database.songDao().getAllSongsFlow().collect { songEntities ->
                    val mappedSongs = withContext(Dispatchers.Default) {
                        songEntities.map { entity ->
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
                    songList = mappedSongs
                    PlaybackQueueManager.updatePlaylist(mappedSongs)
                    if (recentlyAddedSongs.isEmpty() && mappedSongs.isNotEmpty()) {
                        recentlyAddedSongs = mappedSongs.take(20)
                    }
                    if (recentlyPlayedSongs.isEmpty() && mappedSongs.isNotEmpty()) {
                        recentlyPlayedSongs = mappedSongs.take(10)
                    }
                    if (currentSong == null && mappedSongs.isNotEmpty()) {
                        val lastPlayedSongId = autoPlayPrefs.getString("last_played_song_id", "") ?: ""
                        val targetSong = mappedSongs.firstOrNull { it.id == lastPlayedSongId } ?: mappedSongs.first()
                        PlaybackQueueManager.playSong(targetSong, this@MainActivity, mappedSongs)
                    }
                }
            }

            // 核心业务函数：播放指定歌曲 (委托至 PlaybackQueueManager 调度)
            val playSong: (UnifiedSong) -> Unit = { targetSong ->
                if (targetSong.serverId == "lemon_online" && (targetSong.streamUrl.isBlank() || targetSong.streamUrl.startsWith("lemon_online://"))) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val active = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                        if (active != null) {
                            val protocol = LemonMusicProtocol(
                                NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                active.serverUrl,
                                active.username,
                                active.tokenOrApiKey
                            )
                            val source = targetSong.id.removePrefix("lemon_online_").substringBefore("_")
                            val realUrl = protocol.resolveOnlineStreamUrl(targetSong.id, source).getOrNull()
                            if (!realUrl.isNullOrBlank()) {
                                val resolvedSong = targetSong.copy(streamUrl = realUrl)
                                withContext(Dispatchers.Main) {
                                    recentlyPlayedSongs = listOf(resolvedSong) + recentlyPlayedSongs.filter { it.id != resolvedSong.id }
                                    PlaybackQueueManager.playSong(resolvedSong, this@MainActivity, songList)
                                }
                                return@launch
                            }
                        }
                        withContext(Dispatchers.Main) {
                            recentlyPlayedSongs = listOf(targetSong) + recentlyPlayedSongs.filter { it.id != targetSong.id }
                            PlaybackQueueManager.playSong(targetSong, this@MainActivity, songList)
                        }
                    }
                } else {
                    recentlyPlayedSongs = listOf(targetSong) + recentlyPlayedSongs.filter { it.id != targetSong.id }
                    PlaybackQueueManager.playSong(targetSong, this@MainActivity, songList)
                }
            }

            val handleDownloadSong: (UnifiedSong) -> Unit = { songToDownload ->
                if (songToDownload.serverId == "lemon_online" && (songToDownload.streamUrl.isBlank() || songToDownload.streamUrl.startsWith("lemon_online://"))) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val active = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                        if (active != null) {
                            val protocol = LemonMusicProtocol(
                                NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                active.serverUrl,
                                active.username,
                                active.tokenOrApiKey
                            )
                            val source = songToDownload.id.removePrefix("lemon_online_").substringBefore("_")
                            val realUrl = protocol.resolveOnlineStreamUrl(songToDownload.id, source).getOrNull()
                            if (!realUrl.isNullOrBlank()) {
                                val resolved = songToDownload.copy(streamUrl = realUrl)
                                downloadEngine.startDownload(resolved)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "已加入下载队列: ${resolved.title}", Toast.LENGTH_SHORT).show()
                                }
                                return@launch
                            }
                        }
                        withContext(Dispatchers.Main) {
                            downloadEngine.startDownload(songToDownload)
                            Toast.makeText(this@MainActivity, "已加入下载队列: ${songToDownload.title}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    downloadEngine.startDownload(songToDownload)
                    songList = songList.map {
                        if (it.id == songToDownload.id) it.copy(downloadStatus = DownloadStatus.DOWNLOADING) else it
                    }
                    Toast.makeText(this@MainActivity, "已加入下载队列: ${songToDownload.title}", Toast.LENGTH_SHORT).show()
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

            // 启动自动播放与断点恢复逻辑 (启动时优先播放上一次关闭界面时的歌曲)
            LaunchedEffect(songList) {
                if (autoPlayOnStartup && !hasAutoPlayedOnStartup && songList.isNotEmpty()) {
                    hasAutoPlayedOnStartup = true
                    val lastPlayedSongId = autoPlayPrefs.getString("last_played_song_id", "") ?: ""
                    val targetSong = songList.firstOrNull { it.id == lastPlayedSongId } ?: songList.first()
                    playSong(targetSong)
                }
            }

            // 歌曲切换时动态从音乐文件/NAS提取解析歌词 (支持秒级内存预载与防并发竞争)
            LaunchedEffect(currentSong?.id) {
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

            // 监听 ExoPlayer 在线容灾回退
            DisposableEffect(exoPlayer, currentSong, songList, autoFallbackToLocal) {
                val listener = object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("MainActivity", "Player Error encountered: ${error.message}", error)
                        if (autoFallbackToLocal) {
                            val localCandidates = songList.filter {
                                it.downloadStatus == DownloadStatus.DOWNLOADED ||
                                it.serverId in listOf("local_storage", "local_folder", "local_saf") ||
                                !it.localFilePath.isNullOrBlank()
                            }
                            if (localCandidates.isNotEmpty()) {
                                Toast.makeText(this@MainActivity, "在线音频无法缓冲，已自动为您无缝切换至本地歌曲", Toast.LENGTH_SHORT).show()
                                val fallbackSong = localCandidates.firstOrNull { it.id != currentSong?.id } ?: localCandidates.first()
                                playSong(fallbackSong)
                            } else {
                                Toast.makeText(this@MainActivity, "播放出错，且本地暂无已下载歌曲", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                exoPlayer?.addListener(listener)
                onDispose {
                    exoPlayer?.removeListener(listener)
                }
            }

            CompositionLocalProvider(LocalAppDimensions provides appDimensions) {
                ZDSPlayerTheme(themeMode = currentThemeMode) {
                    // 响应式脚手架 (横屏大屏与竖屏手机统一使用底部悬浮一体化播放导航栏)
                    AdaptiveAppScaffold(
                        windowSizeClass = windowSizeClass.widthSizeClass,
                        currentScreen = if (currentScreen == Screen.DOWNLOADS) Screen.LIBRARY else currentScreen,
                        currentPlayingSong = currentSong,
                        isPlaying = isPlaying,
                        blurAlpha = blurAlpha,
                        enableBottomBarAnimation = enableBottomBarAnimation,
                        useLinearAnimation = true,
                        onNavigate = { currentScreen = it },
                        onPlayPauseToggle = togglePlayPause,
                        onPrevious = playPrevious,
                        onNext = playNext,
                        onOpenFullPlayer = { isFullPlayerVisible = true },
                        onSearchClick = { isSearchDialogOpen = true }
                    ) { innerPadding ->
                        AnimatedContent(
                            targetState = currentScreen,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(150, easing = FastOutSlowInEasing)) togetherWith
                                        fadeOut(animationSpec = tween(120, easing = FastOutSlowInEasing))
                            },
                            label = "screen_transition"
                        ) { targetScreen ->
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
                                            activeDownloadTasks = activeDownloadTasks,
                                            activeDownloadCount = activeDownloadTasks.size,
                                            onSongClick = playSong,
                                            onDownloadSong = handleDownloadSong,
                                            onSelectLocalServer = {
                                                activeServerName = "本地 · 已下载"
                                                currentScreen = Screen.HOME
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
                                                Toast.makeText(this@MainActivity, "正在从柠檬音乐同步全量曲库...", Toast.LENGTH_SHORT).show()
                                                syncServerSongs(activeServer)
                                            },
                                            onOpenDownloads = { currentScreen = Screen.DOWNLOADS },
                                            onGoToSettings = { currentScreen = Screen.SETTINGS },
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
                                            onSongClick = playSong,
                                            onDownloadSong = handleDownloadSong,
                                            onSelectLocalServer = {
                                                activeServerName = "本地模式"
                                                currentScreen = Screen.HOME
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
                                                currentScreen = Screen.DOWNLOADS
                                            },
                                            onGoToSettings = {
                                                currentScreen = Screen.SETTINGS
                                            },
                                            onPlaylistClick = {
                                                currentScreen = Screen.LIBRARY
                                            },
                                            onScanLocalMedia = {
                                                currentScreen = Screen.SETTINGS
                                            },
                                            contentPadding = innerPadding
                                        )
                                    }
                                }

                                Screen.LIBRARY -> {
                                    val isLocalMode = activeServerName.contains("本地") || activeServerName.contains("已下载")
                                    val activeConfig = if (isLocalMode) null else serversList.firstOrNull { it.isCurrentActive }
                                    val librarySongs = remember(songList, activeConfig, isLocalMode) {
                                        if (isLocalMode || activeConfig == null) {
                                            songList.filter {
                                                it.downloadStatus == DownloadStatus.DOWNLOADED ||
                                                it.serverId in listOf("local_storage", "local_folder", "local_saf") ||
                                                !it.localFilePath.isNullOrBlank()
                                            }
                                        } else {
                                            // 在线模式严格展示当前服务器曲目，杜绝混入本地独立未匹配歌曲导致数量翻倍！
                                            songList.filter {
                                                it.serverId == activeConfig.id ||
                                                it.serverId == activeConfig.serverUrl ||
                                                (activeConfig.type == ServerType.LEMON_MUSIC && (it.serverId == "lemon_music" || it.serverId == activeConfig.id))
                                            }
                                        }
                                    }
                                    LocalLibraryScreen(
                                        allSongs = librarySongs,
                                        playlists = if (isLocalMode) {
                                            playlistsList.filter { !it.isOnline }
                                        } else {
                                            playlistsList.filter {
                                                !it.isDiscover && !it.id.startsWith("discover_") && !it.id.startsWith("lemon_rec_") &&
                                                (it.serverId == activeConfig?.id || !it.isOnline)
                                            }
                                        },
                                        activeServerConfig = activeConfig,
                                        activeDownloadTasks = activeDownloadTasks,
                                        activeDownloadCount = activeDownloadTasks.size,
                                        onSongClick = playSong,
                                        onDownloadSong = handleDownloadSong,
                                        onOpenDownloads = { currentScreen = Screen.DOWNLOADS },
                                        onRefreshPlaylists = {
                                            if (activeConfig != null) {
                                                Toast.makeText(this@MainActivity, "正在同步在线播放列表...", Toast.LENGTH_SHORT).show()
                                                syncServerSongs(activeConfig)
                                            } else {
                                                Toast.makeText(this@MainActivity, "当前处于本地模式，暂无在线歌单", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onCreatePlaylist = { name, isOnline ->
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                val newId = "pl_${System.currentTimeMillis()}"
                                                database.playlistDao().insertPlaylist(
                                                    com.lm.player.core.database.entity.PlaylistEntity(
                                                        id = newId,
                                                        name = name,
                                                        serverId = if (isOnline && activeConfig != null) activeConfig.id else "local_storage",
                                                        isOnline = isOnline,
                                                        songCount = 0
                                                    )
                                                )
                                            }
                                        },
                                        onDeletePlaylist = { playlistId ->
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                database.playlistDao().deletePlaylist(playlistId)
                                            }
                                        },
                                        onFetchPlaylistSongs = { playlistId, isOnline ->
                                            val rawList = if (isOnline && activeConfig != null) {
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
                                            // 全局统一匹配：将歌单曲目与本地缓存/下载物理文件即时比对并挂载
                                            SongMatchingResolver.resolveSongList(
                                                incomingSongs = rawList,
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
                                        contentPadding = innerPadding
                                    )
                                }

                                Screen.DOWNLOADS -> {
                                    val completedList = remember(songList) {
                                        songList.filter {
                                            it.downloadStatus == DownloadStatus.DOWNLOADED ||
                                            it.serverId in listOf("local_storage", "local_folder", "local_saf") ||
                                            !it.localFilePath.isNullOrBlank()
                                        }
                                    }
                                    DownloadManagerScreen(
                                        activeTasks = activeDownloadTasks,
                                        completedSongs = completedList,
                                        onSongClick = playSong,
                                        onCancelTask = { downloadEngine.cancelDownload(it) },
                                        onDeleteDownloadedSong = { songToDelete ->
                                            downloadEngine.deleteDownloadedSong(songToDelete)
                                        },
                                        onBack = { currentScreen = Screen.LIBRARY },
                                        contentPadding = innerPadding
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
                                        onHomeDisplayConfigChange = { homeDisplayConfig = it },
                                        currentScaleMode = currentScaleMode,
                                        onScaleModeChange = { currentScaleMode = it },
                                        themeMode = currentThemeMode,
                                        onThemeModeChange = { currentThemeMode = it },
                                        blurAlpha = blurAlpha,
                                        onBlurAlphaChange = { blurAlpha = it },
                                        enableBottomBarAnimation = enableBottomBarAnimation,
                                        onEnableBottomBarAnimationChange = { enableBottomBarAnimation = it },
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
                                            currentScreen = Screen.DOWNLOADS
                                        },
                                        onLocalScanCompleted = {
                                            // 触发歌曲刷新
                                        },
                                        onChooseDownloadDirectory = {
                                            onChooseDownloadFolderResult = { uri ->
                                                val updated = downloadSettings.copy(customDownloadPath = uri.toString())
                                                downloadEngine.downloadSettings.value = updated
                                                Toast.makeText(this@MainActivity, "已成功设定下载存储目录", Toast.LENGTH_SHORT).show()
                                            }
                                            chooseDownloadDirectoryLauncher.launch(null)
                                        },
                                        onImportCustomFolder = {
                                            onImportFolderResult = { uri ->
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    val count = LocalMediaScanner.scanDocumentTree(this@MainActivity, uri, database)
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(this@MainActivity, "扫描完成！成功导入 $count 首本地歌曲", Toast.LENGTH_LONG).show()
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
                    }

                    // 全屏全局沉浸式搜索面板
                    if (isSearchDialogOpen) {
                        val activeServer = serversList.firstOrNull { it.isCurrentActive }
                        LibrarySearchDialog(
                            allSongs = songList,
                            onSongClick = playSong,
                            onDownloadSong = handleDownloadSong,
                            initialOnlineSource = currentOnlineSource,
                            onOnlineSourceChanged = { newSrc ->
                                currentOnlineSource = newSrc
                                onlinePrefs.edit().putString("selected_source", newSrc.name).apply()
                            },
                            onOnlineSearch = if (activeServer?.type == ServerType.LEMON_MUSIC) {
                                { keyword, source ->
                                    val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                    val protocol = LemonMusicProtocol(client, activeServer.serverUrl, activeServer.username, activeServer.tokenOrApiKey)
                                    protocol.searchOnline(keyword, source = source.key).getOrNull() ?: emptyList()
                                }
                            } else null,
                            onDismiss = { isSearchDialogOpen = false }
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
                                        // 智能切歌预热：播放进度达到 85% 时，后台异步预热下一首歌曲解析与歌词
                                        if (pos.toFloat() / dur > 0.85f && songList.isNotEmpty()) {
                                            val nextIndex = (songList.indexOfFirst { it.id == song.id } + 1).takeIf { it < songList.size } ?: 0
                                            val nextCandidate = if (isShuffle) (songList.filter { it.id != song.id }.randomOrNull() ?: song) else songList[nextIndex]
                                            if (nextCandidate.id != prebufferedSongId) {
                                                prebufferedSongId = nextCandidate.id
                                                launch(Dispatchers.IO) {
                                                    try {
                                                        val activeServer = serversList.firstOrNull { it.isCurrentActive }
                                                        LyricsManager.loadLyrics(nextCandidate, this@MainActivity, activeServer)
                                                    } catch (_: Exception) {}
                                                }
                                            }
                                        }
                                    }
                                    delay(400)
                                }
                            }

                            var playbackSpeed by remember { mutableStateOf(1.0f) }

                            FullscreenPlayerSheet(
                                song = song,
                                playlist = songList,
                                isPlaying = isPlaying,
                                progressMs = localProgressMs,
                                totalDurationMs = localTotalDurationMs,
                                lyrics = currentLyrics,
                                isLyricsMode = isLyricsMode,
                                isShuffle = isShuffle,
                                isRepeat = isRepeat,
                                playbackSpeed = playbackSpeed,
                                onTogglePlayPause = togglePlayPause,
                                onNext = playNext,
                                onPrevious = playPrevious,
                                onSeekTo = { seekPosition ->
                                    exoPlayer?.seekTo(seekPosition)
                                    localProgressMs = seekPosition
                                },
                                onSelectSongFromQueue = { queueSong ->
                                    playSong(queueSong)
                                },
                                onToggleLyricsMode = { isLyricsMode = it },
                                onToggleFavorite = {
                                    val updatedSong = song.copy(isFavorite = !song.isFavorite)
                                    songList = songList.map { if (it.id == song.id) updatedSong else it }
                                    PlaybackQueueManager.updatePlaylist(songList)
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        database.songDao().updateFavorite(song.id, updatedSong.isFavorite)
                                    }
                                },
                                onToggleShuffle = { PlaybackQueueManager.setShuffle(!isShuffle) },
                                onToggleRepeat = { PlaybackQueueManager.setRepeat(!isRepeat) },
                                onChangePlaybackSpeed = { speed ->
                                    playbackSpeed = speed
                                    exoPlayer?.playbackParameters = androidx.media3.common.PlaybackParameters(speed)
                                },
                                onDownloadSong = { songToDownload ->
                                    downloadEngine.startDownload(songToDownload)
                                    songList = songList.map {
                                        if (it.id == songToDownload.id) it.copy(downloadStatus = DownloadStatus.DOWNLOADING) else it
                                    }
                                    Toast.makeText(this@MainActivity, "已加入下载队列: ${songToDownload.title}", Toast.LENGTH_SHORT).show()
                                },
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

    /**
     * 彻底关闭程序与播放服务
     */
    private fun exitAppCompletely() {
        try {
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
                    when (cmd) {
                        PlaybackService.CMD_NEXT -> playNextAction?.invoke()
                        PlaybackService.CMD_PREV -> playPreviousAction?.invoke()
                        PlaybackService.CMD_TOGGLE -> togglePlayAction?.invoke()
                        PlaybackService.CMD_PLAY -> if (exoPlayer?.isPlaying != true) togglePlayAction?.invoke()
                        PlaybackService.CMD_PAUSE -> if (exoPlayer?.isPlaying == true) togglePlayAction?.invoke()
                    }
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

    // 针对车载中控硬件方向盘按键与蓝牙多功能键的硬件按键分发 (支持全量车机键值与防抖)
    private fun handleMediaKeyEvent(keyCode: Int): Boolean {
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
                playNextAction?.invoke()
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
                playPreviousAction?.invoke()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_BUTTON_START -> {
                PlaybackQueueManager.togglePlay(this@MainActivity)
                togglePlayAction?.invoke()
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
        }
        return false
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
        try {
            val serviceIntent = Intent(this, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
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
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        mediaCommandReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        if (isFinishing) {
            try {
                exoPlayer?.stop()
                exoPlayer?.clearMediaItems()
                PlaybackService.stopServiceAndPlayback(this)
            } catch (_: Exception) {}
        }
        super.onDestroy()
    }
}
