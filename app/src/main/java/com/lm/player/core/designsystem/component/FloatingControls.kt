package com.lm.player.core.designsystem.component

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
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
@Composable
fun CompactMorphingBottomBar(
    currentScreen: Screen,
    currentSong: UnifiedSong?,
    isPlaying: Boolean,
    isSearchActive: Boolean = false,
    blurAlpha: Float = 0.85f,
    onExpandNav: () -> Unit,
    onDismissSearch: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onTogglePlay: () -> Unit,
    onNext: () -> Unit = {},
    onOpenFullPlayer: () -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimensions = LocalAppDimensions.current
    val currentScreenIcon = when (currentScreen) {
        Screen.HOME -> Icons.Filled.Home
        Screen.LIBRARY, Screen.DOWNLOADS -> Icons.Filled.LibraryMusic
        Screen.SETTINGS -> Icons.Filled.Settings
    }

    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val surfaceColor = if (isDark) {
        Color(0xFF222228).copy(alpha = blurAlpha)
    } else {
        Color(0xFFFFFFFF).copy(alpha = blurAlpha)
    }
    val borderColor = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val isWideBar = maxWidth >= 430.dp
        val barHeight = if (maxWidth < 360.dp) 50.dp else 54.dp
        val pillShape = RoundedCornerShape(barHeight / 2)
        val outerGap = if (maxWidth < 380.dp) 7.dp else 9.dp
        val coverBoxSize = if (maxWidth < 360.dp) 34.dp else 38.dp
        val ctrlBtnSize = if (maxWidth < 380.dp) 28.dp else 30.dp

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(outerGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. 左侧当前页面图标按钮 (若当前处于搜索界面，点击自动退出搜索并展开导航)
            Box(
                modifier = Modifier
                    .size(barHeight)
                    .clip(pillShape)
                    .background(surfaceColor)
                    .border(BorderStroke(1.dp, borderColor), pillShape)
                    .clickable {
                        if (isSearchActive) {
                            onDismissSearch()
                        }
                        onExpandNav()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = currentScreenIcon,
                    contentDescription = "展开导航",
                    tint = if (isSearchActive) MaterialTheme.colorScheme.onSurface else AppleRed,
                    modifier = Modifier.size(22.dp)
                )
            }

            // 2. 中间播放胶囊 (自适应宽度，优先保证歌名与歌手完整显示)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(barHeight)
                    .clip(pillShape)
                    .background(surfaceColor)
                    .border(BorderStroke(1.dp, borderColor), pillShape)
                    .clickable(onClick = onOpenFullPlayer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
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

                        // 宽屏显示“上一首”，常规手机竖屏优先保障歌名与歌手展示空间（展开底栏内已含完整三键）
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

                        // 播放/暂停按键
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

                        // 下一首按键
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
                            modifier = Modifier.size(22.dp).padding(start = 6.dp)
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

            // 3. 右侧独立搜索/退出搜索圆形按钮
            Box(
                modifier = Modifier
                    .size(barHeight)
                    .clip(pillShape)
                    .background(if (isSearchActive) AppleRed.copy(alpha = 0.14f) else surfaceColor)
                    .border(
                        BorderStroke(1.dp, if (isSearchActive) AppleRed.copy(alpha = 0.55f) else borderColor),
                        pillShape
                    )
                    .clickable(onClick = { if (isSearchActive) onDismissSearch() else onSearchClick() }),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = if (isSearchActive) "退出搜索模式" else "搜索",
                    tint = if (isSearchActive) AppleRed else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(21.dp)
                )
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
    val borderColor = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)

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
                    .clip(UnifiedPillShape)
                    .background(surfaceColor)
                    .border(BorderStroke(1.dp, borderColor), UnifiedPillShape)
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
                        .clip(UnifiedPillShape)
                        .background(surfaceColor)
                        .border(BorderStroke(1.dp, borderColor), UnifiedPillShape)
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

            // 右侧独立圆形按钮：默认显示搜索放大镜，搜索激活时变成 X (功能为退出搜索模式)
            Box(
                modifier = Modifier
                    .size(BottomBarHeight)
                    .clip(UnifiedPillShape)
                    .background(if (isSearchActive) AppleRed.copy(alpha = 0.15f) else surfaceColor)
                    .border(
                        BorderStroke(1.dp, if (isSearchActive) AppleRed.copy(alpha = 0.60f) else borderColor),
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
                    Icon(
                        imageVector = if (active) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = if (active) "退出搜索模式" else "搜索",
                        tint = if (active) AppleRed else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
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
    blurAlpha: Float = 0.85f,
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
                        blurAlpha = blurAlpha,
                        onExpandNav = { onToggleNavExpanded(true) },
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
