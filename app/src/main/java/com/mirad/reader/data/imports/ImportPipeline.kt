package com.mirad.reader.data.imports

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.mirad.reader.core.epub.EpubParser
import com.mirad.reader.core.epub.ZipEntryReader
import com.mirad.reader.core.html.Html
import com.mirad.reader.core.model.TocJson
import com.mirad.reader.core.text.ChapterRules
import com.mirad.reader.core.text.ChapterSplitter
import com.mirad.reader.core.text.EncodingDetector
import com.mirad.reader.core.text.TextNormalizer
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.repo.BookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

class ImportException(message: String) : Exception(message)

/**
 * 导入管线：复制 → 识别格式 → 解析/分章 → 入库 → 生成封面。
 *
 * 串行执行，向 UI 暴露每一步的进度；单个文件失败不影响其余文件。
 */
class ImportPipeline(
    private val context: Context,
    private val paths: AppPaths,
    private val books: BookRepository,
    private val scope: CoroutineScope,
    private val appDatabase: com.mirad.reader.data.db.AppDatabase? = null
) {

    private val batches = ImportBatchQueue()
    val state: StateFlow<ImportState> = batches.state
    val lastSummary: StateFlow<ImportSummary?> = batches.summary
    private val mutex = Mutex()

    fun clearSummary(batchId: String? = lastSummary.value?.batchId) {
        batches.acknowledge(batchId)
    }

    fun import(sources: List<ImportSource>, force: Boolean = false): String? {
        if (sources.isEmpty()) return null
        val items = sources.map { ImportItem(UUID.randomUUID().toString(), it.displayName) }
        val batch = batches.enqueue(items)
        scope.launch { runBatch(batch, sources, items, force) }
        return batch.id
    }

    suspend fun importAndWait(sources: List<ImportSource>, force: Boolean = false): ImportSummary {
        if (sources.isEmpty()) return ImportSummary(0, 0, 0, emptyList())
        val items = sources.map { ImportItem(UUID.randomUUID().toString(), it.displayName) }
        val batch = batches.enqueue(items)
        return runBatch(batch, sources, items, force)
    }

    private suspend fun runBatch(
        batch: ImportBatchQueue.Batch, sources: List<ImportSource>, items: List<ImportItem>, force: Boolean
    ): ImportSummary = mutex.withLock {
        for (index in sources.indices) importOne(sources[index], items[index].id, force)
        batches.complete(batch)
    }

    // ------------------------------------------------------------------ 单个文件

    /** 内容指纹一致才算重复；任一侧读不出来就保守地**不判定为重复**（宁可多导入一份，也不静默丢书）。 */
    private fun sameContent(incoming: File, existing: com.mirad.reader.data.db.entity.BookEntity): Boolean {
        val mine = FileFingerprint.of(incoming) ?: return false
        val theirs = FileFingerprint.of(books.originalFileOf(existing)) ?: return false
        return mine == theirs
    }

    private suspend fun importOne(source: ImportSource, itemId: String, force: Boolean) {
        val token = UUID.randomUUID().toString()
        var insertedBookId: Long? = null
        try {
            updateItem(itemId) { it.copy(stage = ImportStage.COPYING, progress = 0f) }
            val extension = guessExtension(source)
            val target = paths.originalFile(token, extension)
            // 拷前预检：旧实现是整份拷贝完成后才在 prepareTxt 里校验大小，
            // 于是一个超大 TXT 会先把内部存储写满、再报"文件过大"。
            val declaredSize = withContext(Dispatchers.IO) { runCatching { sizeOf(source) }.getOrDefault(0L) }
            if (declaredSize > MAX_TXT_BYTES) {
                updateItem(itemId) {
                    it.copy(
                        stage = ImportStage.FAILED,
                        message = "文件过大（上限 ${MAX_TXT_BYTES / 1024 / 1024}MB），未开始导入",
                        progress = 1f
                    )
                }
                return
            }
            val size = withContext(Dispatchers.IO) {
                copyTo(source, target) { progress -> updateItem(itemId) { it.copy(progress = progress) } }
            }

            updateItem(itemId) { it.copy(stage = ImportStage.PARSING, progress = 0f) }
            val displayTitle = source.displayName.substringBeforeLast('.').trim().ifBlank { source.displayName }

            if (!force) {
                val duplicate = books.findDuplicate(displayTitle, size)
                // 同名同大小**不足以**判定重复：还要内容指纹一致，
                // 否则"同名同长度的不同书"会被静默跳过，用户以为导入失败。
                if (duplicate != null && sameContent(target, duplicate)) {
                    paths.deleteBookFiles(token, null)
                    updateItem(itemId) {
                        it.copy(
                            stage = ImportStage.SKIPPED,
                            message = "书架中已有同一本书《${duplicate.title}》",
                            progress = 1f
                        )
                    }
                    return
                }
            }

            val prepared = withContext(Dispatchers.IO) {
                if (extension == EPUB_EXT) prepareEpub(source, token, target, displayTitle, size)
                else prepareTxt(source, token, target, displayTitle, size)
            }

            val bookId = run {
                // 入库三步同事务：中途崩溃不会留下"有书行但章节为空"的半本书。
                val block: suspend () -> Long = {
                    val id = books.insertBook(prepared.book)
                    books.saveChapters(id, prepared.chapters.map { it.copy(bookId = id) })
                    books.updateIndexInfo(
                        bookId = id,
                        chapters = prepared.chapters.size,
                        chars = prepared.totalChars,
                        ruleId = prepared.ruleId,
                        regex = prepared.regex,
                        encoding = prepared.encoding
                    )
                    id
                }
                val db = appDatabase
                if (db != null) db.withTransaction { block() } else block()
            }
            insertedBookId = bookId

            updateItem(itemId) {
                it.copy(stage = ImportStage.DONE, progress = 1f, bookId = bookId)
            }
        } catch (t: Throwable) {
            withContext(Dispatchers.IO) {
                insertedBookId?.let { id -> books.getBook(id)?.let { books.deleteBook(it) } }
                // 封面写在 coversDir（不在书籍目录下），补偿必须显式带上，否则孤儿 jpg 会累积。
                if (insertedBookId == null) {
                    paths.deleteBookFiles(token, paths.coverFile(token).absolutePath)
                }
            }
            updateItem(itemId) {
                it.copy(
                    stage = ImportStage.FAILED,
                    progress = 1f,
                    message = t.message ?: t.javaClass.simpleName
                )
            }
        }
    }

    private data class PreparedBook(
        val book: BookEntity,
        val chapters: List<ChapterEntity>,
        val encoding: String?,
        val ruleId: String?,
        val regex: String?,
        val totalChars: Long
    )

    private fun prepareTxt(
        source: ImportSource,
        token: String,
        target: File,
        displayTitle: String,
        size: Long
    ): PreparedBook {
        if (size > MAX_TXT_BYTES) {
            throw ImportException("TXT 文件过大（${size / 1024 / 1024} MB，上限 80 MB）")
        }
        if (size == 0L || !target.exists()) throw ImportException("文件内容为空")

        val contentFile = paths.contentFile(token)
        val streamResult = try {
            com.mirad.reader.core.text.StreamingTxtProcessor.process(
                sourceFile = target,
                targetContentFile = contentFile,
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

        val book = BookEntity(
            title = displayTitle,
            author = null,
            format = BookFormat.TXT,
            filePath = target.absolutePath,
            contentPath = contentFile.absolutePath,
            coverPath = if (hasCover) coverFile.absolutePath else null,
            sourceUri = sourceUriOf(source),
            fileSize = size,
            encoding = streamResult.charset,
            chapterRuleId = streamResult.ruleId,
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
            totalChars = streamResult.totalChars
        )
    }

    private fun prepareEpub(
        source: ImportSource,
        token: String,
        target: File,
        displayTitle: String,
        size: Long
    ): PreparedBook {
        val info = runCatching { EpubParser.parse(target) }
            .getOrElse { throw ImportException("EPUB 解析失败：${it.message ?: "文件可能已损坏"}") }

        val reader = ZipEntryReader.open(target)
        val chapters = ArrayList<ChapterEntity>(info.spine.size)
        var totalChars = 0L
        try {
            for (item in info.spine) {
                // 与阅读/渲染路径同口径（ZipEntryReader.MAX_CHAPTER_BYTES）：口径不一致时
                // 会出现"目录与渲染正常、但该章搜索 0 命中 / TTS 为空"的章节。
                val html = reader.readText(item.href, maxBytes = ZipEntryReader.MAX_CHAPTER_BYTES)
                val text = html?.let { Html.toPlainText(it) }.orEmpty()
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
        } finally {
            reader.close()
        }

        val coverFile = paths.coverFile(token)
        var hasCover = false
        val coverHref = info.coverHref
        if (coverHref != null) {
            runCatching {
                ZipEntryReader.open(target).use { zipReader ->
                    if (zipReader.sizeOf(coverHref) in 1..ZipEntryReader.MAX_COVER_BYTES.toLong()) {
                        zipReader.openStream(coverHref)?.use { input ->
                            hasCover = CoverGenerator.saveCover(input, coverFile)
                        }
                    }
                }
            }
        }

        val title = info.title?.takeIf { it.isNotBlank() } ?: displayTitle
        val book = BookEntity(
            title = title,
            author = info.author,
            format = BookFormat.EPUB,
            filePath = target.absolutePath,
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
            totalChars = totalChars
        )
    }

    // ------------------------------------------------------------------ 工具

    private fun updateItem(id: String, transform: (ImportItem) -> ImportItem) {
        batches.update(id, transform)
    }

    private fun sourceUriOf(source: ImportSource): String = when (source) {
        is ImportSource.FromUri -> source.uri.toString()
        is ImportSource.FromFile -> source.file.absolutePath
    }

    private fun guessExtension(source: ImportSource): String {
        // 严格通过 4 字节魔数判断是否为 ZIP/EPUB（0x50, 0x4B, 0x03, 0x04，杜绝以 PK 开头的普通 TXT 误判）
        val isZipHeader = runCatching {
            openInput(source).use { input ->
                val head = ByteArray(4)
                val read = input.read(head)
                read == 4 &&
                    head[0] == 0x50.toByte() &&
                    head[1] == 0x4B.toByte() &&
                    head[2] == 0x03.toByte() &&
                    head[3] == 0x04.toByte()
            }
        }.getOrDefault(false)

        return detectExtension(source.displayName, isZipHeader)
    }

    private fun copyTo(source: ImportSource, target: File, onProgress: (Float) -> Unit): Long {
        target.parentFile?.mkdirs()
        val total = sizeOf(source)
        var copied = 0L
        openInput(source).use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    // 来源大小不可信（querySize 可能返回 0）时也要兜住：超限立即中止并删掉半截文件
                    if (copied > MAX_TXT_BYTES) {
                        runCatching { target.delete() }
                        throw ImportException("文件超过 ${MAX_TXT_BYTES / 1024 / 1024}MB 上限，已中止导入")
                    }
                    if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        if (copied == 0L) throw ImportException("无法读取文件内容")
        return copied
    }

    private fun openInput(source: ImportSource): InputStream = when (source) {
        is ImportSource.FromUri -> context.contentResolver.openInputStream(source.uri)
            ?: throw ImportException("无法打开所选文件")
        is ImportSource.FromFile -> source.file.inputStream()
    }

    private fun sizeOf(source: ImportSource): Long = when (source) {
        is ImportSource.FromFile -> source.file.length()
        is ImportSource.FromUri -> querySize(source.uri)
    }

    private fun querySize(uri: Uri): Long {
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length > 0) return afd.length
            }
        }
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) return cursor.getLong(index)
            }
        }
        return -1L
    }

    companion object {
        const val EPUB_EXT = "epub"
        const val TXT_EXT = "txt"
        const val MAX_TXT_BYTES = 80L * 1024 * 1024

        internal fun detectExtension(name: String, isZip: Boolean): String {
            if (isZip) return EPUB_EXT
            val lower = name.lowercase()
            if (lower.endsWith(".$EPUB_EXT")) return EPUB_EXT
            if (lower.endsWith(".$TXT_EXT")) return TXT_EXT
            return TXT_EXT
        }
    }
}
