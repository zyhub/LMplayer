package com.lm.player.core.podcast

/**
 * 播客：界面数据约定（UI 与逻辑之间唯一的接口）。
 * 手机端与 TV 车机端两份文件逐字一致（在手机端修改后复制到 TV 端）；
 * 两端界面各自设计（TV 端在 feature/podcast/ 下另做大屏界面），数据与操作完全相同。
 *
 * 数据来源：柠檬服务端 lemon-muisc 的 /api/podcasts（RSS 订阅、单集列表、刷新、取消订阅）
 * 与 /api/play/url（source=podcast 取播放地址）；收听进度、已听完、倍速保存在本机；
 * 「搜索播客」使用 Apple 播客公开目录查 RSS 地址，再交给服务器订阅。
 *
 * 分工：
 * - 逻辑层（core/podcast，由 Claude 实现）负责网络、进度记录、排序、文案格式化，产出下列 *Ui 对象；
 * - 界面层（feature/podcast，由 Antigravity 设计实现）只负责把它们画出来，
 *   用户操作一律通过 [PodcastActions] / [PodcastPlayerActions] 回调交给逻辑层。
 *
 * 规则：
 * - 界面层不得直接访问网络 / 数据库 / 协程 / PlaybackQueueManager / SharedPreferences；
 * - 界面只允许保存纯界面状态（输入框文字、展开/收起、弹层是否显示、滚动位置）；
 * - 所有显示用文案（日期、时长、剩余时间）已由逻辑层格式化好，界面直接显示，不要自行换算；
 * - 本文件是双方约定，要改字段先在交接说明里提出，确认后由 Claude 修改。
 */

/** 页面（或页面中某块列表）的加载状态 */
enum class PodcastLoadState {
    /** 首次加载中，且还没有任何可显示的数据（显示骨架屏或加载指示） */
    LOADING,

    /** 数据就绪（可能同时在后台刷新，见各 Ui 的 isRefreshing） */
    READY,

    /** 已连上服务器，但没有内容：主页 = 还没有订阅；节目页 = 该节目没有可播放的音频单集 */
    EMPTY,

    /** 没有配置柠檬服务器或当前连不上，播客功能依赖服务器 → 引导去设置 / 重试 */
    NO_SERVER,

    /** 服务器版本过旧，没有播客接口 → 提示升级服务器 */
    UNSUPPORTED,

    /** 其他错误，原因见对应 Ui 的 message，可重试 */
    ERROR
}

/** 单集的收听状态 */
enum class EpisodePlayState {
    /** 未听 */
    UNPLAYED,

    /** 听了一部分（[PodcastEpisodeUi.progress] 有意义，可显示进度条 / 剩余时间） */
    IN_PROGRESS,

    /** 已听完（或用户手动标记已听），列表中通常弱化显示 */
    PLAYED
}

/** 一个已订阅的节目（RSS 订阅源） */
data class PodcastShowUi(
    val id: String,
    val title: String,
    /** 主播 / 出品方，可能为空 */
    val author: String,
    /** 节目简介（纯文本，可能很长，界面需折叠或截断） */
    val description: String,
    /** 节目封面，可能为空（界面需有占位图） */
    val imageUrl: String,
    /** 服务器上已收录的单集数 */
    val episodeCount: Int,
    /**
     * 更新角标数：用户上次点开该节目之后新发布的单集数（点开节目即清零，刚订阅时为 0）；
     * 0 不显示角标。（2026-11-07 按用户要求由「7 天内未听完」改为此含义）
     */
    val newEpisodeCount: Int,
    /** 如「3 天前更新」；未知时为空串 */
    val updatedText: String,
    /** 服务器最近一次抓取 RSS 失败的原因；空串 = 正常。非空时界面给出轻量提示 */
    val errorText: String = "",
    /** 正在让服务器重新抓取这个节目的 RSS */
    val isRefreshing: Boolean = false,
    /** 该节目在服务器上订阅的 RSS 地址（订阅管理页显示，可复制） */
    val feedUrl: String = "",
    /** 正在从服务器删除这个订阅 */
    val isDeleting: Boolean = false
)

