package com.mirad.reader.data.repo

import com.mirad.reader.data.db.entity.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatcherTest {

    private fun chapter(index: Int = 0, title: String = "第 ${index + 1} 章") =
        ChapterEntity(bookId = 1L, index = index, title = title)

    @Test
    fun findsMatchesWithAccurateOffsets() {
        val text = "前半句内容。STARFIELD 标记出现在这里，后面还有一些文字。"
        val hits = SearchMatcher.match(chapter(1, "第二章 碑文"), text, "STARFIELD")

        assertEquals(1, hits.size)
        val hit = hits[0]
        assertEquals(1, hit.chapterIndex)
        assertEquals("第二章 碑文", hit.chapterTitle)
        assertEquals("STARFIELD", hit.match)
        assertEquals(text.indexOf("STARFIELD"), hit.charOffset)
        // 片段中的高亮区间必须能对上原始命中词
        assertEquals("STARFIELD", hit.snippet.substring(hit.matchStart, hit.matchEnd))
    }

    @Test
    fun isCaseInsensitiveByDefaultAndSensitiveOnDemand() {
        val text = "MiradReader 与 miradreader 是同一个词的两种写法。"
        assertEquals(2, SearchMatcher.match(chapter(), text, "miradreader").size)

        val sensitive = SearchMatcher.match(
            chapter(),
            text,
            "miradreader",
            SearchOptions(caseSensitive = true)
        )
        assertEquals(1, sensitive.size)
        assertEquals(text.lastIndexOf("miradreader"), sensitive[0].charOffset)
    }

    @Test
    fun respectsLimit() {
        val text = "词 ".repeat(20)
        val hits = SearchMatcher.match(chapter(), text, "词", SearchOptions(limitPerChapter = 3), limit = 3)
        assertEquals(3, hits.size)
    }

    @Test
    fun supportsChineseQueries() {
        val text = "观星台建在半山腰，青石垒成的台阶一共九百九十九级。守台人常把这句话挂在嘴边。"
        val hits = SearchMatcher.match(chapter(), text, "观星台")
        assertEquals(1, hits.size)
        assertEquals(text.indexOf("观星台"), hits[0].charOffset)
        assertTrue(hits[0].snippet.contains("观星台"))
    }

    @Test
    fun snippetStaysWithinBoundsForEdgeMatches() {
        val hits = SearchMatcher.match(chapter(), "start", "start")
        assertEquals(1, hits.size)
        assertEquals(0, hits[0].matchStart)
        assertEquals(5, hits[0].matchEnd)
        assertEquals("start", hits[0].snippet)
    }

    @Test
    fun multilineSnippetKeepsMatchHighlightAligned() {
        val text = "第一段内容写得比较长一些。\n    第二段里出现了关键字 STARFIELD，\n第三段继续。"
        val hits = SearchMatcher.match(chapter(), text, "STARFIELD")
        assertEquals(1, hits.size)
        assertEquals("STARFIELD", hits[0].snippet.substring(hits[0].matchStart, hits[0].matchEnd))
        assertTrue(!hits[0].snippet.contains('\n'))
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertTrue(SearchMatcher.match(chapter(), "没有任何关键字", "STARFIELD").isEmpty())
        assertTrue(SearchMatcher.match(chapter(), "", "x").isEmpty())
        assertTrue(SearchMatcher.match(chapter(), "text", "").isEmpty())
    }
}
