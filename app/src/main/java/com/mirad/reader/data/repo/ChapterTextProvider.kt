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
open class ChapterTextProvider(private val paths: AppPaths? = null) {

    open fun readText(book: BookEntity, chapter: ChapterEntity): String =
        readTextOrNull(book, chapter).orEmpty()

    /**
     * 读取章节正文。成功返回正文字符串（合法空章节返回 ""），读取失败（文件缺失、EOF 异常等）返回 null。
     */
    open fun readTextOrNull(book: BookEntity, chapter: ChapterEntity): String? = when (book.format) {
        BookFormat.EPUB -> readEpubTextOrNull(book, chapter)
        else -> readTxtTextOrNull(book, chapter)
    }

    /** TXT：seek 到章节字节区间读取并解码。 */
    fun readTxtText(book: BookEntity, chapter: ChapterEntity): String =
        readTxtTextOrNull(book, chapter).orEmpty()

    fun readTxtTextOrNull(book: BookEntity, chapter: ChapterEntity): String? {
        val path = book.contentPath ?: return null
        val file = File(path)
        if (!file.exists()) return null
        val length = (chapter.endByte - chapter.startByte).toInt().coerceAtLeast(0)
        if (length == 0) return ""
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(chapter.startByte)
                val buffer = ByteArray(length)
                raf.readFully(buffer)
                String(buffer, Charsets.UTF_8)
            }
        }.getOrNull()
    }

    /** EPUB：读取 zip 内 XHTML 并转成纯文本。 */
    fun readEpubText(book: BookEntity, chapter: ChapterEntity): String =
        readEpubTextOrNull(book, chapter).orEmpty()

    fun readEpubTextOrNull(book: BookEntity, chapter: ChapterEntity): String? {
        val href = chapter.href ?: return null
        val file = File(book.filePath)
        if (!file.exists()) return null
        return runCatching {
            ZipEntryReader.open(file).use { reader ->
                val html = reader.readText(href, maxBytes = ZipEntryReader.MAX_CHAPTER_BYTES) ?: return null
                Html.toPlainText(html)
            }
        }.getOrNull()
    }

    /** EPUB：读取原始 XHTML（渲染用）。 */
    fun readEpubHtml(book: BookEntity, href: String, maxBytes: Int = ZipEntryReader.MAX_CHAPTER_BYTES): String? {
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

    val appPaths: AppPaths get() = paths ?: error("paths is not available")
}
