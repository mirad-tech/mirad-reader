package com.mirad.reader.core.text

import org.mozilla.universalchardet.UniversalDetector
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 解码结果：文本 + 实际使用的编码 + 判定来源。 */
data class DecodedText(
    val text: String,
    val charsetName: String,
    val source: String
)

/**
 * 统一的"字节 → 文本"解码层，TXT 与 EPUB 共用。
 *
 * 判定顺序（chardet 只作为提示，不再权威）：
 *   1. 手动指定
 *   2. BOM
 *   3. 文件内声明的 charset（EPUB 的 `<?xml encoding>` / `<meta charset>`）
 *   4. 严格 UTF-8 校验
 *   5. 多候选评分（GB18030 / BIG5 / SHIFT_JIS / windows-1252）
 *   6. UTF-8 默认兜底
 *
 * 关键点：绝不能让"能解码"等同于"猜对了"——GB18030 几乎能解码任意字节序列，
 * 所以它只能靠评分胜出，不能靠 chardet 断言；默认未知时以 UTF-8 为基准。
 */
object TextCodec {

    /** 评分只取前 64KB，够判定且快。 */
    private const val SAMPLE_BYTES = 64 * 1024

    /**
     * 参与评分的候选。
     *
     * UTF-16 不参与评分：任意字节对都可能落进 CJK 码位，评分会把 GBK 文本误判成 UTF-16。
     * UTF-16 只在有 BOM 或文件声明时采用。
     */
    private val SCORED_CANDIDATES = listOf("GB18030", "BIG5", "SHIFT_JIS", "windows-1252")

    private val CHINESE_PUNCT = "，。、；：？！“”‘’（）《》〈〉「」『』【】…—～·　"
    private val SOUP_CHARS = "ÃÂÐÞãâðþ€‚ƒ„†‡ˆ‰Š‹Œ"

    private val XML_ENCODING = Regex("(?i)<\\?xml[^>]*encoding\\s*=\\s*[\"']([\\w.\\-]+)[\"']")
    private val META_CHARSET = Regex("(?i)<meta[^>]*charset\\s*=\\s*[\"']?\\s*([\\w.\\-]+)")
    private val META_HTTP_EQUIV = Regex("(?i)content\\s*=\\s*[\"'][^\"']*charset\\s*=\\s*([\\w.\\-]+)")

    fun decode(bytes: ByteArray, declared: String? = null, manual: String? = null, isPrefix: Boolean = false): DecodedText {
        if (bytes.isEmpty()) return DecodedText("", "UTF-8", "utf8-strict")

        manual?.let { name ->
            charsetOf(name)?.let { return DecodedText(clean(String(bytes, it)), it.name(), "manual") }
        }

        bomOf(bytes)?.let { (charset, offset) ->
            val text = String(bytes, offset, bytes.size - offset, charset)
            return DecodedText(clean(text), charset.name(), "BOM")
        }

        declared?.let { name ->
            charsetOf(name)?.let { charset ->
                if (canDecodeStrict(bytes, charset)) {
                    return DecodedText(clean(String(bytes, charset)), charset.name(), "declared")
                }
            }
        }

        // UTF-16 无 BOM（记事本另存为"Unicode"时可能丢 BOM）必须排在严格 UTF-8 之前：
        // UTF-16 中文里的 0x00 字节在 UTF-8 下是合法的 NUL，严格校验拦不住，会解出一堆控制字符。
        guessUtf16WithoutBom(bytes)?.let { charset ->
            return DecodedText(clean(String(bytes, charset)), charset.name(), "utf16-heuristic")
        }

        // UTF-8 自带校验能力：严格可解就是最强证据，必须排在 GB18030 之前。
        // 若为前缀样本（isPrefix=true），需剔除末尾可能被截断的不完整多字节序列后再行判定。
        if (isValidUtf8(bytes, isPrefix = isPrefix)) {
            val targetBytes = if (isPrefix) trimTrailingIncompleteUtf8(bytes) else bytes
            return DecodedText(clean(String(targetBytes, Charsets.UTF_8)), "UTF-8", "utf8-strict")
        }

        val scored = SCORED_CANDIDATES
            .mapNotNull { name -> charsetOf(name)?.let { charset -> charset to score(bytes, charset) } }
            .filter { it.second > Int.MIN_VALUE }
            .maxByOrNull { it.second }

        if (scored != null && scored.second > 0) {
            val hinted = runCatching { detectByChardet(bytes) }.getOrNull()
            val source = if (hinted != null && normalize(hinted).name() == scored.first.name()) "chardet" else "scored"
            return DecodedText(clean(String(bytes, scored.first)), scored.first.name(), source)
        }

        val utf8 = Charsets.UTF_8
        return DecodedText(clean(String(bytes, utf8)), "UTF-8", "fallback")
    }

