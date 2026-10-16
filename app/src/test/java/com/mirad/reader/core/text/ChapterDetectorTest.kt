package com.mirad.reader.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterDetectorTest {

    /** 生成一段长度可控的正文。 */
    private fun body(chars: Int, seed: String = "他走在长街上，风从巷口吹来，卷起地上的落叶。"): String {
        val sb = StringBuilder(chars + 64)
        while (sb.length < chars) sb.append(seed)
        return sb.toString()
    }

    private fun novel(chapters: Int, charsPerChapter: Int = 600): String {
        val sb = StringBuilder()
        for (i in 1..chapters) {
            sb.append("第").append(i).append("章 第").append(i).append("次远行\n")
            sb.append(body(charsPerChapter)).append('\n')
        }
        return sb.toString()
    }

    @Test
    fun keywordRuleWinsOverNumericNoise() {
        // 正文里塞满 "3.5公里""1、他走进屋子" 这类数字开头的行，旧的"命中最多者胜"会选数字规则。
        val noisy = StringBuilder()
        for (i in 1..20) {
            noisy.append("第").append(i).append("章 风起\n")
            noisy.append(body(500)).append('\n')
            noisy.append("3.5公里外，雪停了。\n")
            noisy.append("1、他走进屋子，把伞立在门边。\n")
            noisy.append("2、屋里的炭火烧得正旺，暖意扑面而来。\n")
        }
        val detection = ChapterDetector.detect(noisy.toString())
        assertEquals(ChapterRules.CN_GENERIC, detection.rule?.id)
        assertEquals(20, detection.hits)
    }

    @Test
    fun proseLineStartingWithChapterKeywordIsRejected() {
        val text = buildString {
            append("第1章 起\n").append(body(700)).append('\n')
            append("第一章的内容我已经看过了，不必再说一遍。\n")
            append(body(700)).append('\n')
            append("第2章 承\n").append(body(700)).append('\n')
            append("第3章 转\n").append(body(700)).append('\n')
            append("第4章 合\n").append(body(700)).append('\n')
        }
        val detection = ChapterDetector.detect(text)
        assertEquals(ChapterRules.CN_GENERIC, detection.rule?.id)
        // "第一章的内容我已经看过了" 不应被算作一章
        assertEquals(4, detection.hits)
    }

    @Test
    fun pureNumericListIsRejectedByDensity() {
        val text = buildString {
            for (i in 1..40) {
                append(i).append("、小事一桩。\n")
            }
            append(body(3000))
        }
        val detection = ChapterDetector.detect(text)
        // 平均每章不足 300 字 → 判为误判，退回自动分节
        assertNull(detection.rule)
        assertTrue(detection.reason.contains("自动分节"))
    }

    @Test
    fun numberedChaptersAreStillDetectedWhenGenuine() {
        val sb = StringBuilder()
        for (i in 1..12) {
            sb.append(i).append(". 第").append(i).append("节\n")
            sb.append(body(600)).append('\n')
        }
        val detection = ChapterDetector.detect(sb.toString())
        assertEquals(ChapterRules.NUMBERED, detection.rule?.id)
        assertEquals(12, detection.hits)
    }

    @Test
    fun noMarkersMeansAutoSplit() {
        val detection = ChapterDetector.detect(body(9000))
        assertNull(detection.rule)
        assertTrue(detection.reason.contains("自动分节"))
    }

    @Test
    fun volumeOnlyBookFallsBackToVolumeRule() {
        val sb = StringBuilder()
        for (i in 1..4) {
            sb.append("第").append(i).append("卷 山河\n")
            sb.append(body(1200)).append('\n')
        }
        val detection = ChapterDetector.detect(sb.toString())
        assertEquals(ChapterRules.CN_VOLUME_ONLY, detection.rule?.id)
    }

    @Test
    fun englishChaptersDetected() {
        val sb = StringBuilder()
        for (i in 1..6) {
            sb.append("Chapter ").append(i).append('\n')
            sb.append(body(600, "The road was long and the wind was cold. ")).append('\n')
        }
        val detection = ChapterDetector.detect(sb.toString())
        assertEquals(ChapterRules.ENGLISH, detection.rule?.id)
    }

    @Test
    fun nonMonotonicNumbersAreRejected() {
        // 序号来回跳说明不是真正的章节（例如正文里的度量数字）
        val sb = StringBuilder()
        val order = listOf(9, 3, 12, 2, 7, 15, 1, 8, 4, 11)
        for (n in order) {
            sb.append("第").append(n).append("章 乱序\n")
            sb.append(body(700)).append('\n')
        }
        val detection = ChapterDetector.detect(sb.toString())
        assertNull(detection.rule)
    }

    @Test
    fun titlesPreviewIsProvided() {
        val detection = ChapterDetector.detect(novel(6))
        assertTrue(detection.titlesPreview.isNotEmpty())
        assertEquals("第1章 第1次远行", detection.titlesPreview.first())
        assertTrue(detection.reason.contains("平均"))
    }
}
