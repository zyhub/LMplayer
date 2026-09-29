package com.lm.player.core.media

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.lm.player.MainActivity
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.model.UnifiedSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 挂后台时手机顶部系统级灵动岛悬浮窗控制器 (参考酷狗音乐概念版「流体灵动胶囊 + 卡拉OK逐字流光歌词」设计)
 *
 * 核心特性：
 * 1. 酷狗概念版「流体黑曜石 + 封面动态流光光晕」视觉架构：
 *    - 紧凑胶囊态：左侧旋转黑胶唱片 + 中部卡拉OK逐行进度流光染色歌词 + 右侧动态四柱频谱（支持直接点按右侧频谱快速播放/暂停）；
 *    - 展开流体云大卡态：提取当前专辑封面高饱和荧光主色，渲染左上方环境流光光晕、双行卡拉OK进度歌词舞台、「词」快捷开关、可拖拽进度条与完整五键播控。
 * 2. Android 11~15 (API 30~35) 真正置顶贴合挖孔/状态栏：
 *    - 移除会导致系统强制下推避让状态栏的 FLAG_LAYOUT_INSET_DECOR，并在 API 30+ 显式设置 fitInsetsTypes = 0，
 *      配合 LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS 实现与手机顶部状态栏/摄像头挖孔区域的无缝贴合。
 * 3. 全自由度手势交互：
 *    - 紧凑态：单击中左部或下滑展开大卡、单击右侧频谱秒切播放/暂停、左右滑动切歌、长按自由拖拽 X/Y 位置（自动记忆，拖回顶部中心自动吸附复位）；
 *    - 展开态：支持拖拽进度条、一键开关胶囊歌词、点按红心收藏、上滑手势或点按外部自动收起。
 */
object BackgroundIslandOverlayController {

    private const val TAG = "BgIslandOverlay"
    private const val PREFS_NAME = "lemon_settings_prefs"
    private const val KEY_ISLAND_X_OFFSET_DP = "bg_island_x_offset_dp"
    private const val KEY_ISLAND_Y_OFFSET_DP = "bg_island_y_offset_dp"
    private const val KEY_ISLAND_USER_CUSTOM_POS = "bg_island_user_custom_pos"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var windowManager: WindowManager? = null
    private var islandView: BackgroundIslandView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var isWindowAttached = false

    // 临时预览截止时间戳（仅在设置页用户手动点击“测试挂后台灵动岛”时短暂展示 5 秒）
    private var previewUntilTimestampMs: Long = 0L

    private val autoCollapseRunnable = Runnable {
        collapseIsland()
    }

    private val hidePreviewRunnable = Runnable {
        previewUntilTimestampMs = 0L
        refreshVisibilityAndState()
    }

    /**
     * 系统调度的状态栏与挖孔屏自适应几何参数
     */
    data class SystemIslandMetrics(
        val statusBarHeightPx: Int,
        val cutoutTopRect: Rect?,
        val compactWidthPx: Float,
        val compactHeightPx: Float,
        val expandedWidthPx: Float,
        val expandedHeightPx: Float,
        val expandedTopExtraPadPx: Float,
        val systemScheduledXpx: Int,
        val systemScheduledTopYPx: Int
    )

    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * 在设置页点击“预览后台灵动岛效果”时，临时召唤 5 秒供用户预览（前 1.8 秒展示紧凑流光歌词胶囊，随后自动展开流体云大卡）
     */
    fun triggerTemporaryPreview(context: Context) {
        mainHandler.post {
            DynamicIslandManager.ensureInitialized(context)
            previewUntilTimestampMs = System.currentTimeMillis() + 5200L
            ensureInitializedView(context.applicationContext)
            refreshVisibilityAndState(context.applicationContext)
            islandView?.setExpanded(true, animate = true)
            scheduleAutoCollapse()
            mainHandler.removeCallbacks(hidePreviewRunnable)
            mainHandler.postDelayed(hidePreviewRunnable, 5300L)
        }
    }

    /**
     * 刷新后台灵动岛的显示/隐藏状态与实时数据
     */
    fun refreshVisibilityAndState(context: Context? = null) {
        mainHandler.post {
            val appCtx = context?.applicationContext ?: islandView?.context?.applicationContext ?: return@post
            DynamicIslandManager.ensureInitialized(appCtx)

            val isInBackground = DynamicIslandManager.isAppInBackgroundFlow.value
            val isPreviewActive = System.currentTimeMillis() < previewUntilTimestampMs
            val displayMode = DynamicIslandManager.islandDisplayModeFlow.value
            val currentSong = PlaybackQueueManager.currentSongFlow.value
            val player = runCatching { Media3Factory.getSharedExoPlayer(appCtx) }.getOrNull()
            val isPlaying = player?.isPlaying == true
            val isBufferingOrReady = player?.playbackState == androidx.media3.common.Player.STATE_BUFFERING ||
                    player?.playbackState == androidx.media3.common.Player.STATE_READY

            // 核心规则：
            // 1. 应用在前台时绝不显示灵动岛（除非处于设置页手动测试预览）
            // 2. 应用在后台且有当前曲目时，根据系统与播放会话状态全程置顶显示
            val shouldShow = hasOverlayPermission(appCtx) &&
                    displayMode != IslandDisplayMode.SYSTEM_ONLY &&
                    currentSong != null &&
                    (isInBackground || isPreviewActive) &&
                    (isPlaying || isBufferingOrReady || displayMode == IslandDisplayMode.ALWAYS_ON || isPreviewActive || isWindowAttached)

            if (!shouldShow) {
                detachWindow()
                return@post
            }

            ensureInitializedView(appCtx)
            attachWindowIfNeeded(appCtx)
            islandView?.syncWithSystemMetrics(forceLayoutUpdate = false)

            val coverBitmap = DynamicIslandManager.getCachedBitmap(currentSong!!.id)
                ?: DynamicIslandManager.getOrCreateFallbackBitmap()
            val accentColor = DynamicIslandManager.getOrExtractAccentColor(currentSong.id, coverBitmap)

            islandView?.updateState(
                song = currentSong,
                coverBitmap = coverBitmap,
                accentColor = accentColor,
                isPlaying = isPlaying,
                currentLyric = DynamicIslandManager.currentLyricLineFlow.value,
                nextLyric = DynamicIslandManager.nextLyricLineFlow.value,
                lineProgress = DynamicIslandManager.currentLineProgressFlow.value,
                showLyricsInPill = DynamicIslandManager.showLyricsInPillFlow.value,
                progressMs = (player?.currentPosition ?: 0L).coerceAtLeast(0L),
                durationMs = (player?.duration ?: 0L).coerceAtLeast(currentSong.durationMs)
            )
        }
    }

    fun collapseIsland() {
        mainHandler.post {
            islandView?.setExpanded(false, animate = true)
        }
    }

    fun destroy() {
        mainHandler.post {
            mainHandler.removeCallbacks(autoCollapseRunnable)
            mainHandler.removeCallbacks(hidePreviewRunnable)
            detachWindow()
            islandView = null
            windowParams = null
            windowManager = null
        }
    }

