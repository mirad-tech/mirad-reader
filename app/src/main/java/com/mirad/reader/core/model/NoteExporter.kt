package com.mirad.reader.core.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 把划线、笔记与书签导出成 Markdown。
 *
 * 纯函数，便于单元测试：输入是已经读出来的实体数据，不依赖数据库或 Android。
 */
object NoteExporter {

    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** 导出结果为空时返回空串，调用方据此提示"没有可导出的内容"。 */
    fun toMarkdown(
        bookTitle: String,
        author: String?,
        highlights: List<ExportHighlight>,
        bookmarks: List<ExportBookmark>,
        exportedAtMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        if (highlights.isEmpty() && bookmarks.isEmpty()) return ""

        val stamp = Instant.ofEpochMilli(exportedAtMillis).atZone(zone).format(timeFormatter)
        val builder = StringBuilder(1024)
        builder.append("# ").append(bookTitle).append(" · 读书笔记").append('\n').append('\n')
        if (!author.isNullOrBlank()) {
            builder.append("- 作者：").append(author).append('\n')
        }
        builder.append("- 划线 ").append(highlights.size).append(" 条")
        builder.append("，书签 ").append(bookmarks.size).append(" 条").append('\n')
        builder.append("- 导出时间：").append(stamp).append('\n')

        if (highlights.isNotEmpty()) {
            builder.append('\n').append("## 划线与笔记").append('\n')
            for ((chapterIndex, items) in highlights.groupBy { it.chapterIndex }.toSortedMap()) {
                builder.append('\n').append("### ").append(chapterTitle(items.first().chapterTitle, chapterIndex)).append('\n').append('\n')
                items.sortedBy { it.startOffset }.forEach { item ->
                    builder.append("> ").append(inline(item.text)).append('\n')
                    if (!item.note.isNullOrBlank()) {
                        builder.append('\n').append("笔记：").append(inline(item.note)).append('\n')
                    }
                    builder.append('\n')
                }
            }
        }

        if (bookmarks.isNotEmpty()) {
            builder.append('\n').append("## 书签").append('\n')
            for ((chapterIndex, items) in bookmarks.groupBy { it.chapterIndex }.toSortedMap()) {
                builder.append('\n').append("### ").append(chapterTitle(items.first().chapterTitle, chapterIndex)).append('\n').append('\n')
                items.sortedBy { it.charOffset }.forEach { item ->
                    builder.append("- ").append(inline(item.snippet)).append('\n')
                }
            }
        }
        return builder.toString().trimEnd() + "\n"
    }

    private fun chapterTitle(title: String, index: Int): String =
        title.ifBlank { "第 ${index + 1} 章" }

    /** 压平换行并按需转义，避免破坏 Markdown 结构。 */
    private fun inline(text: String): String =
        text.replace('\n', ' ').replace('\r', ' ').trim()

    /** 导出用的划线/笔记视图。 */
    data class ExportHighlight(
        val chapterIndex: Int,
        val chapterTitle: String,
        val startOffset: Int,
        val text: String,
        val note: String?
    )

    /** 导出用的书签视图。 */
    data class ExportBookmark(
        val chapterIndex: Int,
        val chapterTitle: String,
        val charOffset: Int,
        val snippet: String
    )
}
