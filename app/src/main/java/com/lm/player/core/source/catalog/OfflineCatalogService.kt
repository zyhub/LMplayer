package com.lm.player.core.source.catalog

import com.lm.player.core.model.LemonToplist
import com.lm.player.core.model.UnifiedAlbum
import com.lm.player.core.model.UnifiedPlaylist
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.source.SourceAccelerationController
import com.lm.player.core.source.SourceAccelerationSession

/**
 * 没有可用柠檬服务器时（服务器已删除 / 解绑）给界面用的在线目录服务。
 * 数据全部来自本地目录服务 + 磁盘旧数据兜底；播放由本地音源脚本取链。
 */
object OfflineCatalogService {

    /**
     * 是否可以在没有服务器的情况下提供在线目录（本地音源可用才有意义：否则搜到也播不了）。
     * TV 按需启动：目录本身不需要 JS 引擎，本机缓存了音源脚本即视为可用；播放时取链再按需加载引擎。
     */
    fun isAvailable(): Boolean = SourceAccelerationSession.isReady() || SourceAccelerationController.localSourceConfigured()

    suspend fun searchSongs(keyword: String, source: String, page: Int = 1, limit: Int = 30): List<UnifiedSong> {
        val res = LocalCatalogGateway.executeDetached(CatalogOp.Search(keyword, source, page, limit, "music")) ?: return emptyList()
        return OnlineCatalogParser.searchSongs(res.optJSONObject("data")?.optJSONArray("list"), source)
    }

    /** 同 searchSongs，另返回平台给的结果总数（data.total，没有为 0） */
    suspend fun searchSongsPage(keyword: String, source: String, page: Int = 1, limit: Int = 30): Pair<List<UnifiedSong>, Int> {
        val res = LocalCatalogGateway.executeDetached(CatalogOp.Search(keyword, source, page, limit, "music")) ?: return emptyList<UnifiedSong>() to 0
        val data = res.optJSONObject("data")
        return OnlineCatalogParser.searchSongs(data?.optJSONArray("list"), source) to (data?.optInt("total", 0) ?: 0)
    }

    suspend fun searchAlbums(keyword: String, source: String, page: Int = 1, limit: Int = 30): List<UnifiedAlbum> {
        val res = LocalCatalogGateway.executeDetached(CatalogOp.Search(keyword, source, page, limit, "album")) ?: return emptyList()
        return OnlineCatalogParser.albums(res.optJSONObject("data"), source)
    }

    suspend fun searchPlaylists(keyword: String, source: String, page: Int = 1, limit: Int = 30): List<UnifiedPlaylist> {
        val res = LocalCatalogGateway.executeDetached(CatalogOp.Search(keyword, source, page, limit, "playlist")) ?: return emptyList()
        return OnlineCatalogParser.playlists(res.optJSONObject("data"), source)
    }

    suspend fun recommendPlaylists(source: String, page: Int = 1, limit: Int = 20): List<UnifiedPlaylist> {
        val res = LocalCatalogGateway.executeDetached(CatalogOp.Recommend(source, "hot", page, limit.coerceIn(10, 60))) ?: return emptyList()
        return OnlineCatalogParser.playlists(res.optJSONObject("data"), source)
    }

    suspend fun toplists(source: String): List<LemonToplist> {
        val res = LocalCatalogGateway.executeDetached(CatalogOp.Toplists(source)) ?: return emptyList()
        return OnlineCatalogParser.toplists(res.optJSONObject("data"), source)
    }

    suspend fun toplistSongs(toplistId: String, source: String, limit: Int = 100): List<UnifiedSong> {
        val res = LocalCatalogGateway.executeDetached(CatalogOp.ToplistDetail(source, toplistId, 1, limit)) ?: return emptyList()
        return OnlineCatalogParser.listSongs(res.optJSONObject("data"), source)
    }

    suspend fun newSongs(source: String, limit: Int = 30): List<UnifiedSong> {
        val res = LocalCatalogGateway.executeDetached(CatalogOp.NewList(source, false, "", 1, limit)) ?: return emptyList()
        return OnlineCatalogParser.listSongs(res.optJSONObject("data"), source)
    }

    suspend fun newAlbums(source: String, page: Int = 1, limit: Int = 20): List<UnifiedAlbum> {
        val res = LocalCatalogGateway.executeDetached(CatalogOp.NewList(source, true, "", page, limit)) ?: return emptyList()
        return OnlineCatalogParser.albums(res.optJSONObject("data"), source)
    }

    suspend fun parseExternalPlaylist(urlOrId: String, source: String): List<UnifiedSong> {
        val res = LocalCatalogGateway.executeDetached(CatalogOp.Playlist(source, urlOrId, false)) ?: return emptyList()
        return OnlineCatalogParser.listSongs(res.optJSONObject("data"), source)
    }

    /**
     * 对齐 LemonMusicProtocol.getPlaylistSongs 的三种在线集合 ID：
     * lemon_rec_{平台}_{id}（歌单） / lemon_toplist_{平台}_{id}（榜单） / lemon_discover_album_{平台}_{id}（专辑）。
     */
    suspend fun collectionSongs(collectionId: String, defaultSource: String = "kw"): List<UnifiedSong> {
        fun split(prefix: String, def: String): Pair<String, String> {
            val rest = collectionId.removePrefix(prefix)
            val parts = rest.split("_", limit = 2)
            return (parts.getOrNull(0)?.ifBlank { def } ?: def) to (parts.getOrNull(1) ?: rest)
        }
        return when {
            collectionId.startsWith("lemon_rec_") -> {
                val (src, id) = split("lemon_rec_", defaultSource)
                parseExternalPlaylist(id, src)
            }
            collectionId.startsWith("lemon_toplist_") -> {
                val (src, id) = split("lemon_toplist_", defaultSource)
                toplistSongs(id, src)
            }
            collectionId.startsWith("lemon_discover_album_") -> {
                val (src, id) = split("lemon_discover_album_", "tx")
                val res = LocalCatalogGateway.executeDetached(CatalogOp.AlbumDetail(src, id, 1, 0)) ?: return emptyList()
                OnlineCatalogParser.listSongs(res.optJSONObject("data"), src)
            }
            else -> emptyList()
        }
    }
}
