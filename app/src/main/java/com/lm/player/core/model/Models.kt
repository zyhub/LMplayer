package com.lm.player.core.model

import androidx.compose.runtime.Immutable

@Immutable
data class UnifiedSong(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String = "",
    val album: String = "",
    val albumId: String = "",
    val durationMs: Long = 0L,
    val coverUrl: String = "",
    val streamUrl: String = "",
    val serverId: String = "default",
    val localFilePath: String? = null,
    val downloadStatus: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
    val downloadProgress: Float = 0f,
    val bitRate: Int = 320,
    val format: String = "flac",
    val isFavorite: Boolean = false,
    val relativeFolderPath: String? = null
)

@Immutable
data class UnifiedAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val coverUrl: String,
    val songCount: Int = 0,
    val year: Int? = null
)

@Immutable
data class UnifiedArtist(
    val id: String,
    val name: String,
    val avatarUrl: String = "",
    val albumCount: Int = 0
)

@Immutable
data class UnifiedPlaylist(
    val id: String,
    val name: String,
    val coverUrl: String = "",
    val songCount: Int = 0,
    val isOnline: Boolean = false,
    val serverId: String = "local_storage",
    val previewCovers: List<String> = emptyList(),
    val isDiscover: Boolean = false
)

@Immutable
data class ServerFolderItem(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val parentId: String? = null,
    val childCount: Int = 0,
    val coverUrl: String = "",
    val song: UnifiedSong? = null
)

enum class SongSortOption(val displayName: String) {
    ALBUM("专辑"),
    ALBUM_ARTIST("专辑艺人"),
    ARTIST("作曲家"),
    DATE_ADDED("加入日期"),
    RELEASE_DATE("发行日期"),
    FORMAT("媒体容器"),
    RATING("家长评分"),
    YEAR("年份"),
    DATE_PLAYED("播放日期"),
    DURATION("播放时长"),
    PLAY_COUNT("播放次数"),
    FILENAME("文件名"),
    FILE_SIZE("文件尺寸")
}

enum class SortOrder {
    ASCENDING,
    DESCENDING
}

enum class DownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED
}

enum class ServerType {
    LOCAL_OFFLINE,
    LEMON_MUSIC
}

enum class SyncMode(val displayName: String) {
    DIRECT("直连"),
    BACKGROUND("后台"),
    MANUAL("手动")
}

@Immutable
data class ServerConfig(
    val id: String,
    val name: String,
    val type: ServerType,
    val serverUrl: String,
    val username: String = "",
    val tokenOrApiKey: String = "",
    val saltOrSecret: String = "",
    val syncMode: SyncMode = SyncMode.DIRECT,
    val isCurrentActive: Boolean = false
)

@Immutable
data class DownloadTask(
    val song: UnifiedSong,
    val progress: Float = 0f,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val speedKbps: Long = 0L,
    val status: DownloadStatus = DownloadStatus.DOWNLOADING
)

@Immutable
data class DownloadSettings(
    val maxConcurrent: Int = 3,
    val bitrate: String = "原始无损 (FLAC/高码率)",
    val wifiOnly: Boolean = false,
    val autoTagging: Boolean = true,
    val customDownloadPath: String = ""
)

@Immutable
data class HomeScreenDisplayConfig(
    val showRecentlyPlayed: Boolean = true,
    val showRecentlyAdded: Boolean = true,
    val showAlbums: Boolean = false,
    val showArtists: Boolean = false,
    val showFavorites: Boolean = false
)

@Immutable
data class LyricLine(
    val timestampMs: Long,
    val text: String
)

@Immutable
data class LyricResult(
    val lines: List<LyricLine> = emptyList(),
    val isSynced: Boolean = true
)

enum class LibraryCategory {
    PLAYLISTS,
    ARTISTS,
    ALBUMS,
    SONGS,
    DOWNLOADED,
    FAVORITES,
    FOLDERS
}

enum class Screen {
    HOME,
    LIBRARY,
    DOWNLOADS,
    SETTINGS
}

@Immutable
data class LemonToplist(
    val id: String,
    val name: String,
    val coverUrl: String,
    val updateFrequency: String = "",
    val source: String = "kw"
)

enum class OnlineMusicSource(val key: String, val displayName: String) {
    KUWO("kw", "酷我音乐"),
    NETEASE("wy", "网易云音乐"),
    QQ("tx", "QQ音乐"),
    KUGOU("kg", "酷狗音乐"),
    MIGU("mg", "咪咕音乐");

    companion object {
        fun fromKey(key: String): OnlineMusicSource {
            return entries.firstOrNull { it.key == key } ?: KUWO
        }
    }
}
