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
import com.mirad.reader.core.text.StreamingTxtProcessor
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.io.BookContentAccess
import com.mirad.reader.data.repo.BookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
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
 * 导入管线：
 * - 具备持久读取权限的手机本地文件（SAF）：零拷贝记录原文件 URI，直接流式/随机解析章节；
 * - 临时分享流、网址下载与书源下载：仅保留单份原始文件，TXT 不再生成第二份 `content.utf8.txt`。
 */
class ImportPipeline internal constructor(
    private val context: Context,
    private val paths: AppPaths,
    private val books: BookRepository,
    private val scope: CoroutineScope,
    private val appDatabase: com.mirad.reader.data.db.AppDatabase? = null,
    private val contentAccess: BookContentAccess = BookContentAccess(context),
    bookmarkDao: com.mirad.reader.data.db.dao.BookmarkDao? = appDatabase?.bookmarkDao(),
    highlightDao: com.mirad.reader.data.db.dao.HighlightDao? = appDatabase?.highlightDao(),
    preferenceDao: com.mirad.reader.data.db.dao.PreferenceDao? = appDatabase?.preferenceDao()
) : BookImporter {

    constructor(
        context: Context,
        paths: AppPaths,
        books: BookRepository,
        scope: CoroutineScope,
        appDatabase: com.mirad.reader.data.db.AppDatabase? = null,
        contentAccess: BookContentAccess = BookContentAccess(context)
    ) : this(
        context = context,
        paths = paths,
        books = books,
        scope = scope,
        appDatabase = appDatabase,
        contentAccess = contentAccess,
        bookmarkDao = appDatabase?.bookmarkDao(),
        highlightDao = appDatabase?.highlightDao(),
        preferenceDao = appDatabase?.preferenceDao()
    )

    private val batches = ImportBatchQueue()
    val state: StateFlow<ImportState> = batches.state
    val lastSummary: StateFlow<ImportSummary?> = batches.summary
    private val mutex = Mutex()
    private val downloadReceipts = preferenceDao?.let {
        DownloadImportReceipts(it) { bookId -> books.getBook(bookId) != null }
    }
    private val editions = if (bookmarkDao != null && highlightDao != null) {
        BookEditionUpdater(books, bookmarkDao, highlightDao, appDatabase, contentAccess)
    } else null
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
        scope.launch {
            runBatch(
                batch = batch,
                sources = sources,
                items = items,
                force = force,
                local = !force && editions != null,
                shouldPublishSummary = { true }
            )
        }
        return batch.id
    }

    override suspend fun importAndWait(sources: List<ImportSource>, force: Boolean): ImportSummary {
        if (sources.isEmpty()) return ImportSummary(0, 0, 0, emptyList())
        val items = sources.map { ImportItem(UUID.randomUUID().toString(), it.displayName) }
        val batch = batches.enqueue(items)
        val isBackgroundDownloadTask = sources.all { (it as? ImportSource.FromFile)?.downloadTaskId != null }
        return runBatch(
            batch = batch,
            sources = sources,
            items = items,
            force = force,
            local = !force && editions != null,
            shouldPublishSummary = { summary -> !isBackgroundDownloadTask && summary.updated > 0 }
        )
    }

    private suspend fun runBatch(
        batch: ImportBatchQueue.Batch,
        sources: List<ImportSource>,
        items: List<ImportItem>,
        force: Boolean,
        local: Boolean,
        shouldPublishSummary: (ImportSummary) -> Boolean
    ): ImportSummary {
        var summary: ImportSummary? = null
        BatchCompletionGuard(
            abort = { reason -> batches.abort(batch, reason) },
            complete = { summary = batches.complete(batch, shouldPublishSummary) }
        ).run {
            mutex.withLock {
                if (local) recoverLocalImports()
                val matcher = if (local) {
                    EditionMatcher(contentAccess, books::getChapters, books.sourceStampStore)
                } else null
                val batchInserted = mutableMapOf<Long, String>()
                for (index in sources.indices) {
                    importOne(
                        source = sources[index],
                        itemId = items[index].id,
                        batchItemIds = batch.itemIds,
                        batchInserted = batchInserted,
                        force = force,
                        matcher = matcher
                    )
                }
            }
        }
        return checkNotNull(summary)
    }

    // ------------------------------------------------------------------ 单个文件

    /** 采样一致后还需全文确认；读不出来就不判重，不能静默丢掉用户导入的书。 */
    private fun sameContent(incomingPath: String, existing: BookEntity): Boolean =
        FileFingerprint.sameContent(contentAccess, incomingPath, existing.filePath)

    private suspend fun importOne(
        source: ImportSource,
        itemId: String,
        batchItemIds: Set<String>,
        batchInserted: MutableMap<Long, String>,
        force: Boolean,
        matcher: EditionMatcher?
    ) {
        val token = UUID.randomUUID().toString()
        val local = matcher != null
        val downloadTaskId = (source as? ImportSource.FromFile)?.downloadTaskId
        var committedBookId: Long? = null
        try {
            downloadTaskId?.let { taskId ->
                downloadReceipts?.find(taskId)?.let { id ->
                    updateItem(itemId) {
                        it.copy(stage = ImportStage.SKIPPED, progress = 1f, bookId = id, message = "下载书籍已加入书架")
                    }
                    return
                }
            }
            val extension = withContext(Dispatchers.IO) { guessExtension(source) }
            val declaredSize = withContext(Dispatchers.IO) { runCatching { sizeOf(source) }.getOrDefault(-1L) }
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

            val directReference = source is ImportSource.FromUri &&
                downloadTaskId == null &&
                contentAccess.canReferenceUriDirectly(source.uriString)

            val bookFilePath: String
            val size: Long
            if (directReference) {
                updateItem(itemId) { it.copy(stage = ImportStage.PARSING, progress = 0f) }
                bookFilePath = source.uriString
                size = withContext(Dispatchers.IO) { verifyReadableSize(bookFilePath, declaredSize) }
            } else {
                updateItem(itemId) { it.copy(stage = ImportStage.COPYING, progress = 0f) }
                val target = paths.originalFile(token, extension)
                size = withContext(Dispatchers.IO) {
                    copyTo(source, target) { progress -> updateItem(itemId) { it.copy(progress = progress) } }
                }
                bookFilePath = target.absolutePath
                updateItem(itemId) { it.copy(stage = ImportStage.PARSING, progress = 0f) }
            }

            val displayTitle = source.displayName.substringBeforeLast('.').trim().ifBlank { source.displayName }
            val format = if (extension == EPUB_EXT) BookFormat.EPUB else BookFormat.TXT
            val shelf = if (local) books.getAllBooks().filter { it.format == format } else emptyList()
            if (!force) {
                // 文件夹同步快速通道：同一零拷贝 URI 若快照大小、修改时间与采样指纹均未变，直接跳过全书解码/解压
                if (local) {
                    val sameSourceBook = shelf.firstOrNull {
                        it.filePath == bookFilePath && it.contentPath.isNullOrBlank() && it.fileSize == size
                    }
                    if (sameSourceBook != null) {
                        val savedStamp = books.sourceStampStore.get(sameSourceBook.id)
                        if (savedStamp != null && savedStamp.size == size) {
                            val currentStamp = withContext(Dispatchers.IO) {
                                BookSourceStampStore.capture(contentAccess, bookFilePath, size)
                            }
                            if (currentStamp != null && BookSourceStampStore.matches(currentStamp, savedStamp)) {
                                skipExistingBook(itemId, sameSourceBook, "书架中已有同一本书")
                                return
                            }
                        }
                    }
                }

                val duplicate = (if (local) shelf.filter { it.fileSize == size && it.filePath != bookFilePath }
                    else books.findDuplicateCandidates(displayTitle, size))
                    .firstOrNull { sameContent(bookFilePath, it) }
                if (duplicate != null) {
                    if (!local) paths.deleteManagedBookStorage(bookFilePath, null, null)
                    downloadTaskId?.let { taskId ->
                        withContext(NonCancellable + Dispatchers.IO) { downloadReceipts?.record(taskId, duplicate.id) }
                    }
                    skipExistingBook(itemId, duplicate, "书架中已有同一本书")
                    return
                }
            }

            val prepared = withContext(Dispatchers.IO) {
                if (extension == EPUB_EXT) prepareEpub(source, token, bookFilePath, displayTitle, size, local)
                else prepareTxt(source, token, bookFilePath, displayTitle, size, local)
            }

            if (matcher != null) {
                val match = matcher.match(prepared.book, displayTitle, prepared.epub, shelf)
                if (match.duplicate != null) {
                    downloadTaskId?.let { taskId ->
                        withContext(NonCancellable + Dispatchers.IO) {
                            downloadReceipts?.record(taskId, match.duplicate.book.id)
                        }
                    }
                    skipDuplicate(itemId, match.duplicate)
                    return
                }
                if (match.candidates.isNotEmpty()) {
                    // 后台书源离线下载任务要求同步落定 bookId：仅在满足无损自动续章且锁空闲时原地更新，否则安全另存新书
                    if (downloadTaskId != null) {
                        val autoCandidate = match.automatic
                            ?: match.candidates.singleOrNull()?.takeIf {
                                !it.authorConflict &&
                                    !prepared.book.sourceUri.isNullOrBlank() &&
                                    prepared.book.sourceUri == it.book.sourceUri
                            }
                        if (autoCandidate != null) {
                            val updatedId = tryBackgroundAutoUpdate(
                                token = token,
                                prepared = prepared,
                                candidate = autoCandidate,
                                downloadTaskId = downloadTaskId
                            )
                            if (updatedId != null) {
                                committedBookId = updatedId.id
                                updateItem(itemId) {
                                    it.copy(
                                        stage = ImportStage.UPDATED,
                                        progress = 1f,
                                        bookId = updatedId.id,
                                        message = formatUpdateMessage(autoCandidate.book, updatedId)
                                    )
                                }
                                return
                            }
                        }
                    } else {
                        // 同批次多版本去重：若同一批次已有待确认项指向相同候选旧书，只保留较新的那一版
                        if (dedupIntraBatchPending(itemId, batchItemIds, prepared, match.candidates, matcher)) {
                            return
                        }
                        val entry = PendingEdition(token, displayTitle, prepared, match.candidates, batchItemIds)
                        pending[itemId] = entry
                        val automatic = match.automatic
                        if (automatic == null) presentReview(itemId, entry)
                        else attemptUpdate(itemId, entry, automatic, agreed = null, batchInserted = batchInserted)
                        return
                    }
                }
            }

            currentCoroutineContext().ensureActive()
            withContext(NonCancellable + Dispatchers.IO) {
                val bookId = insertPrepared(prepared, downloadTaskId)
                committedBookId = bookId
                batchInserted[bookId] = itemId
                updateItem(itemId) { it.copy(stage = ImportStage.DONE, progress = 1f, bookId = bookId) }
            }
        } catch (t: Throwable) {
            if (committedBookId != null) {
                if (t is CancellationException) throw t
                return
            }
            if (!local) withContext(NonCancellable + Dispatchers.IO) {
                paths.deleteBookFiles(token, paths.coverFile(token).absolutePath)
            }
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

    private suspend fun dedupIntraBatchPending(
        currentItemId: String,
        batchItemIds: Set<String>,
        incoming: PreparedBook,
        candidates: List<EditionCandidate>,
        matcher: EditionMatcher
    ): Boolean {
        val candidateIds = candidates.map { it.book.id }
        val sameTargetEntries = pending.entries
            .filter { (id, entry) ->
                id in batchItemIds && entry.candidates.map { it.book.id } == candidateIds
            }
            .map { it.key to it.value }
        for ((prevItemId, prevEntry) in sameTargetEntries) {
            val rel = matcher.compareIncoming(
                first = prevEntry.prepared.book,
                firstChapters = prevEntry.prepared.chapters,
                firstEpub = prevEntry.prepared.epub,
                second = incoming.book,
                secondEpub = incoming.epub
            )
            when (rel) {
                EditionRelation.SAME, EditionRelation.OLDER -> {
                    updateItem(currentItemId) {
                        it.copy(
                            stage = ImportStage.SKIPPED,
                            progress = 1f,
                            message = "同批次已包含相同或较新版本《${prevEntry.displayTitle}》"
                        )
                    }
                    return true
                }
                EditionRelation.EXTENDS -> {
                    prevEntry.waiter?.cancel()
                    prevEntry.waiter = null
                    finishPending(
                        prevItemId,
                        prevEntry,
                        ImportStage.SKIPPED,
                        message = "已被同批次较新版本《${incoming.book.title}》替代"
                    )
                }
                EditionRelation.UNKNOWN -> Unit
            }
        }
        return false
    }

    private suspend fun tryBackgroundAutoUpdate(
        token: String,
        prepared: PreparedBook,
        candidate: EditionCandidate,
        downloadTaskId: String
    ): BookEntity? {
        val updater = editions ?: return null
        val lock = books.contentLock(candidate.book.id)
        if (!lock.tryLock()) return null
        try {
            val update = updater.inspect(candidate, prepared.book, prepared.chapters)
            val preview = update.preview
            val needsConfirmation = preview.resume == ResumeImpact.UNAVAILABLE ||
                preview.resume == ResumeImpact.CHAPTER_START ||
                preview.lostLocations > 0
            if (needsConfirmation) return null
            currentCoroutineContext().ensureActive()
            return withContext(NonCancellable + Dispatchers.IO) {
                staging.beforeReplacement(token, update.old.book)
                updater.publish(
                    update = update,
                    incoming = prepared.book,
                    chapters = prepared.chapters,
                    sourceStamp = prepared.sourceStamp,
                    downloadTaskId = downloadTaskId,
                    downloadReceipts = downloadReceipts,
                    stagedCoverFile = paths.coverFile(token)
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        } finally {
            lock.unlock()
        }
    }

    private suspend fun insertPrepared(prepared: PreparedBook, downloadTaskId: String? = null): Long {
        val block: suspend () -> Long = {
            val id = books.insertBook(prepared.book)
            books.saveChapters(id, prepared.chapters.map { it.copy(bookId = id) })
            books.updateIndexInfo(id, prepared.chapters.size, prepared.totalChars, prepared.ruleId, prepared.regex, prepared.encoding)
            books.sourceStampStore.save(id, prepared.sourceStamp)
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
        val epub: List<EpubChapterSignature>? = null,
        val sourceStamp: BookSourceStampStore.SourceStamp? = null
    )

    private class PendingEdition(
        val token: String,
        val displayTitle: String,
        val prepared: PreparedBook,
        var candidates: List<EditionCandidate>,
        val batchItemIds: Set<String> = emptySet(),
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
                attemptUpdate(itemId, entry, candidate, agreed = preview, batchInserted = null)
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
        itemId: String,
        entry: PendingEdition,
        candidate: EditionCandidate,
        agreed: ImportUpdatePreview?,
        batchInserted: MutableMap<Long, String>?
    ) {
        val lock = books.contentLock(candidate.book.id)
        if (lock.tryLock()) {
            try {
                applyOrReview(itemId, entry, candidate, agreed, batchInserted)
            } finally {
                lock.unlock()
            }
            return
        }
        updateItem(itemId) { it.copy(stage = ImportStage.WAITING_READER, progress = 1f, review = null, message = null) }
        entry.waiter = scope.launch {
            while (isActive) {
                lock.withLock { }
                val done = mutex.withLock {
                    if (pending[itemId] !== entry) return@withLock true
                    if (!lock.tryLock()) return@withLock false
                    try {
                        entry.waiter = null
                        applyOrReview(itemId, entry, candidate, agreed, batchInserted = null)
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
        itemId: String,
        entry: PendingEdition,
        candidate: EditionCandidate,
        agreed: ImportUpdatePreview?,
        batchInserted: MutableMap<Long, String>?
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
                val savedBook = checkNotNull(editions).publish(
                    update = update,
                    incoming = entry.prepared.book,
                    chapters = entry.prepared.chapters,
                    sourceStamp = entry.prepared.sourceStamp,
                    stagedCoverFile = paths.coverFile(entry.token)
                )
                val supersededInSameBatch = batchInserted?.remove(savedBook.id)
                if (supersededInSameBatch != null) {
                    updateItem(supersededInSameBatch) {
                        it.copy(
                            stage = ImportStage.SKIPPED,
                            bookId = savedBook.id,
                            message = "已被同批次较新版本《${savedBook.title}》替代"
                        )
                    }
                    batchInserted[savedBook.id] = itemId
                    finishPending(itemId, entry, ImportStage.DONE, savedBook.id, message = null)
                } else {
                    finishPending(
                        itemId = itemId,
                        entry = entry,
                        stage = ImportStage.UPDATED,
                        id = savedBook.id,
                        message = formatUpdateMessage(update.old.book, savedBook)
                    )
                }
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
            EditionMatcher(contentAccess, books::getChapters, books.sourceStampStore)
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
            presentReview(itemId, entry, message = "旧书或阅读记录已变化，请重新核对")
        }
    }

    /** 唯一候选旧书进入待确认时自动预计算续读影响，免去用户多点一次「核对」。 */
    private suspend fun presentReview(
        itemId: String,
        entry: PendingEdition,
        preview: ImportUpdatePreview? = null,
        message: String? = null
    ) {
        val effectivePreview = preview ?: if (entry.candidates.size == 1) {
            try {
                inspect(entry, entry.candidates.single()).preview
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else null
        showReview(itemId, entry, effectivePreview, message)
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

    private fun hiddenSuffix(book: BookEntity): String =
        if (book.isHidden) "（位于隐藏书籍）" else ""

    private fun formatUpdateMessage(oldBook: BookEntity, updatedBook: BookEntity): String {
        val chapterChange = if (oldBook.totalChapters != updatedBook.totalChapters) {
            "（${oldBook.totalChapters} 章 → ${updatedBook.totalChapters} 章）"
        } else ""
        return "已更新《${updatedBook.title}》$chapterChange${hiddenSuffix(updatedBook)}"
    }

    private fun skipExistingBook(itemId: String, book: BookEntity, prefix: String) {
        updateItem(itemId) {
            it.copy(
                stage = ImportStage.SKIPPED,
                bookId = book.id,
                review = null,
                message = "$prefix《${book.title}》${hiddenSuffix(book)}",
                progress = 1f
            )
        }
    }

    private fun skipDuplicate(itemId: String, candidate: EditionCandidate) {
        val prefix = if (candidate.relation == EditionRelation.OLDER) "书架已保存较新的版本" else "书架中已有同一本书"
        skipExistingBook(itemId, candidate.book, prefix)
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
        bookFilePath: String,
        displayTitle: String,
        size: Long,
        local: Boolean
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
            sourceStamp = stamp
        )
    }

    private fun prepareEpub(
        source: ImportSource,
        token: String,
        bookFilePath: String,
        displayTitle: String,
        size: Long,
        local: Boolean
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
            epub = signatures,
            sourceStamp = stamp
        )
    }

    // ------------------------------------------------------------------ 工具

    private fun updateItem(id: String, transform: (ImportItem) -> ImportItem) {
        batches.update(id, transform)
    }

    private fun sourceUriOf(source: ImportSource): String = when (source) {
        is ImportSource.FromUri -> source.uriString
        is ImportSource.FromFile -> source.originUrl ?: source.file.absolutePath
    }

    private fun guessExtension(source: ImportSource): String {
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

    private fun verifyReadableSize(path: String, declaredSize: Long): Long {
        val known = if (declaredSize > 0L) declaredSize else contentAccess.size(path)
        if (known > MAX_TXT_BYTES) {
            throw ImportException("文件超过 ${MAX_TXT_BYTES / 1024 / 1024}MB 上限，已中止导入")
        }
        if (known > 0L) {
            if (!contentAccess.isReadable(path)) throw ImportException("无法读取所选文件")
            return known
        }
        var counted = 0L
        contentAccess.openStream(path).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                counted += n
                if (counted > MAX_TXT_BYTES) {
                    throw ImportException("文件超过 ${MAX_TXT_BYTES / 1024 / 1024}MB 上限，已中止导入")
                }
            }
        }
        if (counted == 0L) throw ImportException("文件内容为空")
        return counted
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
        is ImportSource.FromUri -> {
            val uriStr = source.uriString
            if (contentAccess.isReadable(uriStr)) contentAccess.openStream(uriStr)
            else source.uri?.let { context.contentResolver.openInputStream(it) } ?: throw ImportException("无法打开所选文件")
        }
        is ImportSource.FromFile -> source.file.inputStream()
    }

    private fun sizeOf(source: ImportSource): Long = when (source) {
        is ImportSource.FromFile -> source.file.length()
        is ImportSource.FromUri -> {
            val viaAccess = contentAccess.size(source.uriString)
            if (viaAccess >= 0L) viaAccess else source.uri?.let { querySize(it) } ?: -1L
        }
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
