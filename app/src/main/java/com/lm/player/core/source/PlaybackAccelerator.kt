package com.lm.player.core.source

import android.util.Log
import com.lm.player.core.model.AudioQuality
import com.lm.player.core.model.ResolvedOnlineStream
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.network.CdnProbe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 播放 / 下载取链的统一调度（两端 PlaybackRouter、DownloadRequestPlanner 共用）。
 *
 * 按 [SourceAccelerationController.playbackMode]：
 * - LOCAL_ONLY（服务器离线/已删除）：只走本地，总预算 10 秒，含「本平台 → 跨平台同曲同音质 → 降一档」；
 * - LOCAL_FIRST（本地音源可用时的默认模式）：本地先跑，本地失败或 [HEDGE_DELAY_MS] 内仍没结果时才请求服务器兜底（此后谁先成功用谁）；
 * - SERVER_FIRST / SERVER_ONLY：返回 [Outcome.Miss]，由调用方走原服务器路径；服务器失败后可调 [fallbackLocal]。
 *
 * 同一首歌同一音质的并发请求（预取 + 播放）合并为一次；结果写入 [ResolvedUrlCache]。
 */
object PlaybackAccelerator {
    private const val TAG = "PlaybackAccelerator"
    const val LOCAL_FIRST_BUDGET_MS = 4_000L
    const val LOCAL_ONLY_BUDGET_MS = 10_000L
    const val FALLBACK_BUDGET_MS = 6_000L
    /** 本地优先的等待时间：超过它仍没有结果才请求服务器兜底（用户 2026-11-07 确认 3 秒） */
    const val HEDGE_DELAY_MS = 3_000L

    sealed interface Outcome {
        /** 本地拿到可播直链 */
        data class LocalHit(
            val url: String,
            val quality: AudioQuality,
            val platform: String,
            val requested: AudioQuality,
            val matchedSong: UnifiedSong? = null,
            /** 探活时拿到的文件大小（字节，0=未知）：播放缓存的固定缓存键带上它，不同文件不会拼进同一份缓存 */
            val contentBytes: Long = 0L,
            /** 链接实际对应的平台歌曲 ID（跨平台补源时是另一平台的 ID；空=未知） */
            val sourceSongId: String = ""
        ) : Outcome {
            val downgraded: Boolean get() = quality.bitrate < requested.bitrate
        }

        /** 竞速中服务器先返回 */
        data class ServerHit(val stream: ResolvedOnlineStream) : Outcome

        /** 都没拿到；serverAttempted=true 时调用方不必再走一遍服务器在线取链 */
        data class Miss(val localAttempted: Boolean, val serverAttempted: Boolean = false) : Outcome
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = ConcurrentHashMap<String, Deferred<Outcome.LocalHit?>>()

    fun platformOf(song: UnifiedSong): String {
        val clean = song.id.removePrefix("lemon_online_")
        return if (clean.contains("_")) clean.substringBefore("_").lowercase() else "kw"
    }

    fun rawIdOf(song: UnifiedSong): String {
        val clean = song.id.removePrefix("lemon_online_")
        return if (clean.contains("_")) clean.substringAfter("_") else clean
    }

    fun isOnlineSong(song: UnifiedSong): Boolean =
        song.id.startsWith("lemon_online_") || song.serverId == "lemon_online"

    /**
     * 构造传给落雪脚本的 musicInfo，逐字段对齐服务端 server/utils/musicInfo.js 的 buildMusicInfo
     * （服务器上验证过的脚本拿到的就是这个形状）。
     */
    fun buildMusicInfo(song: UnifiedSong, platform: String = platformOf(song)): JSONObject {
        val body = runCatching {
            JSONObject(song.rawMetaJson?.takeIf { it.trim().startsWith("{") }
                ?: song.relativeFolderPath?.takeIf { it.trim().startsWith("{") } ?: "{}")
        }.getOrDefault(JSONObject())
        val rawId = rawIdOf(song)
        if (body.optString("songId").isBlank() && body.optString("songmid").isBlank() &&
            body.optString("hash").isBlank() && body.optString("copyrightId").isBlank() && rawId.isNotBlank()
        ) {
            body.put("songId", rawId)
        }
        if (body.optString("name").isBlank()) body.put("name", song.title)
        if (body.optString("singer").isBlank()) body.put("singer", song.artist)
        if (body.optString("albumName").isBlank() && body.optString("album").isBlank()) body.put("albumName", song.album)
        if (body.optString("source").isBlank()) body.put("source", platform)
        return alignMusicInfo(body)
    }

