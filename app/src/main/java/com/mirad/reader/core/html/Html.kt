package com.mirad.reader.core.html

/** HTML 转义、反转义与纯文本抽取。纯 Kotlin，便于单元测试。 */
object Html {

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to "\u00A0", "ldquo" to "\u201C", "rdquo" to "\u201D",
        "lsquo" to "\u2018", "rsquo" to "\u2019", "hellip" to "\u2026",
        "mdash" to "\u2014", "ndash" to "\u2013", "middot" to "\u00B7",
        "copy" to "\u00A9", "trade" to "\u2122", "deg" to "\u00B0"
    )

    fun escape(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun unescape(text: String): String {
        if (!text.contains('&')) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            val semi = text.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 12) {
                sb.append(c)
                i++
                continue
            }
            val body = text.substring(i + 1, semi)
            val decoded = decodeEntity(body)
            if (decoded != null) {
                sb.append(decoded)
                i = semi + 1
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun decodeEntity(body: String): String? {
        if (body.isEmpty()) return null
        if (body[0] == '#') {
            val code = if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) {
                body.substring(2).toIntOrNull(16)
            } else {
                body.substring(1).toIntOrNull()
            } ?: return null
            return if (code in 1..0x10FFFF) String(Character.toChars(code)) else null
        }
        return NAMED_ENTITIES[body.lowercase()]
    }

    private val BLOCK_TAGS = setOf(
        "p", "div", "br", "li", "tr", "td", "th", "section", "article", "blockquote",
        "h1", "h2", "h3", "h4", "h5", "h6", "hr", "pre", "figure", "figcaption"
    )

    /** 把章节 HTML/XHTML 抽为纯文本（保留段落换行），用于 TTS 朗读与全文搜索。 */
    fun toPlainText(html: String): String {
        var text = html
        text = text.replace(Regex("(?is)<(script|style|head|svg|noscript)\\b[^>]*>.*?</\\1\\s*>"), "")
        text = text.replace(Regex("(?is)<!--.*?-->"), "")
        val blocks = BLOCK_TAGS.joinToString("|")
        text = text.replace(Regex("(?i)</?(?:$blocks)\\b[^>]*>"), "\n")
        text = text.replace(Regex("(?s)<[^>]+>"), "")
        text = unescape(text)
        text = text.replace('\u00A0', ' ')
        text = text.replace(Regex("[ \\t]+"), " ")
        text = text.replace(Regex(" *\\n *"), "\n")
        text = text.replace(Regex("\\n{3,}"), "\n\n")
        return text.trim()
    }

    /** 从 XHTML 中取第一个标题文本，用作目录兜底。 */
    fun firstHeadingText(html: String): String? {
        val m = Regex("(?is)<h[1-6]\\b[^>]*>(.*?)</h[1-6]\\s*>").find(html) ?: return null
        val inner = m.groupValues[1].replace(Regex("(?s)<[^>]+>"), "")
        val text = unescape(inner).trim()
        return text.ifEmpty { null }
    }

    private val XML_BUILTIN = setOf("amp", "lt", "gt", "quot", "apos")

    /**
     * 把 XHTML 中未定义的命名实体转成数字实体，并去掉 DOCTYPE，使其可被 XML 解析器直接解析。
     * （很多 EPUB 的 XHTML 用了 &nbsp; 但没带 DTD。）
     */
    fun sanitizeXml(xml: String): String {
        var text = xml
        text = text.replace(Regex("(?is)<!DOCTYPE[^>]*(\\[[^\\]]*\\])?[^>]*>"), "")
        text = Regex("&([a-zA-Z][a-zA-Z0-9]*);").replace(text) { m ->
            val name = m.groupValues[1]
            if (name in XML_BUILTIN) {
                m.value
            } else {
                NAMED_ENTITIES[name.lowercase()]?.let { c ->
                    "&#" + Character.codePointAt(c, 0) + ";"
                } ?: "&#160;"
            }
        }
        return text
    }
}
