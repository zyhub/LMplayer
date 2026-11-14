package com.lm.player.core.model

/** 批量加入歌单时每首歌的结果（2026-11-14） */
enum class PlaylistAddStatus { ADDED, EXISTED, FAILED }

data class PlaylistAddOutcome(
    val song: UnifiedSong,
    val status: PlaylistAddStatus,
    /** 已存在 / 失败的原因说明 */
    val reason: String = ""
)
