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
import com.lm.player.core.database.ZdsDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * 桌面歌单小部件（4x2 / 4x4）。
 *
 * 视觉与布局由 Antigravity 实现；数据：
 * - 「我的歌单」= 「我喜欢的音乐」+ 本地数据库里的歌单（与资料库同一份，歌单增删改后自动刷新）；
 * - 「推荐歌单」= 发现页当前平台的推荐歌单（柠檬服务器优先，无服务器时走本地音源）。
 * 点卡片或卡片上的播放圆钮：载入整张歌单并从第一首开始播放。
 * 竖屏手机上改为竖向列表（封面 + 名称一行一条，[isPortraitPhone]），横屏与平板保持宫格。
 */
object PlaylistWidgetController {

    const val ACTION_PLAYLIST_TAB_MY = "com.lm.player.action.PLAYLIST_TAB_MY"
    const val ACTION_PLAYLIST_TAB_REC = "com.lm.player.action.PLAYLIST_TAB_REC"
    const val ACTION_PLAYLIST_OPEN = "com.lm.player.action.PLAYLIST_OPEN"
    const val ACTION_PLAYLIST_REFRESH = "com.lm.player.action.PLAYLIST_REFRESH"
    const val EXTRA_PLAYLIST_ID = "extra_playlist_id"
    const val EXTRA_PLAYLIST_TITLE = "extra_playlist_title"
    const val EXTRA_PLAYLIST_TAB = "extra_playlist_tab"

    private const val KEY_TAB = "playlist_tab"
    private const val RETRY_GAP_MS = 60_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * @param padDp 卡片区左右内边距之和；@param chromeDp 卡片区以外的固定占高（内边距 + Tab 栏 + 行间距）
     */
    private class Spec(
        val provider: Class<out AppWidgetProvider>, val layoutId: Int, val maxCards: Int,
        val rows: Int, val padDp: Int, val chromeDp: Int
    )

    private val specs = listOf(
        Spec(PlaylistWidgetProvider4x2::class.java, R.layout.widget_playlist_4x2, 4, rows = 1, padDp = 24, chromeDp = 58),
        Spec(PlaylistWidgetProvider4x4::class.java, R.layout.widget_playlist_4x4, 8, rows = 2, padDp = 28, chromeDp = 82)
    )

    private val itemIds = intArrayOf(
        R.id.playlist_item_1, R.id.playlist_item_2, R.id.playlist_item_3, R.id.playlist_item_4,
        R.id.playlist_item_5, R.id.playlist_item_6, R.id.playlist_item_7, R.id.playlist_item_8
    )
    private val coverIds = intArrayOf(
        R.id.playlist_cov_1, R.id.playlist_cov_2, R.id.playlist_cov_3, R.id.playlist_cov_4,
        R.id.playlist_cov_5, R.id.playlist_cov_6, R.id.playlist_cov_7, R.id.playlist_cov_8
    )
    private val coverBoxIds = intArrayOf(
        R.id.playlist_cov_box_1, R.id.playlist_cov_box_2, R.id.playlist_cov_box_3, R.id.playlist_cov_box_4,
        R.id.playlist_cov_box_5, R.id.playlist_cov_box_6, R.id.playlist_cov_box_7, R.id.playlist_cov_box_8
    )
    private val titleIds = intArrayOf(
        R.id.playlist_title_1, R.id.playlist_title_2, R.id.playlist_title_3, R.id.playlist_title_4,
        R.id.playlist_title_5, R.id.playlist_title_6, R.id.playlist_title_7, R.id.playlist_title_8
    )
    private val subIds = intArrayOf(
        R.id.playlist_sub_1, R.id.playlist_sub_2, R.id.playlist_sub_3, R.id.playlist_sub_4,
        R.id.playlist_sub_5, R.id.playlist_sub_6, R.id.playlist_sub_7, R.id.playlist_sub_8
    )
    private val playIds = intArrayOf(
        R.id.playlist_play_1, R.id.playlist_play_2, R.id.playlist_play_3, R.id.playlist_play_4,
        R.id.playlist_play_5, R.id.playlist_play_6, R.id.playlist_play_7, R.id.playlist_play_8
    )

