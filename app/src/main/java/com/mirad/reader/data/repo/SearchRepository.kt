package com.mirad.reader.data.repo

import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.ChapterEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** 一条搜索命中。 */
data class SearchHit(
    val chapterIndex: Int,
    val chapterTitle: String,
    val charOffset: Int,
    /** 命中的原始文本，用于在阅读器中就近精确定位。 */
    val match: String,
    val snippet: String,
    /** 片段内命中词的位置，用于高亮显示。 */
    val matchStart: Int,
    val matchEnd: Int
)

data class SearchOptions(
    val caseSensitive: Boolean = false,
    val limitPerChapter: Int = 8,
    val maxResults: Int = 500
)

sealed interface SearchProgress {
    data class Scanning(val chapterIndex: Int, val totalChapters: Int, val hits: Int) : SearchProgress
    data class Done(val results: List<SearchHit>, val truncated: Boolean) : SearchProgress
}

/**
 * 单章匹配逻辑（纯函数，不依赖 Android 或存储，可直接单元测试）。
 */
object SearchMatcher {

    fun match(
        chapter: ChapterEntity,
        text: String,
        needle: String,
        options: SearchOptions = SearchOptions(),
        limit: Int = SearchOptions().limitPerChapter
    ): List<SearchHit> {
        if (needle.isEmpty() || text.isEmpty() || limit <= 0) return emptyList()
        val haystack = if (options.caseSensitive) text else text.lowercase()
        val target = if (options.caseSensitive) needle else needle.lowercase()
        val hits = ArrayList<SearchHit>(minOf(limit, 8))
        var from = 0
        while (hits.size < limit) {
            val found = haystack.indexOf(target, from)
            if (found < 0) break
            hits.add(buildHit(chapter, text, found, needle.length))
            from = found + target.length
        }
        return hits
    }

    private fun buildHit(
        chapter: ChapterEntity,
        text: String,
        matchStart: Int,
        matchLength: Int
    ): SearchHit {
        val rawStart = (matchStart - SNIPPET_BEFORE).coerceAtLeast(0)
        val rawEnd = (matchStart + matchLength + SNIPPET_AFTER).coerceAtMost(text.length)
        val raw = text.substring(rawStart, rawEnd)
        val matchInRaw = matchStart - rawStart
        val leading = raw.takeWhile { it.isWhitespace() }.length
        // 仅把换行换成空格，保持字符下标不变
        val snippet = raw.substring(leading).replace('\n', ' ').trimEnd()
        val offset = (matchInRaw - leading).coerceIn(0, snippet.length)
        return SearchHit(
            chapterIndex = chapter.index,
            chapterTitle = chapter.title,
            charOffset = matchStart,
            match = text.substring(matchStart, (matchStart + matchLength).coerceAtMost(text.length)),
            snippet = snippet,
            matchStart = offset,
            matchEnd = (offset + matchLength).coerceIn(0, snippet.length)
        )
    }

    private const val SNIPPET_BEFORE = 24
    private const val SNIPPET_AFTER = 40
}

/**
 * 全书搜索：优先使用 SQLite FTS4 倒排索引实现毫秒级匹配；索引未就绪时逐章读取正文并后台异步构建索引。
 */
