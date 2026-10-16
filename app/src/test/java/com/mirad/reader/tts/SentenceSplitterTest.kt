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
    fun blankTextYieldsNothing() {
        assertTrue(SentenceSplitter.split("").isEmpty())
        assertTrue(SentenceSplitter.split("   \n  ").isEmpty())
    }
}
