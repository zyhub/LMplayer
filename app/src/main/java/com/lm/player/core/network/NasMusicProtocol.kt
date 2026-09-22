package com.lm.player.core.network

import com.lm.player.core.model.*

interface NasMusicProtocol {
    suspend fun authenticate(config: ServerConfig): Result<String>
    suspend fun getSongList(offset: Int = 0, limit: Int = 50): Result<List<UnifiedSong>>
    suspend fun getAlbums(offset: Int = 0, limit: Int = 50): Result<List<UnifiedAlbum>>
    suspend fun getArtists(): Result<List<UnifiedArtist>>
    suspend fun getPlaylists(): Result<List<UnifiedPlaylist>>
    suspend fun getPlaylistSongs(playlistId: String): Result<List<UnifiedSong>>
    suspend fun getFolders(parentId: String? = null): Result<List<ServerFolderItem>>
    suspend fun getFolderSongs(folderId: String): Result<List<UnifiedSong>>
    fun getStreamUrl(songId: String, maxBitrate: Int? = null): String
    fun getCoverArtUrl(mediaId: String, size: Int = 500): String
    suspend fun getLyrics(songId: String): Result<LyricResult>
    suspend fun scrobble(songId: String, submission: Boolean): Result<Unit>
}
