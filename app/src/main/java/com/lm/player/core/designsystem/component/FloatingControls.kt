package com.lm.player.core.designsystem.component

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.model.BottomBarAnimMode
import com.lm.player.core.model.OnlineMusicSource
import com.lm.player.core.model.Screen
import com.lm.player.core.model.SearchContentType
import com.lm.player.core.model.ServerConfig
import com.lm.player.core.model.UnifiedSong

private val BottomBarHeight = 56.dp
private val UnifiedPillShape = RoundedCornerShape(28.dp)

/**
 * 紧凑单排胶囊底栏 (纯净毛玻璃悬浮，无多边形/抠图白底色差)
 */
/**
 * 圆形微型播放栏 (配备居中歌曲封面与外圈高精度环形进度条)
 */
@Composable
fun CircularMorphingMiniPlayer(
    currentSong: UnifiedSong?,
    isPlaying: Boolean,
    progressMs: Long = 0L,
    totalDurationMs: Long = 0L,
    size: androidx.compose.ui.unit.Dp = 54.dp,
    blurAlpha: Float = 0.85f,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val surfaceColor = if (isDark) {
        Color(0xFF222228).copy(alpha = blurAlpha)
    } else {
        Color(0xFFFFFFFF).copy(alpha = blurAlpha)
    }
    val borderColor = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)
    val pillShape = CircleShape

    val progress = remember(progressMs, totalDurationMs) {
        if (totalDurationMs > 0L) {
            (progressMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(pillShape)
            .background(surfaceColor)
            .border(BorderStroke(1.dp, borderColor), pillShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // 外圈环形播放进度条
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(size - 4.dp),
            color = AppleRed,
            trackColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.06f),
            strokeWidth = 2.5.dp,
            strokeCap = StrokeCap.Round
        )

        // 内部居中圆形歌曲封面
        if (currentSong != null) {
            AlbumArtworkImage(
                model = currentSong.coverUrl,
                seedId = currentSong.id,
                modifier = Modifier
                    .size(size - 14.dp)
                    .clip(CircleShape),
                cornerRadius = (size - 14.dp) / 2
            )
        } else {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = AppleRed,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 紧凑单排胶囊底栏 (对标苹果 iOS 液态流体物理形变架构：
 * 1. 左侧首页圆圈长按滑动：全跨度导航轨道展开至搜索按钮 (281dp)，焦点红胶囊 1:1 指尖实时绝对跟随；
 * 2. 搜索点击/向左滑动：长条播放栏连续缩小为 52dp 正圆并左滑至 X=0，搜索框自右向左连续液态拉长并就位)
 */
@Composable
fun CompactMorphingBottomBar(
    currentScreen: Screen,
    currentSong: UnifiedSong?,
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
    onExpandNav: () -> Unit = {},
    onNavigate: (Screen) -> Unit = {},
    onDismissSearch: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onTogglePlay: () -> Unit = {},
    onNext: () -> Unit = {},
    onOpenFullPlayer: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val dimensions = LocalAppDimensions.current
    val haptic = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var isSourceDropdownExpanded by remember { mutableStateOf(false) }

    // 苹果 iOS 26 款连续几何形变进度 (0f = 常态长条播放, 1f = 完全展开搜索 & 播放栏变圆移至最左)
    val searchProgress by animateFloatAsState(
        targetValue = if (isSearchActive) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.78f, // 物理流体弹性阻尼
            stiffness = 380f
        ),
        label = "search_morph_progress"
    )

    // 全跨度导航手势拖拽状态管理
    var isNavDragging by remember { mutableStateOf(false) }
    var isNavManuallyExpanded by remember { mutableStateOf(false) }
    var currentFingerXPx by remember { mutableFloatStateOf(0f) }
    var hoveredNavIndex by remember {
        mutableIntStateOf(
            when (currentScreen) {
                Screen.HOME -> 0
                Screen.LIBRARY, Screen.DOWNLOADS -> 1
                Screen.SETTINGS -> 2
            }
        )
    }

    LaunchedEffect(currentScreen) {
        hoveredNavIndex = when (currentScreen) {
            Screen.HOME -> 0
            Screen.LIBRARY, Screen.DOWNLOADS -> 1
            Screen.SETTINGS -> 2
        }
        isNavManuallyExpanded = false
    }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            try {
                focusRequester.requestFocus()
                keyboardController?.show()
            } catch (_: Exception) {}
        } else {
            isSourceDropdownExpanded = false
            keyboardController?.hide()
        }
    }

    val isDark = MaterialTheme.colorScheme.background.red < 0.5f

    val baseAlpha = blurAlpha.coerceIn(0.10f, 1.0f)
    // 苹果液态玻璃 (Apple Liquid Glass) 材质规范：多段光学漫反射与高光微折射渐变，透明度联动
    val liquidGlassBrush = if (isDark) {
        Brush.verticalGradient(
            listOf(
                Color(0xFF2E2E3A).copy(alpha = (0.86f * baseAlpha).coerceIn(0f, 1f)),
                Color(0xFF20202A).copy(alpha = (0.75f * baseAlpha).coerceIn(0f, 1f)),
                Color(0xFF161622).copy(alpha = (0.85f * baseAlpha).coerceIn(0f, 1f))
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color(0xFFFFFFFF).copy(alpha = (0.94f * baseAlpha).coerceIn(0f, 1f)),
                Color(0xFFF5F5FB).copy(alpha = (0.87f * baseAlpha).coerceIn(0f, 1f)),
                Color(0xFFEDEDF6).copy(alpha = (0.78f * baseAlpha).coerceIn(0f, 1f))
            )
        )
    }
    val liquidGlassBorderBrush = if (isDark) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = (0.38f * baseAlpha).coerceIn(0.12f, 0.60f)),
                Color.White.copy(alpha = (0.12f * baseAlpha).coerceIn(0.04f, 0.30f)),
                Color(0x20000000)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = (0.95f * baseAlpha).coerceIn(0.40f, 1.0f)),
                Color.White.copy(alpha = (0.65f * baseAlpha).coerceIn(0.20f, 0.85f)),
                Color(0x24000000)
            )
        )
    }

    val searchOverlaySurfaceColor = if (isDark) {
        Color(0xFF1F1F27)
    } else {
        Color(0xFFFCFCFE)
    }

    val currentScreenIcon = when (currentScreen) {
        Screen.HOME -> Icons.Filled.Home
        Screen.LIBRARY, Screen.DOWNLOADS -> Icons.Filled.LibraryMusic
        Screen.SETTINGS -> Icons.Filled.Settings
    }

    val playbackProgress = remember(progressMs, totalDurationMs) {
        if (totalDurationMs > 0L) {
            (progressMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f
    }

    // 导航展开动画进度 (物理弹性流体阻尼)
    val navExpandProgress by animateFloatAsState(
        targetValue = if (isNavDragging || isNavManuallyExpanded) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.78f,
            stiffness = 450f
        ),
        label = "nav_expand_progress"
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        val density = LocalDensity.current
        val totalWidth = maxWidth
        val isWideBar = totalWidth >= 430.dp
        val barHeight = 52.dp
        val pillShape = RoundedCornerShape(barHeight / 2)
        val outerGap = if (totalWidth < 380.dp) 8.dp else 10.dp
        val coverBoxSize = if (totalWidth < 360.dp) 34.dp else 38.dp
        val ctrlBtnSize = if (totalWidth < 380.dp) 28.dp else 30.dp

        // =========================================================================
        // 几何实体坐标与尺寸连续插值计算 (基于单一画布绝对定位)
        // =========================================================================
        val restingPlayerX = barHeight + outerGap
        val restingPlayerWidth = (totalWidth - (barHeight * 2) - (outerGap * 2)).coerceAtLeast(barHeight)
        val restingSearchX = totalWidth - barHeight

        // 双向流体对称核心插值：
        // 1. 搜索激活时（向左）：播放栏从 restingPlayerX 滑至 0dp 并缩为 52dp 正圆；
        // 2. 导航滑动时（向右）：播放栏从 restingPlayerX 滑至 restingSearchX 并缩为 52dp 正圆！
        val currentPlayerX = when {
            searchProgress > 0f -> restingPlayerX * (1f - searchProgress)
            navExpandProgress > 0f -> restingPlayerX + (restingSearchX - restingPlayerX) * navExpandProgress
            else -> restingPlayerX
        }
        val morphProgress = maxOf(searchProgress, navExpandProgress)
        val currentPlayerWidth = restingPlayerWidth - (restingPlayerWidth - barHeight) * morphProgress

        // 全跨度导航轨道展开宽度 (延伸至紧贴右侧搜索圆圈边缘)
        val maxNavTrackWidth = (totalWidth - barHeight - outerGap).coerceAtLeast(barHeight)
        val animatedNavTrackWidth = barHeight + (maxNavTrackWidth - barHeight) * navExpandProgress
        val isNavTrackExpanded = isNavDragging || isNavManuallyExpanded || navExpandProgress > 0.08f

        // 导航底轨背景：浅色模式下展开为【透明淡灰色】rgba(120, 120, 128, 0.16)
        val navTrackBgBrush = if (isDark) {
            if (navExpandProgress > 0.08f) {
                Brush.verticalGradient(listOf(Color(0x4D303038), Color(0x38202028)))
            } else {
                liquidGlassBrush
            }
        } else {
            if (navExpandProgress > 0.08f) {
                Brush.verticalGradient(listOf(Color(0x33787880), Color(0x24787880)))
            } else {
                liquidGlassBrush
            }
        }
        val navTrackBorderBrush = if (isDark) {
            if (navExpandProgress > 0.08f) {
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.08f)))
            } else {
                liquidGlassBorderBrush
            }
        } else {
            if (navExpandProgress > 0.08f) {
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.75f), Color(0x18000000)))
            } else {
                liquidGlassBorderBrush
            }
        }

        // 聚焦白胶囊微光材质 (Apple 纯白高光微凸液态胶囊)
        val navFocusPillBrush = if (isDark) {
            Brush.verticalGradient(listOf(Color(0x59FFFFFF), Color(0x33FFFFFF)))
        } else {
            Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF7F7FA)))
        }
        val navFocusPillBorderBrush = if (isDark) {
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.50f), Color.White.copy(alpha = 0.20f)))
        } else {
            Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 0.85f)))
        }

        // 导航等分槽位计算 (三项严格等分撑满整条横轨)
        val trackHorizontalPadding = 4.dp
        val usableTrackWidth = (maxNavTrackWidth - trackHorizontalPadding * 2).coerceAtLeast(barHeight)
        val slotWidthDp = usableTrackWidth / 3
        val pillWidthDp = (slotWidthDp - 6.dp).coerceAtLeast(40.dp)

        val slotWidthPx = with(density) { slotWidthDp.toPx() }
        val pillWidthPx = with(density) { pillWidthDp.toPx() }
        val padPx = with(density) { trackHorizontalPadding.toPx() }

        // 三个槽位的精确几何中心
        val center0Px = padPx + slotWidthPx * 0.5f
        val center1Px = padPx + slotWidthPx * 1.5f
        val center2Px = padPx + slotWidthPx * 2.5f

        val currentScreenIndex = when (currentScreen) {
            Screen.HOME -> 0
            Screen.LIBRARY, Screen.DOWNLOADS -> 1
            Screen.SETTINGS -> 2
        }

        val livePillCenterPx = if (isNavDragging) {
            currentFingerXPx.coerceIn(center0Px, center2Px)
        } else {
            when (if (isNavManuallyExpanded) hoveredNavIndex else currentScreenIndex) {
                0 -> center0Px
                1 -> center1Px
                else -> center2Px
            }
        }
        val targetPillXPx = livePillCenterPx - pillWidthPx / 2f
        val pillXDp = with(density) { targetPillXPx.toDp() }

        // =========================================================================
        // 实体 1：左侧首页 / 全跨度滑动导航轨道 (Left = 0.dp)
        // - 常态：52dp 圆圈，显示当前页面图标；
        // - 拖拽：全跨度拉伸至紧贴搜索框，包含 1:1 指尖实时绝对物理跟随的凸显聚焦胶囊；
        // - 搜索激活时：透明度随圆形播放器移入而平滑淡出。
        // =========================================================================
        val homeCircleAlpha = (1f - searchProgress * 1.5f).coerceIn(0f, 1f)
        if (homeCircleAlpha > 0f) {
            Box(
                modifier = Modifier
                    .offset(x = 0.dp)
                    .width(animatedNavTrackWidth)
                    .height(barHeight)
                    .zIndex(if (isNavDragging || isNavManuallyExpanded) 30f else 10f)
                    .alpha(homeCircleAlpha)
                    .shadow(
                        elevation = if (isNavTrackExpanded) 6.dp else 4.dp,
                        shape = pillShape,
                        ambientColor = if (isDark) Color(0x55000000) else Color(0x18000000),
                        spotColor = if (isDark) Color(0x38000000) else Color(0x10000000)
                    )
                    .clip(pillShape)
                    .background(navTrackBgBrush)
                    .border(BorderStroke(1.dp, navTrackBorderBrush), pillShape)
                    .then(
                        if (bottomBarAnimMode == BottomBarAnimMode.SLIDE && !isSearchActive) {
                            Modifier.pointerInput(currentScreen, isSearchActive) {
                                detectHorizontalDragGestures(
                                    onDragStart = { offset ->
                                        isNavDragging = true
                                        currentFingerXPx = offset.x
                                        val liveCenter = offset.x.coerceIn(center0Px, center2Px)
                                        val newIndex = when {
                                            liveCenter < padPx + slotWidthPx -> 0
                                            liveCenter > padPx + slotWidthPx * 2f -> 2
                                            else -> 1
                                        }
                                        hoveredNavIndex = newIndex
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    },
                                    onDragEnd = {
                                        val targetScreen = when (hoveredNavIndex) {
                                            0 -> Screen.HOME
                                            1 -> Screen.LIBRARY
                                            else -> Screen.SETTINGS
                                        }
                                        if (targetScreen != currentScreen) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onNavigate(targetScreen)
                                        }
                                        isNavDragging = false
                                        isNavManuallyExpanded = false
                                    },
                                    onDragCancel = {
                                        isNavDragging = false
                                        isNavManuallyExpanded = false
                                    },
                                    onHorizontalDrag = { change, _ ->
                                        change.consume()
                                        currentFingerXPx = change.position.x
                                        val liveCenter = change.position.x.coerceIn(center0Px, center2Px)
                                        val newIndex = when {
                                            liveCenter < padPx + slotWidthPx -> 0
                                            liveCenter > padPx + slotWidthPx * 2f -> 2
                                            else -> 1
                                        }
                                        if (newIndex != hoveredNavIndex) {
                                            hoveredNavIndex = newIndex
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                )
                            }
                        } else Modifier
                    )
                    .clickable {
                        if (bottomBarAnimMode == BottomBarAnimMode.CLICK) {
                            if (isSearchActive) {
                                onDismissSearch()
                            }
                            onExpandNav()
                        } else {
                            if (!isNavDragging && !isSearchActive) {
                                isNavManuallyExpanded = !isNavManuallyExpanded
                            }
                        }
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                if (isNavTrackExpanded) {
                    // 1:1 指尖实时绝对跟随的聚焦白胶囊 (方案 A：纯白立体微光液态胶囊)
                    Box(
                        modifier = Modifier
                            .offset(x = pillXDp)
                            .width(pillWidthDp)
                            .height(barHeight - 10.dp)
                            .shadow(
                                elevation = if (isDark) 4.dp else 6.dp,
                                shape = RoundedCornerShape((barHeight - 10.dp) / 2),
                                ambientColor = if (isDark) Color(0x66000000) else Color(0x30000000),
                                spotColor = if (isDark) Color(0x44000000) else Color(0x1C000000)
                            )
                            .clip(RoundedCornerShape((barHeight - 10.dp) / 2))
                            .background(navFocusPillBrush)
                            .border(
                                BorderStroke(0.8.dp, navFocusPillBorderBrush),
                                RoundedCornerShape((barHeight - 10.dp) / 2)
                            )
                    )

                    // 3 个等分导航项：统一使用纯红 Apple Red 图标 (#FF2D55)
                    Row(
                        modifier = Modifier
                            .padding(horizontal = trackHorizontalPadding)
                            .width(usableTrackWidth)
                            .fillMaxHeight(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val navItems = listOf(
                            Triple(0, Icons.Filled.Home, "首页"),
                            Triple(1, Icons.Filled.LibraryMusic, "资料库"),
                            Triple(2, Icons.Filled.Settings, "设置")
                        )
                        navItems.forEach { (idx, icon, title) ->
                            val isHovered = hoveredNavIndex == idx
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .scale(if (isHovered) 1.10f else 0.95f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        val target = when (idx) {
                                            0 -> Screen.HOME
                                            1 -> Screen.LIBRARY
                                            else -> Screen.SETTINGS
                                        }
                                        if (target != currentScreen) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onNavigate(target)
                                        }
                                        isNavManuallyExpanded = false
                                        isNavDragging = false
                                    }
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = title,
                                    tint = AppleRed.copy(alpha = if (isHovered) 1f else 0.70f),
                                    modifier = Modifier.size(19.dp)
                                )
                                Text(
                                    text = title,
                                    fontSize = 10.sp,
                                    fontWeight = if (isHovered) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isHovered) {
                                        if (isDark) Color.White else Color(0xFF1C1C1E)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // 常态单图标居中
                    Box(
                        modifier = Modifier.size(barHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = currentScreenIcon,
                            contentDescription = "按住滑动展开切页",
                            tint = AppleRed,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // =========================================================================
        // 实体 2：歌曲播放栏连续形变实体 (双向流体对称实体)
        // - 常态：长条形 (Left = 60dp, Width = restingPlayerWidth)；
        // - 搜索激活时（向左流动）：平滑连续缩短为正圆 (52dp) 并滑向最左侧首页位 (Left = 0dp)；
        // - 导航滑动时（向右流动）：平滑连续缩短为正圆 (52dp) 并滑向最右侧搜索位 (Left = restingSearchX)！
        // =========================================================================
        Box(
            modifier = Modifier
                .offset(x = currentPlayerX)
                .width(currentPlayerWidth)
                .height(barHeight)
                .zIndex(
                    when {
                        navExpandProgress > 0.01f -> 35f // 导航滑动展开时位于最高层 (35f > 30f & 20f)，保证向右缩圆平滑可见！
                        searchProgress > 0.5f -> 22f
                        else -> 15f
                    }
                )
                .shadow(
                    elevation = 8.dp,
                    shape = pillShape,
                    ambientColor = if (isDark) Color(0x66000000) else Color(0x22000000),
                    spotColor = if (isDark) Color(0x44000000) else Color(0x16000000)
                )
                .clip(pillShape)
                .background(liquidGlassBrush)
                .border(BorderStroke(1.2.dp, liquidGlassBorderBrush), pillShape)
                .clickable {
                    onOpenFullPlayer()
                }
        ) {
            // A. 常态长条播放内容：当变圆形变发生时 (morphProgress > 0) 平滑淡出
            if (morphProgress < 0.85f) {
                val rectContentAlpha = (1f - morphProgress * 1.8f).coerceIn(0f, 1f)
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(rectContentAlpha)
                        .scale(1f - morphProgress * 0.15f)
                        .padding(start = 7.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                        if (currentSong != null) {
                            AlbumArtworkImage(
                                model = currentSong.coverUrl,
                                seedId = currentSong.id,
                                modifier = Modifier.size(coverBoxSize),
                                cornerRadius = 10.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 4.dp)
                            ) {
                                Text(
                                    text = currentSong.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(
                                        fontSize = dimensions.bodySize,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                Spacer(modifier = Modifier.height(1.dp))
                                Text(
                                    text = currentSong.artist,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(
                                        fontSize = dimensions.captionSize,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }

                            if (isWideBar) {
                                IconButton(
                                    onClick = onPrevious,
                                    modifier = Modifier.size(ctrlBtnSize)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SkipPrevious,
                                        contentDescription = "上一首",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = onTogglePlay,
                                modifier = Modifier.size(ctrlBtnSize + 2.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = "播放/暂停",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            IconButton(
                                onClick = onNext,
                                modifier = Modifier.size(ctrlBtnSize)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = "下一首",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = AppleRed,
                                modifier = Modifier
                                    .size(22.dp)
                                    .padding(start = 6.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "LMPlayer • 享受高品质音乐",
                                fontSize = dimensions.bodySize,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // B. 圆形环形进度条微缩内容：在变圆就位时（无论左滑还是右滑）淡入呈现
                if (morphProgress > 0.15f) {
                    val circleContentAlpha = ((morphProgress - 0.15f) / 0.7f).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .size(barHeight)
                            .align(Alignment.Center)
                            .alpha(circleContentAlpha)
                            .scale(0.75f + 0.25f * morphProgress),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = { playbackProgress },
                            modifier = Modifier.size(barHeight - 4.dp),
                            color = AppleRed,
                            trackColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.06f),
                            strokeWidth = 2.5.dp,
                            strokeCap = StrokeCap.Round
                        )
                        if (currentSong != null) {
                            AlbumArtworkImage(
                                model = currentSong.coverUrl,
                                seedId = currentSong.id,
                                modifier = Modifier
                                    .size(barHeight - 14.dp)
                                    .clip(CircleShape),
                                cornerRadius = (barHeight - 14.dp) / 2
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = AppleRed,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }

        // =========================================================================
        // 实体 3：中间独立的搜索框胶囊 (Left = restingPlayerX, Width = restingPlayerWidth)
        // - ★ 核心改动 1：搜索框仅占用中间长条位置 (219dp)，不再包含 ✕ 按钮！★
        // - 搜索未激活时隐藏 (alpha = 0)；
        // - 搜索激活时在中间平滑展开，内含放大镜、输入框、清空键与融合音源标签。
        // =========================================================================
        if (searchProgress > 0.05f) {
            val searchBarAlpha = ((searchProgress - 0.05f) / 0.95f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .offset(x = restingPlayerX)
                    .width(restingPlayerWidth)
                    .height(barHeight)
                    .zIndex(20f)
                    .alpha(searchBarAlpha)
                    .scale(0.85f + 0.15f * searchProgress)
                    .clip(pillShape)
                    .background(searchOverlaySurfaceColor)
                    .border(BorderStroke(1.2.dp, AppleRed.copy(alpha = 0.55f)), pillShape)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "搜索",
                        tint = AppleRed,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "搜索歌曲、歌手或歌单...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                fontSize = dimensions.bodySize,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = dimensions.bodySize,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            ),
                            cursorBrush = SolidColor(AppleRed),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = { keyboardController?.hide() }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                        )
                    }

                    if (searchQuery.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f))
                                .clickable { onSearchQueryChange("") },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "清空关键词",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // 融合式音源切换胶囊与 Dropdown
                    Box {
                        val fusedLabel = if (showOnlineSourceSelector) {
                            "${selectedOnlineSource.shortName}·${selectedSearchType.displayName}"
                        } else {
                            selectedSearchType.displayName
                        }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = AppleRed.copy(alpha = 0.14f),
                            border = BorderStroke(0.8.dp, AppleRed.copy(alpha = 0.40f)),
                            modifier = Modifier
                                .height(30.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { isSourceDropdownExpanded = true }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = fusedLabel,
                                    color = AppleRed,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = isSourceDropdownExpanded,
                            onDismissRequest = { isSourceDropdownExpanded = false },
                            modifier = Modifier.width(232.dp)
                        ) {
                            // 搜索类别分段选择
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "搜索类别",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                                )
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isDark) Color(0xFF1C1C22) else Color(0xFFEFEFF4),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(34.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(3.dp),
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        SearchContentType.entries.forEach { type ->
                                            val isTypeSelected = type == selectedSearchType
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxHeight()
                                                    .clip(RoundedCornerShape(9.dp))
                                                    .background(
                                                        if (isTypeSelected) AppleRed else Color.Transparent
                                                    )
                                                    .clickable {
                                                        onSearchTypeChange(type)
                                                        if (!showOnlineSourceSelector) {
                                                            isSourceDropdownExpanded = false
                                                        }
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = type.displayName,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isTypeSelected) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (isTypeSelected) Color.White else MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 在线音源列表
                            if (showOnlineSourceSelector) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                                Text(
                                    text = "在线音乐源",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                                OnlineMusicSource.entries.forEach { source ->
                                    val isSourceSelected = source == selectedOnlineSource
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = source.displayName,
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isSourceSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSourceSelected) AppleRed else MaterialTheme.colorScheme.onSurface
                                                )
                                                if (isSourceSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = AppleRed,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            onOnlineSourceChange(source)
                                            isSourceDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 实体 4：右侧独立的圆圈按键 (52dp x 52dp 独立圆圈)
        // - ★ 核心改动 1：展开后为【单独的圆圈 ✕ 按钮】★
        // - 常态：搜索圆圈按键 (Left = totalWidth - barHeight, Width = 52dp)
        // - 搜索展开时：变身为独立的圆圈 ✕ 退出按键 (原地顺畅自旋变身为红圈 ✕，点击退出搜索)
        // =========================================================================
        val rightBtnNavAlpha = (1f - navExpandProgress * 2.5f).coerceIn(0f, 1f)
        if (rightBtnNavAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .offset(x = restingSearchX)
                    .size(barHeight)
                    .zIndex(20f)
                    .alpha(rightBtnNavAlpha)
                    .shadow(
                        elevation = 4.dp,
                        shape = pillShape,
                        ambientColor = if (isDark) Color(0x55000000) else Color(0x18000000),
                        spotColor = if (isDark) Color(0x38000000) else Color(0x10000000)
                    )
                    .clip(pillShape)
                    .background(liquidGlassBrush)
                    .border(
                        BorderStroke(1.dp, liquidGlassBorderBrush),
                        pillShape
                    )
                    .clickable {
                        if (isSearchActive) {
                            onDismissSearch()
                        } else {
                            onSearchClick()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // 放大镜图标 (常态可见，搜索展开时旋转90度淡出)
                val magnifierAlpha = (1f - searchProgress * 2f).coerceIn(0f, 1f)
                if (magnifierAlpha > 0f) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "搜索",
                        tint = if (isDark) Color.White else Color(0xFF222228),
                        modifier = Modifier
                            .size(22.dp)
                            .alpha(magnifierAlpha)
                            .rotate(searchProgress * 90f)
                    )
                }

                // 独立的红色 ✕ 图标 (与左侧圆形主页按键完全一致的纯净质感，无亮斑与残影)
                val closeAlpha = ((searchProgress - 0.4f) / 0.6f).coerceIn(0f, 1f)
                if (closeAlpha > 0f) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭搜索",
                        tint = AppleRed,
                        modifier = Modifier
                            .size(22.dp)
                            .alpha(closeAlpha)
                            .rotate((1f - searchProgress) * 90f)
                    )
                }
            }
        }
    }
}

/**
 * 展开形态导航栏 (配备 上一首 / 播放暂停 / 下一首 + 纯净毛玻璃质感 + 弹出式平滑左滑搜索框)
 */
@Composable
fun ExpandedMorphingBottomBar(
    currentScreen: Screen,
    currentSong: UnifiedSong?,
    isPlaying: Boolean,
    isSearchActive: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    selectedOnlineSource: OnlineMusicSource = OnlineMusicSource.KUWO,
    onOnlineSourceChange: (OnlineMusicSource) -> Unit = {},
    selectedSearchType: SearchContentType = SearchContentType.SONG,
    onSearchTypeChange: (SearchContentType) -> Unit = {},
    showOnlineSourceSelector: Boolean = true,
    blurAlpha: Float = 0.85f,
    liquidGlassBlur: Float = 16f,
    onNavigate: (Screen) -> Unit,
    onDismissSearch: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onTogglePlay: () -> Unit,
    onNext: () -> Unit = {},
    onOpenFullPlayer: () -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimensions = LocalAppDimensions.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val baseAlpha = blurAlpha.coerceIn(0.10f, 1.0f)
    val glassBrush = if (isDark) {
        Brush.verticalGradient(
            listOf(
                Color(0xFF2E2E3A).copy(alpha = (0.86f * baseAlpha).coerceIn(0f, 1f)),
                Color(0xFF20202A).copy(alpha = (0.75f * baseAlpha).coerceIn(0f, 1f)),
                Color(0xFF161622).copy(alpha = (0.85f * baseAlpha).coerceIn(0f, 1f))
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color(0xFFFFFFFF).copy(alpha = (0.94f * baseAlpha).coerceIn(0f, 1f)),
                Color(0xFFF5F5FB).copy(alpha = (0.87f * baseAlpha).coerceIn(0f, 1f)),
                Color(0xFFEDEDF6).copy(alpha = (0.78f * baseAlpha).coerceIn(0f, 1f))
            )
        )
    }
    val glassBorderBrush = if (isDark) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = (0.38f * baseAlpha).coerceIn(0.12f, 0.60f)),
                Color.White.copy(alpha = (0.12f * baseAlpha).coerceIn(0.04f, 0.30f)),
                Color(0x20000000)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = (0.95f * baseAlpha).coerceIn(0.40f, 1.0f)),
                Color.White.copy(alpha = (0.65f * baseAlpha).coerceIn(0.20f, 0.85f)),
                Color(0x24000000)
            )
        )
    }
    val surfaceColor = if (isDark) {
        Color(0xFF222228).copy(alpha = blurAlpha)
    } else {
        Color(0xFFFFFFFF).copy(alpha = blurAlpha)
    }
    val searchOverlaySurfaceColor = if (isDark) {
        Color(0xFF25252D).copy(alpha = 0.98f)
    } else {
        Color(0xFFFCFCFE).copy(alpha = 0.98f)
    }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var isSourceDropdownExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            try {
                focusRequester.requestFocus()
                keyboardController?.show()
            } catch (_: Exception) {
            }
        } else {
            isSourceDropdownExpanded = false
            keyboardController?.hide()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. 上层 MiniPlayer 音乐播放栏胶囊
        if (currentSong != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BottomBarHeight)
                    .shadow(
                        elevation = 8.dp,
                        shape = UnifiedPillShape,
                        ambientColor = if (isDark) Color(0x66000000) else Color(0x22000000),
                        spotColor = if (isDark) Color(0x44000000) else Color(0x16000000)
                    )
                    .clip(UnifiedPillShape)
                    .background(glassBrush)
                    .border(BorderStroke(1.2.dp, glassBorderBrush), UnifiedPillShape)
                    .clickable(onClick = onOpenFullPlayer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AlbumArtworkImage(
                        model = currentSong.coverUrl,
                        seedId = currentSong.id,
                        modifier = Modifier.size(40.dp),
                        cornerRadius = 10.dp
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentSong.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                fontSize = dimensions.bodySize,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = currentSong.artist,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                fontSize = dimensions.captionSize,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }

                    // 上一首
                    IconButton(
                        onClick = onPrevious,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "上一首",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // 播放/暂停
                    IconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "播放/暂停",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    // 下一首
                    IconButton(
                        onClick = onNext,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "下一首",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // 2. 音乐播放栏下方：下层导航胶囊 (首页/资料库/设置) + 弹出式平滑左滑搜索框叠层 + 右侧搜索/X退出按键
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(BottomBarHeight),
                contentAlignment = Alignment.CenterEnd
            ) {
                // 底层 (zIndex = 0f)：首页、资料库、设置三个导航项
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(0f)
                        .shadow(
                            elevation = 6.dp,
                            shape = UnifiedPillShape,
                            ambientColor = if (isDark) Color(0x55000000) else Color(0x18000000)
                        )
                        .clip(UnifiedPillShape)
                        .background(glassBrush)
                        .border(BorderStroke(1.2.dp, glassBorderBrush), UnifiedPillShape)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FloatingNavTabItem(
                            title = "首页",
                            icon = Icons.Default.Home,
                            selected = !isSearchActive && currentScreen == Screen.HOME,
                            onClick = { onNavigate(Screen.HOME) }
                        )

                        FloatingNavTabItem(
                            title = "资料库",
                            icon = Icons.Default.LibraryMusic,
                            selected = !isSearchActive && currentScreen == Screen.LIBRARY,
                            onClick = { onNavigate(Screen.LIBRARY) }
                        )

                        FloatingNavTabItem(
                            title = "设置",
                            icon = Icons.Default.Settings,
                            selected = !isSearchActive && currentScreen == Screen.SETTINGS,
                            onClick = { onNavigate(Screen.SETTINGS) }
                        )
                    }
                }

                // 顶层 (zIndex = 10f，层级在首页、资料库、设置三个上方)：点击搜索时向左平滑出现的搜索框
                androidx.compose.animation.AnimatedVisibility(
                    visible = isSearchActive,
                    enter = slideInHorizontally(
                        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                        initialOffsetX = { fullWidth -> fullWidth }
                    ) + expandHorizontally(
                        expandFrom = Alignment.End,
                        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(durationMillis = 180)),
                    exit = slideOutHorizontally(
                        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                        targetOffsetX = { fullWidth -> fullWidth }
                    ) + shrinkHorizontally(
                        shrinkTowards = Alignment.End,
                        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
                    ) + fadeOut(animationSpec = tween(durationMillis = 160)),
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(10f)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .shadow(8.dp, UnifiedPillShape)
                            .clip(UnifiedPillShape)
                            .background(searchOverlaySurfaceColor)
                            .border(
                                BorderStroke(1.2.dp, AppleRed.copy(alpha = 0.52f)),
                                UnifiedPillShape
                            )
                            .padding(start = 12.dp, end = 6.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "搜索",
                                tint = AppleRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (searchQuery.isEmpty()) {
                                    val hintText = when (selectedSearchType) {
                                        SearchContentType.SONG -> "搜索歌曲、歌手或链接..."
                                        SearchContentType.ALBUM -> "搜索专辑名称或歌手..."
                                        SearchContentType.PLAYLIST -> "搜索歌单或粘贴歌单链接..."
                                    }
                                    Text(
                                        text = hintText,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                        fontSize = dimensions.bodySize,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = onSearchQueryChange,
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        fontSize = dimensions.bodySize,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    cursorBrush = SolidColor(AppleRed),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(
                                        onSearch = { keyboardController?.hide() }
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(focusRequester)
                                )
                            }

                            if (searchQuery.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f))
                                        .clickable { onSearchQueryChange("") },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "清空关键词",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }

                            // 融合式音乐切换框：将 [歌曲 / 专辑 / 歌单] 与 [在线音源] 融合在同一个下拉胶囊内
                            Spacer(modifier = Modifier.width(6.dp))
                            Box {
                                val fusedLabel = if (showOnlineSourceSelector) {
                                    "${selectedOnlineSource.shortName}·${selectedSearchType.displayName}"
                                } else {
                                    selectedSearchType.displayName
                                }
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = AppleRed.copy(alpha = 0.14f),
                                    border = BorderStroke(0.8.dp, AppleRed.copy(alpha = 0.40f)),
                                    modifier = Modifier
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .clickable { isSourceDropdownExpanded = true }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .padding(horizontal = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = when (selectedSearchType) {
                                                SearchContentType.SONG -> Icons.Default.MusicNote
                                                SearchContentType.ALBUM -> Icons.Default.Album
                                                SearchContentType.PLAYLIST -> Icons.Default.QueueMusic
                                            },
                                            contentDescription = "搜索模式与音源",
                                            tint = AppleRed,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = fusedLabel,
                                            color = AppleRed,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1
                                        )
                                        Icon(
                                            imageVector = if (isSourceDropdownExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = "展开搜索类型与音源选择",
                                            tint = AppleRed,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = isSourceDropdownExpanded,
                                    onDismissRequest = { isSourceDropdownExpanded = false },
                                    modifier = Modifier.width(232.dp)
                                ) {
                                    // 1. 顶部：搜索内容维度分段选择 [ 歌曲 | 专辑 | 歌单 ]
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = "搜索类别",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (isDark) Color(0xFF1C1C22) else Color(0xFFEFEFF4),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(34.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(3.dp),
                                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                SearchContentType.entries.forEach { type ->
                                                    val isTypeSelected = type == selectedSearchType
                                                    Box(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .fillMaxHeight()
                                                            .clip(RoundedCornerShape(9.dp))
                                                            .background(
                                                                if (isTypeSelected) AppleRed else Color.Transparent
                                                            )
                                                            .clickable {
                                                                onSearchTypeChange(type)
                                                                if (!showOnlineSourceSelector) {
                                                                    isSourceDropdownExpanded = false
                                                                }
                                                            },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = type.displayName,
                                                            fontSize = 12.sp,
                                                            fontWeight = if (isTypeSelected) FontWeight.Bold else FontWeight.Medium,
                                                            color = if (isTypeSelected) Color.White else MaterialTheme.colorScheme.onSurface
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // 2. 底部：在线音源平台列表（来自柠檬音乐服务端）
                                    if (showOnlineSourceSelector) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(vertical = 4.dp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
                                        )
                                        Text(
                                            text = "柠檬服务端在线音源",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                                        )
                                        OnlineMusicSource.entries.forEach { src ->
                                            val isSelected = src == selectedOnlineSource
                                            DropdownMenuItem(
                                                text = {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = src.displayName,
                                                            fontSize = 13.sp,
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                            color = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurface
                                                        )
                                                        if (isSelected) {
                                                            Icon(
                                                                imageVector = Icons.Default.Check,
                                                                contentDescription = "已选中",
                                                                tint = AppleRed,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }
                                                    }
                                                },
                                                onClick = {
                                                    onOnlineSourceChange(src)
                                                    isSourceDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 右侧独立圆形按钮：默认显示搜索放大镜，搜索激活时变成 X (功能为退出搜索模式，真液态玻璃材质！)
            Box(
                modifier = Modifier
                    .size(BottomBarHeight)
                    .shadow(
                        elevation = 4.dp,
                        shape = UnifiedPillShape,
                        ambientColor = if (isDark) Color(0x55000000) else Color(0x18000000),
                        spotColor = if (isDark) Color(0x38000000) else Color(0x10000000)
                    )
                    .clip(UnifiedPillShape)
                    .background(glassBrush)
                    .border(
                        BorderStroke(1.dp, glassBorderBrush),
                        UnifiedPillShape
                    )
                    .clickable(
                        onClick = {
                            if (isSearchActive) {
                                onDismissSearch()
                            } else {
                                onSearchClick()
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = isSearchActive,
                    transitionSpec = {
                        (scaleIn(animationSpec = tween(200)) + fadeIn(animationSpec = tween(180))) togetherWith
                            (scaleOut(animationSpec = tween(160)) + fadeOut(animationSpec = tween(140)))
                    },
                    label = "search_close_icon_morph"
                ) { active ->
                    if (active) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "退出搜索模式",
                            tint = AppleRed,
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "搜索",
                            tint = if (isDark) Color.White else Color(0xFF222228),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingNavTabItem(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val dimensions = LocalAppDimensions.current
    val activeColor = AppleRed
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
    val contentColor = if (selected) activeColor else inactiveColor

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = contentColor,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = title,
                style = TextStyle(
                    fontSize = dimensions.captionSize,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = contentColor
                )
            )
        }
    }
}

/**
 * 优化形态渐变悬浮底栏 (支持线性滑动缩放动效与毛玻璃透明度调节)
 */
@Composable
fun MorphingFloatingBottomBar(
    currentScreen: Screen,
    currentSong: UnifiedSong?,
    isPlaying: Boolean,
    isSearchActive: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    selectedOnlineSource: OnlineMusicSource = OnlineMusicSource.KUWO,
    onOnlineSourceChange: (OnlineMusicSource) -> Unit = {},
    selectedSearchType: SearchContentType = SearchContentType.SONG,
    onSearchTypeChange: (SearchContentType) -> Unit = {},
    showOnlineSourceSelector: Boolean = true,
    isNavExpanded: Boolean,
    progressMs: Long = 0L,
    totalDurationMs: Long = 0L,
    blurAlpha: Float = 0.85f,
    liquidGlassBlur: Float = 16f,
    bottomBarAnimMode: BottomBarAnimMode = BottomBarAnimMode.SLIDE,
    useLinearAnimation: Boolean = true,
    onToggleNavExpanded: (Boolean) -> Unit,
    onDismissSearch: () -> Unit = {},
    onNavigate: (Screen) -> Unit,
    onPrevious: () -> Unit = {},
    onTogglePlay: () -> Unit,
    onNext: () -> Unit = {},
    onOpenFullPlayer: () -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (bottomBarAnimMode == BottomBarAnimMode.CLICK) {
        val effectiveExpanded = isNavExpanded || isSearchActive
        Box(
            modifier = modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            val animationEasing = if (useLinearAnimation) LinearEasing else FastOutSlowInEasing
            val scale by animateFloatAsState(
                targetValue = if (effectiveExpanded) 1f else 0.98f,
                animationSpec = tween(durationMillis = 200, easing = animationEasing),
                label = "morphing_bar_scale"
            )

            Box(modifier = Modifier.scale(scale)) {
                Crossfade(
                    targetState = effectiveExpanded,
                    animationSpec = tween(durationMillis = 200, easing = animationEasing),
                    label = "morphing_bar_crossfade"
                ) { expanded ->
                    if (expanded) {
                        ExpandedMorphingBottomBar(
                            currentScreen = currentScreen,
                            currentSong = currentSong,
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
                                onNavigate(screen)
                                onToggleNavExpanded(false)
                            },
                            onDismissSearch = onDismissSearch,
                            onPrevious = onPrevious,
                            onTogglePlay = onTogglePlay,
                            onNext = onNext,
                            onOpenFullPlayer = onOpenFullPlayer,
                            onSearchClick = onSearchClick
                        )
                    } else {
                        CompactMorphingBottomBar(
                            currentScreen = currentScreen,
                            currentSong = currentSong,
                            isPlaying = isPlaying,
                            isSearchActive = isSearchActive,
                            searchQuery = searchQuery,
                            onSearchQueryChange = onSearchQueryChange,
                            selectedOnlineSource = selectedOnlineSource,
                            onOnlineSourceChange = onOnlineSourceChange,
                            selectedSearchType = selectedSearchType,
                            onSearchTypeChange = onSearchTypeChange,
                            showOnlineSourceSelector = showOnlineSourceSelector,
                            progressMs = progressMs,
                            totalDurationMs = totalDurationMs,
                            blurAlpha = blurAlpha,
                            liquidGlassBlur = liquidGlassBlur,
                            bottomBarAnimMode = BottomBarAnimMode.CLICK,
                            onExpandNav = { onToggleNavExpanded(true) },
                            onNavigate = onNavigate,
                            onDismissSearch = onDismissSearch,
                            onPrevious = onPrevious,
                            onTogglePlay = onTogglePlay,
                            onNext = onNext,
                            onOpenFullPlayer = onOpenFullPlayer,
                            onSearchClick = onSearchClick
                        )
                    }
                }
            }
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            CompactMorphingBottomBar(
                currentScreen = currentScreen,
                currentSong = currentSong,
                isPlaying = isPlaying,
                isSearchActive = isSearchActive,
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                selectedOnlineSource = selectedOnlineSource,
                onOnlineSourceChange = onOnlineSourceChange,
                selectedSearchType = selectedSearchType,
                onSearchTypeChange = onSearchTypeChange,
                showOnlineSourceSelector = showOnlineSourceSelector,
                progressMs = progressMs,
                totalDurationMs = totalDurationMs,
                blurAlpha = blurAlpha,
                liquidGlassBlur = liquidGlassBlur,
                bottomBarAnimMode = BottomBarAnimMode.SLIDE,
                onExpandNav = { },
                onNavigate = onNavigate,
                onDismissSearch = onDismissSearch,
                onPrevious = onPrevious,
                onTogglePlay = onTogglePlay,
                onNext = onNext,
                onOpenFullPlayer = onOpenFullPlayer,
                onSearchClick = onSearchClick
            )
        }
    }
}

/**
 * 首页与资料库顶部服务器/在线本地切换下拉选择菜单
 * 自适应不同分辨率屏幕宽度，并对过长的在线服务名称自动精简与单行省略截断，防止挤压其他按钮
 */
@Composable
fun ServerSwitchDropdownButton(
    currentServer: String,
    configuredServers: List<ServerConfig> = emptyList(),
    blurAlpha: Float = 0.85f,
    onSelectLocal: () -> Unit,
    onSelectServer: (ServerConfig) -> Unit,
    onSyncNow: () -> Unit,
    onGoToSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val dimensions = LocalAppDimensions.current
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val isCompactScreen = screenWidthDp < 380
    val isUltraCompactScreen = screenWidthDp < 345

    var expanded by remember { mutableStateOf(false) }
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val surfaceColor = if (isDark) {
        Color(0xFF222228).copy(alpha = blurAlpha)
    } else {
        Color(0xFFFFFFFF).copy(alpha = blurAlpha)
    }
    val borderColor = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)

    // 智能美化在线/本地按钮文案，过滤冗长协议前缀（如 "lemon_music · " 或 "柠檬音乐 · "）
    val compactDisplayLabel = remember(currentServer, isCompactScreen) {
        val raw = currentServer.trim()
        when {
            raw.contains("本地") -> if (isCompactScreen) "本地" else "本地曲库"
            raw.contains("未连接") -> if (isCompactScreen) "未连接" else "未连接服务"
            else -> {
                val cleaned = raw
                    .replace(Regex("^(lemon_music|柠檬音乐服务端|柠檬音乐|在线)\\s*[·•:\\-|]\\s*", RegexOption.IGNORE_CASE), "")
                    .trim()
                cleaned.ifBlank { "在线服务" }
            }
        }
    }

    val maxTextWidth = when {
        isUltraCompactScreen -> 64.dp
        isCompactScreen -> 84.dp
        screenWidthDp < 430 -> 106.dp
        else -> 136.dp
    }
    val horizontalPad = if (isCompactScreen) 9.dp else 11.dp
    val verticalPad = if (isCompactScreen) 6.dp else 7.dp
    val pillShape = RoundedCornerShape(12.dp)

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .shadow(4.dp, pillShape)
                .clip(pillShape)
                .background(surfaceColor)
                .border(BorderStroke(1.dp, borderColor), pillShape)
                .clickable { expanded = true }
                .padding(horizontal = horizontalPad, vertical = verticalPad)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (currentServer.contains("本地")) Icons.Default.FolderSpecial else Icons.Default.Dns,
                    contentDescription = null,
                    tint = AppleRed,
                    modifier = Modifier.size(if (isCompactScreen) 14.dp else 15.dp)
                )
                Spacer(modifier = Modifier.width(if (isCompactScreen) 4.dp else 5.dp))
                Text(
                    text = compactDisplayLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = maxTextWidth),
                    style = TextStyle(
                        fontSize = if (isCompactScreen) 11.5.sp else dimensions.captionSize,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (isCompactScreen) 14.dp else 16.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 180.dp, max = 260.dp)
        ) {
            // 1. 本地媒体库选项
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (currentServer.contains("本地")) {
                            Text("✓ ", color = AppleRed, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = "本地 · 离线曲库",
                            fontSize = dimensions.bodySize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                onClick = {
                    onSelectLocal()
                    expanded = false
                },
                modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(10.dp))
            )

            // 2. 真实已添加配置的服务器选项
            if (configuredServers.isNotEmpty()) {
                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.2f), thickness = 0.5.dp)
                val isLocalMode = currentServer.contains("本地")
                configuredServers.forEach { server ->
                    val isCurrent = !isLocalMode && (currentServer.contains(server.name, ignoreCase = true) || (currentServer != "未连接服务器" && server.isCurrentActive))
                    val serverLabel = server.name.trim().ifBlank { "柠檬音乐服务器" }
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isCurrent) {
                                    Text("✓ ", color = AppleRed, fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    text = "在线 · $serverLabel",
                                    fontSize = dimensions.bodySize,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isCurrent) AppleRed else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        onClick = {
                            onSelectServer(server)
                            expanded = false
                        },
                        modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(10.dp))
                    )
                }
            } else {
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = AppleRed) },
                    text = { Text("添加柠檬音乐服务器...", fontSize = dimensions.bodySize, color = AppleRed) },
                    onClick = {
                        onGoToSettings()
                        expanded = false
                    },
                    modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(10.dp))
                )
            }

            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f), thickness = 0.5.dp)

            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = AppleRed, modifier = Modifier.size(18.dp))
                },
                text = { Text("立即同步", color = AppleRed, fontSize = dimensions.bodySize, fontWeight = FontWeight.SemiBold) },
                onClick = {
                    onSyncNow()
                    expanded = false
                },
                modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(10.dp))
            )
        }
    }
}
