package com.lm.player.core.media

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lm.player.MainActivity
import com.lm.player.R
import com.lm.player.core.model.LyricLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 桌面歌词悬浮窗控制器（全局悬浮，非 Service）：
 * - 双行歌词（当前句品牌红高亮 / 下一句淡显），文字使用描边方案：
 *   浅色模式=白字黑描边、深色模式=黑字白描边，任何壁纸（黑/白/花色）上都保证可读
 * - 控制区（上一首/播放暂停/下一首/收藏/打开App/锁定）与歌词共用同一块区域：
 *   平时只显示歌词；点击歌词区后整个区域变白底并切换为按钮行，2 秒无操作自动切回歌词
 * - 收藏按钮与 App 内收藏按钮同一全链路：startService -> PlaybackService CMD_FAVORITE
 *   （本地 DB + 服务器收藏同步 + 按 favorite_auto_server_download 设置自动推送云端下载 + FAVORITES_CHANGED 广播）
 * - 背景透明度 0~100% 可调（0% 为全透明）；歌词字号可调；可自由拖动并记忆位置；
 *   锁定后窗口穿透防误触，设置页关闭再开启即可解锁。
 */
object DesktopLyricOverlayController {

    private const val TAG = "DesktopLyric"
    private const val PREFS_NAME = "lemon_settings_prefs"
    private const val KEY_ENABLED = "desktop_lyric_enabled"
    private const val KEY_ALPHA_PERCENT = "desktop_lyric_alpha_percent"
    private const val KEY_LOCKED = "desktop_lyric_locked"
    private const val KEY_POS_X = "desktop_lyric_pos_x"
    private const val KEY_POS_Y = "desktop_lyric_pos_y"
    private const val KEY_TEXT_DARK = "desktop_lyric_text_dark"
    private const val KEY_TEXT_SIZE_SP = "desktop_lyric_text_size_sp"

    private const val BRAND_RED = 0xFFFA2D48.toInt()
    private const val CONTROLS_AUTO_HIDE_MS = 2000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    @Volatile private var isAttached = false
    private var lyricJob: Job? = null

    private var lyricColumn: LinearLayout? = null
    private var currentLineTv: StrokeTextView? = null
    private var nextLineTv: StrokeTextView? = null
    private var playBtn: ImageView? = null
    private var favBtn: ImageView? = null
    private var controlsContainer: LinearLayout? = null
    private var bgDrawable: GradientDrawable? = null
    private var controlsVisible = false

    private var currentLyricSongId: String? = null
    private var currentLines: List<LyricLine> = emptyList()
    @Volatile private var lyricsLoading = false

    private val autoHideControlsRunnable = Runnable {
        showControls(false)
    }