    private val loading = mutableSetOf<PlaylistTab>()
    private val lastFailAt = mutableMapOf<PlaylistTab, Long>()
    private val pendingThumbs = mutableSetOf<String>()

    fun currentTab(context: Context): PlaylistTab = runCatching {
        PlaylistTab.valueOf(WidgetDataRepository.prefs(context).getString(KEY_TAB, PlaylistTab.MY.name) ?: PlaylistTab.MY.name)
    }.getOrDefault(PlaylistTab.MY)

    private fun setTab(context: Context, tab: PlaylistTab) {
        WidgetDataRepository.prefs(context).edit().putString(KEY_TAB, tab.name).apply()
    }

    fun handleAction(context: Context, intent: Intent, pending: BroadcastReceiver.PendingResult?) {
        val appCtx = context.applicationContext
        when (intent.action) {
            ACTION_PLAYLIST_TAB_MY -> { setTab(appCtx, PlaylistTab.MY); updateAll(appCtx) }
            ACTION_PLAYLIST_TAB_REC -> { setTab(appCtx, PlaylistTab.RECOMMEND); updateAll(appCtx) }
            ACTION_PLAYLIST_REFRESH -> {
                val tab = currentTab(appCtx)
                synchronized(lastFailAt) { lastFailAt.remove(tab) }
                fetch(appCtx, tab, force = true)
                updateAll(appCtx, allowFetch = false)
            }
            ACTION_PLAYLIST_OPEN -> {
                val id = intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty()
                val title = intent.getStringExtra(EXTRA_PLAYLIST_TITLE) ?: "歌单"
                val tab = runCatching { PlaylistTab.valueOf(intent.getStringExtra(EXTRA_PLAYLIST_TAB) ?: "") }
                    .getOrDefault(currentTab(appCtx))
                onPlaylistItemClick(appCtx, tab, id, title, pending)
                return
            }
        }
        pending?.finish()
    }

    private fun onPlaylistItemClick(
        context: Context,
        tab: PlaylistTab,
        playlistId: String,
        title: String,
        pending: BroadcastReceiver.PendingResult?
    ) {
        if (playlistId.isBlank()) { pending?.finish(); return }
        toast(context, "正在载入歌单「$title」…")
        ioScope.launch {
            try {
                val songs = WidgetDataRepository.loadPlaylistSongs(context, tab, playlistId)
                if (songs.isEmpty()) {
                    toast(context, "歌单「$title」里没有可播放的歌曲")
                    return@launch
                }
                WidgetDataRepository.playList(context, songs, 0)
            } finally {
                pending?.finish()
            }
        }
    }

