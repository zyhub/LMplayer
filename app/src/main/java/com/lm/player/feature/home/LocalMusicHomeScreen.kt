package com.lm.player.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.component.ArtistAvatarImage
import com.lm.player.core.designsystem.component.DownloadQualityChoiceDialog
import com.lm.player.core.designsystem.component.DownloadQualityDropdownMenu
import com.lm.player.core.designsystem.component.ServerSwitchDropdownButton
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.designsystem.theme.LocalAppDimensions
import com.lm.player.core.model.AudioQuality
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.DownloadTarget
import com.lm.player.core.model.DownloadTask
import com.lm.player.core.model.HomeScreenDisplayConfig
import com.lm.player.core.model.ServerConfig
import com.lm.player.core.model.ServerType
import com.lm.player.core.model.UnifiedAlbum
import com.lm.player.core.model.UnifiedArtist
import com.lm.player.core.model.UnifiedPlaylist
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.model.serverFileSpecs

private val realFormatCache = androidx.collection.LruCache<String, Triple<String, Int, String>>(512)

/**
 * 精准解析本地已下载文件的真实封装格式 (支持魔数头 fLaC 校验)、真实码率与真实物理文件大小
 */
fun resolveRealLocalFormatAndSize(song: UnifiedSong): Triple<String, Int, String> {
    val localPath = song.localFilePath
    val cacheKey = if (!localPath.isNullOrBlank()) "$localPath:${song.bitRate}:${song.format}" else "${song.id}:${song.bitRate}:${song.format}"
    realFormatCache.get(cacheKey)?.let { return it }

    val localFile = if (!localPath.isNullOrBlank() && !localPath.startsWith("content://")) {
        try {
            java.io.File(localPath).takeIf { it.exists() && it.length() > 0L }
        } catch (_: Exception) { null }
    } else null

    val detectedExt = if (localFile != null) {
        val magicExt = try {
            if (localFile.length() >= 12L) {
                val h = ByteArray(12)
                java.io.FileInputStream(localFile).use { it.read(h) }
                when {
                    h[0] == 0x66.toByte() && h[1] == 0x4C.toByte() && h[2] == 0x61.toByte() && h[3] == 0x43.toByte() -> "FLAC"
                    h[0] == 'R'.code.toByte() && h[1] == 'I'.code.toByte() && h[2] == 'F'.code.toByte() && h[3] == 'F'.code.toByte() -> "WAV"
                    h[0] == 'O'.code.toByte() && h[1] == 'g'.code.toByte() && h[2] == 'g'.code.toByte() && h[3] == 'S'.code.toByte() -> "OGG"
                    h[4] == 'f'.code.toByte() && h[5] == 't'.code.toByte() && h[6] == 'y'.code.toByte() && h[7] == 'p'.code.toByte() -> "M4A"
                    else -> null
                }
            } else null
        } catch (_: Exception) { null }
        magicExt ?: localFile.extension.uppercase().ifBlank { song.format.uppercase().ifBlank { "MP3" } }
    } else {
        song.format.uppercase().ifBlank { "MP3" }
    }

    val isLossless = detectedExt in listOf("FLAC", "WAV", "ALAC", "APE", "DSD", "DSF")
    val realBitRate = if (localFile != null && song.durationMs >= 15_000L) {
        ((localFile.length() * 8L) / song.durationMs).toInt().coerceIn(64, 4608)
    } else if (isLossless) {
        song.bitRate.coerceAtLeast(960)
    } else {
        if (song.bitRate > 0) song.bitRate else 320
    }

    val sizeStr = if (localFile != null) {
        "%.1f MB".format(java.util.Locale.US, localFile.length() / (1024.0 * 1024.0))
    } else {
        val durationSec = (song.durationMs / 1000L).coerceAtLeast(180L)
        val rate = if (isLossless) 900 else realBitRate.coerceAtLeast(128)
        val estMb = (durationSec * rate * 1000L / 8L) / (1024.0 * 1024.0)
        "%.1f MB".format(java.util.Locale.US, estMb)
    }

    val result = Triple(detectedExt, realBitRate, sizeStr)
    realFormatCache.put(cacheKey, result)
    return result
}

/**
 * 服务器曲库歌曲（本机还没下载）的音质与大小。
 *
 * 以前列表只给本机文件标音质、在线歌曲只标 Hi-Res，服务器曲库里的无损 / 320K 歌曲一个标签都没有。
 * 服务器返回的曲目带格式、位深、采样率与文件大小（码率由「大小 ÷ 时长」算出），据此判断：
 * 无损格式（或码率 ≥ 800kbps）且 Hi-Res（24bit / 96kHz 以上，或码率 ≥ 1200kbps）→ Hi-Res，其余无损 → 无损，
 * ≥ 300kbps → 极高，其余 → 标准。大小优先用服务器给的文件大小，没有时按时长 × 码率估算。
 *
 * @return Triple(音质, 格式如 FLAC, 大小如 "28.6 MB")；不是服务器曲库歌曲时返回 null
 */
