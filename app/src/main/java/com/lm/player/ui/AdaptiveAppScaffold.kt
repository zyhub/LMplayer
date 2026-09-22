package com.lm.player.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.lm.player.core.designsystem.component.ExpandedMorphingBottomBar
import com.lm.player.core.designsystem.component.MorphingFloatingBottomBar
import com.lm.player.core.model.Screen
import com.lm.player.core.model.UnifiedSong
import kotlin.math.abs

/**
 * 统一响应式脚手架
 * - 增加可视内容区域：缩放后动态释放底部空间 (100dp ⇄ 145dp)
 * - 播放栏集成「上一首」「播放/暂停」「下一首」完整触控
 * - 支持毛玻璃透明度调节与线性滑动缩放动效
 * - 未播放歌曲时始终完整常显导航栏
 */
@Composable
fun AdaptiveAppScaffold(
    windowSizeClass: WindowWidthSizeClass,
    currentScreen: Screen,
    currentPlayingSong: UnifiedSong?,
    isPlaying: Boolean,
    progressMs: Long = 0L,
    totalDurationMs: Long = 0L,
    blurAlpha: Float = 0.85f,
    enableBottomBarAnimation: Boolean = true,
    useLinearAnimation: Boolean = true,
    onNavigate: (Screen) -> Unit,
    onPlayPauseToggle: () -> Unit,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onOpenFullPlayer: () -> Unit,
    onSearchClick: () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    // 悬浮导航栏展开状态 (未播放歌曲时始终常显)
    var isNavExpanded by remember { mutableStateOf(false) }

    // 智能滚动监听连接器：滑动歌曲列表或页面时，自动平滑收起展开栏
    val nestedScrollConnection = remember(currentPlayingSong, enableBottomBarAnimation) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (currentPlayingSong != null && enableBottomBarAnimation && abs(available.y) > 4f && isNavExpanded) {
                    isNavExpanded = false
                }
                return Offset.Zero
            }
        }
    }

    // 动态底部安全间距 (收缩状态下仅占用 100dp，最大化释放页面展示内容)
    val dynamicBottomPadding by animateDpAsState(
        targetValue = if (currentPlayingSong == null || !enableBottomBarAnimation || isNavExpanded) 145.dp else 100.dp,
        label = "scaffold_bottom_padding"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .nestedScroll(nestedScrollConnection)
    ) {
        // 核心页面内容 (动态底部间距，最大化可视内容)
        content(PaddingValues(bottom = dynamicBottomPadding))

        // 统一形态渐变悬浮底栏 (横屏大屏智能居中最大宽度 580dp，竖屏自适应满宽)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            contentAlignment = Alignment.BottomCenter
        ) {
            if (currentPlayingSong == null || !enableBottomBarAnimation) {
                // 没有播放歌曲或关闭动画时：始终完整常显导航底栏
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    ExpandedMorphingBottomBar(
                        currentScreen = currentScreen,
                        currentSong = currentPlayingSong,
                        isPlaying = isPlaying,
                        blurAlpha = blurAlpha,
                        onNavigate = onNavigate,
                        onPrevious = onPrevious,
                        onTogglePlay = onPlayPauseToggle,
                        onNext = onNext,
                        onOpenFullPlayer = onOpenFullPlayer,
                        onSearchClick = onSearchClick,
                        modifier = Modifier.widthIn(max = 580.dp)
                    )
                }
            } else {
                // 有歌曲播放且开启过渡模式：支持滑动隐藏与点击展开
                MorphingFloatingBottomBar(
                    currentScreen = currentScreen,
                    currentSong = currentPlayingSong,
                    isPlaying = isPlaying,
                    isNavExpanded = isNavExpanded,
                    blurAlpha = blurAlpha,
                    useLinearAnimation = useLinearAnimation,
                    onToggleNavExpanded = { isNavExpanded = it },
                    onNavigate = { screen ->
                        onNavigate(screen)
                        isNavExpanded = false
                    },
                    onPrevious = onPrevious,
                    onTogglePlay = onPlayPauseToggle,
                    onNext = onNext,
                    onOpenFullPlayer = onOpenFullPlayer,
                    onSearchClick = onSearchClick,
                    modifier = Modifier.widthIn(max = 580.dp)
                )
            }
        }
    }
}
