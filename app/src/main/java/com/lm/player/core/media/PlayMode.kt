package com.lm.player.core.media

/**
 * 播放模式：界面数据约定（UI 与逻辑之间唯一的接口，仅手机端）。
 *
 * 原先播放页有「随机」「循环」两个独立开关，现合并为一个三态按钮，点击按
 * 列表循环 → 单曲循环 → 随机播放 → 列表循环 依次切换。
 *
 * 分工：
 * - 逻辑层（PlaybackQueueManager，由 Claude 实现）负责持久化与切歌规则，对外提供
 *   `PlaybackQueueManager.playModeFlow` 与 `PlaybackQueueManager.cyclePlayMode()`；
 * - 界面层（feature/player/controls，由 Antigravity 设计实现）只根据 [PlayMode] 画出图标/文字，
 *   点击时调用宿主传入的 onClick，不得自行修改模式或访问 PlaybackQueueManager。
 *
 * 规则：本文件是双方约定，要改字段先在交接说明里提出，由 Claude 修改。
 */
enum class PlayMode(
    /** 完整名称，用于无障碍描述（contentDescription）与切换提示 */
    val label: String,
    /** 短名称，用于空间紧张的胶囊按钮（怀旧主题横屏底栏） */
    val shortLabel: String
) {
    /** 列表循环：按队列顺序播放，最后一首播完回到第一首（默认模式） */
    LIST_LOOP("列表循环", "循环"),

    /** 单曲循环：当前歌曲播完重新播放；手动点「下一首」仍会切到下一首 */
    REPEAT_ONE("单曲循环", "单曲"),

    /** 随机播放：从队列中随机挑选下一首 */
    SHUFFLE("随机播放", "随机");

    /** 点击三态按钮后切换到的模式 */
    fun next(): PlayMode = entries[(ordinal + 1) % entries.size]

    companion object {
        /** 由旧版两个开关换算（旧版两者同时开启时，实际生效的是单曲循环） */
        fun fromLegacy(isShuffle: Boolean, isRepeat: Boolean): PlayMode = when {
            isRepeat -> REPEAT_ONE
            isShuffle -> SHUFFLE
            else -> LIST_LOOP
        }

        fun fromKey(key: String?): PlayMode? = entries.firstOrNull { it.name == key }
    }
}
