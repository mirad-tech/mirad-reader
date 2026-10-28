package com.mirad.reader.core.sources.rules

/** 仅在引号、括号和模板之外分隔，避免破坏 CSS 属性、JSONPath 和脚本。 */
internal object RuleSyntax {
    fun split(text: String, separator: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var index = 0
        var quote = '\u0000'
        var depth = 0
        while (index < text.length) {
            val c = text[index]
            if (c == '\\') { index += 2; continue }
            if (quote != '\u0000') {
                if (c == quote) quote = '\u0000'
                index++
                continue
            }
            if (c == '\'' || c == '"' || c == '`') quote = c
            else if (depth == 0 && text.startsWith(separator, index)) {
                result += text.substring(start, index)
                index += separator.length
                start = index
                continue
            } else if (c == '(' || c == '[' || c == '{') depth++
            else if (c == ')' || c == ']' || c == '}') depth--
            index++
        }
        result += text.substring(start)
        return result
    }

    fun closingBrace(text: String, open: Int): Int {
        var depth = 0
        var quote = '\u0000'
        var index = open
        while (index < text.length) {
            val c = text[index]
            if (c == '\\') { index += 2; continue }
            if (quote != '\u0000') {
                if (c == quote) quote = '\u0000'
            } else if (c == '\'' || c == '"' || c == '`') quote = c
            else if (c == '{') depth++
            else if (c == '}' && --depth == 0) return index
            index++
        }
        throw RuleEvaluationException("规则大括号未闭合")
    }

    fun unquote(value: String): String {
        val text = value.trim()
        return if (text.length >= 2 && (text.first() == '"' || text.first() == '\'') && text.last() == text.first()) {
            if (text.first() == '"') {
                (kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonPrimitive).content
            } else text.substring(1, text.length - 1).replace("\\'", "'").replace("\\\\", "\\")
        } else text
    }

    fun scripts(text: String): List<String> {
        val scripts = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            when {
                text.regionMatches(index, "@js:", 0, 4, true) -> {
                    scripts += text.substring(index + 4)
                    return scripts
                }
                text.regionMatches(index, "<js>", 0, 4, true) -> {
                    val end = text.indexOf("</js>", index + 4, true)
                    if (end < 0) return scripts + text.substring(index + 4)
                    scripts += text.substring(index + 4, end)
                    index = end + 5
                }
                text.startsWith("{{", index) -> {
                    val end = closingBrace(text, index)
                    scripts += text.substring(index + 2, end - 1)
                    index = end + 1
                }
                else -> index++
            }
        }
        return scripts
    }

    /** 保留代码、删除注释和普通字符串，避免把示例网址或说明文本当成调用。 */
    fun executableText(script: String): String {
        val out = StringBuilder(script.length)
        var i = 0
        while (i < script.length) {
            val c = script[i]
            if (c == '\'' || c == '"') {
                val quote = c
                out.append(' ')
                i++
                while (i < script.length) {
                    if (script[i] == '\\') i += 2
                    else if (script[i++] == quote) break
                }
            } else if (script.startsWith("//", i)) {
                while (i < script.length && script[i] != '\n') i++
                out.append('\n')
            } else if (script.startsWith("/*", i)) {
                val end = script.indexOf("*/", i + 2)
                i = if (end < 0) script.length else end + 2
                out.append(' ')
            } else { out.append(c); i++ }
        }
        return out.toString()
    }
}
