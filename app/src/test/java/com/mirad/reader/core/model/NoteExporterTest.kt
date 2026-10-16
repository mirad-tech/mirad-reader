package com.mirad.reader.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class NoteExporterTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val stamp = ZonedDateTime.of(
        LocalDate.of(2026, 3, 10).atTime(9, 30), zone
    ).toInstant().toEpochMilli()

    private fun highlight(chapter: Int, title: String, offset: Int, text: String, note: String? = null) =
        NoteExporter.ExportHighlight(chapter, title, offset, text, note)

    private fun bookmark(chapter: Int, title: String, offset: Int, snippet: String) =
        NoteExporter.ExportBookmark(chapter, title, offset, snippet)

    @Test
    fun emptyInputProducesEmptyStringSoCallerCanTellThereIsNothingToExport() {
        assertEquals("", NoteExporter.toMarkdown("书", null, emptyList(), emptyList(), stamp, zone))
    }

    @Test
    fun groupsByChapterInOrderAndKeepsNotes() {
        val markdown = NoteExporter.toMarkdown(
            bookTitle = "风雪归人",
            author = "测试作者",
            highlights = listOf(
                highlight(2, "第三章 一纸旧约", 40, "第三段里的一句话"),
                highlight(0, "第一章 雪夜出城", 10, "第一段里的一句话", note = "这段写得很好"),
                highlight(0, "第一章 雪夜出城", 5, "更靠前的一句")
            ),
            bookmarks = listOf(bookmark(1, "第二章 破庙逢人", 3, "火光把他的影子投在断墙上")),
            exportedAtMillis = stamp,
            zone = zone
        )

        assertTrue(markdown.startsWith("# 风雪归人 · 读书笔记"))
        assertTrue(markdown.contains("- 作者：测试作者"))
        assertTrue(markdown.contains("- 划线 3 条，书签 1 条"))
        // 划线与书签分两节，划线在前
        val highlightSection = markdown.indexOf("## 划线与笔记")
        val bookmarkSection = markdown.indexOf("## 书签")
        assertTrue(highlightSection in 1 until bookmarkSection)
        // 划线按章节升序
        val firstChapter = markdown.indexOf("第一章 雪夜出城")
        val thirdChapter = markdown.indexOf("第三章 一纸旧约")
        assertTrue(firstChapter in 1 until thirdChapter)
        // 书签章节在书签节内
        assertTrue(markdown.indexOf("第二章 破庙逢人") > bookmarkSection)
        // 章内按偏移升序
        assertTrue(markdown.indexOf("更靠前的一句") < markdown.indexOf("第一段里的一句话"))
        assertTrue(markdown.contains("笔记：这段写得很好"))
        assertTrue(markdown.contains("- 火光把他的影子投在断墙上"))
    }

    @Test
    fun newlinesAreFlattenedSoMarkdownStructureSurvives() {
        val markdown = NoteExporter.toMarkdown(
            bookTitle = "书",
            author = null,
            highlights = listOf(highlight(0, "第一章", 0, "第一行\n第二行", note = "注\n释")),
            bookmarks = emptyList(),
            exportedAtMillis = stamp,
            zone = zone
        )
        assertTrue(markdown.contains("> 第一行 第二行"))
        assertTrue(markdown.contains("笔记：注 释"))
        assertTrue(!markdown.contains("第一行\n第二行"))
    }

    @Test
    fun blankChapterTitleFallsBackToIndex() {
        val markdown = NoteExporter.toMarkdown(
            bookTitle = "书",
            author = null,
            highlights = listOf(highlight(4, "", 0, "内容")),
            bookmarks = emptyList(),
            exportedAtMillis = stamp,
            zone = zone
        )
        assertTrue(markdown.contains("### 第 5 章"))
    }

    @Test
    fun bookmarksOnlyStillProducesADocument() {
        val markdown = NoteExporter.toMarkdown(
            bookTitle = "书",
            author = null,
            highlights = emptyList(),
            bookmarks = listOf(bookmark(0, "第一章", 1, "片段")),
            exportedAtMillis = stamp,
            zone = zone
        )
        assertTrue(markdown.contains("## 书签"))
        assertTrue(!markdown.contains("## 划线与笔记"))
    }
}
