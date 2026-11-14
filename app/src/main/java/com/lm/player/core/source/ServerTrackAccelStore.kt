package com.lm.player.core.source

import android.content.Context
import android.util.Log
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.database.dao.ServerTrackAccelDao
import com.lm.player.core.database.entity.ServerTrackAccelEntity
import com.lm.player.core.media.SongMatchingResolver
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.network.LemonMusicProtocol
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 服务器歌曲加速表的读写（2026-11-13，数据库第 7 版 `server_track_accel`）。
 *
 * 每首服务器曲库歌曲一行：平台对应（播放时跳过现搜）、平台封面、歌词缓存。
 * 只在播放 / 显示时当作「更快的来源」，不改资料库里的歌曲列表、封面和元数据（11-11 规则）。
 *
 * 服务器文件指纹 [fileKeyOf]：路径 + 大小。服务器上换了文件（指纹变了），这一行的匹配和歌词都作废重来。
 */
object ServerTrackAccelStore {
    private const val TAG = "ServerTrackAccel"

    const val STATUS_PENDING = 0
    const val STATUS_MATCHED = 1
    const val STATUS_NOT_FOUND = 2

    /** 未找到的歌多久后再试 */
    const val NOT_FOUND_RETRY_MS = 7L * 24 * 60 * 60 * 1000L

    /** 歌词缓存上限（首），超出按最久未更新淘汰；只清歌词，不清匹配 */
    private const val LYRIC_LIMIT = 2000

    /** 旧版匹配文件（最多 800 首、30 天），升级时并入本表 */
    private const val LEGACY_FILE = "accel_library_matches.json"
    private const val LEGACY_TTL_MS = 30L * 24 * 60 * 60 * 1000L

    @Volatile
    private var appContext: Context? = null

    private val importMutex = Mutex()

    @Volatile
    private var legacyImported = false

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun dao(): ServerTrackAccelDao? =
        appContext?.let { runCatching { ZdsDatabase.getInstance(it).serverTrackAccelDao() }.getOrNull() }

    // ---------------- 指纹 ----------------

    private data class FileId(val path: String, val size: Long)

    private fun fileIdOf(song: UnifiedSong): FileId? {
        val raw = song.rawMetaJson?.trim()?.takeIf { it.startsWith("{") }
        val o = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
        val path = o?.optString("filePath")?.trim().orEmpty()
            .ifBlank { LemonMusicProtocol.getServerFilePath(song.id, song.streamUrl, song.coverUrl).orEmpty() }
            .removePrefix("local:").trim()
        if (path.isBlank()) return null
        return FileId(path, o?.optLong("size", 0L) ?: 0L)
    }

    /** 服务器文件指纹：`路径|大小`；拿不到路径时为空串（视为「无法判断」，不作废） */
    fun fileKeyOf(song: UnifiedSong): String = fileIdOf(song)?.let { "${it.path}|${it.size}" }.orEmpty()

    /** 表里这一行是否还对应同一个服务器文件。任何一边信息不全都按「是」处理，避免误作废 */
    fun isSameFile(row: ServerTrackAccelEntity, song: UnifiedSong): Boolean {
        val now = fileIdOf(song) ?: return true
        if (row.fileKey.isBlank()) return true
        val oldPath = row.fileKey.substringBeforeLast('|')
        val oldSize = row.fileKey.substringAfterLast('|').toLongOrNull() ?: 0L
        if (oldPath != now.path) return false
        if (oldSize > 0 && now.size > 0 && oldSize != now.size) return false
        if (row.durationMs > 0 && song.durationMs > 0 && kotlin.math.abs(row.durationMs - song.durationMs) > 3_000L) return false
        return true
    }

    // ---------------- 平台曲目 JSON ----------------

    fun encodeMatch(song: UnifiedSong): String = JSONObject()
        .put("id", song.id)
        .put("title", song.title)
        .put("artist", song.artist)
        .put("album", song.album)
        .put("durationMs", song.durationMs)
        .put("coverUrl", song.coverUrl)
        .put("raw", song.rawMetaJson.orEmpty())
        .toString()

    fun decodeMatch(json: String): UnifiedSong? {
        if (json.isBlank()) return null
        val s = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val id = s.optString("id")
        if (id.isBlank()) return null
        return UnifiedSong(
            id = id,
            title = s.optString("title"),
            artist = s.optString("artist"),
            album = s.optString("album"),
            durationMs = s.optLong("durationMs"),
            coverUrl = s.optString("coverUrl"),
            serverId = "lemon_online",
            format = "mp3",
            rawMetaJson = s.optString("raw").takeIf { it.isNotBlank() }
        )
    }

    // ---------------- 读 ----------------

    suspend fun get(songId: String): ServerTrackAccelEntity? {
        ensureLegacyImported()
        return runCatching { dao()?.get(songId) }.getOrNull()
    }

