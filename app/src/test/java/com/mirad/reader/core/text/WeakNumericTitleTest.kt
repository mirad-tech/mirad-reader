package com.mirad.reader.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弱数字标题（"1 初见"这类）的识别：必须靠"连续编号 + 篇幅均匀"判定，
 * 既不能把数字列表当章节，也不能因为章短就整本退化成自动分节。
 */
class WeakNumericTitleTest {

    private val body = "他推开门，风灌进来，桌上的稿纸翻了两页。\n"

    /** 四个弱标题 + 两行数字开头的正文干扰（真实样例：样例三_弱数字标题.txt）。 */
    private val mixed = buildString {
        append("1 初见\n").append(body.repeat(12))
        append("2 重逢\n").append(body.repeat(12))
        append("3 别离\n").append(body.repeat(12))
        append("\n2019 年他在城南租了个铺子，那时谁也没想到后来会如何。\n")
        append("他数了数：3 个人，5 袋米，一共 800 块钱。\n")
        append("4 归人\n").append(body.repeat(12))
    }

    @Test
    fun shortWeakNumberedChaptersAreRecognized() {
        val d = ChapterDetector.detect(mixed)
        assertEquals(listOf("1 初见", "2 重逢", "3 别离", "4 归人"), d.marks.map { it.title })
    }

    @Test
    fun decoyLinesAreNotTreatedAsTitles() {
        val titles = ChapterDetector.detect(mixed).marks.map { it.title }
        assertTrue(titles.none { it.startsWith("2019") })
        assertTrue(titles.none { it.contains("袋米") })
    }

    @Test
    fun nonConsecutiveNumbersAreRejected() {
        val text = buildString {
            append("3 第一节\n").append(body.repeat(12))
            append("7 第二节\n").append(body.repeat(12))
            append("2019 第三节\n").append(body.repeat(12))
        }
        // 编号不连续 → 不应当作章节（宁可按篇幅分节）
        assertTrue(ChapterDetector.detect(text).marks.isEmpty())
    }

    @Test
    fun splitterConsumesTheSameMarks() {
        val detection = ChapterDetector.detect(mixed)
        val slices = ChapterSplitter.split(mixed, detection)
        assertEquals(4, slices.size)
        assertEquals(listOf("1 初见", "2 重逢", "3 别离", "4 归人"), slices.map { it.title })
        // 切分结果是正文的连续完整覆盖
        assertEquals(mixed.length, slices.sumOf { it.charCount })
    }
}
