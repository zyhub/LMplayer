package com.lm.player.core.media

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.lm.player.core.database.dao.DownloadDao
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.UnifiedSong
import java.io.File

class PlaybackRouter(
    private val downloadDao: DownloadDao,
    private val context: Context
) {
    private val TAG = "PlaybackRouter"

    /**
     * 核心路由决策机制：
     * 1. 优先检测本地已下载文件 (Room 下载库、曲目本地路径、默认存储目录)。
     * 2. 存在真实本地文件时，构建标准 file:// Uri，实现 0 等待、0 流量极速秒开。
     * 3. 否则路由至远程 NAS 流媒体 URL，自动接管 Media3 播放与缓存。
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

        val localFilePath: String? = when {
            hasValidRecordFile -> recordPath
            hasValidSongLocalFile -> song.localFilePath
            isStreamUrlLocalFile -> song.streamUrl
            hasDefaultFile -> defaultDownloadFile.absolutePath
            else -> null
        }

        val uri: Uri = if (localFilePath != null) {
            Log.d(TAG, "Routing to local file: $localFilePath")
            Uri.fromFile(File(localFilePath))
        } else if (song.streamUrl.startsWith("content://")) {
            Log.d(TAG, "Routing to SAF content uri: ${song.streamUrl}")
            Uri.parse(song.streamUrl)
        } else {
            Log.d(TAG, "Routing to remote stream: ${song.streamUrl}")
            Uri.parse(song.streamUrl)
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
