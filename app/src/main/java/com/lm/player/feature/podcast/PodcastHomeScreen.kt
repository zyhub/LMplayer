package com.lm.player.feature.podcast

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.PullToRefreshLayout
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.podcast.*
import kotlinx.coroutines.launch

/**
 * 播客主页
 *
 * 包含：顶栏操作区、继续收听、我的订阅（横滑封面）、最新单集（纵向列表）、下拉刷新以及 5 种状态页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastHomeScreen(
    ui: PodcastHomeUi,
    actions: PodcastActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showMoreMenu by remember { mutableStateOf(false) }

    // 液态玻璃：顶栏放进上层玻璃（与资料库列表页同一套），列表铺满到屏幕顶部、从玻璃下面穿过，滚动后收成小胶囊
    val podcastGlass = com.lm.player.core.designsystem.glass.rememberLiquidGlassActive()
    val podcastTopInset = com.lm.player.core.designsystem.glass.rememberGlassListTopInset()
    val homeListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val podcastScope = rememberCoroutineScope()
    val homeCollapsed = com.lm.player.core.designsystem.glass.rememberTopBarCollapsed(homeListState) {
        homeListState.firstVisibleItemIndex to homeListState.firstVisibleItemScrollOffset
    }
    val homeBarActions: @Composable RowScope.() -> Unit = {
                    // 搜索按钮
                    IconButton(onClick = actions.onOpenSearch) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "搜索播客",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    // 醒目的「添加 RSS」快捷按钮
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = AppleRed.copy(alpha = 0.12f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(onClick = actions.onOpenManage)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.RssFeed,
                                contentDescription = null,
                                tint = AppleRed,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "添加RSS",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppleRed
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // 更多操作
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "更多",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }

                        com.lm.player.core.designsystem.glass.AdaptiveDropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("管理所有订阅") },
                                leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                                onClick = {
                                    showMoreMenu = false
                                    actions.onOpenManage()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("全部拉取最新") },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                onClick = {
                                    showMoreMenu = false
                                    actions.onFetchAllShows()
                                }
                            )
                        }
                    }
                }
    if (podcastGlass) {
        com.lm.player.core.designsystem.glass.GlassListHeader(
            key = "podcast_home",
            title = "播客",
            subtitle = when {
                ui.refreshProgressText.isNotBlank() -> ui.refreshProgressText
                ui.shows.isNotEmpty() -> "我的订阅 · ${ui.shows.size} 个节目"
                else -> "订阅并收听你喜欢的节目"
            },
            onBack = actions.onBack,
            collapsed = homeCollapsed && ui.state == PodcastLoadState.READY,
            onTapCapsule = { podcastScope.launch { homeListState.animateScrollToItem(0) } },
            capsuleInfo = if (ui.shows.isNotEmpty()) "${ui.shows.size} 个订阅" else "",
            actions = homeBarActions
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "播客",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        if (ui.refreshProgressText.isNotBlank()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = ui.refreshProgressText,
                                fontSize = 11.5.sp,
                                color = AppleRed,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
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
                actions = homeBarActions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        PullToRefreshLayout(
            isRefreshing = ui.isRefreshing,
            onRefresh = actions.onRefresh,
            indicatorTopPadding = podcastTopInset,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (ui.state) {
                PodcastLoadState.LOADING -> {
                    PodcastLoadingSkeleton(modifier = Modifier.fillMaxSize().padding(top = podcastTopInset))
                }
                PodcastLoadState.EMPTY -> {
                    PodcastHomeEmptyView(
                        onOpenSearch = actions.onOpenSearch,
                        onOpenManage = actions.onOpenManage,
                        modifier = Modifier.fillMaxSize().padding(top = podcastTopInset)
                    )
                }
                PodcastLoadState.NO_SERVER -> {
                    PodcastStateNoticeView(
                        icon = Icons.Outlined.ErrorOutline,
                        title = "未连接柠檬服务端",
                        message = ui.message.ifBlank { "播客功能依赖柠檬音乐服务端存储订阅源并解析音频地址，请先连接并登录服务器。" },
                        primaryButtonText = "去设置服务器",
                        onPrimaryClick = actions.onGoToServerSettings,
                        secondaryButtonText = "重试",
                        onSecondaryClick = actions.onRetry,
                        modifier = Modifier.fillMaxSize().padding(top = podcastTopInset)
                    )
                }
                PodcastLoadState.UNSUPPORTED -> {
                    PodcastStateNoticeView(
                        icon = Icons.Outlined.ErrorOutline,
                        title = "服务端版本过旧",
                        message = ui.message.ifBlank { "当前连接的柠檬音乐服务器尚未支持播客接口 (/api/podcasts)，请升级服务器端。" },
                        primaryButtonText = "重试",
                        onPrimaryClick = actions.onRetry,
                        modifier = Modifier.fillMaxSize().padding(top = podcastTopInset)
                    )
                }
                PodcastLoadState.ERROR -> {
                    PodcastStateNoticeView(
                        icon = Icons.Outlined.ErrorOutline,
                        title = "加载失败",
                        message = ui.message.ifBlank { "网络开小差了，无法加载播客内容" },
                        primaryButtonText = "重试",
                        onPrimaryClick = actions.onRetry,
                        modifier = Modifier.fillMaxSize().padding(top = podcastTopInset)
                    )
                }
                PodcastLoadState.READY -> {
                    if (isLandscape) {
                        PodcastHomeLandscapeContent(
                            ui = ui,
                            actions = actions,
                            contentPadding = contentPadding,
                            listState = homeListState,
                            topInset = podcastTopInset
                        )
                    } else {
                        PodcastHomePortraitContent(
                            ui = ui,
                            actions = actions,
                            contentPadding = contentPadding,
                            listState = homeListState,
                            topInset = podcastTopInset
                        )
                    }
                }
            }
        }
    }
}

/**
 * 竖屏主内容编排
 */
