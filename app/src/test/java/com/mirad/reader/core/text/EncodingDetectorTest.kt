package com.mirad.reader.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class EncodingDetectorTest {

    private val gbk = Charset.forName("GBK")
    private val sample = """
        第一章 初入江湖
        李慕白提剑站在山巅，望着远处的云海，心中忽然生出无限感慨。
        他不知道，这一去便是三年。风起于青萍之末，浪成于微澜之间。
        第二章 风起云涌
        山下的镇子依旧热闹，卖馄饨的老翁推着车，一路吆喝着走过长街。
        少年攥紧了手里的铜钱，眼神里有光。
    """.trimIndent()

    @Test
    fun detectsUtf8WithoutBom() {
        val bytes = sample.toByteArray(Charsets.UTF_8)
        val result = EncodingDetector.detect(bytes)
        val decoded = EncodingDetector.decode(bytes, result)
        assertEquals(sample, decoded)
        assertTrue(result.charset.name().uppercase().contains("UTF"))
    }

    @Test
    fun detectsUtf8WithBom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sample.toByteArray(Charsets.UTF_8)
        val result = EncodingDetector.detect(bytes)
        assertEquals("BOM", result.source)
        assertEquals(sample, EncodingDetector.decode(bytes, result))
    }

    @Test
    fun detectsUtf16LeWithBom() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + sample.toByteArray(Charsets.UTF_16LE)
        val result = EncodingDetector.detect(bytes)
        assertEquals("BOM", result.source)
        assertEquals(sample, EncodingDetector.decode(bytes, result))
    }

    @Test
    fun detectsChineseGbkText() {
        val bytes = sample.toByteArray(gbk)
        val result = EncodingDetector.detect(bytes)
        val decoded = EncodingDetector.decode(bytes, result)
        assertEquals(sample, decoded)
    }

    @Test
    fun asciiIsUtf8() {
        val bytes = "Chapter 1\nThe road was long.".toByteArray(Charsets.US_ASCII)
        val result = EncodingDetector.detect(bytes)
        assertEquals("Chapter 1\nThe road was long.", EncodingDetector.decode(bytes, result))
    }

    @Test
    fun manualHintWinsOverDetection() {
        val bytes = sample.toByteArray(gbk)
        val result = EncodingDetector.detect(bytes, hint = "GBK")
        assertEquals("manual", result.source)
        assertEquals(sample, EncodingDetector.decode(bytes, result))
    }

    @Test
    fun utf8ValidityCheck() {
        assertTrue(EncodingDetector.isValidUtf8(sample.toByteArray(Charsets.UTF_8)))
        assertEquals(false, EncodingDetector.isValidUtf8(sample.toByteArray(gbk)))
    }
}
