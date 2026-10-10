package com.lm.player.core.media

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.model.AudioQuality
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.network.LemonMusicProtocol
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
    private const val RECENT_PLAY_PREFS = "zds_recent_play_prefs"
    private const val KEY_RECENT_PLAYED_SONGS = "recent_played_songs_json"
    private const val MAX_RECENT_PLAY_SIZE = 50

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

    private val _recentPlayedSongsFlow = MutableStateFlow<List<UnifiedSong>>(emptyList())
    val recentPlayedSongsFlow: StateFlow<List<UnifiedSong>> = _recentPlayedSongsFlow.asStateFlow()
    @Volatile private var recentPlayedPrimed = false

    /**
     * 收藏切换去抖：记录每首歌最近一次切换的时间戳。
     *
     * 连点红心会产生多次并发的「本机 Room 写入 + 远端整表同步 + 可能的服务器下载任务」，
     * 既浪费请求，也会因为服务端 user-data 的整表读改写而彼此覆盖（用户看到「点了没反应」）。
     * 同一首歌 600ms 内只接受一次切换。
     */
    private val favoriteToggleGuard = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // 注意：监听器幂等判定已改为 listenerAttachedPlayerRef（弱引用），见 ensurePlayerListener
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

    /**
     * 记录一次真实播放：把 [song] 置顶到最近播放足迹。
     *
     * 同一首歌重复播放只做"置顶"，绝不新增第二条记录 —— 判定不只看 id，
     * 还复用 [SongMatchingResolver.isSongMatch]（含专辑/版本/时长比对），
     * 因为同一首歌从资料库、下载列表、搜索结果点播时可能带着不同的 id 进来。
     */
    fun addRecentPlayedSong(context: Context, song: UnifiedSong) {
        try {
            val current = _recentPlayedSongsFlow.value
            var enriched = song
            var duplicateOf: UnifiedSong? = null
            for (s in current) {
                if (s.id == song.id || SongMatchingResolver.isSongMatch(
                        title1 = s.title, artist1 = s.artist, durationMs1 = s.durationMs,
                        title2 = song.title, artist2 = song.artist, durationMs2 = song.durationMs,
                        album1 = s.album, album2 = song.album
                    )
                ) {
                    // 同曲重播：保留已有的本地路径/收藏等元数据，只把位置提到最前
                    enriched = mergeSongMetadata(s, song)
                    duplicateOf = s
                    break
                }
            }

            val updated = ArrayList<UnifiedSong>(MAX_RECENT_PLAY_SIZE)
            updated.add(enriched)
            for (s in current) {
                if (updated.size >= MAX_RECENT_PLAY_SIZE) break
                if (duplicateOf != null) {
                    // 已经用置顶后的新条目代表这首歌，原位置的旧条目直接丢弃，避免重复
                    if (s.id != duplicateOf.id && s.id != enriched.id) updated.add(s)
                } else if (s.id != song.id) {
                    updated.add(s)
                }
            }
            _recentPlayedSongsFlow.value = updated
            persistRecentPlayed(context, updated)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record recent played song", e)
        }
    }

    /**
     * 用服务器返回的播放记录补齐本机足迹。
     *
     * 本机足迹是主账本 —— 它是唯一记录了"这台设备什么时候听了什么"的地方，
     * 而服务器记录来自所有端且没有可用的时间戳。若让服务器记录整体前置或重排，
     * 程序重启、切到本地再切回在线都会在下次同步后把本机足迹挤掉，
     * 表现为"最近播放被重置"。
     * 因此只在末尾追加本机还没有的曲目（其他端听过、这台设备没听过的），
     * 保持本机已有顺序与位置完全不变。
     */
    fun rememberRecentPlayed(context: Context, songs: List<UnifiedSong>) {
        if (songs.isEmpty()) return
        try {
            val current = _recentPlayedSongsFlow.value
            if (current.isEmpty()) {
                // 本机还没有足迹（全新安装/首次开启在线模式）：直接采用服务器记录
                val seeded = ArrayList<UnifiedSong>(MAX_RECENT_PLAY_SIZE)
                val seen = HashSet<String>(songs.size)
                for (s in songs) {
                    if (seeded.size >= MAX_RECENT_PLAY_SIZE) break
                    if (seen.add(s.id)) seeded.add(s)
                }
                _recentPlayedSongsFlow.value = seeded
                persistRecentPlayed(context, seeded)
                return
            }

            val knownIds = HashSet<String>(current.size)
            for (s in current) knownIds.add(s.id)
            val merged = ArrayList<UnifiedSong>(MAX_RECENT_PLAY_SIZE)
            merged.addAll(current)

            for (s in songs) {
                if (merged.size >= MAX_RECENT_PLAY_SIZE) break
                // 已在榜：只做元数据增益（更新鲜的流地址/封面），位置不动
                val existingIdx = merged.indexOfFirst { it.id == s.id }
                if (existingIdx >= 0) {
                    merged[existingIdx] = mergeSongMetadata(merged[existingIdx], s)
                    continue
                }
                if (!knownIds.add(s.id)) continue
                merged.add(s)
            }

            _recentPlayedSongsFlow.value = merged
            persistRecentPlayed(context, merged)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to merge recent played songs", e)
        }
    }

    /** 进程启动时从磁盘恢复足迹（只生效一次，幂等） */
    fun primeRecentPlayedFromPrefs(context: Context) {
        if (recentPlayedPrimed) return
        recentPlayedPrimed = true
        val loaded = try {
            val prefs = context.getSharedPreferences(RECENT_PLAY_PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_RECENT_PLAYED_SONGS, null)
            if (raw.isNullOrBlank()) {
                emptyList()
            } else {
                val arr = JSONArray(raw)
                val list = ArrayList<UnifiedSong>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    jsonToSong(obj)?.let { list.add(it) }
                }
                list
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse recent played songs", e)
            emptyList()
        }
        if (loaded.isNotEmpty() && _recentPlayedSongsFlow.value.isEmpty()) {
            _recentPlayedSongsFlow.value = loaded
        }
    }

    private fun persistRecentPlayed(context: Context, songs: List<UnifiedSong>, commitSync: Boolean = false) {
        try {
            val arr = JSONArray()
            for (s in songs) arr.put(songToJson(s))
            val editor = context.getSharedPreferences(RECENT_PLAY_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_RECENT_PLAYED_SONGS, arr.toString())
            if (commitSync) editor.commit() else editor.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist recent played songs", e)
        }
    }

    /**
     * 把最近播放足迹同步落盘。普通播放走 apply() 异步写盘即可，
     * 但「彻底退出程序」会在写完之前销毁进程，异步写有丢失风险，
     * 于是退出路径上额外用 commit() 强制刷一次 —— 这是"程序退出后最近播放被重置"的兜底。
     */
    fun flushRecentPlayed(context: Context? = appContext) {
        val ctx = context ?: appContext ?: return
        persistRecentPlayed(ctx, _recentPlayedSongsFlow.value, commitSync = true)
    }

    /**
     * 落盘专用串行执行器。
     * 主线程上的 commit() 是同步磁盘写，慢速 eMMC 上会直接掉帧甚至 ANR ——
     * 手机端此前在**每次点播/切歌**（playSong）、onPause/onStop/onDestroy 都走主线程 commit，
     * 并要序列化最多 120 首队列 JSON（MAX_PERSISTED_QUEUE_SIZE）。
     * 单线程执行器保证写入顺序，避免多线程交错写出半份 JSON。
     */
    /**
     * 落盘序号：每次写入（同步或异步）自增，异步任务执行前比对。
     *
     * 背景：主线程的 commitSync 会被投递到 persistExecutor 异步执行，而「退出/关机」类路径
     * 用 allowOffloadOnMain=false 绕过执行器同步写同一份 SharedPreferences ——
     * 若执行器里还压着更早的任务，它会在同步写之后落盘，用旧歌/旧进度覆盖新状态。
     * 因此每个写入都带序号，执行时若发现已有更新的写入完成，就丢弃这次过期写入。
     */
    private val persistSeq = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile
    private var lastPersistedSeq = 0L

    private val persistExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "playback-state-persist").apply { isDaemon = true }
    }

    /**
     * 队列元数据重算专用单线程池。
     *
     * 曲库级（2 万首）的 associateBy + map + 磁盘校验不能放在主线程 —— MainActivity 的
     * invalidationTrackerFlow.collect 体运行在主线程，每次数据库失效都会调用 updateMetadata，
     * 而 mergeSongMetadata 内部还会对每首歌做 2 次 File.exists()（最多 4 万次磁盘 stat）。
     * 但也不能并发执行（会与 _playlistFlow 的读取竞争），故用单线程串行化。
     */
    private val metadataExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "queue-metadata").apply { isDaemon = true }
    }

    fun savePlaybackState(
        context: Context? = appContext,
        song: UnifiedSong? = _currentSongFlow.value,
        positionMs: Long? = null,
        commitSync: Boolean = false,
        /**
         * 是否允许在主线程调用时把 commitSync 降级为「投递到 IO 串行执行」。
         * 默认 true（常规播放路径，不能阻塞主线程）；
         * **关机 / 彻底退出**这类「必须写完再返回」的路径传 false，保持同步语义。
         */
        allowOffloadOnMain: Boolean = true
    ) {
        val ctx = context ?: appContext ?: return
        val target = song ?: _currentSongFlow.value ?: return
        // 主线程 + 允许降级 → 投递到 IO 线程执行同样的写入（保持 commitSync 语义，只是换个线程）
        val seq = persistSeq.incrementAndGet()
        if (commitSync && allowOffloadOnMain &&
            android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        ) {
            persistExecutor.execute {
                // 过期任务丢弃：期间若已有更新的写入（尤其是同步写）完成，本次跳过
                if (seq < lastPersistedSeq) {
                    Log.i(TAG, "skip stale playback-state persist (seq=$seq < $lastPersistedSeq)")
                    return@execute
                }
                writePlaybackState(ctx, target, positionMs, commitSync = true)
                lastPersistedSeq = maxOf(lastPersistedSeq, seq)
            }
            return
        }
        writePlaybackState(ctx, target, positionMs, commitSync)
        lastPersistedSeq = maxOf(lastPersistedSeq, seq)
    }

    private fun writePlaybackState(ctx: Context, target: UnifiedSong, positionMs: Long?, commitSync: Boolean) {
        try {
            val prefs = ctx.getSharedPreferences(AUTO_PLAY_PREFS, Context.MODE_PRIVATE)
            val editor = prefs.edit()
                .putString("last_played_song_id", target.id)
                .putString("last_played_song_title", target.title)
                .putString("last_played_song_artist", target.artist)
                .putString("last_played_song_json", songToJson(target).toString())
            val queue = _playlistFlow.value
            if (queue.isNotEmpty()) {
                editor.putString("last_played_queue_json", queueToJson(queue, target.id))
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

        try {
            primeRecentPlayedFromPrefs(context)
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
        val effectiveLocal = if (incoming.downloadStatus == DownloadStatus.NOT_DOWNLOADED && incoming.localFilePath == null) {
            null
        } else {
            validIncomingLocal ?: validExistingLocal
        }
        val effectiveStream = when {
            !effectiveLocal.isNullOrBlank() -> effectiveLocal
            incoming.streamUrl.isNotBlank() && !incoming.streamUrl.startsWith("lemon_online://") -> incoming.streamUrl
            existing.streamUrl.isNotBlank() && !existing.streamUrl.startsWith("/") -> existing.streamUrl
            else -> incoming.streamUrl
        }
        val effectiveDownloadStatus = if (!effectiveLocal.isNullOrBlank()) {
            DownloadStatus.DOWNLOADED
        } else {
            if (incoming.downloadStatus == DownloadStatus.DOWNLOADED) DownloadStatus.NOT_DOWNLOADED else incoming.downloadStatus
        }
        return incoming.copy(
            localFilePath = effectiveLocal,
            streamUrl = effectiveStream,
            downloadStatus = effectiveDownloadStatus,
            rawMetaJson = incoming.rawMetaJson ?: existing.rawMetaJson
        )
    }

    /**
     * 当本地已下载歌曲被删除时，立即同步清除播放队列、当前播放及最近播放中的已下载标志与本地路径
     */
    fun onSongsDownloadDeleted(songIds: Set<String>) {
        if (songIds.isEmpty()) return
        val current = _currentSongFlow.value
        if (current != null && current.id in songIds) {
            val updated = current.copy(
                localFilePath = null,
                downloadStatus = DownloadStatus.NOT_DOWNLOADED
            )
            _currentSongFlow.value = updated
            savePlaybackState(song = updated, commitSync = true)
        }
        if (_playlistFlow.value.isNotEmpty()) {
            _playlistFlow.value = _playlistFlow.value.map { song ->
                if (song.id in songIds) {
                    song.copy(
                        localFilePath = null,
                        downloadStatus = DownloadStatus.NOT_DOWNLOADED
                    )
                } else song
            }
        }
        if (_recentPlayedSongsFlow.value.isNotEmpty()) {
            _recentPlayedSongsFlow.value = _recentPlayedSongsFlow.value.map { song ->
                if (song.id in songIds) {
                    song.copy(
                        localFilePath = null,
                        downloadStatus = DownloadStatus.NOT_DOWNLOADED
                    )
                } else song
            }
            appContext?.let { flushRecentPlayed(it) }
        }
    }

    fun updateMetadata(songs: List<UnifiedSong>) {
        // **必须在后台线程做**：songs 是整个曲库（2 万首级别）。
        // 此前本函数直接在调用方线程（MainActivity 的 invalidationTrackerFlow.collect 体 = 主线程）
        // 执行 associateBy + map + mergeSongMetadata，每次数据库失效都要在主线程处理 2 万条，
        // 而 mergeSongMetadata 内部还会对每首歌做 2 次 File.exists() → 最多 4 万次磁盘 stat。
        // 结果是「每次同步/扫描都卡死主线程」，必然 ANR。
        // 这里只做主线程安全的引用赋值，真正的重算丢到单线程计算池串行执行。
        if (_playlistFlow.value.isEmpty()) {
            _playlistFlow.value = songs
        } else {
            val snapshot = _playlistFlow.value
            metadataExecutor.execute {
                val songMap = songs.associateBy { it.id }
                val merged = snapshot.map { existing ->
                    val matched = songMap[existing.id]
                    if (matched != null) mergeSongMetadata(existing, matched) else existing
                }
                _playlistFlow.value = merged
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
        // 最近播放足迹同样要跟上最新的本地化元数据：下载完成后点开「最近播放」应能直接播放本地文件
        if (_recentPlayedSongsFlow.value.isNotEmpty()) {
            val recentMap = songs.associateBy { it.id }
            _recentPlayedSongsFlow.value = _recentPlayedSongsFlow.value.map { existing ->
                val matched = recentMap[existing.id]
                if (matched != null) mergeSongMetadata(existing, matched) else existing
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
    private var lastBgErrorQualityIdx: Int = 0

    /**
     * 已挂过监听器的播放器实例（弱引用）。
     *
     * 用弱引用而不是布尔标记：Media3Factory.releaseSharedPlayer() 会在「彻底退出播放」时
     * 释放进程级播放器，之后 getSharedExoPlayer 会重建一个**新实例**。若沿用布尔标记，
     * 新实例上永远挂不上监听器（onPlayerError 容灾、STATE_ENDED 续播、周期落盘全部失效）。
     * 弱引用让旧实例被回收后守卫自动失效，从而在新实例上重新挂载。
     */
    @Volatile
    private var listenerAttachedPlayerRef: java.lang.ref.WeakReference<Player>? = null

    fun ensurePlayerListener(context: Context) {
        val appCtx = context.applicationContext
        if (appContext == null) {
            appContext = appCtx
        }
        val player = Media3Factory.getSharedExoPlayer(appCtx)
        // 判据必须是「挂过监听的那个实例**就是当前共享实例**」，而不是「那个实例还被强引用」。
        // 弱引用的失效依赖 GC 时机：release 后旧实例仍可能被 MainActivity/PlaybackService 的
        // 字段短暂持有，这段时间里 getSharedExoPlayer 已返回**新实例**，若用「弱引用是否还在」
        // 判断就会提前 return —— 新播放器没有监听器，isPlaying 状态、播完续播、后台容灾全部失效。
        val existing = listenerAttachedPlayerRef?.get()
        if (existing === player) return   // 同一实例已挂过，幂等
        listenerAttachedPlayerRef = java.lang.ref.WeakReference(player)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlayingFlow.value = playing
                if (playing) {
                    PlaybackService.startPlaybackService(appCtx)
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
                // 车机/手机挂后台且主界面未处于前台时的自动容灾降级换源重连与切歌保障
                val targetSong = _currentSongFlow.value ?: return
                val resumePos = player.currentPosition.coerceAtLeast(0L)
                val prefQ = LemonMusicProtocol.getPreferredStreamQuality(appCtx)
                val qChain = LemonMusicProtocol.getFallbackQualities(prefQ)
                val currentIdx = if (lastBgErrorRetrySongId == targetSong.id) lastBgErrorQualityIdx else 0
                val nextIdx = currentIdx + 1
                if (nextIdx < qChain.size) {
                    lastBgErrorRetrySongId = targetSong.id
                    lastBgErrorQualityIdx = nextIdx
                    val nextQ = qChain[nextIdx]
                    val label = AudioQuality.fromKey(nextQ).label
                    coroutineScope.launch(Dispatchers.Main) {
                        Toast.makeText(appCtx, "当前音质无法缓冲，已自动为您换源降至【$label】播放", Toast.LENGTH_SHORT).show()
                    }
                    playSong(
                        targetSong = targetSong,
                        context = appCtx,
                        startPositionMs = resumePos,
                        forceRefresh = true,
                        overrideQuality = nextQ
                    )
                } else {
                    val now = System.currentTimeMillis()
                    val canRetry = lastBgErrorRetrySongId != targetSong.id || (now - lastBgErrorRetryTimeMs) > 12_000L
                    if (canRetry) {
                        lastBgErrorRetrySongId = targetSong.id
                        lastBgErrorRetryTimeMs = now
                        lastBgErrorQualityIdx = 0
                        Log.w(TAG, "Background onPlayerError (${error.message}), auto-retrying ${targetSong.title} at ${resumePos}ms")
                        playSong(
                            targetSong = targetSong,
                            context = appCtx,
                            startPositionMs = resumePos,
                            forceRefresh = true
                        )
                    }
                }
            }
        })
        // 已由 listenerAttachedPlayerRef 承担幂等判定，此处不再使用布尔标记
    }

    fun playSong(
        targetSong: UnifiedSong,
        context: Context,
        newPlaylist: List<UnifiedSong>? = null,
        startPositionMs: Long = 0L,
        forceRefresh: Boolean = false,
        overrideQuality: String? = null
    ) {
        val appCtx = context.applicationContext
        ensurePlayerListener(appCtx)
        PlaybackService.startPlaybackService(appCtx)
        _currentSongFlow.value = targetSong
        if (newPlaylist != null && newPlaylist.isNotEmpty()) {
            _playlistFlow.value = newPlaylist
        } else if (_playlistFlow.value.isEmpty()) {
            _playlistFlow.value = listOf(targetSong)
        } else if (_playlistFlow.value.none { it.id == targetSong.id }) {
            _playlistFlow.value = listOf(targetSong) + _playlistFlow.value
        }

        // 播放足迹在这里统一记账：手动点播、上一首/下一首、自动续播、播放失败重连最终都汇聚到本方法。
        // 此前只在界面点播处调用 addRecentPlayedSong，用上一首/下一首切歌不经过那里，
        // 于是「最近播放」永远记不住切歌听过的曲目 —— 这正是本次要修的根因。
        addRecentPlayedSong(appCtx, targetSong)

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
                val mediaItem: MediaItem = router.resolveMediaItem(
                    targetSong,
                    forceRefresh = forceRefresh,
                    overrideQuality = overrideQuality
                )
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

    private var lastSkipElapsedRealtime = 0L

    fun playNext(context: Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSkipElapsedRealtime < 400L) {
            Log.w(TAG, "playNext debounced: ignore fast repeat skip (${now - lastSkipElapsedRealtime}ms)")
            return
        }
        lastSkipElapsedRealtime = now

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
            // 队列只有 1 首、或队列里与当前曲目同 id 的条目占满时，filter 结果会是空列表，
            // 直接 random() 会抛 NoSuchElementException（该回调运行在 ExoPlayer 主线程 → 必崩）。
            // 因此空列表必须回退到整份队列。
            val candidates = list.filter { it.id != current?.id }.ifEmpty { list }
            candidates.randomOrNull() ?: return
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
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSkipElapsedRealtime < 400L) {
            Log.w(TAG, "playPrevious debounced: ignore fast repeat skip (${now - lastSkipElapsedRealtime}ms)")
            return
        }
        lastSkipElapsedRealtime = now

        val appCtx = context.applicationContext
        ensurePlayerListener(appCtx)
        val list = _playlistFlow.value
        if (list.isEmpty()) {
            Log.w(TAG, "playPrevious called but playlist is empty")
            return
        }
        val current = _currentSongFlow.value
        val isShuffle = _isShuffleFlow.value

        val prevSong: UnifiedSong = if (isShuffle && list.size > 1) {
            list.filter { it.id != current?.id }.ifEmpty { list }.randomOrNull() ?: list.first()
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

    /**
     * 收藏状态全链路同步切换：
     * 1. 立即更新当前播放中歌曲状态与播放列表状态
     * 2. 刷新通知栏展开态 ♥ 收藏按钮红心图标状态
     * 3. 异步持久化到 Room 本地数据库及 lemon_favorites 歌单
     * 4. 同步至远端柠檬音乐服务器收藏列表
     * 5. 若开启「收藏后自动缓存到服务器」，自动向服务器提交下载任务
     */
    fun toggleFavorite(context: Context, song: UnifiedSong? = null) {
        val appCtx = context.applicationContext
        val target = song ?: _currentSongFlow.value ?: return
        // 连点去抖：同一首歌 600ms 内的第二次点击不再直接丢弃，而是**取反状态**。
        // 直接 return 会让「点红心 → 立刻取消」停在已收藏状态（本地与服务器一致但违背用户意图），
        // 与我们要修的「连点只生效一次」是同一类问题的另一面。
        // 这里改为：窗口内已有点击时，把目标状态翻转后继续执行（等于合并为最后一次意图）。
        val nowMs = System.currentTimeMillis()
        val lastMs = favoriteToggleGuard[target.id]
        val inDebounceWindow = lastMs != null && nowMs - lastMs < 600L
        if (inDebounceWindow) {
            Log.i(TAG, "toggleFavorite coalesced for ${target.title}")
        }
        favoriteToggleGuard[target.id] = nowMs
        // 简单的容量保护：去抖表按歌曲累积，超过一定规模就清理过期项
        if (favoriteToggleGuard.size > 256) {
            favoriteToggleGuard.entries.removeAll { nowMs - it.value > 60_000L }
        }
        val newFav = !target.isFavorite
        val updated = target.copy(isFavorite = newFav)

        // 1. 即时更新内存 StateFlow
        if (_currentSongFlow.value?.id == target.id) {
            _currentSongFlow.value = updated
        }
        val currentList = _playlistFlow.value
        if (currentList.any { it.id == target.id }) {
            _playlistFlow.value = currentList.map { if (it.id == target.id) updated else it }
        }

        // 2. 刷新通知栏与灵动岛
        try {
            DynamicIslandManager.notifySystemIsland(
                appCtx,
                null,
                Media3Factory.getSharedExoPlayer(appCtx),
                force = true
            )
        } catch (_: Throwable) {}

        // 3. 异步持久化与服务器双向同步
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val db = ZdsDatabase.getInstance(appCtx)
                val existing = db.songDao().getSongById(target.id)
                if (existing == null) {
                    db.songDao().insertSongs(
                        listOf(
                            com.lm.player.core.database.entity.SongEntity(
                                id = updated.id,
                                title = updated.title.ifBlank { "未知曲目" },
                                artist = updated.artist.ifBlank { "未知歌手" },
                                artistId = updated.artistId.ifBlank { "artist_${updated.artist.hashCode()}" },
                                album = updated.album.ifBlank { "单曲精选" },
                                albumId = updated.albumId.ifBlank { "album_${updated.album.hashCode()}" },
                                durationMs = updated.durationMs,
                                coverUrl = updated.coverUrl,
                                streamUrl = updated.streamUrl,
                                serverId = updated.serverId.ifBlank { "local_storage" },
                                localFilePath = updated.localFilePath,
                                downloadStatus = updated.downloadStatus,
                                bitRate = updated.bitRate,
                                format = updated.format,
                                isFavorite = newFav,
                                relativeFolderPath = updated.rawMetaJson ?: updated.relativeFolderPath,
                                addedTimestamp = System.currentTimeMillis()
                            )
                        )
                    )
                } else {
                    db.songDao().updateFavorite(target.id, newFav)
                }
                if (newFav) {
                    db.playlistDao().addSongToPlaylist(
                        com.lm.player.core.database.entity.PlaylistSongEntity(
                            playlistId = "lemon_favorites",
                            songId = target.id
                        )
                    )
                } else {
                    db.playlistDao().removeSongFromPlaylist("lemon_favorites", target.id)
                }
                db.playlistDao().updateSongCount("lemon_favorites")

                // 4. 同步至远端服务器
                val activeServer = db.serverDao().getActiveServer()
                    ?: db.serverDao().getAllServers().firstOrNull { it.type == com.lm.player.core.model.ServerType.LEMON_MUSIC }
                if (activeServer != null) {
                    val protocol = LemonMusicProtocol(
                        com.lm.player.core.network.NetworkClientFactory.createOkHttpClient(appCtx),
                        activeServer.serverUrl,
                        activeServer.username,
                        activeServer.tokenOrApiKey
                    )
                    protocol.toggleFavoriteSongOnServer(updated, newFav)

                    // 5. 检查「收藏后自动缓存到服务器」设置
                    val prefs = appCtx.getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE)
                    val legacyPrefs = appCtx.getSharedPreferences("lm_player_settings", Context.MODE_PRIVATE)
                    val autoCache = prefs.getBoolean("auto_cache_on_favorite", false) ||
                        prefs.getBoolean("auto_cache_to_server_on_favorite", false) ||
                        legacyPrefs.getBoolean("auto_cache_to_server_on_favorite", false)
                    if (newFav && autoCache) {
                        val targetServerId = activeServer.id.ifBlank { "lemon_music" }
                        val updatedServerId = if (target.serverId.isBlank() || target.serverId == "lemon_online" || target.serverId == "default") {
                            targetServerId
                        } else {
                            target.serverId
                        }
                        val songWithServer = updated.copy(serverId = updatedServerId)
                        _currentSongFlow.value = songWithServer
                        db.songDao().updateServerId(target.id, updatedServerId)

                        val qKey = prefs.getString("auto_cache_server_quality", null)
                            ?: prefs.getString("default_download_quality", null)
                            ?: legacyPrefs.getString("default_download_quality", "320k")
                            ?: "320k"
                        val targetQuality = com.lm.player.core.model.AudioQuality.fromKey(qKey)
                        val task = DownloadRequestPlanner.buildServerDownloadTask(songWithServer, targetQuality)
                        protocol.addServerDownloadTasks(listOf(task))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "toggleFavorite async error", e)
            }
        }
    }


    fun togglePlay(context: Context) {
        val appCtx = context.applicationContext
        ensurePlayerListener(appCtx)
        PlaybackService.startPlaybackService(appCtx)
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
