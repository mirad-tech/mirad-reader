package com.mirad.reader.core.text

import org.mozilla.universalchardet.UniversalDetector
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * TXT 编码检测。
 *
 * 顺序：BOM → juniversalchardet → 严格 UTF-8 校验 → 常见中文编码回退。
 */
object EncodingDetector {

    /**
     * 回退候选顺序很重要：UTF-8 必须排在 GB18030 之前，因为 GB18030 几乎能"成功"解码任意字节序列。
     */
    private val FALLBACK_CANDIDATES = listOf("UTF-8", "GB18030", "BIG5", "SHIFT_JIS", "windows-1252")

    data class Result(
        val charset: Charset,
        /** 检测来源，用于界面提示：BOM / chardet / utf8-strict / fallback。 */
        val source: String
    )

    fun detect(bytes: ByteArray, hint: String? = null): Result {
        hint?.let { name ->
            runCatching { Charset.forName(name) }.getOrNull()?.let { return Result(it, "manual") }
        }

        bomCharset(bytes)?.let { return Result(it, "BOM") }

        val detected = runCatching { detectByChardet(bytes) }.getOrNull()
        if (detected != null && canDecode(bytes, detected)) {
            return Result(normalize(detected), "chardet")
        }

        for (name in FALLBACK_CANDIDATES) {
            val charset = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            if (canDecodeStrict(bytes, charset)) return Result(charset, if (name == "UTF-8") "utf8-strict" else "fallback")
        }

        // 兜底：宽松解码 GB18030，几乎不会失败。
        val gb = Charset.forName("GB18030")
        return Result(gb, "fallback")
    }

    /** 依据检测结果解码并去除 BOM。 */
    fun decode(bytes: ByteArray, result: Result): String {
        val text = String(bytes, result.charset)
        return if (text.isNotEmpty() && text[0] == '\uFEFF') text.substring(1) else text
    }

    private fun bomCharset(bytes: ByteArray): Charset? {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Charsets.UTF_8
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Charsets.UTF_16LE
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Charsets.UTF_16BE
        }
        return null
    }

    private fun detectByChardet(bytes: ByteArray): Charset? {
        val detector = UniversalDetector(null)
        // 大文件只取前 128KB 与后 8KB 参与统计，足够判定且更快。
        if (bytes.size <= 136 * 1024) {
            detector.handleData(bytes, 0, bytes.size)
        } else {
            detector.handleData(bytes, 0, 128 * 1024)
            detector.handleData(bytes, bytes.size - 8192, 8192)
        }
        detector.dataEnd()
        val name = detector.detectedCharset ?: return null
        val charset = runCatching { Charset.forName(name) }.getOrNull() ?: return null
        return charset
    }

    /** 宽松可用性检查：能解码即可接受（用于 chardet 结果自检）。 */
    private fun canDecode(bytes: ByteArray, charset: Charset): Boolean =
        runCatching {
            val decoder = charset.newDecoder()
            decoder.onMalformedInput(CodingErrorAction.REPORT)
            decoder.onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(sample(bytes)))
            true
        }.getOrDefault(false)

    private fun canDecodeStrict(bytes: ByteArray, charset: Charset): Boolean = canDecode(bytes, charset)

    private fun sample(bytes: ByteArray): ByteArray =
        if (bytes.size <= 512 * 1024) bytes else bytes.copyOf(512 * 1024)

    /** GB2312/GBK 统一按 GB18030 解码，兼容性更好。 */
    private fun normalize(charset: Charset): Charset {
        val name = charset.name().uppercase()
        return when {
            name.startsWith("GB") -> Charset.forName("GB18030")
            name == "UTF8" -> Charsets.UTF_8
            else -> charset
        }
    }

    /** 严格校验：全部字节必须可解码。 */
    fun isValidUtf8(bytes: ByteArray): Boolean = canDecodeStrict(bytes, Charsets.UTF_8)
}
