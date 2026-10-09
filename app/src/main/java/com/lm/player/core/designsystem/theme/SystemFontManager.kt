package com.lm.player.core.designsystem.theme

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/**
 * 本机系统字体加载与平滑回退管理器
 * 优先调用本机系统字体 (如小米 MiSans、华为鸿蒙 HarmonyOS Sans、思源黑体、Noto Sans CJK 等)，
 * 当系统权限受限或无法调用时，平滑回退至默认软件字体。
 */
object SystemFontManager {
    private var cachedSystemFontFamily: FontFamily? = null

    fun getSystemFontFamily(useSystemFont: Boolean = true): FontFamily {
        if (!useSystemFont) return FontFamily.Default
        cachedSystemFontFamily?.let { return it }

        val fontCandidates = listOf(
            "/system/fonts/NotoSansSC-VF.ttf",
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/SourceHanSansCN-Regular.otf",
            "/system/fonts/MiSans-Normal.ttf",
            "/system/fonts/HarmonyOS_Sans_SC.ttf",
            "/system/fonts/DroidSansFallback.ttf",
            "/system/fonts/Roboto-Regular.ttf"
        )

        for (path in fontCandidates) {
            try {
                val f = File(path)
                if (f.exists() && f.canRead()) {
                    val tf = Typeface.createFromFile(f)
                    if (tf != null) {
                        val ff = FontFamily(tf)
                        cachedSystemFontFamily = ff
                        return ff
                    }
                }
            } catch (_: Throwable) {}
        }

        try {
            val systemTf = Typeface.DEFAULT
            if (systemTf != null) {
                val ff = FontFamily(systemTf)
                cachedSystemFontFamily = ff
                return ff
            }
        } catch (_: Throwable) {}

        return FontFamily.Default
    }
}
