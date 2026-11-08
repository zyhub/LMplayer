package com.lm.player.core.podcast

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.lm.player.core.media.Media3Factory
import com.lm.player.core.media.PlaybackQueueManager
import com.lm.player.core.model.UnifiedSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * 播客逻辑层：订阅 / 单集数据、页面导航栈、播放与收听进度。
 * 界面只拿 [homeUi] 等函数产出的 *Ui 对象与 [actions] 回调（见 PodcastUiModels.kt 约定）。
 */
object PodcastController {
    private const val TAG = "PodcastController"
    private const val LATEST_PER_FEED = 20
    private const val DETAIL_PAGE_SIZE = 50
    private const val NEW_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_QUEUE = 50

    sealed interface Route {
        object Home : Route
        data class Show(val feedId: String) : Route
        object Manage : Route
        object Search : Route
    }

    data class ShowDetail(
        val feedId: String,
        /** 已加载的单集，服务器顺序（最新在前） */
        val episodes: List<PodcastEpisode> = emptyList(),
        val total: Int = 0,
        val nextPage: Int = 1,
        val allLoaded: Boolean = false,
        val load: PodcastLoadState = PodcastLoadState.LOADING,
        val message: String = "",
        val isLoadingMore: Boolean = false,
        val sort: EpisodeSort = EpisodeSort.NEWEST_FIRST,
        val filter: EpisodeFilter = EpisodeFilter.ALL
    )

    data class State(
        val loadedOnce: Boolean = false,
        val homeLoad: PodcastLoadState = PodcastLoadState.LOADING,
        val homeMessage: String = "",
        val isRefreshing: Boolean = false,
        val fetchAllProgress: String = "",
        val feeds: List<PodcastFeed> = emptyList(),
        val latestByFeed: Map<String, List<PodcastEpisode>> = emptyMap(),
        val refreshingFeedIds: Set<String> = emptySet(),
        val deletingFeedIds: Set<String> = emptySet(),
        val details: Map<String, ShowDetail> = emptyMap(),
        val routes: List<Route> = listOf(Route.Home),
        val sheetEpisodeId: String? = null,
        // 订阅管理
        val isAdding: Boolean = false,
        val addError: String = "",
        val lastAddedTitle: String = "",
        val deleteError: String = "",
        // 搜索
        val searchState: PodcastSearchState = PodcastSearchState.IDLE,
        val searchMessage: String = "",
        val searchResults: List<PodcastDirectoryResult> = emptyList(),
        val subscribingUrls: Set<String> = emptySet(),
        val isSubscribingUrl: Boolean = false,
        val subscribeError: String = "",
        /** 单集缓存有新内容（如补齐了简介）时 +1，触发界面重算 */
        val cacheVersion: Int = 0
    )

    /** 当前播放器里的播客单集 */
    data class NowPlaying(val episodeId: String?, val isPlaying: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var appContext: Context? = null
    private val episodeCache = ConcurrentHashMap<String, PodcastEpisode>()
    private val descriptionRequested = HashSet<String>()
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var trackerJob: Job? = null

    // ==================== 初始化与收听进度记录 ====================

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        PodcastProgressStore.init(context)
        startTracker()
    }

    private fun ctx(): Context? = appContext

    /** 每 5 秒记录一次当前播客单集的位置；暂停 / 切走时立即落盘 */
    private fun startTracker() {
        trackerJob?.cancel()
        trackerJob = scope.launch {
            var lastSongId: String? = null
            var lastEp: PodcastEpisode? = null
            var lastPos = 0L
            var lastDur = 0L
            var wasPlaying = false
            while (isActive) {
                val song = PlaybackQueueManager.currentSongFlow.value
                if (song?.id != lastSongId) {
                    lastEp?.let { if (lastPos > 0L) PodcastProgressStore.recordPosition(it, lastPos, lastDur, force = true) }
                    lastSongId = song?.id
                    lastEp = null
                    lastPos = 0L
                    lastDur = 0L
                }
                if (song != null && PodcastSongs.isPodcast(song)) {
                    val player = Media3Factory.peekSharedExoPlayer()
                    if (player != null && player.currentMediaItem?.mediaId == song.id) {
                        val playing = player.isPlaying
                        val pos = player.currentPosition.coerceAtLeast(0L)
                        val dur = player.duration.takeIf { it > 0L } ?: 0L
                        val ep = episodeFor(song)
                        if (playing && pos > 0L) {
                            PodcastProgressStore.recordPosition(ep, pos, dur)
                        } else if (wasPlaying && !playing && pos > 0L) {
                            PodcastProgressStore.recordPosition(ep, pos, dur, force = true)
                        }
                        wasPlaying = playing
                        if (pos > 0L) {
                            lastEp = ep; lastPos = pos; lastDur = dur
                        }
                    }
                } else {
                    wasPlaying = false
                }
                delay(if (wasPlaying) 5_000L else 2_000L)
            }
        }
    }

