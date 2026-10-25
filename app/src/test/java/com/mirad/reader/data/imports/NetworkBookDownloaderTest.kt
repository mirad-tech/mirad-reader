package com.mirad.reader.data.imports

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class NetworkBookDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var server: HttpServer? = null
    private var serverBaseUrl: String = ""

    class TestContext(private val cache: File) : android.content.ContextWrapper(null) {
        override fun getCacheDir(): File = cache
    }

    private fun fakeImporter(handler: suspend (List<ImportSource>) -> ImportSummary): BookImporter =
        object : BookImporter {
            override suspend fun importAndWait(sources: List<ImportSource>, force: Boolean): ImportSummary =
                handler(sources)
        }

    @Before
    fun setUpServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server!!.address.port
        serverBaseUrl = "http://127.0.0.1:$port"
        server!!.executor = Executors.newCachedThreadPool()
        server!!.start()
    }

    @After
    fun tearDownServer() {
        server?.stop(0)
    }

    @Test
    fun testGuessFileNameWithEpubContentType() {
        // 场景 1: URL 无扩展名重定向，但 Content-Type 是 application/epub+zip
        val name1 = NetworkBookDownloader.guessFileName(
            url = "https://example.com/download/book",
            contentDisposition = null,
            contentType = "application/epub+zip"
        )
        assertTrue("应以 .epub 结尾", name1.endsWith(".epub"))

        // 场景 2: Content-Disposition 有文件名但缺少扩展名，Content-Type 是 epub
        val name2 = NetworkBookDownloader.guessFileName(
            url = "https://example.com/ebook",
            contentDisposition = "attachment; filename=\"三体\"",
            contentType = "application/epub+zip"
        )
        assertEquals("三体.epub", name2)

        // 场景 3: Content-Disposition 带有 .txt 但服务器明确响应 application/epub+zip
        val name3 = NetworkBookDownloader.guessFileName(
            url = "https://example.com/ebook",
            contentDisposition = "attachment; filename=\"三体.txt\"",
            contentType = "application/epub+zip"
        )
        assertEquals("三体.epub", name3)

        // 场景 4: 普通 TXT
        val name4 = NetworkBookDownloader.guessFileName(
            url = "https://example.com/book.txt",
            contentDisposition = null,
            contentType = "text/plain"
        )
        assertEquals("book.txt", name4)
    }

    @Test
    fun testZipHeaderOverridesMisnamedTxtExtension() {
        // 场景: 下载文件即使被命名为 .txt，但实际检测到 ZIP 魔数头（PK\x03\x04）
        val ext = ImportPipeline.detectExtension(name = "misnamed_book.txt", isZip = true)
        assertEquals("带 ZIP 魔数头的文件必须识别为 epub，绝不信任 .txt 扩展名", "epub", ext)

        // 普通无 ZIP 魔数的纯文本文件仍识别为 txt
        val plainTxt = ImportPipeline.detectExtension(name = "normal_book.txt", isZip = false)
        assertEquals("纯文本文件识别为 txt", "txt", plainTxt)
    }

    /**
     * 针对 Reviewer Round 2 P2-10 的回归测试：
     * 正文以 'PK' 字符开头的普通文本绝不能被误判为 ZIP/EPUB 魔数。
     */
    @Test
    fun testPkStartingTxtIsNotRecognizedAsZip() {
        val txtBytes = "PK排行榜：第一名张三，第二名李四。".toByteArray(Charsets.UTF_8)
        val isZip = txtBytes.size >= 4 &&
            txtBytes[0] == 0x50.toByte() &&
            txtBytes[1] == 0x4B.toByte() &&
            txtBytes[2] == 0x03.toByte() &&
            txtBytes[3] == 0x04.toByte()
        assertTrue("PK 开头的普通文本不符合 4 字节 ZIP 魔数", !isZip)

        val ext = ImportPipeline.detectExtension(name = "pk_ranking.txt", isZip = isZip)
        assertEquals("依然正确识别为 txt", "txt", ext)

        // 真正的标准 EPUB/ZIP 魔数 (0x50, 0x4B, 0x03, 0x04)
        val realZipBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        val isRealZip = realZipBytes.size >= 4 &&
            realZipBytes[0] == 0x50.toByte() &&
            realZipBytes[1] == 0x4B.toByte() &&
            realZipBytes[2] == 0x03.toByte() &&
            realZipBytes[3] == 0x04.toByte()
        assertTrue("标准 4 字节必须识别为 ZIP", isRealZip)
        val realExt = ImportPipeline.detectExtension(name = "book.txt", isZip = isRealZip)
        assertEquals("真正带 4 字节 ZIP 魔数的文件覆盖为 epub", "epub", realExt)
    }

    @Test
    fun testNormalDownloadAndSuccessfulImport() = runBlocking {
        val content = "第一章 旅程\n这是测试的正文内容。"
        server!!.createContext("/book.txt") { exchange ->
            val bytes = content.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        val cacheDir = tempFolder.newFolder("cache")
        val context = TestContext(cacheDir)
        var importedSources: List<ImportSource>? = null
        val fakeImporter = fakeImporter { sources ->
            importedSources = sources
            ImportSummary(success = 1, failed = 0, skipped = 0, messages = emptyList())
        }
        val downloader = NetworkBookDownloader(context, fakeImporter)

        val success = downloader.downloadAndImport("$serverBaseUrl/book.txt")
        assertTrue("导入应成功", success)
        assertTrue("进度应为 Success", downloader.progress.value is DownloadProgress.Success)
        val files = File(cacheDir, "network_imports").listFiles()?.filter { it.isFile } ?: emptyList()
        assertTrue("下载成功入库后，临时文件必须已被清理", files.isEmpty())
        assertTrue("应向 importer 传递下载文件", !importedSources.isNullOrEmpty())
    }

    @Test
    fun testDelayedResponseHeadersStillDownloadAndImport() = runBlocking {
        val content = "第一章 迟到的响应头\n服务器正常处理请求需要超过 300ms。"
        server!!.createContext("/delayed-headers.txt") { exchange ->
            try {
                Thread.sleep(800)
                val bytes = content.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally {
                exchange.close()
            }
        }

        val cacheDir = tempFolder.newFolder("delayed-headers-cache")
        var imported = false
        val importer = fakeImporter { sources ->
            val downloaded = (sources.single() as ImportSource.FromFile).file
            assertEquals(content, downloaded.readText(Charsets.UTF_8))
            imported = true
            ImportSummary(1, 0, 0, emptyList())
        }
        val downloader = NetworkBookDownloader(TestContext(cacheDir), importer)

        assertTrue("正常响应头延迟超过 300ms 不应使下载失败",
            downloader.downloadAndImport("$serverBaseUrl/delayed-headers.txt"))
        assertTrue("完整下载内容应交给导入器", imported)
        assertTrue(downloader.progress.value is DownloadProgress.Success)
        assertTrue(File(cacheDir, "network_imports").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun testOversizedContentLengthIsRejectedImmediatelyWithoutResidual() = runBlocking {
        // 声明 100MB Content-Length（超过 80MB 上限）
        server!!.createContext("/oversized.txt") { exchange ->
            exchange.sendResponseHeaders(200, 100L * 1024 * 1024)
            // 不发送正文，客户端应在读 header 时立即拒绝
            exchange.close()
        }

        val cacheDir = tempFolder.newFolder("cache")
        val context = TestContext(cacheDir)
        val fakeImporter = fakeImporter {
            ImportSummary(1, 0, 0, emptyList())
        }
        val downloader = NetworkBookDownloader(context, fakeImporter)

        val success = downloader.downloadAndImport("$serverBaseUrl/oversized.txt")
        assertFalse("超限文件不应成功", success)
        val progress = downloader.progress.value
        assertTrue("进度应为 Error", progress is DownloadProgress.Error)
        assertTrue("错误信息应提示超限", (progress as DownloadProgress.Error).message.contains("文件过大"))
        val files = File(cacheDir, "network_imports").listFiles()?.filter { it.isFile } ?: emptyList()
        assertTrue("超限被拒后临时文件无残留", files.isEmpty())
    }

    @Test
    fun testStreamExceedingMaxBytesIsStoppedAndCleaned() = runBlocking {
        // 伪造较小 Content-Length 或流式无长度，但实际发送超过 80MB 数据
        server!!.createContext("/fake_length.txt") { exchange ->
            exchange.sendResponseHeaders(200, 0) // chunked / 动态
            val chunk = ByteArray(64 * 1024) { 'A'.code.toByte() }
            val out = exchange.responseBody
            try {
                // 循环写入直至客户端主动断开
                for (i in 0 until 1400) { // 1400 * 64KB ≈ 89.6MB > 80MB
                    out.write(chunk)
                    out.flush()
                }
            } catch (_: Exception) {
            } finally {
                exchange.close()
            }
        }

        val cacheDir = tempFolder.newFolder("cache")
        val context = TestContext(cacheDir)
        val fakeImporter = fakeImporter {
            ImportSummary(1, 0, 0, emptyList())
        }
        val downloader = NetworkBookDownloader(context, fakeImporter)

        val success = downloader.downloadAndImport("$serverBaseUrl/fake_length.txt")
        assertFalse("流超限应失败", success)
        val progress = downloader.progress.value
        assertTrue("进度应为 Error", progress is DownloadProgress.Error)
        assertTrue("错误信息应提示超限", (progress as DownloadProgress.Error).message.contains("文件过大"))
        val files = File(cacheDir, "network_imports").listFiles()?.filter { it.isFile } ?: emptyList()
        assertTrue("达到限额停止后临时文件无残留", files.isEmpty())
    }

    @Test
    fun testCancellationCleansUpActiveTempFileAndResetsProgress() = runBlocking {
        // 服务端保持连接不主动关闭，超过客户端正常的 20 秒读取超时。
        val clientEnteredRead = java.util.concurrent.CountDownLatch(1)
        val serverDone = java.util.concurrent.CountDownLatch(1)
        server!!.createContext("/slow.txt") { exchange ->
            exchange.sendResponseHeaders(200, 100000)
            val out = exchange.responseBody
            try {
                out.write("Slow test data initial chunk".toByteArray())
                out.flush()
                clientEnteredRead.countDown()
                serverDone.await(30, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) {
            } finally {
                exchange.close()
            }
        }

        val cacheDir = tempFolder.newFolder("cache")
        val context = TestContext(cacheDir)
        val fakeImporter = fakeImporter { ImportSummary(1, 0, 0, emptyList()) }
        val downloader = NetworkBookDownloader(context, fakeImporter)

        val job = launch(Dispatchers.IO) {
            downloader.downloadAndImport("$serverBaseUrl/slow.txt")
        }

        assertTrue("等待客户端进入实际读取", clientEnteredRead.await(5, java.util.concurrent.TimeUnit.SECONDS))
        delay(50) // 确保客户端已在 input.read 阻塞

        job.cancel()

        // 本轮保留 20 秒正常读取等待；取消在读取返回或超时后完成，仍先于服务端关闭。
        try {
            kotlinx.coroutines.withTimeout(25000) {
                job.join()
            }
        } finally {
            serverDone.countDown()
        }

        assertEquals("取消后进度应重置为 Idle", DownloadProgress.Idle, downloader.progress.value)
        val files = File(cacheDir, "network_imports").listFiles()?.filter { it.isFile } ?: emptyList()
        assertTrue("取消后临时文件无残留", files.isEmpty())
    }

    @Test
    fun testHtmlCancellationCompletesAfterReadTimeoutAndCleansUp() = runBlocking {
        val clientEnteredRead = java.util.concurrent.CountDownLatch(1)
        val serverDone = java.util.concurrent.CountDownLatch(1)
        server!!.createContext("/article.html") { exchange ->
            exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, 50000)
            val out = exchange.responseBody
            try {
                out.write("<html><head><title>测试文章</title></head><body><p>正在传输".toByteArray())
                out.flush()
                clientEnteredRead.countDown()
                serverDone.await(30, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) {
            } finally {
                exchange.close()
            }
        }

        val cacheDir = tempFolder.newFolder("cache-html")
        val context = TestContext(cacheDir)
        val fakeImporter = fakeImporter { ImportSummary(1, 0, 0, emptyList()) }
        val downloader = NetworkBookDownloader(context, fakeImporter)

        val job = launch(Dispatchers.IO) {
            downloader.downloadAndImport("$serverBaseUrl/article.html")
        }

        assertTrue("等待 HTML 客户端进入读取", clientEnteredRead.await(5, java.util.concurrent.TimeUnit.SECONDS))
        delay(50)

        job.cancel()

        try {
            kotlinx.coroutines.withTimeout(25000) {
                job.join()
            }
        } finally {
            serverDone.countDown()
        }

        assertEquals("HTML 取消后进度必须重置为 Idle", DownloadProgress.Idle, downloader.progress.value)
        val files = File(cacheDir, "network_imports").listFiles()?.filter { it.isFile } ?: emptyList()
        assertTrue("HTML 取消后临时文件无残留", files.isEmpty())
    }

    @Test
    fun testEmptyFileDoesNotTriggerSuccess() = runBlocking {
        server!!.createContext("/empty.txt") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }

        val cacheDir = tempFolder.newFolder("cache")
        val context = TestContext(cacheDir)
        var importerInvoked = false
        val fakeImporter = fakeImporter {
            importerInvoked = true
            ImportSummary(1, 0, 0, emptyList())
        }
        val downloader = NetworkBookDownloader(context, fakeImporter)

        val success = downloader.downloadAndImport("$serverBaseUrl/empty.txt")
        assertFalse("空文件不能触发成功", success)
        assertFalse("空文件不应调用 importer", importerInvoked)
        val progress = downloader.progress.value
        assertTrue("进度应为 Error", progress is DownloadProgress.Error)
        assertTrue("错误应提示内容为空", (progress as DownloadProgress.Error).message.contains("为空"))
        val files = File(cacheDir, "network_imports").listFiles()?.filter { it.isFile } ?: emptyList()
        assertTrue("空文件失败后临时文件被清理", files.isEmpty())
    }

    @Test
    fun testCorruptBookReportsFailureAndCleansTempFile() = runBlocking {
        server!!.createContext("/corrupt.epub") { exchange ->
            val bytes = byteArrayOf(1, 2, 3, 4, 5) // 损坏的非 ZIP 内容
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        val cacheDir = tempFolder.newFolder("cache")
        val context = TestContext(cacheDir)
        val fakeImporter = fakeImporter {
            ImportSummary(success = 0, failed = 1, skipped = 0, messages = listOf("EPUB 文件损坏或缺少 container.xml"))
        }
        val downloader = NetworkBookDownloader(context, fakeImporter)

        val success = downloader.downloadAndImport("$serverBaseUrl/corrupt.epub")
        assertFalse("损坏书籍不应报告成功", success)
        val progress = downloader.progress.value
        assertTrue("进度应为 Error", progress is DownloadProgress.Error)
        assertTrue("错误应包含入库反馈信息", (progress as DownloadProgress.Error).message.contains("损坏"))
        val files = File(cacheDir, "network_imports").listFiles()?.filter { it.isFile } ?: emptyList()
        assertTrue("入库失败后临时文件被清理", files.isEmpty())
    }

    @Test
    fun testDuplicateBookReportsSkippedAndCleansTempFile() = runBlocking {
        server!!.createContext("/duplicate.txt") { exchange ->
            val bytes = "重复书籍测试正文".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        val cacheDir = tempFolder.newFolder("cache")
        val context = TestContext(cacheDir)
        val fakeImporter = fakeImporter {
            ImportSummary(success = 0, failed = 0, skipped = 1, messages = listOf("书籍已在书架中"))
        }
        val downloader = NetworkBookDownloader(context, fakeImporter)

        val success = downloader.downloadAndImport("$serverBaseUrl/duplicate.txt")
        assertFalse("重复跳过不应触发成功回调", success)
        val progress = downloader.progress.value
        assertTrue("进度应为 Skipped", progress is DownloadProgress.Skipped)
        assertTrue("跳过应包含提示信息", (progress as DownloadProgress.Skipped).message.contains("已在书架中"))
        val files = File(cacheDir, "network_imports").listFiles()?.filter { it.isFile } ?: emptyList()
        assertTrue("跳过后临时文件被清理", files.isEmpty())
    }
}