    private fun JSONObject.pick(vararg keys: String): String {
        for (k in keys) {
            val v = opt(k)
            if (v != null && v != JSONObject.NULL) {
                val s = v.toString()
                if (s.isNotEmpty()) return s
            }
        }
        return ""
    }

    /** server/utils/musicInfo.js buildMusicInfo 的 Kotlin 版。 */
    fun alignMusicInfo(body: JSONObject): JSONObject {
        val out = JSONObject(body.toString())
        val albumMid = body.pick("albumMid", "albummid", "albumId")
        val cover = body.pick("img", "picUrl")
        val songId = body.pick("songId", "songmid", "hash", "copyrightId", "id")
        val songmid = body.pick("songmid", "songId", "hash", "copyrightId", "id")
        val musicId = body.pick("musicId", "songId", "songmid", "hash", "copyrightId", "id")
        out.put("source", body.pick("source"))
        out.put("name", body.optString("name"))
        out.put("singer", body.optString("singer"))
        out.put("album", body.pick("album", "albumName"))
        out.put("albumName", body.pick("albumName", "album"))
        out.put("songId", songId)
        out.put("songmid", songmid)
        out.put("strMediaMid", body.pick("strMediaMid"))
        out.put("hash", body.pick("hash").ifEmpty { songId.ifEmpty { songmid } })
        out.put("copyrightId", body.pick("copyrightId").ifEmpty { songId })
        out.put("albumAudioId", body.optString("albumAudioId"))
        out.put("albumId", body.pick("albumId").ifEmpty { albumMid })
        out.put("albumMid", albumMid)
        out.put("albummid", albumMid)
        out.put("duration", body.opt("duration")?.takeIf { it != JSONObject.NULL } ?: body.opt("interval") ?: "")
        out.put("interval", body.opt("interval")?.takeIf { it != JSONObject.NULL } ?: body.opt("duration") ?: "")
        out.put("musicId", musicId)
        out.put("rid", body.optString("rid"))
        out.put("dcTargetId", body.optString("dcTargetId"))
        out.put("img", cover)
        out.put("picUrl", cover)
        out.put("types", body.optJSONArray("types") ?: JSONArray())
        out.put("qualitys", body.optJSONArray("qualitys") ?: JSONArray())
        return out
    }

    // ======================================================================

    /**
     * 在线曲目（lemon_online_*）取链调度。
     * @param server 服务器在线取链（已含服务器侧的音质降级与跨平台补源）；null 表示没有服务器可用。
     */
    suspend fun resolveOnline(
        song: UnifiedSong,
        preferredQuality: String,
        priority: ResolvePriority,
        refresh: Boolean,
        server: (suspend () -> ResolvedOnlineStream?)?
    ): Outcome {
        // TV 按需启动：取链即需要音源引擎。没有服务器可兜底时等引擎加载好（最多 8 秒）；
        // 有服务器时不等，本次走服务器，引擎在后台加载好后下次本地优先
        if (server == null) SourceAccelerationController.awaitEngines(8_000L)
        else SourceAccelerationController.ensureEnginesForResolve()
        val mode = SourceAccelerationController.playbackMode()
        val requested = AudioQuality.fromKey(preferredQuality)
        return when (mode) {
            SourceAccelerationController.Mode.SERVER_ONLY,
            SourceAccelerationController.Mode.SERVER_FIRST -> {
                PlaybackSourceTracker.record(song.id, PlaybackSourceTracker.Info("柠檬服务器", platformOf(song), viaLocal = false))
                Outcome.Miss(localAttempted = false)
            }
            SourceAccelerationController.Mode.LOCAL_ONLY -> {
                val hit = localResolve(song, requested, priority, LOCAL_ONLY_BUDGET_MS, crossPlatform = true, refresh = refresh, lenientProbe = true)
                hit ?: Outcome.Miss(localAttempted = true, serverAttempted = server == null)
            }
            SourceAccelerationController.Mode.LOCAL_FIRST -> hedged(song, requested, priority, refresh, server)
        }
    }

