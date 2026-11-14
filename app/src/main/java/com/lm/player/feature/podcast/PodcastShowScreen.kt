package com.lm.player.feature.podcast

import android.content.res.Configuration
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.podcast.*
import kotlinx.coroutines.launch

/**
 * 节目详情页
 *
 * 包含：大封面头部、主播/更新信息、折叠/展开简介、主播放按钮（智能续播提示）、
 * 拉取最新、取消订阅二次确认、排序/筛选工具栏、自动分页单集列表与横屏适配。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastShowScreen(
    ui: PodcastShowDetailUi,
    actions: PodcastActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showUnsubscribeConfirmDialog by remember { mutableStateOf(false) }

    // 取消订阅二次确认对话框 (防手抖误触)
    if (showUnsubscribeConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showUnsubscribeConfirmDialog = false },
            title = {
                Text(
                    text = "确认取消订阅？",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Text(
                    text = "确定要取消订阅「${ui.show.title}」吗？\n该节目的单集列表与您的本地收听记录将一并移除。",
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnsubscribeConfirmDialog = false
                        actions.onUnsubscribe(ui.show.id)
                    }
                ) {
                    Text("取消订阅", color = AppleRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnsubscribeConfirmDialog = false }) {
                    Text("保留")
                }
            }
        )
    }

    // 液态玻璃：顶栏放进上层玻璃（与资料库列表页同一套），单集列表从玻璃下面穿过，滚动后收成「返回 + 节目名 · 集数」小胶囊
    val podcastGlass = com.lm.player.core.designsystem.glass.rememberLiquidGlassActive()
    val podcastTopInset = com.lm.player.core.designsystem.glass.rememberGlassListTopInset()
    val episodesListState = rememberLazyListState()
    val podcastScope = rememberCoroutineScope()
    val showCollapsed = com.lm.player.core.designsystem.glass.rememberTopBarCollapsed(episodesListState) {
        episodesListState.firstVisibleItemIndex to episodesListState.firstVisibleItemScrollOffset
    }
    val showBarActions: @Composable RowScope.() -> Unit = {
                    if (ui.show.isRefreshing) {
                        CircularProgressIndicator(
                            color = AppleRed,
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .size(20.dp)
                                .padding(end = 4.dp)
                        )
                    } else {
                        IconButton(onClick = { actions.onFetchShow(ui.show.id) }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "拉取最新",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
    if (podcastGlass) {
        com.lm.player.core.designsystem.glass.GlassListHeader(
            key = "podcast_show:${ui.show.id}",
            title = ui.show.title,
            subtitle = listOf(ui.show.author, "共 ${ui.totalEpisodes} 集").filter { it.isNotBlank() }.joinToString(" · "),
            onBack = actions.onBack,
            collapsed = showCollapsed,
            onTapCapsule = { podcastScope.launch { episodesListState.animateScrollToItem(0) } },
            capsuleInfo = "${ui.totalEpisodes} 集",
            actions = showBarActions
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        // 液态玻璃：顶栏在上层玻璃里，内容从屏幕最顶端开始（顶部不再让出状态栏）
        contentWindowInsets = if (podcastGlass) ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (!podcastGlass) TopAppBar(
                title = {
                    Text(
                        text = ui.show.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = showBarActions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        if (isLandscape) {
            PodcastShowLandscapeLayout(
                ui = ui,
                actions = actions,
                onConfirmUnsubscribe = { showUnsubscribeConfirmDialog = true },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = contentPadding,
                listState = episodesListState,
                topInset = podcastTopInset
            )
        } else {
            PodcastShowPortraitLayout(
                ui = ui,
                actions = actions,
                onConfirmUnsubscribe = { showUnsubscribeConfirmDialog = true },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = contentPadding,
                listState = episodesListState,
                topInset = podcastTopInset
            )
        }
    }
}

/**
 * 竖屏排版
 */
