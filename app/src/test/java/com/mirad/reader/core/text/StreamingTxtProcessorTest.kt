package com.mirad.reader.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.nio.charset.Charset

class StreamingTxtProcessorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun streamingAndInMemoryKeepIdenticalChapterRanges() {
        val chapters = (1..6).joinToString("\n") { "第${it}章 山路\n中文与 emoji 🌲，以及 English。\n" }
        listOf(chapters, "标题之前的正文。\n$chapters", "第一卷 山中\n$chapters\n第二卷 海边\n$chapters")
            .forEach { text ->
                val source = tempFolder.newFile().apply { writeText(text) }
                val normalized = tempFolder.newFile()
                val streamed = StreamingTxtProcessor.process(source, normalized, "UTF-8")
                val content = normalized.readText()
                val detection = ChapterDetector.detect(content)
                assertTrue("夹具必须命中标题识别路径", detection.marks.isNotEmpty())
                assertEquals(ChapterSplitter.split(content, detection), streamed.slices)
            }
    }

    @Test
    fun testChineseRepeated5000Times90000BytesUtf8Integrity() {
        // F01 P1: "中文内容测试" 重复 5000 次的 90000 字节无 BOM UTF-8，
        // 采样截断发生在 64 KiB 边界（byte 65535 恰为第 6 个字符的第 1 个字节），
        // 采样检测不能误判为 GB18030，必须识别为 UTF-8 且全文一致
        val src = tempFolder.newFile("sample_90000_utf8.txt")
        val content = "中文内容测试".repeat(5000)
        src.writeBytes(content.toByteArray(Charsets.UTF_8))
        assertEquals(90000L, src.length())

        val target = tempFolder.newFile("normalized_90000.txt")
        val result = StreamingTxtProcessor.process(src, target)

        assertEquals("UTF-8", result.charset)
        val normalized = target.readText(Charsets.UTF_8)
        assertEquals(content + "\n", normalized)
    }

    @Test
    fun testStreamingUtf8WithChapters() {
        val src = tempFolder.newFile("sample_utf8.txt")
        val content = """
            序章 梦境
            这里是序章的正文内容，讲述古老传说的开始。
            第1章 离别
            微风拂过柳梢，少年收拾行囊踏上征途。
            第2章 相遇
            漫漫长路，他在客栈遇到了一位背剑的侠客。
        """.trimIndent()
        src.writeText(content, Charsets.UTF_8)

        val target = tempFolder.newFile("normalized.txt")
        val result = StreamingTxtProcessor.process(src, target)

        assertEquals("UTF-8", result.charset)
        assertTrue(result.slices.size >= 3)
        assertEquals("序章 梦境", result.slices[0].title)
        assertEquals("第1章 离别", result.slices[1].title)
        assertEquals("第2章 相遇", result.slices[2].title)

        // 验证 startByte 与 endByte 的正确切片
        val raf = RandomAccessFile(target, "r")
        val slice1 = result.slices[1]
        val len = (slice1.endByte - slice1.startByte).toInt()
        val bytes = ByteArray(len)
        raf.seek(slice1.startByte)
        raf.readFully(bytes)
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(text.startsWith("第1章 离别"))
        raf.close()
    }

    @Test
    fun testStreamingGbkFile() {
        val src = tempFolder.newFile("sample_gbk.txt")
        val gbk = Charset.forName("GB18030")
        val content = """
            引子
            江南小镇，烟雨蒙蒙。
            第一章 少年入江湖
            他腰悬铁剑，走入酒家。
            第二章 试剑天下
            剑影交错，声震四方。
        """.trimIndent()
        src.writeBytes(content.toByteArray(gbk))

        val target = tempFolder.newFile("normalized_gbk.txt")
        val result = StreamingTxtProcessor.process(src, target)

        assertTrue(result.charset.contains("GB", ignoreCase = true))
        assertTrue(result.slices.size >= 3)
        assertEquals("引子", result.slices[0].title)
        assertEquals("第一章 少年入江湖", result.slices[1].title)

        // 验证归一化后的输出为 UTF-8
        val outText = target.readText(Charsets.UTF_8)
        assertTrue(outText.contains("少年入江湖"))
    }

    /**
     * 针对 Reviewer P1-1 的回归测试：3000 个 a + 3000 个“中”。
     * 验证：所有切片必须 100% 严格可解码为 UTF-8，且切片拼接后与全文件逐字符完全相等。
     */
    @Test
    fun testFallbackSlicesByteAlignmentWithMixedLengthChars() {
        val src = tempFolder.newFile("mixed_chars.txt")
        // 构造 3000 个 a 和 3000 个 '中'
        val raw = "a".repeat(3000) + "\n" + "中".repeat(3000)
        src.writeText(raw, Charsets.UTF_8)

        val target = tempFolder.newFile("target_mixed.txt")
        val result = StreamingTxtProcessor.process(src, target)

        assertTrue(result.slices.size >= 2)

        val raf = RandomAccessFile(target, "r")
        val fullText = target.readText(Charsets.UTF_8)
        val reconstructed = StringBuilder()

        for (slice in result.slices) {
            val len = (slice.endByte - slice.startByte).toInt()
            assertTrue("切片长度必须大于0", len > 0)
            val bytes = ByteArray(len)
            raf.seek(slice.startByte)
            raf.readFully(bytes)
            // 严格验证：绝不发生 UTF-8 解码截断（包含不可逆问号等）
            val text = String(bytes, Charsets.UTF_8)
            assertEquals("切片字符数必须与声明的 charCount 完全一致", slice.charCount, text.length)
            reconstructed.append(text)
        }
        raf.close()

        assertEquals("逐切片拼接内容必须与全书正文完全逐字一致", fullText, reconstructed.toString())
    }

    /**
     * 针对 Reviewer P1-1 的回归测试：Emoji 和生僻字（代理对）。
     */
    @Test
    fun testFallbackSlicesEmojiAndSurrogates() {
        val src = tempFolder.newFile("emoji_surrogates.txt")
        // "中😀𠮷a" 重复 1200 次（包含 1 字节、3 字节、4 字节代理对）
        val pattern = "中😀𠮷a\n"
        val raw = pattern.repeat(1200)
        src.writeText(raw, Charsets.UTF_8)

        val target = tempFolder.newFile("target_emoji.txt")
        val result = StreamingTxtProcessor.process(src, target)

        assertTrue(result.slices.size >= 2)

        val raf = RandomAccessFile(target, "r")
        val fullText = target.readText(Charsets.UTF_8)
        val reconstructed = StringBuilder()

        for (slice in result.slices) {
            val len = (slice.endByte - slice.startByte).toInt()
            val bytes = ByteArray(len)
            raf.seek(slice.startByte)
            raf.readFully(bytes)
            val text = String(bytes, Charsets.UTF_8)
            assertEquals(slice.charCount, text.length)
            reconstructed.append(text)
        }
        raf.close()

        assertEquals(fullText, reconstructed.toString())
    }

    /**
     * 针对 Reviewer P1-6 的回归测试：超长单行文件不爆内存。
     */
    @Test
    fun testLongSingleLineWithoutNewlines() {
        val src = tempFolder.newFile("single_line_huge.txt")
        // 构造 100,000 个连续字符单行
        val raw = "长".repeat(100000)
        src.writeText(raw, Charsets.UTF_8)

        val target = tempFolder.newFile("target_single_line.txt")
        val result = StreamingTxtProcessor.process(src, target)

        assertTrue(result.slices.isNotEmpty())
        assertTrue(target.length() > 0)
    }

    /**
     * 针对 Reviewer Round 2 P1-1 的回归测试：
     * 8191 个 a + 😀 + 30 个中
     * 必须保证代理对绝不被破坏变成 '?'，切片字节数与目标文件完全对齐，读末切片绝不越过 EOF。
     */
    @Test
    fun testEmojiAtBufferBoundaryDoesNotBreakSurrogate() {
        val src = tempFolder.newFile("emoji_boundary.txt")
        val raw = "a".repeat(8191) + "😀" + "中".repeat(30) + "\n"
        src.writeText(raw, Charsets.UTF_8)

        val target = tempFolder.newFile("target_emoji_boundary.txt")
        val result = StreamingTxtProcessor.process(src, target)

        assertEquals("目标文件大小必须与 result.totalBytes 完全相等", target.length(), result.totalBytes)

        val outText = target.readText(Charsets.UTF_8)
        assertTrue("Emoji 不能被损坏为 '?'", outText.contains("😀"))
        assertTrue("Emoji 不能被拆分", !outText.contains("?\n?"))

        val raf = RandomAccessFile(target, "r")
        val reconstructed = StringBuilder()
        for (slice in result.slices) {
            val len = (slice.endByte - slice.startByte).toInt()
            assertTrue(len > 0)
            val bytes = ByteArray(len)
            raf.seek(slice.startByte)
            raf.readFully(bytes)
            val text = String(bytes, Charsets.UTF_8)
            assertEquals(slice.charCount, text.length)
            reconstructed.append(text)
        }
        raf.close()
        assertEquals(outText, reconstructed.toString())
    }

    /**
     * 针对 Reviewer Round 2 P1-1 的回归测试：
     * 8191 个 a + 𠮷 + 结束
     * 生僻字代理对在分块边界处不能损坏，末切片不得越过 EOF。
     */
    @Test
    fun testRareKanjiSurrogateAtBufferBoundary() {
        val src = tempFolder.newFile("kanji_boundary.txt")
        val raw = "a".repeat(8191) + "𠮷" + "结束\n"
        src.writeText(raw, Charsets.UTF_8)

        val target = tempFolder.newFile("target_kanji_boundary.txt")
        val result = StreamingTxtProcessor.process(src, target)

        assertEquals(target.length(), result.totalBytes)
        val outText = target.readText(Charsets.UTF_8)
        assertTrue("生僻字不能被损坏", outText.contains("𠮷"))

        val lastSlice = result.slices.last()
        assertEquals("末切片 endByte 必须等于文件长度", target.length(), lastSlice.endByte)
    }

    /**
     * 针对 Reviewer Round 2 P2-2 的回归测试：
     * 独立 CR 换行识别与章节分割。
     * 输入："第1章 开始\r正文甲\r第2章 结束\r正文乙"
     * 必须识别出 2 个章节，且正文完好保留。
     */
    @Test
    fun testStandaloneCarriageReturnLineBreaks() {
        val src = tempFolder.newFile("standalone_cr.txt")
        val raw = "第1章 开始\r正文甲内容在此\r第2章 结束\r正文乙内容在此\r"
        src.writeText(raw, Charsets.UTF_8)

        val target = tempFolder.newFile("target_cr.txt")
        val result = StreamingTxtProcessor.process(src, target)

        println("DEBUG SLICES: ${result.slices.map { it.title }}")
        assertEquals("必须识别出 2 个章节", 2, result.slices.size)
        assertEquals("第1章 开始", result.slices[0].title)
        assertEquals("第2章 结束", result.slices[1].title)

        val outText = target.readText(Charsets.UTF_8)
        assertTrue("正文甲内容完好", outText.contains("正文甲内容在此"))
        assertTrue("正文乙内容完好", outText.contains("正文乙内容在此"))
    }

    /**
     * 针对 Reviewer Round 2 P2-2 的回归测试：
     * 8191 个 a + 空格 + b
     * 超长行内部安全分块时，正文中间的空格绝不能当作行尾空白被截断删除。
     */
    @Test
    fun testChunkFlushPreservesInternalSpaces() {
        val src = tempFolder.newFile("chunk_spaces.txt")
        val raw = "a".repeat(8191) + " " + "b\n"
        src.writeText(raw, Charsets.UTF_8)

        val target = tempFolder.newFile("target_spaces.txt")
        StreamingTxtProcessor.process(src, target)

        val outText = target.readText(Charsets.UTF_8)
        assertTrue("空格必须被完整保留，不能被截断删除", outText.contains("a b"))
    }

    /**
     * 针对 Reviewer Round 3 P2-4 的回归测试：
     * 中文全角标点章节识别：
     * "序章：起点"、"番外：归途" 必须精确识别为独立章节标题，绝不退化为"前言"。
     */
    @Test
    fun testFullWidthPunctuationChapterTitles() {
        val src = tempFolder.newFile("fullwidth_punct.txt")
        val content = """
            序章：起点
            这是序章的正文内容。
            第1章：征程
            微风拂过柳梢，少年踏上征程。
            番外：归途
            历经沧桑，剑客重归故里。
        """.trimIndent()
        src.writeText(content, Charsets.UTF_8)

        val target = tempFolder.newFile("target_punct.txt")
        val result = StreamingTxtProcessor.process(src, target)

        assertEquals("必须识别出 3 个章节", 3, result.slices.size)
        assertEquals("序章：起点", result.slices[0].title)
        assertEquals("第1章：征程", result.slices[1].title)
        assertEquals("番外：归途", result.slices[2].title)
    }
}
