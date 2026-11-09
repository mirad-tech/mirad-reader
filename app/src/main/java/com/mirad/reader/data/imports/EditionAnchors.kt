package com.mirad.reader.data.imports

import com.mirad.reader.core.html.ChapterHtmlBuilder
import com.mirad.reader.core.model.ChapterTextMap
import com.mirad.reader.core.text.TextNormalizer
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
    private val textProvider: ChapterTextProvider = ChapterTextProvider()
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

    fun point(chapter: Int, offset: Int): Point? {
        val old = text(false, chapter) ?: return null
        if (offset !in 0..old.value.length) return null
        if (txtExtension) txtPoint(chapter, offset, old)?.let { return it }

        val targets = targets(chapter)
        val matches = targets.exact.mapNotNull { index ->
            val new = text(true, index) ?: return@mapNotNull null
            locateOffset(old.value, new.value, offset)?.let { Point(index, it) }
        }
        return matches.singleOrNull()
    }

    fun chapterStart(chapter: Int): Point? {
        if (txtExtension) {
            val start = oldChapters.getOrNull(chapter)?.startByte ?: return null
            return newTxtChapter(start)?.let { Point(it.index, 0) }
        }
        return targets(chapter).chapter?.let { Point(it, 0) }
    }

    fun ranges(chapter: Int, start: Int, end: Int, expected: String): List<Range>? {
        val old = text(false, chapter)?.value ?: return null
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

    private fun targets(chapter: Int): Targets {
        val old = oldChapters.getOrNull(chapter) ?: return Targets(emptyList(), null)
        if (oldBook.format == BookFormat.EPUB && relation != EditionRelation.UNKNOWN) {
            val index = chapter.takeIf { it in newChapters.indices }
            return Targets(listOfNotNull(index), index)
        }
        val byTitle = titles[editionKey(old.title)].orEmpty().filter { it.isVolume == old.isVolume }.map { it.index }
        val sameIndex = chapter.takeIf { it in newChapters.indices &&
            text(false, chapter)?.value?.let { oldText -> oldText.isNotBlank() && oldText == text(true, it)?.value } == true }
        val indices = (byTitle + listOfNotNull(sameIndex)).distinct()
        val section = "第 " + (chapter + 1) + " 节"
        val syntheticTitle = old.title == section || old.title == "$section · 自动"
        val fallback = indices.singleOrNull()?.takeIf { it == sameIndex || !syntheticTitle }
        return Targets(indices, fallback)
    }

    private fun txtPoint(chapter: Int, offset: Int, old: Text): Point? {
        val source = old.txtMap?.sourceAt(offset) ?: return null
        val body = checkNotNull(old.body)
        val chapterByte = oldChapters[chapter].startByte + TextNormalizer.utf8Length(body, 0, source)
        val target = newTxtChapter(chapterByte) ?: return null
        // 旧末尾恰好紧邻新章时先尝试旧章结尾，避免跳到新增章。
        val indices = if (chapterByte == target.startByte && target.index > 0 && offset == old.value.length) {
            listOf(target.index - 1, target.index)
        } else listOf(target.index)
        return indices.firstNotNullOfOrNull { index ->
            val new = text(true, index) ?: return@firstNotNullOfOrNull null
            val relative = chapterByte - newChapters[index].startByte
            val char = charAtByte(checkNotNull(new.body), relative) ?: return@firstNotNullOfOrNull null
            new.txtMap?.domAt(char)?.let { Point(index, it) }
        }
    }

    private fun newTxtChapter(byte: Long): ChapterEntity? {
        val index = newChapters.binarySearchBy(byte) { it.startByte }.let { if (it >= 0) it else -it - 2 }
        return newChapters.getOrNull(index)?.takeIf { byte <= it.endByte }
    }

    private fun text(new: Boolean, index: Int): Text? {
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

        /** 与 reader/runtime.js 的 walkText 一致，不能用搜索用的纯文本作为 EPUB 定位坐标。 */
        internal fun epubReaderText(html: String): String? {
            val wrapped = ChapterHtmlBuilder.wrapBody(html.replace("\r\n", "\n").replace('\r', '\n'), 0)
            val root = Jsoup.parse(wrapped).select("#mirad-content").singleOrNull() ?: return null
            // 这些节点在两个 DOM 实现中的文本表示不同，不能假装偏移仍然可信。
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

        private fun charAtByte(text: String, target: Long): Int? {
            var bytes = 0L
            var char = 0
            while (char < text.length && bytes < target) {
                val next = char + Character.charCount(text.codePointAt(char))
                bytes += TextNormalizer.utf8Length(text, char, next)
                char = next
            }
            return char.takeIf { bytes == target }
        }
    }
}
