package com.lm.player.core.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.UnifiedSong

/**
 * 歌曲下载 / 服务器状态的即时来源，让各个列表行在下载完成后立刻刷新。
 *
 * 以前列表行只看传进来的 UnifiedSong：搜索结果、歌手 / 专辑页、发现页、歌单详情拿到的都是打开时的快照，
 * 下载完成只写数据库，快照不会变，所以行尾一直是「下载」按钮。而资料库本身要等数据库防抖 + 全表重读，也有明显延迟。
 *
 * 两层数据：
 * - library：数据库镜像（MainActivity 每次重读歌曲表后整体替换），只记有本地文件或属于服务器曲库的歌；
 * - overlay：下载引擎 / 服务器提交的即时事件（完成、删除、已提交服务器），数据库镜像追上后自动清掉。
 *
 * 性能：两份都是不可变 Map 整体替换；行里用 derivedStateOf 只读自己那一条，
 * 批量下载时每完成一首只有对应那一行重组，其它行只做一次 Map 查找。
 */
object SongStatusStore {

    @Immutable
    data class Status(
        /** 本地文件路径（null = 不覆盖） */
        val localFilePath: String? = null,
        /** 本地文件已删除（覆盖快照里的路径） */
        val removed: Boolean = false,
        /** 所属服务器 id（null = 不覆盖） */
        val serverId: String? = null
    )

    private val library = mutableStateOf<Map<String, Status>>(emptyMap())
    private val overlay = mutableStateOf<Map<String, Status>>(emptyMap())
    private val lock = Any()

    /** 与 UnifiedSong.isServerLibrarySong 同一口径 */
    private fun isServerId(id: String?): Boolean =
        !id.isNullOrBlank() && id !in com.lm.player.core.model.NON_SERVER_LIBRARY_IDS

    /** 数据库重读后调用（任意线程）：替换镜像，并清掉已被数据库覆盖的即时事件 */
    fun updateLibrary(songs: List<UnifiedSong>) {
        val map = HashMap<String, Status>(songs.size / 2 + 16)
        for (s in songs) {
            val path = s.localFilePath?.takeIf { it.isNotBlank() }
            val server = s.serverId.takeIf { s.isServerLibrarySong }
            if (path != null || server != null) map[s.id] = Status(localFilePath = path, serverId = server)
        }
        synchronized(lock) {
            library.value = map
            val ov = overlay.value
            if (ov.isEmpty()) return
            val kept = ov.filter { (id, o) ->
                val l = map[id]
                val pathSettled = when {
                    o.removed -> l != null && l.localFilePath == null
                    o.localFilePath != null -> l?.localFilePath == o.localFilePath
                    else -> true
                }
                val serverSettled = o.serverId == null || l?.serverId == o.serverId
                !(pathSettled && serverSettled)
            }
            if (kept.size != ov.size) overlay.value = kept
        }
    }

    private fun edit(ids: Collection<String>, change: (Status) -> Status) {
        if (ids.isEmpty()) return
        synchronized(lock) {
            val next = HashMap(overlay.value)
            for (id in ids) next[id] = change(next[id] ?: Status())
            overlay.value = next
        }
    }

    /** 本地下载完成（下载引擎写库时调用） */
    fun markDownloaded(songId: String, localFilePath: String) {
        edit(listOf(songId)) { it.copy(localFilePath = localFilePath, removed = false) }
        // 双端下载的本地部分完成：请求刷新当前页面
        if (bothExpected.remove(songId) != null) {
            bothDone[songId] = localFilePath to System.currentTimeMillis()
            startRefreshWindow()
        }
    }

    // 双端下载的服务器那边写库较慢：本地完成后每隔 1 秒刷新一次，持续 10 秒（批量时窗口顺延，只跑一个计时器）
    private val refreshScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    @Volatile private var refreshUntil = 0L
    private var refreshJob: kotlinx.coroutines.Job? = null
    private const val REFRESH_INTERVAL_MS = 1000L
    private const val REFRESH_WINDOW_MS = 10_000L