fun resolveServerSongQuality(song: UnifiedSong): Triple<AudioQuality, String, String>? {
    if (!song.isServerLibrarySong) return null
    // 一首歌在数据库里只有一条记录，下载 / 本地导入后 format / bitRate 会换成本地文件的规格；
    // 服务器原文件的规格要从同步时存下的服务器元数据里取，否则本地存一份 128K 会把服务器音质「污染」成 128K
    val specs = song.serverFileSpecs()
        ?: com.lm.player.core.network.LemonMusicProtocol.cachedServerSong(song.id)?.serverFileSpecs()
    // 本地有文件（下载 / 本地导入）或播放时走过音源加速后，记录里的 format / bitRate 已不是服务器原文件的；
    // 这时又拿不到服务器元数据，就不显示服务器音质（下载弹窗会按需向服务器取），绝不显示成本地的
    if (specs == null && (!song.localFilePath.isNullOrBlank() || song.downloadStatus == DownloadStatus.DOWNLOADED)) return null
    val ext = (specs?.format ?: song.format.substringAfterLast('.')).trim().uppercase()
    if (ext.isBlank()) return null
    val serverBitRate = specs?.bitRate ?: song.bitRate
    val lossless = specs?.isLossless ?: (ext in listOf("FLAC", "WAV", "ALAC", "APE", "DSD", "DSF", "AIFF") || song.bitRate >= 800)
    val hiRes = specs?.isHiRes ?: (lossless && (song.isHiRes || song.bitRate >= 1200))
    val quality = when {
        lossless && hiRes -> AudioQuality.Q_HIRES
        lossless -> AudioQuality.Q_FLAC
        serverBitRate >= 300 -> AudioQuality.Q_320K
        else -> AudioQuality.Q_128K
    }
    val sizeBytes = specs?.sizeBytes?.takeIf { it > 0L } ?: song.rawMetaJson
        ?.takeIf { it.trim().startsWith("{") }
        ?.let { runCatching { org.json.JSONObject(it).optLong("size", 0L) }.getOrDefault(0L) }
        ?: 0L
    val sizeStr = if (sizeBytes > 0L) {
        "%.1f MB".format(java.util.Locale.US, sizeBytes / (1024.0 * 1024.0))
    } else {
        val durationSec = (song.durationMs / 1000L).coerceAtLeast(180L)
        val rate = serverBitRate.coerceAtLeast(128)
        "%.1f MB".format(java.util.Locale.US, (durationSec * rate * 1000L / 8L) / (1024.0 * 1024.0))
    }
    return Triple(quality, ext, sizeStr)
}

/**
 * 格式化已下载音频的技术参数与真实物理文件大小
 */
fun formatDownloadedSongSpecs(song: UnifiedSong): String {
    val (formatStr, realBitRate, sizeStr) = resolveRealLocalFormatAndSize(song)
    val isLossless = formatStr in listOf("FLAC", "WAV", "ALAC", "APE", "DSD", "DSF") || realBitRate >= 800
    val qualityTag = when {
        isLossless && realBitRate >= 1200 -> "Hi-Res 无损"
        isLossless -> "无损音质"
        realBitRate >= 320 -> "极高音质"
        else -> "标准音质"
    }
    val bitrateStr = "$realBitRate kbps"
    return "$qualityTag • $formatStr • $bitrateStr • $sizeStr"
}

/**
 * 歌曲列表顶部的「播放全部 + 多选/全选下载」通用操作栏
 * 按钮放置在「播放全部」按钮旁边，支持一键进入多选、全选/取消全选、批量选择缓存位置与音质下载
 */
