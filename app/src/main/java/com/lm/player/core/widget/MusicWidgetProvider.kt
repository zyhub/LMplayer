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
import com.lm.player.core.media.Media3Factory
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
 * 桌面小部件统一控制器：负责 4x2 / 4x1 两种尺寸的 RemoteViews 构建、
 * 按钮命令分发（播放/切歌直接走 PlaybackQueueManager，收藏走 PlaybackService CMD_FAVORITE 全链路）、
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

    private var tickerActive = false
    private var tickAppContext: Context? = null
    private var lastRenderedIsPlaying: Boolean? = null
    private var lastRenderedSongId: String? = null
    private var lastRenderedFav: Boolean? = null

    private val progressTick = object : Runnable {
        override fun run() {
            val ctx = tickAppContext ?: return
            val playing = PlaybackQueueManager.isPlayingFlow.value
            if (!playing || !hasEnabledWidgets(ctx)) {
                tickerActive = false
                return
            }
            updateAllWidgets(ctx, progressOnly = true)
            mainHandler.postDelayed(this, 1000L)
        }
    }

    fun hasEnabledWidgets(context: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(context)
        val ids42 = mgr.getAppWidgetIds(ComponentName(context, MusicWidgetProvider4x2::class.java))
        val ids41 = mgr.getAppWidgetIds(ComponentName(context, MusicWidgetProvider4x1::class.java))
        return (ids42 != null && ids42.isNotEmpty()) || (ids41 != null && ids41.isNotEmpty())
    }

    /** 播放状态/切歌/收藏变化时由 PlaybackQueueManager / PlaybackService 调用 */
    fun notifyPlaybackStateChanged(context: Context) {
        val appCtx = context.applicationContext
        mainHandler.post {
            if (!hasEnabledWidgets(appCtx)) return@post
            updateAllWidgets(appCtx, progressOnly = false)
        }
    }

    fun handleAction(context: Context, action: String?) {
        val appCtx = context.applicationContext
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
                // 收藏必须与 App 内收藏按钮同一全链路：本地 DB + 服务器收藏同步 + 按设置自动推送云端下载
                val intent = Intent(appCtx, PlaybackService::class.java).apply {
                    this.action = PlaybackService.ACTION_MEDIA_COMMAND
                    putExtra(PlaybackService.EXTRA_COMMAND, PlaybackService.CMD_FAVORITE)
                }
                try {
                    appCtx.startService(intent)
                } catch (e: Throwable) {
                    Log.w(TAG, "start CMD_FAVORITE service failed: ${e.message}")
                    PlaybackService.startPlaybackService(appCtx)
                }
            }
        }
    }

    fun updateAllWidgets(context: Context, progressOnly: Boolean) {
        val appCtx = context.applicationContext
        val mgr = AppWidgetManager.getInstance(appCtx)
        val ids42 = mgr.getAppWidgetIds(ComponentName(appCtx, MusicWidgetProvider4x2::class.java))
        val ids41 = mgr.getAppWidgetIds(ComponentName(appCtx, MusicWidgetProvider4x1::class.java))
        if ((ids42 == null || ids42.isEmpty()) && (ids41 == null || ids41.isEmpty())) return

        val song = PlaybackQueueManager.currentSongFlow.value
        val isPlaying = PlaybackQueueManager.isPlayingFlow.value
        val (positionMs, durationMs) = readPlayerPosition(appCtx, song)

        if (ids42 != null && ids42.isNotEmpty()) {
            val rv = buildRemoteViews(appCtx, R.layout.widget_4x2, song, isPlaying, positionMs, durationMs)
            mgr.updateAppWidget(ids42, rv)
        }
        if (ids41 != null && ids41.isNotEmpty()) {
            val rv = buildRemoteViews(appCtx, R.layout.widget_4x1, song, isPlaying, positionMs, durationMs)
            mgr.updateAppWidget(ids41, rv)
        }

        lastRenderedSongId = song?.id
        lastRenderedIsPlaying = isPlaying
        lastRenderedFav = song?.isFavorite

        if (!progressOnly) {
            ensureCoverLoaded(appCtx, song)
        }
        syncTicker(appCtx, isPlaying)
    }

    private fun readPlayerPosition(context: Context, song: UnifiedSong?): Pair<Long, Long> {
        return try {
            val player = Media3Factory.getSharedExoPlayer(context)
            val pos = player.currentPosition.coerceAtLeast(0L)
            val dur = player.duration.takeIf { it > 0L } ?: song?.durationMs ?: 0L
            pos to dur
        } catch (_: Throwable) {
            0L to (song?.durationMs ?: 0L)
        }
    }

    private fun syncTicker(context: Context, isPlaying: Boolean) {
        if (isPlaying && hasEnabledWidgets(context)) {
            if (!tickerActive) {
                tickerActive = true
                tickAppContext = context
                mainHandler.removeCallbacks(progressTick)
                mainHandler.postDelayed(progressTick, 1000L)
            }
        } else {
            tickerActive = false
            mainHandler.removeCallbacks(progressTick)
        }
    }

    private fun buildRemoteViews(
        context: Context,
        layoutId: Int,
        song: UnifiedSong?,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, layoutId)

        val title = song?.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name)
        val artist = song?.artist?.takeIf { it.isNotBlank() } ?: "未在播放"
        rv.setTextViewText(R.id.widget_title, title)
        rv.setTextViewText(R.id.widget_artist, artist)

        // 封面取色渲染：命中缓存时用封面主色染标题 + 整卡背景晕染
        val coverKey = song?.coverUrl?.takeIf { it.isNotBlank() }
        val accent = coverKey?.let { accentColorCache.get(it) }
        if (accent != null) {
            rv.setTextColor(R.id.widget_title, accent)
            val night = (context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            val bgKey = "${accent}_${night}"
            var bgBmp = bgBlurCache.get(bgKey)
            if (bgBmp == null) {
                bgBmp = buildAccentBackground(accent, night)
                bgBlurCache.put(bgKey, bgBmp)
            }
            rv.setImageViewBitmap(R.id.widget_bg_img, bgBmp)
        } else {
            rv.setImageViewBitmap(R.id.widget_bg_img, null)
        }

        // 播放/暂停按钮图标与配色：播放中=灰色圆底+暂停图标；暂停=品牌红圆底+播放图标
        if (isPlaying) {
            rv.setImageViewResource(R.id.widget_btn_play, R.drawable.ic_widget_pause)
            rv.setInt(R.id.widget_btn_play, "setBackgroundResource", R.drawable.widget_btn_circle)
        } else {
            rv.setImageViewResource(R.id.widget_btn_play, R.drawable.ic_widget_play)
            rv.setInt(R.id.widget_btn_play, "setBackgroundResource", R.drawable.widget_btn_play_circle)
        }

        // 收藏心形状态
        if (song?.isFavorite == true) {
            rv.setImageViewResource(R.id.widget_btn_favorite, R.drawable.ic_widget_heart_filled)
            rv.setInt(R.id.widget_btn_favorite, "setColorFilter", 0xFFFA2D48.toInt())
        } else {
            rv.setImageViewResource(R.id.widget_btn_favorite, R.drawable.ic_widget_heart)
            rv.setInt(R.id.widget_btn_favorite, "setColorFilter", 0xFF8E8E93.toInt())
        }

        // 进度条（4x2 / 4x1 均有）
        val dur = durationMs.coerceAtLeast(0L)
        val pos = positionMs.coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
        if (song != null && dur > 0L) {
            rv.setViewVisibility(R.id.widget_progress, View.VISIBLE)
            rv.setProgressBar(R.id.widget_progress, dur.toInt().coerceAtLeast(1), pos.toInt(), false)
        } else {
            rv.setProgressBar(R.id.widget_progress, 100, 0, false)
        }

        // 封面：先尝试内存/磁盘缓存，未命中则显示默认图标并触发异步加载
        val cached = coverKey?.let { loadCoverFromCache(context, it) }
        if (cached != null) {
            rv.setImageViewBitmap(R.id.widget_cover, cached)
        } else {
            rv.setImageViewResource(R.id.widget_cover, R.mipmap.ic_launcher)
        }

        // 按钮 PendingIntent
        rv.setOnClickPendingIntent(R.id.widget_btn_play, buildActionIntent(context, ACTION_WIDGET_TOGGLE, 1))
        rv.setOnClickPendingIntent(R.id.widget_btn_next, buildActionIntent(context, ACTION_WIDGET_NEXT, 2))
        rv.setOnClickPendingIntent(R.id.widget_btn_prev, buildActionIntent(context, ACTION_WIDGET_PREV, 3))
        rv.setOnClickPendingIntent(R.id.widget_btn_favorite, buildActionIntent(context, ACTION_WIDGET_FAVORITE, 4))
        rv.setOnClickPendingIntent(R.id.widget_root, buildOpenAppIntent(context))

        return rv
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
                            updateAllWidgets(context, progressOnly = true)
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
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
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
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = okhttp3.Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val rounded = roundBitmap(raw, 18f)
            try {
                FileOutputStream(coverCacheFile(context, url)).use { out ->
                    rounded.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            } catch (_: Throwable) {}
            return rounded
        }
    }

    /** 用封面主色生成整卡晕染背景：底色跟随系统深浅，左上+右下两团主色径向渐变 */
    private fun buildAccentBackground(accent: Int, night: Boolean): Bitmap {
        val w = 640
        val h = 300
        val base = if (night) 0xF226262A.toInt() else 0xF2FFFFFF.toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // 圆角由布局里 clipToOutline + widget_bg_clip 统一裁剪，bitmap 满绘即可
        canvas.drawColor(base)

        fun accentWithAlpha(a: Float): Int {
            return (accent and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
        }

        // 左上主光晕
        val glow1 = Paint(Paint.ANTI_ALIAS_FLAG)
        glow1.shader = android.graphics.RadialGradient(
            w * 0.22f, h * 0.35f, w * 0.62f,
            accentWithAlpha(if (night) 0.55f else 0.38f),
            accentWithAlpha(0f),
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glow1)

        // 右下辅助光晕（色相轻移）
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(accent, hsv)
        hsv[0] = (hsv[0] + 28f) % 360f
        val accent2 = android.graphics.Color.HSVToColor(hsv)
        val glow2 = Paint(Paint.ANTI_ALIAS_FLAG)
        glow2.shader = android.graphics.RadialGradient(
            w * 0.92f, h * 0.95f, w * 0.55f,
            (accent2 and 0x00FFFFFF) or (((if (night) 0.40f else 0.28f) * 255).toInt() shl 24),
            accent2 and 0x00FFFFFF,
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glow2)

        return bmp
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

/** 4x2 桌面小部件 Provider */
class MusicWidgetProvider4x2 : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        MusicWidgetController.updateAllWidgets(context, progressOnly = false)
    }

    override fun onEnabled(context: Context) {
        MusicWidgetController.notifyPlaybackStateChanged(context)
    }

    override fun onDisabled(context: Context) {
        // 无剩余实例时 ticker 会在下一次 tick 自动停止
    }
}

/** 4x1 紧凑桌面小部件 Provider */
class MusicWidgetProvider4x1 : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        MusicWidgetController.updateAllWidgets(context, progressOnly = false)
    }

    override fun onEnabled(context: Context) {
        MusicWidgetController.notifyPlaybackStateChanged(context)
    }
}

/** 小部件按钮命令统一接收器（与 Provider 分离，保证按钮广播稳定送达） */
class MusicWidgetActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MusicWidgetController.handleAction(context, intent.action)
    }
}
