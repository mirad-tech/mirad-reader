package com.mirad.reader.data.imports

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.io.BookContentAccess
import com.mirad.reader.data.repo.BookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

class ImportException(message: String) : Exception(message)

interface BookImporter {
    suspend fun importAndWait(sources: List<ImportSource>, force: Boolean = false): ImportSummary
}

/**
 * 导入管线：
 * - 具备持久读取权限的手机本地文件（SAF）：零拷贝记录原文件 URI，直接流式/随机解析章节；
 * - 临时分享流、网址下载与书源下载：仅保留单份原始文件，TXT 不再生成第二份 `content.utf8.txt`。
 *
 * 格式解析在 [BookPreparer]，来源读取在 [ImportSourceIo]，同书新版的核对与替换在 [EditionReviewCoordinator]。
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
    private val io = ImportSourceIo(context, contentAccess)
    private val preparer = BookPreparer(paths, contentAccess)
    private val editionReview = EditionReviewCoordinator(
        scope = scope,
        mutex = mutex,
        paths = paths,
        books = books,
        contentAccess = contentAccess,
        editions = editions,
        staging = staging,
        downloadReceipts = downloadReceipts,
        updateItem = this::updateItem,
        insertPrepared = { prepared -> insertPrepared(prepared) },
        cleanStaging = { token -> cleanStaging(token) },
        skipDuplicate = this::skipDuplicate
    )
    private var recovered = false

    init {
        if (editions != null) scope.launch { mutex.withLock { recoverLocalImports() } }
    }

    fun clearSummary(batchId: String? = lastSummary.value?.batchId) {
        scope.launch {
            mutex.withLock {
                batches.acknowledge(batchId).forEach { id -> editionReview.discard(id) }
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
                    EditionMatcher(contentAccess, books::getChapters, books.sourceVersionStore)
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
            val extension = withContext(Dispatchers.IO) { io.guessExtension(source) }
            val declaredSize = withContext(Dispatchers.IO) { runCatching { io.sizeOf(source) }.getOrDefault(-1L) }
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
                size = withContext(Dispatchers.IO) { io.verifyReadableSize(bookFilePath, declaredSize) }
            } else {
                updateItem(itemId) { it.copy(stage = ImportStage.COPYING, progress = 0f) }
                val target = paths.originalFile(token, extension)
                size = withContext(Dispatchers.IO) {
                    io.copyTo(source, target) { progress -> updateItem(itemId) { it.copy(progress = progress) } }
                }
                bookFilePath = target.absolutePath
                updateItem(itemId) { it.copy(stage = ImportStage.PARSING, progress = 0f) }
            }

            val displayTitle = source.displayName.substringBeforeLast('.').trim().ifBlank { source.displayName }
            val format = if (extension == EPUB_EXT) BookFormat.EPUB else BookFormat.TXT
            val shelf = if (local) books.getAllBooks().filter { it.format == format } else emptyList()
            val sourceVersion = withContext(Dispatchers.IO) { SourceVersion.capture(contentAccess, bookFilePath) }
            if (!force) {
                // 文件夹同步：完整源版本一致时跳过解码/解压，采样和修改时间不能证明正文未变。
                if (local) {
                    val sameSourceBook = shelf.firstOrNull {
                        it.filePath == bookFilePath && it.contentPath.isNullOrBlank() && it.fileSize == size
                    }
                    if (sameSourceBook != null) {
                        if (books.sourceVersionStore.get(sameSourceBook.id) == sourceVersion) {
                            skipExistingBook(itemId, sameSourceBook, "书架中已有同一本书")
                            return
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
                val result = preparer.prepare(extension, source, token, bookFilePath, displayTitle, size, local, sourceVersion)
                sourceVersion.verify(contentAccess, bookFilePath)
                result
            }

            if (matcher != null) {
                val match = matcher.match(prepared.book, displayTitle, prepared.epub, shelf, prepared.sourceVersion)
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
                            val updatedId = editionReview.tryBackgroundAutoUpdate(
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
                        if (editionReview.dedupIntraBatchPending(itemId, batchItemIds, prepared, match.candidates, matcher)) {
                            return
                        }
                        editionReview.startReview(itemId, token, displayTitle, prepared, match.candidates, match.automatic, batchItemIds, batchInserted)
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
            editionReview.cancel(itemId)
            if (t is CancellationException) throw t
            updateItem(itemId) {
                it.copy(
                    stage = ImportStage.FAILED,
                    progress = 1f,
                    message = t.message ?: t.javaClass.simpleName
                )
            }
        } finally {
            if (local && !editionReview.isPending(itemId)) cleanStaging(token)
        }
    }

    private suspend fun insertPrepared(prepared: PreparedBook, downloadTaskId: String? = null): Long {
        prepared.sourceVersion.verify(contentAccess, prepared.book.filePath)
        val block: suspend () -> Long = {
            val id = books.insertBook(prepared.book)
            books.customMetaStore.initialize(id)
            books.saveChapters(id, prepared.chapters.map { it.copy(bookId = id) })
            books.updateIndexInfo(id, prepared.chapters.size, prepared.totalChars, prepared.ruleId, prepared.regex, prepared.encoding)
            books.sourceStampStore.save(id, prepared.sourceStamp)
            books.sourceVersionStore.save(id, prepared.sourceVersion)
            downloadTaskId?.let { downloadReceipts?.record(it, id) }
            id
        }
        return appDatabase?.withTransaction { block() } ?: block()
    }

    /** 待确认的同书新版：核对续读位置、确认替换或放弃，具体流程见 [EditionReviewCoordinator]。 */
    fun previewUpdate(itemId: String, bookId: Long) {
        editionReview.previewUpdate(itemId, bookId)
    }

    fun confirmUpdate(itemId: String, preview: ImportUpdatePreview) {
        editionReview.confirmUpdate(itemId, preview)
    }

    fun resolveWithoutUpdate(itemId: String, keepNew: Boolean) {
        editionReview.resolveWithoutUpdate(itemId, keepNew)
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

    // ------------------------------------------------------------------ 工具

    private fun updateItem(id: String, transform: (ImportItem) -> ImportItem) {
        batches.update(id, transform)
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
