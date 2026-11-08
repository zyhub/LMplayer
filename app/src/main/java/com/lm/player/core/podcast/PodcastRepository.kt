package com.lm.player.core.podcast

import android.content.Context
import com.lm.player.core.network.LemonMusicProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 播客请求失败：kind 决定界面显示哪种状态页 */
class PodcastException(val kind: PodcastLoadState, message: String) : Exception(message)

/**
 * 播客网络层：柠檬服务端 /api/podcasts（订阅管理与单集列表）+ /api/play/url（取代理播放地址），
 * 以及 Apple 播客公开目录搜索（服务端没有搜索功能）。
 */
object PodcastRepository {

    private suspend fun protocol(context: Context): LemonMusicProtocol =
        LemonMusicProtocol.getActiveOrNew(context)
            ?: throw PodcastException(PodcastLoadState.NO_SERVER, "还没有配置柠檬服务器。播客订阅保存在服务器上，请先在设置中添加并连接服务器。")

    /** 发请求并把各种失败统一成 [PodcastException] */
    private suspend fun call(
        context: Context,
        method: String,
        path: String,
        body: JSONObject? = null,
        longRunning: Boolean = false,
        notFoundAsUnsupported: Boolean = false
    ): JSONObject {
        val proto = protocol(context)
        val (code, text) = try {
            proto.callJsonApi(method, path, body, longRunning)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IOException) {
            throw PodcastException(PodcastLoadState.NO_SERVER, "无法连接柠檬服务器：${e.message ?: "网络错误"}")
        } catch (e: IllegalStateException) {
            // ensureAuthenticatedOrThrow：地址不合法 / 登录失败
            throw PodcastException(PodcastLoadState.NO_SERVER, e.message ?: "无法登录柠檬服务器")
        }
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (code == 404 && (notFoundAsUnsupported || json == null)) {
            throw PodcastException(PodcastLoadState.UNSUPPORTED, "当前柠檬服务器版本没有播客功能（/api/podcasts），请升级服务器后再试。")
        }
        if (json == null) {
            if (notFoundAsUnsupported) {
                throw PodcastException(PodcastLoadState.UNSUPPORTED, "当前柠檬服务器版本没有播客功能（/api/podcasts），请升级服务器后再试。")
            }
            throw PodcastException(PodcastLoadState.ERROR, "服务器返回了无法识别的内容（HTTP $code）")
        }
        if (code == 401) {
            throw PodcastException(PodcastLoadState.NO_SERVER, "柠檬服务器登录已失效，请在设置中重新登录。")
        }
        if (code !in 200..299 || json.has("error")) {
            val msg = json.optString("error").ifBlank { "请求失败（HTTP $code）" }
            throw PodcastException(PodcastLoadState.ERROR, msg)
        }
        return json
    }

