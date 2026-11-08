package com.lm.player.feature.player.controls

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.media.PlayMode

/**
 * 图标式三态播放模式按钮
 *
 * 适用于：默认主题竖屏/横屏、怀旧主题竖屏。
 * 三态切换顺序：列表循环 -> 单曲循环 -> 随机播放 -> 列表循环。
 *
 * @param mode 当前播放模式
 * @param onClick 点击切换回调
 * @param tint 普通态图标色（由宿主页面根据主题动态传入 primaryTextColor 或 secondaryTextColor）
 * @param activeTint 激活态图标色（单曲循环与随机播放时突出高亮，默认为 AppleRed）
 * @param buttonSize 触控盒尺寸（竖屏通常 48dp，横屏为 28~38dp 自适应）
 * @param iconSize 图标尺寸（竖屏通常 24dp，横屏为 16~20dp 自适应）
 */
@Composable
fun PlayModeIconButton(
    mode: PlayMode,
    onClick: () -> Unit,
    tint: Color,
    activeTint: Color = AppleRed,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 48.dp,
    iconSize: Dp = 24.dp
) {
    val isHighlighted = mode != PlayMode.LIST_LOOP
    val currentTint = if (isHighlighted) activeTint else tint

    Box(
        modifier = modifier
            .size(buttonSize)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(
            targetState = mode,
            animationSpec = tween(durationMillis = 200),
            label = "play_mode_icon_crossfade"
        ) { targetMode ->
            val iconVector = when (targetMode) {
                PlayMode.LIST_LOOP -> Icons.Default.Repeat
                PlayMode.REPEAT_ONE -> Icons.Default.RepeatOne
                PlayMode.SHUFFLE -> Icons.Default.Shuffle
            }
            Icon(
                imageVector = iconVector,
                contentDescription = targetMode.label,
                tint = currentTint,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/**
 * 图标式播放列表按键
 *
 * 适用于：默认主题竖屏/横屏、怀旧主题竖屏（放置于主控行最左侧原随机位置）。
 *
 * @param onClick 点击回调（竖屏打开播放队列 Sheet，横屏切换右侧面板）
 * @param tint 普通态图标色
 * @param isActive 是否正处于激活展示态（横屏右侧面板正展示列表时为 true）
 * @param activeTint 激活高亮色（默认为 AppleRed）
 * @param buttonSize 触控盒尺寸
 * @param iconSize 图标尺寸
 */
@Composable
fun QueueIconButton(
    onClick: () -> Unit,
    tint: Color,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    activeTint: Color = AppleRed,
    buttonSize: Dp = 48.dp,
    iconSize: Dp = 24.dp
) {
    val currentTint = if (isActive) activeTint else tint

    Box(
        modifier = modifier
            .size(buttonSize)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
            contentDescription = "待播列表",
            tint = currentTint,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * 胶囊式播放模式按键
 *
 * 适用于：怀旧主题横屏底栏右侧（带短文本 shortLabel）。
 */
@Composable
fun PlayModeChip(
    mode: PlayMode,
    onClick: () -> Unit,
    isDark: Boolean,
    secondaryTextColor: Color,
    modifier: Modifier = Modifier
) {
    val isHighlighted = mode != PlayMode.LIST_LOOP
    val chipShape = RoundedCornerShape(12.dp)
    val bgColor = if (isHighlighted) {
        AppleRed.copy(alpha = 0.18f)
    } else if (isDark) {
        Color(0x22FFFFFF)
    } else {
        Color(0x15000000)
    }
    val borderColor = if (isHighlighted) AppleRed.copy(alpha = 0.6f) else Color.Transparent
    val contentColor = if (isHighlighted) AppleRed else secondaryTextColor
    val iconVector = when (mode) {
        PlayMode.LIST_LOOP -> Icons.Default.Repeat
        PlayMode.REPEAT_ONE -> Icons.Default.RepeatOne
        PlayMode.SHUFFLE -> Icons.Default.Shuffle
    }

    Surface(
        shape = chipShape,
        color = bgColor,
        border = BorderStroke(0.6.dp, borderColor),
        modifier = modifier
            .clip(chipShape)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = iconVector,
                contentDescription = mode.label,
                tint = contentColor,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = mode.shortLabel,
                fontSize = 11.sp,
                fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
                color = contentColor
            )
        }
    }
}

/**
 * 胶囊式播放列表按键
 *
 * 适用于：怀旧主题横屏底栏左侧（带短文本 "列表"）。
 */
@Composable
fun QueueChip(
    onClick: () -> Unit,
    isDark: Boolean,
    secondaryTextColor: Color,
    isActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    val chipShape = RoundedCornerShape(12.dp)
    val bgColor = if (isActive) {
        AppleRed.copy(alpha = 0.18f)
    } else if (isDark) {
        Color(0x22FFFFFF)
    } else {
        Color(0x15000000)
    }
    val borderColor = if (isActive) AppleRed.copy(alpha = 0.6f) else Color.Transparent
    val contentColor = if (isActive) AppleRed else secondaryTextColor

    Surface(
        shape = chipShape,
        color = bgColor,
        border = BorderStroke(0.6.dp, borderColor),
        modifier = modifier
            .clip(chipShape)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = "待播列表",
                tint = contentColor,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = "列表",
                fontSize = 11.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = contentColor
            )
        }
    }
}

// ==========================================
// 4 组高保真预览
// ==========================================

@Composable
private fun PlayModeControlsPreviewContent(isDark: Boolean) {
    var mode by remember { mutableStateOf(PlayMode.LIST_LOOP) }
    var isQueueActive by remember { mutableStateOf(false) }
    val primaryText = if (isDark) Color.White else Color.Black
    val secondaryText = if (isDark) Color(0xFFA1A1A8) else Color(0xFF636366)
    val bgColor = if (isDark) Color(0xFF101014) else Color(0xFFF2F2F7)

    Column(
        modifier = Modifier
            .background(bgColor)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("图标式控件 (竖屏/横屏)", color = primaryText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QueueIconButton(
                onClick = { isQueueActive = !isQueueActive },
                tint = secondaryText,
                isActive = isQueueActive
            )
            PlayModeIconButton(
                mode = mode,
                onClick = { mode = mode.next() },
                tint = secondaryText
            )
        }

        Text("胶囊式控件 (怀旧横屏底栏)", color = primaryText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QueueChip(
                onClick = { isQueueActive = !isQueueActive },
                isDark = isDark,
                secondaryTextColor = secondaryText,
                isActive = isQueueActive
            )
            PlayModeChip(
                mode = mode,
                onClick = { mode = mode.next() },
                isDark = isDark,
                secondaryTextColor = secondaryText
            )
        }
    }
}

@Preview(name = "竖屏·浅色", showBackground = true)
@Composable
private fun PlayModeControlsPreviewLight() {
    PlayModeControlsPreviewContent(isDark = false)
}

@Preview(name = "竖屏·深色", showBackground = true, backgroundColor = 0xFF101014)
@Composable
private fun PlayModeControlsPreviewDark() {
    PlayModeControlsPreviewContent(isDark = true)
}

@Preview(name = "横屏·浅色", widthDp = 800, heightDp = 360, showBackground = true)
@Composable
private fun PlayModeControlsPreviewLandscapeLight() {
    PlayModeControlsPreviewContent(isDark = false)
}

@Preview(name = "横屏·深色", widthDp = 800, heightDp = 360, showBackground = true, backgroundColor = 0xFF101014)
@Composable
private fun PlayModeControlsPreviewLandscapeDark() {
    PlayModeControlsPreviewContent(isDark = true)
}
