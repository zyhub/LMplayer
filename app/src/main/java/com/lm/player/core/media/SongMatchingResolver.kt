package com.lm.player.core.media

import android.util.Log
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.database.entity.DownloadEntity
import com.lm.player.core.database.entity.SongEntity
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.DownloadTask
import com.lm.player.core.model.UnifiedSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 全局统一的歌曲本地匹配与离线解析引擎
 * 职责：
 * 1. 规范化歌曲标题与艺术家字段（去除音轨编号、各种括号后缀、文件扩展名等）；
 * 2. 高性能 O(N) 内存哈希索引：建立 ID 表、标题+艺术家表、物理文件路径表；
 * 3. 统一解析任何数据源（播放列表/服务器文件夹/搜索/推荐）的歌曲，自动关联本地已缓存/已下载物理文件并标记 DOWNLOADED 状态；
 * 4. 在添加服务器、切换服务器或同步曲库时执行全量双向去重与物理校验。
 */
object SongMatchingResolver {

    private const val TAG = "SongMatchingResolver"
    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "ape", "dsd", "dsf")

    // 预编译正则表达式，彻底消除高频调用时成千上万次 Regex 实例分配与编译开销
    private val TRACK_NUM_REGEX = Regex("""^\d{1,3}[\.\-\s_]+""")
    private val BRACKET_GROUP_REGEX = Regex("""[\(\[\{（【]([^\)\]\}）】]*)[\)\]\}）】]""")
    private val AUDIO_EXT_REGEX = Regex("""\.(mp3|flac|wav|m4a|aac|ogg|opus|ape|dsd|dsf|alac|wma)$""", RegexOption.IGNORE_CASE)
    private val MULTI_SPACE_REGEX = Regex("""\s+""")

    // 仅属于纯音频编码/音质/官方MV/脏标等非版本属性的括号内容正则
    // 仅剥离此类纯技术标签，完整保留 (Live)、(xxx演唱会现场)、(黑胶版)、(伴奏)、(Single Version)、(R&B版)、(Remix) 等音乐版本标识
    private val TECHNICAL_BRACKET_INNER_REGEX = Regex(
        """^(?:flac|mp3|wav|ape|aac|m4a|ogg|opus|dsd|dsf|dff|alac|wma|pcm|hires|hi-res|hr|sq|hq|mq|cd|sacd|master|无损|無損|极高|超高|标准|高品质|高品質|高音质|母带|母帶|臻品|全景声|dolby|atmos|explicit|clean|脏标|官方版|official|mv|mv版|audio|video|hd|4k|\d{2,4}k|\d{2,4}kbps|\d{1,2}bit|\d{2,3}(?:\.\d)?khz|[\s\-\/\.\,\|\+·•])+?$""",
        RegexOption.IGNORE_CASE
    )

    // 标题末尾以连字符表示的版本后缀（如 " - Live", " - 伴奏", " - 现场版"），统一规整为标准括号版本格式
    private val TRAILING_DASH_VERSION_REGEX = Regex(
        """\s+[-—–]\s*(live|现场|现场版|演唱会|伴奏|纯音乐|inst\.?|instrumental|remix|acoustic|不插电|黑胶版|黑胶|vinyl|demo|小样|粤语版|国语版|日语版|英文版|single\s+version|album\s+version|radio\s+edit)$""",
        RegexOption.IGNORE_CASE
    )

    // 高并发内存记忆化缓存，避免同一曲库重复执行字符串正则替换
    private val normalizedTitleCache = java.util.concurrent.ConcurrentHashMap<String, String>(2048)
    private val normalizedArtistCache = java.util.concurrent.ConcurrentHashMap<String, String>(1024)

    fun unescapeMusicText(raw: String): String {
        if (raw.isEmpty()) return ""
        return raw
            .replace("\\&", "&")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .trim()
    }

    /**
     * 规范化曲目标题（版本感知型）：
     * - 去除开头的音轨编号（如 "01. ", "01 - ", "1. ", "1 - ", "01_"）
     * - 去除音频文件扩展名（.flac, .mp3 等）
     * - 仅剥离纯技术规格括号（如 "[FLAC]", "【Hi-Res】", "[320k]", "(无损)", "(Explicit)", "(官方版)"）
     * - 完整保留并规整所有音乐版本括号与后缀（如 "(live)", "(single version)", "(2005飞跃红馆香港演唱会现场)", "(黑胶版)", "(伴奏)", "(r&b版)" 等），
     *   坚决杜绝将同名歌曲的不同现场/伴奏/黑胶/翻唱/混音版本误判为同一首歌！
     */
    fun normalizeTrackTitle(title: String): String {
        if (title.isBlank()) return ""
        normalizedTitleCache[title]?.let { return it }
        var t = unescapeMusicText(title).lowercase()
        t = t.replace(AUDIO_EXT_REGEX, "").trim()
        t = t.replace(TRACK_NUM_REGEX, "").trim()
        t = BRACKET_GROUP_REGEX.replace(t) { matchResult ->
            val inner = matchResult.groupValues[1].trim().replace(MULTI_SPACE_REGEX, " ")
            if (inner.isEmpty() || TECHNICAL_BRACKET_INNER_REGEX.matches(inner)) {
                " "
            } else {
                " ($inner) "
            }
        }
        t = TRAILING_DASH_VERSION_REGEX.replace(t) { matchResult ->
            val ver = matchResult.groupValues[1].trim().replace(MULTI_SPACE_REGEX, " ")
            " ($ver)"
        }
        t = t.replace(MULTI_SPACE_REGEX, " ").trim()
        if (normalizedTitleCache.size > 8192) normalizedTitleCache.clear()
        normalizedTitleCache[title] = t
        return t
    }

    /**
     * 提取歌曲标题与所属专辑中的特殊版本维度特征（现场/黑胶/伴奏/混音/不插电/小样等）
     * 防止标题未标注但专辑名标注了 "xxx演唱会" 或 "xxx（黑胶版）" 的曲目与录音室原版混淆
     */
    fun extractEditionMarkers(title: String, album: String = ""): Set<String> {
        val combined = "${unescapeMusicText(title)} ${unescapeMusicText(album)}".lowercase()
        if (combined.isBlank()) return emptySet()
        val markers = LinkedHashSet<String>(4)
        if (combined.contains("live") || combined.contains("现场") || combined.contains("演唱会") ||
            combined.contains("音乐会") || combined.contains("嘉年华") || combined.contains("巡演") ||
            combined.contains("音乐节") || combined.contains("跨年")
        ) {
            markers.add("live")
        }
        if (combined.contains("黑胶") || combined.contains("vinyl")) {
            markers.add("vinyl")
        }
        if (combined.contains("伴奏") || combined.contains("instrumental") ||
            Regex("""\binst\b""").containsMatchIn(combined) ||
            combined.contains("卡拉ok") || combined.contains("karaoke") ||
            combined.contains("消音") || combined.contains("纯音乐")
        ) {
            markers.add("inst")
        }
        if (combined.contains("remix") || Regex("""\bdj\b""").containsMatchIn(combined) ||
            combined.contains("dj版") || combined.contains("慢摇") ||
            combined.contains("加快") || combined.contains("慢速") ||
            combined.contains("sped up") || combined.contains("slowed") ||
            combined.contains("0.8x") || combined.contains("1.2x")
        ) {
            markers.add("remix")
        }
        if (combined.contains("acoustic") || combined.contains("不插电") ||
            combined.contains("钢琴版") || combined.contains("吉他版")
        ) {
            markers.add("acoustic")
        }
        if (combined.contains("demo") || combined.contains("小样")) {
            markers.add("demo")
        }
        if (combined.contains("r&b")) {
            markers.add("rnb")
        }
        if (combined.contains("single version")) {
            markers.add("single_version")
        }
        if (combined.contains("album version")) {
            markers.add("album_version")
        }
        return markers
    }

    fun hasEditionConflict(
        title1: String,
        album1: String = "",
        title2: String,
        album2: String = ""
    ): Boolean {
        return extractEditionMarkers(title1, album1) != extractEditionMarkers(title2, album2)
    }

    /**
     * 规范化艺术家名称
     */
    fun normalizeArtist(artist: String): String {
        if (artist.isBlank()) return ""
        normalizedArtistCache[artist]?.let { return it }
        val a = unescapeMusicText(artist).lowercase()
        val result = if (a.contains("<unknown>") || a.contains("未知") || a == "local_storage" || a == "local_folder" || a == "local_saf") "" else a
        if (normalizedArtistCache.size > 4096) normalizedArtistCache.clear()
        normalizedArtistCache[artist] = result
        return result
    }

    /**
     * 精确匹配物理磁盘文件名（不含扩展名）与目标曲目：
     * 支持 "歌名"、"歌手 - 歌名"、"歌名 - 歌手"、"01. 歌名" 格式，
     * 严禁使用模糊子串 contains 导致 "我不难过.flac" 误匹配 "我不难过 (Live)" 或 "我不难过 (黑胶版)"
     */
    fun matchFileNameToTrack(
        fileNameWithoutExt: String,
        targetTitle: String,
        targetArtist: String,
        targetAlbum: String = ""
    ): Boolean {
        val normTargetTitle = normalizeTrackTitle(targetTitle)
        if (normTargetTitle.isBlank()) return false
        val normTargetArtist = normalizeArtist(targetArtist)

        val rawClean = unescapeMusicText(fileNameWithoutExt).replace(TRACK_NUM_REGEX, "").trim()
        if (rawClean.isBlank()) return false

        val wholeNorm = normalizeTrackTitle(rawClean)
        if (wholeNorm == normTargetTitle && !hasEditionConflict(rawClean, "", targetTitle, targetAlbum)) {
            return true
        }

        if (rawClean.contains(" - ")) {
            val parts = rawClean.split(" - ", limit = 2)
            if (parts.size == 2) {
                val p0Title = normalizeTrackTitle(parts[0])
                val p1Title = normalizeTrackTitle(parts[1])
                val p0Artist = normalizeArtist(parts[0])
                val p1Artist = normalizeArtist(parts[1])

                // 格式 1: "歌手 - 歌名"
                val artistMatchesP0 = normTargetArtist.isBlank() || p0Artist == normTargetArtist ||
                        p0Artist.contains(normTargetArtist) || normTargetArtist.contains(p0Artist)
                if (p1Title == normTargetTitle && artistMatchesP0 && !hasEditionConflict(parts[1], "", targetTitle, targetAlbum)) {
                    return true
                }

                // 格式 2: "歌名 - 歌手"
                val artistMatchesP1 = normTargetArtist.isBlank() || p1Artist == normTargetArtist ||
                        p1Artist.contains(normTargetArtist) || normTargetArtist.contains(p1Artist)
                if (p0Title == normTargetTitle && artistMatchesP1 && !hasEditionConflict(parts[0], "", targetTitle, targetAlbum)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * 统一判断两首歌曲是否在语义与版本上为同一首歌曲
     */
    fun isSongMatch(
        title1: String,
        artist1: String,
        durationMs1: Long = 0L,
        title2: String,
        artist2: String,
        durationMs2: Long = 0L,
        album1: String = "",
        album2: String = ""
    ): Boolean {
        val normTitle1 = normalizeTrackTitle(title1)
        val normTitle2 = normalizeTrackTitle(title2)
        if (normTitle1.isBlank() || normTitle2.isBlank()) return false
        if (normTitle1 != normTitle2) return false
        if (hasEditionConflict(title1, album1, title2, album2)) return false

        // 若双方均有有效时长且差距超过 5 秒，说明为不同编曲/现场/电台剪辑版本，绝不混用
        if (durationMs1 > 0L && durationMs2 > 0L && kotlin.math.abs(durationMs1 - durationMs2) > 5000L) {
            return false
        }

        val normArtist1 = normalizeArtist(artist1)
        val normArtist2 = normalizeArtist(artist2)

        return normArtist1.isBlank() || normArtist2.isBlank() ||
                normArtist1 == normArtist2 ||
                normArtist1.contains(normArtist2) ||
                normArtist2.contains(normArtist1)
    }

    /**
     * 批量解析并丰富歌曲列表的下载状态与本地文件挂载（O(N) 高性能哈希匹配）
     * 适用于：
     * 1. 播放列表中的歌曲（onFetchPlaylistSongs）
     * 2. 服务器文件夹中的歌曲（onFetchServerFolderSongs）
     * 3. 搜索结果与推荐歌曲
     */
    fun resolveSongList(
        incomingSongs: List<UnifiedSong>,
        allCachedSongs: List<UnifiedSong>,
        activeTasks: List<DownloadTask> = emptyList(),
        downloadDir: File? = null
    ): List<UnifiedSong> {
        if (incomingSongs.isEmpty()) return emptyList()

        val validPathMemo = HashMap<String, Boolean>(128)
        fun isLocalPathValidFast(path: String?, status: DownloadStatus): Boolean {
            if (path.isNullOrBlank()) return false
            if (path.startsWith("content://")) return true
            // 当处于纯内存列表解析 (downloadDir == null) 且状态已明确标记为 DOWNLOADED 时，避免在主线程重复触发磁盘 stat()
            if (downloadDir == null && status == DownloadStatus.DOWNLOADED) return true
            return validPathMemo.getOrPut(path) {
                File(path).let { it.exists() && it.length() > 0 }
            }
        }

        // 1. 预构建内存快速哈希索引表（支持同 (normTitle, normArtist) 下按版本/专辑精确区分）
        val cachedById = HashMap<String, UnifiedSong>(allCachedSongs.size)
        val cachedByNormalizedKey = HashMap<String, MutableList<UnifiedSong>>(allCachedSongs.size)

        for (song in allCachedSongs) {
            cachedById[song.id] = song
            val normTitle = normalizeTrackTitle(song.title)
            val normArtist = normalizeArtist(song.artist)
            if (normTitle.isNotBlank()) {
                val normKey = "$normTitle|||$normArtist"
                cachedByNormalizedKey.getOrPut(normKey) { ArrayList(2) }.add(song)
            }
        }

        val activeTaskBySongId = if (activeTasks.isEmpty()) emptyMap() else activeTasks.associateBy { it.song.id }

        // 2. 收集离线存储目录中的物理文件（如果可用）
        val physicalFiles = if (downloadDir != null && downloadDir.exists() && downloadDir.isDirectory) {
            try {
                val list = mutableListOf<File>()
                downloadDir.walkTopDown().maxDepth(5).forEach { f ->
                    if (f.isFile && f.extension.lowercase() in AUDIO_EXTENSIONS && f.length() > 50 * 1024) {
                        list.add(f)
                    }
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        // 3. 管道化逐首匹配
        return incomingSongs.map { rawSong ->
            val activeTask = activeTaskBySongId[rawSong.id]
            val isDownloading = activeTask?.status == DownloadStatus.DOWNLOADING

            val normKey = "${normalizeTrackTitle(rawSong.title)}|||${normalizeArtist(rawSong.artist)}"
            val match = cachedById[rawSong.id] ?: run {
                val candidates = cachedByNormalizedKey[normKey]
                if (candidates.isNullOrEmpty()) {
                    null
                } else {
                    val compatible = candidates.filter { cand ->
                        isSongMatch(
                            title1 = rawSong.title,
                            artist1 = rawSong.artist,
                            durationMs1 = rawSong.durationMs,
                            title2 = cand.title,
                            artist2 = cand.artist,
                            durationMs2 = cand.durationMs,
                            album1 = rawSong.album,
                            album2 = cand.album
                        )
                    }
                    compatible.firstOrNull { isLocalPathValidFast(it.localFilePath, it.downloadStatus) }
                        ?: compatible.firstOrNull { it.streamUrl.isNotBlank() }
                        ?: compatible.firstOrNull()
                }
            }

            // 检查本地离线物理文件真实有效性（防止指向已删除或空路径导致播放失败）
            val selfPathValid = isLocalPathValidFast(rawSong.localFilePath, rawSong.downloadStatus)
            val matchHasLocal = match != null && isLocalPathValidFast(match.localFilePath, match.downloadStatus)

            // 检查服务端存储状态（仅当匹配的服务器曲目本身具有有效流地址或非未就绪的在线占位符时视为服务端已存）
            val matchHasServer = match != null && (
                match.serverId == "lemon_music" ||
                (match.serverId.isNotBlank() && match.serverId !in listOf("local_storage", "local_folder", "local_saf", "lemon_online"))
            ) && (!match.id.startsWith("lemon_online_") || match.streamUrl.isNotBlank())

            // 物理磁盘匹配（兜底，严格校验文件名与版本一致性，杜绝 contains 子串误匹配）
            var matchedPhysicalPath: String? = null
            if (!selfPathValid && !matchHasLocal && physicalFiles.isNotEmpty()) {
                val normArtist = normalizeArtist(rawSong.artist)
                val matchedFile = physicalFiles.firstOrNull { f ->
                    val titleMatch = matchFileNameToTrack(f.nameWithoutExtension, rawSong.title, rawSong.artist, rawSong.album)
                    val artistMatch = normArtist.isBlank() ||
                            f.absolutePath.lowercase().contains(normArtist) ||
                            f.nameWithoutExtension.lowercase().contains(normArtist)
                    titleMatch && artistMatch
                }
                if (matchedFile != null) {
                    matchedPhysicalPath = matchedFile.absolutePath
                }
            }

            val hasLocal = selfPathValid || matchHasLocal || matchedPhysicalPath != null
            val effectiveLocalPath = if (selfPathValid) rawSong.localFilePath else if (matchHasLocal) match?.localFilePath else matchedPhysicalPath

            val effectiveServerId = when {
                matchHasServer -> match!!.serverId
                rawSong.serverId.isNotBlank() && rawSong.serverId !in listOf("local_storage", "local_folder", "local_saf", "lemon_online") -> rawSong.serverId
                hasLocal && (rawSong.serverId == "lemon_online" || rawSong.serverId.isBlank()) -> "local_storage"
                else -> rawSong.serverId
            }

            // 优先使用本地已下载文件或柠檬音乐服务器已入库曲目流地址 (/api/play/local)，避免复用旧音质的临时代理流 (/api/play/proxy)
            val effectiveStreamUrl = when {
                rawSong.streamUrl.isNotBlank() && !rawSong.streamUrl.startsWith("lemon_online://") && !rawSong.streamUrl.contains("/api/play/proxy") -> rawSong.streamUrl
                hasLocal && !effectiveLocalPath.isNullOrBlank() -> effectiveLocalPath
                match != null && match.streamUrl.isNotBlank() && !match.streamUrl.startsWith("lemon_online://") && !match.streamUrl.contains("/api/play/proxy") -> match.streamUrl
                else -> rawSong.streamUrl
            }

            val effectiveDownloadStatus = when {
                isDownloading -> DownloadStatus.DOWNLOADING
                hasLocal -> DownloadStatus.DOWNLOADED
                match != null -> match.downloadStatus
                else -> DownloadStatus.NOT_DOWNLOADED
            }

            val effectiveProgress = when {
                isDownloading && activeTask != null -> activeTask.progress
                hasLocal -> 1f
                else -> 0f
            }

            val effectiveCover = when {
                rawSong.coverUrl.isNotBlank() -> rawSong.coverUrl
                match?.coverUrl?.isNotBlank() == true -> match.coverUrl
                else -> ""
            }

            val effectiveAddedTimestamp = when {
                match != null && match.addedTimestamp > 0 -> match.addedTimestamp
                rawSong.addedTimestamp > 0 -> rawSong.addedTimestamp
                else -> 0L
            }

            val localExt = effectiveLocalPath?.takeIf { !it.startsWith("content://") }
                ?.substringAfterLast('.', "")
                ?.lowercase()
                ?.takeIf { it in listOf("flac", "wav", "mp3", "m4a", "aac", "ogg", "ape", "alac") }

            val effectiveFormat = when {
                !localExt.isNullOrBlank() -> localExt
                match != null && match.format.isNotBlank() && (hasLocal || rawSong.format.equals("mp3", ignoreCase = true)) -> match.format
                else -> rawSong.format
            }

            val effectiveBitRate = when {
                !effectiveLocalPath.isNullOrBlank() && !effectiveLocalPath.startsWith("content://") -> {
                    val f = File(effectiveLocalPath)
                    val durSec = ((if (rawSong.durationMs > 0) rawSong.durationMs else (match?.durationMs ?: 0L)) / 1000L)
                    if (f.exists() && f.length() > 0 && durSec in 15..3600) {
                        ((f.length() * 8L) / (durSec * 1000L)).toInt().coerceIn(64, 4608)
                    } else if (effectiveFormat in listOf("flac", "wav", "ape", "alac")) {
                        (match?.bitRate ?: rawSong.bitRate).coerceAtLeast(960)
                    } else {
                        match?.bitRate ?: rawSong.bitRate
                    }
                }
                effectiveFormat in listOf("flac", "wav", "ape", "alac") -> (match?.bitRate ?: rawSong.bitRate).coerceAtLeast(960)
                else -> rawSong.bitRate
            }

            rawSong.copy(
                title = unescapeMusicText(rawSong.title),
                artist = unescapeMusicText(rawSong.artist),
                album = unescapeMusicText(rawSong.album),
                serverId = effectiveServerId,
                streamUrl = effectiveStreamUrl,
                localFilePath = effectiveLocalPath,
                downloadStatus = effectiveDownloadStatus,
                downloadProgress = effectiveProgress,
                coverUrl = effectiveCover,
                format = effectiveFormat,
                bitRate = effectiveBitRate,
                addedTimestamp = effectiveAddedTimestamp
            )
        }
    }

    /**
     * 单曲快速匹配并解析
     */
    fun resolveSingleSong(
        rawSong: UnifiedSong,
        allCachedSongs: List<UnifiedSong>,
        activeTasks: List<DownloadTask> = emptyList(),
        downloadDir: File? = null
    ): UnifiedSong {
        return resolveSongList(listOf(rawSong), allCachedSongs, activeTasks, downloadDir).firstOrNull() ?: rawSong
    }

    /**
     * 智能保护性同步与写入服务器歌曲至 Room 数据库：
     * 1. 自动保留已有歌曲的 localFilePath、downloadStatus (DOWNLOADED)、isFavorite 与 relativeFolderPath，
     *    坚决杜绝因重新拉取线上数据而把本地已缓存/已下载状态抹除的问题！
     * 2. 自动关联 downloadDao 中已完成且物理文件有效的下载记录；
     * 3. 写入数据库后，自动触发全局匹配与双向关联去重。
     */
    suspend fun syncAndUpsertServerSongs(
        database: ZdsDatabase,
        incomingServerSongs: List<SongEntity>,
        downloadDir: File? = null,
        targetServerId: String? = null
    ): Int = withContext(Dispatchers.IO) {
        if (incomingServerSongs.isEmpty()) return@withContext 0

        val effectiveServerId = targetServerId?.ifBlank { null } ?: incomingServerSongs.firstOrNull()?.serverId

        // 1. 读取数据库中现有的所有歌曲与已有下载记录
        val existingSongs = database.songDao().getAllSongsList()
        val existingById = existingSongs.associateBy { it.id }
        val existingDownloads = database.downloadDao().getAllDownloadsList().associateBy { it.songId }

        // 预构建规范化标题分桶索引，将 O(N^2) 全表扫描优化为 O(1) 哈希桶查找
        val existingByNormTitle = HashMap<String, MutableList<SongEntity>>(existingSongs.size)
        val localScannedByNormTitle = HashMap<String, MutableList<SongEntity>>(256)
        for (song in existingSongs) {
            val normT = normalizeTrackTitle(song.title)
            if (normT.isNotBlank()) {
                existingByNormTitle.getOrPut(normT) { ArrayList(2) }.add(song)
                if (song.serverId in listOf("local_storage", "local_folder", "local_saf")) {
                    localScannedByNormTitle.getOrPut(normT) { ArrayList(2) }.add(song)
                }
            }
        }

        val validFileMemo = HashMap<String, Boolean>(256)
        fun checkFileExists(path: String?): Boolean {
            if (path.isNullOrBlank()) return false
            if (path.startsWith("content://")) return true
            return validFileMemo.getOrPut(path) {
                File(path).let { it.exists() && it.length() > 0 }
            }
        }

        // 3. 构建保护性实体集合
        val preservedEntities = incomingServerSongs.map { incoming ->
            val existing = existingById[incoming.id]
            val download = existingDownloads[incoming.id]
            val normIncomingTitle = normalizeTrackTitle(incoming.title)

            // 检查已记录的本地路径有效性
            // NAS 路径（来自服务端曲库的 localFilePath，如 /vol1/...）在安卓本地不存在，
            // 不能用 File(path).exists() 判断，否则所有 NAS 歌曲都会被误判为未下载，
            // 导致 getDownloadedSongsWithLocalPath() 查不到数据、收藏/歌单快照升级无法执行。
            val existingPath = existing?.localFilePath
            val isExistingFileValid = checkFileExists(existingPath)
            val isNasPath = existingPath?.isNotBlank() == true &&
                !existingPath.startsWith("content://") &&
                !existingPath.startsWith("/storage/") &&
                !existingPath.startsWith("/sdcard/") &&
                !existingPath.startsWith("/data/")

            val downloadPath = download?.localFilePath
            val isDownloadFileValid = checkFileExists(downloadPath)

            var validLocalPath = when {
                isNasPath -> existingPath
                isExistingFileValid -> existingPath
                isDownloadFileValid -> downloadPath
                else -> null
            }

            // 若依然未找到本地文件，尝试在本地扫描独立歌曲的同标题哈希桶中进行 O(1) 匹配
            if (validLocalPath == null && normIncomingTitle.isNotBlank()) {
                val matchedLocal = localScannedByNormTitle[normIncomingTitle]?.firstOrNull { local ->
                    isSongMatch(
                        title1 = incoming.title,
                        artist1 = incoming.artist,
                        durationMs1 = incoming.durationMs,
                        title2 = local.title,
                        artist2 = local.artist,
                        durationMs2 = local.durationMs,
                        album1 = incoming.album,
                        album2 = local.album
                    )
                }
                if (matchedLocal?.localFilePath != null && checkFileExists(matchedLocal.localFilePath)) {
                    validLocalPath = matchedLocal.localFilePath
                }
            }

            val existingMatch = existing ?: if (normIncomingTitle.isNotBlank()) {
                existingByNormTitle[normIncomingTitle]?.firstOrNull {
                    isSongMatch(
                        title1 = incoming.title,
                        artist1 = incoming.artist,
                        durationMs1 = incoming.durationMs,
                        title2 = it.title,
                        artist2 = it.artist,
                        durationMs2 = it.durationMs,
                        album1 = incoming.album,
                        album2 = it.album
                    )
                }
            } else null
            // 「加入时间」优先取服务器文件时间 (incoming，来自 getSongList 的 mtime)：
            // 在线模式的「按加入时间」排序要还原服务器"最近添加"的先后，而本地库里的值
            // 历史上是"首次同步那一刻"，若继续让 existing 优先，老库永远纠正不过来。
            // 服务器没给时间时（如 /api/download/list 补全的条目）再依次回落。
            val finalTimestamp = when {
                incoming.addedTimestamp > 0 -> incoming.addedTimestamp
                existing != null && existing.addedTimestamp > 0 -> existing.addedTimestamp
                existingMatch != null && existingMatch.addedTimestamp > 0 -> existingMatch.addedTimestamp
                download != null && download.completedTimestamp > 0 -> download.completedTimestamp
                validLocalPath != null -> System.currentTimeMillis()
                else -> 0L
            }

            // incoming.localFilePath 是服务端曲库返回的 NAS 路径（如 /vol1/...），
            // 必须保留，不能被 validLocalPath（本地匹配结果）覆盖。
            // NAS 路径在安卓本地不存在，checkFileExists 返回 false，
            // 所以需要单独判断：有 NAS 路径就标记为已下载。
            val incomingNasPath = incoming.localFilePath?.takeIf {
                it.isNotBlank() && !it.startsWith("content://") &&
                    !it.startsWith("/storage/") && !it.startsWith("/sdcard/") && !it.startsWith("/data/")
            }
            val finalLocalPath = validLocalPath ?: incomingNasPath
            val finalDownloadStatus = if (finalLocalPath != null) DownloadStatus.DOWNLOADED else DownloadStatus.NOT_DOWNLOADED
            val finalIsFavorite = incoming.isFavorite || (existing?.isFavorite == true) || (existingMatch?.isFavorite == true)
            val candidateRelPath = incoming.relativeFolderPath ?: existing?.relativeFolderPath ?: existingMatch?.relativeFolderPath
            val finalRelPath = candidateRelPath?.takeIf { !it.startsWith("{") && !it.contains("\"") && !it.contains("_id__") && it.length <= 100 }
            val finalCover = if (incoming.coverUrl.isNotBlank()) incoming.coverUrl else (existing?.coverUrl ?: existingMatch?.coverUrl ?: "")

            val localExt = finalLocalPath?.takeIf { !it.startsWith("content://") }
                ?.substringAfterLast('.', "")
                ?.lowercase()
                ?.takeIf { it in listOf("flac", "wav", "mp3", "m4a", "aac", "ogg", "ape", "alac") }
            val finalFormat = localExt ?: incoming.format
            val finalBitRate = if (!localExt.isNullOrBlank() && localExt in listOf("flac", "wav", "ape", "alac")) {
                incoming.bitRate.coerceAtLeast(960)
            } else {
                incoming.bitRate
            }

            incoming.copy(
                localFilePath = finalLocalPath,
                downloadStatus = finalDownloadStatus,
                format = finalFormat,
                bitRate = finalBitRate,
                isFavorite = finalIsFavorite,
                relativeFolderPath = finalRelPath,
                coverUrl = finalCover,
                addedTimestamp = finalTimestamp
            )
        }

        // 4. 安全覆盖写入数据库
        database.songDao().insertSongs(preservedEntities)

        // 4.1 差量审查与孤儿清理 (Pruning) - 解决服务端删歌/改名后客户端僵尸曲目残留导致曲目总数对不上的核心问题
        if (!effectiveServerId.isNullOrBlank() && effectiveServerId !in listOf("local_storage", "local_folder", "local_saf", "lemon_online")) {
            val incomingIds = preservedEntities.map { it.id }.toSet()
            val serverIdsToCheck = if (effectiveServerId.startsWith("srv_")) {
                listOf(effectiveServerId, "lemon_music")
            } else {
                listOf(effectiveServerId)
            }
            val existingServerSongs = existingSongs.filter { it.serverId in serverIdsToCheck }
            val orphanedSongs = existingServerSongs.filter { !incomingIds.contains(it.id) }

            if (orphanedSongs.isNotEmpty()) {
                val toDeleteIds = ArrayList<String>()
                for (orphan in orphanedSongs) {
                    val localPath = orphan.localFilePath
                    val hasValidLocalFile = !localPath.isNullOrBlank() && File(localPath).let { it.exists() && it.length() > 0 }
                    if (hasValidLocalFile) {
                        // 本地已有有效物理下载文件：降级归属为 local_storage，保留本地离线播放，但不占在线服务器库名额
                        database.songDao().updateServerId(orphan.id, "local_storage")
                    } else {
                        // 本地无实体文件：彻底从数据库物理删除
                        toDeleteIds.add(orphan.id)
                    }
                }
                if (toDeleteIds.isNotEmpty()) {
                    database.songDao().deleteSongsByIds(toDeleteIds)
                    Log.i(TAG, "Pruned ${toDeleteIds.size} orphaned tracks from server $effectiveServerId")
                }
            }
        }

        // 5. 触发全局匹配与双向关联去重
        autoMatchAndSyncServer(database, downloadDir, preservedEntities)
    }

    /**
     * 全局统一的服务器自动匹配与双向关联去重调度器
     * 在添加服务器、选择服务器或全量同步时调用
     */
    suspend fun autoMatchAndSyncServer(
        database: ZdsDatabase,
        downloadDir: File? = null,
        providedServerSongs: List<SongEntity>? = null
    ): Int = withContext(Dispatchers.IO) {
        var totalMerged = 0
        try {
            // 1. 执行本地音频记录与线上歌曲的双向哈希匹配与去重
            totalMerged = LocalMediaScanner.matchAndMergeLocalWithServer(database, providedServerSongs)

            // 2. 全量核对物理文件真实性，同步已下载索引表
            LocalMediaScanner.verifyAndSyncAllServerSongDownloadStatus(database, downloadDir)

            Log.i(TAG, "autoMatchAndSyncServer completed. Total merged: $totalMerged")
        } catch (e: Exception) {
            Log.e(TAG, "Error in autoMatchAndSyncServer", e)
        }
        totalMerged
    }
}
