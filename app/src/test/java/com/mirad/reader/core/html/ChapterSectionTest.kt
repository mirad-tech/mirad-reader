package com.mirad.reader.core.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1：章节片段构建与 EPUB 资源 URL 绝对化（docs/continuous-scroll-plan.md §5.1 / §7.1）。
 *
 * 核心约束：片段内的局部文本必须与整章加载时的文本一致（偏移语义不变），
 * 且片段里不能出现 head/主题/reader.js。
 */
class ChapterSectionTest {

    private val txt = "第3章 归途\n第一行正文。\n\n第二行正文。\n"

    @Test
    fun txtSectionStripsTitleLineAndKeepsParagraphs() {
        val section = ChapterHtmlBuilder.buildSection(
            title = "第3章 归途",
            bodyText = txt,
            chapterIndex = 3,
            titleLineChars = "第3章 归途\n".length
        )
        assertTrue(section.startsWith("<section class=\"mirad-chapter\" data-chapter=\"3\">"))
        assertTrue(section.endsWith("</section>"))
        assertTrue(section.contains("<h2 class=\"mirad-chapter-title\">第3章 归途</h2>"))
        // 标题行已剔除，正文段落保留
        assertTrue(section.contains("<p>第一行正文。</p>"))
        assertTrue(section.contains("<p>第二行正文。</p>"))
        assertFalse(section.contains("<p>第3章 归途</p>"))
    }

    @Test
    fun txtSectionForVolumeHasNoTitleElement() {
        val section = ChapterHtmlBuilder.buildSection(
            title = "第一卷 风起",
            bodyText = "第一卷 风起\n正文。\n",
            chapterIndex = 1,
            titleLineChars = "第一卷 风起\n".length,
            includeTitleElement = false
        )
        assertFalse(section.contains("mirad-chapter-title"))
        assertTrue(section.contains("<p>正文。</p>"))
    }

    @Test
    fun sectionHasNoHeadThemeOrScript() {
        val section = ChapterHtmlBuilder.buildSection("第1章", "第1章\n正文。", 1, "第1章\n".length)
        assertFalse(section.contains("<html"))
        assertFalse(section.contains("<head"))
        assertFalse(section.contains("<script"))
        assertFalse(section.contains("mirad-content"))
    }

    @Test
    fun emptyChapterProducesEmptySection() {
        val section = ChapterHtmlBuilder.buildSection("", "", 0, 0, includeTitleElement = true)
        assertEquals("<section class=\"mirad-chapter\" data-chapter=\"0\"></section>", section)
    }

    @Test
    fun epubSectionWrapsBodyInnerOnly() {
        val xhtml = "<html><head><title>t</title></head><body><p>插图</p><img src=\"../img/a.png\"></body></html>"
        val section = ChapterHtmlBuilder.epubSection(xhtml, 5) { "ABS:$it" }
        assertTrue(section.startsWith("<section class=\"mirad-chapter\" data-chapter=\"5\">"))
        assertTrue(section.contains("<p>插图</p>"))
        assertTrue(section.contains("src=\"ABS:../img/a.png\""))
        assertFalse(section.contains("<head"))
        assertFalse(section.contains("<body"))
        assertTrue(section.endsWith("</section>"))
    }

    @Test
    fun epubSectionAbsolutizesSrcHrefSrcsetAndInlineUrl() {
        val xhtml = "<body><img src=\"a.png\">" +
            "<a href=\"b.html\">x</a>" +
            "<img srcset=\"c1.png 1x, c2.png 2x\">" +
            "<div style=\"background:url('d.jpg')\"></div>" +
            "</body>"
        val section = ChapterHtmlBuilder.epubSection(xhtml, 2) { "ABS:$it" }
        assertTrue(section.contains("src=\"ABS:a.png\""))
        assertTrue(section.contains("href=\"ABS:b.html\""))
        assertTrue(section.contains("srcset=\"ABS:c1.png 1x, ABS:c2.png 2x\""))
        assertTrue(section.contains("url('ABS:d.jpg')"))
    }

    @Test
    fun epubStyleAttributeAndStyleTagQuoteSafety() {
        // F14: style 属性引号上下文安全改写，不能破坏 HTML 属性引号导致后方 CSS（如 font-style:italic）失效
        val doubleQuoted = "<div style=\"background:url('a.jpg');font-style:italic\">text</div>"
        val singleQuoted = "<div style='background:url(\"a.jpg\");font-style:italic'>text</div>"
        val styleTag = "<style>div { background: url('a.jpg'); font-style: italic; }</style>"
        val multiUrl = "<div style=\"background-image:url('a.jpg'), url('b.png');font-style:italic\">text</div>"

        val rewrittenDouble = HtmlUrlRewriter.rewrite(doubleQuoted) { "ABS:$it" }
        assertTrue(rewrittenDouble.contains("style=\"background:url('ABS:a.jpg');font-style:italic\""))

        val rewrittenSingle = HtmlUrlRewriter.rewrite(singleQuoted) { "ABS:$it" }
        assertTrue(rewrittenSingle.contains("style='background:url(\"ABS:a.jpg\");font-style:italic'"))

        val rewrittenTag = HtmlUrlRewriter.rewrite(styleTag) { "ABS:$it" }
        assertTrue(rewrittenTag.contains("url(\"ABS:a.jpg\")"))
        assertTrue(rewrittenTag.contains("font-style: italic;"))

        val rewrittenMulti = HtmlUrlRewriter.rewrite(multiUrl) { "ABS:$it" }
        assertTrue(rewrittenMulti.contains("url('ABS:a.jpg')"))
        assertTrue(rewrittenMulti.contains("url('ABS:b.png')"))
        assertTrue(rewrittenMulti.contains("font-style:italic"))
    }

    @Test
    fun absoluteDataAndAnchorUrlsAreUntouched() {
        val html = "<body><img src=\"https://x/y.png\"><img src=\"data:image/png;base64,AA\">" +
            "<a href=\"#top\">t</a><img src=\"\"></body>"
        val section = ChapterHtmlBuilder.epubSection(html, 0) { "ABS:$it" }
        assertTrue(section.contains("src=\"https://x/y.png\""))
        assertTrue(section.contains("src=\"data:image/png;base64,AA\""))
        assertTrue(section.contains("href=\"#top\""))
        assertTrue(section.contains("src=\"\""))
        assertFalse(section.contains("ABS:"))
    }

    @Test
    fun resolveRelativeHandlesDotDotDotAndRootEscape() {
        assertEquals("OEBPS/images/a.png", HtmlUrlRewriter.resolveRelative("OEBPS/text", "../images/a.png"))
        assertEquals("OEBPS/text/b.png", HtmlUrlRewriter.resolveRelative("OEBPS/text", "./b.png"))
        assertEquals("OEBPS/text/c.png", HtmlUrlRewriter.resolveRelative("OEBPS/text", "c.png"))
        assertEquals("img/d.png", HtmlUrlRewriter.resolveRelative("", "/img/d.png"))
        assertNull(HtmlUrlRewriter.resolveRelative("OEBPS", "../../etc/x.png"))
        // 查询串/锚点只参与定位，不进路径
        assertEquals("OEBPS/text/e.png", HtmlUrlRewriter.resolveRelative("OEBPS/text", "e.png#frag"))
    }

    @Test
    fun bodyInnerFallsBackToRawWhenNoBodyTag() {
        assertEquals("<p>裸片段</p>", ChapterHtmlBuilder.bodyInner("<p>裸片段</p>"))
        assertEquals("<p>内容</p>", ChapterHtmlBuilder.bodyInner("<html><body><p>内容</p></body></html>"))
    }
}