    /**
     * 无 BOM 的 UTF-16 识别：拉丁字符为主的 UTF-16LE 文本每隔一个字节就是 0x00，
     * 这个模式非常特征化。中文 UTF-16 没有这种规律，退化为"控制字符比例"判断。
     */
    private fun guessUtf16WithoutBom(bytes: ByteArray): Charset? {
        val n = minOf(bytes.size, 4096)
        if (n < 8) return null
        var evenZero = 0
        var oddZero = 0
        var pairs = 0
        var i = 0
        while (i + 1 < n) {
            if (bytes[i] == 0.toByte()) evenZero++
            if (bytes[i + 1] == 0.toByte()) oddZero++
            pairs++
            i += 2
        }
        if (pairs < 4) return null
        val evenRatio = evenZero.toDouble() / pairs
        val oddRatio = oddZero.toDouble() / pairs
        if (oddRatio > 0.3 && oddRatio > evenRatio * 3) return Charsets.UTF_16LE
        if (evenRatio > 0.3 && evenRatio > oddRatio * 3) return Charsets.UTF_16BE

        // 没有 0x00 交替时（中文 UTF-16），用"解出来是不是一堆控制字符"来判断。
        val le = controlRatio(String(bytes, 0, n - n % 2, Charsets.UTF_16LE))
        val be = controlRatio(String(bytes, 0, n - n % 2, Charsets.UTF_16BE))
        return when {
            le < 0.02 && be > 0.10 -> Charsets.UTF_16LE
            be < 0.02 && le > 0.10 -> Charsets.UTF_16BE
            else -> null
        }
    }

    private fun controlRatio(text: String): Double {
        if (text.isEmpty()) return 1.0
        var bad = 0
        for (ch in text) {
            val code = ch.code
            val ok = code == 0x09 || code == 0x0A || code == 0x0D || code in 0x20..0x7E ||
                code in 0x4E00..0x9FFF || code in 0x3000..0x303F || code in 0xFF00..0xFFEF
            if (!ok) bad++
        }
        return bad.toDouble() / text.length
    }

    /** 从文件头解析声明编码（EPUB 的 XHTML / OPF / NCX）。 */
    fun declaredCharset(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        XML_ENCODING.find(head)?.let { return it.groupValues[1] }
        META_CHARSET.find(head)?.let { return it.groupValues[1] }
        META_HTTP_EQUIV.find(head)?.let { return it.groupValues[1] }
        return null
    }

