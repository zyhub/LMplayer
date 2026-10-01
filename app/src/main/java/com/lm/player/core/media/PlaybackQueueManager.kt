package com.lm.player.core.media

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.UnifiedSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@OptIn(UnstableApi::class)
object PlaybackQueueManager {

    private const val TAG = "PlaybackQueueManager"
    private const val AUTO_PLAY_PREFS = "zds_auto_play_prefs"
    private const val MAX_PERSISTED_QUEUE_SIZE = 120

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var playJob: Job? = null
    private var positionSaveJob: Job? = null

    private val _playlistFlow = MutableStateFlow<List<UnifiedSong>>(emptyList())
    val playlistFlow: StateFlow<List<UnifiedSong>> = _playlistFlow.asStateFlow()

    private val _currentSongFlow = MutableStateFlow<UnifiedSong?>(null)
    val currentSongFlow: StateFlow<UnifiedSong?> = _currentSongFlow.asStateFlow()

    private val _isPlayingFlow = MutableStateFlow(false)
    val isPlayingFlow: StateFlow<Boolean> = _isPlayingFlow.asStateFlow()

    private val _isShuffleFlow = MutableStateFlow(false)
    val isShuffleFlow: StateFlow<Boolean> = _isShuffleFlow.asStateFlow()

    private val _isRepeatFlow = MutableStateFlow(false)
    val isRepeatFlow: StateFlow<Boolean> = _isRepeatFlow.asStateFlow()

    private var isListenerAttached = false
    private var appContext: Context? = null

    private fun songToJson(song: UnifiedSong): JSONObject {
        return JSONObject().apply {
            put("id", song.id)
            put("title", song.title)
            put("artist", song.artist)
            put("artistId", song.artistId)
            put("album", song.album)
            put("albumId", song.albumId)
            put("durationMs", song.durationMs)
            put("coverUrl", song.coverUrl)
            put("streamUrl", song.streamUrl)
            put("serverId", song.serverId)
            put("localFilePath", song.localFilePath ?: "")
            put("downloadStatus", song.downloadStatus.name)
            put("bitRate", song.bitRate)
            put("format", song.format)
            put("isFavorite", song.isFavorite)
            put("relativeFolderPath", song.relativeFolderPath ?: "")
            put("addedTimestamp", song.addedTimestamp)
            put("rawMetaJson", song.rawMetaJson ?: "")
        }
    }

    private fun jsonToSong(obj: JSONObject): UnifiedSong? {
        val id = obj.optString("id", "").trim()
        val title = obj.optString("title", "").trim()
        if (id.isEmpty() && title.isEmpty()) return null
        val dlStatusStr = obj.optString("downloadStatus", DownloadStatus.NOT_DOWNLOADED.name)
        val dlStatus = try {
            DownloadStatus.valueOf(dlStatusStr)
        } catch (_: Exception) {
            DownloadStatus.NOT_DOWNLOADED
        }
        return UnifiedSong(
            id = id.ifEmpty { "restored_${title.hashCode()}" },
            title = title.ifEmpty { "未知曲目" },
            artist = obj.optString("artist", "未知歌手"),
            artistId = obj.optString("artistId", ""),
            album = obj.optString("album", ""),
            albumId = obj.optString("albumId", ""),
            durationMs = obj.optLong("durationMs", 0L),
            coverUrl = obj.optString("coverUrl", ""),
            streamUrl = obj.optString("streamUrl", ""),
            serverId = obj.optString("serverId", "default"),
            localFilePath = obj.optString("localFilePath", "").ifBlank { null },
            downloadStatus = dlStatus,
            bitRate = obj.optInt("bitRate", 320),
            format = obj.optString("format", "flac"),
            isFavorite = obj.optBoolean("isFavorite", false),
            relativeFolderPath = obj.optString("relativeFolderPath", "").ifBlank { null },
            addedTimestamp = obj.optLong("addedTimestamp", 0L),
            rawMetaJson = obj.optString("rawMetaJson", "").ifBlank { null }
        )
    }

    private fun queueToJson(queue: List<UnifiedSong>, currentSongId: String?): String {
        if (queue.isEmpty()) return "[]"
        val windowed = if (queue.size <= MAX_PERSISTED_QUEUE_SIZE) {
            queue
        } else {
            val idx = queue.indexOfFirst { it.id == currentSongId }.coerceAtLeast(0)
            val half = MAX_PERSISTED_QUEUE_SIZE / 2
            val start = (idx - half).coerceAtLeast(0)
            val end = (start + MAX_PERSISTED_QUEUE_SIZE).coerceAtMost(queue.size)
            val adjustedStart = (end - MAX_PERSISTED_QUEUE_SIZE).coerceAtLeast(0)
            queue.subList(adjustedStart, end)
        }
        val arr = JSONArray()
        for (s in windowed) {
            arr.put(songToJson(s))
        }
        return arr.toString()
    }

