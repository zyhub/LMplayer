package com.lm.player.core.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.database.entity.SongEntity
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.network.LemonMusicProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 曲库比对互斥锁：本地扫描后的合并、登录服务器后的同步比对、核对下载状态
 * 同一时间只允许一个在跑，避免两边同时合并同一批歌曲造成重复条目或「已下载」标记错乱。
 */
object LibraryMatchLock {
    val mutex = Mutex()
}

/** 本地扫描与曲库比对专用的低优先级线程池 (3 个线程)，尽量不和播放、界面抢 CPU */
object LocalScanDispatchers {
    val background: CoroutineDispatcher by lazy {
        Executors.newFixedThreadPool(3) { runnable ->
            Thread({
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                } catch (_: Throwable) {}
                runnable.run()
            }, "lm-local-scan").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
}

/**
 * 本地音乐后台扫描引擎 (移植自 TV 端第十九节，按手机端原有的歌曲 ID / 过滤规则适配)。
 *
 * 1. 查找文件：合并重叠目录 (上级目录已包含的子目录不再重扫)；
 * 2. 增量判断：已入库且修改时间没变的文件直接跳过；
 * 3. 读取歌曲信息：3 个线程并行；FLAC 直接读文件头，其他格式或读取失败退回系统解码器；每 100 首写一次曲库；
 * 4. (可选) 系统媒体库：只补充上面没扫到的文件；
 * 5. 补封面：按图片内容去重、保持原图，存到应用数据目录 (系统不会自动清理)；
 * 6. 与服务器曲库比对：使用索引化后的比对。
 *
 * 扫描跑在进程级作用域里，离开设置页不会中断；进度通过 [state] 暴露给界面。
 * 内嵌歌词不在扫描时读取：播放时由 LyricsManager 直接从文件读取，扫描不改变文件和路径。
 */
object LocalScanEngine {

    private const val TAG = "LocalScanEngine"
    private const val WORKERS = 3
    private const val BATCH = 100
    private const val ART_DIR = "local_art"
    /** 与原 scanCustomDirectory 一致：过滤小于 50KB 的短音效 */
    private const val MIN_FILE_BYTES = 50L * 1024

    enum class Phase { DISCOVER, METADATA, MEDIASTORE, COVERS, MATCHING, DONE, CANCELLED, FAILED }

    data class ScanState(
        val phase: Phase,
        val processed: Int = 0,
        val total: Int = 0,
        val currentName: String = "",
        val foundFiles: Int = 0,
        val newSongs: Int = 0,
        val skippedUnchanged: Int = 0,
        val coversDone: Int = 0,
        val matched: Int = 0,
        val startedAt: Long = System.currentTimeMillis(),
        val finishedAt: Long = 0L,
        val summary: String = ""
    ) {
        val isRunning: Boolean get() = phase != Phase.DONE && phase != Phase.CANCELLED && phase != Phase.FAILED
    }

    data class MatchProgress(val stage: String, val processed: Int, val total: Int)

    private val _state = MutableStateFlow<ScanState?>(null)
    val state: StateFlow<ScanState?> = _state.asStateFlow()

    private val _matchProgress = MutableStateFlow<MatchProgress?>(null)
    /** 曲库比对进度 (包括登录服务器后触发的比对)，null = 没有比对在进行 */
    val matchProgress: StateFlow<MatchProgress?> = _matchProgress.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + LocalScanDispatchers.background)

    @Volatile
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun reportMatchProgress(stage: String?, processed: Int, total: Int) {
        _matchProgress.value = if (stage == null) null else MatchProgress(stage, processed, total)
    }

    /**
     * 开始后台扫描。已有扫描在进行时返回 false (并提示)。
     * @param includeMediaStore 「立即扫描比对」为 true：额外补充系统媒体库中目录扫描没覆盖到的歌曲
     */
    fun start(context: Context, targets: List<String>, includeMediaStore: Boolean, downloadDir: File?): Boolean {
        val app = context.applicationContext
        if (job?.isActive == true) {
            toast(app, "正在扫描中，请等待当前扫描完成")
            return false
        }
        _state.value = ScanState(Phase.DISCOVER)
        job = scope.launch { runScan(app, targets, includeMediaStore, downloadDir) }
        return true
    }

    fun cancel() {
        job?.cancel()
    }

