package com.mirad.reader.data.sources

import com.mirad.reader.core.sources.BookMatch
import com.mirad.reader.core.sources.BookSource
import com.mirad.reader.core.sources.BookSourceEngine
import com.mirad.reader.core.sources.RemoteBook
import com.mirad.reader.core.sources.SourceComparisonEntry
import com.mirad.reader.core.sources.SourceComparisonState
import com.mirad.reader.core.sources.SourceFailure
import com.mirad.reader.core.sources.SourceRepository
import com.mirad.reader.core.sources.SourceSearch
import com.mirad.reader.core.sources.SourceSearchState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale

/** 每次操作使用独立代次；取消或新查询后到达的旧请求不能覆盖新状态。 */
class SourceSearchCoordinator(
    private val repository: SourceRepository,
    private val engine: BookSourceEngine,
    private val scope: CoroutineScope
) : SourceSearch {
    private val mutableState = MutableStateFlow(SourceSearchState())
    private val mutableComparison = MutableStateFlow(SourceComparisonState())
    override val state = mutableState.asStateFlow()
    override val comparison = mutableComparison.asStateFlow()
    private val guard = Any()
    private val workers = Semaphore(6)
    private var searchGeneration = 0L
    private var comparisonGeneration = 0L
    private var searchJob: Job? = null
    private var comparisonJob: Job? = null

    override fun search(query: String) {
        synchronized(guard) {
            val generation = ++searchGeneration
            searchJob?.cancel()
            val text = query.trim()
            val sources = enabledSources()
            mutableState.value = SourceSearchState(query = text, running = text.isNotEmpty() && sources.isNotEmpty(),
                totalSources = sources.size, message = if (sources.isEmpty()) "没有启用且可用的书源" else null)
            if (text.isEmpty() || sources.isEmpty()) return
            searchJob = scope.launch {
                coroutineScope {
                    sources.forEach { source -> launch { workers.withPermit {
                        var books = emptyList<RemoteBook>()
                        var failure: SourceFailure? = null
                        try {
                            books = engine.search(source, text)
                        } catch (error: CancellationException) { throw error
                        } catch (error: Exception) { failure = failure(source, error) }
                        synchronized(guard) {
                            if (generation != searchGeneration) return@synchronized
                            val current = mutableState.value
                            mutableState.value = current.copy(
                                matches = group(current.matches.flatMap { it.books } + books),
                                completedSources = current.completedSources + 1,
                                failures = current.failures + listOfNotNull(failure)
                            )
                        }
                    } } }
                }
                synchronized(guard) {
                    if (generation == searchGeneration) mutableState.value = mutableState.value.copy(running = false)
                }
            }
        }
    }

    override fun cancelSearch() {
        synchronized(guard) {
            searchGeneration++
            searchJob?.cancel()
            searchJob = null
            mutableState.value = mutableState.value.copy(running = false)
        }
    }

    override fun compare(match: BookMatch) {
        synchronized(guard) {
            cancelSearch()
            val generation = ++comparisonGeneration
            comparisonJob?.cancel()
            val sources = enabledSources()
            mutableComparison.value = SourceComparisonState(match = match, running = sources.isNotEmpty(), totalSources = sources.size)
            comparisonJob = scope.launch {
                coroutineScope {
                    sources.forEach { source -> launch { workers.withPermit {
                        var entry: SourceComparisonEntry? = null
                        var failure: SourceFailure? = null
                        try {
                            // 对每一个启用源重新按完整标题搜索，再读详情确认书名与作者。
                            val candidates = (engine.search(source, match.title) + match.books.filter { it.sourceId == source.id })
                                .distinctBy { it.bookUrl }.filter { normalized(it.title) == normalized(match.title) }
                            var verified: RemoteBook? = null
                            var lastError: Exception? = null
                            for (candidate in candidates) {
                                try {
                                    val info = engine.bookInfo(source, candidate)
                                    val authorMatches = if (match.author.isBlank()) {
                                        match.books.any { it.sourceId == source.id && it.bookUrl == candidate.bookUrl }
                                    } else info.author.isNotBlank() && normalized(info.author) == normalized(match.author)
                                    if (normalized(info.title) == normalized(match.title) && authorMatches) {
                                        verified = info
                                        break
                                    }
                                } catch (error: CancellationException) { throw error
                                } catch (error: Exception) { lastError = error }
                            }
                            if (verified != null) {
                                entry = try {
                                    SourceComparisonEntry(source, verified, catalog = engine.toc(source, verified))
                                } catch (error: CancellationException) { throw error
                                } catch (error: Exception) {
                                    val problem = failure(source, error)
                                    failure = problem
                                    SourceComparisonEntry(source, verified, error = problem.message)
                                }
                            } else if (lastError != null) throw lastError
                        } catch (error: CancellationException) { throw error
                        } catch (error: Exception) { failure = failure(source, error) }
                        synchronized(guard) {
                            if (generation != comparisonGeneration) return@synchronized
                            val current = mutableComparison.value
                            mutableComparison.value = current.copy(
                                entries = (current.entries + listOfNotNull(entry)).sortedWith(
                                    compareByDescending<SourceComparisonEntry> { it.catalog != null }
                                        .thenByDescending { it.catalog?.chapters?.size ?: -1 }
                                        .thenBy { it.source.name }.thenBy { it.source.id }
                                ),
                                failures = current.failures + listOfNotNull(failure),
                                completedSources = current.completedSources + 1
                            )
                        }
                    } } }
                }
                synchronized(guard) {
                    if (generation == comparisonGeneration) mutableComparison.value = mutableComparison.value.copy(running = false)
                }
            }
        }
    }

    override fun clearComparison() {
        synchronized(guard) {
            comparisonGeneration++
            comparisonJob?.cancel()
            comparisonJob = null
            mutableComparison.value = SourceComparisonState()
        }
    }

    private fun enabledSources() = repository.sources.value.filter { it.enabled && it.unsupportedReason == null }
    private fun failure(source: BookSource, error: Exception) = SourceFailure(source.id, source.name, error.message ?: "书源请求失败")

    companion object {
        private fun normalized(value: String) = value.trim().replace(Regex("\\s+"), "").lowercase(Locale.ROOT)

        internal fun group(books: List<RemoteBook>): List<BookMatch> = books.distinctBy { it.sourceId to it.bookUrl }
            .groupBy { book ->
                if (book.author.isBlank()) "${normalized(book.title)}\u0000${book.sourceId}\u0000${book.bookUrl}"
                else "${normalized(book.title)}\u0000${normalized(book.author)}"
            }.map { (key, items) -> BookMatch(key, items.first().title, items.first().author, items.sortedWith(compareBy<RemoteBook> { it.sourceName }.thenBy { it.bookUrl })) }
            .sortedWith(compareBy<BookMatch> { normalized(it.title) }.thenBy { normalized(it.author) }.thenBy { it.key })
    }
}