    private fun toast(context: Context, msg: String) {
        mainHandler.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun idsOf(mgr: AppWidgetManager, context: Context, spec: Spec): IntArray =
        runCatching { mgr.getAppWidgetIds(ComponentName(context, spec.provider)) }.getOrNull() ?: IntArray(0)

    fun hasWidgets(context: Context): Boolean {
        val mgr = WidgetDataRepository.widgetManager(context) ?: return false
        return specs.any { idsOf(mgr, context, it).isNotEmpty() }
    }

    fun updateAll(context: Context, allowFetch: Boolean = true) {
        val appCtx = context.applicationContext
        val mgr = WidgetDataRepository.widgetManager(appCtx) ?: return
        val present = specs.map { it to idsOf(mgr, appCtx, it) }.filter { it.second.isNotEmpty() }
        if (present.isEmpty()) return
        val tab = currentTab(appCtx)
        val items = WidgetDataRepository.cachedPlaylists(appCtx, tab)
        // 逐个实例推送：竖屏手机用竖向列表、横屏 / 平板用宫格；行高、封面边长随各实例在桌面上的实际尺寸计算
        val listMode = isPortraitPhone(appCtx)
        for ((spec, ids) in present) for (id in ids) {
            val size = WidgetDataRepository.widgetSizeDp(appCtx, mgr, id)
            val rv = if (listMode) {
                val avail = (size?.second ?: (spec.maxCards * 44 + LIST_CHROME_DP)) - LIST_CHROME_DP
                val rows = (avail / 34).coerceIn(1, spec.maxCards)
                buildRemoteViews(appCtx, spec, tab, items, R.layout.widget_playlist_list, rows, View.GONE)
                    .also { applyListSize(it, rows, avail) }
            } else {
                buildRemoteViews(appCtx, spec, tab, items, spec.layoutId, spec.maxCards, View.INVISIBLE)
                    .also { rv -> size?.let { (w, h) -> applyCardSize(rv, spec, w, h) } }
            }
            runCatching { mgr.updateAppWidget(id, rv) }
        }
        if (allowFetch && !WidgetDataRepository.isPlaylistFresh(appCtx, tab)) fetch(appCtx, tab, force = false)
        if (items != null) ensureThumbs(appCtx, items)
    }

    private fun fetch(context: Context, tab: PlaylistTab, force: Boolean) {
        synchronized(loading) {
            if (tab in loading) return
            val failAt = synchronized(lastFailAt) { lastFailAt[tab] }
            if (!force && failAt != null && System.currentTimeMillis() - failAt < RETRY_GAP_MS) return
            loading += tab
        }
        ioScope.launch {
            val result = runCatching { WidgetDataRepository.loadPlaylists(context, tab, force) }.getOrNull()
            synchronized(lastFailAt) {
                if (result == null) lastFailAt[tab] = System.currentTimeMillis() else lastFailAt.remove(tab)
            }
            synchronized(loading) { loading -= tab }
            mainHandler.post { updateAll(context, allowFetch = false) }
        }
    }

    /** 补齐卡片封面：全部下载完成后统一重绘一次（每张封面只下载一次） */
    private fun ensureThumbs(context: Context, items: List<PlaylistItemUiState>) {
        val missing = items.take(8).mapNotNull { it.coverUrl }
            .filter { WidgetDataRepository.cachedThumb(it) == null }
            .filter { synchronized(pendingThumbs) { pendingThumbs.add(it) } }
        if (missing.isEmpty()) return
        ioScope.launch {
            var any = false
            for (url in missing) {
                if (WidgetDataRepository.loadThumb(context, url) != null) any = true
            }
            // 失败的封面不移出待加载集合：避免每次重绘都重新下载一张打不开的图
            synchronized(pendingThumbs) {
                missing.filter { WidgetDataRepository.cachedThumb(it) != null }.forEach { pendingThumbs.remove(it) }
            }
            if (any) mainHandler.post { updateAll(context, allowFetch = false) }
        }
    }

    private fun isLoading(tab: PlaylistTab) = synchronized(loading) { tab in loading }

    /**
     * 封面始终是正方形：边长 = min(卡片宽, 行高 − 文字区)，限制在 36~120dp；卡片内容整体居中。
     * 之前封面框按权重同时撑满宽高，竖屏手机格子窄而高时封面变成竖长条被裁切（看起来像被拉伸）。
     */
    private fun applyCardSize(rv: RemoteViews, spec: Spec, widthDp: Int, heightDp: Int) {
        val cols = spec.maxCards / spec.rows
        val cardW = (widthDp - spec.padDp - (cols - 1) * 8f) / cols
        val rowH = (heightDp - spec.chromeDp).toFloat() / spec.rows
        val side = minOf(cardW, rowH - 30f).coerceIn(36f, 120f)
        for (i in 0 until spec.maxCards) {
            WidgetDataRepository.setViewSizeDp(rv, coverBoxIds[i], side, side)
            WidgetDataRepository.setViewSizeDp(rv, titleIds[i], side, null)
            WidgetDataRepository.setViewSizeDp(rv, subIds[i], side, null)
        }
    }

    /** 竖屏手机（最短边 < 600dp）：桌面格子窄而高，歌单改为竖向列表（封面 + 名称一行一条） */
    private fun isPortraitPhone(context: Context): Boolean {
        val c = context.resources.configuration
        return c.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT && c.smallestScreenWidthDp < 600
    }

    /** 列表模式除列表外的固定占高：内边距 + Tab 栏 + 间距 */
    private const val LIST_CHROME_DP = 52

    /** 列表行高 = 可用高度 / 行数（34~56dp），封面随行高（28~44dp）；列表区垂直居中 */
    private fun applyListSize(rv: RemoteViews, rows: Int, availDp: Int) {
        val rowH = (availDp.toFloat() / rows).coerceIn(34f, 56f)
        val cover = (rowH - 8f).coerceIn(28f, 44f)
        for (i in 0 until rows) {
            WidgetDataRepository.setViewSizeDp(rv, itemIds[i], null, rowH)
            WidgetDataRepository.setViewSizeDp(rv, coverBoxIds[i], cover, cover)
        }
    }

    /**
     * @param slots 本次使用的卡片 / 行数；其后的槽位隐藏（列表模式下高度不够时少显示几行）
     * @param emptyVisibility 没有数据的槽位：宫格用 INVISIBLE 保持排版，列表用 GONE 让列表居中
     */
    private fun buildRemoteViews(
        context: Context,
        spec: Spec,
        tab: PlaylistTab,
        items: List<PlaylistItemUiState>?,
        layoutId: Int,
        slots: Int,
        emptyVisibility: Int
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, layoutId)
        val pFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val openApp = Intent(context, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        rv.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 400, openApp, pFlags))

