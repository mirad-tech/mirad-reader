package com.mirad.reader.data.repo

import com.mirad.reader.data.db.entity.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatcherTest {

    private fun chapter(index: Int = 0, title: String = "第 ${index + 1} 章") =
        ChapterEntity(bookId = 1L, index = index, title = title)

    @Test
    fun findsMatchesWithAccurateOffsets() {
        val text = "前半句内容。STARFIELD 标记出现在这里，后面还有一些文字。"
        val hits = SearchMatcher.match(chapter(1, "第二章 碑文"), text, "STARFIELD")

        assertEquals(1, hits.size)
        val hit = hits[0]
        assertEquals(1, hit.chapterIndex)
        assertEquals("第二章 碑文", hit.chapterTitle)
        assertEquals("STARFIELD", hit.match)
        assertEquals(text.indexOf("STARFIELD"), hit.charOffset)
        // 片段中的高亮区间必须能对上原始命中词
        assertEquals("STARFIELD", hit.snippet.substring(hit.matchStart, hit.matchEnd))
    }

    @Test
    fun isCaseInsensitiveByDefaultAndSensitiveOnDemand() {
        val text = "MiradReader 与 miradreader 是同一个词的两种写法。"
        assertEquals(2, SearchMatcher.match(chapter(), text, "miradreader").size)

        val sensitive = SearchMatcher.match(
            chapter(),
            text,
            "miradreader",
            SearchOptions(caseSensitive = true)
        )
        assertEquals(1, sensitive.size)
        assertEquals(text.lastIndexOf("miradreader"), sensitive[0].charOffset)
    }

    @Test
    fun respectsLimit() {
        val text = "词 ".repeat(20)
        val hits = SearchMatcher.match(chapter(), text, "词", SearchOptions(limitPerChapter = 3), limit = 3)
        assertEquals(3, hits.size)
    }

    @Test
    fun supportsChineseQueries() {
        val text = "观星台建在半山腰，青石垒成的台阶一共九百九十九级。守台人常把这句话挂在嘴边。"
        val hits = SearchMatcher.match(chapter(), text, "观星台")
        assertEquals(1, hits.size)
        assertEquals(text.indexOf("观星台"), hits[0].charOffset)
        assertTrue(hits[0].snippet.contains("观星台"))
    }

    @Test
    fun snippetStaysWithinBoundsForEdgeMatches() {
        val hits = SearchMatcher.match(chapter(), "start", "start")
        assertEquals(1, hits.size)
        assertEquals(0, hits[0].matchStart)
        assertEquals(5, hits[0].matchEnd)
        assertEquals("start", hits[0].snippet)
    }

    @Test
    fun multilineSnippetKeepsMatchHighlightAligned() {
        val text = "第一段内容写得比较长一些。\n    第二段里出现了关键字 STARFIELD，\n第三段继续。"
        val hits = SearchMatcher.match(chapter(), text, "STARFIELD")
        assertEquals(1, hits.size)
        assertEquals("STARFIELD", hits[0].snippet.substring(hits[0].matchStart, hits[0].matchEnd))
        assertTrue(!hits[0].snippet.contains('\n'))
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertTrue(SearchMatcher.match(chapter(), "没有任何关键字", "STARFIELD").isEmpty())
        assertTrue(SearchMatcher.match(chapter(), "", "x").isEmpty())
        assertTrue(SearchMatcher.match(chapter(), "text", "").isEmpty())
    }
}

class SearchRepositoryFlowTest {

    private class TestSearchFtsDao : com.mirad.reader.data.db.dao.SearchFtsDao {
        val storage = mutableListOf<com.mirad.reader.data.db.entity.ChapterFtsEntity>()
        var insertCount = 0
        var deleteCount = 0

        override suspend fun insertAll(entities: List<com.mirad.reader.data.db.entity.ChapterFtsEntity>) {
            insertCount++
            storage.addAll(entities)
        }

        override suspend fun countForBook(bookId: Long): Int =
            storage.count { it.bookId == bookId }

        override suspend fun deleteForBook(bookId: Long) {
            deleteCount++
            storage.removeAll { it.bookId == bookId }
        }

        override suspend fun search(
            bookId: Long,
            escapedQuery: String,
            caseSensitive: Boolean,
            limit: Int
        ): List<com.mirad.reader.data.db.entity.ChapterFtsEntity> {
            val literal = escapedQuery.replace("\\%", "%").replace("\\_", "_").replace("\\\\", "\\")
            return storage.filter {
                if (it.bookId != bookId) return@filter false
                if (caseSensitive) {
                    it.content.contains(literal)
                } else {
                    it.contentLower.contains(literal)
                }
            }.take(limit)
        }