@Composable
private fun PodcastHomePortraitContent(
    ui: PodcastHomeUi,
    actions: PodcastActions,
    contentPadding: PaddingValues,
    listState: androidx.compose.foundation.lazy.LazyListState,
    topInset: androidx.compose.ui.unit.Dp
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = 8.dp + topInset,
            bottom = contentPadding.calculateBottomPadding() + 80.dp,
            start = 16.dp,
            end = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 1. 继续收听 (当且仅当列表非空时展示)
        if (ui.continueListening.isNotEmpty()) {
            item {
                ContinueListeningSection(
                    episodes = ui.continueListening,
                    onPlay = actions.onPlayEpisode,
                    onOpen = actions.onOpenEpisode
                )
            }
        }

        // 2. 我的订阅 (横滑封面列)
        if (ui.shows.isNotEmpty()) {
            item {
                MySubscriptionsSection(
                    shows = ui.shows,
                    onOpenShow = actions.onOpenShow,
                    onOpenManage = actions.onOpenManage
                )
            }
        }

        // 3. 最新单集 (纵向列表)
        if (ui.latestEpisodes.isNotEmpty()) {
            item {
                Text(
                    text = "最新单集",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            items(ui.latestEpisodes, key = { it.id }) { episode ->
                PodcastEpisodeRow(
                    episode = episode,
                    onPlayClick = { actions.onPlayEpisode(episode) },
                    onClick = { actions.onOpenEpisode(episode) },
                    showShowTitle = true,
                    onMarkPlayed = { played -> actions.onMarkPlayed(episode, played) },
                    onOpenDetail = { actions.onOpenEpisode(episode) }
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    thickness = 0.6.dp
                )
            }
        }
    }
}

/**
 * 横屏双栏自适应内容编排 (左栏订阅与继续收听，右栏最新单集，高度绝不挤出)
 */
@Composable
private fun PodcastHomeLandscapeContent(
    ui: PodcastHomeUi,
    actions: PodcastActions,
    contentPadding: PaddingValues,
    listState: androidx.compose.foundation.lazy.LazyListState,
    topInset: androidx.compose.ui.unit.Dp
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 左栏：继续收听 + 订阅源 (占 38% 宽)
        LazyColumn(
            modifier = Modifier
                .weight(0.38f)
                .fillMaxHeight(),
            contentPadding = PaddingValues(top = 8.dp + topInset, bottom = contentPadding.calculateBottomPadding() + 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (ui.continueListening.isNotEmpty()) {
                item {
                    ContinueListeningSection(
                        episodes = ui.continueListening,
                        onPlay = actions.onPlayEpisode,
                        onOpen = actions.onOpenEpisode
                    )
                }
            }

            item {
                MySubscriptionsSection(
                    shows = ui.shows,
                    onOpenShow = actions.onOpenShow,
                    onOpenManage = actions.onOpenManage
                )
            }
        }

        // 分割线
        VerticalDivider(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            thickness = 0.8.dp,
            modifier = Modifier.fillMaxHeight().padding(vertical = 8.dp)
        )

        // 右栏：最新单集纵向滚动列表 (占 62% 宽)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(0.62f)
                .fillMaxHeight(),
            contentPadding = PaddingValues(top = 8.dp + topInset, bottom = contentPadding.calculateBottomPadding() + 40.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                Text(
                    text = "最新单集",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            items(ui.latestEpisodes, key = { it.id }) { episode ->
                PodcastEpisodeRow(
                    episode = episode,
                    onPlayClick = { actions.onPlayEpisode(episode) },
                    onClick = { actions.onOpenEpisode(episode) },
                    showShowTitle = true,
                    onMarkPlayed = { played -> actions.onMarkPlayed(episode, played) },
                    onOpenDetail = { actions.onOpenEpisode(episode) }
                )
            }
        }
    }
}

/**
 * 继续收听横向卡片区块
 */
@Composable
private fun ContinueListeningSection(
    episodes: List<PodcastEpisodeUi>,
    onPlay: (PodcastEpisodeUi) -> Unit,
    onOpen: (PodcastEpisodeUi) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "继续收听",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(episodes, key = { it.id }) { episode ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 1.dp,
                    modifier = Modifier
                        .width(260.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onOpen(episode) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AlbumArtworkImage(
                            model = episode.imageUrl,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            cornerRadius = 10.dp,
                            seedId = episode.id,
                            targetSize = 160
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = episode.title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${episode.showTitle} · ${episode.remainingText}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            com.lm.player.core.designsystem.component.ClassicLinearProgress(
                                progress = { episode.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = AppleRed,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = { onPlay(episode) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(AppleRed),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (episode.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = "播放",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 我的订阅区块 (横向滑动封面与更新角标)
 */
@Composable
private fun MySubscriptionsSection(
    shows: List<PodcastShowUi>,
    onOpenShow: (String) -> Unit,
    onOpenManage: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "我的订阅",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "(${shows.size})",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TextButton(
                onClick = onOpenManage,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "管理",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppleRed
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = AppleRed,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(shows, key = { it.id }) { show ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(76.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenShow(show.id) }
                ) {
                    Box(modifier = Modifier.size(76.dp)) {
                        AlbumArtworkImage(
                            model = show.imageUrl,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(14.dp)),
                            cornerRadius = 14.dp,
                            seedId = show.id,
                            targetSize = 200
                        )

                        // 错误微警示红点/角标
                        if (show.errorText.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE65100)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PriorityHigh,
                                    contentDescription = "抓取失败",
                                    tint = Color.White,
                                    modifier = Modifier.size(10.dp)
                                )
                            }
                        } else if (show.newEpisodeCount > 0) {
                            // 新更新集数角标
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AppleRed)
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = if (show.newEpisodeCount > 99) "99+" else "${show.newEpisodeCount}",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = show.title,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * 骨架屏脉冲加载态
 */
@Composable
private fun PodcastLoadingSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 头部模拟
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        )

        // 订阅封面行骨架
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(4) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 列表行骨架
        repeat(5) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.4f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    )
                }
            }
        }
    }
}

/**
 * 主页无订阅引导空态
 */
@Composable
private fun PodcastHomeEmptyView(
    onOpenSearch: () -> Unit,
    onOpenManage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(AppleRed.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Podcasts,
                contentDescription = null,
                tint = AppleRed,
                modifier = Modifier.size(38.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "还没有订阅任何播客",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "您可以从 Apple 播客公开目录中搜索喜爱的内容，或者直接粘贴第三方 RSS / Atom 链接添加订阅。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onOpenManage,
                shape = RoundedCornerShape(20.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("粘贴 RSS")
            }

            Button(
                onClick = onOpenSearch,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppleRed)
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("搜索播客")
            }
        }
    }
}

/**
 * 状态提示页（无服务器、版本过旧、错误）
 */
@Composable
fun PodcastStateNoticeView(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    primaryButtonText: String,
    onPrimaryClick: () -> Unit,
    secondaryButtonText: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (secondaryButtonText != null && onSecondaryClick != null) {
                OutlinedButton(
                    onClick = onSecondaryClick,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(secondaryButtonText)
                }
            }

            Button(
                onClick = onPrimaryClick,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppleRed)
            ) {
                Text(primaryButtonText)
            }
        }
    }
}

