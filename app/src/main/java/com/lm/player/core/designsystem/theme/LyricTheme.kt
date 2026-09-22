package com.lm.player.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * 现代高保真歌词主题预设 (调研主流音乐流媒体与车机视听场景设计)
 */
enum class LyricTheme(
    val id: String,
    val displayName: String,
    val description: String,
    val activeColorDark: Color,
    val inactiveColorDark: Color,
    val activeColorLight: Color,
    val inactiveColorLight: Color,
    val glowColor: Color,
    val previewGradient: List<Color>
) {
    APPLE_MUSIC(
        id = "apple_music",
        displayName = "Apple 灵动光彩",
        description = "纯白柔光呼吸，未高亮 40% 柔白半透明",
        activeColorDark = Color(0xFFFFFFFF),
        inactiveColorDark = Color(0x66FFFFFF),
        activeColorLight = Color(0xFF1C1C1E),
        inactiveColorLight = Color(0x551C1C1E),
        glowColor = Color(0x44FFFFFF),
        previewGradient = listOf(Color(0xFFFA2D48), Color(0xFFFF5E3A))
    ),
    AURORA_CYBER(
        id = "aurora_cyber",
        displayName = "极光星云",
        description = "冰蓝极光青色高亮，深邃科技感",
        activeColorDark = Color(0xFF00F2FE),
        inactiveColorDark = Color(0x5500F2FE),
        activeColorLight = Color(0xFF007AFF),
        inactiveColorLight = Color(0x55007AFF),
        glowColor = Color(0x4400F2FE),
        previewGradient = listOf(Color(0xFF00F2FE), Color(0xFF4FACFE))
    ),
    SUNSET_GOLD(
        id = "sunset_gold",
        displayName = "落日暖金",
        description = "奢华金珀暖光高亮，优雅温润",
        activeColorDark = Color(0xFFFFB300),
        inactiveColorDark = Color(0x55FFB300),
        activeColorLight = Color(0xFFD97706),
        inactiveColorLight = Color(0x55D97706),
        glowColor = Color(0x44FFB300),
        previewGradient = listOf(Color(0xFFFFB300), Color(0xFFFF5722))
    ),
    EMERALD_NEON(
        id = "emerald_neon",
        displayName = "薄荷极客",
        description = "荧光翡翠青绿高亮，灵动鲜明",
        activeColorDark = Color(0xFF10B981),
        inactiveColorDark = Color(0x5510B981),
        activeColorLight = Color(0xFF059669),
        inactiveColorLight = Color(0x55059669),
        glowColor = Color(0x4410B981),
        previewGradient = listOf(Color(0xFF10B981), Color(0xFF06B6D4))
    ),
    CYBER_VIOLET(
        id = "cyber_violet",
        displayName = "紫罗兰之夜",
        description = "霓虹梦幻紫粉高亮，浪漫微醺",
        activeColorDark = Color(0xFFE879F9),
        inactiveColorDark = Color(0x55E879F9),
        activeColorLight = Color(0xFF9333EA),
        inactiveColorLight = Color(0x559333EA),
        glowColor = Color(0x44E879F9),
        previewGradient = listOf(Color(0xFFE879F9), Color(0xFFA855F7))
    ),
    HIGH_CONTRAST(
        id = "high_contrast",
        displayName = "车机高对比",
        description = "专为行车强光防眩调优，100% 极速辨识",
        activeColorDark = Color(0xFFFFFFFF),
        inactiveColorDark = Color(0x33FFFFFF),
        activeColorLight = Color(0xFF000000),
        inactiveColorLight = Color(0x33000000),
        glowColor = Color(0x66FFFFFF),
        previewGradient = listOf(Color(0xFFFFFFFF), Color(0xFF8E8E93))
    );

    companion object {
        fun fromId(id: String): LyricTheme {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: APPLE_MUSIC
        }
    }
}
