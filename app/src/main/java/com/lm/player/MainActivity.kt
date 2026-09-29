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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
        exoPlayer = Media3Factory.getSharedExoPlayer(this)
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
            var showBgIslandOverlayPrompt by remember {
                DynamicIslandManager.ensureInitialized(this@MainActivity)
                mutableStateOf(
                    DynamicIslandManager.systemIslandEnabledFlow.value &&
                        DynamicIslandManager.islandDisplayModeFlow.value != com.lm.player.core.media.IslandDisplayMode.SYSTEM_ONLY &&
                        !DynamicIslandManager.hasOverlayPermission(this@MainActivity) &&
                        !uiPrefs.getBoolean("bg_island_overlay_prompted_v157", false)
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
            val currentQueue by PlaybackQueueManager.playlistFlow.collectAsState()

            var currentLyrics by remember { mutableStateOf(LyricResult()) }
            var isFullPlayerVisible by remember { mutableStateOf(false) }
            var isLyricsMode by remember { mutableStateOf(false) }

            // 正在播放歌曲定位悬浮按钮状态（记录歌曲所属列表页面 + 仅在播放中且列表滑动时显示，播放界面不显示）
            var playingListScreen by remember { mutableStateOf(Screen.HOME) }
            var locateSongTrigger by remember { mutableStateOf(0) }
            var isSongListScrolling by remember { mutableStateOf(false) }
            var isLocateButtonVisible by remember { mutableStateOf(false) }

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
                                if (!incomingIds.contains(oldPl.id) && oldPl.isOnline && !oldPl.id.startsWith("pl_")) {
                                    database.playlistDao().deletePlaylist(oldPl.id)
                                }
                            }
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
                            Log.i("MainActivity", "Successfully synced ${plEntities.size} server playlists for server ${config.id}")
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "syncServerPlaylists failed", e)
                    }
                }
            }

            // 同步远程柠檬音乐歌曲至本地 Room 数据库 (并行异步加速，全量同步，支持静默后台刷新与显式 Toast 提示)
            val syncServerSongsWithToast: (ServerConfig, Boolean) -> Unit = { config, showToast ->
                lifecycleScope.launch(Dispatchers.IO) {
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
                                    if (!incomingIds.contains(oldPl.id) && oldPl.isOnline && !oldPl.id.startsWith("pl_")) {
                                        database.playlistDao().deletePlaylist(oldPl.id)
                                    }
                                }
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
                        } catch (e: Exception) {
                            Log.w("MainActivity", "Pre-sync playlists failed", e)
                        }

                        // 极速轻量化同步全量歌曲与最近添加、最近播放
                        val songsRes = activeProto.getSongList(offset = 0, limit = 0)
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
                                // 智能保护性同步：保留已有本地已下载路径、收藏与层级，杜绝覆盖重置，并进行差量清理
                                val mergedCount = SongMatchingResolver.syncAndUpsertServerSongs(
                                    database = database,
                                    incomingServerSongs = entities,
                                    downloadDir = downloadEngine.getDownloadDir(),
                                    targetServerId = config.id
                                )
                                Log.i("MainActivity", "Server sync completed. Merged & verified $mergedCount local tracks.")
                            }
                        }

                        // 同步用户收藏与用户数据 (/api/library/user-data)
                        try {
                            val userDataRes = activeProto.getLibraryUserData()
                            if (userDataRes.isSuccess) {
                                val userDataObj = userDataRes.getOrNull()
                                val favArr = userDataObj?.optJSONArray("favorites")
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

                        val addedList = activeProto.getRecentlyAdded(limit = 30).getOrNull() ?: emptyList()
                        if (addedList.isNotEmpty()) recentlyAddedSongs = addedList

                        val playedList = activeProto.getRecentlyPlayed(limit = 30).getOrNull() ?: emptyList()
                        if (playedList.isNotEmpty()) recentlyPlayedSongs = playedList

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
                }
            }

            val syncServerSongs: (ServerConfig) -> Unit = { config ->
                syncServerSongsWithToast(config, true)
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
                database.songDao().getAllSongsFlow().collect { songEntities ->
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
                    recentlyAddedSongs = recAdded
                    if (recentlyPlayedSongs.isEmpty() && mappedSongs.isNotEmpty()) {
                        recentlyPlayedSongs = mappedSongs.take(10)
                    }
                    if (currentSong == null && mappedSongs.isNotEmpty()) {
                        val lastPlayedSongId = autoPlayPrefs.getString("last_played_song_id", "") ?: ""
                        val targetSong = mappedSongs.firstOrNull { it.id == lastPlayedSongId } ?: mappedSongs.first()
                        PlaybackQueueManager.setInitialSongIfAbsent(targetSong, mappedSongs)
                    }
                }
            }

            // 核心业务函数：播放指定歌曲 (委托至 PlaybackQueueManager 调度，支持动态上下文队列与全局本地优先调用)
            val playSongWithQueue: (UnifiedSong, List<UnifiedSong>?) -> Unit = { targetSong, contextQueue ->
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

                // 若未直接携带本地路径，快速从当前已收录歌曲库匹配（如从在线搜索或云端列表点击）
                val resolvedLocalPath = validDirectPath ?: run {
                    val normTitle = SongMatchingResolver.normalizeTrackTitle(targetSong.title)
                    val normArtist = SongMatchingResolver.normalizeArtist(targetSong.artist)
                    val matchedLocal = songList.firstOrNull {
                        val hasFile = !it.localFilePath.isNullOrBlank() && java.io.File(it.localFilePath).let { f -> f.exists() && f.length() > 0 }
                        hasFile && (it.id == targetSong.id || (
                            normTitle.isNotBlank() && SongMatchingResolver.normalizeTrackTitle(it.title) == normTitle &&
                            (normArtist.isBlank() || SongMatchingResolver.normalizeArtist(it.artist) == normArtist)
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
                    recentlyPlayedSongs = listOf(localSong) + recentlyPlayedSongs.filter { it.id != localSong.id }
                    val resolvedQueue = activeQueue.map { if (it.id == localSong.id) localSong else it }
                    PlaybackQueueManager.playSong(localSong, this@MainActivity, resolvedQueue)
                } else if (
                    (targetSong.serverId == "lemon_online" || targetSong.id.startsWith("lemon_online_")) &&
                    (targetSong.streamUrl.isBlank() || targetSong.streamUrl.startsWith("lemon_online://") || targetSong.streamUrl.contains("/api/play/proxy"))
                ) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val active = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                            ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }
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
                                // 若第三方在线源暂时无法返回直链，自动回退匹配资料库中同名服务端歌曲的有效流地址
                                val normTitle = SongMatchingResolver.normalizeTrackTitle(targetSong.title)
                                val normArtist = SongMatchingResolver.normalizeArtist(targetSong.artist)
                                val matchedServerSong = songList.firstOrNull {
                                    normTitle.isNotBlank() &&
                                    SongMatchingResolver.normalizeTrackTitle(it.title) == normTitle &&
                                    (normArtist.isBlank() || SongMatchingResolver.normalizeArtist(it.artist) == normArtist) &&
                                    (it.streamUrl.startsWith("http://") || it.streamUrl.startsWith("https://"))
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
                                    recentlyPlayedSongs = listOf(resolvedSong) + recentlyPlayedSongs.filter { it.id != resolvedSong.id }
                                    val resolvedQueue = activeQueue.map { if (it.id == resolvedSong.id) resolvedSong else it }
                                    PlaybackQueueManager.playSong(resolvedSong, this@MainActivity, resolvedQueue)
                                }
                                return@launch
                            }
                        }
                        withContext(Dispatchers.Main) {
                            recentlyPlayedSongs = listOf(targetSong) + recentlyPlayedSongs.filter { it.id != targetSong.id }
                            PlaybackQueueManager.playSong(targetSong, this@MainActivity, activeQueue)
                        }
                    }
                } else {
                    recentlyPlayedSongs = listOf(targetSong) + recentlyPlayedSongs.filter { it.id != targetSong.id }
                    PlaybackQueueManager.playSong(targetSong, this@MainActivity, activeQueue)
                }
            }

            val playSong: (UnifiedSong) -> Unit = { targetSong ->
                playSongWithQueue(targetSong, null)
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
                                val rawObj = try {
                                    if (!rawJsonStr.isNullOrBlank()) {
                                        org.json.JSONObject(rawJsonStr)
                                    } else null
                                } catch (_: Exception) { null }

                                val platform = songToDownload.id.removePrefix("lemon_online_").substringBefore("_").ifBlank { "kw" }
                                val serverTask = LemonServerDownloadTask(
                                    id = songToDownload.id,
                                    name = songToDownload.title,
                                    singer = songToDownload.artist,
                                    source = platform,
                                    album = songToDownload.album,
                                    pic = songToDownload.coverUrl,
                                    platform = platform,
                                    quality = quality.key,
                                    songId = rawObj?.optString("songId")?.ifBlank { null } ?: rawObj?.optString("id") ?: songToDownload.id.removePrefix("lemon_online_${platform}_"),
                                    songmid = rawObj?.optString("songmid") ?: "",
                                    hash = rawObj?.optString("hash") ?: "",
                                    rid = rawObj?.optString("rid") ?: "",
                                    copyrightId = rawObj?.optString("copyrightId") ?: "",
                                    img = songToDownload.coverUrl,
                                    raw = rawJsonStr ?: ""
                                )
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
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        delay(1500L)
                                        protocol.triggerServerScan()
                                        delay(2500L)
                                        syncServerSongsWithToast(activeServer, false)
                                        delay(5000L)
                                        syncServerSongsWithToast(activeServer, false)
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

                    if (
                        (songToDownload.serverId == "lemon_online" || songToDownload.id.startsWith("lemon_online_")) &&
                        (songToDownload.streamUrl.isBlank() || songToDownload.streamUrl.startsWith("lemon_online://") || songToDownload.streamUrl.contains("/api/play/proxy"))
                    ) {
                        // 在线歌曲：传入异步直链解析器，downloadEngine 立即将任务入队并展示在“正在下载”中
                        downloadEngine.startDownload(
                            song = preparedSong,
                            urlResolver = {
                                if (activeServer != null) {
                                    val protocol = LemonMusicProtocol(
                                        NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                        activeServer.serverUrl,
                                        activeServer.username,
                                        activeServer.tokenOrApiKey
                                    )
                                    val cleanId = songToDownload.id.removePrefix("lemon_online_")
                                    val source = if (cleanId.contains("_")) cleanId.substringBefore("_") else "kw"
                                    val meta = songToDownload.rawMetaJson?.takeIf { it.trim().startsWith("{") }
                                        ?: songToDownload.relativeFolderPath?.takeIf { it.trim().startsWith("{") }
                                    protocol.resolveOnlineStreamWithQuality(
                                        songId = songToDownload.id,
                                        source = source,
                                        preferredQuality = quality.key,
                                        metaJson = meta,
                                        fallbackTitle = songToDownload.title,
                                        fallbackArtist = songToDownload.artist
                                    ).getOrNull()?.url
                                } else null
                            }
                        )
                        Toast.makeText(this@MainActivity, "已加入下载队列 [${quality.badge}]: ${songToDownload.title}", Toast.LENGTH_SHORT).show()
                    } else {
                        // 本地/服务器直链歌曲直接启动下载
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
                                    val serverTasks = distinctSongs.map { songToDownload ->
                                        val rawJsonStr = songToDownload.rawMetaJson ?: songToDownload.relativeFolderPath
                                        val rawObj = try {
                                            if (!rawJsonStr.isNullOrBlank()) org.json.JSONObject(rawJsonStr) else null
                                        } catch (_: Exception) { null }
                                        val platform = songToDownload.id.removePrefix("lemon_online_").substringBefore("_").ifBlank { "kw" }
                                        LemonServerDownloadTask(
                                            id = songToDownload.id,
                                            name = songToDownload.title,
                                            singer = songToDownload.artist,
                                            source = platform,
                                            album = songToDownload.album,
                                            pic = songToDownload.coverUrl,
                                            platform = platform,
                                            quality = quality.key,
                                            songId = rawObj?.optString("songId")?.ifBlank { null } ?: rawObj?.optString("id") ?: songToDownload.id.removePrefix("lemon_online_${platform}_"),
                                            songmid = rawObj?.optString("songmid") ?: "",
                                            hash = rawObj?.optString("hash") ?: "",
                                            rid = rawObj?.optString("rid") ?: "",
                                            copyrightId = rawObj?.optString("copyrightId") ?: "",
                                            img = songToDownload.coverUrl,
                                            raw = rawJsonStr ?: ""
                                        )
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
                        distinctSongs.forEach { songToDownload ->
                            val hasLocalFile = songToDownload.downloadStatus == DownloadStatus.DOWNLOADED &&
                                !songToDownload.localFilePath.isNullOrBlank() &&
                                (songToDownload.localFilePath.startsWith("content://") || File(songToDownload.localFilePath).exists())
                            if (!hasLocalFile) {
                                enqueuedCount++
                                val preparedSong = songToDownload.copy(format = safeFormat, bitRate = quality.bitrate)
                                if (
                                    (songToDownload.serverId == "lemon_online" || songToDownload.id.startsWith("lemon_online_")) &&
                                    (songToDownload.streamUrl.isBlank() || songToDownload.streamUrl.startsWith("lemon_online://") || songToDownload.streamUrl.contains("/api/play/proxy"))
                                ) {
                                    downloadEngine.startDownload(
                                        song = preparedSong,
                                        urlResolver = {
                                            if (activeServer != null) {
                                                val protocol = LemonMusicProtocol(
                                                    NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                                    activeServer.serverUrl,
                                                    activeServer.username,
                                                    activeServer.tokenOrApiKey
                                                )
                                                val cleanId = songToDownload.id.removePrefix("lemon_online_")
                                                val source = if (cleanId.contains("_")) cleanId.substringBefore("_") else "kw"
                                                val meta = songToDownload.rawMetaJson?.takeIf { it.trim().startsWith("{") }
                                                    ?: songToDownload.relativeFolderPath?.takeIf { it.trim().startsWith("{") }
                                                protocol.resolveOnlineStreamWithQuality(
                                                    songId = songToDownload.id,
                                                    source = source,
                                                    preferredQuality = quality.key,
                                                    metaJson = meta,
                                                    fallbackTitle = songToDownload.title,
                                                    fallbackArtist = songToDownload.artist
                                                ).getOrNull()?.url
                                            } else null
                                        }
                                    )
                                } else {
                                    downloadEngine.startDownload(preparedSong)
                                }
                            }
                        }
                        Toast.makeText(
                            this@MainActivity,
                            if (enqueuedCount > 0) "已将 $enqueuedCount 首歌曲加入本地下载队列 [${quality.badge}]" else "所选歌曲均已下载至本地",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            val handleDownloadSong: (UnifiedSong) -> Unit = { songToDownload ->
                handleDownloadWithOptions(songToDownload, DownloadTarget.LOCAL, AudioQuality.Q_320K)
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

            // 监听 ExoPlayer 在线容灾回退与中途断流无缝断点续播
            var lastStreamRetrySongId by remember { mutableStateOf("") }
            var lastStreamRetryTimeMs by remember { mutableStateOf(0L) }
            DisposableEffect(exoPlayer, currentSong, songList, autoFallbackToLocal) {
                val listener = object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("MainActivity", "Player Error encountered: ${error.message}", error)
                        val targetSong = currentSong
                        val resumePos = (exoPlayer?.currentPosition ?: 0L).coerceAtLeast(0L)
                        if (targetSong != null) {
                            val normTitle = SongMatchingResolver.normalizeTrackTitle(targetSong.title)
                            val normArtist = SongMatchingResolver.normalizeArtist(targetSong.artist)

                            if (autoFallbackToLocal) {
                                // 1. 优先尝试切换至本地离线音频文件并保留当前播放进度
                                val matchedLocalSong = songList.firstOrNull {
                                    val hasFile = !it.localFilePath.isNullOrBlank() && java.io.File(it.localFilePath).let { f -> f.exists() && f.length() > 0 }
                                    hasFile && (it.id == targetSong.id || (
                                        normTitle.isNotBlank() && SongMatchingResolver.normalizeTrackTitle(it.title) == normTitle &&
                                        (normArtist.isBlank() || SongMatchingResolver.normalizeArtist(it.artist) == normArtist)
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

                            // 2. 若在线流或柠檬代理流播放中途断流/超时，自动向柠檬服务器重新换取最新流地址并从当前秒数无缝续播
                            val now = System.currentTimeMillis()
                            val canRetryRefresh = lastStreamRetrySongId != targetSong.id || (now - lastStreamRetryTimeMs) > 10_000L
                            if (canRetryRefresh) {
                                lastStreamRetrySongId = targetSong.id
                                lastStreamRetryTimeMs = now
                                Log.i("MainActivity", "Auto-recovering stream for ${targetSong.title} at ${resumePos}ms")
                                PlaybackQueueManager.playSong(
                                    targetSong = targetSong,
                                    context = this@MainActivity,
                                    startPositionMs = resumePos,
                                    forceRefresh = true
                                )
                                return
                            }

                            if (autoFallbackToLocal) {
                                // 3. 若重新刷新仍失败，尝试回退至服务器资料库内同名已归档曲目并保留播放进度
                                val matchedServerSong = songList.firstOrNull {
                                    it.id != targetSong.id &&
                                    it.streamUrl != targetSong.streamUrl &&
                                    (it.streamUrl.contains("/api/play/local") || it.streamUrl.startsWith("http")) &&
                                    normTitle.isNotBlank() && SongMatchingResolver.normalizeTrackTitle(it.title) == normTitle &&
                                    (normArtist.isBlank() || SongMatchingResolver.normalizeArtist(it.artist) == normArtist)
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

                                downloadedFromSongList + complementaryFromDownloads
                            }
                        }

                        var globalSearchQuery by remember { mutableStateOf("") }
                        var currentSearchType by remember { mutableStateOf(SearchContentType.SONG) }
                        val activeLemonServerForSearch = remember(serversList) {
                            serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                                ?: serversList.firstOrNull { it.type == ServerType.LEMON_MUSIC }
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
                            currentScreen = targetNavScreen
                            isSearchDialogOpen = false
                            globalSearchQuery = ""
                            if (targetNavScreen == Screen.LIBRARY) {
                                // 每次点击资料库时自动进行一次后台数据同步刷新
                                val isLocalMode = activeServerName.contains("本地") || activeServerName.contains("已下载")
                                val active = if (isLocalMode) null else serversList.firstOrNull { it.isCurrentActive }
                                if (active != null) {
                                    syncServerPlaylists(active)
                                    syncServerSongsWithToast(active, false)
                                } else {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val dlDir = downloadEngine.getDownloadDir()
                                        LocalMediaScanner.verifyAndSyncAllServerSongDownloadStatus(database, dlDir)
                                    }
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
                                            allCachedSongs = songList,
                                            activeDownloadTasks = activeDownloadTasks,
                                            activeDownloadCount = activeDownloadTasks.size,
                                            currentPlayingSong = currentSong,
                                            isPlaying = isPlaying,
                                            locateSongTrigger = locateSongTrigger,
                                            onListScrollingChange = { isSongListScrolling = it },
                                            onSongClick = { song, queue -> playSongWithQueue(song, queue) },
                                            onDownloadSong = handleDownloadSong,
                                            onDownloadSongWithOptions = handleDownloadWithOptions,
                                            onBatchDownloadSongsWithOptions = handleBatchDownloadWithOptions,
                                            onSelectLocalServer = {
                                                activeServerName = "本地 · 已下载"
                                                currentScreen = Screen.HOME
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
                                            onListScrollingChange = { isSongListScrolling = it },
                                            onSongClick = { song, queue -> playSongWithQueue(song, queue) },
                                            onDownloadSong = handleDownloadSong,
                                            onDownloadSongWithOptions = handleDownloadWithOptions,
                                            onBatchDownloadSongsWithOptions = handleBatchDownloadWithOptions,
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
                                            // 在线模式：仅展示属于当前服务器的真实曲目，与服务器曲库 1:1 严格对齐
                                            songList.filter {
                                                it.serverId == activeConfig.id ||
                                                it.serverId == activeConfig.serverUrl ||
                                                (activeConfig.type == ServerType.LEMON_MUSIC && (it.serverId == "lemon_music" || it.serverId == activeConfig.id))
                                            }
                                        }
                                    }
                                    LocalLibraryScreen(
                                        allSongs = librarySongs,
                                        downloadedSongs = completedDownloadedSongs,
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
                                                Toast.makeText(this@MainActivity, "正在从柠檬音乐同步曲库...", Toast.LENGTH_SHORT).show()
                                                syncServerPlaylists(active)
                                                syncServerSongs(active)
                                            } else {
                                                Toast.makeText(this@MainActivity, "当前为本地模式，可前往设置扫描本地文件", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onGoToSettings = { currentScreen = Screen.SETTINGS },
                                        onSongClick = { song, queue -> playSongWithQueue(song, queue) },
                                        onDownloadSong = handleDownloadSong,
                                        onDownloadSongWithOptions = handleDownloadWithOptions,
                                        onBatchDownloadSongsWithOptions = handleBatchDownloadWithOptions,
                                        onDeleteDownloadedSongs = { songsToDelete ->
                                            downloadEngine.deleteDownloadedSongs(songsToDelete)
                                        },
                                        onToggleFavorite = { songToToggle ->
                                            val updated = songToToggle.copy(isFavorite = !songToToggle.isFavorite)
                                            songList = songList.map { if (it.id == updated.id) updated else it }
                                            if (PlaybackQueueManager.currentSongFlow.value?.id == updated.id) {
                                                PlaybackQueueManager.updateCurrentSong(updated)
                                            }
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                database.songDao().updateFavorite(updated.id, updated.isFavorite)
                                                if (activeConfig != null && activeConfig.type == ServerType.LEMON_MUSIC) {
                                                    val serverPath = LemonMusicProtocol.getServerFilePath(updated.id, updated.streamUrl)
                                                    if (!serverPath.isNullOrBlank()) {
                                                        val protocol = LemonMusicProtocol(
                                                            NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                                            activeConfig.serverUrl,
                                                            activeConfig.username,
                                                            activeConfig.tokenOrApiKey
                                                        )
                                                        protocol.toggleFavoriteOnServer(serverPath, updated.isFavorite)
                                                    }
                                                }
                                            }
                                        },
                                        onOpenDownloads = { currentScreen = Screen.DOWNLOADS },
                                        onRefreshPlaylists = {
                                            if (activeConfig != null) {
                                                Toast.makeText(this@MainActivity, "正在同步在线播放列表...", Toast.LENGTH_SHORT).show()
                                                syncServerPlaylists(activeConfig)
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
                                        contentPadding = innerPadding
                                    )
                                }

                                Screen.DOWNLOADS -> {
                                    DownloadManagerScreen(
                                        activeTasks = activeDownloadTasks,
                                        completedSongs = completedDownloadedSongs,
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
                                            downloadEngine.deleteDownloadedSong(songToDelete)
                                        },
                                        onDeleteDownloadedSongs = { songsToDelete ->
                                            downloadEngine.deleteDownloadedSongs(songsToDelete)
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
                                        onScaleModeChange = { newMode ->
                                            currentScaleMode = newMode
                                            uiPrefs.edit().putString("ui_scale_mode", newMode.name).apply()
                                        },
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
                                            currentScreen = Screen.DOWNLOADS
                                        },
                                        onLocalScanCompleted = {
                                            // 触发歌曲刷新
                                        },
                                        onChooseDownloadDirectory = {
                                            onChooseDownloadFolderResult = { uri ->
                                                val updated = downloadEngine.downloadSettings.value.copy(customDownloadPath = uri.toString())
                                                downloadEngine.downloadSettings.value = updated
                                                Toast.makeText(this@MainActivity, "已成功设定下载存储目录", Toast.LENGTH_SHORT).show()
                                            }
                                            chooseDownloadDirectoryLauncher.launch(null)
                                        },
                                        onImportCustomFolder = {
                                            onImportFolderResult = { uri ->
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
                                    val song = currentSong ?: return@Surface
                                    val isLocalMode = activeServerName.contains("本地") || activeServerName.contains("已下载")
                                    val activeConfig = if (isLocalMode) null else serversList.firstOrNull { it.isCurrentActive }
                                    val existsInLibrary = songList.any { s ->
                                        com.lm.player.core.designsystem.component.isSamePlayingSong(s, song) && (
                                            if (isLocalMode || activeConfig == null) {
                                                s.downloadStatus == DownloadStatus.DOWNLOADED ||
                                                s.serverId in listOf("local_storage", "local_folder", "local_saf") ||
                                                !s.localFilePath.isNullOrBlank()
                                            } else {
                                                s.serverId == activeConfig.id ||
                                                s.serverId == activeConfig.serverUrl ||
                                                (activeConfig.type == ServerType.LEMON_MUSIC && (s.serverId == "lemon_music" || s.serverId == activeConfig.id))
                                            }
                                        )
                                    }
                                    val targetListScreen = when {
                                        playingListScreen == Screen.LIBRARY && existsInLibrary -> Screen.LIBRARY
                                        playingListScreen == Screen.HOME -> Screen.HOME
                                        existsInLibrary -> Screen.LIBRARY
                                        else -> playingListScreen
                                    }
                                    if (currentScreen != targetListScreen) {
                                        currentScreen = targetListScreen
                                    }
                                    locateSongTrigger++
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
                                        imageVector = Icons.Default.MyLocation,
                                        contentDescription = "定位当前播放歌曲",
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
                            val onlineSearchCallback: (suspend (String, OnlineMusicSource) -> List<UnifiedSong>)? = remember(activeLemonServerForSearch) {
                                val srv = activeLemonServerForSearch
                                if (srv != null) {
                                    { keyword, source ->
                                        val client = NetworkClientFactory.createOkHttpClient(this@MainActivity)
                                        val protocol = LemonMusicProtocol(client, srv.serverUrl, srv.username, srv.tokenOrApiKey)
                                        protocol.searchOnline(keyword, source = source.key).getOrNull() ?: emptyList()
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
                                uiPrefs.edit().putBoolean("bg_island_overlay_prompted_v157", true).apply()
                                DynamicIslandManager.requestOverlayPermission(this@MainActivity)
                            },
                            onDismiss = {
                                showBgIslandOverlayPrompt = false
                                uiPrefs.edit().putBoolean("bg_island_overlay_prompted_v157", true).apply()
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

                            var playbackSpeed by remember { mutableStateOf(1.0f) }

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
                                },
                                onSelectSongFromQueue = { queueSong ->
                                    playSongWithQueue(queueSong, currentQueue.ifEmpty { songList })
                                },
                                onToggleLyricsMode = { isLyricsMode = it },
                                onToggleFavorite = {
                                    val updatedSong = song.copy(isFavorite = !song.isFavorite)
                                    songList = songList.map { if (it.id == song.id) updatedSong else it }
                                    PlaybackQueueManager.updateCurrentSong(updatedSong)
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        database.songDao().updateFavorite(song.id, updatedSong.isFavorite)
                                        val activeServer = serversList.firstOrNull { it.isCurrentActive && it.type == ServerType.LEMON_MUSIC }
                                        if (activeServer != null) {
                                            val serverPath = LemonMusicProtocol.getServerFilePath(song.id, song.streamUrl)
                                            if (!serverPath.isNullOrBlank()) {
                                                val protocol = LemonMusicProtocol(
                                                    NetworkClientFactory.createOkHttpClient(this@MainActivity),
                                                    activeServer.serverUrl,
                                                    activeServer.username,
                                                    activeServer.tokenOrApiKey
                                                )
                                                protocol.toggleFavoriteOnServer(serverPath, updatedSong.isFavorite)
                                            }
                                        }
                                    }
                                },
                                onToggleShuffle = { PlaybackQueueManager.setShuffle(!isShuffle) },
                                onToggleRepeat = { PlaybackQueueManager.setRepeat(!isRepeat) },
                                onChangePlaybackSpeed = { speed ->
                                    playbackSpeed = speed
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
        DynamicIslandManager.onAppBackgroundStateChanged(this, inBackground = false)
    }

    override fun onResume() {
        super.onResume()
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        DynamicIslandManager.onAppBackgroundStateChanged(this, inBackground = false)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isChangingConfigurations) {
            DynamicIslandManager.onAppBackgroundStateChanged(this, inBackground = true)
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            DynamicIslandManager.onAppBackgroundStateChanged(this, inBackground = true)
        }
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
