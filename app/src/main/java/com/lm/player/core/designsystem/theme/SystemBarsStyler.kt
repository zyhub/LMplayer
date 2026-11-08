package com.lm.player.core.designsystem.theme

import android.app.Activity
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 系统状态栏 / 导航栏着色（深色模式系统级适配）。
 *
 * 应用没有开启 edge-to-edge（targetSdk 34、未调用 enableEdgeToEdge），内容不会画到系统栏下面，
 * 系统栏背后露出的是「窗口背景」。此前系统栏设成透明、窗口背景来自浅色主题
 * （android:Theme.Material.Light），于是应用切到深色后顶部状态栏仍是白底，
 * 而图标又被切成了白色，几乎看不见。
 *
 * 现在：窗口背景跟随应用主题；系统栏显式涂成当前页面的背景色；图标深浅按底色亮度自动决定。
 * 背景与应用底色不同的全屏页面（如播放页）用 [SystemBarsColor] 临时覆盖，离开页面自动恢复。
 */
object SystemBarsStyler {
    private data class Style(val statusBar: Int, val navigationBar: Int)

    private var baseStyle: Style? = null
    private val overrides = ArrayList<Pair<Any, Style>>()

    /** 应用底色，与 Theme.kt 中配色方案的 background 保持一致 */
    fun backgroundFor(dark: Boolean): Color = if (dark) AppleDarkBg else AppleLightBg

    /** onCreate 中、setContent 之前调用：按保存的主题偏好先把窗口与系统栏涂好，避免冷启动白闪 */
    fun applyInitial(activity: Activity, themeMode: AppThemeMode) {
        val dark = when (themeMode) {
            AppThemeMode.DARK -> true
            AppThemeMode.LIGHT -> false
            AppThemeMode.FOLLOW_SYSTEM ->
                (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        }
        applyBase(activity.window, activity.window.decorView, dark)
    }

    /** 主题（深浅）确定或变化时调用：更新窗口背景与默认系统栏配色 */
    fun applyBase(window: Window, view: View, dark: Boolean) {
        val bg = backgroundFor(dark).toArgb()
        baseStyle = Style(bg, bg)
        window.setBackgroundDrawable(ColorDrawable(bg))
        render(window, view)
    }

    internal fun push(key: Any, statusBar: Color, navigationBar: Color, window: Window, view: View) {
        overrides.removeAll { it.first === key }
        overrides.add(key to Style(statusBar.toArgb(), navigationBar.toArgb()))
        render(window, view)
    }

    internal fun pop(key: Any, window: Window, view: View) {
        if (overrides.removeAll { it.first === key }) render(window, view)
    }

    private fun render(window: Window, view: View) {
        val style = overrides.lastOrNull()?.second ?: baseStyle ?: return
        val controller = WindowCompat.getInsetsController(window, view)

        window.statusBarColor = style.statusBar
        // isAppearanceLightStatusBars = true 表示「浅色底、深色图标」
        controller.isAppearanceLightStatusBars = Color(style.statusBar).luminance() > 0.5f

        val lightNav = Color(style.navigationBar).luminance() > 0.5f
        if (lightNav && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Android 8.0 以下不支持深色导航栏按键，浅色底会让白色按键看不见，退回黑底
            window.navigationBarColor = android.graphics.Color.BLACK
        } else {
            window.navigationBarColor = style.navigationBar
            controller.isAppearanceLightNavigationBars = lightNav
        }
    }
}

/**
 * 当前页面在组合中期间，把系统栏涂成指定颜色（页面背景与应用底色不同时使用，如全屏播放页）；
 * 页面离开组合后自动恢复应用默认配色。
 */
@Composable
fun SystemBarsColor(statusBar: Color, navigationBar: Color = statusBar) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val key = remember { Any() }
    DisposableEffect(statusBar, navigationBar) {
        val window = (view.context as? Activity)?.window
        if (window != null) SystemBarsStyler.push(key, statusBar, navigationBar, window, view)
        onDispose {
            if (window != null) SystemBarsStyler.pop(key, window, view)
        }
    }
}
