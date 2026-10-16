package com.mirad.reader.core.model

import com.mirad.reader.core.model.ReaderConfig as Cfg

/** 主题配色：背景色与文字色（含次要文字色）。 */
data class ThemePalette(
    val background: Int,
    val text: Int,
    val secondaryText: Int,
    val isDark: Boolean
)

/**
 * 主题 → 配色映射。纯函数，便于单元测试。
 */
object ThemePaletteResolver {

    fun resolve(config: Cfg, systemDark: Boolean): ThemePalette {
        val effectiveTheme = when (config.theme) {
            ThemeKey.SYSTEM -> if (systemDark) ThemeKey.DARK else ThemeKey.LIGHT
            else -> config.theme
        }
        if (effectiveTheme == ThemeKey.CUSTOM) {
            val dark = isDarkColor(config.backgroundColor)
            return ThemePalette(
                background = config.backgroundColor,
                text = if (dark) 0xFFC9CCD0.toInt() else 0xFF33302B.toInt(),
                secondaryText = if (dark) 0xFF6C7075.toInt() else 0xFF9C9488.toInt(),
                isDark = dark
            )
        }
        return when (effectiveTheme) {
            ThemeKey.LIGHT -> ThemePalette(0xFFFFFFFF.toInt(), 0xFF22252A.toInt(), 0xFF8A8F98.toInt(), false)
            ThemeKey.SEPIA -> ThemePalette(0xFFEDE0C8.toInt(), 0xFF4A3F2E.toInt(), 0xFF9A8B74.toInt(), false)
            ThemeKey.GREEN -> ThemePalette(0xFFCCE8CF.toInt(), 0xFF223A26.toInt(), 0xFF6E8B72.toInt(), false)
            ThemeKey.DARK -> ThemePalette(0xFF1A1C1E.toInt(), 0xFFC9CCD0.toInt(), 0xFF6C7075.toInt(), true)
            ThemeKey.BLACK -> ThemePalette(0xFF000000.toInt(), 0xFF9E9E9E.toInt(), 0xFF5E5E5E.toInt(), true)
            else -> ThemePalette(0xFFF5F1E6.toInt(), 0xFF33302B.toInt(), 0xFF9C9488.toInt(), false)
        }
    }

    /** 依据背景亮度判断是否为深色（相对亮度阈值 0.5）。 */
    fun isDarkColor(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return luminance < 0.5
    }

    /** 已配置自动夜间且当前处于夜间时段。 */
    fun isAutoNight(config: Cfg, minuteOfDay: Int): Boolean {
        if (!config.autoNightEnabled) return false
        val start = config.autoNightStartMinute
        val end = config.autoNightEndMinute
        return if (start <= end) {
            minuteOfDay in start until end
        } else {
            minuteOfDay >= start || minuteOfDay < end
        }
    }
}
