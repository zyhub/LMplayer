package com.lm.player.core.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.lm.player.MainActivity
import com.lm.player.R
import com.lm.player.core.media.PlaybackQueueManager
import com.lm.player.core.media.PlaybackService
import com.lm.player.core.model.UnifiedSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * 桌面播放器小部件统一控制器：负责 4x2 / 2x2（歌词 + 播控）、3x3（顶部搜索框 + 歌词）与 2x1（播控）四种尺寸的 RemoteViews 构建
 * （视觉按 Antigravity_设计稿/桌面小组件/标注说明.md，数据约定见 WidgetUiModels.kt；4x2 及以上带 3~5 行歌词）、
 * 按钮命令分发（播放/切歌/收藏均直接走 PlaybackQueueManager 全链路）、
 * 封面异步加载（内存 + 磁盘二级缓存）与播放进度秒级刷新。
 */
object MusicWidgetController {

    private const val TAG = "MusicWidget"

    const val ACTION_WIDGET_TOGGLE = "com.lm.player.action.WIDGET_TOGGLE"
    const val ACTION_WIDGET_NEXT = "com.lm.player.action.WIDGET_NEXT"
    const val ACTION_WIDGET_PREV = "com.lm.player.action.WIDGET_PREV"
    const val ACTION_WIDGET_FAVORITE = "com.lm.player.action.WIDGET_FAVORITE"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val coverMemoryCache = LruCache<String, Bitmap>(24)
    private val accentColorCache = LruCache<String, Int>(36)
    private val bgBlurCache = LruCache<String, Bitmap>(24)
    private val pendingCoverLoads = mutableSetOf<String>()

    /**
     * 各尺寸小部件规格。
     * @param lyricLines 默认歌词行数（0 = 不显示歌词）；即上限；桌面给出的高度放不下时按高度减到最少 3 行
     * @param chromeDp 除歌词区外的固定占高（封面行 + 按钮行 + 内边距），用于按高度估算可容纳的歌词行数
     * @param lineDp 单行歌词占高
     */
    private class Spec(
        val provider: Class<out AppWidgetProvider>,
        val layoutId: Int,
        val lyricLines: Int,
        val chromeDp: Int = 0,
        val lineDp: Int = 18,
        /** 布局里当前句所在的槽位（0 起）：4x2 / 3x3 都是第 2 行 */
        val activeSlot: Int = 1,
        /** 高度不够减行时依次隐藏的槽位（3x3 先隐藏最下一行） */
        val dropOrder: List<Int> = emptyList(),
        /** 歌手栏是否拼上专辑名「歌手 · 专辑」（3x3） */
        val artistWithAlbum: Boolean = false,
        /** 按桌面实际尺寸（宽, 高 dp）给出封面边长；竖屏手机格子更高时封面相应放大，避免内容稀疏、比例失调 */
        val coverDp: ((Int, Int) -> Float)? = null,
        /** 高度放不下时歌词最少保留几行（4x2 / 3x3 为 3 行，2x2 为 2 行） */
        val minLyricLines: Int = 3,
        /** 实际高度低于该值（dp）时隐藏歌手行，保证按钮完整可见（2x1 在手机上只有一行高） */
        val hideArtistBelowDp: Int = 0
    )

    private val specs: List<Spec> by lazy {
        listOf(
            Spec(
                MusicWidgetProvider3x3::class.java, R.layout.widget_3x3, 4, chromeDp = 154, lineDp = 20,
                dropOrder = listOf(3), artistWithAlbum = true,
                coverDp = { _, h -> (44f + (h - 250) / 5f).coerceIn(44f, 60f) }
            ),
            Spec(
                MusicWidgetProvider4x2::class.java, R.layout.widget_4x2, 3, chromeDp = 80, lineDp = 20,
                coverDp = { _, h -> (h - 96f).coerceIn(52f, 84f) }
            ),
            Spec(
                MusicWidgetProvider2x2::class.java, R.layout.widget_2x2, 3, chromeDp = 116, lineDp = 18,
                activeSlot = 0, dropOrder = listOf(2), minLyricLines = 2,
                coverDp = { _, h -> (40f + (h - 170) / 4f).coerceIn(36f, 52f) }
            ),
            Spec(
                MusicWidgetProvider2x1::class.java, R.layout.widget_2x1, 0, hideArtistBelowDp = 86,
                coverDp = { _, h -> (h - 16f).coerceIn(36f, 58f) }
            )
        )
    }

