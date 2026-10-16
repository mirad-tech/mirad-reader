package com.mirad.reader.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterSplitterTest {

    private val novel = buildString {
        append("作品简介\n这是一本测试用的小说。\n\n")
        append("第一章 初入江湖\n")
        append("李慕白提剑站在山巅，望着远处的云海。\n")
        append("风起于青萍之末。\n\n")
        append("第二章 风起云涌\n")
        append("山下的镇子依旧热闹。\n")
        append("少年攥紧了手里的铜钱。\n\n")
        append("第三章 大雨将至\n")
        append("雨点落在青石板上，溅起细碎的水花。\n")
    }

    @Test
    fun splitsByChineseChapterRule() {
        val slices = ChapterSplitter.split(novel, ChapterRules.CN_GENERIC_RULE)
        assertEquals(4, slices.size)
        assertEquals("前言", slices[0].title)
        assertEquals("第一章 初入江湖", slices[1].title)
        assertEquals("第二章 风起云涌", slices[2].title)
        assertEquals("第三章 大雨将至", slices[3].title)
    }

    @Test
    fun chaptersPartitionTheWholeTextWithoutGaps() {
        val slices = ChapterSplitter.split(novel, ChapterRules.CN_GENERIC_RULE)
        assertEquals(0, slices.first().startChar)
        assertEquals(novel.length, slices.last().endChar)
        for (i in 0 until slices.size - 1) {
            assertEquals(slices[i].endChar, slices[i + 1].startChar)
            assertEquals(slices[i].endByte, slices[i + 1].startByte)
        }
        val totalBytes = TextNormalizer.utf8Length(novel)
        assertEquals(totalBytes, slices.last().endByte)
    }

    @Test
    fun byteOffsetsMatchUtf8Slices() {
        val text = "第一章 开始\n正文内容一二三。\n第二章 结束\n结尾。\n"
        val slices = ChapterSplitter.split(text, ChapterRules.CN_GENERIC_RULE)
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (slice in slices) {
            val expected = String(bytes, slice.startByte.toInt(), (slice.endByte - slice.startByte).toInt(), Charsets.UTF_8)
            assertEquals(text.substring(slice.startChar, slice.endChar), expected)
        }
    }

    @Test
    fun titleLineLengthIsRecorded() {
        val slices = ChapterSplitter.split(novel, ChapterRules.CN_GENERIC_RULE)
        assertEquals(0, slices[0].titleLineChars)
        assertEquals("第一章 初入江湖\n".length, slices[1].titleLineChars)
        assertEquals("第三章 大雨将至\n".length, slices[3].titleLineChars)
    }

    @Test
    fun fallsBackToChunkedSectionsWhenNoRuleMatches() {
        val text = (1..600).joinToString("\n") { "这是第 $it 行没有章节标记的正文内容。" }
        val slices = ChapterSplitter.split(text, ChapterRules.CN_GENERIC_RULE, fallbackChunkChars = 800)
        assertTrue(slices.size > 5)
        assertTrue(slices.all { it.title.startsWith("第 ") && it.title.endsWith(" 节") })
        assertEquals(text.length, slices.last().endChar)
    }

    @Test
    fun volumeLinesBecomeSeparateSlices() {
        val text = "第一卷 少年游\n第一章 出发\n他背上行囊。\n第二卷 江湖路\n第二章 相遇\n她撑着伞。\n"
        val slices = ChapterSplitter.split(text, ChapterRules.CN_GENERIC_RULE)
        assertEquals(4, slices.size)
        assertTrue(slices[0].isVolume)
        assertEquals("第一卷 少年游", slices[0].title)
        assertEquals("第一章 出发", slices[1].title)
        assertTrue(slices[2].isVolume)
        assertEquals("第二章 相遇", slices[3].title)
    }

    @Test
    fun emptyTextYieldsNoChapters() {
        assertEquals(0, ChapterSplitter.split("", ChapterRules.CN_GENERIC_RULE).size)
    }

    @Test
    fun autoDetectPicksTheMostFrequentRule() {
        val text = "第一章 甲\n内容\n第二章 乙\n内容\n第三章 丙\n内容\n"
        val (rule, count) = ChapterRules.autoDetect(text)
        assertEquals(3, count)
        assertTrue(rule != null && rule.id == ChapterRules.CN_GENERIC)
    }

    @Test
    fun bracketChapterRuleMatches() {
        val text = "【第一章】天地\n内容一\n【第二章】玄黄\n内容二\n"
        val slices = ChapterSplitter.split(text, ChapterRules.CN_BRACKET_RULE)
        assertEquals(2, slices.size)
        assertEquals("【第一章】天地", slices[0].title)
    }
}