@Composable
fun SongListPlayAndBatchDownloadBar(
    totalCount: Int,
    isMultiSelectMode: Boolean,
    selectedCount: Int,
    isAllSelected: Boolean,
    onPlayAll: () -> Unit,
    onEnterMultiSelect: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onBatchDownloadClick: () -> Unit,
    onExitMultiSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val borderColor = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!isMultiSelectMode) {
            Button(
                onClick = onPlayAll,
                colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("播放全部 ($totalCount)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            OutlinedButton(
                onClick = onEnterMultiSelect,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, borderColor),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Checklist,
                    contentDescription = "多选",
                    tint = AppleRed,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "多选",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        } else {
            OutlinedButton(
                onClick = onToggleSelectAll,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, if (isAllSelected) AppleRed else borderColor),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(
                    imageVector = if (isAllSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = "全选",
                    tint = AppleRed,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isAllSelected) "取消全选" else "全选 ($totalCount)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isAllSelected) AppleRed else MaterialTheme.colorScheme.onSurface
                )
            }

            // 下载 / 加入歌单 / 移出歌单在底部悬浮操作条里（SongBatchFloatingBar）；这里只显示已选数量
            Text(
                text = "已选 $selectedCount 首",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selectedCount > 0) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )

            // 完成：与「播放全部」同款红底白字按钮
            Button(
                onClick = onExitMultiSelect,
                colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("完成", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * 歌曲列表单行（已下载展示音质/码率/大小，后方展示正在播放动态音波标志与下载按键/进度，支持多选下载）
 * 全局通用行组件，用于首页、资料库、搜索与歌单
 */
@Composable
fun SongListItemRow(
    song: UnifiedSong,
    isLocalOfflineMode: Boolean = false,
    activeDownloadTasks: List<DownloadTask> = emptyList(),
    isServerConnected: Boolean = true,
    currentPlayingSong: UnifiedSong? = null,
    isPlaying: Boolean = false,
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit = {},
    onDownloadWithOptions: ((UnifiedSong, DownloadTarget, AudioQuality) -> Unit)? = null,
    onOpenDownloads: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dimensions = LocalAppDimensions.current
    // 带上最新的下载 / 服务器状态：列表里的歌可能是打开页面时的快照，下载完成后要立刻变
    @Suppress("NAME_SHADOWING")
    val song = com.lm.player.core.media.SongStatusStore.rememberLive(song)
    val activeTask = activeDownloadTasks.firstOrNull { it.song.id == song.id }
    // 磁盘存在性判定必须 remember：列表行每次重组都会执行这一行，
    // 而 File.exists() 是真实的磁盘 stat —— 滚动时每行每帧一次 stat 是掉帧的直接来源。
    // key 用路径本身，路径不变就不重复 stat。
    // key 还要带上「是否在下载」「下载状态」和页面刷新计数：下载中列表里的歌可能已经带着目标路径（文件还没写完），
    // 那时查到「不存在」会被缓存；下载完成路径不变，只靠路径做 key 就一直不重查，要滑出屏幕再滑回来才对
    val hasPhysicalLocal = remember(song.id, song.localFilePath, song.downloadStatus, activeTask != null, com.lm.player.core.media.SongStatusStore.pageRefreshTick.intValue) {
        val path = song.localFilePath
        !path.isNullOrBlank() && (path.startsWith("content://") || java.io.File(path).exists())
    }
    val isDownloaded = hasPhysicalLocal && (song.downloadStatus == DownloadStatus.DOWNLOADED || !song.localFilePath.isNullOrBlank())
    val isDownloading = activeTask != null || song.downloadStatus == DownloadStatus.DOWNLOADING
    val currentProgress = activeTask?.progress ?: song.downloadProgress
    val isCurrentPlaying = com.lm.player.core.designsystem.component.isSamePlayingSong(song, currentPlayingSong)
    val streamQualityVer by com.lm.player.core.network.LemonMusicProtocol.streamQualityConfigVersion.collectAsState()
    val onlineStreamQualityTag = remember(context, streamQualityVer) {
        val preferredKey = com.lm.player.core.network.LemonMusicProtocol.getPreferredStreamQuality(context)
        AudioQuality.fromKey(preferredKey).onlineStreamTag()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    isMultiSelectMode && isSelected -> AppleRed.copy(alpha = 0.08f)
                    isCurrentPlaying -> AppleRed.copy(alpha = 0.06f)
                    else -> Color.Transparent
                }
            )
            .clickable(
                onClick = {
                    if (isMultiSelectMode && onToggleSelect != null) {
                        onToggleSelect()
                    } else {
                        onClick()
                    }
                }
            )
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isMultiSelectMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect?.invoke() },
                colors = CheckboxDefaults.colors(
                    checkedColor = AppleRed,
                    uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        AlbumArtworkImage(
            model = song.coverUrl,
            seedId = song.id,
            modifier = Modifier.size(48.dp),
            cornerRadius = 10.dp
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontSize = dimensions.itemTitleSize,
                    fontWeight = if (isCurrentPlaying) FontWeight.Bold else FontWeight.Medium,
                    color = if (isCurrentPlaying) AppleRed else MaterialTheme.colorScheme.onSurface
                )
            )

            Spacer(modifier = Modifier.height(3.dp))

            // 第二行统一为「[音质标签] 歌手 · 专辑」：
            // - 已下载：按本地文件的真实音质标出 Hi-Res / 无损 / 极高 / 标准（不再出现「已离线」这种状态文字）；
            // - 在线：只标出 Hi-Res 曲目，其余在线歌曲都按设置里的试听音质播放，不逐行重复；
            // - 下载 / 同步状态统一由行尾图标表示；格式、码率、大小等细节在播放页的音质标签里看。
            // 本地文件与服务器原文件都有、且服务器那份更好时，额外标出「云·无损」等（播放仍优先本地文件，所以主标签是本地音质）
            val cloudBetterTag: Pair<String, Color>? = if (isDownloaded && song.isServerLibrarySong) {
                val tiers = remember(song.id, song.localFilePath, song.format, song.bitRate, song.rawMetaJson) {
                    val (lf, lb, _) = resolveRealLocalFormatAndSize(song)
                    val localLossless = lf in listOf("FLAC", "WAV", "ALAC", "APE", "DSD", "DSF") || lb >= 800
                    val localTier = when {
                        localLossless && lb >= 1200 -> 3
                        localLossless -> 2
                        lb >= 320 -> 1
                        else -> 0
                    }
                    val serverTier = when (resolveServerSongQuality(song)?.first) {
                        AudioQuality.Q_HIRES -> 3
                        AudioQuality.Q_FLAC -> 2
                        AudioQuality.Q_320K -> 1
                        AudioQuality.Q_128K -> 0
                        null -> -1
                    }
                    localTier to serverTier
                }
                when {
                    tiers.second <= tiers.first -> null
                    tiers.second == 3 -> "云·Hi-Res" to Color(0xFFD4AF37)
                    tiers.second == 2 -> "云·无损" to AppleRed
                    else -> "云·极高" to Color(0xFF0A84FF)
                }
            } else null
            val qualityTag: Pair<String, Color>? = if (isDownloaded) {
                val (realFormatStr, realBitRate, _) = remember(song.id, song.localFilePath, song.format, song.bitRate) {
                    resolveRealLocalFormatAndSize(song)
                }
                val lossless = realFormatStr in listOf("FLAC", "WAV", "ALAC", "APE", "DSD", "DSF") || realBitRate >= 800
                when {
                    lossless && realBitRate >= 1200 -> "Hi-Res" to Color(0xFFD4AF37)
                    lossless -> "无损" to AppleRed
                    realBitRate >= 320 -> "极高" to Color(0xFF0A84FF)
                    else -> "标准" to MaterialTheme.colorScheme.onSurfaceVariant
                }
            } else if (song.isServerLibrarySong) {
                // 服务器曲库歌曲：按服务器给的格式 / 码率 / 位深判断（以前这里只标 Hi-Res，无损 / 320K 都不显示）
                val serverQuality = remember(song.id, song.format, song.bitRate, song.bitDepth, song.sampleRate, song.rawMetaJson) {
                    resolveServerSongQuality(song)?.first
                }
                when (serverQuality) {
                    AudioQuality.Q_HIRES -> "Hi-Res" to Color(0xFFD4AF37)
                    AudioQuality.Q_FLAC -> "无损" to AppleRed
                    AudioQuality.Q_320K -> "极高" to Color(0xFF0A84FF)
                    AudioQuality.Q_128K -> "标准" to MaterialTheme.colorScheme.onSurfaceVariant
                    null -> if (song.isHiRes) "Hi-Res" to Color(0xFFD4AF37) else null
                }
            } else if (song.isHiRes) {
                "Hi-Res" to Color(0xFFD4AF37)
            } else null
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (qualityTag != null) {
                    Text(
                        text = qualityTag.first,
                        color = qualityTag.second,
                        fontSize = dimensions.badgeSize,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(qualityTag.second.copy(alpha = 0.14f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                if (cloudBetterTag != null) {
                    Text(
                        text = cloudBetterTag.first,
                        color = cloudBetterTag.second.copy(alpha = 0.85f),
                        fontSize = dimensions.badgeSize,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .border(0.6.dp, cloudBetterTag.second.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 0.5.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = if (song.album.isNotBlank()) "${song.artist} · ${song.album}" else song.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontSize = dimensions.captionSize,
                        fontWeight = FontWeight.Normal,
                        color = if (isCurrentPlaying) AppleRed.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }

        // 歌曲后方正在播放的动态音波标志
        if (isCurrentPlaying) {
            Spacer(modifier = Modifier.width(6.dp))
            com.lm.player.core.designsystem.component.NowPlayingWaveIndicator(
                isPlaying = isPlaying
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // hasLocal 必须基于物理本地文件真实存在，避免 NAS 服务器曲库路径被误判为本地已有
        val hasLocal = hasPhysicalLocal || (song.serverId in listOf("local_storage", "local_folder", "local_saf"))
        // 「在不在服务器」是曲库数据事实，不能叠加 isServerConnected：外网抖动、服务器暂时
        // 不可达时，徽标会错误地从「仅服务器」退化成「下载」
        val hasServer = song.isServerLibrarySong

        if (onDownloadWithOptions != null) {
            com.lm.player.core.designsystem.component.SongSyncStatusTrailing(
                song = song,
                isDownloading = isDownloading,
                downloadProgress = currentProgress,
                isServerConnected = isServerConnected,
                hasLocal = hasLocal,
                hasServer = hasServer,
                onOpenDownloads = onOpenDownloads,
                onDownloadWithOptions = onDownloadWithOptions
            )
        } else if (isDownloading) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AppleRed.copy(alpha = 0.12f),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenDownloads() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        progress = { currentProgress },
                        modifier = Modifier.size(16.dp),
                        color = AppleRed,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "${(currentProgress * 100).toInt()}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppleRed
                    )
                }
            }
        } else if (isDownloaded) {
            IconButton(
                onClick = onClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "已离线",
                    tint = Color(0xFF34C759),
                    modifier = Modifier.size(22.dp)
                )
            }
        } else {
            IconButton(
                onClick = onDownloadClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowCircleDown,
                    contentDescription = "下载歌曲",
                    tint = AppleRed,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * LMPlayer 本地离线模式首页
 */
@Composable
fun LocalMusicHomeScreen(
    serverName: String = "本地模式",
    recentSongs: List<UnifiedSong>,
    configuredServers: List<ServerConfig> = emptyList(),
    blurAlpha: Float = 0.85f,
    recentlyAddedSongs: List<UnifiedSong> = emptyList(),
    recentlyPlayedSongs: List<UnifiedSong> = emptyList(),
    discoverPlaylists: List<UnifiedPlaylist> = emptyList(),
    homeDisplayConfig: HomeScreenDisplayConfig = HomeScreenDisplayConfig(),
    activeDownloadTasks: List<DownloadTask> = emptyList(),
    activeDownloadCount: Int = 0,
    currentPlayingSong: UnifiedSong? = null,
    isPlaying: Boolean = false,
    locateSongTrigger: Int = 0,
    scrollToTopTrigger: Int = 0,
    onListScrollingChange: (Boolean) -> Unit = {},
    onScrollPositionChange: (Boolean) -> Unit = {},
    onSongClick: (UnifiedSong, List<UnifiedSong>?) -> Unit = { _, _ -> },
    onDownloadSong: (UnifiedSong) -> Unit = {},
    onDownloadSongWithOptions: (UnifiedSong, DownloadTarget, AudioQuality) -> Unit = { song, _, _ -> onDownloadSong(song) },
    onBatchDownloadSongsWithOptions: (List<UnifiedSong>, DownloadTarget, AudioQuality) -> Unit = { songs, target, quality ->
        songs.forEach { onDownloadSongWithOptions(it, target, quality) }
    },
    onSelectLocalServer: () -> Unit = {},
    onSelectServer: (ServerConfig) -> Unit = {},
    onSyncNow: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onGoToSettings: () -> Unit = {},
    onPlaylistClick: ((UnifiedPlaylist) -> Unit)? = null,
    onScanLocalMedia: (() -> Unit)? = null,
    onSubViewActiveChange: (Boolean) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val dimensions = LocalAppDimensions.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val isCompactHeader = screenWidthDp < 390
    val isUltraCompactHeader = screenWidthDp < 350
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = blurAlpha)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.10f)

    var songForDownloadChoice by remember { mutableStateOf<UnifiedSong?>(null) }
    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedSongIds = remember { mutableStateListOf<String>() }
    var showBatchDownloadDialog by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    LaunchedEffect(isMultiSelectMode) {
        onSubViewActiveChange(isMultiSelectMode)
    }
    DisposableEffect(Unit) {
        onDispose { onSubViewActiveChange(false) }
    }
    if (isMultiSelectMode) {
        androidx.activity.compose.BackHandler(enabled = true) {
            isMultiSelectMode = false
            selectedSongIds.clear()
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        onListScrollingChange(listState.isScrollInProgress)
    }

    // 本地模式仅筛选真正已下载到本地或扫描导入且物理文件存在的离线音频
    val localDownloadedSongs = remember(recentSongs) {
        recentSongs.filter {
            val hasValidFile = !it.localFilePath.isNullOrBlank() && java.io.File(it.localFilePath).let { f -> f.exists() && f.length() > 0 }
            (it.downloadStatus == DownloadStatus.DOWNLOADED && hasValidFile) ||
            (it.serverId in listOf("local_storage", "local_saf", "local_folder") && hasValidFile)
        }
    }

    // 严密约束：本地已下载界面绝不回退至包含全量在线服务器曲目的 recentSongs
    val activeSongSource = localDownloadedSongs

    val effectiveRecentlyAdded = remember(activeSongSource) {
        activeSongSource.sortedByDescending { it.addedTimestamp }.take(20)
    }


    // 从真实歌曲列表中动态提取专辑集合
    val albums = remember(activeSongSource) {
        activeSongSource.filter { it.album.isNotBlank() }
            .groupBy { it.album }
            .map { (albumTitle, songs) ->
                val first = songs.first()
                UnifiedAlbum(
                    id = first.albumId.ifBlank { albumTitle },
                    title = albumTitle,
                    artist = first.artist,
                    coverUrl = first.coverUrl,
                    songCount = songs.size
                )
            }
    }

    // 从真实歌曲列表中动态提取艺术家集合
    val artists = remember(activeSongSource) {
        activeSongSource.filter { it.artist.isNotBlank() }
            .groupBy { it.artist }
            .map { (artistName, songs) ->
                UnifiedArtist(
                    id = artistName,
                    name = artistName,
                    avatarUrl = songs.firstOrNull()?.coverUrl ?: "",
                    albumCount = songs.map { it.album }.distinct().size
                )
            }
    }

    val isServerOk = configuredServers.any { it.type == ServerType.LEMON_MUSIC }

    LaunchedEffect(locateSongTrigger) {
        if (locateSongTrigger > 0 && currentPlayingSong != null && activeSongSource.isNotEmpty()) {
            val songIdx = activeSongSource.indexOfFirst {
                com.lm.player.core.designsystem.component.isSamePlayingSong(it, currentPlayingSong)
            }
            if (songIdx >= 0) {
                var headerOffset = 2 // 顶部 Header + 所有曲目 Header
                if (homeDisplayConfig.showRecentlyAdded && effectiveRecentlyAdded.isNotEmpty()) headerOffset++
                if (homeDisplayConfig.showAlbums && albums.isNotEmpty()) headerOffset++
                if (homeDisplayConfig.showArtists && artists.isNotEmpty()) headerOffset++
                listState.scrollToItem((headerOffset + songIdx).coerceAtLeast(0))
            }
        }
    }

    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) {
            listState.scrollToItem(0)
        }
    }

    val isScrolledAway by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }
    LaunchedEffect(isScrolledAway) {
        onScrollPositionChange(isScrolledAway)
    }

    // 头部右侧「下载管理 + 服务器切换」：普通模式画在头部；液态玻璃模式登记到上层固定显示
    val pinnedHeaderActions: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isUltraCompactHeader) 4.dp else 6.dp)
        ) {
            com.lm.player.core.designsystem.component.HeaderDownloadPill(
                activeDownloadCount = activeDownloadCount,
                onClick = onOpenDownloads,
                isCompactHeader = isCompactHeader,
                horizontalPad = if (isUltraCompactHeader) 7.dp else if (isCompactHeader) 8.dp else 10.dp,
                verticalPad = if (isCompactHeader) 5.dp else 6.dp,
                surfaceColor = surfaceColor,
                borderColor = borderColor,
                blurAlpha = blurAlpha
            )
            ServerSwitchDropdownButton(
                currentServer = serverName,
                configuredServers = configuredServers,
                blurAlpha = blurAlpha,
                onSelectLocal = onSelectLocalServer,
                onSelectServer = onSelectServer,
                onSyncNow = onSyncNow,
                onGoToSettings = onGoToSettings
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
        // 1. 顶部 Header (大标题 + 下载管理胶囊 + 音源下拉切换，自适应不同分辨率)
        item {
            val pillHorizontalPad = if (isUltraCompactHeader) 7.dp else if (isCompactHeader) 8.dp else 10.dp
            val pillVerticalPad = if (isCompactHeader) 5.dp else 6.dp
            val buttonGap = if (isUltraCompactHeader) 4.dp else 6.dp

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(end = 6.dp)
                ) {
                    Text(
                        text = "本地音乐",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontSize = dimensions.pageTitleSize,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    )
                    Text(
                        text = if (activeSongSource.isEmpty()) "暂无已下载歌曲" else "已收录 ${activeSongSource.size} 首已下载歌曲 · ${albums.size} 张专辑",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = dimensions.captionSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

 pinnedHeaderActions()             }
        }

        if (activeSongSource.isEmpty()) {
            item {
                EmptyStateWelcomeCard(
                    onGoToSettings = onGoToSettings,
                    onScanLocalMedia = onScanLocalMedia
                )
            }
        } else {
            // A. 最近添加
            if (homeDisplayConfig.showRecentlyAdded && effectiveRecentlyAdded.isNotEmpty()) {
                item {
                    Column {
                        SectionHeader(
                            title = "最近添加",
                            subtitle = "本机最新入库音频"
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(
                                items = effectiveRecentlyAdded,
                                key = { it.id },
                                contentType = { "recent_song_card" }
                            ) { song ->
                                RecentlyAddedSongCard(
                                    song = song,
                                    isLocalOfflineMode = true,
                                    onClick = { onSongClick(song, effectiveRecentlyAdded) }
                                )
                            }
                        }
                    }
                }
            }

            // B. 专辑合集
            if (homeDisplayConfig.showAlbums && albums.isNotEmpty()) {
                item {
                    Column {
                        SectionHeader(
                            title = "本地专辑",
                            subtitle = "已收录唱片合集"
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(
                                items = albums,
                                key = { it.id },
                                contentType = { "album_card" }
                            ) { album ->
                                AlbumCardItem(
                                    album = album,
                                    onClick = {
                                        val albumSongs = activeSongSource.filter { it.album == album.title }
                                        albumSongs.firstOrNull()?.let { onSongClick(it, albumSongs) }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // C. 歌手合集
            if (homeDisplayConfig.showArtists && artists.isNotEmpty()) {
                item {
                    Column {
                        SectionHeader(
                            title = "本地歌手",
                            subtitle = "本地音乐人"
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(
                                items = artists,
                                key = { it.id },
                                contentType = { "artist_circle" }
                            ) { artist ->
                                ArtistCircleItem(
                                    artist = artist,
                                    onClick = {
                                        val artistSongs = activeSongSource.filter { it.artist == artist.name }
                                        artistSongs.firstOrNull()?.let { onSongClick(it, artistSongs) }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // D. 所有曲目 (含播放全部 + 多选/全选下载操作栏)
            item {
                val isAllSelected = activeSongSource.isNotEmpty() && selectedSongIds.size == activeSongSource.size
                Column {
                    SectionHeader(
                        title = "所有曲目",
                        subtitle = "共 ${activeSongSource.size} 首本地音频"
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    SongListPlayAndBatchDownloadBar(
                        totalCount = activeSongSource.size,
                        isMultiSelectMode = isMultiSelectMode,
                        selectedCount = selectedSongIds.size,
                        isAllSelected = isAllSelected,
                        onPlayAll = {
                            activeSongSource.firstOrNull()?.let { onSongClick(it, activeSongSource) }
                        },
                        onEnterMultiSelect = {
                            selectedSongIds.clear()
                            isMultiSelectMode = true
                        },
                        onToggleSelectAll = {
                            if (isAllSelected) {
                                selectedSongIds.clear()
                            } else {
                                selectedSongIds.clear()
                                selectedSongIds.addAll(activeSongSource.map { it.id })
                            }
                        },
                        onBatchDownloadClick = {
                            if (selectedSongIds.isNotEmpty()) {
                                showBatchDownloadDialog = true
                            }
                        },
                        onExitMultiSelect = {
                            isMultiSelectMode = false
                            selectedSongIds.clear()
                        }
                    )
                }
            }

            items(
                items = activeSongSource,
                key = { it.id },
                contentType = { "song_row" }
            ) { song ->
                val isSelected = song.id in selectedSongIds
                SongListItemRow(
                    song = song,
                    isLocalOfflineMode = true,
                    activeDownloadTasks = activeDownloadTasks,
                    isServerConnected = isServerOk,
                    currentPlayingSong = currentPlayingSong,
                    isPlaying = isPlaying,
                    isMultiSelectMode = isMultiSelectMode,
                    isSelected = isSelected,
                    onToggleSelect = {
                        if (isSelected) selectedSongIds.remove(song.id) else selectedSongIds.add(song.id)
                    },
                    onClick = { onSongClick(song, activeSongSource) },
                    onDownloadClick = { songForDownloadChoice = song },
                    onDownloadWithOptions = { s, target, quality ->
                        onDownloadSongWithOptions(s, target, quality)
                    },
                    onOpenDownloads = onOpenDownloads
                )
            }
        }
    }

        // 顶部渐进毛玻璃条已删除：应用不是 edge-to-edge，它只会在状态栏下方形成一条白 / 灰条

    // 多选底部悬浮操作条（下载 / 加入歌单）：放在页面顶层，列表头滑出屏幕也不会消失
    val batchBarContext = androidx.compose.ui.platform.LocalContext.current
    com.lm.player.core.designsystem.component.SongBatchFloatingBar(
        key = "home_all_songs",
        visible = isMultiSelectMode,
        selectedCount = selectedSongIds.size,
        selectedSongs = { activeSongSource.filter { it.id in selectedSongIds } },
        isServerConnected = isServerOk,
        onDownload = { picked, target, quality ->
            isMultiSelectMode = false
            selectedSongIds.clear()
            onBatchDownloadSongsWithOptions(picked, target, quality)
        },
        onFinished = {
            isMultiSelectMode = false
            selectedSongIds.clear()
        }
    )

    if (showBatchDownloadDialog) {
        val selectedSongs = activeSongSource.filter { it.id in selectedSongIds }
        com.lm.player.core.designsystem.component.BatchDownloadQualityChoiceDialog(
            selectedCount = selectedSongs.size,
            isServerConnected = isServerOk,
            onConfirm = { target, quality ->
                showBatchDownloadDialog = false
                isMultiSelectMode = false
                selectedSongIds.clear()
                onBatchDownloadSongsWithOptions(selectedSongs, target, quality)
            },
            onDismiss = { showBatchDownloadDialog = false }
        )
    }

    if (songForDownloadChoice != null) {
            DownloadQualityChoiceDialog(
                song = songForDownloadChoice!!,
                onDismiss = { songForDownloadChoice = null },
                onConfirm = { target, quality ->
                    onDownloadSongWithOptions(songForDownloadChoice!!, target, quality)
                    songForDownloadChoice = null
                }
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String = ""
) {
    val dimensions = LocalAppDimensions.current
    Column {
        Text(
            text = title,
            style = TextStyle(
                fontSize = dimensions.sectionTitleSize,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        )
        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = TextStyle(
                    fontSize = dimensions.captionSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

@Composable
private fun RecentlyAddedSongCard(
    song: UnifiedSong,
    isLocalOfflineMode: Boolean = false,
    onClick: () -> Unit
) {
    val dimensions = LocalAppDimensions.current
    @Suppress("NAME_SHADOWING")
    val song = com.lm.player.core.media.SongStatusStore.rememberLive(song)
    // 同 SongListItemRow：磁盘 stat 必须 remember，否则每次重组都打一次磁盘
    val isDownloaded = remember(song.id, song.localFilePath, song.downloadStatus, com.lm.player.core.media.SongStatusStore.pageRefreshTick.intValue) {
        val path = song.localFilePath
        !path.isNullOrBlank() &&
            (path.startsWith("content://") || java.io.File(path).exists()) &&
            (song.downloadStatus == DownloadStatus.DOWNLOADED || !path.isNullOrBlank())
    }

    Column(
        modifier = Modifier
            .width(135.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(135.dp)
                .shadow(3.dp, RoundedCornerShape(14.dp))
        ) {
            AlbumArtworkImage(
                model = song.coverUrl,
                seedId = song.id,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 14.dp
            )

            Surface(
                shape = RoundedCornerShape(topStart = 0.dp, bottomEnd = 0.dp, topEnd = 14.dp, bottomStart = 8.dp),
                color = Color.Black.copy(alpha = 0.65f),
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Text(
                    text = if (isDownloaded) "本地已存" else song.format.uppercase(),
                    color = if (isDownloaded) AppleRed else Color.White,
                    fontSize = dimensions.badgeSize,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = song.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = dimensions.bodySize,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = song.artist,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = dimensions.captionSize,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

@Composable
private fun AlbumCardItem(
    album: UnifiedAlbum,
    onClick: () -> Unit
) {
    val dimensions = LocalAppDimensions.current
    Column(
        modifier = Modifier
            .width(130.dp)
            .clickable(onClick = onClick)
    ) {
        AlbumArtworkImage(
            model = album.coverUrl,
            seedId = album.id,
            modifier = Modifier
                .size(130.dp)
                .shadow(3.dp, RoundedCornerShape(12.dp)),
            cornerRadius = 12.dp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = album.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = dimensions.bodySize,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "${album.artist} • ${album.songCount} 首",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = dimensions.captionSize,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

@Composable
private fun ArtistCircleItem(
    artist: UnifiedArtist,
    onClick: () -> Unit
) {
    val dimensions = LocalAppDimensions.current
    Column(
        modifier = Modifier
            .width(85.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ArtistAvatarImage(
            model = artist.avatarUrl,
            seedId = artist.name,
            modifier = Modifier
                .size(75.dp)
                .shadow(2.dp, CircleShape)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = artist.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = dimensions.captionSize,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        )
    }
}

@Composable
private fun EmptyStateWelcomeCard(
    onGoToSettings: () -> Unit,
    onScanLocalMedia: (() -> Unit)? = null
) {
    val dimensions = LocalAppDimensions.current
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(id = com.lm.player.R.drawable.app_logo),
                contentDescription = "LMPlayer Logo",
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "欢迎使用 柠檬音乐 (LMPlayer)",
                style = TextStyle(fontSize = dimensions.sectionTitleSize, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "当前处于本地模式。您可以扫描本机存储中的音乐文件，或前往设置登录柠檬音乐服务器获取海量在线乐库与推荐歌单。",
                style = TextStyle(fontSize = dimensions.bodySize, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center),
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (onScanLocalMedia != null) {
                // 扫描进度 / 结果（与设置页「本地音乐路径」同一张进度卡）
                com.lm.player.feature.settings.LocalScanProgressCard()
                val scanState by com.lm.player.core.media.LocalScanEngine.state.collectAsState()
                if (scanState?.isRunning == true) Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onScanLocalMedia,
                    enabled = scanState?.isRunning != true,
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("一键扫描本地音乐", fontSize = dimensions.bodySize, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            OutlinedButton(
                onClick = onGoToSettings,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(46.dp)
            ) {
                Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("前往设置登录柠檬音乐", fontSize = dimensions.bodySize, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
