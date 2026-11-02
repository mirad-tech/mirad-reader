package com.mirad.reader.data.imports

import android.content.ContextWrapper
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.dao.BookDao
import com.mirad.reader.data.db.dao.ChapterDao
import com.mirad.reader.data.db.dao.ProgressDao
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.db.entity.ProgressEntity
import com.mirad.reader.data.repo.BookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/** 真实 ImportPipeline 作出 DONE/SKIPPED 决定；DAO仅代替存储，不复制去重算法。 */
class ImportDuplicateContractTest {
    @get:Rule val temp = TemporaryFolder()

    private class TestContext(private val directory: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = directory
    }

    private class Books(initial: List<BookEntity>) : BookDao {
        val records = initial.associateByTo(linkedMapOf()) { it.id }
        private var nextId = (records.keys.maxOrNull() ?: 0L) + 1L
        private val flow = MutableStateFlow(records.values.toList())
        override suspend fun insert(book: BookEntity): Long {
            val id = nextId++
            records[id] = book.copy(id = id)
            return id
        }
        override suspend fun update(book: BookEntity) { records[book.id] = book }
        override suspend fun delete(book: BookEntity) { records.remove(book.id) }
        override suspend fun getById(id: Long) = records[id]
        override fun observeAll() = flow
        override suspend fun findDuplicateCandidates(title: String, fileSize: Long) =
            records.values.filter { it.title == title && it.fileSize == fileSize }.sortedBy { it.id }
        override suspend fun setPinned(id: Long, pinned: Boolean) { records[id]?.let { update(it.copy(isPinned = pinned)) } }
        override suspend fun setHidden(id: Long, hidden: Boolean) { records[id]?.let { update(it.copy(isHidden = hidden)) } }
        override suspend fun setGroup(id: Long, groupId: Long?) { records[id]?.let { update(it.copy(groupId = groupId)) } }
        override suspend fun touchLastRead(id: Long, time: Long) { records[id]?.let { update(it.copy(lastReadAt = time)) } }
        override suspend fun updateMeta(id: Long, title: String, author: String?) { records[id]?.let { update(it.copy(title = title, author = author)) } }
        override suspend fun setFinished(id: Long, finished: Boolean) { records[id]?.let { update(it.copy(finished = finished)) } }
        override suspend fun updateCounts(id: Long, chapters: Int, chars: Long) { records[id]?.let { update(it.copy(totalChapters = chapters, totalChars = chars)) } }
        override suspend fun updateIndexInfo(id: Long, chapters: Int, chars: Long, ruleId: String?, regex: String?, encoding: String?) {
            records[id]?.let { update(it.copy(totalChapters = chapters, totalChars = chars, chapterRuleId = ruleId, chapterRegex = regex, encoding = encoding)) }
        }
    }

    private class Chapters : ChapterDao {
        private val records = mutableListOf<ChapterEntity>()
        override suspend fun insertAll(chapters: List<ChapterEntity>) { records += chapters }
        override suspend fun deleteForBook(bookId: Long) { records.removeAll { it.bookId == bookId } }
        override suspend fun getForBook(bookId: Long) = records.filter { it.bookId == bookId }.sortedBy { it.index }
        override suspend fun getChapter(bookId: Long, index: Int) = records.firstOrNull { it.bookId == bookId && it.index == index }
        override suspend fun countForBook(bookId: Long) = records.count { it.bookId == bookId }
    }

    private class Progress : ProgressDao {
        private val records = mutableMapOf<Long, ProgressEntity>()
        override suspend fun upsert(progress: ProgressEntity) { records[progress.bookId] = progress }
        override suspend fun get(bookId: Long) = records[bookId]
        override fun observeAll() = MutableStateFlow(records.values.toList())
        override suspend fun deleteForBook(bookId: Long) { records.remove(bookId) }
    }

    private fun file(path: String, bytes: ByteArray): File = File(temp.root, path).apply {
        checkNotNull(parentFile).mkdirs()
        writeBytes(bytes)
    }

