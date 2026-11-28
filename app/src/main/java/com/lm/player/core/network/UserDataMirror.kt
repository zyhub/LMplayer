package com.lm.player.core.network

import android.content.Context
import android.util.Log
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.media.PlaybackQueueManager
import com.lm.player.core.media.SongMatchingResolver
import com.lm.player.core.model.ServerConfig
import com.lm.player.core.model.UnifiedSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 2026-11-25（第二轮）：「我的喜欢」「最近播放」三端统一以服务器 user-data 为准。手机端与 TV 端两份逐字一致。
 *
 * 此前安卓两端：
 * - 收藏同步只会把服务器收藏「标为喜欢」，从不清除——别的设备取消的收藏在这边一直亮红心、一直在「我喜欢」里；
 * - 最近播放是本机足迹与服务器记录混排，而且本机的播放从来不写回服务器（iOS / 网页端看不到安卓听过什么）。
 * 现在：
 * - [reconcileFavorites]：本机红心标记对齐到服务器收藏（服务器有的标上，没有的清掉；纯本地文件不动）；
 * - [onPlayed]：每次真实播放记一笔，2 秒内合并成一次写入服务器 recentPlays（置顶、去重、最多 100 首，带 baseRevision）；
 *   离线时先攒着，回到在线（[setServer]）再补推；
 * - [pullRecent]：「最近播放」整份换成服务器那 100 首（本机还没推上去的几首放最前）。
 */
object UserDataMirror {
    private const val TAG = "UserDataMirror"
    const val RECENT_LIMIT = 100
    private const val PUSH_DEBOUNCE_MS = 2_000L
    private val LOCAL_ONLY_SERVER_IDS = setOf("local_storage", "local_folder", "local_saf")

    // ==================== 我的喜欢 ====================

    private val _favoritesVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /**
     * 本机点红心（播放页 / 通知栏 / 小组件）写完服务器后加一。界面据此立刻按服务器重新拉一次「我喜欢」——
     * 本地先改的那份只按歌曲 id 增删，服务器按同名同歌手合并 / 删除的结果要重新拉才对得上数量。
     */
    val favoritesVersion: kotlinx.coroutines.flow.StateFlow<Long> = _favoritesVersion

    fun notifyFavoritesChanged() {
        _favoritesVersion.value = _favoritesVersion.value + 1
    }

    /** 拉服务器收藏并把本机红心对齐过去；读取失败返回 null（本机标记不动） */
    suspend fun pullFavorites(database: ZdsDatabase, protocol: LemonMusicProtocol): List<UnifiedSong>? {
        val favs = protocol.getPlaylistSongs("lemon_favorites").getOrNull() ?: return null
        reconcileFavorites(database, favs)
        return favs
    }

    /**
     * 本机曲库的红心标记对齐到服务器收藏：服务器收藏里的标为喜欢，不在里面的（别的设备取消的）清掉。
     * 纯本地文件（不在服务器上）不动；与某条服务器收藏是同一首歌（在线音源身份的收藏对应的曲库歌曲）的保留，避免误清。
     */
    suspend fun reconcileFavorites(database: ZdsDatabase, serverFavorites: List<UnifiedSong>): Unit = withContext(Dispatchers.IO) {
        try {
            val songDao = database.songDao()
            val ids = serverFavorites.mapTo(HashSet()) { it.id }
            for (id in ids) songDao.updateFavorite(id, true)
            val flagged = songDao.getFavoriteSongsFlow().first()
            var cleared = 0
            for (song in flagged) {
                if (song.id in ids || song.serverId in LOCAL_ONLY_SERVER_IDS || song.id.startsWith("local_")) continue
                val sameSong = serverFavorites.any { fav ->
                    fav.title.isNotBlank() && SongMatchingResolver.isSongMatch(
                        title1 = song.title, artist1 = song.artist, durationMs1 = song.durationMs,
                        title2 = fav.title, artist2 = fav.artist, durationMs2 = fav.durationMs,
                        album1 = song.album, album2 = fav.album
                    )
                }
                if (sameSong) continue
                songDao.updateFavorite(song.id, false)
                runCatching { database.playlistDao().removeSongFromPlaylist("lemon_favorites", song.id) }
                cleared++
            }
            if (cleared > 0) {
                Log.i(TAG, "cleared $cleared favorites no longer on server")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "reconcile favorites failed", e)
        }
        Unit
    }

