package com.mirad.reader.data.repo

import androidx.room.withTransaction
import com.mirad.reader.data.db.AppDatabase
import com.mirad.reader.core.model.ChapterTextMap
import com.mirad.reader.core.text.ChapterSlice
import java.security.MessageDigest
import com.mirad.reader.core.text.ChapterDetector
import com.mirad.reader.core.text.ChapterSplitter
import com.mirad.reader.core.text.TextCodec
import com.mirad.reader.core.text.TextNormalizer
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.ChapterEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 已导入书籍的维护操作。
 *
 * 存在的原因：TXT 正文在导入时就解码成 UTF-8 写进了 `content.utf8.txt`，
 * 所以解码器后来修好了，**已经导入的书仍然是乱的**，必须用原始文件重新解码。
 * （EPUB 不在此列：正文是显示时从 zip 现读的，修解码器即刻生效。）
 */
class BookMaintenance(
    private val books: BookRepository,
    private val paths: AppPaths,
    private val database: AppDatabase
) {

    data class ChapterPreview internal constructor(
        val slices: List<ChapterSlice>,
        val automatic: Boolean,
        val reason: String,
        internal val bookId: Long,
        internal val digest: String,
        internal val oldChapters: List<ChapterEntity>,
        internal val ruleId: String?
    )

    suspend fun previewChapters(book: BookEntity): ChapterPreview = withContext(Dispatchers.IO) {
        require(book.format != BookFormat.EPUB) { "EPUB 不需要重新识别章节" }
        val current = books.getBook(book.id) ?: error("书籍已不存在")
        val bytes = normalizedFile(current).readBytes()
        val text = bytes.toString(Charsets.UTF_8)
        check(text.isNotBlank()) { "没有可识别的正文" }
        val detection = ChapterDetector.detect(text)
        ChapterPreview(ChapterSplitter.split(text, detection), detection.marks.isEmpty(), detection.reason,
            book.id, digest(bytes), books.getChapters(book.id), detection.rule?.id)
    }

    suspend fun applyChapters(book: BookEntity, preview: ChapterPreview): Int = withContext(Dispatchers.IO) {
        require(book.id == preview.bookId && book.format != BookFormat.EPUB)
        database.withTransaction {
            val current = books.getBook(book.id) ?: error("书籍已不存在")
            val bytes = normalizedFile(current).readBytes()
            check(digest(bytes) == preview.digest && books.getChapters(book.id) == preview.oldChapters) {
                "正文或目录已变化，请重新预览"
            }
            val text = bytes.toString(Charsets.UTF_8)
            val slices = preview.slices
            check(slices.isNotEmpty()) { "无法切分正文" }
            val newMaps = slices.map { slice ->
                ChapterTextMap(text.substring(slice.startChar, slice.endChar), slice.title,
                    slice.titleLineChars, slice.isVolume, slice.startChar)
            }
            var sourceStart = 0
            var previousEnd = 0L
            var validPrefix = true
            val oldMaps = preview.oldChapters.map { chapter ->
                // Refuse malformed historical byte indexes rather than fabricate an anchor.
                val valid = validPrefix && chapter.startByte == previousEnd && chapter.endByte >= chapter.startByte && chapter.endByte <= bytes.size
                previousEnd = chapter.endByte
                validPrefix = valid
                if (!valid) null else {
                    val body = bytes.copyOfRange(chapter.startByte.toInt(), chapter.endByte.toInt()).toString(Charsets.UTF_8)
                    validPrefix = body.toByteArray(Charsets.UTF_8).contentEquals(bytes.copyOfRange(chapter.startByte.toInt(), chapter.endByte.toInt()))
                    val map = ChapterTextMap(body, chapter.title, chapter.titleLineChars, chapter.isVolume, sourceStart)
                    sourceStart += body.length
                    map.takeIf { validPrefix }
                }
            }
            fun point(chapter: Int, offset: Int): Pair<Int, Int>? {
                val source = oldMaps.getOrNull(chapter)?.sourceAt(offset) ?: return null
                return newMaps.mapIndexedNotNull { index, map -> map.domAt(source)?.let { index to it } }.lastOrNull()
            }
            val bookmarks = database.bookmarkDao().getAllForBook(book.id)
            val highlights = database.highlightDao().getAllForBook(book.id)
            val progress = books.getProgress(book.id)
            val chapters = slices.mapIndexed { i, slice ->
                ChapterEntity(bookId = book.id, index = i, title = slice.title,
                    startByte = slice.startByte, endByte = slice.endByte, charCount = slice.charCount.toLong(),
                    titleLineChars = slice.titleLineChars, isVolume = slice.isVolume)
            }
            database.searchFtsDao().deleteForBook(book.id)
            books.saveChapters(book.id, chapters)
            books.updateIndexInfo(book.id, chapters.size, text.length.toLong(), preview.ruleId, null, current.encoding)
            bookmarks.forEach { bookmark ->
                val mapped = point(bookmark.chapterIndex, bookmark.charOffset)
                database.bookmarkDao().update(if (mapped == null) bookmark.copy(chapterIndex = -1)
                    else bookmark.copy(chapterIndex = mapped.first, charOffset = mapped.second, chapterTitle = chapters[mapped.first].title))
            }
            highlights.forEach { highlight ->
                val ranges = oldMaps.getOrNull(highlight.chapterIndex)?.migrateRange(
                    highlight.startOffset, highlight.endOffset, highlight.text, newMaps)
                if (ranges == null) database.highlightDao().update(highlight.copy(chapterIndex = -1))
                else ranges.forEachIndexed { i, range ->
                    val mapped = highlight.copy(id = if (i == 0) highlight.id else 0,
                        chapterIndex = range.chapter, startOffset = range.start, endOffset = range.end, text = range.text)
                    if (i == 0) database.highlightDao().update(mapped) else database.highlightDao().insert(mapped)
                }
            }
            if (progress != null) {
                val mapped = point(progress.chapterIndex, progress.charOffset)
                    ?: oldMaps.getOrNull(progress.chapterIndex)?.let { old ->
                        val index = slices.indexOfLast { it.startChar <= old.sourceStart }.coerceAtLeast(0)
                        index to 0
                    } ?: (0 to 0)
                books.saveProgress(progress.copy(chapterIndex = mapped.first, charOffset = mapped.second, pageIndex = 0, pageCount = 0))
            }
            chapters.size
        }
    }

    private fun normalizedFile(book: BookEntity): File {
        val file = File(book.contentPath ?: error("找不到归一化正文，请重新导入"))
        check(file.isFile) { "找不到归一化正文，请重新导入" }
        return file
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    data class ReindexResult(
        val encoding: String,
        val source: String,
        val chapters: Int,
        val chars: Long
    )

    /** 候选编码的一项预览，供用户肉眼挑正确的那一个。 */
    data class Preview(
        val charset: String,
        val source: String,
        val text: String
    )

    /** 用原始文件重新解码并重建章节索引。 */
    suspend fun redetect(book: BookEntity, charsetHint: String? = null): ReindexResult =
        withContext(Dispatchers.IO) {
            val bytes = rawBytes(book)
            val decoded = TextCodec.decode(bytes, manual = charsetHint)
            val normalized = TextNormalizer.normalize(decoded.text)
            if (normalized.isBlank()) throw IllegalStateException("重新解码后没有正文内容")
            database.searchFtsDao().deleteForBook(book.id)
            writeContent(book, normalized)
            val chapters = index(book, normalized)
            books.updateBook(
                book.copy(
                    encoding = decoded.charsetName,
                    chapterRuleId = chapters.ruleId,
                    totalChapters = chapters.count,
                    totalChars = normalized.length.toLong()
                )
            )
            ReindexResult(decoded.charsetName, decoded.source, chapters.count, normalized.length.toLong())
        }

    /**
     * 各候选编码的前若干字。用于「编码」对话框：选中即预览，用户一眼就能看出哪个是对的。
     */
    suspend fun previews(book: BookEntity, maxChars: Int = 100): List<Preview> =
        withContext(Dispatchers.IO) {
            val bytes = runCatching { rawBytes(book) }.getOrNull() ?: return@withContext emptyList()
            val auto = TextCodec.decode(bytes)
            val autoPreview = Preview(
                charset = auto.charsetName,
                source = "自动检测（${auto.source}）",
                text = auto.text.take(maxChars).replace('\n', ' ')
            )
            val candidates = listOf("UTF-8", "GB18030", "BIG5", "UTF-16LE", "UTF-16BE")
            val list = ArrayList<Preview>(candidates.size + 1)
            list.add(autoPreview)
            for (name in candidates) {
                val decoded = runCatching { TextCodec.decode(bytes, manual = name) }.getOrNull() ?: continue
                list.add(
                    Preview(
                        charset = decoded.charsetName,
                        source = "手动指定",
                        text = decoded.text.take(maxChars).replace('\n', ' ')
                    )
                )
            }
            list
        }

    private fun rawBytes(book: BookEntity): ByteArray {
        if (book.format == BookFormat.EPUB) {
            // EPUB 是显示时现读现解码，没有需要重写的正文文件
            throw IllegalStateException("EPUB 不需要重新识别编码")
        }
        val file = File(book.filePath)
        if (!file.exists()) throw IllegalStateException("找不到原始文件，请重新导入")
        return file.readBytes()
    }

    private fun contentFile(book: BookEntity): File = paths.contentFile(books.tokenOf(book))

    private fun writeContent(book: BookEntity, text: String) {
        val file = contentFile(book)
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    private class Indexed(val count: Int, val ruleId: String?)

    private suspend fun index(
        book: BookEntity,
        text: String,
        rule: com.mirad.reader.core.text.ChapterRule? = null
    ): Indexed {
        val detection = if (rule == null) ChapterDetector.detect(text) else ChapterDetector.detect(text, listOf(rule))
        val effective = detection.rule
        val slices = ChapterSplitter.split(text, detection)
        if (slices.isEmpty()) throw IllegalStateException("无法切分正文")
        val chapters = slices.mapIndexed { i, slice ->
            ChapterEntity(
                bookId = book.id,
                index = i,
                title = slice.title,
                startByte = slice.startByte,
                endByte = slice.endByte,
                charCount = slice.charCount.toLong(),
                titleLineChars = slice.titleLineChars,
                isVolume = slice.isVolume
            )
        }
        books.saveChapters(book.id, chapters)
        clampProgress(book.id, chapters)
        return Indexed(chapters.size, effective?.id)
    }

    /**
     * 重新分章后章节数与各章长度都会变，把越界的阅读进度夹回有效范围。
     * 注意也要夹 `charOffset`：章节还在但变短了的话，旧偏移会落到正文之外。
     */
    private suspend fun clampProgress(bookId: Long, chapters: List<ChapterEntity>) {
        val progress = books.getProgress(bookId) ?: return
        val index = if (chapters.getOrNull(progress.chapterIndex) != null) {
            progress.chapterIndex
        } else {
            (chapters.size - 1).coerceAtLeast(0)
        }
        val maxOffset = chapters.getOrNull(index)?.charCount?.toInt() ?: 0
        val offset = progress.charOffset.coerceIn(0, maxOffset)
        if (index == progress.chapterIndex && offset == progress.charOffset) return
        books.saveProgress(
            progress.copy(chapterIndex = index, charOffset = offset, pageIndex = 0)
        )
    }
}
