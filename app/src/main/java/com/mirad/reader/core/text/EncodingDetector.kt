package com.mirad.reader.core.text

import java.nio.charset.Charset

/**
 * TXT 编码检测：保留旧接口，实际判定委托给 [TextCodec]。
 *
 * source 取值：manual / BOM / declared / utf8-strict / scored / chardet / fallback。
 */
object EncodingDetector {

    data class Result(
        val charset: Charset,
        /** 检测来源，用于界面提示。 */
        val source: String
    )

    fun detect(bytes: ByteArray, hint: String? = null): Result {
        val decoded = TextCodec.decode(bytes, manual = hint)
        val charset = runCatching { Charset.forName(decoded.charsetName) }.getOrDefault(Charsets.UTF_8)
        return Result(charset, decoded.source)
    }

    /** 依据检测结果解码并去除 BOM。 */
    fun decode(bytes: ByteArray, result: Result): String {
        val text = String(bytes, result.charset)
        return if (text.isNotEmpty() && text[0] == '\uFEFF') text.substring(1) else text
    }

    /** 严格校验：全部字节必须可解码为 UTF-8。 */
    fun isValidUtf8(bytes: ByteArray): Boolean = TextCodec.isValidUtf8(bytes)
}
