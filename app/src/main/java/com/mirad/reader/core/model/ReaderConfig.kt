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
    val pageAnim: String = PageAnim.SLIDE,

    // ---- 背景与主题 ----
    val theme: String = ThemeKey.PAPER,
    val backgroundType: String = BackgroundType.COLOR,
    val backgroundColor: Int = 0xFFF5F1E6.toInt(),
    val backgroundImageUri: String? = null,
    val backgroundImageAlpha: Float = 1f,

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
    val tapZone: String = TapZoneKey.THREE,
    val volumeKeyPage: Boolean = true,

    // ---- EPUB ----
    val epubLayoutMode: String = EpubLayoutMode.UNIFIED,
    val allowImages: Boolean = true,

    // ---- TTS ----
    val ttsSpeechRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsAutoNextChapter: Boolean = true,

    // ---- 自动阅读 ----
    /**
     * 分页模式的翻页间隔（秒/页），5..120。
     * 只保存速度，不保存运行状态：每次重新打开书籍自动阅读都从关闭开始。
     */
    val autoPageSeconds: Int = AutoReadSpeed.DEFAULT_PAGE_SECONDS,
    /** 滚动模式的下滑速度（秒/屏），5..120；一屏按扣除正文上下留白后的可读高度计算。 */
    val autoScrollSecondsPerScreen: Int = AutoReadSpeed.DEFAULT_SCROLL_SECONDS
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
        val config = runCatching { json.decodeFromString(ReaderConfig.serializer(), text) }
            .getOrElse { return ReaderConfig.DEFAULT }
        // 历史版本存在已删除的翻页动画（如 CURL）：解码时收敛到合法值，避免旧数据卡住阅读。
        return if (config.pageAnim in ANIMS) config else config.copy(pageAnim = PageAnim.SLIDE)
    }

    private val ANIMS = listOf(PageAnim.NONE, PageAnim.SLIDE, PageAnim.COVER)
}
