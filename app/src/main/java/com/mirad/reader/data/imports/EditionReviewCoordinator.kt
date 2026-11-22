package com.mirad.reader.data.imports

import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.io.BookContentAccess
import com.mirad.reader.data.repo.BookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
/**
 * 同书新版的核对与替换：同批次多版本去重、后台书源下载的无损自动续章、待确认项的预览／确认／放弃，
 * 以及阅读器占用内容锁时等待释放后再替换。
 *
 * 由 [ImportPipeline] 持有；所有入口都在管线的同一把 [mutex] 下执行，与拆分前在管线内部时的并发约束一致。
 */
internal class EditionReviewCoordinator(
    private val scope: CoroutineScope,
    private val mutex: Mutex,
    private val paths: AppPaths,
    private val books: BookRepository,
    private val contentAccess: BookContentAccess,
    private val editions: BookEditionUpdater?,
    private val staging: LocalImportStaging,
    private val downloadReceipts: DownloadImportReceipts?,
    private val updateItem: (String, (ImportItem) -> ImportItem) -> Unit,
    private val insertPrepared: suspend (PreparedBook) -> Long,
    private val cleanStaging: suspend (String) -> Unit,
    private val skipDuplicate: (String, EditionCandidate) -> Unit
) {
    private val pending = mutableMapOf<String, PendingEdition>()

    fun isPending(itemId: String): Boolean = itemId in pending

    /** 导入失败时撤下待确认项并停止等待；暂存文件由调用方随后清理。 */
    fun cancel(itemId: String) {
        pending.remove(itemId)?.waiter?.cancel()
    }

    /** 用户确认导入结果后，丢弃仍未处理的待确认项及其暂存文件。 */
    suspend fun discard(itemId: String) {
        pending.remove(itemId)?.let {
            it.waiter?.cancel()
            cleanStaging(it.token)
        }
    }

    /** 新书匹配到候选旧书：登记待确认项；能自动判定时直接尝试替换，否则展示核对。 */
    suspend fun startReview(
        itemId: String,
        token: String,
        displayTitle: String,
        prepared: PreparedBook,
        candidates: List<EditionCandidate>,
        automatic: EditionCandidate?,
        batchItemIds: Set<String>,
        batchInserted: MutableMap<Long, String>
    ) {
        val entry = PendingEdition(token, displayTitle, prepared, candidates, batchItemIds)
        pending[itemId] = entry
        if (automatic == null) presentReview(itemId, entry)
        else attemptUpdate(itemId, entry, automatic, agreed = null, batchInserted = batchInserted)
    }

    private class PendingEdition(
        val token: String,
        val displayTitle: String,
        val prepared: PreparedBook,
        var candidates: List<EditionCandidate>,
        val batchItemIds: Set<String> = emptySet(),
        var preview: ImportUpdatePreview? = null,
        var waiter: Job? = null
    )

    suspend fun dedupIntraBatchPending(
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

    suspend fun tryBackgroundAutoUpdate(
        token: String,
        prepared: PreparedBook,
        candidate: EditionCandidate,
        downloadTaskId: String
    ): BookEntity? {
        val updater = editions ?: return null
        val lock = books.contentLock(candidate.book.id)
        if (!lock.tryLock()) return null
        try {
            prepared.sourceVersion.verify(contentAccess, prepared.book.filePath)
            val update = updater.inspect(candidate, prepared.book, prepared.chapters, prepared.sourceVersion)
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
                    sourceVersion = prepared.sourceVersion,
                    sourceStamp = prepared.sourceStamp,
                    downloadTaskId = downloadTaskId,
                    downloadReceipts = downloadReceipts,
                    stagedCoverFile = paths.coverFile(token)
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IncomingSourceChanged) {
            throw e
        } catch (_: Exception) {
            return null
        } finally {
            lock.unlock()
        }
    }

    fun previewUpdate(itemId: String, bookId: Long) {
        scope.launch {
            mutex.withLock {
                val entry = pending[itemId] ?: return@withLock
                if (entry.waiter != null) return@withLock
                val candidate = entry.candidates.firstOrNull { it.book.id == bookId } ?: return@withLock
                try {
                    showReview(itemId, entry, inspect(entry, candidate).preview)
                } catch (e: IncomingSourceChanged) {
                    finishPending(itemId, entry, ImportStage.FAILED, message = e.message)
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
                    sourceVersion = entry.prepared.sourceVersion,
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

    private suspend fun inspect(entry: PendingEdition, candidate: EditionCandidate): BookEditionUpdater.Update {
        entry.prepared.sourceVersion.verify(contentAccess, entry.prepared.book.filePath)
        return checkNotNull(editions).inspect(candidate, entry.prepared.book, entry.prepared.chapters, entry.prepared.sourceVersion)
    }

    private suspend fun refreshReview(itemId: String, entry: PendingEdition) {
        val match = try {
            EditionMatcher(contentAccess, books::getChapters, books.sourceVersionStore)
                .match(entry.prepared.book, entry.displayTitle, entry.prepared.epub, books.getAllBooks(), entry.prepared.sourceVersion)
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
            } catch (e: IncomingSourceChanged) {
                finishPending(itemId, entry, ImportStage.FAILED, message = e.message)
                return
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

    private suspend fun finishPending(
        itemId: String, entry: PendingEdition, stage: ImportStage, id: Long? = null, message: String? = null
    ) {
        pending.remove(itemId)
        updateItem(itemId) { it.copy(stage = stage, progress = 1f, bookId = id, review = null, message = message) }
        cleanStaging(entry.token)
    }
}

internal fun hiddenSuffix(book: BookEntity): String =
    if (book.isHidden) "（位于隐藏书籍）" else ""

internal fun formatUpdateMessage(oldBook: BookEntity, updatedBook: BookEntity): String {
    val chapterChange = if (oldBook.totalChapters != updatedBook.totalChapters) {
        "（${oldBook.totalChapters} 章 → ${updatedBook.totalChapters} 章）"
    } else ""
    return "已更新《${updatedBook.title}》$chapterChange${hiddenSuffix(updatedBook)}"
}