        override suspend fun getIndexedChapters(bookId: Long): List<com.mirad.reader.data.db.dao.IndexedChapterBrief> {
            return storage.filter { it.bookId == bookId }
                .sortedBy { it.chapterIndex }
                .map {
                    com.mirad.reader.data.db.dao.IndexedChapterBrief(
                        chapterIndex = it.chapterIndex,
                        chapterTitle = it.chapterTitle
                    )
                }
        }

        override suspend fun getChapterContent(bookId: Long, chapterIndex: Int): String? {
            return storage.firstOrNull { it.bookId == bookId && it.chapterIndex == chapterIndex }?.content
        }
    }

    private class MapTextProvider(private val chaptersText: Map<Int, String?>) :
        ChapterTextProvider(null) {
        override fun readTextOrNull(
            book: com.mirad.reader.data.db.entity.BookEntity,
            chapter: ChapterEntity
        ): String? {
            return chaptersText[chapter.index]
        }

        override fun readText(
            book: com.mirad.reader.data.db.entity.BookEntity,
            chapter: ChapterEntity
        ): String {
            return readTextOrNull(book, chapter).orEmpty()
        }
    }

    @Test
    fun testInitialSearchBuildsIndexAndSubsequentHitsIndex() = kotlinx.coroutines.runBlocking {
        val texts = mutableMapOf(
            0 to "第一章内容包含了 early 阶段的描述。",
            1 to "第二章内容描述了 late 阶段的故事。"
        )
        val textProvider = MapTextProvider(texts)
        val ftsDao = TestSearchFtsDao()
        val repo = SearchRepository(textProvider, ftsDao)

        val book = com.mirad.reader.data.db.entity.BookEntity(
            id = 100L,
            title = "测试书",
            format = com.mirad.reader.data.db.entity.BookFormat.TXT,
            filePath = "/fake/path"
        )
        val chapters = listOf(
            ChapterEntity(bookId = 100L, index = 0, title = "第一章", charCount = texts[0]!!.length.toLong()),
            ChapterEntity(bookId = 100L, index = 1, title = "第二章", charCount = texts[1]!!.length.toLong())
        )

        // 第一次搜索 early，触发流式扫描并在全书扫描完成后建立全量索引
        var hits1 = emptyList<SearchHit>()
        repo.search(book, chapters, "early").collect { progress ->
            if (progress is SearchProgress.Done) hits1 = progress.results
        }
        assertEquals(1, hits1.size)
        assertEquals(0, hits1[0].chapterIndex)
        assertEquals(1, ftsDao.insertCount)
        assertEquals(2, ftsDao.storage.size)

        // 第二次搜索 late，直接通过 FTS 索引快速命中，不再二次扫描建库
        var hits2 = emptyList<SearchHit>()
        repo.search(book, chapters, "late").collect { progress ->
            if (progress is SearchProgress.Done) hits2 = progress.results
        }
        assertEquals(1, hits2.size)
        assertEquals(1, hits2[0].chapterIndex)
        assertEquals(1, ftsDao.insertCount) // 仍为 1，走快速通道
    }

    @Test
    fun testContentChangeInvalidatesIndexAndRebuilds() = kotlinx.coroutines.runBlocking {
        val texts = mutableMapOf(
            0 to "原始章节：包含 early 词汇。",
            1 to "第二章：包含 normal 词汇。"
        )
        val textProvider = MapTextProvider(texts)
        val ftsDao = TestSearchFtsDao()
        val repo = SearchRepository(textProvider, ftsDao)

        val book = com.mirad.reader.data.db.entity.BookEntity(
            id = 200L,
            title = "测试正文变更书",
            format = com.mirad.reader.data.db.entity.BookFormat.TXT,
            filePath = "/fake/path"
        )
        val chapters = listOf(
            ChapterEntity(bookId = 200L, index = 0, title = "第一章", charCount = texts[0]!!.length.toLong()),
            ChapterEntity(bookId = 200L, index = 1, title = "第二章", charCount = texts[1]!!.length.toLong())
        )

        // 首次搜索建库
        repo.search(book, chapters, "early").collect {}
        assertEquals(1, ftsDao.insertCount)

        // 模拟正文被修改：从 early 改为 fresh
        texts[0] = "修订章节：包含 fresh 词汇。"
        val updatedChapters = listOf(
            ChapterEntity(bookId = 200L, index = 0, title = "第一章", charCount = texts[0]!!.length.toLong()),
            ChapterEntity(bookId = 200L, index = 1, title = "第二章", charCount = texts[1]!!.length.toLong())
        )

        // 再次搜索 fresh：因为首章正文变化，索引自动失效并清除重建，绝不返回空！
        var hits = emptyList<SearchHit>()
        repo.search(book, updatedChapters, "fresh").collect { progress ->
            if (progress is SearchProgress.Done) hits = progress.results
        }
        assertEquals(1, hits.size)
        assertEquals(0, hits[0].chapterIndex)
        assertTrue(ftsDao.deleteCount >= 1)
        assertEquals(2, ftsDao.insertCount)
    }

