package com.mirad.reader.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 同一真实归一化文件分别进入导入与重新识别路径，目录和正文必须一致。 */
class StreamingChapterContractTest {
    @get:Rule val temp = TemporaryFolder()

    private fun assertSameChapters(text: String, expectedTitles: List<String>) {
        val source = temp.newFile().apply { writeText(text, Charsets.UTF_8) }
        val output = temp.newFile()
        val imported = StreamingTxtProcessor.process(source, output, "UTF-8")
        val normalized = output.readText(Charsets.UTF_8)
        val detected = ChapterDetector.detect(normalized)
        val reidentified = ChapterSplitter.split(normalized, detected)
        assertEquals(expectedTitles, reidentified.map { it.title })
        assertEquals(expectedTitles, imported.slices.map { it.title })
        assertEquals("导入和重新识别必须使用相同字符/字节边界", reidentified, imported.slices)
        val bytes = output.readBytes()
        assertEquals(normalized, imported.slices.joinToString("") {
            bytes.copyOfRange(it.startByte.toInt(), it.endByte.toInt()).toString(Charsets.UTF_8)
        })
        assertEquals(if (text.endsWith('\n')) text else "$text\n", normalized)
    }

    @Test fun mixedChineseAndEnglishStrongTitlesAgree() {
        val titles = listOf("Chapter 1 First", "第2章 中文", "Chapter 3 Third")
        assertSameChapters(titles.joinToString("\n正文与 English、😀 共同组成这一章。\n", postfix = "\n结束。\n"), titles)
    }

    @Test fun strongTitleFirstAppearingBeyondTheSampleIsStillDetected() {
        val preface = "这里是没有章节标记的前言与正文😀。\n".repeat(4000)
        assertTrue(preface.toByteArray().size > 64 * 1024)
        assertSameChapters(preface + "Chapter 1 Late\n迟到的英文章节。\n第2章 继续\n中文章正文。\n",
            listOf("前言", "Chapter 1 Late", "第2章 继续"))
    }

    @Test fun laterStrongRuleDoesNotRetainEarlierWeakNumberedCandidates() {
        val prefix = (1..3).joinToString("") { index ->
            "$index 记录\n" + "这一段只是前言中的有序记录，并非正式章节。\n".repeat(1600)
        }
        assertSameChapters(prefix + "Chapter 1 正式开始\n正式内容。\n第2章 正式继续\n正式内容。\n",
            listOf("前言", "Chapter 1 正式开始", "第2章 正式继续"))
    }

    @Test fun chineseEnglishBracketsVolumesAndWeakNumbersKeepTheirContracts() {
        val fixtures = listOf(
            listOf("第一章 开始", "第二章 继续"),
            listOf("Chapter 1 First", "Chapter 2 Second"),
            listOf("序章", "【第一章】开始", "第二章 继续", "外传 重逢"),
            listOf("第一卷 山中", "第一章 开始", "第二章 继续", "第二卷 海边", "第一章 重启"),
            listOf("1 初见", "2 重逢", "3 归途")
        )
        fixtures.forEach { titles ->
            val text = titles.joinToString("") { title -> "$title\n" + "完整正文包含中文、English及😀，用于验证连续边界。\n".repeat(8) }
            assertSameChapters(text, titles)
        }
    }

    @Test fun nonConsecutiveWeakNumbersRemainRejected() {
        val text = listOf("3 第一项", "7 第二项", "2019 第三项").joinToString("") {
            "$it\n" + "正文内容足够长，但编号本身不应被当成可靠章节。\n".repeat(8)
        }
        val source = temp.newFile().apply { writeText(text) }
        val output = temp.newFile()
        val imported = StreamingTxtProcessor.process(source, output, "UTF-8")
        assertTrue(ChapterDetector.detect(output.readText()).marks.isEmpty())
        assertNull(imported.ruleId)
        assertEquals(text, output.readText())
    }
}
