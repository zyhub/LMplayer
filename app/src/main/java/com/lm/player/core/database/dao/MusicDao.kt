package com.lm.player.core.database.dao

import androidx.room.*
import com.lm.player.core.database.entity.DownloadEntity
import com.lm.player.core.database.entity.PlaylistEntity
import com.lm.player.core.database.entity.PlaylistSongEntity
import com.lm.player.core.database.entity.ResolvedStreamCacheEntity
import com.lm.player.core.database.entity.ServerEntity
import com.lm.player.core.database.entity.ServerSourceEntity
import com.lm.player.core.database.entity.ServerSourceMetaEntity
import com.lm.player.core.database.entity.ServerTrackAccelEntity
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

    /** 本地扫描「补封面」阶段使用 */
    /** 补存服务器元数据（老库同步时没存，下载弹窗按需向服务器取到后写回） */
    @Query("UPDATE songs SET rawMetaJson = :meta WHERE id = :songId")
    suspend fun updateRawMetaJson(songId: String, meta: String)

    @Query("UPDATE songs SET coverUrl = :coverUrl WHERE id = :songId")
    suspend fun updateCoverUrl(songId: String, coverUrl: String)

    /** 在线修标签写回后，按 id 同步本地曲库缓存的展示元数据（PR #5） */
    @Query("UPDATE songs SET title = :title, artist = :artist, album = :album, coverUrl = :coverUrl WHERE id = :songId")
    suspend fun updateSongMetadataById(songId: String, title: String, artist: String, album: String, coverUrl: String)

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

    /** 删除历史非柠檬服务器前，先把已下载到本机的曲目转为本地记录，避免误删文件。 */
    @Query("UPDATE songs SET serverId = 'local_storage' WHERE serverId = :serverId AND downloadStatus = 'DOWNLOADED' AND localFilePath IS NOT NULL AND localFilePath != ''")
    suspend fun migrateDownloadedSongsToLocal(serverId: String)

    @Query("DELETE FROM songs WHERE downloadStatus != 'DOWNLOADED' AND (localFilePath IS NULL OR localFilePath = '')")
    suspend fun clearNonDownloadedSongs()

    @Query("DELETE FROM songs WHERE serverId NOT IN (:validServerIds) AND downloadStatus != 'DOWNLOADED' AND (localFilePath IS NULL OR localFilePath = '')")
    suspend fun deleteOrphanSongsRaw(validServerIds: List<String>)

    suspend fun deleteOrphanSongs(validServerIds: List<String>) {
        if (validServerIds.isEmpty()) return
        validServerIds.chunked(500).forEach { deleteOrphanSongsRaw(it) }
    }

    @Query("SELECT id FROM songs WHERE serverId = :serverId")
    suspend fun getSongIdsByServer(serverId: String): List<String>

    /** 服务器歌曲加速（预匹配 / 旧匹配并入）用的轻量列：只取服务器曲库歌曲，不含在线收藏曲目 */
    @Query(
        "SELECT id, title, artist, album, durationMs, coverUrl, streamUrl, localFilePath, downloadStatus, " +
            "isFavorite, lastPlayedTimestamp, rawMetaJson, format, bitRate, serverId FROM songs " +
            "WHERE serverId IN (:serverIds) AND id NOT LIKE 'lemon_online_%'"
    )
    suspend fun getAccelCandidates(serverIds: List<String>): List<AccelCandidateRow>

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
    suspend fun insertDownloadsRaw(downloads: List<DownloadEntity>)

    suspend fun insertDownloads(downloads: List<DownloadEntity>) {
        if (downloads.isEmpty()) return
        downloads.chunked(500).forEach { insertDownloadsRaw(it) }
    }

    @Query("DELETE FROM downloads WHERE songId = :songId")
    suspend fun deleteDownload(songId: String)

    @Query("DELETE FROM downloads WHERE localFilePath = :localPath")
    suspend fun deleteDownloadByLocalPath(localPath: String)

    // 待同步口径必须包含 3 (上次推送失败) 与 2 (推送中，进程被杀后会由
    // OfflineSyncManager 启动时恢复为 1)。只查 1 会让失败任务从看板上彻底消失，
    // 用户既看不到失败原因，也没有重试入口。
    @Query("SELECT * FROM downloads WHERE serverPushStatus IN (1, 2, 3) ORDER BY completedTimestamp DESC")
    fun getPendingServerPushFlow(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE serverPushStatus IN (1, 2, 3) ORDER BY completedTimestamp DESC")
    suspend fun getPendingServerPushList(): List<DownloadEntity>

    @Query("SELECT COUNT(*) FROM downloads WHERE serverPushStatus IN (1, 2, 3)")
    fun getPendingServerPushCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM downloads WHERE serverPushStatus IN (1, 2, 3)")
    suspend fun getPendingServerPushCount(): Int

    @Query("UPDATE downloads SET serverPushStatus = :status, lastPushTime = :pushTime, pushErrorMessage = :errorMessage WHERE songId = :songId")
    suspend fun updateServerPushStatus(songId: String, status: Int, pushTime: Long = System.currentTimeMillis(), errorMessage: String? = null)

    @Query("UPDATE downloads SET serverPushStatus = :status, lastPushTime = :pushTime, pushErrorMessage = :errorMessage WHERE songId IN (:songIds)")
    suspend fun updateServerPushStatusBatchRaw(songIds: List<String>, status: Int, pushTime: Long = System.currentTimeMillis(), errorMessage: String? = null)

    suspend fun updateServerPushStatusBatch(songIds: List<String>, status: Int, pushTime: Long = System.currentTimeMillis(), errorMessage: String? = null) {
        if (songIds.isEmpty()) return
        songIds.chunked(500).forEach { chunk ->
            updateServerPushStatusBatchRaw(chunk, status, pushTime, errorMessage)
        }
    }

    @Query("UPDATE downloads SET serverPushStatus = :status WHERE serverPushStatus = :fromStatus")
    suspend fun markAllServerPushStatus(fromStatus: Int, status: Int)
}

