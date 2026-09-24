package com.lm.player.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlin.math.abs

import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest

// Apple Music 经典质感渐变调色板
val AppleGradients = listOf(
    listOf(Color(0xFFFA233B), Color(0xFFFF5E3A)), // Apple Red -> Sunset Orange
    listOf(Color(0xFF5856D6), Color(0xFFAF52DE)), // Indigo -> Purple
    listOf(Color(0xFFFF2D55), Color(0xFFFF375F)), // Pink Magenta
    listOf(Color(0xFF007AFF), Color(0xFF5AC8FA)), // Apple Blue -> Cyan
    listOf(Color(0xFFFF9500), Color(0xFFFFCC00)), // Amber Orange
    listOf(Color(0xFF34C759), Color(0xFF30D158)), // Emerald Mint
    listOf(Color(0xFF1D1D1F), Color(0xFF3A3A3C))  // Obsidian Dark
)

fun getGradientForId(id: String): List<Color> {
    val index = abs(id.hashCode()) % AppleGradients.size
    return AppleGradients[index]
}

/**
 * 高性能专辑/歌曲封面组件 (限制解码尺寸杜绝车机内存溢出与 GC 卡顿)
 */
@Composable
fun AlbumArtworkImage(
    model: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    shadowElevation: Dp = 0.dp,
    contentScale: ContentScale = ContentScale.Crop,
    seedId: String = "",
    targetSize: Int = 160 // 默认按列表高保真 160px 解码，大幅减少 75% 显存
) {
    val cleanModel = remember(model) {
        if (model.isNullOrBlank()) null
        else {
            val trimmed = model.trim()
            if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
        }
    }
    val gradientColors = remember(seedId, cleanModel) {
        getGradientForId(if (seedId.isNotBlank()) seedId else (cleanModel ?: "seed"))
    }
    val context = LocalContext.current

    Box(
        modifier = modifier
            .then(if (shadowElevation > 0.dp) Modifier.shadow(shadowElevation, RoundedCornerShape(cornerRadius)) else Modifier)
            .clip(RoundedCornerShape(cornerRadius))
            .background(Brush.linearGradient(gradientColors)),
        contentAlignment = Alignment.Center
    ) {
        if (!cleanModel.isNullOrBlank()) {
            AsyncImage(
                model = remember(cleanModel, targetSize) {
                    ImageRequest.Builder(context)
                        .data(cleanModel)
                        .size(targetSize, targetSize)
                        .crossfade(false)
                        .build()
                },
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            DefaultMusicPlaceholder(colors = gradientColors)
        }
    }
}

/**
 * 高性能歌单封面组件
 */
@Composable
fun PlaylistArtworkImage(
    title: String = "",
    model: String? = null,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    seedId: String = "",
    targetSize: Int = 160
) {
    val gradientColors = remember(title, seedId, model) {
        getGradientForId(if (title.isNotBlank()) title else if (seedId.isNotBlank()) seedId else (model ?: "playlist"))
    }
    val context = LocalContext.current

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(Brush.linearGradient(gradientColors)),
        contentAlignment = Alignment.Center
    ) {
        if (!model.isNullOrBlank()) {
            AsyncImage(
                model = remember(model, targetSize) {
                    ImageRequest.Builder(context)
                        .data(model)
                        .size(targetSize, targetSize)
                        .crossfade(false)
                        .build()
                },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/**
 * 高性能艺术家圆形头像组件
 */
@Composable
fun ArtistAvatarImage(
    model: String?,
    modifier: Modifier = Modifier,
    seedId: String = "",
    targetSize: Int = 160
) {
    val gradientColors = remember(seedId, model) {
        getGradientForId(if (seedId.isNotBlank()) seedId else (model ?: "artist"))
    }
    val context = LocalContext.current

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Brush.linearGradient(gradientColors)),
        contentAlignment = Alignment.Center
    ) {
        if (!model.isNullOrBlank()) {
            AsyncImage(
                model = remember(model, targetSize) {
                    ImageRequest.Builder(context)
                        .data(model)
                        .size(targetSize, targetSize)
                        .crossfade(false)
                        .build()
                },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

/**
 * 高性能 2x2 四宫格封面缩略图组件 (针对车机进行精简与小图优化)
 */
@Composable
fun MosaicArtworkCollage(
    coverUrls: List<String>,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    seedId: String = ""
) {
    val validCovers = remember(coverUrls) { coverUrls.filter { it.isNotBlank() }.take(4) }
    val gradientColors = remember(seedId) { getGradientForId(seedId) }
    val context = LocalContext.current

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(Brush.linearGradient(gradientColors)),
        contentAlignment = Alignment.Center
    ) {
        when (validCovers.size) {
            0 -> {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(28.dp)
                )
            }
            1 -> {
                AsyncImage(
                    model = remember(validCovers[0]) {
                        ImageRequest.Builder(context)
                            .data(validCovers[0])
                            .size(200, 200)
                            .crossfade(false)
                            .build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            AsyncImage(
                                model = remember(validCovers[0]) {
                                    ImageRequest.Builder(context)
                                        .data(validCovers[0])
                                        .size(100, 100)
                                        .crossfade(false)
                                        .build()
                                },
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            val c1 = validCovers.getOrElse(1) { validCovers[0] }
                            AsyncImage(
                                model = remember(c1) {
                                    ImageRequest.Builder(context)
                                        .data(c1)
                                        .size(100, 100)
                                        .crossfade(false)
                                        .build()
                                },
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            val c2 = validCovers.getOrElse(2) { validCovers[0] }
                            AsyncImage(
                                model = remember(c2) {
                                    ImageRequest.Builder(context)
                                        .data(c2)
                                        .size(100, 100)
                                        .crossfade(false)
                                        .build()
                                },
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            val c3 = validCovers.getOrElse(3) { validCovers.getOrElse(1) { validCovers[0] } }
                            AsyncImage(
                                model = remember(c3) {
                                    ImageRequest.Builder(context)
                                        .data(c3)
                                        .size(100, 100)
                                        .crossfade(false)
                                        .build()
                                },
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DefaultMusicPlaceholder(
    colors: List<Color>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(24.dp)
        )
    }
}
