package com.mirad.reader.data.repo

import com.mirad.reader.core.html.Html
import com.mirad.reader.core.epub.ZipEntryReader
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.ChapterEntity
import java.io.File
import java.io.RandomAccessFile

/**
 * 章节正文读取：TXT 按字节偏移从归一化文件读取，EPUB 按需从 zip 读取原始 XHTML 并抽取纯文本。
 */
class ChapterTextProvider(private val paths: AppPaths) {

    fun readText(book: BookEntity, chapter: ChapterEntity): String = when (book.format) {
        BookFormat.EPUB -> readEpubText(book, chapter)
        else -> readTxtText(book, chapter)
    }

    /** TXT：seek 到章节字节区间读取并解码。 */
    fun readTxtText(book: BookEntity, chapter: ChapterEntity): String {
        val file = File(book.contentPath ?: return "")
        if (!file.exists()) return ""
        val length = (chapter.endByte - chapter.startByte).toInt().coerceAtLeast(0)
        if (length == 0) return ""
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(chapter.startByte)
                val buffer = ByteArray(length)
                raf.readFully(buffer)
                String(buffer, Charsets.UTF_8)
            }
        }.getOrDefault("")
    }

    /** EPUB：读取 zip 内 XHTML 并转成纯文本。 */
    fun readEpubText(book: BookEntity, chapter: ChapterEntity): String {
        val href = chapter.href ?: return ""
        val file = File(book.filePath)
        if (!file.exists()) return ""
        return runCatching {
            ZipEntryReader.open(file).use { reader ->
                val html = reader.readText(href, maxBytes = 4 * 1024 * 1024) ?: return ""
                Html.toPlainText(html)
            }
        }.getOrDefault("")
    }

    /** EPUB：读取原始 XHTML（渲染用）。 */
    fun readEpubHtml(book: BookEntity, href: String, maxBytes: Int = 8 * 1024 * 1024): String? {
        val file = File(book.filePath)
        if (!file.exists()) return null
        return runCatching {
            ZipEntryReader.open(file).use { it.readText(href, maxBytes) }
        }.getOrNull()
    }

    fun coverFile(book: BookEntity): File? {
        val path = book.coverPath ?: return null
        val file = File(path)
        return if (file.exists()) file else null
    }

    val appPaths: AppPaths get() = paths
}
