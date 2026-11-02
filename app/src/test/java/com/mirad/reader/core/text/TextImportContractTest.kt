package com.mirad.reader.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 编码判断必须落实到真实流式产物，合法 UTF-8 不能被弱 UTF-16 猜测改写。 */
class TextImportContractTest {
    @get:Rule val temp = TemporaryFolder()

    private val utf8Samples = listOf(
        "The quick brown fox",
        "The quick brown fox\n",
        "The quick brown fox\nThe second paragraph is also ordinary English.\n",
        "第一章 开始\nThe quick brown fox 与中文正文、emoji 😀 混排。\n"
    )

    @Test fun strictUtf8ContentWinsOverWeakUtf16Distribution() {
        utf8Samples.forEach { text ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            assertTrue(TextCodec.isValidUtf8(bytes))
            val actual = TextCodec.decode(bytes)
            assertEquals("合法 UTF-8 原文必须保留：$text", text, actual.text)
            assertEquals("UTF-8", actual.charsetName)
        }
    }

    @Test fun streamingImportPreservesOddEvenAndMultilineUtf8Content() {
        utf8Samples.forEachIndexed { index, text ->
            val source = temp.newFile("source-$index.txt").apply { writeText(text, Charsets.UTF_8) }
            val output = temp.newFile("normalized-$index.txt")
            val result = StreamingTxtProcessor.process(source, output)
            val expected = if (text.endsWith('\n')) text else "$text\n"
            assertEquals("UTF-8", result.charset)
            assertEquals(expected, output.readText(Charsets.UTF_8))
            assertEquals(expected.toByteArray(Charsets.UTF_8).size.toLong(), result.totalBytes)
        }
    }

    @Test fun explicitUtf16AndAlternatingNulEvidenceStillWork() {
        val text = "Chapter 1\nThis is a sufficiently long UTF-16 sample.\n"
        for (charset in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val bytes = text.toByteArray(charset)
            assertEquals(text, TextCodec.decode(bytes).text)
            assertEquals(text, TextCodec.decode(bytes, manual = charset.name()).text)
            val source = temp.newFile("${charset.name()}.txt").apply { writeBytes(bytes) }
            val target = temp.newFile("${charset.name()}-normalized.txt")
            StreamingTxtProcessor.process(source, target)
            assertEquals(text, target.readText(Charsets.UTF_8))
        }
    }
}
