package com.lm.player.core.media

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.common.collect.ImmutableList
import com.lm.player.MainActivity
import com.lm.player.R
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.model.LyricResult
import com.lm.player.core.model.ServerConfig
import com.lm.player.core.model.UnifiedSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/**
 * 挂后台手机灵动岛展示模式枚举
 * 默认使用厂商系统原生上岛 (SYSTEM_ONLY)
 */
enum class IslandDisplayMode(val key: String, val label: String, val subtitle: String) {
    SYSTEM_ONLY(
        key = "SYSTEM_ONLY",
        label = "仅使用厂商系统原生上岛 (默认)",
        subtitle = "使用小米澎湃超级岛/OPPO流体云/vivo原子岛/荣耀灵动胶囊/华为实况窗系统原生媒体上岛"
    ),
    SMART(
        key = "SMART",
        label = "概念版智能悬浮胶囊",
        subtitle = "挂入后台播放时在顶部呈现流体灵动胶囊，回到应用内自动隐身（需悬浮窗权限）"
    ),
    ALWAYS_ON(
        key = "ALWAYS_ON",
        label = "挂后台常驻顶部概念胶囊",
        subtitle = "只要应用在后台且有待播曲目（含暂停态），始终在手机顶部保留概念版灵动胶囊"
    );

    companion object {
        fun fromKey(key: String?): IslandDisplayMode {
            return entries.firstOrNull { it.key == key } ?: SYSTEM_ONLY
        }
    }
}

/**
 * 挂后台全品牌安卓手机灵动岛 (Dynamic Island) 核心引擎
 *
 * 核心机制：
 * 1. 默认使用各大厂商系统原生媒体上岛（小米澎湃 OS 超级岛、OPPO ColorOS 流体云、vivo OriginOS 原子岛、荣耀 MagicOS 灵动胶囊、华为实况窗）：
 *    全程保持纯净、持久的 Media3 MediaSession + MediaStyle 通知绑定，杜绝非法焦点参数覆写或高频重发导致系统断连下岛。
 * 2. 可选挂后台概念版流体悬浮胶囊通道：
 *    当用户切换为概念版胶囊模式时，在后台通过悬浮窗呈现黑胶唱片与逐行歌词胶囊。
 */
@OptIn(UnstableApi::class)
object DynamicIslandManager {

    private const val TAG = "DynamicIslandManager"
    private const val PREFS_NAME = "lemon_settings_prefs"

    private const val KEY_SYSTEM_ISLAND_ENABLED = "island_system_enabled"
    private const val KEY_ISLAND_DISPLAY_MODE = "island_display_mode_v2"
    private const val KEY_SHOW_LYRICS_IN_PILL = "island_show_lyrics_in_pill"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 内存封面 Bitmap、主题强调色与压缩字节缓存 (保证通知栏与系统原生灵动岛 0ms 命中有图封面)
    private val bitmapCache = LruCache<String, Bitmap>(24)
    private val accentColorCache = LruCache<String, Int>(36)
    private val artworkBytesCache = LruCache<String, ByteArray>(24)
    private var fallbackCoverBitmap: Bitmap? = null

    // 持久持有当前活跃的 MediaSession 与 MediaNotification.Provider.Callback，严防无 Session 覆盖导致下岛
    @Volatile
    private var activeMediaSession: MediaSession? = null

    @Volatile
    private var activeNotificationCallback: MediaNotification.Provider.Callback? = null

    private val _isAppInBackgroundFlow = MutableStateFlow(false)
    val isAppInBackgroundFlow: StateFlow<Boolean> = _isAppInBackgroundFlow.asStateFlow()

    private val _systemIslandEnabledFlow = MutableStateFlow(true)
    val systemIslandEnabledFlow: StateFlow<Boolean> = _systemIslandEnabledFlow.asStateFlow()

    private val _islandDisplayModeFlow = MutableStateFlow(IslandDisplayMode.SYSTEM_ONLY)
    val islandDisplayModeFlow: StateFlow<IslandDisplayMode> = _islandDisplayModeFlow.asStateFlow()

    private val _showLyricsInPillFlow = MutableStateFlow(true)
    val showLyricsInPillFlow: StateFlow<Boolean> = _showLyricsInPillFlow.asStateFlow()

