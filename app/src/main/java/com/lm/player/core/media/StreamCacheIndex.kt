package com.lm.player.core.media

import android.content.Context
import android.util.Log
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 播放缓存（边听边存）的固定缓存键 + 「整首已缓存」索引（2026-11-13，服务器歌曲加速第 1 期）。
 *
 * 以前播放缓存按播放地址认：服务器地址固定还好，本地音源加速拿到的平台地址每次都不一样（带签名、有时效），
 * 加速播放过的歌永远命中不了缓存，每次重播都重新下载。现在：
 * - 服务器文件：按「服务器 + 接口类型（原文件 / 转码 128K / APE 转码）+ 文件路径」；
 * - 本地加速：按「平台 + 平台歌曲 ID + 音质 + 文件大小」（带上大小，不同来源的文件不会拼进同一份缓存）；
 * - 其它地址（服务器代理的在线流等）拿不准是不是同一个文件，仍按地址认（与以前一样）。
 *
 * 播放用的固定键会记进索引（歌曲 ID + 请求音质 → 缓存键、地址）。下次播放前先查：
 * 当前音质下整首已在缓存里，就**不取地址**直接从缓存播，完全不联网。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object StreamCacheIndex {
    private const val TAG = "StreamCacheIndex"
    private const val FILE_NAME = "stream_cache_index.json"
    private const val MAX_SONGS = 800

    data class Entry(
        val requestedQuality: String,
        val cacheKey: String,
        val url: String,
        val format: String,
        val bitRate: Int,
        val at: Long
    )

    private val map = ConcurrentHashMap<String, List<Entry>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var loaded = false

    @Volatile
    private var persistScheduled = false

    // ---------------- 固定缓存键 ----------------

    /** 服务器文件地址的固定键；不是服务器文件接口（例如服务器代理的在线流）时返回 null */
    fun keyForServerUrl(url: String, serverUrl: String?): String? {
        if (serverUrl.isNullOrBlank()) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val serverHost = runCatching { URI(serverUrl.trim()).host }.getOrNull()?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host != serverHost) return null
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val kind = when {
            path.endsWith("/api/play/local-smooth") -> "smooth"
            path.endsWith("/api/play/local-ape") -> "ape"
            // 服务端 /api/play/local 忽略 quality 参数，永远返回原文件：键里不带音质
            path.endsWith("/api/play/local") -> "orig"
            else -> return null
        }
        val filePath = queryParam(uri.rawQuery, "path") ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "srv|$host$port|$kind|$filePath"
    }

    /** 本地加速平台地址的固定键；平台 / 歌曲 ID / 文件大小任一未知时返回 null（按地址认，与以前一样） */
    fun keyForAccelerated(platform: String, sourceSongId: String, qualityKey: String, contentBytes: Long): String? {
        if (platform.isBlank() || sourceSongId.isBlank() || contentBytes <= 0L) return null
        return "acc|${platform.lowercase()}|$sourceSongId|${qualityKey.lowercase()}|$contentBytes"
    }

    private fun queryParam(rawQuery: String?, name: String): String? {
        if (rawQuery.isNullOrBlank()) return null
        for (pair in rawQuery.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0 || pair.substring(0, idx) != name) continue
            return runCatching { URLDecoder.decode(pair.substring(idx + 1), "UTF-8") }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        return null
    }

    // ---------------- 整首已缓存 ----------------

    /** 这个缓存键是否整首都在播放缓存里。必须在 IO 线程调用 */
    fun isFullyCached(context: Context, cacheKey: String): Boolean = runCatching {
        val cache = Media3Factory.getSimpleCache(context)
        val len = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
        len > 0 && cache.isCached(cacheKey, 0, len)
    }.getOrDefault(false)

    /**
     * 当前请求音质下整首已在缓存里的条目；没有返回 null。
     * 播放缓存没开时直接返回 null（没开就不会有新缓存，旧的也不该再用）。必须在 IO 线程调用
     */
    fun fullyCachedEntry(context: Context, songId: String, requestedQuality: String): Entry? {
        if (!Media3Factory.isCacheEnabled()) return null
        ensureLoaded(context)
        val entries = map[songId] ?: return null
        val candidate = entries.filter { it.requestedQuality.equals(requestedQuality, true) }
            .sortedByDescending { it.at }
        for (e in candidate) {
            if (isFullyCached(context, e.cacheKey)) return e
        }
        return null
    }

    /** 记下这首歌在这个请求音质下用的固定缓存键（同一音质只留最新一条） */
    fun record(context: Context, songId: String, requestedQuality: String, cacheKey: String, url: String, format: String, bitRate: Int) {
        if (songId.isBlank() || cacheKey.isBlank()) return
        ensureLoaded(context)
        val now = System.currentTimeMillis()
        val old = map[songId].orEmpty().filterNot { it.requestedQuality.equals(requestedQuality, true) }
        map[songId] = old + Entry(requestedQuality.lowercase(), cacheKey, url, format, bitRate, now)
        if (map.size > MAX_SONGS + 50) {
            map.entries.sortedBy { e -> e.value.maxOfOrNull { it.at } ?: 0L }
                .take(map.size - MAX_SONGS)
                .forEach { map.remove(it.key) }
        }
        persistSoon(context)
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            runCatching {
                val f = file(context)
                if (!f.exists()) return
                val arr = JSONArray(f.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val list = o.optJSONArray("e") ?: continue
                    val entries = (0 until list.length()).mapNotNull { j ->
                        val e = list.optJSONObject(j) ?: return@mapNotNull null
                        Entry(
                            e.optString("q"), e.optString("k"), e.optString("u"),
                            e.optString("f"), e.optInt("b"), e.optLong("t")
                        ).takeIf { it.cacheKey.isNotBlank() }
                    }
                    if (entries.isNotEmpty()) map[o.optString("id")] = entries
                }
            }.onFailure { Log.w(TAG, "load failed", it) }
        }
    }

    private fun persistSoon(context: Context) {
        if (persistScheduled) return
        persistScheduled = true
        val app = context.applicationContext
        scope.launch {
            delay(3_000L)
            persistScheduled = false
            runCatching {
                val arr = JSONArray()
                map.forEach { (id, entries) ->
                    val list = JSONArray()
                    entries.forEach { e ->
                        list.put(
                            JSONObject().put("q", e.requestedQuality).put("k", e.cacheKey).put("u", e.url)
                                .put("f", e.format).put("b", e.bitRate).put("t", e.at)
                        )
                    }
                    arr.put(JSONObject().put("id", id).put("e", list))
                }
                val f = file(app)
                val tmp = File(f.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(arr.toString(), Charsets.UTF_8)
                if (!tmp.renameTo(f)) {
                    tmp.copyTo(f, overwrite = true)
                    tmp.delete()
                }
            }.onFailure { Log.w(TAG, "persist failed", it) }
        }
    }
}
