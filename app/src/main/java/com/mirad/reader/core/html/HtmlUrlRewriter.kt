package com.mirad.reader.core.html

/**
 * 把 EPUB 片段里的相对资源 URL 改写为绝对 URL。
 *
 * 为什么需要：滚动窗口把多章拼进同一个文档后，文档级 `<base>` 只能有一个，
 * 而各章的资源相对路径不同。片段里必须自带绝对 URL。
 *
 * 纯逻辑（不依赖 Android）：资源地址由调用方通过 [resolve] 提供，便于单测。
 */
object HtmlUrlRewriter {

    private val KEEP_PREFIXES = listOf("data:", "http://", "https://", "//", "#", "mailto:", "javascript:", "tel:")

    /** 绝对地址、内联数据、纯锚点等不需要改写。 */
    fun shouldKeep(raw: String): Boolean {
        val value = raw.trim()
        return value.isEmpty() || KEEP_PREFIXES.any { value.startsWith(it, ignoreCase = true) }
    }

    /**
     * 把 [baseDir]（zip 内目录，如 `OEBPS/text`）与相对引用 [ref] 解析成 zip 内路径。
     * 越出归档根目录（`..` 过多）返回 null。
     */
    fun resolveRelative(baseDir: String, ref: String): String? {
        val clean = ref.substringBefore('#').substringBefore('?').trim()
        if (clean.isEmpty()) return null
        if (clean.startsWith("/")) return clean.trimStart('/').ifEmpty { null }
        val stack = ArrayList<String>()
        baseDir.split('/').filter { it.isNotEmpty() && it != "." }.forEach { stack.add(it) }
        for (part in clean.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isEmpty()) return null else stack.removeAt(stack.size - 1)
                else -> stack.add(part)
            }
        }
        return stack.joinToString("/").ifEmpty { null }
    }

    fun rewrite(html: String, resolve: (String) -> String?): String {
        var out = rewriteAttribute(html, "src", resolve)
        out = rewriteAttribute(out, "href", resolve)
        out = rewriteAttribute(out, "poster", resolve)
        out = rewriteAttribute(out, "data-src", resolve)
        out = rewriteSrcset(out, resolve)
        out = rewriteStyleAttribute(out, resolve)
        out = rewriteStyleTags(out, resolve)
        return out
    }

    private fun rewriteAttribute(html: String, name: String, resolve: (String) -> String?): String {
        val regex = Regex("(?i)\\b" + Regex.escape(name) + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
        return regex.replace(html) { match ->
            val raw = match.value
            val doubleQuoted = match.groupValues[2]
            val singleQuoted = match.groupValues[3]
            val value = if (match.groupValues[1].startsWith("\"")) doubleQuoted else singleQuoted
            if (shouldKeep(value)) return@replace raw
            val absolute = resolve(value) ?: return@replace raw
            val quote = if (match.groupValues[1].startsWith("\"")) "\"" else "'"
            "$name=$quote$absolute$quote"
        }
    }

    private fun rewriteSrcset(html: String, resolve: (String) -> String?): String {
        val regex = Regex("(?i)\\bsrcset\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
        return regex.replace(html) { match ->
            val raw = match.value
            val value = if (match.groupValues[1].startsWith("\"")) match.groupValues[2] else match.groupValues[3]
            if (value.isBlank()) return@replace raw
            val rewritten = value.split(',').map { part ->
                val candidate = part.trim()
                if (candidate.isEmpty()) return@map candidate
                val space = candidate.indexOfFirst { it.isWhitespace() }
                val url = if (space < 0) candidate else candidate.substring(0, space)
                val descriptor = if (space < 0) "" else candidate.substring(space)
                if (shouldKeep(url)) candidate else (resolve(url)?.let { "$it$descriptor" } ?: candidate)
            }
            val quote = if (match.groupValues[1].startsWith("\"")) "\"" else "'"
            "srcset=$quote${rewritten.joinToString(", ")}$quote"
        }
    }

    private fun rewriteStyleAttribute(html: String, resolve: (String) -> String?): String {
        val regex = Regex("(?i)\\bstyle\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
        return regex.replace(html) { match ->
            val isDoubleQuoted = match.groupValues[1].startsWith("\"")
            val content = if (isDoubleQuoted) match.groupValues[2] else match.groupValues[3]
            val attrQuote = if (isDoubleQuoted) "\"" else "'"
            val innerQuote = if (isDoubleQuoted) "'" else "\""
            val rewritten = rewriteCssUrls(content, innerQuote, resolve)
            "style=$attrQuote$rewritten$attrQuote"
        }
    }

    private fun rewriteStyleTags(html: String, resolve: (String) -> String?): String {
        val regex = Regex("(?is)<style\\b[^>]*>(.*?)</style>")
        return regex.replace(html) { match ->
            val content = match.groupValues[1]
            val rewritten = rewriteCssUrls(content, "\"", resolve)
            val openTag = match.value.substring(0, match.value.indexOf('>') + 1)
            "$openTag$rewritten</style>"
        }
    }

    private fun rewriteCssUrls(css: String, defaultQuote: String, resolve: (String) -> String?): String {
        val regex = Regex("(?i)url\\(\\s*(['\"]?)([^)'\"]+)\\1\\s*\\)")
        return regex.replace(css) { match ->
            val raw = match.groupValues[2]
            if (shouldKeep(raw)) match.value else resolve(raw)?.let { "url($defaultQuote$it$defaultQuote)" } ?: match.value
        }
    }
}