    /** 关闭已结束扫描的结果卡片 */
    fun dismiss() {
        if (job?.isActive != true) _state.value = null
    }

    /**
     * 不显示进度的增量目录扫描 (供切换本地模式、同步下载目录等后台流程使用)：
     * 只读取新增或改过的文件并补封面，不做服务器比对，返回新读取的歌曲数。
     */
    suspend fun scanDirectoryQuietly(context: Context, folderPath: String): Int = withContext(LocalScanDispatchers.background) {
        val app = context.applicationContext
        val counters = Counters()
        scanCore(app, ZdsDatabase.getInstance(app), listOf(folderPath), includeMediaStore = false, counters = counters, report = null)
        counters.newSongs.get()
    }

    private fun toast(app: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            try { Toast.makeText(app, text, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
        }
    }

    private fun setState(transform: (ScanState) -> ScanState) {
        _state.update { current -> current?.let(transform) }
    }

    private class WorkItem(val file: File, val existing: SongEntity?)

    private class CoverJob(
        val songId: String,
        val path: String,
        val albumIdFallback: Long = 0L,
        /** FLAC 已解析时的封面位置；-2 表示未解析 (需要重新解析或走系统解码器) */
        val flacOffset: Long = -2L,
        val flacLength: Int = 0
    )

    private class Counters {
        val newSongs = AtomicInteger(0)
        val coversDone = AtomicInteger(0)
        @Volatile var skipped = 0
        @Volatile var foundFiles = 0
    }

    /** 每个线程一个系统解码器，每 200 首重建一次 (避免 native 状态累积) */
    private class RetrieverHolder {
        private var retriever: MediaMetadataRetriever? = null
        private var uses = 0
        fun get(): MediaMetadataRetriever {
            val current = retriever
            if (current != null && uses < 200) {
                uses++
                return current
            }
            runCatching { current?.release() }
            uses = 1
            return MediaMetadataRetriever().also { retriever = it }
        }
        fun release() {
            runCatching { retriever?.release() }
            retriever = null
        }
    }

    private fun hasReadPermission(app: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(app, perm) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun runScan(app: Context, targets: List<String>, includeMediaStore: Boolean, downloadDir: File?) {
        val db = ZdsDatabase.getInstance(app)
        val startedAt = System.currentTimeMillis()
        val counters = Counters()
        var matched = 0

        suspend fun runMatching() {
            setState { it.copy(phase = Phase.MATCHING, processed = 0, total = 0, currentName = "") }
            // 顺序与原先「立即扫描比对」一致：合并本地歌曲 → 核对下载状态 → 再合并一次
            LocalMediaScanner.matchAndMergeLocalWithServer(db)
            matched = LocalMediaScanner.verifyAndSyncAllServerSongDownloadStatus(db, downloadDir)
            LocalMediaScanner.matchAndMergeLocalWithServer(db)
        }

        try {
            scanCore(app, db, targets, includeMediaStore, counters, report = { transform -> setState(transform) })
            runMatching()

            val elapsed = System.currentTimeMillis() - startedAt
            val newSongs = counters.newSongs.get()
            val covers = counters.coversDone.get()
            val summary = if (counters.foundFiles == 0 && !hasReadPermission(app)) {
                "扫描到 0 首：未授予存储读取权限，无法读取本地文件。请在系统设置中开启存储 / 音乐权限后重试"
            } else {
                buildString {
                    append("扫描完成：找到 ${counters.foundFiles} 个音频文件，新增 $newSongs 首")
                    if (counters.skipped > 0) append("，${counters.skipped} 首未变化已跳过")
                    if (covers > 0) append("，补封面 $covers 张")
                    append("，匹配服务器歌曲 $matched 首。用时 ${formatElapsed(elapsed)}")
                }
            }
            setState {
                it.copy(
                    phase = Phase.DONE, newSongs = newSongs, skippedUnchanged = counters.skipped,
                    coversDone = covers, matched = matched,
                    finishedAt = System.currentTimeMillis(), summary = summary, currentName = ""
                )
            }
            toast(app, summary)
        } catch (e: CancellationException) {
            // 取消：已扫到的歌曲在 scanCore 里已写库；做一次比对避免重复条目；封面下次扫描时补齐
            withContext(NonCancellable) {
                try {
                    runMatching()
                } catch (inner: Exception) {
                    Log.w(TAG, "Post-cancel matching failed: ${inner.message}")
                }
                val summary = "已取消扫描：已保存 ${counters.newSongs.get()} 首，未完成的封面会在下次扫描时补齐"
                setState {
                    it.copy(phase = Phase.CANCELLED, newSongs = counters.newSongs.get(), finishedAt = System.currentTimeMillis(), summary = summary, currentName = "")
                }
                toast(app, summary)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Local scan failed", e)
            withContext(NonCancellable) {
                val summary = "扫描出错：${e.message ?: e.javaClass.simpleName}。已保存 ${counters.newSongs.get()} 首"
                setState { it.copy(phase = Phase.FAILED, finishedAt = System.currentTimeMillis(), summary = summary, currentName = "") }
                toast(app, summary)
            }
        }
    }

    /**
     * 扫描主体：查找文件 → 增量判断 → 读取歌曲信息 → (媒体库补充) → 补封面。
     * [report] 为 null 时不更新界面进度 (后台静默扫描)。被取消时会先把已读到的歌曲写库再抛出。
     */
    private suspend fun scanCore(
        app: Context,
        db: ZdsDatabase,
        targets: List<String>,
        includeMediaStore: Boolean,
        counters: Counters,
        report: (((ScanState) -> ScanState) -> Unit)?
    ) {
        fun update(transform: (ScanState) -> ScanState) { report?.invoke(transform) }

        val pending = ArrayList<SongEntity>(BATCH)
        val pendingLock = Mutex()
        suspend fun flush() {
            val batch = pendingLock.withLock {
                if (pending.isEmpty()) null else ArrayList(pending).also { pending.clear() }
            }
            if (batch != null) db.songDao().insertSongs(batch)
        }
        val coverJobs = ConcurrentLinkedQueue<CoverJob>()

        try {
            // ---------- 1. 查找文件 ----------
            val files = discover(targets) { count, name ->
                update { it.copy(phase = Phase.DISCOVER, foundFiles = count, currentName = name) }
            }
            counters.foundFiles = files.size
            update { it.copy(foundFiles = files.size) }

            // ---------- 2. 增量判断 ----------
            val all = db.songDao().getAllSongsList()
            val knownByPath = HashMap<String, SongEntity>(all.size)
            val localByPath = HashMap<String, SongEntity>()
            for (s in all) {
                val p = s.localFilePath?.takeIf { it.isNotBlank() } ?: continue
                knownByPath.putIfAbsent(p, s)
                if (s.serverId == "local_folder") localByPath[p] = s
            }
            val work = ArrayList<WorkItem>(files.size)
            var skipped = 0
            for (f in files) {
                val path = f.absolutePath
                val local = localByPath[path]
                val known = local ?: knownByPath[path]
                if (local != null && local.addedTimestamp != f.lastModified()) {
                    work.add(WorkItem(f, local)) // 文件被改过 (如重新写过标签)：重新读取
                } else if (known != null) {
                    skipped++
                    if (needsCover(known.coverUrl)) coverJobs.add(CoverJob(known.id, path))
                } else {
                    work.add(WorkItem(f, null))
                }
            }
            counters.skipped = skipped

            // ---------- 3. 读取歌曲信息 (并行) ----------
            val total = work.size
            update { it.copy(phase = Phase.METADATA, processed = 0, total = total, skippedUnchanged = skipped) }
            if (total > 0) {
                val next = AtomicInteger(0)
                val processed = AtomicInteger(0)
                coroutineScope {
                    repeat(WORKERS) {
                        launch {
                            val holder = RetrieverHolder()
                            try {
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    yield()
                                    val i = next.getAndIncrement()
                                    if (i >= total) break
                                    val item = work[i]
                                    val result = readMetadata(app, item, holder)
                                    if (result != null) {
                                        val size = pendingLock.withLock {
                                            pending.add(result.first)
                                            pending.size
                                        }
                                        result.second?.let { coverJobs.add(it) }
                                        counters.newSongs.incrementAndGet()
                                        if (size >= BATCH) flush()
                                    }
                                    val p = processed.incrementAndGet()
                                    if (p % 5 == 0 || p == total) {
                                        update { it.copy(processed = p, currentName = item.file.name, newSongs = counters.newSongs.get()) }
                                    }
                                }
                            } finally {
                                holder.release()
                            }
                        }
                    }
                }
                flush()
            }

            // ---------- 4. 系统媒体库补充 ----------
            if (includeMediaStore) {
                update { it.copy(phase = Phase.MEDIASTORE, processed = 0, total = 0, currentName = "") }
                val scannedPaths = HashSet<String>(files.size)
                files.forEach { scannedPaths.add(it.absolutePath) }
                collectMediaStore(app, knownByPath.keys, scannedPaths) { entity, job ->
                    val size = pendingLock.withLock {
                        pending.add(entity)
                        pending.size
                    }
                    coverJobs.add(job)
                    counters.newSongs.incrementAndGet()
                    if (size >= BATCH) flush()
                }
                flush()
                update { it.copy(newSongs = counters.newSongs.get()) }
            }

            // ---------- 5. 补封面 (并行) ----------
            val jobs = coverJobs.toList()
            if (jobs.isNotEmpty()) {
                update { it.copy(phase = Phase.COVERS, processed = 0, total = jobs.size, currentName = "") }
                val artDir = File(app.filesDir, ART_DIR).apply { if (!exists()) mkdirs() }
                val dirCoverCache = ConcurrentHashMap<String, String>()
                val updates = ArrayList<Pair<String, String>>(BATCH)
                val updatesLock = Mutex()
                suspend fun flushCovers() {
                    val batch = updatesLock.withLock {
                        if (updates.isEmpty()) null else ArrayList(updates).also { updates.clear() }
                    }
                    if (batch != null) {
                        db.withTransaction {
                            for ((id, url) in batch) db.songDao().updateCoverUrl(id, url)
                        }
                    }
                }
                val next = AtomicInteger(0)
                val processed = AtomicInteger(0)
                try {
                    coroutineScope {
                        repeat(WORKERS) {
                            launch {
                                val holder = RetrieverHolder()
                                try {
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        yield()
                                        val i = next.getAndIncrement()
                                        if (i >= jobs.size) break
                                        val job = jobs[i]
                                        val url = resolveCover(job, artDir, dirCoverCache, holder)
                                        if (url.isNotBlank()) {
                                            val size = updatesLock.withLock {
                                                updates.add(job.songId to url)
                                                updates.size
                                            }
                                            counters.coversDone.incrementAndGet()
                                            if (size >= BATCH) flushCovers()
                                        }
                                        val p = processed.incrementAndGet()
                                        if (p % 5 == 0 || p == jobs.size) {
                                            update { it.copy(processed = p, coversDone = counters.coversDone.get(), currentName = File(job.path).name) }
                                        }
                                    }
                                } finally {
                                    holder.release()
                                }
                            }
                        }
                    }
                } finally {
                    withContext(NonCancellable) { runCatching { flushCovers() } }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { flush() } }
            throw e
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { flush() } }
            throw e
        }
    }

    private fun formatElapsed(ms: Long): String {
        val sec = (ms / 1000).coerceAtLeast(0)
        return if (sec >= 60) "${sec / 60} 分 ${sec % 60} 秒" else "$sec 秒"
    }

    private fun needsCover(coverUrl: String): Boolean {
        if (coverUrl.isBlank()) return true
        if (coverUrl.startsWith("/")) return !File(coverUrl).exists()
        return false
    }

    /** 查找所有音频文件；被其他目标包含的子目录只扫一次 */
    private suspend fun discover(targets: List<String>, onProgress: (Int, String) -> Unit): List<File> {
        val normalized = targets.map { it.trim().trimEnd('/') }.filter { it.isNotBlank() }.distinct()
        val roots = normalized.filter { t -> normalized.none { o -> o != t && t.startsWith("$o/") } }
        val visitedDirs = HashSet<String>()
        val seenFiles = HashSet<String>()
        val out = ArrayList<File>()

        suspend fun walk(dir: File) {
            currentCoroutineContext().ensureActive()
            val canon = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
            if (!visitedDirs.add(canon)) return
            val children = try { dir.listFiles() } catch (_: Exception) { null } ?: return
            for (f in children) {
                if (f.isDirectory && !f.name.startsWith(".")) {
                    walk(f)
                } else if (f.isFile) {
                    val ext = f.extension.lowercase()
                    if (ext in LocalMediaScanner.AUDIO_EXTENSIONS && f.length() > MIN_FILE_BYTES && seenFiles.add(f.absolutePath)) {
                        out.add(f)
                        if (out.size % 50 == 0) onProgress(out.size, f.name)
                    }
                }
            }
        }

        for (root in roots) {
            val dir = File(root)
            if (dir.exists() && dir.isDirectory) walk(dir)
        }
        onProgress(out.size, "")
        return out
    }

    /** 读取一首歌的文字信息 (与手机端原 scanCustomDirectory 的字段取值一致)，返回实体与封面任务 */
    private fun readMetadata(app: Context, item: WorkItem, holder: RetrieverHolder): Pair<SongEntity, CoverJob?>? {
        val file = item.file
        val path = file.absolutePath
        val ext = file.extension.lowercase()
        var rawTitle: String? = null
        var artist: String? = null
        var album: String? = null
        var durationMs = 0L
        var flacOffset = -2L
        var flacLength = 0

        val flac = if (ext == "flac") FlacTagReader.read(file) else null
        if (flac != null) {
            rawTitle = flac.title?.ifBlank { null }
            artist = flac.artist?.ifBlank { null }
            album = flac.album?.ifBlank { null }
            durationMs = flac.durationMs
            flacOffset = flac.pictureOffset
            flacLength = flac.pictureLength
        } else {
            try {
                val r = holder.get()
                r.setDataSource(path)
                rawTitle = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.ifBlank { null }
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.ifBlank { null }
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.ifBlank { null }
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing file ${file.name}: ${e.message}")
                holder.release() // 解析失败后的解码器状态不可靠，下一首重建
                return null
            }
        }

        val title = rawTitle ?: file.nameWithoutExtension
        val finalArtist = artist ?: "本地音乐"
        val finalAlbum = album ?: "本地文件夹"
        val songId = "local_dir_${LemonMusicProtocol.md5(path)}"

        // 旧版扫描已缓存过的封面 (按歌曲缓存在 cache/album_art)：仍在就直接沿用，不必重新提取
        val legacyCover = File(File(app.cacheDir, "album_art"), "${LemonMusicProtocol.md5(songId)}.jpg")
        val existingCover = when {
            legacyCover.exists() && legacyCover.length() > 0 -> legacyCover.absolutePath
            item.existing != null && !needsCover(item.existing.coverUrl) -> item.existing.coverUrl
            else -> ""
        }

        val entity = SongEntity(
            id = songId,
            title = title,
            artist = finalArtist,
            artistId = "local_artist_${finalArtist.hashCode()}",
            album = finalAlbum,
            albumId = "local_album_${finalAlbum.hashCode()}",
            durationMs = durationMs,
            coverUrl = existingCover,
            streamUrl = path,
            serverId = "local_folder",
            localFilePath = path,
            downloadStatus = DownloadStatus.DOWNLOADED,
            bitRate = 320,
            format = ext.ifBlank { "flac" },
            isFavorite = item.existing?.isFavorite ?: false,
            lastPlayedTimestamp = item.existing?.lastPlayedTimestamp ?: 0L,
            addedTimestamp = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
        )
        // 文件被改过时封面也可能换了：重新提取
        val coverJob = if (existingCover.isBlank() || item.existing != null) {
            CoverJob(songId, path, flacOffset = flacOffset, flacLength = flacLength)
        } else null
        return entity to coverJob
    }

    /** 系统媒体库中目录扫描没覆盖、也未入库的歌曲 (字段取值与手机端原 scanSystemMediaStore 一致) */
    private suspend fun collectMediaStore(
        app: Context,
        knownPaths: Set<String>,
        scannedPaths: Set<String>,
        onEntity: suspend (SongEntity, CoverJob) -> Unit
    ) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 10000"
        val seen = HashSet<String>()
        for (uri in LocalMediaScanner.resolveAudioContentUris(app)) {
            currentCoroutineContext().ensureActive()
            try {
                app.contentResolver.query(uri, projection, selection, null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    while (c.moveToNext()) {
                        val path = c.getString(dataCol) ?: continue
                        if (path.isBlank() || path in knownPaths || path in scannedPaths || !seen.add(path)) continue
                        val file = File(path)
                        if (!file.exists()) continue
                        val title = c.getString(titleCol) ?: "未知歌曲"
                        val artist = c.getString(artistCol) ?: "未知艺术家"
                        val album = c.getString(albumCol) ?: "未知专辑"
                        val albumId = c.getLong(albumIdCol)
                        val songId = "local_media_${c.getLong(idCol)}"
                        val legacyCover = File(File(app.cacheDir, "album_art"), "${LemonMusicProtocol.md5(songId)}.jpg")
                        val entity = SongEntity(
                            id = songId,
                            title = title,
                            artist = if (artist.contains("<unknown>", ignoreCase = true)) "本地艺术家" else artist,
                            artistId = "local_artist_${artist.hashCode()}",
                            album = if (album.contains("<unknown>", ignoreCase = true)) "本地专辑" else album,
                            albumId = "local_album_${album.hashCode()}",
                            durationMs = c.getLong(durationCol),
                            coverUrl = if (legacyCover.exists() && legacyCover.length() > 0) legacyCover.absolutePath else "",
                            streamUrl = path,
                            serverId = "local_storage",
                            localFilePath = path,
                            downloadStatus = DownloadStatus.DOWNLOADED,
                            bitRate = 320,
                            format = file.extension.lowercase().ifBlank { "mp3" },
                            isFavorite = false,
                            addedTimestamp = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                        )
                        onEntity(entity, CoverJob(songId, path, albumIdFallback = albumId))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore query failed for $uri: ${e.message}")
            }
        }
    }

    /**
     * 取得一首歌的封面地址：内嵌封面 (按图片内容去重、保持原图) → 同目录 cover/folder 图片 → 媒体库专辑图。
     */
    private fun resolveCover(
        job: CoverJob,
        artDir: File,
        dirCoverCache: ConcurrentHashMap<String, String>,
        holder: RetrieverHolder
    ): String {
        val file = File(job.path)
        var bytes: ByteArray? = null
        try {
            val isFlac = file.extension.equals("flac", ignoreCase = true)
            var offset = job.flacOffset
            var length = job.flacLength
            var flacParsed = offset != -2L
            if (!flacParsed && isFlac) {
                val info = FlacTagReader.read(file)
                if (info != null) {
                    offset = info.pictureOffset
                    length = info.pictureLength
                    flacParsed = true
                }
            }
            bytes = if (flacParsed) {
                if (offset >= 0) FlacTagReader.readPicture(file, offset, length) else null
            } else {
                try {
                    val r = holder.get()
                    r.setDataSource(job.path)
                    r.embeddedPicture
                } catch (_: Exception) {
                    holder.release()
                    null
                }
            }
        } catch (_: Exception) {}

        if (bytes != null && bytes.isNotEmpty()) {
            saveArtwork(artDir, bytes)?.let { return it }
        }

        // 同目录封面图：每个目录只查一次
        val parent = file.parentFile
        if (parent != null) {
            val dirCover = dirCoverCache.getOrPut(parent.absolutePath) {
                try {
                    parent.listFiles()?.firstOrNull { f ->
                        val name = f.name.lowercase()
                        (name.startsWith("cover") || name.startsWith("folder") || name.startsWith("front") || name.startsWith("album")) &&
                            (name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg") || name.endsWith(".webp"))
                    }?.absolutePath ?: ""
                } catch (_: Exception) {
                    ""
                }
            }
            if (dirCover.isNotBlank()) return dirCover
        }

        if (job.albumIdFallback > 0) {
            return "content://media/external/audio/albumart/${job.albumIdFallback}"
        }
        return ""
    }

    /** 按图片内容 (MD5) 保存：完全相同的图片只存一份，不缩放、不重新压缩 */
    private fun saveArtwork(artDir: File, bytes: ByteArray): String? {
        return try {
            val digest = MessageDigest.getInstance("MD5").digest(bytes)
            val name = digest.joinToString("") { "%02x".format(it) } + ".jpg"
            val out = File(artDir, name)
            if (out.exists() && out.length() == bytes.size.toLong()) return out.absolutePath
            // 先写临时文件再改名：多个线程同时遇到同一张封面时不会读到写了一半的文件
            val tmp = File(artDir, "$name.${Thread.currentThread().id}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(out)) {
                tmp.delete()
                if (!(out.exists() && out.length() == bytes.size.toLong())) return null
            }
            out.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save artwork: ${e.message}")
            null
        }
    }
}