    // ==================== 供 PlaybackQueueManager / 播放路由调用 ====================

    /** 自然播完：标记已听完 */
    fun onEpisodeCompleted(song: UnifiedSong) {
        PodcastProgressStore.markPlayed(episodeFor(song), true)
    }

    /** 播放某集时的起始位置：未听完则从记忆位置往回 3 秒续播 */
    fun resumePositionMs(episodeId: String): Long {
        val e = PodcastProgressStore.get(episodeId) ?: return 0L
        if (e.played || e.positionMs < 5_000L) return 0L
        return (e.positionMs - 3_000L).coerceAtLeast(0L)
    }

    fun resumePositionFor(song: UnifiedSong): Long = resumePositionMs(PodcastSongs.episodeId(song))

    fun speedFor(context: Context, song: UnifiedSong): Float =
        if (PodcastSongs.isPodcast(song)) PodcastProgressStore.podcastSpeed(context)
        else context.getSharedPreferences("lemon_settings_prefs", Context.MODE_PRIVATE)
            .getFloat("playback_speed", 1.0f).coerceIn(0.5f, 2.0f)

    /** 直连音频失败时，经服务器代理取地址 */
    suspend fun resolveProxyUrl(context: Context, song: UnifiedSong): String? =
        runCatching { PodcastRepository.resolveProxyUrl(context, PodcastSongs.episodeId(song)) }
            .onFailure { Log.w(TAG, "resolve proxy url failed: ${it.message}") }
            .getOrNull()

    // ==================== 数据加载 ====================

    /** 发现页卡片用：首次需要时加载一次 */
    fun ensureLoaded() {
        val s = _state.value
        if (!s.loadedOnce && loadJob?.isActive != true) loadHome(userRefresh = false)
    }