/** 一期单集 */
data class PodcastEpisodeUi(
    val id: String,
    val showId: String,
    val showTitle: String,
    val title: String,
    /** 单集简介 / Shownotes（纯文本，可能很长） */
    val description: String,
    /** 单集封面；没有单集封面时已回退为节目封面；仍可能为空 */
    val imageUrl: String,
    /** 发布时间文案，如「今天」「昨天」「3 天前」「10月2日」「2024年10月2日」；未知为空串 */
    val publishedText: String,
    /** 时长文案，如「1 小时 12 分」「38 分钟」；未知为空串 */
    val durationText: String,
    /** 仅 [EpisodePlayState.IN_PROGRESS] 时有值，如「剩余 23 分钟」；其余为空串 */
    val remainingText: String = "",
    /** 收听进度 0f..1f（IN_PROGRESS 时有意义） */
    val progress: Float = 0f,
    val playState: EpisodePlayState = EpisodePlayState.UNPLAYED,
    /** 7 天内发布且未听完，可显示「新」标记 */
    val isNew: Boolean = false,
    /** 是播放器当前载入的这一集（不论暂停与否） */
    val isCurrent: Boolean = false,
    /** 当前正在播放（isCurrent 且未暂停） */
    val isPlaying: Boolean = false
)

/** 发现页「播客」入口卡片（替换原「私人漫游」卡片） */
data class PodcastEntryUi(
    /** 订阅数；-1 = 尚未加载 / 未连接服务器（卡片显示通用文案） */
    val showCount: Int = -1,
    /** 所有订阅里「新」单集总数；0 不显示 */
    val newEpisodeCount: Int = 0,
    /** 当前正在播放播客（卡片可显示跳动波形，与原私人漫游卡片一致） */
    val isPodcastPlaying: Boolean = false
)

/** 播客主页 */
data class PodcastHomeUi(
    val state: PodcastLoadState,
    /** NO_SERVER / UNSUPPORTED / ERROR 时的说明文字 */
    val message: String = "",
    /** 正在刷新（下拉刷新或「全部拉取最新」进行中），列表照常显示 */
    val isRefreshing: Boolean = false,
    /** 「全部拉取最新」进度，如「正在拉取 3/12」；不在拉取时为空串 */
    val refreshProgressText: String = "",
    /** 继续收听：听了一部分的单集，按最近收听时间倒序，最多 10 个；为空则不显示该区块 */
    val continueListening: List<PodcastEpisodeUi> = emptyList(),
    /** 我的订阅，按最近更新时间倒序 */
    val shows: List<PodcastShowUi> = emptyList(),
    /** 最新单集：汇总所有订阅，按发布时间倒序，最多 30 个 */
    val latestEpisodes: List<PodcastEpisodeUi> = emptyList()
)

enum class EpisodeSort(val label: String) {
    NEWEST_FIRST("最新在前"),
    OLDEST_FIRST("最早在前")
}

enum class EpisodeFilter(val label: String) {
    ALL("全部"),
    UNPLAYED("未听完")
}

/** 节目详情页 */
data class PodcastShowDetailUi(
    val show: PodcastShowUi,
    /** 单集列表的加载状态（EMPTY = 该节目没有可播放的音频单集，或筛选后为空） */
    val state: PodcastLoadState,
    val message: String = "",
    /** 已加载的单集（已按 [sort] 排序、按 [filter] 过滤） */
    val episodes: List<PodcastEpisodeUi> = emptyList(),
    /** 服务器上该节目单集总数 */
    val totalEpisodes: Int = 0,
    val sort: EpisodeSort = EpisodeSort.NEWEST_FIRST,
    val filter: EpisodeFilter = EpisodeFilter.ALL,
    /** 还有更多可分页加载；列表滑到底部时调用 onLoadMore */
    val canLoadMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    /**
     * 主按钮：非 null 时显示「继续收听」并播放这一集（从记忆位置）；
     * null 时显示「播放最新一期」。
     */
    val resumeEpisode: PodcastEpisodeUi? = null
)

/**
 * 订阅管理页：集中添加 / 删除服务器上的 RSS 订阅。
 * 添加 = 服务器 POST /api/podcasts {url}；删除 = 服务器 DELETE /api/podcasts/:id。
 * 在这里添加成功后**留在本页**（新订阅出现在列表顶部），不会跳转到节目详情。
 */
data class PodcastManageUi(
    /** 订阅列表的加载状态（EMPTY = 服务器上还没有订阅） */
    val state: PodcastLoadState,
    val message: String = "",
    /** 服务器上的全部订阅，按添加时间倒序 */
    val shows: List<PodcastShowUi> = emptyList(),
    /** 正在添加（服务器解析 RSS 可能需要十几秒到一分钟） */
    val isAdding: Boolean = false,
    /** 最近一次添加失败的原因（服务器原文），空串 = 无 */
    val addError: String = "",
    /** 最近一次添加成功的节目名，用于「已添加：xxx」提示；空串 = 无 */
    val lastAddedTitle: String = "",
    /** 最近一次删除失败的原因，空串 = 无 */
    val deleteError: String = ""
)

