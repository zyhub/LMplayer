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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.lm.player.core.designsystem.component.ExpandedMorphingBottomBar
import com.lm.player.core.designsystem.component.MorphingFloatingBottomBar
import com.lm.player.core.designsystem.component.ProgressiveBlurBottomScrim
import com.lm.player.core.model.BottomBarAnimMode
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
    windowSizeClass: WindowWidthSizeClass? = null,
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
    liquidGlassBlur: Float = 16f,
    bottomBarAnimMode: BottomBarAnimMode = BottomBarAnimMode.SLIDE,
    enableBottomBarAnimation: Boolean = true,
    useLinearAnimation: Boolean = true,
    onNavigate: (Screen) -> Unit,
    onDismissSearch: () -> Unit = {},
    onPlayPauseToggle: () -> Unit,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onOpenFullPlayer: () -> Unit,
    onSearchClick: () -> Unit,
    /** 液态玻璃模式下底栏在上层独立绘制层：全屏播放页 / 启动页盖住主界面时要隐藏它 */
    topLayerHidden: Boolean = false,
    /** 竖屏打开播放页：底栏向下滑走（收起播放页时滑回） */
    bottomBarSlidDown: Boolean = false,
    /** 播放页进出期间：上层只画底栏，页面玻璃顶栏 / 多选条先不画 */
    topChromeHidden: Boolean = false,
    content: @Composable (PaddingValues) -> Unit
) {
    // 悬浮导航栏展开状态 (未播放歌曲或处于弹出搜索模式时始终展开)
    var isNavExpanded by remember { mutableStateOf(false) }

    // 智能滚动监听连接器：在点击模式下，滑动歌曲列表或页面时自动平滑收起展开栏 (搜索模式下保持展开)
    val nestedScrollConnection = remember(currentPlayingSong, enableBottomBarAnimation, isSearchActive, bottomBarAnimMode) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (bottomBarAnimMode == BottomBarAnimMode.CLICK && !isSearchActive && currentPlayingSong != null && enableBottomBarAnimation && abs(available.y) > 4f && isNavExpanded) {
                    isNavExpanded = false
                }
                return Offset.Zero
            }
        }
    }

    // 动态底部安全间距 (点击展开双排状态下占用 145dp，收缩与滑动单条模式占用 100dp)
    val kyantBar = com.lm.player.core.designsystem.glass.rememberLiquidGlassActive()
    val isDoubleLayer = bottomBarAnimMode == BottomBarAnimMode.CLICK && (currentPlayingSong == null || !enableBottomBarAnimation || isNavExpanded)
    val dynamicBottomPadding by animateDpAsState(
        // 液态玻璃底栏：单行 56dp（播放胶囊 + 两侧圆钮，导航展开为示例标签栏）
        targetValue = if (kyantBar) 104.dp else if (isDoubleLayer) 145.dp else 100.dp,
        label = "scaffold_bottom_padding"
    )

    val glassActive = com.lm.player.core.designsystem.glass.rememberLiquidGlassActive()

    // 底栏随播放页进出上下滑动（只改图层位移，不重新布局）；滑走期间冻结播放条位置记录，神奇效果的起止点不跟着跑
    val barSlide = remember { androidx.compose.animation.core.Animatable(if (bottomBarSlidDown) 1f else 0f) }
    LaunchedEffect(bottomBarSlidDown) {
        val target = if (bottomBarSlidDown) 1f else 0f
        if (barSlide.value == target) return@LaunchedEffect
        com.lm.player.feature.player.PlayerLaunchOrigin.frozen = true
        // 收起播放页时：等播放页底部玻璃工具条先滑走，底栏再滑回来，两条不会叠在一起
        if (target == 0f) kotlinx.coroutines.delay(com.lm.player.feature.player.BAR_HANDOFF_DELAY_MS)
        barSlide.animateTo(target, androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        com.lm.player.feature.player.PlayerLaunchOrigin.frozen = target != 0f
    }

    // 统一形态渐变悬浮底栏 (横屏大屏智能居中最大宽度 580dp，竖屏自适应满宽，搜索输入时自动避让软键盘)
    val bottomBarHost: @Composable (Modifier) -> Unit = { hostModifier ->
        Box(
            modifier = hostModifier
                .graphicsLayer {
                    val v = barSlide.value
                    translationY = if (v > 0f) v * (size.height + 24.dp.toPx()) else 0f
                }
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
                        liquidGlassBlur = liquidGlassBlur,
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
                            com.lm.player.feature.player.PlayerLaunchOrigin.arm()
                            onOpenFullPlayer()
                        },
                        onSearchClick = onSearchClick,
                        modifier = Modifier.widthIn(max = 580.dp)
                    )
                }
            } else {
                // 有歌曲播放且开启过渡模式：支持滑动隐藏与点击展开，搜索激活时液态置换
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
                    progressMs = progressMs,
                    totalDurationMs = totalDurationMs,
                    blurAlpha = blurAlpha,
                    liquidGlassBlur = liquidGlassBlur,
                    bottomBarAnimMode = bottomBarAnimMode,
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
                        com.lm.player.feature.player.PlayerLaunchOrigin.arm()
                        onOpenFullPlayer()
                    },
                    onSearchClick = onSearchClick,
                    modifier = Modifier.widthIn(max = 580.dp)
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 液态玻璃每帧要把主界面再画一遍来采样；包成一层后采样只引用这一层已录好的显示列表，
            // 不必把整页的绘制指令重新执行一遍
            .then(if (glassActive) Modifier.graphicsLayer() else Modifier)
            .background(MaterialTheme.colorScheme.background)
            .nestedScroll(nestedScrollConnection)
    ) {
        // 核心页面内容 (动态底部间距，最大化可视内容)
        content(PaddingValues(bottom = dynamicBottomPadding))

        // 底部渐进虚化衬底 (向上柔和淡化，使滚动内容滑入底栏前平滑消融，解决生硬截断)
        // 液态玻璃开启时不画：衬底会把底栏下方洗成纯色，玻璃就没有内容可折射了
        if (!glassActive) {
            ProgressiveBlurBottomScrim(
                modifier = Modifier.align(Alignment.BottomCenter),
                height = 120.dp
            )
        }

        // 未开启液态玻璃：底栏照旧画在主界面里
        if (!glassActive) {
            // 列表页底部悬浮条（多选操作条等）：未开启玻璃时画成实心卡片
            com.lm.player.core.designsystem.glass.GlassFloatingBarHost(
                modifier = Modifier.align(Alignment.BottomCenter),
                defaultBottomPadding = dynamicBottomPadding + 8.dp,
                glass = false
            )
            bottomBarHost(Modifier.align(Alignment.BottomCenter))
        }
    }

    // 液态玻璃：底栏移到上层独立绘制层，玻璃折射下面的主界面（含滚动中的列表）。
    // 玻璃不能采样自己所在的 ComposeView，所以必须分层（见 core/designsystem/glass/LiquidGlass.kt）
    if (glassActive) {
        // 底栏滑块对应的玻璃外观：顶部固定按钮 / 搜索面板 / 下拉菜单都跟着同一套
        val isDarkTheme = MaterialTheme.colorScheme.background.red < 0.5f
        val barStyle = com.lm.player.core.designsystem.glass.barGlassStyle(isDarkTheme, blurAlpha, liquidGlassBlur)
        SideEffect {
            com.lm.player.core.designsystem.glass.GlassStyleState.barStyle = barStyle
            com.lm.player.core.designsystem.glass.GlassStyleState.opacity = blurAlpha
            com.lm.player.core.designsystem.glass.GlassStyleState.blurDp = liquidGlassBlur
        }

        com.lm.player.core.designsystem.glass.GlassTopLayer(hidden = topLayerHidden, zOrder = 2f) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                // 资料库二级页（歌手 / 专辑 / 歌单 / 文件夹…）的玻璃顶栏，列表从它下面穿过
                if (!topChromeHidden) {
                    com.lm.player.core.designsystem.glass.GlassTopBarHost(modifier = Modifier.align(Alignment.TopCenter))
                    // 列表页底部的多选操作条等（下载管理…）：玻璃折射下面的列表
                    com.lm.player.core.designsystem.glass.GlassFloatingBarHost(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    defaultBottomPadding = dynamicBottomPadding + 8.dp
                )
                }
                bottomBarHost(Modifier)
            }
        }
    }
}
