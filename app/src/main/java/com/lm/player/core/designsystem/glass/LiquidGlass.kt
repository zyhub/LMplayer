package com.lm.player.core.designsystem.glass

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * 液态玻璃（Kyant0/AndroidLiquidGlass 的 backdrop 库，Apache-2.0，https://github.com/Kyant0/AndroidLiquidGlass）。
 *
 * 纯 Compose 实现：页面内容用 `Modifier.layerBackdrop` 录制成一个图层，玻璃用 `Modifier.drawBackdrop`
 * 取这个图层做模糊（RenderEffect）+ 透镜折射（AGSL）+ 边缘高光。图层是 RenderNode，内容变化时玻璃自动跟着刷新，
 * 不再需要按帧轮询（[GlassRefreshController] 保留为空实现，兼容旧调用）。
 *
 * 只在「Android 13+（AGSL 着色器）· 非低内存机 · 未开省电」时启用真玻璃；其余设备保持原来的半透明样式。
 *
 * 玻璃不能采样「包含它自己的那块内容」（图层成环），所以：
 * - 底栏 / 顶栏这些玻璃仍放在主界面上层独立的 ComposeView（[GlassTopLayer]），采样主界面整页（[AppGlassBackdrop]）；
 * - 弹出菜单在独立窗口里，同样采样主界面整页（按屏幕坐标对齐）；
 * - 播放页按钮采样页内的封面模糊底图（[CoverBackdrop] 外面套 `layerBackdrop`）。
 */
object LiquidGlassSupport {
    private const val PREFS = "lemon_settings_prefs"
    const val KEY_ENABLED = "ui_liquid_glass_enabled"
    /** 机型判断为「不推荐」时用户仍手动开启（Android 13+ 都允许） */
    const val KEY_FORCED = "ui_liquid_glass_forced"
    /** 档位：true = 高档（全部玻璃），false = 低档（只有播放栏与列表顶栏） */
    const val KEY_FULL = "ui_liquid_glass_full"

    private val _forced = MutableStateFlow(false)
    val forced: StateFlow<Boolean> = _forced

    /** 最近一次玻璃出错的原因（设置页显示，方便定位新系统版本上的问题） */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    /** 硬性条件只有 Android 13+（透镜折射用的 AGSL 着色器）；其余机型条件只是推荐 */
    fun isSdkSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** 设置页诊断：系统版本与 CPU 架构 */
    fun deviceSummary(): String =
        "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}" +
            (Build.VERSION.CODENAME.takeIf { it != "REL" }?.let { " ($it)" } ?: "") +
            " · ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}"

    private val _fullLevel = MutableStateFlow(true)
    /** 档位：高档 = 全部玻璃；低档 = 只有播放栏（含底栏搜索音源面板）与列表顶栏，其余（播放页、弹出面板、悬浮按钮、进度条 / 开关、修标签弹窗）用原样式 */
    val fullLevel: StateFlow<Boolean> = _fullLevel

