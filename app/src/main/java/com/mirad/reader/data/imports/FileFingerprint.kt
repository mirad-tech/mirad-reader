package com.mirad.reader.data.imports

import com.mirad.reader.data.io.BookContentAccess
import java.io.File
import java.security.MessageDigest

/**
 * 文件内容指纹，用于导入去重。
 *
 * 只采样首/中/尾各 64KB 作快速排除；相同采样必须再由 [sameContent] 逐字节确认，不能据此直接跳过导入。
 * 同时支持本地 [File] 与外部 `content://` 数据源。
 */
object FileFingerprint {

    private const val SAMPLE = 64 * 1024

    /** 只有完整读取并逐字节相同时才确认重复；任一端读取失败都保守地允许导入。 */
    fun sameContent(first: File, second: File): Boolean =
        sameContent(BookContentAccess(), first.absolutePath, second.absolutePath)

    fun sameContent(access: BookContentAccess, firstPath: String, secondPath: String): Boolean = runCatching {
        val firstLen = access.size(firstPath)
        val secondLen = access.size(secondPath)
        if (firstLen < 0L || secondLen < 0L || firstLen != secondLen) return false
        if (firstPath == secondPath) return access.isReadable(firstPath)
        val firstSample = of(access, firstPath) ?: return false
        if (firstSample != of(access, secondPath)) return false
        access.openStream(firstPath).use { left ->
            access.openStream(secondPath).use { right ->
                val leftBytes = ByteArray(SAMPLE)
                val rightBytes = ByteArray(SAMPLE)
                var count = left.read(leftBytes)
                while (count >= 0) {
                    var read = 0
                    while (read < count) {
                        val next = right.read(rightBytes, read, count - read)
                        if (next <= 0) return false
                        read += next
                    }
                    for (index in 0 until count) {
                        if (leftBytes[index] != rightBytes[index]) return false
                    }
                    count = left.read(leftBytes)
                }
                right.read() < 0
            }
        }
    }.getOrDefault(false)

    /** 读取文件并计算指纹；文件不存在或读取失败返回 null（调用方据此保守处理，不判定为重复）。 */
    fun of(file: File): String? = of(BookContentAccess(), file.absolutePath)

    fun of(access: BookContentAccess, path: String): String? = runCatching {
        val length = access.size(path)
        if (length < 0L) return null
        val headSize = minOf(SAMPLE.toLong(), length).toInt()
        val tailSize = minOf(SAMPLE.toLong(), (length - headSize).coerceAtLeast(0L)).toInt()
        val middleSize = minOf(SAMPLE.toLong(), (length - headSize - tailSize).coerceAtLeast(0L)).toInt()
        val head = if (headSize > 0) access.readRange(path, 0L, headSize) ?: return null else ByteArray(0)
        val middle = if (middleSize > 0) {
            access.readRange(path, (length - middleSize) / 2L, middleSize) ?: return null
        } else ByteArray(0)
        val tail = if (tailSize > 0) {
            access.readRange(path, length - tailSize, tailSize) ?: return null
        } else ByteArray(0)
        digest(length, head, tail, middle)
    }.getOrNull()

    /** 纯逻辑部分：长度 + 首中尾采样一起做 SHA-256，便于单测。 */
    fun digest(length: Long, head: ByteArray, tail: ByteArray, middle: ByteArray = ByteArray(0)): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(length.toString().toByteArray())
        md.update(head)
        md.update(middle)
        md.update(tail)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