    // ==================== 最近播放 ====================

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushMutex = Mutex()
    private val pending = ArrayList<UnifiedSong>() // 新 → 旧，还没写到服务器的播放
    @Volatile private var appContext: Context? = null
    @Volatile private var server: ServerConfig? = null
    @Volatile private var flushing = false
    private var flushJob: Job? = null

    /** 当前可用的柠檬服务器（在线时传入，离线 / 本地模式传 null）；回到在线时把离线期间的播放补推上去 */
    fun setServer(context: Context, config: ServerConfig?) {
        appContext = context.applicationContext
        server = config
        if (config != null && hasPending()) scheduleFlush(0L)
    }

    /** 记录一次真实播放（播放入口统一调用）。不在服务器上的纯本地文件不推（别的设备放不了） */
    fun onPlayed(context: Context, song: UnifiedSong) {
        if (song.id.isBlank() || song.serverId in LOCAL_ONLY_SERVER_IDS || song.id.startsWith("local_")) return
        appContext = context.applicationContext
        synchronized(pending) {
            pending.removeAll { it.id == song.id }
            pending.add(0, song)
            while (pending.size > RECENT_LIMIT) pending.removeAt(pending.lastIndex)
        }
        if (server != null) scheduleFlush(PUSH_DEBOUNCE_MS)
    }

    private fun hasPending(): Boolean = synchronized(pending) { pending.isNotEmpty() }

    private fun scheduleFlush(delayMs: Long) {
        synchronized(this) {
            // 正在写服务器的那次不打断（打断了服务器可能已经收到），只重排还在等待的
            if (!flushing) flushJob?.cancel()
            flushJob = scope.launch {
                delay(delayMs)
                flush()
            }
        }
    }

    private suspend fun flush() = flushMutex.withLock {
        val cfg = server ?: return@withLock
        val ctx = appContext ?: return@withLock
        val batch = synchronized(pending) { ArrayList(pending) }
        if (batch.isEmpty()) return@withLock
        flushing = true
        try {
            val protocol = LemonMusicProtocol(
                NetworkClientFactory.createOkHttpClient(ctx), cfg.serverUrl, cfg.username, cfg.tokenOrApiKey
            )
            protocol.pushRecentPlays(batch, RECENT_LIMIT)
                .onSuccess { list ->
                    // 只去掉这次推上去的那几个对象；推送期间又播的（新对象）留着等下一次
                    synchronized(pending) { pending.removeAll { p -> batch.any { it === p } } }
                    applyServerList(ctx, list)
                }
                .onFailure { Log.w(TAG, "push recent plays failed: ${it.message}") }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "push recent plays failed", e)
        } finally {
            flushing = false
        }
    }

    /** 拉服务器最近播放（100 首）替换本机列表；先把攒着的播放推上去。读取失败返回 false（本机列表不动） */
    suspend fun pullRecent(context: Context, protocol: LemonMusicProtocol): Boolean {
        appContext = context.applicationContext
        if (server != null && hasPending()) flush()
        val list = protocol.fetchRecentPlaysFromServer(RECENT_LIMIT).getOrNull() ?: return false
        applyServerList(context.applicationContext, list)
        return true
    }

    private fun applyServerList(context: Context, serverList: List<UnifiedSong>) {
        val local = synchronized(pending) { ArrayList(pending) }
        val seen = HashSet<String>()
        val merged = ArrayList<UnifiedSong>(RECENT_LIMIT)
        for (s in local + serverList) {
            if (merged.size >= RECENT_LIMIT) break
            if (s.id.isNotBlank() && seen.add(s.id)) merged.add(s)
        }
        PlaybackQueueManager.replaceRecentPlayedFromServer(context, merged)
    }
}
