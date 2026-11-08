package com.lm.player.feature.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.podcast.*

/**
 * 搜索与直链添加订阅页
 *
 * 具备双模输入识别：输入节目名触发 Apple 播客公开库搜索；输入 http(s) 链接自动转换为直连订阅 RSS。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastSearchScreen(
    ui: PodcastSearchUi,
    actions: PodcastActions,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    val isFeedUrl = remember(query) { looksLikeFeedUrl(query) }
    val keyboardController = LocalSoftwareKeyboardController.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    // 输入框搜索栏
                    TextField(
                        value = query,
                        onValueChange = {
                            query = it
                            if (it.isBlank()) {
                                actions.onClearSearch()
                            }
                        },
                        placeholder = {
                            Text(
                                text = "搜索播客名，或直接粘贴 RSS 链接",
                                fontSize = 13.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(20.dp),
                        trailingIcon = {
                            if (query.isNotBlank()) {
                                IconButton(onClick = {
                                    query = ""
                                    actions.onClearSearch()
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "清空",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            imeAction = if (isFeedUrl) ImeAction.Done else ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                if (query.isNotBlank()) {
                                    keyboardController?.hide()
                                    actions.onSearch(query.trim())
                                }
                            },
                            onDone = {
                                if (isFeedUrl) {
                                    keyboardController?.hide()
                                    actions.onSubscribeUrl(query.trim())
                                }
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
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
                actions = {
                    // 动态主操作按钮：输入 URL 时为「订阅此 RSS」，输入文字时为「搜索」
                    if (isFeedUrl) {
                        Button(
                            onClick = {
                                keyboardController?.hide()
                                actions.onSubscribeUrl(query.trim())
                            },
                            enabled = !ui.isSubscribingUrl,
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            if (ui.isSubscribingUrl) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("解析中…", fontSize = 12.sp)
                            } else {
                                Text("订阅此RSS", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else if (query.isNotBlank()) {
                        TextButton(
                            onClick = {
                                keyboardController?.hide()
                                actions.onSearch(query.trim())
                            },
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text("搜索", color = AppleRed, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 订阅错误醒目提示条
            if (ui.subscribeError.isNotBlank()) {
                Surface(
                    color = Color(0xFFFDE8E8),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = Color(0xFFC81E1E),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = ui.subscribeError,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp,
                            color = Color(0xFF9B1C1C)
                        )
                    }
                }
            }

            // 内容视图区域
            when (ui.state) {
                PodcastSearchState.IDLE -> {
                    SearchIdleGuide(
                        onExampleClick = { example ->
                            query = example
                            actions.onSearch(example)
                        }
                    )
                }
                PodcastSearchState.SEARCHING -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = AppleRed)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "正在检索节目目录…",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                PodcastSearchState.NO_RESULT -> {
                    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Outlined.Podcasts,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(54.dp)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "未搜索到匹配的播客节目",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "请检查搜索关键词是否有误，或者直接粘贴节目的 RSS/Atom 链接进行添加。",
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                PodcastSearchState.ERROR -> {
                    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = ui.message.ifBlank { "搜索服务暂时不可用" },
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { actions.onSearch(query.trim()) },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AppleRed)
                            ) {
                                Text("重试搜索")
                            }
                        }
                    }
                }
                PodcastSearchState.RESULTS -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(ui.results, key = { it.feedUrl }) { item ->
                            SearchResultRow(
                                item = item,
                                onSubscribe = { actions.onSubscribeResult(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 搜索初始状态说明卡片
 */
@Composable
private fun SearchIdleGuide(onExampleClick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(AppleRed.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.RssFeed,
                contentDescription = null,
                tint = AppleRed,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "探索与订阅海量播客",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "• 输入播客节目名称，即刻搜索 Apple 播客公开源；\n• 直接粘贴任何以 http:// 或 https:// 开头的第三方 RSS 订阅地址，服务端将自动为您拉取并同步单集。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "大家都在搜：",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("故事FM", "日谈公园", "机核", "声东击西").forEach { tag ->
                SuggestionChip(
                    onClick = { onExampleClick(tag) },
                    label = { Text(tag, fontSize = 11.5.sp) },
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }
    }
}

/**
 * 搜索结果单项
 */
@Composable
private fun SearchResultRow(
    item: PodcastSearchResultUi,
    onSubscribe: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlbumArtworkImage(
                model = item.imageUrl,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp)),
                cornerRadius = 10.dp,
                seedId = item.feedUrl,
                targetSize = 160
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (item.author.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.author,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.genre.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Text(
                                text = item.genre,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (item.episodeCountText.isNotBlank()) {
                        Text(
                            text = item.episodeCountText,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // 订阅状态按键
            if (item.isSubscribed) {
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("已订阅", fontSize = 11.5.sp)
                }
            } else if (item.isSubscribing) {
                Button(
                    onClick = {},
                    enabled = false,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp)
                    )
                }
            } else {
                Button(
                    onClick = onSubscribe,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("+ 订阅", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ==========================================
// 4 组预览
// ==========================================

@Preview(name = "搜索·竖屏浅色", showBackground = true)
@Composable
private fun PodcastSearchScreenPreviewLight() {
    MaterialTheme {
        PodcastSearchScreen(
            ui = PodcastPreviewData.sampleSearchUi,
            actions = PodcastActions()
        )
    }
}

@Preview(name = "搜索·竖屏深色", showBackground = true, backgroundColor = 0xFF101014)
@Composable
private fun PodcastSearchScreenPreviewDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        PodcastSearchScreen(
            ui = PodcastPreviewData.sampleSearchUi,
            actions = PodcastActions()
        )
    }
}

@Preview(name = "搜索·横屏浅色", widthDp = 800, heightDp = 360, showBackground = true)
@Composable
private fun PodcastSearchScreenPreviewLandscapeLight() {
    MaterialTheme {
        PodcastSearchScreen(
            ui = PodcastPreviewData.sampleSearchUi,
            actions = PodcastActions()
        )
    }
}

@Preview(name = "搜索·横屏深色", widthDp = 800, heightDp = 360, showBackground = true, backgroundColor = 0xFF101014)
@Composable
private fun PodcastSearchScreenPreviewLandscapeDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        PodcastSearchScreen(
            ui = PodcastPreviewData.sampleSearchUi,
            actions = PodcastActions()
        )
    }
}
