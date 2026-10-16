package com.mirad.reader.core.html

/** 由归一化后的 TXT 正文生成章节 HTML（markup 由阅读器完全掌握）。 */
object ChapterHtmlBuilder {

    /** 每章 HTML 的容器 id，阅读器 JS 依赖它。 */
    const val CONTENT_ID = "mirad-content"

    fun build(
        title: String,
        bodyText: String,
        chapterIndex: Int,
        /** 标题行字符数；>0 时该行已由页眉/标题元素呈现，需要从正文中剔除。 */
        titleLineChars: Int = 0,
        includeTitleElement: Boolean = true
    ): String {
        val body = if (titleLineChars > 0 && titleLineChars <= bodyText.length) {
            bodyText.substring(titleLineChars)
        } else {
            bodyText
        }
        val sb = StringBuilder(body.length + 256)
        sb.append("<!DOCTYPE html>\n<html lang=\"zh\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no\">")
        sb.append("</head><body class=\"mirad-txt\">")
        sb.append("<div id=\"").append(CONTENT_ID).append("\" data-chapter=\"").append(chapterIndex).append("\">")
        if (includeTitleElement && title.isNotBlank()) {
            sb.append("<h2 class=\"mirad-chapter-title\">").append(Html.escape(title)).append("</h2>")
        }
        for (line in body.split('\n')) {
            val text = line.trim()
            if (text.isEmpty()) continue
            sb.append("<p>").append(Html.escape(text)).append("</p>")
        }
        sb.append("</div></body></html>")
        return sb.toString()
    }

    /**
     * 为原始 EPUB XHTML 包一层统一容器，便于阅读器注入排版与分页样式。
     * 找不到 body 时退化为整体包裹。
     */
    fun wrapBody(xhtml: String, chapterIndex: Int): String {
        val open = Regex("(?is)<body\\b[^>]*>")
        val close = Regex("(?is)</body\\s*>")
        val openMatch = open.find(xhtml)
        if (openMatch == null) {
            return "<div id=\"$CONTENT_ID\" data-chapter=\"$chapterIndex\">" + xhtml + "</div>"
        }
        val closeMatch = close.find(xhtml, openMatch.range.last + 1)
        val insertAt = openMatch.range.last + 1
        val wrapperOpen = "<div id=\"$CONTENT_ID\" data-chapter=\"$chapterIndex\">"
        return if (closeMatch != null) {
            xhtml.substring(0, insertAt) + wrapperOpen +
                xhtml.substring(insertAt, closeMatch.range.first) +
                "</div>" + xhtml.substring(closeMatch.range.first)
        } else {
            xhtml.substring(0, insertAt) + wrapperOpen + xhtml.substring(insertAt) + "</div>"
        }
    }
}
