package com.lm.player.ui.car

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.model.Screen
import com.lm.player.core.model.UnifiedSong

/**
 * 车机专属大触控左侧常驻侧边导航栏 (64dp+ 靶心，高对比度防误触)
 */
@Composable
fun CarSideNavigationBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF141417))
            .padding(vertical = 24.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Logo / 品牌标
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = AppleRed,
            modifier = Modifier.size(36.dp)
        )

        // 核心导航项列表 (大触控靶心)
        Column(
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CarNavItem(
                title = "首页",
                selected = currentScreen == Screen.HOME,
                icon = if (currentScreen == Screen.HOME) Icons.Filled.Home else Icons.Outlined.Home,
                onClick = { onNavigate(Screen.HOME) }
            )

            CarNavItem(
                title = "资料库",
                selected = currentScreen == Screen.LIBRARY,
                icon = if (currentScreen == Screen.LIBRARY) Icons.Filled.LibraryMusic else Icons.Outlined.LibraryMusic,
                onClick = { onNavigate(Screen.LIBRARY) }
            )

            CarNavItem(
                title = "设置",
                selected = currentScreen == Screen.SETTINGS,
                icon = if (currentScreen == Screen.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                onClick = { onNavigate(Screen.SETTINGS) }
            )
        }

        // 底部搜索项
        IconButton(
            onClick = { /* 搜索 */ },
            modifier = Modifier
                .size(52.dp)
                .background(Color(0xFF24242A), CircleShape)
        ) {
            Icon(Icons.Default.Search, contentDescription = "搜索", tint = Color.White)
        }
    }
}

@Composable
private fun CarNavItem(
    title: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val bg = if (selected) AppleRed else Color.Transparent
    val tint = if (selected) Color.White else Color(0xFF9E9EA7)

    Column(
        modifier = Modifier
            .size(width = 72.dp, height = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = title, tint = tint, modifier = Modifier.size(28.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(title, color = tint, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

/**
 * 车机横屏右侧常驻播放控制面板 (无需频繁在页面与全屏播放器之间来回切换)
 */
@Composable
fun CarDockPlayerPanel(
    song: UnifiedSong,
    isPlaying: Boolean,
    progressMs: Long,
    totalDurationMs: Long,
    onPlayPauseToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpenFullPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onOpenFullPlayer),
        color = Color(0xFF1A1A1E),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "正在播放",
                color = Color.Gray,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )

            // 封面大图
            AsyncImage(
                model = song.coverUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(190.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .shadow(12.dp, RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop
            )

            // 标题与艺术家
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = song.title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = song.artist,
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 进度指示条
            Slider(
                value = if (totalDurationMs > 0) progressMs.toFloat() / totalDurationMs else 0f,
                onValueChange = {},
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = AppleRed,
                    inactiveTrackColor = Color.DarkGray
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // 车机超大号播放控制按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(54.dp)) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "上一首", tint = Color.White, modifier = Modifier.size(36.dp))
                }

                FloatingActionButton(
                    onClick = onPlayPauseToggle,
                    containerColor = AppleRed,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "播放/暂停",
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(onClick = onNext, modifier = Modifier.size(54.dp)) {
                    Icon(Icons.Default.SkipNext, contentDescription = "下一首", tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
        }
    }
}