@Composable
private fun PodcastShowPortraitLayout(
    ui: PodcastShowDetailUi,
    actions: PodcastActions,
    onConfirmUnsubscribe: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    listState: androidx.compose.foundation.lazy.LazyListState,
    topInset: androidx.compose.ui.unit.Dp
) {
    // 划动到底部自动分页加载
    val shouldLoadMore by remember {
        derivedStateOf {
            val totalItemsCount = listState.layoutInfo.totalItemsCount
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItemsCount > 0 && lastVisibleIndex >= totalItemsCount - 3
        }
    }

    LaunchedEffect(shouldLoadMore, ui.canLoadMore, ui.isLoadingMore) {
        if (shouldLoadMore && ui.canLoadMore && !ui.isLoadingMore) {
            actions.onLoadMore(ui.show.id)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = 8.dp + topInset,
            bottom = contentPadding.calculateBottomPadding() + 80.dp,
            start = 16.dp,
            end = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. 头部展示：封面 + 标题 + 主播 + 展开简介
        item {
            ShowHeader(show = ui.show)
        }

        // 2. 主操作按钮行
        item {
            ShowActionButtons(
                show = ui.show,
                resumeEpisode = ui.resumeEpisode,
                onPlayResume = { ep -> actions.onPlayEpisode(ep) },
                onPlayLatest = { actions.onPlayShowLatest(ui.show.id) },
                onUnsubscribe = onConfirmUnsubscribe,
                onFetchShow = { actions.onFetchShow(ui.show.id) }
            )
        }

        // 3. 排序与筛选工具条
        item {
            ShowListToolbar(
                totalEpisodes = ui.totalEpisodes,
                sort = ui.sort,
                filter = ui.filter,
                onSortChange = { s -> actions.onSortChange(ui.show.id, s) },
                onFilterChange = { f -> actions.onFilterChange(ui.show.id, f) }
            )
        }

        // 4. 单集列表
        if (ui.state == PodcastLoadState.LOADING && ui.episodes.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AppleRed)
                }
            }
        } else if (ui.state == PodcastLoadState.EMPTY || ui.episodes.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (ui.filter == EpisodeFilter.UNPLAYED) "暂无未听完的单集" else "暂无可播放的音频单集",
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(ui.episodes, key = { it.id }) { episode ->
                PodcastEpisodeRow(
                    episode = episode,
                    onPlayClick = { actions.onPlayEpisode(episode) },
                    onClick = { actions.onOpenEpisode(episode) },
                    showShowTitle = false,
                    onMarkPlayed = { played -> actions.onMarkPlayed(episode, played) },
                    onOpenDetail = { actions.onOpenEpisode(episode) }
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    thickness = 0.6.dp
                )
            }

            // 底部加载更多指示器
            if (ui.isLoadingMore) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                color = AppleRed,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "正在加载更多单集...",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 横屏双栏自适应排版 (左侧节目信息与主控，右侧单集列表)
 */
@Composable
private fun PodcastShowLandscapeLayout(
    ui: PodcastShowDetailUi,
    actions: PodcastActions,
    onConfirmUnsubscribe: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    listState: androidx.compose.foundation.lazy.LazyListState,
    topInset: androidx.compose.ui.unit.Dp
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 左栏：大封面 + 信息 + 主按钮 (40% 宽)
        LazyColumn(
            modifier = Modifier
                .weight(0.40f)
                .fillMaxHeight(),
            contentPadding = PaddingValues(top = 8.dp + topInset, bottom = contentPadding.calculateBottomPadding() + 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                ShowHeader(show = ui.show, compact = true)
            }

            item {
                ShowActionButtons(
                    show = ui.show,
                    resumeEpisode = ui.resumeEpisode,
                    onPlayResume = { ep -> actions.onPlayEpisode(ep) },
                    onPlayLatest = { actions.onPlayShowLatest(ui.show.id) },
                    onUnsubscribe = onConfirmUnsubscribe,
                    onFetchShow = { actions.onFetchShow(ui.show.id) }
                )
            }
        }

        VerticalDivider(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            thickness = 0.8.dp,
            modifier = Modifier.fillMaxHeight().padding(vertical = 8.dp)
        )

        // 右栏：单集列表与工具条 (60% 宽)
        val shouldLoadMore by remember {
            derivedStateOf {
                val totalItemsCount = listState.layoutInfo.totalItemsCount
                val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                totalItemsCount > 0 && lastVisibleIndex >= totalItemsCount - 3
            }
        }

        LaunchedEffect(shouldLoadMore, ui.canLoadMore, ui.isLoadingMore) {
            if (shouldLoadMore && ui.canLoadMore && !ui.isLoadingMore) {
                actions.onLoadMore(ui.show.id)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(0.60f)
                .fillMaxHeight(),
            contentPadding = PaddingValues(top = 8.dp + topInset, bottom = contentPadding.calculateBottomPadding() + 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                ShowListToolbar(
                    totalEpisodes = ui.totalEpisodes,
                    sort = ui.sort,
                    filter = ui.filter,
                    onSortChange = { s -> actions.onSortChange(ui.show.id, s) },
                    onFilterChange = { f -> actions.onFilterChange(ui.show.id, f) }
                )
            }

            items(ui.episodes, key = { it.id }) { episode ->
                PodcastEpisodeRow(
                    episode = episode,
                    onPlayClick = { actions.onPlayEpisode(episode) },
                    onClick = { actions.onOpenEpisode(episode) },
                    showShowTitle = false,
                    onMarkPlayed = { played -> actions.onMarkPlayed(episode, played) },
                    onOpenDetail = { actions.onOpenEpisode(episode) }
                )
            }

            if (ui.isLoadingMore) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = AppleRed, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/**
 * 节目头部详情
 */
@Composable
private fun ShowHeader(show: PodcastShowUi, compact: Boolean = false) {
    var isDescExpanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlbumArtworkImage(
                model = show.imageUrl,
                modifier = Modifier
                    .size(if (compact) 84.dp else 104.dp)
                    .clip(RoundedCornerShape(16.dp)),
                cornerRadius = 16.dp,
                seedId = show.id,
                targetSize = 300
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = show.title,
                    fontSize = if (compact) 16.sp else 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    lineHeight = if (compact) 21.sp else 24.sp
                )

                if (show.author.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = show.author,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "共 ${show.episodeCount} 期" + if (show.updatedText.isNotBlank()) " · ${show.updatedText}" else "",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )

                if (show.errorText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = show.errorText,
                        fontSize = 11.sp,
                        color = Color(0xFFE65100),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // 简介 (可折叠/展开)
        if (show.description.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { isDescExpanded = !isDescExpanded }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = show.description,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (isDescExpanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isDescExpanded) "收起" else "展开简介",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppleRed
                    )
                }
            }
        }
    }
}

/**
 * 播放与管理主操作按键
 */
@Composable
private fun ShowActionButtons(
    show: PodcastShowUi,
    resumeEpisode: PodcastEpisodeUi?,
    onPlayResume: (PodcastEpisodeUi) -> Unit,
    onPlayLatest: () -> Unit,
    onUnsubscribe: () -> Unit,
    onFetchShow: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 主播放大按钮
        Button(
            onClick = {
                if (resumeEpisode != null) {
                    onPlayResume(resumeEpisode)
                } else {
                    onPlayLatest()
                }
            },
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (resumeEpisode != null) {
                    if (resumeEpisode.remainingText.isNotBlank()) "继续收听 (${resumeEpisode.remainingText})" else "继续收听"
                } else {
                    "播放最新一期"
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 取消订阅次按钮
        OutlinedButton(
            onClick = onUnsubscribe,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
        ) {
            Text("取消订阅", fontSize = 12.sp)
        }
    }
}

/**
 * 单集排序与过滤工具条
 */
@Composable
private fun ShowListToolbar(
    totalEpisodes: Int,
    sort: EpisodeSort,
    filter: EpisodeFilter,
    onSortChange: (EpisodeSort) -> Unit,
    onFilterChange: (EpisodeFilter) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "共 $totalEpisodes 期",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 排序切换 (最新 / 最早)
            FilterChip(
                selected = sort == EpisodeSort.OLDEST_FIRST,
                onClick = {
                    val next = if (sort == EpisodeSort.NEWEST_FIRST) EpisodeSort.OLDEST_FIRST else EpisodeSort.NEWEST_FIRST
                    onSortChange(next)
                },
                label = { Text(sort.label, fontSize = 11.5.sp) },
                shape = RoundedCornerShape(12.dp)
            )

            // 过滤切换 (全部 / 未听完)
            FilterChip(
                selected = filter == EpisodeFilter.UNPLAYED,
                onClick = {
                    val next = if (filter == EpisodeFilter.ALL) EpisodeFilter.UNPLAYED else EpisodeFilter.ALL
                    onFilterChange(next)
                },
                label = { Text(filter.label, fontSize = 11.5.sp) },
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

// ==========================================
// 4 组预览
// ==========================================

@Preview(name = "节目详情·竖屏浅色", showBackground = true)
@Composable
private fun PodcastShowScreenPreviewLight() {
    MaterialTheme {
        PodcastShowScreen(
            ui = PodcastPreviewData.sampleShowDetailUi,
            actions = PodcastActions()
        )
    }
}

@Preview(name = "节目详情·竖屏深色", showBackground = true, backgroundColor = 0xFF101014)
@Composable
private fun PodcastShowScreenPreviewDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        PodcastShowScreen(
            ui = PodcastPreviewData.sampleShowDetailUi,
            actions = PodcastActions()
        )
    }
}

@Preview(name = "节目详情·横屏浅色", widthDp = 800, heightDp = 360, showBackground = true)
@Composable
private fun PodcastShowScreenPreviewLandscapeLight() {
    MaterialTheme {
        PodcastShowScreen(
            ui = PodcastPreviewData.sampleShowDetailUi,
            actions = PodcastActions()
        )
    }
}

@Preview(name = "节目详情·横屏深色", widthDp = 800, heightDp = 360, showBackground = true, backgroundColor = 0xFF101014)
@Composable
private fun PodcastShowScreenPreviewLandscapeDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        PodcastShowScreen(
            ui = PodcastPreviewData.sampleShowDetailUi,
            actions = PodcastActions()
        )
    }
}
