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
}