@Dao
interface ServerSourceDao {
    @Query("SELECT * FROM server_sources WHERE serverId = :serverId ORDER BY isActive DESC, name ASC")
    fun observeSources(serverId: String): Flow<List<ServerSourceEntity>>

    @Query("SELECT * FROM server_sources WHERE serverId = :serverId ORDER BY isActive DESC, name ASC")
    suspend fun getSources(serverId: String): List<ServerSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSources(items: List<ServerSourceEntity>)

    @Query("DELETE FROM server_sources WHERE serverId = :serverId")
    suspend fun deleteSources(serverId: String)

    @Query("SELECT * FROM server_source_meta WHERE serverId = :serverId LIMIT 1")
    fun observeMeta(serverId: String): Flow<ServerSourceMetaEntity?>

    @Query("SELECT * FROM server_source_meta WHERE serverId = :serverId LIMIT 1")
    suspend fun getMeta(serverId: String): ServerSourceMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: ServerSourceMetaEntity)

    @Query("SELECT * FROM resolved_stream_cache WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun getCachedStream(cacheKey: String): ResolvedStreamCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedStream(entity: ResolvedStreamCacheEntity)

    @Query("DELETE FROM resolved_stream_cache WHERE expiresAt < :now")
    suspend fun purgeExpiredStreams(now: Long = System.currentTimeMillis())

    @Query("DELETE FROM resolved_stream_cache WHERE serverHost = :serverHost")
    suspend fun clearCachedStreamsForHost(serverHost: String)
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

    @Query("UPDATE servers SET isCurrentActive = 0")
    suspend fun clearActiveServer()

    @Query("UPDATE servers SET serverUrl = :url WHERE id = :serverId")
    suspend fun updateServerUrl(serverId: String, url: String)

    @Query("DELETE FROM servers WHERE id = :serverId")
    suspend fun deleteServer(serverId: String)

    @Query("UPDATE servers SET tokenOrApiKey = :encrypted WHERE id = :serverId")
    suspend fun updateServerToken(serverId: String, encrypted: String)
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

    /** 所有歌单里出现过的歌曲 ID（服务器歌曲预匹配排序用） */
    @Query("SELECT DISTINCT songId FROM playlist_songs")
    suspend fun getAllPlaylistSongIds(): List<String>

    /** 已收录某首歌的歌单 id：「加入歌单」里把已加入的歌单置灰并标「已加入」 */
    @Query("SELECT DISTINCT playlistId FROM playlist_songs WHERE songId = :songId")
    fun getPlaylistIdsContainingSongFlow(songId: String): Flow<List<String>>

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: String, songId: String)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearSongsForPlaylist(playlistId: String)

    @Query("DELETE FROM playlist_songs WHERE songId = :songId")
    suspend fun removeSongFromAllPlaylists(songId: String)

    /** 把歌单里对 [oldSongId] 的引用改指 [newSongId]；同一歌单已有 newSongId 时该行保持不动（OR IGNORE） */
    @Query("UPDATE OR IGNORE playlist_songs SET songId = :newSongId WHERE songId = :oldSongId")
    suspend fun repointPlaylistSongsRaw(oldSongId: String, newSongId: String)

    /**
     * 歌曲记录被另一条记录取代（如在线记录并入服务器曲目）时迁移歌单引用。
     * playlist_songs 没有外键级联，直接删除歌曲会让歌单里这首歌「消失」（Claude，PR #7 合入时补充）。
     */
    @androidx.room.Transaction
    suspend fun migratePlaylistSongRefs(oldSongId: String, newSongId: String) {
        if (oldSongId.isBlank() || newSongId.isBlank() || oldSongId == newSongId) return
        repointPlaylistSongsRaw(oldSongId, newSongId)
        // OR IGNORE 跳过的行（歌单里本来就有新曲目）还挂着旧 id，去重删除
        removeSongFromAllPlaylists(oldSongId)
    }

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

