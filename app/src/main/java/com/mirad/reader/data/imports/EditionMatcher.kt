package com.mirad.reader.data.imports

import com.mirad.reader.core.epub.ZipEntryReader
import com.mirad.reader.core.html.Html
import com.mirad.reader.core.text.ChapterDetector
import com.mirad.reader.core.text.ChapterRules
import com.mirad.reader.core.text.StreamingTxtProcessor
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.io.BookContentAccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.Reader
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

internal enum class EditionRelation { SAME, EXTENDS, OLDER, UNKNOWN }

internal data class EpubChapterSignature(val digest: List<Byte>, val readableText: Boolean) {
    companion object {
        fun of(text: String): EpubChapterSignature {
            val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
            val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).asList()
            return EpubChapterSignature(digest, normalized.isNotEmpty())
        }
    }
}

internal data class EditionCandidate(
    val book: BookEntity,
    val relation: EditionRelation,
    val authorConflict: Boolean,
    val normalizedModified: Long?,
    val exactCharPrefix: Boolean = false
)

internal data class EditionMatch(
    val duplicate: EditionCandidate? = null,
    val candidates: List<EditionCandidate> = emptyList()
) {
    val automatic: EditionCandidate?
        get() = candidates.singleOrNull()?.takeIf { it.relation == EditionRelation.EXTENDS && !it.authorConflict }
}

