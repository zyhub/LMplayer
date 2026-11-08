package com.lm.player.core.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import android.util.LruCache
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.database.entity.SongEntity
import com.lm.player.core.media.PlaybackQueueManager
import com.lm.player.core.media.PlaybackService
import com.lm.player.core.model.LemonToplist
import com.lm.player.core.model.OnlineMusicSource
import com.lm.player.core.model.UnifiedPlaylist
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.network.LemonMusicProtocol
import com.lm.player.core.source.catalog.OfflineCatalogService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 榜单 / 歌单 / 搜索小部件共用的数据层（双端逐字一致）。
 *
 * 数据来源与 App 发现页一致：
 * - 有柠檬服务器：走服务器的发现接口（排行榜 / 推荐歌单 / 歌单曲目）；
 * - 没有服务器但本地音源已就绪：走本地目录服务 [OfflineCatalogService]；
 * - 「我的歌单」直接读本地数据库（与资料库同一份数据）。
 * 榜单与推荐歌单在内存里缓存 30 分钟，桌面刷新时不会反复请求网络。
 */
internal object WidgetDataRepository {

    private const val TAG = "WidgetData"
    private const val PREFS = "lm_widget_prefs"
    private const val TTL_MS = 30 * 60_000L

    /** 「我喜欢的音乐」虚拟歌单 ID（与 App 内「我的喜欢」同一份收藏数据） */
    const val FAVORITES_ID = "widget_favorites"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 部分电视盒子 / 车机系统没有桌面小部件服务，getInstance 会返回 null；统一经此取用。
     */
    fun widgetManager(context: Context): AppWidgetManager? =
        runCatching { AppWidgetManager.getInstance(context) as AppWidgetManager? }.getOrNull()

    /**
     * 小部件在桌面上的实际尺寸（dp）。按 AppWidgetManager 约定：竖屏取 minWidth × maxHeight，横屏取 maxWidth × minHeight。
     * 竖屏手机的格子「窄而高」、横屏平板的格子「宽而矮」，布局要按这个实际尺寸调整，不能只靠权重拉伸。
     */
    fun widgetSizeDp(context: Context, mgr: AppWidgetManager, id: Int): Pair<Int, Int>? {
        val o = runCatching { mgr.getAppWidgetOptions(id) }.getOrNull() ?: return null
        val portrait = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val w = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
        val h = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        return if (w > 0 && h > 0) w to h else null
    }

    /** 横竖屏切换后桌面格子尺寸变了：所有小部件按新尺寸重绘一次（不重新拉网络数据） */
    fun refreshAllForNewSize(context: Context) {
        val appCtx = context.applicationContext
        runCatching { MusicWidgetController.updateAllWidgets(appCtx) }
        runCatching { ChartWidgetController.updateAll(appCtx, allowFetch = false) }
        runCatching { PlaylistWidgetController.updateAll(appCtx, allowFetch = false) }
    }