    fun setFullLevel(context: Context, full: Boolean) {
        _fullLevel.value = full
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FULL, full).apply()
    }

    private val _enabled = MutableStateFlow(true)
    /** 用户开关（设置页「液态玻璃」） */
    val enabled: StateFlow<Boolean> = _enabled

    private val _powerSave = MutableStateFlow(false)
    val powerSave: StateFlow<Boolean> = _powerSave

    @Volatile
    private var deviceSupportedCache: Boolean? = null
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _enabled.value = p.getBoolean(KEY_ENABLED, true)
        _forced.value = p.getBoolean(KEY_FORCED, false)
        // 首次安装（从没设置过档位）：按手机性能自动选，之后用户在设置里改的会保存下来
        if (!p.contains(KEY_FULL)) {
            val auto = recommendFullLevel(app)
            p.edit().putBoolean(KEY_FULL, auto).apply()
        }
        _fullLevel.value = p.getBoolean(KEY_FULL, true)
        refreshPowerSave(app)
    }

    /**
     * 打开 / 关闭。打开时：机型判断不推荐但系统是 Android 13+ 的，记为「强制开启」；
     * 之前出错回退过的，清掉出错标记重新尝试。
     */
    fun setEnabled(context: Context, value: Boolean) {
        if (value) {
            if (!isSdkSupported()) return
            val forcedNow = !isDeviceSupported(context)
            _forced.value = forcedNow
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_FORCED, forcedNow).apply()
            _broken.value = false
        }
        _enabled.value = value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** Activity onResume 时调用：省电模式切换后回到 App 即生效 */
    fun refreshPowerSave(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        _powerSave.value = pm?.isPowerSaveMode == true
    }

    /**
     * 推荐条件：Android 13+，且非低内存机（3GB 以上；电脑上的模拟器不看内存）。
     * 新库全部走 GPU（RenderEffect 模糊 + AGSL 透镜），没有原生 .so，ARM / x86 都能用。
     */
    fun isDeviceSupported(context: Context): Boolean {
        deviceSupportedCache?.let { return it }
        val result = runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@runCatching false
            if (isEmulatorLike()) return@runCatching true
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am?.isLowRamDevice == true) return@runCatching false
            val info = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            // 3GB 以下视为低端机
            info.totalMem <= 0L || info.totalMem >= 3L * 1024 * 1024 * 1024
        }.getOrDefault(false)
        deviceSupportedCache = result
        return result
    }

    /**
     * 首次安装时的档位推荐：高档（全部玻璃）要求设备支持玻璃，且属于高性能机——
     * 系统声明的媒体性能等级 ≥ Android 12（S），或内存 ≥ 7.5GB 且 8 核以上；其余用低档（只有播放栏与列表顶栏是玻璃）。
     */
    fun recommendFullLevel(context: Context): Boolean = runCatching {
        if (!isDeviceSupported(context)) return@runCatching false
        if (isEmulatorLike()) return@runCatching true
        val perfClass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0
        if (perfClass >= Build.VERSION_CODES.S) return@runCatching true
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        val memOk = info.totalMem >= (7.5 * 1024 * 1024 * 1024).toLong()
        val coresOk = Runtime.getRuntime().availableProcessors() >= 8
        memOk && coresOk
    }.getOrDefault(false)

    /** x86 指令集（如今只有电脑上的模拟器）或官方模拟器特征 */
    fun isEmulatorLike(): Boolean {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        if (abi.startsWith("x86")) return true
        val fp = Build.FINGERPRINT.orEmpty()
        val hw = Build.HARDWARE.orEmpty()
        val product = Build.PRODUCT.orEmpty()
        return fp.startsWith("generic") || fp.contains("emulator") ||
            hw == "goldfish" || hw == "ranchu" || hw.contains("vbox") ||
            product.contains("sdk_gphone") || product.contains("emulator")
    }

    // ===== 兜底：玻璃出错时整体回退原样式，本次运行不再尝试 =====
    private val _broken = MutableStateFlow(false)
    val broken: StateFlow<Boolean> = _broken

    fun markBroken(error: Throwable) {
        if (_broken.value) return
        android.util.Log.w("LiquidGlass", "glass failed, fallback to classic style", error)
        _lastError.value = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(120)}"
        _broken.value = true
    }

    // ===== 诊断：当前渲染管线（设置页显示） =====
    private val _pipeline = MutableStateFlow("")
    val pipeline: StateFlow<String> = _pipeline

    internal fun reportPipeline(name: String) {
        if (_pipeline.value != name) _pipeline.value = name
    }

    internal const val PIPELINE_NAME = "Kyant0 Backdrop（AGSL 透镜 + GPU 模糊）"
}

/** 当前是否应绘制真玻璃（设备支持 + 用户开启 + 未省电） */
@Composable
fun rememberLiquidGlassActive(): Boolean {
    val context = LocalContext.current
    val recommended = remember { LiquidGlassSupport.isDeviceSupported(context) }
    val forced by LiquidGlassSupport.forced.collectAsState()
    val supported = LiquidGlassSupport.isSdkSupported() && (recommended || forced)
    val enabled by LiquidGlassSupport.enabled.collectAsState()
    val powerSave by LiquidGlassSupport.powerSave.collectAsState()
    val broken by LiquidGlassSupport.broken.collectAsState()
    return supported && enabled && !powerSave && !broken
}

/**
 * 高档玻璃是否生效：播放页、弹出面板、悬浮按钮、液态进度条 / 开关、修标签弹窗等只在高档时用玻璃。
 * 播放栏与列表顶栏用 [rememberLiquidGlassActive]（低档也是玻璃）。
 */
@Composable
fun rememberLiquidGlassFull(): Boolean {
    val active = rememberLiquidGlassActive()
    val full by LiquidGlassSupport.fullLevel.collectAsState()
    return active && full
}

/** 玻璃材质：CLEAR 通透（只略提饱和度），REGULAR 常规（vibrancy 提亮色彩） */
enum class GlassMaterial { CLEAR, REGULAR }