    fun loadHome(userRefresh: Boolean) {
        val context = ctx() ?: return
        if (loadJob?.isActive == true && !userRefresh) return
        loadJob?.cancel()
        loadJob = scope.launch {
            _state.update {
                it.copy(
                    isRefreshing = userRefresh,
                    homeLoad = if (it.loadedOnce) it.homeLoad else PodcastLoadState.LOADING
                )
            }
            try {
                val feeds = PodcastRepository.listFeeds(context)
                _state.update {
                    it.copy(
                        feeds = feeds, loadedOnce = true, homeLoad = PodcastLoadState.READY, homeMessage = "",
                        latestByFeed = it.latestByFeed.filterKeys { id -> feeds.any { f -> f.id == id } }
                    )
                }
                val latest = loadLatest(context, feeds)
                latest.forEach { (id, eps) -> PodcastProgressStore.baselineSeenIfMissing(id, maxPub(eps)) }
                _state.update { it.copy(latestByFeed = it.latestByFeed + latest, isRefreshing = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val (kind, msg) = classify(e)
                val hadData = _state.value.loadedOnce
                _state.update {
                    if (hadData) it.copy(isRefreshing = false)
                    else it.copy(isRefreshing = false, homeLoad = kind, homeMessage = msg)
                }
                if (hadData && userRefresh) toast(msg)
            }
        }
    }

    private suspend fun loadLatest(context: Context, feeds: List<PodcastFeed>): Map<String, List<PodcastEpisode>> {
        val gate = Semaphore(3)
        return kotlinx.coroutines.coroutineScope {
            feeds.map { f ->
                async {
                    gate.withPermit {
                        runCatching { PodcastRepository.listEpisodes(context, f.id, 1, LATEST_PER_FEED) }
                            .getOrNull()?.let { page -> cache(page.episodes); f.id to page.episodes }
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    private fun loadDetail(feedId: String, reset: Boolean) {
        val context = ctx() ?: return
        scope.launch {
            // 已有数据时先照常显示，后台刷新；没有数据才显示加载中
            val old = _state.value.details[feedId] ?: ShowDetail(feedId)
            val base = old.copy(
                load = if (old.episodes.isEmpty()) PodcastLoadState.LOADING else PodcastLoadState.READY,
                message = "",
                isLoadingMore = false
            )
            putDetail(base)
            try {
                val page = PodcastRepository.listEpisodes(context, feedId, 1, DETAIL_PAGE_SIZE)
                cache(page.episodes)
                page.feed?.let { f -> replaceFeed(f) }
                val d = base.copy(
                    episodes = page.episodes, total = page.total, nextPage = 2,
                    allLoaded = page.episodes.size >= page.total || page.episodes.isEmpty(),
                    load = if (page.episodes.isEmpty()) PodcastLoadState.EMPTY else PodcastLoadState.READY, message = ""
                )
                putDetail(d)
                _state.update { it.copy(latestByFeed = it.latestByFeed + (feedId to page.episodes.take(LATEST_PER_FEED))) }
                // 正在看这个节目：更新角标基准（看过即清零）
                PodcastProgressStore.baselineSeenIfMissing(feedId, maxPub(page.episodes))
                if (_state.value.routes.lastOrNull() == Route.Show(feedId)) {
                    PodcastProgressStore.markSeen(feedId, maxPub(page.episodes))
                }
                if (d.sort == EpisodeSort.OLDEST_FIRST && !d.allLoaded) loadAllPages(feedId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val (kind, msg) = classify(e)
                val cur = _state.value.details[feedId] ?: base
                if (cur.episodes.isEmpty()) putDetail(cur.copy(load = kind, message = msg)) else toast(msg)
            }
        }
    }

    private fun loadMore(feedId: String) {
        val context = ctx() ?: return
        val d = _state.value.details[feedId] ?: return
        if (d.allLoaded || d.isLoadingMore) return
        putDetail(d.copy(isLoadingMore = true))
        scope.launch {
            try {
                val page = PodcastRepository.listEpisodes(context, feedId, d.nextPage, DETAIL_PAGE_SIZE)
                cache(page.episodes)
                val cur = _state.value.details[feedId] ?: d
                val merged = (cur.episodes + page.episodes).distinctBy { it.id }
                putDetail(cur.copy(
                    episodes = merged, total = page.total, nextPage = d.nextPage + 1, isLoadingMore = false,
                    allLoaded = page.episodes.isEmpty() || merged.size >= page.total
                ))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value.details[feedId]?.let { putDetail(it.copy(isLoadingMore = false)) }
                toast(classify(e).second)
            }
        }
    }

    /** 「最早在前」需要拿到全部单集（服务器只支持倒序分页） */
    private fun loadAllPages(feedId: String) {
        val context = ctx() ?: return
        val d = _state.value.details[feedId] ?: return
        if (d.allLoaded || d.isLoadingMore) return
        putDetail(d.copy(isLoadingMore = true))
        scope.launch {
            try {
                var all = d.episodes
                var page = 1
                var total = d.total
                while (page <= 20) {
                    val p = PodcastRepository.listEpisodes(context, feedId, page, 100)
                    cache(p.episodes)
                    total = p.total
                    all = (all + p.episodes).distinctBy { it.id }
                    if (p.episodes.isEmpty() || all.size >= total) break
                    page++
                }
                val cur = _state.value.details[feedId] ?: d
                putDetail(cur.copy(episodes = all.sortedByDescending { it.pubDateMs }, total = total, allLoaded = true,
                    isLoadingMore = false, nextPage = page + 1,
                    load = if (all.isEmpty()) PodcastLoadState.EMPTY else PodcastLoadState.READY))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value.details[feedId]?.let { putDetail(it.copy(isLoadingMore = false)) }
                toast(classify(e).second)
            }
        }
    }

    /** 播放页需要简介但缓存里没有（如重启后恢复的队列）：补拉该节目的单集 */
    fun ensureDescription(song: UnifiedSong) {
        val context = ctx() ?: return
        val id = PodcastSongs.episodeId(song)
        if (episodeCache[id]?.description?.isNotBlank() == true) return
        val feedId = PodcastSongs.feedId(song).ifBlank { song.albumId }
        if (feedId.isBlank() || !descriptionRequested.add(id)) return
        scope.launch {
            for (page in 1..3) {
                val p = runCatching { PodcastRepository.listEpisodes(context, feedId, page, 100) }.getOrNull() ?: break
                cache(p.episodes)
                if (p.episodes.any { it.id == id } || p.episodes.isEmpty()) break
            }
            _state.update { it.copy(cacheVersion = it.cacheVersion + 1) }
        }
    }

    // ==================== 订阅管理 ====================

    private fun fetchShow(feedId: String, quiet: Boolean = false) {
        val context = ctx() ?: return
        if (feedId in _state.value.refreshingFeedIds) return
        _state.update { it.copy(refreshingFeedIds = it.refreshingFeedIds + feedId) }
        scope.launch {
            try {
                val feed = PodcastRepository.refresh(context, feedId)
                replaceFeed(feed)
                if (_state.value.details.containsKey(feedId)) loadDetail(feedId, reset = true)
                else reloadLatest(context, feedId)
                if (!quiet) toast("已拉取最新：${feed.title}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!quiet) toast(classify(e).second)
                // 服务器会把失败原因记在 lastError 里，重新读一次订阅列表让界面显示出来
                runCatching { PodcastRepository.listFeeds(context) }.getOrNull()?.let { feeds -> _state.update { it.copy(feeds = feeds) } }
            } finally {
                _state.update { it.copy(refreshingFeedIds = it.refreshingFeedIds - feedId) }
            }
        }
    }

    private suspend fun reloadLatest(context: Context, feedId: String) {
        runCatching { PodcastRepository.listEpisodes(context, feedId, 1, LATEST_PER_FEED) }.getOrNull()?.let { p ->
            cache(p.episodes)
            PodcastProgressStore.baselineSeenIfMissing(feedId, maxPub(p.episodes))
            _state.update { it.copy(latestByFeed = it.latestByFeed + (feedId to p.episodes)) }
        }
    }

    private fun fetchAllShows() {
        val context = ctx() ?: return
        val feeds = _state.value.feeds
        if (feeds.isEmpty() || _state.value.fetchAllProgress.isNotBlank()) return
        scope.launch {
            var failed = 0
            feeds.forEachIndexed { i, f ->
                _state.update { it.copy(fetchAllProgress = "正在拉取 ${i + 1}/${feeds.size}", refreshingFeedIds = it.refreshingFeedIds + f.id) }
                try {
                    replaceFeed(PodcastRepository.refresh(context, f.id))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed++
                } finally {
                    _state.update { it.copy(refreshingFeedIds = it.refreshingFeedIds - f.id) }
                }
            }
            _state.update { it.copy(fetchAllProgress = "") }
            toast(if (failed == 0) "已拉取全部 ${feeds.size} 个节目" else "已拉取 ${feeds.size - failed} 个节目，$failed 个失败")
            loadHome(userRefresh = false)
        }
    }

    private suspend fun subscribeInternal(context: Context, url: String): PodcastFeed {
        val feed = PodcastRepository.subscribe(context, url)
        _state.update { s ->
            s.copy(feeds = listOf(feed) + s.feeds.filterNot { it.id == feed.id }, loadedOnce = true, homeLoad = PodcastLoadState.READY)
        }
        reloadLatest(context, feed.id)
        return feed
    }

    /** 订阅管理页添加：成功后留在本页 */
    private fun addRss(url: String) {
        val context = ctx() ?: return
        if (_state.value.isAdding) return
        _state.update { it.copy(isAdding = true, addError = "", lastAddedTitle = "") }
        scope.launch {
            try {
                val feed = subscribeInternal(context, url)
                _state.update { it.copy(isAdding = false, lastAddedTitle = feed.title) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isAdding = false, addError = classify(e).second) }
            }
        }
    }

    /** 搜索页订阅（粘贴的地址或搜索结果）：成功后打开节目详情 */
    private fun subscribeFromSearch(url: String, fromResult: Boolean) {
        val context = ctx() ?: return
        _state.update {
            if (fromResult) it.copy(subscribingUrls = it.subscribingUrls + url, subscribeError = "")
            else it.copy(isSubscribingUrl = true, subscribeError = "")
        }
        scope.launch {
            try {
                val feed = subscribeInternal(context, url)
                toast("已订阅：${feed.title}")
                openShow(feed.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(subscribeError = classify(e).second) }
            } finally {
                _state.update {
                    if (fromResult) it.copy(subscribingUrls = it.subscribingUrls - url) else it.copy(isSubscribingUrl = false)
                }
            }
        }
    }

    private fun deleteShows(ids: List<String>) {
        val context = ctx() ?: return
        val targets = ids.filterNot { it in _state.value.deletingFeedIds }
        if (targets.isEmpty()) return
        _state.update { it.copy(deletingFeedIds = it.deletingFeedIds + targets, deleteError = "") }
        scope.launch {
            val errors = mutableListOf<String>()
            for (id in targets) {
                try {
                    PodcastRepository.unsubscribe(context, id)
                    PodcastProgressStore.removeFeed(id)
                    _state.update { s ->
                        s.copy(
                            feeds = s.feeds.filterNot { it.id == id },
                            latestByFeed = s.latestByFeed - id,
                            details = s.details - id,
                            routes = s.routes.filterNot { it is Route.Show && it.feedId == id }.ifEmpty { listOf(Route.Home) }
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors += classify(e).second
                } finally {
                    _state.update { it.copy(deletingFeedIds = it.deletingFeedIds - id) }
                }
            }
            if (errors.isNotEmpty()) {
                _state.update { it.copy(deleteError = if (errors.size == 1) errors.first() else "${errors.size} 个订阅删除失败：${errors.first()}") }
            } else {
                toast(if (targets.size == 1) "已删除订阅" else "已删除 ${targets.size} 个订阅")
            }
        }
    }

    // ==================== 搜索 ====================

    private fun search(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        searchJob?.cancel()
        _state.update { it.copy(searchState = PodcastSearchState.SEARCHING, searchMessage = "", subscribeError = "") }
        searchJob = scope.launch {
            try {
                val results = PodcastRepository.searchDirectory(q)
                _state.update {
                    it.copy(
                        searchResults = results,
                        searchState = if (results.isEmpty()) PodcastSearchState.NO_RESULT else PodcastSearchState.RESULTS,
                        searchMessage = if (results.isEmpty()) "没有找到「$q」相关的播客，可以换个关键词，或直接粘贴 RSS 地址" else ""
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(searchState = PodcastSearchState.ERROR, searchMessage = classify(e).second) }
            }
        }
    }

    private fun clearSearch() {
        searchJob?.cancel()
        _state.update { it.copy(searchState = PodcastSearchState.IDLE, searchResults = emptyList(), searchMessage = "", subscribeError = "") }
    }

    // ==================== 导航 ====================

    private fun push(route: Route) {
        _state.update { s -> if (s.routes.last() == route) s else s.copy(routes = s.routes + route, sheetEpisodeId = null) }
    }

    /** 返回上一级；已在主页时返回 false（由宿主退出播客页面） */
    fun back(): Boolean {
        val s = _state.value
        if (s.sheetEpisodeId != null) {
            _state.update { it.copy(sheetEpisodeId = null) }
            return true
        }
        if (s.routes.size <= 1) return false
        val leaving = s.routes.last()
        _state.update { it.copy(routes = it.routes.dropLast(1)) }
        if (leaving == Route.Search) clearSearch()
        if (leaving == Route.Manage) _state.update { it.copy(addError = "", lastAddedTitle = "", deleteError = "") }
        return true
    }

    /** 离开播客页面时回到主页（下次从发现页进入从主页开始） */
    fun resetToHome() {
        clearSearch()
        _state.update { it.copy(routes = listOf(Route.Home), sheetEpisodeId = null, addError = "", lastAddedTitle = "", deleteError = "") }
    }

    fun openShow(feedId: String) {
        push(Route.Show(feedId))
        // 点开节目：订阅更新角标清零
        PodcastProgressStore.markSeen(feedId, maxPub(_state.value.latestByFeed[feedId].orEmpty()))
        val d = _state.value.details[feedId]
        if (d == null || d.load != PodcastLoadState.READY) loadDetail(feedId, reset = true)
    }

    /** 从播放页「节目名」进入：栈设为 主页 → 节目页 */
    fun openShowFromPlayer(feedId: String) {
        _state.update { it.copy(routes = listOf(Route.Home, Route.Show(feedId)), sheetEpisodeId = null) }
        ensureLoaded()
        loadDetail(feedId, reset = true)
    }

    // ==================== 播放 ====================

    private fun playEpisode(episodeId: String) {
        val context = ctx() ?: return
        val ep = findEpisode(episodeId) ?: return
        val current = PlaybackQueueManager.currentSongFlow.value
        if (current != null && PodcastSongs.isPodcast(current) && PodcastSongs.episodeId(current) == episodeId) {
            PlaybackQueueManager.togglePlay(context)
            return
        }
        start(context, ep, contextListFor(ep))
    }

    private fun playShowLatest(feedId: String) {
        val context = ctx() ?: return
        val s = _state.value
        val list = s.details[feedId]?.episodes?.takeIf { it.isNotEmpty() } ?: s.latestByFeed[feedId].orEmpty()
        val first = list.firstOrNull()
        if (first == null) {
            toast("还没有可播放的单集，请先拉取最新")
            return
        }
        start(context, first, list)
    }

    private fun start(context: Context, ep: PodcastEpisode, list: List<PodcastEpisode>) {
        val idx = list.indexOfFirst { it.id == ep.id }
        val queueEps = if (idx >= 0) list.drop(idx).take(MAX_QUEUE) else listOf(ep)
        val songs = queueEps.map(PodcastSongs::toSong)
        prepareReplay(ep.id)
        PlaybackQueueManager.playSong(songs.first(), context, newPlaylist = songs, startPositionMs = resumePositionMs(ep.id))
    }

    /** 自动续播的顺序：节目页的当前排序；否则该节目最新单集（最新在前） */
    private fun contextListFor(ep: PodcastEpisode): List<PodcastEpisode> {
        val s = _state.value
        s.details[ep.feedId]?.let { d ->
            val ordered = ordered(d)
            if (ordered.any { it.id == ep.id }) return ordered
        }
        s.latestByFeed[ep.feedId]?.let { l -> if (l.any { it.id == ep.id }) return l }
        return listOf(ep)
    }

    fun markEpisodePlayed(episodeId: String, played: Boolean) {
        val current = PlaybackQueueManager.currentSongFlow.value
        val isCurrent = current != null && PodcastSongs.isPodcast(current) && PodcastSongs.episodeId(current) == episodeId
        // 重启后恢复的当前单集可能不在缓存里，用播放队列里的信息兜底
        val ep = findEpisode(episodeId) ?: current?.takeIf { isCurrent }?.let { episodeFor(it) } ?: return
        // 用户要求：标记后留在当前这集，按钮变淡绿色「已听完」（不再自动跳到下一集）
        PodcastProgressStore.markPlayed(ep, played)
        if (!played && isCurrent) {
            // 取消已听完：立刻按当前位置重新记录进度
            Media3Factory.peekSharedExoPlayer()?.let { p ->
                PodcastProgressStore.recordPosition(ep, p.currentPosition, p.duration.takeIf { it > 0L } ?: 0L, force = true)
            }
        }
    }

    /** 用户主动重新播放一集已听完的单集：清除「已听完」，从头开始 */
    fun prepareReplay(episodeId: String) {
        val e = PodcastProgressStore.get(episodeId) ?: return
        if (e.played) PodcastProgressStore.markPlayed(findEpisode(episodeId) ?: e.toEpisode(), false)
    }

    fun setSpeed(speed: Float) {
        val context = ctx() ?: return
        PodcastProgressStore.setPodcastSpeed(context, speed)
        if (PodcastSongs.isPodcast(PlaybackQueueManager.currentSongFlow.value)) {
            Media3Factory.peekSharedExoPlayer()?.setPlaybackSpeed(speed)
        }
        _state.update { it.copy(cacheVersion = it.cacheVersion + 1) }
    }

    fun skipBy(deltaMs: Long) {
        val context = ctx() ?: return
        val player = Media3Factory.peekSharedExoPlayer() ?: return
        val dur = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0L, dur)
        player.seekTo(target)
        com.lm.player.core.media.AudioSharingManager.onPhoneSeekIfCasting(context, target)
    }

    // ==================== 界面数据构建 ====================

    fun findEpisode(id: String): PodcastEpisode? =
        episodeCache[id] ?: PodcastProgressStore.get(id)?.toEpisode()

    fun episodeFor(song: UnifiedSong): PodcastEpisode {
        val id = PodcastSongs.episodeId(song)
        return episodeCache[id] ?: PodcastSongs.toEpisode(song)
    }

    private fun ordered(d: ShowDetail): List<PodcastEpisode> =
        if (d.sort == EpisodeSort.OLDEST_FIRST) d.episodes.sortedBy { it.pubDateMs } else d.episodes

    fun nowPlaying(song: UnifiedSong?, isPlaying: Boolean): NowPlaying =
        if (song != null && PodcastSongs.isPodcast(song)) NowPlaying(PodcastSongs.episodeId(song), isPlaying)
        else NowPlaying(null, false)

    fun episodeUi(ep: PodcastEpisode, np: NowPlaying, now: Long = System.currentTimeMillis()): PodcastEpisodeUi {
        val e = PodcastProgressStore.get(ep.id)
        val durMs = e?.durationMs?.takeIf { it > 0L } ?: (ep.durationSec * 1000L)
        val pos = e?.positionMs ?: 0L
        val playState = when {
            e?.played == true -> EpisodePlayState.PLAYED
            pos > 0L -> EpisodePlayState.IN_PROGRESS
            else -> EpisodePlayState.UNPLAYED
        }
        val inProgress = playState == EpisodePlayState.IN_PROGRESS
        val isCurrent = np.episodeId == ep.id
        val cached = episodeCache[ep.id]
        return PodcastEpisodeUi(
            id = ep.id,
            showId = ep.feedId,
            showTitle = ep.feedTitle.ifBlank { cached?.feedTitle.orEmpty() },
            title = ep.title,
            description = ep.description.ifBlank { cached?.description.orEmpty() },
            imageUrl = ep.coverUrl.ifBlank { cached?.coverUrl.orEmpty() },
            publishedText = PodcastFormat.published(ep.pubDateMs, now),
            durationText = PodcastFormat.duration(durMs / 1000L),
            remainingText = if (inProgress && durMs > 0L) PodcastFormat.remaining(durMs - pos) else "",
            progress = if (inProgress && durMs > 0L) (pos.toFloat() / durMs).coerceIn(0f, 1f) else 0f,
            playState = playState,
            isNew = ep.pubDateMs > now - NEW_WINDOW_MS && playState != EpisodePlayState.PLAYED,
            isCurrent = isCurrent,
            isPlaying = isCurrent && np.isPlaying
        )
    }

    private fun showUi(f: PodcastFeed, s: State, now: Long): PodcastShowUi {
        val latest = s.latestByFeed[f.id].orEmpty()
        val latestPub = latest.maxOfOrNull { it.pubDateMs } ?: 0L
        return PodcastShowUi(
            id = f.id,
            title = f.title,
            author = f.author,
            description = f.description,
            imageUrl = f.imageUrl,
            episodeCount = f.episodeCount,
            // 用户要求：角标 = 上次点开该节目之后新出的集数，点开即清零
            newEpisodeCount = PodcastProgressStore.seenPubMs(f.id)?.let { seen ->
                latest.count { it.pubDateMs > seen }
            } ?: 0,
            updatedText = PodcastFormat.updated(latestPub, now),
            errorText = f.lastError,
            isRefreshing = f.id in s.refreshingFeedIds,
            feedUrl = f.url,
            isDeleting = f.id in s.deletingFeedIds
        )
    }

    private fun latestPub(f: PodcastFeed, s: State): Long =
        s.latestByFeed[f.id]?.maxOfOrNull { it.pubDateMs } ?: (f.createdAtSec * 1000L)

    fun entryUi(s: State, np: NowPlaying): PodcastEntryUi {
        val now = System.currentTimeMillis()
        return PodcastEntryUi(
            showCount = if (s.loadedOnce) s.feeds.size else -1,
            newEpisodeCount = if (s.loadedOnce) s.feeds.sumOf { showUi(it, s, now).newEpisodeCount } else 0,
            isPodcastPlaying = np.episodeId != null && np.isPlaying
        )
    }

    fun homeUi(s: State, np: NowPlaying): PodcastHomeUi {
        val now = System.currentTimeMillis()
        val state = when {
            !s.loadedOnce -> s.homeLoad
            s.feeds.isEmpty() -> PodcastLoadState.EMPTY
            else -> PodcastLoadState.READY
        }
        return PodcastHomeUi(
            state = state,
            message = if (!s.loadedOnce) s.homeMessage else "",
            isRefreshing = s.isRefreshing || s.fetchAllProgress.isNotBlank(),
            refreshProgressText = s.fetchAllProgress,
            continueListening = PodcastProgressStore.inProgress().take(10).map { e ->
                episodeUi(findEpisode(e.episodeId) ?: e.toEpisode(), np, now)
            },
            shows = s.feeds.sortedByDescending { latestPub(it, s) }.map { showUi(it, s, now) },
            latestEpisodes = s.latestByFeed.values.flatten().distinctBy { it.id }
                .sortedByDescending { it.pubDateMs }.take(30).map { episodeUi(it, np, now) }
        )
    }

    fun showDetailUi(s: State, feedId: String, np: NowPlaying): PodcastShowDetailUi {
        val now = System.currentTimeMillis()
        val feed = s.feeds.firstOrNull { it.id == feedId }
            ?: PodcastFeed(feedId, "", "播客", "", "", "", "", 0L, 0L, 0)
        val d = s.details[feedId] ?: ShowDetail(feedId)
        val all = ordered(d).map { episodeUi(it, np, now) }
        val shown = if (d.filter == EpisodeFilter.UNPLAYED) all.filter { it.playState != EpisodePlayState.PLAYED } else all
        val resume = PodcastProgressStore.inProgress().firstOrNull { it.feedId == feedId }?.let { e ->
            episodeUi(findEpisode(e.episodeId) ?: e.toEpisode(), np, now)
        }
        return PodcastShowDetailUi(
            show = showUi(feed, s, now),
            state = when {
                d.load == PodcastLoadState.READY && shown.isEmpty() -> PodcastLoadState.EMPTY
                else -> d.load
            },
            message = d.message,
            episodes = shown,
            totalEpisodes = if (d.total > 0) d.total else feed.episodeCount,
            sort = d.sort,
            filter = d.filter,
            canLoadMore = !d.allLoaded && d.load == PodcastLoadState.READY,
            isLoadingMore = d.isLoadingMore,
            resumeEpisode = resume
        )
    }

    fun manageUi(s: State): PodcastManageUi {
        val now = System.currentTimeMillis()
        val state = when {
            !s.loadedOnce -> s.homeLoad
            s.feeds.isEmpty() -> PodcastLoadState.EMPTY
            else -> PodcastLoadState.READY
        }
        return PodcastManageUi(
            state = state,
            message = if (!s.loadedOnce) s.homeMessage else "",
            shows = s.feeds.sortedByDescending { it.createdAtSec }.map { showUi(it, s, now) },
            isAdding = s.isAdding,
            addError = s.addError,
            lastAddedTitle = s.lastAddedTitle,
            deleteError = s.deleteError
        )
    }

    fun searchUi(s: State): PodcastSearchUi {
        val subscribed = s.feeds.map { PodcastRepository.normalizeFeedUrl(it.url) }.toSet()
        return PodcastSearchUi(
            state = s.searchState,
            message = s.searchMessage,
            results = s.searchResults.map { r ->
                PodcastSearchResultUi(
                    feedUrl = r.feedUrl,
                    title = r.title,
                    author = r.author,
                    imageUrl = r.imageUrl,
                    genre = r.genre,
                    episodeCountText = PodcastFormat.episodeCount(r.trackCount),
                    isSubscribed = PodcastRepository.normalizeFeedUrl(r.feedUrl) in subscribed,
                    isSubscribing = r.feedUrl in s.subscribingUrls
                )
            },
            isSubscribingUrl = s.isSubscribingUrl,
            subscribeError = s.subscribeError
        )
    }

    fun sheetEpisodeUi(s: State, np: NowPlaying): PodcastEpisodeUi? =
        s.sheetEpisodeId?.let { id -> findEpisode(id)?.let { episodeUi(it, np) } }

    fun playerUi(
        context: Context,
        song: UnifiedSong,
        isPlaying: Boolean,
        isBuffering: Boolean,
        durationMs: Long,
        sleepTimerMinutes: Int,
        sleepTimerRemainingSec: Long,
        queue: List<UnifiedSong>,
        isCasting: Boolean
    ): PodcastPlayerUi {
        val now = System.currentTimeMillis()
        val ep = episodeFor(song)
        val np = NowPlaying(ep.id, isPlaying)
        val idx = queue.indexOfFirst { it.id == song.id }
        val upNext = (if (idx >= 0) queue.drop(idx + 1) else emptyList())
            .filter { PodcastSongs.isPodcast(it) }.take(20)
            .map { episodeUi(episodeFor(it), np, now) }
        return PodcastPlayerUi(
            episode = episodeUi(ep, np, now),
            showTitle = ep.feedTitle.ifBlank { song.album },
            coverUrl = ep.coverUrl.ifBlank { song.coverUrl },
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            durationMs = durationMs.takeIf { it > 0L } ?: (ep.durationSec * 1000L),
            speed = PodcastProgressStore.podcastSpeed(context),
            sleepTimerMinutes = sleepTimerMinutes,
            sleepTimerRemainingSec = sleepTimerRemainingSec,
            upNext = upNext,
            isCasting = isCasting
        )
    }

    /** 播客页面的全部操作 */
    fun actions(
        onExit: () -> Unit,
        onGoToServerSettings: () -> Unit,
        onOpenPlayer: () -> Unit
    ): PodcastActions = PodcastActions(
        onBack = { if (!back()) onExit() },
        onOpenShow = { openShow(it) },
        onOpenEpisode = { ep -> _state.update { it.copy(sheetEpisodeId = ep.id) } },
        onCloseEpisode = { _state.update { it.copy(sheetEpisodeId = null) } },
        onOpenSearch = { push(Route.Search) },
        onGoToServerSettings = onGoToServerSettings,
        onPlayEpisode = { playEpisode(it.id) },
        onPlayShowLatest = { playShowLatest(it) },
        onOpenPlayer = onOpenPlayer,
        onRefresh = { loadHome(userRefresh = true) },
        onFetchAllShows = { fetchAllShows() },
        onFetchShow = { fetchShow(it) },
        onUnsubscribe = { deleteShows(listOf(it)) },
        onRetry = { loadHome(userRefresh = false) },
        onLoadMore = { feedId ->
            val d = _state.value.details[feedId]
            if (d?.sort == EpisodeSort.OLDEST_FIRST) loadAllPages(feedId) else loadMore(feedId)
        },
        onSortChange = { feedId, sort ->
            val d = _state.value.details[feedId] ?: ShowDetail(feedId)
            putDetail(d.copy(sort = sort))
            if (sort == EpisodeSort.OLDEST_FIRST && !d.allLoaded) loadAllPages(feedId)
        },
        onFilterChange = { feedId, filter ->
            val d = _state.value.details[feedId] ?: ShowDetail(feedId)
            putDetail(d.copy(filter = filter))
        },
        onMarkPlayed = { ep, played -> markEpisodePlayed(ep.id, played) },
        onOpenManage = { push(Route.Manage) },
        onAddRss = { addRss(it) },
        onDeleteShows = { deleteShows(it) },
        onDismissManageMessage = { _state.update { it.copy(addError = "", lastAddedTitle = "", deleteError = "") } },
        onSearch = { search(it) },
        onSubscribeUrl = { subscribeFromSearch(it.trim(), fromResult = false) },
        onSubscribeResult = { subscribeFromSearch(it.feedUrl, fromResult = true) },
        onClearSearch = { clearSearch() }
    )

    // ==================== 工具 ====================

    private fun maxPub(list: List<PodcastEpisode>): Long = list.maxOfOrNull { it.pubDateMs } ?: 0L

    private fun cache(list: List<PodcastEpisode>) {
        list.forEach { episodeCache[it.id] = it }
    }

    private fun replaceFeed(f: PodcastFeed) {
        _state.update { s ->
            if (s.feeds.none { it.id == f.id }) s
            else s.copy(feeds = s.feeds.map { if (it.id == f.id) f else it })
        }
    }

    private fun putDetail(d: ShowDetail) {
        _state.update { it.copy(details = it.details + (d.feedId to d)) }
    }

    private fun classify(e: Exception): Pair<PodcastLoadState, String> = when (e) {
        is PodcastException -> e.kind to (e.message ?: "请求失败")
        is java.io.IOException -> PodcastLoadState.NO_SERVER to "无法连接柠檬服务器：${e.message ?: "网络错误"}"
        else -> PodcastLoadState.ERROR to (e.message ?: "加载失败")
    }

    private fun toast(msg: String) {
        val c = ctx() ?: return
        scope.launch { Toast.makeText(c, msg, Toast.LENGTH_SHORT).show() }
    }
}
