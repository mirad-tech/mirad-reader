package com.mirad.reader.core.text

import org.junit.Assert.*
import org.junit.Test

class ChapterRecognitionRegressionTest {
    @Test fun shortStoryLongPunctuatedTitlesAndMixedMarkers() {
        val titles = listOf("序章", "【第一章】我走过了很长很长的道路，终于回到了那个熟悉的家！", "第二章 你终于来了？", "番外 重逢")
        val text = titles.joinToString("\n短篇正文。\n", postfix = "\n完。")
        val detection = ChapterDetector.detect(text)
        assertEquals(titles, detection.marks.map { it.title })
        assertEquals(titles, ChapterSplitter.split(text, detection).map { it.title })
    }
    @Test fun rejectedProseIsNotRescannedBySplitter() {
        val text = "第一章 初见\n第一章的故事已经结束了。\n第二章 重逢\n尾声的内容很长。\n完。"
        val detection = ChapterDetector.detect(text)
        assertEquals(listOf("第一章 初见", "第二章 重逢"), ChapterSplitter.split(text, detection).map { it.title })
    }
    @Test fun chapterNumbersCanRestartAfterVolume() {
        val text = (1..2).joinToString("\n") { volume -> "第${volume}卷\n" + (1..4).joinToString("\n") { "第${it}章\n故事内容。" } }
        val detection = ChapterDetector.detect(text)
        assertEquals(10, detection.marks.size)
        assertEquals(2, detection.marks.count { it.isVolume })
    }
    @Test fun singleChapterIsNotRejectedForBeingShort() {
        assertEquals(1, ChapterDetector.detect("第一章 开始\n一个短故事。").hits)
    }
    @Test fun mixedUtf8AndEmojiPartitionExactly() {
        val text = "简介😀\n第一章 你好！\n他回来了😀\n【第二章】再会\n结束。"
        val bytes = text.toByteArray()
        val slices = ChapterSplitter.split(text, ChapterDetector.detect(text))
        assertEquals(text, slices.joinToString("") { bytes.copyOfRange(it.startByte.toInt(), it.endByte.toInt()).toString(Charsets.UTF_8) })
        assertEquals(text.length, slices.sumOf { it.charCount })
    }
}
