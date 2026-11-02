package com.mirad.reader.data.imports

import java.io.File
import java.security.MessageDigest

/**
 * 文件内容指纹，用于导入去重。
 *
 * 旧的去重键是「文件名去扩展名 + 字节数」，会把**同名同长度的不同书**误判成重复而静默跳过
 * （用户在书架里看不到新书，还以为是导入失败）。这里改成内容指纹，
 * 并且只采样首/中/尾各 64KB——不必为了一次去重把几百 MB 读进内存。
 *
 * 采样仅用于快速排除；相同采样必须再由 [sameContent] 逐字节确认，不能据此直接跳过导入。
 * 指纹不落库、比对时两端现场重算，算法演进无兼容负担。
 */
object FileFingerprint {

    private const val SAMPLE = 64 * 1024

    /** 只有完整读取并逐字节相同时才确认重复；任一端读取失败都保守地允许导入。 */
    fun sameContent(first: File, second: File): Boolean = runCatching {
        if (!first.isFile || !second.isFile || first.length() != second.length()) return false
        val firstSample = of(first) ?: return false
        if (firstSample != of(second)) return false
        first.inputStream().use { left ->
            second.inputStream().use { right ->
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
    fun of(file: File): String? = runCatching {
        if (!file.exists()) return null
        val length = file.length()
        val headSize = minOf(SAMPLE.toLong(), length).toInt()
        val tailSize = minOf(SAMPLE.toLong(), (length - headSize).coerceAtLeast(0L)).toInt()
        val middleSize = minOf(SAMPLE.toLong(), (length - headSize - tailSize).coerceAtLeast(0L)).toInt()
        val head = ByteArray(headSize)
        val middle = ByteArray(middleSize)
        val tail = ByteArray(tailSize)
        file.inputStream().use { input ->
            var read = 0
            while (read < headSize) {
                val n = input.read(head, read, headSize - read)
                if (n <= 0) break
                read += n
            }
            if (middleSize > 0) {
                input.channel.position((length - middleSize) / 2)
                read = 0
                while (read < middleSize) {
                    val n = input.read(middle, read, middleSize - read)
                    if (n <= 0) break
                    read += n
                }
            }
            if (tailSize > 0) {
                input.channel.position(length - tailSize)
                read = 0
                while (read < tailSize) {
                    val n = input.read(tail, read, tailSize - read)
                    if (n <= 0) break
                    read += n
                }
            }
        }
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
