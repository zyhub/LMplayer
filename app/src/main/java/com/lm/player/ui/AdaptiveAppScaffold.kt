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
import com.lm.player.core.model.OnlineMusicSource
import com.lm.player.core.model.Screen
import com.lm.player.core.model.SearchContentType
import com.lm.player.core.model.UnifiedSong
import kotlin.math.abs

/**
 * 统一响应式脚手架
 * - 增加可视内容区域：缩放后动态释放底部空间 (100dp ⇄ 145dp)
 * - 播放栏集成「上一首」「播放/暂停」「下一首」完整触控
 * - 支持弹出式平滑左滑搜索框：搜索框在音乐播放栏下方向左平滑展开覆盖于首页/资料库/设置上方，搜索按键变为 X
 * - 支持毛玻璃透明度调节与线性滑动缩放动效
 */
@Composable
fun AdaptiveAppScaffold(
    windowSizeClass: WindowWidthSizeClass,
    currentScreen: Screen,
    currentPlayingSong: UnifiedSong?,
    isPlaying: Boolean,
    isSearchActive: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    selectedOnlineSource: OnlineMusicSource = OnlineMusicSource.KUWO,
    onOnlineSourceChange: (OnlineMusicSource) -> Unit = {},
    selectedSearchType: SearchContentType = SearchContentType.SONG,
    onSearchTypeChange: (SearchContentType) -> Unit = {},
    showOnlineSourceSelector: Boolean = true,
    progressMs: Long = 0L,
    totalDurationMs: Long = 0L,
    blurAlpha: Float = 0.85f,
    enableBottomBarAnimation: Boolean = true,
    useLinearAnimation: Boolean = true,
    onNavigate: (Screen) -> Unit,
    onDismissSearch: () -> Unit = {},
    onPlayPauseToggle: () -> Unit,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onOpenFullPlayer: () -> Unit,
    onSearchClick: () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    // 悬浮导航栏展开状态 (未播放歌曲或处于弹出搜索模式时始终展开)
    var isNavExpanded by remember { mutableStateOf(false) }

    // 智能滚动监听连接器：滑动歌曲列表或页面时，自动平滑收起展开栏 (搜索模式下保持展开)
    val nestedScrollConnection = remember(currentPlayingSong, enableBottomBarAnimation, isSearchActive) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!isSearchActive && currentPlayingSong != null && enableBottomBarAnimation && abs(available.y) > 4f && isNavExpanded) {
                    isNavExpanded = false
                }
                return Offset.Zero
            }
        }
    }

    // 动态底部安全间距 (收缩状态下仅占用 100dp，最大化释放页面展示内容)
    val dynamicBottomPadding by animateDpAsState(
        targetValue = if (currentPlayingSong == null || !enableBottomBarAnimation || isNavExpanded || isSearchActive) 145.dp else 100.dp,
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

        // 统一形态渐变悬浮底栏 (横屏大屏智能居中最大宽度 580dp，竖屏自适应满宽，搜索输入时自动避让软键盘)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding(),
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
                        isSearchActive = isSearchActive,
                        searchQuery = searchQuery,
                        onSearchQueryChange = onSearchQueryChange,
                        selectedOnlineSource = selectedOnlineSource,
                        onOnlineSourceChange = onOnlineSourceChange,
                        selectedSearchType = selectedSearchType,
                        onSearchTypeChange = onSearchTypeChange,
                        showOnlineSourceSelector = showOnlineSourceSelector,
                        blurAlpha = blurAlpha,
                        onNavigate = { screen ->
                            onDismissSearch()
                            onNavigate(screen)
                        },
                        onDismissSearch = onDismissSearch,
                        onPrevious = onPrevious,
                        onTogglePlay = onPlayPauseToggle,
                        onNext = onNext,
                        onOpenFullPlayer = {
                            onDismissSearch()
                            onOpenFullPlayer()
                        },
                        onSearchClick = onSearchClick,
                        modifier = Modifier.widthIn(max = 580.dp)
                    )
                }
            } else {
                // 有歌曲播放且开启过渡模式：支持滑动隐藏与点击展开，搜索激活时自动展开双层并滑出搜索框
                MorphingFloatingBottomBar(
                    currentScreen = currentScreen,
                    currentSong = currentPlayingSong,
                    isPlaying = isPlaying,
                    isSearchActive = isSearchActive,
                    searchQuery = searchQuery,
                    onSearchQueryChange = onSearchQueryChange,
                    selectedOnlineSource = selectedOnlineSource,
                    onOnlineSourceChange = onOnlineSourceChange,
                    selectedSearchType = selectedSearchType,
                    onSearchTypeChange = onSearchTypeChange,
                    showOnlineSourceSelector = showOnlineSourceSelector,
                    isNavExpanded = isNavExpanded,
                    blurAlpha = blurAlpha,
                    useLinearAnimation = useLinearAnimation,
                    onToggleNavExpanded = { isNavExpanded = it },
                    onDismissSearch = onDismissSearch,
                    onNavigate = { screen ->
                        onDismissSearch()
                        onNavigate(screen)
                        isNavExpanded = false
                    },
                    onPrevious = onPrevious,
                    onTogglePlay = onPlayPauseToggle,
                    onNext = onNext,
                    onOpenFullPlayer = {
                        onDismissSearch()
                        onOpenFullPlayer()
                    },
                    onSearchClick = onSearchClick,
                    modifier = Modifier.widthIn(max = 580.dp)
                )
            }
        }
    }
}