    @Test
    fun testChineseSubstringSearchWithoutOmission() = kotlinx.coroutines.runBlocking {
        val texts = mutableMapOf(
            0 to "少年踏上征途 hello world，前方一片苍茫。"
        )
        val textProvider = MapTextProvider(texts)
        val ftsDao = TestSearchFtsDao()
        val repo = SearchRepository(textProvider, ftsDao)

        val book = com.mirad.reader.data.db.entity.BookEntity(
            id = 300L,
            title = "中文子串测试书",
            format = com.mirad.reader.data.db.entity.BookFormat.TXT,
            filePath = "/fake/path"
        )
        val chapters = listOf(
            ChapterEntity(bookId = 300L, index = 0, title = "第一章", charCount = texts[0]!!.length.toLong())
        )

        // 建库
        repo.search(book, chapters, "少年").collect {}

        // 测试各种中文子串检索：少年、征途、世界等任意子串都能 100% 召回
        var hits1 = emptyList<SearchHit>()
        repo.search(book, chapters, "少年").collect { if (it is SearchProgress.Done) hits1 = it.results }
        assertEquals(1, hits1.size)

        var hits2 = emptyList<SearchHit>()
        repo.search(book, chapters, "征途").collect { if (it is SearchProgress.Done) hits2 = it.results }
        assertEquals(1, hits2.size)

        var hits3 = emptyList<SearchHit>()
        repo.search(book, chapters, "踏上").collect { if (it is SearchProgress.Done) hits3 = it.results }
        assertEquals(1, hits3.size)
    }

    /**
     * 针对 Reviewer Round 2 P2-4 的回归测试：
     * 正文含 Emoji 与生僻字时，不能因为 SQLite 码点与 UTF-16 长度差异导致每次搜索都误删重建索引。
     */
    @Test
    fun testEmojiBookDoesNotRebuildIndex() = kotlinx.coroutines.runBlocking {
        val texts = mutableMapOf(
            0 to "中😀𠮷a 探险记，第一章的内容。",
            1 to "第二章继续包含 Emoji 😀。"
        )
        val textProvider = MapTextProvider(texts)
        val ftsDao = TestSearchFtsDao()
        val repo = SearchRepository(textProvider, ftsDao)

        val book = com.mirad.reader.data.db.entity.BookEntity(
            id = 400L,
            title = "Emoji 书",
            format = com.mirad.reader.data.db.entity.BookFormat.TXT,
            filePath = "/fake/path"
        )
        val chapters = listOf(
            ChapterEntity(bookId = 400L, index = 0, title = "第一章", charCount = texts[0]!!.length.toLong()),
            ChapterEntity(bookId = 400L, index = 1, title = "第二章", charCount = texts[1]!!.length.toLong())
        )

        // 首次搜索：建库，入库时先清理后插入，insertCount = 1, deleteCount = 1
        repo.search(book, chapters, "不存在的词1").collect {}
        assertEquals(1, ftsDao.insertCount)
        assertEquals(1, ftsDao.deleteCount)

        // 连续搜索：绝不能因为 Emoji 长度误判为脏索引！deleteCount 必须仍为 1，insertCount 仍为 1
        repo.search(book, chapters, "不存在的词2").collect {}
        assertEquals(1, ftsDao.insertCount)
        assertEquals(1, ftsDao.deleteCount)

        repo.search(book, chapters, "不存在的词3").collect {}
        assertEquals(1, ftsDao.insertCount)
        assertEquals(1, ftsDao.deleteCount)
    }

