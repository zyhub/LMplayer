package com.lm.player.core.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.lm.player.MainActivity
import com.lm.player.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 桌面热歌榜单小部件（4x2 / 4x4）。
 *
 * 视觉与布局由 Antigravity 实现（无第三方平台图标、无「换一批」）；数据为真实榜单：
 * 按发现页当前平台取「热歌 / 飙升 / 新歌」三个榜单（柠檬服务器优先，无服务器时走本地音源），见 [WidgetDataRepository.loadChart]。
 * - 顶部三 Tab 切换热歌 / 飙升 / 新歌榜。
 * - 点条目或播放圆钮：以整张榜单为播放队列，从该曲开始播放。
 * - 平台没有热度数据，设计稿里的「飙升 +92%」类标签不显示。
 */
object ChartWidgetController {

    const val ACTION_CHART_TAB_HOT = "com.lm.player.action.CHART_TAB_HOT"
    const val ACTION_CHART_TAB_SURGE = "com.lm.player.action.CHART_TAB_SURGE"
    const val ACTION_CHART_TAB_NEW = "com.lm.player.action.CHART_TAB_NEW"
    const val ACTION_CHART_PLAY_ITEM = "com.lm.player.action.CHART_PLAY_ITEM"
    const val ACTION_CHART_REFRESH = "com.lm.player.action.CHART_REFRESH"
    const val EXTRA_SONG_TITLE = "extra_song_title"
    const val EXTRA_SONG_ARTIST = "extra_song_artist"
    const val EXTRA_SONG_RANK = "extra_song_rank"
    const val EXTRA_CHART_TAB = "extra_chart_tab"

    private const val KEY_TAB = "chart_tab"
    private const val RETRY_GAP_MS = 60_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * @param chromeDp 列表以外的固定占高（内边距 + Tab 栏），用于按实际高度算每行高度
     */
    private class Spec(val provider: Class<out AppWidgetProvider>, val layoutId: Int, val maxItems: Int, val chromeDp: Int)

    private val specs = listOf(
        Spec(ChartWidgetProvider4x2::class.java, R.layout.widget_chart_4x2, 4, chromeDp = 54),
        Spec(ChartWidgetProvider4x4::class.java, R.layout.widget_chart_4x4, 8, chromeDp = 66)
    )

    private val itemIds = intArrayOf(
        R.id.chart_item_1, R.id.chart_item_2, R.id.chart_item_3, R.id.chart_item_4,
        R.id.chart_item_5, R.id.chart_item_6, R.id.chart_item_7, R.id.chart_item_8
    )
    private val rankIds = intArrayOf(
        R.id.chart_rank_1, R.id.chart_rank_2, R.id.chart_rank_3, R.id.chart_rank_4,
        R.id.chart_rank_5, R.id.chart_rank_6, R.id.chart_rank_7, R.id.chart_rank_8
    )
    private val titleIds = intArrayOf(
        R.id.chart_title_1, R.id.chart_title_2, R.id.chart_title_3, R.id.chart_title_4,
        R.id.chart_title_5, R.id.chart_title_6, R.id.chart_title_7, R.id.chart_title_8
    )
    private val artistIds = intArrayOf(
        R.id.chart_artist_1, R.id.chart_artist_2, R.id.chart_artist_3, R.id.chart_artist_4,
        R.id.chart_artist_5, R.id.chart_artist_6, R.id.chart_artist_7, R.id.chart_artist_8
    )
    private val tagIds = intArrayOf(
        R.id.chart_tag_1, R.id.chart_tag_2, R.id.chart_tag_3, R.id.chart_tag_4,
        R.id.chart_tag_5, R.id.chart_tag_6, R.id.chart_tag_7, R.id.chart_tag_8
    )
    private val playIds = intArrayOf(
        R.id.chart_play_1, R.id.chart_play_2, R.id.chart_play_3, R.id.chart_play_4,
        R.id.chart_play_5, R.id.chart_play_6, R.id.chart_play_7, R.id.chart_play_8
    )

