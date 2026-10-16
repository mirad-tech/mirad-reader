package com.mirad.reader.core.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 条目读取的边界语义：**超限必须是"读不到"，不能是"读到半截"**。
 *
 * 旧实现边读边 break，半截 XHTML 与完整内容无法区分：截断点常落在多字节 UTF-8 中间，
 * 严格解码失败后退到候选评分，可能把整章判成 GB18030 而全篇乱码。
 */
class ZipEntryReaderLimitTest {

    private fun zipWith(entries: Map<String, ByteArray>): File {
        val file = File.createTempFile("mirad-limit-", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun oversizedEntryIsReportedAsUnreadableNotTruncated() {
        val big = ByteArray(64 * 1024) { 'A'.code.toByte() }
        val file = zipWith(mapOf("big.xhtml" to big, "small.xhtml" to "hello".toByteArray()))
        try {
            ZipEntryReader.open(file).use { reader ->
                // 上限小于条目声明大小：必须 null，而不是 4KB 半截数据
                assertNull(reader.readBytes("big.xhtml", maxBytes = 4096))
                assertNull(reader.readText("big.xhtml", maxBytes = 4096))
                // 正常条目不受影响
                assertEquals(5, reader.readBytes("small.xhtml", maxBytes = 4096)?.size)
                assertEquals("hello", reader.readText("small.xhtml", maxBytes = 4096))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun entryExactlyAtTheLimitIsStillReadable() {
        val exact = ByteArray(4096) { 'B'.code.toByte() }
        val file = zipWith(mapOf("exact.xhtml" to exact))
        try {
            ZipEntryReader.open(file).use { reader ->
                assertNotNull(reader.readBytes("exact.xhtml", maxBytes = 4096))
                assertEquals(4096, reader.readBytes("exact.xhtml", maxBytes = 4096)?.size)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun missingEntryIsNull() {
        val file = zipWith(mapOf("a.xhtml" to "x".toByteArray()))
        try {
            ZipEntryReader.open(file).use { reader ->
                assertNull(reader.readBytes("nope.xhtml"))
                assertNull(reader.readText("nope.xhtml"))
            }
        } finally {
            file.delete()
        }
    }
}
