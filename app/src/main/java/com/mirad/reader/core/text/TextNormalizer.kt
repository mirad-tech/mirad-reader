package com.mirad.reader.core.text

/** 正文归一化工具。 */
object TextNormalizer {

    /** 统一换行为 \n，去掉 BOM/NUL 与行尾空白，压缩连续空行。 */
    fun normalize(raw: String): String {
        var text = raw
        if (text.isNotEmpty() && text[0] == '\uFEFF') text = text.substring(1)
        text = text.replace("\r\n", "\n").replace('\r', '\n')
        text = text.replace("\u0000", "")
        text = text.replace(Regex("[ \\t\\u00A0\\u3000]+\\n"), "\n")
        text = text.replace(Regex("\\n{3,}"), "\n\n")
        return text
    }

    /** 按空行/换行切分为段落，过滤空白段。 */
    fun paragraphs(text: String): List<String> =
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** 单个字符的 UTF-8 字节长度。 */
    fun utf8Length(c: Char): Int = when {
        c.code < 0x80 -> 1
        c.code < 0x800 -> 2
        else -> 3
    }

    /**
     * 字符串的 UTF-8 字节长度（**含代理对修正**）。
     *
     * 逐 Char 累加会把一个 4 字节码点（emoji、增补平面汉字如 𠮷）算成两个 3 字节字符，
     * 每个多算 2 字节；而 startByte/endByte 会被用来 seek 切片，偏差会让正文读到错位区间。
     */
    fun utf8Length(s: CharSequence, start: Int = 0, end: Int = s.length): Long {
        var sum = 0L
        var i = start
        while (i < end) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < end && s[i + 1].isLowSurrogate()) {
                sum += 4
                i += 2
            } else {
                sum += utf8Length(c)
                i++
            }
        }
        return sum
    }
}