    /** 服务器路径失败后的本地兜底（SERVER_FIRST 模式）。 */
    suspend fun fallbackLocal(song: UnifiedSong, preferredQuality: String, priority: ResolvePriority): Outcome.LocalHit? {
        // TV 按需启动：服务器路径已失败，本地是唯一兜底 —— 引擎还没加载就加载并等一下
        SourceAccelerationController.awaitEngines(6_000L)
        if (!SourceAccelerationController.canResolveLocally()) return null
        return localResolve(song, AudioQuality.fromKey(preferredQuality), priority, FALLBACK_BUDGET_MS, crossPlatform = true, refresh = false, lenientProbe = false)
    }

    private suspend fun hedged(
        song: UnifiedSong,
        requested: AudioQuality,
        priority: ResolvePriority,
        refresh: Boolean,
        server: (suspend () -> ResolvedOnlineStream?)?
    ): Outcome {
        val local = scope.async {
            localResolve(song, requested, priority, LOCAL_FIRST_BUDGET_MS, crossPlatform = false, refresh = refresh, lenientProbe = false)
        }
        val early = withTimeoutOrNull(HEDGE_DELAY_MS) { local.await() }
        if (early != null) return early
        if (local.isCompleted || server == null) {
            return local.await() ?: Outcome.Miss(localAttempted = true)
        }
        // 本地 3 秒还没结果：请求服务器兜底，同时本地继续，取先成功的一方（失败方不再等待；不取消 native 求值）
        Log.i(TAG, "hedging with server: ${song.title}")
        val winner = CompletableDeferred<Outcome?>()
        val remaining = AtomicInteger(2)
        scope.launch {
            val r = runCatching { local.await() }.getOrNull()
            if (r != null) winner.complete(r)
            if (remaining.decrementAndGet() == 0) winner.complete(null)
        }
        scope.launch {
            val s = runCatching { server() }.getOrNull()?.takeIf { it.url.isNotBlank() }
            if (s != null) PlaybackSourceTracker.record(song.id, PlaybackSourceTracker.Info("柠檬服务器", platformOf(song), viaLocal = false))
            if (s != null) winner.complete(Outcome.ServerHit(s))
            if (remaining.decrementAndGet() == 0) winner.complete(null)
        }
        return winner.await() ?: Outcome.Miss(localAttempted = true, serverAttempted = true)
    }

    /**
     * 本地取链：缓存 → 合并在途请求 → 逐档音质（本平台全部脚本 → 跨平台同曲同音质）→ 真实音质校验。
     *
     * 对齐服务端的「换音源 → 换平台 → 降音质」：每一档都先把本平台所有脚本试遍，再去其它平台找同一首歌，
     * 本档全部拿不到才降一档；脚本返回的链接会按「文件大小 ÷ 时长」核对真实码率，
     * 例如请求无损却拿到 MP3 时不算命中，继续换源。最终都达不到时，返回过程中拿到的音质最好的链接。
     *
     * @param floorQuality 最低可接受音质。下载在服务器可用时传入目标音质：本地达不到就交给服务器按完整阶梯处理，
     *                     而不是本地直接降到 128K。
     */
    suspend fun localResolve(
        song: UnifiedSong,
        requested: AudioQuality,
        priority: ResolvePriority,
        budgetMs: Long,
        crossPlatform: Boolean,
        refresh: Boolean,
        lenientProbe: Boolean,
        floorQuality: AudioQuality = AudioQuality.Q_128K
    ): Outcome.LocalHit? {
        // TV 按需启动：未加载则加载；有服务器可兜底时不等，没有时最多等 8 秒
        if (!SourceAccelerationController.ensureEnginesForResolve()) return null
        val platform = platformOf(song)
        val songKey = rawIdOf(song)
        if (!refresh) {
            val cached = ResolvedUrlCache.get(platform, songKey, requested.key)
            if (cached != null && AudioQuality.fromKey(cached.qualityKey).bitrate >= floorQuality.bitrate) {
                if (PlaybackSourceTracker.get(song.id)?.viaLocal != true) {
                    PlaybackSourceTracker.record(song.id, PlaybackSourceTracker.Info("本地音源", cached.platform, viaLocal = true))
                }
                return Outcome.LocalHit(
                    cached.url, AudioQuality.fromKey(cached.qualityKey), cached.platform, requested,
                    contentBytes = cached.totalBytes, sourceSongId = cached.sourceSongId
                )
            }
        } else {
            ResolvedUrlCache.invalidate(platform, songKey, requested.key)
        }
        val key = "$platform|$songKey|${requested.key}|${floorQuality.key}|$crossPlatform"
        val existing = inflight[key]
        if (existing != null && existing.isActive) {
            return withTimeoutOrNull(budgetMs) { existing.await() }
        }
        val deferred = scope.async {
            doLocalResolve(song, platform, songKey, requested, floorQuality, priority, System.currentTimeMillis() + budgetMs, crossPlatform, lenientProbe)
        }
        inflight[key] = deferred
        deferred.invokeOnCompletion { inflight.remove(key, deferred) }
        return withTimeoutOrNull(budgetMs + 300L) { deferred.await() }
    }

