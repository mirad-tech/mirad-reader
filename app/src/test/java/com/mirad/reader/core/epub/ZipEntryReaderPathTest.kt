package com.mirad.reader.core.epub

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * zip 路径的规范化与 %XX 解码。
 *
 * 重点是中文条目名：%XX 是字节序列，解码后必须还原成 UTF-8 字符，
 * 否则中文文件名的 href 会解析不到条目（表现为整章空白）。
 */
class ZipEntryReaderPathTest {

    @Test
    fun percentDecodedChineseEntryNameMatchesUtf8Name() {
        // 这是审计里那条"中文条目名 href 静默解析为空章节"的直接用例
        assertEquals("第一章.xhtml", ZipEntryReader.percentDecode("%E7%AC%AC%E4%B8%80%E7%AB%A0.xhtml"))
        assertEquals("中", ZipEntryReader.percentDecode("%E4%B8%AD"))
    }

    @Test
    fun percentDecodeHandlesAsciiEscapes() {
        assertEquals("a b", ZipEntryReader.percentDecode("a%20b"))
        assertEquals("a/b", ZipEntryReader.percentDecode("a%2Fb"))
    }

    @Test
    fun percentDecodeLeavesPlainTextUntouched() {
        assertEquals("OEBPS/ch1.xhtml", ZipEntryReader.percentDecode("OEBPS/ch1.xhtml"))
    }

    @Test
    fun percentDecodeMixesLiteralAndEscapedParts() {
        assertEquals("目录/中.xhtml", ZipEntryReader.percentDecode("目录/%E4%B8%AD.xhtml"))
    }

    @Test
    fun malformedEscapeIsKeptAsLiteralText() {
        assertEquals("100%", ZipEntryReader.percentDecode("100%"))
        assertEquals("%ZZ", ZipEntryReader.percentDecode("%ZZ"))
    }

    @Test
    fun resolveNormalisesRelativeSegments() {
        assertEquals("OEBPS/ch1.xhtml", ZipEntryReader.resolve("OEBPS", "images/../ch1.xhtml"))
        assertEquals("ch1.xhtml", ZipEntryReader.resolve("", "./ch1.xhtml"))
        assertEquals("OEBPS/ch1.xhtml", ZipEntryReader.resolve("OEBPS", "ch1.xhtml#anchor"))
    }

    @Test
    fun leadingParentSegmentsCannotEscapeTheArchiveRoot() {
        // 当前语义：越出根目录的 .. 被忽略（不会逃出应用目录），这里锁定该行为
        assertEquals("etc/passwd", ZipEntryReader.normalize("../../etc/passwd"))
    }
}
