package com.lm.player.feature.player

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 底栏迷你播放条在窗口中的位置：点它打开播放页时，播放页从这里展开，关闭时以神奇效果（Genie）收回这里。
 * 只存普通字段（不是 Compose 状态）：底栏形变动画每帧更新位置也不会引起重组。
 */
object PlayerLaunchOrigin {
    @Volatile
    var rect: Rect = Rect.Zero
        private set

    @Volatile
    private var armed = false

    /** 底栏随播放页滑走 / 滑回期间不更新（否则记下的是滑到一半的位置） */
    @Volatile
    var frozen = false

    fun update(bounds: Rect) {
        if (!frozen && bounds.width > 1f && bounds.height > 1f) rect = bounds
    }

    /** 底栏迷你播放条被点击（下一次打开播放页用展开动画） */
    fun arm() {
        armed = true
    }

    fun consumeArmed(): Boolean {
        val a = armed
        armed = false
        return a
    }
}

/** 挂在底栏迷你播放条上：记录它在窗口中的位置 */
fun Modifier.trackPlayerLaunchOrigin(): Modifier =
    this.onGloballyPositioned { PlayerLaunchOrigin.update(it.boundsInWindow()) }

/** 每帧的圆角裁剪区域（只改图层轮廓，不重新布局、不重组） */
private class IslandClipShape(
    private val left: Float,
    private val top: Float,
    private val right: Float,
    private val bottom: Float,
    private val radius: Float
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(Rect(left, top, right, bottom), CornerRadius(radius)))
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun smooth(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** 展开：平滑的缓入缓出（起步柔和、中段匀速、收尾减速），整个长大过程都看得见；以前的强调减速曲线头 50ms 就长到七成，看着像一闪 */
private val ExpandEasing = CubicBezierEasing(0.42f, 0f, 0.2f, 1f)
private const val EXPAND_MS = 560
private const val GENIE_MS = 680
private const val KEEPALIVE_EXIT_MS = 2200
private const val SLIDE_KEEPALIVE_EXIT_MS = 900
private const val SLIDE_IN_MS = 380
private const val SLIDE_OUT_MS = 320

/**
 * 本进程里是否已经打开过播放页：第一次打开要组合整个播放页、类首次加载、着色器首次编译，最重。
 * 第一次多等一会儿（等帧率稳定，最多 [FIRST_OPEN_WAIT_MS]），动画本身就不会卡；之后照常快速打开。
 */
object PlayerWarmState {
    private const val FIRST_OPEN_WAIT_MS = 560L
    @Volatile
    private var opened = false
    fun openWaitMs(firstShort: Long): Long = if (opened) firstShort else FIRST_OPEN_WAIT_MS
    fun markOpened() { opened = true }
}
/** 打开（倒放）比收回快一点 */
private const val OPEN_GENIE_MS = 500
/** 神奇效果截图按半分辨率取：拷贝 / 上传纹理的像素量是全尺寸的 1/4，变形中看不出差别 */
private const val SNAP_SCALE = 0.5f
/** 打开要等截图再倒放，保活久一点（只影响 AnimatedVisibility 何时算「已静止」） */
private const val OPEN_KEEPALIVE_MS = 1300

/** 等到帧率稳定：连续 2 帧间隔 < 24ms，或最多等 [maxWaitMs] */
private suspend fun awaitStableFrames(maxWaitMs: Long) {
    var last = withFrameNanos { it }
    val start = last
    var stable = 0
    while (stable < 2 && (last - start) < maxWaitMs * 1_000_000L) {
        val now = withFrameNanos { it }
        stable = if (now - last < 24_000_000L) stable + 1 else 0
        last = now
    }
}

/**
 * 按帧推进的补间：每帧最多推进 24ms 的进度。掉帧时动画放慢而不是跳过，保证每一段缩放都能完整看到；
 * 帧率正常时与普通 tween 完全一样。
 */
private suspend fun Animatable<Float, *>.animateFramePaced(target: Float, durationMs: Int, easing: androidx.compose.animation.core.Easing) {
    val start = value
    if (start == target || durationMs <= 0) {
        snapTo(target)
        return
    }
    var elapsed = 0f
    var last = withFrameNanos { it }
    while (elapsed < durationMs) {
        val now = withFrameNanos { it }
        elapsed += ((now - last) / 1_000_000f).coerceIn(0f, 24f)
        last = now
        val f = easing.transform((elapsed / durationMs).coerceIn(0f, 1f))
        snapTo(start + (target - start) * f)
    }
    snapTo(target)
}

/** 打开用：[GenieEasing] 的时间倒放（配合从 1 播到 0），与收回的每一帧完全对称 */
private val ReverseGenieEasing = androidx.compose.animation.core.Easing { 1f - GenieEasing.transform(1f - it) }

/** 神奇效果：慢起步、中段加速被「吸」进去、末尾稍缓，非线性 */
private val GenieEasing = CubicBezierEasing(0.55f, 0.02f, 0.3f, 1f)
/** 下拉到底（屏幕高度 60%）时 Genie 走到的进度；松手关闭后再从这里接着收完 */
private const val DRAG_GENIE_MAX = 0.7f

/** 播放页下拉手势与 Genie 的桥：展开模式下播放页不再整页平移，把拖动进度交给 [PlayerIslandTransition] */
object PlayerDismissDrag {
    var enabled by mutableStateOf(false)
    var dragging by mutableStateOf(false)
    var progress by androidx.compose.runtime.mutableFloatStateOf(0f)
    /** Genie 正在画（拖动中 / 收回中）：此时底栏等上层要露出来，播放页被「吸」到它下面 */
    var genieActive by mutableStateOf(false)
}

// =====================================================================================
// 底栏 ⇄ 播放页底部玻璃工具条的联动：打开时底栏先滑走、工具条再滑进来；收起时反过来，两条不会叠在一起
// =====================================================================================

/** 底栏滑走后多久工具条开始滑入 / 工具条滑走后多久底栏开始滑回（避免两条重叠） */
internal const val BAR_HANDOFF_DELAY_MS = 200L
private const val TOOLBAR_IN_MS = 340
private const val TOOLBAR_OUT_MS = 260

/**
 * 播放页底部玻璃工具条的「替身」：工具条把自己录进 [layer]；进出动画期间 [detached] = true，
 * 原位不再画它（也就不进 Genie 截图），改由转场在屏幕上单独画、单独上下滑。
 */
object PlayerToolbarLink {
    internal var layer: androidx.compose.ui.graphics.layer.GraphicsLayer? = null
    internal var toolbarCoords: androidx.compose.ui.layout.LayoutCoordinates? = null
    internal var contentCoords: androidx.compose.ui.layout.LayoutCoordinates? = null
    var detached by mutableStateOf(false)
}

/** 挂在播放页底部玻璃工具条上 */
@Composable
fun Modifier.playerToolbarLink(): Modifier {
    val layer = rememberGraphicsLayer()
    DisposableEffect(layer) {
        PlayerToolbarLink.layer = layer
        onDispose { if (PlayerToolbarLink.layer === layer) PlayerToolbarLink.layer = null }
    }
    return this
        .onGloballyPositioned { PlayerToolbarLink.toolbarCoords = it }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            if (!PlayerToolbarLink.detached) drawLayer(layer)
        }
}

