package com.mirad.reader.core.sources

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class BookDownloadStatus {
    QUEUED, DOWNLOADING, WAITING_FOR_NETWORK, PAUSED, FAILED, IMPORTING, COMPLETED, CANCELLED
}

@Serializable
data class BookDownloadTask(
    val id: String,
    val source: BookSource,
    val catalog: SourceCatalog,
    val status: BookDownloadStatus = BookDownloadStatus.QUEUED,
    val completedChapters: Set<Int> = emptySet(),
    val message: String? = null,
    val bookId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val title: String get() = catalog.book.title
    val totalChapters: Int get() = catalog.chapters.size
    val finishedChapters: Int get() = completedChapters.size
}

interface BookDownloads {
    val tasks: StateFlow<List<BookDownloadTask>>
    val loadError: StateFlow<String?>
    suspend fun enqueue(source: BookSource, catalog: SourceCatalog): String
    suspend fun pause(id: String)
    suspend fun resume(id: String)
    suspend fun cancel(id: String)
}
