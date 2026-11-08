package com.lm.player.core.source.health

/**
 * 音源状态监测：界面数据约定（UI 与逻辑之间唯一的接口）。
 *
 * 分工：
 * - 逻辑层（core/source，由 Claude 实现）负责统计、检测、排序，并产出 [SourceHealthScreenUi]；
 * - 界面层（feature/settings/sourcehealth，由 Antigravity 设计实现）只负责把它画出来，
 *   用户操作一律通过 [SourceHealthActions] 回调交给逻辑层。
 *
 * 规则：
 * - 界面层不得直接访问 SourceAccelerationSession / 数据库 / 网络 / 协程；
 * - 本文件是双方约定，任何一方要改字段，先在交接说明里提出，确认后由 Claude 修改；
 * - 手机端与 TV 端两份文件内容保持完全一致。
 */

/** 顶部状态行：本地加速的总体状态 */
enum class AccelOverallState {
    /** 已就绪，按解析顺序加速 */
    READY,

    /** 正在加载音源脚本 */
    LOADING,

    /** 内网且延迟低，按策略服务器优先（加速处于待命） */
    SERVER_PRIORITY,

    /** 唯一音源或全部音源整体失效，已暂停本地加速，全部走服务器（等待自动重试） */
    PAUSED,

    /** 没有可用音源脚本（服务器未启用音源，或脚本全部加载失败） */
    UNAVAILABLE,

    /** 未连接服务器 / 本地模式 */
    OFFLINE
}

/** 音源卡片的整体状态（卡片左侧色条、标题前圆点、状态标签） */
enum class SourceCardStatus {
    /** 全部可用（绿） */
    ALL_OK,

    /** 部分可用（黄）：有平台失败或太慢 */
    PARTIAL,

    /** 全部失败（红） */
    ALL_FAILED,

    /** 未加载（灰）：脚本初始化失败 / 超时被停用，原因见 [SourceCardUi.notLoadedReason] */
    NOT_LOADED,

    /** 本音源正在检测中 */
    CHECKING
}

/** 平台胶囊状态 */
enum class PlatformPillStatus {
    /** 可用且在加速时限内（绿点 + 耗时） */
    OK,

    /** 可用但超出播放加速时限（黄点 + 耗时） */
    SLOW,

    /** 失败（红点 + 「失败」） */
    FAILED,

    /** 已手动关闭（灰，不自动恢复） */
    CLOSED,

    /** 尚未检测 / 没有样本 / 没网跳过（灰） */
    UNTESTED,

    /** 正在检测这个平台 */
    CHECKING
}

/** 「最近 42 次」方块条里每一轮检测的结果 */
enum class RoundResult {
    /** 全部平台可用（绿） */
    ALL_OK,

    /** 部分可用（黄） */
    PARTIAL,

    /** 全部失败（红） */
    ALL_FAILED,

    /** 本轮未检测：没网、已关闭等（灰） */
    SKIPPED
}

/** 失败原因类型（展开「查看失败原因」时显示 [label]） */
enum class FailureKind(val label: String) {
    EMPTY_URL("返回空地址"),
    TIMEOUT("超时"),
    UNREACHABLE("地址打不开"),
    PREVIEW_CLIP("只拿到试听片段"),
    DOWNGRADED("音质被降级"),
    SCRIPT_ERROR("脚本报错"),
    TOO_SLOW("超出加速时限"),
    NO_SAMPLE("没有测试样本"),
    SERVER_DISABLED("服务器已禁用该平台")
}

/** 定时检测频率（设置项） */
enum class CheckInterval(val minutes: Int, val label: String) {
    OFF(0, "关闭"),
    MIN_30(30, "每 30 分钟"),
    HOUR_1(60, "每 1 小时"),
    HOUR_3(180, "每 3 小时")
}

enum class BannerLevel { INFO, WARNING, ERROR }

/** 页面顶部横幅（如：只有 1 个音源的提示、整体失效的提示）。null 表示不显示。 */
data class BannerUi(
    val level: BannerLevel,
    val text: String
)

/** 音源卡片里的一个平台胶囊 */
data class PlatformPillUi(
    /** 平台标识：kw / kg / tx / wy / mg */
    val platformId: String,
    /** 平台显示名：酷我 / 酷狗 / QQ 音乐 / 网易云 / 咪咕 */
    val platformName: String,
    val status: PlatformPillStatus,
    /** OK / SLOW 时为最近一次成功取链耗时（毫秒），其余为 null */
    val latencyMs: Long? = null,
    /** 实际拿到过的最高音质，如「无损」「320k」；未知为 null */
    val bestQualityLabel: String? = null,
    /** 这个平台当前首选的就是本音源 */
    val isPreferred: Boolean = false
)

