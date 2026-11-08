package com.lm.player.core.podcast

import com.lm.player.core.model.UnifiedSong
import org.json.JSONObject

/** 服务器上的一个订阅（/api/podcasts 返回的 feed） */
data class PodcastFeed(
    val id: String,
    val url: String,
    val title: String,
    val author: String,
    val description: String,
    val imageUrl: String,
    val lastError: String,
    /** 秒（服务端 unixepoch） */
    val lastFetchedAtSec: Long,
    /** 秒（服务端 unixepoch） */
    val createdAtSec: Long,
    val episodeCount: Int
) {
    companion object {
        fun fromJson(o: JSONObject) = PodcastFeed(
            id = o.optString("id"),
            url = o.optString("url"),
            title = o.optString("title").ifBlank { "未命名播客" },
            author = o.optString("author"),
            description = o.optString("description"),
            imageUrl = o.optString("imageUrl"),
            lastError = o.optString("lastError"),
            lastFetchedAtSec = o.optLong("lastFetchedAt"),
            createdAtSec = o.optLong("createdAt"),
            episodeCount = o.optInt("episodeCount")
        )
    }
}

/** 一期单集（/api/podcasts/:id/episodes 返回的 episode） */
data class PodcastEpisode(
    val id: String,
    val feedId: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val durationSec: Long,
    /** 毫秒；0 = 未知 */
    val pubDateMs: Long,
    val imageUrl: String,
    val feedTitle: String,
    val feedAuthor: String,
    val feedImageUrl: String
) {
    val coverUrl: String get() = imageUrl.ifBlank { feedImageUrl }

    companion object {
        fun fromJson(o: JSONObject) = PodcastEpisode(
            id = o.optString("id"),
            feedId = o.optString("feedId"),
            title = o.optString("title").ifBlank { "未命名节目" },
            description = o.optString("description"),
            audioUrl = o.optString("audioUrl"),
            durationSec = o.optLong("duration"),
            pubDateMs = o.optLong("pubDate"),
            imageUrl = o.optString("imageUrl"),
            feedTitle = o.optString("feedTitle"),
            feedAuthor = o.optString("feedAuthor"),
            feedImageUrl = o.optString("feedImageUrl")
        )
    }
}

/** 播客单集与播放队列里 UnifiedSong 之间的换算 */
object PodcastSongs {
    /** 播放队列中播客单集的 serverId 标记 */
    const val SERVER_ID = "lemon_podcast"
    private const val ID_PREFIX = "podcast_"

    fun isPodcast(song: UnifiedSong?): Boolean =
        song != null && (song.serverId == SERVER_ID || song.id.startsWith(ID_PREFIX))

    fun episodeId(song: UnifiedSong): String = song.id.removePrefix(ID_PREFIX)

    fun feedId(song: UnifiedSong): String =
        runCatching { JSONObject(song.rawMetaJson ?: "{}").optString("podcastFeedId") }.getOrDefault("")

    /** 服务器给的原始音频地址（流地址可能已被换成服务器代理地址） */
    fun originalAudioUrl(song: UnifiedSong): String =
        runCatching { JSONObject(song.rawMetaJson ?: "{}").optString("audioUrl") }.getOrDefault("")
            .ifBlank { song.streamUrl }

    fun toSong(ep: PodcastEpisode): UnifiedSong {
        val ext = ep.audioUrl.substringBefore('?').substringAfterLast('.', "").lowercase()
        val format = if (ext in setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")) ext else "mp3"
        val meta = JSONObject().apply {
            put("podcastEpisodeId", ep.id)
            put("podcastFeedId", ep.feedId)
            put("audioUrl", ep.audioUrl)
            put("pubDate", ep.pubDateMs)
        }
        return UnifiedSong(
            id = ID_PREFIX + ep.id,
            title = ep.title,
            artist = ep.feedTitle.ifBlank { ep.feedAuthor }.ifBlank { "播客" },
            album = ep.feedTitle.ifBlank { "播客" },
            albumId = ep.feedId,
            durationMs = ep.durationSec * 1000L,
            coverUrl = ep.coverUrl,
            streamUrl = ep.audioUrl,
            serverId = SERVER_ID,
            bitRate = 128,
            format = format,
            rawMetaJson = meta.toString()
        )
    }

    /** 播放队列里只有 UnifiedSong 时（如重启后恢复）还原出单集的基本信息 */
    fun toEpisode(song: UnifiedSong): PodcastEpisode {
        val meta = runCatching { JSONObject(song.rawMetaJson ?: "{}") }.getOrDefault(JSONObject())
        return PodcastEpisode(
            id = episodeId(song),
            feedId = meta.optString("podcastFeedId", song.albumId),
            title = song.title,
            description = "",
            audioUrl = meta.optString("audioUrl").ifBlank { song.streamUrl },
            durationSec = song.durationMs / 1000L,
            pubDateMs = meta.optLong("pubDate"),
            imageUrl = song.coverUrl,
            feedTitle = song.album,
            feedAuthor = "",
            feedImageUrl = song.coverUrl
        )
    }
}

/** Apple 播客目录的一条搜索结果 */
data class PodcastDirectoryResult(
    val feedUrl: String,
    val title: String,
    val author: String,
    val imageUrl: String,
    val genre: String,
    val trackCount: Int
)