/** 播放栏玻璃外观（由设置页两个滑块换算） */
data class BarGlassStyle(
    val tint: Color,
    val blurAmount: Float,
    val dispersion: Float,
    val material: GlassMaterial
)

/**
 * 设置页滑块 → 播放栏玻璃外观。
 *
 * @param opacity 「播放栏透明度」0.10–1.0：控制玻璃底色浓淡，拉到最低 = 完全不染色
 * @param blurDp 「液态玻璃高斯模糊」0–30：控制磨砂程度，拉到 0 = 完全关闭模糊
 *
 * 两个都拉到最低时是 Apple「Clear」那种通透玻璃：背景清晰可见，只剩边缘折射、高光与色散。
 */
fun barGlassStyle(isDark: Boolean, opacity: Float, blurDp: Float): BarGlassStyle {
    val o = ((opacity - 0.10f) / 0.90f).coerceIn(0f, 1f)
    val b = (blurDp / 30f).coerceIn(0f, 1f)
    return BarGlassStyle(
        tint = if (isDark) Color(0xFF15151C).copy(alpha = 0.42f * o) else Color.White.copy(alpha = 0.58f * o),
        blurAmount = b * 0.5f,
        dispersion = 0.10f + 0.12f * (1f - b),
        material = if (isDark && o < 0.4f) GlassMaterial.CLEAR else GlassMaterial.REGULAR
    )
}

/**
 * 当前播放栏玻璃外观（底栏组合时写入）：顶部固定按钮、搜索面板、下拉菜单与底栏保持同一套滑块效果。
 */
object GlassStyleState {
    var barStyle by mutableStateOf<BarGlassStyle?>(null)
    /** 设置页「透明度」滑块原值（0.10–1.0）：所有液态玻璃的底色浓淡都跟它走 */
    var opacity by mutableStateOf(0.85f)
    /** 设置页「高斯模糊」滑块原值（0–30 dp）：所有液态玻璃的磨砂强度都跟它走 */
    var blurDp by mutableStateOf(16f)

    /** 透明度归一化到 0–1 */
    val opacityLevel: Float get() = ((opacity - 0.10f) / 0.90f).coerceIn(0f, 1f)
    /** 模糊归一化到 0–1 */
    val blurLevel: Float get() = (blurDp / 30f).coerceIn(0f, 1f)
}

/** 播放页 / 浮层按钮等「叠在封面底图上」的玻璃底色：跟随设置页的透明度滑块 */
fun accentGlassTint(isDark: Boolean, emphasized: Boolean = false): Color {
    val o = GlassStyleState.opacityLevel
    val alpha = when {
        emphasized && isDark -> 0.04f + 0.18f * o
        emphasized -> 0.12f + 0.42f * o
        isDark -> 0.01f + 0.08f * o
        else -> 0.05f + 0.27f * o
    }
    return Color.White.copy(alpha = alpha)
}

/** 叠在封面底图上的玻璃磨砂：底图本身已模糊，这里按滑块给一点点（拉到 0 完全不模糊） */
fun accentGlassBlur(): Float = GlassStyleState.blurLevel * 0.30f

/**
 * 内容面板 / 菜单用的玻璃：跟随底栏滑块，但保底一定的底色与磨砂，
 * 否则滑块拉到最低时列表文字会和下面的页面内容叠在一起看不清。
 */
fun panelGlassStyle(isDark: Boolean): BarGlassStyle {
    val base = GlassStyleState.barStyle ?: barGlassStyle(isDark, 0.85f, 16f)
    val o = GlassStyleState.opacityLevel
    val minAlpha = if (isDark) 0.50f + 0.22f * o else 0.60f + 0.22f * o
    val tintBase = if (isDark) Color(0xFF15151C) else Color.White
    return base.copy(
        tint = tintBase.copy(alpha = maxOf(base.tint.alpha, minAlpha)),
        // 弹出面板一律高模糊：下面的内容只剩色块，文字不会和背景叠在一起
        blurAmount = maxOf(base.blurAmount, 0.62f + 0.18f * GlassStyleState.blurLevel),
        dispersion = 0.06f,
        material = GlassMaterial.REGULAR
    )
}

/** 圆角矩形玻璃底座：玻璃铺满 [modifier] 决定的区域，[content] 画在上面 */
@Composable
fun GlassSurface(
    style: BarGlassStyle,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier) {
        LiquidGlassLayer(
            modifier = Modifier.matchParentSize(),
            cornerRadius = cornerRadius,
            tint = style.tint,
            blurAmount = style.blurAmount,
            dispersion = style.dispersion,
            material = style.material
        )
        content()
    }
}

