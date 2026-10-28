package com.mirad.reader.data.sources

import com.mirad.reader.core.sources.BookMatch
import com.mirad.reader.core.sources.BookSource
import com.mirad.reader.core.sources.BookSourceEngine
import com.mirad.reader.core.sources.RemoteBook
import com.mirad.reader.core.sources.RemoteChapter
import com.mirad.reader.core.sources.SourceCatalog
import com.mirad.reader.core.sources.SourceImportReport
import com.mirad.reader.core.sources.SourceRepository
import com.mirad.reader.core.sources.SourceSubscription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SourceSearchCoordinatorTest {
    private class Repository(sources: List<BookSource>) : SourceRepository {
        override val sources = MutableStateFlow(sources)
        override val subscriptions = MutableStateFlow<List<SourceSubscription>>(emptyList())
        override val loadError = MutableStateFlow<String?>(null)
        override suspend fun importUrl(url: String) = SourceImportReport()
        override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
        override suspend fun remove(id: String) = Unit
        override suspend fun removeSubscription(url: String) = Unit
    }

    private open class Engine : BookSourceEngine {
        override suspend fun search(source: BookSource, query: String, page: Int) = listOf(book(source, query))
        override suspend fun bookInfo(source: BookSource, book: RemoteBook) = book
        override suspend fun toc(source: BookSource, book: RemoteBook) = SourceCatalog(book, listOf(RemoteChapter("一", "https://example.com/chapter")))
        override suspend fun content(source: BookSource, book: RemoteBook, chapter: RemoteChapter, nextChapterUrl: String?) = "正文"
    }

    @Test
    fun groupsKnownAuthorsButKeepsAuthorlessResultsSeparate() {
        val first = source("a")
        val second = source("b")
        val matches = SourceSearchCoordinator.group(listOf(
            book(first, "同名"), book(second, "同名"), book(first, "同名").copy(author = "", bookUrl = "https://a/unknown"),
            book(second, "同名").copy(author = "", bookUrl = "https://b/unknown"), book(second, "同名").copy(author = "另一个作者", bookUrl = "https://b/other-book")
        ))
        assertEquals(4, matches.size)
        assertEquals(2, matches.single { it.author == "作者" }.books.size)
    }

    @Test
    fun progressiveResultsSurviveAnIndependentSourceFailure() = runTest {
        val sources = listOf(source("fast"), source("slow"), source("bad"))
        val engine = object : Engine() {
            override suspend fun search(source: BookSource, query: String, page: Int): List<RemoteBook> {
                if (source.id == "bad") throw IOException("访问失败")
                if (source.id == "slow") delay(200)
                return super.search(source, query, page)
            }
        }
        val coordinator = SourceSearchCoordinator(Repository(sources), engine, this)
        coordinator.search("整本书名")
        runCurrent()
        assertTrue(coordinator.state.value.running)
        assertEquals(1, coordinator.state.value.matches.single().books.size)
        assertEquals(1, coordinator.state.value.failures.size)
        advanceUntilIdle()
        assertFalse(coordinator.state.value.running)
        assertEquals(3, coordinator.state.value.completedSources)
        assertEquals(2, coordinator.state.value.matches.single().books.size)
    }

    @Test
    fun cancelledAndSupersededSearchesCannotPublishLateResponses() = runTest {
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val engine = object : Engine() {
            override suspend fun search(source: BookSource, query: String, page: Int): List<RemoteBook> {
                if (query == "旧") withContext(NonCancellable) { oldStarted.complete(Unit); releaseOld.await() }
                return listOf(book(source, query))
            }
        }
        val coordinator = SourceSearchCoordinator(Repository(listOf(source("a"))), engine, this)
        coordinator.search("旧")
        runCurrent()
        assertTrue(oldStarted.isCompleted)
        coordinator.search("新")
        runCurrent()
        releaseOld.complete(Unit)
        advanceUntilIdle()
        assertEquals("新", coordinator.state.value.matches.single().title)
        assertEquals("新", coordinator.state.value.query)
        coordinator.cancelSearch()
        assertFalse(coordinator.state.value.running)
    }

    @Test
    fun compareResearchesAllEnabledSourcesVerifiesDetailsAndRanksOnlyCompleteCatalogs() = runTest {
        val sources = listOf(source("short"), source("long"), source("wrong"), source("bad"), source("disabled").copy(enabled = false))
        val calls = mutableListOf<String>()
        val engine = object : Engine() {
            override suspend fun search(source: BookSource, query: String, page: Int): List<RemoteBook> {
                calls += "${source.id}:$query"
                return listOf(book(source, query))
            }
            override suspend fun bookInfo(source: BookSource, book: RemoteBook) = if (source.id == "wrong") book.copy(author = "其他作者") else book
            override suspend fun toc(source: BookSource, book: RemoteBook): SourceCatalog {
                if (source.id == "bad") throw IOException("第二页失败，目录不完整")
                if (source.id == "long") delay(100)
                return SourceCatalog(book, List(if (source.id == "long") 9 else 2) { RemoteChapter("第 $it 章", "https://example.com/$it", it) })
            }
        }
        val coordinator = SourceSearchCoordinator(Repository(sources), engine, this)
        coordinator.compare(BookMatch("match", "完整书名", "作者", listOf(book(sources.first(), "完整书名"))))
        runCurrent()
        assertTrue(coordinator.comparison.value.running)
        assertEquals("short", coordinator.comparison.value.entries.first().source.id)
        advanceTimeBy(101)
        advanceUntilIdle()
        val state = coordinator.comparison.value
        assertFalse(state.running)
        assertEquals(setOf("short:完整书名", "long:完整书名", "wrong:完整书名", "bad:完整书名"), calls.toSet())
        assertEquals(listOf("long", "short", "bad"), state.entries.map { it.source.id })
        assertNotNull(state.entries.last().error)
        assertEquals(null, state.entries.last().catalog)
        assertEquals(1, state.failures.size)
    }

    @Test
    fun selectingABookCancelsSlowSearchesBeforeComparisonNeedsTheirSlots() = runTest {
        val sources = listOf(source("fast")) + List(6) { source("slow-$it") }
        var oldStarted = 0
        var oldCancelled = 0
        val engine = object : Engine() {
            override suspend fun search(source: BookSource, query: String, page: Int): List<RemoteBook> {
                if (query == "初始查询" && source.id != "fast") {
                    oldStarted++
                    try { awaitCancellation() } finally { oldCancelled++ }
                }
                return listOf(book(source, "完整书名"))
            }
        }
        val coordinator = SourceSearchCoordinator(Repository(sources), engine, this)
        coordinator.search("初始查询")
        runCurrent()
        assertEquals(6, oldStarted)
        assertTrue(coordinator.state.value.running)
        coordinator.compare(coordinator.state.value.matches.single())
        runCurrent()
        assertEquals(6, oldCancelled)
        assertFalse(coordinator.state.value.running)
        assertFalse(coordinator.comparison.value.running)
        assertEquals(7, coordinator.comparison.value.completedSources)
        assertEquals(7, coordinator.comparison.value.entries.size)
    }

    companion object {
        private fun source(id: String) = BookSource(id, id, "https://$id.example", JsonObject(emptyMap()))
        private fun book(source: BookSource, title: String) = RemoteBook(source.id, source.name, title, "${source.url}/book", author = "作者")
    }
}