    private fun scheduleAutoCollapse() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        mainHandler.postDelayed(autoCollapseRunnable, 6500L)
    }

    /**
     * 根据系统 WindowManager / WindowInsets / DisplayCutout 实时计算灵动岛自适应高度与置顶坐标
     */
    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    private fun computeSystemIslandMetrics(
        appContext: Context,
        rootInsets: WindowInsets? = null
    ): SystemIslandMetrics {
        val resources = appContext.resources
        val dm = resources.displayMetrics
        val density = dm.density
        val fontScale = resources.configuration.fontScale.coerceIn(0.88f, 1.15f)
        fun dp(v: Float): Float = v * density

        // 1. 获取系统状态栏基准高度
        val statusBarResId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val resourceStatusBarPx = if (statusBarResId > 0) {
            resources.getDimensionPixelSize(statusBarResId)
        } else {
            dp(28f).roundToInt()
        }

        // 2. 从系统 WindowMetrics (API 30+) 或 View RootWindowInsets (API 28+) 读取真实状态栏与挖孔屏调度参数
        var insetStatusBarPx = resourceStatusBarPx
        var cutoutSafeTopPx = 0
        var topCutoutRect: Rect? = null

        val wm = windowManager ?: (appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
        val effectiveInsets: WindowInsets? = rootInsets ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            runCatching { wm.currentWindowMetrics.windowInsets }.getOrNull()
        } else {
            null
        }

        if (effectiveInsets != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val statusInsets = effectiveInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
                if (statusInsets.top > 0) {
                    insetStatusBarPx = max(insetStatusBarPx, statusInsets.top)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val cutout = effectiveInsets.displayCutout
                if (cutout != null) {
                    cutoutSafeTopPx = cutout.safeInsetTop
                    if (cutoutSafeTopPx > 0) {
                        insetStatusBarPx = max(insetStatusBarPx, cutoutSafeTopPx)
                    }
                    topCutoutRect = cutout.boundingRects.firstOrNull { rect ->
                        rect.height() > 0 && rect.top <= max(insetStatusBarPx, dp(60f).roundToInt())
                    }
                }
            }
        }

        val effectiveSystemBarHeightPx = max(insetStatusBarPx, dp(26f).roundToInt())

        // 3. 根据系统挖孔/状态栏高度自适应计算紧凑胶囊高度与顶部置顶 Y 坐标
        val compactHeightPx: Float
        val scheduledTopYPx: Int

        if (topCutoutRect != null && topCutoutRect.height() > 0) {
            // 存在前置摄像头挖孔/刘海：胶囊高度自适应包裹挖孔高度并留出上下呼吸边距，垂直中心严格对齐挖孔中心
            val rawCutoutH = topCutoutRect.height().toFloat()
            compactHeightPx = max(rawCutoutH + dp(6f), effectiveSystemBarHeightPx * 0.84f)
                .coerceIn(dp(32f), dp(44f))
            val alignedTop = (topCutoutRect.centerY() - compactHeightPx / 2f).roundToInt()
            scheduledTopYPx = alignedTop.coerceIn(dp(2f).roundToInt(), max(dp(2f).roundToInt(), topCutoutRect.top))
        } else {
            // 无顶部挖孔或系统未暴露挖孔矩形：根据系统状态栏高度自适应计算胶囊高度，并居中于状态栏层
            compactHeightPx = (effectiveSystemBarHeightPx * 0.86f).coerceIn(dp(32f), dp(42f))
            scheduledTopYPx = ((effectiveSystemBarHeightPx - compactHeightPx) / 2f)
                .roundToInt()
                .coerceAtLeast(dp(3f).roundToInt())
        }

        // 4. 自适应计算胶囊宽度与展开态卡片宽高（酷狗概念版比例：修长精致、留出左右状态栏时钟/电量空间）
        val screenMinSidePx = min(dm.widthPixels, dm.heightPixels).toFloat()
        val isCenterCutout = topCutoutRect != null &&
                abs(topCutoutRect.centerX() - dm.widthPixels / 2) < dp(64f)
        val cutoutWidthPx = if (isCenterCutout) (topCutoutRect?.width()?.toFloat() ?: 0f) else 0f
        val compactWidthPx = max(dp(232f), cutoutWidthPx + dp(156f))
            .coerceAtMost(screenMinSidePx - dp(32f))

        // 若摄像头挖孔位于屏幕顶部居中区域，展开大卡顶部自动增加避让内边距，防止挖孔遮挡歌名
        val expandedTopExtraPadPx = if (isCenterCutout && topCutoutRect != null) {
            max(0f, (topCutoutRect.bottom - scheduledTopYPx).toFloat() - dp(6f))
                .coerceIn(0f, dp(18f))
        } else {
            0f
        }

        val expandedWidthPx = min(dp(356f), screenMinSidePx - dp(20f)).coerceAtLeast(compactWidthPx + dp(40f))
        val expandedHeightPx = (dp(202f) * (0.94f + 0.06f * fontScale)) + expandedTopExtraPadPx

        // 5. 若用户曾主动长按拖动过自定义 X/Y 位置，则在合理范围内恢复，否则严格跟随系统调度置顶居中
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasUserCustomPos = prefs.getBoolean(KEY_ISLAND_USER_CUSTOM_POS, false)
        val maxAbsX = ((dm.widthPixels - compactWidthPx) / 2f).coerceAtLeast(0f)
        val finalXpx = if (hasUserCustomPos) {
            val savedXDp = prefs.getFloat(KEY_ISLAND_X_OFFSET_DP, 0f)
            (savedXDp * density).coerceIn(-maxAbsX, maxAbsX).roundToInt()
        } else {
            0
        }
        val finalTopYPx = if (hasUserCustomPos) {
            val savedYDp = prefs.getFloat(KEY_ISLAND_Y_OFFSET_DP, scheduledTopYPx / density).coerceIn(0f, 96f)
            (savedYDp * density).roundToInt()
        } else {
            scheduledTopYPx
        }

        return SystemIslandMetrics(
            statusBarHeightPx = effectiveSystemBarHeightPx,
            cutoutTopRect = topCutoutRect,
            compactWidthPx = compactWidthPx,
            compactHeightPx = compactHeightPx,
            expandedWidthPx = expandedWidthPx,
            expandedHeightPx = expandedHeightPx,
            expandedTopExtraPadPx = expandedTopExtraPadPx,
            systemScheduledXpx = finalXpx,
            systemScheduledTopYPx = finalTopYPx
        )
    }

    private fun ensureInitializedView(appContext: Context) {
        if (windowManager == null) {
            windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
        val metrics = computeSystemIslandMetrics(appContext)
        if (islandView == null) {
            islandView = BackgroundIslandView(appContext, metrics)
        }
        if (windowParams == null) {
            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            // 注意：绝不能包含 FLAG_LAYOUT_INSET_DECOR，否则系统会强制把悬浮窗推到状态栏下方
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED

            windowParams = WindowManager.LayoutParams(
                metrics.compactWidthPx.roundToInt(),
                metrics.compactHeightPx.roundToInt(),
                overlayType,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = metrics.systemScheduledXpx
                y = metrics.systemScheduledTopYPx
                alpha = 1.0f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                // Android 11+ (API 30+): 禁用 WindowManager 默认的 statusBars/systemBars 避让偏移，允许灵动岛直接贴合屏幕最顶端挖孔区
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fitInsetsTypes = 0
                }
            }
        }
    }

    private fun attachWindowIfNeeded(appContext: Context) {
        if (isWindowAttached) return
        val wm = windowManager ?: return
        val view = islandView ?: return
        val params = windowParams ?: return
        if (!hasOverlayPermission(appContext)) return
        try {
            params.alpha = 1.0f
            wm.addView(view, params)
            isWindowAttached = true
            view.startRenderLoop()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to attach background island window", e)
        }
    }

    private fun detachWindow() {
        if (!isWindowAttached) return
        val wm = windowManager
        val view = islandView
        try {
            view?.stopRenderLoop()
            view?.setExpanded(false, animate = false)
            if (wm != null && view != null && view.isAttachedToWindow) {
                wm.removeViewImmediate(view)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error detaching background island window", e)
        } finally {
            isWindowAttached = false
        }
    }

    private fun updateWindowGeometry(
        widthPx: Int,
        heightPx: Int,
        newXPx: Int? = null,
        newYPx: Int? = null,
        forceUpdate: Boolean = false
    ) {
        val wm = windowManager ?: return
        val view = islandView ?: return
        val params = windowParams ?: return
        if (!isWindowAttached) return
        var changed = forceUpdate
        if (params.width != widthPx || params.height != heightPx) {
            params.width = widthPx
            params.height = heightPx
            changed = true
        }
        if (newXPx != null && params.x != newXPx) {
            params.x = newXPx
            changed = true
        }
        if (newYPx != null && params.y != newYPx) {
            params.y = newYPx
            changed = true
        }
        if (params.alpha != 1.0f) {
            params.alpha = 1.0f
            changed = true
        }
        if (changed) {
            try {
                wm.updateViewLayout(view, params)
            } catch (_: Throwable) {}
        }
    }

    /**
     * 酷狗音乐概念版风格硬件加速自定义 Canvas 灵动岛视图
     * - 紧凑态：黑胶唱片旋转 + 卡拉OK流光染色逐行歌词 + 动态频谱（右侧频谱区支持一键点按暂停/播放）
     * - 展开态：封面提取动态流光背景光晕 + 顶栏「词」开关/打开/收起 + 双行卡拉OK染色歌词视窗 + 进度条拖拽 + 5键播控
     */
    private class BackgroundIslandView(
        context: Context,
        initialMetrics: SystemIslandMetrics
    ) : View(context) {

        private val density: Float
            get() = resources.displayMetrics.density

        private fun dp(value: Float): Float = value * density
        private fun sp(value: Float): Float = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            resources.displayMetrics
        )

        private var metrics: SystemIslandMetrics = initialMetrics

        private val compactWidthPx: Float
            get() = metrics.compactWidthPx
        private val compactHeightPx: Float
            get() = metrics.compactHeightPx
        private val expandedWidthPx: Float
            get() = metrics.expandedWidthPx
        private val expandedHeightPx: Float
            get() = metrics.expandedHeightPx

        var isExpandedState: Boolean = false
            private set

        // 0f = 紧凑胶囊态, 1f = 展开流体云大卡态
        private var expandFraction: Float = 0f
        private var expandAnimator: ValueAnimator? = null

        // 实时状态
        private var currentSong: UnifiedSong? = null
        private var coverBitmap: Bitmap? = null
        private var cachedCoverShader: BitmapShader? = null
        private val shaderMatrix = Matrix()

        // 酷狗概念版封面动态流光主色（默认荧光青 #00E5FF）
        private var accentColor: Int = Color.parseColor("#FF00E5FF")
        private var isPlaying: Boolean = false
        private var currentLyric: String = ""
        private var nextLyric: String = ""
        private var lineProgress: Float = 1f
        private var showLyricsInPill: Boolean = true
        private var progressMs: Long = 0L
        private var durationMs: Long = 0L

        // 歌词上下翻滚过渡动画
        private var displayedPillText: String = ""
        private var previousPillText: String = ""
        private var pillTextTransition: Float = 1f
        private var pillTextAnimator: ValueAnimator? = null

        // 黑胶旋转角度与音频频谱相位
        private var vinylRotationDeg: Float = 0f
        private var wavePhase: Float = 0f
        private var isRenderLoopRunning = false
        private var tickCount = 0

        // 触控与手势区域
        private var downRawX = 0f
        private var downRawY = 0f
        private var initialWindowX = 0
        private var initialWindowY = 0
        private var isHorizontalSwiping = false
        private var isDraggingWindowPosition = false
        private var isSeekingProgress = false
        private var horizontalDragOffsetPx = 0f
        private var downTimeMs = 0L

        // 展开态可点击热区
        private val rectCover = RectF()
        private val rectLyricToggleBtn = RectF()
        private val rectOpenAppBtn = RectF()
        private val rectCollapseBtn = RectF()
        private val rectSeekBarHit = RectF()
        private val rectFavBtn = RectF()
        private val rectPrevBtn = RectF()
        private val rectPlayPauseBtn = RectF()
        private val rectNextBtn = RectF()
        private val rectExpandOpenBtn = RectF()

        private val bgRect = RectF()
        private val lyricBoxRect = RectF()
        private val badgeRect = RectF()
        private val clipPath = Path()

        // 高质感黑曜石不透明主体画笔 (#FF08090F)，彻底遮蔽底部状态栏与挖孔
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF08090F")
            alpha = 255
            style = Paint.Style.FILL
        }

        // 酷狗概念版流体光晕画笔
        private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.15f)
            alpha = 255
        }

        private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
        }

        private val vinylDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF181922")
            style = Paint.Style.FILL
        }

        private val vinylCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF08090F")
            alpha = 255
            style = Paint.Style.FILL
        }

        private val vinylRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF484A58")
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
        }

        private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF00E5FF")
            style = Paint.Style.FILL
        }

        private val pillBaseTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6FFFFFF")
            textSize = sp(12.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        private val pillHighlightTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF00E5FF")
            textSize = sp(12.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        private val titleTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = sp(14.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        private val subtitleTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFB4B6C6")
            textSize = sp(11.5f)
        }

        private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF1C2A38")
            style = Paint.Style.FILL
        }

        private val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF00E5FF")
            textSize = sp(9.2f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        private val lyricBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF131520")
            style = Paint.Style.FILL
        }

        private val lyricBoxBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF24283A")
            style = Paint.Style.STROKE
            strokeWidth = dp(0.8f)
        }

        private val currentLyricBasePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D8FFFFFF")
            textSize = sp(12.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        private val currentLyricHighlightPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF00E5FF")
            textSize = sp(12.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        private val nextLyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF8E92A4")
            textSize = sp(11f)
        }

        private val timeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF989AA8")
            textSize = sp(10.5f)
        }

        private val trackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF2A2D3C")
            style = Paint.Style.FILL
        }

        private val trackActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF00E5FF")
            style = Paint.Style.FILL
        }

        private val playCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        private val smallBtnBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF222534")
            style = Paint.Style.FILL
        }

        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
            alpha = 1.0f
        }

        override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
            syncWithSystemMetrics(rootInsets = insets, forceLayoutUpdate = true)
            return super.onApplyWindowInsets(insets)
        }

        override fun onConfigurationChanged(newConfig: Configuration?) {
            super.onConfigurationChanged(newConfig)
            syncWithSystemMetrics(forceLayoutUpdate = true)
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility == VISIBLE) {
                syncWithSystemMetrics(forceLayoutUpdate = true)
            }
        }

        /**
         * 同步系统 WindowInsets / DisplayCutout 调度参数，动态更新灵动岛自适应高度与置顶位置
         */
        fun syncWithSystemMetrics(rootInsets: WindowInsets? = null, forceLayoutUpdate: Boolean = false) {
            val newMetrics = computeSystemIslandMetrics(context.applicationContext, rootInsets ?: rootWindowInsets)
            val sizeChanged = abs(newMetrics.compactHeightPx - metrics.compactHeightPx) > 0.5f ||
                    abs(newMetrics.compactWidthPx - metrics.compactWidthPx) > 0.5f ||
                    newMetrics.systemScheduledTopYPx != metrics.systemScheduledTopYPx ||
                    newMetrics.systemScheduledXpx != metrics.systemScheduledXpx
            metrics = newMetrics
            if ((sizeChanged || forceLayoutUpdate) && expandAnimator?.isRunning != true && !isDraggingWindowPosition) {
                val clamped = expandFraction.coerceIn(0f, 1f)
                val targetW = lerp(compactWidthPx, expandedWidthPx, clamped).roundToInt()
                val targetH = lerp(compactHeightPx, expandedHeightPx, clamped).roundToInt()
                val targetX = if (clamped > 0.2f) 0 else newMetrics.systemScheduledXpx
                updateWindowGeometry(
                    widthPx = targetW,
                    heightPx = targetH,
                    newXPx = targetX,
                    newYPx = newMetrics.systemScheduledTopYPx,
                    forceUpdate = forceLayoutUpdate
                )
                invalidate()
            }
        }

        private val frameTicker = object : Runnable {
            override fun run() {
                if (!isRenderLoopRunning || !isAttachedToWindow) return
                if (isPlaying) {
                    vinylRotationDeg = (vinylRotationDeg + 1.15f) % 360f
                    wavePhase = (wavePhase + 0.20f) % (Math.PI.toFloat() * 20f)
                }
                val player = runCatching { Media3Factory.getSharedExoPlayer(context) }.getOrNull()
                if (player != null && !isSeekingProgress) {
                    progressMs = player.currentPosition.coerceAtLeast(0L)
                    val dur = player.duration
                    if (dur > 0L) durationMs = dur
                }
                lineProgress = DynamicIslandManager.currentLineProgressFlow.value

                tickCount++
                if (tickCount % 90 == 0) {
                    syncWithSystemMetrics(forceLayoutUpdate = false)
                }

                invalidate()
                mainHandler.postDelayed(this, if (isPlaying) 33L else 220L)
            }
        }

        fun startRenderLoop() {
            if (isRenderLoopRunning) return
            isRenderLoopRunning = true
            mainHandler.removeCallbacks(frameTicker)
            mainHandler.post(frameTicker)
        }

        fun stopRenderLoop() {
            isRenderLoopRunning = false
            mainHandler.removeCallbacks(frameTicker)
        }

        fun updateState(
            song: UnifiedSong,
            coverBitmap: Bitmap,
            accentColor: Int,
            isPlaying: Boolean,
            currentLyric: String,
            nextLyric: String,
            lineProgress: Float,
            showLyricsInPill: Boolean,
            progressMs: Long,
            durationMs: Long
        ) {
            this.currentSong = song
            if (this.coverBitmap !== coverBitmap) {
                this.coverBitmap = coverBitmap
                this.cachedCoverShader = BitmapShader(coverBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            this.accentColor = accentColor
            this.isPlaying = isPlaying
            this.currentLyric = currentLyric
            this.nextLyric = nextLyric
            this.lineProgress = lineProgress.coerceIn(0f, 1f)
            this.showLyricsInPill = showLyricsInPill
            if (!isSeekingProgress) {
                this.progressMs = progressMs
            }
            this.durationMs = durationMs

            val targetPillText = if (showLyricsInPill && currentLyric.isNotBlank()) {
                currentLyric
            } else {
                "${song.title} - ${song.artist}"
            }
            if (targetPillText != displayedPillText) {
                if (displayedPillText.isBlank()) {
                    displayedPillText = targetPillText
                    previousPillText = targetPillText
                    pillTextTransition = 1f
                } else {
                    previousPillText = displayedPillText
                    displayedPillText = targetPillText
                    pillTextAnimator?.cancel()
                    pillTextAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 220L
                        interpolator = DecelerateInterpolator()
                        addUpdateListener {
                            pillTextTransition = it.animatedValue as Float
                            invalidate()
                        }
                        start()
                    }
                }
            }
            invalidate()
        }

        fun setExpanded(expanded: Boolean, animate: Boolean) {
            if (isExpandedState == expanded && expandAnimator?.isRunning != true) return
            isExpandedState = expanded
            expandAnimator?.cancel()

            val target = if (expanded) 1f else 0f
            if (!animate) {
                expandFraction = target
                val w = lerp(compactWidthPx, expandedWidthPx, expandFraction).roundToInt()
                val h = lerp(compactHeightPx, expandedHeightPx, expandFraction).roundToInt()
                val x = if (expanded) 0 else metrics.systemScheduledXpx
                updateWindowGeometry(w, h, newXPx = x)
                invalidate()
                return
            }

            expandAnimator = ValueAnimator.ofFloat(expandFraction, target).apply {
                duration = if (expanded) 290L else 220L
                interpolator = if (expanded) OvershootInterpolator(0.72f) else DecelerateInterpolator()
                addUpdateListener { anim ->
                    expandFraction = (anim.animatedValue as Float).coerceIn(0f, 1.04f)
                    val clamped = expandFraction.coerceIn(0f, 1f)
                    val w = lerp(compactWidthPx, expandedWidthPx, clamped).roundToInt()
                    val h = lerp(compactHeightPx, expandedHeightPx, clamped).roundToInt()
                    val x = lerp(metrics.systemScheduledXpx.toFloat(), 0f, clamped).roundToInt()
                    updateWindowGeometry(w, h, newXPx = x)
                    invalidate()
                }
                start()
            }
            if (expanded) {
                scheduleAutoCollapse()
            } else {
                mainHandler.removeCallbacks(autoCollapseRunnable)
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val clamped = expandFraction.coerceIn(0f, 1f)
            val compactCorner = h / 2f
            val cornerRadius = lerp(compactCorner, dp(26f), clamped)
            val inset = dp(0.9f)
            bgRect.set(
                inset + horizontalDragOffsetPx * 0.25f,
                inset,
                w - inset + horizontalDragOffsetPx * 0.25f,
                h - inset
            )

            // 1. 绘制酷狗概念版深邃黑曜石底座 (#FF08090F)
            bgPaint.color = Color.parseColor("#FF08090F")
            bgPaint.alpha = 255
            canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)

            // 2. 绘制专辑封面主色调流体环境光晕 (Ambient Fluid Aura)
            val auraRadius = lerp(w * 0.48f, w * 0.72f, clamped).coerceAtLeast(dp(40f))
            val auraCenterX = bgRect.left + lerp(dp(26f), dp(58f), clamped)
            val auraCenterY = bgRect.top + lerp(h / 2f, dp(48f), clamped)
            val auraAlpha = if (isPlaying) 76 else 42
            val auraColorWithAlpha = Color.argb(
                auraAlpha,
                Color.red(accentColor),
                Color.green(accentColor),
                Color.blue(accentColor)
            )
            auraPaint.shader = RadialGradient(
                auraCenterX,
                auraCenterY,
                auraRadius,
                intArrayOf(auraColorWithAlpha, Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, auraPaint)

            // 3. 绘制概念流光金属描边（随音乐播放状态律动发光）
            val activeStrokeColor = if (isPlaying) accentColor else Color.parseColor("#FF383C4E")
            borderPaint.shader = LinearGradient(
                bgRect.left, bgRect.top, bgRect.right, bgRect.bottom,
                intArrayOf(
                    activeStrokeColor,
                    Color.parseColor("#FF282B3A"),
                    activeStrokeColor
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            borderPaint.alpha = if (isDraggingWindowPosition) 255 else 215
            borderPaint.strokeWidth = if (isDraggingWindowPosition) dp(1.8f) else dp(1.1f)
            canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, borderPaint)

            if (clamped < 0.35f) {
                drawCompactPill(canvas, w, h, 1f - (clamped / 0.35f))
            } else {
                drawExpandedCard(canvas, w, h, ((clamped - 0.35f) / 0.65f).coerceIn(0f, 1f))
            }
        }

        /**
         * 紧凑态：酷狗音乐概念版「黑胶 + 卡拉OK逐行流光染色歌词 + 动态频谱」胶囊
         */
        private fun drawCompactPill(canvas: Canvas, w: Float, h: Float, alphaFactor: Float) {
            val alphaInt = (255 * alphaFactor).toInt().coerceIn(0, 255)
            val centerY = h / 2f

            // 1. 左侧黑胶唱片与旋转封面
            val discRadius = (h * 0.36f).coerceIn(dp(11f), dp(15.5f))
            val coverRadius = discRadius * 0.76f
            val sidePadding = (h - discRadius * 2f) / 2f + dp(2.5f)
            val coverCx = sidePadding + discRadius + horizontalDragOffsetPx * 0.25f
            val coverCy = centerY

            // 外圈黑胶纹理盘面
            vinylDiscPaint.alpha = alphaInt
            canvas.drawCircle(coverCx, coverCy, discRadius, vinylDiscPaint)
            vinylRingPaint.alpha = (alphaInt * 0.7f).toInt()
            canvas.drawCircle(coverCx, coverCy, discRadius, vinylRingPaint)

            val bmp = coverBitmap
            val shader = cachedCoverShader
            if (bmp != null && shader != null) {
                canvas.save()
                canvas.rotate(vinylRotationDeg, coverCx, coverCy)
                val scale = (coverRadius * 2f) / min(bmp.width, bmp.height).toFloat()
                shaderMatrix.reset()
                shaderMatrix.setScale(scale, scale)
                shaderMatrix.postTranslate(coverCx - coverRadius, coverCy - coverRadius)
                shader.setLocalMatrix(shaderMatrix)
                coverPaint.shader = shader
                coverPaint.alpha = alphaInt
                canvas.drawCircle(coverCx, coverCy, coverRadius, coverPaint)
                vinylCenterPaint.alpha = alphaInt
                canvas.drawCircle(coverCx, coverCy, coverRadius * 0.24f, vinylCenterPaint)
                canvas.restore()
            }

            // 2. 右侧四柱概念流光频谱（支持直接点按快速切换播放/暂停）
            val waveMaxH = (h * 0.42f).coerceIn(dp(12f), dp(17f))
            val barWidth = dp(2.5f)
            val barGap = dp(2.3f)
            val totalWaveW = 4 * barWidth + 3 * barGap
            val waveRightStart = w - sidePadding - totalWaveW - dp(2f) + horizontalDragOffsetPx * 0.25f
            wavePaint.color = accentColor
            wavePaint.alpha = alphaInt
            for (i in 0 until 4) {
                val ratio = if (isPlaying) {
                    (0.30f + 0.70f * abs(sin(wavePhase + i * 0.92f))).coerceIn(0.24f, 1f)
                } else {
                    0.26f
                }
                val barH = waveMaxH * ratio
                val left = waveRightStart + i * (barWidth + barGap)
                val top = centerY - barH / 2f
                canvas.drawRoundRect(
                    left,
                    top,
                    left + barWidth,
                    top + barH,
                    barWidth / 2f,
                    barWidth / 2f,
                    wavePaint
                )
            }

            // 3. 中部酷狗概念版卡拉OK流光染色歌词 / 曲目名
            val adaptiveTextSize = (h * 0.34f).coerceIn(sp(11.5f), sp(13.2f))
            pillBaseTextPaint.textSize = adaptiveTextSize
            pillHighlightTextPaint.textSize = adaptiveTextSize
            pillHighlightTextPaint.color = accentColor

            val textLeft = coverCx + discRadius + dp(7.5f)
            val textRight = waveRightStart - dp(7.5f)
            val availWidth = (textRight - textLeft).coerceAtLeast(dp(40f))

            canvas.save()
            canvas.clipRect(textLeft, dp(2f), textRight, h - dp(2f))

            val fm = pillBaseTextPaint.fontMetrics
            val baselineCenterY = centerY - (fm.ascent + fm.descent) / 2f

            if (pillTextTransition < 1f && previousPillText.isNotBlank()) {
                pillBaseTextPaint.alpha = ((1f - pillTextTransition) * alphaInt * 0.85f).toInt().coerceIn(0, 255)
                val prevEllip = TextUtils.ellipsize(previousPillText, pillBaseTextPaint, availWidth, TextUtils.TruncateAt.END).toString()
                canvas.drawText(prevEllip, textLeft, baselineCenterY - dp(10f) * pillTextTransition, pillBaseTextPaint)
            }

            val curAlpha = (pillTextTransition * alphaInt).toInt().coerceIn(0, 255)
            pillBaseTextPaint.alpha = (curAlpha * 0.88f).toInt()
            pillHighlightTextPaint.alpha = curAlpha

            val curEllip = TextUtils.ellipsize(displayedPillText, pillBaseTextPaint, availWidth, TextUtils.TruncateAt.END).toString()
            val drawY = baselineCenterY + dp(10f) * (1f - pillTextTransition)

            // 先绘制底层柔白文字
            canvas.drawText(curEllip, textLeft, drawY, pillBaseTextPaint)

            // 再根据当前歌词行卡拉OK进度绘制流光染色覆盖层
            val hasActiveLyric = showLyricsInPill && currentLyric.isNotBlank()
            val sweepFraction = if (hasActiveLyric) {
                lineProgress.coerceIn(0.08f, 1f)
            } else if (durationMs > 0L) {
                (progressMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                1f
            }
            val measuredTextW = pillBaseTextPaint.measureText(curEllip).coerceAtMost(availWidth)
            val highlightRight = textLeft + measuredTextW * sweepFraction
            if (highlightRight > textLeft) {
                canvas.save()
                canvas.clipRect(textLeft, dp(2f), highlightRight, h - dp(2f))
                canvas.drawText(curEllip, textLeft, drawY, pillHighlightTextPaint)
                canvas.restore()
            }

            canvas.restore()
        }

        /**
         * 展开态：酷狗音乐概念版「流体玻璃大卡 + 双行卡拉OK流光歌词舞台 + 快捷词开关 + 5键播控」
         */
        private fun drawExpandedCard(canvas: Canvas, w: Float, h: Float, alphaFactor: Float) {
            val song = currentSong ?: return
            val alphaInt = (255 * alphaFactor).toInt().coerceIn(0, 255)
            val padH = dp(16f)
            val topExtra = metrics.expandedTopExtraPadPx

            // 1. 左上角「微露旋转黑胶边 + 高清圆角专辑封面」组合 (酷狗概念版标志性唱片夹造型)
            val coverSize = dp(50f)
            val coverTop = dp(14f) + topExtra
            rectCover.set(padH, coverTop, padH + coverSize, coverTop + coverSize)

            // 专辑右侧微露的旋转黑胶唱片边缘
            val vinylPeekCx = rectCover.right - dp(14f)
            val vinylPeekCy = rectCover.centerY()
            val vinylPeekR = coverSize * 0.47f
            vinylDiscPaint.alpha = alphaInt
            canvas.drawCircle(vinylPeekCx, vinylPeekCy, vinylPeekR, vinylDiscPaint)
            vinylRingPaint.alpha = alphaInt
            canvas.drawCircle(vinylPeekCx, vinylPeekCy, vinylPeekR * 0.78f, vinylRingPaint)

            val bmp = coverBitmap
            val shader = cachedCoverShader
            if (bmp != null && shader != null) {
                val scale = coverSize / min(bmp.width, bmp.height).toFloat()
                shaderMatrix.reset()
                shaderMatrix.setScale(scale, scale)
                shaderMatrix.postTranslate(rectCover.left, rectCover.top)
                shader.setLocalMatrix(shaderMatrix)
                coverPaint.shader = shader
                coverPaint.alpha = alphaInt
                canvas.drawRoundRect(rectCover, dp(12f), dp(12f), coverPaint)
            }

            // 2. 右上角三联快捷按钮：「词」开关 + 「打开」 + 「收起」
            val collapseSize = dp(27f)
            rectCollapseBtn.set(w - padH - collapseSize, coverTop + dp(4f), w - padH, coverTop + dp(4f) + collapseSize)
            smallBtnBgPaint.color = Color.parseColor("#FF222534")
            smallBtnBgPaint.alpha = alphaInt
            canvas.drawOval(rectCollapseBtn, smallBtnBgPaint)
            iconPaint.alpha = alphaInt
            iconPaint.color = Color.WHITE
            iconPaint.style = Paint.Style.STROKE
            iconPaint.strokeWidth = dp(1.8f)
            val cxCol = rectCollapseBtn.centerX()
            val cyCol = rectCollapseBtn.centerY()
            canvas.drawLine(cxCol - dp(4.2f), cyCol + dp(2f), cxCol, cyCol - dp(2.3f), iconPaint)
            canvas.drawLine(cxCol, cyCol - dp(2.3f), cxCol + dp(4.2f), cyCol + dp(2f), iconPaint)

            // 「打开」胶囊按钮
            val openBtnW = dp(46f)
            val openBtnH = dp(25f)
            rectOpenAppBtn.set(
                rectCollapseBtn.left - dp(5f) - openBtnW,
                coverTop + dp(5f),
                rectCollapseBtn.left - dp(5f),
                coverTop + dp(5f) + openBtnH
            )
            canvas.drawRoundRect(rectOpenAppBtn, dp(12.5f), dp(12.5f), smallBtnBgPaint)
            subtitleTextPaint.alpha = alphaInt
            subtitleTextPaint.color = Color.WHITE
            subtitleTextPaint.textSize = sp(10.8f)
            val openText = "打开"
            val openTw = subtitleTextPaint.measureText(openText)
            val openFm = subtitleTextPaint.fontMetrics
            canvas.drawText(
                openText,
                rectOpenAppBtn.centerX() - openTw / 2f,
                rectOpenAppBtn.centerY() - (openFm.ascent + openFm.descent) / 2f,
                subtitleTextPaint
            )

            // 「词」开关圆钮（开启时高亮概念主色）
            val lyricBtnSize = dp(25f)
            rectLyricToggleBtn.set(
                rectOpenAppBtn.left - dp(5f) - lyricBtnSize,
                coverTop + dp(5f),
                rectOpenAppBtn.left - dp(5f),
                coverTop + dp(5f) + lyricBtnSize
            )
            smallBtnBgPaint.color = if (showLyricsInPill) {
                Color.argb(
                    68,
                    Color.red(accentColor),
                    Color.green(accentColor),
                    Color.blue(accentColor)
                )
            } else {
                Color.parseColor("#FF222534")
            }
            smallBtnBgPaint.alpha = alphaInt
            canvas.drawOval(rectLyricToggleBtn, smallBtnBgPaint)
            subtitleTextPaint.color = if (showLyricsInPill) accentColor else Color.parseColor("#FF8E92A4")
            subtitleTextPaint.textSize = sp(10.5f)
            val ciTw = subtitleTextPaint.measureText("词")
            canvas.drawText(
                "词",
                rectLyricToggleBtn.centerX() - ciTw / 2f,
                rectLyricToggleBtn.centerY() - (openFm.ascent + openFm.descent) / 2f,
                subtitleTextPaint
            )

            // 3. 歌曲标题 + 概念版音质徽章 + 歌手副标题
            val titleLeft = rectCover.right + dp(16f)
            val titleRight = rectLyricToggleBtn.left - dp(6f)
            val availTitleW = (titleRight - titleLeft).coerceAtLeast(dp(56f))

            titleTextPaint.alpha = alphaInt
            val ellipTitle = TextUtils.ellipsize(song.title, titleTextPaint, availTitleW, TextUtils.TruncateAt.END).toString()
            canvas.drawText(ellipTitle, titleLeft, coverTop + dp(19f), titleTextPaint)

            val isLossless = song.format.uppercase() in listOf("FLAC", "WAV", "ALAC", "APE") || song.bitRate >= 800
            val badgeLabel = if (isLossless) "Hi-Res" else "SQ"
            badgeTextPaint.color = accentColor
            badgeTextPaint.alpha = alphaInt
            badgeBgPaint.color = Color.argb(
                48,
                Color.red(accentColor),
                Color.green(accentColor),
                Color.blue(accentColor)
            )
            val badgeW = badgeTextPaint.measureText(badgeLabel) + dp(9f)
            val subY = coverTop + dp(40f)
            badgeRect.set(titleLeft, subY - dp(10.5f), titleLeft + badgeW, subY + dp(3f))
            canvas.drawRoundRect(badgeRect, dp(4f), dp(4f), badgeBgPaint)
            canvas.drawText(badgeLabel, titleLeft + dp(4.5f), subY - dp(0.5f), badgeTextPaint)

            subtitleTextPaint.color = Color.parseColor("#FFB4B6C6")
            subtitleTextPaint.alpha = alphaInt
            subtitleTextPaint.textSize = sp(11.2f)
            val artistAlbum = "${song.artist} · ${song.album.ifBlank { "概念高保真" }}"
            val availSubW = (w - padH - badgeRect.right - dp(6f)).coerceAtLeast(dp(40f))
            val ellipSub = TextUtils.ellipsize(artistAlbum, subtitleTextPaint, availSubW, TextUtils.TruncateAt.END).toString()
            canvas.drawText(ellipSub, badgeRect.right + dp(6f), subY, subtitleTextPaint)

            // 4. 中部酷狗概念版双行卡拉OK流光歌词视窗
            val lyricTop = dp(72f) + topExtra
            val lyricBottom = dp(116f) + topExtra
            lyricBoxRect.set(padH, lyricTop, w - padH, lyricBottom)
            lyricBoxPaint.alpha = alphaInt
            lyricBoxBorderPaint.alpha = alphaInt
            canvas.drawRoundRect(lyricBoxRect, dp(11f), dp(11f), lyricBoxPaint)
            canvas.drawRoundRect(lyricBoxRect, dp(11f), dp(11f), lyricBoxBorderPaint)

            val lyricAvailW = lyricBoxRect.width() - dp(20f)
            val line1 = if (currentLyric.isNotBlank()) currentLyric else "正在播放 · ${song.title}"
            val line2 = if (nextLyric.isNotBlank()) nextLyric else "${song.artist} · 酷狗概念流体音频引擎"
            val ellipL1 = TextUtils.ellipsize(line1, currentLyricBasePaint, lyricAvailW, TextUtils.TruncateAt.END).toString()
            val ellipL2 = TextUtils.ellipsize(line2, nextLyricPaint, lyricAvailW, TextUtils.TruncateAt.END).toString()

            val l1X = lyricBoxRect.left + dp(10f)
            val l1Y = lyricTop + dp(18.5f)
            currentLyricBasePaint.alpha = (alphaInt * 0.85f).toInt()
            currentLyricHighlightPaint.color = accentColor
            currentLyricHighlightPaint.alpha = alphaInt
            canvas.drawText(ellipL1, l1X, l1Y, currentLyricBasePaint)

            // 当前歌词行卡拉OK流光染色
            val l1MeasuredW = currentLyricBasePaint.measureText(ellipL1).coerceAtMost(lyricAvailW)
            val l1SweepW = l1MeasuredW * (if (currentLyric.isNotBlank()) lineProgress.coerceIn(0.08f, 1f) else 1f)
            if (l1SweepW > 0f) {
                canvas.save()
                canvas.clipRect(l1X, lyricTop, l1X + l1SweepW, lyricTop + dp(25f))
                canvas.drawText(ellipL1, l1X, l1Y, currentLyricHighlightPaint)
                canvas.restore()
            }

            nextLyricPaint.alpha = alphaInt
            canvas.drawText(ellipL2, l1X, lyricTop + dp(35.5f), nextLyricPaint)

            // 5. 进度条与时间
            val progressY = dp(131f) + topExtra
            timeTextPaint.alpha = alphaInt
            val leftTimeStr = formatTimeMs(progressMs)
            val rightTimeStr = formatTimeMs(durationMs)
            canvas.drawText(leftTimeStr, padH, progressY + dp(3.5f), timeTextPaint)
            val rightTw = timeTextPaint.measureText(rightTimeStr)
            canvas.drawText(rightTimeStr, w - padH - rightTw, progressY + dp(3.5f), timeTextPaint)

            val barLeft = padH + dp(34f)
            val barRight = w - padH - dp(34f)
            val barHeight = dp(3.5f)
            rectSeekBarHit.set(barLeft - dp(8f), progressY - dp(12f), barRight + dp(8f), progressY + dp(12f))

            trackBgPaint.alpha = alphaInt
            trackActivePaint.color = accentColor
            trackActivePaint.alpha = alphaInt
            playCirclePaint.color = Color.WHITE
            playCirclePaint.alpha = alphaInt
            canvas.drawRoundRect(barLeft, progressY - barHeight / 2f, barRight, progressY + barHeight / 2f, barHeight / 2f, barHeight / 2f, trackBgPaint)
            val progressRatio = if (durationMs > 0L) (progressMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
            val activeRight = barLeft + (barRight - barLeft) * progressRatio
            canvas.drawRoundRect(barLeft, progressY - barHeight / 2f, activeRight, progressY + barHeight / 2f, barHeight / 2f, barHeight / 2f, trackActivePaint)
            canvas.drawCircle(activeRight, progressY, dp(5f), playCirclePaint)

            // 6. 底部 5 大播控按钮 (收藏 / 上一首 / 概念流体主控圆钮 / 下一首 / 进入播放器)
            val controlsCenterY = min(h - dp(27f), dp(168f) + topExtra)
            val step = (w - padH * 2f) / 5f

            for (idx in 0 until 5) {
                val cx = padH + step * (idx + 0.5f)
                val cy = controlsCenterY
                val hitR = dp(20f)
                when (idx) {
                    0 -> {
                        rectFavBtn.set(cx - hitR, cy - hitR, cx + hitR, cy + hitR)
                        drawHeartIcon(canvas, cx, cy, dp(8.5f), song.isFavorite, alphaInt)
                    }
                    1 -> {
                        rectPrevBtn.set(cx - hitR, cy - hitR, cx + hitR, cy + hitR)
                        drawSkipPreviousIcon(canvas, cx, cy, dp(8f), alphaInt)
                    }
                    2 -> {
                        val btnR = dp(19f)
                        rectPlayPauseBtn.set(cx - btnR, cy - btnR, cx + btnR, cy + btnR)
                        playCirclePaint.color = accentColor
                        playCirclePaint.alpha = alphaInt
                        canvas.drawCircle(cx, cy, btnR, playCirclePaint)
                        drawPlayPauseIcon(canvas, cx, cy, dp(7.5f), isPlaying, alphaInt)
                    }
                    3 -> {
                        rectNextBtn.set(cx - hitR, cy - hitR, cx + hitR, cy + hitR)
                        drawSkipNextIcon(canvas, cx, cy, dp(8f), alphaInt)
                    }
                    4 -> {
                        rectExpandOpenBtn.set(cx - hitR, cy - hitR, cx + hitR, cy + hitR)
                        drawOpenAppIcon(canvas, cx, cy, dp(7.5f), alphaInt)
                    }
                }
            }
        }

        private fun drawHeartIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, isFav: Boolean, alphaInt: Int) {
            iconPaint.style = if (isFav) Paint.Style.FILL else Paint.Style.STROKE
            iconPaint.strokeWidth = dp(1.7f)
            iconPaint.color = if (isFav) Color.parseColor("#FFFA2D48") else Color.parseColor("#FFD8D8E0")
            iconPaint.alpha = alphaInt
            clipPath.reset()
            clipPath.moveTo(cx, cy + r * 0.85f)
            clipPath.cubicTo(cx - r * 1.3f, cy + r * 0.1f, cx - r * 1.1f, cy - r * 0.95f, cx, cy - r * 0.35f)
            clipPath.cubicTo(cx + r * 1.1f, cy - r * 0.95f, cx + r * 1.3f, cy + r * 0.1f, cx, cy + r * 0.85f)
            clipPath.close()
            canvas.drawPath(clipPath, iconPaint)
        }

        private fun drawSkipPreviousIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, alphaInt: Int) {
            iconPaint.style = Paint.Style.FILL
            iconPaint.color = Color.WHITE
            iconPaint.alpha = alphaInt
            canvas.drawRoundRect(cx - r, cy - r * 0.85f, cx - r + dp(2.2f), cy + r * 0.85f, dp(1f), dp(1f), iconPaint)
            clipPath.reset()
            clipPath.moveTo(cx - r + dp(3f), cy)
            clipPath.lineTo(cx + r * 0.9f, cy - r * 0.85f)
            clipPath.lineTo(cx + r * 0.9f, cy + r * 0.85f)
            clipPath.close()
            canvas.drawPath(clipPath, iconPaint)
        }

        private fun drawSkipNextIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, alphaInt: Int) {
            iconPaint.style = Paint.Style.FILL
            iconPaint.color = Color.WHITE
            iconPaint.alpha = alphaInt
            canvas.drawRoundRect(cx + r - dp(2.2f), cy - r * 0.85f, cx + r, cy + r * 0.85f, dp(1f), dp(1f), iconPaint)
            clipPath.reset()
            clipPath.moveTo(cx + r - dp(3f), cy)
            clipPath.lineTo(cx - r * 0.9f, cy - r * 0.85f)
            clipPath.lineTo(cx - r * 0.9f, cy + r * 0.85f)
            clipPath.close()
            canvas.drawPath(clipPath, iconPaint)
        }

        private fun drawPlayPauseIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, playing: Boolean, alphaInt: Int) {
            iconPaint.style = Paint.Style.FILL
            iconPaint.color = Color.parseColor("#FF08090F")
            iconPaint.alpha = alphaInt
            if (playing) {
                val barW = dp(2.8f)
                val gap = dp(2.4f)
                canvas.drawRoundRect(cx - gap - barW, cy - r, cx - gap, cy + r, dp(1.2f), dp(1.2f), iconPaint)
                canvas.drawRoundRect(cx + gap, cy - r, cx + gap + barW, cy + r, dp(1.2f), dp(1.2f), iconPaint)
            } else {
                clipPath.reset()
                clipPath.moveTo(cx - r * 0.60f, cy - r)
                clipPath.lineTo(cx + r * 0.98f, cy)
                clipPath.lineTo(cx - r * 0.60f, cy + r)
                clipPath.close()
                canvas.drawPath(clipPath, iconPaint)
            }
        }

        private fun drawOpenAppIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, alphaInt: Int) {
            iconPaint.style = Paint.Style.STROKE
            iconPaint.strokeWidth = dp(1.7f)
            iconPaint.color = Color.parseColor("#FFD8D8E0")
            iconPaint.alpha = alphaInt
            val boxR = r * 0.85f
            canvas.drawRoundRect(cx - boxR, cy - boxR, cx + boxR, cy + boxR, dp(3f), dp(3f), iconPaint)
            canvas.drawLine(cx - boxR * 0.35f, cy + boxR * 0.35f, cx + boxR * 0.45f, cy - boxR * 0.45f, iconPaint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                if (isExpandedState) {
                    setExpanded(false, animate = true)
                }
                return false
            }

            return if (isExpandedState) {
                handleExpandedTouch(event)
            } else {
                handleCompactTouch(event)
            }
        }

        private fun handleCompactTouch(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    initialWindowX = windowParams?.x ?: metrics.systemScheduledXpx
                    initialWindowY = windowParams?.y ?: metrics.systemScheduledTopYPx
                    isHorizontalSwiping = false
                    isDraggingWindowPosition = false
                    horizontalDragOffsetPx = 0f
                    downTimeMs = System.currentTimeMillis()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    val holdMs = System.currentTimeMillis() - downTimeMs
                    if (!isHorizontalSwiping && !isDraggingWindowPosition) {
                        if (holdMs > 280L && (abs(dx) > dp(8f) || abs(dy) > dp(8f))) {
                            // 长按拖拽胶囊自由调整 X/Y 悬浮位置
                            isDraggingWindowPosition = true
                            invalidate()
                        } else if (dy > dp(16f) && abs(dy) > abs(dx) * 1.2f) {
                            // 向下轻滑胶囊 -> 立即展开概念流体云大卡
                            setExpanded(true, animate = true)
                            return true
                        } else if (abs(dx) > dp(11f) && abs(dx) > abs(dy) * 1.3f) {
                            isHorizontalSwiping = true
                        }
                    }
                    if (isHorizontalSwiping) {
                        horizontalDragOffsetPx = dx.coerceIn(-dp(48f), dp(48f))
                        invalidate()
                    } else if (isDraggingWindowPosition) {
                        val dm = resources.displayMetrics
                        val maxAbsX = ((dm.widthPixels - compactWidthPx) / 2f).coerceAtLeast(0f).toInt()
                        val maxTopY = (dm.heightPixels * 0.14f).toInt()
                        val newX = (initialWindowX + dx.toInt()).coerceIn(-maxAbsX, maxAbsX)
                        val newY = (initialWindowY + dy.toInt()).coerceIn(0, maxTopY)
                        val w = windowParams?.width ?: compactWidthPx.roundToInt()
                        val h = windowParams?.height ?: compactHeightPx.roundToInt()
                        updateWindowGeometry(w, h, newXPx = newX, newYPx = newY)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = event.rawX - downRawX
                    if (isHorizontalSwiping) {
                        if (dx > dp(40f)) {
                            PlaybackQueueManager.playPrevious(context)
                        } else if (dx < -dp(40f)) {
                            PlaybackQueueManager.playNext(context)
                        }
                        horizontalDragOffsetPx = 0f
                        invalidate()
                    } else if (isDraggingWindowPosition) {
                        val curX = windowParams?.x ?: 0
                        val curY = windowParams?.y ?: 0
                        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        // 若用户拖回顶部中心附近，自动吸附恢复系统置顶居中调度
                        if (abs(curX) <= dp(18f).roundToInt() && curY <= dp(6f).roundToInt()) {
                            prefs.edit()
                                .putBoolean(KEY_ISLAND_USER_CUSTOM_POS, false)
                                .remove(KEY_ISLAND_X_OFFSET_DP)
                                .remove(KEY_ISLAND_Y_OFFSET_DP)
                                .apply()
                            syncWithSystemMetrics(forceLayoutUpdate = true)
                        } else {
                            prefs.edit()
                                .putBoolean(KEY_ISLAND_USER_CUSTOM_POS, true)
                                .putFloat(KEY_ISLAND_X_OFFSET_DP, curX / density)
                                .putFloat(KEY_ISLAND_Y_OFFSET_DP, curY / density)
                                .apply()
                            metrics = metrics.copy(
                                systemScheduledXpx = curX,
                                systemScheduledTopYPx = curY
                            )
                        }
                        isDraggingWindowPosition = false
                        invalidate()
                    } else {
                        // 酷狗概念版快捷操作：单击胶囊最右侧频谱区域直接切换播放/暂停；单击其余区域展开大卡
                        if (event.x >= width - dp(46f)) {
                            PlaybackQueueManager.togglePlay(context)
                            invalidate()
                        } else {
                            setExpanded(true, animate = true)
                        }
                    }
                    isHorizontalSwiping = false
                    isDraggingWindowPosition = false
                    return true
                }
            }
            return true
        }

        private fun handleExpandedTouch(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    scheduleAutoCollapse()
                    if (rectSeekBarHit.contains(x, y)) {
                        isSeekingProgress = true
                        seekByTouchX(x)
                        return true
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isSeekingProgress) {
                        scheduleAutoCollapse()
                        seekByTouchX(x)
                    } else {
                        val dy = event.rawY - downRawY
                        if (dy < -dp(24f) && abs(dy) > abs(event.rawX - downRawX) * 1.2f) {
                            // 向上轻滑大卡 -> 流畅收起为胶囊
                            setExpanded(false, animate = true)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    scheduleAutoCollapse()
                    if (isSeekingProgress) {
                        isSeekingProgress = false
                        seekByTouchX(x, commitToPlayer = true)
                        return true
                    }
                    when {
                        rectCollapseBtn.contains(x, y) -> {
                            setExpanded(false, animate = true)
                        }
                        rectLyricToggleBtn.contains(x, y) -> {
                            DynamicIslandManager.setShowLyricsInPill(context, !showLyricsInPill)
                            invalidate()
                        }
                        rectOpenAppBtn.contains(x, y) || rectExpandOpenBtn.contains(x, y) || rectCover.contains(x, y) -> {
                            openMainApp()
                        }
                        rectPlayPauseBtn.contains(x, y) -> {
                            PlaybackQueueManager.togglePlay(context)
                            invalidate()
                        }
                        rectPrevBtn.contains(x, y) -> {
                            PlaybackQueueManager.playPrevious(context)
                        }
                        rectNextBtn.contains(x, y) -> {
                            PlaybackQueueManager.playNext(context)
                        }
                        rectFavBtn.contains(x, y) -> {
                            toggleCurrentSongFavorite()
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    isSeekingProgress = false
                    return true
                }
            }
            return true
        }

        private fun seekByTouchX(x: Float, commitToPlayer: Boolean = false) {
            val padH = dp(16f)
            val barLeft = padH + dp(34f)
            val barRight = width - padH - dp(34f)
            if (barRight <= barLeft || durationMs <= 0L) return
            val ratio = ((x - barLeft) / (barRight - barLeft)).coerceIn(0f, 1f)
            val targetMs = (ratio * durationMs).toLong()
            progressMs = targetMs
            if (commitToPlayer) {
                try {
                    Media3Factory.getSharedExoPlayer(context).seekTo(targetMs)
                } catch (_: Exception) {}
            }
            invalidate()
        }

        private fun toggleCurrentSongFavorite() {
            val song = currentSong ?: return
            val updated = song.copy(isFavorite = !song.isFavorite)
            currentSong = updated
            PlaybackQueueManager.updateCurrentSong(updated)
            invalidate()
            ioScope.launch {
                try {
                    ZdsDatabase.getInstance(context).songDao().updateFavorite(song.id, updated.isFavorite)
                } catch (_: Exception) {}
            }
        }

        private fun openMainApp() {
            try {
                setExpanded(false, animate = false)
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open MainActivity from background island", e)
            }
        }

        private fun lerp(start: Float, stop: Float, fraction: Float): Float {
            return start + (stop - start) * fraction
        }

        private fun formatTimeMs(ms: Long): String {
            val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            return String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
