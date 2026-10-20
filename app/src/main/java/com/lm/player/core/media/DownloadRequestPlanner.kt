package com.lm.player.core.media

import com.lm.player.core.model.AudioQuality
import com.lm.player.core.model.LemonServerDownloadTask
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.network.LemonMusicProtocol
import org.json.JSONObject

/**
 * 下载请求规划：**单首下载与多选批量下载共用同一套决策**。
 *
 * 背景：此前两条路径是两份平行实现（服务器任务构造重复 20 行、本地下载分支重复 40 行），
 * 任何修复都只落在其中一条上；更严重的是服务器曲库歌曲（streamUrl 形如
 * `/api/play/local?path=…&token=…`，URL 里**没有音质参数**）在下载分支被当作"直链歌曲"
 * 直接下载，拉到的是服务器上的**原文件**（可能是无损），用户选的 320K 只写进了标签 ——
 * 这就是"选 320K 却下到无损"的根因。
 *
 * 这里的音质决策完全镜像播放链路 [PlaybackRouter]，三步：
 * 1. 服务器原文件音质 == 目标音质 → 直接用服务器原文件；
 * 2. 否则按目标音质向音源解析（音源内部自带 `flac24bit→flac→320k→128k` 逐级降级，
 *    并在曲目自身取链失败时按"歌名+歌手"严格版本匹配搜索回退）；
 * 3. 音源仍未命中 → 退回服务器本地流，并带上 `quality` 参数让服务端给对应音质。
 */
object DownloadRequestPlanner {

    /** 音源返回的直链及其真实音质 */
    data class ResolvedDownloadStream(
        val url: String,
        val format: String,
        val bitRate: Int
    )

    /** 下载链路单次本地加速预算；本地失败必须尽快回服务器，不能让进度条停在 0%。 */
    private const val DOWNLOAD_ACCEL_BUDGET_MS = 2_500L

    private val LOSSY_EXTS = setOf("mp3", "m4a", "aac", "ogg", "opus", "wma")
    private val LOSSLESS_EXTS = setOf("flac", "wav", "ape", "alac", "dsf", "dff")

    /** 非"服务器曲库"的 serverId 标记：本地入库与全网在线占位 */
    private val NON_SERVER_LIBRARY_IDS = setOf("local_storage", "local_folder", "local_saf", "lemon_online")

    /**
     * 该歌曲是否能"按指定音质重新获取"：全网在线曲目或柠檬服务器曲库曲目都行。
     * 纯本地入库的文件（local_storage 等）没有可换音质的来源，重新下载只会失败，
     * 因此对它们仍沿用"本地已有文件即视为已下载"的老行为。
     *
     * ⚠️ 服务器曲库歌曲的 serverId 是**服务器自身的 id**（例如 uuid），
     * 并不是字面量 `"lemon_music"`（见 MainActivity 同步写库处的 `serverId = config.id`）。
     * 之前只比对字面量，导致服务器曲库歌曲全部判为"无可换音质的来源"而落到
     * "本地直链下载"分支，直接把 `/api/play/local?path=…` 指向的**服务器原文件**
     * 拖下来（常见为无损）——这就是"选低音质仍下到无损"的真正原因。
     */
    fun hasRemoteSource(song: UnifiedSong): Boolean {
        if (song.id.startsWith("lemon_online_") || song.serverId == "lemon_online") return true
        return song.serverId.isNotBlank() && song.serverId !in NON_SERVER_LIBRARY_IDS
    }

    /**
     * 歌曲是否已经在**服务器曲库**里（serverId 为服务器实例 id，而不是 local_storage /
     * lemon_online 这类本地或在线占位）。
     *
     * 用途：服务器已有该曲目时，禁止再次向 /api/download/add 提交同名任务。
     * 服务端对「已有同名文件」的任务会进入 await_exist 等待用户确认，客户端却只看到
     * 任务已提交，最终表现为「服务器下载队列一直排队、无法完成」。
     */
    fun isServerLibrarySong(song: UnifiedSong): Boolean =
        song.serverId.isNotBlank() && song.serverId !in NON_SERVER_LIBRARY_IDS

    /**
     * 本地已存在的文件是否满足目标音质档次（与 DownloadEngine 的防重复判定同一口径：
     * 无损与有损互不兼容，无损内部不再细分 flac / Hi-Res）。
     */
    fun existingLocalFileSatisfies(song: UnifiedSong, quality: AudioQuality): Boolean {
        val targetLossless = quality.format.equals("flac", ignoreCase = true) || quality.bitrate >= 800
        val existingLossless = song.format.lowercase() in LOSSLESS_EXTS
        return if (targetLossless) {
            existingLossless
        } else {
            // 已有无损文件对任何有损目标都算「已满足」，避免批量下载再存一份 FLAC
            if (existingLossless) return true
            if (quality.bitrate >= 320 && song.bitRate in 1..255) return false
            true
        }
    }

