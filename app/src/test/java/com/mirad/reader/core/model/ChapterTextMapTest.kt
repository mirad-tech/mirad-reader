package com.mirad.reader.core.model

import org.junit.Assert.*
import org.junit.Test

class ChapterTextMapTest {
    @Test fun trimmedParagraphsAndTitleUseReaderBlockOffsets() {
        val body = "第一章\n  甲乙  \n\n 丙丁\n"
        val map = ChapterTextMap(body, "第一章", 4, false, 100)
        assertEquals("第一章\n甲乙\n丙丁", map.text)
        assertEquals(100 + body.indexOf('丙'), map.sourceAt(map.text.indexOf('丙')))
        assertEquals(map.text.indexOf('乙'), map.domAt(100 + body.indexOf('乙')))
    }
    @Test fun syntheticTitleHasNoFalseSourceAnchor() {
        val map = ChapterTextMap("甲乙\n丙丁", "自动分节", 0, false, 0)
        assertNull(map.sourceAt(0))
        assertEquals(0, map.sourceAt(map.text.indexOf('甲')))
    }
    @Test fun crossChapterHighlightSplitsAndKeepsAllSelectedSourceText() {
        val source = "第一章\n  甲乙  \n\n第二章\n 丙丁\n"
        val old = ChapterTextMap(source, "旧分节", 0, false, 0)
        val cut = source.indexOf("第二章")
        val targets = listOf(ChapterTextMap(source.take(cut), "第一章", 4, false, 0),
            ChapterTextMap(source.substring(cut), "第二章", 4, false, cut))
        val start = old.text.indexOf('乙')
        val end = old.text.indexOf('丁') + 1
        val ranges = old.migrateRange(start, end, old.text.substring(start, end), targets)!!
        assertEquals(2, ranges.size)
        assertEquals("乙", ranges[0].text)
        assertEquals("第二章\n丙丁", ranges[1].text)
        assertEquals(1, ranges[1].chapter)
    }
    @Test fun staleTextAndOffsetsAreRejected() {
        val map = ChapterTextMap("甲乙", "", 0, false, 0)
        assertNull(map.migrateRange(0, 1, "丙", listOf(map)))
        assertNull(map.migrateRange(-1, 1, "甲", listOf(map)))
        assertNull(map.migrateRange(0, 9, "甲", listOf(map)))
    }
    @Test fun hiddenVolumeHeadingCannotSilentlyLoseSelectedText() {
        val old = ChapterTextMap("第一卷\n正文", "", 0, false, 0)
        val new = ChapterTextMap("第一卷\n正文", "第一卷", 4, true, 0)
        assertNull(old.migrateRange(0, old.text.length, old.text, listOf(new)))
    }
    @Test fun emojiUsesUtf16OffsetsLikeJavascript() {
        val map = ChapterTextMap("  甲😀乙\n", "", 0, false, 10)
        assertEquals(15, map.sourceAt(3))
        assertEquals(3, map.domAt(15))
    }
}
