package com.mirad.reader.data.imports

import com.mirad.reader.core.html.ChapterHtmlBuilder
import com.mirad.reader.core.model.ChapterTextMap
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.repo.ChapterTextProvider
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor

internal class EditionAnchors(
    private val oldBook: BookEntity,
    private val oldChapters: List<ChapterEntity>,
    private val newBook: BookEntity,
    private val newChapters: List<ChapterEntity>,
    private val relation: EditionRelation,
    private val textProvider: ChapterTextProvider = ChapterTextProvider(),
    private val exactTxtCharPrefix: Boolean = (oldBook.format == BookFormat.TXT && relation == EditionRelation.EXTENDS)
) {
    data class Point(val chapter: Int, val offset: Int)
    data class Range(val chapter: Int, val start: Int, val end: Int, val text: String)

    private data class Text(val value: String, val txtMap: ChapterTextMap? = null, val body: String? = null)
    private data class Targets(val exact: List<Int>, val chapter: Int?)

    // 有笔记的长书也只保留当前附近几章的正文，避免映射时把整本书放进内存。
    private val texts = object : LinkedHashMap<Pair<Boolean, Int>, Text?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Boolean, Int>, Text?>): Boolean =
            size > 8
    }
    private val titles = newChapters.groupBy { editionKey(it.title) }
    private val txtExtension = oldBook.format == BookFormat.TXT && relation == EditionRelation.EXTENDS

    // 同一外部 URI 被原地修改后重新导入：底层文件已是新内容，不能再用旧章节字节区间去切新文件。
    private val oldSourceOverwritten =
        oldBook.filePath == newBook.filePath && oldBook.contentPath.isNullOrBlank()

    // 归一化全局字符起点表：与原文件具体编码（GBK/UTF-8/UTF-16/CRLF）解耦，在零拷贝与旧版模式下均精确成立。
    private val oldStartChars: LongArray = prefixCharStarts(oldChapters)
    private val newStartChars: LongArray = prefixCharStarts(newChapters)

    // 仅在全书逐字符前缀严格一致（exactTxtCharPrefix == true，非忽略空白/跳过首章前简介的兜底匹配）时启用全局字符坐标映射
    private val strictTxtCharPrefix: Boolean by lazy {
        if (!txtExtension || !exactTxtCharPrefix || oldChapters.isEmpty() || newChapters.isEmpty()) return@lazy false
        val oldFirst = text(false, 0)?.body ?: return@lazy false
        val newFirst = text(true, 0)?.body ?: return@lazy false
        newFirst.startsWith(oldFirst) || oldFirst.startsWith(newFirst)
    }

    // 核验修订版 EPUB 是否有同序号可读章节完全一致（证明两书为同一 spine 序列，允许无目录合成标题按同序号回退章首）
    private val epubSpineAligned: Boolean by lazy {
        if (oldBook.format != BookFormat.EPUB) return@lazy false
        val shared = minOf(oldChapters.size, newChapters.size)
        (0 until shared).any { idx ->
            val a = text(false, idx)?.value
            val b = text(true, idx)?.value
            !a.isNullOrBlank() && a == b
        }
    }

    fun point(chapter: Int, offset: Int): Point? {
        val old = text(false, chapter)
        if (old == null) {
            return fallbackPointWhenOldUnreadable(chapter, offset)
        }
        if (offset !in 0..old.value.length) return null
        if (strictTxtCharPrefix) txtPoint(chapter, offset, old)?.let { return it }

        val targets = targets(chapter)
        val matches = targets.exact.mapNotNull { index ->
            val new = text(true, index) ?: return@mapNotNull null
            locateOffset(old.value, new.value, offset)?.let { Point(index, it) }
        }
        return matches.singleOrNull()
    }

    fun chapterStart(chapter: Int): Point? {
        if (strictTxtCharPrefix) {
            val startChar = oldStartChars.getOrNull(chapter) ?: return null
            return newTxtChapterByChar(startChar)?.let { Point(it.index, 0) }
        }
        return targets(chapter).chapter?.let { Point(it, 0) }
    }

    fun ranges(chapter: Int, start: Int, end: Int, expected: String): List<Range>? {
        val old = text(false, chapter)?.value
        if (old == null) {
            return fallbackRangesWhenOldUnreadable(chapter, start, end, expected)
        }
        if (start < 0 || end <= start || end > old.length || compact(expected).isEmpty() ||
            compact(old.substring(start, end)) != compact(expected)) return null
        val from = point(chapter, start) ?: return null
        val to = point(chapter, end) ?: return null
        if (from.chapter > to.chapter || (!txtExtension && from.chapter != to.chapter)) return null
        val ranges = (from.chapter..to.chapter).mapNotNull { index ->
            val value = text(true, index)?.value ?: return null
            val lo = if (index == from.chapter) from.offset else 0
            val hi = if (index == to.chapter) to.offset else value.length
            if (lo >= hi) null else Range(index, lo, hi, value.substring(lo, hi))
        }
        return ranges.takeIf { it.isNotEmpty() && compact(it.joinToString("") { range -> range.text }) == compact(expected) }
    }

    private fun resolveUnreadableTargetChapter(chapter: Int): Int? {
        val oldCh = oldChapters.getOrNull(chapter) ?: return null
        val candidates = titles[editionKey(oldCh.title)].orEmpty().filter { it.isVolume == oldCh.isVolume }
        if (candidates.size == 1) return candidates[0].index
        if (chapter in newChapters.indices) {
            val sameIdx = newChapters[chapter]
            if (sameIdx.isVolume == oldCh.isVolume && editionKey(sameIdx.title) == editionKey(oldCh.title)) {
                return chapter
            }
        }
        return null
    }

    private fun fallbackPointWhenOldUnreadable(chapter: Int, offset: Int): Point? {
        val oldCh = oldChapters.getOrNull(chapter) ?: return null
        val targetIndex = resolveUnreadableTargetChapter(chapter) ?: return null
        val newText = text(true, targetIndex)?.value ?: return null
        return if (oldCh.charCount == newChapters[targetIndex].charCount && offset in 0..newText.length) {
            Point(targetIndex, offset)
        } else null
    }

    private fun fallbackRangesWhenOldUnreadable(chapter: Int, start: Int, end: Int, expected: String): List<Range>? {
        val compactExpected = compact(expected)
        if (start < 0 || end <= start || compactExpected.isEmpty()) return null
        val oldCh = oldChapters.getOrNull(chapter) ?: return null
        val primaryIndex = resolveUnreadableTargetChapter(chapter)
        if (primaryIndex != null) {
            val newText = text(true, primaryIndex)?.value
            if (newText != null) {
                if (end <= newText.length && compact(newText.substring(start, end)) == compactExpected) {
                    return listOf(Range(primaryIndex, start, end, newText.substring(start, end)))
                }
                val found = newText.indexOf(expected)
                if (found >= 0 && newText.indexOf(expected, found + 1) < 0) {
                    return listOf(Range(primaryIndex, found, found + expected.length, expected))
                }
            }
        }

        // 多卷同名章且下标发生前插/删除偏移时：在全部同名章节中寻找唯一精确命中的原文段落
        val candidates = titles[editionKey(oldCh.title)].orEmpty().filter { it.isVolume == oldCh.isVolume }
        val uniqueHits = candidates.mapNotNull { candidate ->
            val newText = text(true, candidate.index)?.value ?: return@mapNotNull null
            val first = newText.indexOf(expected)
            if (first < 0 || newText.indexOf(expected, first + 1) >= 0) null
            else Range(candidate.index, first, first + expected.length, expected)
        }
        return uniqueHits.singleOrNull()?.let { listOf(it) }
    }

    private fun targets(chapter: Int): Targets {
        val old = oldChapters.getOrNull(chapter) ?: return Targets(emptyList(), null)
        if (oldBook.format == BookFormat.EPUB && relation != EditionRelation.UNKNOWN) {
            val index = chapter.takeIf { it in newChapters.indices }
            return Targets(listOfNotNull(index), index)
        }
        val oldTextValue = text(false, chapter)?.value
        val byTitle = titles[editionKey(old.title)].orEmpty().filter { it.isVolume == old.isVolume }.map { it.index }
        val sameIndex = chapter.takeIf { it in newChapters.indices &&
            oldTextValue?.let { oldText -> oldText.isNotBlank() && oldText == text(true, it)?.value } == true }
        val extraIndex = if (oldBook.format == BookFormat.EPUB && chapter in newChapters.indices) listOf(chapter) else emptyList()
        val indices = (byTitle + listOfNotNull(sameIndex) + extraIndex).distinct()
        val section = "第 " + (chapter + 1) + " 节"
        val syntheticTitle = old.title == section || old.title == "$section · 自动"
        var fallback = indices.singleOrNull()?.takeIf { it == sameIndex || !syntheticTitle }
        if (fallback == null) {
            if (oldBook.format == BookFormat.EPUB && chapter in newChapters.indices &&
                (oldTextValue == null || epubSpineAligned)
            ) {
                fallback = chapter
            } else if (oldTextValue == null) {
                fallback = resolveUnreadableTargetChapter(chapter)?.takeIf { !syntheticTitle }
            }
        }
        return Targets(indices, fallback)
    }

    private fun txtPoint(chapter: Int, offset: Int, old: Text): Point? {
        val source = old.txtMap?.sourceAt(offset) ?: return null
        val globalChar = oldStartChars[chapter] + source
        val target = newTxtChapterByChar(globalChar) ?: return null
        val targetStartChar = newStartChars[target.index]
        // 旧末尾恰好紧邻新章时先尝试旧章结尾，避免跳到新增章。
        val indices = if (globalChar == targetStartChar && target.index > 0 && offset == old.value.length) {
            listOf(target.index - 1, target.index)
        } else listOf(target.index)
        return indices.firstNotNullOfOrNull { index ->
            val new = text(true, index) ?: return@firstNotNullOfOrNull null
            val relativeChar = (globalChar - newStartChars[index]).toInt()
            val newBody = checkNotNull(new.body)
            if (relativeChar !in 0..newBody.length) return@firstNotNullOfOrNull null
            new.txtMap?.domAt(relativeChar)?.let { Point(index, it) }
        }
    }

    private fun newTxtChapterByChar(globalChar: Long): ChapterEntity? {
        if (newChapters.isEmpty() || globalChar < 0L) return null
        var lo = 0
        var hi = newChapters.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (newStartChars[mid] <= globalChar) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < 0) return null
        val endChar = newStartChars[found] + newChapters[found].charCount
        return newChapters[found].takeIf { globalChar <= endChar }
    }

    private fun text(new: Boolean, index: Int): Text? {
        if (!new && oldSourceOverwritten) return null
        val key = new to index
        if (!texts.containsKey(key)) {
            val book = if (new) newBook else oldBook
            val chapter = (if (new) newChapters else oldChapters).getOrNull(index) ?: return null
            texts[key] = when (book.format) {
                BookFormat.TXT -> textProvider.readTxtTextOrNull(book, chapter)?.let { body ->
                    val map = ChapterTextMap(body, chapter.title, chapter.titleLineChars, chapter.isVolume, 0)
                    Text(map.text, map, body)
                }
                else -> chapter.href?.let { textProvider.readEpubHtml(book, it) }
                    ?.let(::epubReaderText)?.let { Text(it) }
            }
        }
        return texts[key]
    }

    companion object {
        private val blocks = setOf("p", "div", "li", "dd", "dt", "blockquote", "section", "article", "h1",
            "h2", "h3", "h4", "h5", "h6", "tr", "td", "th", "figcaption", "pre", "body")

        private fun prefixCharStarts(chapters: List<ChapterEntity>): LongArray {
            val starts = LongArray(chapters.size)
            var sum = 0L
            for (i in chapters.indices) {
                starts[i] = sum
                sum += chapters[i].charCount.coerceAtLeast(0L)
            }
            return starts
        }

        /** 与 reader/runtime.js 的 walkText 一致，不能用搜索用的纯文本作为 EPUB 定位坐标。 */
        internal fun epubReaderText(html: String): String? {
            val wrapped = ChapterHtmlBuilder.wrapBody(html.replace("\r\n", "\n").replace('\r', '\n'), 0)
            val root = Jsoup.parse(wrapped).select("#mirad-content").singleOrNull() ?: return null
            if (root.selectFirst("script, style, template, noscript, svg, math") != null) return null
            val text = StringBuilder()
            var lastBlock: Element? = null
            NodeTraversor.traverse({ node, _ ->
                if (node is TextNode && node.wholeText.isNotEmpty()) {
                    var block = node.parent()
                    while (block is Element && block !== root && block.normalName() !in blocks) block = block.parent()
                    if (block !== lastBlock && text.isNotEmpty()) text.append('\n')
                    lastBlock = block as? Element
                    text.append(node.wholeText)
                }
            }, root)
            return text.toString()
        }

        internal fun locateOffset(old: String, new: String, offset: Int): Int? {
            if (offset !in 0..old.length) return null
            if (old == new) return offset
            val start = (offset - 24).coerceAtLeast(0)
            val end = (offset + 32).coerceAtMost(old.length)
            val context = old.substring(start, end)
            if (context.count { !it.isWhitespace() } < 8) return null
            val found = new.indexOf(context)
            return (found + offset - start).takeIf { found >= 0 && new.indexOf(context, found + 1) < 0 }
        }

        private fun compact(value: String) = value.filterNot(Char::isWhitespace)
    }
}
