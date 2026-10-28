package com.mirad.reader.data.sources

import com.mirad.reader.core.epub.ZipEntryReader
import com.mirad.reader.core.sources.BookSource
import com.mirad.reader.core.sources.RemoteBook
import com.mirad.reader.core.sources.RemoteChapter
import com.mirad.reader.core.sources.RuleExecutor
import com.mirad.reader.core.sources.rules.RuleEvaluator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SourcePaginationRegressionTest {
    private fun engine() = DefaultBookSourceEngine(SourceHttpClient(retries = 0), RuleExecutor { RuleEvaluator().evaluate(it) })

    private fun source(base: String, rules: String) = BookSource(
        "pagination", "分页夹具", base, Json.parseToJsonElement(rules) as JsonObject)

    private fun post(url: String, body: String): String = "$url," + buildJsonObject {
        put("method", "POST")
        put("body", body)
    }

    @Test fun sameEndpointPostBodiesSupportBothCatalogAndChapterPagination() = runBlocking {
        SourceHttpFixture().use { fixture ->
            val tocBodies = mutableListOf<String>()
            val contentBodies = mutableListOf<String>()
            fixture.route("/toc") { exchange ->
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                tocBodies += body
                val first = body == "page=1"
                exchange.reply(buildJsonObject {
                    put("chapters", Json.parseToJsonElement(if (first) """[{"name":"一","url":"/chapter1"}]""" else """[{"name":"二","url":"/chapter2"}]"""))
                    put("next", if (first) post("${fixture.base}/toc", "page=2") else "")
                }.toString())
            }
            fixture.route("/content") { exchange ->
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                contentBodies += body
                val first = body == "chapter=1&page=1"
                exchange.reply(buildJsonObject {
                    put("text", if (first) "第一页正文" else "第二页正文")
                    put("next", if (first) post("${fixture.base}/content", "chapter=1&page=2") else "")
                }.toString())
            }
            val source = source(fixture.base, """{
                "ruleToc":{"chapterList":"$.chapters[*]","chapterName":"$.name","chapterUrl":"$.url","nextTocUrl":"$.next"},
                "ruleContent":{"content":"$.text","nextContentUrl":"$.next"}
            }""")
            val engine = engine()
            val book = RemoteBook(source.id, source.name, "分页书", "${fixture.base}/book", tocUrl = post("${fixture.base}/toc", "page=1"))
            assertEquals(listOf("一", "二"), engine.toc(source, book).chapters.map { it.title })
            assertEquals("第一页正文\n\n第二页正文", engine.content(source, book,
                RemoteChapter("一", post("${fixture.base}/content", "chapter=1&page=1"))))
            assertEquals(listOf("page=1", "page=2"), tocBodies)
            assertEquals(listOf("chapter=1&page=1", "chapter=1&page=2"), contentBodies)
        }
    }

    @Test fun redirectedPostPagesKeepDistinctBodiesAfterTemplateRendering() = runBlocking {
        SourceHttpFixture().use { fixture ->
            val bodies = mutableListOf<String>()
            val nextRequest = post("${fixture.base}/alias", "page={{page}}")
            fixture.route("/alias") {
                it.requestBody.close()
                it.responseHeaders.add("Location", "/content")
                it.sendResponseHeaders(307, -1)
                it.close()
            }
            fixture.route("/content") { exchange ->
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                bodies += body
                exchange.reply(buildJsonObject {
                    put("text", body)
                    put("next", if (body == "page=1") nextRequest else "")
                }.toString())
            }
            val source = source(fixture.base, """{"ruleContent":{"content":"$.text","nextContentUrl":"$.next"}}""")
            val book = RemoteBook(source.id, source.name, "分页书", "${fixture.base}/book")
            assertEquals("page=1\n\npage=2", engine().content(source, book, RemoteChapter("一", nextRequest)))
            assertEquals(listOf("page=1", "page=2"), bodies)
        }
    }

    @Test fun repeatedEffectivePostRequestStillFailsAsACycle() = runBlocking {
        SourceHttpFixture().use { fixture ->
            val request = post("${fixture.base}/content", "page=1")
            fixture.route("/content") { it.reply(buildJsonObject {
                put("text", "正文")
                put("next", request)
            }.toString()) }
            val source = source(fixture.base, """{"ruleContent":{"content":"$.text","nextContentUrl":"$.next"}}""")
            val book = RemoteBook(source.id, source.name, "分页书", "${fixture.base}/book")
            val error = runCatching { engine().content(source, book, RemoteChapter("一", request)) }.exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue(error!!.message.orEmpty().contains("循环"))
        }
    }

    @Test fun postToGetRedirectsCompareTheActualFinalRequest() = runBlocking {
        SourceHttpFixture().use { fixture ->
            val methods = mutableListOf<String>()
            fixture.route("/content") { exchange ->
                methods += exchange.requestMethod
                if (exchange.requestMethod == "POST") {
                    exchange.requestBody.close()
                    exchange.responseHeaders.add("Location", "/content")
                    exchange.sendResponseHeaders(303, -1)
                    exchange.close()
                } else exchange.reply("<div id=body>实际 GET 正文</div>")
            }
            val source = source(fixture.base, """{"ruleContent":{"content":"#body@text"}}""")
            val book = RemoteBook(source.id, source.name, "分页书", "${fixture.base}/book")
            assertEquals("实际 GET 正文", engine().content(source, book,
                RemoteChapter("一", post("${fixture.base}/content", "page=1"))))
            assertEquals(listOf("POST", "GET"), methods)
        }
    }

    @Test fun oversizedMultiPageChapterStopsBeforeRequestingAnotherPage() = runBlocking {
        SourceHttpFixture().use { fixture ->
            val pages = AtomicInteger()
            val chunk = "界".repeat(1_000_000) // 每页3MB，不触及单页限制，第三页使整章超过8MB。
            fixture.route("/content") { exchange ->
                val page = pages.incrementAndGet()
                val next = if (page < 4) "<a class=next href='/content?page=${page + 1}'>下一页</a>" else ""
                // 旧实现最多读四页，避免回归复现本身进行内存压力测试。
                exchange.reply("<div id=body>${if (page < 4) chunk else "不应请求"}</div>$next")
            }
            val source = source(fixture.base, """{"ruleContent":{"content":"#body@text","nextContentUrl":"a.next@href"}}""")
            val book = RemoteBook(source.id, source.name, "大章节", "${fixture.base}/book")
            val error = runCatching { engine().content(source, book, RemoteChapter("一", "${fixture.base}/content?page=1")) }.exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue(error!!.message.orEmpty().contains("8MB"))
            assertEquals(3, pages.get())
        }
    }

    @Test fun utf8LimitIncludesTheSeparatorAndAllowsTheExactBoundary() = runBlocking {
        for (extraBytes in 0..1) SourceHttpFixture().use { fixture ->
            val first = "界".repeat(1_000_000)
            val remaining = ZipEntryReader.MAX_CHAPTER_BYTES - first.toByteArray(Charsets.UTF_8).size - 2
            val second = "界".repeat(remaining / 3) + "a".repeat(remaining % 3 + extraBytes)
            fixture.route("/content") { exchange ->
                if (exchange.requestURI.query == "page=1") exchange.reply("<div id=body>$first</div><a class=next href='?page=2'>下一页</a>")
                else exchange.reply("<div id=body>$second</div>")
            }
            val source = source(fixture.base, """{"ruleContent":{"content":"#body@text","nextContentUrl":"a.next@href"}}""")
            val book = RemoteBook(source.id, source.name, "边界章节", "${fixture.base}/book")
            val result = runCatching { engine().content(source, book, RemoteChapter("一", "${fixture.base}/content?page=1")) }
            if (extraBytes == 0) {
                val content = result.getOrThrow()
                assertEquals(ZipEntryReader.MAX_CHAPTER_BYTES, content.toByteArray(Charsets.UTF_8).size)
                assertTrue(content.contains("\n\n"))
            } else {
                assertTrue(result.exceptionOrNull() is IOException)
                assertTrue(result.exceptionOrNull()!!.message.orEmpty().contains("8MB"))
            }
        }
    }
}
