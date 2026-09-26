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
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
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
import com.lm.player.core.model.UnifiedSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/**
 * 灵动岛展示模式枚举
 */
enum class IslandDisplayMode(val key: String, val label: String, val subtitle: String) {
    SMART(
        key = "SMART",
        label = "智能灵动上岛 (推荐)",
        subtitle = "切歌、状态切换及实时歌词更新时灵动呈现，点击可展开完整操控面板"
    ),
    ALWAYS_ON(
        key = "ALWAYS_ON",
        label = "常驻顶部灵动胶囊",
        subtitle = "播放音乐且未打开全屏播放器时，始终悬浮于屏幕顶部居中区域"
    ),
    SYSTEM_ONLY(
        key = "SYSTEM_ONLY",
        label = "仅使用系统原生上岛",
        subtitle = "关闭应用内顶部胶囊，仅向小米超级岛/OPPO流体云/vivo原子岛/荣耀灵动胶囊推送"
    );

    companion object {
        fun fromKey(key: String?): IslandDisplayMode {
            return entries.firstOrNull { it.key == key } ?: SMART
        }
    }
}

/**
 * 全品牌安卓灵动岛 (Dynamic Island) 与系统级实时歌词上岛核心引擎
 *
 * 全面适配：
 * 1. 小米澎湃 OS (HyperOS 1/2/3) 超级岛 / 焦点通知 + MediaStyle 媒体上岛
 * 2. OPPO / 一加 / 真我 (ColorOS) 流体云 (Fluid Cloud) 胶囊与进度同步
 * 3. vivo / iQOO (OriginOS 4/5) 原子岛 (Atomic Island) 音频播控胶囊
 * 4. 荣耀 (MagicOS) 灵动胶囊 (Magic Capsule) 与华为实况窗
 * 5. 魅族 (Flyme) 状态栏歌词协议 (FLAG_ALWAYS_SHOW_TICKER / FLAG_ONLY_UPDATE_TICKER)
 * 6. Android 16+ Promoted Ongoing 实时活动通知及第三方超级岛/状态栏歌词广播协议
 * 7. 应用内高帧率灵动岛交互胶囊 (支持封面旋转呼吸、音频频谱律动、实时双行歌词与展开式播控)
 */
@OptIn(UnstableApi::class)
object DynamicIslandManager {

    private const val TAG = "DynamicIslandManager"
    private const val PREFS_NAME = "lemon_settings_prefs"

    private const val KEY_SYSTEM_ISLAND_ENABLED = "island_system_enabled"
    private const val KEY_LIVE_LYRICS_ON_ISLAND = "island_live_lyrics_enabled"
    private const val KEY_ISLAND_DISPLAY_MODE = "island_display_mode"
    private const val KEY_SHOW_LYRICS_IN_PILL = "island_show_lyrics_in_pill"

    // 魅族 Flyme / 类原生 / 状态栏歌词通用 Ticker Flag
    private const val FLAG_ALWAYS_SHOW_TICKER = 0x01000000
    private const val FLAG_ONLY_UPDATE_TICKER = 0x02000000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 内存封面 Bitmap 与压缩字节缓存 (保证通知栏与灵动岛 0ms 命中有图封面)
    private val bitmapCache = LruCache<String, Bitmap>(20)
    private val artworkBytesCache = LruCache<String, ByteArray>(20)
    private var fallbackCoverBitmap: Bitmap? = null

    private val _systemIslandEnabledFlow = MutableStateFlow(true)
    val systemIslandEnabledFlow: StateFlow<Boolean> = _systemIslandEnabledFlow.asStateFlow()

    private val _liveLyricsOnIslandFlow = MutableStateFlow(true)
    val liveLyricsOnIslandFlow: StateFlow<Boolean> = _liveLyricsOnIslandFlow.asStateFlow()

    private val _islandDisplayModeFlow = MutableStateFlow(IslandDisplayMode.SMART)
    val islandDisplayModeFlow: StateFlow<IslandDisplayMode> = _islandDisplayModeFlow.asStateFlow()

    private val _showLyricsInPillFlow = MutableStateFlow(true)
    val showLyricsInPillFlow: StateFlow<Boolean> = _showLyricsInPillFlow.asStateFlow()

    private val _currentLyricLineFlow = MutableStateFlow("")
    val currentLyricLineFlow: StateFlow<String> = _currentLyricLineFlow.asStateFlow()

    private val _nextLyricLineFlow = MutableStateFlow("")
    val nextLyricLineFlow: StateFlow<String> = _nextLyricLineFlow.asStateFlow()

    private val _manualExpandTriggerFlow = MutableStateFlow(0L)
    val manualExpandTriggerFlow: StateFlow<Long> = _manualExpandTriggerFlow.asStateFlow()