/** 玻璃要折射的背景（Kyant0 Backdrop）；为 null 时玻璃不绘制，调用方走原样式 */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

// =====================================================================================
// 主界面整页背景：MainActivity 根布局录制，上层玻璃 / 弹出菜单按屏幕坐标采样
// =====================================================================================

/** 主界面整页内容录成的图层（由根布局的 [appGlassBackdropSource] 登记） */
object AppGlassBackdrop {
    internal var layer by mutableStateOf<LayerBackdrop?>(null)
    internal var hostView: View? = null
}

/** 放在主界面根布局上：把整页内容录成玻璃背景图层 */
@Composable
fun Modifier.appGlassBackdropSource(): Modifier {
    val layer = rememberLayerBackdrop()
    val view = LocalView.current
    DisposableEffect(layer, view) {
        AppGlassBackdrop.layer = layer
        AppGlassBackdrop.hostView = view
        onDispose {
            if (AppGlassBackdrop.layer === layer) {
                AppGlassBackdrop.layer = null
                AppGlassBackdrop.hostView = null
            }
        }
    }
    return this.layerBackdrop(layer)
}

/**
 * 在另一个视图 / 窗口里（上层玻璃 ComposeView、弹出菜单）采样主界面：
 * 用屏幕坐标把主界面图层对齐到玻璃所在位置；玻璃自身的缩放（按下放大、弹出动画）会被抵消，背景不跟着放大。
 * 在主界面自己里面调用时返回 null（图层不能采样包含自己的内容）。
 */
@Composable
fun rememberAppGlassBackdrop(): Backdrop? {
    val layer = AppGlassBackdrop.layer ?: return null
    val source = AppGlassBackdrop.hostView ?: return null
    if (LocalView.current === source) return null
    return remember(layer, source) { ScreenAlignedBackdrop(layer, source) }
}

private class ScreenAlignedBackdrop(
    private val source: LayerBackdrop,
    private val sourceView: View
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    private val location = IntArray(2)

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val coords = coordinates ?: return
        if (!coords.isAttached || !sourceView.isAttachedToWindow) return
        val w = coords.size.width.toFloat()
        val h = coords.size.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val topLeft = runCatching { coords.localToScreen(Offset.Zero) }.getOrNull() ?: return
        val bottomRight = runCatching { coords.localToScreen(Offset(w, h)) }.getOrNull() ?: return
        sourceView.getLocationOnScreen(location)
        val sx = ((bottomRight.x - topLeft.x) / w).takeIf { it > 0.01f } ?: 1f
        val sy = ((bottomRight.y - topLeft.y) / h).takeIf { it > 0.01f } ?: 1f
        withTransform({
            scale(1f / sx, 1f / sy, Offset.Zero)
            translate(location[0] - topLeft.x, location[1] - topLeft.y)
        }) {
            drawLayer(source.graphicsLayer)
        }
    }
}

/**
 * 旧库按帧刷新玻璃的调度器。新库的玻璃直接引用页面图层（RenderNode），内容一变自动重绘，
 * 不再需要轮询；保留空实现兼容旧调用。
 */
object GlassRefreshController {
    fun keepLive(durationMs: Long = 0L) {}
    fun onTouchEvent(event: MotionEvent) {}
    fun setForeground(value: Boolean) {}
}

/**
 * 任意形状尺寸的玻璃底座（播放页分段切换胶囊等）：[backdrop] 为 null 时就是普通 Box，布局不变。
 */
@Composable
fun GlassBox(
    backdrop: Backdrop?,
    isDark: Boolean,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    contentAlignment: Alignment = Alignment.TopStart,
    /** 面板（有文字列表的展开框）：用高模糊的面板玻璃，保证文字清楚 */
    panel: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier, contentAlignment = contentAlignment) {
        if (backdrop != null) {
            val panelStyle = if (panel) panelGlassStyle(isDark) else null
            LiquidGlassLayer(
                modifier = Modifier.matchParentSize(),
                cornerRadius = cornerRadius,
                tint = panelStyle?.tint ?: accentGlassTint(isDark, emphasized),
                blurAmount = panelStyle?.blurAmount ?: accentGlassBlur(),
                dispersion = panelStyle?.dispersion ?: if (emphasized) 0.20f else 0.16f,
                material = panelStyle?.material ?: if (isDark) GlassMaterial.CLEAR else GlassMaterial.REGULAR,
                backdropOverride = backdrop
            )
        }
        content()
    }
}