    /**
     * 构造推送至柠檬服务端的缓存任务（单首与批量共用，杜绝两份实现各自演化）。
     */
    fun buildServerDownloadTask(song: UnifiedSong, quality: AudioQuality): LemonServerDownloadTask {
        val rawJsonStr = song.rawMetaJson ?: song.relativeFolderPath
        val rawObj = try {
            if (!rawJsonStr.isNullOrBlank()) JSONObject(rawJsonStr) else null
        } catch (_: Exception) {
            null
        }
        val platform = song.id.removePrefix("lemon_online_").substringBefore("_").ifBlank { "kw" }
        return LemonServerDownloadTask(
            id = song.id,
            name = song.title,
            singer = song.artist,
            source = platform,
            album = song.album,
            pic = song.coverUrl,
            platform = platform,
            quality = quality.key,
            songId = rawObj?.optString("songId")?.ifBlank { null }
                ?: rawObj?.optString("id")
                ?: song.id.removePrefix("lemon_online_${platform}_"),
            songmid = rawObj?.optString("songmid") ?: "",
            hash = rawObj?.optString("hash") ?: "",
            rid = rawObj?.optString("rid") ?: "",
            copyrightId = rawObj?.optString("copyrightId") ?: "",
            img = song.coverUrl,
            raw = rawJsonStr ?: ""
        )
    }

    /** 从在线曲目 id 推出音源标识（kw / kg / mg / tx …），无法识别时退回 kw */
    fun resolveOnlineSource(song: UnifiedSong): String {
        val cleanId = song.id.removePrefix("lemon_online_")
        return if (cleanId.contains("_")) cleanId.substringBefore("_") else "kw"
    }

