package com.mirad.reader.data.repo

import com.mirad.reader.data.db.dao.IndexedChapterBrief
import com.mirad.reader.data.db.dao.SearchFtsDao
import com.mirad.reader.data.db.dao.PreferenceDao
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.db.entity.ChapterFtsEntity
import com.mirad.reader.data.db.entity.PreferenceEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 每一个查询拥有独立的冷缓存，避免第一个查询建库后对照也意外走热缓存。 */
class SearchCacheContractTest {
    private class MemoryFts : SearchFtsDao {
        val rows = mutableListOf<ChapterFtsEntity>()
        var inserts = 0
        var clears = 0
        var failNextClear = false
        override suspend fun insertAll(entities: List<ChapterFtsEntity>) { inserts++; rows += entities }
        override suspend fun countForBook(bookId: Long) = rows.count { it.bookId == bookId }
        override suspend fun deleteForBook(bookId: Long) { rows.removeAll { it.bookId == bookId } }
        override suspend fun deleteAll() {
            if (failNextClear) { failNextClear = false; error("controlled cache clear failure") }
            clears++
            rows.clear()
        }
        override suspend fun getIndexedChapters(bookId: Long) = rows.filter { it.bookId == bookId }
            .sortedBy { it.chapterIndex }.map { IndexedChapterBrief(it.chapterIndex, it.chapterTitle) }
        override suspend fun getChapterContent(bookId: Long, chapterIndex: Int) =
            rows.firstOrNull { it.bookId == bookId && it.chapterIndex == chapterIndex }?.content
        override suspend fun search(bookId: Long, escapedQuery: String, caseSensitive: Boolean, limit: Int): List<ChapterFtsEntity> {
            val literal = buildString {
                var i = 0
                while (i < escapedQuery.length) {
                    if (escapedQuery[i] == '\\' && i + 1 < escapedQuery.length) i++
                    append(escapedQuery[i++])
                }
            }
            return rows.filter { it.bookId == bookId &&
                (if (caseSensitive) it.content else it.contentLower).contains(literal) }.take(limit)
        }
    }

    private class Text(private val value: String) : ChapterTextProvider() {
        override fun readTextOrNull(book: BookEntity, chapter: ChapterEntity) = value
        override fun readText(book: BookEntity, chapter: ChapterEntity) = value
    }

    private class Preferences : PreferenceDao {
        val values = mutableMapOf("user_setting" to "must be preserved")
        var failNextPut = false
        override suspend fun put(preference: PreferenceEntity) {
            if (failNextPut) { failNextPut = false; error("controlled metadata write failure") }
            values[preference.key] = preference.value
        }
        override suspend fun get(key: String) = values[key]
        override fun observe(key: String) = MutableStateFlow(values[key])
        override suspend fun remove(key: String) { values.remove(key) }
        override suspend fun removeByPrefix(prefix: String) { values.keys.removeAll { it.startsWith(prefix) } }
        override suspend fun getAll() = values.map { PreferenceEntity(it.key, it.value) }
    }

    private val book = BookEntity(id = 1, title = "合成搜索书", format = BookFormat.TXT, filePath = "/unused")
    private val chapter = ChapterEntity(bookId = 1, index = 0, title = "合成章节")

    private suspend fun results(repo: SearchRepository, query: String, options: SearchOptions): List<SearchHit> =
        (repo.search(book, listOf(chapter), query, options).toList().last() as SearchProgress.Done).results

    @Test fun eachUnicodeQueryHasTheSameColdAndWarmResultsAndOriginalOffsets() = runBlocking {
        val cases = listOf(
            "前文 ς 后文" to "Σ", "前文 σ 后文" to "ς", "前文 ı 后文" to "I",
            "前文 ſ 后文" to "S", "前文 İX 后文" to "ix", "😀前文 Äpfel 后文" to "äpfel",
            "前文 MiradReader 后文" to "miradreader", "前文 观星台 后文" to "观星台",
            "前文 50%_\\done 后文" to "50%_\\done", "😀前文 𐐀 后文" to "𐐨"
        )
        for ((text, query) in cases) {
            val dao = MemoryFts()
            val repository = SearchRepository(Text(text), dao)
            val options = SearchOptions(caseSensitive = false)
            val cold = results(repository, query, options)
            assertEquals("冷扫描应命中 $query", 1, cold.size)
            assertEquals(text.indexOf(query, ignoreCase = true), cold.single().charOffset)
            assertEquals("冷扫描后建立完整索引", 1, dao.inserts)
            val warm = results(repository, query, options)
            assertEquals("$query 的热缓存不得漏检或改变偏移", cold, warm)
            assertEquals("相同格式的热缓存不应重建", 1, dao.inserts)
        }
    }