    /** 接下来 10 秒内每秒刷新一次当前页面（已在刷新时只顺延结束时间） */
    fun startRefreshWindow() {
        synchronized(lock) {
            refreshUntil = maxOf(refreshUntil, System.currentTimeMillis() + REFRESH_WINDOW_MS)
            if (refreshJob?.isActive == true) return
            refreshJob = refreshScope.launch {
                while (System.currentTimeMillis() < refreshUntil) {
                    kotlinx.coroutines.delay(REFRESH_INTERVAL_MS)
                    requestPageRefresh()
                }
            }
        }
    }

    // ---------- 双端下载完成后刷新当前页面 ----------
    private val bothExpected = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val bothDone = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private val forcedRefreshPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val _refreshRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    /** 需要立即重读歌曲表并刷新当前页面（MainActivity 收集） */
    val refreshRequests: kotlinx.coroutines.flow.SharedFlow<Unit> = _refreshRequests

    /** 当前页面刷新计数：MainActivity 立即重读歌曲表后 +1，资料库各页据此把列表换成数据库里的最新数据 */
    val pageRefreshTick = androidx.compose.runtime.mutableIntStateOf(0)

    /** 发起双端下载时登记（本地部分完成时刷新页面） */
    fun expectBothDownload(songIds: Collection<String>) {
        val now = System.currentTimeMillis()
        songIds.forEach { bothExpected[it] = now }
    }

    fun requestPageRefresh() {
        forcedRefreshPending.set(true)
        _refreshRequests.tryEmit(Unit)
    }

    fun isForcedRefreshPending(): Boolean = forcedRefreshPending.get()

    /** 重读完成：有待处理的刷新请求就让页面刷新 */
    fun onLibraryReloaded() {
        if (forcedRefreshPending.getAndSet(false)) pageRefreshTick.intValue += 1
    }

    /** 最近 10 分钟内完成本地部分的双端下载（歌曲 id → 本地文件），刷新前用来核对数据库 */
    fun recentBothDownloads(): Map<String, String> {
        val now = System.currentTimeMillis()
        bothDone.entries.removeIf { now - it.value.second > 10 * 60 * 1000L }
        return bothDone.mapValues { it.value.first }
    }

    /** 本地文件已删除 */
    fun markRemoved(songIds: Collection<String>) =
        edit(songIds) { it.copy(localFilePath = null, removed = true) }

    /** 已提交到服务器曲库（缓存至服务器 / 双端下载） */
    fun markOnServer(songIds: Collection<String>, serverId: String) {
        if (!isServerId(serverId)) return
        edit(songIds) { it.copy(serverId = serverId) }
    }

    private fun statusOf(id: String): Pair<Status?, Status?> = library.value[id] to overlay.value[id]

    /** 把最新状态叠到歌曲上（只改本地路径 / 下载状态 / 所属服务器，其余字段原样） */
    fun apply(song: UnifiedSong, lib: Status?, ov: Status?): UnifiedSong {
        if (lib == null && ov == null) return song
        var path = song.localFilePath
        var status = song.downloadStatus
        var server = song.serverId
        if (lib != null) {
            if (lib.localFilePath != null) {
                path = lib.localFilePath
                status = DownloadStatus.DOWNLOADED
            }
            if (lib.serverId != null && !song.isServerLibrarySong) server = lib.serverId
        }
        if (ov != null) {
            if (ov.removed) {
                path = null
                status = DownloadStatus.NOT_DOWNLOADED
            } else if (ov.localFilePath != null) {
                path = ov.localFilePath
                status = DownloadStatus.DOWNLOADED
            }
            if (ov.serverId != null) server = ov.serverId
        }
        if (path == song.localFilePath && status == song.downloadStatus && server == song.serverId) return song
        return song.copy(localFilePath = path, downloadStatus = status, serverId = server)
    }

    /** 列表行 / 播放页用：拿到带最新下载与服务器状态的歌曲；只有这首歌的状态变化才会触发重组 */
    @Composable
    fun rememberLive(song: UnifiedSong): UnifiedSong {
        val id = song.id
        val status by remember(id) { derivedStateOf { statusOf(id) } }
        return remember(song, status) { apply(song, status.first, status.second) }
    }
}
