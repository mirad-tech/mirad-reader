package com.mirad.reader.core.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTest {

    @Test
    fun escapeHandlesMarkupCharacters() {
        assertEquals("&lt;p&gt;a &amp; b&lt;/p&gt;", Html.escape("<p>a & b</p>"))
        assertEquals("it&#39;s", Html.escape("it's"))
    }

    @Test
    fun unescapeHandlesNamedAndNumericEntities() {
        assertEquals("a & b < c", Html.unescape("a &amp; b &lt; c"))
        assertEquals("中文\u2014ok", Html.unescape("中文&mdash;ok"))
        assertEquals("A中", Html.unescape("&#65;&#20013;"))
        assertEquals("&unknown;", Html.unescape("&unknown;"))
    }

    @Test
    fun toPlainTextKeepsParagraphBreaks() {
        val html = "<html><head><style>p{color:red}</style></head><body>" +
            "<h2>第一章</h2><p>第一段。</p><p>第二段<br/>换行。</p>" +
            "<script>var a=1;</script></body></html>"
        val text = Html.toPlainText(html)
        assertTrue(text.contains("第一章"))
        assertTrue(text.contains("第一段。"))
        assertTrue(text.contains("第二段\n换行。"))
        assertTrue(!text.contains("var a=1"))
        assertTrue(!text.contains("color:red"))
    }

    @Test
    fun firstHeadingTextFindsHeading() {
        assertEquals("第一章 出发", Html.firstHeadingText("<body><h2>第一章 出发</h2><p>x</p></body>"))
        assertNull(Html.firstHeadingText("<body><p>没有标题</p></body>"))
    }

    @Test
    fun sanitizeXmlRemovesDoctypeAndUndefinedEntities() {
        val xml = "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" \"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">" +
            "<html><body><p>a&nbsp;b &amp; c</p></body></html>"
        val sanitized = Html.sanitizeXml(xml)
        assertTrue(!sanitized.contains("DOCTYPE"))
        assertTrue(sanitized.contains("&#160;"))
        assertTrue(sanitized.contains("&amp;"))
    }
}