/**
 * 圆形玻璃按钮底座：[backdrop] 为 null（未启用玻璃）时原样只画 [content]，布局尺寸不变。
 */
@Composable
fun GlassCircle(
    backdrop: Backdrop?,
    size: Dp,
    isDark: Boolean,
    emphasized: Boolean = false,
    content: @Composable () -> Unit
) {
    if (backdrop == null) {
        content()
        return
    }
    Box(
        modifier = Modifier.size(size).glassPressBulge(),
        contentAlignment = Alignment.Center
    ) {
        LiquidGlassLayer(
            modifier = Modifier.matchParentSize(),
            cornerRadius = size / 2,
            tint = accentGlassTint(isDark, emphasized),
            blurAmount = accentGlassBlur(),
            dispersion = if (emphasized) 0.20f else 0.16f,
            material = if (isDark) GlassMaterial.CLEAR else GlassMaterial.REGULAR,
            backdropOverride = backdrop
        )
        content()
    }
}

/**
 * 液态玻璃修饰符：采样 [backdrop]，按材质提色、按 [blurAmount] 磨砂、边缘透镜折射（[dispersion] 大时带色散），
 * 再叠 [tint] 底色与边缘高光。形状须是圆角矩形 / 胶囊（透镜着色器只支持这类形状）。
 */
fun Modifier.liquidGlass(
    backdrop: Backdrop,
    shape: Shape,
    tint: Color,
    blurAmount: Float,
    dispersion: Float,
    material: GlassMaterial,
    refractionHeight: Dp? = null,
    highlightAlpha: Float = 1f,
    shadow: Boolean = false,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null
): Modifier = this.drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        when (material) {
            GlassMaterial.REGULAR -> vibrancy()
            GlassMaterial.CLEAR -> colorControls(saturation = 1.25f)
        }
        // 旧库的 blurAmount 0–1 ≈ 0–32px，这里换成 0–24dp
        if (blurAmount > 0f) blur((blurAmount * 24f).dp.toPx())
        val minSide = size.minDimension
        if (minSide > 0f) {
            val height = refractionHeight?.toPx() ?: minOf(minSide * 0.30f, 20.dp.toPx())
            val amount = minOf(minSide * 0.45f, 28.dp.toPx())
            lens(height, amount, chromaticAberration = dispersion >= 0.15f)
        }
    },
    highlight = if (highlightAlpha > 0f) {
        { Highlight.Default.copy(alpha = highlightAlpha) }
    } else null,
    shadow = if (shadow) {
        { Shadow.Default }
    } else null,
    layerBlock = layerBlock,
    onDrawSurface = if (tint.alpha > 0f) {
        { drawRect(tint) }
    } else null
)

/**
 * 悬浮圆形按钮（置顶 / 定位当前歌曲）的玻璃：[backdrop] 为 null 时原样返回，调用方照旧画实心按钮。
 * 底色带一点 [tint]（定位按钮红、置顶按钮中性），模糊跟随设置滑块，带柔和投影浮在页面上。
 */
fun Modifier.floatingGlass(
    backdrop: Backdrop?,
    shape: Shape = androidx.compose.foundation.shape.CircleShape
): Modifier {
    if (backdrop == null) return this
    // 与底栏搜索圆钮同一块玻璃：同底色、同模糊、无描边、无投影
    val style = GlassStyleState.barStyle ?: barGlassStyle(false, GlassStyleState.opacity, GlassStyleState.blurDp)
    return this.liquidGlass(
        backdrop = backdrop,
        shape = shape,
        tint = style.tint,
        blurAmount = style.blurAmount,
        dispersion = style.dispersion,
        material = style.material
    )
}

/**
 * 一块液态玻璃底板。放在容器 Box 的第一个子级，`Modifier.matchParentSize()`，内容画在它上面。
 *
 * @param tint 玻璃底色（alpha 即强度）；alpha 为 0 = 不染色
 * @param blurAmount 磨砂程度（0–1）；0 = 完全关闭背景模糊（通透玻璃）
 * @param primaryShape / [secondaryShape] 只在这两块矩形里画玻璃（视图内 px 坐标；旧库能把两块融合，新库分别画）
 */