        // Tab（仅 4x2 / 4x4）
        val activeColor = context.getColor(R.color.widget_tab_active)
        val inactiveColor = context.getColor(R.color.widget_tab_inactive)
        listOf(
            Triple(R.id.tab_playlist_my, PlaylistTab.MY, ACTION_PLAYLIST_TAB_MY),
            Triple(R.id.tab_playlist_rec, PlaylistTab.RECOMMEND, ACTION_PLAYLIST_TAB_REC)
        ).forEachIndexed { i, (viewId, t, action) ->
            val active = t == tab
            rv.setInt(viewId, "setBackgroundResource", if (active) R.drawable.bg_widget_tab_active else R.drawable.bg_widget_tab_inactive)
            rv.setTextColor(viewId, if (active) activeColor else inactiveColor)
            val intent = Intent(context, PlaylistWidgetActionReceiver::class.java).setAction(action)
            rv.setOnClickPendingIntent(viewId, PendingIntent.getBroadcast(context, 401 + i, intent, pFlags))
        }

        val list = items.orEmpty()
        for (i in slots until itemIds.size) rv.setViewVisibility(itemIds[i], View.GONE)
        val shown = minOf(slots, list.size)
        rv.setTextViewText(R.id.playlist_header_sub, if (shown > 0) "共 $shown 套" else "")

        if (list.isEmpty()) {
            val (title, sub) = when {
                isLoading(tab) || (items == null && synchronized(lastFailAt) { lastFailAt[tab] } == null) -> "正在加载…" to tab.displayName
                items != null -> "还没有歌单" to "在 App 里创建歌单"
                tab == PlaylistTab.RECOMMEND -> "暂无推荐" to "点击重试"
                else -> "加载失败" to "点击重试"
            }
            val refresh = Intent(context, PlaylistWidgetActionReceiver::class.java).setAction(ACTION_PLAYLIST_REFRESH)
            val refreshPending = PendingIntent.getBroadcast(context, 409, refresh, pFlags)
            for (i in 0 until slots) {
                if (i == 0) {
                    rv.setViewVisibility(itemIds[0], View.VISIBLE)
                    rv.setImageViewResource(coverIds[0], R.drawable.widget_playlist_cover_ph)
                    rv.setTextViewText(titleIds[0], title)
                    rv.setTextViewText(subIds[0], sub)
                    rv.setViewVisibility(playIds[0], View.INVISIBLE)
                    rv.setOnClickPendingIntent(itemIds[0], refreshPending)
                } else {
                    rv.setViewVisibility(itemIds[i], emptyVisibility)
                }
            }
            return rv
        }