class SearchRepository(
    private val textProvider: ChapterTextProvider,
    private val ftsDao: com.mirad.reader.data.db.dao.SearchFtsDao? = null
) {

    fun search(
        book: BookEntity,
        chapters: List<ChapterEntity>,
        query: String,
        options: SearchOptions = SearchOptions()
    ): Flow<SearchProgress> = flow {
        val needle = query.trim()
        if (needle.isEmpty() || chapters.isEmpty()) {
            emit(SearchProgress.Done(emptyList(), false))
            return@flow
        }

        // 1. 只有当整本书的所有章节全部完整录入索引且与当前章节结构、正文抽样指纹一致时，才走极速通道
        val isFullyIndexed = if (ftsDao != null) {
            runCatching {
                val metas = ftsDao.getIndexedChapters(book.id)
                if (metas.size != chapters.size) {
                    if (metas.isNotEmpty()) ftsDao.deleteForBook(book.id)
                    false
                } else {
                    var matches = true
                    for (i in chapters.indices) {
                        val ch = chapters[i]
                        val m = metas[i]
                        if (m.chapterIndex != ch.index || m.chapterTitle != ch.title) {
                            matches = false
                            break
                        }
                    }
                    if (matches && chapters.isNotEmpty()) {
                        // 抽样验证首章正文前 64 字符（纯 Kotlin 环境比对，彻底消除 SQLite 码点与 UTF-16 长度/截断差异）
                        val firstText = textProvider.readText(book, chapters[0])
                        val indexedFirst = ftsDao.getChapterContent(book.id, chapters[0].index)
                        if (indexedFirst == null || indexedFirst.take(64) != firstText.take(64)) {
                            matches = false
                        }
                    }
                    if (!matches) {
                        ftsDao.deleteForBook(book.id)
                        false
                    } else {
                        true
                    }
                }
            }.getOrDefault(false)
        } else false

        if (ftsDao != null && isFullyIndexed) {
            emit(SearchProgress.Scanning(0, chapters.size, 0))
            val baseNeedle = if (options.caseSensitive) needle else needle.lowercase()
            val escapedNeedle = baseNeedle
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
            val ftsResults = runCatching {
                ftsDao.search(book.id, escapedNeedle, options.caseSensitive, options.maxResults)
            }.getOrNull()

            if (ftsResults != null) {
                val hits = ArrayList<SearchHit>()
                var truncated = false
                val chapterMap = chapters.associateBy { it.index }
                for (entry in ftsResults) {
                    val chapter = chapterMap[entry.chapterIndex] ?: continue
                    val remaining = options.maxResults - hits.size
                    if (remaining <= 0) {
                        truncated = true
                        break
                    }
                    hits.addAll(matchInText(chapter, entry.content, needle, options, minOf(remaining, options.limitPerChapter)))
                    if (hits.size >= options.maxResults) {
                        truncated = true
                        break
                    }
                }
                emit(SearchProgress.Done(hits, truncated))
                return@flow
            }
        }

        // 2. 索引不完整时，走原有安全流式扫描，并在后台独立补齐完整索引
        val results = ArrayList<SearchHit>()
        val shouldBuildIndex = ftsDao != null && !isFullyIndexed
        val ftsCache = if (shouldBuildIndex) HashMap<Int, String>(chapters.size) else null
        var indexBuildFailed = false
        var truncated = false

        for ((position, chapter) in chapters.withIndex()) {
            if (position % 5 == 0 || position == chapters.lastIndex) {
                emit(SearchProgress.Scanning(chapter.index, chapters.size, results.size))
            }
            val textOrNull = textProvider.readTextOrNull(book, chapter)
            if (textOrNull == null) {
                indexBuildFailed = true
            }
            val text = textOrNull.orEmpty()
            if (ftsCache != null && !indexBuildFailed) {
                ftsCache[chapter.index] = text
            }

            if (!truncated) {
                val remaining = options.maxResults - results.size
                if (remaining > 0) {
                    results.addAll(matchInText(chapter, text, needle, options, minOf(remaining, options.limitPerChapter)))
                    if (results.size >= options.maxResults) {
                        truncated = true
                    }
                } else {
                    truncated = true
                }
            }

            // 如果不需要建库且搜索结果已满，可以提前 break
            if (truncated && (!shouldBuildIndex || indexBuildFailed)) {
                break
            }
        }

        // 只有当全书所有章节都成功读取且没有任何一章读取失败时，才一次性写入数据库，严禁残缺索引入库
        if (ftsDao != null && ftsCache != null && !indexBuildFailed && ftsCache.size == chapters.size) {
            runCatching {
                val fullBatch = chapters.map { ch ->
                    val content = ftsCache[ch.index].orEmpty()
                    com.mirad.reader.data.db.entity.ChapterFtsEntity(
                        bookId = book.id,
                        chapterIndex = ch.index,
                        chapterTitle = ch.title,
                        content = content,
                        contentLower = content.lowercase()
                    )
                }
                ftsDao.deleteForBook(book.id)
                ftsDao.insertAll(fullBatch)
            }
        }

        emit(SearchProgress.Done(results, truncated))
    }.flowOn(Dispatchers.IO)

    /**
     * 单章匹配（委托给纯函数 [SearchMatcher]，便于单元测试）。
     */
    fun matchInText(
        chapter: ChapterEntity,
        text: String,
        needle: String,
        options: SearchOptions = SearchOptions(),
        limit: Int = SearchOptions().limitPerChapter
    ): List<SearchHit> = SearchMatcher.match(chapter, text, needle, options, limit)
}