    /** 表里已匹配、且服务器文件没变时返回平台曲目 */
    suspend fun matchFor(song: UnifiedSong): UnifiedSong? {
        val row = get(song.id) ?: return null
        if (row.matchStatus != STATUS_MATCHED || !isSameFile(row, song)) return null
        return decodeMatch(row.matchJson)
    }

    // ---------------- 写 ----------------

    private fun baseRow(serverId: String, song: UnifiedSong, old: ServerTrackAccelEntity?): ServerTrackAccelEntity {
        val sameFile = old != null && isSameFile(old, song)
        return ServerTrackAccelEntity(
            songId = song.id,
            serverId = serverId,
            fileKey = fileKeyOf(song).ifBlank { old?.fileKey.orEmpty() },
            title = song.title,
            artist = song.artist,
            durationMs = song.durationMs,
            // 换了文件：歌词、封面一并作废
            platformCoverUrl = if (sameFile) old!!.platformCoverUrl else "",
            lyricText = if (sameFile) old!!.lyricText else "",
            lyricSource = if (sameFile) old!!.lyricSource else "",
            lyricAt = if (sameFile) old!!.lyricAt else 0L,
            attempts = if (sameFile) old!!.attempts else 0
        )
    }

    suspend fun recordMatch(serverId: String, song: UnifiedSong, match: UnifiedSong, score: Int = 0) {
        val d = dao() ?: return
        runCatching {
            val old = d.get(song.id)
            val now = System.currentTimeMillis()
            val cover = match.coverUrl.takeIf { it.startsWith("http") }
                ?: old?.takeIf { isSameFile(it, song) }?.platformCoverUrl.orEmpty()
            d.upsert(
                baseRow(serverId, song, old).copy(
                    matchStatus = STATUS_MATCHED,
                    platform = PlaybackAccelerator.platformOf(match),
                    platformSongId = PlaybackAccelerator.rawIdOf(match),
                    matchJson = encodeMatch(match),
                    matchScore = if (score > 0) score else CrossPlatformMatcher.trackNameArtistScore(match, song.title, song.artist),
                    platformCoverUrl = cover,
                    attempts = (old?.attempts ?: 0) + 1,
                    lastTriedAt = now,
                    matchedAt = now
                )
            )
            val path = fileIdOf(song)?.path
            if (!path.isNullOrBlank() && cover.startsWith("http")) coverByPath[path] = cover
        }.onFailure { Log.w(TAG, "recordMatch failed: ${song.title}", it) }
    }

    suspend fun recordNotFound(serverId: String, song: UnifiedSong) {
        val d = dao() ?: return
        runCatching {
            val old = d.get(song.id)
            // 已匹配的歌这次没搜到（多半是网络问题）：保留原匹配，只记尝试时间
            if (old != null && old.matchStatus == STATUS_MATCHED && isSameFile(old, song)) {
                d.upsert(old.copy(lastTriedAt = System.currentTimeMillis()))
                return
            }
            d.upsert(
                baseRow(serverId, song, old).copy(
                    matchStatus = STATUS_NOT_FOUND,
                    attempts = (old?.attempts ?: 0) + 1,
                    lastTriedAt = System.currentTimeMillis()
                )
            )
        }.onFailure { Log.w(TAG, "recordNotFound failed: ${song.title}", it) }
    }

    /** 播放探活失败、确认匹配错了时调用：作废匹配，下次重新搜 */
    suspend fun invalidateMatch(songId: String) {
        val d = dao() ?: return
        runCatching {
            val old = d.get(songId) ?: return
            d.upsert(old.copy(matchStatus = STATUS_PENDING, platform = "", platformSongId = "", matchJson = "", matchScore = 0))
        }
    }

    /** 歌词写入（第 3 期）：只缓存播放过的歌，超出上限淘汰最久的 */
    suspend fun saveLyric(serverId: String, song: UnifiedSong, lyric: String, source: String) {
        if (lyric.isBlank()) return
        val d = dao() ?: return
        runCatching {
            val old = d.get(song.id)
            val row = if (old != null && isSameFile(old, song)) old else baseRow(serverId, song, old)
            d.upsert(row.copy(lyricText = lyric, lyricSource = source, lyricAt = System.currentTimeMillis()))
            val over = d.lyricCount() - LYRIC_LIMIT
            if (over > 0) d.dropOldestLyrics(over + 50)
        }.onFailure { Log.w(TAG, "saveLyric failed: ${song.title}", it) }
    }

    /** 表里缓存的歌词（服务器文件没变时） */
    suspend fun lyricFor(song: UnifiedSong): Pair<String, String>? {
        val row = get(song.id) ?: return null
        if (row.lyricText.isBlank() || !isSameFile(row, song)) return null
        return row.lyricText to row.lyricSource
    }

    /** 平台封面（第 4 期）：服务器文件没变时返回 */
    suspend fun platformCoverFor(song: UnifiedSong): String? {
        val row = get(song.id) ?: return null
        if (!isSameFile(row, song)) return null
        return row.platformCoverUrl.takeIf { it.startsWith("http") }
    }

