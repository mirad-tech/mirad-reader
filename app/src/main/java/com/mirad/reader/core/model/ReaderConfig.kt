package com.mirad.reader.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 翻页/阅读方式。 */
object PageMode {
    const val PAGED = "PAGED"
    const val SCROLL = "SCROLL"
}

object PageAnim {
    const val NONE = "NONE"
    const val SLIDE = "SLIDE"
    const val COVER = "COVER"
    const val CURL = "CURL"
}

object ThemeKey {
    const val LIGHT = "LIGHT"
    const val PAPER = "PAPER"
    const val SEPIA = "SEPIA"
    const val GREEN = "GREEN"
    const val DARK = "DARK"
    const val BLACK = "BLACK"
    const val SYSTEM = "SYSTEM"
    const val CUSTOM = "CUSTOM"
}

object BackgroundType {
    const val COLOR = "COLOR"
    const val IMAGE = "IMAGE"
}

object FontFamilyKey {
    const val SYSTEM = "SYSTEM"
    const val SERIF = "SERIF"
    const val SANS = "SANS"
    const val KAITI = "KAITI"
    const val FANGSONG = "FANGSONG"
    const val ROUND = "ROUND"
    const val LIGHT = "LIGHT"
    const val MONO = "MONO"
    const val CUSTOM = "CUSTOM"
}

object TextAlignKey {
    const val LEFT = "LEFT"
    const val JUSTIFY = "JUSTIFY"
    const val CENTER = "CENTER"
}

object EpubLayoutMode {
    /** 统一排版：阅读器强制字号/行距/字体，保留图片、加粗与层级。 */
    const val UNIFIED = "UNIFIED"

    /** 保留原书样式：只注入背景与文字颜色，尽量还原原书版式。 */
    const val ORIGINAL = "ORIGINAL"
}

object TapZoneKey {
    const val THREE = "THREE"
    const val TWO = "TWO"
    const val NONE = "NONE"
}

object OrientationKey {
    const val AUTO = "AUTO"
    const val PORTRAIT = "PORTRAIT"
    const val LANDSCAPE = "LANDSCAPE"
}

/**
 * 全部阅读设置项。既作为全局默认，也作为每本书的覆盖配置。
 */
@Serializable
data class ReaderConfig(
    // ---- 字体与排版 ----
    val fontFamily: String = FontFamilyKey.CUSTOM,
    /** 用户导入的字体文件路径（fontFamily = CUSTOM 时生效）。 */
    @SerialName("customFontPath") val customFontPath: String? = null,
    /** 内置在 assets/fonts 下的字体（fontFamily = CUSTOM 时生效）。 */
    val customFontAsset: String? = ReadingFonts.DEFAULT_CUSTOM_FONT,
    val fontSizeSp: Float = 24f,
    val lineHeightMultiplier: Float = 1.7f,
    val paragraphSpacingEm: Float = 0.55f,
    val letterSpacingEm: Float = 0f,
    val firstLineIndentEm: Float = 2f,
    val textAlign: String = TextAlignKey.JUSTIFY,
    val boldText: Boolean = false,

    // ---- 阅读方式 ----
    val pageMode: String = PageMode.PAGED,
    /**
     * 滚动模式「连续接章」开关（见 docs/continuous-scroll-plan.md）。
     * 默认**关闭**：P2 窗口引擎尚未完成（活动章号取值有误），先保持 1.0.8 行为；
     * 实现稳定后再改成默认开。
     */
    val windowedScroll: Boolean = false,
    val pageAnim: String = PageAnim.SLIDE,

    // ---- 背景与主题 ----
    val theme: String = ThemeKey.PAPER,
    val backgroundType: String = BackgroundType.COLOR,
    val backgroundColor: Int = 0xFFF5F1E6.toInt(),
    val backgroundImageUri: String? = null,
    val backgroundImageAlpha: Float = 1f,
    val autoNightEnabled: Boolean = false,
    val autoNightStartMinute: Int = 22 * 60,
    val autoNightEndMinute: Int = 7 * 60,

    // ---- 屏幕 ----
    val brightness: Float = -1f,
    val keepScreenOn: Boolean = true,
    val fullScreen: Boolean = true,
    val orientation: String = OrientationKey.AUTO,

    // ---- 页眉页脚 ----
    val showHeader: Boolean = true,
    val showFooter: Boolean = true,
    val showChapterTitleInHeader: Boolean = true,
    val showClock: Boolean = true,
    val showBattery: Boolean = false,
    val showPageNumber: Boolean = true,

    // ---- 页面与交互 ----
    val horizontalPaddingDp: Int = 20,
    /**
     * 单行最大宽度（em，按字号换算）。0 = 不限。
     * 手机上通常不触发，横屏/折叠屏展开/平板上用来限制行长并把正文居中。
     */
    val maxColumnWidthEm: Float = 34f,
    val verticalPaddingDp: Int = 16,
    val tapZone: String = TapZoneKey.THREE,
    val volumeKeyPage: Boolean = true,
    val longPressSelect: Boolean = true,

    // ---- EPUB ----
    val epubLayoutMode: String = EpubLayoutMode.UNIFIED,
    val allowImages: Boolean = true,

    // ---- TTS ----
    val ttsSpeechRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsAutoNextChapter: Boolean = true
) {
    companion object {
        val DEFAULT = ReaderConfig()
    }
}

/** 阅读配置的 JSON 编解码。 */
object ReaderConfigJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    fun encode(config: ReaderConfig): String = json.encodeToString(ReaderConfig.serializer(), config)

    fun decode(text: String?): ReaderConfig {
        if (text.isNullOrBlank()) return ReaderConfig.DEFAULT
        return runCatching { json.decodeFromString(ReaderConfig.serializer(), text) }
            .getOrElse { ReaderConfig.DEFAULT }
    }
}
