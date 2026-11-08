package com.lm.player.core.widget

/**
 * 桌面小组件 UI 数据约定（Antigravity 出设计稿、Claude 写 RemoteViews 实现，双方以此为准）。
 *
 * 设计稿里出现的每个元素都必须能由下列字段之一驱动；没有字段支撑的元素（如实时进度条、
 * 音频频谱、在线状态）不会被实现。新增字段请在交接说明里提出，由 Claude 统一修改本文件。
 *
 * 双端（手机 / TV 车机）逐字一致：在手机端修改后复制到 TV 端。
 */
enum class WidgetSize(
    /** 桌面网格：列 × 行 */
    val cols: Int,
    val rows: Int,
    /** 歌词行数上限（0 = 不显示歌词）；实际行数在桌面高度放不下时最少减到 3 行 */
    val maxLyricLines: Int
) {
    S2x1(2, 1, 0),
    S2x2(2, 2, 3),
    S4x2(4, 2, 3),
    S3x3(3, 3, 4)
}

/** 小组件一次完整渲染所需的全部数据 */
data class WidgetUiState(
    /** 歌名；未播放时为应用名「LMPlayer」 */
    val title: String,
    /** 歌手；未播放时为「未在播放」 */
    val artist: String,
    /** 专辑名（可能为空） */
    val album: String = "",
    /** 是否有封面图（无封面时显示应用图标占位） */
    val hasCover: Boolean,
    /** 封面主色 ARGB（无封面或尚未取色时为 null，设计需给出无主色时的默认配色） */
    val accentColor: Int?,
    val isPlaying: Boolean,
    val isFavorite: Boolean,
    /** 系统深色模式 */
    val isNight: Boolean,
    /** 歌词区内容（仅 4x2 及以上）；null 表示该尺寸不显示歌词 */
    val lyrics: WidgetLyrics?
)

/**
 * 歌词窗口：按句切换（不是逐字），只在换句时刷新，不支持逐字高亮动画与滚动动画。
 * [lines] 已按 [WidgetSize.maxLyricLines] 截好，[currentIndex] 指向其中的当前句。
 */
data class WidgetLyrics(
    val lines: List<String>,
    val currentIndex: Int,
    /** 不为 null 时歌词区只显示占位（图标 + 主文案 + 副文案），见 [WidgetPlaceholderKind] */
    val placeholder: WidgetPlaceholderKind? = null
)

/** 歌词占位态（采纳 Antigravity 交接建议 1：强类型，避免按文案字符串匹配） */
enum class WidgetPlaceholderKind(val title: String, val subtitle: String) {
    LOADING("歌词加载中…", "正在匹配曲库最佳滚动字幕"),
    EMPTY("暂无歌词", "纯音乐或暂未收录歌词"),
    IDLE("未在播放", "点击播放按钮开启音乐之旅")
}

/** 小组件可响应的点击（RemoteViews 只能整块点击，不支持滑动 / 长按 / 拖动进度） */
enum class WidgetAction {
    OPEN_APP, TOGGLE_PLAY, PREVIOUS, NEXT, FAVORITE,
    // 榜单切换与单曲点播
    SWITCH_CHART_HOT, SWITCH_CHART_SURGE, SWITCH_CHART_NEW, PLAY_CHART_ITEM,
    // 歌单分类切换与打开
    SWITCH_PLAYLIST_MY, SWITCH_PLAYLIST_REC, OPEN_PLAYLIST,
    // 搜索动作
    OPEN_SEARCH, OPEN_VOICE_SEARCH
}

/** 榜单分类 Tab */
enum class ChartTab(val displayName: String) {
    HOT("热歌榜"),
    SURGE("飙升榜"),
    NEW("新歌榜")
}

/** 榜单单曲条目 UI 数据 */
data class ChartItemUiState(
    val rank: Int,
    val title: String,
    val artist: String,
    val tag: String = "",
    val audioId: String = ""
)

/** 榜单小组件完整 UI 状态 */
data class ChartWidgetUiState(
    val activeTab: ChartTab = ChartTab.HOT,
    val items: List<ChartItemUiState> = emptyList(),
    val updateTime: String = "今日更新"
)

/** 歌单分类 Tab */
enum class PlaylistTab(val displayName: String) {
    MY("我的歌单"),
    RECOMMEND("推荐歌单")
}

/** 歌单卡片 UI 数据 */
data class PlaylistItemUiState(
    val id: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String? = null
)

/** 歌单小组件完整 UI 状态 */
data class PlaylistWidgetUiState(
    val activeTab: PlaylistTab = PlaylistTab.MY,
    val items: List<PlaylistItemUiState> = emptyList()
)

/** 搜索小组件完整 UI 状态 */
data class SearchWidgetUiState(
    val hint: String = "搜索歌曲、歌手、专辑...",
    val hotChips: List<String> = emptyList()
)
