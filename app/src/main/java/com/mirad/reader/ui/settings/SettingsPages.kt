package com.mirad.reader.ui.settings

import com.mirad.reader.core.model.EpubLayoutMode
import com.mirad.reader.core.model.ReaderConfig

/** 阅读设置分类：全书通用，设置页与阅读面板共用同一组 Section。 */
enum class ReadingSettingsPage(val title: String) {
    TYPOGRAPHY("字体与排版"),
    THEME("阅读主题与背景"),
    INTERACTION("翻页与交互"),
    SCREEN("屏幕"),
    HEADER_FOOTER("页眉页脚"),
    EPUB("EPUB"),
    TTS("朗读")
}

/** 应用级设置分类：只出现在设置页，不进入阅读面板。 */
enum class AppSettingsPage(val title: String) {
    APPEARANCE("界面外观"),
    SHELF("书架管理"),
    ABOUT("关于")
}

/** 设置详情路由：阅读级与应用级分开，避免同一个 when 混合两类页面。 */
sealed interface SettingsRoute {
    val title: String

    data class Reading(val page: ReadingSettingsPage) : SettingsRoute {
        override val title: String get() = page.title
    }

    data class App(val page: AppSettingsPage) : SettingsRoute {
        override val title: String get() = page.title
    }

    companion object {
        /**
         * 供预览包与验证脚本按名字打开页面。旧名 SCREEN 仍指向「屏幕」，
         * 旧脚本不需要同步改名也能打开。
         */
        fun parse(name: String?): SettingsRoute? {
            if (name.isNullOrBlank()) return null
            val key = name.trim().uppercase()
            ReadingSettingsPage.entries.firstOrNull { it.name == key }?.let { return Reading(it) }
            AppSettingsPage.entries.firstOrNull { it.name == key }?.let { return App(it) }
            return null
        }
    }
}

/** null 表示全局设置页：它同时决定以后打开 EPUB 时的配置，所以始终显示 EPUB 分类。 */
internal fun readingSettingsPages(isEpub: Boolean? = null): List<ReadingSettingsPage> =
    ReadingSettingsPage.entries.filter { isEpub != false || it != ReadingSettingsPage.EPUB }

internal val appSettingsPages: List<AppSettingsPage> = AppSettingsPage.entries

/** EPUB「保留原书」时由原书样式决定字体与文字排版，阅读器只保留版面类设置。 */
internal fun canEditReaderTypography(config: ReaderConfig, isEpub: Boolean?): Boolean =
    isEpub != true || config.epubLayoutMode != EpubLayoutMode.ORIGINAL