@Composable
fun LiquidGlassLayer(
    modifier: Modifier = Modifier,
    cornerRadius: Dp,
    tint: Color,
    blurAmount: Float = 0.08f,
    dispersion: Float = 0.10f,
    material: GlassMaterial = GlassMaterial.REGULAR,
    refractionHeight: Dp? = null,
    @Suppress("UNUSED_PARAMETER") bevelWidth: Dp? = null,
    @Suppress("UNUSED_PARAMETER") saturation: Float = 150f,
    backdropOverride: Backdrop? = null,
    primaryShape: Rect? = null,
    secondaryShape: Rect? = null,
    @Suppress("UNUSED_PARAMETER") mergeSmoothingPx: Float = 48f
) {
    val backdrop = backdropOverride ?: LocalGlassBackdrop.current ?: return
    androidx.compose.runtime.LaunchedEffect(Unit) { LiquidGlassSupport.reportPipeline(LiquidGlassSupport.PIPELINE_NAME) }
    val shape = RoundedCornerShape(cornerRadius)
    if (primaryShape == null && secondaryShape == null) {
        Box(
            modifier = modifier.liquidGlass(
                backdrop = backdrop,
                shape = shape,
                tint = tint,
                blurAmount = blurAmount,
                dispersion = dispersion,
                material = material,
                refractionHeight = refractionHeight
            )
        )
        return
    }
    val density = LocalDensity.current
    Box(modifier = modifier) {
        listOfNotNull(primaryShape, secondaryShape).forEach { r ->
            if (r.width > 0.5f && r.height > 0.5f) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                        .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
                        .liquidGlass(
                            backdrop = backdrop,
                            shape = shape,
                            tint = tint,
                            blurAmount = blurAmount,
                            dispersion = dispersion,
                            material = material,
                            refractionHeight = refractionHeight
                        )
                )
            }
        }
    }
}

/**
 * 把 [content] 放到主界面上层一个独立的 ComposeView 里绘制（与主界面共享同一套状态与主题），
 * 其中的玻璃通过 [LocalGlassBackdrop] 采样主界面整页（[AppGlassBackdrop]）。
 *
 * 上层视图里没有可点元素的区域不消费触摸，点击照常落到下面的主界面。
 * [hidden] 为 true 时整层隐藏（全屏播放页、启动页盖在上面时），组合状态保留。
 */
@Composable
fun GlassTopLayer(
    hidden: Boolean,
    /** 多个上层之间的前后顺序：底栏 2，搜索面板 1（面板的遮罩要在底栏下面） */
    zOrder: Float = 2f,
    content: @Composable () -> Unit
) {
    val hostView = LocalView.current
    val parentContext = rememberCompositionContext()
    val latestContent = rememberUpdatedState(content)
    // 主界面用了自适应缩放密度，上层视图要沿用同一套，否则播放栏尺寸会变
    val latestDensity = rememberUpdatedState(LocalDensity.current)
    val latestLayoutDirection = rememberUpdatedState(LocalLayoutDirection.current)
    val overlay = remember(hostView) { ComposeView(hostView.context) }

    DisposableEffect(hostView, overlay) {
        val container = hostView.rootView.findViewById<ViewGroup>(android.R.id.content)
        if (container == null) {
            onDispose { }
        } else {
            overlay.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            overlay.setParentCompositionContext(parentContext)
            overlay.setContent {
                CompositionLocalProvider(
                    LocalDensity provides latestDensity.value,
                    LocalLayoutDirection provides latestLayoutDirection.value,
                    LocalGlassBackdrop provides rememberAppGlassBackdrop()
                ) {
                    latestContent.value()
                }
            }
            // FrameLayout 按 Z 值决定绘制与触摸分发顺序；没有背景就没有轮廓，不会产生投影
            overlay.translationZ = zOrder
            container.addView(
                overlay,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            onDispose {
                container.removeView(overlay)
            }
        }
    }

    SideEffect {
        val target = if (hidden) View.INVISIBLE else View.VISIBLE
        if (overlay.visibility != target) overlay.visibility = target
    }
}

/**
 * 按下时玻璃微微鼓起（iOS 26 交互玻璃的按压反馈），松手弹回。
 * 只观察指针、不消费事件，原有的点击 / 拖动照常工作。
 */
fun Modifier.glassPressBulge(enabled: Boolean = true, pressedScale: Float = 1.07f, holdOff: Boolean = false): Modifier =
    if (!enabled) this else this.composed {
        var pressed by remember { mutableStateOf(false) }
        val scale by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (pressed && !holdOff) pressedScale else 1f,
            animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 520f),
            label = "glass_press_bulge"
        )
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    waitForUpOrCancellation()
                    pressed = false
                }
            }
    }
