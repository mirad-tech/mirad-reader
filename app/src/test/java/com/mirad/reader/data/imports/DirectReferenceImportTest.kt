package com.mirad.reader.data.imports

import android.content.ContextWrapper
import com.mirad.reader.core.epub.EpubParser
import com.mirad.reader.core.epub.ZipEntryReader
import com.mirad.reader.core.text.StreamingTxtProcessor
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.dao.BookDao
import com.mirad.reader.data.db.dao.BookmarkDao
import com.mirad.reader.data.db.dao.ChapterDao
import com.mirad.reader.data.db.dao.HighlightDao
import com.mirad.reader.data.db.dao.ProgressDao
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookmarkEntity
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.db.entity.HighlightEntity
import com.mirad.reader.data.db.entity.ProgressEntity
import com.mirad.reader.data.io.BookContentAccess
import com.mirad.reader.data.repo.BookMaintenance
import com.mirad.reader.data.repo.BookRepository
import com.mirad.reader.data.repo.ChapterTextProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DirectReferenceImportTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class TestContext(private val rootDir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = rootDir
    }

    /** 模拟 Android SAF `content://` 提供方（可控持久授权状态、未知大小与范围读取计数）。 */
    private class FakeSafBackend : BookContentAccess.UriBackend {
        data class Entry(val file: File, val persistable: Boolean, val unknownSize: Boolean = false)
        val mounts = ConcurrentHashMap<String, Entry>()
        val rangeReads = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, Int>>()

        fun mount(uri: String, file: File, persistable: Boolean = true, unknownSize: Boolean = false) {
            mounts[uri] = Entry(file, persistable, unknownSize)
        }

        fun unmount(uri: String) {
            mounts.remove(uri)
        }

        override fun canReferenceDirectly(uriString: String): Boolean {
            val e = mounts[uriString] ?: return false
            return e.persistable && e.file.isFile && e.file.length() > 0L
        }

        override fun isReadable(uriString: String): Boolean =
            mounts[uriString]?.file?.let { it.isFile && it.canRead() } == true

        override fun size(uriString: String): Long {
            val e = mounts[uriString] ?: return -1L
            if (e.unknownSize) return -1L
            return e.file.takeIf { it.isFile }?.length() ?: -1L
        }

        override fun lastModified(uriString: String): Long? =
            mounts[uriString]?.file?.takeIf { it.isFile }?.lastModified()

        override fun openStream(uriString: String): InputStream {
            val file = mounts[uriString]?.file?.takeIf { it.isFile }
                ?: throw java.io.FileNotFoundException("Missing URI: $uriString")
            return FileInputStream(file)
        }

        override fun readRange(uriString: String, startByte: Long, length: Int): ByteArray? {
            val file = mounts[uriString]?.file?.takeIf { it.isFile } ?: return null
            rangeReads += startByte to length
            return FileChannel.open(file.toPath(), StandardOpenOption.READ).use { ch ->
                BookContentAccess.readChannelRange(ch, startByte, length)
            }
        }

        override fun openZip(uriString: String): ZipEntryReader {
            val file = mounts[uriString]?.file?.takeIf { it.isFile }
                ?: throw java.io.FileNotFoundException("Missing EPUB URI: $uriString")
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
            return ZipEntryReader.open(channel, Closeable { channel.close() })
        }
    }

    @Test
    fun txtIndexRawAndDecodeSliceMatchNormalizedOutputAcrossCharsetsAndFallback() {
        val longParagraph = "山风吹过竹林，远处灯火通明。".repeat(750)
        val titledBody = buildString {
            append("序言\r\n这里有第一段文字，行尾带全角空格　　  \r\n\r\n\r\n\r\n")
            append("第一章 启程\n中文、English 与长段落：\n")
            append(longParagraph).append("\n")
            append("第二章 归途\r孤立 CR 换行测试，还有第二章正文。\r\n\r\n\r\n末尾收束。")
        }
        val untitledBody = (1..25).joinToString("\r\n\r\n\r\n\r\n") { idx ->
            "无标题段落 $idx：" + "春水初生，春林初盛。".repeat(55)
        }

        val encodings = listOf(
            "UTF-8-BOM" to { text: String -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8) },
            "UTF-8" to { text: String -> (text + "\n补充生僻字𠮷与🌲").toByteArray(Charsets.UTF_8) },
            "GB18030" to { text: String -> text.toByteArray(Charset.forName("GB18030")) },
            "BIG5" to { text: String -> "第一章 繁體測試\r\n風吹過山崗，燈火依舊。\r\n\r\n\r\n第二章 遠行\n踏上新的旅途。".toByteArray(Charset.forName("BIG5")) },
            "UTF-16LE" to { text: String -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE) },
            "UTF-16BE" to { text: String -> byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE) }
        )

        for ((label, encoder) in encodings) {
            for (sample in listOf(titledBody, untitledBody)) {
                val rawFile = temp.newFile("raw_${label}_${sample.hashCode()}.txt")
                rawFile.writeBytes(encoder(sample))
                val explicitHint = if (label == "BIG5") "BIG5" else null

                val legacyOut = temp.newFile("legacy_${label}_${sample.hashCode()}.utf8.txt")
                val expected = StreamingTxtProcessor.process(rawFile, legacyOut, explicitHint)
                val expectedFullText = legacyOut.readText(Charsets.UTF_8)

                val rawIndexed = StreamingTxtProcessor.indexRaw(rawFile, explicitHint)
                assertEquals("[$label] 章节数量必须一致", expected.slices.size, rawIndexed.slices.size)
                assertEquals("[$label] 总字符数必须一致", expected.totalChars, rawIndexed.totalChars)
                assertEquals("[$label] 原文件字节长度必须一致", rawFile.length(), rawIndexed.totalBytes)

                val rebuilt = StringBuilder()
                RandomAccessFile(rawFile, "r").use { raf ->
                    rawIndexed.slices.forEachIndexed { idx, slice ->
                        val len = (slice.endByte - slice.startByte).toInt()
                        val buf = ByteArray(len)
                        raf.seek(slice.startByte)
                        raf.readFully(buf)
                        val decoded = StreamingTxtProcessor.decodeRawSlice(
                            rawBytes = buf,
                            charsetName = rawIndexed.charset,
                            stripLeadingBom = slice.startByte == 0L
                        )
                        assertEquals("[$label] 第 $idx 章字符数必须严格等于 charCount", slice.charCount, decoded.length)
                        assertEquals("[$label] 第 $idx 章标题行字数必须一致", expected.slices[idx].titleLineChars, slice.titleLineChars)
                        rebuilt.append(decoded)
                    }
                }
                assertEquals("[$label] 按原文件字节切片解码拼接必须与归一化全文逐字恒等", expectedFullText, rebuilt.toString())
            }
        }
    }

    @Test
    fun channelZipBackendSupportsStoredDeflatedChinesePathsLimitsAndConcurrentReads() = runBlocking {
        val epubFile = buildSampleEpub(
            temp.newFile("channel_sample.epub"),
            chapters = listOf(
                "OEBPS/第一章 启程.xhtml" to "第一章 启程" to "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>第一章 启程</h1><p>零拷贝随机访问正文甲。</p></body></html>",
                "OEBPS/第二章 远方.xhtml" to "第二章 远方" to "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>第二章 远方</h1><p>零拷贝随机访问正文乙。</p></body></html>"
            ),
            oversizedBytes = 2048
        )

        val channel = FileChannel.open(epubFile.toPath(), StandardOpenOption.READ)
        ZipEntryReader.open(channel).use { reader ->
            assertEquals("application/epub+zip", reader.readText("mimetype"))
            val parsed = EpubParser(reader).parse()
            assertEquals("测试零拷贝EPUB", parsed.title)
            assertEquals(2, parsed.spine.size)
            assertTrue(reader.readText("OEBPS/%E7%AC%AC%E4%B8%80%E7%AB%A0%20%E5%90%AF%E7%A8%8B.xhtml")!!.contains("正文甲"))
            assertNull("超出 maxBytes 的条目必须返回 null", reader.readBytes("OEBPS/oversized.bin", maxBytes = 1024))

            // 多协程并发随机读取同一 ChannelZipBackend，验证无状态 pread 互不干扰
            val concurrentResults = (1..16).map { round ->
                async(Dispatchers.IO) {
                    val target = if (round % 2 == 0) "OEBPS/第一章 启程.xhtml" else "OEBPS/第二章 远方.xhtml"
                    reader.readText(target)
                }
            }.awaitAll()
            assertTrue(concurrentResults.all { !it.isNullOrBlank() })
        }
    }

    @Test
    fun persistedSafTxtAndEpubImportZeroBodyFilesAndDeleteKeepsExternalFileIntact() = runBlocking {
        val env = TestHarness(this)
        val externalDir = temp.newFolder("phone_storage_books")

        // 1. 手机本地 GB18030 TXT
        val phoneTxt = File(externalDir, "剑来.txt").apply {
            writeBytes(
                "第一章 惊蛰\r\n泥瓶巷的少年推开木门。\r\n第二章 远游\r\n山高水长，江湖路远。".toByteArray(Charset.forName("GB18030"))
            )
        }
        val txtUri = "content://com.android.externalstorage.documents/document/primary%3ABooks%2Fjianlai.txt"
        env.saf.mount(txtUri, phoneTxt, persistable = true)

        // 2. 手机本地 EPUB
        val phoneEpub = buildSampleEpub(
            File(externalDir, "雪中.epub"),
            chapters = listOf(
                "OEBPS/ch1.xhtml" to "第1章 小二上酒" to "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>白狐儿脸佩双刀。</p></body></html>"
            )
        )
        val epubUri = "content://com.android.externalstorage.documents/document/primary%3ABooks%2Fxuezhong.epub"
        env.saf.mount(epubUri, phoneEpub, persistable = true)

        val summary = env.pipeline.importAndWait(
            listOf(
                ImportSource.FromUri(null, "剑来.txt", txtUri),
                ImportSource.FromUri(null, "雪中.epub", epubUri)
            )
        )
        assertEquals(2, summary.success)

        // 核心断言：应用私有 booksDir 下没有复制任何一本书（0 个文件、0 个非空目录）！
        val copiedFiles = env.paths.booksDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue("零拷贝导入不得在 booksDir 下产生任何正文副本，实际：$copiedFiles", copiedFiles.isEmpty())

        val importedTxt = env.bookDao.records.values.first { it.format == "TXT" }
        assertEquals(txtUri, importedTxt.filePath)
        assertNull("零拷贝 TXT 不得生成 contentPath", importedTxt.contentPath)
        val txtChapters = env.chapterDao.getForBook(importedTxt.id)
        assertEquals(2, txtChapters.size)
        assertTrue(env.textProvider.readTextOrNull(importedTxt, txtChapters[0])!!.contains("泥瓶巷的少年推开木门"))
        assertTrue(env.textProvider.readTextOrNull(importedTxt, txtChapters[1])!!.contains("山高水长，江湖路远"))

        val importedEpub = env.bookDao.records.values.first { it.format == "EPUB" }
        assertEquals(epubUri, importedEpub.filePath)
        val epubChapters = env.chapterDao.getForBook(importedEpub.id)
        assertTrue(env.textProvider.readTextOrNull(importedEpub, epubChapters[0])!!.contains("白狐儿脸佩双刀"))

        // 删除书架上的外部引用书籍：手机原文件必须 100% 完好保留！
        env.books.deleteBook(importedTxt)
        assertNull(env.books.getBook(importedTxt.id))
        assertTrue("删除书架记录绝不能删除用户手机内的原文件", phoneTxt.isFile && phoneTxt.length() > 0L)
    }

    @Test
    fun nonPersistableTemporaryShareFallsBackToSingleCopyWithoutDuplicateUtf8File() = runBlocking {
        val env = TestHarness(this)
        val wechatTemp = temp.newFile("wechat_share_temp.txt").apply {
            writeBytes("第一章 临时分享\r\n对方 FileProvider 不支持持久授权。".toByteArray(Charset.forName("GB18030")))
        }
        val tempUri = "content://com.tencent.mm.external.fileprovider/temp/share.txt"
        env.saf.mount(tempUri, wechatTemp, persistable = false)

        val summary = env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "分享小说.txt", tempUri)))
        assertEquals(1, summary.success)

        // 模拟微信随后清理了它的临时缓存
        env.saf.unmount(tempUri)
        wechatTemp.delete()

        val savedBook = env.bookDao.records.values.single()
        assertNull("即使是回退单份保存，也不再生成第二份 content.utf8.txt", savedBook.contentPath)
        val internalFiles = env.paths.booksDir.walkTopDown().filter { it.isFile }.toList()
        assertEquals("内部仅保留单份 original.txt", 1, internalFiles.size)
        assertEquals("original.txt", internalFiles.single().name)

        val ch0 = env.chapterDao.getForBook(savedBook.id).single()
        assertTrue("微信临时文件消失后仍可从单份回退文件正常读取", env.textProvider.readTextOrNull(savedBook, ch0)!!.contains("不支持持久授权"))

        // 删除内部托管书籍时，内部单份文件应被干净回收
        env.books.deleteBook(savedBook)
        assertTrue(env.paths.booksDir.walkTopDown().filter { it.isFile }.toList().isEmpty())
    }

    @Test
    fun movedExternalFileCanBeRelinkedWhilePreservingProgressBookmarksAndHighlights() = runBlocking {
        val env = TestHarness(this)
        val folderA = temp.newFolder("Download")
        val folderB = temp.newFolder("Documents")
        val originalText = "第一章 初见\n窗外落着细雨，案头放着一卷古书。\n第二章 夜谈\n炉火微红，故人长谈至深夜。"
        val fileInA = File(folderA, "古书奇谭.txt").apply { writeText(originalText, Charsets.UTF_8) }

        val oldUri = "content://external/download/gushu.txt"
        env.saf.mount(oldUri, fileInA, persistable = true)

        val summary = env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "古书奇谭.txt", oldUri)))
        assertEquals(1, summary.success)
        val book = env.bookDao.records.values.single()
        val ch1 = env.chapterDao.getForBook(book.id)[1]
        val ch1Body = env.textProvider.readText(book, ch1)
        val noteWord = "故人长谈"
        val bodyOffset = ch1Body.indexOf(noteWord)
        assertTrue(bodyOffset >= 0)

        // 保存阅读进度、书签与划线
        val savedProgress = ProgressEntity(bookId = book.id, chapterIndex = 1, charOffset = 10, percent = 0.72f)
        env.books.saveProgress(savedProgress)
        val bmId = env.bookmarkDao.insert(BookmarkEntity(bookId = book.id, chapterIndex = 1, charOffset = 10, chapterTitle = ch1.title, snippet = "炉火微红"))
        val hlId = env.highlightDao.insert(HighlightEntity(bookId = book.id, chapterIndex = 1, startOffset = 5, endOffset = 9, text = "炉火微红", color = 1, note = "批注"))

        // 模拟用户在手机文件管理器中把文件从 Download 移到了 Documents 并改了名
        val fileInB = File(folderB, "古书奇谭_整理归档.txt")
        assertTrue(fileInA.renameTo(fileInB))
        env.saf.unmount(oldUri)
        assertFalse("原文件移走后应识别为不可读", env.textProvider.isBookSourceReadable(book))

        // 重新关联到移动后的新位置
        val newUri = "content://external/documents/gushu_archived.txt"
        env.saf.mount(newUri, fileInB, persistable = true)
        val relinked = env.maintenance.relinkSource(book.id, newUri, newUri)

        assertEquals(newUri, relinked.filePath)
        assertTrue("重关联后应立即恢复可读", env.textProvider.isBookSourceReadable(relinked))
        assertEquals("原文件未改动时阅读进度必须 100% 原样保留", savedProgress, env.books.getProgress(book.id))
        assertEquals(10, env.bookmarkDao.getById(bmId)?.charOffset)
        assertEquals("批注", env.highlightDao.getById(hlId)?.note)
        assertTrue(env.textProvider.readTextOrNull(relinked, env.chapterDao.getForBook(book.id)[1])!!.contains("故人长谈至深夜"))
    }

    @Test
    fun zeroCopyTxtSupportsEncodingRedetectChapterResplitAndEditionAppendWithoutBodyCopies() = runBlocking {
        val env = TestHarness(this)
        val externalDir = temp.newFolder("external_editions")
        val gbk = Charset.forName("GB18030")

        val v1Raw = "第一章 踏雪\r\n" + "北风卷地白草折，胡天八月即飞雪。".repeat(25) + "\r\n"
        val v1File = File(externalDir, "边塞行_v1.txt").apply { writeBytes(v1Raw.toByteArray(gbk)) }
        val v1Uri = "content://external/books/biansai_v1.txt"
        env.saf.mount(v1Uri, v1File, persistable = true)

        assertEquals(1, env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "边塞行.txt", v1Uri))).success)
        val imported = env.bookDao.records.values.single()
        assertNull(imported.contentPath)

        // 1. 零拷贝重新识别编码与预览：不得在 booksDir 写出任何文件
        val previews = env.maintenance.previews(imported)
        assertTrue(previews.any { it.text.contains("北风卷地白草折") })
        val redetected = env.maintenance.redetect(imported, charsetHint = "GB18030")
        assertEquals("GB18030", redetected.encoding)
        assertTrue(env.paths.booksDir.walkTopDown().filter { it.isFile }.toList().isEmpty())

        // 2. 零拷贝重新识别章节：书签与阅读进度保留
        val currentBook = env.books.getBook(imported.id)!!
        env.books.saveProgress(ProgressEntity(bookId = currentBook.id, chapterIndex = 0, charOffset = 12, percent = 0.2f))
        val bmId = env.bookmarkDao.insert(
            BookmarkEntity(bookId = currentBook.id, chapterIndex = 0, charOffset = 12, chapterTitle = "第一章 踏雪", snippet = "北风卷地")
        )
        val chapterPreview = env.maintenance.previewChapters(currentBook)
        assertEquals(1, env.maintenance.applyChapters(currentBook, chapterPreview))
        assertEquals(12, env.books.getProgress(currentBook.id)?.charOffset)
        assertEquals(12, env.bookmarkDao.getById(bmId)?.charOffset)

        // 3. 零拷贝外部 GB18030 新版续写匹配与进度迁移
        val v2Raw = v1Raw + "\r\n第二章 破阵\r\n醉里挑灯看剑，梦回吹角连营。\r\n"
        val v2File = File(externalDir, "边塞行_v2.txt").apply { writeBytes(v2Raw.toByteArray(gbk)) }
        val v2Uri = "content://external/books/biansai_v2.txt"
        env.saf.mount(v2Uri, v2File, persistable = true)

        val indexedV2 = StreamingTxtProcessor.indexRaw(v2File)
        val v2CandidateBook = currentBook.copy(
            id = 0L,
            filePath = v2Uri,
            sourceUri = v2Uri,
            fileSize = v2File.length(),
            encoding = indexedV2.charset,
            totalChapters = indexedV2.slices.size,
            totalChars = indexedV2.totalChars
        )
        val v2Chapters = indexedV2.slices.mapIndexed { i, s ->
            ChapterEntity(
                bookId = currentBook.id, index = i, title = s.title,
                startByte = s.startByte, endByte = s.endByte,
                charCount = s.charCount.toLong(), titleLineChars = s.titleLineChars, isVolume = s.isVolume
            )
        }
        val matcher = EditionMatcher(env.contentAccess, env.books::getChapters)
        val match = matcher.match(v2CandidateBook, "边塞行_v2", null, listOf(currentBook))
        assertEquals("零拷贝外部 TXT 续写应自动匹配旧书", currentBook.id, match.automatic?.book?.id)

        val update = BookEditionUpdater.map(
            old = BookEditionUpdater.Records(
                currentBook,
                env.books.getChapters(currentBook.id),
                env.books.getProgress(currentBook.id),
                env.bookmarkDao.getAllForBook(currentBook.id),
                emptyList()
            ),
            incoming = v2CandidateBook,
            chapters = v2Chapters,
            relation = EditionRelation.EXTENDS,
            textProvider = env.textProvider
        )
        assertEquals(ResumeImpact.EXACT, update.preview.resume)
        assertEquals(12, update.progress?.charOffset)
        assertTrue("内部 booksDir 全程保持 0 正文副本", env.paths.booksDir.walkTopDown().filter { it.isFile }.toList().isEmpty())
    }

    @Test
    fun relinkFromGbkToUtf8AutoDetectsNewCharsetWithoutMojibake() = runBlocking {
        val env = TestHarness(this)
        val content = "第一章 烟雨江南\n春水碧于天，画船听雨眠。\n第二章 塞北秋风\n长河落日圆，大漠孤烟直。"
        val gbkFile = temp.newFile("old_gbk.txt").apply {
            writeBytes(content.toByteArray(Charset.forName("GB18030")))
        }
        val oldUri = "content://external/books/old_gbk.txt"
        env.saf.mount(oldUri, gbkFile, persistable = true)

        assertEquals(1, env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "诗词选.txt", oldUri))).success)
        val oldBook = env.bookDao.records.values.single()
        assertEquals("GB18030", oldBook.encoding)

        // 原 GBK 文件丢失，用户重新关联了一份带 BOM 的 UTF-8 同书文件
        env.saf.unmount(oldUri)
        gbkFile.delete()
        val utf8File = temp.newFile("new_utf8.txt").apply {
            writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + content.toByteArray(Charsets.UTF_8))
        }
        val newUri = "content://external/books/new_utf8.txt"
        env.saf.mount(newUri, utf8File, persistable = true)

        val relinked = env.maintenance.relinkSource(oldBook.id, newUri, newUri)
        assertEquals("重关联新文件必须自动识别为 UTF-8，不得沿用旧 GB18030", "UTF-8", relinked.encoding)
        val chapters = env.chapterDao.getForBook(relinked.id)
        assertEquals(2, chapters.size)
        assertEquals("第一章 烟雨江南", chapters[0].title)
        assertTrue(env.textProvider.readText(relinked, chapters[0]).contains("春水碧于天，画船听雨眠。"))
    }

    @Test
    fun relinkToNonPersistableUriFallsBackToSingleCopyAndRejectsOversizedUnknownLengthStream() = runBlocking {
        val env = TestHarness(this)
        val originalFile = temp.newFile("persisted.txt").apply {
            writeText("第一章 原书\n初始持久引用的正文。", Charsets.UTF_8)
        }
        val persistedUri = "content://external/books/persisted.txt"
        env.saf.mount(persistedUri, originalFile, persistable = true)
        assertEquals(1, env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "原书.txt", persistedUri))).success)
        val book = env.bookDao.records.values.single()

        // 1. 重关联到第三方无持久权限的临时 URI：应自动落盘单份内部文件，临时 URI 失效后仍可读
        val tempFile = temp.newFile("third_party_temp.txt").apply {
            writeText("第一章 原书\n来自无持久授权 Provider 的重关联文件。", Charsets.UTF_8)
        }
        val nonPersistUri = "content://thirdparty.provider/temp/relink.txt"
        env.saf.mount(nonPersistUri, tempFile, persistable = false)

        val relinked = env.maintenance.relinkSource(book.id, nonPersistUri, nonPersistUri)
        assertFalse("无持久权限的 URI 不得直接存入 filePath", BookContentAccess.isContentUri(relinked.filePath))
        assertNull(relinked.contentPath)

        // 模拟应用重启后临时授权失效、外部临时文件被清理
        env.saf.unmount(nonPersistUri)
        tempFile.delete()
        val ch0 = env.chapterDao.getForBook(relinked.id).single()
        assertTrue(env.textProvider.readText(relinked, ch0).contains("来自无持久授权 Provider"))

        // 2. 重关联到一个 size() == -1 且实际超过 80MB 上限的未知长度流：必须拦截且保留刚才的内部副本不损坏
        val oversized = temp.newFile("oversized_unknown.txt")
        RandomAccessFile(oversized, "rw").use { raf ->
            raf.setLength(ImportPipeline.MAX_TXT_BYTES + 4096L)
            raf.seek(0L)
            raf.write("第一章 超大文件\n".toByteArray(Charsets.UTF_8))
        }
        val oversizedUri = "content://thirdparty.provider/stream/huge.txt"
        env.saf.mount(oversizedUri, oversized, persistable = true, unknownSize = true)

        val error = runCatching {
            env.maintenance.relinkSource(relinked.id, oversizedUri, oversizedUri)
        }.exceptionOrNull()
        assertTrue("未知大小的超限流必须被拦截", error is IllegalArgumentException && error.message!!.contains("上限"))
        assertTrue("重关联失败后原有的内部单份文件必须完好可读", env.textProvider.isBookSourceReadable(env.books.getBook(relinked.id)!!))
    }

    @Test
    fun inPlaceOverwrittenExternalUriDoesNotSliceStaleByteOffsetsAndPreservesNotes() = runBlocking {
        val env = TestHarness(this)
        val sharedFile = temp.newFile("in_place.txt").apply {
            writeText("第一章 晨钟\n古寺钟声悠远，惊起林间飞鸟。\n第二章 暮鼓\n夕阳西下，山门紧闭。", Charsets.UTF_8)
        }
        val sharedUri = "content://external/books/in_place.txt"
        env.saf.mount(sharedUri, sharedFile, persistable = true)

        assertEquals(1, env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "古寺记.txt", sharedUri))).success)
        val oldBook = env.bookDao.records.values.single()
        val oldChapters = env.books.getChapters(oldBook.id)
        val ch1Dom = com.mirad.reader.core.model.ChapterTextMap(
            env.textProvider.readText(oldBook, oldChapters[1]),
            oldChapters[1].title, oldChapters[1].titleLineChars, oldChapters[1].isVolume, 0
        ).text
        val hlStart = ch1Dom.indexOf("夕阳西下")
        val hl = HighlightEntity(1L, oldBook.id, 1, hlStart, hlStart + 4, "夕阳西下", 1, "写景")
        val progress = ProgressEntity(oldBook.id, chapterIndex = 1, charOffset = hlStart, percent = 0.6f)

        // 用户在外部直接改写了同一个文件（第一章前部增加文字使第二章字节偏移彻底改变，并新增第三章）
        sharedFile.writeText(
            "第一章 晨钟\n清晨薄雾笼罩山谷，古寺钟声悠远，惊起林间飞鸟。\n第二章 暮鼓\n夜色渐浓，夕阳西下，山门紧闭。\n第三章 星夜\n银河横跨天际。",
            Charsets.UTF_8
        )
        val newStream = StreamingTxtProcessor.indexRaw(sharedFile)
        val incomingBook = oldBook.copy(
            fileSize = sharedFile.length(),
            totalChapters = newStream.slices.size,
            totalChars = newStream.totalChars
        )
        val newChapters = newStream.slices.mapIndexed { idx, s ->
            ChapterEntity(
                bookId = oldBook.id, index = idx, title = s.title,
                startByte = s.startByte, endByte = s.endByte,
                charCount = s.charCount.toLong(), titleLineChars = s.titleLineChars, isVolume = s.isVolume
            )
        }

        val mapped = BookEditionUpdater.map(
            old = BookEditionUpdater.Records(oldBook, oldChapters, progress, emptyList(), listOf(hl)),
            incoming = incomingBook,
            chapters = newChapters,
            relation = EditionRelation.UNKNOWN,
            textProvider = env.textProvider
        )
        assertEquals("原地覆盖后应安全回退至同名第二章开头而非错切乱码", ResumeImpact.CHAPTER_START, mapped.preview.resume)
        assertEquals(1, mapped.progress?.chapterIndex)
        val migratedHl = mapped.highlights.single()
        assertEquals("原地覆盖后第二章内的划线应按原文在同名章内找回", 1, migratedHl.chapterIndex)
        assertEquals("夕阳西下", migratedHl.text)
    }

    @Test
    fun unreadableSourcePreservesHighlightsInMultiVolumeDuplicateTitlesAndSyntheticEpubChapters() = runBlocking {
        val env = TestHarness(this)

        // 1. 多卷各有“第一章”的网文，原文件丢失后关联增补版
        val v1File = temp.newFile("volumes_v1.txt").apply {
            writeText(
                "第一卷 初入\n第一章 少年\n青山村的铁匠铺炉火通明。\n第二卷 远行\n第一章 少年\n渡口白帆点点，船夫解开缆绳。",
                Charsets.UTF_8
            )
        }
        val v1Uri = "content://external/books/volumes_v1.txt"
        env.saf.mount(v1Uri, v1File, persistable = true)
        assertEquals(1, env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "分卷小说.txt", v1Uri))).success)
        val txtBook = env.bookDao.records.values.single()
        val txtOldChapters = env.books.getChapters(txtBook.id)
        val vol2Ch1Index = txtOldChapters.lastIndex
        val vol2Dom = com.mirad.reader.core.model.ChapterTextMap(
            env.textProvider.readText(txtBook, txtOldChapters[vol2Ch1Index]),
            txtOldChapters[vol2Ch1Index].title, txtOldChapters[vol2Ch1Index].titleLineChars, txtOldChapters[vol2Ch1Index].isVolume, 0
        ).text
        val targetPhrase = "船夫解开缆绳"
        val pStart = vol2Dom.indexOf(targetPhrase)
        val hlId = env.highlightDao.insert(
            HighlightEntity(
                bookId = txtBook.id, chapterIndex = vol2Ch1Index,
                startOffset = pStart, endOffset = pStart + targetPhrase.length,
                text = targetPhrase, color = 2, note = "第二卷同名章笔记"
            )
        )

        // 原文件删除，关联到第一卷多了一章的新版（第二卷的“第一章 少年”下标后移了 1 位）
        env.saf.unmount(v1Uri)
        v1File.delete()
        val v2File = temp.newFile("volumes_v2.txt").apply {
            writeText(
                "第一卷 初入\n第一章 少年\n青山村的铁匠铺炉火通明。\n第二章 辞行\n背上行囊告别长辈。\n第二卷 远行\n第一章 少年\n渡口白帆点点，船夫解开缆绳。",
                Charsets.UTF_8
            )
        }
        val v2Uri = "content://external/books/volumes_v2.txt"
        env.saf.mount(v2Uri, v2File, persistable = true)

        env.maintenance.relinkSource(txtBook.id, v2Uri, v2Uri)
        val migratedNote = env.highlightDao.getById(hlId)!!
        assertEquals("即使存在多个同名『第一章』且下标偏移，划线仍应精准定位到第二卷的对应章节", 4, migratedNote.chapterIndex)
        assertEquals(targetPhrase, migratedNote.text)

        // 2. 合成标题（“第 1 节”）的 EPUB 原文件丢失后关联修订版：进度应回退到同序号章首而非 UNAVAILABLE
        val epub1 = buildSampleEpub(
            temp.newFile("synth1.epub"),
            chapters = listOf(
                "OEBPS/s1.xhtml" to "第 1 节" to "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>旧版第一节内容。</p></body></html>",
                "OEBPS/s2.xhtml" to "第 2 节" to "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>旧版第二节内容。</p></body></html>"
            )
        )
        val epub1Uri = "content://external/books/synth1.epub"
        env.saf.mount(epub1Uri, epub1, persistable = true)
        assertEquals(1, env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "无目录书.epub", epub1Uri))).success)
        val epubBook = env.bookDao.records.values.first { it.format == "EPUB" }
        val oldEpubChapters = env.books.getChapters(epubBook.id)
        env.saf.unmount(epub1Uri)
        epub1.delete()

        val epub2 = buildSampleEpub(
            temp.newFile("synth2.epub"),
            chapters = listOf(
                "OEBPS/s1.xhtml" to "第 1 节" to "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>修订后的第一节更长正文。</p></body></html>",
                "OEBPS/s2.xhtml" to "第 2 节" to "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>修订后的第二节全新正文。</p></body></html>",
                "OEBPS/s3.xhtml" to "第 3 节" to "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>新增第三节。</p></body></html>"
            )
        )
        val epub2Uri = "content://external/books/synth2.epub"
        env.saf.mount(epub2Uri, epub2, persistable = true)
        env.books.saveProgress(ProgressEntity(epubBook.id, chapterIndex = 1, charOffset = 5, percent = 0.5f))
        env.maintenance.relinkSource(epubBook.id, epub2Uri, epub2Uri)
        val epubProgress = env.books.getProgress(epubBook.id)!!
        assertEquals(1, epubProgress.chapterIndex)
        assertEquals(0, epubProgress.charOffset)
        assertEquals(oldEpubChapters.size + 1, env.books.getChapters(epubBook.id).size)
    }

    @Test
    fun zeroCopyApplyChaptersReadsOnlyReferencedChaptersInsteadOfFullBook() = runBlocking {
        val env = TestHarness(this)
        val twentyChapters = (1..20).joinToString("\n") { idx ->
            "第${idx}章 章节标题$idx\n" + "这是第${idx}章的正文内容，山水相连。".repeat(30)
        }
        val txtFile = temp.newFile("twenty_chapters.txt").apply { writeText(twentyChapters, Charsets.UTF_8) }
        val uri = "content://external/books/twenty.txt"
        env.saf.mount(uri, txtFile, persistable = true)

        assertEquals(1, env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "二十章测试.txt", uri))).success)
        val book = env.bookDao.records.values.single()
        // 仅在第 15 章（index = 14）保存阅读进度，其余 19 章没有任何书签或划线
        env.books.saveProgress(ProgressEntity(bookId = book.id, chapterIndex = 14, charOffset = 10, percent = 0.7f))

        val preview = env.maintenance.previewChapters(book)
        env.saf.rangeReads.clear()
        val count = env.maintenance.applyChapters(book, preview)
        assertEquals(20, count)
        assertEquals(14, env.books.getProgress(book.id)?.chapterIndex)
        assertEquals(10, env.books.getProgress(book.id)?.charOffset)
        // rawSourceDigest 采样至多 3 次 + 旧第14章 1 次 + 候选新章节至多 3 次，远小于全书 20 章全读
        assertTrue(
            "applyChapters 应仅按需读取被引用章节而非全书 20 章，实际 rangeReads=${env.saf.rangeReads.size}",
            env.saf.rangeReads.size <= 8
        )
    }

    // ------------------------------------------------------------------ 测试夹具

    private inner class TestHarness(scope: kotlinx.coroutines.CoroutineScope) {
        val context = TestContext(tempFolderRoot())
        val paths = AppPaths(context)
        val saf = FakeSafBackend()
        val contentAccess = BookContentAccess(null, saf)
        val bookDao = InMemoryBookDao()
        val chapterDao = InMemoryChapterDao()
        val progressDao = InMemoryProgressDao()
        val bookmarkDao = InMemoryBookmarkDao()
        val highlightDao = InMemoryHighlightDao()
        val books = BookRepository(bookDao, chapterDao, progressDao, paths)
        val textProvider = ChapterTextProvider(paths, contentAccess)
        val pipeline = ImportPipeline(context, paths, books, scope, appDatabase = null, contentAccess = contentAccess)
        val maintenance = BookMaintenance(books, paths, bookmarkDao, highlightDao, contentAccess = contentAccess)

        private fun tempFolderRoot(): File = temp.newFolder()
    }

    private fun buildSampleEpub(
        file: File,
        chapters: List<Pair<Pair<String, String>, String>>,
        oversizedBytes: Int = 0
    ): File {
        ZipOutputStream(file.outputStream()).use { zip ->
            val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val storedMime = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimeBytes.size.toLong()
                compressedSize = mimeBytes.size.toLong()
                crc = CRC32().also { it.update(mimeBytes) }.value
            }
            zip.putNextEntry(storedMime)
            zip.write(mimeBytes)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""".trimIndent().toByteArray()
            )
            zip.closeEntry()

            val manifestItems = chapters.mapIndexed { i, (meta, _) ->
                val rel = meta.first.removePrefix("OEBPS/")
                """<item id="ch$i" href="$rel" media-type="application/xhtml+xml"/>"""
            }.joinToString("\n")
            val spineRefs = chapters.indices.joinToString("\n") { """<itemref idref="ch$it"/>""" }
            val navLinks = chapters.joinToString("\n") { (meta, _) ->
                val rel = meta.first.removePrefix("OEBPS/")
                """<li><a href="$rel">${meta.second}</a></li>"""
            }

            zip.putNextEntry(ZipEntry("OEBPS/nav.xhtml"))
            zip.write(
                """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <body><nav epub:type="toc"><ol>$navLinks</ol></nav></body></html>""".trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <package version="3.0" xmlns="http://www.idpf.org/2007/opf">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>测试零拷贝EPUB</dc:title></metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    $manifestItems
                  </manifest>
                  <spine>$spineRefs</spine>
                </package>""".trimIndent().toByteArray()
            )
            zip.closeEntry()

            for ((meta, html) in chapters) {
                zip.putNextEntry(ZipEntry(meta.first))
                zip.write(html.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            if (oversizedBytes > 0) {
                zip.putNextEntry(ZipEntry("OEBPS/oversized.bin"))
                zip.write(ByteArray(oversizedBytes) { 0x41 })
                zip.closeEntry()
            }
        }
        return file
    }

    private class InMemoryBookDao : BookDao {
        val records = linkedMapOf<Long, BookEntity>()
        private var nextId = 1L
        private val flow = MutableStateFlow<List<BookEntity>>(emptyList())

        override suspend fun insert(book: BookEntity): Long {
            val id = nextId++
            records[id] = book.copy(id = id)
            return id
        }
        override suspend fun update(book: BookEntity) { records[book.id] = book }
        override suspend fun delete(book: BookEntity) { records.remove(book.id) }
        override suspend fun getById(id: Long): BookEntity? = records[id]
        override fun observeAll(): Flow<List<BookEntity>> = flow
        override suspend fun getAll(): List<BookEntity> = records.values.toList()
        override suspend fun findDuplicateCandidates(title: String, fileSize: Long): List<BookEntity> =
            records.values.filter { it.title == title && it.fileSize == fileSize }
        override suspend fun setPinned(id: Long, pinned: Boolean) { records[id]?.let { records[id] = it.copy(isPinned = pinned) } }
        override suspend fun setHidden(id: Long, hidden: Boolean) { records[id]?.let { records[id] = it.copy(isHidden = hidden) } }
        override suspend fun setGroup(id: Long, groupId: Long?) { records[id]?.let { records[id] = it.copy(groupId = groupId) } }
        override suspend fun touchLastRead(id: Long, time: Long) { records[id]?.let { records[id] = it.copy(lastReadAt = time) } }
        override suspend fun updateMeta(id: Long, title: String, author: String?) { records[id]?.let { records[id] = it.copy(title = title, author = author) } }
        override suspend fun setFinished(id: Long, finished: Boolean, endChapterIndex: Int?): Int {
            val b = records[id] ?: return 0
            records[id] = b.copy(finished = finished)
            return 1
        }
        override suspend fun updateCounts(id: Long, chapters: Int, chars: Long) {
            records[id]?.let { records[id] = it.copy(totalChapters = chapters, totalChars = chars) }
        }
        override suspend fun updateIndexInfo(id: Long, chapters: Int, chars: Long, ruleId: String?, regex: String?, encoding: String?) {
            records[id]?.let {
                records[id] = it.copy(totalChapters = chapters, totalChars = chars, chapterRuleId = ruleId, chapterRegex = regex, encoding = encoding)
            }
        }
    }

    private class InMemoryChapterDao : ChapterDao {
        private val items = mutableListOf<ChapterEntity>()
        override suspend fun insertAll(chapters: List<ChapterEntity>) { items += chapters }
        override suspend fun deleteForBook(bookId: Long) { items.removeAll { it.bookId == bookId } }
        override suspend fun getForBook(bookId: Long): List<ChapterEntity> = items.filter { it.bookId == bookId }.sortedBy { it.index }
        override suspend fun getChapter(bookId: Long, index: Int): ChapterEntity? = items.firstOrNull { it.bookId == bookId && it.index == index }
        override suspend fun countForBook(bookId: Long): Int = items.count { it.bookId == bookId }
    }

    private class InMemoryProgressDao : ProgressDao {
        private val map = mutableMapOf<Long, ProgressEntity>()
        override suspend fun upsert(progress: ProgressEntity) { map[progress.bookId] = progress }
        override suspend fun get(bookId: Long): ProgressEntity? = map[bookId]
        override fun observeAll(): Flow<List<ProgressEntity>> = MutableStateFlow(map.values.toList())
        override suspend fun deleteForBook(bookId: Long) { map.remove(bookId) }
    }

    private class InMemoryBookmarkDao : BookmarkDao {
        private val map = linkedMapOf<Long, BookmarkEntity>()
        private var nextId = 1L
        override suspend fun insert(bookmark: BookmarkEntity): Long {
            val id = nextId++
            map[id] = bookmark.copy(id = id)
            return id
        }
        override suspend fun update(bookmark: BookmarkEntity) { map[bookmark.id] = bookmark }
        override suspend fun delete(bookmark: BookmarkEntity) { map.remove(bookmark.id) }
        override suspend fun getById(id: Long): BookmarkEntity? = map[id]
        override suspend fun deleteForBook(bookId: Long) { map.values.removeAll { it.bookId == bookId } }
        override fun observeForBook(bookId: Long): Flow<List<BookmarkEntity>> = MutableStateFlow(emptyList())
        override suspend fun find(bookId: Long, chapterIndex: Int, charOffset: Int): BookmarkEntity? =
            map.values.firstOrNull { it.bookId == bookId && it.chapterIndex == chapterIndex && it.charOffset == charOffset }
        override suspend fun getAllForBook(bookId: Long): List<BookmarkEntity> = map.values.filter { it.bookId == bookId }
        override suspend fun getAll(): List<BookmarkEntity> = map.values.toList()
    }

    private class InMemoryHighlightDao : HighlightDao {
        private val map = linkedMapOf<Long, HighlightEntity>()
        private var nextId = 1L
        override suspend fun insert(highlight: HighlightEntity): Long {
            val id = nextId++
            map[id] = highlight.copy(id = id)
            return id
        }
        override suspend fun update(highlight: HighlightEntity) { map[highlight.id] = highlight }
        override suspend fun delete(highlight: HighlightEntity) { map.remove(highlight.id) }
        override suspend fun getById(id: Long): HighlightEntity? = map[id]
        override suspend fun deleteForBook(bookId: Long) { map.values.removeAll { it.bookId == bookId } }
        override fun observeForBook(bookId: Long): Flow<List<HighlightEntity>> = MutableStateFlow(emptyList())
        override suspend fun getForChapter(bookId: Long, chapterIndex: Int): List<HighlightEntity> =
            map.values.filter { it.bookId == bookId && it.chapterIndex == chapterIndex }
        override suspend fun getAllForBook(bookId: Long): List<HighlightEntity> = map.values.filter { it.bookId == bookId }
        override suspend fun getAll(): List<HighlightEntity> = map.values.toList()
    }
}
