package com.mirad.reader.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 应用和阅读器共用的明暗模式；具体阅读配色不再反向决定此值。 */
@Serializable
enum class AppThemeMode { SYSTEM, DAY, NIGHT }

/** 一组阅读外观。排版、字体、阅读位置等仍由 ReaderConfig 管理。 */
@Serializable
data class ReadingStyle(
    val theme: String = ThemeKey.PAPER,
    val backgroundType: String = BackgroundType.COLOR,
    val backgroundColor: Int = 0xFFF5F1E6.toInt(),
    val backgroundImageUri: String? = null,
    val backgroundImageAlpha: Float = 1f
) {
    fun applyTo(config: ReaderConfig): ReaderConfig = config.copy(
        theme = theme,
        backgroundType = backgroundType,
        backgroundColor = backgroundColor,
        backgroundImageUri = backgroundImageUri,
        backgroundImageAlpha = backgroundImageAlpha
    )

    /** 对历史值和过期界面事件兜底，始终保留调用方指定的日/夜组。 */
    fun normalized(isDark: Boolean): ReadingStyle {
        val defaults = if (isDark) NIGHT_DEFAULT else DAY_DEFAULT
        val opaqueColor = backgroundColor or 0xFF000000.toInt()
        val matchingColor = ThemePaletteResolver.isDarkColor(opaqueColor) == isDark
        val matchingTheme = theme in themes(isDark) && (theme != ThemeKey.CUSTOM || matchingColor)
        val type = if (backgroundType == BackgroundType.IMAGE) BackgroundType.IMAGE else BackgroundType.COLOR
        val builtin = BuiltinBackgrounds.fromUri(backgroundImageUri)
        return copy(
            theme = if (matchingTheme) theme else defaults.theme,
            backgroundType = type,
            backgroundColor = if (matchingColor) opaqueColor else defaults.backgroundColor,
            // 纯色模式保留尚未启用的图片选择；启用时只允许本组的内置素材。
            backgroundImageUri = if (type == BackgroundType.IMAGE && builtin != null && builtin.isDark != isDark) {
                null
            } else {
                backgroundImageUri
            },
            backgroundImageAlpha = if (backgroundImageAlpha.isFinite()) backgroundImageAlpha.coerceIn(0f, 1f) else 1f
        )
    }

    companion object {
        val DAY_DEFAULT = ReadingStyle()
        val NIGHT_DEFAULT = ReadingStyle(theme = ThemeKey.DARK, backgroundColor = 0xFF1A1C1E.toInt())

        private val DAY_THEMES = listOf(ThemeKey.LIGHT, ThemeKey.PAPER, ThemeKey.SEPIA, ThemeKey.GREEN, ThemeKey.CUSTOM)
        private val NIGHT_THEMES = listOf(ThemeKey.DARK, ThemeKey.BLACK, ThemeKey.CUSTOM)

        fun themes(isDark: Boolean): List<String> = if (isDark) NIGHT_THEMES else DAY_THEMES

        fun isDarkTheme(theme: String, backgroundColor: Int): Boolean = when (theme) {
            ThemeKey.DARK, ThemeKey.BLACK -> true
            ThemeKey.CUSTOM -> ThemePaletteResolver.isDarkColor(backgroundColor)
            else -> false
        }

        fun from(config: ReaderConfig): ReadingStyle = ReadingStyle(
            theme = config.theme,
            backgroundType = config.backgroundType,
            backgroundColor = config.backgroundColor,
            backgroundImageUri = config.backgroundImageUri,
            backgroundImageAlpha = config.backgroundImageAlpha
        )
    }
}

@Serializable
data class AppAppearance(
    val mode: AppThemeMode = AppThemeMode.SYSTEM,
    val day: ReadingStyle = ReadingStyle.DAY_DEFAULT,
    val night: ReadingStyle = ReadingStyle.NIGHT_DEFAULT
) {
    fun effectiveDark(systemDark: Boolean): Boolean = when (mode) {
        AppThemeMode.SYSTEM -> systemDark
        AppThemeMode.DAY -> false
        AppThemeMode.NIGHT -> true
    }

    fun style(isDark: Boolean): ReadingStyle = (if (isDark) night else day).normalized(isDark)

    fun resolveReaderConfig(base: ReaderConfig, systemDark: Boolean): ReaderConfig =
        style(effectiveDark(systemDark)).applyTo(base)

    fun withReadingStyle(isDark: Boolean, style: ReadingStyle): AppAppearance =
        if (isDark) copy(night = style.normalized(true)) else copy(day = style.normalized(false))

    fun normalized(): AppAppearance = copy(day = day.normalized(false), night = night.normalized(true))

    companion object {
        val DEFAULT = AppAppearance()

        /** 只在新偏好键不存在时使用。旧字段保留在 ReaderConfig 中供回退版本读取。 */
        fun fromLegacy(config: ReaderConfig): AppAppearance {
            val followsSystem = config.theme == ThemeKey.SYSTEM
            val initial = if (followsSystem) {
                DEFAULT.copy(day = ReadingStyle.DAY_DEFAULT.copy(theme = ThemeKey.LIGHT, backgroundColor = 0xFFFFFFFF.toInt()))
            } else {
                DEFAULT
            }
            val builtin = if (config.backgroundType == BackgroundType.IMAGE) {
                BuiltinBackgrounds.fromUri(config.backgroundImageUri)
            } else {
                null
            }
            val isDark = builtin?.isDark ?: ReadingStyle.isDarkTheme(config.theme, config.backgroundColor)
            val compatibleTheme = when {
                followsSystem -> if (isDark) ThemeKey.DARK else ThemeKey.LIGHT
                builtin != null && ReadingStyle.isDarkTheme(config.theme, config.backgroundColor) != isDark -> builtin.suggestedTheme
                else -> config.theme
            }
            val migrated = ReadingStyle.from(config).copy(theme = compatibleTheme).normalized(isDark)
            return initial.withReadingStyle(isDark, migrated)
        }
    }
}

object AppAppearanceJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    fun encode(appearance: AppAppearance): String =
        json.encodeToString(AppAppearance.serializer(), appearance.normalized())

    /** 已有但损坏的新记录不触发旧配置迁移，也不会因一次读取而被覆盖。 */
    fun decode(text: String?): AppAppearance = if (text.isNullOrBlank()) {
        AppAppearance.DEFAULT
    } else {
        runCatching { json.decodeFromString(AppAppearance.serializer(), text).normalized() }
            .getOrDefault(AppAppearance.DEFAULT)
    }
}