    /** 按 dp 设置 RemoteViews 子视图宽高（Android 12+ 才支持；更低版本保持布局里的默认尺寸） */
    fun setViewSizeDp(rv: android.widget.RemoteViews, viewId: Int, widthDp: Float?, heightDp: Float?) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        widthDp?.let { rv.setViewLayoutWidth(viewId, it, android.util.TypedValue.COMPLEX_UNIT_DIP) }
        heightDp?.let { rv.setViewLayoutHeight(viewId, it, android.util.TypedValue.COMPLEX_UNIT_DIP) }
    }

    /** 发现页当前选中的在线平台（与 App 内来源切换同一个设置） */
    fun onlineSource(context: Context): OnlineMusicSource = runCatching {
        val name = context.getSharedPreferences("zds_online_prefs", Context.MODE_PRIVATE)
            .getString("selected_source", OnlineMusicSource.KUWO.name) ?: OnlineMusicSource.KUWO.name
        OnlineMusicSource.valueOf(name)
    }.getOrDefault(OnlineMusicSource.KUWO)

    private suspend fun protocol(context: Context): LemonMusicProtocol? =
        runCatching { LemonMusicProtocol.getActiveOrNew(context) }.getOrNull()

    /** 是否有任何在线目录来源（柠檬服务器或本地音源） */
    suspend fun hasOnlineCatalog(context: Context): Boolean =
        protocol(context) != null || OfflineCatalogService.isAvailable()

    // ---------------- 榜单 ----------------

    class ChartData(
        val tab: ChartTab,
        val songs: List<UnifiedSong>,
        val listName: String,
        val updateText: String,
        val loadedAt: Long
    )

    private val chartCache = ConcurrentHashMap<ChartTab, ChartData>()

    fun cachedChart(tab: ChartTab): ChartData? = chartCache[tab]

    fun isChartFresh(tab: ChartTab): Boolean =
        chartCache[tab]?.let { System.currentTimeMillis() - it.loadedAt < TTL_MS } == true

    /** 拉取某个榜单的前 30 首；失败返回 null（保留旧缓存） */
    suspend fun loadChart(context: Context, tab: ChartTab, force: Boolean = false): ChartData? = withContext(Dispatchers.IO) {
        if (!force && isChartFresh(tab)) return@withContext chartCache[tab]
        val src = onlineSource(context).key
        val proto = protocol(context)

        suspend fun fromServer(): Pair<LemonToplist, List<UnifiedSong>>? {
            val p = proto ?: return null
            val lists = p.getDiscoverToplists(source = src).getOrNull().orEmpty()
            val pick = pickToplist(lists, tab) ?: return null
            val songs = p.getDiscoverToplistSongs(toplistId = pick.id, source = pick.source, limit = 30).getOrNull().orEmpty()
            return if (songs.isEmpty()) null else pick to songs
        }

        suspend fun fromLocalSource(): Pair<LemonToplist, List<UnifiedSong>>? {
            if (!OfflineCatalogService.isAvailable()) return null
            val lists = OfflineCatalogService.toplists(src)
            val pick = pickToplist(lists, tab) ?: return null
            val songs = OfflineCatalogService.toplistSongs(pick.id, pick.source, 30)
            return if (songs.isEmpty()) null else pick to songs
        }

        val result = try {
            fromServer() ?: fromLocalSource()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "chart load failed: ${e.message}")
            null
        } ?: return@withContext null

        val (list, songs) = result
        val update = list.updateFrequency.trim().takeIf { it.isNotBlank() && it.length <= 12 } ?: "今日更新"
        ChartData(tab, songs, list.name, update, System.currentTimeMillis()).also { chartCache[tab] = it }
    }

    /** 各平台榜单名不统一：按关键词匹配热歌 / 飙升 / 新歌榜，匹配不到按榜单顺序兜底 */
    private fun pickToplist(lists: List<LemonToplist>, tab: ChartTab): LemonToplist? {
        if (lists.isEmpty()) return null
        val keys = when (tab) {
            ChartTab.HOT -> listOf("热歌", "热门", "TOP500", "Top500", "top500", "热榜", "巅峰")
            ChartTab.SURGE -> listOf("飙升", "上升", "飚升")
            ChartTab.NEW -> listOf("新歌", "新曲", "首发")
        }
        for (k in keys) lists.firstOrNull { it.name.contains(k) }?.let { return it }
        val fallbackIndex = when (tab) {
            ChartTab.HOT -> 0
            ChartTab.SURGE -> 1
            ChartTab.NEW -> 2
        }
        return lists.getOrNull(fallbackIndex) ?: lists.first()
    }

    // ---------------- 歌单 ----------------

    private val recommendCache = ConcurrentHashMap<String, Pair<Long, List<PlaylistItemUiState>>>()
    @Volatile private var myPlaylistsCache: List<PlaylistItemUiState>? = null

    fun cachedPlaylists(context: Context, tab: PlaylistTab): List<PlaylistItemUiState>? = when (tab) {
        PlaylistTab.MY -> myPlaylistsCache
        PlaylistTab.RECOMMEND -> recommendCache[onlineSource(context).key]?.second
    }

    fun isPlaylistFresh(context: Context, tab: PlaylistTab): Boolean = when (tab) {
        PlaylistTab.MY -> myPlaylistsCache != null
        PlaylistTab.RECOMMEND -> recommendCache[onlineSource(context).key]
            ?.let { System.currentTimeMillis() - it.first < TTL_MS } == true
    }

    /** 本地歌单有增删改时由观察者调用，下次渲染重新读库 */
    fun invalidateMyPlaylists() {
        myPlaylistsCache = null
    }

    suspend fun loadPlaylists(context: Context, tab: PlaylistTab, force: Boolean = false): List<PlaylistItemUiState>? =
        withContext(Dispatchers.IO) {
            if (!force && isPlaylistFresh(context, tab)) return@withContext cachedPlaylists(context, tab)
            try {
                when (tab) {
                    PlaylistTab.MY -> loadMyPlaylists(context).also { myPlaylistsCache = it }
                    PlaylistTab.RECOMMEND -> loadRecommendPlaylists(context)?.also {
                        recommendCache[onlineSource(context).key] = System.currentTimeMillis() to it
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "playlist load failed: ${e.message}")
                null
            }
        }

    private fun cleanCover(raw: String?): String? {
        val url = raw?.substringBefore("|")?.trim().orEmpty()
        if (url.isBlank() || LemonMusicProtocol.isKnownMissingEmbeddedCoverUrl(url)) return null
        return url
    }

    private suspend fun loadMyPlaylists(context: Context): List<PlaylistItemUiState> {
        val db = ZdsDatabase.getInstance(context)
        val all = db.playlistDao().getAllPlaylists()
        val favSongs = runCatching { db.songDao().getFavoriteSongsFlow().first() }.getOrDefault(emptyList())
        val serverFav = all.firstOrNull { it.id == "lemon_favorites" }
        val favCount = maxOf(favSongs.size, serverFav?.songCount ?: 0)
        val favCover = cleanCover(serverFav?.coverUrl)
            ?: favSongs.firstNotNullOfOrNull { cleanCover(it.coverUrl) }

        val out = ArrayList<PlaylistItemUiState>()
        out += PlaylistItemUiState(FAVORITES_ID, "我喜欢的音乐", "${favCount}首", favCover)
        all.asSequence()
            // 与 App 歌单列表一致：「我的收藏」由「我的喜欢」承载；发现页临时歌单不算「我的歌单」
            .filter { it.id != "lemon_favorites" && it.name != "我的收藏" }
            .filter { !it.id.startsWith("discover_") && !it.id.startsWith("lemon_rec_") }
            .take(7)
            .forEach { entity ->
                val cover = cleanCover(entity.coverUrl)
                    ?: db.playlistDao().getSongsForPlaylist(entity.id).firstNotNullOfOrNull { cleanCover(it.coverUrl) }
                out += PlaylistItemUiState(entity.id, entity.name, "${entity.songCount}首", cover)
            }
        return out
    }

    private suspend fun loadRecommendPlaylists(context: Context): List<PlaylistItemUiState>? {
        val source = onlineSource(context)
        val proto = protocol(context)
        var lists: List<UnifiedPlaylist> = proto?.getDiscoverRecommendPlaylists(source = source.key, page = 1, limit = 12)
            ?.getOrNull().orEmpty()
        if (lists.isEmpty() && OfflineCatalogService.isAvailable()) {
            lists = OfflineCatalogService.recommendPlaylists(source.key, 1, 12)
        }
        if (lists.isEmpty()) return null
        return lists.take(8).map { p ->
            PlaylistItemUiState(
                id = p.id,
                title = p.name,
                subtitle = if (p.songCount > 0) "${p.songCount}首" else "${source.shortName}精选",
                coverUrl = cleanCover(p.coverUrl) ?: p.previewCovers.firstNotNullOfOrNull { cleanCover(it) }
            )
        }
    }

    /** 歌单曲目：本地库优先，库里没有曲目（服务器歌单 / 推荐歌单）时再走服务器或本地音源 */
    suspend fun loadPlaylistSongs(context: Context, tab: PlaylistTab, playlistId: String): List<UnifiedSong> =
        withContext(Dispatchers.IO) {
            val db = ZdsDatabase.getInstance(context)
            val proto = protocol(context)
            try {
                when {
                    playlistId == FAVORITES_ID -> {
                        val local = db.songDao().getFavoriteSongsFlow().first().map { it.toWidgetSong() }
                        val server = proto?.getPlaylistSongs("lemon_favorites")?.getOrNull().orEmpty()
                        (server + local).distinctBy { it.id }
                    }
                    tab == PlaylistTab.MY -> {
                        val local = db.playlistDao().getSongsForPlaylist(playlistId).map { it.toWidgetSong() }
                        local.ifEmpty { proto?.getPlaylistSongs(playlistId)?.getOrNull().orEmpty() }
                    }
                    else -> {
                        val fromServer = proto?.getPlaylistSongs(playlistId)?.getOrNull().orEmpty()
                        fromServer.ifEmpty {
                            if (OfflineCatalogService.isAvailable()) {
                                OfflineCatalogService.collectionSongs(playlistId, onlineSource(context).key)
                            } else emptyList()
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "playlist songs failed: ${e.message}")
                emptyList()
            }
        }

    /** 两端 SongEntity 字段不完全相同，这里只取双方都有的字段 */
    private fun SongEntity.toWidgetSong(): UnifiedSong = UnifiedSong(
        id = id,
        title = title,
        artist = artist,
        artistId = artistId,
        album = album,
        albumId = albumId,
        durationMs = durationMs,
        coverUrl = coverUrl,
        streamUrl = streamUrl,
        serverId = serverId,
        localFilePath = localFilePath,
        downloadStatus = downloadStatus,
        bitRate = bitRate,
        format = format,
        isFavorite = isFavorite,
        relativeFolderPath = relativeFolderPath
    )

    // ---------------- 播放 ----------------

    /** 以整张列表为播放队列，从第 [index] 首开始播放（与 App 内点歌同一条播放链路） */
    suspend fun playList(context: Context, songs: List<UnifiedSong>, index: Int) = withContext(Dispatchers.Main) {
        val target = songs.getOrNull(index) ?: return@withContext
        val appCtx = context.applicationContext
        PlaybackQueueManager.initFromPrefs(appCtx)
        PlaybackService.startPlaybackService(appCtx)
        PlaybackQueueManager.playSong(targetSong = target, context = appCtx, newPlaylist = songs)
    }

    // ---------------- 缩略图（歌单封面） ----------------

    private const val THUMB_PX = 120
    private val thumbCache = LruCache<String, Bitmap>(24)

    fun cachedThumb(url: String?): Bitmap? = url?.let { thumbCache.get(it) }

    /** 下载并裁成圆角方图（内存 + 磁盘二级缓存）；失败返回 null */
    suspend fun loadThumb(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
        thumbCache.get(url)?.let { return@withContext it }
        val file = File(File(context.cacheDir, "widget_thumbs").apply { mkdirs() }, url.hashCode().toString(16) + ".png")
        if (file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath)?.let {
                thumbCache.put(url, it)
                return@withContext it
            }
        }
        val raw: Bitmap = try {
            when {
                url.startsWith("http://") || url.startsWith("https://") -> {
                    val client = com.lm.player.core.network.NetworkClientFactory.createImageOkHttpClient(context)
                    client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext null
                        decodeSampled(resp.body?.bytes() ?: return@withContext null)
                    }
                }
                url.startsWith("file://") || url.startsWith("/") -> {
                    val path = url.removePrefix("file://")
                    decodeSampled(File(path).takeIf { it.exists() }?.readBytes() ?: return@withContext null)
                }
                else -> null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "thumb load failed: ${e.message}")
            null
        } ?: return@withContext null

        val out = roundedSquare(raw, THUMB_PX, THUMB_PX * 0.12f)
        if (raw !== out) raw.recycle()
        runCatching { FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        thumbCache.put(url, out)
        out
    }

    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_PX && bounds.outHeight / (sample * 2) >= THUMB_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun roundedSquare(src: Bitmap, size: Int, radius: Float): Bitmap {
        val side = minOf(src.width, src.height).coerceAtLeast(1)
        val left = (src.width - side) / 2
        val top = (src.height - side) / 2
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val path = Path().apply {
            addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, Path.Direction.CW)
        }
        canvas.clipPath(path)
        canvas.drawBitmap(
            src,
            android.graphics.Rect(left, top, left + side, top + side),
            RectF(0f, 0f, size.toFloat(), size.toFloat()),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        return out
    }

    // ---------------- 悬浮面板搜索 ----------------

    /**
     * @param songs 本地曲库命中在前（离线也能播），在线结果在后；按「歌名 + 歌手」去重
     * @param onlineAvailable 是否有在线来源（柠檬服务器或本地音源）；没有时只搜了本地曲库
     */
    class SearchResult(val songs: List<UnifiedSong>, val onlineAvailable: Boolean, val onlineFailed: Boolean)

    suspend fun searchSongs(context: Context, keyword: String): SearchResult = withContext(Dispatchers.IO) {
        val q = keyword.trim()
        if (q.isEmpty()) return@withContext SearchResult(emptyList(), true, false)
        val tokens = q.split(Regex("\\s+")).filter { it.isNotBlank() }

        val local = runCatching {
            ZdsDatabase.getInstance(context).songDao().getAllSongsList().asSequence()
                .filter { s ->
                    val hay = "${s.title} ${s.artist} ${s.album}"
                    tokens.all { hay.contains(it, ignoreCase = true) }
                }
                .take(20)
                .map { it.toWidgetSong() }
                .toList()
        }.getOrDefault(emptyList())

        val src = onlineSource(context).key
        val proto = protocol(context)
        val hasLocalSource = OfflineCatalogService.isAvailable()
        var onlineFailed = false
        val online: List<UnifiedSong> = try {
            var list = proto?.searchOnline(keyword = q, source = src, page = 1, limit = 30)?.getOrNull().orEmpty()
            if (list.isEmpty() && hasLocalSource) list = OfflineCatalogService.searchSongs(q, src, 1, 30)
            list
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "search failed: ${e.message}")
            onlineFailed = true
            emptyList()
        }
        val merged = (local + online).distinctBy { "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}" }
        SearchResult(merged, proto != null || hasLocalSource, onlineFailed)
    }

    // ---------------- 搜索历史 ----------------

    private const val KEY_HISTORY = "search_history"

    fun searchHistory(context: Context): List<String> = runCatching {
        val arr = org.json.JSONArray(prefs(context).getString(KEY_HISTORY, "[]"))
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }.getOrDefault(emptyList())

    fun addSearchHistory(context: Context, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val list = (listOf(q) + searchHistory(context).filterNot { it == q }).take(10)
        prefs(context).edit().putString(KEY_HISTORY, org.json.JSONArray(list).toString()).apply()
    }

    fun clearSearchHistory(context: Context) {
        prefs(context).edit().remove(KEY_HISTORY).apply()
    }
}
