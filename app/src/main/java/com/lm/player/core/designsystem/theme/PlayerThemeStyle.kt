package com.lm.player.core.designsystem.theme

/**
 * 播放界面主题风格预设
 */
enum class PlayerThemeStyle(
    val id: String,
    val displayName: String,
    val description: String
) {
    MODERN(
        id = "modern",
        displayName = "现代极简",
        description = "Apple Music 现代拟态分屏与大封面，优雅大气"
    ),
    IPOD_RETRO(
        id = "ipod_retro",
        displayName = "怀旧专辑 (iPod)",
        description = "经典 3D Cover Flow 滚动卡片流，歌词在卡片下方展示"
    );

    companion object {
        fun fromId(id: String): PlayerThemeStyle {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: MODERN
        }
    }
}
