package com.mirad.reader.core.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterHtmlBuilderTest {

    @Test
    fun buildsParagraphsFromPlainText() {
        val html = ChapterHtmlBuilder.build(
            title = "第一章 出发",
            bodyText = "第一章 出发\n他背上行囊。\n\n城门在身后。",
            chapterIndex = 0,
            titleLineChars = "第一章 出发\n".length
        )
        assertTrue(html.contains("id=\"${ChapterHtmlBuilder.CONTENT_ID}\""))
        assertTrue(html.contains("<h2 class=\"mirad-chapter-title\">第一章 出发</h2>"))
        assertTrue(html.contains("<p>他背上行囊。</p>"))
        assertTrue(html.contains("<p>城门在身后。</p>"))
        assertTrue(!html.contains("<p>第一章 出发</p>"))
        assertEquals(2, Regex("<p>").findAll(html).count())
    }

    @Test
    fun escapesMarkupInsideText() {
        val html = ChapterHtmlBuilder.build("t", "<script>alert(1)</script>", 0)
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(!html.contains("<script>"))
    }

    @Test
    fun wrapBodyKeepsOriginalMarkupInsideContainer() {
        val xhtml = "<html><head><title>x</title></head><body class=\"c\"><p>原文</p><img src=\"a.png\"/></body></html>"
        val wrapped = ChapterHtmlBuilder.wrapBody(xhtml, 3)
        assertTrue(wrapped.contains("<div id=\"${ChapterHtmlBuilder.CONTENT_ID}\" data-chapter=\"3\">"))
        assertTrue(wrapped.contains("<p>原文</p>"))
        assertTrue(wrapped.contains("<img src=\"a.png\"/>"))
        val openIdx = wrapped.indexOf("<div id=")
        val closeIdx = wrapped.indexOf("</div>")
        assertTrue(openIdx in 1 until closeIdx)
        assertTrue(wrapped.substring(closeIdx).startsWith("</div></body>"))
    }

    @Test
    fun wrapBodyFallsBackWhenNoBodyTag() {
        val wrapped = ChapterHtmlBuilder.wrapBody("<p>纯片段</p>", 1)
        assertTrue(wrapped.startsWith("<div id=\"${ChapterHtmlBuilder.CONTENT_ID}\""))
        assertTrue(wrapped.endsWith("</div>"))
    }
}
