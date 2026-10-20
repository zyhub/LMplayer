package com.lm.player.core.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.database.dao.DownloadDao
import com.lm.player.core.model.DownloadStatus
import com.lm.player.core.model.ServerType
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.network.LemonMusicProtocol
import com.lm.player.core.network.NetworkClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PlaybackRouter(
    private val downloadDao: DownloadDao,
    private val context: Context
) {
    private companion object {
        /** 播放链路单次本地加速预算；超过即回退服务器，避免点歌后卡 10~25 秒。 */
        const val PLAYBACK_ACCEL_BUDGET_MS = 2_200L
    }

    private val TAG = "PlaybackRouter"

    /** 构造 LX 音源最小 musicInfo；空 hash/songmid 必须删除，否则脚本会优先取到空串。 */
    private fun buildAccelerationInfo(song: UnifiedSong): String {
        val obj = runCatching { org.json.JSONObject(song.rawMetaJson ?: "{}") }.getOrDefault(org.json.JSONObject())
        val clean = song.id.removePrefix("lemon_online_")
        val rawId = if (clean.contains("_")) clean.substringAfter("_") else clean
        if (rawId.isNotBlank()) {
            if (obj.optString("id").isBlank()) obj.put("id", rawId)
            if (obj.optString("songId").isBlank()) obj.put("songId", rawId)
        }
        if (obj.optString("name").isBlank()) obj.put("name", song.title)
        if (obj.optString("singer").isBlank()) obj.put("singer", song.artist)
        if (obj.optString("albumName").isBlank()) obj.put("albumName", song.album)
        listOf("hash", "songmid").forEach { key ->
            if (obj.has(key) && obj.optString(key).isBlank()) obj.remove(key)
        }
        return obj.toString()
    }

    /**
     * 核心路由决策机制：
     * 1. 优先检测本地已下载文件 (Room 下载库、曲目本地路径、默认存储目录)。
     * 2. 若未直接记录本地路径，通过歌曲标题与艺术家进行全局本地离线库模糊匹配。
     * 3. 存在真实本地文件时，构建标准 file:// Uri，实现 0 等待、0 流量极速秒开。
     * 4. 若为在线歌曲且流媒体链接未就绪，自动通过柠檬音乐协议后台快速解析真实流媒体链接。
     * 5. 否则路由至远程流媒体 URL，自动接管 Media3 播放与缓存。
     */
    suspend fun resolveMediaItem(
        song: UnifiedSong,
        forceRefresh: Boolean = false,
        overrideQuality: String? = null
    ): MediaItem {
        val resolvedLocalPath: String? = withContext(Dispatchers.IO) {
            try {
                val downloadRecord = downloadDao.getDownloadRecord(song.id)
                val recordPath = downloadRecord?.localFilePath
                val hasValidRecordFile = downloadRecord?.status == DownloadStatus.DOWNLOADED &&
                        !recordPath.isNullOrEmpty() &&
                        File(recordPath).let { it.exists() && it.length() > 0 }

                val hasValidSongLocalFile = !song.localFilePath.isNullOrEmpty() &&
                        File(song.localFilePath).let { it.exists() && it.length() > 0 }

                val isStreamUrlLocalFile = song.streamUrl.startsWith("/") &&
                        File(song.streamUrl).let { it.exists() && it.length() > 0 }

                val defaultDownloadFile = File(File(context.getExternalFilesDir(null), "music"), "${song.id}.${song.format}")
                val hasDefaultFile = defaultDownloadFile.exists() && defaultDownloadFile.length() > 0

                val directLocalPath: String? = when {
                    hasValidRecordFile -> recordPath
                    hasValidSongLocalFile -> song.localFilePath
                    isStreamUrlLocalFile -> song.streamUrl
                    hasDefaultFile -> defaultDownloadFile.absolutePath
                    else -> null
                }

                // 本地库智能匹配：当直接路径为空时，尝试从本地曲库匹配已下载的物理音频（定向索引查询，严禁全库扫描）
                directLocalPath ?: run {
                    val db = ZdsDatabase.getInstance(context)
                    val candidates = db.songDao().findCandidatesForPlayback(song.id, song.title)
                    val matched = candidates.firstOrNull { s ->
                        val hasFile = !s.localFilePath.isNullOrBlank() && File(s.localFilePath).let { f -> f.exists() && f.length() > 0 }
                        hasFile && (s.id == song.id || SongMatchingResolver.isSongMatch(
                            s.title, s.artist, s.durationMs,
                            song.title, song.artist, song.durationMs,
                            s.album, song.album
                        ))
                    }
                    matched?.localFilePath
                }
            } catch (_: Exception) {
                null
            }
        }

        val isStaleProxyQuality = overrideQuality != null

        var finalStreamUrl = if ((forceRefresh || isStaleProxyQuality) && (song.id.startsWith("lemon_online_") || song.streamUrl.contains("/api/play/proxy"))) {
            ""
        } else {
            song.streamUrl
        }

        val isOnlineId = song.id.startsWith("lemon_online_") || song.serverId == "lemon_online"
        val isServerSong = !isOnlineId &&
                song.serverId !in listOf("local_storage", "local_folder", "local_saf")

        if (resolvedLocalPath != null) {
            // 本地文件命中，同步更新队列与当前曲目状态
            val updated = song.copy(
                localFilePath = resolvedLocalPath,
                streamUrl = resolvedLocalPath,
                downloadStatus = DownloadStatus.DOWNLOADED
            )
            PlaybackQueueManager.updateCurrentSong(updated)
        } else {
            withContext(Dispatchers.IO) {
                try {
                    val db = ZdsDatabase.getInstance(context)
                    val active = db.serverDao().getActiveServer()
                        ?: if (!isOnlineId) db.serverDao().getAllServers().firstOrNull { it.id == song.serverId } else null

                    if (active != null && active.type == ServerType.LEMON_MUSIC) {
                        val client = NetworkClientFactory.createOkHttpClient(context)
                        val protocol = LemonMusicProtocol(client, active.serverUrl, active.username, active.tokenOrApiKey)

                        // 1. 音源加速优先：外网/内网高延迟时，先本地解析；即使这首歌在服务端有原文件，
                        //    也允许用同曲目同音质直链加速。失败不报错，继续原有服务器路径。
                        //
                        // 注意：本地 QuickJS 不依赖服务器鉴权，必须先尝试加速、再 ensureAuthenticated()，
                        // 否则服务器失联时鉴权会先阻塞，本地引擎根本没机会接管。
                        var accelerated = false
                        var accelerationAttempted = false
                        var localQuality: com.lm.player.core.model.AudioQuality? = null
                        // 服务端曲库曲目优先走 NAS 本地文件；只对真正的在线曲目做本地音源加速，
                        // 避免把 lemon_<hash> 误当成平台标识传给脚本。
                        if (!forceRefresh && isOnlineId && com.lm.player.core.source.SourceAccelerationController.shouldAccelerate()) {
                            accelerationAttempted = true
                            val clean = song.id.removePrefix("lemon_online_")
                            val accelSource = if (clean.contains("_")) clean.substringBefore("_") else "kw"
                            val accelQuality = overrideQuality ?: LemonMusicProtocol.getPreferredStreamQuality(context)
                            val local = com.lm.player.core.source.SourceAccelerationSession.resolveMusicUrlWithQualityFallback(
                                source = accelSource,
                                preferredQuality = accelQuality,
                                musicInfoJson = buildAccelerationInfo(song),
                                budgetMs = PLAYBACK_ACCEL_BUDGET_MS
                            )
                            val localResolution = local.getOrNull()
                            val localUrl = localResolution?.url?.takeIf {
                                it.startsWith("http://") || it.startsWith("https://")
                            }
                            if (localUrl != null && localResolution != null &&
                                com.lm.player.core.network.CdnProbe.probe(localUrl)
                            ) {
                                finalStreamUrl = localUrl
                                accelerated = true
                                localQuality = com.lm.player.core.model.AudioQuality.fromKey(localResolution.qualityKey)
                                Log.i(TAG, "Home/playback local acceleration resolved: ${song.title}")
                            } else if (localUrl != null) {
                                Log.w(TAG, "Home/playback local acceleration probe failed: ${song.title}")
                            }
                        } else if (isServerSong && com.lm.player.core.source.SourceAccelerationController.shouldAccelerate()) {
                            // 服务器曲库没有平台 ID：按音源健康度搜索同曲目，再做本地取链；
                            // 搜索与本地取链各有独立预算，超时直接回 NAS 文件。
                            accelerationAttempted = true
                            val resolved = com.lm.player.core.source.ServerLibraryAccelerationResolver.resolve(
                                protocol = protocol,
                                serverId = active.id,
                                song = song,
                                overrideQuality = overrideQuality,
                                refresh = forceRefresh
                            ).getOrNull()
                            resolved?.let {
                                if (com.lm.player.core.network.CdnProbe.probe(it.url)) {
                                    finalStreamUrl = it.url
                                    accelerated = true
                                    localQuality = it.quality
                                    Log.i(TAG, "Server library local acceleration resolved: ${song.title} via ${it.source}")
                                } else {
                                    Log.w(TAG, "Server library local acceleration probe failed: ${song.title}")
                                    com.lm.player.core.source.ServerLibraryAccelerationResolver.invalidate(
                                        serverId = active.id,
                                        song = song,
                                        qualityKey = it.requestedQuality.key
                                    )
                                }
                            }
                        }

                        // 只有没拿到本地直链、需要走服务器回退时才鉴权；本地加速成功时
                        // 不再触碰服务器，避免服务器失联把已拿到的可播放直链又卡住。
                        if (!accelerated) {
                            protocol.ensureAuthenticated()
                        }

                        // 2. 若已有服务端本地流地址或属于服务端曲库曲目，优先刷新鉴权 Token 与标准流地址
                        if (!accelerated && (finalStreamUrl.contains("/api/play/local") || isServerSong)) {
                            var serverPath = LemonMusicProtocol.getServerFilePath(song.id, finalStreamUrl, song.coverUrl)
                            if (serverPath.isNullOrBlank()) {
                                val rel = song.relativeFolderPath?.trim().orEmpty()
                                val hasAudioExt = rel.substringAfterLast('.', "").lowercase() in setOf(
                                    "mp3", "flac", "wav", "ape", "m4a", "aac", "ogg", "opus", "wma", "dsf", "dff"
                                )
                                if (hasAudioExt && (rel.startsWith("/") || rel.contains(":/") || rel.contains(":\\"))) {
                                    serverPath = rel
                                }
                            }
                            if (serverPath.isNullOrBlank()) {
                                // 从本地数据库中查找同 ID 或完全同版本服务端曲目的有效路径/流地址
                                val allSongs = db.songDao().getAllSongsList()
                                val matchedServerSong = allSongs.firstOrNull { s ->
                                    (s.id == song.id || SongMatchingResolver.isSongMatch(
                                        s.title, s.artist, s.durationMs,
                                        song.title, song.artist, song.durationMs,
                                        s.album, song.album
                                    )) && (s.streamUrl.contains("/api/play/local") || s.coverUrl.contains("path="))
                                }
                                if (matchedServerSong != null) {
                                    serverPath = LemonMusicProtocol.getServerFilePath(
                                        matchedServerSong.id,
                                        matchedServerSong.streamUrl,
                                        matchedServerSong.coverUrl
                                    )
                                }
                            }
                            if (!serverPath.isNullOrBlank()) {
                                finalStreamUrl = protocol.getStreamUrlForPath(serverPath)
                            }
                        }

                        var resolvedFormat = song.format
                        var resolvedBitRate = song.bitRate
                        localQuality?.let { q ->
                            resolvedFormat = q.format.lowercase()
                            resolvedBitRate = q.bitrate
                        }

                        // 2. 若流地址仍为空或为待解析在线标识：通过柠檬服务端 /api/play/url 换取播放流
                        if (finalStreamUrl.isBlank() || finalStreamUrl.startsWith("lemon_online://")) {
                            val cleanId = song.id.removePrefix("lemon_online_")
                            val source = if (cleanId.contains("_")) cleanId.substringBefore("_") else "kw"
                            val meta = song.rawMetaJson?.takeIf { it.trim().startsWith("{") }
                                ?: song.relativeFolderPath?.takeIf { it.trim().startsWith("{") }
                            val preferredQuality = overrideQuality ?: LemonMusicProtocol.getPreferredStreamQuality(context)

                            val resolvedStream = protocol.resolveOnlineStreamWithQuality(
                                songId = song.id,
                                source = source,
                                preferredQuality = preferredQuality,
                                metaJson = meta,
                                fallbackTitle = song.title,
                                fallbackArtist = song.artist,
                                refresh = forceRefresh,
                                // 上面已经试过一次本地加速；失败后不要在协议层再试一次。
                                skipLocalAcceleration = accelerationAttempted
                            ).getOrNull()

                            if (resolvedStream != null && resolvedStream.url.isNotBlank()) {
                                finalStreamUrl = resolvedStream.url
                                resolvedFormat = resolvedStream.format
                                resolvedBitRate = resolvedStream.bitRate
                                val globalPrefQ = LemonMusicProtocol.getPreferredStreamQuality(context)
                                if (resolvedStream.isDowngraded || (overrideQuality != null && overrideQuality != globalPrefQ)) {
                                    val qLabel = com.lm.player.core.model.AudioQuality.fromKey(resolvedStream.qualityKey).label
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context.applicationContext, "已自动为您换源降至【$qLabel】播放", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else {
                                // 兜底：若第三方音源暂时不可用，回退匹配资料库内完全同版本已就绪的服务端曲目流地址
                                val allSongs = db.songDao().getAllSongsList()
                                val fallbackLibSong = allSongs.firstOrNull { s ->
                                    (s.streamUrl.startsWith("http://") || s.streamUrl.startsWith("https://")) &&
                                    SongMatchingResolver.isSongMatch(
                                        s.title, s.artist, s.durationMs,
                                        song.title, song.artist, song.durationMs,
                                        s.album, song.album
                                    )
                                }
                                if (fallbackLibSong != null) {
                                    val fbPath = LemonMusicProtocol.getServerFilePath(
                                        fallbackLibSong.id,
                                        fallbackLibSong.streamUrl,
                                        fallbackLibSong.coverUrl
                                    )
                                    finalStreamUrl = if (!fbPath.isNullOrBlank()) {
                                        protocol.getStreamUrlForPath(fbPath)
                                    } else {
                                        fallbackLibSong.streamUrl
                                    }
                                }
                            }
                        }

                        if (finalStreamUrl.isNotBlank() && (finalStreamUrl != song.streamUrl || resolvedFormat != song.format || resolvedBitRate != song.bitRate)) {
                            val updated = song.copy(
                                streamUrl = finalStreamUrl,
                                format = resolvedFormat,
                                bitRate = resolvedBitRate
                            )
                            PlaybackQueueManager.updateCurrentSong(updated)
                        }
                    }
                    Unit
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to resolve stream URL for ${song.title}", e)
                }
                Unit
            }
        }

        val uri: Uri = if (resolvedLocalPath != null) {
            Log.d(TAG, "Routing to local file: ${com.lm.player.core.security.LogRedactor.url(resolvedLocalPath)}")
            Uri.fromFile(File(resolvedLocalPath))
        } else if (finalStreamUrl.startsWith("content://")) {
            Log.d(TAG, "Routing to SAF content uri: ${com.lm.player.core.security.LogRedactor.url(finalStreamUrl)}")
            Uri.parse(finalStreamUrl)
        } else if (finalStreamUrl.startsWith("http://") || finalStreamUrl.startsWith("https://")) {
            Log.d(TAG, "Routing to remote stream: ${com.lm.player.core.security.LogRedactor.url(finalStreamUrl)}")
            Uri.parse(finalStreamUrl)
        } else if (finalStreamUrl.startsWith("/") && File(finalStreamUrl).let { it.exists() && it.length() > 0 }) {
            Log.d(TAG, "Routing to direct file path: ${com.lm.player.core.security.LogRedactor.url(finalStreamUrl)}")
            Uri.fromFile(File(finalStreamUrl))
        } else {
            Log.e(TAG, "Invalid stream URL for ${song.title}: '${com.lm.player.core.security.LogRedactor.url(finalStreamUrl)}'")
            throw IllegalStateException("未能解析到有效的音频播放地址，请检查网络或音源设置")
        }

        val isOffline = uri.scheme == "file" || uri.scheme == "content"

        var cachedArtworkBytes = DynamicIslandManager.getCachedArtworkBytes(song.id)
        if (cachedArtworkBytes == null) {
            try {
                withContext(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(700L) {
                        DynamicIslandManager.loadSongArtworkBitmap(context, song)
                    }
                }
                cachedArtworkBytes = DynamicIslandManager.getCachedArtworkBytes(song.id)
            } catch (_: Exception) {}
        }

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(song.title)
            .setDisplayTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setArtworkUri(if (song.coverUrl.isNotEmpty()) Uri.parse(song.coverUrl) else null)
            .setExtras(Bundle().apply {
                putBoolean("KEY_IS_OFFLINE", isOffline)
                putString("KEY_SONG_ID", song.id)
                putString("KEY_SERVER_ID", song.serverId)
                putInt("KEY_BITRATE", song.bitRate)
            })

        if (cachedArtworkBytes != null) {
            metadataBuilder.setArtworkData(cachedArtworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }

        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(uri)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }
}
