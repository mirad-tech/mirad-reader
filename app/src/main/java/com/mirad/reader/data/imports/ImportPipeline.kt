package com.mirad.reader.data.imports

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.room.withTransaction
import com.mirad.reader.core.epub.EpubParser
import com.mirad.reader.core.epub.ZipEntryReader
import com.mirad.reader.core.html.Html
import com.mirad.reader.core.model.TocJson
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.repo.BookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

class ImportException(message: String) : Exception(message)

interface BookImporter {
    suspend fun importAndWait(sources: List<ImportSource>, force: Boolean = false): ImportSummary
}

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
) : BookImporter {

    private val batches = ImportBatchQueue()
    val state: StateFlow<ImportState> = batches.state
    val lastSummary: StateFlow<ImportSummary?> = batches.summary
    private val mutex = Mutex()
    private val downloadReceipts = appDatabase?.let {
        DownloadImportReceipts(it.preferenceDao()) { bookId -> books.getBook(bookId) != null }
    }
    private val editions = appDatabase?.let { BookEditionUpdater(it, books) }
    private val staging = LocalImportStaging(paths)
    private val pending = mutableMapOf<String, PendingEdition>()
    private var recovered = false

    init {
        if (editions != null) scope.launch { mutex.withLock { recoverLocalImports() } }
    }

    fun clearSummary(batchId: String? = lastSummary.value?.batchId) {
        scope.launch {
            mutex.withLock {
                batches.acknowledge(batchId).forEach { id ->
                    pending.remove(id)?.let {
                        it.waiter?.cancel()
                        cleanStaging(it.token)
                    }
                }
            }
        }
    }

    fun import(sources: List<ImportSource>, force: Boolean = false): String? {
        if (sources.isEmpty()) return null
        val items = sources.map { ImportItem(UUID.randomUUID().toString(), it.displayName) }
        val batch = batches.enqueue(items)
        scope.launch { runBatch(batch, sources, items, force, local = !force && editions != null) }
        return batch.id
    }

    override suspend fun importAndWait(sources: List<ImportSource>, force: Boolean): ImportSummary {
        if (sources.isEmpty()) return ImportSummary(0, 0, 0, emptyList())
        val items = sources.map { ImportItem(UUID.randomUUID().toString(), it.displayName) }
        val batch = batches.enqueue(items)
        return runBatch(batch, sources, items, force, local = false)
    }

    private suspend fun runBatch(
        batch: ImportBatchQueue.Batch, sources: List<ImportSource>, items: List<ImportItem>, force: Boolean, local: Boolean
    ): ImportSummary {
        var summary: ImportSummary? = null
        // 收敛逻辑见 BatchCompletionGuard：取消/异常也要把批次收成终态，否则 running 永远为 true。
        BatchCompletionGuard(
            abort = { reason -> batches.abort(batch, reason) },
            complete = { summary = batches.complete(batch) }
        ).run {
            mutex.withLock {
                if (local) recoverLocalImports()
                val matcher = if (local) EditionMatcher(books::getChapters) else null
                for (index in sources.indices) importOne(sources[index], items[index].id, force, matcher)
            }
        }
        return checkNotNull(summary)
    }

    // ------------------------------------------------------------------ 单个文件

    /** 采样一致后还需全文确认；读不出来就不判重，不能静默丢掉用户导入的书。 */
    private fun sameContent(incoming: File, existing: com.mirad.reader.data.db.entity.BookEntity): Boolean =
        FileFingerprint.sameContent(incoming, books.originalFileOf(existing))

    private suspend fun importOne(source: ImportSource, itemId: String, force: Boolean, matcher: EditionMatcher?) {
        val token = UUID.randomUUID().toString()
        val local = matcher != null
        val downloadTaskId = (source as? ImportSource.FromFile)?.downloadTaskId
        var committedBookId: Long? = null
        try {
            downloadTaskId?.let { taskId ->
                downloadReceipts?.find(taskId)?.let { id ->
                    updateItem(itemId) { it.copy(stage = ImportStage.SKIPPED, progress = 1f,
                        bookId = id, message = "下载书籍已加入书架") }
                    return
                }
            }
            updateItem(itemId) { it.copy(stage = ImportStage.COPYING, progress = 0f) }
            val extension = guessExtension(source)
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
            if (local) staging.mark(token)
            val target = paths.originalFile(token, extension)
            val size = withContext(Dispatchers.IO) {
                copyTo(source, target) { progress -> updateItem(itemId) { it.copy(progress = progress) } }
            }

            updateItem(itemId) { it.copy(stage = ImportStage.PARSING, progress = 0f) }
            val displayTitle = source.displayName.substringBeforeLast('.').trim().ifBlank { source.displayName }

            val format = if (extension == EPUB_EXT) BookFormat.EPUB else BookFormat.TXT
            val shelf = if (local) books.getAllBooks().filter { it.format == format } else emptyList()
            if (!force) {
                val duplicate = (if (local) shelf.filter { it.fileSize == size }
                    else books.findDuplicateCandidates(displayTitle, size))
                    .firstOrNull { sameContent(target, it) }
                if (duplicate != null) {
                    if (!local) paths.deleteBookFiles(token, null)
                    downloadTaskId?.let { taskId ->
                        withContext(NonCancellable + Dispatchers.IO) { downloadReceipts?.record(taskId, duplicate.id) }
                    }
                    updateItem(itemId) {
                        it.copy(
                            stage = ImportStage.SKIPPED,
                            bookId = duplicate.id,
                            message = "书架中已有同一本书《${duplicate.title}》",
                            progress = 1f
                        )
                    }
                    return
                }
            }

            val prepared = withContext(Dispatchers.IO) {
                if (extension == EPUB_EXT) prepareEpub(source, token, target, displayTitle, size, local)
                else prepareTxt(source, token, target, displayTitle, size)
            }

            if (matcher != null) {
                val match = matcher.match(prepared.book, displayTitle, prepared.epub, shelf)
                if (match.duplicate != null) {
                    skipDuplicate(itemId, match.duplicate)
                    return
                }
                if (match.candidates.isNotEmpty()) {
                    val entry = PendingEdition(token, displayTitle, prepared, match.candidates)
                    pending[itemId] = entry
                    val automatic = match.automatic
                    if (automatic == null) showReview(itemId, entry)
                    else attemptUpdate(itemId, entry, automatic, agreed = null)
                    return
                }
            }

            currentCoroutineContext().ensureActive()
            withContext(NonCancellable + Dispatchers.IO) {
                val bookId = insertPrepared(prepared, downloadTaskId)
                committedBookId = bookId
                updateItem(itemId) { it.copy(stage = ImportStage.DONE, progress = 1f, bookId = bookId) }
            }
        } catch (t: Throwable) {
            // 事务成功后不能因恢复协程时的取消删除正文；恢复任务从同事务的凭据找回书籍。
            if (committedBookId != null) {
                updateItem(itemId) { it.copy(stage = ImportStage.DONE, progress = 1f, bookId = committedBookId) }
                if (t is kotlinx.coroutines.CancellationException) throw t
                return
            }
            // 清理必须包 NonCancellable：协程已取消时，裸的 withContext 会立刻抛
            // CancellationException，文件/DB 补偿一步都不会执行。
            if (!local) withContext(NonCancellable + Dispatchers.IO) {
                // 封面写在 coversDir（不在书籍目录下），补偿必须显式带上，否则孤儿 jpg 会累积。
                paths.deleteBookFiles(token, paths.coverFile(token).absolutePath)
            }
            // 取消不是"导入失败"：往上抛，由 runBatch 统一把批次收敛成"已取消"。
            pending.remove(itemId)?.waiter?.cancel()
            if (t is CancellationException) throw t
            updateItem(itemId) {
                it.copy(
                    stage = ImportStage.FAILED,
                    progress = 1f,
                    message = t.message ?: t.javaClass.simpleName
                )
            }
        } finally {
            if (local && itemId !in pending) cleanStaging(token)
        }
    }

    private suspend fun insertPrepared(prepared: PreparedBook, downloadTaskId: String? = null): Long {
        val block: suspend () -> Long = {
            val id = books.insertBook(prepared.book)
            books.saveChapters(id, prepared.chapters.map { it.copy(bookId = id) })
            books.updateIndexInfo(id, prepared.chapters.size, prepared.totalChars, prepared.ruleId, prepared.regex, prepared.encoding)
            downloadTaskId?.let { downloadReceipts?.record(it, id) }
            id
        }
        return appDatabase?.withTransaction { block() } ?: block()
    }

    private data class PreparedBook(
        val book: BookEntity,
        val chapters: List<ChapterEntity>,
        val encoding: String?,
        val ruleId: String?,
        val regex: String?,
        val totalChars: Long,
        val epub: List<EpubChapterSignature>? = null
    )

    private class PendingEdition(
        val token: String,
        val displayTitle: String,
        val prepared: PreparedBook,
        var candidates: List<EditionCandidate>,
        var preview: ImportUpdatePreview? = null,
        var waiter: Job? = null
    )

    fun previewUpdate(itemId: String, bookId: Long) {
        scope.launch {
            mutex.withLock {
                val entry = pending[itemId] ?: return@withLock
                if (entry.waiter != null) return@withLock
                val candidate = entry.candidates.firstOrNull { it.book.id == bookId } ?: return@withLock
                try {
                    showReview(itemId, entry, inspect(entry, candidate).preview)
                } catch (_: EditionChanged) {
                    refreshReview(itemId, entry)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showReview(itemId, entry, message = e.message ?: "无法核对续读位置")
                }
            }
        }
    }

    fun confirmUpdate(itemId: String, preview: ImportUpdatePreview) {
        scope.launch {
            mutex.withLock {
                val entry = pending[itemId] ?: return@withLock
                if (entry.waiter != null || entry.preview != preview || preview.resume == ResumeImpact.UNAVAILABLE) return@withLock
                val candidate = entry.candidates.firstOrNull { it.book.id == preview.bookId } ?: return@withLock
                attemptUpdate(itemId, entry, candidate, agreed = preview)
            }
        }
    }

    fun resolveWithoutUpdate(itemId: String, keepNew: Boolean) {
        scope.launch {
            mutex.withLock {
                val entry = pending[itemId] ?: return@withLock
                if (entry.waiter != null && keepNew) return@withLock
                entry.waiter?.cancel()
                try {
                    withContext(NonCancellable + Dispatchers.IO) {
                        val id = if (keepNew) insertPrepared(entry.prepared) else null
                        finishPending(itemId, entry, if (keepNew) ImportStage.DONE else ImportStage.SKIPPED, id,
                            if (keepNew) null else "已跳过")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    finishPending(itemId, entry, ImportStage.FAILED, message = e.message ?: "无法保存新书")
                }
            }
        }
    }

    private suspend fun attemptUpdate(
        itemId: String, entry: PendingEdition, candidate: EditionCandidate, agreed: ImportUpdatePreview?
    ) {
        val lock = books.contentLock(candidate.book.id)
        if (lock.tryLock()) {
            try {
                applyOrReview(itemId, entry, candidate, agreed)
            } finally {
                lock.unlock()
            }
            return
        }
        updateItem(itemId) { it.copy(stage = ImportStage.WAITING_READER, progress = 1f, review = null, message = null) }
        entry.waiter = scope.launch {
            // 只等待同书的会话，释放整个导入队列给批次中的其它书。
            while (isActive) {
                lock.withLock { }
                val done = mutex.withLock {
                    if (pending[itemId] !== entry) return@withLock true
                    if (!lock.tryLock()) return@withLock false
                    try {
                        entry.waiter = null
                        applyOrReview(itemId, entry, candidate, agreed)
                        true
                    } finally {
                        lock.unlock()
                    }
                }
                if (done) break
            }
        }
    }

    private suspend fun applyOrReview(
        itemId: String, entry: PendingEdition, candidate: EditionCandidate, agreed: ImportUpdatePreview?
    ) {
        try {
            val update = inspect(entry, candidate)
            val preview = update.preview
            val needsConfirmation = preview.resume == ResumeImpact.CHAPTER_START || preview.lostLocations > 0
            if (preview.resume == ResumeImpact.UNAVAILABLE ||
                (agreed == null && needsConfirmation) || (agreed != null && agreed != preview)) {
                showReview(itemId, entry, preview)
                return
            }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable + Dispatchers.IO) {
                staging.beforeReplacement(entry.token, update.old.book)
                checkNotNull(editions).publish(update, entry.prepared.book, entry.prepared.chapters)
                finishPending(itemId, entry, ImportStage.UPDATED, candidate.book.id)
            }
        } catch (_: EditionChanged) {
            refreshReview(itemId, entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finishPending(itemId, entry, ImportStage.FAILED, message = e.message ?: "更新失败")
        }
    }

    private suspend fun inspect(entry: PendingEdition, candidate: EditionCandidate) =
        checkNotNull(editions).inspect(candidate, entry.prepared.book, entry.prepared.chapters)

    private suspend fun refreshReview(itemId: String, entry: PendingEdition) {
        val match = try {
            EditionMatcher(books::getChapters)
                .match(entry.prepared.book, entry.displayTitle, entry.prepared.epub, books.getAllBooks())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finishPending(itemId, entry, ImportStage.FAILED, message = e.message ?: "无法重新核对旧书")
            return
        }
        if (match.duplicate != null) {
            pending.remove(itemId)
            skipDuplicate(itemId, match.duplicate)
            cleanStaging(entry.token)
        } else {
            entry.candidates = match.candidates
            showReview(itemId, entry, message = "旧书或阅读记录已变化，请重新核对")
        }
    }

    private fun showReview(
        itemId: String, entry: PendingEdition, preview: ImportUpdatePreview? = null, message: String? = null
    ) {
        entry.preview = preview
        val candidates = entry.candidates.map {
            val book = it.book
            ImportReviewCandidate(book.id, book.title, book.author, book.totalChapters, book.addedAt)
        }
        updateItem(itemId) {
            it.copy(stage = ImportStage.REVIEW, progress = 1f, message = message,
                review = ImportReview(entry.prepared.chapters.size, candidates, preview))
        }
    }

    private fun skipDuplicate(itemId: String, candidate: EditionCandidate) {
        val prefix = if (candidate.relation == EditionRelation.OLDER) "书架已保存较新的版本" else "书架中已有同一本书"
        updateItem(itemId) {
            it.copy(stage = ImportStage.SKIPPED, progress = 1f, bookId = candidate.book.id,
                review = null, message = prefix + "《" + candidate.book.title + "》")
        }
    }

    private suspend fun finishPending(
        itemId: String, entry: PendingEdition, stage: ImportStage, id: Long? = null, message: String? = null
    ) {
        pending.remove(itemId)
        updateItem(itemId) { it.copy(stage = stage, progress = 1f, bookId = id, review = null, message = message) }
        cleanStaging(entry.token)
    }

    private suspend fun recoverLocalImports() {
        if (recovered) return
        // 只在本进程创建暂存项之前恢复，不能把仍在等待确认的文件当作孤儿。
        recovered = true
        try {
            staging.recover(books.getAllBooks())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ImportPipeline", "无法读取本地导入清理记录", e)
        }
    }

    private suspend fun cleanStaging(token: String) = withContext(NonCancellable + Dispatchers.IO) {
        try {
            if (staging.isMarked(token)) staging.finish(token, books.getAllBooks())
        } catch (e: Exception) {
            Log.w("ImportPipeline", "无法核对导入文件引用，保留清理记录", e)
        }
    }

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
            totalChars = streamResult.totalChars
        )
    }

    private fun prepareEpub(
        source: ImportSource,
        token: String,
        target: File,
        displayTitle: String,
        size: Long,
        local: Boolean
    ): PreparedBook {
        val info = runCatching { EpubParser.parse(target) }
            .getOrElse { throw ImportException("EPUB 解析失败：${it.message ?: "文件可能已损坏"}") }

        val reader = ZipEntryReader.open(target)
        val chapters = ArrayList<ChapterEntity>(info.spine.size)
        val signatures = if (local) ArrayList<EpubChapterSignature>(info.spine.size) else null
        var totalChars = 0L
        try {
            for (item in info.spine) {
                // 与阅读/渲染路径同口径（ZipEntryReader.MAX_CHAPTER_BYTES）：口径不一致时
                // 会出现"目录与渲染正常、但该章搜索 0 命中 / TTS 为空"的章节。
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
            totalChars = totalChars,
            epub = signatures
        )
    }

    // ------------------------------------------------------------------ 工具

    private fun updateItem(id: String, transform: (ImportItem) -> ImportItem) {
        batches.update(id, transform)
    }

    private fun sourceUriOf(source: ImportSource): String = when (source) {
        is ImportSource.FromUri -> source.uri.toString()
        is ImportSource.FromFile -> source.originUrl ?: source.file.absolutePath
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

        /** 取消/异常中断时写入条目的原因，用户能在导入结果里看到。 */
        const val CANCELLED_MESSAGE = "导入已取消"
        const val INTERRUPTED_MESSAGE = "导入未完成"

        internal fun detectExtension(name: String, isZip: Boolean): String {
            if (isZip) return EPUB_EXT
            val lower = name.lowercase()
            if (lower.endsWith(".$EPUB_EXT")) return EPUB_EXT
            if (lower.endsWith(".$TXT_EXT")) return TXT_EXT
            return TXT_EXT
        }
    }
}
