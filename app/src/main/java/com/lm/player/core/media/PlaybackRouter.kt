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
    suspend fun resolveMediaItem(song: UnifiedSong): MediaItem {
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

        // 本地库智能匹配：当直接路径为空时，尝试从本地曲库匹配已下载的物理音频
        val resolvedLocalPath: String? = directLocalPath ?: run {
            try {
                val db = ZdsDatabase.getInstance(context)
                val allSongs = db.songDao().getAllSongsList()
                val normTitle = SongMatchingResolver.normalizeTrackTitle(song.title)
                val normArtist = SongMatchingResolver.normalizeArtist(song.artist)
                val matched = allSongs.firstOrNull { s ->
                    val hasFile = !s.localFilePath.isNullOrBlank() && File(s.localFilePath).let { f -> f.exists() && f.length() > 0 }
                    hasFile && (s.id == song.id || (
                        normTitle.isNotBlank() && SongMatchingResolver.normalizeTrackTitle(s.title) == normTitle &&
                        (normArtist.isBlank() || SongMatchingResolver.normalizeArtist(s.artist) == normArtist)
                    ))
                }
                matched?.localFilePath
            } catch (_: Exception) {
                null
            }
        }

        var finalStreamUrl = song.streamUrl

        val isServerSong = song.serverId != "lemon_online" &&
                song.serverId !in listOf("local_storage", "local_folder", "local_saf")

        if (resolvedLocalPath != null) {
            // 本地文件命中，同步更新队列与当前曲目状态
            val updated = song.copy(
                localFilePath = resolvedLocalPath,
                streamUrl = resolvedLocalPath,
                downloadStatus = DownloadStatus.DOWNLOADED
            )
            PlaybackQueueManager.updateCurrentSong(updated)
        } else if (isServerSong) {
            // 服务端歌曲：动态绑定当前活跃服务端的有效 BaseURL 与 Token
            withContext(Dispatchers.IO) {
                try {
                    val db = ZdsDatabase.getInstance(context)
                    val active = db.serverDao().getActiveServer()
                        ?: db.serverDao().getAllServers().firstOrNull { it.id == song.serverId || it.type == ServerType.LEMON_MUSIC }
                    if (active != null && active.type == ServerType.LEMON_MUSIC) {
                        val cleanBase = active.serverUrl.trimEnd('/')
                        val token = active.tokenOrApiKey
                        if (finalStreamUrl.contains("/api/play/local")) {
                            val pathParam = finalStreamUrl.substringAfter("path=").substringBefore("&")
                            finalStreamUrl = if (token.isNotBlank()) {
                                "$cleanBase/api/play/local?path=$pathParam&token=$token"
                            } else {
                                "$cleanBase/api/play/local?path=$pathParam"
                            }
                        } else if (finalStreamUrl.isBlank()) {
                            val cachedPath = LemonMusicProtocol.getServerFilePath(song.id, null)
                            val candidatePath = cachedPath ?: song.relativeFolderPath
                            if (!candidatePath.isNullOrBlank()) {
                                val client = NetworkClientFactory.createOkHttpClient(context)
                                val protocol = LemonMusicProtocol(client, active.serverUrl, active.username, active.tokenOrApiKey)
                                finalStreamUrl = protocol.getStreamUrlForPath(candidatePath)
                            }
                        }
                        if (finalStreamUrl.isNotBlank() && finalStreamUrl != song.streamUrl) {
                            val updated = song.copy(streamUrl = finalStreamUrl)
                            PlaybackQueueManager.updateCurrentSong(updated)
                        }
                    }
                    Unit
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to resolve server stream URL for ${song.title}", e)
                }
                Unit
            }
        } else if (finalStreamUrl.isBlank() || finalStreamUrl.startsWith("lemon_online://") || song.serverId == "lemon_online") {
            // 在线音频流未就绪，通过网络协议异步解析真实播放流
            withContext(Dispatchers.IO) {
                try {
                    val db = ZdsDatabase.getInstance(context)
                    val active = db.serverDao().getActiveServer()
                        ?: db.serverDao().getAllServers().firstOrNull { it.type == ServerType.LEMON_MUSIC }
                    if (active != null) {
                        val client = NetworkClientFactory.createOkHttpClient(context)
                        val protocol = LemonMusicProtocol(client, active.serverUrl, active.username, active.tokenOrApiKey)
                        val source = song.id.removePrefix("lemon_online_").substringBefore("_")
                        val meta = song.rawMetaJson ?: song.relativeFolderPath

                        val settingsPrefs = context.getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE)
                        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                        val isWifi = cm?.activeNetwork?.let { nw ->
                            val caps = cm.getNetworkCapabilities(nw)
                            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
                        } ?: false
                        val preferredQuality = if (isWifi) {
                            settingsPrefs.getString("wifi_stream_quality", "320k") ?: "320k"
                        } else {
                            settingsPrefs.getString("cellular_stream_quality", "128k") ?: "128k"
                        }

                        var realUrl = protocol.resolveOnlineStreamUrl(
                            songId = song.id,
                            source = source,
                            quality = preferredQuality,
                            metaJson = meta
                        ).getOrNull()

                        if (realUrl.isNullOrBlank() && preferredQuality != "128k") {
                            realUrl = protocol.resolveOnlineStreamUrl(
                                songId = song.id,
                                source = source,
                                quality = "128k",
                                metaJson = meta
                            ).getOrNull()
                        }

                        if (!realUrl.isNullOrBlank()) {
                            finalStreamUrl = realUrl
                            val updated = song.copy(streamUrl = realUrl)
                            PlaybackQueueManager.updateCurrentSong(updated)
                        }
                    }
                    Unit
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to resolve online stream URL for ${song.title}", e)
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

        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setArtworkUri(if (song.coverUrl.isNotEmpty()) Uri.parse(song.coverUrl) else null)
            .setExtras(Bundle().apply {
                putBoolean("KEY_IS_OFFLINE", isOffline)
                putString("KEY_SONG_ID", song.id)
                putString("KEY_SERVER_ID", song.serverId)
                putInt("KEY_BITRATE", song.bitRate)
            })
            .build()

        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }
}
