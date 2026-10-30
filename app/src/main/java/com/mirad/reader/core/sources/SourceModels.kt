package com.mirad.reader.core.sources

import java.io.InputStream
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 原始规则保留未知字段，供重新导入和下载快照使用。 */
@Serializable
data class BookSource(
    val id: String,
    val name: String,
    val url: String,
    val definition: JsonObject,
    val enabled: Boolean = true,
    val importUrl: String = "",
    val unsupportedReason: String? = null
)

@Serializable
data class SourceSubscription(val url: String, val fetchedAt: Long)

data class SourceImportReport(
    val added: Int = 0,
    val updated: Int = 0,
    val invalid: Int = 0,
    val unsupported: Int = 0,
    val messages: List<String> = emptyList(),
    val failedLinks: Int = 0,
    val cancelled: Boolean = false
)

data class SourceImportProgress(val completed: Int, val total: Int, val report: SourceImportReport)

@Serializable
data class RemoteBook(
    val sourceId: String,
    val sourceName: String,
    val title: String,
    val bookUrl: String,
    val author: String = "",
    val tocUrl: String = "",
    val coverUrl: String = "",
    val intro: String = "",
    val latestChapter: String = "",
    val variables: Map<String, String> = emptyMap()
)

@Serializable
data class RemoteChapter(
    val title: String,
    val url: String,
    val index: Int = 0,
    val isVolume: Boolean = false,
    val restricted: Boolean = false,
    val variables: Map<String, String> = emptyMap()
)

/** 仅完整读取目录后才能构造并交给排名或下载。 */
@Serializable
data class SourceCatalog(val book: RemoteBook, val chapters: List<RemoteChapter>)

interface BookSourceEngine {
    suspend fun search(source: BookSource, query: String, page: Int = 1): List<RemoteBook>
    suspend fun bookInfo(source: BookSource, book: RemoteBook): RemoteBook
    suspend fun toc(source: BookSource, book: RemoteBook): SourceCatalog
    suspend fun content(source: BookSource, book: RemoteBook, chapter: RemoteChapter, nextChapterUrl: String? = null): String
}

interface SourceRepository {
    val sources: StateFlow<List<BookSource>>
    val subscriptions: StateFlow<List<SourceSubscription>>
    val loadError: StateFlow<String?>
    suspend fun importUrl(url: String): SourceImportReport
    suspend fun importText(text: String, onProgress: (SourceImportProgress) -> Unit = {}): SourceImportReport
    suspend fun importDocument(openInput: () -> InputStream?, onProgress: (SourceImportProgress) -> Unit = {}): SourceImportReport
    suspend fun setEnabled(id: String, enabled: Boolean)
    suspend fun remove(id: String)
    suspend fun removeSubscription(url: String)
}

data class BookMatch(val key: String, val title: String, val author: String, val books: List<RemoteBook>)
data class SourceFailure(val sourceId: String, val sourceName: String, val message: String)
data class SourceSearchState(
    val query: String = "",
    val matches: List<BookMatch> = emptyList(),
    val running: Boolean = false,
    val totalSources: Int = 0,
    val completedSources: Int = 0,
    val failures: List<SourceFailure> = emptyList(),
    val message: String? = null
)

data class SourceComparisonEntry(
    val source: BookSource,
    val book: RemoteBook,
    val catalog: SourceCatalog? = null,
    val error: String? = null
)

data class SourceComparisonState(
    val match: BookMatch? = null,
    val entries: List<SourceComparisonEntry> = emptyList(),
    val running: Boolean = false,
    val totalSources: Int = 0,
    val completedSources: Int = 0,
    val failures: List<SourceFailure> = emptyList()
)

interface SourceSearch {
    val state: StateFlow<SourceSearchState>
    val comparison: StateFlow<SourceComparisonState>
    fun search(query: String)
    fun cancelSearch()
    fun compare(match: BookMatch)
    fun clearComparison()
}
