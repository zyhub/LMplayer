package com.lm.player.core.media

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.database.entity.DownloadEntity
import com.lm.player.core.database.entity.SongEntity
import com.lm.player.core.model.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object LocalMediaScanner {

    private const val TAG = "LocalMediaScanner"
    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "ape", "dsd")

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

                val entities = mutableListOf<SongEntity>()
                val retriever = MediaMetadataRetriever()

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

                        // 提取并持久化内嵌高清专辑封面 (优先从音频文件 ID3/FLAC tag 中提取，次选 MediaStore)
                        var coverUrl = extractAndCacheArtwork(context, path, songId, retriever)
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
                                isFavorite = false
                            )
                        )
                        count++
                    }
                }
                try { retriever.release() } catch (_: Exception) {}

                if (entities.isNotEmpty()) {
                    database.songDao().insertSongs(entities)
                    matchAndMergeLocalWithServer(database)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan system media store", e)
        }
        count
    }

    /**
     * 2. 递归扫描指定本地文件夹路径中的所有音频文件
     */
    suspend fun scanCustomDirectory(context: Context, folderPath: String, database: ZdsDatabase): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            val rootDir = File(folderPath)
            if (!rootDir.exists() || !rootDir.isDirectory) return@withContext 0

            val audioFiles = mutableListOf<File>()
            fun collectAudioFiles(dir: File) {
                val files = dir.listFiles() ?: return
                for (file in files) {
                    if (file.isDirectory && !file.name.startsWith(".")) {
                        collectAudioFiles(file)
                    } else if (file.isFile) {
                        val ext = file.extension.lowercase()
                        if (ext in AUDIO_EXTENSIONS && file.length() > 50 * 1024) { // 过滤小于50KB短音效
                            audioFiles.add(file)
                        }
                    }
                }
            }

            collectAudioFiles(rootDir)
            if (audioFiles.isEmpty()) return@withContext 0

            val retriever = MediaMetadataRetriever()
            val entities = mutableListOf<SongEntity>()

            for (file in audioFiles) {
                try {
                    retriever.setDataSource(file.absolutePath)
                    val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.ifBlank { null } ?: file.nameWithoutExtension
                    val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.ifBlank { null } ?: "本地音乐"
                    val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.ifBlank { null } ?: "本地文件夹"
                    val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val durationMs = durationStr?.toLongOrNull() ?: 0L
                    val ext = file.extension.lowercase().ifBlank { "flac" }
                    val songId = "local_dir_${file.absolutePath.hashCode()}"

                    // 提取并保存内嵌专辑封面
                    val coverUrl = extractAndCacheArtwork(context, file.absolutePath, songId, retriever)

                    entities.add(
                        SongEntity(
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
                            isFavorite = false
                        )
                    )
                    count++
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing file ${file.name}: ${e.message}")
                }
            }
            try { retriever.release() } catch (_: Exception) {}

            if (entities.isNotEmpty()) {
                database.songDao().insertSongs(entities)
                matchAndMergeLocalWithServer(database)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan custom directory: $folderPath", e)
        }
        count
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
                            val songId = "local_dir_${file.absolutePath.hashCode()}"
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
                                relativeFolderPath = file.parentFile?.name
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
                                relativeFolderPath = file.parentFile?.name
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
                                        val songId = "local_dir_${f.absolutePath.hashCode()}"
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
                                            relativeFolderPath = f.parentFile?.name
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
            try { retriever.release() } catch (_: Exception) {}
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
                    completedTimestamp = System.currentTimeMillis()
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
                                val songId = "saf_doc_${file.uri.toString().hashCode()}"

                                // 提取封面字节并缓存为本地文件
                                val pictureBytes = try { retriever.embeddedPicture } catch (_: Exception) { null }
                                var coverUrl = ""
                                if (pictureBytes != null && pictureBytes.isNotEmpty()) {
                                    val artDir = File(context.cacheDir, "album_art").apply { if (!exists()) mkdirs() }
                                    val artFile = File(artDir, "${songId.hashCode()}.jpg")
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
                                        isFavorite = false
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
            try { retriever.release() } catch (_: Exception) {}

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
            val outFile = File(artDir, "${songId.hashCode()}.jpg")
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
    ): Int = withContext(Dispatchers.IO) {
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
            for (s in serverSongs) {
                val nTitle = normalizeTrackTitle(s.title)
                val nArtist = normalizeArtist(s.artist)
                if (nTitle.isNotBlank()) {
                    if (nArtist.isNotBlank()) {
                        serverExactMap["$nTitle|||$nArtist"] = s
                    }
                    serverTitleMap.getOrPut(nTitle) { mutableListOf() }.add(s)
                }
            }

            val localIdsToDelete = mutableListOf<String>()
            val downloadEntitiesToInsert = mutableListOf<DownloadEntity>()

            for (local in localSongs) {
                val localPath = local.localFilePath ?: continue
                val localFile = File(localPath)
                if (!localFile.exists() && !localPath.startsWith("content://")) continue

                val normLocalTitle = normalizeTrackTitle(local.title)
                val normLocalArtist = normalizeArtist(local.artist)
                val localFileName = localFile.nameWithoutExtension.lowercase()

                // 优先 O(1) 精确哈希匹配
                var matchedServerSong: SongEntity? = null
                if (normLocalTitle.isNotBlank() && normLocalArtist.isNotBlank()) {
                    matchedServerSong = serverExactMap["$normLocalTitle|||$normLocalArtist"]
                }

                // 次优 O(1) 标题命中 + 艺术家兼容性校验
                if (matchedServerSong == null && normLocalTitle.isNotBlank()) {
                    val candidates = serverTitleMap[normLocalTitle]
                    if (!candidates.isNullOrEmpty()) {
                        matchedServerSong = candidates.firstOrNull { server ->
                            val normServerArtist = normalizeArtist(server.artist)
                            normServerArtist.isBlank() || normLocalArtist.isBlank() ||
                                    normServerArtist == normLocalArtist ||
                                    normServerArtist.contains(normLocalArtist) ||
                                    normLocalArtist.contains(normServerArtist)
                        }
                    }
                }

                // 文件名包含匹配与模糊时长降级兜底
                if (matchedServerSong == null) {
                    matchedServerSong = serverSongs.firstOrNull { server ->
                        if (server.id == local.id) return@firstOrNull false
                        val normServerTitle = normalizeTrackTitle(server.title)
                        val normServerArtist = normalizeArtist(server.artist)
                        if (normServerTitle.isBlank()) return@firstOrNull false

                        // 本地文件名包含服务器标题与艺术家
                        val fileNameMatch = localFileName.contains(normServerTitle) && normServerTitle.length >= 2 &&
                                (normServerArtist.isBlank() || localFileName.contains(normServerArtist))
                        if (fileNameMatch) return@firstOrNull true

                        // 标题模糊包含且时长在 3.5 秒误差范围内
                        val durationMatch = server.durationMs > 0 && local.durationMs > 0 &&
                                kotlin.math.abs(server.durationMs - local.durationMs) < 3500
                        val titleFuzzyMatch = (normServerTitle.contains(normLocalTitle) || normLocalTitle.contains(normServerTitle)) &&
                                normServerTitle.length >= 2 && normLocalTitle.length >= 2

                        val artistMatch = normServerArtist.isBlank() || normLocalArtist.isBlank() ||
                                normServerArtist == normLocalArtist ||
                                normServerArtist.contains(normLocalArtist) ||
                                normLocalArtist.contains(normServerArtist)

                        titleFuzzyMatch && durationMatch && artistMatch
                    }
                }

                if (matchedServerSong != null) {
                    val cover = if (matchedServerSong.coverUrl.isBlank()) local.coverUrl else matchedServerSong.coverUrl
                    val isFav = matchedServerSong.isFavorite || local.isFavorite
                    val fileLength = try { if (localFile.exists()) localFile.length() else 1024L * 1024L } catch (_: Exception) { 1024L * 1024L }

                    database.songDao().matchAndLinkLocalFile(
                        songId = matchedServerSong.id,
                        status = DownloadStatus.DOWNLOADED,
                        localPath = localPath,
                        coverUrl = cover
                    )
                    if (isFav && !matchedServerSong.isFavorite) {
                        database.songDao().updateFavorite(matchedServerSong.id, true)
                    }

                    downloadEntitiesToInsert.add(
                        DownloadEntity(
                            songId = matchedServerSong.id,
                            title = matchedServerSong.title,
                            artist = matchedServerSong.artist,
                            coverUrl = cover,
                            localFilePath = localPath,
                            remoteUrl = matchedServerSong.streamUrl,
                            status = DownloadStatus.DOWNLOADED,
                            bytesDownloaded = fileLength,
                            totalBytes = fileLength,
                            errorMessage = null,
                            completedTimestamp = System.currentTimeMillis()
                        )
                    )

                    localIdsToDelete.add(local.id)
                    matchCount++
                }
            }

            if (downloadEntitiesToInsert.isNotEmpty()) {
                downloadEntitiesToInsert.chunked(500).forEach { chunk ->
                    database.downloadDao().insertDownloads(chunk)
                }
            }

            if (localIdsToDelete.isNotEmpty()) {
                localIdsToDelete.chunked(500).forEach { chunk ->
                    database.songDao().deleteSongsByIds(chunk)
                }
                Log.i(TAG, "Cleaned up ${localIdsToDelete.size} redundant duplicate local song records from database.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in matchAndMergeLocalWithServer", e)
        }
        matchCount
    }

    /**
     * 全面核对线上歌曲与线下实际物理文件：
     * 1. 检验所有已被标记为已下载的在线歌曲，若其 localFilePath 对应的物理文件不存在或长度为0，强制重置为 NOT_DOWNLOADED 并清空 localFilePath；
     * 2. 对所有未标记已下载的在线歌曲，与当前离线存储目录及本地扫描库比对；
     * 3. 若匹配成功则挂载 localFilePath 并更新为 DOWNLOADED；未匹配上的在线模式歌曲严格保持 NOT_DOWNLOADED（绝不误显已下载）。
     */
    suspend fun verifyAndSyncAllServerSongDownloadStatus(
        database: ZdsDatabase,
        downloadDir: File? = null
    ): Int = withContext(Dispatchers.IO) {
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

            for (server in serverSongs) {
                val currentPath = server.localFilePath
                val isCurrentFileValid = !currentPath.isNullOrBlank() && File(currentPath).let { it.exists() && it.length() > 0 }

                if (isCurrentFileValid) {
                    if (server.downloadStatus != DownloadStatus.DOWNLOADED) {
                        database.songDao().updateDownloadStatus(server.id, DownloadStatus.DOWNLOADED, currentPath)
                        updatedCount++
                    }
                    continue
                }

                val downloadRec = downloadRecords[server.id]
                val recPath = downloadRec?.localFilePath
                val isDownloadRecValid = !recPath.isNullOrBlank() && File(recPath).let { it.exists() && it.length() > 0 }
                if (isDownloadRecValid && !recPath.isNullOrBlank()) {
                    database.songDao().updateDownloadStatus(server.id, DownloadStatus.DOWNLOADED, recPath)
                    updatedCount++
                    continue
                }

                // 若之前记录的路径已失效或尚未关联，尝试在本地文件与离线目录中精确搜索匹配
                val normServerTitle = normalizeTrackTitle(server.title)
                val normServerArtist = normalizeArtist(server.artist)
                val normServerAlbum = normalizeArtist(server.album)

                // 1. 优先在离线下载目录中按路径层级与文件名匹配
                val matchedPhysicalFile = localFiles.firstOrNull { f ->
                    val fName = normalizeTrackTitle(f.nameWithoutExtension)
                    val pDir = f.parentFile?.name.orEmpty().lowercase()
                    val pParentDir = f.parentFile?.parentFile?.name.orEmpty().lowercase()

                    val titleMatches = fName == normServerTitle || fName.contains(normServerTitle) || normServerTitle.contains(fName)
                    val dirMatches = pDir.contains(normServerArtist) || pDir.contains(normServerAlbum) ||
                            pParentDir.contains(normServerArtist) || pParentDir.contains(normServerAlbum)

                    (titleMatches && (normServerArtist.isBlank() || dirMatches || fName.contains(normServerArtist)))
                }

                if (matchedPhysicalFile != null) {
                    val filePath = matchedPhysicalFile.absolutePath
                    database.songDao().updateDownloadStatus(server.id, DownloadStatus.DOWNLOADED, filePath)
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
                            completedTimestamp = System.currentTimeMillis()
                        )
                    )
                    updatedCount++
                    continue
                }

                // 2. 在独立扫描的本地歌曲中比对
                val matchedLocalSong = localSongs.firstOrNull { local ->
                    val lPath = local.localFilePath ?: return@firstOrNull false
                    if (!File(lPath).exists()) return@firstOrNull false

                    val normLocalTitle = normalizeTrackTitle(local.title)
                    val normLocalArtist = normalizeArtist(local.artist)

                    val titleStrictMatch = normServerTitle.isNotBlank() && normLocalTitle.isNotBlank() && normServerTitle == normLocalTitle
                    val artistMatch = normServerArtist.isBlank() || normLocalArtist.isBlank() ||
                            normServerArtist == normLocalArtist ||
                            normServerArtist.contains(normLocalArtist) ||
                            normLocalArtist.contains(normServerArtist)

                    titleStrictMatch && artistMatch
                }

                if (matchedLocalSong != null && matchedLocalSong.localFilePath != null) {
                    val lFile = File(matchedLocalSong.localFilePath)
                    database.songDao().updateDownloadStatus(server.id, DownloadStatus.DOWNLOADED, matchedLocalSong.localFilePath)
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
                            completedTimestamp = System.currentTimeMillis()
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
        } catch (e: Exception) {
            Log.e(TAG, "Error in verifyAndSyncAllServerSongDownloadStatus", e)
        }
        updatedCount
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
}
