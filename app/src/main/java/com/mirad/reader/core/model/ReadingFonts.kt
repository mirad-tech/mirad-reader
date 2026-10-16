package com.mirad.reader.core.model

/** 一款可用于阅读的字体（系统字体族或用户提供的字体文件）。 */
data class ReadingFont(
    val label: String,
    /** 系统字体：对应 [FontFamilyKey]；自定义字体：为 null。 */
    val systemKey: String? = null,
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
 * 因此这里以「系统字体族 + 常见中文字体名 + 优雅回退」为主，同时支持导入 .ttf/.otf。
 */
object ReadingFonts {

    private const val SANS = "\"MiradSans\",\"Noto Sans CJK SC\",\"Source Han Sans SC\",\"PingFang SC\",\"Microsoft YaHei\",sans-serif"
    private const val SERIF = "\"MiradSerif\",\"Noto Serif CJK SC\",\"Source Han Serif SC\",\"Songti SC\",\"SimSun\",serif"
    private const val KAI = "\"Kaiti SC\",\"KaiTi\",\"STKaiti\",\"Kaiti TC\",\"Noto Serif CJK SC\",serif"
    private const val FANGSONG = "\"FangSong\",\"STFangsong\",\"FangSong_GB2312\",\"Noto Serif CJK SC\",serif"
    private const val ROUND = "\"Yuanti SC\",\"STYuanti\",\"Yuanti TC\",\"Noto Sans CJK SC\",sans-serif"
    private const val LIGHT = "\"Noto Sans CJK SC Light\",\"Source Han Sans SC Light\",\"PingFang SC Light\",sans-serif"
    private const val MONO = "monospace,\"Noto Sans Mono CJK SC\",\"Courier New\",monospace"

    /** UI 中的系统字体预设。 */
    val PRESETS: List<ReadingFont> = listOf(
        ReadingFont("系统默认", FontFamilyKey.SYSTEM, note = "跟随系统"),
        ReadingFont("宋体", FontFamilyKey.SERIF, note = "衬线，适合长文"),
        ReadingFont("黑体", FontFamilyKey.SANS, note = "无衬线"),
        ReadingFont("楷体", FontFamilyKey.KAITI, note = "偏手写，缺字体时回退宋体"),
        ReadingFont("仿宋", FontFamilyKey.FANGSONG, note = "公文风，缺字体时回退宋体"),
        ReadingFont("圆体", FontFamilyKey.ROUND, note = "笔画圆润"),
        ReadingFont("细黑", FontFamilyKey.LIGHT, note = "笔画更细"),
        ReadingFont("等宽", FontFamilyKey.MONO, note = "固定字宽")
    )

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

    fun presetLabel(key: String): String =
        PRESETS.firstOrNull { it.systemKey == key }?.label ?: "自定义"
}
