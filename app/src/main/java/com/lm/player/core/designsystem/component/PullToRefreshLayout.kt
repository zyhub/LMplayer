package com.lm.player.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.lm.player.core.designsystem.theme.AppleRed

/**
 * 现代轻奢交互：通用平滑下拉刷新容器
 * 支持各种滚动容器（LazyColumn、LazyVerticalGrid、Column 带 verticalScroll 等）
 */
@Composable
fun PullToRefreshLayout(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    refreshThresholdDp: Float = 70f,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { refreshThresholdDp.dp.toPx() }
    var pullDistance by remember { mutableFloatStateOf(0f) }

    val animatedOffset by animateFloatAsState(
        targetValue = if (isRefreshing) thresholdPx * 0.75f else pullDistance,
        animationSpec = tween(durationMillis = 220),
        label = "pull_offset"
    )

    val isDark = MaterialTheme.colorScheme.background.red < 0.5f

    val nestedScrollConnection = remember(isRefreshing) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 用户向上滑动回退拉取距离
                if (available.y < 0 && pullDistance > 0) {
                    val consumed = available.y.coerceAtLeast(-pullDistance)
                    pullDistance += consumed
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // 在顶部且向下拉动时累积下拉偏移（使用 0.52 阻尼系数）
                if (source == NestedScrollSource.Drag && available.y > 0) {
                    val newPull = pullDistance + available.y * 0.52f
                    pullDistance = newPull.coerceIn(0f, thresholdPx * 1.5f)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pullDistance >= thresholdPx) {
                    if (!isRefreshing) {
                        onRefresh()
                    }
                }
                pullDistance = 0f
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier = modifier
            .nestedScroll(nestedScrollConnection)
            .fillMaxSize()
    ) {
        // 内容包裹并带平滑偏移
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = with(density) { (animatedOffset * 0.55f).toDp() })
        ) {
            content()
        }

        // 下拉刷新指示器胶囊
        if (animatedOffset > 8f || isRefreshing) {
            val progress = (animatedOffset / thresholdPx).coerceIn(0f, 1f)
            Surface(
                shape = CircleShape,
                color = if (isDark) Color(0xFF222228).copy(alpha = 0.95f) else Color(0xFFFFFFFF).copy(alpha = 0.95f),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = with(density) { (animatedOffset * 0.5f - 16f).coerceAtLeast(12f).toDp() })
                    .size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            color = AppleRed,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "下拉刷新",
                            tint = AppleRed,
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(progress * 360f)
                        )
                    }
                }
            }
        }
    }
}
