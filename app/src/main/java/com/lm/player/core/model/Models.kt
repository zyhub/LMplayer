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
    val relativeFolderPath: String? = null,
    val addedTimestamp: Long = 0L,
    val rawMetaJson: String? = null
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
    val albumCount: Int = 0,
    val songCount: Int = 0
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
    val isDiscover: Boolean = false,
    val updatedTimestamp: Long = 0L
)

@Immutable
data class UnifiedFolder(
    val id: String,
    val name: String,
    val path: String = "",
    val songCount: Int = 0,
    val songs: List<UnifiedSong> = emptyList()
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
    FAILED,
    PAUSED
}

enum class ServerType(val displayName: String) {
    LOCAL_OFFLINE("本地离线模式"),
    LEMON_MUSIC("柠檬音乐服务端")
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
    val status: DownloadStatus = DownloadStatus.DOWNLOADING,
    /** 是否为服务器端下载任务（由 NAS 服务端执行，App 只负责展示进度） */
    val isServerTask: Boolean = false,
    /** 服务器任务的错误信息（status 为 FAILED 时展示） */
    val errorMessage: String? = null,
    /** 服务器下载任务对应的服务端文件路径（已完成时用于构造流地址播放） */
    val serverFilePath: String? = null
)

@Immutable
data class DownloadSettings(
    val maxConcurrent: Int = 3,
    val bitrate: String = "原始无损 (FLAC/高码率)",
    val wifiOnly: Boolean = false,
    val autoTagging: Boolean = true,
    val customDownloadPath: String = "",
    val downloadSourcePriority: DownloadSourcePriority = DownloadSourcePriority.CLOUD_FIRST
)

/**
 * 本地下载时的音源优先级。
 * - CLOUD_FIRST（默认）：优先从 NAS/云端服务器原文件下载，失败再回退到在线音源。
 *   适合服务器曲库已有的歌曲，速度快、不占在线音源配额。
 * - ONLINE_FIRST：优先从在线音源下载目标音质，失败再回退到服务器。
 *   适合想要特定音质（如服务器只有无损但想要 320K）的场景。
 */
enum class DownloadSourcePriority(val key: String, val label: String) {
    CLOUD_FIRST("cloud_first", "云端优先（失败自动回退在线）"),
    ONLINE_FIRST("online_first", "在线优先（失败自动回退云端）");

    companion object {
        fun fromKey(key: String?): DownloadSourcePriority =
            entries.firstOrNull { it.key == key } ?: CLOUD_FIRST
    }
}

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

enum class OnlineMusicSource(val key: String, val displayName: String, val shortName: String) {
    KUWO("kw", "酷我音乐", "酷我"),
    NETEASE("wy", "网易云音乐", "网易"),
    QQ("tx", "QQ音乐", "QQ"),
    KUGOU("kg", "酷狗音乐", "酷狗"),
    MIGU("mg", "咪咕音乐", "咪咕");

    companion object {
        fun fromKey(key: String): OnlineMusicSource {
            return entries.firstOrNull { it.key == key } ?: KUWO
        }
    }
}

enum class SearchContentType(val key: String, val displayName: String) {
    SONG("song", "歌曲"),
    ALBUM("album", "专辑"),
    PLAYLIST("playlist", "歌单");

    companion object {
        fun fromKey(key: String): SearchContentType {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: SONG
        }
    }
}

/**
 * 柠檬音乐服务端导入的落雪/澜音音源脚本数据模型
 */
@Immutable
data class LemonSourceScriptInfo(
    val id: String,
    val name: String,
    val description: String = "",
    val author: String = "",
    val version: String = "",
    val homepage: String = "",
    val supportedPlatforms: List<String> = emptyList(),
    val isActive: Boolean = false,
    val healthSummary: String = ""
)

enum class DownloadTarget(val displayName: String, val desc: String) {
    LOCAL("本地下载", "保存到本设备内部存储，离线随时聆听"),
    SERVER("服务器下载", "保存到飞牛/NAS曲库，全终端同步共享"),
    BOTH("双端下载", "同时推送到服务器曲库并下载到本地离线存储")
}