    private fun bomOf(bytes: ByteArray): Pair<Charset, Int>? {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Charsets.UTF_8 to 3
        }
        if (bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() &&
            bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()
        ) {
            return charsetOf("UTF-32LE")?.let { it to 4 }
        }
        if (bytes.size >= 4 && bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
            bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte()
        ) {
            return charsetOf("UTF-32BE")?.let { it to 4 }
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Charsets.UTF_16LE to 2
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Charsets.UTF_16BE to 2
        }
        return null
    }

    private fun charsetOf(name: String): Charset? =
        runCatching { normalize(Charset.forName(name)) }.getOrNull()

    /** GB2312/GBK 统一按 GB18030 解码，兼容性更好。 */
    private fun normalize(charset: Charset): Charset {
        val name = charset.name().uppercase()
        return when {
            name.startsWith("GB") -> charsetOfRaw("GB18030")
            name == "UTF8" -> Charsets.UTF_8
            else -> charset
        }
    }

    private fun charsetOfRaw(name: String): Charset = Charset.forName(name)

    /**
     * 文本"像不像正常中文"的评分（每千字），用于在多个可解码的编码之间取舍。
     * 加分：CJK 汉字与中文标点；减分：私用区、替换符、控制字符、Latin-1 乱码特征。
     */
    private fun score(bytes: ByteArray, charset: Charset): Int {
        val text = decodeLenient(sample(bytes), charset)
        if (text.isEmpty()) return Int.MIN_VALUE
        var cjk = 0
        var punct = 0
        var pua = 0
        var bad = 0
        var ctrl = 0
        var soup = 0
        for (ch in text) {
            val code = ch.code
            when {
                ch == '\uFFFD' -> bad++
                code in 0xE000..0xF8FF -> pua++
                code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF -> cjk++
                code < 0x20 && ch != '\n' && ch != '\r' && ch != '\t' -> ctrl++
                CHINESE_PUNCT.indexOf(ch) >= 0 -> punct++
                SOUP_CHARS.indexOf(ch) >= 0 -> soup++
            }
        }
        val n = text.length
        return ((cjk * 3 + punct * 2 - pua * 4 - bad * 5 - ctrl * 3 - soup * 4) * 1000) / n
    }

    private fun sample(bytes: ByteArray): ByteArray =
        if (bytes.size <= SAMPLE_BYTES) bytes else bytes.copyOf(SAMPLE_BYTES)

    private fun decodeLenient(bytes: ByteArray, charset: Charset): String =
        runCatching {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrDefault("")

    fun isValidUtf8(bytes: ByteArray, isPrefix: Boolean = false): Boolean {
        if (bytes.isEmpty()) return true
        val target = if (isPrefix) trimTrailingIncompleteUtf8(bytes) else bytes
        return canDecodeStrict(target, Charsets.UTF_8)
    }

    internal fun trimTrailingIncompleteUtf8(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes
        val len = bytes.size
        for (i in 1..minOf(4, len)) {
            val b = bytes[len - i].toInt() and 0xFF
            if ((b and 0x80) == 0) {
                // ASCII (0xxxxxxx)
                break
            }
            if ((b and 0xC0) == 0x80) {
                // Continuation byte (10xxxxxx)
                continue
            }
            val expectedLen = when {
                (b and 0xE0) == 0xC0 -> 2 // 110xxxxx
                (b and 0xF0) == 0xE0 -> 3 // 1110xxxx
                (b and 0xF8) == 0xF0 -> 4 // 11110xxx
                else -> -1
            }
            if (expectedLen > 0 && i < expectedLen) {
                return bytes.copyOf(len - i)
            }
            break
        }
        return bytes
    }

    private fun canDecodeStrict(bytes: ByteArray, charset: Charset): Boolean =
        runCatching {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        }.getOrDefault(false)

    private fun detectByChardet(bytes: ByteArray): Charset? {
        val detector = UniversalDetector(null)
        if (bytes.size <= 136 * 1024) {
            detector.handleData(bytes, 0, bytes.size)
        } else {
            detector.handleData(bytes, 0, 128 * 1024)
            detector.handleData(bytes, bytes.size - 8192, 8192)
        }
        detector.dataEnd()
        val name = detector.detectedCharset ?: return null
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    private fun clean(text: String): String =
        if (text.isNotEmpty() && text[0] == '\uFEFF') text.substring(1) else text
}