    suspend fun listFeeds(context: Context): List<PodcastFeed> = withContext(Dispatchers.IO) {
        val json = call(context, "GET", "/api/podcasts", notFoundAsUnsupported = true)
        val arr = json.optJSONArray("data") ?: throw PodcastException(
            PodcastLoadState.UNSUPPORTED, "当前柠檬服务器版本没有播客功能（/api/podcasts），请升级服务器后再试。"
        )
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(PodcastFeed::fromJson) }
            .filter { it.id.isNotBlank() }
    }

    data class EpisodePage(val feed: PodcastFeed?, val total: Int, val page: Int, val episodes: List<PodcastEpisode>)

    /** 服务端按发布时间倒序分页；limit 最大 100 */
    suspend fun listEpisodes(context: Context, feedId: String, page: Int, limit: Int): EpisodePage =
        withContext(Dispatchers.IO) {
            val json = call(context, "GET", "/api/podcasts/${enc(feedId)}/episodes?page=$page&limit=$limit")
            val data = json.optJSONObject("data") ?: JSONObject()
            val arr = data.optJSONArray("episodes")
            val episodes = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let(PodcastEpisode::fromJson)
            }.filter { it.id.isNotBlank() && it.audioUrl.isNotBlank() }
            EpisodePage(
                feed = data.optJSONObject("feed")?.let(PodcastFeed::fromJson),
                total = data.optInt("total", episodes.size),
                page = data.optInt("page", page),
                episodes = episodes
            )
        }

    /** 订阅：服务器会立即抓取并解析 RSS（可能需要几十秒） */
    suspend fun subscribe(context: Context, url: String): PodcastFeed = withContext(Dispatchers.IO) {
        val json = call(context, "POST", "/api/podcasts", JSONObject().put("url", url.trim()), longRunning = true)
        json.optJSONObject("data")?.let(PodcastFeed::fromJson)
            ?: throw PodcastException(PodcastLoadState.ERROR, "订阅失败：服务器没有返回节目信息")
    }

    /** 让服务器重新抓取 RSS */
    suspend fun refresh(context: Context, feedId: String): PodcastFeed = withContext(Dispatchers.IO) {
        val json = call(context, "POST", "/api/podcasts/${enc(feedId)}/refresh", longRunning = true)
        json.optJSONObject("data")?.let(PodcastFeed::fromJson)
            ?: throw PodcastException(PodcastLoadState.ERROR, "刷新失败：服务器没有返回节目信息")
    }

    suspend fun unsubscribe(context: Context, feedId: String) = withContext(Dispatchers.IO) {
        call(context, "DELETE", "/api/podcasts/${enc(feedId)}")
        Unit
    }

    /**
     * 通过服务器取播放地址（服务器代理 RSS 里的音频，带短时效签名）。
     * 直连音频失败（防盗链 / 地区限制 / 证书问题）时作为兜底。
     */
    suspend fun resolveProxyUrl(context: Context, episodeId: String): String = withContext(Dispatchers.IO) {
        val json = call(
            context, "POST", "/api/play/url",
            JSONObject().put("source", "podcast").put("podcastEpisodeId", episodeId)
        )
        val url = json.optString("url").trim()
        if (url.isBlank()) throw PodcastException(PodcastLoadState.ERROR, "服务器没有返回播放地址")
        protocol(context).absoluteServerUrl(url)
    }

    // ==================== Apple 播客目录搜索 ====================

    private val directoryClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    suspend fun searchDirectory(query: String): List<PodcastDirectoryResult> = withContext(Dispatchers.IO) {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("media", "podcast")
            .addQueryParameter("entity", "podcast")
            .addQueryParameter("country", "CN")
            .addQueryParameter("limit", "40")
            .addQueryParameter("term", query.trim())
            .build()
        val text = try {
            directoryClient.newCall(Request.Builder().url(url).header("User-Agent", "LMPlayer").build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    resp.body?.string().orEmpty()
                }
        } catch (e: IOException) {
            throw PodcastException(PodcastLoadState.ERROR, "无法连接 Apple 播客目录：${e.message ?: "网络错误"}。也可以直接粘贴 RSS 地址订阅。")
        }
        val arr = runCatching { JSONObject(text).optJSONArray("results") }.getOrNull()
            ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val feedUrl = o.optString("feedUrl").trim()
            if (feedUrl.isBlank()) return@mapNotNull null
            PodcastDirectoryResult(
                feedUrl = feedUrl,
                title = o.optString("collectionName").ifBlank { o.optString("trackName") },
                author = o.optString("artistName"),
                imageUrl = o.optString("artworkUrl600").ifBlank { o.optString("artworkUrl100") },
                genre = o.optString("primaryGenreName").takeUnless { it == "Podcasts" }.orEmpty(),
                trackCount = o.optInt("trackCount")
            )
        }.distinctBy { normalizeFeedUrl(it.feedUrl) }
    }

    /** 比较 RSS 地址用：忽略协议大小写、末尾斜杠 */
    fun normalizeFeedUrl(url: String): String =
        url.trim().removeSuffix("/").replaceFirst(Regex("^https?://", RegexOption.IGNORE_CASE), "").lowercase()

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