enum class AudioQuality(val key: String, val label: String, val format: String, val badge: String, val bitrate: Int) {
    Q_128K("128k", "标准音质", "MP3", "128K", 128),
    Q_320K("320k", "极高音质", "MP3", "320K", 320),
    Q_FLAC("flac", "无损音质", "FLAC", "FLAC", 960),
    Q_HIRES("flac24bit", "Hi-Res 高解析母带", "FLAC", "Hi-Res", 1411);

    fun onlineStreamTag(): String = when (this) {
        Q_128K -> "标准 128K"
        Q_320K -> "极高 320K"
        Q_FLAC -> "无损 FLAC"
        Q_HIRES -> "Hi-Res 无损"
    }

    fun estimateSizeText(song: UnifiedSong): String {
        val rawJson = song.rawMetaJson?.takeIf { it.trim().startsWith("{") }
            ?: song.relativeFolderPath?.takeIf { it.trim().startsWith("{") }
        if (!rawJson.isNullOrBlank()) {
            try {
                val obj = org.json.JSONObject(rawJson)
                val typesObj = obj.optJSONObject("_types")
                if (typesObj != null) {
                    val qObj = typesObj.optJSONObject(key)
                    val s = qObj?.optString("size")?.trim().orEmpty()
                    if (s.isNotBlank() && !s.equals("null", true) && !s.startsWith("0")) {
                        return s.replace(Regex("(?i)(\\d)(mb|kb|gb)"), "$1 $2").uppercase(java.util.Locale.US)
                    }
                }
                val typesArr = obj.optJSONArray("types")
                if (typesArr != null) {
                    for (i in 0 until typesArr.length()) {
                        val item = typesArr.optJSONObject(i) ?: continue
                        if (item.optString("type").equals(key, ignoreCase = true)) {
                            val s = item.optString("size").trim()
                            if (s.isNotBlank() && !s.equals("null", true) && !s.startsWith("0")) {
                                return s.replace(Regex("(?i)(\\d)(mb|kb|gb)"), "$1 $2").uppercase(java.util.Locale.US)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val batchMultiplier = if (song.id.startsWith("batch_download_")) {
            song.id.removePrefix("batch_download_").toIntOrNull()?.coerceAtLeast(1) ?: 1
        } else 1
        val durationSec = if (song.durationMs > 1000L) (song.durationMs / 1000.0).coerceIn(30.0, 1800.0) else 215.0
        val singleMb = (durationSec * bitrate * 1000.0 / 8.0) / (1024.0 * 1024.0)
        val totalMb = singleMb * batchMultiplier
        return if (totalMb >= 1024.0) {
            "约 %.2f GB".format(java.util.Locale.US, totalMb / 1024.0)
        } else {
            "约 %.1f MB".format(java.util.Locale.US, totalMb)
        }
    }

    fun labelWithSize(song: UnifiedSong): String = "$label (${estimateSizeText(song)})"

    companion object {
        fun fromKey(key: String): AudioQuality {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: Q_320K
        }
    }
}

@Immutable
data class LemonServerDownloadTask(
    val id: String = "",
    val name: String,
    val singer: String,
    val source: String = "kw",
    val album: String = "",
    val interval: String = "",
    val quality: String = "320k",
    val songId: String = "",
    val songmid: String = "",
    val hash: String = "",
    val rid: String = "",
    val copyrightId: String = "",
    val img: String = "",
    val platform: String = source,
    val pic: String = img,
    val raw: String = ""
)

@Immutable
data class LemonDownloadPreferences(
    val defaultTarget: DownloadTarget = DownloadTarget.LOCAL,
    val defaultQuality: AudioQuality = AudioQuality.Q_320K,
    val maxConcurrent: Int = 3,
    val embedCover: Boolean = true,
    val embedLyric: Boolean = true,
    val downloadLrcFile: Boolean = false,
    val existFileMode: String = "skip", // skip | overwrite
    val groupByFolder: Boolean = false
)

@Immutable
data class ResolvedOnlineStream(
    val url: String,
    val qualityKey: String,
    val format: String,
    val bitRate: Int,
    val isDowngraded: Boolean = false
)

@Immutable
data class UnifiedGenre(
    val id: String,
    val name: String,
    val trackCount: Int = 0,
    val coverUrl: String = ""
)

@Immutable
data class LemonScanStatus(
    val isScanning: Boolean = false,
    val cachedCount: Int = 0,
    val pendingCount: Int = 0,
    val total: Int = 0
)
