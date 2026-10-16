package com.mirad.reader.core.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * 有界读取：封面这类不可信来源（zip 条目可声明极小压缩体却解压出巨量数据）必须按上限截断为"拒绝"，
 * 而不是把内存吃爆。
 */
class BoundedReadTest {

    @Test
    fun streamUnderLimitIsReadFully() {
        val data = ByteArray(1024) { 'A'.code.toByte() }
        val read = ZipEntryReader.readAtMost(ByteArrayInputStream(data), maxBytes = 4096)
        assertEquals(1024, read?.size)
    }

    @Test
    fun streamExactlyAtLimitIsAccepted() {
        val data = ByteArray(4096)
        assertEquals(4096, ZipEntryReader.readAtMost(ByteArrayInputStream(data), 4096)?.size)
    }

    @Test
    fun streamOverLimitIsRejectedWithoutBufferingEverything() {
        // 64KB 数据、上限 4KB：必须是 null，而不是返回 4KB 半截
        val data = ByteArray(64 * 1024)
        assertNull(ZipEntryReader.readAtMost(ByteArrayInputStream(data), maxBytes = 4096))
    }

    @Test
    fun emptyStreamReturnsEmptyArray() {
        assertEquals(0, ZipEntryReader.readAtMost(ByteArrayInputStream(ByteArray(0)), 1024)?.size)
    }
}
