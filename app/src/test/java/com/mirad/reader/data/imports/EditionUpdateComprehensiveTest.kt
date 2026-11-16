package com.mirad.reader.data.imports

import android.content.ContextWrapper
import com.mirad.reader.core.epub.ZipEntryReader
import com.mirad.reader.core.sources.BookDownloadStatus
import com.mirad.reader.core.sources.BookSource
import com.mirad.reader.core.sources.BookSourceEngine
import com.mirad.reader.core.sources.RemoteBook
import com.mirad.reader.core.sources.RemoteChapter
import com.mirad.reader.core.sources.SourceCatalog
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.dao.BookDao
import com.mirad.reader.data.db.dao.BookmarkDao
import com.mirad.reader.data.db.dao.ChapterDao
import com.mirad.reader.data.db.dao.HighlightDao
import com.mirad.reader.data.db.dao.PreferenceDao
import com.mirad.reader.data.db.dao.ProgressDao
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookmarkEntity
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.db.entity.HighlightEntity
import com.mirad.reader.data.db.entity.PreferenceEntity
import com.mirad.reader.data.db.entity.ProgressEntity
import com.mirad.reader.data.downloads.BookDownloadManager
import com.mirad.reader.data.io.BookContentAccess
import com.mirad.reader.data.repo.BookMaintenance
import com.mirad.reader.data.repo.BookRepository
import com.mirad.reader.data.repo.ChapterTextProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EditionUpdateComprehensiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun defaultTitleUpdatesToNewFileWhileUserEditedTitleAndAuthorArePreservedAndFinishedResets() = runBlocking {
        val env = Harness(this)
        val ch1 = "第一章 骊珠洞天\n" + "少年陈平安走在泥瓶巷中，春雨绵绵。".repeat(40)
        val ch2 = "\n\n第二章 风起小镇\n" + "老槐树下棋局渐深，檐下水珠滴落。".repeat(40)
        val ch3 = "\n\n第三章 远游万里\n" + "背起竹箱走出小镇，山河辽阔。".repeat(40)

        val f1 = temp.newFile("v1.txt").apply { writeText(ch1, Charsets.UTF_8) }
        val u1 = "content://external/books/剑来(1-500章).txt"
        env.saf.mount(u1, f1, persistable = true)

        // 1. 初次导入默认书名为“剑来(1-500章)”，并模拟已读到末章（finished = true）
        val s1 = env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "剑来(1-500章).txt", u1)))
        assertEquals(1, s1.success)
        val oldId = s1.bookIds.single()
        val oldCoverToken = UUID.randomUUID().toString()
        val oldCoverFile = env.paths.coverFile(oldCoverToken).apply {
            parentFile?.mkdirs()
            writeText("old-cover")
        }
        env.bookDao.update(env.books.getBook(oldId)!!.copy(coverPath = oldCoverFile.absolutePath, finished = true))
        env.books.saveProgress(ProgressEntity(oldId, chapterIndex = 0, charOffset = 20, percent = 1f))

        // 2. 导入续章新版“剑来(1-800章).txt”：默认书名自动更新为新文件名，且 finished 自动解除为 false
        val f2 = temp.newFile("v2.txt").apply { writeText(ch1 + ch2, Charsets.UTF_8) }
        val u2 = "content://external/books/剑来(1-800章).txt"
        env.saf.mount(u2, f2, persistable = true)

        val s2 = env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "剑来(1-800章).txt", u2)))
        assertEquals(1, s2.updated)
        assertTrue("更新结果明细应注明书名与章数变化", s2.messages.single().contains("已更新《剑来(1-800章)》（1 章 → 2 章）"))
        val updatedBook = env.books.getBook(oldId)!!
        assertEquals("未手动编辑过的默认书名应随新文件同步更新", "剑来(1-800章)", updatedBook.title)
        assertFalse("连载新增章节后『已读完』状态必须自动解除", updatedBook.finished)

        // 2.5 用户仅手动补填作者（未改书名）后更新：书名继续随新文件更新，作者保留，且若有新封面不应锁死在旧封面路径上
        env.books.updateMeta(oldId, "剑来(1-800章)", "烽火戏诸侯")
        val updater = BookEditionUpdater(env.books, env.bookmarkDao, env.highlightDao, null, env.contentAccess)
        val candBeforeAuthorTest = env.books.getBook(oldId)!!
        val incomingOnlyTitleChanged = candBeforeAuthorTest.copy(
            id = 0L,
            title = "剑来(1-900章)",
            author = null,
            coverPath = env.paths.coverFile(UUID.randomUUID().toString()).apply { writeText("fresh-cover") }.absolutePath
        )
        val inspectedOnlyAuthor = updater.inspect(
            EditionCandidate(
                candBeforeAuthorTest,
                EditionRelation.EXTENDS,
                false,
                EditionMatcher.versionModified(env.contentAccess, candBeforeAuthorTest),
                exactCharPrefix = true
            ),
            incomingOnlyTitleChanged,
            env.books.getChapters(oldId)
        )
        val publishedOnlyAuthor = updater.publish(inspectedOnlyAuthor, incomingOnlyTitleChanged, env.books.getChapters(oldId), SourceVersion.capture(env.contentAccess, incomingOnlyTitleChanged.filePath))
        assertEquals("仅改过作者而未改书名时，书名仍应随新文件更新", "剑来(1-900章)", publishedOnlyAuthor.title)
        assertEquals("用户补填的作者必须保留", "烽火戏诸侯", publishedOnlyAuthor.author)
        assertEquals("仅改作者但书名已更新时，封面路径应切换为新封面而非停留在旧书名封面", incomingOnlyTitleChanged.coverPath, publishedOnlyAuthor.coverPath)

        // 3. 用户在书架手动编辑书名与作者后再导入更新版：手动改过的书名与作者必须保留
        env.books.updateMeta(oldId, "剑来（珍藏精校）", "烽火戏诸侯")
        val f3 = temp.newFile("v3.txt").apply { writeText(ch1 + ch2 + ch3, Charsets.UTF_8) }
        val u3 = "content://external/books/剑来(1-1000章).txt"
        env.saf.mount(u3, f3, persistable = true)

        val s3 = env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "剑来(1-1000章).txt", u3)))
        assertEquals(1, s3.updated)
        val afterUserEdit = env.books.getBook(oldId)!!
        assertEquals("用户手动改过的书名在后续更新时必须保留", "剑来（珍藏精校）", afterUserEdit.title)
        assertEquals("用户手动改过的作者在后续更新时必须保留", "烽火戏诸侯", afterUserEdit.author)
        assertEquals(3, afterUserEdit.totalChapters)

        // 4. 验证零拷贝书籍更新封面时 LocalImportStaging 能通过 managedCoverToken 清理不再引用的旧封面
        val staging = LocalImportStaging(env.paths)
        val newCoverToken = UUID.randomUUID().toString()
        val newCoverFile = env.paths.coverFile(newCoverToken).apply { writeText("new-cover") }
        staging.mark(newCoverToken)
        staging.beforeReplacement(newCoverToken, afterUserEdit.copy(coverPath = oldCoverFile.absolutePath))
        env.bookDao.update(afterUserEdit.copy(coverPath = newCoverFile.absolutePath))
        staging.finish(newCoverToken, env.books.getAllBooks())
        assertFalse("零拷贝书籍替换封面后，旧封面文件必须被自动清理", oldCoverFile.exists())
        assertTrue("当前引用的新封面文件必须保留", newCoverFile.exists())
    }

    @Test
    fun relinkUpdatesUneditedTitleResetsFinishedAndKeepsUserEditedTitle() = runBlocking {
        val env = Harness(this)
        val f1 = temp.newFile("relink_v1.txt").apply {
            writeText("第一章 旧章\n" + "江南春水绿如蓝。".repeat(30), Charsets.UTF_8)
        }
        val u1 = "content://external/books/诗词选_旧名.txt"
        env.saf.mount(u1, f1, persistable = true)
        val id = env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "诗词选_旧名.txt", u1))).bookIds.single()
        env.books.setFinished(id, true, endChapterIndex = 0)
        assertTrue(env.books.getBook(id)!!.finished)

        // 原文件丢失，重关联到改名且扩充了第二章的新文件：默认书名更新，finished 自动解除
        env.saf.unmount(u1)
        f1.delete()
        val f2 = temp.newFile("relink_v2.txt").apply {
            writeText(
                "第一章 旧章\n" + "江南春水绿如蓝。".repeat(30) + "\n第二章 新增\n塞北秋风劲如刀。",
                Charsets.UTF_8
            )
        }
        val u2 = "content://external/books/诗词选_全本新版.txt"
        env.saf.mount(u2, f2, persistable = true)
        val relinked = env.maintenance.relinkSource(id, u2, u2, "诗词选_全本新版.txt") { true }!!
        assertEquals("诗词选_全本新版", relinked.title)
        assertFalse("重关联到续章文件后 finished 应复位", relinked.finished)

        // 手动改名后再次重关联：保留手动改过的书名
        env.books.updateMeta(id, "我的诗词选", "白居易")
        val relinked2 = env.maintenance.relinkSource(id, u2, u2, "随便什么文件名.txt") { true }!!
        assertEquals("我的诗词选", relinked2.title)
        assertEquals("白居易", relinked2.author)
    }

    @Test
    fun coreTitleKeyAndIntroTolerantMatchingHandleSerializedTxtEpubAndDeletedOldSource() = runBlocking {
        // 1. 核心书名提取核验
        assertEquals("诡秘之主", coreTitleKey("《诡秘之主》精校全本（1-1394章）"))
        assertEquals("诡秘之主", coreTitleKey("诡秘之主_更新至第800章"))
        assertEquals("诡秘之主", coreTitleKey("[完结]诡秘之主 作者：爱潜水的乌贼"))
        assertEquals("道诡异仙", coreTitleKey("道诡异仙(1-500) v2.1"))

        val env = Harness(this)

        // 2. TXT 新版在开头新增整理声明与简介，且文件名带新章号：仍自动识别 EXTENDS 并精确定位续读
        val chapter1Body = "第一章 噩梦\n" + "火旺猛地从病床上坐起，冷汗浸透了衣衫，窗外雷声滚滚。".repeat(30)
        val chapter2Body = "\n\n第二章 幻觉\n" + "铜钱剑悬在梁上微微颤动，烛火摇曳不定。".repeat(30)
        val txtV1 = temp.newFile("dao_v1.txt").apply { writeText(chapter1Body, Charsets.UTF_8) }
        val uriV1 = "content://external/books/dao_1_100.txt"
        env.saf.mount(uriV1, txtV1, persistable = true)

        val idTxt = env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "道诡异仙(1-100章).txt", uriV1))).bookIds.single()
        val ch0Dom = com.mirad.reader.core.model.ChapterTextMap(
            env.textProvider.readText(env.books.getBook(idTxt)!!, env.books.getChapters(idTxt)[0]),
            "第一章 噩梦", "第一章 噩梦\n".length, false, 0
        ).text
        val readTarget = "窗外雷声滚滚"
        val oldOffset = ch0Dom.indexOf(readTarget)
        assertTrue(oldOffset > 0)
        env.books.saveProgress(ProgressEntity(idTxt, chapterIndex = 0, charOffset = oldOffset, percent = 0.5f))

        val introHeader = "【本书由热心书友整理，仅供个人学习交流】\n内容简介：修真与克苏鲁交织的诡谲世界。\n\n"
        val txtV2 = temp.newFile("dao_v2.txt").apply { writeText(introHeader + chapter1Body + chapter2Body, Charsets.UTF_8) }
        val uriV2 = "content://external/books/dao_1_200.txt"
        env.saf.mount(uriV2, txtV2, persistable = true)

        val updateTxtSummary = env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "道诡异仙(1-200章).txt", uriV2)))
        assertEquals("即使开头多了简介且改了章号文件名，仍应自动无感续章更新", 1, updateTxtSummary.updated)
        val newProgress = env.books.getProgress(idTxt)!!
        val updatedTxtBook = env.books.getBook(idTxt)!!
        val newChDom = com.mirad.reader.core.model.ChapterTextMap(
            env.textProvider.readText(updatedTxtBook, env.books.getChapters(idTxt)[newProgress.chapterIndex]),
            env.books.getChapters(idTxt)[newProgress.chapterIndex].title,
            env.books.getChapters(idTxt)[newProgress.chapterIndex].titleLineChars,
            false, 0
        ).text
        assertEquals(readTarget, newChDom.substring(newProgress.charOffset, newProgress.charOffset + readTarget.length))

        // 3. EPUB 第 0 页（最新章节/日期说明页）变动，后续正文章不变且尾部新增章节：自动识别 EXTENDS 更新
        val epubOld = buildEpub(
            temp.newFile("serial_v1.epub"),
            title = "星海纪元(1-50)",
            chapters = listOf(
                "info.xhtml" to "制作说明" to "<html><body><p>打包日期：2026-10-01，更新至第 1 章</p></body></html>",
                "c1.xhtml" to "第一章 启航" to "<html><body><h1>第一章 启航</h1><p>星舰驶出木星船坞，引擎喷射幽蓝尾焰。</p></body></html>"
            )
        )
        val epubOldUri = "content://external/books/serial_v1.epub"
        env.saf.mount(epubOldUri, epubOld, persistable = true)
        val epubId = env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "星海纪元(1-50).epub", epubOldUri))).bookIds.single()

        val epubNew = buildEpub(
            temp.newFile("serial_v2.epub"),
            title = "星海纪元(1-100)",
            chapters = listOf(
                "info.xhtml" to "制作说明" to "<html><body><p>打包日期：2026-11-14，更新至第 2 章</p></body></html>",
                "c1.xhtml" to "第一章 启航" to "<html><body><h1>第一章 启航</h1><p>星舰驶出木星船坞，引擎喷射幽蓝尾焰。</p></body></html>",
                "c2.xhtml" to "第二章 跃迁" to "<html><body><h1>第二章 跃迁</h1><p>曲率泡张开，群星化作流光。</p></body></html>"
            )
        )
        val epubNewUri = "content://external/books/serial_v2.epub"
        env.saf.mount(epubNewUri, epubNew, persistable = true)
        val epubUpdateSummary = env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "星海纪元(1-100).epub", epubNewUri)))
        assertEquals("EPUB 首部短说明页变动不应阻断自动续章识别", 1, epubUpdateSummary.updated)
        assertEquals("星海纪元(1-100)", env.books.getBook(epubId)!!.title)

        // 4. 旧文件已被用户提前删除，导入改了章号后缀的新文件：通过核心书名成功召回旧书候选
        env.saf.unmount(uriV2)
        txtV2.delete()
        val txtV3 = temp.newFile("dao_v3.txt").apply {
            writeText("第一章 噩梦\n修订后的全新正文，且旧原文件已不可读。", Charsets.UTF_8)
        }
        val uriV3 = "content://external/books/dao_1_300.txt"
        env.saf.mount(uriV3, txtV3, persistable = true)
        val deletedOldSummary = env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "道诡异仙(1-300章).txt", uriV3)))
        assertEquals("原文件已删除但核心书名匹配时应进入待确认而非静默新建第二本书", 1, deletedOldSummary.pending.size)
        assertEquals(idTxt, deletedOldSummary.pending.single().review!!.candidates.single().bookId)
    }

    @Test
    fun syntheticEpubRevisionAutoPreviewsChapterStartWhileUnrelatedUnavailableStaysBlocked() = runBlocking {
        val env = Harness(this)

        // 1. 无目录合成标题 EPUB（“第 1 节”、“第 2 节”）原文件仍可读，第 1 节相同、第 2 节有修订：
        //    唯一候选自动预计算 preview = CHAPTER_START（无需先点核对），且可确认更新
        val synth1 = buildEpub(
            temp.newFile("synth_read_1.epub"),
            title = "无目录手记",
            includeNav = false,
            chapters = listOf(
                "s1.xhtml" to "" to "<html><body><p>第一节保持完全不变的锚点正文内容。</p></body></html>",
                "s2.xhtml" to "" to "<html><body><p>第二节旧版待大修的草稿文字。</p></body></html>"
            )
        )
        val u1 = "content://external/books/synth_read_1.epub"
        env.saf.mount(u1, synth1, persistable = true)
        val epubId = env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "无目录手记.epub", u1))).bookIds.single()
        env.books.saveProgress(ProgressEntity(epubId, chapterIndex = 1, charOffset = 4, percent = 0.6f))

        val synth2 = buildEpub(
            temp.newFile("synth_read_2.epub"),
            title = "无目录手记",
            includeNav = false,
            chapters = listOf(
                "s1.xhtml" to "" to "<html><body><p>第一节保持完全不变的锚点正文内容。</p></body></html>",
                "s2.xhtml" to "" to "<html><body><p>第二节已被作者彻底重写的正式定稿。</p></body></html>",
                "s3.xhtml" to "" to "<html><body><p>新增的第三节内容。</p></body></html>"
            )
        )
        val u2 = "content://external/books/synth_read_2.epub"
        env.saf.mount(u2, synth2, persistable = true)
        val reviewSummary = env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "无目录手记.epub", u2)))
        val pendingItem = reviewSummary.pending.single()
        val autoPreview = pendingItem.review?.preview
        assertNotNull("单候选进入待确认时必须自动预计算续读预览，省去多余点击", autoPreview)
        assertEquals(ResumeImpact.CHAPTER_START, autoPreview!!.resume)
        assertEquals("第 2 节", autoPreview.chapterTitle)

        // 2. 完全无关的同名书（章名与正文均对不上 -> UNAVAILABLE）：必须严格维持禁止更新
        val unrelated = buildEpub(
            temp.newFile("synth_unrelated.epub"),
            title = "无目录手记",
            includeNav = false,
            chapters = listOf(
                "x1.xhtml" to "" to "<html><body><p>完全是另一本书的正文，没有任何一节相同。</p></body></html>"
            )
        )
        val u3 = "content://external/books/synth_unrelated.epub"
        env.saf.mount(u3, unrelated, persistable = true)
        val blockedSummary = env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "无目录手记_误导入.epub", u3)))
        val blockedItem = blockedSummary.pending.single()
        val blockedPreview = blockedItem.review!!.preview!!
        assertEquals(ResumeImpact.UNAVAILABLE, blockedPreview.resume)

        env.pipeline.confirmUpdate(blockedItem.id, blockedPreview)
        // 等待后台协程处理，验证旧书未被覆盖
        withTimeout(2000) {
            kotlinx.coroutines.delay(80)
        }
        assertEquals("UNAVAILABLE 时 confirmUpdate 必须拒绝覆盖旧书", u1, env.books.getBook(epubId)!!.filePath)
    }

    @Test
    fun urlOriginAndBookSourceDownloadsUpdateExistingShelfBook() = runBlocking {
        val env = Harness(this)
        val source = BookSource("src-1", "测试书源", "https://novel.test", JsonObject(emptyMap()))
        val bookUrl = "https://novel.test/book/99"
        val chaptersContent = ConcurrentHashMap<Int, String>().apply {
            put(0, "这是书源第一章的正文内容，风雪夜归人。")
            put(1, "这是书源第二章的正文内容，柴门闻犬吠。")
            put(2, "这是书源新增第三章的正文内容，千里共婵娟。")
        }
        val engine = object : BookSourceEngine {
            override suspend fun search(source: BookSource, query: String, page: Int) = emptyList<RemoteBook>()
            override suspend fun bookInfo(source: BookSource, book: RemoteBook) = book
            override suspend fun toc(source: BookSource, book: RemoteBook) = SourceCatalog(book, emptyList())
            override suspend fun content(source: BookSource, book: RemoteBook, chapter: RemoteChapter, nextChapterUrl: String?) =
                chaptersContent.getValue(chapter.index)
        }

        val dlRoot = temp.newFolder("book-downloads")
        val receipts = DownloadImportReceipts(env.preferenceDao) { env.books.getBook(it) != null }
        val manager = BookDownloadManager(
            root = dlRoot,
            engine = engine,
            importer = env.pipeline,
            scope = this,
            findImported = receipts::find,
            networkAvailable = { true }
        )

        // 1. 首次从书源下载前 2 章入库
        val cat2 = SourceCatalog(
            RemoteBook(source.id, source.name, "风雪行", bookUrl, author = "刘长卿"),
            (0..1).map { RemoteChapter("第${it + 1}章", "$bookUrl/$it", index = it) }
        )
        manager.enqueue(source, cat2)
        manager.runQueue()
        val firstTask = manager.tasks.value.single()
        assertEquals(BookDownloadStatus.COMPLETED, firstTask.status)
        val shelfBookId = firstTask.bookId!!
        assertEquals(2, env.books.getBook(shelfBookId)!!.totalChapters)

        // 2. 连载更新至 3 章后再次从书源下载：自动原地更新已有旧书，不重复新建第二本
        val cat3 = SourceCatalog(
            RemoteBook(source.id, source.name, "风雪行", bookUrl, author = "刘长卿"),
            (0..2).map { RemoteChapter("第${it + 1}章", "$bookUrl/$it", index = it) }
        )
        val secondTaskId = manager.enqueue(source, cat3)
        manager.runQueue()
        val secondTask = manager.tasks.value.first { it.id == secondTaskId }
        assertEquals(BookDownloadStatus.COMPLETED, secondTask.status)
        assertEquals("书源下载新版应原地更新原书 ID", shelfBookId, secondTask.bookId)
        assertEquals("书架中仍只有 1 本《风雪行》", 1, env.books.getAllBooks().size)
        assertEquals(3, env.books.getBook(shelfBookId)!!.totalChapters)
        assertNull("书源后台下载新增或更新均不得向全局 lastSummary 投递多余模态弹窗", env.pipeline.lastSummary.value)
    }

    @Test
    fun folderSyncFastSkipsUnchangedFilesAndDetectsEqualLengthInPlaceEdits() = runBlocking {
        val env = Harness(this)
        val f1 = temp.newFile("folder_b1.txt").apply {
            writeText("第一章 青山\n" + "青山依旧在，几度夕阳红。".repeat(50), Charsets.UTF_8)
        }
        val epubFile = buildEpub(
            temp.newFile("folder_b2.epub"),
            title = "文件夹EPUB",
            chapters = listOf("c1.xhtml" to "第一章" to "<html><body><p>白发渔樵江渚上，惯看秋月春风。</p></body></html>")
        )
        val u1 = "content://external/folder/b1.txt"
        val u2 = "content://external/folder/b2.epub"
        env.saf.mount(u1, f1, persistable = true)
        env.saf.mount(u2, epubFile, persistable = true)

        val firstImport = env.pipeline.importAndWait(
            listOf(
                ImportSource.FromUri(null, "青山记.txt", u1),
                ImportSource.FromUri(null, "文件夹EPUB.epub", u2)
            )
        )
        assertEquals(2, firstImport.success)

        // 再次同步导入同一文件夹（两本书均未改动）：除 guessExtension 的 4 字节魔数探测外，不得打开 EPUB ZIP 解压全书
        env.saf.zipOpens.set(0)
        val secondImport = env.pipeline.importAndWait(
            listOf(
                ImportSource.FromUri(null, "青山记.txt", u1),
                ImportSource.FromUri(null, "文件夹EPUB.epub", u2)
            )
        )
        assertEquals(2, secondImport.skipped)
        assertEquals("未改动的同 URI EPUB 应直接命中指纹快照快速跳过，不得重新解压 ZIP", 0, env.saf.zipOpens.get())

        // 原地等长修改 TXT（总字节数、总章数、总字数完全不变，仅修改正文文字）：必须检出变更进入待确认而非误判 SAME 跳过
        val beforeBytes = f1.readBytes()
        val replacedText = "第一章 青山\n" + "绿水长流去，几度夕阳红。" + "青山依旧在，几度夕阳红。".repeat(49)
        val afterBytes = replacedText.toByteArray(Charsets.UTF_8)
        assertEquals("构造等长原地修改前提", beforeBytes.size, afterBytes.size)
        f1.writeBytes(afterBytes)
        f1.setLastModified(System.currentTimeMillis() + 5000L)

        val afterInPlaceEdit = env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "青山记.txt", u1)))
        assertEquals("原地等长改字不得被误判为未变化跳过", 0, afterInPlaceEdit.skipped)
        assertEquals(1, afterInPlaceEdit.pending.size)
    }

    @Test
    fun intraBatchMultiVersionsMergeCleanlyAndHiddenBooksReportLocation() = runBlocking {
        val env = Harness(this)
        val part1 = "第一章 初见\n" + "人生若只如初见，何事秋风悲画扇。".repeat(30)
        val part2 = "\n\n第二章 变却\n" + "等闲变却故人心，却道故人心易变。".repeat(30)
        val part3 = "\n\n第三章 骊山\n" + "骊山语罢清宵半，泪雨霖铃终不怨。".repeat(30)

        val f10 = temp.newFile("batch_10.txt").apply { writeText(part1, Charsets.UTF_8) }
        val f20 = temp.newFile("batch_20.txt").apply { writeText(part1 + part2, Charsets.UTF_8) }
        val f30 = temp.newFile("batch_30.txt").apply { writeText(part1 + part2 + part3, Charsets.UTF_8) }
        env.saf.mount("content://external/batch/10.txt", f10)
        env.saf.mount("content://external/batch/20.txt", f20)
        env.saf.mount("content://external/batch/30.txt", f30)

        // 空书架一次性导入同一本书的 3 个递进版本：最终应合并为 新增 1 · 更新 0 · 跳过 2
        val batchSummary = env.pipeline.importAndWait(
            listOf(
                ImportSource.FromUri(null, "纳兰词(1章).txt", "content://external/batch/10.txt"),
                ImportSource.FromUri(null, "纳兰词(2章).txt", "content://external/batch/20.txt"),
                ImportSource.FromUri(null, "纳兰词(3章).txt", "content://external/batch/30.txt")
            )
        )
        assertEquals("同批次多版本导入空书架应计为新增 1 本", 1, batchSummary.success)
        assertEquals("同批次内的中间过渡版本不应额外累加到更新计数", 0, batchSummary.updated)
        assertEquals(2, batchSummary.skipped)
        val singleBook = env.books.getAllBooks().single()
        assertEquals("纳兰词(3章)", singleBook.title)
        assertEquals(3, singleBook.totalChapters)

        // 将该书设为隐藏后再次导入重复版：提示信息应明确标注“（位于隐藏书籍）”
        env.books.setHidden(singleBook.id, true)
        val hiddenSkip = env.pipeline.importAndWait(
            listOf(ImportSource.FromUri(null, "纳兰词(3章).txt", "content://external/batch/30.txt"))
        )
        assertEquals(1, hiddenSkip.skipped)
        assertTrue(hiddenSkip.messages.single().contains("位于隐藏书籍"))

        // 同批次同时导入两个需要人工确认（REVIEW）的修订版本：自动淘汰较旧修订版，只保留较新修订版待确认
        val revA = temp.newFile("rev_a.txt").apply {
            writeText("第一章 初见\n修订稿甲：" + "何事秋风悲画扇。".repeat(30), Charsets.UTF_8)
        }
        val revB = temp.newFile("rev_b.txt").apply {
            writeText("第一章 初见\n修订稿甲：" + "何事秋风悲画扇。".repeat(30) + "\n\n第四章 决绝\n锦水汤汤，与君长诀。", Charsets.UTF_8)
        }
        env.saf.mount("content://external/batch/rev_a.txt", revA)
        env.saf.mount("content://external/batch/rev_b.txt", revB)
        val revBatch = env.pipeline.importAndWait(
            listOf(
                ImportSource.FromUri(null, "纳兰词_修订1.txt", "content://external/batch/rev_a.txt"),
                ImportSource.FromUri(null, "纳兰词_修订2.txt", "content://external/batch/rev_b.txt")
            )
        )
        assertEquals("同批次两个递进修订版指向同一本旧书时，只保留最新的 1 项待确认", 1, revBatch.pending.size)
        assertEquals("纳兰词_修订2.txt", revBatch.pending.single().displayName)
        assertEquals(1, revBatch.skipped)
    }

    @Test
    fun whitespaceChangesInMiddleChapterDoNotShiftSubsequentChapterProgressOrLoseHighlights() = runBlocking {
        val env = Harness(this)
        val c0 = "第一章 江雪\n" + "千山鸟飞绝，万径人踪灭。".repeat(30)
        val c1Old = "\n\n第二章 渔歌\n" + "西塞山前白鹭飞，桃花流水鳜鱼肥。".repeat(25)
        // 新版第二章每段插入了大量全角缩进与额外换行（+100 余字符），但第一章完全不变
        val c1New = "\n\n第二章 渔歌\n" + "\u3000\u3000西塞山前白鹭飞，桃花流水鳜鱼肥。\n\n".repeat(25)
        val c2 = "\n\n第三章 独钓\n春江潮水连海平，孤舟蓑笠翁，独钓寒江雪，明月共潮生。"
        val c3 = "\n\n第四章 归舟\n" + "轻舟已过万重山，两岸猿声啼不住。".repeat(20)

        val fOld = temp.newFile("mid_ws_old.txt").apply { writeText(c0 + c1Old + c2, Charsets.UTF_8) }
        val uOld = "content://external/books/mid_ws_old.txt"
        env.saf.mount(uOld, fOld)
        val bookId = env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "寒江集(1-3).txt", uOld))).bookIds.single()

        val oldCh2 = env.books.getChapters(bookId)[2]
        val oldCh2Dom = com.mirad.reader.core.model.ChapterTextMap(
            env.textProvider.readText(env.books.getBook(bookId)!!, oldCh2),
            oldCh2.title, oldCh2.titleLineChars, oldCh2.isVolume, 0
        ).text
        val phrase = "孤舟蓑笠翁"
        val pStart = oldCh2Dom.indexOf(phrase)
        assertTrue(pStart > 0)
        env.books.saveProgress(ProgressEntity(bookId, chapterIndex = 2, charOffset = pStart, percent = 0.8f))
        val hlId = env.highlightDao.insert(
            HighlightEntity(
                bookId = bookId,
                chapterIndex = 2,
                startOffset = pStart,
                endOffset = pStart + phrase.length,
                text = phrase,
                color = 1,
                note = "名句"
            )
        )

        val fNew = temp.newFile("mid_ws_new.txt").apply { writeText(c0 + c1New + c2 + c3, Charsets.UTF_8) }
        val uNew = "content://external/books/mid_ws_new.txt"
        env.saf.mount(uNew, fNew)

        val summary = env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "寒江集(1-4).txt", uNew)))
        assertEquals("忽略空白匹配续章且定位精确时应直接完成更新", 1, summary.updated)

        val migratedProgress = env.books.getProgress(bookId)!!
        assertEquals(2, migratedProgress.chapterIndex)
        val newCh2 = env.books.getChapters(bookId)[2]
        val newCh2Dom = com.mirad.reader.core.model.ChapterTextMap(
            env.textProvider.readText(env.books.getBook(bookId)!!, newCh2),
            newCh2.title, newCh2.titleLineChars, newCh2.isVolume, 0
        ).text
        assertEquals(
            "中间章节增删空白不得导致后续章节阅读位置偏移",
            phrase,
            newCh2Dom.substring(migratedProgress.charOffset, migratedProgress.charOffset + phrase.length)
        )
        val migratedHl = env.highlightDao.getById(hlId)!!
        assertEquals("第三章的划线定位不得丢失（不得置为 -1）", 2, migratedHl.chapterIndex)
        assertEquals(phrase, newCh2Dom.substring(migratedHl.startOffset, migratedHl.endOffset))
    }

    @Test
    fun waitingReaderIntraBatchCollapsesToLatestAndBackgroundDownloadsKeepLastSummaryClean() = runBlocking {
        val env = Harness(this)
        val p1 = "第一章 破晓\n" + "晨光微熹，山路崎岖。".repeat(30)
        val p2 = "\n\n第二章 正午\n" + "烈日当空，蝉鸣阵阵。".repeat(30)
        val p3 = "\n\n第三章 黄昏\n" + "落霞满天，倦鸟归林。".repeat(30)

        val f1 = temp.newFile("wait_1.txt").apply { writeText(p1, Charsets.UTF_8) }
        val f2 = temp.newFile("wait_2.txt").apply { writeText(p1 + p2, Charsets.UTF_8) }
        val f3 = temp.newFile("wait_3.txt").apply { writeText(p1 + p2 + p3, Charsets.UTF_8) }
        env.saf.mount("content://external/wait/1.txt", f1)
        env.saf.mount("content://external/wait/2.txt", f2)
        env.saf.mount("content://external/wait/3.txt", f3)

        // 1. importAndWait 新增或跳过重复书籍时不应向全局 lastSummary 塞入多余弹窗（避免网址导入双重弹窗）
        val s1 = env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "山行记(1章).txt", "content://external/wait/1.txt")))
        assertEquals(1, s1.success)
        assertNull("importAndWait 单纯新增完成不应污染全局 lastSummary", env.pipeline.lastSummary.value)
        val sDup = env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "山行记(1章).txt", "content://external/wait/1.txt")))
        assertEquals(1, sDup.skipped)
        assertNull("importAndWait 跳过重复书不应遗留全局 lastSummary 弹窗", env.pipeline.lastSummary.value)

        val bookId = s1.bookIds.single()

        // 2. 模拟用户正在阅读该书（持有 contentLock），同批次导入 2章版 与 3章版：
        //    WAITING_READER 条目应自动去重合并为仅剩最新的 3章版，释放阅读锁后一次性升至 3 章，不降级为人工 REVIEW
        val readerLock = env.books.contentLock(bookId)
        readerLock.lock()
        val waitingSummary = try {
            env.pipeline.importAndWait(
                listOf(
                    ImportSource.FromUri(null, "山行记(2章).txt", "content://external/wait/2.txt"),
                    ImportSource.FromUri(null, "山行记(3章).txt", "content://external/wait/3.txt")
                )
            )
        } finally {
            // 先核验锁释放前的挂起状态
            val pendingBeforeUnlock = env.pipeline.lastSummary.value?.pending.orEmpty()
            assertEquals("阅读中同批次导入多版只应保留最新 1 项等待退出阅读", 1, pendingBeforeUnlock.size)
            assertEquals(ImportStage.WAITING_READER, pendingBeforeUnlock.single().stage)
            assertEquals("山行记(3章).txt", pendingBeforeUnlock.single().displayName)
            readerLock.unlock()
        }
        assertEquals(1, waitingSummary.skipped)

        withTimeout(3000) {
            while (env.pipeline.lastSummary.value?.pending?.isNotEmpty() == true) {
                kotlinx.coroutines.delay(20)
            }
        }
        val finalSummary = env.pipeline.lastSummary.value!!
        assertEquals("退出阅读后应直接自动更新至最新第 3 章，不得降级为人工确认", 1, finalSummary.updated)
        assertTrue(finalSummary.pending.isEmpty())
        assertEquals(3, env.books.getBook(bookId)!!.totalChapters)
        env.pipeline.clearSummary(finalSummary.batchId)
        withTimeout(2000) {
            while (env.pipeline.lastSummary.value != null) kotlinx.coroutines.delay(10)
        }

        // 3. 验证 EPUB 第 0 章若是显式“第一章”正文被重写（即便字数 < 800），必须进入 REVIEW 而非当作前言短页静默覆盖
        val epubCh1Old = buildEpub(
            temp.newFile("story_ch1_old.epub"),
            title = "短章测试",
            chapters = listOf(
                "c1.xhtml" to "第一章 序幕" to "<html><body><h1>第一章 序幕</h1><p>原本的第一章短正文内容。</p></body></html>",
                "c2.xhtml" to "第二章 展开" to "<html><body><h1>第二章 展开</h1><p>保持不变的第二章正文内容。</p></body></html>"
            )
        )
        val epubCh1New = buildEpub(
            temp.newFile("story_ch1_new.epub"),
            title = "短章测试",
            chapters = listOf(
                "c1.xhtml" to "第一章 序幕" to "<html><body><h1>第一章 序幕</h1><p>被彻底改写的第一章全新剧情。</p></body></html>",
                "c2.xhtml" to "第二章 展开" to "<html><body><h1>第二章 展开</h1><p>保持不变的第二章正文内容。</p></body></html>",
                "c3.xhtml" to "第三章 终局" to "<html><body><h1>第三章 终局</h1><p>新增第三章。</p></body></html>"
            )
        )
        env.saf.mount("content://external/epub/story_old.epub", epubCh1Old)
        env.saf.mount("content://external/epub/story_new.epub", epubCh1New)
        env.pipeline.importAndWait(listOf(ImportSource.FromUri(null, "短章测试.epub", "content://external/epub/story_old.epub")))
        val rewrittenCh1Summary = env.pipeline.importAndWait(
            listOf(ImportSource.FromUri(null, "短章测试.epub", "content://external/epub/story_new.epub"))
        )
        assertEquals("正规『第一章』被改写时不得误判为前言页静默覆盖，必须进入人工确认", 1, rewrittenCh1Summary.pending.size)
        assertEquals(0, rewrittenCh1Summary.updated)
    }

    // ------------------------------------------------------------------ 测试夹具

    internal inner class Harness(scope: CoroutineScope) {
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = rootDir
        }
        private val rootDir = temp.newFolder()
        val paths = AppPaths(context)
        val saf = FakeSaf()
        val contentAccess = BookContentAccess(null, saf)
        val bookDao = MemBookDao()
        val chapterDao = MemChapterDao()
        val progressDao = MemProgressDao()
        val bookmarkDao = MemBookmarkDao()
        val highlightDao = MemHighlightDao()
        val preferenceDao = MemPreferenceDao()
        val books = BookRepository(
            bookDao = bookDao,
            chapterDao = chapterDao,
            progressDao = progressDao,
            paths = paths,
            preferenceDao = preferenceDao
        )
        val textProvider = ChapterTextProvider(paths, contentAccess)
        val pipeline = ImportPipeline(
            context = context,
            paths = paths,
            books = books,
            scope = scope,
            appDatabase = null,
            contentAccess = contentAccess,
            bookmarkDao = bookmarkDao,
            highlightDao = highlightDao,
            preferenceDao = preferenceDao
        )
        val maintenance = BookMaintenance(books, paths, bookmarkDao, highlightDao, contentAccess = contentAccess)
    }

    internal class FakeSaf : BookContentAccess.UriBackend {
        private val files = ConcurrentHashMap<String, File>()
        val zipOpens = AtomicInteger(0)

        fun mount(uri: String, file: File, persistable: Boolean = true) {
            if (persistable) files[uri] = file
        }

        fun unmount(uri: String) {
            files.remove(uri)
        }

        override fun canReferenceDirectly(uriString: String): Boolean =
            files[uriString]?.let { it.isFile && it.length() > 0L } == true

        override fun isReadable(uriString: String): Boolean =
            files[uriString]?.let { it.isFile && it.canRead() } == true

        override fun size(uriString: String): Long =
            files[uriString]?.takeIf { it.isFile }?.length() ?: -1L

        override fun lastModified(uriString: String): Long? =
            files[uriString]?.takeIf { it.isFile }?.lastModified()

        override fun openStream(uriString: String): InputStream {
            val f = files[uriString]?.takeIf { it.isFile }
                ?: throw java.io.FileNotFoundException("Missing URI: $uriString")
            return FileInputStream(f)
        }

        override fun readRange(uriString: String, startByte: Long, length: Int): ByteArray? {
            val f = files[uriString]?.takeIf { it.isFile } ?: return null
            return FileChannel.open(f.toPath(), StandardOpenOption.READ).use { ch ->
                BookContentAccess.readChannelRange(ch, startByte, length)
            }
        }

        override fun openZip(uriString: String): ZipEntryReader {
            zipOpens.incrementAndGet()
            val f = files[uriString]?.takeIf { it.isFile }
                ?: throw java.io.FileNotFoundException("Missing EPUB URI: $uriString")
            val ch = FileChannel.open(f.toPath(), StandardOpenOption.READ)
            return ZipEntryReader.open(ch, Closeable { ch.close() })
        }
    }

    private fun buildEpub(
        file: File,
        title: String,
        chapters: List<Pair<Pair<String, String>, String>>,
        includeNav: Boolean = true
    ): File {
        ZipOutputStream(file.outputStream()).use { zip ->
            val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                crc = CRC32().also { it.update(mime) }.value
            })
            zip.write(mime)
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
                """<item id="ch$i" href="${meta.first}" media-type="application/xhtml+xml"/>"""
            }.joinToString("\n")
            val navManifest = if (includeNav) """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""" else ""
            val spineRefs = chapters.indices.joinToString("\n") { """<itemref idref="ch$it"/>""" }

            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <package version="3.0" xmlns="http://www.idpf.org/2007/opf">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>$title</dc:title>
                  </metadata>
                  <manifest>
                    $navManifest
                    $manifestItems
                  </manifest>
                  <spine>$spineRefs</spine>
                </package>""".trimIndent().toByteArray()
            )
            zip.closeEntry()

            if (includeNav) {
                val links = chapters.joinToString("\n") { (meta, _) ->
                    """<li><a href="${meta.first}">${meta.second}</a></li>"""
                }
                zip.putNextEntry(ZipEntry("OEBPS/nav.xhtml"))
                zip.write(
                    """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                    <body><nav epub:type="toc"><ol>$links</ol></nav></body></html>""".trimIndent().toByteArray()
                )
                zip.closeEntry()
            }

            for ((meta, html) in chapters) {
                zip.putNextEntry(ZipEntry("OEBPS/${meta.first}"))
                zip.write(html.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    internal class MemPreferenceDao : PreferenceDao {
        private val map = ConcurrentHashMap<String, PreferenceEntity>()
        override suspend fun put(preference: PreferenceEntity) { map[preference.key] = preference }
        override suspend fun get(key: String): String? = map[key]?.value
        override fun observe(key: String): Flow<String?> = MutableStateFlow(map[key]?.value)
        override suspend fun remove(key: String) { map.remove(key) }
        override suspend fun removeByPrefix(prefix: String) {
            map.keys.removeIf { it.startsWith(prefix) }
        }
        override suspend fun getAll(): List<PreferenceEntity> = map.values.toList()
    }

    internal class MemBookDao : BookDao {
        val records = linkedMapOf<Long, BookEntity>()
        private var nextId = 1L
        override suspend fun insert(book: BookEntity): Long {
            val id = nextId++
            records[id] = book.copy(id = id)
            return id
        }
        override suspend fun update(book: BookEntity) { records[book.id] = book }
        override suspend fun delete(book: BookEntity) { records.remove(book.id) }
        override suspend fun getById(id: Long): BookEntity? = records[id]
        override fun observeAll(): Flow<List<BookEntity>> = MutableStateFlow(records.values.toList())
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
            if (endChapterIndex != null && (endChapterIndex < 0 || b.totalChapters != endChapterIndex + 1)) return 0
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

    internal class MemChapterDao : ChapterDao {
        private val items = mutableListOf<ChapterEntity>()
        override suspend fun insertAll(chapters: List<ChapterEntity>) { items += chapters }
        override suspend fun deleteForBook(bookId: Long) { items.removeAll { it.bookId == bookId } }
        override suspend fun getForBook(bookId: Long): List<ChapterEntity> = items.filter { it.bookId == bookId }.sortedBy { it.index }
        override suspend fun getChapter(bookId: Long, index: Int): ChapterEntity? = items.firstOrNull { it.bookId == bookId && it.index == index }
        override suspend fun countForBook(bookId: Long): Int = items.count { it.bookId == bookId }
    }

    internal class MemProgressDao : ProgressDao {
        private val map = mutableMapOf<Long, ProgressEntity>()
        override suspend fun upsert(progress: ProgressEntity) { map[progress.bookId] = progress }
        override suspend fun get(bookId: Long): ProgressEntity? = map[bookId]
        override fun observeAll(): Flow<List<ProgressEntity>> = MutableStateFlow(map.values.toList())
        override suspend fun deleteForBook(bookId: Long) { map.remove(bookId) }
    }

    internal class MemBookmarkDao : BookmarkDao {
        private val map = linkedMapOf<Long, BookmarkEntity>()
        private var nextId = 1L
        override suspend fun insert(bookmark: BookmarkEntity): Long {
            val id = nextId++
            map[id] = bookmark.copy(id = id)
            return id
        }
        override suspend fun update(bookmark: BookmarkEntity) { map[bookmark.id] = bookmark }
        override suspend fun delete(bookmark: BookmarkEntity) { map.remove(bookmark.id) }
        override suspend fun contentGeneration(bookId: Long): String? = null
        override suspend fun getById(id: Long): BookmarkEntity? = map[id]
        override suspend fun deleteForBook(bookId: Long) { map.values.removeAll { it.bookId == bookId } }
        override fun observeForBook(bookId: Long): Flow<List<BookmarkEntity>> = MutableStateFlow(emptyList())
        override suspend fun find(bookId: Long, chapterIndex: Int, charOffset: Int): BookmarkEntity? =
            map.values.firstOrNull { it.bookId == bookId && it.chapterIndex == chapterIndex && it.charOffset == charOffset }
        override suspend fun getAllForBook(bookId: Long): List<BookmarkEntity> = map.values.filter { it.bookId == bookId }
        override suspend fun getAll(): List<BookmarkEntity> = map.values.toList()
    }

    internal class MemHighlightDao : HighlightDao {
        private val map = linkedMapOf<Long, HighlightEntity>()
        private var nextId = 1L
        override suspend fun insert(highlight: HighlightEntity): Long {
            val id = nextId++
            map[id] = highlight.copy(id = id)
            return id
        }
        override suspend fun update(highlight: HighlightEntity) { map[highlight.id] = highlight }
        override suspend fun delete(highlight: HighlightEntity) { map.remove(highlight.id) }
        override suspend fun contentGeneration(bookId: Long): String? = null
        override suspend fun getById(id: Long): HighlightEntity? = map[id]
        override suspend fun deleteForBook(bookId: Long) { map.values.removeAll { it.bookId == bookId } }
        override fun observeForBook(bookId: Long): Flow<List<HighlightEntity>> = MutableStateFlow(emptyList())
        override suspend fun getForChapter(bookId: Long, chapterIndex: Int): List<HighlightEntity> =
            map.values.filter { it.bookId == bookId && it.chapterIndex == chapterIndex }
        override suspend fun getAllForBook(bookId: Long): List<HighlightEntity> = map.values.filter { it.bookId == bookId }
        override suspend fun getAll(): List<HighlightEntity> = map.values.toList()
    }
}
