package com.mirad.reader.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class TextCodecTest {

    private val gbk = Charset.forName("GBK")
    private val big5 = Charset.forName("BIG5")

    private val sample = """
        第一章 初入江湖
        李慕白提剑站在山巅，望着远处的云海，心中忽然生出无限感慨。
        他不知道，这一去便是三年。风起于青萍之末，浪成于微澜之间。
        第二章 风起云涌
        山下的镇子依旧热闹，卖馄饨的老翁推着车，一路吆喝着走过长街。
    """.trimIndent()

    private val traditional = "第一章 初入江湖\n李慕白提劍站在山巔，望著遠處的雲海。"

    @Test
    fun utf8WithoutBomIsChosenOverGb18030() {
        // 真机上的核心故障：UTF-8 文件被 chardet 判成 GB18030 后整段乱码。
        val decoded = TextCodec.decode(sample.toByteArray(Charsets.UTF_8))
        assertEquals("UTF-8", decoded.charsetName)
        assertEquals(sample, decoded.text)
    }

    @Test
    fun gbkTextDecodesToCorrectChinese() {
        val decoded = TextCodec.decode(sample.toByteArray(gbk))
        assertEquals(sample, decoded.text)
        assertTrue(decoded.charsetName.startsWith("GB"))
    }

    @Test
    fun utf16WithBomDecodes() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + sample.toByteArray(Charsets.UTF_16LE)
        val decoded = TextCodec.decode(bytes)
        assertEquals("BOM", decoded.source)
        assertEquals(sample, decoded.text)
    }

    @Test
    fun declaredCharsetWinsForGbkXhtml() {
        val head = "<?xml version=\"1.0\" encoding=\"gb2312\"?>\n<html><body><p>".toByteArray(Charsets.US_ASCII)
        val tail = "</p></body></html>".toByteArray(Charsets.US_ASCII)
        val bytes = head + sample.toByteArray(gbk) + tail
        val declared = TextCodec.declaredCharset(bytes)
        assertEquals("gb2312", declared)
        val decoded = TextCodec.decode(bytes, declared = declared)
        assertEquals("declared", decoded.source)
        assertTrue(decoded.text.contains("李慕白提剑站在山巅"))
    }

    @Test
    fun declaredCharsetFromMetaTag() {
        val head = "<html><head><meta charset=\"GBK\"></head><body>".toByteArray(Charsets.US_ASCII)
        val head2 = "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=gb18030\"></head>"
            .toByteArray(Charsets.US_ASCII)
        assertEquals("GBK", TextCodec.declaredCharset(head))
        assertEquals("gb18030", TextCodec.declaredCharset(head2))
    }

    @Test
    fun manualOverrideBeatsEverything() {
        val decoded = TextCodec.decode(sample.toByteArray(Charsets.UTF_8), manual = "GBK")
        assertEquals("manual", decoded.source)
        assertTrue(decoded.text != sample)
    }

    @Test
    fun asciiStillWorks() {
        val text = "Chapter 1\nThe road was long."
        val decoded = TextCodec.decode(text.toByteArray(Charsets.US_ASCII))
        assertEquals(text, decoded.text)
    }

    @Test
    fun traditionalChineseBig5BeatsGb18030() {
        val decoded = TextCodec.decode(traditional.toByteArray(big5))
        assertEquals(traditional, decoded.text)
    }

    @Test
    fun repeat5000ChineseText90000BytesDecodesUtf8Strictly() {
        // F01: 90000 字节无 BOM UTF-8 在 64KiB 边界截断，必须正确识别为 UTF-8 且全文一致
        val content = "中文内容测试".repeat(5000)
        val bytes = content.toByteArray(Charsets.UTF_8)
        assertEquals(90000, bytes.size)

        val decoded = TextCodec.decode(bytes)
        assertEquals("UTF-8", decoded.charsetName)
        assertEquals("utf8-strict", decoded.source)
        assertEquals(content, decoded.text)
    }

    @Test
    fun boundaryMultiByteCharactersAt64KiBDecodesAsPrefix() {
        // 2 字节字符在 64KiB 边界截断（byte 65535 为 2-byte 前导字节）
        val prefix2 = ByteArray(65535) { 'a'.code.toByte() } + byteArrayOf(0xCE.toByte())
        val decoded2 = TextCodec.decode(prefix2, isPrefix = true)
        assertEquals("UTF-8", decoded2.charsetName)

        // 3 字节字符在 64KiB 边界截断（byte 65534 为 3-byte 前导字节，65535 为续字节）
        val prefix3 = ByteArray(65534) { 'a'.code.toByte() } + byteArrayOf(0xE4.toByte(), 0xBD.toByte())
        val decoded3 = TextCodec.decode(prefix3, isPrefix = true)
        assertEquals("UTF-8", decoded3.charsetName)

        // 4 字节字符在 64KiB 边界截断（byte 65533 为 4-byte 前导字节，65534/65535 为续字节）
        val prefix4 = ByteArray(65533) { 'a'.code.toByte() } + byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte())
        val decoded4 = TextCodec.decode(prefix4, isPrefix = true)
        assertEquals("UTF-8", decoded4.charsetName)
    }

    @Test
    fun completeInputWithIllegalTrailingUtf8FailsStrictValidation() {
        // 完整文件（isPrefix = false）末尾残缺字节必须被视为非法 UTF-8，不能被静默截断当成有效
        val incompleteBytes = "完整内容".toByteArray(Charsets.UTF_8) + byteArrayOf(0xE4.toByte())
        val isValid = TextCodec.isValidUtf8(incompleteBytes, isPrefix = false)
        org.junit.Assert.assertFalse("完整输入末尾缺少后续字节应判定为非法 UTF-8", isValid)

        // 而若显式为采样前缀，则允许末尾残缺
        val isValidPrefix = TextCodec.isValidUtf8(incompleteBytes, isPrefix = true)
        assertTrue("采样前缀末尾残缺应被容许", isValidPrefix)
    }

    @Test
    fun fallbackDefaultCharsetIsUtf8() {
        // 当文本无特征且无法通过评分明确判定为遗留编码时，默认编码必须回退为 UTF-8
        val ambiguousBytes = byteArrayOf(0x80.toByte(), 0x81.toByte(), 0x82.toByte(), 0x83.toByte())
        val decoded = TextCodec.decode(ambiguousBytes)
        assertEquals("UTF-8", decoded.charsetName)
        assertEquals("fallback", decoded.source)
    }
}
