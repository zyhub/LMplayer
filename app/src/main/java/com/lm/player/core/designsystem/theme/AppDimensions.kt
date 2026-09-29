package com.lm.player.core.designsystem.theme

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * 全局字体与界面缩放规格模式
 */
enum class UiScaleMode(val label: String, val subtitle: String) {
    AUTO("智能自适应 (推荐)", "根据手机屏幕分辨率、DPI 与横竖屏自动匹配最佳视觉比例"),
    STANDARD_PHONE("标准精致 (100%)", "紧凑精致的现代流媒体标准字号比例"),
    CAR_LARGE("舒朗大字 (112%)", "适度放大全局各级标题与曲目列表字号，阅读更轻松"),
    CAR_EXTRA_LARGE("超大关怀 / 车机 (125%)", "显著增强远距离与车载中控大屏的字体视觉辨识度")
}

/**
 * 全局统一 7 级语义化字体规格与尺寸设计系统 (LMPlayer Typography & Dimension Spec)
 */
data class AppDimensions(
    val fontScale: Float = 1.0f,
    val resolutionScale: Float = 1.0f,
    val isCompactScreen: Boolean = false,

    // 7 级标准语义字号
    val pageTitleSize: TextUnit = 26.sp,
    val sectionTitleSize: TextUnit = 18.sp,
    val cardHeaderSize: TextUnit = 16.sp,
    val itemTitleSize: TextUnit = 15.sp,
    val bodySize: TextUnit = 13.5.sp,
    val captionSize: TextUnit = 12.sp,
    val badgeSize: TextUnit = 10.5.sp,

    // 兼容别名映射
    val titleLargeSize: TextUnit = pageTitleSize,
    val titleMediumSize: TextUnit = sectionTitleSize,
    val bodyLargeSize: TextUnit = itemTitleSize,
    val bodyMediumSize: TextUnit = bodySize,

    val coverLargeSize: Dp = 300.dp,
    val coverMediumSize: Dp = 90.dp,
    val coverSmallSize: Dp = 54.dp,

    val touchTargetLarge: Dp = 68.dp,
    val touchTargetMedium: Dp = 48.dp,
    val touchTargetSmall: Dp = 36.dp,

    val iconLargeSize: Dp = 36.dp,
    val iconMediumSize: Dp = 24.dp,
    val iconSmallSize: Dp = 16.dp,

    val paddingLarge: Dp = 24.dp,
    val paddingMedium: Dp = 16.dp,
    val paddingSmall: Dp = 8.dp
)

val LocalAppDimensions = compositionLocalOf { AppDimensions() }

private fun buildScaledDimensions(
    scale: Float,
    resolutionScale: Float = 1.0f,
    isCompactScreen: Boolean = false,
    coverLarge: Dp = 300.dp,
    coverMedium: Dp = 90.dp,
    coverSmall: Dp = 54.dp,
    touchLarge: Dp = 68.dp,
    touchMedium: Dp = 48.dp,
    touchSmall: Dp = 36.dp,
    iconLarge: Dp = 36.dp,
    iconMedium: Dp = 24.dp,
    iconSmall: Dp = 16.dp,
    padLarge: Dp = 24.dp,
    padMedium: Dp = 16.dp,
    padSmall: Dp = 8.dp
): AppDimensions {
    val pageTitle = 25.sp * scale
    val sectionTitle = 17.5.sp * scale
    val cardHeader = 15.5.sp * scale
    val itemTitle = 14.5.sp * scale
    val body = 13.sp * scale
    val caption = 11.5.sp * scale
    val badge = 10.sp * scale
    return AppDimensions(
        fontScale = scale,
        resolutionScale = resolutionScale,
        isCompactScreen = isCompactScreen,
        pageTitleSize = pageTitle,
        sectionTitleSize = sectionTitle,
        cardHeaderSize = cardHeader,
        itemTitleSize = itemTitle,
        bodySize = body,
        captionSize = caption,
        badgeSize = badge,
        titleLargeSize = pageTitle,
        titleMediumSize = sectionTitle,
        bodyLargeSize = itemTitle,
        bodyMediumSize = body,
        coverLargeSize = coverLarge,
        coverMediumSize = coverMedium,
        coverSmallSize = coverSmall,
        touchTargetLarge = touchLarge,
        touchTargetMedium = touchMedium,
        touchTargetSmall = touchSmall,
        iconLargeSize = iconLarge,
        iconMediumSize = iconMedium,
        iconSmallSize = iconSmall,
        paddingLarge = padLarge,
        paddingMedium = padMedium,
        paddingSmall = padSmall
    )
}

/**
 * 全局屏幕分辨率与系统字体缩放自适应 Density：
 * 1. 自动以 396dp 黄金手机短边宽度为基准进行等比自适应缩放，解决小屏/系统高显示缩放下控件拥挤问题；
 * 2. 钳制系统级异常超大 fontScale（防止系统大字体导致设置页与底栏文字竖排挤压变形）。
 */