/** 小样本只用于筛选。自动更新或跳过必须有完整的正文证据。 */
internal class EditionMatcher(
    private val contentAccess: BookContentAccess,
    private val chapters: suspend (Long) -> List<ChapterEntity>,
    private val sourceVersions: BookSourceVersionStore? = null
) {
    constructor(chapters: suspend (Long) -> List<ChapterEntity>) : this(BookContentAccess(), chapters, null)

    private data class SampleKey(val path: String, val bytes: Long, val modified: Long, val encoding: String?)
    private data class TxtSample(val head: String, val firstChapterTitle: String?, val firstChapterAnchor: String?)
    private data class TxtComparison(val relation: EditionRelation, val exactCharPrefix: Boolean)

    private val txtSamples = mutableMapOf<SampleKey, TxtSample?>()
    private val epubSamples = mutableMapOf<SampleKey, List<EpubChapterSignature>>()

    suspend fun match(
        incoming: BookEntity,
        displayTitle: String,
        epub: List<EpubChapterSignature>?,
        shelf: List<BookEntity>,
        incomingVersion: SourceVersion
    ): EditionMatch {
        val epubOpening = epub?.filter { it.readableText }?.take(EPUB_SAMPLE_CHAPTERS).orEmpty()
        val txtOpening = if (incoming.format == BookFormat.TXT) txtSample(incoming) else null
        val exactTitles = setOf(editionKey(incoming.title), editionKey(displayTitle)) - ""
        val coreTitles = setOf(coreTitleKey(incoming.title), coreTitleKey(displayTitle))
            .filter { it.length >= 2 }
            .toSet()
        val author = editionKey(incoming.author.orEmpty())
        val candidates = mutableListOf<EditionCandidate>()
        for (old in shelf) {
            currentCoroutineContext().ensureActive()
            if (old.format != incoming.format) continue
            val sameUnderlyingSource = old.filePath == incoming.filePath &&
                old.contentPath.isNullOrBlank() && incoming.contentPath.isNullOrBlank()
            val oldExactTitle = editionKey(old.title)
            val oldCoreTitle = coreTitleKey(old.title)
            val titleMatch = oldExactTitle in exactTitles ||
                (oldCoreTitle.length >= 2 && oldCoreTitle in coreTitles)
            val sourceMatch = (incoming.sourceUri != null && incoming.sourceUri == old.sourceUri) || sameUnderlyingSource
            val oldAuthor = editionKey(old.author.orEmpty())
            val authorConflict = author.isNotEmpty() && oldAuthor.isNotEmpty() && author != oldAuthor
            val normalizedModified = versionModified(contentAccess, old)

            // 同一外部 URI 只有已保存的完整版本与本次解析内容一致时才可判重。
            if (sameUnderlyingSource) {
                val metaUnchanged = old.fileSize == incoming.fileSize &&
                    old.totalChapters == incoming.totalChapters &&
                    old.totalChars == incoming.totalChars
                val savedVersion = sourceVersions?.get(old.id)
                val unchanged = metaUnchanged && savedVersion == incomingVersion
                val relation = if (unchanged) EditionRelation.SAME else EditionRelation.UNKNOWN
                val candidate = EditionCandidate(old, relation, authorConflict, normalizedModified)
                if (!authorConflict && relation == EditionRelation.SAME) {
                    return EditionMatch(duplicate = candidate)
                }
                candidates += candidate
                continue
            }

            val openingMatches: Boolean
            val relation: EditionRelation
            val exactCharPrefix: Boolean
            if (old.format == BookFormat.TXT) {
                val opening = txtSample(old)
                openingMatches = txtOpeningMatches(opening, txtOpening)
                if (openingMatches) {
                    val cmp = compareTxtDetailed(old, incoming)
                    relation = cmp.relation
                    exactCharPrefix = cmp.exactCharPrefix
                } else {
                    relation = EditionRelation.UNKNOWN
                    exactCharPrefix = false
                }
            } else {
                val opening = if (epubOpening.isNotEmpty()) epubSample(old) else emptyList()
                openingMatches = opening.isNotEmpty() && opening.any { it in epubOpening }
                relation = if (openingMatches) compareEpub(old, checkNotNull(epub)) else EditionRelation.UNKNOWN
                exactCharPrefix = false
            }
            val candidate = EditionCandidate(old, relation, authorConflict, normalizedModified, exactCharPrefix)
            if (!authorConflict && (relation == EditionRelation.SAME || relation == EditionRelation.OLDER)) {
                return EditionMatch(duplicate = candidate)
            }
            if (titleMatch || sourceMatch || openingMatches) candidates += candidate
        }
        return EditionMatch(candidates = candidates)
    }

    /** 比较同批次中两个待入库版本的先后关系，用于批次内去重归并。 */
    suspend fun compareIncoming(
        first: BookEntity,
        firstChapters: List<ChapterEntity>,
        firstEpub: List<EpubChapterSignature>?,
        second: BookEntity,
        secondEpub: List<EpubChapterSignature>?
    ): EditionRelation {
        if (first.format != second.format) return EditionRelation.UNKNOWN
        return if (first.format == BookFormat.TXT) {
            compareTxt(first, second)
        } else {
            if (firstEpub == null || secondEpub == null) EditionRelation.UNKNOWN
            else compareEpubSignatures(firstChapters, firstEpub, secondEpub)
        }
    }

    private fun txtSample(book: BookEntity): TxtSample? {
        val targetPath = book.contentPath?.takeIf { it.isNotBlank() } ?: book.filePath
        val size = contentAccess.size(targetPath)
        if (size < 0L) return null
        val modified = versionModified(contentAccess, book) ?: 0L
        val key = SampleKey(targetPath, size, modified, book.encoding)
        if (key !in txtSamples) {
            txtSamples[key] = try {
                openNormalizedBookReader(book).buffered().use { reader ->
                    val buf = CharArray(TXT_SAMPLE_WINDOW_CHARS)
                    var total = 0
                    while (total < buf.size) {
                        val n = reader.read(buf, total, buf.size - total)
                        if (n <= 0) break
                        total += n
                    }
                    if (total > 0) buildTxtSample(String(buf, 0, total)) else null
                }
            } catch (_: Exception) {
                null
            }
        }
        return txtSamples[key]
    }

    private fun buildTxtSample(window: String): TxtSample? {
        val head = window.take(1024).trimEnd()
        if (head.isBlank()) return null
        var pos = 0
        var firstTitle: String? = null
        var firstTitlePos = -1
        while (pos < window.length) {
            val nl = window.indexOf('\n', pos)
            val end = if (nl < 0) window.length else nl
            val trimmed = window.substring(pos, end).trim()
            if (trimmed.isNotEmpty() && ChapterDetector.isTitleCandidate(trimmed) && !ChapterDetector.isVolumeTitle(trimmed)) {
                firstTitle = editionKey(trimmed)
                firstTitlePos = pos
                break
            }
            if (nl < 0) break
            pos = nl + 1
        }
        val anchorSource = if (firstTitlePos >= 0) window.substring(firstTitlePos) else window
        val anchor = compactAnchor(anchorSource, maxChars = 256).takeIf { it.length >= MIN_ANCHOR_CHARS }
        return TxtSample(head, firstTitle, anchor)
    }

    private fun txtOpeningMatches(old: TxtSample?, incoming: TxtSample?): Boolean {
        if (old == null || incoming == null) return false
        if (old.head.isNotBlank() && incoming.head.isNotBlank() &&
            (old.head.startsWith(incoming.head) || incoming.head.startsWith(old.head))
        ) {
            return true
        }
        val a = old.firstChapterAnchor
        val b = incoming.firstChapterAnchor
        if (a != null && b != null) {
            val checkLen = minOf(a.length, b.length, 64)
            if (checkLen >= MIN_ANCHOR_CHARS && a.regionMatches(0, b, 0, checkLen)) {
                return true
            }
        }
        return false
    }

    private fun openNormalizedBookReader(book: BookEntity): Reader {
        val legacyPath = book.contentPath
        if (!legacyPath.isNullOrBlank()) {
            return File(legacyPath).reader(Charsets.UTF_8)
        }
        return StreamingTxtProcessor.openNormalizedReader(
            openStream = { contentAccess.openStream(book.filePath) },
            charsetName = book.encoding
        )
    }

    private suspend fun compareTxt(old: BookEntity, incoming: BookEntity): EditionRelation =
        compareTxtDetailed(old, incoming).relation

    private suspend fun compareTxtDetailed(old: BookEntity, incoming: BookEntity): TxtComparison {
        val exact = try {
            compareReaders(
                openFirst = { BufferedReader(openNormalizedBookReader(old)) },
                openSecond = { BufferedReader(openNormalizedBookReader(incoming)) }
            )
        } catch (_: Exception) {
            EditionRelation.UNKNOWN
        }
        if (exact != EditionRelation.UNKNOWN) {
            return TxtComparison(exact, exactCharPrefix = exact == EditionRelation.EXTENDS)
        }

        // 兜底：若新旧版仅在首章之前的简介/整理声明或空白换行上有差异，从首个相同章节标题起做非空白流式比对
        val fallback = try {
            compareFromFirstChapterIgnoringWhitespace(
                openFirst = { BufferedReader(openNormalizedBookReader(old)) },
                openSecond = { BufferedReader(openNormalizedBookReader(incoming)) }
            )
        } catch (_: Exception) {
            EditionRelation.UNKNOWN
        }
        return TxtComparison(fallback, exactCharPrefix = false)
    }

    private suspend fun epubSample(book: BookEntity): List<EpubChapterSignature> {
        val size = contentAccess.size(book.filePath)
        if (size < 0L) return emptyList()
        val modified = contentAccess.lastModified(book.filePath) ?: 0L
        val key = SampleKey(book.filePath, size, modified, null)
        if (key !in epubSamples) epubSamples[key] = try {
            val spine = chapters(book.id)
            contentAccess.openZip(book.filePath).use { zip ->
                val list = ArrayList<EpubChapterSignature>(EPUB_SAMPLE_CHAPTERS)
                for (ch in spine) {
                    if (ch.charCount <= 0L) continue
                    val sig = signature(zip, ch) ?: continue
                    if (sig.readableText) {
                        list.add(sig)
                        if (list.size >= EPUB_SAMPLE_CHAPTERS) break
                    }
                }
                list
            }
        } catch (_: Exception) { emptyList() }
        return epubSamples[key].orEmpty()
    }

    private suspend fun compareEpub(old: BookEntity, incoming: List<EpubChapterSignature>): EditionRelation {
        return try {
            val spine = chapters(old.id)
            contentAccess.openZip(old.filePath).use { zip ->
                val oldSigs = ArrayList<EpubChapterSignature>(spine.size)
                for (ch in spine) {
                    currentCoroutineContext().ensureActive()
                    val sig = signature(zip, ch) ?: return EditionRelation.UNKNOWN
                    oldSigs.add(sig)
                }
                compareEpubSignatures(spine, oldSigs, incoming)
            }
        } catch (_: Exception) { EditionRelation.UNKNOWN }
    }

    private fun compareEpubSignatures(
        oldSpine: List<ChapterEntity>,
        oldSigs: List<EpubChapterSignature>,
        incoming: List<EpubChapterSignature>
    ): EditionRelation {
        val shared = minOf(oldSigs.size, incoming.size)
        if (shared == 0) return EditionRelation.UNKNOWN
        val mismatches = ArrayList<Int>(2)
        for (index in 0 until shared) {
            if (oldSigs[index] != incoming[index]) {
                mismatches.add(index)
                if (mismatches.size > 1) return EditionRelation.UNKNOWN
            }
        }
        if (mismatches.isEmpty()) {
            if ((0 until shared).none { incoming[it].readableText }) return EditionRelation.UNKNOWN
            return when {
                oldSigs.size == incoming.size -> EditionRelation.SAME
                oldSigs.size < incoming.size -> {
                    if ((shared until incoming.size).any { incoming[it].readableText }) EditionRelation.EXTENDS
                    else EditionRelation.UNKNOWN
                }
                else -> {
                    if ((shared until oldSigs.size).any { oldSigs[it].readableText }) EditionRelation.OLDER
                    else EditionRelation.UNKNOWN
                }
            }
        }

        // 仅第 0 个短前言/信息页（如“更新至第 xxx 章 / 制作日期”）变动，其余全部正文章节 100% 顺序一致且尾部新增可读章节
        if (mismatches.single() == 0 &&
            oldSigs.size >= 2 &&
            incoming.size > oldSigs.size &&
            isFrontMatterInfoPage(oldSpine.getOrNull(0)) &&
            (1 until shared).any { incoming[it].readableText } &&
            (shared until incoming.size).any { incoming[it].readableText }
        ) {
            return EditionRelation.EXTENDS
        }
        return EditionRelation.UNKNOWN
    }

    private fun isFrontMatterInfoPage(firstChapter: ChapterEntity?): Boolean {
        if (firstChapter == null || firstChapter.charCount > MAX_FRONT_MATTER_CHARS) return false
        val trimmed = firstChapter.title.trim()
        // 排除显式的正文章节名（如“第一章 xxx”、“Chapter 1”），保留无目录默认“第 1 节”或“制作说明/简介/版权”等前言短页
        if (trimmed != "第 1 节" && (
                STORY_CHAPTER_TITLE_REGEX.containsMatchIn(trimmed) ||
                    ChapterRules.ENGLISH_RULE.matches(trimmed)
            )
        ) {
            return false
        }
        return true
    }

    private fun signature(zip: ZipEntryReader, chapter: ChapterEntity): EpubChapterSignature? {
        val href = chapter.href ?: return null
        val html = zip.readText(href, maxBytes = ZipEntryReader.MAX_CHAPTER_BYTES) ?: return null
        return EpubChapterSignature.of(Html.toPlainText(html))
    }

    companion object {
        private const val TXT_SAMPLE_WINDOW_CHARS = 4096
        private const val MIN_ANCHOR_CHARS = 48
        private const val MIN_SHARED_COMPACT_CHARS = 64
        private const val EPUB_SAMPLE_CHAPTERS = 3
        private const val MAX_FRONT_MATTER_CHARS = 800L
        private val STORY_CHAPTER_TITLE_REGEX = Regex("^第\\s*[0-9０-９零〇一二三四五六七八九十百千万两]+\\s*[章节節回话]")

        internal fun versionModified(access: BookContentAccess, book: BookEntity): Long? {
            val legacy = book.contentPath?.takeIf { it.isNotBlank() }
            return if (legacy != null) File(legacy).takeIf { it.isFile }?.lastModified()
            else access.lastModified(book.filePath)
        }

        /** 仅忽略文件末尾空白，正文中间的差异不会被误判为续章。 */
        internal suspend fun compareTxt(old: File, incoming: File): EditionRelation =
            compareReaders({ old.bufferedReader() }, { incoming.bufferedReader() })

        internal suspend fun compareReaders(
            openFirst: () -> BufferedReader,
            openSecond: () -> BufferedReader
        ): EditionRelation {
            return try {
                val context = currentCoroutineContext()
                openFirst().use { a ->
                    openSecond().use { b ->
                        var compared = 0
                        var sharedText = false
                        var left = a.read()
                        var right = b.read()
                        while (left == right && left != -1) {
                            if (compared++ % 8192 == 0) context.ensureActive()
                            if (!left.toChar().isWhitespace()) sharedText = true
                            left = a.read()
                            right = b.read()
                        }
                        if (!sharedText) return EditionRelation.UNKNOWN
                        val oldHasTail = hasText(left, a, context::ensureActive)
                        val newHasTail = hasText(right, b, context::ensureActive)
                        when {
                            !oldHasTail && !newHasTail -> EditionRelation.SAME
                            !oldHasTail -> EditionRelation.EXTENDS
                            !newHasTail -> EditionRelation.OLDER
                            else -> EditionRelation.UNKNOWN
                        }
                    }
                }
            } catch (_: IOException) { EditionRelation.UNKNOWN }
        }

        /**
         * 跳过首章标题前的短简介/声明差异，从首个相同章节标题行起做忽略空白的流式续章核验：
         * 仅当首章及后续旧书全部非空白字符一字不差且新版末尾确有新增正文时返回 [EditionRelation.EXTENDS]（反向返回 [EditionRelation.OLDER]）。
         */
        internal suspend fun compareFromFirstChapterIgnoringWhitespace(
            openFirst: () -> BufferedReader,
            openSecond: () -> BufferedReader
        ): EditionRelation {
            return try {
                val context = currentCoroutineContext()
                openFirst().use { a ->
                    openSecond().use { b ->
                        val firstLineA = seekFirstChapterHeading(a) ?: return EditionRelation.UNKNOWN
                        val firstLineB = seekFirstChapterHeading(b) ?: return EditionRelation.UNKNOWN
                        if (editionKey(firstLineA) != editionKey(firstLineB)) return EditionRelation.UNKNOWN

                        val streamA = ChainedNonWhitespaceReader(firstLineA, a)
                        val streamB = ChainedNonWhitespaceReader(firstLineB, b)
                        var shared = 0
                        var left = streamA.nextNonWhitespace()
                        var right = streamB.nextNonWhitespace()
                        while (left == right && left != -1) {
                            if (++shared % 8192 == 0) context.ensureActive()
                            left = streamA.nextNonWhitespace()
                            right = streamB.nextNonWhitespace()
                        }
                        if (shared < MIN_SHARED_COMPACT_CHARS) return EditionRelation.UNKNOWN
                        return when {
                            left == -1 && right != -1 -> EditionRelation.EXTENDS
                            left != -1 && right == -1 -> EditionRelation.OLDER
                            else -> EditionRelation.UNKNOWN
                        }
                    }
                }
            } catch (_: IOException) { EditionRelation.UNKNOWN }
        }

        private fun seekFirstChapterHeading(reader: BufferedReader): String? {
            var scannedChars = 0
            while (scannedChars < TXT_SAMPLE_WINDOW_CHARS) {
                val line = reader.readLine() ?: return null
                scannedChars += line.length + 1
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() &&
                    ChapterDetector.isTitleCandidate(trimmed) &&
                    !ChapterDetector.isVolumeTitle(trimmed)
                ) {
                    return trimmed
                }
            }
            return null
        }

        private class ChainedNonWhitespaceReader(
            private val prefix: String,
            private val rest: BufferedReader
        ) {
            private var prefixPos = 0

            fun nextNonWhitespace(): Int {
                while (prefixPos < prefix.length) {
                    val ch = prefix[prefixPos++]
                    if (!isIgnorableBlank(ch)) return ch.code
                }
                while (true) {
                    val c = rest.read()
                    if (c == -1) return -1
                    if (!isIgnorableBlank(c.toChar())) return c
                }
            }
        }

        private fun isIgnorableBlank(ch: Char): Boolean =
            ch.isWhitespace() || ch == '\u3000' || ch == '\u00A0' || ch == '\uFEFF'

        private fun compactAnchor(source: String, maxChars: Int): String {
            val sb = StringBuilder(maxChars)
            for (ch in source) {
                if (Character.isLetterOrDigit(ch)) {
                    sb.append(ch.lowercaseChar())
                    if (sb.length >= maxChars) break
                }
            }
            return sb.toString()
        }

        private fun hasText(first: Int, input: BufferedReader, ensureActive: () -> Unit): Boolean {
            var c = first
            var read = 0
            while (c != -1) {
                if (read++ % 8192 == 0) ensureActive()
                if (!c.toChar().isWhitespace()) return true
                c = input.read()
            }
            return false
        }
    }
}

