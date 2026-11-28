package com.lm.player.core.network

import android.util.Log
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.database.entity.PlaylistEntity
import com.lm.player.core.model.ServerConfig
import com.lm.player.core.model.UnifiedPlaylist
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 2026-11-25：服务器歌单 → 本机数据库的对齐（原来 MainActivity 里三处各写一份）。
 *
 * 修的问题（iOS 端新建 / 删除的歌单同步不到安卓）：
 * - 删除时跳过了所有 `pl_` 开头的歌单（安卓自己建的歌单都是这个前缀），别的设备删了安卓建的歌单，
 *   这边永远删不掉。现在服务器上没有的云端歌单一律删掉，只给本机刚新建的留 2 分钟（见
 *   [LemonMusicProtocol.isRecentlyCreatedPlaylist]）；
 * - 启动时几路同步同时跑、各自「先删后插」，旧快照会把新快照刚插入的歌单删掉 → 串行执行；
 * - 读服务器失败时 getPlaylists 不再返回空列表（以前会把本机云端歌单全删）。
 *
 * 另外记下每台服务器最后对齐到的 user-data revision，[changedSinceLastSync] 用服务器的
 * `?sinceRevision=` 做轻量检查（回到前台 / 前台每分钟一次），有变化才整份拉取。
 */
object ServerPlaylistSync {
    private const val TAG = "ServerPlaylistSync"
    private val mutex = Mutex()
    private val lastSyncedRevision = ConcurrentHashMap<String, Long>()

    suspend fun sync(
        database: ZdsDatabase,
        protocol: LemonMusicProtocol,
        config: ServerConfig,
        toEntity: (UnifiedPlaylist) -> PlaylistEntity
    ): Result<List<UnifiedPlaylist>> = mutex.withLock {
        val plList = protocol.getPlaylists(targetServerId = config.id).getOrElse {
            Log.w(TAG, "fetch server playlists failed: ${it.message}")
            return@withLock Result.failure(it)
        }
        val dao = database.playlistDao()
        val incomingIds = plList.mapTo(HashSet()) { it.id }
        val all = dao.getAllPlaylists()
        val existing = all
            .filter { it.serverId == config.id || it.serverId == "lemon_music" || it.serverId == config.serverUrl }
        var removed = 0
        for (old in existing) {
            if (old.id in incomingIds || !old.isOnline) continue
            if (LemonMusicProtocol.isRecentlyCreatedPlaylist(old.id)) continue
            dao.deletePlaylist(old.id)
            // 「我的收藏」的本机关联同时承载红心状态，不随服务器收藏清空而清；其余歌单的本机曲目关联一并清掉
            if (old.id != "lemon_favorites") dao.clearSongsForPlaylist(old.id)
            removed++
        }
        // 2026-11-26（方案 A）：云端歌单严格以服务端 user-data.playlists 数组顺序为唯一基准（与 iOS / 网页端完全一致）。
        // 此前有两个导致刷新乱序的问题：
        // 1. 批量新建入库时循环内调用 System.currentTimeMillis()，多条歌单同毫秒撞车或跨毫秒倒序；
        // 2. 只要 songCount 变动就把 updatedTimestamp 刷成当前时间，与封面/曲目回写形成乒乓死循环。
        // 现在：若现有库里云端歌单的 updatedTimestamp 已严格递减且与服务端顺序一致，则完全保留原时间、没变化不写库；
        // 否则（首次入库 / 新增歌单 / 服务端顺序变更 / 旧库存在同毫秒撞车）按服务端顺序赋予严格递减且互不相等的时间戳。
        val oldById = all.associateBy { it.id }
        val existingTimestamps = plList.map { oldById[it.id]?.updatedTimestamp }
        val orderAlreadyValid = existingTimestamps.all { it != null && it > 0L } &&
            (0 until existingTimestamps.size - 1).all { idx -> existingTimestamps[idx]!! > existingTimestamps[idx + 1]!! }
        val maxExistingCloudTs = existingTimestamps.filterNotNull().maxOrNull() ?: 0L
        val baseTs = maxOf(System.currentTimeMillis(), maxExistingCloudTs + 1000L)
        val changed = plList.mapIndexedNotNull { index, pl ->
            val fresh = toEntity(pl)
            val old = oldById[fresh.id]
            val targetTs = if (orderAlreadyValid) old!!.updatedTimestamp else (baseTs - index * 1000L)
            if (old == null) return@mapIndexedNotNull fresh.copy(updatedTimestamp = targetTs)
            val same = old.name == fresh.name && old.coverUrl == fresh.coverUrl && old.serverId == fresh.serverId &&
                old.isOnline == fresh.isOnline && old.songCount == fresh.songCount && old.updatedTimestamp == targetTs
            if (same) null else fresh.copy(updatedTimestamp = targetTs)
        }
        if (changed.isNotEmpty()) dao.insertPlaylists(changed)
        // 服务器不带 revision（老版本）记 -1：没法做轻量检查，只靠原有的进页面 / 启动同步
        lastSyncedRevision[config.id] = protocol.lastPlaylistsRevision ?: -1L
        Log.i(TAG, "synced ${plList.size} server playlists (changed ${changed.size}, removed $removed) for ${config.id}, revision=${protocol.lastPlaylistsRevision}")
        Result.success(plList)
    }

    /** 服务器 user-data 自上次对齐后有没有变；还没同步过按「变了」处理，服务器不支持 revision 按「没变」处理 */
    suspend fun changedSinceLastSync(protocol: LemonMusicProtocol, config: ServerConfig): Boolean {
        val rev = lastSyncedRevision[config.id] ?: return true
        if (rev < 0) return false
        return protocol.hasUserDataChangedSince(rev).getOrDefault(false)
    }
}
