package com.mirad.reader.data.imports

import android.content.ContextWrapper
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.dao.BookDao
import com.mirad.reader.data.db.dao.ChapterDao
import com.mirad.reader.data.db.dao.ProgressDao
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.BookmarkEntity
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.db.entity.HighlightEntity
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
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
        override suspend fun getAll() = records.values.toList()
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

    @Test fun renamedTxtExtensionMatchesButSameTitleAndOpeningAloneDoNotAuthorizeReplacement() = runBlocking {
        val start = "第一章\n" + "雨停后村口的灯亮了。".repeat(160)
        val old = txtEdition(8, "旧小说", start + "\n末尾没有改写。\n")
        val incoming = txtEdition(0, "改过文件名", start + "\n末尾没有改写。\n\n第二章\n新正文。")
        val matcher = EditionMatcher { emptyList() }
        assertEquals(8L, matcher.match(incoming, incoming.title, null, listOf(old)).automatic?.book?.id)
        assertEquals(EditionRelation.SAME, matcher.match(old.copy(title = "重复副本"), "副本", null, listOf(old)).duplicate?.relation)
        assertEquals(EditionRelation.OLDER, matcher.match(old, old.title, null, listOf(incoming)).duplicate?.relation)

        val similar = txtEdition(9, "旧小说", start + "\n旧章后面其实是另一个故事。")
        val review = matcher.match(incoming.copy(title = old.title), old.title, null, listOf(old, similar))
        assertEquals(listOf(8L, 9L), review.candidates.map { it.book.id })
        assertEquals(null, review.automatic)
        val unrelated = txtEdition(10, old.title, "完全无关的正文。")
        assertEquals(EditionRelation.UNKNOWN,
            matcher.match(unrelated, unrelated.title, null, listOf(old)).candidates.single().relation)
    }

    @Test fun epubUsesReadingOrderAndIgnoresPackagedChapterPaths() = runBlocking {
        val body = "<html><body><h1>第一章</h1><p>雨夜 &amp; 旧城</p><p>灯亮了。</p></body></html>"
        val next = "<html><body><h1>第二章</h1><p>下一章新增正文。</p></body></html>"
        val oldFile = epubFile("old.epub", listOf("old/001.xhtml" to body))
        val newFile = epubFile("new.epub", listOf("new/changed.xhtml" to body, "new/next.xhtml" to next))
        val old = BookEntity(7L, "书架别名", format = BookFormat.EPUB, filePath = oldFile.path)
        val incoming = old.copy(id = 0L, title = "换了名字", filePath = newFile.path)
        val oldChapters = listOf(ChapterEntity(bookId = 7, index = 0, title = "第一章", href = "old/001.xhtml", charCount = 15))
        val newChapters = listOf(oldChapters[0].copy(href = "new/changed.xhtml"), oldChapters[0].copy(
            index = 1, title = "第二章", href = "new/next.xhtml"
        ))
        val signatures = listOf(body, next).map { EpubChapterSignature.of(com.mirad.reader.core.html.Html.toPlainText(it)) }
        val matcher = EditionMatcher { oldChapters }
        val match = matcher.match(incoming, incoming.title, signatures, listOf(old))
        assertEquals(7L, match.automatic?.book?.id)
        assertEquals(null, matcher.match(incoming, incoming.title, signatures.reversed(), listOf(old)).automatic)
        val model = checkNotNull(EditionAnchors.epubReaderText(body))
        assertEquals("第一章\n雨夜 & 旧城\n灯亮了。", model)
        val progress = ProgressEntity(7L, charOffset = model.indexOf("旧城"), percent = .5f)
        val update = BookEditionUpdater.map(
            BookEditionUpdater.Records(old, oldChapters, progress, emptyList(), emptyList()),
            incoming, newChapters, EditionRelation.EXTENDS
        )
        assertEquals(ResumeImpact.EXACT, update.preview.resume)
        assertEquals(progress.charOffset, update.progress?.charOffset)
        assertTrue(checkNotNull(update.progress).percent < progress.percent)
    }

    @Test fun txtAppendPreservesPositionAcrossByteAndChapterBoundaries() = runBlocking {
        val original = "第一章\n这里有生僻字𠮷，读到这句之后再继续。"
        val previous = txtEdition(3, "旧", original)
        val incoming = txtEdition(0, "新", original + "\n\n第二章\n接着写下了下一天的故事。")
        val old = txtChapter(previous, 0, "第一章", original, 0L)
        val tailStart = File(checkNotNull(incoming.contentPath)).readText().indexOf("第二章")
        val prefix = File(checkNotNull(incoming.contentPath)).readText().take(tailStart)
        val chapters = listOf(
            txtChapter(incoming, 0, "第一章", prefix, 0L),
            txtChapter(incoming, 1, "第二章", "第二章\n接着写下了下一天的故事。", prefix.toByteArray().size.toLong())
        )
        val offset = original.indexOf("读到")
        val progress = ProgressEntity(3L, charOffset = offset, pageIndex = 4, pageCount = 5, percent = .8f)
        val bookmark = BookmarkEntity(1, 3, 0, offset, old.title, "读到这句")
        val highlight = HighlightEntity(1, 3, 0, offset, offset + 4, "读到这句", 123, "保留笔记")
        val records = BookEditionUpdater.Records(previous, listOf(old), progress, listOf(bookmark), listOf(highlight))
        val update = BookEditionUpdater.map(records, incoming, chapters, EditionRelation.EXTENDS)
        assertEquals(ImportUpdatePreview(3L, ResumeImpact.EXACT, "第一章", 0), update.preview)
        assertEquals(offset, update.progress?.charOffset)
        assertEquals(0, update.progress?.pageIndex)
        assertEquals(bookmark, update.bookmarks.single())
        assertEquals(highlight, update.highlights.single())

        val atEnd = BookEditionUpdater.map(records.copy(progress = progress.copy(charOffset = original.length)),
            incoming, chapters, EditionRelation.EXTENDS)
        assertEquals(0, atEnd.progress?.chapterIndex)
        assertEquals(original.length, atEnd.progress?.charOffset)
    }

    @Test fun rewrittenChapterFallsBackAndKeepsUnlocatableNotes() = runBlocking {
        val oldBody = "第一章\n掌灯人走过长廊，墙上留下他匆匆赶路的影子。"
        val newBody = "第一章\n晨雾笼罩树林，远处传来钟声。"
        val old = txtEdition(15, "修订小说", oldBody)
        val incoming = txtEdition(0, "修订新版", newBody)
        val chapter = txtChapter(old, 0, "第一章", oldBody, 0L)
        val start = oldBody.indexOf("掌灯")
        val note = HighlightEntity(55, 15, 0, start, start + 3, "掌灯人", 123, "我的笔记")
        val records = BookEditionUpdater.Records(old, listOf(chapter), ProgressEntity(15, charOffset = start), emptyList(), listOf(note))
        val newChapter = txtChapter(incoming, 0, "第一章", newBody, 0L)
        val update = BookEditionUpdater.map(records, incoming, listOf(newChapter), EditionRelation.UNKNOWN)
        assertEquals(ImportUpdatePreview(15L, ResumeImpact.CHAPTER_START, "第一章", 1), update.preview)
        assertEquals(0, update.progress?.charOffset)
        assertEquals(note.copy(chapterIndex = -1), update.highlights.single())

        val blocked = BookEditionUpdater.map(records, incoming, listOf(newChapter.copy(title = "不相干的章名")), EditionRelation.UNKNOWN)
        assertEquals(ResumeImpact.UNAVAILABLE, blocked.preview.resume)
    }

    @Test fun recoveryDiscardsUnconfirmedFilesButKeepsCommittedBodyAndReferencedCover() {
        val paths = AppPaths(TestContext(temp.newFolder()))
        val staging = LocalImportStaging(paths)
        val old = UUID.randomUUID().toString()
        val previousFile = paths.originalFile(old, "txt").apply { writeText("旧内容") }
        val cover = paths.coverFile(old).apply { writeText("旧封面") }
        val original = BookEntity(8, "书籍", format = BookFormat.TXT, filePath = previousFile.path, coverPath = cover.path)

        val cancelled = UUID.randomUUID().toString()
        staging.mark(cancelled)
        staging.beforeReplacement(cancelled, original)
        val rejected = paths.originalFile(cancelled, "txt").apply { writeText("未确认的新版") }
        staging.recover(listOf(original))
        assertTrue(previousFile.isFile)
        assertTrue(!rejected.exists() && !staging.isMarked(cancelled))

        val committed = UUID.randomUUID().toString()
        staging.mark(committed)
        staging.beforeReplacement(committed, original)
        val replacement = paths.originalFile(committed, "txt").apply { writeText("已提交的新版") }
        val unusedCover = paths.coverFile(committed).apply { writeText("未采用的新版封面") }
        staging.recover(listOf(original.copy(filePath = replacement.path)))
        assertTrue(replacement.isFile && cover.isFile)
        assertTrue(!previousFile.exists() && !unusedCover.exists() && !staging.isMarked(committed))
    }

    private fun txtEdition(id: Long, title: String, text: String): BookEntity {
        val body = file("txt/" + UUID.randomUUID() + ".txt", text.toByteArray())
        return BookEntity(id, title, format = BookFormat.TXT, filePath = body.path, contentPath = body.path)
    }

    private fun txtChapter(book: BookEntity, index: Int, title: String, body: String, start: Long) =
        ChapterEntity(bookId = book.id, index = index, title = title, startByte = start,
            endByte = start + body.toByteArray().size, charCount = body.length.toLong(), titleLineChars = title.length + 1)

    private fun epubFile(name: String, parts: List<Pair<String, String>>): File = File(temp.root, name).apply {
        ZipOutputStream(outputStream()).use { zip ->
            parts.forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