    private val lyricSlotIds = intArrayOf(
        R.id.widget_lyric_1, R.id.widget_lyric_2, R.id.widget_lyric_3, R.id.widget_lyric_4
    )

    private const val BRAND_RED = 0xFFFA2D48.toInt()

    private fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun accentOf(song: UnifiedSong?): Int? =
        song?.coverUrl?.takeIf { it.isNotBlank() }?.let { accentColorCache.get(it) }

    /**
     * 文字用主色的对比度兜底（Antigravity 交接建议 2）：
     * 深色模式下主色过暗就提亮、浅色模式下过亮就压暗；无主色时用品牌红。
     */
    private fun safeAccent(accent: Int?, night: Boolean): Int {
        if (accent == null) return BRAND_RED
        val lum = androidx.core.graphics.ColorUtils.calculateLuminance(accent)
        return when {
            night && lum < 0.25 -> androidx.core.graphics.ColorUtils.blendARGB(accent, android.graphics.Color.WHITE, 0.4f)
            !night && lum > 0.6 -> androidx.core.graphics.ColorUtils.blendARGB(accent, android.graphics.Color.BLACK, 0.4f)
            else -> accent
        }
    }

    private fun idsOf(mgr: AppWidgetManager, context: Context, spec: Spec): IntArray =
        runCatching { mgr.getAppWidgetIds(ComponentName(context, spec.provider)) }.getOrNull() ?: IntArray(0)

    /**
     * 部分电视盒子 / 车机系统没有桌面小部件服务（无 FEATURE_APP_WIDGETS），此时 getInstance 返回 null，
     * 直接使用会空指针闪退；统一经此取用，拿不到就当作「没有小部件」。
     */
    private fun widgetManager(context: Context): AppWidgetManager? =
        runCatching { AppWidgetManager.getInstance(context) as AppWidgetManager? }.getOrNull()

    fun hasEnabledWidgets(context: Context): Boolean {
        val mgr = widgetManager(context) ?: return false
        return specs.any { idsOf(mgr, context, it).isNotEmpty() }
    }

    private fun hasLyricWidgets(context: Context): Boolean {
        val mgr = widgetManager(context) ?: return false
        return specs.any { it.lyricLines > 0 && idsOf(mgr, context, it).isNotEmpty() }
    }

    /** 按桌面实际给出的高度估算歌词行数（3~5 行）；拿不到尺寸时用该规格默认值 */
    private fun lyricLinesFor(mgr: AppWidgetManager, id: Int, spec: Spec): Int {
        if (spec.lyricLines <= 0) return 0
        val opts = runCatching { mgr.getAppWidgetOptions(id) }.getOrNull()
        val h = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
        if (h <= 0) return spec.lyricLines
        return ((h - spec.chromeDp) / spec.lineDp).coerceIn(minOf(spec.minLyricLines, spec.lyricLines), spec.lyricLines)
    }

    // ---------------- 歌词（4x2 及以上） ----------------

    /** 已为哪首歌加载过歌词（含加载失败 / 无歌词），避免重复请求 */
    @Volatile private var lyricSongId: String? = null
    @Volatile private var lyricLines: List<com.lm.player.core.model.LyricLine> = emptyList()
    @Volatile private var lyricState = LyricState.NONE
    private var lastLyricIndex = Int.MIN_VALUE
    private var lastLyricKey: String? = null
    private val lyricTick = Runnable { tickLyrics() }

    private enum class LyricState { NONE, LOADING, READY, EMPTY }

