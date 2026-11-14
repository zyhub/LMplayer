package com.lm.player.feature.podcast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
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
 * 播客订阅管理页
 *
 * 核心管理入口：直接向服务器添加/删除 RSS 订阅。
 * 特性：快捷从剪贴板粘贴、URL 格式校验、拉取等待状态、二次确认防误删、批量删除模式与 RSS 地址一键复制。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastManageScreen(
    ui: PodcastManageUi,
    actions: PodcastActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val clipboardManager = LocalClipboardManager.current
    var isBatchMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showToDelete by remember { mutableStateOf<PodcastShowUi?>(null) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    // 输入框状态
    var rssInput by remember { mutableStateOf("") }
    val isValidRssUrl = remember(rssInput) { looksLikeFeedUrl(rssInput) }

    // 添加成功后清空输入框（Claude 接线时补充）
    LaunchedEffect(ui.lastAddedTitle) {
        if (ui.lastAddedTitle.isNotBlank()) rssInput = ""
    }

    // 单项删除二次确认对话框
    showToDelete?.let { targetShow ->
        AlertDialog(
            onDismissRequest = { showToDelete = null },
            title = {
                Text(
                    text = "确认从服务器删除？",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Text(
                    text = "确定要从服务器删除订阅「${targetShow.title}」吗？\n该节目的所有单集与您的本地收听记录将一并移除。",
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = targetShow.id
                        showToDelete = null
                        actions.onDeleteShows(listOf(id))
                    }
                ) {
                    Text("确认删除", color = AppleRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 批量删除二次确认对话框
    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = {
                Text(
                    text = "确认批量删除选中订阅？",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Text(
                    text = "确定要从服务器删除选中的 ${selectedIds.size} 个订阅源吗？\n相关节目的单集列表与收听记录都将被彻底移除。",
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val idsToDelete = selectedIds.toList()
                        showBatchDeleteConfirm = false
                        selectedIds = emptySet()
                        isBatchMode = false
                        actions.onDeleteShows(idsToDelete)
                    }
                ) {
                    Text("确认删除 (${selectedIds.size})", color = AppleRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 液态玻璃：顶栏放进上层玻璃（与播客首页 / 节目页同一套），列表从玻璃下面穿过，滚动后收成小胶囊
    val podcastGlass = com.lm.player.core.designsystem.glass.rememberLiquidGlassActive()
    val podcastTopInset = com.lm.player.core.designsystem.glass.rememberGlassListTopInset()
    val manageListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val podcastScope = rememberCoroutineScope()
    val manageCollapsed = com.lm.player.core.designsystem.glass.rememberTopBarCollapsed(manageListState) {
        manageListState.firstVisibleItemIndex to manageListState.firstVisibleItemScrollOffset
    }
    val manageBarActions: @Composable RowScope.() -> Unit = {
                    if (ui.shows.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                isBatchMode = !isBatchMode
                                if (!isBatchMode) selectedIds = emptySet()
                            }
                        ) {
                            Text(
                                text = if (isBatchMode) "完成" else "批量管理",
                                color = if (isBatchMode) AppleRed else MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.5.sp
                            )
                        }
                    }
                }
    if (podcastGlass) {
        com.lm.player.core.designsystem.glass.GlassListHeader(
            key = "podcast_manage",
            title = "管理订阅",
            subtitle = "共 ${ui.shows.size} 个 RSS 订阅源",
            onBack = actions.onBack,
            collapsed = manageCollapsed && !isBatchMode,
            onTapCapsule = { podcastScope.launch { manageListState.animateScrollToItem(0) } },
            capsuleInfo = "${ui.shows.size} 个订阅",
            actions = manageBarActions
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = if (podcastGlass) ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (!podcastGlass) TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "管理订阅",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "共 ${ui.shows.size} 个 RSS 订阅源",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                actions = manageBarActions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            // 批量删除浮动底栏
            AnimatedVisibility(
                visible = isBatchMode && selectedIds.isNotEmpty(),
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "已选中 ${selectedIds.size} 项",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Button(
                            onClick = { showBatchDeleteConfirm = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("删除选中项", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            state = manageListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                top = 8.dp + podcastTopInset,
                bottom = contentPadding.calculateBottomPadding() + 80.dp,
                start = 16.dp,
                end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 成功 / 错误提示条
            if (ui.lastAddedTitle.isNotBlank()) {
                item {
                    SuccessNoticeBanner(
                        title = "已成功添加订阅：${ui.lastAddedTitle}",
                        onDismiss = actions.onDismissManageMessage
                    )
                }
            }

            if (ui.addError.isNotBlank()) {
                item {
                    ErrorNoticeBanner(
                        title = "添加失败",
                        message = ui.addError,
                        onDismiss = actions.onDismissManageMessage
                    )
                }
            }

            if (ui.deleteError.isNotBlank()) {
                item {
                    ErrorNoticeBanner(
                        title = "删除失败",
                        message = ui.deleteError,
                        onDismiss = actions.onDismissManageMessage
                    )
                }
            }

            // 2. 常驻快捷添加 RSS 卡片
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.RssFeed,
                                    contentDescription = null,
                                    tint = AppleRed,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "添加 RSS 订阅",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = "直连服务端解析",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // URL 输入框与一键粘贴
                        OutlinedTextField(
                            value = rssInput,
                            onValueChange = {
                                rssInput = it
                                if (ui.addError.isNotBlank()) actions.onDismissManageMessage()
                            },
                            placeholder = {
                                Text(
                                    text = "输入以 http:// 或 https:// 开头的 RSS 链接",
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            trailingIcon = {
                                TextButton(
                                    onClick = {
                                        val clipText = clipboardManager.getText()?.text.orEmpty().trim()
                                        if (clipText.isNotBlank()) {
                                            rssInput = clipText
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("一键粘贴", fontSize = 11.5.sp, color = AppleRed)
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AppleRed,
                                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 格式微提示
                        if (rssInput.isNotBlank() && !isValidRssUrl) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "⚠️ 请输入有效的 http:// 或 https:// 播客 RSS 订阅链接",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                actions.onAddRss(rssInput.trim())
                            },
                            enabled = isValidRssUrl && !ui.isAdding,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (ui.isAdding) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("服务器解析中 (可能需要几十秒)…", fontSize = 13.sp)
                            } else {
                                Text("确认添加订阅", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 3. 订阅源列表
            item {
                Text(
                    text = "已订阅节目 (${ui.shows.size})",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            if (ui.state == PodcastLoadState.LOADING && ui.shows.isEmpty()) {
                // 加载 / 无服务器 / 出错状态（Claude 接线时补充）
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = AppleRed)
                    }
                }
            } else if (ui.state == PodcastLoadState.NO_SERVER || ui.state == PodcastLoadState.UNSUPPORTED ||
                ui.state == PodcastLoadState.ERROR
            ) {
                item {
                    PodcastStateNoticeView(
                        icon = Icons.Outlined.ErrorOutline,
                        title = when (ui.state) {
                            PodcastLoadState.NO_SERVER -> "未连接柠檬服务端"
                            PodcastLoadState.UNSUPPORTED -> "服务端版本过旧"
                            else -> "加载失败"
                        },
                        message = ui.message.ifBlank { "无法读取服务器上的订阅列表" },
                        primaryButtonText = "重试",
                        onPrimaryClick = actions.onRetry,
                        secondaryButtonText = if (ui.state == PodcastLoadState.NO_SERVER) "去设置服务器" else null,
                        onSecondaryClick = if (ui.state == PodcastLoadState.NO_SERVER) actions.onGoToServerSettings else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else if (ui.state == PodcastLoadState.EMPTY || ui.shows.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "服务器上暂无订阅源，快在上方添加你的第一个播客吧！",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(ui.shows, key = { it.id }) { show ->
                    ManageShowRow(
                        show = show,
                        isBatchMode = isBatchMode,
                        isSelected = selectedIds.contains(show.id),
                        onToggleSelect = {
                            selectedIds = if (selectedIds.contains(show.id)) {
                                selectedIds - show.id
                            } else {
                                selectedIds + show.id
                            }
                        },
                        onOpenShow = { actions.onOpenShow(show.id) },
                        onFetchShow = { actions.onFetchShow(show.id) },
                        onDeleteClick = { showToDelete = show },
                        onCopyFeedUrl = { url ->
                            clipboardManager.setText(AnnotatedString(url))
                        }
                    )
                }
            }
        }
    }
}

/**
 * 订阅源单项行
 */
@Composable
private fun ManageShowRow(
    show: PodcastShowUi,
    isBatchMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onOpenShow: () -> Unit,
    onFetchShow: () -> Unit,
    onDeleteClick: () -> Unit,
    onCopyFeedUrl: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 批量勾选框
            if (isBatchMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(checkedColor = AppleRed),
                    modifier = Modifier.padding(end = 6.dp)
                )
            }

            // 封面
            AlbumArtworkImage(
                model = show.imageUrl,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenShow),
                cornerRadius = 10.dp,
                seedId = show.id,
                targetSize = 160
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = show.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onOpenShow)
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = listOf(
                        if (show.author.isNotBlank()) show.author else null,
                        "共${show.episodeCount}期",
                        if (show.updatedText.isNotBlank()) show.updatedText else null
                    ).filterNotNull().joinToString(" · "),
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // RSS 地址展示行 (点击复制)
                if (show.feedUrl.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onCopyFeedUrl(show.feedUrl) }
                            .padding(vertical = 1.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "复制链接",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = show.feedUrl,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // 抓取失败警示
                if (show.errorText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "⚠️ ${show.errorText}",
                        fontSize = 10.5.sp,
                        color = Color(0xFFE65100),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 右侧快捷操作
            if (!isBatchMode) {
                if (show.isDeleting) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.error,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (show.isRefreshing) {
                            CircularProgressIndicator(
                                color = AppleRed,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            IconButton(onClick = onFetchShow, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "拉取最新",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        IconButton(onClick = onDeleteClick, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "删除订阅",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuccessNoticeBanner(title: String, onDismiss: () -> Unit) {
    Surface(
        color = Color(0xFFDEF7EC),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color(0xFF03543F),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF03543F)
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color(0xFF03543F), modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun ErrorNoticeBanner(title: String, message: String, onDismiss: () -> Unit) {
    Surface(
        color = Color(0xFFFDE8E8),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF9B1C1C)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = message,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = Color(0xFFC81E1E)
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color(0xFF9B1C1C), modifier = Modifier.size(14.dp))
            }
        }
    }
}

// ==========================================
// 4 组预览
// ==========================================

@Preview(name = "管理订阅·竖屏浅色", showBackground = true)
@Composable
private fun PodcastManageScreenPreviewLight() {
    MaterialTheme {
        PodcastManageScreen(
            ui = PodcastPreviewData.sampleManageUi,
            actions = PodcastActions()
        )
    }
}

@Preview(name = "管理订阅·竖屏深色", showBackground = true, backgroundColor = 0xFF101014)
@Composable
private fun PodcastManageScreenPreviewDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        PodcastManageScreen(
            ui = PodcastPreviewData.sampleManageUi,
            actions = PodcastActions()
        )
    }
}

@Preview(name = "管理订阅·横屏浅色", widthDp = 800, heightDp = 360, showBackground = true)
@Composable
private fun PodcastManageScreenPreviewLandscapeLight() {
    MaterialTheme {
        PodcastManageScreen(
            ui = PodcastPreviewData.sampleManageUi,
            actions = PodcastActions()
        )
    }
}

@Preview(name = "管理订阅·横屏深色", widthDp = 800, heightDp = 360, showBackground = true, backgroundColor = 0xFF101014)
@Composable
private fun PodcastManageScreenPreviewLandscapeDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        PodcastManageScreen(
            ui = PodcastPreviewData.sampleManageUi,
            actions = PodcastActions()
        )
    }
}
