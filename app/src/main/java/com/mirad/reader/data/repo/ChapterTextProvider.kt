package com.mirad.reader.data.repo

import com.mirad.reader.core.epub.ZipEntryReader
import com.mirad.reader.core.html.Html
import com.mirad.reader.core.text.StreamingTxtProcessor
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.io.BookContentAccess
import java.io.File
import java.io.RandomAccessFile

/**
 * 章节正文读取：
 * - TXT（零拷贝新模式，`contentPath == null`）：按原文件字节区间从 [BookContentAccess] 随机读取并即时解码归一化；
 * - TXT（历史旧书兼容，`contentPath != null`）：按 UTF-8 字节区间从归一化文件读取；
 * - EPUB：按需通过 [BookContentAccess.openZip] 随机读取原始 XHTML，无需整本拷贝或解压落盘。
 */
open class ChapterTextProvider(
    private val paths: AppPaths? = null,
    val contentAccess: BookContentAccess = BookContentAccess()
) {

    open fun readText(book: BookEntity, chapter: ChapterEntity): String =
        readTextOrNull(book, chapter).orEmpty()

    /**
     * 读取章节正文。成功返回正文字符串（合法空章节返回 ""），读取失败（文件缺失、权限失效、EOF 异常等）返回 null。
     */
    open fun readTextOrNull(book: BookEntity, chapter: ChapterEntity): String? = when (book.format) {
        BookFormat.EPUB -> readEpubTextOrNull(book, chapter)
        else -> readTxtTextOrNull(book, chapter)
    }

    /** 检查书籍底层正文源当前是否可读。 */
    fun isBookSourceReadable(book: BookEntity): Boolean {
        if (book.format != BookFormat.EPUB && !book.contentPath.isNullOrBlank()) {
            return File(book.contentPath).isFile
        }
        return contentAccess.isReadable(book.filePath)
    }

    /** TXT：seek 到章节字节区间读取并解码。 */
    fun readTxtText(book: BookEntity, chapter: ChapterEntity): String =
        readTxtTextOrNull(book, chapter).orEmpty()

    fun readTxtTextOrNull(book: BookEntity, chapter: ChapterEntity): String? {
        val rawLength = chapter.endByte - chapter.startByte
        if (rawLength < 0L || rawLength > Int.MAX_VALUE) return null
        val length = rawLength.toInt()
        val legacyNormalizedPath = book.contentPath
        if (!legacyNormalizedPath.isNullOrBlank()) {
            val file = File(legacyNormalizedPath)
            if (!file.isFile) return null
            if (length == 0) return ""
            return runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    if (chapter.startByte + length > raf.length()) return null
                    raf.seek(chapter.startByte)
                    val buffer = ByteArray(length)
                    raf.readFully(buffer)
                    String(buffer, Charsets.UTF_8)
                }
            }.getOrNull()
        }

        if (length == 0) {
            return if (contentAccess.isReadable(book.filePath)) "" else null
        }
        val rawBytes = contentAccess.readRange(book.filePath, chapter.startByte, length) ?: return null
        return runCatching {
            StreamingTxtProcessor.decodeRawSlice(
                rawBytes = rawBytes,
                charsetName = book.encoding,
                stripLeadingBom = chapter.startByte == 0L
            )
        }.getOrNull()
    }

    /** EPUB：读取 zip 内 XHTML 并转成纯文本。 */
    fun readEpubTextOrNull(book: BookEntity, chapter: ChapterEntity): String? {
        val href = chapter.href ?: return null
        return runCatching {
            contentAccess.openZip(book.filePath).use { reader ->
                val html = reader.readText(href, maxBytes = ZipEntryReader.MAX_CHAPTER_BYTES) ?: return null
                Html.toPlainText(html)
            }
        }.getOrNull()
    }

    /** EPUB：读取原始 XHTML（渲染用）。 */
    fun readEpubHtml(book: BookEntity, href: String, maxBytes: Int = ZipEntryReader.MAX_CHAPTER_BYTES): String? {
        return runCatching {
            contentAccess.openZip(book.filePath).use { it.readText(href, maxBytes) }
        }.getOrNull()
    }

    fun coverFile(book: BookEntity): File? {
        val path = book.coverPath ?: return null
        val file = File(path)
        return if (file.exists()) file else null
    }

    val appPaths: AppPaths get() = paths ?: error("paths is not available")
}
