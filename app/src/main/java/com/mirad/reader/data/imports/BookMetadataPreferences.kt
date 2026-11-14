package com.mirad.reader.data.imports

import com.mirad.reader.data.db.dao.PreferenceDao
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.db.entity.PreferenceEntity
import com.mirad.reader.data.db.entity.ProgressEntity
import com.mirad.reader.data.io.BookContentAccess
import java.net.URLDecoder
import java.util.UUID

/**
 * 基于 `preferences` 表的书籍自定义元数据标记（遵循不修改 Room 表结构的不变量）：
 * - 记录用户是否在书架手动编辑过书名或作者；
 * - 更新或重新关联书籍时，保留用户手动改过的书名/作者，未手动改过的默认书名则随新文件同步更新。
 */
internal class BookCustomMetaStore(private val preferences: PreferenceDao?) {

    data class CustomMeta(
        val titleCustomized: Boolean = false,
        val authorCustomized: Boolean = false
    )

    data class ResolvedMeta(
        val title: String,
        val author: String?,
        val keptCustomTitle: Boolean,
        val keptCustomAuthor: Boolean
    )

    suspend fun get(bookId: Long): CustomMeta {
        val raw = preferences?.get(key(bookId)) ?: return CustomMeta()
        return decode(raw)
    }

    suspend fun recordEdit(book: BookEntity, newTitle: String, newAuthor: String?) {
        val dao = preferences ?: return
        val prev = get(book.id)
        val normTitle = newTitle.trim().ifBlank { book.title }
        val normNewAuthor = newAuthor?.trim()?.ifBlank { null }
        val normOldAuthor = book.author?.trim()?.ifBlank { null }
        val next = CustomMeta(
            titleCustomized = prev.titleCustomized || normTitle != book.title,
            authorCustomized = prev.authorCustomized || normNewAuthor != normOldAuthor
        )
        if (next.titleCustomized || next.authorCustomized) {
            dao.put(PreferenceEntity(key(book.id), encode(next), System.currentTimeMillis()))
        }
    }

    suspend fun remove(bookId: Long) {
        preferences?.remove(key(bookId))
    }

    suspend fun resolve(
        oldBook: BookEntity,
        incomingTitle: String,
        incomingAuthor: String?
    ): ResolvedMeta {
        val stored = get(oldBook.id)
        return resolveWithStored(oldBook, incomingTitle, incomingAuthor, stored)
    }

    companion object {
        private const val KEY_PREFIX = "book_custom_meta_v1_"

        fun key(bookId: Long): String = "$KEY_PREFIX$bookId"

        internal fun encode(meta: CustomMeta): String =
            "t=${if (meta.titleCustomized) 1 else 0}|a=${if (meta.authorCustomized) 1 else 0}"

        internal fun decode(raw: String): CustomMeta {
            var t = false
            var a = false
            for (part in raw.split('|')) {
                when (part.trim()) {
                    "t=1" -> t = true
                    "a=1" -> a = true
                }
            }
            return CustomMeta(t, a)
        }

        internal fun resolveWithStored(
            oldBook: BookEntity,
            incomingTitle: String,
            incomingAuthor: String?,
            stored: CustomMeta
        ): ResolvedMeta {
            val keepOldTitle = stored.titleCustomized
            val finalTitle = if (keepOldTitle) {
                oldBook.title
            } else {
                incomingTitle.trim().ifBlank { oldBook.title }
            }

            val normIncomingAuthor = incomingAuthor?.trim()?.ifBlank { null }
            val keepOldAuthor = stored.authorCustomized ||
                (oldBook.format == BookFormat.TXT && !oldBook.author.isNullOrBlank() && normIncomingAuthor == null)
            val finalAuthor = if (keepOldAuthor) {
                oldBook.author
            } else {
                normIncomingAuthor ?: oldBook.author
            }
            return ResolvedMeta(
                title = finalTitle,
                author = finalAuthor,
                keptCustomTitle = keepOldTitle && finalTitle != incomingTitle.trim(),
                keptCustomAuthor = keepOldAuthor
            )
        }

        internal fun extractFileNameStem(uriOrPath: String): String? {
            val withoutQuery = uriOrPath.substringBefore('?').substringBefore('#')
            val decoded = runCatching {
                URLDecoder.decode(withoutQuery.replace("+", "%2B"), Charsets.UTF_8.name())
            }.getOrDefault(withoutQuery)
            val normalized = decoded.replace('\\', '/')
            val afterSlash = normalized.substringAfterLast('/')
            val fileName = afterSlash.substringAfterLast(':').trim()
            if (fileName.isEmpty()) return null
            val stem = fileName.substringBeforeLast('.').trim()
            if (stem.isEmpty() || stem.equals("original", ignoreCase = true) || stem.equals("shared-file", ignoreCase = true)) {
                return null
            }
            if (runCatching { UUID.fromString(stem).toString().equals(stem, ignoreCase = true) }.getOrDefault(false)) {
                return null
            }
            return stem
        }
    }
}