        for (i in 0 until slots) {
            val item = list.getOrNull(i)
            if (item == null) {
                rv.setViewVisibility(itemIds[i], emptyVisibility)
                continue
            }
            rv.setViewVisibility(itemIds[i], View.VISIBLE)
            rv.setViewVisibility(playIds[i], View.VISIBLE)
            rv.setTextViewText(titleIds[i], item.title)
            rv.setTextViewText(subIds[i], item.subtitle)
            val thumb = WidgetDataRepository.cachedThumb(item.coverUrl)
            if (thumb != null) rv.setImageViewBitmap(coverIds[i], thumb)
            else rv.setImageViewResource(coverIds[i], R.drawable.widget_playlist_cover_ph)

            val open = Intent(context, PlaylistWidgetActionReceiver::class.java).apply {
                action = ACTION_PLAYLIST_OPEN
                putExtra(EXTRA_PLAYLIST_ID, item.id)
                putExtra(EXTRA_PLAYLIST_TITLE, item.title)
                putExtra(EXTRA_PLAYLIST_TAB, tab.name)
            }
            val openPending = PendingIntent.getBroadcast(context, 410 + i, open, pFlags)
            rv.setOnClickPendingIntent(itemIds[i], openPending)
            rv.setOnClickPendingIntent(playIds[i], openPending)
        }
        return rv
    }

    /**
     * 本地歌单 / 收藏变化时刷新「我的歌单」（由 LMApplication 启动一次）。
     * 只在放置了歌单小部件且当前 Tab 是「我的歌单」时才重读数据库。
     */
    @OptIn(FlowPreview::class)
    fun startObserving(context: Context) {
        val appCtx = context.applicationContext
        ioScope.launch {
            runCatching {
                val db = ZdsDatabase.getInstance(appCtx)
                combine(db.playlistDao().getAllPlaylistsFlow(), db.songDao().getFavoriteSongsFlow()) { pls, favs ->
                    pls.map { Triple(it.id, it.name, it.songCount) } to favs.size
                }.distinctUntilChanged().drop(1).debounce(1_500).collect {
                    WidgetDataRepository.invalidateMyPlaylists()
                    if (currentTab(appCtx) == PlaylistTab.MY && hasWidgets(appCtx)) {
                        mainHandler.post { updateAll(appCtx) }
                    }
                }
            }
        }
    }
}

/** 歌单 Tab / 打开 / 重试广播接收器（异步载入歌单用 goAsync 保活） */
class PlaylistWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PlaylistWidgetController.handleAction(context, intent, goAsync())
    }
}

/** 所有歌单尺寸共用：系统刷新 / 首次添加时重绘 */
open class BasePlaylistWidgetProvider : AppWidgetProvider() {
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle) {
        PlaylistWidgetController.updateAll(context, allowFetch = false)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        PlaylistWidgetController.updateAll(context)
    }

    override fun onEnabled(context: Context) {
        PlaylistWidgetController.updateAll(context)
    }
}

/** 4x2 精选歌单小部件（横向 4 套） */
class PlaylistWidgetProvider4x2 : BasePlaylistWidgetProvider()

/** 4x4 歌单全景大部件（严格 2 行 8 套） */
class PlaylistWidgetProvider4x4 : BasePlaylistWidgetProvider()