/** 展开「查看 N 个平台的失败原因」后的一行 */
data class PlatformFailureUi(
    val platformId: String,
    val platformName: String,
    val kind: FailureKind,
    /** 脚本原始报错（已脱敏，最长 60 字），可为空 */
    val message: String = "",
    /** 连续失败次数 */
    val consecutiveFailures: Int = 0,
    /** 最近一次失败时间（epoch 毫秒） */
    val lastFailedAt: Long = 0L,
    /** 下次自动重试时间（epoch 毫秒）；null 表示「下一轮检测时」 */
    val nextRetryAt: Long? = null,
    /** 已被手动关闭（不自动恢复） */
    val manuallyClosed: Boolean = false
)

/** 一个音源（脚本）的卡片 */
data class SourceCardUi(
    val sourceId: String,
    val name: String,
    val version: String = "",
    val author: String = "",
    /** 音源简介（来自服务器），可能较长，界面需截断 */
    val description: String = "",
    val status: SourceCardStatus,
    /** 平台胶囊，顺序即显示顺序 */
    val platforms: List<PlatformPillUi>,
    /** 最近检测轮次，按时间从旧到新，最多 [MAX_ROUNDS] 个；不足时界面在左侧补灰色 */
    val recentRounds: List<RoundResult>,
    /** 「最近 42 次 · x/y 平台」中的 x */
    val availablePlatformCount: Int,
    /** 「最近 42 次 · x/y 平台」中的 y */
    val totalPlatformCount: Int,
    /** 失败平台的详情；为空时不显示「查看失败原因」 */
    val failures: List<PlatformFailureUi> = emptyList(),
    /** [SourceCardStatus.NOT_LOADED] 时的原因 */
    val notLoadedReason: String? = null
) {
    companion object {
        const val MAX_ROUNDS = 42
    }
}

enum class ResolveStepKind { SOURCE, SERVER }

/** 解析顺序里的一步 */
data class ResolveStepUi(
    val kind: ResolveStepKind,
    /** 音源名；kind = SERVER 时为「服务器」 */
    val label: String,
    /** 附注，如「慢」 */
    val note: String? = null,
    /** 被跳过（失败 / 已关闭 / 太慢），界面应弱化或划掉 */
    val skipped: Boolean = false,
    val skipReason: String? = null
)

/** 一个平台的解析顺序，如「酷狗：C → D → 服务器」 */
data class ResolveOrderUi(
    val platformId: String,
    val platformName: String,
    val steps: List<ResolveStepUi>
)

/** 整个音源状态区的数据 */
data class SourceHealthScreenUi(
    val overall: AccelOverallState,
    /** 顶部状态行的一句话，如「本地加速已就绪」 */
    val overallText: String,
    val serverLatencyMs: Long? = null,
    /** 上次检测时间（epoch 毫秒），null = 从未检测 */
    val lastCheckAt: Long? = null,
    /** 下次自动检测时间（epoch 毫秒），null = 不自动检测 */
    val nextCheckAt: Long? = null,
    val interval: CheckInterval = CheckInterval.HOUR_1,
    /** 「立即检测」进行中 */
    val isCheckingAll: Boolean = false,
    val banner: BannerUi? = null,
    /** 解析顺序；只有 1 个音源时可为空，界面改为显示单音源提示 */
    val resolveOrder: List<ResolveOrderUi> = emptyList(),
    val sources: List<SourceCardUi> = emptyList(),
    /** 计算「xx 分钟前」用的当前时间；预览里传固定值，保证预览稳定 */
    val now: Long = System.currentTimeMillis()
) {
    val isSingleSource: Boolean get() = sources.size == 1
}

/** 界面上的全部操作，由逻辑层实现 */
class SourceHealthActions(
    val onCheckAll: () -> Unit = {},
    val onCheckSource: (sourceId: String) -> Unit = {},
    val onSetPlatformEnabled: (sourceId: String, platformId: String, enabled: Boolean) -> Unit = { _, _, _ -> },
    val onCloseFailedPlatforms: (sourceId: String) -> Unit = {},
    /** sourceId 为 null 表示重置全部音源的统计 */
    val onResetStats: (sourceId: String?) -> Unit = {},
    val onIntervalChange: (CheckInterval) -> Unit = {}
)
