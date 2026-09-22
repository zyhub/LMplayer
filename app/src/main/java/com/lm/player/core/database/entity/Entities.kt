package com.lm.player.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.ServerType
import com.lm.player.core.model.SyncMode

@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["serverId"]),
        Index(value = ["downloadStatus"]),
        Index(value = ["isFavorite"]),
        Index(value = ["lastPlayedTimestamp"]),
        Index(value = ["addedTimestamp"]),
        Index(value = ["localFilePath"]),
        Index(value = ["albumId"]),
        Index(value = ["artistId"])
    ]
)
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val artistId: String,
    val album: String,
    val albumId: String,
    val durationMs: Long,
    val coverUrl: String,
    val streamUrl: String,
    val serverId: String,
    val localFilePath: String?,
    val downloadStatus: DownloadStatus,
    val bitRate: Int,
    val format: String,
    val isFavorite: Boolean,
    val relativeFolderPath: String? = null,
    val lastPlayedTimestamp: Long = 0L,
    val addedTimestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["status"]),
        Index(value = ["completedTimestamp"]),
        Index(value = ["localFilePath"])
    ]
)
data class DownloadEntity(
    @PrimaryKey val songId: String,
    val title: String,
    val artist: String,
    val coverUrl: String,
    val localFilePath: String?,
    val remoteUrl: String,
    val status: DownloadStatus,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val errorMessage: String? = null,
    val completedTimestamp: Long = 0L
)

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: ServerType,
    val serverUrl: String,
    val username: String,
    val tokenOrApiKey: String,
    val saltOrSecret: String,
    val syncMode: SyncMode = SyncMode.DIRECT,
    val isCurrentActive: Boolean
)

@Entity(
    tableName = "playlists",
    indices = [
        Index(value = ["serverId"]),
        Index(value = ["isOnline"])
    ]
)
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val coverUrl: String = "",
    val serverId: String = "local_storage",
    val isOnline: Boolean = false,
    val songCount: Int = 0,
    val updatedTimestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["songId"])
    ]
)
data class PlaylistSongEntity(
    val playlistId: String,
    val songId: String,
    val orderIndex: Int = 0
)
