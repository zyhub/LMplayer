package com.lm.player.core.media

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.room.withTransaction
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.database.entity.DownloadEntity
import com.lm.player.core.database.entity.SongEntity
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.network.LemonMusicProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.withLock
import java.io.File

object LocalMediaScanner {

    /**
     * 解析需要扫描的 MediaStore 音频卷 URI 列表。
     *
     * Android 10 (API 29) 起支持多外部卷：getExternalVolumeNames 会返回
     * "external_primary" 以及 "1234-5678" 这类 SD 卡 / U 盘卷标识。
     * 老系统（API < 29）没有该 API，回退到 EXTERNAL_CONTENT_URI。
     */
    internal fun resolveAudioContentUris(context: Context): List<Uri> {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            return listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        }
        return runCatching {
            MediaStore.getExternalVolumeNames(context)
                .map { volume -> MediaStore.Audio.Media.getContentUri(volume) }
                .ifEmpty { listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI) }
        }.getOrElse { listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI) }
    }

    private const val TAG = "LocalMediaScanner"

    /**
     * 第二十二节：扫描 / 匹配写下载记录时用的「下载完成时间」。
     * 原先一律写当前时间，一次扫描后上千首旧文件都变成"刚刚下载"，挤到下载管理最前面。
     * 改用文件自身的修改时间 (文件落到磁盘的时间)，取不到时才用当前时间。
     */
    internal fun fileTimeOrNow(path: String?): Long {
        if (!path.isNullOrBlank() && !path.startsWith("content://")) {
            val t = try { File(path).lastModified() } catch (_: Exception) { 0L }
            if (t > 0L) return t
        }
        return System.currentTimeMillis()
    }
    internal val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "ape", "dsd")

    /**
     * 1. 一键全盘扫描系统 MediaStore 音频数据库
     */
    suspend fun scanSystemMediaStore(context: Context, database: ZdsDatabase): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.SIZE
            )

            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 10000"
            val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

            // 遍历**全部外部卷**（内置存储 + SD 卡 + U 盘/移动硬盘）：此前只查
            // MediaStore.Audio.Media.EXTERNAL_CONTENT_URI，在 Android 10+ 分区存储下该 URI
            // 只覆盖 primary 卷，电视盒子/车机接的 U 盘与移动硬盘会被整体漏扫。
            for (scanUri in resolveAudioContentUris(context)) {
            context.contentResolver.query(
                scanUri,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                val entities = mutableListOf<SongEntity>()
                val retriever = MediaMetadataRetriever()

                // 长循环中复用同一个 MediaMetadataRetriever 会让 native 侧状态累积；
                // 每处理 200 个文件重建一次，兼顾性能与稳定性。
                var scannedSinceRetrieverReset = 0
                var metadataRetriever = retriever
                try {
                    while (cursor.moveToNext()) {
                        if (scannedSinceRetrieverReset >= 200) {
                            runCatching { metadataRetriever.release() }
                            metadataRetriever = MediaMetadataRetriever()
                            scannedSinceRetrieverReset = 0
                        }
                        scannedSinceRetrieverReset++
                        val id = cursor.getLong(idCol)
                        val title = cursor.getString(titleCol) ?: "未知歌曲"
                        val artist = cursor.getString(artistCol) ?: "未知艺术家"
                        val album = cursor.getString(albumCol) ?: "未知专辑"
                        val albumId = cursor.getLong(albumIdCol)
                        val durationMs = cursor.getLong(durationCol)
                        val path = cursor.getString(dataCol) ?: ""

                        if (path.isNotBlank() && File(path).exists()) {
                            val file = File(path)
                            val ext = file.extension.lowercase().ifBlank { "mp3" }
                            val songId = "local_media_${id}"

                            // 提取并持久化内嵌高清专辑封面 (优先从音频文件 ID3/FLAC tag 中提取，次选 MediaStore)
                            var coverUrl = extractAndCacheArtwork(context, path, songId, metadataRetriever)
                            if (coverUrl.isBlank() && albumId > 0) {
                                val albumArtUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
                                coverUrl = albumArtUri.toString()
                            }

                            entities.add(
                                SongEntity(
                                    id = songId,
                                    title = title,
                                    artist = if (artist.contains("<unknown>", ignoreCase = true)) "本地艺术家" else artist,
                                    artistId = "local_artist_${artist.hashCode()}",
                                    album = if (album.contains("<unknown>", ignoreCase = true)) "本地专辑" else album,
                                    albumId = "local_album_${album.hashCode()}",
                                    durationMs = durationMs,
                                    coverUrl = coverUrl,
                                    streamUrl = path,
                                    serverId = "local_storage",
                                    localFilePath = path,
                                    downloadStatus = DownloadStatus.DOWNLOADED,
                                    bitRate = 320,
                                    format = ext,
                                    isFavorite = false,
                                    addedTimestamp = File(path).lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                                )
                            )
                            count++
                        }
                    }
                } finally {
                    // 释放当前实际使用的实例（循环中可能已重建过多次），确保异常时也能无条件释放
                    runCatching { metadataRetriever.release() }
                }

                if (entities.isNotEmpty()) {
                    database.songDao().insertSongs(entities)
                    matchAndMergeLocalWithServer(database)
                }
            }
            }   // end for (scanUri in resolveAudioContentUris)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan system media store", e)
        }
        count
    }

    /**
     * 2. 递归扫描指定本地文件夹路径中的所有音频文件
     */
    suspend fun scanCustomDirectory(context: Context, folderPath: String, database: ZdsDatabase): Int = withContext(Dispatchers.IO) {
        // 扫描提速 (第二十节)：改走后台扫描引擎的增量模式 —— 只读取新增或改过的文件，
        // FLAC 直接读文件头，3 线程并行，封面按内容去重保存。切换本地模式 / 同步下载目录时
        // 不再每次把整个目录重读一遍。返回本次新读取的歌曲数。
        try {
            val count = LocalScanEngine.scanDirectoryQuietly(context, folderPath)
            if (count > 0) {
                matchAndMergeLocalWithServer(database)
            }
            count
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan custom directory: $folderPath", e)
            0
        }
    }

    /**
     * 发现并按文件夹分组聚合本地音频文件（支持全盘检索或指定目录扫描，不直接写入数据库，返回供预览和勾选）
     */
    suspend fun discoverLocalAudioFilesGrouped(
        context: Context,
        customFolderPath: String? = null
    ): Map<String, List<SongEntity>> = withContext(Dispatchers.IO) {
        val groupedMap = mutableMapOf<String, MutableList<SongEntity>>()
        val retriever = MediaMetadataRetriever()

        try {
            if (!customFolderPath.isNullOrBlank()) {
                val rootDir = File(customFolderPath)
                if (rootDir.exists() && rootDir.isDirectory) {
                    val audioFiles = mutableListOf<File>()
                    fun collectAudio(dir: File) {
                        dir.listFiles()?.forEach { f ->
                            if (f.isDirectory && !f.name.startsWith(".")) {
                                collectAudio(f)
                            } else if (f.isFile && f.extension.lowercase() in AUDIO_EXTENSIONS && f.length() > 50 * 1024) {
                                audioFiles.add(f)
                            }
                        }
                    }
                    collectAudio(rootDir)

                    for (file in audioFiles) {
                        try {
                            retriever.setDataSource(file.absolutePath)
                            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.ifBlank { null } ?: file.nameWithoutExtension
                            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.ifBlank { null } ?: "本地音乐"
                            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.ifBlank { null } ?: file.parentFile?.name.orEmpty().ifBlank { "本地文件夹" }
                            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            val durationMs = durationStr?.toLongOrNull() ?: 0L
                            val ext = file.extension.lowercase().ifBlank { "mp3" }
                            val songId = "local_dir_${LemonMusicProtocol.md5(file.absolutePath)}"
                            val parentDir = file.parentFile?.absolutePath ?: customFolderPath

                            val coverUrl = extractAndCacheArtwork(context, file.absolutePath, songId, retriever)

                            val entity = SongEntity(
                                id = songId,
                                title = title,
                                artist = artist,
                                artistId = "local_artist_${artist.hashCode()}",
                                album = album,
                                albumId = "local_album_${album.hashCode()}",
                                durationMs = durationMs,
                                coverUrl = coverUrl,
                                streamUrl = file.absolutePath,
                                serverId = "local_folder",
                                localFilePath = file.absolutePath,
                                downloadStatus = DownloadStatus.DOWNLOADED,
                                bitRate = 320,
                                format = ext,
                                isFavorite = false,
                                relativeFolderPath = file.parentFile?.name,
                                addedTimestamp = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                            )
                            groupedMap.getOrPut(parentDir) { mutableListOf() }.add(entity)
                        } catch (_: Exception) {}
                    }
                }
            } else {
                // 1. 从 MediaStore 扫描
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.SIZE
                )
                val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 10000"
                val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val title = cursor.getString(titleCol) ?: "未知歌曲"
                        val artist = cursor.getString(artistCol) ?: "未知艺术家"
                        val album = cursor.getString(albumCol) ?: "未知专辑"
                        val albumId = cursor.getLong(albumIdCol)
                        val durationMs = cursor.getLong(durationCol)
                        val path = cursor.getString(dataCol) ?: ""

                        if (path.isNotBlank() && File(path).exists()) {
                            val file = File(path)
                            val ext = file.extension.lowercase().ifBlank { "mp3" }
                            val songId = "local_media_${id}"
                            val parentDir = file.parentFile?.absolutePath ?: "/storage/emulated/0/Music"

                            // 高性能封面：优先使用 MediaStore 系统内置 albumart 协议，避免阻塞式逐文件 I/O 写入
                            val coverUrl = if (albumId > 0) {
                                ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId).toString()
                            } else {
                                extractAndCacheArtwork(context, path, songId, retriever)
                            }

                            val entity = SongEntity(
                                id = songId,
                                title = title,
                                artist = if (artist.contains("<unknown>", ignoreCase = true)) "本地艺术家" else artist,
                                artistId = "local_artist_${artist.hashCode()}",
                                album = if (album.contains("<unknown>", ignoreCase = true)) "本地专辑" else album,
                                albumId = "local_album_${album.hashCode()}",
                                durationMs = durationMs,
                                coverUrl = coverUrl,
                                streamUrl = path,
                                serverId = "local_storage",
                                localFilePath = path,
                                downloadStatus = DownloadStatus.DOWNLOADED,
                                bitRate = 320,
                                format = ext,
                                isFavorite = false,
                                relativeFolderPath = file.parentFile?.name,
                                addedTimestamp = File(path).lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                            )
                            groupedMap.getOrPut(parentDir) { mutableListOf() }.add(entity)
                        }
                    }
                }

                // 2. 深度扫描常见车机内置/SD卡路径
                val candidateDirs = listOf(
                    File("/storage/emulated/0/Music"),
                    File("/storage/emulated/0/Download"),
                    File("/storage/emulated/0/netease/cloudmusic/Music"),
                    File("/storage/emulated/0/qqmusic/song"),
                    File("/storage/emulated/0/kugou/down_c")
                )
                val visitedPaths = groupedMap.values.flatten().mapNotNull { it.localFilePath }.toMutableSet()

                for (cand in candidateDirs) {
                    if (cand.exists() && cand.isDirectory) {
                        cand.listFiles()?.forEach { f ->
                            if (f.isFile && f.extension.lowercase() in AUDIO_EXTENSIONS && f.length() > 50 * 1024) {
                                if (!visitedPaths.contains(f.absolutePath)) {
                                    visitedPaths.add(f.absolutePath)
                                    try {
                                        retriever.setDataSource(f.absolutePath)
                                        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.ifBlank { null } ?: f.nameWithoutExtension
                                        val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.ifBlank { null } ?: "本地音乐"
                                        val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.ifBlank { null } ?: cand.name
                                        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                        val durationMs = durationStr?.toLongOrNull() ?: 0L
                                        val ext = f.extension.lowercase().ifBlank { "mp3" }
                                        val songId = "local_dir_${LemonMusicProtocol.md5(f.absolutePath)}"
                                        val parentDir = f.parentFile?.absolutePath ?: cand.absolutePath

                                        val coverUrl = extractAndCacheArtwork(context, f.absolutePath, songId, retriever)

                                        val entity = SongEntity(
                                            id = songId,
                                            title = title,
                                            artist = artist,
                                            artistId = "local_artist_${artist.hashCode()}",
                                            album = album,
                                            albumId = "local_album_${album.hashCode()}",
                                            durationMs = durationMs,
                                            coverUrl = coverUrl,
                                            streamUrl = f.absolutePath,
                                            serverId = "local_folder",
                                            localFilePath = f.absolutePath,
                                            downloadStatus = DownloadStatus.DOWNLOADED,
                                            bitRate = 320,
                                            format = ext,
                                            isFavorite = false,
                                            relativeFolderPath = f.parentFile?.name,
                                            addedTimestamp = f.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                                        )
                                        groupedMap.getOrPut(parentDir) { mutableListOf() }.add(entity)
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in discoverLocalAudioFilesGrouped", e)
        } finally {
            runCatching { retriever.release() }   // release 必须无条件执行：中途抛异常时原先会跳过，导致 native 解码器与 FD 泄漏
        }
        groupedMap
    }

    /**
     * 将用户勾选或一键添加的本地扫描歌曲保存入库并触发全局匹配与双向关联
     */
    suspend fun saveScannedSongsToDatabase(
        database: ZdsDatabase,
        songs: List<SongEntity>,
        downloadDir: File? = null
    ): Int = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext 0
        try {
            val distinctSongs = songs.distinctBy { it.localFilePath ?: it.id }
            database.songDao().insertSongs(distinctSongs)

            val downloadEntities = distinctSongs.map { s ->
                val f = s.localFilePath?.let { File(it) }
                val fileLength = try { if (f != null && f.exists()) f.length() else 1024L * 1024L } catch (_: Exception) { 1024L * 1024L }
                DownloadEntity(
                    songId = s.id,
                    title = s.title,
                    artist = s.artist,
                    coverUrl = s.coverUrl,
                    localFilePath = s.localFilePath ?: "",
                    remoteUrl = s.streamUrl,
                    status = DownloadStatus.DOWNLOADED,
                    bytesDownloaded = fileLength,
                    totalBytes = fileLength,
                    errorMessage = null,
                    completedTimestamp = fileTimeOrNow(s.localFilePath)
                )
            }
            database.downloadDao().insertDownloads(downloadEntities)

            // 触发与已有在线服务器歌曲的双向哈希匹配（传入 null，确保仅与真正的在线服务器曲目匹配合并）
            SongMatchingResolver.autoMatchAndSyncServer(database, downloadDir, null)
            distinctSongs.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save scanned songs", e)
            0
        }
    }

    /**
     * 3. 扫描 SAF DocumentTree Uri 对应的文件夹目录
     */
    suspend fun scanDocumentTree(context: Context, treeUri: android.net.Uri, database: ZdsDatabase): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
            if (docFile == null || !docFile.isDirectory) return@withContext 0

            val entities = mutableListOf<SongEntity>()
            val retriever = MediaMetadataRetriever()

            fun traverseDoc(dir: androidx.documentfile.provider.DocumentFile) {
                val files = dir.listFiles()
                for (file in files) {
                    if (file.isDirectory && !file.name.orEmpty().startsWith(".")) {
                        traverseDoc(file)
                    } else if (file.isFile && file.length() > 50 * 1024) {
                        val name = file.name.orEmpty()
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext in AUDIO_EXTENSIONS) {
                            try {
                                retriever.setDataSource(context, file.uri)
                                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.ifBlank { null } ?: name.substringBeforeLast('.')
                                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.ifBlank { null } ?: "本地音乐"
                                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.ifBlank { null } ?: (dir.name ?: "本地文件夹")
                                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                val songId = "saf_doc_${LemonMusicProtocol.md5(file.uri.toString())}"

                                // 提取封面字节并缓存为本地文件
                                val pictureBytes = try { retriever.embeddedPicture } catch (_: Exception) { null }
                                var coverUrl = ""
                                if (pictureBytes != null && pictureBytes.isNotEmpty()) {
                                    val artDir = File(context.cacheDir, "album_art").apply { if (!exists()) mkdirs() }
                                    val artFile = File(artDir, "${LemonMusicProtocol.md5(songId)}.jpg")
                                    artFile.writeBytes(pictureBytes)
                                    coverUrl = artFile.absolutePath
                                }

                                entities.add(
                                    SongEntity(
                                        id = songId,
                                        title = title,
                                        artist = artist,
                                        artistId = "saf_artist_${artist.hashCode()}",
                                        album = album,
                                        albumId = "saf_album_${album.hashCode()}",
                                        durationMs = durationMs,
                                        coverUrl = coverUrl,
                                        streamUrl = file.uri.toString(),
                                        serverId = "local_saf",
                                        localFilePath = file.uri.toString(),
                                        downloadStatus = DownloadStatus.DOWNLOADED,
                                        bitRate = 320,
                                        format = ext.ifBlank { "mp3" },
                                        isFavorite = false,
                                        addedTimestamp = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                                    )
                                )
                                count++
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to read metadata for SAF file $name: ${e.message}")
                            }
                        }
                    }
                }
            }

            traverseDoc(docFile)
            runCatching { retriever.release() }   // release 必须无条件执行：中途抛异常时原先会跳过，导致 native 解码器与 FD 泄漏

            if (entities.isNotEmpty()) {
                database.songDao().insertSongs(entities)
                matchAndMergeLocalWithServer(database)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan SAF DocumentTree: $treeUri", e)
        }
        count
    }

    /**
     * 辅助函数：从音频文件中提取内嵌封面图片并持久化至本地 Cache 目录
     */
    private fun extractAndCacheArtwork(
        context: Context,
        path: String,
        songId: String,
        retriever: MediaMetadataRetriever? = null
    ): String {
        try {
            val artDir = File(context.cacheDir, "album_art")
            if (!artDir.exists()) artDir.mkdirs()
            val outFile = File(artDir, "${LemonMusicProtocol.md5(songId)}.jpg")
            if (outFile.exists() && outFile.length() > 0) {
                return outFile.absolutePath
            }

            val localRetriever = retriever ?: MediaMetadataRetriever().apply { setDataSource(path) }
            val pictureBytes = try { localRetriever.embeddedPicture } catch (_: Exception) { null }
            if (retriever == null) {
                try { localRetriever.release() } catch (_: Exception) {}
            }

            if (pictureBytes != null && pictureBytes.isNotEmpty()) {
                outFile.writeBytes(pictureBytes)
                return outFile.absolutePath
            }

            // 若音频无内嵌图，寻找同目录下是否有 cover.jpg / folder.jpg / album.jpg
            val parentDir = File(path).parentFile
            if (parentDir != null && parentDir.exists()) {
                val coverCandidate = parentDir.listFiles()?.firstOrNull { f ->
                    val name = f.name.lowercase()
                    (name.startsWith("cover") || name.startsWith("folder") || name.startsWith("front") || name.startsWith("album")) &&
                            (name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg") || name.endsWith(".webp"))
                }
                if (coverCandidate != null && coverCandidate.exists()) {
                    return coverCandidate.absolutePath
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract artwork for $path: ${e.message}")
        }
        return ""
    }

    /**
     * 核心双向匹配与智能去重算法：
     * 1. 无论是扫描本地歌曲后连接服务器，还是连接服务器后扫描本地歌曲，
     *    均将本地音频文件路径（localFilePath）自动挂载至对应的线上歌曲，并标记 downloadStatus = DOWNLOADED；
     * 2. 安全清理本地产生的冗余 standalone 临时记录（local_media_xxx / local_folder_xxx / local_saf_xxx），
     *    彻底解决曲库中同首歌曲出现 2 倍重复的问题！
     */
    suspend fun matchAndMergeLocalWithServer(
        database: ZdsDatabase,
        providedServerSongs: List<SongEntity>? = null
    ): Int = LibraryMatchLock.mutex.withLock {
        withContext(LocalScanDispatchers.background) {
        var matchCount = 0
        try {
            val allSongs = database.songDao().getAllSongsList()
            val serverSongs = (providedServerSongs ?: allSongs).filter {
                it.serverId != "local_storage" && it.serverId != "local_folder" && it.serverId != "local_saf"
            }.ifEmpty { return@withContext 0 }

            val localSongs = allSongs.filter {
                it.serverId == "local_storage" || it.serverId == "local_folder" || it.serverId == "local_saf"
            }.ifEmpty { return@withContext 0 }

            // 1. 构建服务器歌曲 O(1) 高速哈希索引表 (空间换时间，消除 O(N^2) 耗时)
            val serverExactMap = HashMap<String, SongEntity>(serverSongs.size)
            val serverTitleMap = HashMap<String, MutableList<SongEntity>>()
            // 文件名兜底匹配也走索引：记录每个规范化标题对应的服务器歌曲**下标**，
            // 候选按原列表顺序取第一个，结果与原先逐首遍历全部服务器歌曲完全一致
            val serverTitleIndex = HashMap<String, MutableList<Int>>()
            serverSongs.forEachIndexed { idx, s ->
                val nTitle = normalizeTrackTitle(s.title)
                val nArtist = normalizeArtist(s.artist)
                if (nTitle.isNotBlank()) {
                    if (nArtist.isNotBlank()) {
                        serverExactMap["$nTitle|||$nArtist"] = s
                    }
                    serverTitleMap.getOrPut(nTitle) { mutableListOf() }.add(s)
                    serverTitleIndex.getOrPut(nTitle) { ArrayList(2) }.add(idx)
                }
            }

            val localIdsToDelete = mutableListOf<String>()
            val downloadEntitiesToInsert = mutableListOf<DownloadEntity>()
            // 所有写操作先攒起来，最后在一个事务里一次提交 —— 曲库界面只刷新一次
            val writes = ArrayList<suspend () -> Unit>()

            for ((loopIndex, local) in localSongs.withIndex()) {
                if (loopIndex % 100 == 0) {
                    LocalScanEngine.reportMatchProgress("合并本地歌曲", loopIndex, localSongs.size)
                    yield()
                }
                val localPath = local.localFilePath ?: continue
                val localFile = File(localPath)
                if (!localFile.exists() && !localPath.startsWith("content://")) continue

                val normLocalTitle = normalizeTrackTitle(local.title)
                val normLocalArtist = normalizeArtist(local.artist)

                // 优先 O(1) 精确哈希匹配（含版本与专辑版本冲突校验）
                var matchedServerSong: SongEntity? = null
                if (normLocalTitle.isNotBlank() && normLocalArtist.isNotBlank()) {
                    matchedServerSong = serverExactMap["$normLocalTitle|||$normLocalArtist"]?.takeIf { server ->
                        SongMatchingResolver.isSongMatch(
                            local.title, local.artist, local.durationMs,
                            server.title, server.artist, server.durationMs,
                            local.album, server.album
                        )
                    }
                }

                // 次优 O(1) 标题命中 + 艺术家与版本兼容性校验
                if (matchedServerSong == null && normLocalTitle.isNotBlank()) {
                    val candidates = serverTitleMap[normLocalTitle]
                    if (!candidates.isNullOrEmpty()) {
                        matchedServerSong = candidates.firstOrNull { server ->
                            SongMatchingResolver.isSongMatch(
                                local.title, local.artist, local.durationMs,
                                server.title, server.artist, server.durationMs,
                                local.album, server.album
                            )
                        }
                    }
                }

                // 文件名结构化精确匹配兜底（绝不用包含子串避免把原版识别成 Live 版）
                // matchFileNameToTrack 只可能在文件名推出的几个规范化歌名上命中，只查这些歌名下的服务器歌曲
                if (matchedServerSong == null) {
                    val keys = SongMatchingResolver.fileNameTitleKeys(localFile.nameWithoutExtension)
                    val candidateIdx = if (keys.size == 1) {
                        serverTitleIndex[keys.first()].orEmpty()
                    } else {
                        keys.flatMap { serverTitleIndex[it].orEmpty() }.distinct().sorted()
                    }
                    matchedServerSong = candidateIdx.asSequence().map { serverSongs[it] }.firstOrNull { server ->
                        if (server.id == local.id) return@firstOrNull false
                        val durationCompatible = !(server.durationMs > 0L && local.durationMs > 0L &&
                                kotlin.math.abs(server.durationMs - local.durationMs) > 5000L)
                        durationCompatible && SongMatchingResolver.matchFileNameToTrack(
                            fileNameWithoutExt = localFile.nameWithoutExtension,
                            targetTitle = server.title,
                            targetArtist = server.artist,
                            targetAlbum = server.album
                        )
                    }
                }

                if (matchedServerSong != null) {
                    val matched = matchedServerSong
                    val cover = if (matched.coverUrl.isBlank()) local.coverUrl else matched.coverUrl
                    val isFav = matched.isFavorite || local.isFavorite
                    val fileLength = try { if (localFile.exists()) localFile.length() else 1024L * 1024L } catch (_: Exception) { 1024L * 1024L }

                    val effectiveTimestamp = when {
                        matched.addedTimestamp > 0 -> matched.addedTimestamp
                        local.addedTimestamp > 0 -> local.addedTimestamp
                        else -> System.currentTimeMillis()
                    }

                    val localExt = localFile.extension.lowercase().ifBlank { local.format.ifBlank { matched.format } }
                    val localBitRate = if (localExt in listOf("flac", "wav", "ape", "alac")) {
                        local.bitRate.coerceAtLeast(matched.bitRate).coerceAtLeast(960)
                    } else {
                        if (local.bitRate > 0) local.bitRate else matched.bitRate
                    }

                    writes.add {
                        database.songDao().matchAndLinkLocalFile(
                            songId = matched.id,
                            status = DownloadStatus.DOWNLOADED,
                            localPath = localPath,
                            coverUrl = cover
                        )
                        database.songDao().updateDownloadStatusSpecsAndTimestamp(
                            songId = matched.id,
                            status = DownloadStatus.DOWNLOADED,
                            localPath = localPath,
                            format = localExt,
                            bitRate = localBitRate,
                            timestamp = effectiveTimestamp
                        )
                        if (isFav && !matched.isFavorite) {
                            database.songDao().updateFavorite(matched.id, true)
                        }
                    }

                    downloadEntitiesToInsert.add(
                        DownloadEntity(
                            songId = matched.id,
                            title = matched.title,
                            artist = matched.artist,
                            coverUrl = cover,
                            localFilePath = localPath,
                            remoteUrl = matched.streamUrl,
                            status = DownloadStatus.DOWNLOADED,
                            bytesDownloaded = fileLength,
                            totalBytes = fileLength,
                            errorMessage = null,
                            completedTimestamp = fileTimeOrNow(localPath)
                        )
                    )

                    localIdsToDelete.add(local.id)
                    matchCount++
                }
            }

            if (writes.isNotEmpty() || localIdsToDelete.isNotEmpty() || downloadEntitiesToInsert.isNotEmpty()) {
                database.withTransaction {
                    for (w in writes) w()
                    if (localIdsToDelete.isNotEmpty()) {
                        database.songDao().deleteSongsByIds(localIdsToDelete)
                        for (delId in localIdsToDelete) {
                            database.downloadDao().deleteDownload(delId)
                        }
                    }
                    if (downloadEntitiesToInsert.isNotEmpty()) {
                        for (entity in downloadEntitiesToInsert) {
                            entity.localFilePath?.takeIf { it.isNotBlank() }?.let { path ->
                                database.downloadDao().deleteDownloadByLocalPath(path)
                            }
                        }
                        downloadEntitiesToInsert.chunked(500).forEach { chunk ->
                            database.downloadDao().insertDownloads(chunk)
                        }
                    }
                }
                if (localIdsToDelete.isNotEmpty()) {
                    Log.i(TAG, "Cleaned up ${localIdsToDelete.size} redundant duplicate local song records from database.")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in matchAndMergeLocalWithServer", e)
            matchCount = 0
        } finally {
            LocalScanEngine.reportMatchProgress(null, 0, 0)
        }
        matchCount
        }
    }

    /**
     * 全面核对线上歌曲与线下实际物理文件：
     * 1. 检验所有已被标记为已下载的在线歌曲，若其 localFilePath 对应的物理文件不存在或长度为0，强制重置为 NOT_DOWNLOADED 并清空 localFilePath；
     * 2. 对所有未标记已下载的在线歌曲，与当前离线存储目录及本地扫描库比对；
     * 3. 若匹配成功则挂载 localFilePath 并更新为 DOWNLOADED；未匹配上的在线模式歌曲严格保持 NOT_DOWNLOADED（绝不误显已下载）。
     *
     * 扫描提速：下载目录文件、本地歌曲都按规范化歌名 / 路径建索引 (结果与逐首比对一致)；
     * 此前每首服务器歌曲都要对全部本地歌曲各做一次磁盘 stat，大曲库下是主要耗时。
     */
    suspend fun verifyAndSyncAllServerSongDownloadStatus(
        database: ZdsDatabase,
        downloadDir: File? = null
    ): Int = LibraryMatchLock.mutex.withLock {
        withContext(LocalScanDispatchers.background) {
        var updatedCount = 0
        try {
            val allSongs = database.songDao().getAllSongsList()
            val serverSongs = allSongs.filter {
                it.serverId != "local_storage" && it.serverId != "local_folder" && it.serverId != "local_saf"
            }
            if (serverSongs.isEmpty()) return@withContext 0

            val localSongs = allSongs.filter {
                it.serverId == "local_storage" || it.serverId == "local_folder" || it.serverId == "local_saf"
            }

            // 收集离线下载目录内的物理文件
            val localFiles = mutableListOf<File>()
            if (downloadDir != null && downloadDir.exists() && downloadDir.isDirectory) {
                fun collectFiles(dir: File) {
                    dir.listFiles()?.forEach { f ->
                        if (f.isDirectory && !f.name.startsWith(".")) {
                            collectFiles(f)
                        } else if (f.isFile && f.extension.lowercase() in AUDIO_EXTENSIONS && f.length() > 50 * 1024) {
                            localFiles.add(f)
                        }
                    }
                }
                collectFiles(downloadDir)
            }

            val downloadRecords = database.downloadDao().getAllDownloadsList().associateBy { it.songId }

            fun resolveLocalSpecs(path: String, fallbackFormat: String, fallbackBitRate: Int, durationMs: Long): Pair<String, Int> {
                val f = File(path)
                val ext = f.extension.lowercase().ifBlank { fallbackFormat.ifBlank { "mp3" } }
                val durSec = durationMs / 1000L
                val br = if (f.exists() && f.length() > 0 && durSec in 15..3600) {
                    ((f.length() * 8L) / (durSec * 1000L)).toInt().coerceIn(64, 4608)
                } else if (ext in listOf("flac", "wav", "ape", "alac")) {
                    fallbackBitRate.coerceAtLeast(960)
                } else {
                    if (fallbackBitRate > 0) fallbackBitRate else 320
                }
                return ext to br
            }

            // 同一次核对内同一条路径只 stat 一次
            val existsMemo = HashMap<String, Boolean>(256)
            fun existsMemoized(path: String): Boolean = existsMemo.getOrPut(path) {
                try { File(path).exists() } catch (_: Exception) { false }
            }

            // 索引：路径 → 本地歌曲 (清理重复记录用)；规范化歌名 → 本地歌曲 / 下载目录文件 (候选查找用)
            val localByPath = HashMap<String, MutableList<SongEntity>>()
            val localByTitle = HashMap<String, MutableList<Int>>()
            localSongs.forEachIndexed { idx, s ->
                s.localFilePath?.let { p -> localByPath.getOrPut(p) { ArrayList(1) }.add(s) }
                val k = normalizeTrackTitle(s.title)
                if (k.isNotBlank()) localByTitle.getOrPut(k) { ArrayList(1) }.add(idx)
            }
            val fileIndex = HashMap<String, MutableList<Int>>()
            localFiles.forEachIndexed { idx, f ->
                val keys = SongMatchingResolver.fileNameTitleKeys(f.nameWithoutExtension).toMutableSet()
                keys.add(normalizeTrackTitle(f.nameWithoutExtension))
                for (k in keys) if (k.isNotBlank()) fileIndex.getOrPut(k) { ArrayList(1) }.add(idx)
            }

            database.withTransaction {
                for ((loopIndex, server) in serverSongs.withIndex()) {
                    if (loopIndex % 200 == 0) {
                        LocalScanEngine.reportMatchProgress("核对下载状态", loopIndex, serverSongs.size)
                        yield()
                    }
                    val downloadRec = downloadRecords[server.id]
                    val currentPath = server.localFilePath
                    val isCurrentFileValid = !currentPath.isNullOrBlank() && File(currentPath).let { it.exists() && it.length() > 0 }

                    if (isCurrentFileValid && !currentPath.isNullOrBlank()) {
                        val finalTs = if (server.addedTimestamp > 0) server.addedTimestamp else (downloadRec?.completedTimestamp ?: System.currentTimeMillis())
                        val (realFmt, realBr) = resolveLocalSpecs(currentPath, server.format, server.bitRate, server.durationMs)
                        val needsUpdate = server.downloadStatus != DownloadStatus.DOWNLOADED || server.localFilePath != currentPath || server.format != realFmt || server.bitRate != realBr
                        if (needsUpdate) {
                            database.songDao().updateDownloadStatusSpecsAndTimestamp(server.id, DownloadStatus.DOWNLOADED, currentPath, realFmt, realBr, finalTs)
                            updatedCount++
                        }
                        // 清理指向同一物理文件的冗余独立本地记录，避免已下载列表重复
                        localByPath[currentPath].orEmpty().filter { it.id != server.id }.forEach { dup ->
                            database.songDao().deleteSongById(dup.id)
                            database.downloadDao().deleteDownload(dup.id)
                        }
                        continue
                    }

                    val recPath = downloadRec?.localFilePath
                    val isDownloadRecValid = !recPath.isNullOrBlank() && File(recPath).let { it.exists() && it.length() > 0 }
                    if (isDownloadRecValid && !recPath.isNullOrBlank()) {
                        val finalTs = if (server.addedTimestamp > 0) server.addedTimestamp else (downloadRec?.completedTimestamp ?: System.currentTimeMillis())
                        val (realFmt, realBr) = resolveLocalSpecs(recPath, server.format, server.bitRate, server.durationMs)
                        val needsUpdate = server.downloadStatus != DownloadStatus.DOWNLOADED || server.localFilePath != recPath || server.format != realFmt || server.bitRate != realBr
                        if (needsUpdate) {
                            database.songDao().updateDownloadStatusSpecsAndTimestamp(server.id, DownloadStatus.DOWNLOADED, recPath, realFmt, realBr, finalTs)
                            updatedCount++
                        }
                        localByPath[recPath].orEmpty().filter { it.id != server.id }.forEach { dup ->
                            database.songDao().deleteSongById(dup.id)
                            database.downloadDao().deleteDownload(dup.id)
                        }
                        continue
                    }

                    // 若之前记录的路径已失效或尚未关联，尝试在本地文件与离线目录中精确搜索匹配（严格区分 Live/不同版本）
                    val normServerTitle = normalizeTrackTitle(server.title)
                    val normServerArtist = normalizeArtist(server.artist)
                    val normServerAlbum = normalizeArtist(server.album)

                    // 1. 优先在离线下载目录中按路径层级与文件名精确匹配 (两种命中方式都要求歌名相同，只查索引候选)
                    val fileCandidates = if (normServerTitle.isBlank()) emptyList() else fileIndex[normServerTitle].orEmpty()
                    val matchedPhysicalFile = fileCandidates.asSequence().map { localFiles[it] }.firstOrNull { f ->
                        if (SongMatchingResolver.matchFileNameToTrack(f.nameWithoutExtension, server.title, server.artist, server.album)) {
                            return@firstOrNull true
                        }
                        val fName = normalizeTrackTitle(f.nameWithoutExtension)
                        val pDir = f.parentFile?.name.orEmpty().lowercase()
                        val pParentDir = f.parentFile?.parentFile?.name.orEmpty().lowercase()

                        val titleExactMatch = normServerTitle.isNotBlank() && fName == normServerTitle &&
                                !SongMatchingResolver.hasEditionConflict(f.nameWithoutExtension, pDir, server.title, server.album)
                        val dirMatches = (normServerArtist.isNotBlank() && (pDir.contains(normServerArtist) || pParentDir.contains(normServerArtist))) ||
                                (normServerAlbum.isNotBlank() && (pDir.contains(normServerAlbum) || pParentDir.contains(normServerAlbum)))

                        titleExactMatch && (normServerArtist.isBlank() || dirMatches)
                    }

                    if (matchedPhysicalFile != null) {
                        val filePath = matchedPhysicalFile.absolutePath
                        val finalTs = if (server.addedTimestamp > 0) server.addedTimestamp else System.currentTimeMillis()
                        val (realFmt, realBr) = resolveLocalSpecs(filePath, server.format, server.bitRate, server.durationMs)
                        database.songDao().updateDownloadStatusSpecsAndTimestamp(server.id, DownloadStatus.DOWNLOADED, filePath, realFmt, realBr, finalTs)
                        localByPath[filePath].orEmpty().filter { it.id != server.id }.forEach { dup ->
                            database.songDao().deleteSongById(dup.id)
                            database.downloadDao().deleteDownload(dup.id)
                        }
                        database.downloadDao().deleteDownloadByLocalPath(filePath)
                        database.downloadDao().insertOrUpdate(
                            DownloadEntity(
                                songId = server.id,
                                title = server.title,
                                artist = server.artist,
                                coverUrl = server.coverUrl,
                                localFilePath = filePath,
                                remoteUrl = server.streamUrl,
                                status = DownloadStatus.DOWNLOADED,
                                bytesDownloaded = matchedPhysicalFile.length(),
                                totalBytes = matchedPhysicalFile.length(),
                                completedTimestamp = fileTimeOrNow(filePath)
                            )
                        )
                        updatedCount++
                        continue
                    }

                    // 2. 在独立扫描的本地歌曲中比对 (isSongMatch 要求规范化歌名相同，只查同名候选)
                    val localCandidates = if (normServerTitle.isBlank()) emptyList() else localByTitle[normServerTitle].orEmpty()
                    val matchedLocalSong = localCandidates.asSequence().map { localSongs[it] }.firstOrNull { local ->
                        val lPath = local.localFilePath ?: return@firstOrNull false
                        if (!existsMemoized(lPath)) return@firstOrNull false
                        SongMatchingResolver.isSongMatch(
                            local.title, local.artist, local.durationMs,
                            server.title, server.artist, server.durationMs,
                            local.album, server.album
                        )
                    }

                    if (matchedLocalSong != null && matchedLocalSong.localFilePath != null) {
                        val lFile = File(matchedLocalSong.localFilePath)
                        val finalTs = if (server.addedTimestamp > 0) server.addedTimestamp else if (matchedLocalSong.addedTimestamp > 0) matchedLocalSong.addedTimestamp else System.currentTimeMillis()
                        val (realFmt, realBr) = resolveLocalSpecs(matchedLocalSong.localFilePath, matchedLocalSong.format, matchedLocalSong.bitRate, server.durationMs)
                        database.songDao().updateDownloadStatusSpecsAndTimestamp(server.id, DownloadStatus.DOWNLOADED, matchedLocalSong.localFilePath, realFmt, realBr, finalTs)
                        if (matchedLocalSong.id != server.id) {
                            database.songDao().deleteSongById(matchedLocalSong.id)
                            database.downloadDao().deleteDownload(matchedLocalSong.id)
                        }
                        database.downloadDao().deleteDownloadByLocalPath(matchedLocalSong.localFilePath)
                        database.downloadDao().insertOrUpdate(
                            DownloadEntity(
                                songId = server.id,
                                title = server.title,
                                artist = server.artist,
                                coverUrl = server.coverUrl,
                                localFilePath = matchedLocalSong.localFilePath,
                                remoteUrl = server.streamUrl,
                                status = DownloadStatus.DOWNLOADED,
                                bytesDownloaded = lFile.length(),
                                totalBytes = lFile.length(),
                                completedTimestamp = fileTimeOrNow(matchedLocalSong.localFilePath)
                            )
                        )
                        updatedCount++
                    } else {
                        // 3. 严格核对：本地没有任何物理文件对应此在线歌曲，强制设为未下载！
                        if (server.downloadStatus == DownloadStatus.DOWNLOADED || !server.localFilePath.isNullOrBlank()) {
                            database.songDao().updateDownloadStatus(server.id, DownloadStatus.NOT_DOWNLOADED, null)
                            database.downloadDao().deleteDownload(server.id)
                            updatedCount++
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in verifyAndSyncAllServerSongDownloadStatus", e)
        } finally {
            LocalScanEngine.reportMatchProgress(null, 0, 0)
        }
        updatedCount
        }
    }

    private fun normalizeTrackTitle(title: String): String = SongMatchingResolver.normalizeTrackTitle(title)

    private fun normalizeArtist(artist: String): String = SongMatchingResolver.normalizeArtist(artist)

    /**
     * 删除本地音频文件时同步更新 Room 数据库（解除线上曲目下载关联或删除纯本地曲目）
     */
    suspend fun deleteLocalAudioFile(database: ZdsDatabase, filePath: String) = withContext(Dispatchers.IO) {
        try {
            val matched = database.songDao().getSongByLocalPath(filePath)
            if (matched != null) {
                if (matched.serverId in listOf("local_storage", "local_folder", "local_saf") || matched.id.startsWith("local_")) {
                    database.songDao().deleteSongById(matched.id)
                } else {
                    database.songDao().updateDownloadStatus(matched.id, DownloadStatus.NOT_DOWNLOADED, null)
                }
                database.downloadDao().deleteDownload(matched.id)
            }
            database.downloadDao().deleteDownloadByLocalPath(filePath)
            database.songDao().deleteSongByLocalPath(filePath)
        } catch (e: Exception) {
            Log.e(TAG, "Error in deleteLocalAudioFile for $filePath", e)
        }
    }

    /**
     * 清理资料库中的遗留/无效数据
     * 1. 清理本地物理文件已丢失的纯本地歌曲记录；
     * 2. 清除失效服务器残留的未下载歌曲；
     * 3. 清理残留的发现/推荐临时歌单；
     * 4. 校验下载物理文件是否存在。
     */
    suspend fun purgeLegacyResidualData(database: ZdsDatabase, validServerIds: List<String> = emptyList()): Int = withContext(Dispatchers.IO) {
        var purgedCount = 0
        try {
            // 清除残留的发现推荐歌单
            database.playlistDao().clearDiscoverPlaylists()

            // 清除不存在服务器的未下载孤立歌曲
            if (validServerIds.isNotEmpty()) {
                database.songDao().deleteOrphanSongs(validServerIds)
            }

            // 校验本地歌曲的实际物理文件是否存在
            val allSongs = database.songDao().getAllSongsList()
            val toDeleteIds = mutableListOf<String>()

            for (song in allSongs) {
                val isLocalOnly = song.serverId in listOf("local_storage", "local_folder", "local_saf") || song.id.startsWith("local_")
                val path = song.localFilePath
                val fileExists = !path.isNullOrBlank() && try { File(path).exists() } catch (_: Exception) { false }

                if (isLocalOnly && !fileExists) {
                    // 纯本地歌曲且文件已物理删除 -> 移除遗留孤立条目
                    toDeleteIds.add(song.id)
                } else if (!isLocalOnly && song.downloadStatus == DownloadStatus.DOWNLOADED && !fileExists) {
                    // 线上曲目标记已下载但物理文件丢失 -> 还原为未下载
                    database.songDao().updateDownloadStatus(song.id, DownloadStatus.NOT_DOWNLOADED, null)
                    database.downloadDao().deleteDownload(song.id)
                    purgedCount++
                }
            }

            // 5. 清理指向同一物理本地路径的重复歌曲记录与下载记录（优先保留已关联服务器的曲目记录）
            val remainingSongs = allSongs.filter { it.id !in toDeleteIds && !it.localFilePath.isNullOrBlank() }
            val byLocalPath = remainingSongs.groupBy { it.localFilePath!!.trim().replace('\\', '/').lowercase() }
            for ((_, group) in byLocalPath) {
                if (group.size > 1) {
                    val canonical = group.firstOrNull {
                        it.serverId.isNotBlank() && it.serverId !in listOf("local_storage", "local_folder", "local_saf", "lemon_online")
                    } ?: group.first()
                    for (dup in group) {
                        if (dup.id != canonical.id) {
                            toDeleteIds.add(dup.id)
                            database.downloadDao().deleteDownload(dup.id)
                        }
                    }
                }
            }

            if (toDeleteIds.isNotEmpty()) {
                database.songDao().deleteSongsByIds(toDeleteIds)
                purgedCount += toDeleteIds.size
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in purgeLegacyResidualData", e)
        }
        purgedCount
    }
}
