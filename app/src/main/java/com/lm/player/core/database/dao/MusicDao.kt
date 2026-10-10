package com.lm.player.core.database.dao

import androidx.room.*
import com.lm.player.core.database.entity.DownloadEntity
import com.lm.player.core.database.entity.PlaylistEntity
import com.lm.player.core.database.entity.PlaylistSongEntity
import com.lm.player.core.database.entity.ServerEntity
import com.lm.player.core.database.entity.SongEntity
import com.lm.player.core.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Deprecated("大曲库下全量查询 Flow 会导致 CursorWindow 2MB 崩溃，请使用 getAllSongsList() 游标分页或定向条件查询")
    @Query("SELECT * FROM songs ORDER BY lastPlayedTimestamp DESC")
    fun getAllSongsFlow(): Flow<List<SongEntity>>

    @Deprecated("大曲库下全量查询 Flow 会导致 CursorWindow 2MB 崩溃，请使用游标分页或定向条件查询")
    @Query("SELECT * FROM songs WHERE serverId = :serverId ORDER BY addedTimestamp DESC")
    fun getSongsByServerFlow(serverId: String): Flow<List<SongEntity>>

    @Deprecated("大曲库下全量查询 Flow 会导致 CursorWindow 2MB 崩溃，请使用游标分页或定向条件查询")
    @Query("SELECT * FROM songs WHERE downloadStatus = 'DOWNLOADED' ORDER BY addedTimestamp DESC")
    fun getDownloadedSongsFlow(): Flow<List<SongEntity>>

    @Deprecated("大曲库下全量查询 Flow 会导致 CursorWindow 2MB 崩溃，请使用游标分页或定向条件查询")
    @Query("SELECT * FROM songs WHERE isFavorite = 1 ORDER BY addedTimestamp DESC")
    fun getFavoriteSongsFlow(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id = :songId LIMIT 1")
    suspend fun getSongById(songId: String): SongEntity?

    /**
     * 播放路由定向候选匹配：按歌曲 ID 与标题精准/模糊筛选，严禁拉取全库导致起播卡顿与内存膨胀
     */
    @Query("SELECT * FROM songs WHERE id = :songId OR title = :title OR (length(:title) >= 2 AND title LIKE '%' || :title || '%') LIMIT 40")
    suspend fun findCandidatesForPlayback(songId: String, title: String): List<SongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongsRaw(songs: List<SongEntity>)

    /**
     * 分块写入歌曲 —— **大曲库必须走这里，不要直接调用 insertSongsRaw**。
     *
     * 背景（2 万首级别的实际风险）：
     *  - Room 的 @Insert(List) 会把整个列表放进**一个事务**里逐行执行；
     *  - songs 表有 8 个索引（serverId / downloadStatus / isFavorite / lastPlayedTimestamp /
     *    addedTimestamp / localFilePath / albumId / artistId），2 万行 = 16 万次索引更新；
     *  - SQLite 在单事务中会把脏页累积在内存里，直到 commit 才落盘 ——
     *    整库同步时这一步的峰值内存可达上百 MB，叠加同一时刻仍在内存里的
     *    原始 JSON 与入库前的实体列表，很容易在 192~256MB 堆的中端机上直接 OOM。
     *
     * 按 [CHUNK] 行分批提交，每批一次独立事务：峰值内存与索引维护量都被限制在单批规模，
     * 且中途失败时已成功的批次不会回滚（对「同步」这种可重入操作是更安全的行为）。
     */
    suspend fun insertSongs(songs: List<SongEntity>) {
        if (songs.isEmpty()) return
        // 单批 500 行：8 个索引 × 500 = 4000 次索引更新/事务，内存与耗时都足够温和
        val chunk = 500
        var index = 0
        while (index < songs.size) {
            val end = minOf(index + chunk, songs.size)
            insertSongsRaw(songs.subList(index, end))
            index = end
        }
    }

    @Update
    suspend fun updateSong(song: SongEntity)

    // 大曲库一次性 SELECT * 会撑爆 CursorWindow 2MB 上限（大曲库时必现
    // "Couldn't read row N from CursorWindow" 崩溃），因此分页读取后聚合。
    //
    // 分页方式改为**主键游标**而不是 LIMIT/OFFSET：
    //  - LIMIT/OFFSET 在深分页时逐页扫描量线性增长（第 N 页要跳过前 N-1 页），大曲库下退化为 O(n²)；
    //  - 且 OFFSET 分页没有稳定排序键，分页期间若有并发写入（同步入库 / 扫描）会漏读或重读同一行。
    // 用 id > lastId ORDER BY id 既能走主键索引，也不受并发插入影响。
    @Query("SELECT * FROM songs WHERE id > :lastId ORDER BY id ASC LIMIT :limit")
    suspend fun getSongsPageByCursor(lastId: String, limit: Int): List<SongEntity>

    suspend fun getAllSongsList(): List<SongEntity> {
        val result = ArrayList<SongEntity>()
        val pageSize = 200
        var lastId = ""
        while (true) {
            val page = getSongsPageByCursor(lastId, pageSize)
            result.addAll(page)
            if (page.size < pageSize) break
            lastId = page.last().id
        }
        return result
    }

    @Query("UPDATE songs SET downloadStatus = :status, localFilePath = :localPath WHERE id = :songId")
    suspend fun updateDownloadStatus(songId: String, status: DownloadStatus, localPath: String?)

    @Query("UPDATE songs SET downloadStatus = :status, localFilePath = :localPath, addedTimestamp = :timestamp WHERE id = :songId")
    suspend fun updateDownloadStatusAndTimestamp(songId: String, status: DownloadStatus, localPath: String?, timestamp: Long)

    @Query("UPDATE songs SET downloadStatus = :status, localFilePath = :localPath, format = :format, bitRate = :bitRate, addedTimestamp = :timestamp WHERE id = :songId")
    suspend fun updateDownloadStatusSpecsAndTimestamp(songId: String, status: DownloadStatus, localPath: String?, format: String, bitRate: Int, timestamp: Long)

    @Query("UPDATE songs SET downloadStatus = :status, localFilePath = :localPath, coverUrl = CASE WHEN coverUrl = '' OR coverUrl IS NULL THEN :coverUrl ELSE coverUrl END WHERE id = :songId")
    suspend fun matchAndLinkLocalFile(songId: String, status: DownloadStatus, localPath: String?, coverUrl: String)

    @Query("UPDATE songs SET isFavorite = :isFavorite WHERE id = :songId")
    suspend fun updateFavorite(songId: String, isFavorite: Boolean)

    @Query("UPDATE songs SET isFavorite = :isFavorite WHERE id = :idOrPath OR localFilePath = :idOrPath")
    suspend fun updateFavoriteByIdOrPath(idOrPath: String, isFavorite: Boolean)

    @Query("UPDATE songs SET isFavorite = 0 WHERE serverId NOT IN ('local_storage', 'local_folder', 'local_saf')")
    suspend fun clearServerFavorites()

    @Query("UPDATE songs SET lastPlayedTimestamp = :timestamp WHERE id = :songId")
    suspend fun updateLastPlayed(songId: String, timestamp: Long)

    @Query("DELETE FROM songs WHERE id IN (:songIds)")
    suspend fun deleteSongsByIdsRaw(songIds: List<String>)

    /**
     * 分块删除歌曲 —— **不要直接调用 deleteSongsByIdsRaw**。
     *
     * Room 会把 `IN (:songIds)` 展开成 N 个绑定参数，而 SQLite 的变量上限是
     * API<30 为 999、API>=30 为 32766。服务端重命名/移动目录后 md5(filePath) 变化，
     * 旧 id 会**成批**变成孤儿，一次传入上千个 id 就会抛
     * `SQLiteException: too many SQL variables`。
     * 该异常此前落在没有 try/catch 的同步链路里 → 未捕获异常 → 进程崩溃；
     * 在 purgeLegacyResidualData 里则被 catch(Exception) 吞掉 → 一条都删不掉（静默失效）。
     */
    suspend fun deleteSongsByIds(songIds: List<String>) {
        if (songIds.isEmpty()) return
        // 500 远低于 API<30 的 999 上限，留足余量
        songIds.chunked(500).forEach { deleteSongsByIdsRaw(it) }
    }

    @Query("DELETE FROM songs WHERE id = :songId")
    suspend fun deleteSongById(songId: String)

    @Query("SELECT * FROM songs WHERE localFilePath = :localPath LIMIT 1")
    suspend fun getSongByLocalPath(localPath: String): SongEntity?

    @Query("DELETE FROM songs WHERE localFilePath = :localPath")
    suspend fun deleteSongByLocalPath(localPath: String)

    @Query("SELECT * FROM songs WHERE serverId IN ('local_storage', 'local_folder', 'local_saf')")
    suspend fun getLocalScannedSongs(): List<SongEntity>

    @Query("DELETE FROM songs")
    suspend fun clearAllSongs()

    @Query("DELETE FROM songs WHERE serverId = :serverId")
    suspend fun deleteSongsByServer(serverId: String)

    @Query("DELETE FROM songs WHERE downloadStatus != 'DOWNLOADED' AND (localFilePath IS NULL OR localFilePath = '')")
    suspend fun clearNonDownloadedSongs()

    @Query("DELETE FROM songs WHERE serverId NOT IN (:validServerIds) AND downloadStatus != 'DOWNLOADED' AND (localFilePath IS NULL OR localFilePath = '')")
    suspend fun deleteOrphanSongs(validServerIds: List<String>)

    @Query("SELECT id FROM songs WHERE serverId = :serverId")
    suspend fun getSongIdsByServer(serverId: String): List<String>

    @Query("UPDATE songs SET serverId = :newServerId WHERE id = :songId")
    suspend fun updateServerId(songId: String, newServerId: String)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE songId = :songId LIMIT 1")
    suspend fun getDownloadRecord(songId: String): DownloadEntity?

    @Query("SELECT * FROM downloads ORDER BY completedTimestamp DESC")
    fun getAllDownloadsFlow(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads")
    suspend fun getAllDownloadsList(): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(download: DownloadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownloads(downloads: List<DownloadEntity>)

    @Query("DELETE FROM downloads WHERE songId = :songId")
    suspend fun deleteDownload(songId: String)

    @Query("DELETE FROM downloads WHERE localFilePath = :localPath")
    suspend fun deleteDownloadByLocalPath(localPath: String)
}

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers")
    fun getAllServersFlow(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers")
    suspend fun getAllServers(): List<ServerEntity>

    @Query("SELECT * FROM servers WHERE isCurrentActive = 1 LIMIT 1")
    suspend fun getActiveServer(): ServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: ServerEntity)

    @Query("UPDATE servers SET isCurrentActive = (id = :activeServerId)")
    suspend fun setActiveServer(activeServerId: String)

    @Query("DELETE FROM servers WHERE id = :serverId")
    suspend fun deleteServer(serverId: String)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY updatedTimestamp DESC")
    fun getAllPlaylistsFlow(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE isOnline = :isOnline ORDER BY updatedTimestamp DESC")
    fun getPlaylistsByModeFlow(isOnline: Boolean): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE serverId = :serverId ORDER BY updatedTimestamp DESC")
    fun getPlaylistsByServerFlow(serverId: String): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY updatedTimestamp DESC")
    suspend fun getAllPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getPlaylistById(playlistId: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylists(playlists: List<PlaylistEntity>)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Query("DELETE FROM playlists WHERE serverId = :serverId")
    suspend fun deletePlaylistsByServer(serverId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSongToPlaylist(playlistSong: PlaylistSongEntity)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: String, songId: String)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearSongsForPlaylist(playlistId: String)

    @Query("DELETE FROM playlist_songs WHERE songId = :songId")
    suspend fun removeSongFromAllPlaylists(songId: String)

    @Query("SELECT s.* FROM songs s INNER JOIN playlist_songs ps ON s.id = ps.songId WHERE ps.playlistId = :playlistId ORDER BY ps.orderIndex ASC")
    fun getSongsForPlaylistFlow(playlistId: String): Flow<List<SongEntity>>

    @Query("SELECT s.* FROM songs s INNER JOIN playlist_songs ps ON s.id = ps.songId WHERE ps.playlistId = :playlistId ORDER BY ps.orderIndex ASC")
    suspend fun getSongsForPlaylist(playlistId: String): List<SongEntity>

    @Query("SELECT s.coverUrl FROM songs s INNER JOIN playlist_songs ps ON s.id = ps.songId WHERE ps.playlistId = :playlistId AND s.coverUrl IS NOT NULL AND s.coverUrl != '' ORDER BY ps.orderIndex ASC LIMIT 4")
    suspend fun getPlaylistCoverUrls(playlistId: String): List<String>

    @Query("UPDATE playlists SET songCount = (SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId), updatedTimestamp = :timestamp WHERE id = :playlistId")
    suspend fun updateSongCount(playlistId: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM playlists WHERE id LIKE 'discover_%' OR id LIKE 'lemon_rec_%'")
    suspend fun clearDiscoverPlaylists()

    @Query("DELETE FROM playlists WHERE isOnline = 1")
    suspend fun clearOnlinePlaylists()

    @Query("DELETE FROM playlists")
    suspend fun clearAllPlaylists()
}
