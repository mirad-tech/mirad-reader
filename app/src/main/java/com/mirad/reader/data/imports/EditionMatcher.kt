package com.mirad.reader.data.imports

import com.mirad.reader.core.epub.ZipEntryReader
import com.mirad.reader.core.html.Html
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
    val normalizedModified: Long?
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
    private val chapters: suspend (Long) -> List<ChapterEntity>
) {
    constructor(chapters: suspend (Long) -> List<ChapterEntity>) : this(BookContentAccess(), chapters)
    private data class SampleKey(val path: String, val bytes: Long, val modified: Long, val encoding: String?)
    private val txtSamples = mutableMapOf<SampleKey, String?>()
    private val epubSamples = mutableMapOf<SampleKey, EpubChapterSignature?>()

    suspend fun match(
        incoming: BookEntity,
        displayTitle: String,
        epub: List<EpubChapterSignature>?,
        shelf: List<BookEntity>
    ): EditionMatch {
        val epubOpening = epub?.firstOrNull { it.readableText }
        val txtOpening = if (incoming.format == BookFormat.TXT) txtSample(incoming) else null
        val titles = setOf(editionKey(incoming.title), editionKey(displayTitle)) - ""
        val author = editionKey(incoming.author.orEmpty())
        val candidates = mutableListOf<EditionCandidate>()
        for (old in shelf) {
            currentCoroutineContext().ensureActive()
            if (old.format != incoming.format) continue
            val sameUnderlyingSource = old.filePath == incoming.filePath &&
                old.contentPath.isNullOrBlank() && incoming.contentPath.isNullOrBlank()
            val titleMatch = editionKey(old.title) in titles
            val sourceMatch = (incoming.sourceUri != null && incoming.sourceUri == old.sourceUri) || sameUnderlyingSource
            val oldAuthor = editionKey(old.author.orEmpty())
            val authorConflict = author.isNotEmpty() && oldAuthor.isNotEmpty() && author != oldAuthor
            val normalizedModified = versionModified(contentAccess, old)

            // 同一外部 URI 原地被用户替换文件后重新导入：旧文件的原始内容已变成新内容，按已入库元数据区分未变与已变
            if (sameUnderlyingSource) {
                val unchanged = old.fileSize == incoming.fileSize &&
                    old.totalChapters == incoming.totalChapters &&
                    old.totalChars == incoming.totalChars
                val relation = if (unchanged) EditionRelation.SAME else EditionRelation.UNKNOWN
                val candidate = EditionCandidate(old, relation, authorConflict, normalizedModified)
                if (!authorConflict && relation == EditionRelation.SAME) {
                    return EditionMatch(duplicate = candidate)
                }
                candidates += candidate
                continue
            }

            val (openingMatches, relation) = if (old.format == BookFormat.TXT) {
                val opening = txtSample(old)
                val matches = !opening.isNullOrBlank() && !txtOpening.isNullOrBlank() &&
                    (opening.startsWith(txtOpening) || txtOpening.startsWith(opening))
                matches to if (matches) compareTxt(old, incoming) else EditionRelation.UNKNOWN
            } else {
                val opening = if (epubOpening != null) epubSample(old) else null
                val matches = opening != null && opening == epubOpening
                matches to if (matches) compareEpub(old, checkNotNull(epub)) else EditionRelation.UNKNOWN
            }
            val candidate = EditionCandidate(old, relation, authorConflict, normalizedModified)
            if (!authorConflict && (relation == EditionRelation.SAME || relation == EditionRelation.OLDER)) {
                return EditionMatch(duplicate = candidate)
            }
            if (titleMatch || sourceMatch || openingMatches) candidates += candidate
        }
        return EditionMatch(candidates = candidates)
    }

    private fun txtSample(book: BookEntity): String? {
        val targetPath = book.contentPath?.takeIf { it.isNotBlank() } ?: book.filePath
        val size = contentAccess.size(targetPath)
        if (size < 0L) return null
        val modified = versionModified(contentAccess, book) ?: 0L
        val key = SampleKey(targetPath, size, modified, book.encoding)
        if (key !in txtSamples) {
            txtSamples[key] = try {
                openNormalizedBookReader(book).buffered().use { reader ->
                    val head = CharArray(1024)
                    val count = reader.read(head)
                    if (count > 0) String(head, 0, count).trimEnd() else null
                }
            } catch (_: Exception) {
                null
            }
        }
        return txtSamples[key]
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

    private suspend fun compareTxt(old: BookEntity, incoming: BookEntity): EditionRelation {
        return try {
            compareReaders(
                openFirst = { BufferedReader(openNormalizedBookReader(old)) },
                openSecond = { BufferedReader(openNormalizedBookReader(incoming)) }
            )
        } catch (_: Exception) {
            EditionRelation.UNKNOWN
        }
    }

    private suspend fun epubSample(book: BookEntity): EpubChapterSignature? {
        val size = contentAccess.size(book.filePath)
        if (size < 0L) return null
        val modified = contentAccess.lastModified(book.filePath) ?: 0L
        val key = SampleKey(book.filePath, size, modified, null)
        if (key !in epubSamples) epubSamples[key] = try {
            val spine = chapters(book.id)
            contentAccess.openZip(book.filePath).use { zip ->
                spine.firstOrNull { it.charCount > 0L }?.let { signature(zip, it) }?.takeIf { it.readableText }
            }
        } catch (_: Exception) { null }
        return epubSamples[key]
    }

    private suspend fun compareEpub(old: BookEntity, incoming: List<EpubChapterSignature>): EditionRelation {
        return try {
            val spine = chapters(old.id)
            contentAccess.openZip(old.filePath).use { zip ->
                val shared = minOf(spine.size, incoming.size)
                for (index in 0 until shared) {
                    currentCoroutineContext().ensureActive()
                    if (signature(zip, spine[index]) != incoming[index]) return EditionRelation.UNKNOWN
                }
                if ((0 until shared).none { incoming[it].readableText }) return EditionRelation.UNKNOWN
                when {
                    spine.size == incoming.size -> EditionRelation.SAME
                    spine.size < incoming.size -> {
                        if ((shared until incoming.size).any { incoming[it].readableText }) EditionRelation.EXTENDS
                        else EditionRelation.UNKNOWN
                    }
                    else -> {
                        for (index in shared until spine.size) {
                            currentCoroutineContext().ensureActive()
                            val chapter = signature(zip, spine[index]) ?: return EditionRelation.UNKNOWN
                            if (chapter.readableText) return EditionRelation.OLDER
                        }
                        EditionRelation.UNKNOWN
                    }
                }
            }
        } catch (_: Exception) { EditionRelation.UNKNOWN }
    }

    private fun signature(zip: ZipEntryReader, chapter: ChapterEntity): EpubChapterSignature? {
        val href = chapter.href ?: return null
        val html = zip.readText(href, maxBytes = ZipEntryReader.MAX_CHAPTER_BYTES) ?: return null
        return EpubChapterSignature.of(Html.toPlainText(html))
    }

    companion object {
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
