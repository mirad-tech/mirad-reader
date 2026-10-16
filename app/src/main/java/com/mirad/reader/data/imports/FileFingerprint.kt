package com.mirad.reader.data.imports

import java.io.File
import java.security.MessageDigest

/**
 * 文件内容指纹，用于导入去重。
 *
 * 旧的去重键是「文件名去扩展名 + 字节数」，会把**同名同长度的不同书**误判成重复而静默跳过
 * （用户在书架里看不到新书，还以为是导入失败）。这里改成内容指纹，
 * 并且只采样首尾各 64KB——不必为了一次去重把几百 MB 读进内存。
 *
 * 已知局限：只比对首尾，**中间不同、首尾相同的两个文件会被视为同一份**。
 * 对"同一本书的不同来源版本"这类真实场景足够，因为它极少只在中段有差异。
 */
object FileFingerprint {

    private const val SAMPLE = 64 * 1024

    /** 读取文件并计算指纹；文件不存在或读取失败返回 null（调用方据此保守处理，不判定为重复）。 */
    fun of(file: File): String? = runCatching {
        if (!file.exists()) return null
        val length = file.length()
        val headSize = minOf(SAMPLE.toLong(), length).toInt()
        val tailSize = minOf(SAMPLE.toLong(), (length - headSize).coerceAtLeast(0L)).toInt()
        val head = ByteArray(headSize)
        val tail = ByteArray(tailSize)
        file.inputStream().use { input ->
            var read = 0
            while (read < headSize) {
                val n = input.read(head, read, headSize - read)
                if (n <= 0) break
                read += n
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
        digest(length, head, tail)
    }.getOrNull()

    /** 纯逻辑部分：长度 + 首尾采样一起做 SHA-256，便于单测。 */
    fun digest(length: Long, head: ByteArray, tail: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(length.toString().toByteArray())
        md.update(head)
        md.update(tail)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
