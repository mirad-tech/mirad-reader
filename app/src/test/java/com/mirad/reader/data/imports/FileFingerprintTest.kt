package com.mirad.reader.data.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * 内容指纹：用于把去重从"同名同大小"升级为"同名同大小且内容相同"。
 */
class FileFingerprintTest {

    private fun tempFile(bytes: ByteArray): File {
        val f = File.createTempFile("mirad-fp-", ".bin")
        f.writeBytes(bytes)
        return f
    }

    @Test
    fun sameLengthDifferentContentProducesDifferentFingerprint() {
        // 这正是旧去重键会误判的场景：文件名相同、字节数相同，但内容不同
        val a = tempFile("ABCDEFGH".toByteArray())
        val b = tempFile("ABCDEFGX".toByteArray())
        try {
            assertEquals(a.length(), b.length())
            assertNotEquals(FileFingerprint.of(a), FileFingerprint.of(b))
        } finally {
            a.delete(); b.delete()
        }
    }

    @Test
    fun identicalContentProducesIdenticalFingerprint() {
        val a = tempFile("同一本书的内容".toByteArray())
        val b = tempFile("同一本书的内容".toByteArray())
        try {
            assertEquals(FileFingerprint.of(a), FileFingerprint.of(b))
        } finally {
            a.delete(); b.delete()
        }
    }

    @Test
    fun differentLengthIsAlwaysDifferent() {
        val a = tempFile("short".toByteArray())
        val b = tempFile("short but longer".toByteArray())
        try {
            assertNotEquals(FileFingerprint.of(a), FileFingerprint.of(b))
        } finally {
            a.delete(); b.delete()
        }
    }

    @Test
    fun largeFileIsSampledFromHeadAndTail() {
        // 大于 128KB：只采样首尾，不应把整个文件读进内存
        val head = ByteArray(100 * 1024) { 'H'.code.toByte() }
        val tail = ByteArray(100 * 1024) { 'T'.code.toByte() }
        val same = tempFile(head + tail)
        val other = tempFile(head + ByteArray(100 * 1024) { 'X'.code.toByte() })
        try {
            assertNotEquals(FileFingerprint.of(same), FileFingerprint.of(other))
        } finally {
            same.delete(); other.delete()
        }
    }

    @Test
    fun emptyFileHasFingerprintAndMissingFileDoesNot() {
        val empty = tempFile(ByteArray(0))
        try {
            assertEquals(FileFingerprint.digest(0, ByteArray(0), ByteArray(0)), FileFingerprint.of(empty))
            assertNull(FileFingerprint.of(File(empty.parentFile, "definitely-missing.bin")))
        } finally {
            empty.delete()
        }
    }

    @Test
    fun digestIsStableForSameInputs() {
        val head = "head".toByteArray()
        val tail = "tail".toByteArray()
        assertEquals(FileFingerprint.digest(8, head, tail), FileFingerprint.digest(8, head, tail))
        assertNotEquals(FileFingerprint.digest(8, head, tail), FileFingerprint.digest(9, head, tail))
    }
}
