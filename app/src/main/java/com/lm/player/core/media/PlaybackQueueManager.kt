package com.lm.player.core.media

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.model.UnifiedSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
object PlaybackQueueManager {

    private const val TAG = "PlaybackQueueManager"
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

    fun updatePlaylist(songs: List<UnifiedSong>) {
        _playlistFlow.value = songs
        val current = _currentSongFlow.value
        if (current != null) {
            val updated = songs.firstOrNull { it.id == current.id }
            if (updated != null && updated != current) {
                _currentSongFlow.value = updated
            }
        }
    }

    fun updateCurrentSong(song: UnifiedSong) {
        _currentSongFlow.value = song
        _playlistFlow.value = _playlistFlow.value.map { if (it.id == song.id) song else it }
    }

    fun setShuffle(shuffle: Boolean) {
        _isShuffleFlow.value = shuffle
    }

    fun setRepeat(repeat: Boolean) {
        _isRepeatFlow.value = repeat
    }

    fun ensurePlayerListener(context: Context) {
        if (isListenerAttached) return
        val player = Media3Factory.getSharedExoPlayer(context)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlayingFlow.value = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    if (_isRepeatFlow.value && _currentSongFlow.value != null) {
                        playSong(_currentSongFlow.value!!, context)
                    } else {
                        playNext(context)
                    }
                }
            }
        })
        isListenerAttached = true
    }

    fun playSong(targetSong: UnifiedSong, context: Context, newPlaylist: List<UnifiedSong>? = null) {
        ensurePlayerListener(context)
        _currentSongFlow.value = targetSong
        if (newPlaylist != null && newPlaylist.isNotEmpty()) {
            _playlistFlow.value = newPlaylist
        } else if (_playlistFlow.value.isEmpty()) {
            _playlistFlow.value = listOf(targetSong)
        }

        try {
            val prefs = context.getSharedPreferences("zds_auto_play_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("last_played_song_id", targetSong.id).apply()
        } catch (_: Exception) {}

        coroutineScope.launch {
            try {
                val db = ZdsDatabase.getInstance(context)
                val router = PlaybackRouter(db.downloadDao(), context)
                val mediaItem: MediaItem = router.resolveMediaItem(targetSong)
                val player = Media3Factory.getSharedExoPlayer(context)
                player.setMediaItem(mediaItem)
                player.prepare()
                player.play()
                Log.i(TAG, "playSong started:  ()")
            } catch (e: Exception) {
                Log.e(TAG, "Error playing song: ", e)
            }
        }
    }

    fun playNext(context: Context) {
        ensurePlayerListener(context)
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
        Log.i(TAG, "playNext: switching to ")
        playSong(nextSong, context)
    }

    fun playPrevious(context: Context) {
        ensurePlayerListener(context)
        val list = _playlistFlow.value
        if (list.isEmpty()) {
            Log.w(TAG, "playPrevious called but playlist is empty")
            return
        }
        val current = _currentSongFlow.value
        val isShuffle = _isShuffleFlow.value

        val prevSong: UnifiedSong = if (isShuffle) {
            val candidates = if (list.size > 1) list.filter { it.id != current?.id } else list
            candidates.random()
        } else {
            val currentIndex = list.indexOfFirst { it.id == current?.id }
            if (currentIndex > 0) {
                list[currentIndex - 1]
            } else {
                list.last()
            }
        }
        Log.i(TAG, "playPrevious: switching to ")
        playSong(prevSong, context)
    }

    fun togglePlay(context: Context) {
        ensurePlayerListener(context)
        val player = Media3Factory.getSharedExoPlayer(context)
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.currentMediaItem == null && _currentSongFlow.value != null) {
                playSong(_currentSongFlow.value!!, context)
            } else {
                player.play()
            }
        }
    }
}