    /** 校验过的候选链接：实际音质（估算） */
    private class Checked(val url: String, val actual: AudioQuality, val platform: String, val matched: UnifiedSong?, val totalBytes: Long = 0L)

    private suspend fun doLocalResolve(
        song: UnifiedSong,
        platform: String,
        songKey: String,
        requested: AudioQuality,
        floorQuality: AudioQuality,
        priority: ResolvePriority,
        deadline: Long,
        crossPlatform: Boolean,
        lenientProbe: Boolean
    ): Outcome.LocalHit? {
        val info = buildMusicInfo(song, platform).toString()
        var alternatives: List<UnifiedSong>? = null
        var best: Checked? = null

        fun hit(c: Checked, sourceId: String? = null): Outcome.LocalHit {
            PlaybackSourceTracker.record(
                song.id,
                PlaybackSourceTracker.Info(SourceAccelerationSession.sourceNameOf(sourceId) ?: "本地音源", c.platform, viaLocal = true)
            )
            val srcId = c.matched?.let { rawIdOf(it) } ?: songKey
            ResolvedUrlCache.put(platform, songKey, requested.key, c.url, c.actual.key, viaLocal = true, totalBytes = c.totalBytes, sourceSongId = srcId)
            return Outcome.LocalHit(c.url, c.actual, c.platform, requested, matchedSong = c.matched, contentBytes = c.totalBytes, sourceSongId = srcId)
        }

        val ladder = SourceAccelerationSession.qualitiesFrom(requested.key)
            .map { AudioQuality.fromKey(it) }
            .filter { it.bitrate >= floorQuality.bitrate }
        for (q in ladder) {
            if (deadline - System.currentTimeMillis() <= 300L) break
            // 1) 本平台：所有脚本逐个尝试，结果必须达到本档真实音质
            if (SourceAccelerationSession.canResolve(platform)) {
                var checked: Checked? = null
                val resolved = SourceAccelerationSession.resolveMusicUrl(platform, q.key, info, priority, deadline, accept = acc@{ url ->
                    val c = check(url, q, song.durationMs, lenientProbe, platform, null) ?: return@acc false
                    if (best == null || c.actual.bitrate > best!!.actual.bitrate) best = c
                    val ok = meets(c.actual, q)
                    if (ok) checked = c
                    ok
                })
                checked?.let { return hit(it, resolved.getOrNull()?.sourceId) }
            }
            // 2) 跨平台同曲同音质（对齐服务端 crossPlatformMatch：本档所有平台都拿不到才降档）
            if (crossPlatform && deadline - System.currentTimeMillis() > 1_000L) {
                if (alternatives == null) {
                    alternatives = runCatching {
                        CrossPlatformMatcher.alternatives(song, exclude = platform, deadline = deadline)
                    }.getOrDefault(emptyList())
                }
                for (alt in alternatives.orEmpty()) {
                    if (deadline - System.currentTimeMillis() <= 300L) break
                    val altPlatform = platformOf(alt)
                    if (!SourceAccelerationSession.canResolve(altPlatform)) continue
                    val altInfo = buildMusicInfo(alt, altPlatform).toString()
                    var checked: Checked? = null
                    val duration = if (alt.durationMs > 0) alt.durationMs else song.durationMs
                    val altResolved = SourceAccelerationSession.resolveMusicUrl(altPlatform, q.key, altInfo, priority, deadline, accept = acc@{ url ->
                        val c = check(url, q, duration, lenientProbe, altPlatform, alt) ?: return@acc false
                        if (best == null || c.actual.bitrate > best!!.actual.bitrate) best = c
                        val ok = meets(c.actual, q)
                        if (ok) checked = c
                        ok
                    })
                    checked?.let {
                        Log.i(TAG, "cross-platform local hit: ${song.title} $platform -> $altPlatform (${q.key})")
                        return hit(it, altResolved.getOrNull()?.sourceId)
                    }
                }
            }
        }
        // 所有档位都没「达标」：返回拿到过的最好链接（不低于下限）
        val fallback = best
        if (fallback != null && fallback.actual.bitrate >= floorQuality.bitrate) {
            Log.i(TAG, "local best-effort: ${song.title} requested=${requested.key} actual=${fallback.actual.key}")
            return hit(fallback)
        }
        return null
    }