    fun getSavedLastSong(context: Context): UnifiedSong? {
        return try {
            val prefs = context.getSharedPreferences(AUTO_PLAY_PREFS, Context.MODE_PRIVATE)
            val rawJson = prefs.getString("last_played_song_json", null)
            if (!rawJson.isNullOrBlank()) {
                jsonToSong(JSONObject(rawJson))
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse saved last song", e)
            null
        }
    }

    fun getSavedQueue(context: Context): List<UnifiedSong> {
        return try {
            val prefs = context.getSharedPreferences(AUTO_PLAY_PREFS, Context.MODE_PRIVATE)
            val rawJson = prefs.getString("last_played_queue_json", null)
            if (!rawJson.isNullOrBlank()) {
                val arr = JSONArray(rawJson)
                val list = ArrayList<UnifiedSong>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    jsonToSong(obj)?.let { list.add(it) }
                }
                list
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse saved queue", e)
            emptyList()
        }
    }

    fun getSavedPositionMs(context: Context): Long {
        return try {
            val prefs = context.getSharedPreferences(AUTO_PLAY_PREFS, Context.MODE_PRIVATE)
            prefs.getLong("last_played_position_ms", 0L).coerceAtLeast(0L)
        } catch (_: Exception) {
            0L
        }
    }

    fun savePlaybackState(
        context: Context? = appContext,
        song: UnifiedSong? = _currentSongFlow.value,
        positionMs: Long? = null,
        commitSync: Boolean = false
    ) {
        val ctx = context ?: appContext ?: return
        val target = song ?: _currentSongFlow.value ?: return
        try {
            val prefs = ctx.getSharedPreferences(AUTO_PLAY_PREFS, Context.MODE_PRIVATE)
            val editor = prefs.edit()
                .putString("last_played_song_id", target.id)
                .putString("last_played_song_title", target.title)
                .putString("last_played_song_artist", target.artist)
                .putString("last_played_song_json", songToJson(target).toString())
            if (_playlistFlow.value.isNotEmpty()) {
                editor.putString("last_played_queue_json", queueToJson(_playlistFlow.value, target.id))
            }
            if (positionMs != null && positionMs >= 0L) {
                editor.putLong("last_played_position_ms", positionMs)
            }
            if (commitSync) {
                editor.commit()
            } else {
                editor.apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save playback state", e)
        }
    }

    fun initFromPrefs(context: Context) {
        appContext = context.applicationContext
        try {
            val prefs = context.getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE)
            _isShuffleFlow.value = prefs.getBoolean("playback_is_shuffle", false)
            _isRepeatFlow.value = prefs.getBoolean("playback_is_repeat", false)
        } catch (_: Exception) {}

        try {
            val savedQueue = getSavedQueue(context)
            if (_playlistFlow.value.isEmpty() && savedQueue.isNotEmpty()) {
                _playlistFlow.value = savedQueue
            }
            val savedSong = getSavedLastSong(context)
            if (_currentSongFlow.value == null && savedSong != null) {
                _currentSongFlow.value = savedSong
            }
        } catch (_: Exception) {}
    }

    fun setQueue(songs: List<UnifiedSong>) {
        _playlistFlow.value = songs
        savePlaybackState(commitSync = false)
    }

    fun setInitialSongIfAbsent(song: UnifiedSong, playlist: List<UnifiedSong>) {
        if (_currentSongFlow.value == null) {
            _currentSongFlow.value = song
            if (_playlistFlow.value.isEmpty() && playlist.isNotEmpty()) {
                _playlistFlow.value = playlist
            }
        }
    }

    private fun mergeSongMetadata(existing: UnifiedSong, incoming: UnifiedSong): UnifiedSong {
        val validExistingLocal = existing.localFilePath?.takeIf {
            it.isNotBlank() && (it.startsWith("content://") || java.io.File(it).exists())
        }
        val validIncomingLocal = incoming.localFilePath?.takeIf {
            it.isNotBlank() && (it.startsWith("content://") || java.io.File(it).exists())
        }
        val effectiveLocal = validIncomingLocal ?: validExistingLocal
        val effectiveStream = when {
            !effectiveLocal.isNullOrBlank() -> effectiveLocal
            incoming.streamUrl.isNotBlank() && !incoming.streamUrl.startsWith("lemon_online://") -> incoming.streamUrl
            existing.streamUrl.isNotBlank() -> existing.streamUrl
            else -> incoming.streamUrl
        }
        return incoming.copy(
            localFilePath = effectiveLocal,
            streamUrl = effectiveStream,
            downloadStatus = if (!effectiveLocal.isNullOrBlank()) DownloadStatus.DOWNLOADED else incoming.downloadStatus,
            rawMetaJson = incoming.rawMetaJson ?: existing.rawMetaJson
        )
    }

    fun updateMetadata(songs: List<UnifiedSong>) {
        if (_playlistFlow.value.isEmpty()) {
            _playlistFlow.value = songs
        } else {
            val songMap = songs.associateBy { it.id }
            _playlistFlow.value = _playlistFlow.value.map { existing ->
                val matched = songMap[existing.id]
                if (matched != null) mergeSongMetadata(existing, matched) else existing
            }
        }
        val current = _currentSongFlow.value
        if (current != null) {
            val updated = songs.firstOrNull { it.id == current.id }
            if (updated != null) {
                val merged = mergeSongMetadata(current, updated)
                if (merged != current) {
                    _currentSongFlow.value = merged
                    savePlaybackState(song = merged, commitSync = false)
                }
            }
        }
    }

    fun updatePlaylist(songs: List<UnifiedSong>) {
        _playlistFlow.value = songs
        val current = _currentSongFlow.value
        if (current != null) {
            val updated = songs.firstOrNull { it.id == current.id }
            if (updated != null && updated != current) {
                _currentSongFlow.value = mergeSongMetadata(current, updated)
            }
        }
        savePlaybackState(commitSync = false)
    }

    fun updateCurrentSong(song: UnifiedSong) {
        _currentSongFlow.value = song
        _playlistFlow.value = _playlistFlow.value.map { if (it.id == song.id) song else it }
        savePlaybackState(song = song, commitSync = false)
    }

    fun setShuffle(shuffle: Boolean) {
        _isShuffleFlow.value = shuffle
        try {
            appContext?.getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE)
                ?.edit()?.putBoolean("playback_is_shuffle", shuffle)?.apply()
        } catch (_: Exception) {}
    }