    /**
     * 按**目标音质**解析该歌曲的下载直链。服务器曲库歌曲与原文件音质不符时，
     * 会优先改走音源取对应音质，而不是把服务器上的原文件原样拖下来。
     * 支持本地音源引擎直连与服务端协同双通道。
     */
    suspend fun resolveStreamAtQuality(
        protocol: LemonMusicProtocol?,
        song: UnifiedSong,
        quality: AudioQuality,
        context: android.content.Context? = null,
        // 下载链路专用：服务器曲库已有这首歌时直接取服务器原文件。
        // 第三方音源返回的是 /api/play/proxy，实测大文件会被服务端代理限速到
        // 2MB/156s 甚至卡死；服务器原文件 /api/play/local 稳定且快。
        preferServerFile: Boolean = false,
        // 降级候选需要先尝试服务器原文件时，跳过本地音源解析，避免又拿回同一个 403 地址。
        skipLocalAcceleration: Boolean = false
    ): ResolvedDownloadStream? {
        if (protocol == null) return null

        // 本地加速不需要服务器鉴权；先尝试本地，失败后再 ensureAuthenticated() 走服务器，
        // 避免服务器失联时鉴权先阻塞、把本地直链的机会耗掉。
        var accelerationAttempted = false

        // 服务器曲库没有平台 ID：按音源健康度搜索同曲目，再做本地取链；失败回 NAS。
        if (!skipLocalAcceleration && com.lm.player.core.source.SourceAccelerationController.shouldAccelerate() && isServerLibrarySong(song)) {
            accelerationAttempted = true
            val resolved = com.lm.player.core.source.ServerLibraryAccelerationResolver.resolve(
                protocol = protocol,
                serverId = song.serverId,
                song = song,
                overrideQuality = quality.key
            ).getOrNull()
            resolved?.let { r ->
                if (probeLocalUrl(r.url)) {
                    return ResolvedDownloadStream(r.url, r.quality.format.lowercase(), r.quality.bitrate)
                }
                // 探测失败说明缓存直链已失效，主动清掉，避免下一次下载继续拿到同一条。
                com.lm.player.core.source.ServerLibraryAccelerationResolver.invalidate(
                    serverId = song.serverId,
                    song = song,
                    qualityKey = quality.key
                )
            }
        }

        // 在线曲目：外网/内网高延迟且会话就绪时，先本地解析同音质直链。
        // 失败完全不报错，继续走原来的服务器/音源链路。
        if (!skipLocalAcceleration && com.lm.player.core.source.SourceAccelerationController.shouldAccelerate()) {
            val accelSource = resolveAccelerationSource(song)
            if (accelSource.isBlank()) {
                // 服务器曲库 ID 没有平台信息，无法安全匹配本地音源，直接走服务器。
            } else {
                accelerationAttempted = true
                val accelInfo = buildAccelerationInfo(song)
                val local = com.lm.player.core.source.SourceAccelerationSession.resolveMusicUrlWithQualityFallback(
                    source = accelSource,
                    preferredQuality = quality.key,
                    musicInfoJson = accelInfo,
                    budgetMs = DOWNLOAD_ACCEL_BUDGET_MS
                )
                val resolution = local.getOrNull()
                if (resolution != null) {
                    val url = resolution.url.takeIf {
                        it.startsWith("http://") || it.startsWith("https://")
                    }
                    if (url != null && probeLocalUrl(url)) {
                        val actual = AudioQuality.fromKey(resolution.qualityKey)
                        return ResolvedDownloadStream(url, actual.format.lowercase(), actual.bitrate)
                    }
                }
            }
        }

        protocol.ensureAuthenticated()

        var serverPath = LemonMusicProtocol.getServerFilePath(song.id, song.streamUrl, song.coverUrl)
            ?: song.relativeFolderPath?.trim()?.takeIf { it.looksLikeAudioPath() }
        var trackId = LemonMusicProtocol.getServerTrackId(song.id)

        // 在线搜索结果的 ID 与服务器曲库 ID 对不上，streamUrl 又经常是空/本地路径。
        // 用一次快速单曲查询补出服务器物理路径；查不到再走第三方音源。
        if (serverPath == null && (song.serverId.isNotBlank() || song.id.startsWith("lemon_online_"))) {
            val lookedUp = try {
                protocol.findServerLibraryTrack(song.title, song.artist, song.durationMs, song.album)
            } catch (_: Exception) {
                null
            }
            if (lookedUp != null) {
                serverPath = lookedUp.filePath
                if (trackId.isNullOrBlank()) trackId = lookedUp.trackId
                LemonMusicProtocol.registerServerFilePath(song.id, lookedUp.filePath, lookedUp.trackId)
            }
        }

        val serverExt = serverPath?.substringAfterLast('.', "")?.lowercase().orEmpty()
            .ifBlank { song.format.lowercase() }
        val serverMatchesTarget = !serverPath.isNullOrBlank() && when (quality) {
            AudioQuality.Q_128K -> serverExt in LOSSY_EXTS && song.bitRate in 64..192
            AudioQuality.Q_320K -> serverExt in LOSSY_EXTS && song.bitRate in 193..512
            AudioQuality.Q_FLAC -> serverExt in LOSSLESS_EXTS && song.bitRate < 1200
            AudioQuality.Q_HIRES -> serverExt in LOSSLESS_EXTS && song.bitRate >= 1200
        }

        var url: String? = null
        var format = quality.format.lowercase()
        var bitRate = quality.bitrate

        // 下载链路：服务器已有该文件时直接走 /api/play/local。
        // 即使服务器原文件是 FLAC、用户选的是 320K，也宁可下到无损（下载完成后的 toast
        // 会如实显示实际音质），也不要卡在第三方代理的限速上。
        val preferredServerPath = serverPath
        // 只在服务器原文件音质「不低于」目标时直接用它；服务器只有 128K 而用户要 FLAC
        // 时仍先尝试音源，音源失败再由步骤 3 回退服务器文件。
        val targetLossless = quality.bitrate >= 800 || quality.format.lowercase() in LOSSLESS_EXTS
        val serverCoversTarget = when {
            serverExt in LOSSLESS_EXTS -> true
            targetLossless -> false
            else -> song.bitRate >= quality.bitrate - 64
        }
        if (preferServerFile && !preferredServerPath.isNullOrBlank() && serverCoversTarget) {
            val serverUrl = protocol.getStreamUrlForPath(preferredServerPath, quality.key)
                .takeIf { it.isNotBlank() }
            if (!serverUrl.isNullOrBlank()) {
                val actualFormat = serverExt.ifBlank { quality.format.lowercase() }
                val actualBitRate = when {
                    actualFormat in LOSSLESS_EXTS && song.bitRate < 800 -> 960
                    actualFormat in LOSSLESS_EXTS -> song.bitRate
                    song.bitRate > 0 -> song.bitRate
                    else -> quality.bitrate
                }
                return ResolvedDownloadStream(serverUrl, actualFormat, actualBitRate)
            }
        }

        // 步骤 2：服务器原文件不是目标音质 → 优先向音源按目标音质解析（含逐级降级 + 同名搜索回退）
        if (!serverMatchesTarget) {
            val meta = song.rawMetaJson?.takeIf { it.trim().startsWith("{") }
                ?: song.relativeFolderPath?.takeIf { it.trim().startsWith("{") }
            val resolved = try {
                protocol.resolveOnlineStreamWithQuality(
                    songId = song.id,
                    source = resolveOnlineSource(song),
                    preferredQuality = quality.key,
                    metaJson = meta,
                    fallbackTitle = song.title,
                    fallbackArtist = song.artist,
                    // 下载链路最多等 10 秒拿目标音质：服务器已有该文件时必须尽快回退到
                    // /api/play/local，而不是让进度条永远停在 0% 等音源逐档降级。
                    maxDurationMs = 10_000L,
                    // 上面已经试过一次本地加速；失败后不要在协议层再试一次。
                    skipLocalAcceleration = skipLocalAcceleration || accelerationAttempted
                ).getOrNull()
            } catch (_: Exception) {
                null
            }
            if (resolved != null && resolved.url.isNotBlank()) {
                url = resolved.url
                format = resolved.format
                bitRate = resolved.bitRate
            }
        }

        // 步骤 3：音源未命中 → 退回服务器本地流（带 quality 参数换取对应音质）
        if (url.isNullOrBlank() && (serverPath != null || trackId != null)) {
            val serverUrl = try {
                protocol.resolveServerLocalPlayUrl(serverPath, trackId, quality = quality.key).getOrNull()
            } catch (_: Exception) {
                null
            }
            url = serverUrl?.takeIf { it.isNotBlank() }
                ?: serverPath?.let {
                    protocol.getStreamUrlForPath(it, quality = quality.key).takeIf { u -> u.isNotBlank() }
                }
        }

        // 兜底：歌曲自带的直链。必须是 http(s) 绝对地址 ——
        // 已下载到本地的歌曲 streamUrl 会被改写成**本地文件路径**，那种地址交给引擎只会得
        // 到"未能解析或获取有效的音频直链"，不如在这里就返回 null 让调用方走本地分支；
        // 服务器流地址则补上 quality 参数，尽量拿目标音质而不是服务器原文件。
        if (url.isNullOrBlank()) {
            url = song.streamUrl
                .takeIf { it.isNotBlank() && !it.startsWith("lemon_online://") }
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?.let { streamUrl ->
                    if (streamUrl.contains("/api/play/local") && !streamUrl.contains("quality=")) {
                        "$streamUrl&quality=${quality.key}"
                    } else {
                        streamUrl
                    }
                }
        }

        val finalUrl = url?.takeIf { it.isNotBlank() } ?: return null
        return ResolvedDownloadStream(finalUrl, format, bitRate)
    }