    /** 描边文字：先画一遍描边（反向色），再画填充，任何背景上都清晰可读 */
    private class StrokeTextView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null
    ) : TextView(context, attrs) {
        var strokeColor: Int = Color.BLACK
        var strokeWidthPx: Float = 4f

        override fun onDraw(canvas: Canvas) {
            val fillColor = currentTextColor
            val p = paint
            // 描边层
            setTextColor(strokeColor)
            p.style = Paint.Style.STROKE
            p.strokeWidth = strokeWidthPx
            p.strokeJoin = Paint.Join.ROUND
            p.strokeCap = Paint.Cap.ROUND
            super.onDraw(canvas)
            // 填充层
            setTextColor(fillColor)
            p.style = Paint.Style.FILL
            p.strokeWidth = 0f
            super.onDraw(canvas)
        }
    }

    // ---------------- 设置读写 ----------------

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            // 重新开启时自动解除锁定（锁定后窗口穿透无法自解锁，设置页重开是唯一解锁路径）
            .putBoolean(KEY_LOCKED, false)
            .apply()
        if (enabled) show(context.applicationContext) else hide()
    }

    fun getAlphaPercent(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_ALPHA_PERCENT, 72).coerceIn(0, 100)

    fun setAlphaPercent(context: Context, percent: Int) {
        val p = percent.coerceIn(0, 100)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_ALPHA_PERCENT, p).apply()
        applyBackground()
    }

    fun isTextDark(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_TEXT_DARK, false)

    fun setTextDark(context: Context, dark: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TEXT_DARK, dark).apply()
        applyTextStyle(context.applicationContext)
    }

    fun getTextSizeSp(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_TEXT_SIZE_SP, 17).coerceIn(13, 24)

    fun setTextSizeSp(context: Context, sp: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_TEXT_SIZE_SP, sp.coerceIn(13, 24)).apply()
        applyTextStyle(context.applicationContext)
    }

    fun isLocked(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_LOCKED, false)

    fun setLocked(context: Context, locked: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LOCKED, locked).apply()
        applyLockFlags(context.applicationContext)
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "requestOverlayPermission failed: ${e.message}")
            }
        }
    }

    // ---------------- 显示 / 隐藏 ----------------

    @Synchronized
    fun show(context: Context) {
        val appCtx = context.applicationContext
        if (!hasOverlayPermission(appCtx)) return
        mainHandler.post {
            if (isAttached) {
                applyBackground()
                applyLockFlags(appCtx)
                applyTextStyle(appCtx)
                return@post
            }
            try {
                attachWindow(appCtx)
            } catch (e: Throwable) {
                Log.w(TAG, "attach desktop lyric failed: ${e.message}")
            }
        }
    }

    @Synchronized
    fun hide() {
        mainHandler.post {
            lyricJob?.cancel()
            lyricJob = null
            mainHandler.removeCallbacks(autoHideControlsRunnable)
            val wm = windowManager
            val v = rootView
            if (isAttached && wm != null && v != null) {
                try { wm.removeView(v) } catch (_: Throwable) {}
            }
            isAttached = false
            rootView = null
            windowParams = null
            windowManager = null
            lyricColumn = null
            currentLineTv = null
            nextLineTv = null
            playBtn = null
            favBtn = null
            controlsContainer = null
            bgDrawable = null
            currentLyricSongId = null
            currentLines = emptyList()
            controlsVisible = false
        }
    }

    // ---------------- 控制区（与歌词共用区域，互斥显示） ----------------

    /** true=显示白底按钮行并隐藏歌词；false=恢复歌词态与透明度背景 */
    private fun showControls(show: Boolean) {
        val ctx = rootView?.context ?: return
        controlsVisible = show
        mainHandler.removeCallbacks(autoHideControlsRunnable)
        if (show) {
            controlsContainer?.visibility = View.VISIBLE
            lyricColumn?.visibility = View.INVISIBLE
            // 整个区域切换为白色背景，按钮图标用深色
            bgDrawable?.setColor(0xEBFFFFFF.toInt())
            tintControls(0xFF1C1C1E.toInt())
            rootView?.invalidate()
            mainHandler.postDelayed(autoHideControlsRunnable, CONTROLS_AUTO_HIDE_MS)
        } else {
            controlsContainer?.visibility = View.GONE
            lyricColumn?.visibility = View.VISIBLE
            applyBackground()
        }
        // 收藏心形保持红色，不随主题反色
        if (currentFavoriteState) {
            favBtn?.setColorFilter(BRAND_RED)
        }
    }

    private var currentFavoriteState = false

    private fun tintControls(color: Int) {
        val c = controlsContainer ?: return
        for (i in 0 until c.childCount) {
            val child = c.getChildAt(i)
            if (child is ImageView && child != favBtn) {
                child.setColorFilter(color)
            }
        }
        favBtn?.setColorFilter(if (currentFavoriteState) BRAND_RED else color)
    }

    // ---------------- 窗口构建 ----------------

    private fun dp(context: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).roundToInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun attachWindow(context: Context) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val screenH = context.resources.displayMetrics.heightPixels
        val defaultY = (screenH * 0.35f).roundToInt()
        val savedX = prefs.getInt(KEY_POS_X, 0)
        val savedY = prefs.getInt(KEY_POS_Y, defaultY)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }
        windowParams = params

        // 背景（透明度可调，含 0% 全透明；控制态时强制白底）
        val bg = GradientDrawable().apply {
            cornerRadius = dp(context, 24f).toFloat()
        }
        bgDrawable = bg

        val root = FrameLayout(context).apply {
            val ph = dp(context, 18f)
            val pv = dp(context, 10f)
            setPadding(ph, pv, ph, pv)
            background = bg
        }

        // ---- 歌词层（双行：当前句 + 下一句），占满整个区域 ----
        val lyricCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        currentLineTv = StrokeTextView(context)
        nextLineTv = StrokeTextView(context)
        val fullW = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lyricCol.addView(currentLineTv, fullW)
        lyricCol.addView(nextLineTv, fullW)
        lyricColumn = lyricCol
        root.addView(lyricCol, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        // ---- 控制层（覆盖在歌词区域上，默认隐藏） ----
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        controlsContainer = controls

        val btnSize = dp(context, 44f)
        val iconPad = dp(context, 8f)
        fun makeBtn(iconRes: Int, desc: String, onClick: () -> Unit): ImageView {
            return ImageView(context).apply {
                setImageResource(iconRes)
                contentDescription = desc
                setPadding(iconPad, iconPad, iconPad, iconPad)
                setColorFilter(0xFF1C1C1E.toInt())
                setOnClickListener {
                    showControls(true) // 重置 2 秒计时
                    onClick()
                }
            }
        }

        val prevBtn = makeBtn(R.drawable.ic_widget_prev, "上一首") {
            PlaybackService.startPlaybackService(context)
            PlaybackQueueManager.playPrevious(context)
        }
        playBtn = makeBtn(R.drawable.ic_widget_play, "播放/暂停") {
            PlaybackService.startPlaybackService(context)
            PlaybackQueueManager.togglePlay(context)
        }
        val nextBtn = makeBtn(R.drawable.ic_widget_next, "下一首") {
            PlaybackService.startPlaybackService(context)
            PlaybackQueueManager.playNext(context)
        }
        favBtn = makeBtn(R.drawable.ic_widget_heart, "收藏") {
            // 与 App 内收藏按钮同一全链路（服务器同步 + 自动云端下载）
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_MEDIA_COMMAND
                putExtra(PlaybackService.EXTRA_COMMAND, PlaybackService.CMD_FAVORITE)
            }
            try {
                context.startService(intent)
            } catch (e: Throwable) {
                PlaybackService.startPlaybackService(context)
            }
        }
        val openAppBtn = makeBtn(R.drawable.ic_widget_open_app, "打开应用") {
            try {
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Throwable) {
                Log.w(TAG, "open app failed: ${e.message}")
            }
        }
        val lockBtn = makeBtn(R.drawable.ic_widget_lock_open, "锁定") {
            setLocked(context, true)
            Toast.makeText(context, "桌面歌词已锁定（防误触穿透），可在设置页关闭再开启来解锁", Toast.LENGTH_LONG).show()
        }
        val closeBtn = makeBtn(R.drawable.ic_widget_close, "关闭桌面歌词") {
            setEnabled(context, false)
        }

        controls.addView(prevBtn, LinearLayout.LayoutParams(btnSize, btnSize))
        controls.addView(playBtn, LinearLayout.LayoutParams(btnSize, btnSize))
        controls.addView(nextBtn, LinearLayout.LayoutParams(btnSize, btnSize))
        controls.addView(favBtn, LinearLayout.LayoutParams(btnSize, btnSize))
        controls.addView(openAppBtn, LinearLayout.LayoutParams(btnSize, btnSize))
        controls.addView(lockBtn, LinearLayout.LayoutParams(btnSize, btnSize))
        controls.addView(closeBtn, LinearLayout.LayoutParams(btnSize, btnSize))
        root.addView(controls, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        // 根视图触摸：按住拖动；未拖动的点击 → 切换为控制态 2 秒
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        root.setOnTouchListener { _, event ->
            val p = windowParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).roundToInt()
                    val dy = (event.rawY - downRawY).roundToInt()
                    if (!dragging && (abs(dx) > dp(context, 6f) || abs(dy) > dp(context, 6f))) {
                        dragging = true
                    }
                    if (dragging) {
                        p.x = startX + dx
                        p.y = (startY + dy).coerceAtLeast(0)
                        try { wm.updateViewLayout(root, p) } catch (_: Throwable) {}
                        true
                    } else false
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putInt(KEY_POS_X, p.x).putInt(KEY_POS_Y, p.y).apply()
                        dragging = false
                        true
                    } else {
                        showControls(true)
                        false
                    }
                }
                else -> false
            }
        }

        rootView = root
        wm.addView(root, params)
        isAttached = true

        applyBackground()
        applyLockFlags(context)
        applyTextStyle(context)
        startLyricLoop(context)
    }

    private fun applyBackground() {
        val ctx = rootView?.context ?: return
        if (controlsVisible) {
            bgDrawable?.setColor(0xEBFFFFFF.toInt())
        } else {
            val percent = getAlphaPercent(ctx)
            val alpha = (percent * 255 / 100).coerceIn(0, 255)
            bgDrawable?.setColor(Color.argb(alpha, 28, 28, 30))
        }
        rootView?.invalidate()
    }

    /** 文字配色与字号：描边方案 —— 浅色模式白字黑边，深色模式黑字白边，任意壁纸可读 */
    private fun applyTextStyle(context: Context) {
        val dark = isTextDark(context)
        val sizeSp = getTextSizeSp(context).toFloat()
        val subSp = (sizeSp - 4f).coerceAtLeast(11f)
        val strokeW = dp(context, 1.6f).toFloat().coerceAtLeast(3f)

        val curFill = BRAND_RED
        val subFill = if (dark) 0xCC000000.toInt() else 0xE6FFFFFF.toInt()
        val stroke = if (dark) 0xF2FFFFFF.toInt() else 0xE6000000.toInt()

        currentLineTv?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            strokeColor = stroke
            strokeWidthPx = strokeW
            setTextColor(curFill)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.START
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        nextLineTv?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, subSp)
            strokeColor = stroke
            strokeWidthPx = strokeW
            setTextColor(subFill)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.START
            gravity = Gravity.END
        }
    }

    private fun applyLockFlags(context: Context) {
        val p = windowParams ?: return
        val v = rootView ?: return
        val wm = windowManager ?: return
        if (isLocked(context)) {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        try { wm.updateViewLayout(v, p) } catch (_: Throwable) {}
    }

    // ---------------- 歌词同步循环 ----------------

    private fun startLyricLoop(context: Context) {
        lyricJob?.cancel()
        lyricJob = scope.launch {
            while (isActive && isAttached) {
                try {
                    tickLyric(context)
                } catch (e: Throwable) {
                    Log.w(TAG, "lyric tick error: ${e.message}")
                }
                delay(300L)
            }
        }
    }

    private suspend fun tickLyric(context: Context) {
        val song = PlaybackQueueManager.currentSongFlow.value
        val playing = PlaybackQueueManager.isPlayingFlow.value

        // 切歌：重置歌词缓存并触发加载
        if (song?.id != currentLyricSongId) {
            currentLyricSongId = song?.id
            currentLines = song?.let { LyricsManager.getCachedLyrics(it.id)?.lines } ?: emptyList()
            if (song != null && currentLines.isEmpty() && !lyricsLoading) {
                lyricsLoading = true
                scope.launch {
                    try {
                        val result = LyricsManager.loadLyrics(song, context, null)
                        if (currentLyricSongId == song.id) {
                            currentLines = result.lines
                        }
                    } catch (_: Throwable) {
                    } finally {
                        lyricsLoading = false
                    }
                }
            }
        }

        // 播放/暂停按钮
        playBtn?.setImageResource(if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)

        // 收藏按钮状态
        currentFavoriteState = song?.isFavorite == true
        if (currentFavoriteState) {
            favBtn?.setImageResource(R.drawable.ic_widget_heart_filled)
            favBtn?.setColorFilter(BRAND_RED)
        } else {
            favBtn?.setImageResource(R.drawable.ic_widget_heart)
            favBtn?.setColorFilter(0xFF1C1C1E.toInt())
        }

        if (song == null) {
            currentLineTv?.text = "LMPlayer 桌面歌词"
            nextLineTv?.text = ""
            return
        }

        val lines = currentLines
        if (lines.isEmpty()) {
            currentLineTv?.text = if (lyricsLoading) "歌词加载中…" else "纯音乐 / 暂无歌词"
            nextLineTv?.text = ""
            return
        }

        val pos = try {
            Media3Factory.getSharedExoPlayer(context).currentPosition.coerceAtLeast(0L)
        } catch (_: Throwable) {
            0L
        }
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timestampMs <= pos + 150L) idx = i else break
        }
        if (idx < 0) idx = 0

        currentLineTv?.text = lines.getOrNull(idx)?.text ?: ""
        nextLineTv?.text = lines.getOrNull(idx + 1)?.text ?: ""
    }
}
