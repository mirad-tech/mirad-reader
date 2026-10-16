package com.mirad.reader.data.imports

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val scope: CoroutineScope
) {

    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()

    private val mutex = Mutex()

    /** 最近一次导入结果汇总（UI 提示后可清除）。 */
    private val _lastSummary = MutableStateFlow<ImportSummary?>(null)
    val lastSummary: StateFlow<ImportSummary?> = _lastSummary.asStateFlow()

    fun clearSummary() {
        _lastSummary.value = null
    }

    fun import(sources: List<ImportSource>, force: Boolean = false) {
        if (sources.isEmpty()) return
        val items = sources.map {
            ImportItem(id = UUID.randomUUID().toString(), displayName = it.displayName)
        }
        _state.update { it.copy(items = it.items + items, running = true) }
        scope.launch {
            mutex.withLock {
                for (index in sources.indices) {
                    importOne(sources[index], items[index].id, force)
                }
            }
            finishBatch()
        }
    }

    /** 供测试或调试入口直接等待完成。 */
    suspend fun importAndWait(sources: List<ImportSource>, force: Boolean = false): ImportSummary {
        val items = sources.map { ImportItem(id = UUID.randomUUID().toString(), displayName = it.displayName) }
        _state.update { it.copy(items = it.items + items, running = true) }
        mutex.withLock {
            for (index in sources.indices) {
                importOne(sources[index], items[index].id, force)
            }
        }
        return finishBatch()
    }

    private fun finishBatch(): ImportSummary {
        _state.update { it.copy(running = false) }
        val snapshot = _state.value
        val messages = snapshot.items
            .filter { it.stage == ImportStage.FAILED || it.stage == ImportStage.SKIPPED }
            .map { "${it.displayName}：${it.message ?: ""}" }
        val summary = ImportSummary(
            success = snapshot.successCount,
            failed = snapshot.failedCount,
            skipped = snapshot.skippedCount,
            messages = messages
        )
        _lastSummary.value = summary
        return summary
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

            val bookId = books.insertBook(prepared.book)
            insertedBookId = bookId
            books.saveChapters(bookId, prepared.chapters.map { it.copy(bookId = bookId) })
            books.updateIndexInfo(
                bookId = bookId,
                chapters = prepared.chapters.size,
                chars = prepared.totalChars,
                ruleId = prepared.ruleId,
                regex = prepared.regex,
                encoding = prepared.encoding
            )

            updateItem(itemId) {
                it.copy(stage = ImportStage.DONE, progress = 1f, bookId = bookId)
            }
        } catch (t: Throwable) {
            withContext(Dispatchers.IO) {
                insertedBookId?.let { id -> books.getBook(id)?.let { books.deleteBook(it) } }
                if (insertedBookId == null) paths.deleteBookFiles(token, null)
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
        val bytes = target.readBytes()
        if (bytes.isEmpty()) throw ImportException("文件内容为空")

        val decoded = com.mirad.reader.core.text.TextCodec.decode(bytes)
        val normalized = TextNormalizer.normalize(decoded.text)
        if (normalized.isBlank()) throw ImportException("未解析出正文内容")

        val contentFile = paths.contentFile(token)
        contentFile.writeText(normalized, Charsets.UTF_8)

        val (rule, hits) = ChapterRules.autoDetect(normalized)
        val effectiveRule = if (hits > 0) rule else null
        val slices = ChapterSplitter.split(normalized, effectiveRule)
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
            encoding = decoded.charsetName,
            chapterRuleId = effectiveRule?.id,
            totalChapters = chapters.size,
            totalChars = normalized.length.toLong(),
            addedAt = System.currentTimeMillis()
        )
        return PreparedBook(
            book = book,
            chapters = chapters,
            encoding = decoded.charsetName,
            ruleId = effectiveRule?.id,
            regex = null,
            totalChars = normalized.length.toLong()
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
                // 与阅读路径同口径（8MB）：1MB 会把偏大的章节 XHTML 判成"读不到"，
                // 导致该章 charCount=0、目录里显示为空章。
                val html = reader.readText(item.href, maxBytes = 8 * 1024 * 1024)
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
        _state.update { state ->
            state.copy(items = state.items.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun sourceUriOf(source: ImportSource): String = when (source) {
        is ImportSource.FromUri -> source.uri.toString()
        is ImportSource.FromFile -> source.file.absolutePath
    }

    private fun guessExtension(source: ImportSource): String {
        val name = source.displayName.lowercase()
        if (name.endsWith(".$EPUB_EXT")) return EPUB_EXT
        if (name.endsWith(".$TXT_EXT")) return TXT_EXT
        // 通过文件头判断 zip（EPUB 本质是 zip）
        return runCatching {
            openInput(source).use { input ->
                val head = ByteArray(4)
                val read = input.read(head)
                if (read == 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) EPUB_EXT else TXT_EXT
            }
        }.getOrDefault(TXT_EXT)
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
    }
}
