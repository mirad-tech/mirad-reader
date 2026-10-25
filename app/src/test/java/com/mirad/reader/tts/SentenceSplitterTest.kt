package com.mirad.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSplitterTest {

    @Test
    fun splitsOnChinesePunctuation() {
        val text = "第一句话就在这里的。第二句话稍微长一点点！第三句也不短呢？"
        val sentences = SentenceSplitter.split(text)
        assertEquals(3, sentences.size)
        assertEquals("第一句话就在这里的。", sentences[0].text)
        assertEquals("第二句话稍微长一点点！", sentences[1].text)
        assertEquals("第三句也不短呢？", sentences[2].text)
    }

    @Test
    fun offsetsPointBackIntoTheSourceText() {
        val text = "他说：「今天天气不错。」然后转身离开，没有再回头。"
        for (sentence in SentenceSplitter.split(text)) {
            assertEquals(sentence.text, text.substring(sentence.start, sentence.end))
        }
    }

    @Test
    fun newlineEndsASentence() {
        val text = "第一行内容比较长一些\n第二行内容也不短啊\n"
        val sentences = SentenceSplitter.split(text)
        assertTrue(sentences.size >= 2)
        assertTrue(sentences.all { it.text.isNotBlank() })
    }

    @Test
    fun mergesVeryShortFragments() {
        val text = "好。我们继续往前走，去看一看那边的风景如何。"
        val sentences = SentenceSplitter.split(text)
        assertTrue("短句应并入下一句", sentences.none { it.text == "好。" })
        for (sentence in sentences) {
            assertEquals(sentence.text, text.substring(sentence.start, sentence.end))
        }
    }

    @Test
    fun veryLongParagraphIsSplitIntoChunks() {
        val text = "很长的一段话".repeat(40) + "。"
        val sentences = SentenceSplitter.split(text)
        assertTrue(sentences.size > 1)
        assertTrue(sentences.all { it.text.length <= SentenceSplitter.MAX_LENGTH + 8 })
    }

    @Test
    fun testShortSentenceMergeHasStrictBoundAndPreservesOffsets() {
        // F12: "好。" 重复 3000 次（共 6000 字符），不能合并成一条 6000 字符的单一巨句
        val text = "好。".repeat(3000)
        val sentences = SentenceSplitter.split(text)

        assertTrue("不能合并为一个巨句，必须拆分为多个有界分片", sentences.size > 1)
        for (sentence in sentences) {
            assertTrue("每个分片长度必须有严格上限（不超过 MAX_MERGE_LENGTH 200）", sentence.text.length <= SentenceSplitter.MAX_MERGE_LENGTH)
            assertEquals("分片偏移必须严格对应原文", sentence.text, text.substring(sentence.start, sentence.end))
        }
        // 验证顺序无丢失，全文覆盖
        assertEquals("第一句从 0 开始", 0, sentences.first().start)
        assertEquals("最后一句到结尾", text.length, sentences.last().end)
        for (i in 0 until sentences.size - 1) {
            assertEquals("相邻句子偏移连续无缝隙", sentences[i].end, sentences[i + 1].start)
        }
    }

    @Test
    fun testTrailingPunctuationCycleDoesNotCreateUnboundedFragment() {
        // F12: "好。" 后面跟随 5000 个双引号，不得吞入为 5002 字符的巨片
        val text = "好。" + "\"".repeat(5000)
        val sentences = SentenceSplitter.split(text)
        assertTrue("必须拆分为多个有界分片", sentences.size > 1)
        for (sentence in sentences) {
            assertTrue("每个分片长度必须有严格上限（不超过 MAX_MERGE_LENGTH 200）", sentence.text.length <= SentenceSplitter.MAX_MERGE_LENGTH)
            assertEquals("分片偏移必须严格对应原文", sentence.text, text.substring(sentence.start, sentence.end))
        }
        assertEquals(0, sentences.first().start)
        assertEquals(text.length, sentences.last().end)
    }

    @Test
    fun blankTextYieldsNothing() {
        assertTrue(SentenceSplitter.split("").isEmpty())
        assertTrue(SentenceSplitter.split("   \n  ").isEmpty())
    }
}
