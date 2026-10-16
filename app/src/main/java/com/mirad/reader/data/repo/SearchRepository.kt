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
 * 全书搜索：逐章读取正文并按子串匹配（不做正则，避免灾难性回溯）。
 */
class SearchRepository(private val textProvider: ChapterTextProvider) {

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
        val results = ArrayList<SearchHit>()
        var truncated = false

        for ((position, chapter) in chapters.withIndex()) {
            if (position % 5 == 0 || position == chapters.lastIndex) {
                emit(SearchProgress.Scanning(chapter.index, chapters.size, results.size))
            }
            val text = textProvider.readText(book, chapter)
            if (text.isEmpty()) continue
            val remaining = options.maxResults - results.size
            if (remaining <= 0) {
                truncated = true
                break
            }
            results.addAll(matchInText(chapter, text, needle, options, minOf(remaining, options.limitPerChapter)))
            if (results.size >= options.maxResults) {
                truncated = true
                break
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
