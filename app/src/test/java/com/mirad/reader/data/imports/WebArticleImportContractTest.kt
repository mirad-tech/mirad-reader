package com.mirad.reader.data.imports

import android.content.ContextWrapper
import com.sun.net.httpserver.HttpServer
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 宿主 HTTP → 生产解析器 → BookImporter 交接；不访问外部站点，不代替 Android Room。 */
class WebArticleImportContractTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService
    private lateinit var baseUrl: String

    private class TestContext(private val cache: File) : ContextWrapper(null) {
        override fun getCacheDir(): File = cache
    }

    @Before fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        executor = Executors.newCachedThreadPool()
        server.executor = executor
        baseUrl = "http://127.0.0.1:${server.address.port}"
        server.start()
    }

    @After fun stopServer() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun serve(html: String) {
        server.createContext("/article.html") { exchange ->
            val bytes = html.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
    }

    @Test fun mixedParagraphsNestedTagsAndEntitiesReachImporterInOrder() = runBlocking {
        serve("""
            <html><head><meta charset="utf-8"><title>Audit article</title>
            <style>STYLE_SHOULD_NOT_LEAK</style></head><body>
            <script>SCRIPT_SHOULD_NOT_LEAK</script><nav>NAV_SHOULD_NOT_LEAK</nav>
            <p>First complete paragraph.</p>
            <p>
              Second complete <strong>paragraph</strong> &amp; &quot;entity&quot;.
            </p>
            <p>Third paragraph: &#x1F600; and &lt;tag&gt;.</p>
            </body></html>
        """.trimIndent())
        var handedOff: String? = null
        val importer = object : BookImporter {
            override suspend fun importAndWait(sources: List<ImportSource>, force: Boolean): ImportSummary {
                handedOff = (sources.single() as ImportSource.FromFile).file.readText(Charsets.UTF_8)
                return ImportSummary(1, 0, 0, emptyList())
            }
        }
        val downloader = NetworkBookDownloader(TestContext(temp.newFolder()), importer)
        assertTrue(downloader.downloadAndImport("$baseUrl/article.html"))
        assertEquals(
            "Audit article\n\nFirst complete paragraph.\n\nSecond complete paragraph & \"entity\".\n\nThird paragraph: 😀 and <tag>.",
            handedOff
        )
        assertTrue(downloader.progress.value is DownloadProgress.Success)
    }

    @Test fun titleScriptsAndStylesWithoutBodyDoNotProduceASuccessfulBook() = runBlocking {
        serve("""
            <html><head><meta charset="utf-8"><title>Only a title</title></head>
            <body><script>alert('not body')</script><style>p {color:red}</style><!-- no article --></body></html>
        """.trimIndent())
        var imports = 0
        val importer = object : BookImporter {
            override suspend fun importAndWait(sources: List<ImportSource>, force: Boolean): ImportSummary {
                imports++
                return ImportSummary(1, 0, 0, emptyList())
            }
        }
        val cache = temp.newFolder()
        val downloader = NetworkBookDownloader(TestContext(cache), importer)
        assertFalse(downloader.downloadAndImport("$baseUrl/article.html"))
        assertEquals(0, imports)
        val error = downloader.progress.value
        assertTrue(error is DownloadProgress.Error)
        assertTrue((error as DownloadProgress.Error).message.contains("正文"))
        assertTrue(File(cache, "network_imports").listFiles().orEmpty().isEmpty())
    }

    @Test fun unclosedParagraphMarkupStillKeepsItsReadableText() = runBlocking {
        serve("<html><head><meta charset='utf-8'><title>Recoverable markup</title></head><body><p>First readable paragraph.<p>Second readable paragraph.")
        var handedOff: String? = null
        val importer = object : BookImporter {
            override suspend fun importAndWait(sources: List<ImportSource>, force: Boolean): ImportSummary {
                handedOff = (sources.single() as ImportSource.FromFile).file.readText()
                return ImportSummary(1, 0, 0, emptyList())
            }
        }
        val downloader = NetworkBookDownloader(TestContext(temp.newFolder()), importer)
        assertTrue(downloader.downloadAndImport("$baseUrl/article.html"))
        assertEquals("Recoverable markup\n\nFirst readable paragraph.\n\nSecond readable paragraph.", handedOff)
    }
}
