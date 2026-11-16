package com.mirad.reader.data.imports

import com.mirad.reader.core.model.ChapterTextMap
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookmarkEntity
import com.mirad.reader.data.db.entity.HighlightEntity
import com.mirad.reader.data.db.entity.ProgressEntity
import com.mirad.reader.data.io.BookContentAccess
import com.mirad.reader.data.repo.BookMaintenance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class EditionSafetyRegressionTest {
    private val fixtures = EditionUpdateComprehensiveTest()
    @get:Rule val temp = fixtures.temp

    private suspend fun importManaged(
        env: EditionUpdateComprehensiveTest.Harness,
        text: String
    ): BookEntity {
        val source = temp.newFile("小说.txt").apply { writeText(text) }
        val summary = env.pipeline.importAndWait(listOf(ImportSource.FromFile(source)))
        return checkNotNull(env.books.getBook(summary.bookIds.single()))
    }

    @Test fun fullVersionRejectsAnUnsampledEqualLengthEditWithUnchangedTimestamp() = runBlocking {
        val file = temp.newFile("large.txt").apply { writeBytes(ByteArray(512 * 1024) { 65 }) }
        val access = BookContentAccess()
        val original = SourceVersion.capture(access, file.absolutePath)
        val sample = FileFingerprint.of(file)
        val modified = file.lastModified()
        RandomAccessFile(file, "rw").use { it.seek(100 * 1024L); it.write(66) }
        assertTrue(file.setLastModified(modified))
        assertEquals(sample, FileFingerprint.of(file))
        assertTrue(runCatching { original.verify(access, file.absolutePath) }.exceptionOrNull() is IncomingSourceChanged)
    }

    @Test fun sameUriUnsampledEditRequiresReviewUntilTheChangedVersionIsConfirmed() = runBlocking {
        val env = fixtures.Harness(this)
        val file = temp.newFile("same-uri.txt").apply { writeBytes(ByteArray(512 * 1024) { 65 }) }
        val uri = "content://edition/same-uri"
        env.saf.mount(uri, file)
        val source = ImportSource.FromUri(null, "连载小说.txt", uri)
        val first = env.pipeline.importAndWait(listOf(source))
        assertEquals(1, first.success)
        val bookId = first.bookIds.single()
        val oldVersion = checkNotNull(env.books.sourceVersionStore.get(bookId))
        val oldSample = checkNotNull(BookSourceStampStore.capture(env.contentAccess, uri))
        val modified = file.lastModified()

        RandomAccessFile(file, "rw").use { it.seek(100 * 1024L); it.write(66) }
        assertTrue(file.setLastModified(modified))
        assertEquals(512 * 1024L, file.length())
        assertEquals(oldSample, BookSourceStampStore.capture(env.contentAccess, uri))
        val changedVersion = SourceVersion.capture(env.contentAccess, uri)
        assertTrue(oldVersion != changedVersion)

        val changed = env.pipeline.importAndWait(listOf(source))
        assertEquals(0, changed.skipped)
        assertEquals(0, changed.updated)
        assertEquals(0, changed.success)
        val pending = changed.pending.single()
        assertEquals(ImportStage.REVIEW, pending.stage)
        assertEquals(bookId, pending.review?.candidates?.single()?.bookId)
        assertEquals(oldVersion, env.books.sourceVersionStore.get(bookId))
        env.pipeline.confirmUpdate(pending.id, checkNotNull(pending.review?.preview))
        withTimeout(3000) { while (env.pipeline.lastSummary.value?.pending?.isNotEmpty() == true) delay(10) }
        assertEquals(1, env.pipeline.lastSummary.value?.updated)
        assertEquals(changedVersion, env.books.sourceVersionStore.get(bookId))
        assertEquals(1, env.books.getAllBooks().size)

        val confirmedDuplicate = env.pipeline.importAndWait(listOf(source))
        assertEquals(1, confirmedDuplicate.skipped)
        assertEquals(0, confirmedDuplicate.updated)
        assertEquals(0, confirmedDuplicate.success)
        assertTrue(confirmedDuplicate.pending.isEmpty())
        assertEquals(listOf(bookId), confirmedDuplicate.bookIds)
    }

    @Test fun sameUriWithoutAHistoricalFullVersionCannotBeAssumedUnchanged() = runBlocking {
        val env = fixtures.Harness(this)
        val file = temp.newFile("legacy-source.txt").apply { writeText("第一章 晨钟\n原来的完整故事。") }
        val uri = "content://edition/legacy-source"
        env.saf.mount(uri, file)
        val source = ImportSource.FromUri(null, "旧书.txt", uri)
        val bookId = env.pipeline.importAndWait(listOf(source)).bookIds.single()
        val book = checkNotNull(env.books.getBook(bookId))
        env.books.sourceVersionStore.remove(bookId)
        assertNotNull(env.books.sourceStampStore.get(bookId))
        val version = SourceVersion.capture(env.contentAccess, uri)

        val match = EditionMatcher(env.contentAccess, env.books::getChapters, env.books.sourceVersionStore)
            .match(book.copy(id = 0L), book.title, null, listOf(book), version)
        assertNull(match.duplicate)
        assertEquals(EditionRelation.UNKNOWN, match.candidates.single().relation)

        val reimported = env.pipeline.importAndWait(listOf(source))
        assertEquals(0, reimported.skipped)
        assertEquals(ImportStage.REVIEW, reimported.pending.single().stage)
        assertNull("未确认前不得把当前文件追认为历史完整版本", env.books.sourceVersionStore.get(bookId))
        assertEquals(book, env.books.getBook(bookId))
        assertEquals(1, env.books.getAllBooks().size)
    }

    @Test fun deletedPreparedSourceCannotReplaceOrCleanTheManagedOriginal() = runBlocking {
        val env = fixtures.Harness(this)
        val old = importManaged(env, "第一章 晨钟\n" + "原版古寺钟声悠远。".repeat(20))
        val chapters = env.books.getChapters(old.id)
        val replacement = temp.newFile("revision.txt").apply { writeText("第一章 晨钟\n全新修订的古寺故事。") }
        val uri = "content://edition/revision"
        env.saf.mount(uri, replacement)
        val pending = env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "小说.txt", uri))).pending.single()
        assertTrue(replacement.delete())
        env.pipeline.confirmUpdate(pending.id, checkNotNull(pending.review?.preview))
        withTimeout(3000) { while (env.pipeline.lastSummary.value?.pending?.isNotEmpty() == true) delay(10) }
        assertEquals(1, env.pipeline.lastSummary.value?.failed)
        assertEquals(old, env.books.getBook(old.id))
        assertEquals(chapters, env.books.getChapters(old.id))
        assertTrue(File(old.filePath).isFile)
    }

    @Test fun changedSourceWhileWaitingForReaderCannotPublishCachedChapters() = runBlocking {
        val env = fixtures.Harness(this)
        val body = "第一章 晨钟\n" + "原版古寺钟声悠远。".repeat(20)
        val old = importManaged(env, body)
        val chapters = env.books.getChapters(old.id)
        val replacement = temp.newFile("append.txt").apply { writeText(body + "\n第二章 暮鼓\n新的章节。") }
        val uri = "content://edition/append"
        env.saf.mount(uri, replacement)
        val lock = env.books.contentLock(old.id)
        lock.lock()
        try {
            val summary = env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "小说.txt", uri)))
            assertEquals(ImportStage.WAITING_READER, summary.pending.single().stage)
            replacement.writeText("第一章 已被换成别的文件\n不同正文。")
        } finally { lock.unlock() }
        withTimeout(3000) { while (env.pipeline.lastSummary.value?.pending?.isNotEmpty() == true) delay(10) }
        assertEquals(1, env.pipeline.lastSummary.value?.failed)
        assertEquals(old, env.books.getBook(old.id))
        assertEquals(chapters, env.books.getChapters(old.id))
        assertTrue(File(old.filePath).isFile)
    }

    @Test fun equalLengthRelinkNeedsConfirmationAndOnlyRecoversTheUniqueHighlight() = runBlocking {
        val env = fixtures.Harness(this)
        val old = importManaged(env, "第一章 晨钟\n甲甲甲甲目标段落乙乙乙乙")
        val chapter = env.books.getChapters(old.id).single()
        val dom = ChapterTextMap(env.textProvider.readText(old, chapter), chapter.title, chapter.titleLineChars, false, 0).text
        val offset = dom.indexOf("目标段落")
        env.books.saveProgress(ProgressEntity(old.id, 0, offset))
        val bookmarkId = env.bookmarkDao.insert(BookmarkEntity(bookId = old.id, chapterIndex = 0, charOffset = offset,
            chapterTitle = chapter.title, snippet = "目标段落", note = "保留书签内容"))
        val highlightId = env.highlightDao.insert(HighlightEntity(bookId = old.id, chapterIndex = 0,
            startOffset = offset, endOffset = offset + 4, text = "目标段落", color = 1, note = "保留笔记"))
        assertTrue(File(old.filePath).delete())
        val replacement = temp.newFile("equal.txt").apply { writeText("第一章 晨钟\n丙丙丙丙甲甲甲甲目标段落") }
        val uri = "content://edition/equal"
        env.saf.mount(uri, replacement)
        var confirmations = 0
        val result = env.maintenance.relinkSource(old.id, uri) { preview ->
            confirmations++
            assertEquals(ResumeImpact.CHAPTER_START, preview.resume)
            assertEquals(1, preview.lostLocations)
            val lock = env.books.contentLock(old.id)
            assertTrue("用户确认阶段不得持有正文锁", lock.tryLock())
            lock.unlock()
            true
        }
        assertNotNull(result)
        assertEquals(1, confirmations)
        assertEquals(0, env.books.getProgress(old.id)?.charOffset)
        assertEquals(-1, env.bookmarkDao.getById(bookmarkId)?.chapterIndex)
        assertEquals("保留书签内容", env.bookmarkDao.getById(bookmarkId)?.note)
        val highlight = checkNotNull(env.highlightDao.getById(highlightId))
        assertEquals(offset + 4, highlight.startOffset)
        assertEquals("保留笔记", highlight.note)
    }

    @Test fun fullVersionRestoresAnUnchangedMissingOriginalWithoutConfirmation() = runBlocking {
        val env = fixtures.Harness(this)
        val text = "第一章 晨钟\n甲甲甲甲目标段落乙乙乙乙"
        val old = importManaged(env, text)
        env.books.saveProgress(ProgressEntity(old.id, 0, 9))
        assertTrue(File(old.filePath).delete())
        val replacement = temp.newFile("identical.txt").apply { writeText(text) }
        val uri = "content://edition/identical"
        env.saf.mount(uri, replacement)
        assertNotNull(env.maintenance.relinkSource(old.id, uri) { error("完整版本相同应精确保留位置") })
        assertEquals(9, env.books.getProgress(old.id)?.charOffset)
    }

    @Test fun cancellingRelinkConfirmationCleansOnlyItsStagedResources() = runBlocking {
        val env = fixtures.Harness(this)
        val old = importManaged(env, "第一章 晨钟\n原来的故事。")
        env.books.saveProgress(ProgressEntity(old.id, 0, 8))
        val replacement = temp.newFile("temporary.txt").apply { writeText("第一章 晨钟\n完全改写后更长的新故事。") }
        val uri = "content://edition/temporary"
        env.saf.mount(uri, replacement)
        val temporaryAccess = BookContentAccess(null, object : BookContentAccess.UriBackend by env.saf {
            override fun canReferenceDirectly(uriString: String) = false
        })
        val maintenance = BookMaintenance(env.books, env.paths, env.bookmarkDao, env.highlightDao, contentAccess = temporaryAccess)
        val originalBodies = env.paths.booksDir.listFiles().orEmpty().map { it.name }.toSet()
        val originalCovers = env.paths.coversDir.listFiles().orEmpty().map { it.name }.toSet()
        val awaiting = CompletableDeferred<Unit>()
        val operation = launch {
            maintenance.relinkSource(old.id, uri) {
                assertEquals(originalBodies.size + 1, env.paths.booksDir.listFiles().orEmpty().size)
                awaiting.complete(Unit)
                CompletableDeferred<Boolean>().await()
            }
        }
        withTimeout(3000) { awaiting.await() }
        operation.cancelAndJoin()
        assertEquals(originalBodies, env.paths.booksDir.listFiles().orEmpty().map { it.name }.toSet())
        assertEquals(originalCovers, env.paths.coversDir.listFiles().orEmpty().map { it.name }.toSet())
        assertEquals(old, env.books.getBook(old.id))
        assertTrue(File(old.filePath).isFile)
    }

    @Test fun changedOldRecordsDuringRelinkConfirmationAreNeverOverwritten() = runBlocking {
        val env = fixtures.Harness(this)
        val old = importManaged(env, "第一章 晨钟\n原来的故事。")
        env.books.saveProgress(ProgressEntity(old.id, 0, 8))
        val replacement = temp.newFile("changed-records.txt").apply { writeText("第一章 晨钟\n完全不同的新故事。") }
        val changedProgress = ProgressEntity(old.id, 0, 3)
        val error = runCatching {
            env.maintenance.relinkSource(old.id, replacement.absolutePath) {
                env.books.saveProgress(changedProgress)
                true
            }
        }.exceptionOrNull()
        assertTrue(error is EditionChanged)
        assertEquals(old, env.books.getBook(old.id))
        assertEquals(changedProgress, env.books.getProgress(old.id))
        assertTrue(File(old.filePath).isFile)
    }

    @Test fun sourceRemovedDuringRelinkConfirmationKeepsTheOldManagedBody() = runBlocking {
        val env = fixtures.Harness(this)
        val old = importManaged(env, "第一章 晨钟\n原来的故事。")
        val progress = ProgressEntity(old.id, 0, 8)
        env.books.saveProgress(progress)
        val chapters = env.books.getChapters(old.id)
        val replacement = temp.newFile("removed-at-confirm.txt").apply { writeText("第一章 晨钟\n完全不同的新故事。") }
        val error = runCatching {
            env.maintenance.relinkSource(old.id, replacement.absolutePath) {
                assertTrue(replacement.delete())
                true
            }
        }.exceptionOrNull()
        assertTrue(error is IncomingSourceChanged)
        assertEquals(old, env.books.getBook(old.id))
        assertEquals(chapters, env.books.getChapters(old.id))
        assertEquals(progress, env.books.getProgress(old.id))
        assertTrue(File(old.filePath).isFile)
    }
}