@Dao
interface ServerTrackAccelDao {
    @Query("SELECT * FROM server_track_accel WHERE songId = :songId LIMIT 1")
    suspend fun get(songId: String): ServerTrackAccelEntity?

    @Query("SELECT * FROM server_track_accel WHERE songId IN (:songIds)")
    suspend fun getMany(songIds: List<String>): List<ServerTrackAccelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ServerTrackAccelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ServerTrackAccelEntity>)

    @Query("SELECT songId FROM server_track_accel WHERE serverId = :serverId AND matchStatus = 1")
    suspend fun matchedIds(serverId: String): List<String>

    @Query("SELECT songId, fileKey, matchStatus, lastTriedAt FROM server_track_accel WHERE serverId = :serverId")
    suspend fun statusRows(serverId: String): List<ServerTrackAccelStatusRow>

    @Query("SELECT COUNT(*) FROM server_track_accel WHERE serverId = :serverId AND matchStatus = :status")
    fun observeCount(serverId: String, status: Int): Flow<Int>

    @Query("SELECT COUNT(*) FROM server_track_accel WHERE serverId = :serverId AND matchStatus = :status")
    suspend fun count(serverId: String, status: Int): Int

    @Query("SELECT COUNT(*) FROM server_track_accel WHERE lyricAt > 0")
    suspend fun lyricCount(): Int

    /** 歌词缓存超出上限时清掉最久没更新的那批（只清歌词，匹配数据保留） */
    @Query(
        "UPDATE server_track_accel SET lyricText = '', lyricSource = '', lyricAt = 0 WHERE songId IN (" +
            "SELECT songId FROM server_track_accel WHERE lyricAt > 0 ORDER BY lyricAt ASC LIMIT :n)"
    )
    suspend fun dropOldestLyrics(n: Int)

    /** 有平台封面的行（图片加载时按服务器文件路径查平台封面用） */
    @Query("SELECT fileKey, platformCoverUrl FROM server_track_accel WHERE platformCoverUrl != ''")
    suspend fun coverRows(): List<AccelCoverRow>

    @Query("DELETE FROM server_track_accel WHERE serverId = :serverId")
    suspend fun deleteForServer(serverId: String)

    @Query("DELETE FROM server_track_accel WHERE serverId NOT IN (:serverIds)")
    suspend fun deleteExceptServers(serverIds: List<String>)
}

/** 预匹配排队用的轻量行（不读歌词、匹配 JSON 等大字段） */
data class ServerTrackAccelStatusRow(
    val songId: String,
    val fileKey: String,
    val matchStatus: Int,
    val lastTriedAt: Long
)

/** [SongDao.getAccelCandidates] 的结果行 */
data class AccelCandidateRow(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String,
    val streamUrl: String,
    val localFilePath: String?,
    val downloadStatus: String,
    val isFavorite: Boolean,
    val lastPlayedTimestamp: Long,
    val rawMetaJson: String?,
    val format: String,
    val bitRate: Int,
    val serverId: String
) {
    fun toUnifiedSong(): com.lm.player.core.model.UnifiedSong = com.lm.player.core.model.UnifiedSong(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        coverUrl = coverUrl,
        streamUrl = streamUrl,
        serverId = serverId,
        localFilePath = localFilePath,
        downloadStatus = runCatching { DownloadStatus.valueOf(downloadStatus) }.getOrDefault(DownloadStatus.NOT_DOWNLOADED),
        bitRate = bitRate,
        format = format,
        isFavorite = isFavorite,
        rawMetaJson = rawMetaJson
    )
}

/** [ServerTrackAccelDao.coverRows] 的结果行 */
data class AccelCoverRow(
    val fileKey: String,
    val platformCoverUrl: String
)