/** 搜索 / 添加订阅的状态 */
enum class PodcastSearchState {
    /** 还没搜索（显示说明：可搜节目名，或直接粘贴 RSS 地址） */
    IDLE,
    SEARCHING,
    RESULTS,
    NO_RESULT,
    ERROR
}

/** 搜索结果中的一个节目（来自 Apple 播客公开目录） */
data class PodcastSearchResultUi(
    /** RSS 地址，作为唯一键 */
    val feedUrl: String,
    val title: String,
    val author: String,
    val imageUrl: String,
    /** 分类，如「喜剧」「科技」；可能为空 */
    val genre: String = "",
    /** 如「共 268 期」；未知为空串 */
    val episodeCountText: String = "",
    /** 已在我的订阅中（按钮显示「已订阅」并禁用） */
    val isSubscribed: Boolean = false,
    /** 正在订阅（服务器解析 RSS 可能需要十几秒到一分钟） */
    val isSubscribing: Boolean = false
)

/** 搜索 / 添加订阅页 */
data class PodcastSearchUi(
    val state: PodcastSearchState = PodcastSearchState.IDLE,
    /** ERROR / NO_RESULT 时的说明 */
    val message: String = "",
    val results: List<PodcastSearchResultUi> = emptyList(),
    /** 正在订阅用户粘贴的 RSS 地址 */
    val isSubscribingUrl: Boolean = false,
    /**
     * 最近一次订阅失败的原因（服务器原文，如「这是网页地址，不是播客 RSS……」），空串 = 无。
     * 订阅成功时逻辑层会自动打开该节目详情页，不需要界面处理。
     */
    val subscribeError: String = ""
)

/** 输入框内容是否像 RSS 地址（是则主按钮改为「订阅此 RSS」，否则为「搜索」） */
fun looksLikeFeedUrl(text: String): Boolean =
    text.trim().let { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }

/** 播客播放页（当前播放的是播客单集时，全屏播放页显示这个界面，替代音乐播放页） */
data class PodcastPlayerUi(
    val episode: PodcastEpisodeUi,
    val showTitle: String,
    /** 大封面：单集封面，回退节目封面，可能为空 */
    val coverUrl: String,
    val isPlaying: Boolean,
    /** 正在缓冲（可在播放键上叠加加载指示） */
    val isBuffering: Boolean = false,
    /** 总时长；0 = 未知（进度条不可拖动，仅显示已播时间） */
    val durationMs: Long = 0L,
    /** 当前倍速，取值见 [PodcastPlayerDefaults.SPEED_OPTIONS] */
    val speed: Float = 1.0f,
    /** 定时关闭：0 = 关闭；-1 = 本集播完停止；>0 = 分钟数 */
    val sleepTimerMinutes: Int = 0,
    /** 定时关闭剩余秒数（sleepTimerMinutes > 0 时有意义） */
    val sleepTimerRemainingSec: Long = 0L,
    /** 待播：同一节目中接下来会自动播放的单集（最多 20 个） */
    val upNext: List<PodcastEpisodeUi> = emptyList(),
    /** 正在投屏 / 输出到外部设备 */
    val isCasting: Boolean = false
)

object PodcastPlayerDefaults {
    /** 后退 15 秒 */
    const val SKIP_BACK_MS = 15_000L

    /** 前进 30 秒 */
    const val SKIP_FORWARD_MS = 30_000L

    /** 倍速选项 */
    val SPEED_OPTIONS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

    /** 定时关闭选项（分钟）；-1 = 本集播完停止 */
    val SLEEP_TIMER_OPTIONS: List<Int> = listOf(15, 30, 45, 60, 90, -1)

    /** 倍速显示文案：1.0 → 「1x」，1.25 → 「1.25x」 */
    fun speedText(speed: Float): String {
        val s = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')
        return "${s}x"
    }
}

