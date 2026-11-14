package com.lm.player.core.source

import android.content.Context
import android.util.Log
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.database.dao.AccelCandidateRow
import com.lm.player.core.media.PlaybackQueueManager
import com.lm.player.core.media.PlaybackRouter
import com.lm.player.core.model.ServerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 服务器曲库后台预匹配（2026-11-13，服务器歌曲加速第 2 期）。
 *
 * 登录、曲库同步完成后，在后台逐首给服务器歌曲找平台对应（这首歌在酷我 / 酷狗 / QQ 等平台上是哪一首），
 * 写进服务器歌曲加速表。之后播放这些歌时跳过现搜，直接本地取链；服务器离线时整个曲库都能本地播。
 *
 * 用户确认的规则：
 * - 范围：整个曲库；顺序：待播队列 → 最近播放 → 收藏 → 歌单里的歌 → 其余；
 * - 任何网络都做，每天最多 [DAILY_LIMIT] 首（「立即补全」不受当天上限限制）；
 * - 一次只搜一首、按平台顺序搜到即停，两首之间至少间隔 1.5 秒；连续失败（网络问题）暂停 1 小时；
 * - 正在播放 / 预取取链时让路；已下载到本机的歌跳过；没找到的 7 天后再试。
 *
 * 搜索只用本地目录服务（Kotlin，不需要 JS 引擎），不会为了预匹配把音源引擎加载起来。
 */
object ServerTrackPrematcher {
    private const val TAG = "ServerPrematch"
    private const val PREFS = "server_track_prematch"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DAY = "day"
    private const val KEY_DAY_COUNT = "day_count"

    /** 每天最多预匹配多少首（手机端 2000，用户 2026-11-14 定：任意网络） */
    const val DAILY_LIMIT = 2000
    private const val GAP_MS = 1_500L
    private const val STARTUP_DELAY_MS = 90_000L
    private const val FAIL_STREAK_LIMIT = 5
    private const val FAIL_PAUSE_MS = 60 * 60_000L
    private const val PER_PLATFORM_TIMEOUT_MS = 6_000L
    /** 让路时最多等多久（取链一般几秒内结束） */
    private const val YIELD_MAX_MS = 30_000L

    data class Progress(
        val enabled: Boolean = true,
        val running: Boolean = false,
        /** 服务器曲库里需要预匹配的歌（不含已下载到本机的） */
        val total: Int = 0,
        val matched: Int = 0,
        val notFound: Int = 0,
        val todayCount: Int = 0,
        val dailyLimit: Int = DAILY_LIMIT,
        val status: String = ""
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    @Volatile
    private var appContext: Context? = null

    /** 「立即补全」：本轮不受当天上限限制 */
    @Volatile
    private var ignoreCapThisRun = false

    @Volatile
    private var pausedUntil = 0L

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        ServerTrackAccelStore.init(app)
        _progress.value = _progress.value.copy(enabled = isEnabled(app), todayCount = todayCount(app))
        kick("startup", delayMs = STARTUP_DELAY_MS)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(KEY_ENABLED, true) }.getOrDefault(true)

    fun setEnabled(context: Context, enabled: Boolean) {
        runCatching { prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply() }
        _progress.value = _progress.value.copy(enabled = enabled, status = if (enabled) "" else "已关闭")
        if (enabled) kick("enabled", delayMs = 1_000L) else job?.cancel()
    }

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun todayCount(context: Context): Int {
        val p = prefs(context)
        return if (p.getString(KEY_DAY, "") == today()) p.getInt(KEY_DAY_COUNT, 0) else 0
    }

    private fun addTodayCount(context: Context): Int {
        val p = prefs(context)
        val day = today()
        val n = (if (p.getString(KEY_DAY, "") == day) p.getInt(KEY_DAY_COUNT, 0) else 0) + 1
        p.edit().putString(KEY_DAY, day).putInt(KEY_DAY_COUNT, n).apply()
        return n
    }