    /** 服务器文件路径 → 平台封面（图片加载拦截器用；首次用时从表里整体读一次，之后随匹配增量更新） */
    private val coverByPath = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val coverMutex = Mutex()

    @Volatile
    private var coverMapLoaded = false

    /**
     * 服务器封面地址（`/api/tag/cover?path=…`）对应的平台封面；没有匹配或平台没给封面时返回 null。
     * 在图片加载线程调用（可能读一次数据库）
     */
    suspend fun platformCoverForServerCoverUrl(url: String): String? {
        if (!url.contains("/api/tag/cover")) return null
        val path = runCatching {
            val q = java.net.URI(url).rawQuery ?: return null
            q.split('&').firstOrNull { it.startsWith("path=") }
                ?.substringAfter("path=")
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        }.getOrNull()?.removePrefix("local:")?.trim()
        if (path.isNullOrBlank()) return null
        if (!coverMapLoaded) {
            coverMutex.withLock {
                if (!coverMapLoaded) {
                    ensureLegacyImported()
                    runCatching { dao()?.coverRows() }.getOrNull()?.forEach { r ->
                        val p = r.fileKey.substringBeforeLast('|')
                        if (p.isNotBlank()) coverByPath[p] = r.platformCoverUrl
                    }
                    coverMapLoaded = true
                }
            }
        }
        return coverByPath[path]
    }

    suspend fun deleteForServer(serverId: String) {
        runCatching { dao()?.deleteForServer(serverId) }
        coverByPath.clear()
        coverMapLoaded = false
    }

    // ---------------- 旧匹配文件并入 ----------------

    private fun legacyKey(serverId: String, title: String, artist: String, durationMs: Long): String =
        "$serverId|${SongMatchingResolver.normalizeTrackTitle(title)}|${SongMatchingResolver.normalizeArtist(artist)}|${durationMs / 1000L}"

    /**
     * 旧版 `accel_library_matches.json`（按「服务器|歌名|歌手|时长」记的对应关系）并入本表：
     * 按同样的规则给数据库里的服务器歌曲算键，对上的写成「已匹配」。只做一次，完成后把旧文件改名留底。
     */
    suspend fun ensureLegacyImported() {
        if (legacyImported) return
        importMutex.withLock {
            if (legacyImported) return
            val ctx = appContext ?: return
            val file = File(ctx.filesDir, LEGACY_FILE)
            if (!file.exists()) {
                legacyImported = true
                return
            }
            runCatching {
                val arr = JSONArray(file.readText(Charsets.UTF_8))
                val now = System.currentTimeMillis()
                // 键 → 平台曲目 JSON
                val legacy = HashMap<String, Pair<String, Long>>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val at = o.optLong("at")
                    if (now - at > LEGACY_TTL_MS) continue
                    val s = o.optJSONObject("song") ?: continue
                    legacy[o.optString("key")] = s.toString() to at
                }
                if (legacy.isEmpty()) return@runCatching 0
                val db = ZdsDatabase.getInstance(ctx)
                val serverIds = legacy.keys.map { it.substringBefore('|') }.toSet()
                val rows = ArrayList<ServerTrackAccelEntity>()
                val songDao = db.songDao()
                val accelDao = db.serverTrackAccelDao()
                for (serverId in serverIds) {
                    val songs = songDao.getAccelCandidates(listOf(serverId, "lemon_music"))
                    for (s in songs) {
                        val hit = legacy[legacyKey(serverId, s.title, s.artist, s.durationMs)] ?: continue
                        val match = decodeMatch(hit.first) ?: continue
                        if (accelDao.get(s.id)?.matchStatus == STATUS_MATCHED) continue
                        val unified = s.toUnifiedSong()
                        rows += ServerTrackAccelEntity(
                            songId = s.id,
                            serverId = serverId,
                            fileKey = fileKeyOf(unified),
                            title = s.title,
                            artist = s.artist,
                            durationMs = s.durationMs,
                            matchStatus = STATUS_MATCHED,
                            platform = PlaybackAccelerator.platformOf(match),
                            platformSongId = PlaybackAccelerator.rawIdOf(match),
                            matchJson = encodeMatch(match),
                            matchScore = CrossPlatformMatcher.trackNameArtistScore(match, s.title, s.artist),
                            platformCoverUrl = match.coverUrl.takeIf { it.startsWith("http") }.orEmpty(),
                            attempts = 1,
                            lastTriedAt = hit.second,
                            matchedAt = hit.second
                        )
                    }
                }
                rows.chunked(200).forEach { accelDao.upsertAll(it) }
                rows.size
            }.onSuccess { n ->
                Log.i(TAG, "legacy matches imported: $n")
                runCatching { file.renameTo(File(ctx.filesDir, "$LEGACY_FILE.imported")) }
            }.onFailure {
                Log.w(TAG, "legacy import failed", it)
            }
            legacyImported = true
        }
    }
}
