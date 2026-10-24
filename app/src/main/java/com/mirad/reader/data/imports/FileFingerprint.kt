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
 * 已知局限：首中尾之外的差异检测不到；相比旧方案（只采首尾）已消除
 * "中间不同、首尾相同被误判为同一份"的主要误判来源。
 * 指纹不落库、比对时两端现场重算，算法演进无兼容负担。
 */
object FileFingerprint {

    private const val SAMPLE = 64 * 1024

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
