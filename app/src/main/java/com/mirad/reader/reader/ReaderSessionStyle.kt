package com.mirad.reader.reader

import android.webkit.WebView
import com.mirad.reader.core.model.BackgroundType
import com.mirad.reader.core.model.BuiltinBackgrounds
import com.mirad.reader.core.model.EpubLayoutMode
import com.mirad.reader.core.model.FontFamilyKey
import com.mirad.reader.core.model.PageAnim
import com.mirad.reader.core.model.PageMode
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.ThemePaletteResolver
import com.mirad.reader.core.reader.ReaderCss
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.io.File

/** 会话的主题资源与版心配置；缓存仍仅随资源路径变化刷新。 */
internal class ReaderSessionStyle(
    private val book: BookEntity,
    private val resources: ReaderResourceProvider
) {
    private val backgroundRevision = ReaderBackgroundRevision()

    // 路径有效性/版本只在路径变化时重新 stat：applyConfigToWebView 在拖滑杆时高频触发，
    // 不能每次都在主线程做磁盘 IO。导入器生成的文件名唯一（createTempFile），
    // 同路径被覆盖替换的场景不存在，按路径缓存是安全的。
    private var bgPathStat: Pair<String, Triple<Boolean, Long, Long>>? = null
    private var fontPathStat: Pair<String, Boolean>? = null

    private fun statBackgroundFile(path: String): Triple<Boolean, Long, Long> {
        bgPathStat?.let { (cached, stat) -> if (cached == path) return stat }
        val file = File(path)
        val stat = Triple(
            file.exists(),
            runCatching { file.lastModified() }.getOrDefault(0L),
            runCatching { file.length() }.getOrDefault(0L)
        )
        bgPathStat = path to stat
        return stat
    }

    private fun statFontFile(path: String): Boolean {
        fontPathStat?.let { (cached, exists) -> if (cached == path) return exists }
        val exists = File(path).exists()
        fontPathStat = path to exists
        return exists
    }

    /** 状态栏 / 手势条预留高度（dp），由界面按 WindowInsets 写入。 */
    private var insetTopDp = 0
    private var insetBottomDp = 0
    private var insetHorizontalDp = 0
    private var insetClipTopDp = 0
    private var insetClipBottomDp = 0

    fun layoutChanged(previous: ReaderConfig, next: ReaderConfig): Boolean =
        previous.pageMode != next.pageMode ||
            previous.fontSizeSp != next.fontSizeSp ||
            previous.lineHeightMultiplier != next.lineHeightMultiplier ||
            previous.paragraphSpacingEm != next.paragraphSpacingEm ||
            previous.letterSpacingEm != next.letterSpacingEm ||
            previous.firstLineIndentEm != next.firstLineIndentEm ||
            previous.horizontalPaddingDp != next.horizontalPaddingDp ||
            previous.maxColumnWidthEm != next.maxColumnWidthEm ||
            previous.textAlign != next.textAlign ||
            previous.boldText != next.boldText ||
            previous.fontFamily != next.fontFamily ||
            previous.customFontAsset != next.customFontAsset ||
            previous.customFontPath != next.customFontPath ||
            previous.epubLayoutMode != next.epubLayoutMode ||
            previous.allowImages != next.allowImages

    /** 更新配置：仅注入样式，不重载章节（保留当前位置）。 */
    fun update(config: ReaderConfig, systemDark: Boolean) {
        val palette = ThemePaletteResolver.resolve(config, systemDark)

        // 背景：内置素材优先，其次用户选的图片
        val bgImageUrl = if (config.backgroundType == BackgroundType.IMAGE) {
            val builtin = BuiltinBackgrounds.fromUri(config.backgroundImageUri)
            val filePath = config.backgroundImageUri?.takeIf { it.isNotBlank() && statBackgroundFile(it).first }
            when {
                builtin != null -> {
                    resources.backgroundAssetName = builtin.assetName
                    resources.backgroundImagePath = null
                    backgroundRevision.url(ReaderResourceProvider.backgroundUrl(book.id), "asset:${builtin.assetName}")
                }
                filePath != null -> {
                    val stat = statBackgroundFile(filePath)
                    resources.backgroundImagePath = filePath
                    resources.backgroundAssetName = null
                    backgroundRevision.url(ReaderResourceProvider.backgroundUrl(book.id),
                        "file:$filePath:${stat.second}:${stat.third}")
                }
                else -> {
                    resources.backgroundAssetName = null
                    resources.backgroundImagePath = null
                    null
                }
            }
        } else {
            resources.backgroundAssetName = null
            resources.backgroundImagePath = null
            null
        }

        // 字体：内置字体优先，其次用户导入的字体文件
        val fontUrl = if (config.fontFamily == FontFamilyKey.CUSTOM) {
            val asset = config.customFontAsset?.takeIf { it.isNotBlank() }
            val path = config.customFontPath?.takeIf { it.isNotBlank() && statFontFile(it) }
            when {
                asset != null -> {
                    resources.fontAssetName = asset
                    resources.fontFilePath = null
                    ReaderResourceProvider.fontUrl(book.id)
                }
                path != null -> {
                    resources.fontFilePath = path
                    resources.fontAssetName = null
                    ReaderResourceProvider.fontUrl(book.id)
                }
                else -> {
                    resources.fontAssetName = null
                    resources.fontFilePath = null
                    null
                }
            }
        } else {
            resources.fontAssetName = null
            resources.fontFilePath = null
            null
        }

        val css = ReaderCss.themeCss(
            config = config,
            palette = palette,
            customFontUrl = fontUrl,
            backgroundImageUrl = bgImageUrl
        )
        resources.updateTheme(css, bgImageUrl, fontUrl)
    }

    fun jsConfig(config: ReaderConfig, chapterCount: Int): String {
        // WebView 里 1 CSS px == 1 dp，所以留白直接就是 paddingDp（不要再乘 density，
        // 否则列间距会跑到屏幕外，页边距看起来"没生效"）。
        val pad = maxOf(0, config.horizontalPaddingDp, insetHorizontalDp)
        val colMax = if (config.maxColumnWidthEm > 0f) {
            (config.fontSizeSp * config.maxColumnWidthEm).roundToInt()
        } else {
            0
        }
        val mode = if (config.pageMode == PageMode.SCROLL) "SCROLL" else "PAGED"
        val anim = when (config.pageAnim) {
            PageAnim.NONE -> "NONE"
            PageAnim.COVER -> "COVER"
            else -> "SLIDE"
        }
        // 窗口滚动引擎已长期稳定，不再作为可配置项：滚动模式一律启用 3 章滑动窗口。
        val isWindowed = ReaderSession.WINDOWED_SCROLL && !(book.format == BookFormat.EPUB && config.epubLayoutMode == EpubLayoutMode.ORIGINAL)
        return """{mode:"$mode",gap:${pad * 2},pad:$pad,colMax:$colMax,""" +
            """padTop:$insetTopDp,padBottom:$insetBottomDp,anim:"$anim",""" +
            """clipTop:$insetClipTopDp,clipBottom:$insetClipBottomDp,""" +
            """chapterCount:${chapterCount},windowedScroll:$isWindowed}"""
    }

    /**
     * 系统栏预留：正文避开状态栏与手势条，但背景层仍然满屏（沉浸式不破坏）。
     * 由界面按 WindowInsets 计算后传入。
     */
    fun updateInsets(topDp: Int, bottomDp: Int, leftDp: Int = 0, rightDp: Int = 0,
                     cutoutTopDp: Int = 0, cutoutBottomDp: Int = 0): Boolean {
        val horizontalDp = maxOf(0, leftDp, rightDp)
        if (insetTopDp == topDp && insetBottomDp == bottomDp && insetHorizontalDp == horizontalDp &&
            insetClipTopDp == cutoutTopDp && insetClipBottomDp == cutoutBottomDp) return false
        insetTopDp = topDp
        insetBottomDp = bottomDp
        insetHorizontalDp = horizontalDp
        insetClipTopDp = cutoutTopDp
        insetClipBottomDp = cutoutBottomDp
        return true
    }

}

/** A source switch needs a new URL even when Chromium retains the old decoded background in memory. */
internal class ReaderBackgroundRevision {
    private var source: String? = null
    private var revision = 0L

    fun url(baseUrl: String, sourceKey: String): String {
        if (source != sourceKey) {
            source = sourceKey
            revision++
        }
        return "$baseUrl?v=$revision"
    }
}
