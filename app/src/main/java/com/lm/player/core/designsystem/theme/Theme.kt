package com.lm.player.core.designsystem.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

val AppleRed = Color(0xFFFA2D48)
val AppleRedDark = Color(0xFFD81E37)
val AppleLightBg = Color(0xFFF2F2F7)
val AppleDarkBg = Color(0xFF101014)
val AppleCardLight = Color(0xFFFFFFFF)
val AppleCardDark = Color(0xFF1C1C20)
val ApplePillBg = Color(0xFFE5E5EA)

enum class AppThemeMode(val displayName: String) {
    FOLLOW_SYSTEM("跟随系统"),
    DARK("纯黑深色"),
    LIGHT("纯白浅色")
}

// 针对媒体播放器深度调优的高对比度亮色调色板
private val LightColorScheme = lightColorScheme(
    primary = AppleRed,
    onPrimary = Color.White,
    background = AppleLightBg,
    onBackground = Color(0xFF111113),
    surface = AppleCardLight,
    onSurface = Color(0xFF111113),
    surfaceVariant = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0xFF636366),
    outline = Color(0xFFD1D1D6),
    outlineVariant = Color(0xFFE5E5EA),
    surfaceTint = Color.Transparent
)

// 针对车机大屏与现代流媒体深度调优的高对比度深色调色板 (高亮白字，不反光)
private val DarkColorScheme = darkColorScheme(
    primary = AppleRed,
    onPrimary = Color.White,
    background = AppleDarkBg,
    onBackground = Color(0xFFF5F5F7),
    surface = AppleCardDark,
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2C2C30),
    onSurfaceVariant = Color(0xFFA1A1A8),
    outline = Color(0xFF38383A),
    outlineVariant = Color(0xFF2C2C30),
    surfaceTint = Color.Transparent
)

// 全局统一形状系统 (统一 16dp 浮层圆角，彻底消除直角阴影与遮罩色差)
val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
)

@Composable
fun LMPlayerTheme(
    themeMode: AppThemeMode = AppThemeMode.FOLLOW_SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
    }

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = Color.Transparent.toArgb()
                window.navigationBarColor = Color.Transparent.toArgb()
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        typography = Typography(),
        content = content
    )
}

@Composable
fun ZDSPlayerTheme(
    themeMode: AppThemeMode = AppThemeMode.FOLLOW_SYSTEM,
    content: @Composable () -> Unit
) = LMPlayerTheme(themeMode = themeMode, content = content)