    /** 正在拉取的榜单与最近一次失败时间（失败后 1 分钟内不自动重试，避免断网时反复请求） */
    private val loading = mutableSetOf<ChartTab>()
    private val lastFailAt = mutableMapOf<ChartTab, Long>()
    @Volatile private var noSource = false

    fun currentTab(context: Context): ChartTab = runCatching {
        ChartTab.valueOf(WidgetDataRepository.prefs(context).getString(KEY_TAB, ChartTab.HOT.name) ?: ChartTab.HOT.name)
    }.getOrDefault(ChartTab.HOT)

    private fun setTab(context: Context, tab: ChartTab) {
        WidgetDataRepository.prefs(context).edit().putString(KEY_TAB, tab.name).apply()
    }

    fun handleAction(context: Context, intent: Intent, pending: BroadcastReceiver.PendingResult?) {
        val appCtx = context.applicationContext
        when (intent.action) {
            ACTION_CHART_TAB_HOT -> { setTab(appCtx, ChartTab.HOT); updateAll(appCtx) }
            ACTION_CHART_TAB_SURGE -> { setTab(appCtx, ChartTab.SURGE); updateAll(appCtx) }
            ACTION_CHART_TAB_NEW -> { setTab(appCtx, ChartTab.NEW); updateAll(appCtx) }
            ACTION_CHART_REFRESH -> {
                val tab = currentTab(appCtx)
                synchronized(lastFailAt) { lastFailAt.remove(tab) }
                fetch(appCtx, tab, force = true)
                updateAll(appCtx, allowFetch = false)
            }
            ACTION_CHART_PLAY_ITEM -> {
                val tab = runCatching { ChartTab.valueOf(intent.getStringExtra(EXTRA_CHART_TAB) ?: "") }
                    .getOrDefault(currentTab(appCtx))
                val index = (intent.getIntExtra(EXTRA_SONG_RANK, 1) - 1).coerceAtLeast(0)
                playItem(appCtx, tab, index, pending)
                return
            }
        }
        pending?.finish()
    }

    /** 进程可能已被回收（内存缓存为空）：先补拉榜单再播放 */
    private fun playItem(context: Context, tab: ChartTab, index: Int, pending: BroadcastReceiver.PendingResult?) {
        ioScope.launch {
            try {
                val data = WidgetDataRepository.cachedChart(tab) ?: WidgetDataRepository.loadChart(context, tab)
                val song = data?.songs?.getOrNull(index)
                if (data == null || song == null) {
                    toast(context, "榜单还没有加载出来，请稍后再试")
                    return@launch
                }
                toast(context, "正在播放：${song.title}")
                WidgetDataRepository.playList(context, data.songs, index)
            } finally {
                pending?.finish()
            }
        }
    }