    /**
     * 针对 Reviewer Round 2 P2-5 的回归测试：
     * 搜索词含特殊字符与通配符（%，_，\）时，正确转义并精准字面召回。
     */
    @Test
    fun testWildcardSearchEscaping() = kotlinx.coroutines.runBlocking {
        val texts = mutableMapOf(
            0 to "当前进度已达到 50% 阈值，目标完成。",
            1 to "另一个变量是 _blank 属性。"
        )
        val textProvider = MapTextProvider(texts)
        val ftsDao = TestSearchFtsDao()
        val repo = SearchRepository(textProvider, ftsDao)

        val book = com.mirad.reader.data.db.entity.BookEntity(
            id = 500L,
            title = "通配符测试书",
            format = com.mirad.reader.data.db.entity.BookFormat.TXT,
            filePath = "/fake/path"
        )
        val chapters = listOf(
            ChapterEntity(bookId = 500L, index = 0, title = "第一章", charCount = texts[0]!!.length.toLong()),
            ChapterEntity(bookId = 500L, index = 1, title = "第二章", charCount = texts[1]!!.length.toLong())
        )

        // 搜索 50%
        var hitsPercent = emptyList<SearchHit>()
        repo.search(book, chapters, "50%").collect { if (it is SearchProgress.Done) hitsPercent = it.results }
        assertEquals(1, hitsPercent.size)
        assertEquals("50%", hitsPercent[0].match)

        // 搜索 _blank
        var hitsUnderline = emptyList<SearchHit>()
        repo.search(book, chapters, "_blank").collect { if (it is SearchProgress.Done) hitsUnderline = it.results }
        assertEquals(1, hitsUnderline.size)
        assertEquals("_blank", hitsUnderline[0].match)
    }

    /**
     * 针对 Reviewer Round 2 P2-6 的回归测试：
     * 含有空章节（空正文）的书也能完整入库，后续搜索不再重新走全量流式扫描。
     */
    @Test
    fun testEmptyChaptersIndexing() = kotlinx.coroutines.runBlocking {
        val texts = mutableMapOf(
            0 to "", // 空章节
            1 to "第二章包含实质内容 target"
        )
        val textProvider = MapTextProvider(texts)
        val ftsDao = TestSearchFtsDao()
        val repo = SearchRepository(textProvider, ftsDao)

        val book = com.mirad.reader.data.db.entity.BookEntity(
            id = 600L,
            title = "含空章节的书",
            format = com.mirad.reader.data.db.entity.BookFormat.TXT,
            filePath = "/fake/path"
        )
        val chapters = listOf(
            ChapterEntity(bookId = 600L, index = 0, title = "空序章", charCount = 0L),
            ChapterEntity(bookId = 600L, index = 1, title = "正文章", charCount = texts[1]!!.length.toLong())
        )

        // 首次搜索：全书入库，包含空章节
        repo.search(book, chapters, "target").collect {}
        assertEquals("即使含空章节，索引也必须覆盖全部 2 章", 2, ftsDao.storage.size)
        assertEquals(1, ftsDao.insertCount)

        // 二次搜索：走索引快速通道，不二次建库
        var hits = emptyList<SearchHit>()
        repo.search(book, chapters, "target").collect { if (it is SearchProgress.Done) hits = it.results }
        assertEquals(1, hits.size)
        assertEquals(1, ftsDao.insertCount)
    }

    /**
     * 针对 Reviewer Round 3 P2-1 的回归测试：
     * 读取失败（readTextOrNull 返回 null）严禁当作合法空章节持久化入库；
     * 正文恢复后再次搜索，必须能重新流式扫描、召回正文，并在全书成功读取后建立完整索引。
     */
    @Test
    fun testReadFailureDoesNotCommitPartialIndex() = kotlinx.coroutines.runBlocking {
        // 第一章正常，第二章读取失败（null，模拟文件暂缺或 IO 异常）
        val texts = mutableMapOf<Int, String?>(
            0 to "第一章内容包含了 head 关键字。",
            1 to null
        )
        val textProvider = MapTextProvider(texts)
        val ftsDao = TestSearchFtsDao()
        val repo = SearchRepository(textProvider, ftsDao)

        val book = com.mirad.reader.data.db.entity.BookEntity(
            id = 700L,
            title = "读取异常书",
            format = com.mirad.reader.data.db.entity.BookFormat.TXT,
            filePath = "/fake/path"
        )
        val chapters = listOf(
            ChapterEntity(bookId = 700L, index = 0, title = "第一章", charCount = texts[0]!!.length.toLong()),
            ChapterEntity(bookId = 700L, index = 1, title = "第二章", charCount = 100L)
        )

        // 首次搜索：第二章读取失败，虽然第一章能命中，但绝不能将半截或损坏的空正文持久化到 FTS 索引
        var hits1 = emptyList<SearchHit>()
        repo.search(book, chapters, "head").collect { if (it is SearchProgress.Done) hits1 = it.results }
        assertEquals(1, hits1.size)
        assertEquals("第二章读取失败时，FTS 索引必须拒绝入库，防止残缺脏数据持久化", 0, ftsDao.storage.size)
        assertEquals(0, ftsDao.insertCount)

        // 模拟正文恢复（例如下载完成或文件就绪）
        texts[1] = "第二章内容恢复了，包含了 late 关键字。"

        // 再次搜索 late：由于上次未提交残缺索引，这次能正常通过流式扫描完整召回，绝不会漏检！
        var hits2 = emptyList<SearchHit>()
        repo.search(book, chapters, "late").collect { if (it is SearchProgress.Done) hits2 = it.results }
        assertEquals(1, hits2.size)
        assertEquals(1, hits2[0].chapterIndex)
        // 且本次全书读取成功后，索引顺利完整建库
        assertEquals("正文全部恢复后，全书 2 章完整入库", 2, ftsDao.storage.size)
        assertEquals(1, ftsDao.insertCount)

        // 第三次搜索 late：走索引快速通道，insertCount 保持不变
        var hits3 = emptyList<SearchHit>()
        repo.search(book, chapters, "late").collect { if (it is SearchProgress.Done) hits3 = it.results }
        assertEquals(1, hits3.size)
        assertEquals(1, ftsDao.insertCount)
    }

