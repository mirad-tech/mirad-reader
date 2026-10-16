package com.mirad.reader.core.reader

import com.mirad.reader.core.model.EpubLayoutMode
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.ReadingFonts
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
        sb.append("html{background:").append(bg).append(" !important;}")
            .append("-webkit-overflow-scrolling:touch;overscroll-behavior-y:contain;}\n")
        sb.append("body{background:transparent !important;color:").append(fg).append(" !important;")
        sb.append("font-size:").append(config.fontSizeSp).append("px;")
        sb.append("line-height:").append(config.lineHeightMultiplier).append(";")
        sb.append("letter-spacing:").append(config.letterSpacingEm).append("em;")
        sb.append("font-family:").append(fontStack(config, customFontUrl)).append(";")
        ReadingFonts.weightOf(config.fontFamily)?.let { weight ->
            sb.append("font-weight:").append(weight).append(";")
        }
        sb.append("margin:0;padding:0;")
        sb.append("-webkit-text-size-adjust:100%;word-wrap:break-word;}\n")

        if (useImageBg) {
            // 背景图与不透明度覆盖层画在固定定位的层上（由 reader.js 注入 #mirad-bg / #mirad-tint），
            // 而不是画在 html/body 上：正文列居中变窄后，画在元素上的背景会跟着变窄或错位。
            val overlay = (1f - config.backgroundImageAlpha.coerceIn(0f, 1f))
            val rgb = argbToRgbTriplet(palette.background)
            sb.append("html{background-image:none !important;}\n")
            sb.append("#mirad-bg{background-image:url('").append(backgroundImageUrl).append("') !important;}\n")
            sb.append("#mirad-tint{background:rgba(").append(rgb).append(",").append(overlay).append(") !important;}\n")
        } else {
            sb.append("#mirad-bg{background-image:none !important;}\n")
            sb.append("#mirad-tint{background:transparent !important;}\n")
        }

        sb.append("body{background:transparent !important;}\n")
        // 固定视口层：不论分列/居中/滚动，永远铺满整屏
        sb.append("#mirad-bg,#mirad-tint{position:fixed !important;top:0 !important;left:0 !important;")
            .append("right:0 !important;bottom:0 !important;width:100% !important;height:100% !important;")
            .append("z-index:0 !important;pointer-events:none !important;background-color:transparent;")
            .append("background-size:cover !important;background-position:center !important;")
            .append("background-repeat:no-repeat !important;}\n")
        sb.append("#mirad-content{position:relative;z-index:1;}\n")

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
            // 压掉书内 CSS 的固定像素宽度：否则 EPUB 自带样式会把正文撑出屏幕（"没有适应屏幕大小"）
            sb.append("#").append(CONTENT_ID).append(" p,#").append(CONTENT_ID).append(" div,")
                .append("#").append(CONTENT_ID).append(" section,#").append(CONTENT_ID).append(" article,")
                .append("#").append(CONTENT_ID).append(" blockquote,#").append(CONTENT_ID).append(" li")
                .append("{max-width:100% !important;width:auto !important;min-width:0 !important;}\n")
            sb.append("h1,h2,h3,h4,h5,h6{font-size:1.22em !important;font-weight:700;text-indent:0;")
                .append("text-align:center;margin:0.6em 0 0.9em 0;line-height:1.4;}\n")
            sb.append("h1{font-size:1.35em !important;}\n")
        } else {
            sb.append("h1,h2,h3,h4,h5,h6{color:").append(fg).append(";text-indent:0;}\n")
        }

        // 保留原书版式时也要防横向溢出
        sb.append("#").append(CONTENT_ID).append("{max-width:100% !important;overflow-wrap:break-word;}\n")

        sb.append("img{max-width:100% !important;height:auto !important;display:block;margin:0.6em auto;}\n")
        if (!config.allowImages) {
            sb.append("img,svg,image{display:none !important;}\n")
        }
        if (config.boldText) {
            sb.append("#").append(CONTENT_ID).append(" p,#").append(CONTENT_ID).append(" div{font-weight:700 !important;}\n")
        }
        // 标题与引用不参与首行缩进，避免 EPUB 原书样式叠加出奇怪缩进
        sb.append("blockquote p,li p,td p{text-indent:0;}\n")
        // 划线高亮（reader.js 给带笔记的高亮加的类是 mirad-note，两边必须一致）
        sb.append("mark.mirad-hl{background:rgba(255,214,102,0.55);color:inherit;padding:0;}\n")
        sb.append("mark.mirad-note{background:rgba(140,200,255,0.55);}\n")
        sb.append("span.mirad-tts{background:rgba(255,180,80,0.35);border-radius:2px;}\n")
        sb.append("mark.mirad-search{background:rgba(255,120,120,0.45);color:inherit;}\n")
        return sb.toString()
    }

    /** 字体栈（委托给 [ReadingFonts]，便于统一维护与测试）。 */
    fun fontStack(config: ReaderConfig, customFontUrl: String?): String =
        ReadingFonts.stackOf(config.fontFamily, customFontUrl)

    /** 自定义字体的 @font-face 片段。 */
    fun fontFaceCss(url: String): String =
        "@font-face{font-family:'MiradCustom';src:url('$url');font-display:swap;}\n"

    /** "r,g,b" 三元组，供 rgba() 拼装使用。 */
    fun argbToRgbTriplet(argb: Int): String {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return "$r,$g,$b"
    }

    fun argbToCss(argb: Int): String {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return "rgb($r,$g,$b)"
    }

    /** 与 reader.js 保持一致的分页计算，便于单元测试与自检。 */
    object PageMath {

        /** 正文版心几何（单位 CSS px，等于 dp）。 */
        data class ColumnGeometry(val colWidth: Int, val inset: Int, val gap: Int)

        /**
         * 与 reader.js 的 columnGeometry() 保持一致：
         * 留白 → 单行上限 → 列宽与居中偏移。窄屏只留白，宽屏由 colMax 限宽并居中。
         */
        fun columnGeometry(
            viewportWidth: Int,
            paddingDp: Int,
            colMax: Int,
            minColumnWidth: Int = 192
        ): ColumnGeometry {
            var pad = paddingDp.coerceAtLeast(0)
            if (viewportWidth - pad * 2 < minColumnWidth) {
                pad = ((viewportWidth - minColumnWidth) / 2).coerceAtLeast(0)
            }
            var colW = viewportWidth - pad * 2
            if (colMax > 0 && colW > colMax) colW = colMax
            colW = colW.coerceAtLeast(1)
            return ColumnGeometry(
                colWidth = colW,
                inset = ((viewportWidth - colW) / 2).coerceAtLeast(0),
                gap = pad * 2
            )
        }

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