    fun setRepeat(repeat: Boolean) {
        _isRepeatFlow.value = repeat
        try {
            appContext?.getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE)
                ?.edit()?.putBoolean("playback_is_repeat", repeat)?.apply()
        } catch (_: Exception) {}
    }

    private fun startPeriodicPositionSave(context: Context, player: Player) {
        positionSaveJob?.cancel()
        positionSaveJob = coroutineScope.launch {
            while (isActive && _isPlayingFlow.value) {
                delay(5000L)
                if (player.isPlaying) {
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    if (pos > 0L) {
                        savePlaybackState(context, _currentSongFlow.value, positionMs = pos, commitSync = false)
                    }
                }
            }
        }
    }

    private var lastBgErrorRetrySongId: String = ""
    private var lastBgErrorRetryTimeMs: Long = 0L

    fun ensurePlayerListener(context: Context) {
        val appCtx = context.applicationContext
        if (appContext == null) {
            appContext = appCtx
        }
        if (isListenerAttached) return
        val player = Media3Factory.getSharedExoPlayer(appCtx)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlayingFlow.value = playing
                if (playing) {
                    PlaybackService.ensureServiceAlive(appCtx)
                    startPeriodicPositionSave(appCtx, player)
                } else {
                    positionSaveJob?.cancel()
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    if (pos > 0L) {
                        savePlaybackState(appCtx, _currentSongFlow.value, positionMs = pos, commitSync = false)
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    if (_isRepeatFlow.value && _currentSongFlow.value != null) {
                        playSong(_currentSongFlow.value!!, appCtx)
                    } else {
                        playNext(appCtx)
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // 车机/手机挂后台且主界面未处于前台时的自动容灾重连与切歌保障
                val targetSong = _currentSongFlow.value ?: return
                val resumePos = player.currentPosition.coerceAtLeast(0L)
                val now = System.currentTimeMillis()
                val canRetry = lastBgErrorRetrySongId != targetSong.id || (now - lastBgErrorRetryTimeMs) > 12_000L
                if (canRetry) {
                    lastBgErrorRetrySongId = targetSong.id
                    lastBgErrorRetryTimeMs = now
                    Log.w(TAG, "Background onPlayerError (${error.message}), auto-retrying ${targetSong.title} at ${resumePos}ms")
                    playSong(
                        targetSong = targetSong,
                        context = appCtx,
                        startPositionMs = resumePos,
                        forceRefresh = true
                    )
                }
            }
        })
        isListenerAttached = true
    }

    fun playSong(
        targetSong: UnifiedSong,
        context: Context,
        newPlaylist: List<UnifiedSong>? = null,
        startPositionMs: Long = 0L,
        forceRefresh: Boolean = false
    ) {
        val appCtx = context.applicationContext
        ensurePlayerListener(appCtx)
        PlaybackService.ensureServiceAlive(appCtx)
        _currentSongFlow.value = targetSong
        if (newPlaylist != null && newPlaylist.isNotEmpty()) {
            _playlistFlow.value = newPlaylist
        } else if (_playlistFlow.value.isEmpty()) {
            _playlistFlow.value = listOf(targetSong)
        } else if (_playlistFlow.value.none { it.id == targetSong.id }) {
            _playlistFlow.value = listOf(targetSong) + _playlistFlow.value
        }

        // 立即同步持久化当前播放歌曲完整元数据、播放队列与起始进度，确保任意时刻关闭应用均可精准恢复
        savePlaybackState(
            context = appCtx,
            song = targetSong,
            positionMs = startPositionMs.coerceAtLeast(0L),
            commitSync = true
        )

        playJob?.cancel()
        playJob = coroutineScope.launch {
            try {
                val db = ZdsDatabase.getInstance(appCtx)
                val router = PlaybackRouter(db.downloadDao(), appCtx)
                val mediaItem: MediaItem = router.resolveMediaItem(targetSong, forceRefresh = forceRefresh)
                val player = Media3Factory.getSharedExoPlayer(appCtx)
                if (startPositionMs > 0L) {
                    player.setMediaItem(mediaItem, startPositionMs)
                } else {
                    player.setMediaItem(mediaItem)
                }
                player.prepare()
                player.play()
                AudioSharingManager.onPhoneSongChangedIfCasting(appCtx, targetSong, startPositionMs)
                Log.i(TAG, "playSong started: ${targetSong.title} (startPos=${startPositionMs}ms, forceRefresh=$forceRefresh)")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error playing song: ", e)
            }
        }
    }

    fun playNext(context: Context) {
        val appCtx = context.applicationContext
        ensurePlayerListener(appCtx)
        val list = _playlistFlow.value
        if (list.isEmpty()) {
            Log.w(TAG, "playNext called but playlist is empty")
            return
        }
        val current = _currentSongFlow.value
        val isShuffle = _isShuffleFlow.value

        val nextSong: UnifiedSong = if (isShuffle) {
            val candidates = if (list.size > 1) list.filter { it.id != current?.id } else list
            candidates.random()
        } else {
            val currentIndex = list.indexOfFirst { it.id == current?.id }
            if (currentIndex >= 0 && currentIndex < list.size - 1) {
                list[currentIndex + 1]
            } else {
                list.first()
            }
        }
        Log.i(TAG, "playNext: switching to ${nextSong.title}")
        playSong(nextSong, appCtx)
    }

    fun playPrevious(context: Context) {
        val appCtx = context.applicationContext
        ensurePlayerListener(appCtx)
        val list = _playlistFlow.value
        if (list.isEmpty()) {
            Log.w(TAG, "playPrevious called but playlist is empty")
            return
        }
        val current = _currentSongFlow.value
        val isShuffle = _isShuffleFlow.value

        val prevSong: UnifiedSong = if (list.size > 1 && isShuffle) {
            list.filter { it.id != current?.id }.random()
        } else {
            val currentIndex = list.indexOfFirst { it.id == current?.id }
            if (currentIndex > 0) {
                list[currentIndex - 1]
            } else {
                list.last()
            }
        }
        Log.i(TAG, "playPrevious: switching to ${prevSong.title}")
        playSong(prevSong, appCtx)
    }

    fun togglePlay(context: Context) {
        val appCtx = context.applicationContext
        ensurePlayerListener(appCtx)
        PlaybackService.ensureServiceAlive(appCtx)
        val player = Media3Factory.getSharedExoPlayer(appCtx)
        if (player.isPlaying) {
            player.pause()
            AudioSharingManager.onPhonePlayStateChangedIfCasting(appCtx, false)
        } else {
            if (player.currentMediaItem == null && _currentSongFlow.value != null) {
                val savedPos = getSavedPositionMs(appCtx)
                playSong(_currentSongFlow.value!!, appCtx, startPositionMs = savedPos)
            } else {
                player.play()
                AudioSharingManager.onPhonePlayStateChangedIfCasting(appCtx, true)
            }
        }
    }
}
