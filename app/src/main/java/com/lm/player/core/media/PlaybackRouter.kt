package com.lm.player.core.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.database.dao.DownloadDao
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.ServerType
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.network.LemonMusicProtocol
import com.lm.player.core.network.NetworkClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PlaybackRouter(
    private val downloadDao: DownloadDao,
    private val context: Context
) {
    private val TAG = "PlaybackRouter"

    /**
     * 核心路由决策机制：
     * 1. 优先检测本地已下载文件 (Room 下载库、曲目本地路径、默认存储目录)。
     * 2. 若未直接记录本地路径，通过歌曲标题与艺术家进行全局本地离线库模糊匹配。
     * 3. 存在真实本地文件时，构建标准 file:// Uri，实现 0 等待、0 流量极速秒开。
     * 4. 若为在线歌曲且流媒体链接未就绪，自动通过柠檬音乐协议后台快速解析真实流媒体链接。
     * 5. 否则路由至远程流媒体 URL，自动接管 Media3 播放与缓存。
     */
    suspend fun resolveMediaItem(song: UnifiedSong, forceRefresh: Boolean = false): MediaItem {
        val downloadRecord = downloadDao.getDownloadRecord(song.id)
        val recordPath = downloadRecord?.localFilePath
        val hasValidRecordFile = downloadRecord?.status == DownloadStatus.DOWNLOADED &&
                !recordPath.isNullOrEmpty() &&
                File(recordPath).let { it.exists() && it.length() > 0 }

        val hasValidSongLocalFile = !song.localFilePath.isNullOrEmpty() &&
                File(song.localFilePath).let { it.exists() && it.length() > 0 }

        val isStreamUrlLocalFile = song.streamUrl.startsWith("/") &&
                File(song.streamUrl).let { it.exists() && it.length() > 0 }

        val defaultDownloadFile = File(File(context.getExternalFilesDir(null), "music"), "${song.id}.${song.format}")
        val hasDefaultFile = defaultDownloadFile.exists() && defaultDownloadFile.length() > 0

        val directLocalPath: String? = when {
            hasValidRecordFile -> recordPath
            hasValidSongLocalFile -> song.localFilePath
            isStreamUrlLocalFile -> song.streamUrl
            hasDefaultFile -> defaultDownloadFile.absolutePath
            else -> null
        }

        // 本地库智能匹配：当直接路径为空时，尝试从本地曲库匹配已下载的物理音频（严格校验版本、专辑与时长）
        val resolvedLocalPath: String? = directLocalPath ?: run {
            try {
                val db = ZdsDatabase.getInstance(context)
                val allSongs = db.songDao().getAllSongsList()
                val matched = allSongs.firstOrNull { s ->
                    val hasFile = !s.localFilePath.isNullOrBlank() && File(s.localFilePath).let { f -> f.exists() && f.length() > 0 }
                    hasFile && (s.id == song.id || SongMatchingResolver.isSongMatch(
                        s.title, s.artist, s.durationMs,
                        song.title, song.artist, song.durationMs,
                        s.album, song.album
                    ))
                }
                matched?.localFilePath
            } catch (_: Exception) {
                null
            }
        }

        val currentPreferredQuality = LemonMusicProtocol.getPreferredStreamQuality(context)
        val targetBitRate = com.lm.player.core.model.AudioQuality.fromKey(currentPreferredQuality).bitrate
        val isStaleProxyQuality = song.streamUrl.contains("/api/play/proxy") && song.bitRate != targetBitRate

        var finalStreamUrl = if ((forceRefresh || isStaleProxyQuality) && (song.id.startsWith("lemon_online_") || song.streamUrl.contains("/api/play/proxy"))) {
            ""
        } else {
            song.streamUrl
        }

        val isOnlineId = song.id.startsWith("lemon_online_") || song.serverId == "lemon_online"
        val isServerSong = !isOnlineId &&
                song.serverId !in listOf("local_storage", "local_folder", "local_saf")

        if (resolvedLocalPath != null) {
            // 本地文件命中，同步更新队列与当前曲目状态
            val updated = song.copy(
                localFilePath = resolvedLocalPath,
                streamUrl = resolvedLocalPath,
                downloadStatus = DownloadStatus.DOWNLOADED
            )
            PlaybackQueueManager.updateCurrentSong(updated)
        } else {
            withContext(Dispatchers.IO) {
                try {
                    val db = ZdsDatabase.getInstance(context)
                    val active = db.serverDao().getActiveServer()
                        ?: db.serverDao().getAllServers().firstOrNull { it.id == song.serverId || it.type == ServerType.LEMON_MUSIC }

                    if (active != null && active.type == ServerType.LEMON_MUSIC) {
                        val client = NetworkClientFactory.createOkHttpClient(context)
                        val protocol = LemonMusicProtocol(client, active.serverUrl, active.username, active.tokenOrApiKey)
                        protocol.ensureAuthenticated()

                        // 1. 若已有服务端本地流地址或属于服务端曲库曲目，优先刷新鉴权 Token 与标准流地址
                        if (finalStreamUrl.contains("/api/play/local") || isServerSong) {
                            var serverPath = LemonMusicProtocol.getServerFilePath(song.id, finalStreamUrl, song.coverUrl)
                            if (serverPath.isNullOrBlank()) {
                                val rel = song.relativeFolderPath?.trim().orEmpty()
                                val hasAudioExt = rel.substringAfterLast('.', "").lowercase() in setOf(
                                    "mp3", "flac", "wav", "ape", "m4a", "aac", "ogg", "opus", "wma", "dsf", "dff"
                                )
                                if (hasAudioExt && (rel.startsWith("/") || rel.contains(":/") || rel.contains(":\\"))) {
                                    serverPath = rel
                                }
                            }
                            if (serverPath.isNullOrBlank()) {
                                // 从本地数据库中查找同 ID 或完全同版本服务端曲目的有效路径/流地址
                                val allSongs = db.songDao().getAllSongsList()
                                val matchedServerSong = allSongs.firstOrNull { s ->
                                    (s.id == song.id || SongMatchingResolver.isSongMatch(
                                        s.title, s.artist, s.durationMs,
                                        song.title, song.artist, song.durationMs,
                                        s.album, song.album
                                    )) && (s.streamUrl.contains("/api/play/local") || s.coverUrl.contains("path="))
                                }
                                if (matchedServerSong != null) {
                                    serverPath = LemonMusicProtocol.getServerFilePath(
                                        matchedServerSong.id,
                                        matchedServerSong.streamUrl,
                                        matchedServerSong.coverUrl
                                    )
                                }
                            }
                            if (!serverPath.isNullOrBlank()) {
                                finalStreamUrl = protocol.getStreamUrlForPath(serverPath)
                            }
                        }

                        var resolvedFormat = song.format
                        var resolvedBitRate = song.bitRate

                        // 2. 若流地址仍为空或为待解析在线标识，通过柠檬服务端 /api/play/url 换取服务器代理流 (/api/play/proxy) 或服务器本地缓存流
                        if (finalStreamUrl.isBlank() || finalStreamUrl.startsWith("lemon_online://")) {
                            val cleanId = song.id.removePrefix("lemon_online_")
                            val source = if (cleanId.contains("_")) cleanId.substringBefore("_") else "kw"
                            val meta = song.rawMetaJson?.takeIf { it.trim().startsWith("{") }
                                ?: song.relativeFolderPath?.takeIf { it.trim().startsWith("{") }

                            val preferredQuality = LemonMusicProtocol.getPreferredStreamQuality(context)
                            val resolvedStream = protocol.resolveOnlineStreamWithQuality(
                                songId = song.id,
                                source = source,
                                preferredQuality = preferredQuality,
                                metaJson = meta,
                                fallbackTitle = song.title,
                                fallbackArtist = song.artist,
                                refresh = forceRefresh
                            ).getOrNull()

                            if (resolvedStream != null && resolvedStream.url.isNotBlank()) {
                                finalStreamUrl = resolvedStream.url
                                resolvedFormat = resolvedStream.format
                                resolvedBitRate = resolvedStream.bitRate
                            } else {
                                // 兜底：若第三方音源暂时不可用，回退匹配资料库内完全同版本已就绪的服务端曲目流地址
                                val allSongs = db.songDao().getAllSongsList()
                                val fallbackLibSong = allSongs.firstOrNull { s ->
                                    (s.streamUrl.startsWith("http://") || s.streamUrl.startsWith("https://")) &&
                                    SongMatchingResolver.isSongMatch(
                                        s.title, s.artist, s.durationMs,
                                        song.title, song.artist, song.durationMs,
                                        s.album, song.album
                                    )
                                }
                                if (fallbackLibSong != null) {
                                    val fbPath = LemonMusicProtocol.getServerFilePath(
                                        fallbackLibSong.id,
                                        fallbackLibSong.streamUrl,
                                        fallbackLibSong.coverUrl
                                    )
                                    finalStreamUrl = if (!fbPath.isNullOrBlank()) {
                                        protocol.getStreamUrlForPath(fbPath)
                                    } else {
                                        fallbackLibSong.streamUrl
                                    }
                                }
                            }
                        }

                        if (finalStreamUrl.isNotBlank() && (finalStreamUrl != song.streamUrl || resolvedFormat != song.format || resolvedBitRate != song.bitRate)) {
                            val updated = song.copy(
                                streamUrl = finalStreamUrl,
                                format = resolvedFormat,
                                bitRate = resolvedBitRate
                            )
                            PlaybackQueueManager.updateCurrentSong(updated)
                        }
                    }
                    Unit
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to resolve stream URL for ${song.title}", e)
                }
                Unit
            }
        }

        val uri: Uri = if (resolvedLocalPath != null) {
            Log.d(TAG, "Routing to local file: $resolvedLocalPath")
            Uri.fromFile(File(resolvedLocalPath))
        } else if (finalStreamUrl.startsWith("content://")) {
            Log.d(TAG, "Routing to SAF content uri: $finalStreamUrl")
            Uri.parse(finalStreamUrl)
        } else {
            Log.d(TAG, "Routing to remote stream: $finalStreamUrl")
            Uri.parse(finalStreamUrl)
        }

        val isOffline = uri.scheme == "file" || uri.scheme == "content"

        var cachedArtworkBytes = DynamicIslandManager.getCachedArtworkBytes(song.id)
        if (cachedArtworkBytes == null) {
            try {
                withContext(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(700L) {
                        DynamicIslandManager.loadSongArtworkBitmap(context, song)
                    }
                }
                cachedArtworkBytes = DynamicIslandManager.getCachedArtworkBytes(song.id)
            } catch (_: Exception) {}
        }

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(song.title)
            .setDisplayTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setArtworkUri(if (song.coverUrl.isNotEmpty()) Uri.parse(song.coverUrl) else null)
            .setExtras(Bundle().apply {
                putBoolean("KEY_IS_OFFLINE", isOffline)
                putString("KEY_SONG_ID", song.id)
                putString("KEY_SERVER_ID", song.serverId)
                putInt("KEY_BITRATE", song.bitRate)
            })

        if (cachedArtworkBytes != null) {
            metadataBuilder.setArtworkData(cachedArtworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }

        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(uri)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }
}
