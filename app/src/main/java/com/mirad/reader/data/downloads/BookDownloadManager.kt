package com.mirad.reader.data.downloads

import com.mirad.reader.core.sources.BookDownloadStatus
import com.mirad.reader.core.sources.BookDownloadStatus.*
import com.mirad.reader.core.sources.BookDownloadTask
import com.mirad.reader.core.sources.BookDownloads
import com.mirad.reader.core.sources.BookSource
import com.mirad.reader.core.sources.BookSourceEngine
import com.mirad.reader.core.sources.SourceCatalog
import com.mirad.reader.data.imports.BookImporter
import com.mirad.reader.data.imports.ImportSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Android 调度器与界面共用的持久化执行器；构造时不启动下载。 */
class BookDownloadManager(
    root: File,
    private val engine: BookSourceEngine,
    private val importer: BookImporter,
    scope: CoroutineScope,
    private val findImported: suspend (String) -> Long? = { null },
    private val scheduleRun: () -> Unit = {},
    private val cancelRun: () -> Unit = {},
    private val networkAvailable: () -> Boolean = { true }
) : BookDownloads {
    private val store = DownloadStore(root)
    private val mutex = Mutex()
    private val commands = Mutex()
    private val runnerMutex = Mutex()
    private val _tasks = MutableStateFlow<List<BookDownloadTask>>(emptyList())
    override val tasks = _tasks.asStateFlow()
    private val _loadError = MutableStateFlow<String?>(null)
    override val loadError = _loadError.asStateFlow()
    private var active: Pair<String, Deferred<Unit>>? = null
    private val ready = scope.async(Dispatchers.IO) {
        runCatching {
            val (saved, errors) = store.load()
            _tasks.value = saved.map { task ->
                if (task.status in setOf(DOWNLOADING, IMPORTING)) {
                    task.copy(status = QUEUED, message = "上次下载已中断，等待继续", completedChapters = store.completed(task))
                        .also(store::save)
                } else task
            }
            _loadError.value = errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
        }.onFailure { _loadError.value = it.message ?: "下载任务读取失败" }
    }

    override suspend fun enqueue(source: BookSource, catalog: SourceCatalog): String = withContext(Dispatchers.IO) {
        ready.await()
        require(catalog.chapters.isNotEmpty()) { "目录尚未读取完整" }
        require(catalog.book.sourceId == source.id) { "书籍与书源不匹配" }
        require(catalog.chapters.none { it.restricted || it.isVolume }) { "目录含受限章节，暂不能整本下载" }
        val (task, created) = mutex.withLock {
            val existing = _tasks.value.firstOrNull { it.source.id == source.id && it.source.definition == source.definition && it.catalog == catalog }
            if (existing != null) existing to false
            else BookDownloadTask(UUID.randomUUID().toString(), source, catalog).also {
                store.save(it)
                _tasks.value = _tasks.value + it
            } to true
        }
        if (!created) {
            if (task.status == COMPLETED && findImported(task.id) != null) return@withContext task.id
            if (task.status !in setOf(DOWNLOADING, IMPORTING, QUEUED)) resume(task.id) else scheduleRun()
            return@withContext task.id
        }
        scheduleOrFail(task.id)
        task.id
    }

    override suspend fun pause(id: String) = stop(id, PAUSED)
    override suspend fun cancel(id: String) = stop(id, CANCELLED)

    private suspend fun stop(id: String, state: BookDownloadStatus) = commands.withLock { withContext(Dispatchers.IO) {
        ready.await()
        val job = mutex.withLock {
            val task = _tasks.value.firstOrNull { it.id == id } ?: return@withContext
            if (task.status in setOf(COMPLETED, IMPORTING)) return@withContext
            saveLocked(task.copy(status = state, message = null))
            active?.takeIf { it.first == id }?.second
        }
        job?.cancelAndJoin()
        if (state == CANCELLED) mutex.withLock {
            store.clearPayload(id)
            taskLocked(id)?.let { saveLocked(it.copy(completedChapters = emptySet())) }
        }
        cancelRun()
    } }

    override suspend fun resume(id: String) = commands.withLock { withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            val task = taskLocked(id) ?: error("下载任务不存在")
            if (task.status in setOf(DOWNLOADING, IMPORTING)) return@withContext
            findImported(id)?.let { bookId ->
                saveLocked(task.copy(status = COMPLETED, bookId = bookId, message = null,
                    completedChapters = task.catalog.chapters.indices.toSet()))
                return@withContext
            }
            saveLocked(task.copy(status = QUEUED, bookId = null, message = null, completedChapters = store.completed(task)))
        }
        scheduleOrFail(id)
    } }

    suspend fun restoreScheduling() {
        ready.await()
        if (hasPendingTasks()) scheduleRun()
    }

    fun hasPendingTasks(): Boolean = _tasks.value.any { it.status in setOf(QUEUED, DOWNLOADING, WAITING_FOR_NETWORK, IMPORTING) }

    fun reportRunnerFailure(error: Exception) {
        _loadError.value = error.message ?: "后台下载运行失败，请重试"
    }

    /** 单一执行锁同时约束 UIDT、WorkManager 和恢复调用，任意时刻只有一本书下载。 */
    suspend fun runQueue(onProgress: suspend (BookDownloadTask) -> Unit = {}) = withContext(Dispatchers.IO) {
        ready.await()
        runnerMutex.withLock {
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val next = mutex.withLock {
                        _tasks.value.firstOrNull { it.status == QUEUED || it.status == WAITING_FOR_NETWORK }
                    } ?: break
                    if (!networkAvailable()) {
                        update(next.id) { it.copy(status = WAITING_FOR_NETWORK, message = "等待网络") }
                        onProgress(checkNotNull(tasks.value.firstOrNull { it.id == next.id }))
                        break
                    }
                    supervisorScope {
                        val work = async(start = CoroutineStart.LAZY) { runTask(next.id, onProgress) }
                        val accepted = mutex.withLock {
                            val latest = taskLocked(next.id)
                            if (latest?.status in setOf(QUEUED, WAITING_FOR_NETWORK)) {
                                saveLocked(latest!!.copy(status = DOWNLOADING, message = null, completedChapters = store.completed(latest)))
                                active = next.id to work
                                true
                            } else false
                        }
                        if (accepted) {
                            work.start()
                            try { work.await() } catch (_: CancellationException) { currentCoroutineContext().ensureActive() }
                            finally { mutex.withLock { active = null } }
                        } else work.cancel()
                    }
                    if (_tasks.value.firstOrNull { it.id == next.id }?.status == WAITING_FOR_NETWORK) break
                }
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        active = null
                        _tasks.value.filter { it.status in setOf(DOWNLOADING, IMPORTING) }.forEach {
                            saveLocked(it.copy(status = QUEUED, message = "下载中断，可继续"))
                        }
                    }
                }
            }
        }
    }

    private suspend fun runTask(id: String, onProgress: suspend (BookDownloadTask) -> Unit) {
        try {
            val task = mutex.withLock { taskLocked(id) } ?: return
            findImported(id)?.let { bookId ->
                update(id) { it.copy(status = COMPLETED, bookId = bookId, message = null,
                    completedChapters = task.catalog.chapters.indices.toSet()) }?.let { onProgress(it) }
                store.clearPayload(id)
                return
            }
            onProgress(task)
            val missing = task.catalog.chapters.indices.filterNot { it in task.completedChapters }
            for (pair in missing.chunked(2)) coroutineScope {
                pair.map { index -> async {
                    val chapter = task.catalog.chapters[index]
                    val text = engine.content(task.source, task.catalog.book, chapter,
                        task.catalog.chapters.getOrNull(index + 1)?.url)
                    currentCoroutineContext().ensureActive()
                    val saved = mutex.withLock {
                        val latest = taskLocked(id) ?: return@withLock null
                        if (latest.status != DOWNLOADING) return@withLock null
                        store.writeChapter(id, index, text)
                        latest.copy(completedChapters = latest.completedChapters + index).also(::saveLocked)
                    }
                    if (saved != null) onProgress(saved)
                } }.awaitAll()
            }
            currentCoroutineContext().ensureActive()
            val importing = mutex.withLock {
                val latest = taskLocked(id) ?: return
                if (latest.status != DOWNLOADING) return
                check(store.completed(latest).size == latest.totalChapters) { "章节尚未下载完整" }
                latest.copy(status = IMPORTING, message = null).also(::saveLocked)
            }
            onProgress(importing)
            val context = currentCoroutineContext()
            val epub = store.epubFile(id)
            DownloadedEpubWriter.write(epub, id, task.catalog, { store.readChapter(id, it) }, { context.ensureActive() })
            val result = importer.importAndWait(listOf(ImportSource.FromFile(epub, task.catalog.book.bookUrl, id)))
            val bookId = result.bookIds.singleOrNull()
            check(result.failed == 0 && result.success + result.skipped == 1 && bookId != null) {
                result.messages.firstOrNull() ?: "下载完成，但加入书架失败"
            }
            update(id) { it.copy(status = COMPLETED, bookId = bookId, message = null) }?.let { onProgress(it) }
            store.clearPayload(id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            update(id) {
                if (it.status in setOf(PAUSED, CANCELLED, COMPLETED)) it
                else it.copy(status = if (networkAvailable()) FAILED else WAITING_FOR_NETWORK,
                    message = if (networkAvailable()) error.message ?: "下载失败" else "等待网络")
            }?.let { onProgress(it) }
        }
    }

    private suspend fun scheduleOrFail(id: String) {
        try { scheduleRun() } catch (error: Exception) {
            update(id) { it.copy(status = FAILED, message = error.message ?: "无法启动后台下载") }
            throw error
        }
    }

    private suspend fun update(id: String, transform: (BookDownloadTask) -> BookDownloadTask): BookDownloadTask? = mutex.withLock {
        taskLocked(id)?.let { transform(it).also(::saveLocked) }
    }

    private fun taskLocked(id: String): BookDownloadTask? = _tasks.value.firstOrNull { it.id == id }
    private fun saveLocked(task: BookDownloadTask) {
        store.save(task)
        _tasks.value = _tasks.value.map { if (it.id == task.id) task else it }
    }
}
