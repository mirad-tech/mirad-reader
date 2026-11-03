package com.mirad.reader.core.model

/** 一款可用于阅读的内置字体或历史导入字体。 */
data class ReadingFont(
    val label: String,
    /** 用户导入的字体文件绝对路径。 */
    val filePath: String? = null,
    /** 内置在 assets/fonts 下的字体（assets 内相对路径）。 */
    val assetName: String? = null,
    /** 一句话说明，UI 上做副标题。 */
    val note: String? = null
) {
    val isCustom: Boolean get() = filePath != null || assetName != null
}

/**
 * 阅读字体预设与 CSS 字体栈。
 *
 * 中文正文字体的现实约束：中文字体文件动辄 10–20MB，全部内置会让 APK 膨胀数倍，
 * 因此这里以「系统字体族 + 内置中文字体 + 优雅回退」为主，并兼容历史已导入字体。
 */
object ReadingFonts {

    private val RETIRED_PREVIEW_FONT = Regex(
        "BarlowCondensed-Bold(?:-[0-9]+)?(?:\\.(?:ttf|otf|ttc|otc|woff|woff2))?",
        RegexOption.IGNORE_CASE
    )

    /** 匹配旧测试字体的文件名或显示名，含导入器自动编号的副本。 */
    fun isRetiredPreviewFont(fileName: String?): Boolean =
        fileName != null && RETIRED_PREVIEW_FONT.matches(fileName)

    private const val SANS = "\"MiradSans\",\"Noto Sans CJK SC\",\"Source Han Sans SC\",\"PingFang SC\",\"Microsoft YaHei\",sans-serif"
    private const val SERIF = "\"MiradSerif\",\"Noto Serif CJK SC\",\"Source Han Serif SC\",\"Songti SC\",\"SimSun\",serif"
    private const val KAI = "\"Kaiti SC\",\"KaiTi\",\"STKaiti\",\"Kaiti TC\",\"Noto Serif CJK SC\",serif"
    private const val FANGSONG = "\"FangSong\",\"STFangsong\",\"FangSong_GB2312\",\"Noto Serif CJK SC\",serif"
    private const val ROUND = "\"Yuanti SC\",\"STYuanti\",\"Yuanti TC\",\"Noto Sans CJK SC\",sans-serif"
    private const val LIGHT = "\"Noto Sans CJK SC Light\",\"Source Han Sans SC Light\",\"PingFang SC Light\",sans-serif"
    private const val MONO = "monospace,\"Noto Sans Mono CJK SC\",\"Courier New\",monospace"

    /** 字体族 key → CSS 字体栈。 */
    fun stackOf(key: String, customFontUrl: String?): String = when (key) {
        FontFamilyKey.SERIF -> SERIF
        FontFamilyKey.SANS -> SANS
        FontFamilyKey.KAITI -> KAI
        FontFamilyKey.FANGSONG -> FANGSONG
        FontFamilyKey.ROUND -> ROUND
        FontFamilyKey.LIGHT -> LIGHT
        FontFamilyKey.MONO -> MONO
        FontFamilyKey.CUSTOM ->
            if (!customFontUrl.isNullOrBlank()) "\"MiradCustom\",$SANS" else SANS
        else -> SANS
    }

    /** 细体需要更小的字重，否则系统会用常规笔画渲染。 */
    fun weightOf(key: String): Int? = if (key == FontFamilyKey.LIGHT) 300 else null

    // ---------------------------------------------------------------- 内置字体

    /**
     * 内置字体（assets/fonts 下）；各款字形覆盖和许可见该目录 README。
     * 文件名 → 中文显示名。
     */
    private val BUNDLED_LABELS = mapOf(
        "lxgw-wenkai-regular" to "霞鹜文楷",
        "noto-serif-sc-regular" to "思源宋体",
        "mirad-reading-sans" to "Noto 黑体",
        "zhuque-fangsong-regular" to "朱雀仿宋"
    )

    const val BUNDLED_WENKAI = "fonts/lxgw-wenkai-regular.woff2"
    const val BUNDLED_SERIF = "fonts/noto-serif-sc-regular.woff2"
    const val BUNDLED_SANS = "fonts/mirad-reading-sans.woff2"
    const val BUNDLED_FANGSONG = "fonts/zhuque-fangsong-regular.woff2"

    /** 新书默认用内置楷体：保证开箱即用的一致观感。 */
    const val DEFAULT_CUSTOM_FONT = BUNDLED_WENKAI

    fun bundledLabel(fileName: String): String {
        val stem = fileName.substringBeforeLast('.')
        return BUNDLED_LABELS[stem.lowercase()] ?: stem
    }

    /** 内置字体在「我的字体」里的副标题。 */
    fun bundledNote(fileName: String): String = when (fileName.lowercase()) {
        "lxgw-wenkai-regular.woff2" -> "内置 · 楷体，笔画舒展"
        "noto-serif-sc-regular.woff2" -> "内置 · 宋体，横细竖粗"
        "mirad-reading-sans.woff2" -> "内置 · 黑体，笔画匀净"
        "zhuque-fangsong-regular.woff2" -> "内置 · 仿宋，字形舒展"
        else -> "内置"
    }
}
