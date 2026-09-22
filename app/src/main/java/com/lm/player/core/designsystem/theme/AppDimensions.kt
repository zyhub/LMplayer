package com.lm.player.core.designsystem.theme

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class UiScaleMode {
    AUTO,
    STANDARD_PHONE,
    CAR_LARGE,
    CAR_EXTRA_LARGE
}

data class AppDimensions(
    val fontScale: Float = 1.0f,
    val titleLargeSize: TextUnit = 32.sp,
    val titleMediumSize: TextUnit = 22.sp,
    val bodyLargeSize: TextUnit = 16.sp,
    val bodyMediumSize: TextUnit = 14.sp,
    val captionSize: TextUnit = 12.sp,
    
    val coverLargeSize: Dp = 320.dp,
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

@Composable
fun rememberAppDimensions(scaleMode: UiScaleMode = UiScaleMode.AUTO): AppDimensions {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val screenWidth = configuration.screenWidthDp

    return when (scaleMode) {
        UiScaleMode.CAR_EXTRA_LARGE -> AppDimensions(
            fontScale = 1.25f,
            titleLargeSize = 36.sp,
            titleMediumSize = 26.sp,
            bodyLargeSize = 20.sp,
            bodyMediumSize = 17.sp,
            captionSize = 14.sp,
            coverLargeSize = 240.dp,
            coverMediumSize = 110.dp,
            coverSmallSize = 64.dp,
            touchTargetLarge = 76.dp,
            touchTargetMedium = 58.dp,
            touchTargetSmall = 44.dp,
            iconLargeSize = 42.dp,
            iconMediumSize = 28.dp,
            iconSmallSize = 20.dp,
            paddingLarge = 28.dp,
            paddingMedium = 20.dp,
            paddingSmall = 12.dp
        )
        UiScaleMode.CAR_LARGE -> AppDimensions(
            fontScale = 1.12f,
            titleLargeSize = 32.sp,
            titleMediumSize = 24.sp,
            bodyLargeSize = 18.sp,
            bodyMediumSize = 15.sp,
            captionSize = 13.sp,
            coverLargeSize = 220.dp,
            coverMediumSize = 96.dp,
            coverSmallSize = 58.dp,
            touchTargetLarge = 68.dp,
            touchTargetMedium = 52.dp,
            touchTargetSmall = 40.dp,
            iconLargeSize = 36.dp,
            iconMediumSize = 26.dp,
            iconSmallSize = 18.dp,
            paddingLarge = 24.dp,
            paddingMedium = 16.dp,
            paddingSmall = 10.dp
        )
        UiScaleMode.STANDARD_PHONE -> AppDimensions(
            fontScale = 1.0f
        )
        UiScaleMode.AUTO -> {
            if (isLandscape || screenWidth >= 800) {
                // 车机大屏 / 横屏自适应放大
                AppDimensions(
                    fontScale = 1.1f,
                    titleLargeSize = 30.sp,
                    titleMediumSize = 22.sp,
                    bodyLargeSize = 17.sp,
                    bodyMediumSize = 14.sp,
                    captionSize = 12.sp,
                    coverLargeSize = 200.dp,
                    coverMediumSize = 90.dp,
                    coverSmallSize = 54.dp,
                    touchTargetLarge = 64.dp,
                    touchTargetMedium = 48.dp,
                    touchTargetSmall = 36.dp,
                    iconLargeSize = 34.dp,
                    iconMediumSize = 24.dp,
                    iconSmallSize = 16.dp,
                    paddingLarge = 20.dp,
                    paddingMedium = 14.dp,
                    paddingSmall = 8.dp
                )
            } else {
                AppDimensions(fontScale = 1.0f)
            }
        }
    }
}