    private fun toast(context: Context, msg: String) {
        mainHandler.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    fun hasWidgets(context: Context): Boolean {
        val mgr = WidgetDataRepository.widgetManager(context) ?: return false
        return specs.any { idsOf(mgr, context, it).isNotEmpty() }
    }

    private fun idsOf(mgr: AppWidgetManager, context: Context, spec: Spec): IntArray =
        runCatching { mgr.getAppWidgetIds(ComponentName(context, spec.provider)) }.getOrNull() ?: IntArray(0)

    /**
     * 用当前缓存重绘全部榜单小部件；缓存缺失或过期时后台拉取，拉完再重绘一次。
     * @param allowFetch 拉取完成后的重绘传 false，避免失败时循环请求
     */
    fun updateAll(context: Context, allowFetch: Boolean = true) {
        val appCtx = context.applicationContext
        val mgr = WidgetDataRepository.widgetManager(appCtx) ?: return
        val present = specs.map { it to idsOf(mgr, appCtx, it) }.filter { it.second.isNotEmpty() }
        if (present.isEmpty()) return
        val tab = currentTab(appCtx)
        val data = WidgetDataRepository.cachedChart(tab)
        // 逐个实例推送：行高随各实例在桌面上的实际高度计算（竖屏手机格子更高，平板横屏更矮）
        for ((spec, ids) in present) for (id in ids) {
            val rv = buildRemoteViews(appCtx, spec, tab, data)
            WidgetDataRepository.widgetSizeDp(appCtx, mgr, id)?.let { (_, h) -> applyRowHeight(rv, spec, h) }
            runCatching { mgr.updateAppWidget(id, rv) }
        }
        if (allowFetch && !WidgetDataRepository.isChartFresh(tab)) fetch(appCtx, tab, force = false)
    }

    /** 拉取榜单（同一榜单同时只拉一次）；完成后刷新榜单小部件 */
    fun fetch(context: Context, tab: ChartTab, force: Boolean) {
        val appCtx = context.applicationContext
        synchronized(loading) {
            if (tab in loading) return
            val failAt = synchronized(lastFailAt) { lastFailAt[tab] }
            if (!force && failAt != null && System.currentTimeMillis() - failAt < RETRY_GAP_MS) return
            loading += tab
        }
        ioScope.launch {
            val result = runCatching { WidgetDataRepository.loadChart(appCtx, tab, force) }.getOrNull()
            noSource = result == null && !WidgetDataRepository.hasOnlineCatalog(appCtx)
            synchronized(lastFailAt) {
                if (result == null) lastFailAt[tab] = System.currentTimeMillis() else lastFailAt.remove(tab)
            }
            synchronized(loading) { loading -= tab }
            mainHandler.post {
                updateAll(appCtx, allowFetch = false)
            }
        }
    }

    fun isLoading(tab: ChartTab) = synchronized(loading) { tab in loading }

    /**
     * 行高 = 可用高度 / 行数，限制在 26~54dp：高度富余时各行保持紧凑并在列表区垂直居中，
     * 不会像按权重平分那样把每行拉得很高（竖屏手机上 4x2 / 4x4 被拉伸的根因）。
     */
    private fun applyRowHeight(rv: RemoteViews, spec: Spec, heightDp: Int) {
        val row = ((heightDp - spec.chromeDp).toFloat() / spec.maxItems).coerceIn(26f, 54f)
        for (i in 0 until spec.maxItems) WidgetDataRepository.setViewSizeDp(rv, itemIds[i], null, row)
    }

    private fun buildRemoteViews(
        context: Context,
        spec: Spec,
        tab: ChartTab,
        data: WidgetDataRepository.ChartData?
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, spec.layoutId)
        val pFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val openApp = Intent(context, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        rv.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 300, openApp, pFlags))

        // Tab（仅 4x2 / 4x4 布局里有，其它布局里这些调用会被忽略）
        val activeColor = context.getColor(R.color.widget_tab_active)
        val inactiveColor = context.getColor(R.color.widget_tab_inactive)
        listOf(
            Triple(R.id.tab_chart_hot, ChartTab.HOT, ACTION_CHART_TAB_HOT),
            Triple(R.id.tab_chart_surge, ChartTab.SURGE, ACTION_CHART_TAB_SURGE),
            Triple(R.id.tab_chart_new, ChartTab.NEW, ACTION_CHART_TAB_NEW)
        ).forEachIndexed { i, (viewId, t, action) ->
            val active = t == tab
            rv.setInt(viewId, "setBackgroundResource", if (active) R.drawable.bg_widget_tab_active else R.drawable.bg_widget_tab_inactive)
            rv.setTextColor(viewId, if (active) activeColor else inactiveColor)
            val intent = Intent(context, ChartWidgetActionReceiver::class.java).setAction(action)
            rv.setOnClickPendingIntent(viewId, PendingIntent.getBroadcast(context, 301 + i, intent, pFlags))
        }

