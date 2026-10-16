package com.mirad.reader.core.reader

import com.mirad.reader.core.model.EpubLayoutMode
import com.mirad.reader.core.model.FontFamilyKey
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.TextAlignKey
import com.mirad.reader.core.model.ThemePalette

/**
 * 阅读器样式生成：把 [ReaderConfig] 映射为注入到 WebView 的 CSS。
 *
 * 只负责主题与排版（背景/字体/字号/行距/段距/对齐），分页布局由 reader.js 依据视口尺寸生成。
 */
object ReaderCss {

    /** 生成主题与排版样式表。 */
    fun themeCss(
        config: ReaderConfig,
        palette: ThemePalette,
        customFontUrl: String? = null,
        /** 背景图片 URL（设置了图片背景时由资源加载器提供）。 */
        backgroundImageUrl: String? = null
    ): String {
        val bg = argbToCss(palette.background)
        val fg = argbToCss(palette.text)
        val secondary = argbToCss(palette.secondaryText)
        val align = when (config.textAlign) {
            TextAlignKey.LEFT -> "left"
            TextAlignKey.CENTER -> "center"
            else -> "justify"
        }
        val useImageBg = config.backgroundType == com.mirad.reader.core.model.BackgroundType.IMAGE &&
            !backgroundImageUrl.isNullOrBlank()

        val sb = StringBuilder(2048)
        if (!customFontUrl.isNullOrBlank()) {
            sb.append(fontFaceCss(customFontUrl))
        }
        sb.append(":root{--mirad-bg:").append(bg).append(";--mirad-fg:").append(fg)
            .append(";--mirad-fg-2:").append(secondary).append(";}\n")
        sb.append("html{background:").append(bg).append(" !important;}\n")
        sb.append("body{background:transparent !important;color:").append(fg).append(" !important;")
        sb.append("font-size:").append(config.fontSizeSp).append("px;")
        sb.append("line-height:").append(config.lineHeightMultiplier).append(";")
        sb.append("letter-spacing:").append(config.letterSpacingEm).append("em;")
        sb.append("font-family:").append(fontStack(config, customFontUrl)).append(";")
        sb.append("margin:0;padding:0;")
        sb.append("-webkit-text-size-adjust:100%;word-wrap:break-word;}\n")

        if (useImageBg) {
            sb.append("html{background-image:url('").append(backgroundImageUrl).append("') !important;")
                .append("background-size:cover;background-position:center;background-repeat:no-repeat;}\n")
            sb.append("body{background:transparent !important;}\n")
        }

        sb.append("#").append(CONTENT_ID).append("{color:").append(fg).append(";}\n")

        val force = config.epubLayoutMode == EpubLayoutMode.UNIFIED
        val important = if (force) " !important" else ""

        sb.append("p,div,li,dd,dt,blockquote,section,article,td,th{")
            .append("color:").append(fg).append(important).append(";")
            .append("font-family:inherit").append(important).append(";")
            .append("line-height:").append(config.lineHeightMultiplier).append(important).append(";")
            .append("letter-spacing:").append(config.letterSpacingEm).append("em").append(important).append(";")
            .append("}\n")

        sb.append("p{margin:0 0 ").append(config.paragraphSpacingEm).append("em 0").append(important).append(";")
            .append("text-indent:").append(config.firstLineIndentEm).append("em").append(important).append(";")
            .append("text-align:").append(align).append(important).append(";}\n")

        if (force) {
            sb.append("div,section,article,blockquote,li{text-indent:0;text-align:inherit;}\n")
            sb.append("#").append(CONTENT_ID).append(" *:not(img):not(svg):not(image):not(figure):not(table)")
                .append("{font-size:inherit !important;background-color:transparent !important;}\n")
            sb.append("h1,h2,h3,h4,h5,h6{font-size:1.22em !important;font-weight:700;text-indent:0;")
                .append("text-align:center;margin:0.6em 0 0.9em 0;line-height:1.4;}\n")
            sb.append("h1{font-size:1.35em !important;}\n")
        } else {
            sb.append("h1,h2,h3,h4,h5,h6{color:").append(fg).append(";text-indent:0;}\n")
        }

        sb.append("img{max-width:100% !important;height:auto !important;display:block;margin:0.6em auto;}\n")
        if (!config.allowImages) {
            sb.append("img,svg,image{display:none !important;}\n")
        }
        if (config.boldText) {
            sb.append("#").append(CONTENT_ID).append(" p,#").append(CONTENT_ID).append(" div{font-weight:700 !important;}\n")
        }
        // 标题与引用不参与首行缩进，避免 EPUB 原书样式叠加出奇怪缩进
        sb.append("blockquote p,li p,td p{text-indent:0;}\n")
        // 划线高亮
        sb.append("mark.mirad-hl{background:rgba(255,214,102,0.55);color:inherit;padding:0;}\n")
        sb.append("mark.mirad-hl.mirad-hl-note{background:rgba(140,200,255,0.55);}\n")
        sb.append("span.mirad-tts{background:rgba(255,180,80,0.35);border-radius:2px;}\n")
        sb.append("mark.mirad-search{background:rgba(255,120,120,0.45);color:inherit;}\n")
        return sb.toString()
    }

    /** 字体栈。 */
    fun fontStack(config: ReaderConfig, customFontUrl: String?): String {
        val fallback = "\"Noto Sans CJK SC\",\"Source Han Sans SC\",\"PingFang SC\",\"Microsoft YaHei\",sans-serif"
        return when (config.fontFamily) {
            FontFamilyKey.SERIF -> "\"Noto Serif CJK SC\",\"Source Han Serif SC\",\"Songti SC\",serif,$fallback"
            FontFamilyKey.SANS -> fallback
            FontFamilyKey.MONO -> "monospace,\"Noto Sans Mono CJK SC\",$fallback"
            FontFamilyKey.CUSTOM ->
                if (!customFontUrl.isNullOrBlank()) "\"MiradCustom\",$fallback" else fallback
            else -> "\"Noto Sans CJK SC\",\"Source Han Sans SC\",\"PingFang SC\",\"Microsoft YaHei\",sans-serif"
        }
    }

    /** 自定义字体的 @font-face 片段。 */
    fun fontFaceCss(url: String): String =
        "@font-face{font-family:'MiradCustom';src:url('$url');font-display:swap;}\n"

    fun argbToCss(argb: Int): String {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return "rgb($r,$g,$b)"
    }

    /** 与 reader.js 保持一致的分页计算，便于单元测试与自检。 */
    object PageMath {
        fun pageCount(scrollWidth: Int, viewportWidth: Int, gap: Int): Int {
            if (viewportWidth <= 0) return 1
            val step = viewportWidth + gap
            val pages = Math.ceil(scrollWidth.toDouble() / step.toDouble()).toInt()
            return pages.coerceAtLeast(1)
        }

        fun offsetForPage(pageIndex: Int, viewportWidth: Int, gap: Int): Int =
            (pageIndex * (viewportWidth + gap)).coerceAtLeast(0)
    }

    const val CONTENT_ID = "mirad-content"
}
