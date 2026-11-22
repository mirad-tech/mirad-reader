package com.mirad.reader.data.imports

import com.mirad.reader.core.epub.EpubParser
import com.mirad.reader.core.epub.ZipEntryReader
import com.mirad.reader.core.html.Html
import com.mirad.reader.core.model.TocJson
import com.mirad.reader.core.text.StreamingTxtProcessor
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.imports.ImportPipeline.Companion.EPUB_EXT
import com.mirad.reader.data.imports.ImportPipeline.Companion.MAX_TXT_BYTES
import com.mirad.reader.data.io.BookContentAccess
/** 导入前解析得到的书籍、章节与来源版本，尚未写入数据库。 */
internal data class PreparedBook(
    val book: BookEntity,
    val chapters: List<ChapterEntity>,
    val encoding: String?,
    val ruleId: String?,
    val regex: String?,
    val totalChars: Long,
    val sourceVersion: SourceVersion,
    val epub: List<EpubChapterSignature>? = null,
    val sourceStamp: BookSourceStampStore.SourceStamp? = null
)

/**
 * 把来源文件解析成可入库的 [PreparedBook]：TXT 流式识别编码并分章，EPUB 读取书脊、目录与封面。
 * 只读源文件、在应用目录生成封面，不写数据库。
 */
internal class BookPreparer(
    private val paths: AppPaths,
    private val contentAccess: BookContentAccess
) {
    fun prepare(
        extension: String,
        source: ImportSource,
        token: String,
        bookFilePath: String,
        displayTitle: String,
        size: Long,
        local: Boolean,
        sourceVersion: SourceVersion
    ): PreparedBook =
        if (extension == EPUB_EXT) prepareEpub(source, token, bookFilePath, displayTitle, size, local, sourceVersion)
        else prepareTxt(source, token, bookFilePath, displayTitle, size, local, sourceVersion)

    private fun prepareTxt(
        source: ImportSource,
        token: String,
        bookFilePath: String,
        displayTitle: String,
        size: Long,
        local: Boolean,
        sourceVersion: SourceVersion
    ): PreparedBook {
        if (size > MAX_TXT_BYTES) {
            throw ImportException("TXT 文件过大（${size / 1024 / 1024} MB，上限 80 MB）")
        }
        if (size <= 0L || !contentAccess.isReadable(bookFilePath)) {
            throw ImportException("文件内容为空")
        }

        val streamResult = try {
            StreamingTxtProcessor.indexRaw(
                openStream = { contentAccess.openStream(bookFilePath) },
                declaredBytes = size,
                manualEncoding = null
            )
        } catch (e: Throwable) {
            throw ImportException("TXT 解析失败: ${e.message ?: "未知错误"}")
        }

        val slices = streamResult.slices
        if (slices.isEmpty()) throw ImportException("无法切分正文")

        val chapters = slices.mapIndexed { index, slice ->
            ChapterEntity(
                bookId = 0L,
                index = index,
                title = slice.title,
                startByte = slice.startByte,
                endByte = slice.endByte,
                charCount = slice.charCount.toLong(),
                titleLineChars = slice.titleLineChars,
                isVolume = slice.isVolume
            )
        }

        val coverFile = paths.coverFile(token)
        val hasCover = CoverGenerator.generatePlaceholder(displayTitle, null, coverFile)
        val effectiveSize = streamResult.totalBytes.takeIf { it > 0L } ?: size
        val stamp = if (local) BookSourceStampStore.capture(contentAccess, bookFilePath, effectiveSize) else null

        val book = BookEntity(
            title = displayTitle,
            author = null,
            format = BookFormat.TXT,
            filePath = bookFilePath,
            contentPath = null,
            coverPath = if (hasCover) coverFile.absolutePath else null,
            sourceUri = sourceUriOf(source),
            fileSize = effectiveSize,
            encoding = streamResult.charset,
            chapterRuleId = streamResult.ruleId,
            chapterRegex = streamResult.regex,
            totalChapters = chapters.size,
            totalChars = streamResult.totalChars,
            addedAt = System.currentTimeMillis()
        )
        return PreparedBook(
            book = book,
            chapters = chapters,
            encoding = streamResult.charset,
            ruleId = streamResult.ruleId,
            regex = streamResult.regex,
            totalChars = streamResult.totalChars,
            sourceVersion = sourceVersion,
            sourceStamp = stamp
        )
    }

    private fun prepareEpub(
        source: ImportSource,
        token: String,
        bookFilePath: String,
        displayTitle: String,
        size: Long,
        local: Boolean,
        sourceVersion: SourceVersion
    ): PreparedBook {
        val reader = runCatching { contentAccess.openZip(bookFilePath) }
            .getOrElse { throw ImportException("EPUB 解析失败：${it.message ?: "文件可能已损坏"}") }

        val info: com.mirad.reader.core.epub.EpubBookInfo
        val chapters: ArrayList<ChapterEntity>
        val signatures: ArrayList<EpubChapterSignature>?
        var totalChars = 0L
        var hasCover = false
        val coverFile = paths.coverFile(token)

        try {
            info = runCatching { EpubParser(reader).parse() }
                .getOrElse { throw ImportException("EPUB 解析失败：${it.message ?: "文件可能已损坏"}") }
            chapters = ArrayList(info.spine.size)
            signatures = if (local) ArrayList(info.spine.size) else null
            for (item in info.spine) {
                val html = reader.readText(item.href, maxBytes = ZipEntryReader.MAX_CHAPTER_BYTES)
                if (local && html == null) throw ImportException("EPUB 有章节无法读取，未完成导入")
                val text = html?.let { Html.toPlainText(it) }.orEmpty()
                signatures?.add(EpubChapterSignature.of(text))
                totalChars += text.length
                chapters.add(
                    ChapterEntity(
                        bookId = 0L,
                        index = item.index,
                        title = info.chapterTitles[item.index] ?: "第 ${item.index + 1} 节",
                        href = item.href,
                        charCount = text.length.toLong()
                    )
                )
            }
            val coverHref = info.coverHref
            if (coverHref != null) {
                runCatching {
                    if (reader.sizeOf(coverHref) in 1..ZipEntryReader.MAX_COVER_BYTES.toLong()) {
                        reader.openStream(coverHref)?.use { input ->
                            hasCover = CoverGenerator.saveCover(input, coverFile)
                        }
                    }
                }
            }
        } finally {
            reader.close()
        }

        val title = info.title?.takeIf { it.isNotBlank() } ?: displayTitle
        val stamp = if (local) BookSourceStampStore.capture(contentAccess, bookFilePath, size) else null
        val book = BookEntity(
            title = title,
            author = info.author,
            format = BookFormat.EPUB,
            filePath = bookFilePath,
            contentPath = null,
            coverPath = if (hasCover) coverFile.absolutePath else null,
            sourceUri = sourceUriOf(source),
            fileSize = size,
            tocJson = if (info.toc.isEmpty()) null else TocJson.encode(info.toc),
            language = info.language,
            publisher = info.publisher,
            description = info.description,
            totalChapters = chapters.size,
            totalChars = totalChars,
            addedAt = System.currentTimeMillis()
        )
        return PreparedBook(
            book = book,
            chapters = chapters,
            encoding = null,
            ruleId = null,
            regex = null,
            totalChars = totalChars,
            sourceVersion = sourceVersion,
            epub = signatures,
            sourceStamp = stamp
        )
    }

    private fun sourceUriOf(source: ImportSource): String = when (source) {
        is ImportSource.FromUri -> source.uriString
        is ImportSource.FromFile -> source.originUrl ?: source.file.absolutePath
    }
}