    @Test fun caseSensitiveQueriesKeepTheirColdAndWarmSemantics() = runBlocking {
        for ((text, query, count) in listOf(Triple("ς Σ", "Σ", 1), Triple("ı I", "I", 1), Triple("OnlyUpper", "onlyupper", 0))) {
            val dao = MemoryFts()
            val repository = SearchRepository(Text(text), dao)
            val options = SearchOptions(caseSensitive = true)
            val cold = results(repository, query, options)
            assertEquals(count, cold.size)
            assertEquals(cold, results(repository, query, options))
        }
    }

    @Test fun normalizationKeepsUtf16LengthAndTheMatchersEquivalenceClasses() {
        for ((text, query) in listOf("ς" to "Σ", "ı" to "I", "ſ" to "S", "İ" to "i", "K" to "k", "𐐀" to "𐐨")) {
            assertTrue(text.equals(query, ignoreCase = true))
            assertEquals(SearchRepository.normalizeCase(text), SearchRepository.normalizeCase(query))
        }
        val original = "😀İςıſ𠮷𐐀"
        assertEquals(original.length, SearchRepository.normalizeCase(original).length)
    }

    @Test fun legacyFoldedCacheIsRebuiltOnceAndItsVersionSurvivesRepositoryRecreation() = runBlocking {
        val dao = MemoryFts().apply {
            rows += ChapterFtsEntity(bookId = 1, chapterIndex = 0, chapterTitle = chapter.title, content = "ς", contentLower = "ς")
        }
        val preferences = Preferences()
        val repository = SearchRepository(Text("ς"), dao, preferences)
        val first = results(repository, "Σ", SearchOptions())
        assertEquals(1, first.size)
        assertEquals("ς", first.single().match)
        assertEquals("σ", dao.rows.single().contentLower)
        assertEquals(1, dao.clears)
        assertEquals("must be preserved", preferences.values["user_setting"])

        val reopened = SearchRepository(Text("ς"), dao, preferences)
        assertEquals(first, results(reopened, "Σ", SearchOptions()))
        assertEquals(1, dao.clears)
        assertEquals(1, dao.inserts)
    }

    @Test fun failedCacheInvalidationUsesScanningAndRetriesWithoutTrustingOldRows() = runBlocking {
        val dao = MemoryFts().apply {
            rows += ChapterFtsEntity(bookId = 1, chapterIndex = 0, chapterTitle = chapter.title, content = "ς", contentLower = "ς")
            failNextClear = true
        }
        val repository = SearchRepository(Text("ς"), dao, Preferences())
        assertEquals(1, results(repository, "Σ", SearchOptions()).size)
        assertEquals(0, dao.inserts)
        assertEquals("ς", dao.rows.single().contentLower)
        assertEquals(1, results(repository, "Σ", SearchOptions()).size)
        assertEquals("σ", dao.rows.single().contentLower)
        assertEquals(1, dao.inserts)
    }

    @Test fun failedFormatMarkerWriteDoesNotReenableTheOldCache() = runBlocking {
        val dao = MemoryFts()
        val preferences = Preferences().apply { failNextPut = true }
        val repository = SearchRepository(Text("ς"), dao, preferences)
        assertEquals(1, results(repository, "Σ", SearchOptions()).size)
        assertEquals(0, dao.inserts)
        assertEquals(1, results(repository, "Σ", SearchOptions()).size)
        assertEquals(1, dao.inserts)
        assertEquals("must be preserved", preferences.values["user_setting"])
    }
}
