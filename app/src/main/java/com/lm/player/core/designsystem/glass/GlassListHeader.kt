package com.lm.player.core.designsystem.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 列表页通用的液态玻璃顶栏（与资料库列表页 / 歌手页同一套）：
 * - 玻璃开启：登记到上层玻璃顶栏（铺满宽度、直角、从屏幕最顶端开始），列表从它下面穿过；
 *   列表滚过一小段后收成「返回 + 标题 · 信息」小胶囊，点胶囊回到顶部。
 * - 玻璃未开启：原地画普通的「返回 + 标题 / 副标题 + 右侧按钮」标题行，同样滚动后收成小胶囊。
 *
 * 玻璃开启时页面自己不要再加 statusBarsPadding，列表顶部内边距用 [rememberGlassListTopInset]。
 */
@Composable
fun GlassListHeader(
    key: Any,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    collapsed: Boolean,
    onTapCapsule: () -> Unit,
    capsuleInfo: String = subtitle.substringAfterLast("·").trim(),
    titleSize: TextUnit = 18.sp,
    subtitleSize: TextUnit = 12.sp,
    rowVerticalPadding: Dp = 8.dp,
    actions: @Composable RowScope.() -> Unit = {},
    below: (@Composable ColumnScope.() -> Unit)? = null,
    /** 搜索中：输入框原地取代标题与右侧按钮（与资料库列表页一样）；调用方给它 weight(1f) */
    searchField: (@Composable RowScope.() -> Unit)? = null
) {
    val glass = rememberLiquidGlassActive()
    // 开 / 关液态玻璃都收缩（关玻璃时标题行在页面里，收起后列表区域随之变高）
    val showCompact = collapsed
    val block: @Composable ColumnScope.() -> Unit = {
        AnimatedVisibility(
            visible = showCompact,
            enter = fadeIn(tween(220)) + expandVertically(tween(260)),
            exit = fadeOut(tween(160)) + shrinkVertically(tween(240))
        ) {
            GlassListCompactBar(title = title, info = capsuleInfo, onBack = onBack, onTapCapsule = onTapCapsule)
        }
        AnimatedVisibility(
            visible = !showCompact,
            enter = fadeIn(tween(220)) + expandVertically(tween(260)),
            exit = fadeOut(tween(160)) + shrinkVertically(tween(240))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = if (glass) 8.dp else rowVerticalPadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    if (searchField != null) {
                        searchField()
                    } else {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontSize = titleSize,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (subtitle.isNotBlank()) {
                            Text(
                                text = subtitle,
                                fontSize = subtitleSize,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    actions()
                    }
                }
                below?.invoke(this)
            }
        }
    }
    if (glass) {
        RegisterGlassTopBar(key = key, content = block)
    } else {
        Column(modifier = Modifier.fillMaxWidth(), content = block)
    }
}

/**
 * 滚动后的收缩顶栏：返回键 + 居中一颗小胶囊（标题 · 信息）；点胶囊回到顶部并展开完整顶栏。
 */
@Composable
fun GlassListCompactBar(
    title: String,
    info: String,
    onBack: () -> Unit,
    onTapCapsule: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = onSurface)
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(onSurface.copy(alpha = 0.07f))
                    .clickable(onClick = onTapCapsule)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 190.dp)
                )
                if (info.isNotBlank()) {
                    Text(
                        text = " · $info",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
        // 与左侧返回键等宽，让小胶囊居中
        Spacer(Modifier.width(40.dp))
    }
}

/**
 * 列表顶部需要给玻璃顶栏留出的高度（含状态栏；用展开时的最大高度，收成小胶囊时列表不跳）。
 * 玻璃未开启时为 0。
 */
@Composable
fun rememberGlassListTopInset(): Dp {
    if (!rememberLiquidGlassActive()) return 0.dp
    return with(LocalDensity.current) { GlassTopBars.expandedHeightPx.toDp() }
}

/** 页面内部的列表（例如在私有子组件里）读取玻璃顶栏高度用；不需要时为 0 */
val LocalGlassListTopInset = compositionLocalOf { 0.dp }

/**
 * 顶栏是否收成小胶囊（与资料库列表页一致：往下滚过 56dp 收起，回到顶部 8dp 以内才展开）。
 * [position] 返回 (第一个可见项下标, 偏移像素)。
 */
@Composable
fun rememberTopBarCollapsed(vararg keys: Any?, position: () -> Pair<Int, Int>): Boolean {
    val density = LocalDensity.current
    val collapsePx = with(density) { 56.dp.toPx() }
    val expandPx = with(density) { 8.dp.toPx() }
    var collapsed by remember(*keys) { mutableStateOf(false) }
    val latest = rememberUpdatedState(position)
    LaunchedEffect(*keys, collapsePx, expandPx) {
        snapshotFlow { latest.value() }.collect { (idx, off) ->
            if (!collapsed && (idx > 0 || off > collapsePx)) collapsed = true
            else if (collapsed && idx == 0 && off < expandPx) collapsed = false
        }
    }
    return collapsed
}