    /** 下载前轻量探测，避免本地直链 403/过期导致整个下载失败；失败则无感回服务器。 */
    private fun probeLocalUrl(url: String): Boolean = runCatching {
        val builder = okhttp3.Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .get()
        com.lm.player.core.network.MusicCdnHeaders.buildHeadersForUrl(url).forEach { (k, v) ->
            builder.header(k, v)
        }
        val req = builder.build()
        probeClient.newCall(req).execute().use { resp ->
            val type = resp.header("Content-Type").orEmpty().lowercase()
            (resp.code == 200 || resp.code == 206) && !type.contains("text/html")
        }
    }.getOrDefault(false)

    private val probeClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** 服务器曲库 ID 不带平台前缀，必须从 rawMetaJson 的 source/platform 取；取不到就不加速。 */
    private fun resolveAccelerationSource(song: UnifiedSong): String {
        val clean = song.id.removePrefix("lemon_online_")
        if (clean != song.id || song.serverId == "lemon_online") {
            return if (clean.contains("_")) clean.substringBefore("_") else "kw"
        }
        val meta = runCatching { JSONObject(song.rawMetaJson ?: "{}") }.getOrNull()
        val source = meta?.optString("source").orEmpty().ifBlank { meta?.optString("platform").orEmpty() }
        return source.lowercase().takeIf { it in setOf("kw", "kg", "tx", "wy", "mg") }.orEmpty()
    }

    private fun buildAccelerationInfo(song: UnifiedSong): String {
        val obj = runCatching { JSONObject(song.rawMetaJson ?: "{}") }.getOrDefault(JSONObject())
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

    private fun hasAudioExt(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in LOSSY_EXTS || ext in LOSSLESS_EXTS
    }

    /**
     * relativeFolderPath 字段偶尔存的是 metaJson 原文，必须要求它看上去像一条音频路径
     * （与 PlaybackRouter 的判定保持一致），否则 JSON 尾巴会被误当成服务器文件路径。
     */
    private fun String.looksLikeAudioPath(): Boolean =
        (startsWith("/") || contains(":/") || contains(":\\")) && hasAudioExt(this)
}
