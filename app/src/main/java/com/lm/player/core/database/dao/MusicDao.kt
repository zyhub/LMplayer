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
    @Query("SELECT * FROM songs ORDER BY lastPlayedTimestamp DESC")
    fun getAllSongsFlow(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE serverId = :serverId ORDER BY addedTimestamp DESC")
    fun getSongsByServerFlow(serverId: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE downloadStatus = 'DOWNLOADED' ORDER BY addedTimestamp DESC")
    fun getDownloadedSongsFlow(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isFavorite = 1 ORDER BY addedTimestamp DESC")
    fun getFavoriteSongsFlow(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id = :songId LIMIT 1")
    suspend fun getSongById(songId: String): SongEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<SongEntity>)

    @Update
    suspend fun updateSong(song: SongEntity)

    @Query("SELECT * FROM songs")
    suspend fun getAllSongsList(): List<SongEntity>

    @Query("UPDATE songs SET downloadStatus = :status, localFilePath = :localPath WHERE id = :songId")
    suspend fun updateDownloadStatus(songId: String, status: DownloadStatus, localPath: String?)

    @Query("UPDATE songs SET downloadStatus = :status, localFilePath = :localPath, addedTimestamp = :timestamp WHERE id = :songId")
    suspend fun updateDownloadStatusAndTimestamp(songId: String, status: DownloadStatus, localPath: String?, timestamp: Long)

    @Query("UPDATE songs SET downloadStatus = :status, localFilePath = :localPath, coverUrl = CASE WHEN coverUrl = '' OR coverUrl IS NULL THEN :coverUrl ELSE coverUrl END WHERE id = :songId")
    suspend fun matchAndLinkLocalFile(songId: String, status: DownloadStatus, localPath: String?, coverUrl: String)

    @Query("UPDATE songs SET isFavorite = :isFavorite WHERE id = :songId")
    suspend fun updateFavorite(songId: String, isFavorite: Boolean)

    @Query("UPDATE songs SET isFavorite = :isFavorite WHERE id = :idOrPath OR localFilePath = :idOrPath")
    suspend fun updateFavoriteByIdOrPath(idOrPath: String, isFavorite: Boolean)

    @Query("UPDATE songs SET lastPlayedTimestamp = :timestamp WHERE id = :songId")
    suspend fun updateLastPlayed(songId: String, timestamp: Long)

    @Query("DELETE FROM songs WHERE id IN (:songIds)")
    suspend fun deleteSongsByIds(songIds: List<String>)

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

    @Query("SELECT s.* FROM songs s INNER JOIN playlist_songs ps ON s.id = ps.songId WHERE ps.playlistId = :playlistId ORDER BY ps.orderIndex ASC")
    fun getSongsForPlaylistFlow(playlistId: String): Flow<List<SongEntity>>

    @Query("SELECT s.* FROM songs s INNER JOIN playlist_songs ps ON s.id = ps.songId WHERE ps.playlistId = :playlistId ORDER BY ps.orderIndex ASC")
    suspend fun getSongsForPlaylist(playlistId: String): List<SongEntity>

    @Query("UPDATE playlists SET songCount = (SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId), updatedTimestamp = :timestamp WHERE id = :playlistId")
    suspend fun updateSongCount(playlistId: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM playlists WHERE id LIKE 'discover_%' OR id LIKE 'lemon_rec_%'")
    suspend fun clearDiscoverPlaylists()

    @Query("DELETE FROM playlists WHERE isOnline = 1")
    suspend fun clearOnlinePlaylists()

    @Query("DELETE FROM playlists")
    suspend fun clearAllPlaylists()
}