    /** 实际音质是否达到该档（无损档要求真无损，320K 档要求不是 128K） */
    private fun meets(actual: AudioQuality, target: AudioQuality): Boolean = actual.bitrate >= target.bitrate

    /**
     * 可播校验 + 真实音质估算。返回 null 表示不可播。
     *
     * 估算顺序：文件大小 ÷ 时长 得到平均码率（最可靠）→ Content-Type / 扩展名 → 无法判断时按请求档计。
     * 阈值：≥1500kbps 视为 Hi-Res，≥500kbps 视为无损，≥230kbps 视为 320K，其余 128K。
     */
    private fun check(url: String, requestedQ: AudioQuality, durationMs: Long, lenient: Boolean, platform: String, matched: UnifiedSong?): Checked? {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
        val probe = CdnProbe.probeInfo(url)
        when (probe.result) {
            CdnProbe.Result.BAD -> return null
            CdnProbe.Result.UNKNOWN -> if (!lenient) return null
            CdnProbe.Result.OK -> Unit
        }
        return Checked(url, estimateQuality(url, probe, durationMs, requestedQ), platform, matched, probe.totalBytes)
    }

    fun estimateQuality(url: String, probe: CdnProbe.Info, durationMs: Long, requestedQ: AudioQuality): AudioQuality {
        val path = url.substringBefore('?').lowercase()
        val type = probe.contentType
        val lossyByName = path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".aac") ||
            path.endsWith(".ogg") || path.endsWith(".opus") || type.contains("mpeg") || type.contains("aac") ||
            type.contains("mp4") || type.contains("ogg")
        val losslessByName = path.endsWith(".flac") || path.endsWith(".ape") || path.endsWith(".wav") ||
            type.contains("flac") || type.contains("wav")
        if (probe.totalBytes > 0 && durationMs >= 30_000L) {
            val kbps = probe.totalBytes * 8.0 / (durationMs / 1000.0) / 1000.0
            return when {
                kbps >= 1500 && !lossyByName -> AudioQuality.Q_HIRES
                kbps >= 500 && !lossyByName -> AudioQuality.Q_FLAC
                kbps >= 230 -> AudioQuality.Q_320K
                else -> AudioQuality.Q_128K
            }
        }
        // 没有可靠的大小/时长：只能按格式判断
        val lossless = requestedQ == AudioQuality.Q_FLAC || requestedQ == AudioQuality.Q_HIRES
        return when {
            lossless && lossyByName -> if (path.contains("128")) AudioQuality.Q_128K else AudioQuality.Q_320K
            !lossless && losslessByName -> AudioQuality.Q_FLAC
            requestedQ == AudioQuality.Q_320K && path.contains("128") && !path.contains("320") -> AudioQuality.Q_128K
            else -> requestedQ
        }
    }

    /** 播放器报 403/404 时调用，避免缓存里的坏链被反复使用。 */
    fun invalidateUrl(url: String) = ResolvedUrlCache.invalidateUrl(url)
}
