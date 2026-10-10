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
    val isWhiteTheme: Boolean,
    val karaokeBaseColorDark: Color,
    val karaokeOverlayColorDark: Color,
    val karaokeBaseColorLight: Color,
    val karaokeOverlayColorLight: Color,
    val previewGradient: List<Color>
) {
    APPLE_MUSIC(
        id = "apple_music",
        displayName = "Apple 灵动光彩",
        description = "白色主题高亮，待唱柔白底字，流光覆盖 Apple 灵动红反差色",
        activeColorDark = Color(0xFFFFFFFF),
        inactiveColorDark = Color(0x66FFFFFF),
        activeColorLight = Color(0xFF1C1C1E),
        inactiveColorLight = Color(0x551C1C1E),
        glowColor = Color(0x44FFFFFF),
        isWhiteTheme = true,
        karaokeBaseColorDark = Color(0x66FFFFFF),
        karaokeOverlayColorDark = Color(0xFFFF2D55),
        karaokeBaseColorLight = Color(0x551C1C1E),
        karaokeOverlayColorLight = Color(0xFF1C1C1E),
        previewGradient = listOf(Color(0xFFFA2D48), Color(0xFFFF5E3A))
    ),
    AURORA_CYBER(
        id = "aurora_cyber",
        displayName = "极光星云",
        description = "暗调科技极光青，待唱柔和底字，逐字点亮冰蓝高亮反差色",
        activeColorDark = Color(0xFF00F2FE),
        inactiveColorDark = Color(0x66FFFFFF),
        activeColorLight = Color(0xFF007AFF),
        inactiveColorLight = Color(0x551C1C1E),
        glowColor = Color(0x4400F2FE),
        isWhiteTheme = false,
        karaokeBaseColorDark = Color(0x66FFFFFF),
        karaokeOverlayColorDark = Color(0xFF00F2FE),
        karaokeBaseColorLight = Color(0x551C1C1E),
        karaokeOverlayColorLight = Color(0xFF007AFF),
        previewGradient = listOf(Color(0xFF00F2FE), Color(0xFF4FACFE))
    ),
    SUNSET_GOLD(
        id = "sunset_gold",
        displayName = "落日暖金",
        description = "暗调奢华金珀，待唱柔和底字，逐字点亮暖金高亮反差色",
        activeColorDark = Color(0xFFFFB300),
        inactiveColorDark = Color(0x66FFFFFF),
        activeColorLight = Color(0xFFD97706),
        inactiveColorLight = Color(0x551C1C1E),
        glowColor = Color(0x44FFB300),
        isWhiteTheme = false,
        karaokeBaseColorDark = Color(0x66FFFFFF),
        karaokeOverlayColorDark = Color(0xFFFFB300),
        karaokeBaseColorLight = Color(0x551C1C1E),
        karaokeOverlayColorLight = Color(0xFFD97706),
        previewGradient = listOf(Color(0xFFFFB300), Color(0xFFFF5722))
    ),
    EMERALD_NEON(
        id = "emerald_neon",
        displayName = "薄荷极客",
        description = "暗调灵动薄荷绿，待唱柔和底字，逐字点亮翠绿荧光反差色",
        activeColorDark = Color(0xFF10B981),
        inactiveColorDark = Color(0x66FFFFFF),
        activeColorLight = Color(0xFF059669),
        inactiveColorLight = Color(0x551C1C1E),
        glowColor = Color(0x4410B981),
        isWhiteTheme = false,
        karaokeBaseColorDark = Color(0x66FFFFFF),
        karaokeOverlayColorDark = Color(0xFF10B981),
        karaokeBaseColorLight = Color(0x551C1C1E),
        karaokeOverlayColorLight = Color(0xFF059669),
        previewGradient = listOf(Color(0xFF10B981), Color(0xFF06B6D4))
    ),
    CYBER_VIOLET(
        id = "cyber_violet",
        displayName = "紫罗兰之夜",
        description = "暗调微醺霓虹紫，待唱柔和底字，逐字点亮梦幻粉紫反差色",
        activeColorDark = Color(0xFFE879F9),
        inactiveColorDark = Color(0x66FFFFFF),
        activeColorLight = Color(0xFF9333EA),
        inactiveColorLight = Color(0x551C1C1E),
        glowColor = Color(0x44E879F9),
        isWhiteTheme = false,
        karaokeBaseColorDark = Color(0x66FFFFFF),
        karaokeOverlayColorDark = Color(0xFFE879F9),
        karaokeBaseColorLight = Color(0x551C1C1E),
        karaokeOverlayColorLight = Color(0xFF9333EA),
        previewGradient = listOf(Color(0xFFE879F9), Color(0xFFA855F7))
    ),
    HIGH_CONTRAST(
        id = "high_contrast",
        displayName = "车机高对比",
        description = "行车强光防眩调优，待唱柔和底字，流光覆盖金黄高反差色",
        activeColorDark = Color(0xFFFFFFFF),
        inactiveColorDark = Color(0x66FFFFFF),
        activeColorLight = Color(0xFF000000),
        inactiveColorLight = Color(0x55000000),
        glowColor = Color(0x66FFFFFF),
        isWhiteTheme = true,
        karaokeBaseColorDark = Color(0x66FFFFFF),
        karaokeOverlayColorDark = Color(0xFFFFD600),
        karaokeBaseColorLight = Color(0x55000000),
        karaokeOverlayColorLight = Color(0xFF000000),
        previewGradient = listOf(Color(0xFFFFFFFF), Color(0xFF8E8E93))
    );

    fun getKaraokeBaseColor(isDark: Boolean): Color = if (isDark) karaokeBaseColorDark else karaokeBaseColorLight
    fun getKaraokeOverlayColor(isDark: Boolean): Color = if (isDark) karaokeOverlayColorDark else karaokeOverlayColorLight

    companion object {
        fun fromId(id: String): LyricTheme {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: APPLE_MUSIC
        }
    }
}
