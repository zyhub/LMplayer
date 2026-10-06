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
     * 本地已存在的文件是否满足目标音质档次（与 DownloadEngine 的防重复判定同一口径：
     * 无损与有损互不兼容，无损内部不再细分 flac / Hi-Res）。
     */
    fun existingLocalFileSatisfies(song: UnifiedSong, quality: AudioQuality): Boolean {
        val targetLossless = quality.format.equals("flac", ignoreCase = true) || quality.bitrate >= 800
        val existingLossless = song.format.lowercase() in LOSSLESS_EXTS
        return if (targetLossless) existingLossless else !existingLossless
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
     * 按**目标音质**解析该歌曲的下载直链，返回**按优先级排序的候选 URL 列表**。
     *
     * 优先级（可由 DownloadSettings.downloadSourcePriority 调整）：
     * - CLOUD_FIRST（默认）：服务器原文件 → 音源在线 → 服务器本地流回退
     * - ONLINE_FIRST：音源在线 → 服务器本地流 → 服务器原文件
     *
     * 下载引擎会按顺序逐个尝试，某条 URL 下载失败（HTTP 4xx/5xx、网络错误等）
     * 自动切换到下一条候选，全部失败才报失败。这解决了"在线音源临时抽风导致
     * 下载失败、明明服务器上有文件却不回退"的问题。
     */
    suspend fun resolveStreamCandidates(
        protocol: LemonMusicProtocol,
        song: UnifiedSong,
        quality: AudioQuality,
        priority: com.lm.player.core.model.DownloadSourcePriority = com.lm.player.core.model.DownloadSourcePriority.CLOUD_FIRST
    ): List<ResolvedDownloadStream> {
        protocol.ensureAuthenticated()

        val serverPath = LemonMusicProtocol.getServerFilePath(song.id, song.streamUrl, song.coverUrl)
            ?: song.relativeFolderPath?.trim()?.takeIf { it.looksLikeAudioPath() }
        val trackId = LemonMusicProtocol.getServerTrackId(song.id)

        val serverExt = serverPath?.substringAfterLast('.', "")?.lowercase().orEmpty()
            .ifBlank { song.format.lowercase() }
        val serverMatchesTarget = !serverPath.isNullOrBlank() && when (quality) {
            AudioQuality.Q_128K -> serverExt in LOSSY_EXTS && song.bitRate in 64..192
            AudioQuality.Q_320K -> serverExt in LOSSY_EXTS && song.bitRate in 193..512
            AudioQuality.Q_FLAC -> serverExt in LOSSLESS_EXTS && song.bitRate < 1200
            AudioQuality.Q_HIRES -> serverExt in LOSSLESS_EXTS && song.bitRate >= 1200
        }

        val candidates = mutableListOf<ResolvedDownloadStream>()

        // 候选 A：服务器原文件（音质匹配时直接拖原文件，最稳最快）
        if (serverMatchesTarget && serverPath != null) {
            val serverUrl = try {
                protocol.resolveServerLocalPlayUrl(serverPath, trackId, quality = quality.key).getOrNull()
            } catch (_: Exception) { null }
            val url = serverUrl?.takeIf { it.isNotBlank() }
                ?: protocol.getStreamUrlForPath(serverPath, quality = quality.key).takeIf { it.isNotBlank() }
            if (!url.isNullOrBlank()) {
                candidates.add(ResolvedDownloadStream(url, serverExt, song.bitRate))
            }
        }

        // 候选 B：音源在线按目标音质解析（含逐级降级 + 同名搜索回退）
        val meta = song.rawMetaJson?.takeIf { it.trim().startsWith("{") }
            ?: song.relativeFolderPath?.takeIf { it.trim().startsWith("{") }
        val onlineResolved = try {
            protocol.resolveOnlineStreamWithQuality(
                songId = song.id,
                source = resolveOnlineSource(song),
                preferredQuality = quality.key,
                metaJson = meta,
                fallbackTitle = song.title,
                fallbackArtist = song.artist
            ).getOrNull()
        } catch (_: Exception) { null }
        if (onlineResolved != null && onlineResolved.url.isNotBlank()) {
            candidates.add(ResolvedDownloadStream(onlineResolved.url, onlineResolved.format, onlineResolved.bitRate))
        }

        // 候选 C：服务器本地流（带 quality 参数让服务端给对应音质）—— 永远的兜底
        if ((serverPath != null || trackId != null) && !serverMatchesTarget) {
            val serverUrl = try {
                protocol.resolveServerLocalPlayUrl(serverPath, trackId, quality = quality.key).getOrNull()
            } catch (_: Exception) { null }
            val url = serverUrl?.takeIf { it.isNotBlank() }
                ?: serverPath?.let {
                    protocol.getStreamUrlForPath(it, quality = quality.key).takeIf { u -> u.isNotBlank() }
                }
            if (!url.isNullOrBlank()) {
                candidates.add(ResolvedDownloadStream(url, quality.format.lowercase(), quality.bitrate))
            }
        }

        // 候选 D：歌曲自带直链兜底
        val fallbackUrl = song.streamUrl
            .takeIf { it.isNotBlank() && !it.startsWith("lemon_online://") }
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let { streamUrl ->
                if (streamUrl.contains("/api/play/local") && !streamUrl.contains("quality=")) {
                    "$streamUrl&quality=${quality.key}"
                } else streamUrl
            }
        if (!fallbackUrl.isNullOrBlank()) {
            candidates.add(ResolvedDownloadStream(fallbackUrl, quality.format.lowercase(), quality.bitrate))
        }

        // 按优先级重排：CLOUD_FIRST 保持当前顺序（服务器→在线→服务器流→自带）；
        // ONLINE_FIRST 把在线候选提到最前。
        return if (priority == com.lm.player.core.model.DownloadSourcePriority.ONLINE_FIRST) {
            // 把非 /api/play 的在线 URL 移到首位
            val onlineCandidate = candidates.firstOrNull { !it.url.contains("/api/play/") && !it.url.contains("lemon_music") }
            if (onlineCandidate != null) {
                listOf(onlineCandidate) + candidates.filter { it !== onlineCandidate }
            } else candidates
        } else {
            candidates
        }.distinctBy { it.url }
    }

    /**
     * 向后兼容：返回优先级最高的候选 URL。
     */
    suspend fun resolveStreamAtQuality(
        protocol: LemonMusicProtocol,
        song: UnifiedSong,
        quality: AudioQuality
    ): ResolvedDownloadStream? = resolveStreamCandidates(protocol, song, quality).firstOrNull()

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