    private val _currentLyricLineFlow = MutableStateFlow("")
    val currentLyricLineFlow: StateFlow<String> = _currentLyricLineFlow.asStateFlow()

    private val _nextLyricLineFlow = MutableStateFlow("")
    val nextLyricLineFlow: StateFlow<String> = _nextLyricLineFlow.asStateFlow()

    // 当前歌词行卡拉OK染色进度 (0f..1f)，用于概念版胶囊的逐行流光染色
    private val _currentLineProgressFlow = MutableStateFlow(1f)
    val currentLineProgressFlow: StateFlow<Float> = _currentLineProgressFlow.asStateFlow()

    private var isInitialized = false
    private var backgroundLyricsJob: Job? = null
    private var activeLyricsSongId: String = ""
    private var activeLyricResult: LyricResult = LyricResult()

    private var lastNotifiedSongId: String = ""
    private var lastNotifiedPlaying: Boolean? = null
    private var lastNotifiedFavorite: Boolean? = null
    private var lastNotifiedHasCustomArtwork: Boolean = false

    /**
     * 检测当前设备是否为华为鸿蒙系统 (HarmonyOS 2.0 / 3.0 / 4.0 / 4.2 等)
     * 备注：小米澎湃 OS 超级岛、vivo OriginOS 原子岛等支持将标准 MediaSession 直接提升为状态栏胶囊；
     * 而华为鸿蒙 4.2 系统级「实况窗」对普通第三方 MediaStyle 通知仅放入下拉播控中心，不会自动在状态栏生成胶囊，
     * 因此华为鸿蒙设备自动启用「鸿蒙实况通知扩展 + 顶部流体悬浮灵动胶囊」双通道确保正常上岛。
     */
    fun isHuaweiOrHarmonyOS(): Boolean {
        val manufacturer = (Build.MANUFACTURER ?: "").lowercase(Locale.US)
        val brand = (Build.BRAND ?: "").lowercase(Locale.US)
        if (manufacturer.contains("huawei") || brand.contains("huawei")) {
            return true
        }
        return try {
            val clz = Class.forName("com.huawei.system.BuildEx")
            val method = clz.getMethod("getOsBrand")
            val osBrand = (method.invoke(null) as? String)?.lowercase(Locale.US).orEmpty()
            osBrand.contains("harmony")
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 判断当前是否应启用后台顶部流体悬浮灵动岛胶囊
     * - 小米、vivo 等原生上岛机型在默认 SYSTEM_ONLY 下仅使用系统原生岛；
     * - 华为鸿蒙系统（如 HarmonyOS 4.2）开启灵动岛功能时自动启用顶部流体灵动胶囊以解决无法上岛问题。
     */
    fun shouldUseOverlayIsland(): Boolean {
        if (!_systemIslandEnabledFlow.value) return false
        return _islandDisplayModeFlow.value != IslandDisplayMode.SYSTEM_ONLY || isHuaweiOrHarmonyOS()
    }

    fun bindMediaSession(session: MediaSession?) {
        activeMediaSession = session
    }

    fun ensureInitialized(context: Context) {
        val appCtx = context.applicationContext
        if (isInitialized) return
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _systemIslandEnabledFlow.value = prefs.getBoolean(KEY_SYSTEM_ISLAND_ENABLED, true)
        val defaultMode = if (isHuaweiOrHarmonyOS()) IslandDisplayMode.SMART else IslandDisplayMode.SYSTEM_ONLY
        val savedModeKey = prefs.getString(KEY_ISLAND_DISPLAY_MODE, null)
        _islandDisplayModeFlow.value = if (savedModeKey != null) {
            IslandDisplayMode.fromKey(savedModeKey)
        } else {
            defaultMode
        }
        _showLyricsInPillFlow.value = prefs.getBoolean(KEY_SHOW_LYRICS_IN_PILL, true)
        isInitialized = true
        startBackgroundLyricsEngine(appCtx)
    }

    /**
     * 独立于 Activity 的后台封面预热与歌词同步守护协程
     */
    private fun startBackgroundLyricsEngine(appContext: Context) {
        if (backgroundLyricsJob?.isActive == true) return
        backgroundLyricsJob = mainScope.launch {
            // 1. 监听当前歌曲切换，自动预加载封面、提取专辑流体主题色并解析歌词
            launch {
                PlaybackQueueManager.currentSongFlow.collectLatest { song ->
                    if (song == null) {
                        activeLyricsSongId = ""
                        activeLyricResult = LyricResult()
                        clearLyrics()
                        BackgroundIslandOverlayController.refreshVisibilityAndState(appContext)
                        return@collectLatest
                    }
                    if (song.id != activeLyricsSongId) {
                        activeLyricsSongId = song.id
                        clearLyrics()
                    }
                    // 异步加载封面与主题色，完成后仅在拥有有效 MediaSession 时平滑刷新系统媒体通知封面
                    scope.launch {
                        val bmp = loadSongArtworkBitmap(appContext, song)
                        getOrExtractAccentColor(song.id, bmp)
                        withContext(Dispatchers.Main) {
                            BackgroundIslandOverlayController.refreshVisibilityAndState(appContext)
                            val session = activeMediaSession
                            val player = runCatching { Media3Factory.getSharedExoPlayer(appContext) }.getOrNull()
                            if (session != null && player != null && PlaybackQueueManager.currentSongFlow.value?.id == song.id) {
                                notifySystemIsland(
                                    context = appContext,
                                    mediaSession = session,
                                    exoPlayer = player,
                                    force = true
                                )
                            }
                        }
                    }
                    // 异步加载歌词
                    val loaded = withContext(Dispatchers.IO) {
                        LyricsManager.getCachedLyrics(song.id)?.takeIf { it.lines.isNotEmpty() } ?: run {
                            val activeServer = try {
                                val db = ZdsDatabase.getInstance(appContext)
                                db.serverDao().getAllServers().firstOrNull { it.isCurrentActive }?.let { entity ->
                                    ServerConfig(
                                        id = entity.id,
                                        name = entity.name,
                                        type = entity.type,
                                        serverUrl = entity.serverUrl,
                                        username = entity.username,
                                        tokenOrApiKey = entity.tokenOrApiKey,
                                        saltOrSecret = entity.saltOrSecret,
                                        syncMode = entity.syncMode,
                                        isCurrentActive = entity.isCurrentActive
                                    )
                                }
                            } catch (_: Exception) { null }
                            LyricsManager.loadLyrics(song, appContext, activeServer)
                        }
                    }
                    if (PlaybackQueueManager.currentSongFlow.value?.id == song.id) {
                        activeLyricResult = loaded
                        BackgroundIslandOverlayController.refreshVisibilityAndState(appContext)
                    }
                }
            }

            // 2. 实时歌词匹配驱动器（仅驱动概念版悬浮胶囊视图，绝不高频刷写系统通知以免破坏原生上岛稳定性）
            while (isActive) {
                val song = PlaybackQueueManager.currentSongFlow.value
                val player = runCatching { Media3Factory.getSharedExoPlayer(appContext) }.getOrNull()
                val playing = player?.isPlaying == true
                val needOverlayLyrics = shouldUseOverlayIsland() &&
                        (_isAppInBackgroundFlow.value || playing)
                if (song != null && player != null && needOverlayLyrics) {
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    val lines = activeLyricResult.lines.ifEmpty {
                        LyricsManager.getCachedLyrics(song.id)?.lines.orEmpty()
                    }
                    if (lines.isNotEmpty()) {
                        val idx = lines.indexOfLast { it.timestampMs <= pos }.coerceAtLeast(0)
                        val curObj = lines.getOrNull(idx)
                        val nxtObj = lines.getOrNull(idx + 1)
                        val curText = curObj?.text.orEmpty()
                        val nxtText = nxtObj?.text.orEmpty()

                        val startMs = curObj?.timestampMs ?: 0L
                        val endMs = nxtObj?.timestampMs ?: (startMs + 4000L)
                        val spanMs = (endMs - startMs).coerceIn(600L, 12000L)
                        val lineProg = ((pos - startMs).toFloat() / spanMs.toFloat()).coerceIn(0f, 1f)
                        _currentLineProgressFlow.value = lineProg

                        updateRealtimeLyrics(
                            context = appContext,
                            song = song,
                            currentLine = curText,
                            nextLine = nxtText,
                            lineProgress = lineProg,
                            isPlaying = playing
                        )
                    } else {
                        _currentLineProgressFlow.value = 1f
                    }
                }
                delay(if (playing && needOverlayLyrics) 200L else 600L)
            }
        }
    }

    /**
     * 当应用前后台状态切换时调用：
     * - 仅刷新概念版悬浮胶囊可见性，绝不用无 Session 的通知覆盖系统原生媒体通知（防止切后台几秒后掉岛）
     */
    fun onAppBackgroundStateChanged(context: Context, inBackground: Boolean) {
        ensureInitialized(context)
        _isAppInBackgroundFlow.value = inBackground
        BackgroundIslandOverlayController.refreshVisibilityAndState(context)
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return BackgroundIslandOverlayController.hasOverlayPermission(context)
    }

    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open overlay permission settings", e)
            }
        }
    }

    fun setSystemIslandEnabled(context: Context, enabled: Boolean) {
        ensureInitialized(context)
        _systemIslandEnabledFlow.value = enabled
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SYSTEM_ISLAND_ENABLED, enabled).apply()
        if (!enabled) {
            BackgroundIslandOverlayController.destroy()
        } else {
            BackgroundIslandOverlayController.refreshVisibilityAndState(context)
        }
        PlaybackService.syncSystemIslandMasterSwitch(context, enabled)
        val session = activeMediaSession
        val player = runCatching { Media3Factory.getSharedExoPlayer(context) }.getOrNull()
        if (session != null && player != null) {
            notifySystemIsland(context, mediaSession = session, exoPlayer = player, force = true)
        }
    }

    fun setIslandDisplayMode(context: Context, mode: IslandDisplayMode) {
        ensureInitialized(context)
        _islandDisplayModeFlow.value = mode
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ISLAND_DISPLAY_MODE, mode.key).apply()
        BackgroundIslandOverlayController.refreshVisibilityAndState(context)
    }

    fun setShowLyricsInPill(context: Context, show: Boolean) {
        ensureInitialized(context)
        _showLyricsInPillFlow.value = show
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_LYRICS_IN_PILL, show).apply()
        BackgroundIslandOverlayController.refreshVisibilityAndState(context)
    }

    /**
     * 在设置页中手动测试预览后台灵动岛效果（临时展示 5 秒）
     */
    fun triggerIslandPreview(context: Context) {
        ensureInitialized(context)
        if (!_systemIslandEnabledFlow.value) return
        BackgroundIslandOverlayController.triggerTemporaryPreview(context)
    }

    /**
     * 更新当前播放的实时同步歌词行（仅用于应用内/悬浮胶囊展示，不干扰系统原生 MediaStyle 媒体岛通知）
     */
    fun updateRealtimeLyrics(
        context: Context,
        song: UnifiedSong?,
        currentLine: String,
        nextLine: String = "",
        lineProgress: Float = _currentLineProgressFlow.value,
        isPlaying: Boolean = true
    ) {
        ensureInitialized(context)
        val cleanCurrent = currentLine.trim()
        val cleanNext = nextLine.trim()
        val changed = (_currentLyricLineFlow.value != cleanCurrent) || (_nextLyricLineFlow.value != cleanNext)
        _currentLyricLineFlow.value = cleanCurrent
        _nextLyricLineFlow.value = cleanNext
        _currentLineProgressFlow.value = lineProgress.coerceIn(0f, 1f)

        if (changed && shouldUseOverlayIsland()) {
            BackgroundIslandOverlayController.refreshVisibilityAndState(context)
        }
    }

    fun clearLyrics() {
        _currentLyricLineFlow.value = ""
        _nextLyricLineFlow.value = ""
        _currentLineProgressFlow.value = 1f
    }

    /**
     * 识别当前设备所属厂商与系统原生灵动岛协议类型
     */
    fun getDeviceIslandProfile(): String {
        val manufacturer = (Build.MANUFACTURER ?: "").lowercase(Locale.US)
        val brand = (Build.BRAND ?: "").lowercase(Locale.US)
        val display = (Build.DISPLAY ?: "").lowercase(Locale.US)
        return when {
            manufacturer.contains("xiaomi") || brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
                "小米澎湃 OS · 系统原生超级岛 (MediaSession 原生直连)"
            manufacturer.contains("oppo") || brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme") ->
                "OPPO ColorOS · 系统原生流体云 (MediaSession 原生直连)"
            manufacturer.contains("vivo") || brand.contains("vivo") || brand.contains("iqoo") ->
                "vivo OriginOS · 系统原生原子岛 (MediaSession 原生直连)"
            manufacturer.contains("honor") || brand.contains("honor") ->
                "荣耀 MagicOS · 系统原生灵动胶囊 (MediaSession 原生直连)"
            isHuaweiOrHarmonyOS() ->
                "华为 HarmonyOS · 鸿蒙实况窗 + 顶部流体灵动胶囊双擎适配"
            manufacturer.contains("meizu") || brand.contains("meizu") || display.contains("flyme") ->
                "魅族 Flyme · 系统原生媒体胶囊 (MediaSession 原生直连)"
            manufacturer.contains("samsung") || brand.contains("samsung") ->
                "三星 One UI · 系统原生实时媒体胶囊 (MediaSession 原生直连)"
            else ->
                "Android 系统原生媒体上岛 (MediaSession 原生直连)"
        }
    }

    fun getCachedBitmap(songId: String): Bitmap? = bitmapCache.get(songId)

    fun getCachedArtworkBytes(songId: String): ByteArray? = artworkBytesCache.get(songId)

    fun getCachedAccentColor(songId: String): Int {
        return accentColorCache.get(songId) ?: 0xFF00E5FF.toInt()
    }

    /**
     * 从专辑封面提取高饱和度概念荧光主色调（参考酷狗音乐概念版流体光晕算法）
     */
    fun getOrExtractAccentColor(songId: String, bitmap: Bitmap): Int {
        accentColorCache.get(songId)?.let { return it }
        return try {
            val w = bitmap.width
            val h = bitmap.height
            var rSum = 0L
            var gSum = 0L
            var bSum = 0L
            var count = 0
            val stepX = (w / 8).coerceAtLeast(1)
            val stepY = (h / 8).coerceAtLeast(1)
            val hsv = FloatArray(3)
            for (x in stepX until w - stepX step stepX) {
                for (y in stepY until h - stepY step stepY) {
                    val pixel = bitmap.getPixel(x, y)
                    android.graphics.Color.colorToHSV(pixel, hsv)
                    // 优先采样具备一定饱和度与亮度的彩色像素
                    if (hsv[1] > 0.22f && hsv[2] in 0.20f..0.95f) {
                        rSum += android.graphics.Color.red(pixel)
                        gSum += android.graphics.Color.green(pixel)
                        bSum += android.graphics.Color.blue(pixel)
                        count++
                    }
                }
            }
            val finalColor = if (count >= 3) {
                val avgColor = android.graphics.Color.rgb(
                    (rSum / count).toInt(),
                    (gSum / count).toInt(),
                    (bSum / count).toInt()
                )
                android.graphics.Color.colorToHSV(avgColor, hsv)
                // 提升饱和度与明度以匹配酷狗概念版清透霓虹流光质感
                hsv[1] = hsv[1].coerceIn(0.68f, 0.92f)
                hsv[2] = hsv[2].coerceIn(0.88f, 1.0f)
                android.graphics.Color.HSVToColor(hsv)
            } else {
                // 默认酷狗概念版荧光青色
                0xFF00E5FF.toInt()
            }
            accentColorCache.put(songId, finalColor)
            finalColor
        } catch (_: Exception) {
            0xFF00E5FF.toInt()
        }
    }

    /**
     * 同步或异步加载曲目高清封面 Bitmap (带内存缓存 + 本地内嵌封面提取 + Coil 网络拉取 + 渐变兜底图)
     * 确保各大厂商系统灵动岛与后台顶部悬浮灵动岛 100% 获取到有效封面位图
     */
    suspend fun loadSongArtworkBitmap(context: Context, song: UnifiedSong): Bitmap {
        bitmapCache.get(song.id)?.let { return it }

        var loadedBitmap: Bitmap? = null

        // 1. 优先尝试从本地音频文件提取内嵌 ID3/FLAC 封面
        val localPath = song.localFilePath?.takeIf { it.isNotBlank() }
            ?: song.streamUrl.takeIf { it.startsWith("/") }
        if (!localPath.isNullOrBlank()) {
            try {
                val file = File(localPath)
                if (file.exists() && file.length() > 0) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.absolutePath)
                        val picBytes = retriever.embeddedPicture
                        if (picBytes != null && picBytes.isNotEmpty()) {
                            val opts = BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            }
                            loadedBitmap = BitmapFactory.decodeByteArray(picBytes, 0, picBytes.size, opts)
                        }
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. 使用全局 Coil ImageLoader 加载远程封面或本地 URI
        if (loadedBitmap == null && song.coverUrl.isNotBlank()) {
            try {
                val cleanUrl = if (song.coverUrl.startsWith("//")) "https:${song.coverUrl}" else song.coverUrl
                val request = ImageRequest.Builder(context.applicationContext)
                    .data(cleanUrl)
                    .size(256, 256)
                    .allowHardware(false) // Notification / MediaSession / Canvas Shader 要求非 Hardware Bitmap
                    .build()
                val result = Coil.imageLoader(context.applicationContext).execute(request)
                if (result is SuccessResult) {
                    val drawable = result.drawable
                    if (drawable is BitmapDrawable && drawable.bitmap != null) {
                        loadedBitmap = drawable.bitmap
                    } else {
                        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bmp)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        loadedBitmap = bmp
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Coil cover load skipped for island: ${e.message}")
            }
        }

        // 3. 缩放到标准 256x256 尺寸并写入 JPEG 字节缓存 (供 MediaSession artworkData 投递给系统灵动岛)
        if (loadedBitmap != null) {
            val scaled = if (loadedBitmap.width > 256 || loadedBitmap.height > 256) {
                Bitmap.createScaledBitmap(loadedBitmap, 256, 256, true)
            } else {
                loadedBitmap
            }
            bitmapCache.put(song.id, scaled)
            getOrExtractAccentColor(song.id, scaled)
            try {
                val bos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, bos)
                artworkBytesCache.put(song.id, bos.toByteArray())
            } catch (_: Exception) {}
            return scaled
        }

        return getOrCreateFallbackBitmap()
    }

    /**
     * 生成高颜值 Apple Red 渐变兜底封面 Bitmap，防止无封面歌曲在灵动岛上呈现空白
     */
    fun getOrCreateFallbackBitmap(): Bitmap {
        fallbackCoverBitmap?.let { return it }
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            intArrayOf(0xFFFA233B.toInt(), 0xFFFF5E3A.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 36f, 36f, paint)

        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33FFFFFF
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size * 0.28f, circlePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 96f
            textAlign = Paint.Align.CENTER
        }
        val fontMetrics = textPaint.fontMetrics
        val centerY = size / 2f - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText("♪", size / 2f, centerY, textPaint)

        fallbackCoverBitmap = bmp
        return bmp
    }

    /**
     * 构建 Media3 自定义 MediaNotification.Provider，接管前台服务媒体通知并与各大系统原生灵动岛深度互通
     */
    fun createMediaNotificationProvider(service: PlaybackService): MediaNotification.Provider {
        return object : MediaNotification.Provider {
            override fun createNotification(
                mediaSession: MediaSession,
                customLayout: ImmutableList<CommandButton>,
                actionFactory: MediaNotification.ActionFactory,
                onNotificationChangedCallback: MediaNotification.Provider.Callback
            ): MediaNotification {
                activeMediaSession = mediaSession
                activeNotificationCallback = onNotificationChangedCallback

                val player = Media3Factory.getSharedExoPlayer(service)
                val currentSong = PlaybackQueueManager.currentSongFlow.value
                val cachedBmp = currentSong?.let { bitmapCache.get(it.id) }

                if (currentSong != null && cachedBmp == null) {
                    val targetSongId = currentSong.id
                    scope.launch {
                        val bmp = loadSongArtworkBitmap(service, currentSong)
                        withContext(Dispatchers.Main) {
                            try {
                                if (PlaybackQueueManager.currentSongFlow.value?.id == targetSongId) {
                                    BackgroundIslandOverlayController.refreshVisibilityAndState(service)
                                    val updatedNotif = buildIslandNotification(
                                        context = service,
                                        mediaSession = mediaSession,
                                        exoPlayer = player,
                                        overrideBitmap = bmp
                                    )
                                    lastNotifiedSongId = targetSongId
                                    lastNotifiedPlaying = player.isPlaying
                                    lastNotifiedHasCustomArtwork = true
                                    onNotificationChangedCallback.onNotificationChanged(
                                        MediaNotification(PlaybackService.NOTIFICATION_ID, updatedNotif)
                                    )
                                }
                            } catch (e: Exception) {
                                Log.d(TAG, "Callback notification update skipped: ${e.message}")
                            }
                        }
                    }
                }

                val notification = buildIslandNotification(
                    context = service,
                    mediaSession = mediaSession,
                    exoPlayer = player,
                    overrideBitmap = cachedBmp
                )
                lastNotifiedSongId = currentSong?.id.orEmpty()
                lastNotifiedPlaying = player.isPlaying
                lastNotifiedHasCustomArtwork = (cachedBmp != null)
                return MediaNotification(PlaybackService.NOTIFICATION_ID, notification)
            }

            override fun handleCustomCommand(
                session: MediaSession,
                action: String,
                extras: Bundle
            ): Boolean = false
        }
    }

    /**
     * 构建符合各大厂商系统原生媒体上岛（小米澎湃超级岛、OPPO流体云、vivo原子岛、荣耀灵动胶囊、华为实况窗）的纯净标准 MediaStyle 媒体通知：
     * - 当「启用挂后台手机灵动岛」开启时：绑定有效 MediaSession Token，使用 MediaStyle + CATEGORY_TRANSPORT 激活系统原生上岛
     * - 当「启用挂后台手机灵动岛」关闭时：剥离 MediaStyle 与 EXTRA_MEDIA_SESSION，降级为普通后台服务通知并注入各厂商禁岛标志，彻底禁止系统上岛
     */
    fun buildIslandNotification(
        context: Context,
        mediaSession: MediaSession?,
        exoPlayer: ExoPlayer?,
        overrideBitmap: Bitmap? = null
    ): Notification {
        ensureInitialized(context)

        val islandEnabled = _systemIslandEnabledFlow.value
        val resolvedSession = mediaSession ?: activeMediaSession
        val currentSong = PlaybackQueueManager.currentSongFlow.value
        val currentMediaItem = exoPlayer?.currentMediaItem
        val rawTitle = currentSong?.title?.ifBlank { null }
            ?: currentMediaItem?.mediaMetadata?.title?.toString()?.ifBlank { null }
            ?: context.getString(R.string.app_name)
        val rawArtist = currentSong?.artist?.ifBlank { null }
            ?: currentMediaItem?.mediaMetadata?.artist?.toString()?.ifBlank { null }
            ?: "柠檬音乐 Hi-Res"
        val rawAlbum = currentSong?.album?.ifBlank { null }
            ?: currentMediaItem?.mediaMetadata?.albumTitle?.toString().orEmpty()

        val isPlaying = exoPlayer?.isPlaying == true
        val isActiveSession = isPlaying || exoPlayer?.playWhenReady == true || currentSong != null

        val displayTitle = rawTitle
        val displayContent = if (rawAlbum.isNotBlank() && rawAlbum != rawArtist) {
            "$rawArtist · $rawAlbum"
        } else {
            rawArtist
        }

        val coverBitmap = overrideBitmap
            ?: currentSong?.let { bitmapCache.get(it.id) }
            ?: getOrCreateFallbackBitmap()

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val prevPendingIntent = buildServiceCommandPendingIntent(context, PlaybackService.CMD_PREV, 101)
        val togglePendingIntent = buildServiceCommandPendingIntent(context, PlaybackService.CMD_TOGGLE, 102)
        val nextPendingIntent = buildServiceCommandPendingIntent(context, PlaybackService.CMD_NEXT, 103)
        val favPendingIntent = buildServiceCommandPendingIntent(context, PlaybackService.CMD_TOGGLE_FAVORITE, 104)

        val isFavorite = currentSong?.isFavorite == true
        val favIcon = if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border
        val favTitle = if (isFavorite) "已收藏" else "收藏"

        val builder = NotificationCompat.Builder(context, PlaybackService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(coverBitmap)
            .setContentTitle(displayTitle)
            .setContentText(displayContent)
            .setSubText(rawAlbum.takeIf { it.isNotBlank() })
            .setContentIntent(contentPendingIntent)
            .setOngoing(isActiveSession)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColorized(islandEnabled)
            .setCategory(if (islandEnabled) NotificationCompat.CATEGORY_TRANSPORT else NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(if (islandEnabled) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_SECRET)
            .setPriority(if (islandEnabled) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_media_previous,
                    "上一首",
                    prevPendingIntent
                ).build()
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (isPlaying) "暂停" else "播放",
                    togglePendingIntent
                ).build()
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_media_next,
                    "下一首",
                    nextPendingIntent
                ).build()
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    favIcon,
                    favTitle,
                    favPendingIntent
                ).build()
            )

        if (islandEnabled && resolvedSession != null) {
            builder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(resolvedSession)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            // 仅在开启灵动岛且为华为鸿蒙系统设备上注入鸿蒙实况窗/状态栏胶囊扩展参数
            if (isHuaweiOrHarmonyOS()) {
                try {
                    val hwExtras = Bundle().apply {
                        putBoolean("hw_enable_live_notification", true)
                        putBoolean("hw_live_view", true)
                        putInt("hw_live_notification_type", 2)
                        putString("hw_capsule_title", displayTitle)
                        putString("hw_capsule_content", rawArtist)
                        putInt("hw_capsule_status", if (isPlaying) 1 else 0)
                    }
                    builder.addExtras(hwExtras)
                } catch (_: Throwable) {}
            }
        } else {
            // 灵动岛开关关闭时：显式注入各厂商关闭实况胶囊/焦点通知/原子岛/流体云参数
            try {
                val disableExtras = Bundle().apply {
                    putBoolean("hw_enable_live_notification", false)
                    putBoolean("hw_live_view", false)
                    putInt("hw_capsule_status", 0)
                    putBoolean("miui.focus.enable", false)
                    putBoolean("oplus_fluid_cloud_enable", false)
                    putBoolean("vivo.originos.atomic_island.enable", false)
                }
                builder.addExtras(disableExtras)
            } catch (_: Throwable) {}
        }

        val notification = builder.build()
        if (!islandEnabled) {
            // 彻底移除 EXTRA_MEDIA_SESSION，防止小米澎湃 OS / vivo OriginOS / OPPO ColorOS 自动提取 Session 上岛
            runCatching {
                notification.extras?.remove(Notification.EXTRA_MEDIA_SESSION)
                notification.extras?.remove("android.mediaSession")
            }
        }
        if (isActiveSession) {
            notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        }
        return notification
    }

    private fun buildServiceCommandPendingIntent(
        context: Context,
        command: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_MEDIA_COMMAND
            putExtra(PlaybackService.EXTRA_COMMAND, command)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, requestCode, intent, flags)
        } else {
            PendingIntent.getService(context, requestCode, intent, flags)
        }
    }

    /**
     * 刷新系统前台通知、各大厂商原生灵动岛与后台顶部悬浮灵动岛状态
     * 仅在曲目切换、播放/暂停状态改变、开关切换或封面加载完成时更新
     */
    fun notifySystemIsland(
        context: Context,
        mediaSession: MediaSession?,
        exoPlayer: ExoPlayer?,
        force: Boolean = false
    ) {
        ensureInitialized(context)
        BackgroundIslandOverlayController.refreshVisibilityAndState(context)

        val resolvedSession = mediaSession ?: activeMediaSession ?: return
        val player = exoPlayer ?: runCatching { Media3Factory.getSharedExoPlayer(context) }.getOrNull() ?: return

        val songId = PlaybackQueueManager.currentSongFlow.value?.id.orEmpty()
        val isFavorite = PlaybackQueueManager.currentSongFlow.value?.isFavorite == true
        val isPlaying = player.isPlaying
        val hasCustomArtwork = songId.isNotEmpty() && bitmapCache.get(songId) != null

        if (!force &&
            songId == lastNotifiedSongId &&
            isPlaying == lastNotifiedPlaying &&
            isFavorite == lastNotifiedFavorite &&
            hasCustomArtwork == lastNotifiedHasCustomArtwork
        ) {
            return
        }

        lastNotifiedSongId = songId
        lastNotifiedPlaying = isPlaying
        lastNotifiedFavorite = isFavorite
        lastNotifiedHasCustomArtwork = hasCustomArtwork

        try {
            val notification = buildIslandNotification(
                context = context,
                mediaSession = resolvedSession,
                exoPlayer = player,
                overrideBitmap = songId.takeIf { it.isNotEmpty() }?.let { bitmapCache.get(it) }
            )
            val callback = activeNotificationCallback
            if (_systemIslandEnabledFlow.value && callback != null) {
                callback.onNotificationChanged(
                    MediaNotification(PlaybackService.NOTIFICATION_ID, notification)
                )
            } else {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(PlaybackService.NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error pushing island notification", e)
        }
    }
}