/** 播放页内容的根：工具条位置都相对它算（不受转场的位移 / 缩放影响） */
@Composable
private fun PlayerContentRoot(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { PlayerToolbarLink.contentCoords = it }
    ) { content() }
}

/** 进出期间单独画工具条：原位 + 向下滑出 [out]（0 = 原位，1 = 完全滑出屏幕底部） */
@Composable
private fun PlayerToolbarOverlay(out: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (!PlayerToolbarLink.detached) return@Canvas
        val layer = PlayerToolbarLink.layer ?: return@Canvas
        val root = PlayerToolbarLink.contentCoords?.takeIf { it.isAttached } ?: return@Canvas
        val bar = PlayerToolbarLink.toolbarCoords?.takeIf { it.isAttached } ?: return@Canvas
        val b = runCatching { root.localBoundingBoxOf(bar, clipBounds = false) }.getOrNull() ?: return@Canvas
        val v = out.value
        if (v >= 0.999f) return@Canvas
        val dy = v * (size.height - b.top + 24.dp.toPx())
        translate(b.left, b.top + dy) { drawLayer(layer) }
    }
}

/** Genie 网格密度（列 × 行）：竖向更密，弯曲更顺 */
private const val MESH_W = 10
private const val MESH_H = 28

/**
 * 全屏播放页的显隐动画（放在 AnimatedVisibility 里，enter / exit 都传 None，由这里驱动）。
 *
 * 展开模式（设置「音乐栏动画」= 展开，竖屏、从底栏迷你播放条点开）：
 * - 打开：圆角区域从播放条的胶囊连续长满全屏，内容随之放大淡入（等播放页第一帧画完再开始，首帧组合不会吃掉动画）；
 * - 关闭（按钮 / 返回键 / 下滑）：把播放页当前画面截成一张图，用网格变形做 macOS 式的神奇效果（Genie），
 *   两侧沿曲线收窄、整体被「吸」进音乐栏正中间。截图失败时退回原路收回。
 * 其它情况（滑出模式、横屏、从别处打开）：保持原来的从底部滑入 / 滑出。
 *
 * 性能：展开只在 graphicsLayer 里改裁剪轮廓 / 缩放 / 透明度；Genie 每帧只画一次带网格的位图（drawBitmapMesh），
 * 播放页本身在 Genie 期间隐藏不绘制。都不重组、不重新布局。
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AnimatedVisibilityScope.PlayerIslandTransition(
    islandFromBar: Boolean,
    content: @Composable () -> Unit
) {
    val portrait = LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
    // 打开那一刻的播放条位置；之后底栏被隐藏 / 形变都不影响这一次的动画
    val origin = remember { PlayerLaunchOrigin.rect }
    // 只认打开那一刻的方向：横屏打开后再转到竖屏，不能拿横屏时记下的底栏位置做神奇效果（会漂到右边）
    val islandAllowed = remember { islandFromBar && portrait && origin.width > 1f && origin.height > 1f }
    val island = islandAllowed && portrait

    if (!island) {
        // 让 AnimatedVisibility 等滑动播完再移除内容（数值本身不用）
        transition.animateFloat(
            transitionSpec = { if (targetState == EnterExitState.Visible) tween(OPEN_KEEPALIVE_MS) else tween(SLIDE_KEEPALIVE_EXIT_MS) },
            label = "player_slide_keepalive"
        ) { if (it == EnterExitState.Visible) 1f else 0f }
        // 滑动进度自己驱动：先等播放页头几帧（首次打开最重）稳定再滑，按帧推进，掉帧时放慢而不是跳过
        val slide = remember { Animatable(0f) }
        val slideVisible = transition.targetState == EnterExitState.Visible
        // 竖屏：工具条与底栏交接（横屏工具条跟着播放页整体走）
        val toolbarOut = remember { Animatable(if (portrait) 1f else 0f) }
        if (portrait) DisposableEffect(Unit) { onDispose { PlayerToolbarLink.detached = false } }
        LaunchedEffect(slideVisible) {
            if (portrait) PlayerToolbarLink.detached = true
            if (slideVisible) {
                awaitStableFrames(maxWaitMs = PlayerWarmState.openWaitMs(firstShort = 160L))
                coroutineScope {
                    if (portrait) launch {
                        toolbarOut.snapTo(1f)
                        delay(BAR_HANDOFF_DELAY_MS)
                        toolbarOut.animateTo(0f, tween(TOOLBAR_IN_MS, easing = FastOutSlowInEasing))
                    }
                    slide.animateFramePaced(1f, SLIDE_IN_MS, FastOutSlowInEasing)
                }
                PlayerWarmState.markOpened()
                if (portrait) PlayerToolbarLink.detached = false
            } else {
                coroutineScope {
                    if (portrait) launch { toolbarOut.animateTo(1f, tween(TOOLBAR_OUT_MS, easing = FastOutSlowInEasing)) }
                    slide.animateFramePaced(0f, SLIDE_OUT_MS, FastOutSlowInEasing)
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = (1f - slide.value) * size.height }
            ) { PlayerContentRoot(content) }
            if (portrait) PlayerToolbarOverlay(toolbarOut)
        }
        return
    }

    // 让 AnimatedVisibility 等到展开 / Genie 播完再移除内容（数值本身不用）
    transition.animateFloat(
        transitionSpec = {
            // 收回时给足时间：卡顿时动画按帧推进会变慢，播完后内容会立即卸下（见 finished），多出来的时间不影响操作
            if (targetState == EnterExitState.Visible) tween(OPEN_KEEPALIVE_MS) else tween(KEEPALIVE_EXIT_MS)
        },
        label = "player_island_keepalive"
    ) { if (it == EnterExitState.Visible) 1f else 0f }

    // 收回的目标：音乐栏正中间的一小块（收进整条音乐栏太宽，看着很假）
    val target = remember {
        val th = origin.height * 0.86f
        val tw = minOf(origin.width, origin.height * 1.3f)
        val cx = origin.center.x
        val cy = origin.center.y
        Rect(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f)
    }
    val open = remember { Animatable(0f) }
    val genie = remember { Animatable(0f) }
    var snapshot by remember { mutableStateOf<Bitmap?>(null) }
    // 收回播完：不再组合播放页（隐形的播放页不能继续挡住下面的点击）
    var finished by remember { mutableStateOf(false) }
    // 打开中：播放页照常组合、录进图层，但先不画到屏幕上，等截图后用「倒放的 Genie」从音乐栏里长出来
    var hideLive by remember { mutableStateOf(true) }
    var opening by remember { mutableStateOf(false) }
    val recordLayer = rememberGraphicsLayer()
    val snapLayer = rememberGraphicsLayer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val winOffset = remember { FloatArray(2) }
    val background = MaterialTheme.colorScheme.background
    val visibleTarget = transition.targetState == EnterExitState.Visible
    // 底部玻璃工具条单独上下滑（0 = 原位，1 = 滑出屏幕底部），与底栏交接
    val toolbarOut = remember { Animatable(1f) }
    DisposableEffect(Unit) { onDispose { PlayerToolbarLink.detached = false } }

    // 播放页的下拉手势改由这里接管：不再整页平移，而是按拖动进度做 Genie（下层内容随之露出来）
    DisposableEffect(Unit) {
        PlayerDismissDrag.enabled = true
        PlayerDismissDrag.progress = 0f
        PlayerDismissDrag.dragging = false
        onDispose {
            PlayerDismissDrag.enabled = false
            PlayerDismissDrag.progress = 0f
            PlayerDismissDrag.dragging = false
            PlayerDismissDrag.genieActive = false
        }
    }
    // 打开 / 收回的整个过程底栏都露着：播放页从它里面长出来、又被吸回去
    androidx.compose.runtime.SideEffect { PlayerDismissDrag.genieActive = snapshot != null || finished || opening }

    suspend fun takeSnapshot(): Bitmap? = try {
        withTimeoutOrNull(300L) {
            val full = recordLayer.size
            if (full.width <= 0 || full.height <= 0) return@withTimeoutOrNull null
            val sw = (full.width * SNAP_SCALE).toInt().coerceAtLeast(1)
            val sh = (full.height * SNAP_SCALE).toInt().coerceAtLeast(1)
            snapLayer.record(density, layoutDirection, androidx.compose.ui.unit.IntSize(sw, sh)) {
                scale(SNAP_SCALE, SNAP_SCALE, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                    drawLayer(recordLayer)
                }
            }
            val image = snapLayer.toImageBitmap().asAndroidBitmap()
            // 硬件位图不能直接做网格绘制：后台转成普通位图（只做一次）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && image.config == Bitmap.Config.HARDWARE) {
                withContext(Dispatchers.Default) { image.copy(Bitmap.Config.ARGB_8888, false) }
            } else image
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    // 拖动中：一开始拖就截图，之后 Genie 进度直接跟手；松手没关掉就弹回并丢掉截图
    LaunchedEffect(Unit) {
        var snapshotting = false
        snapshotFlow { PlayerDismissDrag.dragging to PlayerDismissDrag.progress }.collect { (dragging, p) ->
            if (transition.targetState != EnterExitState.Visible) return@collect
            if (opening) return@collect
            if (dragging && p > 0f) {
                if (snapshot == null && !snapshotting) {
                    snapshotting = true
                    // 工具条先从原位摘下来（下一帧重录时不带它），截图里就没有工具条
                    toolbarOut.snapTo(0f)
                    PlayerToolbarLink.detached = true
                    withFrameNanos { }
                    snapshot = takeSnapshot()
                    snapshotting = false
                }
                genie.snapTo(PlayerDismissDrag.progress * DRAG_GENIE_MAX)
                // 工具条比播放页先走：拖到三分之一就完全滑出
                toolbarOut.snapTo((PlayerDismissDrag.progress * 3f).coerceAtMost(1f))
            } else if (!dragging && p == 0f && snapshot != null) {
                coroutineScope {
                    launch { toolbarOut.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 420f)) }
                    genie.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 420f))
                }
                snapshot = null
                PlayerToolbarLink.detached = false
            }
        }
    }

    LaunchedEffect(visibleTarget) {
        if (visibleTarget) {
            // 打开 = 收回的倒放：同一套 Genie 网格，从音乐栏正中间（t = 1）放回全屏（t = 0）
            finished = false
            opening = true
            hideLive = true
            snapshot = null
            open.snapTo(1f)
            // 工具条先摘下来（不进截图），停在屏幕底部外，等播放页展开到一半再滑进来
            toolbarOut.snapTo(1f)
            PlayerToolbarLink.detached = true
            // 播放页刚组合的头几帧很重（封面解码、歌词、玻璃图层）：等帧率稳定（连续 2 帧 < 24ms，最多 240ms）再截图开播
            awaitStableFrames(maxWaitMs = PlayerWarmState.openWaitMs(firstShort = 240L))
            val bmp = takeSnapshot()
            coroutineScope {
                // 底栏已在滑走：再等一小会儿工具条才进来，两条不会叠在一起
                launch {
                    delay(BAR_HANDOFF_DELAY_MS / 2)
                    toolbarOut.animateTo(0f, tween(TOOLBAR_IN_MS, easing = FastOutSlowInEasing))
                }
                if (bmp != null) {
                    genie.snapTo(1f)
                    snapshot = bmp
                    // 严格倒放：value(τ) = GenieEasing(1 - τ)
                    genie.animateFramePaced(0f, OPEN_GENIE_MS, ReverseGenieEasing)
                } else {
                    // 截图失败：退回圆角展开
                    open.snapTo(0f)
                    hideLive = false
                    open.animateFramePaced(1f, EXPAND_MS, ExpandEasing)
                }
            }
            // t = 0 时网格就是原图、工具条也已就位：换回实时播放页没有跳变
            hideLive = false
            snapshot = null
            PlayerToolbarLink.detached = false
            opening = false
            PlayerWarmState.markOpened()
        } else {
            opening = false
            if (!PlayerToolbarLink.detached) {
                toolbarOut.snapTo(0f)
                PlayerToolbarLink.detached = true
                if (snapshot == null) withFrameNanos { }
            }
            val bmp = snapshot ?: takeSnapshot()?.also { snapshot = it }
            coroutineScope {
                // 工具条先滑走，底栏随后滑回（见 AdaptiveAppScaffold 的交接延迟）
                launch { toolbarOut.animateTo(1f, tween(TOOLBAR_OUT_MS, easing = FastOutSlowInEasing)) }
                if (bmp != null) {
                    // 从当前进度（下拉时已经走了一段）接着收：剩下的路程按比例给时长，至少 320ms，完整展示缩放
                    val from = genie.value
                    val ms = (GENIE_MS * (1f - from)).toInt().coerceAtLeast(320)
                    genie.animateFramePaced(1f, ms, GenieEasing)
                } else {
                    open.animateFramePaced(0f, 360, GenieEasing)
                }
            }
            finished = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                val p = it.positionInWindow()
                winOffset[0] = p.x
                winOffset[1] = p.y
            }
    ) {
        // 实时播放页（Genie 期间隐藏）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = open.value
                    alpha = if (snapshot != null || finished) 0f else 1f
                    if (p >= 0.999f) {
                        clip = false
                        shape = RectangleShape
                    } else {
                        val ol = origin.left - winOffset[0]
                        val ot = origin.top - winOffset[1]
                        val or = origin.right - winOffset[0]
                        val ob = origin.bottom - winOffset[1]
                        clip = true
                        shape = IslandClipShape(
                            left = lerp(ol, 0f, p),
                            top = lerp(ot, 0f, p),
                            right = lerp(or, size.width, p),
                            bottom = lerp(ob, size.height, p),
                            radius = lerp(origin.height / 2f, 0f, p)
                        )
                    }
                }
                // 展开初期内容还没淡入：胶囊先是实心底色（打开前隐藏播放页时不画，下层页面照常露出）
                .drawBehind { if (!hideLive) drawRect(background) }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = open.value
                        alpha = ((p - 0.22f) / 0.5f).coerceIn(0f, 1f)
                        val s = 0.82f + 0.18f * p
                        scaleX = s
                        scaleY = s
                        val w = size.width.coerceAtLeast(1f)
                        val h = size.height.coerceAtLeast(1f)
                        transformOrigin = TransformOrigin(
                            ((origin.center.x - winOffset[0]) / w).coerceIn(0f, 1f),
                            ((origin.center.y - winOffset[1]) / h).coerceIn(0f, 1f)
                        )
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    // 同时把播放页录进一个图层：关闭时直接从它截图做 Genie（不用再走一遍 View 绘制）
                    .drawWithContent {
                        recordLayer.record { this@drawWithContent.drawContent() }
                        if (!hideLive) drawLayer(recordLayer)
                    }
            ) {
                if (!finished) PlayerContentRoot(content)
            }
        }

        // Genie：截图按网格变形，被「吸」进底栏播放条
        val bmp = snapshot
        if (bmp != null && !finished) {
            val verts = remember(bmp) { FloatArray((MESH_W + 1) * (MESH_H + 1) * 2) }
            val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val t = genie.value
                val w = size.width
                val h = size.height
                // 目标：播放条左侧的封面小方块（本地坐标）
                val tl = target.left - winOffset[0]
                val tt = target.top - winOffset[1]
                val tr = target.right - winOffset[0]
                val tb = target.bottom - winOffset[1]
                // 三段叠加：先弯（两侧沿 S 曲线收窄成漏斗）→ 底边落到目标 → 整体滑进漏斗口
                val bend = smooth(t / 0.45f)
                val suck = smooth((t - 0.22f) / 0.78f)
                val yTop = lerp(0f, tt, suck)
                val yBottom = lerp(h, tb, smooth(t / 0.62f))
                val funnelSpan = tt.coerceAtLeast(1f)
                val sx = w / bmp.width
                val sy = h / bmp.height
                var k = 0
                for (j in 0..MESH_H) {
                    val v = j.toFloat() / MESH_H
                    val y = lerp(yTop, yBottom, v)
                    // 越靠近播放条越窄：按屏幕高度位置走一条 S 形曲线
                    val f = smooth(y / funnelSpan) * bend
                    val left = lerp(0f, tl, f)
                    val right = lerp(w, tr, f)
                    for (i in 0..MESH_W) {
                        val u = i.toFloat() / MESH_W
                        verts[k++] = lerp(left, right, u) / sx
                        verts[k++] = y / sy
                    }
                }
                // 只在最后一小段淡出，前面完整展示缩放
                paint.alpha = (255 * (1f - ((t - 0.93f) / 0.07f).coerceIn(0f, 1f))).toInt()
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.save()
                    canvas.nativeCanvas.scale(sx, sy)
                    canvas.nativeCanvas.drawBitmapMesh(bmp, MESH_W, MESH_H, verts, 0, null, 0, paint)
                    canvas.nativeCanvas.restore()
                }
            }
        }
        // 底部玻璃工具条：单独画在最上面，和底栏交接上下滑
        if (!finished) PlayerToolbarOverlay(toolbarOut)
    }
}
