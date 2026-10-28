package com.mirad.reader.data.downloads

import com.mirad.reader.core.epub.EpubParser
import com.mirad.reader.core.epub.ZipEntryReader
import com.mirad.reader.core.sources.*
import com.mirad.reader.core.sources.BookDownloadStatus.*
import com.mirad.reader.data.imports.BookImporter
import com.mirad.reader.data.imports.ImportSource
import com.mirad.reader.data.imports.ImportSummary
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class BookDownloadManagerTest {
    @get:Rule val temp = TemporaryFolder()
    private val source = BookSource("source-a", "测试书源", "https://example.test", JsonObject(emptyMap()))
    private fun catalog(title: String = "离线测试<&书>", count: Int = 4) = SourceCatalog(
        RemoteBook(source.id, source.name, title, "https://example.test/book/$title", author = "测试作者"),
        (0 until count).map { RemoteChapter("章节 $it", "https://example.test/chapter/$it", index = it) })

    private class Engine : BookSourceEngine {
        val calls = ConcurrentHashMap<Int, AtomicInteger>()
        var fetch: suspend (Int) -> String = { "正文 $it\n第二段 <script>只是文本</script>" }
        override suspend fun search(source: BookSource, query: String, page: Int) = emptyList<RemoteBook>()
        override suspend fun bookInfo(source: BookSource, book: RemoteBook) = book
        override suspend fun toc(source: BookSource, book: RemoteBook) = error("unused")
        override suspend fun content(source: BookSource, book: RemoteBook, chapter: RemoteChapter, nextChapterUrl: String?): String {
            calls.getOrPut(chapter.index) { AtomicInteger() }.incrementAndGet()
            return fetch(chapter.index)
        }
    }

    private inner class Harness(val engine: Engine = Engine()) : AutoCloseable {
        val root = temp.newFolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val receipts = ConcurrentHashMap<String, Long>()
        var imports = 0
        var importedBytes: ByteArray? = null
        var commitThenCancel = false
        var failImport = false
        var online = true
        val importer = object : BookImporter {
            override suspend fun importAndWait(sources: List<ImportSource>, force: Boolean): ImportSummary {
                imports++
                val file = sources.single() as ImportSource.FromFile
                if (failImport) return ImportSummary(0, 1, 0, listOf("注入入库失败"))
                importedBytes = file.file.readBytes()
                receipts[file.downloadTaskId!!] = 41L
                if (commitThenCancel) throw CancellationException("模拟提交后进程离开")
                return ImportSummary(1, 0, 0, emptyList(), bookIds = listOf(41L))
            }
        }
        fun manager() = BookDownloadManager(root, engine, importer, scope, findImported = { receipts[it] }, networkAvailable = { online })
        override fun close() { scope.cancel() }
    }

    @Test fun completeBookImportsOnceAndGeneratedEpubKeepsTheExactCatalog() = runBlocking {
        Harness().use { h ->
            val manager = h.manager()
            val book = catalog()
            val id = manager.enqueue(source, book)
            manager.runQueue()
            val done = manager.tasks.value.single()
            assertEquals(COMPLETED, done.status)
            assertEquals(41L, done.bookId)
            assertEquals(4, done.finishedChapters)
            assertEquals(id, manager.enqueue(source, book))
            manager.runQueue()
            assertEquals(1, h.imports)
            val epub = temp.newFile("roundtrip.epub").apply { writeBytes(h.importedBytes!!) }
            val parsed = EpubParser.parse(epub)
            assertEquals(book.book.title, parsed.title)
            assertEquals("测试作者", parsed.author)
            assertEquals(book.chapters.map { it.title }, parsed.spine.map { parsed.chapterTitles[it.index] })
            ZipEntryReader.open(epub).use { zip ->
                val chapter = zip.readText(parsed.spine[0].href)!!
                assertTrue(chapter.contains("&lt;script&gt;"))
                assertFalse(chapter.contains("<script>"))
            }
            assertFalse(File(h.root, "$id/chapter-0.txt").exists())
            assertTrue(File(h.root, "$id/task.json").exists())
        }
    }

    @Test fun retryOnlyFetchesMissingChaptersAndNeverImportsAnIncompleteBook() = runBlocking {
        Harness().use { h ->
            h.engine.fetch = { if (it == 2) throw IOException("章节暂时不可用") else "正文 $it" }
            val manager = h.manager()
            val id = manager.enqueue(source, catalog())
            manager.runQueue()
            assertEquals(FAILED, manager.tasks.value.single().status)
            assertEquals(0, h.imports)
            val completed = manager.tasks.value.single().completedChapters
            assertTrue(completed.containsAll(setOf(0, 1)))
            val calls = completed.associateWith { h.engine.calls.getValue(it).get() }
            h.engine.fetch = { "正文 $it" }
            manager.resume(id)
            manager.runQueue()
            assertEquals(COMPLETED, manager.tasks.value.single().status)
            calls.forEach { (index, count) -> assertEquals(count, h.engine.calls.getValue(index).get()) }
        }
    }

    @Test fun pauseCancelsTheActualChapterOperationAndResumeCanFinish() = runBlocking {
        Harness().use { h ->
            val started = CompletableDeferred<Unit>()
            h.engine.fetch = { started.complete(Unit); awaitCancellation() }
            val manager = h.manager()
            val id = manager.enqueue(source, catalog())
            val running = launch(Dispatchers.Default) { manager.runQueue() }
            withTimeout(5000) { started.await(); manager.pause(id); running.join() }
            assertEquals(PAUSED, manager.tasks.value.single().status)
            assertEquals(0, h.imports)
            h.engine.fetch = { "正文 $it" }
            manager.resume(id)
            manager.runQueue()
            assertEquals(COMPLETED, manager.tasks.value.single().status)
        }
    }

    @Test fun cancelRemovesOnlyItsPayloadAndSupportsAnExplicitRetry() = runBlocking {
        Harness().use { h ->
            h.engine.fetch = { if (it == 2) throw IOException("失败") else "正文 $it" }
            val manager = h.manager()
            val id = manager.enqueue(source, catalog())
            manager.runQueue()
            val unrelated = File(h.root, "keep.txt").apply { writeText("保留") }
            manager.cancel(id)
            assertEquals(CANCELLED, manager.tasks.value.single().status)
            assertEquals(0, manager.tasks.value.single().finishedChapters)
            assertTrue(unrelated.exists())
            assertEquals(listOf("task.json"), File(h.root, id).listFiles()!!.map { it.name })
            h.engine.fetch = { "正文 $it" }
            manager.resume(id)
            manager.runQueue()
            assertEquals(COMPLETED, manager.tasks.value.single().status)
        }
    }

    @Test fun processInterruptionRestoresSavedChaptersWithAnewManager() = runBlocking {
        Harness().use { h ->
            val thirdStarted = CompletableDeferred<Unit>()
            h.engine.fetch = { if (it >= 2) { thirdStarted.complete(Unit); awaitCancellation() } else "正文 $it" }
            val first = h.manager()
            first.enqueue(source, catalog())
            val running = launch(Dispatchers.Default) { first.runQueue() }
            withTimeout(5000) { thirdStarted.await(); running.cancelAndJoin() }
            assertEquals(QUEUED, first.tasks.value.single().status)
            h.engine.fetch = { "正文 $it" }
            val restored = h.manager()
            restored.runQueue()
            assertEquals(COMPLETED, restored.tasks.value.single().status)
            assertEquals(1, h.engine.calls.getValue(0).get())
            assertEquals(1, h.engine.calls.getValue(1).get())
        }
    }

    @Test fun committedImportReceiptRecoversWithoutDownloadingOrImportingAgain() = runBlocking {
        Harness().use { h ->
            h.commitThenCancel = true
            val manager = h.manager()
            manager.enqueue(source, catalog())
            manager.runQueue()
            assertEquals(1, h.imports)
            assertEquals(QUEUED, manager.tasks.value.single().status)
            val restored = h.manager()
            restored.runQueue()
            assertEquals(COMPLETED, restored.tasks.value.single().status)
            assertEquals(41L, restored.tasks.value.single().bookId)
            assertEquals(1, h.imports)
            assertTrue(h.engine.calls.values.all { it.get() == 1 })
        }
    }

    @Test fun failedImportKeepsAllChaptersForImportRetry() = runBlocking {
        Harness().use { h ->
            h.failImport = true
            val manager = h.manager()
            val id = manager.enqueue(source, catalog())
            manager.runQueue()
            assertEquals(FAILED, manager.tasks.value.single().status)
            assertEquals(4, manager.tasks.value.single().finishedChapters)
            h.failImport = false
            manager.resume(id)
            manager.runQueue()
            assertEquals(COMPLETED, manager.tasks.value.single().status)
            assertTrue(h.engine.calls.values.all { it.get() == 1 })
        }
    }

    @Test fun repeatedConcurrentClicksCreateOneTask() = runBlocking {
        Harness().use { h ->
            val manager = h.manager()
            val ids = (0 until 8).map { async(Dispatchers.Default) { manager.enqueue(source, catalog()) } }.awaitAll()
            assertEquals(1, ids.distinct().size)
            assertEquals(1, manager.tasks.value.size)
        }
    }

    @Test fun concurrentRunnersStillDownloadOneBookAndAtMostTwoChapters() = runBlocking {
        Harness().use { h ->
            val concurrent = AtomicInteger()
            val peak = AtomicInteger()
            h.engine.fetch = {
                val count = concurrent.incrementAndGet()
                peak.updateAndGet { current -> maxOf(current, count) }
                try { delay(20); "正文 $it" } finally { concurrent.decrementAndGet() }
            }
            val manager = h.manager()
            manager.enqueue(source, catalog("第一本"))
            manager.enqueue(source, catalog("第二本"))
            listOf(async(Dispatchers.Default) { manager.runQueue() }, async(Dispatchers.Default) { manager.runQueue() }).awaitAll()
            assertTrue(peak.get() <= 2)
            assertEquals(2, h.imports)
            assertTrue(manager.tasks.value.all { it.status == COMPLETED })
        }
    }

    @Test fun offlineTaskWaitsWithoutFetchingAndContinuesWhenNetworkReturns() = runBlocking {
        Harness().use { h ->
            h.online = false
            val manager = h.manager()
            manager.enqueue(source, catalog())
            manager.runQueue()
            assertEquals(WAITING_FOR_NETWORK, manager.tasks.value.single().status)
            assertTrue(h.engine.calls.isEmpty())
            h.online = true
            manager.runQueue()
            assertEquals(COMPLETED, manager.tasks.value.single().status)
        }
    }

    @Test fun restrictedOrEmptyCatalogCannotBeQueued() = runBlocking {
        Harness().use { h ->
            val manager = h.manager()
            val restricted = catalog().let { it.copy(chapters = it.chapters.map { chapter -> chapter.copy(restricted = true) }) }
            for (candidate in listOf(restricted, catalog(count = 0))) {
                val error = runCatching { manager.enqueue(source, candidate) }.exceptionOrNull()
                assertTrue(error is IllegalArgumentException)
            }
            assertTrue(manager.tasks.value.isEmpty())
        }
    }

    @Test fun resumeWaitsUntilAnEarlierCancelHasFinishedCleaningItsPayload() = runBlocking {
        Harness().use { h ->
            val started = CompletableDeferred<Unit>()
            val cancelling = CompletableDeferred<Unit>()
            val releaseCancellation = CompletableDeferred<Unit>()
            h.engine.fetch = {
                started.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cancelling.complete(Unit); releaseCancellation.await() }
                }
            }
            val manager = h.manager()
            val id = manager.enqueue(source, catalog())
            val runner = launch(Dispatchers.Default) { manager.runQueue() }
            withTimeout(5000) {
                started.await()
                val cancel = async(Dispatchers.Default) { manager.cancel(id) }
                cancelling.await()
                val resume = async(Dispatchers.Default) { manager.resume(id) }
                delay(30)
                assertFalse("恢复不能跨过旧取消的清理", resume.isCompleted)
                h.engine.fetch = { "正文 $it" }
                releaseCancellation.complete(Unit)
                cancel.await()
                resume.await()
                runner.join()
            }
            manager.runQueue()
            assertEquals(COMPLETED, manager.tasks.value.single().status)
            assertEquals(1, h.imports)
        }
    }
}
