package com.mirad.reader.data.repo

import com.mirad.reader.core.text.ChapterDetector
import com.mirad.reader.core.text.ChapterRules
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
    private val paths: AppPaths
) {

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

    /** 只重新分章（不动编码）。 */
    suspend fun resplit(book: BookEntity, ruleId: String?, regex: String? = null): Int =
        withContext(Dispatchers.IO) {
            val contentFile = contentFile(book)
            if (!contentFile.exists()) throw IllegalStateException("正文文件缺失，请重新导入")
            val text = contentFile.readText(Charsets.UTF_8)
            val rule = if (!regex.isNullOrBlank()) ChapterRules.custom(regex) else ChapterRules.byId(ruleId)
            val chapters = index(book, text, rule)
            books.updateBook(book.copy(chapterRuleId = chapters.ruleId))
            chapters.count
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
        val effective = rule ?: ChapterDetector.detect(text).rule
        val slices = ChapterSplitter.split(text, effective)
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
        clampProgress(book.id, chapters.size)
        return Indexed(chapters.size, effective?.id)
    }

    /** 重新分章后章节数会变，把越界的阅读进度夹回有效范围。 */
    private suspend fun clampProgress(bookId: Long, chapterCount: Int) {
        val progress = books.getProgress(bookId) ?: return
        if (progress.chapterIndex in 0 until chapterCount) return
        books.saveProgress(
            progress.copy(
                chapterIndex = (chapterCount - 1).coerceAtLeast(0),
                charOffset = 0,
                pageIndex = 0
            )
        )
    }
}