    private fun ensureLyricsLoaded(context: Context, song: UnifiedSong?) {
        if (song == null) {
            lyricSongId = null; lyricLines = emptyList(); lyricState = LyricState.NONE
            return
        }
        if (lyricSongId == song.id) return
        lyricSongId = song.id
        val cached = com.lm.player.core.media.LyricsManager.getCachedLyrics(song.id)?.lines.orEmpty()
        if (cached.isNotEmpty()) {
            lyricLines = cached; lyricState = LyricState.READY
            return
        }
        lyricLines = emptyList(); lyricState = LyricState.LOADING
        ioScope.launch {
            val lines = try {
                val server = activeServerConfig(context)
                com.lm.player.core.media.LyricsManager.loadLyrics(song, context, server).lines
            } catch (e: Throwable) {
                Log.w(TAG, "lyric load failed: ${e.message}")
                emptyList()
            }
            mainHandler.post {
                if (lyricSongId != song.id) return@post
                lyricLines = lines
                lyricState = if (lines.isNotEmpty()) LyricState.READY else LyricState.EMPTY
                lastLyricKey = null
                tickLyrics()
            }
        }
    }

    private suspend fun activeServerConfig(context: Context): com.lm.player.core.model.ServerConfig? = try {
        val db = com.lm.player.core.database.ZdsDatabase.getInstance(context)
        db.serverDao().getAllServers().firstOrNull { it.isCurrentActive }?.let { entity ->
            com.lm.player.core.model.ServerConfig(
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

    /** 当前播放位置（含用户设置的歌词偏移）；必须在主线程调用 */
    private fun currentLyricPositionMs(context: Context): Long? {
        val player = com.lm.player.core.media.Media3Factory.peekSharedExoPlayer() ?: return null
        val offset = runCatching {
            context.getSharedPreferences("zds_lyrics_prefs", Context.MODE_PRIVATE).getLong("lyrics_offset_ms", 0L)
        }.getOrDefault(0L)
        return (player.currentPosition + offset).coerceAtLeast(0L)
    }

    /**
     * 歌词驱动：只在「有歌词小部件 + 正在播放 + 歌词已就绪」时运行，
     * 按下一句的时间戳精确定时（不做秒级轮询），且仅在句子变化时做局部刷新（不重发封面位图），避免发热。
     * 每次最多睡 8 秒，用于吸收拖动进度等外部跳转。
     */
    private fun tickLyrics() {
        mainHandler.removeCallbacks(lyricTick)
        val ctx = appCtxRef ?: return
        if (!hasLyricWidgets(ctx)) return
        val lines = lyricLines
        val playing = PlaybackQueueManager.isPlayingFlow.value
        var idx = -1
        var delayMs = -1L
        if (lyricState == LyricState.READY && lines.isNotEmpty()) {
            val pos = currentLyricPositionMs(ctx)
            if (pos != null) {
                idx = lines.indexOfLast { it.timestampMs <= pos }.coerceAtLeast(0)
                val next = lines.getOrNull(idx + 1)
                delayMs = if (next != null) (next.timestampMs - pos).coerceIn(150L, 8_000L) else 8_000L
            } else idx = 0
        }
        val key = "${lyricSongId}|${lyricState}|$idx"
        if (key != lastLyricKey) {
            lastLyricKey = key
            lastLyricIndex = idx
            pushLyrics(ctx, idx)
        }
        if (playing && delayMs > 0) mainHandler.postDelayed(lyricTick, delayMs)
    }

    @Volatile private var appCtxRef: Context? = null

    /** 局部刷新所有歌词小部件的歌词行 */
    private fun pushLyrics(context: Context, idx: Int) {
        val mgr = widgetManager(context) ?: return
        val song = PlaybackQueueManager.currentSongFlow.value
        for (spec in specs) {
            if (spec.lyricLines <= 0) continue
            for (id in idsOf(mgr, context, spec)) {
                val rv = RemoteViews(context.packageName, spec.layoutId)
                applyLyrics(context, rv, spec, lyricLinesFor(mgr, id, spec), idx, song)
                runCatching { mgr.partiallyUpdateAppWidget(id, rv) }
            }
        }
    }

    /**
     * 歌词区渲染（按 Antigravity《标注说明》§3.3~3.5、§4）：
     * - 当前句：加粗放大（布局里写死），颜色 = 封面主色（对比度兜底），4x2 左侧主色导标；
     * - 上下文句：二级文字色，离当前句 1 行 48%、2 行 30% 透明度；
     * - 占位态：隐藏歌词列表，显示「图标 + 主文案 + 副文案」。
     */
    private fun applyLyrics(context: Context, rv: RemoteViews, spec: Spec, count: Int, idx: Int, song: UnifiedSong?) {
        val lines = lyricLines
        val night = isNight(context)
        val accent = safeAccent(accentOf(song), night)
        val placeholder: WidgetPlaceholderKind? = when {
            song == null -> WidgetPlaceholderKind.IDLE
            lyricState == LyricState.LOADING || lyricState == LyricState.NONE -> WidgetPlaceholderKind.LOADING
            lyricState == LyricState.EMPTY || lines.isEmpty() -> WidgetPlaceholderKind.EMPTY
            else -> null
        }
        if (placeholder != null) {
            rv.setViewVisibility(R.id.widget_lyrics_list, View.GONE)
            rv.setViewVisibility(R.id.widget_lyrics_ph_box, View.VISIBLE)
            val (icon, tint) = when (placeholder) {
                WidgetPlaceholderKind.LOADING -> R.drawable.ic_widget_loading to accent
                WidgetPlaceholderKind.EMPTY -> R.drawable.ic_widget_note to context.getColor(R.color.widget_text_secondary)
                WidgetPlaceholderKind.IDLE -> R.drawable.ic_widget_vinyl to context.getColor(R.color.widget_text_secondary)
            }
            rv.setImageViewResource(R.id.widget_lyrics_ph_icon, icon)
            rv.setInt(R.id.widget_lyrics_ph_icon, "setColorFilter", tint)
            rv.setTextViewText(R.id.widget_lyrics_ph_title, placeholder.title)
            rv.setTextViewText(R.id.widget_lyrics_ph_sub, placeholder.subtitle)
            return
        }
        rv.setViewVisibility(R.id.widget_lyrics_ph_box, View.GONE)
        rv.setViewVisibility(R.id.widget_lyrics_list, View.VISIBLE)

        val slotCount = spec.lyricLines
        val hidden = spec.dropOrder.take((slotCount - count.coerceIn(minOf(spec.minLyricLines, slotCount), slotCount)).coerceAtLeast(0)).toSet()
        val secondary = context.getColor(R.color.widget_text_secondary)
        rv.setInt(R.id.widget_lyric_indicator, "setColorFilter", accent)
        for (slot in 0 until slotCount) {
            val vid = lyricSlotIds[slot]
            if (slot in hidden) {
                rv.setViewVisibility(vid, View.GONE)
                continue
            }
            rv.setViewVisibility(vid, View.VISIBLE)
            val distance = kotlin.math.abs(slot - spec.activeSlot)
            val li = idx + (slot - spec.activeSlot)
            rv.setTextViewText(vid, lines.getOrNull(li)?.text?.trim().orEmpty())
            val color = when (distance) {
                0 -> accent
                1 -> androidx.core.graphics.ColorUtils.setAlphaComponent(secondary, (255 * 0.48f).toInt())
                else -> androidx.core.graphics.ColorUtils.setAlphaComponent(secondary, (255 * 0.30f).toInt())
            }
            rv.setTextColor(vid, color)
        }
    }

    /** 播放状态/切歌/收藏变化时由 PlaybackQueueManager / PlaybackService 调用 */
    fun notifyPlaybackStateChanged(context: Context) {
        val appCtx = context.applicationContext
        mainHandler.post {
            if (!hasEnabledWidgets(appCtx)) return@post
            updateAllWidgets(appCtx)
        }
    }

    /** 封面已被服务端更新（如 enrich 写回文件）但 URL 不变时调用：清除该 URL 的缓存以便重新下载 */
    fun invalidateCover(context: Context, url: String) {
        if (url.isBlank()) return
        coverMemoryCache.remove(url)
        accentColorCache.remove(url)
        try { coverCacheFile(context.applicationContext, url).delete() } catch (_: Throwable) {}
    }

    fun handleAction(context: Context, action: String?) {
        val appCtx = context.applicationContext
        // 进程可能被系统回收后由本广播冷启动：先恢复持久化的播放队列与当前歌曲，
        // 否则 playNext/playPrevious 会因播放列表为空而无效（表现为小部件切歌偶发失灵）
        PlaybackQueueManager.initFromPrefs(appCtx)
        when (action) {
            ACTION_WIDGET_TOGGLE -> {
                PlaybackService.startPlaybackService(appCtx)
                PlaybackQueueManager.togglePlay(appCtx)
            }
            ACTION_WIDGET_NEXT -> {
                PlaybackService.startPlaybackService(appCtx)
                PlaybackQueueManager.playNext(appCtx)
            }
            ACTION_WIDGET_PREV -> {
                PlaybackService.startPlaybackService(appCtx)
                PlaybackQueueManager.playPrevious(appCtx)
            }
            ACTION_WIDGET_FAVORITE -> {
                // 收藏与 App 内收藏按钮同一全链路：本地 DB + 服务器收藏同步 + 按设置自动推送云端下载
                PlaybackQueueManager.toggleFavorite(appCtx)
            }
        }
    }

    fun updateAllWidgets(context: Context) {
        val appCtx = context.applicationContext
        appCtxRef = appCtx
        val mgr = widgetManager(appCtx) ?: return
        val present = specs.map { it to idsOf(mgr, appCtx, it) }.filter { it.second.isNotEmpty() }
        if (present.isEmpty()) {
            mainHandler.removeCallbacks(lyricTick)
            return
        }

        // 进度条秒刷已移除：避免每秒 binder 推送 + 桌面重复模糊造成的持续 CPU/发热。
        // 小部件仅在切歌 / 播放暂停 / 收藏变化等事件驱动时刷新；歌词按句定时局部刷新。
        val song = PlaybackQueueManager.currentSongFlow.value
        val isPlaying = PlaybackQueueManager.isPlayingFlow.value
        val withLyrics = present.any { it.first.lyricLines > 0 }
        if (withLyrics) ensureLyricsLoaded(appCtx, song)

        var lyricIdx = -1
        if (withLyrics && lyricState == LyricState.READY && lyricLines.isNotEmpty()) {
            val pos = currentLyricPositionMs(appCtx)
            lyricIdx = if (pos != null) lyricLines.indexOfLast { it.timestampMs <= pos }.coerceAtLeast(0) else 0
        }

        // 逐个实例推送：封面大小、歌词行数随各实例在桌面上的实际尺寸不同；搜索框要带上实例 ID 以便面板对齐到该小部件
        for ((spec, ids) in present) for (id in ids) {
            val rv = buildRemoteViews(appCtx, spec, song, isPlaying, id)
            val size = WidgetDataRepository.widgetSizeDp(appCtx, mgr, id)
            if (size != null) spec.coverDp?.let { f ->
                val side = f(size.first, size.second)
                WidgetDataRepository.setViewSizeDp(rv, R.id.widget_cover, side, side)
            }
            if (size != null && spec.hideArtistBelowDp > 0) {
                rv.setViewVisibility(R.id.widget_artist, if (size.second < spec.hideArtistBelowDp) View.GONE else View.VISIBLE)
            }
            if (spec.lyricLines > 0) applyLyrics(appCtx, rv, spec, lyricLinesFor(mgr, id, spec), lyricIdx, song)
            runCatching { mgr.updateAppWidget(id, rv) }
        }
        if (withLyrics) {
            lastLyricKey = "${lyricSongId}|${lyricState}|$lyricIdx"
            lastLyricIndex = lyricIdx
            // 播放/暂停/切歌后重新对齐下一句的定时
            tickLyrics()
        } else {
            mainHandler.removeCallbacks(lyricTick)
        }

        ensureCoverLoaded(appCtx, song)
    }

    private fun buildRemoteViews(
        context: Context,
        spec: Spec,
        song: UnifiedSong?,
        isPlaying: Boolean,
        appWidgetId: Int
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, spec.layoutId)
        val night = isNight(context)

        // 文字：歌名 / 歌手（3x3 拼专辑「歌手 · 专辑」）
        val title = song?.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name)
        val artistName = song?.artist?.takeIf { it.isNotBlank() }
        val album = song?.album?.trim().orEmpty()
        val artist = when {
            song == null -> "未在播放"
            spec.artistWithAlbum && album.isNotBlank() -> listOfNotNull(artistName, album).joinToString(" · ")
            else -> artistName ?: "未知歌手"
        }
        rv.setTextViewText(R.id.widget_title, title)
        rv.setTextViewText(R.id.widget_artist, artist)

        // 背景：封面主色铺底 + 封面静态预模糊叠加，上面由布局里约 78% 的磨砂蒙版压住保证文字对比度。
        // 最终效果是「带封面主色的磨砂玻璃」；无封面时不出图，蒙版直接盖在纯色卡片底上。
        val coverKey = song?.coverUrl?.takeIf { it.isNotBlank() }
        val cached = coverKey?.let { loadCoverFromCache(context, it) }
        val accent = accentOf(song)
        val ambient = if (coverKey != null && cached != null) {
            val key = "$coverKey|${accent ?: 0}"
            bgBlurCache.get(key) ?: runCatching { buildAmbientBackground(cached, accent) }.getOrNull()
                ?.also { bgBlurCache.put(key, it) }
        } else null
        rv.setImageViewBitmap(R.id.widget_bg_img, ambient)

        // 播放圆钮：底色 = 封面主色（无主色用品牌红），图标白色；播放中显示暂停图标
        rv.setInt(R.id.widget_btn_play_bg, "setColorFilter", accent ?: BRAND_RED)
        rv.setImageViewResource(R.id.widget_btn_play, if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
        // 上一首 / 下一首跟随播放圆钮的颜色（封面主色，无主色用品牌红）；
        // 图标直接画在磨砂底上，主色过亮 / 过暗时按深浅模式做对比度兜底，保证看得清
        val controlTint = safeAccent(accent, night)
        rv.setInt(R.id.widget_btn_prev, "setColorFilter", controlTint)
        rv.setInt(R.id.widget_btn_next, "setColorFilter", controlTint)

        // 收藏：已收藏实心品牌红，未收藏空心描边灰
        if (song?.isFavorite == true) {
            rv.setImageViewResource(R.id.widget_btn_favorite, R.drawable.ic_widget_heart_filled)
            rv.setInt(R.id.widget_btn_favorite, "setColorFilter", BRAND_RED)
        } else {
            rv.setImageViewResource(R.id.widget_btn_favorite, R.drawable.ic_widget_heart)
            rv.setInt(R.id.widget_btn_favorite, "setColorFilter", if (night) 0xFF98989D.toInt() else 0xFF8E8E93.toInt())
        }

        // 封面：先尝试内存/磁盘缓存，未命中则显示默认图标并触发异步加载
        if (cached != null) {
            rv.setImageViewBitmap(R.id.widget_cover, cached)
        } else {
            rv.setImageViewResource(R.id.widget_cover, R.mipmap.ic_launcher)
        }

        // 点击（对齐 WidgetAction）：整块卡片 / 封面 = 打开 App；播放圆钮整块可点
        val toggle = buildActionIntent(context, ACTION_WIDGET_TOGGLE, 1)
        rv.setOnClickPendingIntent(R.id.widget_btn_play_box, toggle)
        rv.setOnClickPendingIntent(R.id.widget_btn_play, toggle)
        rv.setOnClickPendingIntent(R.id.widget_btn_next, buildActionIntent(context, ACTION_WIDGET_NEXT, 2))
        rv.setOnClickPendingIntent(R.id.widget_btn_prev, buildActionIntent(context, ACTION_WIDGET_PREV, 3))
        rv.setOnClickPendingIntent(R.id.widget_btn_favorite, buildActionIntent(context, ACTION_WIDGET_FAVORITE, 4))
        val open = buildOpenAppIntent(context)
        rv.setOnClickPendingIntent(R.id.widget_root, open)
        rv.setOnClickPendingIntent(R.id.widget_cover, open)

        // 3x3 顶部搜索框：打开桌面悬浮搜索面板（居中大面板，不进入 App 主界面）
        if (spec.layoutId == R.layout.widget_3x3) {
            val searchIntent = Intent(context, WidgetSearchActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val searchPending = PendingIntent.getActivity(
                context, 100, searchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.widget_search_box, searchPending)
        }

        return rv
    }

    /**
     * 主色氛围背景：
     * 1. 整张先铺封面主色（无主色时取封面平均色）；
     * 2. 叠一层 55% 的封面静态预模糊（先缩到 12px 再放大回 160px，双线性插值即得到柔和的大半径模糊）；
     * 3. 左上到右下加一道主色渐变光晕，让颜色有层次而不是一块平涂。
     * 布局里的磨砂蒙版再压在上面。结果按「封面 URL + 主色」缓存，只在换封面时计算一次。
     */
    private fun buildAmbientBackground(cover: Bitmap, accent: Int?): Bitmap {
        val size = 160
        val tiny = Bitmap.createScaledBitmap(cover, 12, 12, true)
        val mid = Bitmap.createScaledBitmap(tiny, 40, 40, true)
        val blurred = Bitmap.createScaledBitmap(mid, size, size, true)
        val base = accent ?: tiny.getPixel(6, 6)
        if (tiny !== cover) tiny.recycle()
        if (mid !== blurred) mid.recycle()

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(base or 0xFF000000.toInt())
        canvas.drawBitmap(blurred, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 140 })
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                androidx.core.graphics.ColorUtils.setAlphaComponent(base, 150),
                androidx.core.graphics.ColorUtils.setAlphaComponent(base, 0),
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), glow)
        blurred.recycle()
        return out
    }

