package com.lm.player.core.designsystem.component

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.model.Screen
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
    blurAlpha: Float = 0.85f,
    onExpandNav: () -> Unit,
    onPrevious: () -> Unit = {},
    onTogglePlay: () -> Unit,
    onNext: () -> Unit = {},
    onOpenFullPlayer: () -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier
) {
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

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. 左侧当前页面图标按钮 (使用原生 RoundedCornerShape 杜绝八边形/色块)
        Box(
            modifier = Modifier
                .size(BottomBarHeight)
                .clip(UnifiedPillShape)
                .background(surfaceColor)
                .border(BorderStroke(1.dp, borderColor), UnifiedPillShape)
                .clickable(onClick = onExpandNav),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = currentScreenIcon,
                contentDescription = "展开导航",
                tint = AppleRed,
                modifier = Modifier.size(24.dp)
            )
        }

        // 2. 中间播放胶囊 (封面 + 歌名歌手 + 上一首 + 播放暂停 + 下一首)
        Box(
            modifier = Modifier
                .weight(1f)
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
                if (currentSong != null) {
                    AlbumArtworkImage(
                        model = currentSong.coverUrl,
                        seedId = currentSong.id,
                        modifier = Modifier.size(40.dp),
                        cornerRadius = 10.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentSong.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = currentSong.artist,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }

                    // 上一首按键
                    IconButton(
                        onClick = onPrevious,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "上一首",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // 播放/暂停按键
                    IconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "播放/暂停",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // 下一首按键
                    IconButton(
                        onClick = onNext,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "下一首",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
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
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 3. 右侧独立搜索圆形按钮 (与中段与左段使用同源统一形状与纯净背景)
        Box(
            modifier = Modifier
                .size(BottomBarHeight)
                .clip(UnifiedPillShape)
                .background(surfaceColor)
                .border(BorderStroke(1.dp, borderColor), UnifiedPillShape)
                .clickable(onClick = onSearchClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "搜索",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * 展开形态导航栏 (配备 上一首 / 播放暂停 / 下一首 + 纯净毛玻璃质感)
 */
@Composable
fun ExpandedMorphingBottomBar(
    currentScreen: Screen,
    currentSong: UnifiedSong?,
    isPlaying: Boolean,
    blurAlpha: Float = 0.85f,
    onNavigate: (Screen) -> Unit,
    onPrevious: () -> Unit = {},
    onTogglePlay: () -> Unit,
    onNext: () -> Unit = {},
    onOpenFullPlayer: () -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val surfaceColor = if (isDark) {
        Color(0xFF222228).copy(alpha = blurAlpha)
    } else {
        Color(0xFFFFFFFF).copy(alpha = blurAlpha)
    }
    val borderColor = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. 上层 MiniPlayer 胶囊
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
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = currentSong.artist,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                fontSize = 11.sp,
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

        // 2. 下层导航胶囊 + 独立圆形搜索按键
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(BottomBarHeight)
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
                        selected = currentScreen == Screen.HOME,
                        onClick = { onNavigate(Screen.HOME) }
                    )

                    FloatingNavTabItem(
                        title = "资料库",
                        icon = Icons.Default.LibraryMusic,
                        selected = currentScreen == Screen.LIBRARY,
                        onClick = { onNavigate(Screen.LIBRARY) }
                    )

                    FloatingNavTabItem(
                        title = "设置",
                        icon = Icons.Default.Settings,
                        selected = currentScreen == Screen.SETTINGS,
                        onClick = { onNavigate(Screen.SETTINGS) }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(BottomBarHeight)
                    .clip(UnifiedPillShape)
                    .background(surfaceColor)
                    .border(BorderStroke(1.dp, borderColor), UnifiedPillShape)
                .clickable(onClick = onSearchClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "搜索",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
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
                    fontSize = 11.sp,
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
    isNavExpanded: Boolean,
    blurAlpha: Float = 0.85f,
    useLinearAnimation: Boolean = true,
    onToggleNavExpanded: (Boolean) -> Unit,
    onNavigate: (Screen) -> Unit,
    onPrevious: () -> Unit = {},
    onTogglePlay: () -> Unit,
    onNext: () -> Unit = {},
    onOpenFullPlayer: () -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        val animationEasing = if (useLinearAnimation) LinearEasing else FastOutSlowInEasing
        val scale by animateFloatAsState(
            targetValue = if (isNavExpanded) 1f else 0.98f,
            animationSpec = tween(durationMillis = 200, easing = animationEasing),
            label = "morphing_bar_scale"
        )

        Box(modifier = Modifier.scale(scale)) {
            Crossfade(
                targetState = isNavExpanded,
                animationSpec = tween(durationMillis = 200, easing = animationEasing),
                label = "morphing_bar_crossfade"
            ) { expanded ->
                if (expanded) {
                    ExpandedMorphingBottomBar(
                        currentScreen = currentScreen,
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        blurAlpha = blurAlpha,
                        onNavigate = { screen ->
                            onNavigate(screen)
                            onToggleNavExpanded(false)
                        },
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
                        blurAlpha = blurAlpha,
                        onExpandNav = { onToggleNavExpanded(true) },
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
 * 首页顶部服务器切换下拉选择菜单 (毛玻璃效果 + 仅显示已真实配置的服务器与本地曲库)
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
    var expanded by remember { mutableStateOf(false) }
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val surfaceColor = if (isDark) {
        Color(0xFF222228).copy(alpha = blurAlpha)
    } else {
        Color(0xFFFFFFFF).copy(alpha = blurAlpha)
    }
    val borderColor = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)

    val pillShape = RoundedCornerShape(12.dp)

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .shadow(6.dp, pillShape)
                .clip(pillShape)
                .background(surfaceColor)
                .border(BorderStroke(1.dp, borderColor), pillShape)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Dns,
                    contentDescription = null,
                    tint = AppleRed,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = currentServer,
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            // 1. 本地媒体库选项
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (currentServer.contains("本地")) {
                            Text("✓ ", color = AppleRed, fontWeight = FontWeight.Bold)
                        }
                        Text("本地 · 已下载", fontSize = 14.sp)
                    }
                },
                onClick = {
                    onSelectLocal()
                    expanded = false
                },
                modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(10.dp))
            )

            // 2. 真实已添加配置的服务器选项 (不显示未添加的占位项)
            if (configuredServers.isNotEmpty()) {
                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.2f), thickness = 0.5.dp)
                val isLocalMode = currentServer.contains("本地")
                configuredServers.forEach { server ->
                    val isCurrent = !isLocalMode && (currentServer.contains(server.name, ignoreCase = true) || (currentServer != "未连接服务器" && server.isCurrentActive))
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isCurrent) {
                                    Text("✓ ", color = AppleRed, fontWeight = FontWeight.Bold)
                                }
                                Text("${server.type.name.lowercase()} · ${server.name}", fontSize = 14.sp)
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
                    text = { Text("添加 NAS 服务器...", fontSize = 13.sp, color = AppleRed) },
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
                text = { Text("立即同步", color = AppleRed, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) },
                onClick = {
                    onSyncNow()
                    expanded = false
                },
                modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(10.dp))
            )
        }
    }
}