/** 播客页面（主页 / 节目页 / 搜索 / 单集详情）的全部操作，由逻辑层实现 */
class PodcastActions(
    // ---------- 导航 ----------
    /** 返回上一级（节目页 → 主页 → 发现页） */
    val onBack: () -> Unit = {},
    val onOpenShow: (showId: String) -> Unit = {},
    /** 打开单集详情（简介 / Shownotes），通常为底部弹层 */
    val onOpenEpisode: (episode: PodcastEpisodeUi) -> Unit = {},
    val onCloseEpisode: () -> Unit = {},
    val onOpenSearch: () -> Unit = {},
    /** NO_SERVER 状态下「去设置服务器」 */
    val onGoToServerSettings: () -> Unit = {},

    // ---------- 播放 ----------
    /**
     * 播放某一集（从记忆位置继续；已听完的从头播）。
     * 如果它就是当前载入的那一集，则切换播放 / 暂停。
     * 自动续播：同一节目中按列表顺序往下播。
     */
    val onPlayEpisode: (episode: PodcastEpisodeUi) -> Unit = {},
    /** 节目页主按钮：resumeEpisode 为 null 时播放最新一期 */
    val onPlayShowLatest: (showId: String) -> Unit = {},
    /** 打开全屏播放页（迷你播放条以外的入口，如「正在播放」标记被点击） */
    val onOpenPlayer: () -> Unit = {},

    // ---------- 刷新与订阅管理 ----------
    /** 下拉刷新：重新读取订阅列表与最新单集（不让服务器重抓 RSS，较快） */
    val onRefresh: () -> Unit = {},
    /** 让服务器重新抓取全部订阅的 RSS（较慢，进度见 refreshProgressText） */
    val onFetchAllShows: () -> Unit = {},
    /** 让服务器重新抓取某个节目的 RSS（节目页「拉取最新」） */
    val onFetchShow: (showId: String) -> Unit = {},
    /** 取消订阅（界面需先二次确认再调用） */
    val onUnsubscribe: (showId: String) -> Unit = {},
    /** 错误状态下的「重试」 */
    val onRetry: () -> Unit = {},

    // ---------- 单集列表 ----------
    val onLoadMore: (showId: String) -> Unit = {},
    val onSortChange: (showId: String, sort: EpisodeSort) -> Unit = { _, _ -> },
    val onFilterChange: (showId: String, filter: EpisodeFilter) -> Unit = { _, _ -> },
    /** 标记已听完 / 未听 */
    val onMarkPlayed: (episode: PodcastEpisodeUi, played: Boolean) -> Unit = { _, _ -> },

    // ---------- 订阅管理（RSS 添加 / 删除，直接作用于服务器） ----------
    val onOpenManage: () -> Unit = {},
    /** 在订阅管理页添加 RSS 地址（url 已 trim）；成功后留在管理页 */
    val onAddRss: (url: String) -> Unit = {},
    /** 从服务器删除一个或多个订阅（界面需先二次确认，提示「该节目的单集与收听记录将一并移除」） */
    val onDeleteShows: (showIds: List<String>) -> Unit = {},
    /** 清除添加 / 删除的错误与成功提示（关闭提示条或重新输入时） */
    val onDismissManageMessage: () -> Unit = {},

    // ---------- 搜索 / 添加订阅 ----------
    /** 搜索节目（Apple 播客目录），query 已 trim 且非空 */
    val onSearch: (query: String) -> Unit = {},
    /** 订阅用户粘贴的 RSS 地址 */
    val onSubscribeUrl: (url: String) -> Unit = {},
    /** 订阅某个搜索结果 */
    val onSubscribeResult: (result: PodcastSearchResultUi) -> Unit = {},
    /** 清空搜索结果与错误（输入框被清空或离开搜索页时） */
    val onClearSearch: () -> Unit = {}
)

/** 播客播放页的全部操作，由逻辑层实现 */
class PodcastPlayerActions(
    val onTogglePlayPause: () -> Unit = {},
    val onSeekTo: (positionMs: Long) -> Unit = {},
    /** 后退 [PodcastPlayerDefaults.SKIP_BACK_MS] */
    val onSkipBack: () -> Unit = {},
    /** 前进 [PodcastPlayerDefaults.SKIP_FORWARD_MS] */
    val onSkipForward: () -> Unit = {},
    val onSetSpeed: (speed: Float) -> Unit = {},
    /** 设置定时关闭，取值见 [PodcastPlayerDefaults.SLEEP_TIMER_OPTIONS]，0 = 关闭 */
    val onSetSleepTimer: (minutes: Int) -> Unit = {},
    /** 播放待播列表中的某一集 */
    val onPlayFromUpNext: (episode: PodcastEpisodeUi) -> Unit = {},
    /** 当前集标记已听完 / 未听 */
    val onMarkPlayed: (played: Boolean) -> Unit = {},
    /** 点节目名：收起播放页并打开该节目详情 */
    val onOpenShow: (showId: String) -> Unit = {},
    /** 收起播放页（下滑手势 / 左上角按钮 / 返回键） */
    val onDismiss: () -> Unit = {}
)