    private fun buildActionIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(action).apply {
            setPackage(context.packageName)
            setClass(context, MusicWidgetActionReceiver::class.java)
        }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildOpenAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context, 100, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ---------------- 封面加载（内存 LruCache + 磁盘缓存） ----------------

    private fun ensureCoverLoaded(context: Context, song: UnifiedSong?) {
        val url = song?.coverUrl?.takeIf { it.isNotBlank() } ?: return
        if (loadCoverFromCache(context, url) != null) return
        synchronized(pendingCoverLoads) {
            if (!pendingCoverLoads.add(url)) return
        }
        ioScope.launch {
            try {
                val bmp = downloadAndRoundCover(context, url)
                if (bmp != null) {
                    coverMemoryCache.put(url, bmp)
                    accentColorCache.put(url, extractAccentColor(bmp))
                    mainHandler.post {
                        // 仅当当前歌曲仍是该封面时刷新
                        val cur = PlaybackQueueManager.currentSongFlow.value
                        if (cur?.coverUrl == url && hasEnabledWidgets(context)) {
                            // 必须全量刷新：partial update 不携带封面 bitmap，
                            // 否则封面要等下次切歌才显示（缓存已命中，不会重复下载）
                            updateAllWidgets(context)
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "cover load failed: ${e.message}")
            } finally {
                synchronized(pendingCoverLoads) { pendingCoverLoads.remove(url) }
            }
        }
    }

    private fun loadCoverFromCache(context: Context, url: String): Bitmap? {
        coverMemoryCache.get(url)?.let { return it }
        val file = coverCacheFile(context, url)
        if (file.exists()) {
            // 先读尺寸，避免解码大图导致 OOM 或 RemoteViews 内存超限
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            val srcW = opts.outWidth
            val srcH = opts.outHeight

            // 限制最大 512x512，防止 RemoteViews 内存超限
            val maxSize = 512
            var sampleSize = 1
            while (srcW / (sampleSize * 2) >= maxSize && srcH / (sampleSize * 2) >= maxSize) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
            if (bmp != null) {
                coverMemoryCache.put(url, bmp)
                if (accentColorCache.get(url) == null) {
                    accentColorCache.put(url, extractAccentColor(bmp))
                }
                return bmp
            }
        }
        return null
    }

    private fun coverCacheFile(context: Context, url: String): File {
        val dir = File(context.cacheDir, "widget_covers")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, url.hashCode().toString(16) + ".jpg")
    }

    private fun downloadAndRoundCover(context: Context, url: String): Bitmap? {
        // 复用应用全局图片客户端：柠檬服务端封面 URL 不携带 query token，
        // 鉴权依赖该客户端拦截器注入的 Bearer Token（与 App 内封面加载保持一致）
        val client = com.lm.player.core.network.NetworkClientFactory.createImageOkHttpClient(context)
        val request = okhttp3.Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null

            // 先读尺寸，避免解码大图导致 OOM 或 RemoteViews 内存超限
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val srcW = opts.outWidth
            val srcH = opts.outHeight

            // 限制最大 512x512，防止 RemoteViews 内存超限（36MB → 约 1MB）
            val maxSize = 512
            var sampleSize = 1
            while (srcW / (sampleSize * 2) >= maxSize && srcH / (sampleSize * 2) >= maxSize) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null
            val rounded = roundBitmap(raw, 18f)
            try {
                FileOutputStream(coverCacheFile(context, url)).use { out ->
                    rounded.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            } catch (_: Throwable) {}
            return rounded
        }
    }

    /** 从封面提取主色调（取饱和像素均值，亮度钳制到 0.35~0.65 保证深浅背景可读） */
    private fun extractAccentColor(bitmap: Bitmap): Int {
        val sample = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (x in 0 until 24) {
            for (y in 0 until 24) {
                val c = sample.getPixel(x, y)
                val cr = android.graphics.Color.red(c)
                val cg = android.graphics.Color.green(c)
                val cb = android.graphics.Color.blue(c)
                val maxc = maxOf(cr, cg, cb)
                val minc = minOf(cr, cg, cb)
                // 过滤掉接近纯黑/纯白/纯灰的低饱和像素
                if (maxc - minc > 40) {
                    r += cr; g += cg; b += cb; n++
                }
            }
        }
        if (n == 0) return 0xFFFA2D48.toInt()
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
        hsv[1] = hsv[1].coerceAtLeast(0.45f)
        hsv[2] = hsv[2].coerceIn(0.55f, 0.85f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    private fun roundBitmap(src: Bitmap, radiusPx: Float): Bitmap {
        val size = minOf(src.width, src.height).coerceAtLeast(1)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()
        val left = (src.width - size) / 2f
        val top = (src.height - size) / 2f
        path.addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radiusPx, radiusPx, Path.Direction.CW)
        canvas.clipPath(path)
        canvas.drawBitmap(src, -left, -top, paint)
        return out
    }
}

/**
 * 所有尺寸共用的 Provider 行为：系统请求刷新 / 首个实例添加 / 用户拖拽改尺寸时，统一交给控制器全量刷新
 * （改尺寸后会按新高度重新计算歌词行数）。各尺寸子类只用于在桌面小部件列表里区分条目。
 */
open class BaseMusicWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        MusicWidgetController.updateAllWidgets(context)
    }

    override fun onEnabled(context: Context) {
        MusicWidgetController.notifyPlaybackStateChanged(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle) {
        MusicWidgetController.updateAllWidgets(context)
    }
}

/** 4x2：3 行歌词 */
class MusicWidgetProvider4x2 : BaseMusicWidgetProvider()
/** 3x3：顶部搜索框 + 最多 4 行歌词 */
class MusicWidgetProvider3x3 : BaseMusicWidgetProvider()
/** 2x1：封面 + 歌名 + 上一首 / 播放 / 下一首 */
class MusicWidgetProvider2x1 : BaseMusicWidgetProvider()
/** 2x2：封面 + 歌名 + 2~3 行歌词 + 上一首 / 播放 / 下一首 */
class MusicWidgetProvider2x2 : BaseMusicWidgetProvider()

/** 小部件按钮命令统一接收器（与 Provider 分离，保证按钮广播稳定送达） */
class MusicWidgetActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MusicWidgetController.handleAction(context, intent.action)
    }
}