    private var isInitialized = false
    private var lastNotifiedSongId: String = ""
    private var lastNotifiedPlaying: Boolean? = null
    private var lastNotifiedLyric: String = ""

    fun ensureInitialized(context: Context) {
        if (isInitialized) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _systemIslandEnabledFlow.value = prefs.getBoolean(KEY_SYSTEM_ISLAND_ENABLED, true)
        _liveLyricsOnIslandFlow.value = prefs.getBoolean(KEY_LIVE_LYRICS_ON_ISLAND, true)
        _islandDisplayModeFlow.value = IslandDisplayMode.fromKey(prefs.getString(KEY_ISLAND_DISPLAY_MODE, IslandDisplayMode.SMART.key))
        _showLyricsInPillFlow.value = prefs.getBoolean(KEY_SHOW_LYRICS_IN_PILL, true)
        isInitialized = true
    }

    fun setSystemIslandEnabled(context: Context, enabled: Boolean) {
        ensureInitialized(context)
        _systemIslandEnabledFlow.value = enabled
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SYSTEM_ISLAND_ENABLED, enabled).apply()
    }

    fun setLiveLyricsOnIsland(context: Context, enabled: Boolean) {
        ensureInitialized(context)
        _liveLyricsOnIslandFlow.value = enabled
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LIVE_LYRICS_ON_ISLAND, enabled).apply()
    }

    fun setIslandDisplayMode(context: Context, mode: IslandDisplayMode) {
        ensureInitialized(context)
        _islandDisplayModeFlow.value = mode
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ISLAND_DISPLAY_MODE, mode.key).apply()
    }

    fun setShowLyricsInPill(context: Context, show: Boolean) {
        ensureInitialized(context)
        _showLyricsInPillFlow.value = show
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_LYRICS_IN_PILL, show).apply()
    }

    /**
     * 触发应用内灵动岛展开动效并同步刷新系统灵动岛通知
     */
    fun triggerIslandPreview(context: Context) {
        ensureInitialized(context)
        _manualExpandTriggerFlow.value = System.currentTimeMillis()
    }

    /**
     * 更新当前播放的实时同步歌词行，并按需推送至系统灵动岛与状态栏
     */
    fun updateRealtimeLyrics(
        context: Context,
        song: UnifiedSong?,
        currentLine: String,
        nextLine: String = "",
        isPlaying: Boolean = true
    ) {
        ensureInitialized(context)
        val cleanCurrent = currentLine.trim()
        val cleanNext = nextLine.trim()
        val changed = (_currentLyricLineFlow.value != cleanCurrent) || (_nextLyricLineFlow.value != cleanNext)
        _currentLyricLineFlow.value = cleanCurrent
        _nextLyricLineFlow.value = cleanNext

        if (changed && song != null && _systemIslandEnabledFlow.value && _liveLyricsOnIslandFlow.value) {
            dispatchThirdPartyLyricBroadcast(context, song, cleanCurrent, isPlaying)
        }
    }

    fun clearLyrics() {
        _currentLyricLineFlow.value = ""
        _nextLyricLineFlow.value = ""
    }

    /**
     * 识别当前设备所属厂商与系统灵动岛协议类型
     */
    fun getDeviceIslandProfile(): String {
        val manufacturer = (Build.MANUFACTURER ?: "").lowercase(Locale.US)
        val brand = (Build.BRAND ?: "").lowercase(Locale.US)
        val display = (Build.DISPLAY ?: "").lowercase(Locale.US)
        return when {
            manufacturer.contains("xiaomi") || brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
                "小米澎湃 OS · 超级岛 / 焦点媒体通知"
            manufacturer.contains("oppo") || brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme") ->
                "OPPO ColorOS · 流体云 (Fluid Cloud)"
            manufacturer.contains("vivo") || brand.contains("vivo") || brand.contains("iqoo") ->
                "vivo OriginOS · 原子岛 (Atomic Island)"
            manufacturer.contains("honor") || brand.contains("honor") ->
                "荣耀 MagicOS · 灵动胶囊 (Magic Capsule)"
            manufacturer.contains("huawei") || brand.contains("huawei") ->
                "华为 HarmonyOS · 实况窗 (Live View)"
            manufacturer.contains("meizu") || brand.contains("meizu") || display.contains("flyme") ->
                "魅族 Flyme · 灵动岛与状态栏实时歌词"
            manufacturer.contains("samsung") || brand.contains("samsung") ->
                "三星 One UI · 实时媒体胶囊 (Now Bar)"
            else ->
                "Android MediaSession 原生上岛 + 内置灵动岛双引擎"
        }
    }

    fun getCachedBitmap(songId: String): Bitmap? = bitmapCache.get(songId)

    fun getCachedArtworkBytes(songId: String): ByteArray? = artworkBytesCache.get(songId)

    /**
     * 同步或异步加载曲目高清封面 Bitmap (带内存缓存 + 本地内嵌封面提取 + Coil 网络拉取 + 渐变兜底图)
     * 确保各大厂商系统灵动岛 (HyperOS/OriginOS/ColorOS/MagicOS) 100% 获取到有效封面位图
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
                    .allowHardware(false) // Notification RemoteViews / MediaSession 要求非 Hardware Bitmap
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
     * 生成高颜值 Apple Red 渐变兜底封面 Bitmap，防止无封面歌曲在系统灵动岛上呈现空白
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

        // 绘制中心音符圆形修饰
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
     * 构建 Media3 自定义 MediaNotification.Provider，接管前台服务媒体通知并与各大系统灵动岛深度互通
     */
    fun createMediaNotificationProvider(service: PlaybackService): MediaNotification.Provider {
        return object : MediaNotification.Provider {
            override fun createNotification(
                mediaSession: MediaSession,
                customLayout: ImmutableList<CommandButton>,
                actionFactory: MediaNotification.ActionFactory,
                onNotificationChangedCallback: MediaNotification.Provider.Callback
            ): MediaNotification {
                val player = Media3Factory.getSharedExoPlayer(service)
                val currentSong = PlaybackQueueManager.currentSongFlow.value
                val isPlaying = player.isPlaying
                val lyricLine = _currentLyricLineFlow.value

                // 若当前曲目封面尚未缓存，后台异步加载完成后主动回调刷新系统灵动岛封面与 MediaMetadata
                if (currentSong != null && bitmapCache.get(currentSong.id) == null) {
                    scope.launch {
                        val bmp = loadSongArtworkBitmap(service, currentSong)
                        val bytes = artworkBytesCache.get(currentSong.id)
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            try {
                                // 同步将封面字节注入当前 MediaItem 的 MediaMetadata，使澎湃OS/OriginOS/ColorOS灵动岛立即显示专辑图
                                val curItem = player.currentMediaItem
                                if (curItem != null && curItem.mediaId == currentSong.id && bytes != null) {
                                    val newMeta = curItem.mediaMetadata.buildUpon()
                                        .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                        .build()
                                    val newItem = curItem.buildUpon().setMediaMetadata(newMeta).build()
                                    player.replaceMediaItem(player.currentMediaItemIndex, newItem)
                                }
                                val updatedNotif = buildIslandNotification(
                                    context = service,
                                    mediaSession = mediaSession,
                                    exoPlayer = player,
                                    currentLyric = _currentLyricLineFlow.value,
                                    overrideBitmap = bmp,
                                    isTickerOnlyUpdate = false
                                )
                                onNotificationChangedCallback.onNotificationChanged(
                                    MediaNotification(PlaybackService.NOTIFICATION_ID, updatedNotif)
                                )
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
                    currentLyric = lyricLine,
                    overrideBitmap = currentSong?.let { bitmapCache.get(it.id) },
                    isTickerOnlyUpdate = false
                )
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
     * 构建符合 Android MediaStyle + 小米澎湃超级岛/焦点通知 + OPPO流体云 + vivo原子岛 + 荣耀灵动胶囊 + Flyme状态栏歌词的综合媒体通知
     */
    fun buildIslandNotification(
        context: Context,
        mediaSession: MediaSession?,
        exoPlayer: ExoPlayer?,
        currentLyric: String = _currentLyricLineFlow.value,
        overrideBitmap: Bitmap? = null,
        isTickerOnlyUpdate: Boolean = false
    ): Notification {
        ensureInitialized(context)

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
        val useLiveLyric = _systemIslandEnabledFlow.value && _liveLyricsOnIslandFlow.value && currentLyric.isNotBlank()

        val displayTitle = rawTitle
        val displayContent = if (useLiveLyric) {
            "♪ $currentLyric"
        } else if (rawAlbum.isNotBlank()) {
            "$rawArtist · $rawAlbum"
        } else {
            rawArtist
        }
        val tickerText = if (useLiveLyric) currentLyric else "$rawTitle - $rawArtist"

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

        val extras = Bundle().apply {
            putString("android.substName", context.getString(R.string.app_name))
            if (_systemIslandEnabledFlow.value) {
                // 1. 小米澎湃 OS (HyperOS) 焦点通知与超级岛扩展参数
                putBoolean("miui.focus.enable", true)
                putString("miui.focus.ticker", tickerText)
                try {
                    val miuiParam = JSONObject().apply {
                        put("protocol", 1)
                        put("scene", "music")
                        put("ticker", tickerText)
                        put("title", displayTitle)
                        put("content", displayContent)
                        put("enableFloat", false)
                        put("updatable", true)
                    }
                    putString("miui.focus.param", miuiParam.toString())
                } catch (_: Exception) {}

                // 2. OPPO / OnePlus / Realme ColorOS 流体云 (Fluid Cloud) 扩展参数
                putBoolean("oplus.fluids.enable", true)
                putBoolean("coloros.fluid.cloud", true)
                putInt("android.ongoingActivityNoti.style", 1)

                // 3. vivo / iQOO OriginOS 原子岛 (Atomic Island) 扩展参数
                putBoolean("vivo.originos.atomic.island", true)
                putBoolean("android.media.extra.ATOMIC_ISLAND_ENABLED", true)

                // 4. 荣耀 MagicOS 灵动胶囊 & 华为 HarmonyOS 实况窗扩展参数
                putBoolean("honor.magic.capsule.enable", true)
                putBoolean("hw_live_view_enabled", true)

                // 5. Android 16+ 实时活动胶囊 (Promoted Ongoing)
                putBoolean("android.app.extra.PROMOTED_ONGOING", true)

                // 6. 实时状态栏/灵动岛歌词扩展字段
                if (useLiveLyric) {
                    putString("lyric_line", currentLyric)
                    putInt("ticker_icon_switch", 0)
                }
            }
        }

        val builder = NotificationCompat.Builder(context, PlaybackService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(coverBitmap)
            .setContentTitle(displayTitle)
            .setContentText(displayContent)
            .setSubText(if (useLiveLyric) rawArtist else null)
            .setTicker(tickerText)
            .setContentIntent(contentPendingIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addExtras(extras)
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

        // 关键：挂载 Media3 标准 MediaStyle 并绑定 MediaSession，使澎湃OS/ColorOS/OriginOS/MagicOS 系统识别为原生媒体灵动岛
        if (mediaSession != null) {
            builder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(mediaSession)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }

        val notification = builder.build()
        if (useLiveLyric) {
            notification.flags = notification.flags or FLAG_ALWAYS_SHOW_TICKER
            if (isTickerOnlyUpdate) {
                notification.flags = notification.flags or FLAG_ONLY_UPDATE_TICKER
            }
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
     * 刷新系统前台通知与各大厂商灵动岛胶囊状态 (带去重节流)
     */
    fun notifySystemIsland(
        context: Context,
        mediaSession: MediaSession?,
        exoPlayer: ExoPlayer?,
        force: Boolean = false
    ) {
        ensureInitialized(context)
        if (!_systemIslandEnabledFlow.value && !force) return

        val songId = PlaybackQueueManager.currentSongFlow.value?.id.orEmpty()
        val isPlaying = exoPlayer?.isPlaying == true
        val lyric = _currentLyricLineFlow.value

        if (!force && songId == lastNotifiedSongId && isPlaying == lastNotifiedPlaying && lyric == lastNotifiedLyric) {
            return
        }
        val isTickerOnly = !force && songId == lastNotifiedSongId && isPlaying == lastNotifiedPlaying && lyric != lastNotifiedLyric

        lastNotifiedSongId = songId
        lastNotifiedPlaying = isPlaying
        lastNotifiedLyric = lyric

        try {
            val notification = buildIslandNotification(
                context = context,
                mediaSession = mediaSession,
                exoPlayer = exoPlayer,
                currentLyric = lyric,
                isTickerOnlyUpdate = isTickerOnly
            )
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(PlaybackService.NOTIFICATION_ID, notification)
        } catch (e: Throwable) {
            Log.e(TAG, "Error pushing island notification", e)
        }
    }

    /**
     * 发送标准音乐元数据与实时歌词广播，兼容安卓第三方超级岛/灵动鸟/状态栏歌词插件及车载蓝牙仪表盘
     */
    fun dispatchThirdPartyLyricBroadcast(
        context: Context,
        song: UnifiedSong,
        lyricLine: String,
        isPlaying: Boolean
    ) {
        try {
            // 1. 标准 Android 音乐状态与元数据广播
            val metaIntent = Intent("com.android.music.metachanged").apply {
                putExtra("id", song.id)
                putExtra("track", if (lyricLine.isNotBlank()) lyricLine else song.title)
                putExtra("artist", song.artist)
                putExtra("album", song.album)
                putExtra("playing", isPlaying)
                putExtra("lyric", lyricLine)
            }
            context.sendBroadcast(metaIntent)

            // 2. 通用状态栏歌词与第三方安卓灵动岛插件广播
            val lyricIntent = Intent("com.lm.player.ACTION_LYRIC_UPDATED").apply {
                putExtra("song_id", song.id)
                putExtra("title", song.title)
                putExtra("artist", song.artist)
                putExtra("lyric", lyricLine)
                putExtra("is_playing", isPlaying)
            }
            context.sendBroadcast(lyricIntent)
        } catch (_: Exception) {}
    }
}