    /**
     * 触发一轮预匹配（已在跑时不重复启动）。登录 / 曲库同步完成、开关打开、设置页「立即补全」时调用。
     * @param force 设置页「立即补全」：开关关着也跑、不受当天上限、忽略失败暂停
     */
    fun kick(reason: String, force: Boolean = false, delayMs: Long = 8_000L) {
        val ctx = appContext ?: return
        if (!force && !isEnabled(ctx)) return
        if (force) {
            ignoreCapThisRun = true
            pausedUntil = 0L
        }
        if (job?.isActive == true) return
        Log.i(TAG, "kick: $reason")
        job = scope.launch {
            delay(delayMs)
            runCatching { runLoop(ctx, force) }.onFailure { Log.w(TAG, "prematch loop failed", it) }
            ignoreCapThisRun = false
            _progress.value = _progress.value.copy(running = false)
        }
    }

    private fun status(text: String) {
        _progress.value = _progress.value.copy(status = text)
    }

    private fun hasLocalFile(row: AccelCandidateRow): Boolean {
        val path = row.localFilePath
        if (path.isNullOrBlank()) return false
        if (path.startsWith("content://")) return true
        return runCatching { File(path).let { it.isFile && it.length() > 0 } }.getOrDefault(false)
    }

    private suspend fun runLoop(ctx: Context, force: Boolean) {
        val now0 = System.currentTimeMillis()
        if (!force && pausedUntil > now0) {
            status("搜索连续失败，稍后自动重试")
            return
        }
        val db = ZdsDatabase.getInstance(ctx)
        val server = db.serverDao().getActiveServer()?.takeIf { it.type == ServerType.LEMON_MUSIC }
            ?: db.serverDao().getAllServers().firstOrNull { it.type == ServerType.LEMON_MUSIC }
        if (server == null) {
            status("未登录柠檬服务器")
            return
        }
        if (!SourceAccelerationController.localSourceConfigured()) {
            status("本地音源未启用")
            return
        }
        ServerTrackAccelStore.ensureLegacyImported()
        val platforms = ServerLibraryAccelerationResolver.platformOrder(server.id, requireResolvable = false)
        if (platforms.isEmpty()) {
            status("没有可搜索的平台")
            return
        }

        val accelDao = db.serverTrackAccelDao()
        val candidates = db.songDao().getAccelCandidates(listOf(server.id, "lemon_music"))
            .filter { it.title.isNotBlank() }
        val rows = accelDao.statusRows(server.id).associateBy { it.songId }
        val now = System.currentTimeMillis()

        // 已下载到本机的歌跳过（播放时直接播本地文件，不需要平台对应）
        val pool = candidates.filterNot { hasLocalFile(it) }
        val poolIds = pool.mapTo(HashSet()) { it.id }

        fun needsMatch(c: AccelCandidateRow): Boolean {
            val r = rows[c.id] ?: return true
            val song = c.toUnifiedSong()
            val sameFile = r.fileKey.isBlank() || ServerTrackAccelStore.fileKeyOf(song).let { it.isBlank() || it == r.fileKey }
            if (!sameFile) return true
            return when (r.matchStatus) {
                ServerTrackAccelStore.STATUS_MATCHED -> false
                ServerTrackAccelStore.STATUS_NOT_FOUND -> now - r.lastTriedAt >= ServerTrackAccelStore.NOT_FOUND_RETRY_MS
                else -> true
            }
        }

        val matchedCount = pool.count { c -> rows[c.id]?.matchStatus == ServerTrackAccelStore.STATUS_MATCHED }
        val notFoundCount = pool.count { c -> rows[c.id]?.matchStatus == ServerTrackAccelStore.STATUS_NOT_FOUND }
        _progress.value = _progress.value.copy(
            total = pool.size, matched = matchedCount, notFound = notFoundCount,
            todayCount = todayCount(ctx), enabled = isEnabled(ctx)
        )

        // 排序：待播队列 → 最近播放 → 收藏 → 歌单里的歌 → 其余
        val byId = pool.associateBy { it.id }
        val ordered = LinkedHashSet<String>()
        runCatching { PlaybackQueueManager.playlistFlow.value }.getOrDefault(emptyList())
            .forEach { if (it.id in poolIds) ordered += it.id }
        pool.filter { it.lastPlayedTimestamp > 0 }.sortedByDescending { it.lastPlayedTimestamp }.forEach { ordered += it.id }
        pool.filter { it.isFavorite }.forEach { ordered += it.id }
        runCatching { db.playlistDao().getAllPlaylistSongIds() }.getOrDefault(emptyList())
            .forEach { if (it in poolIds) ordered += it }
        pool.forEach { ordered += it.id }

        val todo = ordered.mapNotNull { byId[it] }.filter { needsMatch(it) }
        if (todo.isEmpty()) {
            status(if (pool.isEmpty()) "曲库为空" else "已全部完成")
            return
        }
        Log.i(TAG, "prematch start: todo=${todo.size} pool=${pool.size} platforms=$platforms force=$force")
        _progress.value = _progress.value.copy(running = true, status = "正在补全")

        var matched = matchedCount
        var notFound = notFoundCount
        var failStreak = 0
        for (c in todo) {
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) break
            if (!force && !isEnabled(ctx)) {
                status("已关闭")
                break
            }
            if (!ignoreCapThisRun && todayCount(ctx) >= DAILY_LIMIT) {
                status("今天已补全 $DAILY_LIMIT 首（每天上限），明天继续")
                break
            }
            if (!NetworkScopeDetector.isOnline(ctx)) {
                status("没有网络，联网后继续")
                break
            }
            // 让路：正在取链（播放 / 预取）时等它结束，不和播放抢网络
            var waited = 0L
            while (PlaybackRouter.activeResolves.get() > 0 && waited < YIELD_MAX_MS) {
                delay(500L)
                waited += 500L
            }

            val song = c.toUnifiedSong()
            val result = CrossPlatformMatcher.findSequential(song, platforms, PER_PLATFORM_TIMEOUT_MS)
            val wasNotFound = rows[c.id]?.matchStatus == ServerTrackAccelStore.STATUS_NOT_FOUND
            when {
                result.match != null -> {
                    ServerTrackAccelStore.recordMatch(server.id, song, result.match, result.score)
                    matched++
                    if (wasNotFound) notFound--
                    failStreak = 0
                }
                result.anySearchOk -> {
                    ServerTrackAccelStore.recordNotFound(server.id, song)
                    if (!wasNotFound) notFound++
                    failStreak = 0
                }
                else -> {
                    failStreak++
                    if (failStreak >= FAIL_STREAK_LIMIT) {
                        pausedUntil = System.currentTimeMillis() + FAIL_PAUSE_MS
                        status("搜索连续失败，1 小时后自动重试")
                        Log.w(TAG, "prematch paused: $failStreak consecutive search failures")
                        scope.launch {
                            delay(FAIL_PAUSE_MS + 5_000L)
                            kick("resume_after_pause")
                        }
                        break
                    }
                }
            }
            val day = addTodayCount(ctx)
            _progress.value = _progress.value.copy(
                matched = matched, notFound = notFound.coerceAtLeast(0), todayCount = day, running = true
            )
            delay(GAP_MS)
        }
        if (_progress.value.status == "正在补全") {
            status(if (matched + notFound >= pool.size) "已全部完成" else "")
        }
        Log.i(TAG, "prematch end: matched=$matched notFound=$notFound total=${pool.size}")
    }

    /** 设置页打开时刷新一次计数（不启动搜索） */
    fun refreshCounts() {
        val ctx = appContext ?: return
        scope.launch {
            runCatching {
                val db = ZdsDatabase.getInstance(ctx)
                val server = db.serverDao().getActiveServer()?.takeIf { it.type == ServerType.LEMON_MUSIC }
                    ?: db.serverDao().getAllServers().firstOrNull { it.type == ServerType.LEMON_MUSIC }
                    ?: return@runCatching
                val pool = db.songDao().getAccelCandidates(listOf(server.id, "lemon_music"))
                    .filter { it.title.isNotBlank() && !hasLocalFile(it) }
                val rows = db.serverTrackAccelDao().statusRows(server.id).associateBy { it.songId }
                _progress.value = _progress.value.copy(
                    enabled = isEnabled(ctx),
                    total = pool.size,
                    matched = pool.count { rows[it.id]?.matchStatus == ServerTrackAccelStore.STATUS_MATCHED },
                    notFound = pool.count { rows[it.id]?.matchStatus == ServerTrackAccelStore.STATUS_NOT_FOUND },
                    todayCount = todayCount(ctx)
                )
            }
        }
    }
}
