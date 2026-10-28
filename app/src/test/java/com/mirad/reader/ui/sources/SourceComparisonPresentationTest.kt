package com.mirad.reader.ui.sources

import com.mirad.reader.core.sources.BookSource
import com.mirad.reader.core.sources.RemoteBook
import com.mirad.reader.core.sources.RemoteChapter
import com.mirad.reader.core.sources.SourceCatalog
import com.mirad.reader.core.sources.SourceComparisonEntry
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceComparisonPresentationTest {
    @Test
    fun unverifiedOrFailedCatalogsNeverEnterTheRanking() {
        val complete = entry("完整目录", 25)
        val pending = entry("仍在读取", 100).copy(catalog = null)
        val failed = entry("目录读取中断", 200).copy(error = "第二页读取失败")

        assertEquals(listOf(complete), rankedCatalogs(listOf(pending, failed, complete)))
    }

    @Test
    fun rankingUsesCompleteChapterCountsAndRetainsCandidatesBeyondFive() {
        val entries = listOf(10, 60, 30, 20, 50, 40).mapIndexed { index, chapters ->
            entry("书源 $index", chapters).let { candidate ->
                candidate.copy(book = candidate.book.copy(latestChapter = "最新章：第 ${10000 - chapters} 章"))
            }
        }

        val ranked = rankedCatalogs(entries)

        assertEquals(listOf(60, 50, 40, 30, 20, 10), ranked.map { it.catalog!!.chapters.size })
        assertEquals(listOf(60, 50, 40, 30, 20), ranked.take(5).map { it.catalog!!.chapters.size })
        assertEquals(6, ranked.size)
    }

    private fun entry(name: String, chapters: Int): SourceComparisonEntry {
        val source = BookSource(name, name, "https://example.test/$name", JsonObject(emptyMap()))
        val book = RemoteBook(source.id, source.name, "相同书名", "${source.url}/book")
        val catalog = SourceCatalog(book, List(chapters) { RemoteChapter("第 ${it + 1} 章", "${source.url}/$it", index = it) })
        return SourceComparisonEntry(source, book, catalog)
    }
}