// ==========================================
// 4 组预览
// ==========================================

@Preview(name = "播客主页·竖屏浅色", showBackground = true)
@Composable
private fun PodcastHomeScreenPreviewLight() {
    MaterialTheme {
        PodcastHomeScreen(
            ui = PodcastPreviewData.sampleHomeUi,
            actions = PodcastActions()
        )
    }
}

@Preview(name = "播客主页·竖屏深色", showBackground = true, backgroundColor = 0xFF101014)
@Composable
private fun PodcastHomeScreenPreviewDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        PodcastHomeScreen(
            ui = PodcastPreviewData.sampleHomeUi,
            actions = PodcastActions()
        )
    }
}

@Preview(name = "播客主页·横屏浅色", widthDp = 800, heightDp = 360, showBackground = true)
@Composable
private fun PodcastHomeScreenPreviewLandscapeLight() {
    MaterialTheme {
        PodcastHomeScreen(
            ui = PodcastPreviewData.sampleHomeUi,
            actions = PodcastActions()
        )
    }
}

@Preview(name = "播客主页·横屏深色", widthDp = 800, heightDp = 360, showBackground = true, backgroundColor = 0xFF101014)
@Composable
private fun PodcastHomeScreenPreviewLandscapeDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        PodcastHomeScreen(
            ui = PodcastPreviewData.sampleHomeUi,
            actions = PodcastActions()
        )
    }
}
