package com.lm.player.core.podcast

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 播客收听记录（仅本机）：播放位置、是否听完、最近收听时间，以及「继续收听」需要的单集快照。
 * 服务端不保存收听进度，所以这里是唯一来源。快照不含简介，避免频繁写入大段文本。
 */
object PodcastProgressStore {
    private const val PREFS = "podcast_progress_v1"
    private const val KEY_DATA = "entries"
    private const val KEY_SPEED = "podcast_speed"
    private const val MAX_ENTRIES = 300
    /** 同一集的位置写盘间隔下限（播放中每 5 秒上报一次，磁盘没必要那么勤） */
    private const val WRITE_THROTTLE_MS = 15_000L

    data class Entry(
        val episodeId: String,
        val feedId: String,
        val positionMs: Long,
        val durationMs: Long,
        val played: Boolean,
        val updatedAt: Long,
        val title: String,
        val feedTitle: String,
        val imageUrl: String,
        val audioUrl: String,
        val pubDateMs: Long
    ) {
        fun toEpisode() = PodcastEpisode(
            id = episodeId, feedId = feedId, title = title, description = "", audioUrl = audioUrl,
            durationSec = durationMs / 1000L, pubDateMs = pubDateMs, imageUrl = imageUrl,
            feedTitle = feedTitle, feedAuthor = "", feedImageUrl = imageUrl
        )
    }

    private var prefs: SharedPreferences? = null
    private val entries = LinkedHashMap<String, Entry>()
    private var lastWriteAt = 0L

    /** 任何记录变化都会 +1，界面据此重新计算进度显示 */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        runCatching {
            val arr = JSONArray(p.getString(KEY_DATA, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val e = Entry(
                    episodeId = o.optString("id"),
                    feedId = o.optString("feedId"),
                    positionMs = o.optLong("pos"),
                    durationMs = o.optLong("dur"),
                    played = o.optBoolean("played"),
                    updatedAt = o.optLong("at"),
                    title = o.optString("title"),
                    feedTitle = o.optString("feedTitle"),
                    imageUrl = o.optString("img"),
                    audioUrl = o.optString("audio"),
                    pubDateMs = o.optLong("pub")
                )
                if (e.episodeId.isNotBlank()) entries[e.episodeId] = e
            }
        }
        _version.value++
    }

    @Synchronized
    fun get(episodeId: String): Entry? = entries[episodeId]

    /** 听了一部分、未听完的单集，按最近收听时间倒序 */
    @Synchronized
    fun inProgress(): List<Entry> =
        entries.values.filter { !it.played && it.positionMs > 0L }.sortedByDescending { it.updatedAt }

    /** 播放中上报位置；接近结尾（剩余 < 30 秒或 ≥ 97%）视为听完 */
    @Synchronized
    fun recordPosition(ep: PodcastEpisode, positionMs: Long, durationMs: Long, force: Boolean = false) {
        if (positionMs <= 0L) return
        // 已标记听完的单集继续播放时保持「已听完」（用户在播放页手动标记后不被进度记录冲掉）；
        // 用户从列表重新播放时会先清除标记，见 PodcastController.prepareReplay
        if (entries[ep.id]?.played == true) return
        val dur = if (durationMs > 0L) durationMs else ep.durationSec * 1000L
        val nearEnd = dur > 0L && (dur - positionMs < 30_000L || positionMs >= dur * 97 / 100)
        // 已听完的单集重新播放时，新位置会覆盖「已听完」标记
        entries.remove(ep.id)
        entries[ep.id] = snapshot(ep, if (nearEnd) 0L else positionMs, dur, nearEnd)
        trim()
        persist(force || nearEnd)
    }

    @Synchronized
    fun markPlayed(ep: PodcastEpisode, played: Boolean) {
        val old = entries[ep.id]
        entries.remove(ep.id)
        entries[ep.id] = snapshot(ep, 0L, old?.durationMs ?: (ep.durationSec * 1000L), played)
        trim()
        persist(true)
    }

    @Synchronized
    fun removeFeed(feedId: String) {
        val removed = entries.values.removeAll { it.feedId == feedId }
        if (removed) persist(true)
        if (seenPub.remove(feedId) != null) persistSeen()
    }

    // ==================== 订阅更新角标：每个节目「上次点开时看到的最新一期」 ====================

    private const val KEY_SEEN = "seen_pub"
    private val seenPub = HashMap<String, Long>()
    private var seenLoaded = false

    private fun ensureSeenLoaded() {
        if (seenLoaded) return
        seenLoaded = true
        runCatching {
            val o = JSONObject(prefs?.getString(KEY_SEEN, "{}") ?: "{}")
            o.keys().forEach { k -> seenPub[k] = o.optLong(k) }
        }
    }

    /** 该节目上次点开时看到的最新发布时间；null = 还没有基准 */
    @Synchronized
    fun seenPubMs(feedId: String): Long? {
        ensureSeenLoaded()
        return seenPub[feedId]
    }

    /** 第一次拿到该节目的单集时建立基准（刚订阅不显示一大串「更新」） */
    @Synchronized
    fun baselineSeenIfMissing(feedId: String, latestPubMs: Long) {
        ensureSeenLoaded()
        if (latestPubMs <= 0L || seenPub.containsKey(feedId)) return
        seenPub[feedId] = latestPubMs
        persistSeen()
    }

    /** 点开节目：角标清零 */
    @Synchronized
    fun markSeen(feedId: String, latestPubMs: Long) {
        ensureSeenLoaded()
        if (latestPubMs <= 0L || (seenPub[feedId] ?: 0L) >= latestPubMs) return
        seenPub[feedId] = latestPubMs
        persistSeen()
    }

    private fun persistSeen() {
        val o = JSONObject()
        seenPub.forEach { (k, v) -> o.put(k, v) }
        prefs?.edit()?.putString(KEY_SEEN, o.toString())?.apply()
        _version.value++
    }

    fun podcastSpeed(context: Context): Float =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_SPEED, 1.0f).coerceIn(0.5f, 3.0f)

    fun setPodcastSpeed(context: Context, speed: Float) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_SPEED, speed).apply()
    }

    private fun snapshot(ep: PodcastEpisode, pos: Long, dur: Long, played: Boolean) = Entry(
        episodeId = ep.id,
        feedId = ep.feedId,
        positionMs = pos,
        durationMs = dur,
        played = played,
        updatedAt = System.currentTimeMillis(),
        title = ep.title,
        feedTitle = ep.feedTitle,
        imageUrl = ep.coverUrl,
        audioUrl = ep.audioUrl,
        pubDateMs = ep.pubDateMs
    )

    private fun trim() {
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.values.minByOrNull { it.updatedAt } ?: break
            entries.remove(oldest.episodeId)
        }
    }

    private fun persist(force: Boolean) {
        _version.value++
        val now = System.currentTimeMillis()
        if (!force && now - lastWriteAt < WRITE_THROTTLE_MS) return
        lastWriteAt = now
        val arr = JSONArray()
        entries.values.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.episodeId); put("feedId", e.feedId); put("pos", e.positionMs); put("dur", e.durationMs)
                put("played", e.played); put("at", e.updatedAt); put("title", e.title); put("feedTitle", e.feedTitle)
                put("img", e.imageUrl); put("audio", e.audioUrl); put("pub", e.pubDateMs)
            })
        }
        prefs?.edit()?.putString(KEY_DATA, arr.toString())?.apply()
    }

    /** 暂停 / 切走时调用，把节流期间未写盘的位置落盘 */
    @Synchronized
    fun flush() {
        lastWriteAt = 0L
        persist(true)
    }
}
