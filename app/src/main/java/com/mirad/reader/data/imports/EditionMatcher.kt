package com.mirad.reader.data.imports

import com.mirad.reader.core.epub.ZipEntryReader
import com.mirad.reader.core.html.Html
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.ChapterEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedReader
import java.io.File
import java.io.IOException
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
internal class EditionMatcher(private val chapters: suspend (Long) -> List<ChapterEntity>) {
    private data class SampleKey(val path: String, val bytes: Long, val modified: Long)
    private val txtSamples = mutableMapOf<SampleKey, String?>()
    private val epubSamples = mutableMapOf<SampleKey, EpubChapterSignature?>()

    suspend fun match(
        incoming: BookEntity,
        displayTitle: String,
        epub: List<EpubChapterSignature>?,
        shelf: List<BookEntity>
    ): EditionMatch {
        val epubOpening = epub?.firstOrNull { it.readableText }
        val txtOpening = if (incoming.format == BookFormat.TXT) txtSample(incoming.contentPath) else null
        val titles = setOf(editionKey(incoming.title), editionKey(displayTitle)) - ""
        val author = editionKey(incoming.author.orEmpty())
        val candidates = mutableListOf<EditionCandidate>()
        for (old in shelf) {
            currentCoroutineContext().ensureActive()
            if (old.format != incoming.format) continue
            val titleMatch = editionKey(old.title) in titles
            val sourceMatch = incoming.sourceUri != null && incoming.sourceUri == old.sourceUri
            val oldAuthor = editionKey(old.author.orEmpty())
            val authorConflict = author.isNotEmpty() && oldAuthor.isNotEmpty() && author != oldAuthor
            val normalizedModified = old.contentPath?.let { File(it).lastModified() }
            val (openingMatches, relation) = if (old.format == BookFormat.TXT) {
                val opening = txtSample(old.contentPath)
                val matches = !opening.isNullOrBlank() && !txtOpening.isNullOrBlank() &&
                    (opening.startsWith(txtOpening) || txtOpening.startsWith(opening))
                matches to if (matches) compareTxt(File(checkNotNull(old.contentPath)), File(checkNotNull(incoming.contentPath)))
                else EditionRelation.UNKNOWN
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

    private fun txtSample(path: String?): String? {
        val file = path?.let(::File) ?: return null
        val key = SampleKey(path, file.length(), file.lastModified())
        if (key !in txtSamples) txtSamples[key] = try {
            file.bufferedReader().use { reader ->
                val head = CharArray(1024)
                val count = reader.read(head)
                if (count > 0) String(head, 0, count).trimEnd() else null
            }
        } catch (_: IOException) { null }
        return txtSamples[key]
    }

    private suspend fun epubSample(book: BookEntity): EpubChapterSignature? {
        val file = File(book.filePath)
        val key = SampleKey(file.path, file.length(), file.lastModified())
        if (key !in epubSamples) epubSamples[key] = try {
            val spine = chapters(book.id)
            ZipEntryReader.open(file).use { zip ->
                spine.firstOrNull { it.charCount > 0L }?.let { signature(zip, it) }?.takeIf { it.readableText }
            }
        } catch (_: IOException) { null }
        return epubSamples[key]
    }

    private suspend fun compareEpub(old: BookEntity, incoming: List<EpubChapterSignature>): EditionRelation {
        return try {
            val spine = chapters(old.id)
            ZipEntryReader.open(File(old.filePath)).use { zip ->
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
        } catch (_: IOException) { EditionRelation.UNKNOWN }
    }

    private fun signature(zip: ZipEntryReader, chapter: ChapterEntity): EpubChapterSignature? {
        val href = chapter.href ?: return null
        val html = zip.readText(href, maxBytes = ZipEntryReader.MAX_CHAPTER_BYTES) ?: return null
        return EpubChapterSignature.of(Html.toPlainText(html))
    }

    companion object {
        /** 仅忽略文件末尾空白，正文中间的差异不会被误判为续章。 */
        internal suspend fun compareTxt(old: File, incoming: File): EditionRelation {
            return try {
                val context = currentCoroutineContext()
                old.bufferedReader().use { a ->
                    incoming.bufferedReader().use { b ->
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