internal fun editionKey(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)

private val LEADING_TAG_REGEX = Regex("^(?:\\[[^\\[\\]]{1,12}]|【[^【】]{1,12}】)\\s*")
private val AUTHOR_SUFFIX_REGEX = Regex("\\s*(?:作者[:：\\s]*|by\\s+)[^\\s()（）\\[\\]【】《》]{1,24}$")
private val BRACKET_SUFFIX_REGEX = Regex(
    "\\s*(?:[(（\\[【][^()（）\\[\\]【】]*(?:章|节|卷|集|部|回|话|完结|未完|全本|全集|精校|校对|更新|连载|番外|修订|新版|旧版|\\d+\\s*[-~—至到]\\s*\\d+|v\\d+(?:\\.\\d+)*)[^()（）\\[\\]【】]*[)）\\]】])\\s*$"
)
private val TAIL_QUALIFIER_REGEX = Regex(
    "(?:[\\s_\\-—·]+(?:更新至.*|连载至.*|第?\\d+\\s*[-~—至到]\\s*第?\\d+[章节回集话]?|第?\\d+[章节回集话](?:完|止)?|第?\\d+版|精校(?:全本|版|\\d+)?|校对(?:全本|版|\\d+)?|全本|全集|完结版?|未完结?|修订(?:版|\\d+)?|新版|v\\d+(?:\\.\\d+)*)|\\s*(?:精校全本|精校版|校对全本|校对版|全本完结))$",
    RegexOption.IGNORE_CASE
)

/**
 * 提取剥离书名号、章节区间、版本标签与作者后缀的核心书名，仅用于候选召回筛选。
 */
internal fun coreTitleKey(value: String): String {
    var current = editionKey(value)
    if (current.isEmpty()) return ""

    // 若标题以《书名》开头（如“《剑来》精校版（1-800）”），优先提取书名号内部主干
    if (current.startsWith("《")) {
        val closeIdx = current.indexOf('》')
        if (closeIdx > 1) {
            val inside = current.substring(1, closeIdx).trim()
            if (inside.length >= 2) return inside
        }
    }

    current = current.replace(LEADING_TAG_REGEX, "").trim()
    repeat(4) {
        val next = current
            .replace(AUTHOR_SUFFIX_REGEX, "")
            .replace(BRACKET_SUFFIX_REGEX, "")
            .replace(TAIL_QUALIFIER_REGEX, "")
            .trim()
            .trim('_', '-', '—', '·')
            .trim()
        if (next == current || next.isEmpty()) return@repeat
        current = next
    }
    if (current.startsWith("《") && current.endsWith("》") && current.length > 2) {
        current = current.substring(1, current.length - 1).trim()
    }
    return if (current.length >= 2) current else editionKey(value)
}