    private fun metadata(id: Long, file: File, size: Long = file.length()) = BookEntity(
        id = id, title = "book", format = BookFormat.TXT, filePath = file.absolutePath, fileSize = size
    )

    private fun pipeline(scope: CoroutineScope, books: Books): ImportPipeline {
        val context = TestContext(temp.newFolder())
        val paths = AppPaths(context)
        return ImportPipeline(context, paths, BookRepository(books, Chapters(), Progress(), paths), scope)
    }

    @Test fun differentUnsampledBytesAreImportedRatherThanSkipped() = runBlocking {
        val bytes = ByteArray(1024 * 1024) { 'a'.code.toByte() }
        val original = file("existing/original.txt", bytes)
        bytes[200000] = 'b'.code.toByte()
        val incoming = file("incoming/book.txt", bytes)
        assertEquals("夹具须复现采样相同的前提", FileFingerprint.of(original), FileFingerprint.of(incoming))
        val books = Books(listOf(metadata(1, original)))
        val summary = pipeline(this, books).importAndWait(listOf(ImportSource.FromFile(incoming)))
        assertEquals(1, summary.success)
        assertEquals(0, summary.skipped)
        assertEquals(2, books.records.size)
    }

    @Test fun identicalContentIsSkippedAndReportsTheExistingBook() = runBlocking {
        val bytes = "第一章\n同一本书的完整正文。\n".toByteArray()
        val original = file("existing/original.txt", bytes)
        val incoming = file("incoming/book.txt", bytes)
        val books = Books(listOf(metadata(4, original)))
        val summary = pipeline(this, books).importAndWait(listOf(ImportSource.FromFile(incoming)))
        assertEquals(0, summary.success)
        assertEquals(1, summary.skipped)
        assertEquals(listOf(4L), summary.bookIds)
        assertEquals(1, books.records.size)
    }

    @Test fun aLaterSameNameAndSizeCandidateCanBeTheActualDuplicate() = runBlocking {
        val first = file("existing/first.txt", "不同正文甲".toByteArray())
        val second = file("existing/second.txt", "相同正文乙".toByteArray())
        val incoming = file("incoming/book.txt", second.readBytes())
        assertEquals(first.length(), second.length())
        val books = Books(listOf(metadata(1, first), metadata(2, second)))
        val summary = pipeline(this, books).importAndWait(listOf(ImportSource.FromFile(incoming)))
        assertEquals(0, summary.success)
        assertEquals(1, summary.skipped)
        assertEquals(listOf(2L), summary.bookIds)
        assertEquals(2, books.records.size)
    }

    @Test fun unreadableCandidateDoesNotCauseAnIncomingBookToBeSkipped() = runBlocking {
        val incoming = file("incoming/book.txt", "第一章\n应当正常导入的正文。\n".toByteArray())
        val missing = File(temp.root, "missing/original.txt")
        val books = Books(listOf(metadata(1, missing, incoming.length())))
        val summary = pipeline(this, books).importAndWait(listOf(ImportSource.FromFile(incoming)))
        assertEquals(1, summary.success)
        assertEquals(0, summary.skipped)
    }

    @Test fun maximumSupportedFileCanBeConfirmedAsDuplicateBeforeParsing() = runBlocking {
        val original = file("existing/original.txt", byteArrayOf())
        RandomAccessFile(original, "rw").use { file ->
            file.setLength(ImportPipeline.MAX_TXT_BYTES)
            file.seek(200000)
            file.write("sample".toByteArray())
        }
        val incoming = File(temp.newFolder(), "book.txt")
        original.copyTo(incoming)
        val books = Books(listOf(metadata(1, original)))
        val summary = pipeline(this, books).importAndWait(listOf(ImportSource.FromFile(incoming)))
        assertEquals(1, summary.skipped)
        assertTrue(summary.messages.single().contains("同一本书"))
    }
}