/**
 * 基于 `preferences` 表的书籍源文件轻量指纹快照（`size|lastModified|fingerprint`）：
 * 1. 「导入文件夹」重复扫描同一未改动 URI 时，无需全书解码/解压即可毫秒级跳过；
 * 2. 同一外部 URI 被原地等长修改（字节数、章数、字数恰好不变）时，可精准识别文件已变更。
 */
internal class BookSourceStampStore(private val preferences: PreferenceDao?) {

    data class SourceStamp(
        val size: Long,
        val lastModified: Long,
        val fingerprint: String
    )

    suspend fun get(bookId: Long): SourceStamp? {
        val raw = preferences?.get(key(bookId)) ?: return null
        return decode(raw)
    }

    suspend fun save(bookId: Long, stamp: SourceStamp?) {
        if (stamp == null) return
        preferences?.put(PreferenceEntity(key(bookId), encode(stamp), System.currentTimeMillis()))
    }

    suspend fun remove(bookId: Long) {
        preferences?.remove(key(bookId))
    }

    companion object {
        private const val KEY_PREFIX = "book_source_stamp_v1_"

        fun key(bookId: Long): String = "$KEY_PREFIX$bookId"

        fun capture(access: BookContentAccess, path: String, fallbackSize: Long = -1L): SourceStamp? {
            if (path.isBlank()) return null
            val size = access.size(path).let { if (it >= 0L) it else fallbackSize }
            if (size <= 0L) return null
            val modified = access.lastModified(path) ?: 0L
            val fp = FileFingerprint.of(access, path) ?: return null
            return SourceStamp(size, modified, fp)
        }

        fun matches(current: SourceStamp, saved: SourceStamp): Boolean {
            if (current.size != saved.size || current.fingerprint != saved.fingerprint) return false
            if (saved.lastModified > 0L && current.lastModified > 0L && current.lastModified != saved.lastModified) {
                return false
            }
            return true
        }

        internal fun encode(stamp: SourceStamp): String =
            "${stamp.size}|${stamp.lastModified}|${stamp.fingerprint}"

        internal fun decode(raw: String): SourceStamp? {
            val parts = raw.split('|', limit = 3)
            if (parts.size != 3) return null
            val size = parts[0].toLongOrNull() ?: return null
            val modified = parts[1].toLongOrNull() ?: return null
            val fp = parts[2].trim().takeIf { it.isNotEmpty() } ?: return null
            return SourceStamp(size, modified, fp)
        }
    }
}

/**
 * 更新或重新关联书籍后的「已读完」状态校准：
 * 只要新版章节数或总字数增加（有续章/新增内容），或映射后的续读位置不在末章，自动解除已读完状态。
 */
internal fun resolveFinishedAfterUpdate(
    wasFinished: Boolean,
    oldChaptersCount: Int,
    oldTotalChars: Long,
    newChapters: List<ChapterEntity>,
    newTotalChars: Long,
    mappedProgress: ProgressEntity?
): Boolean {
    if (!wasFinished || newChapters.isEmpty()) return false
    if (newChapters.size > oldChaptersCount || newTotalChars > oldTotalChars) return false
    if (mappedProgress != null && mappedProgress.chapterIndex < newChapters.lastIndex) return false
    return true
}