    /**
     * 针对 Reviewer Round 3 P2-3 的回归测试：
     * 非 ASCII 变音符号（如德语 Äpfel / äpfel，Überraschung / überraschung）
     * 必须在大小写不敏感模式下 100% 精确召回，且支持区分大小写模式。
     */
    @Test
    fun testNonAsciiCaseInsensitiveSearch() = kotlinx.coroutines.runBlocking {
        val texts = mutableMapOf<Int, String?>(
            0 to "Hier sind köstliche Äpfel und eine große Überraschung für dich."
        )
        val textProvider = MapTextProvider(texts)
        val ftsDao = TestSearchFtsDao()
        val repo = SearchRepository(textProvider, ftsDao)

        val book = com.mirad.reader.data.db.entity.BookEntity(
            id = 800L,
            title = "非 ASCII 多语言书",
            format = com.mirad.reader.data.db.entity.BookFormat.TXT,
            filePath = "/fake/path"
        )
        val chapters = listOf(
            ChapterEntity(bookId = 800L, index = 0, title = "第一章", charCount = texts[0]!!.length.toLong())
        )

        // 1. 首次搜索建库，大小写不敏感搜索 äpfel，命中 Äpfel
        var hits1 = emptyList<SearchHit>()
        repo.search(book, chapters, "äpfel", SearchOptions(caseSensitive = false)).collect {
            if (it is SearchProgress.Done) hits1 = it.results
        }
        assertEquals(1, hits1.size)
        assertEquals("Äpfel", hits1[0].match)
        assertEquals(1, ftsDao.storage.size)

        // 2. 索引已就绪后走 FTS 快速通道，搜索大写 Äpfel，命中
        var hits2 = emptyList<SearchHit>()
        repo.search(book, chapters, "Äpfel", SearchOptions(caseSensitive = false)).collect {
            if (it is SearchProgress.Done) hits2 = it.results
        }
        assertEquals(1, hits2.size)
        assertEquals("Äpfel", hits2[0].match)

        // 3. 走 FTS 快速通道，搜索小写 überraschung，命中 Überraschung
        var hits3 = emptyList<SearchHit>()
        repo.search(book, chapters, "überraschung", SearchOptions(caseSensitive = false)).collect {
            if (it is SearchProgress.Done) hits3 = it.results
        }
        assertEquals(1, hits3.size)
        assertEquals("Überraschung", hits3[0].match)

        // 4. 区分大小写模式：搜索小写 äpfel 应该不命中，搜索大写 Äpfel 命中
        var hitsCaseNo = emptyList<SearchHit>()
        repo.search(book, chapters, "äpfel", SearchOptions(caseSensitive = true)).collect {
            if (it is SearchProgress.Done) hitsCaseNo = it.results
        }
        assertEquals(0, hitsCaseNo.size)

        var hitsCaseYes = emptyList<SearchHit>()
        repo.search(book, chapters, "Äpfel", SearchOptions(caseSensitive = true)).collect {
            if (it is SearchProgress.Done) hitsCaseYes = it.results
        }
        assertEquals(1, hitsCaseYes.size)
        assertEquals("Äpfel", hitsCaseYes[0].match)
    }
}