@Composable
fun rememberAdaptiveDensity(scaleMode: UiScaleMode = UiScaleMode.AUTO): Density {
    val configuration = LocalConfiguration.current
    val baseDensity = LocalDensity.current
    val shortSideDp = min(configuration.screenWidthDp, configuration.screenHeightDp).coerceAtLeast(280)
    val isTabletOrCar = shortSideDp >= 600

    return remember(baseDensity.density, baseDensity.fontScale, shortSideDp, scaleMode) {
        val resolutionRatio = if (isTabletOrCar) {
            1.0f
        } else {
            // 手机端以 396dp 短边为标准基准，当手机短边 < 396dp（如 320dp/360dp 高缩放模式）时自动缩小 density，释放横向布局空间
            (shortSideDp.toFloat() / 396f).coerceIn(0.75f, 1.06f)
        }
        val effectiveDensity = baseDensity.density * resolutionRatio
        // 将系统 fontScale 归一化，避免系统“超大字体”叠加导致文字换行崩坏，字号缩放统一由 AppDimensions 精确接管
        val normalizedSysFont = baseDensity.fontScale.coerceIn(0.92f, 1.02f)
        Density(
            density = effectiveDensity,
            fontScale = normalizedSysFont
        )
    }
}

@Composable
fun rememberAppDimensions(scaleMode: UiScaleMode = UiScaleMode.AUTO): AppDimensions {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val screenWidth = configuration.screenWidthDp
    val screenHeight = configuration.screenHeightDp
    val shortSideDp = min(screenWidth, screenHeight).coerceAtLeast(280)
    val isCompact = shortSideDp < 370
    val resScale = (shortSideDp.toFloat() / 396f).coerceIn(0.82f, 1.08f)
    val adaptiveCoverLarge = (min(screenWidth, screenHeight) * 0.72f).dp.coerceIn(200.dp, 310.dp)

    return when (scaleMode) {
        UiScaleMode.CAR_EXTRA_LARGE -> buildScaledDimensions(
            scale = if (isCompact) 1.12f else 1.20f,
            resolutionScale = resScale,
            isCompactScreen = isCompact,
            coverLarge = 240.dp,
            coverMedium = 106.dp,
            coverSmall = 60.dp,
            touchLarge = 72.dp,
            touchMedium = 54.dp,
            touchSmall = 42.dp,
            iconLarge = 40.dp,
            iconMedium = 26.dp,
            iconSmall = 18.dp,
            padLarge = 24.dp,
            padMedium = 18.dp,
            padSmall = 10.dp
        )
        UiScaleMode.CAR_LARGE -> buildScaledDimensions(
            scale = if (isCompact) 1.05f else 1.10f,
            resolutionScale = resScale,
            isCompactScreen = isCompact,
            coverLarge = 220.dp,
            coverMedium = 94.dp,
            coverSmall = 56.dp,
            touchLarge = 66.dp,
            touchMedium = 50.dp,
            touchSmall = 38.dp,
            iconLarge = 36.dp,
            iconMedium = 25.dp,
            iconSmall = 17.dp,
            padLarge = 22.dp,
            padMedium = 16.dp,
            padSmall = 9.dp
        )
        UiScaleMode.STANDARD_PHONE -> buildScaledDimensions(
            scale = 0.98f,
            resolutionScale = resScale,
            isCompactScreen = isCompact,
            coverLarge = adaptiveCoverLarge,
            padLarge = if (isCompact) 18.dp else 22.dp,
            padMedium = if (isCompact) 12.dp else 15.dp,
            padSmall = 8.dp
        )
        UiScaleMode.AUTO -> {
            if (isLandscape || screenWidth >= 800) {
                buildScaledDimensions(
                    scale = 1.05f,
                    resolutionScale = resScale,
                    isCompactScreen = isCompact,
                    coverLarge = 200.dp,
                    coverMedium = 90.dp,
                    coverSmall = 54.dp,
                    touchLarge = 64.dp,
                    touchMedium = 48.dp,
                    touchSmall = 36.dp,
                    iconLarge = 34.dp,
                    iconMedium = 24.dp,
                    iconSmall = 16.dp,
                    padLarge = 20.dp,
                    padMedium = 14.dp,
                    padSmall = 8.dp
                )
            } else {
                buildScaledDimensions(
                    scale = 1.0f,
                    resolutionScale = resScale,
                    isCompactScreen = isCompact,
                    coverLarge = adaptiveCoverLarge,
                    padLarge = if (isCompact) 18.dp else 22.dp,
                    padMedium = if (isCompact) 12.dp else 15.dp,
                    padSmall = 8.dp
                )
            }
        }
    }
}