        // 「Top N · 更新时间」
        rv.setTextViewText(R.id.chart_update_time, "Top ${spec.maxItems} · ${data?.updateText ?: "今日更新"}")

        val songs = data?.songs.orEmpty()
        if (songs.isEmpty()) {
            // 占位：第一行显示状态文案，点击重试
            val (title, sub) = when {
                isLoading(tab) || (data == null && synchronized(lastFailAt) { lastFailAt[tab] } == null) ->
                    "正在加载${tab.displayName}…" to "首次加载需要几秒钟"
                noSource -> "暂无榜单数据" to "需要连接柠檬服务器或启用本地音源"
                else -> "${tab.displayName}加载失败" to "点击重试"
            }
            val refresh = Intent(context, ChartWidgetActionReceiver::class.java).setAction(ACTION_CHART_REFRESH)
            val refreshPending = PendingIntent.getBroadcast(context, 309, refresh, pFlags)
            for (i in 0 until spec.maxItems) {
                if (i == 0) {
                    rv.setViewVisibility(itemIds[0], View.VISIBLE)
                    rv.setTextViewText(rankIds[0], "")
                    rv.setTextViewText(titleIds[0], title)
                    rv.setTextViewText(artistIds[0], sub)
                    rv.setViewVisibility(tagIds[0], View.GONE)
                    rv.setViewVisibility(playIds[0], View.INVISIBLE)
                    rv.setOnClickPendingIntent(itemIds[0], refreshPending)
                } else {
                    rv.setViewVisibility(itemIds[i], View.INVISIBLE)
                    rv.setViewVisibility(playIds[i], View.INVISIBLE)
                }
            }
            return rv
        }

        for (i in 0 until spec.maxItems) {
            val song = songs.getOrNull(i)
            if (song == null) {
                rv.setViewVisibility(itemIds[i], View.INVISIBLE)
                rv.setViewVisibility(playIds[i], View.INVISIBLE)
                continue
            }
            rv.setViewVisibility(itemIds[i], View.VISIBLE)
            rv.setViewVisibility(playIds[i], View.VISIBLE)
            rv.setTextViewText(rankIds[i], (i + 1).toString())
            rv.setTextViewText(titleIds[i], song.title.ifBlank { "未知歌曲" })
            rv.setTextViewText(artistIds[i], song.artist.ifBlank { "未知歌手" })
            rv.setViewVisibility(tagIds[i], View.GONE)

            val play = Intent(context, ChartWidgetActionReceiver::class.java).apply {
                action = ACTION_CHART_PLAY_ITEM
                putExtra(EXTRA_SONG_RANK, i + 1)
                putExtra(EXTRA_SONG_TITLE, song.title)
                putExtra(EXTRA_SONG_ARTIST, song.artist)
                putExtra(EXTRA_CHART_TAB, tab.name)
            }
            val playPending = PendingIntent.getBroadcast(context, 310 + i, play, pFlags)
            rv.setOnClickPendingIntent(itemIds[i], playPending)
            rv.setOnClickPendingIntent(playIds[i], playPending)
        }
        return rv
    }
}

/** 榜单 Tab / 点播 / 重试广播接收器（异步播放用 goAsync 保活） */
class ChartWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ChartWidgetController.handleAction(context, intent, goAsync())
    }
}

/** 所有榜单尺寸共用：系统刷新 / 首次添加时用缓存重绘，缓存过期则后台拉取 */
open class BaseChartWidgetProvider : AppWidgetProvider() {
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle) {
        ChartWidgetController.updateAll(context, allowFetch = false)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        ChartWidgetController.updateAll(context)
    }

    override fun onEnabled(context: Context) {
        ChartWidgetController.updateAll(context)
    }
}

/** 4x2 热歌榜小部件（横向 Top 4） */
class ChartWidgetProvider4x2 : BaseChartWidgetProvider()

/** 4x4 热歌榜大部件（总榜 Top 8） */
class ChartWidgetProvider4x4 : BaseChartWidgetProvider()
