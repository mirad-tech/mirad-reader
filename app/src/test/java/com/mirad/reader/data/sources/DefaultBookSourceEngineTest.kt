package com.mirad.reader.data.sources

import com.mirad.reader.core.sources.BookSource
import com.mirad.reader.core.sources.RemoteBook
import com.mirad.reader.core.sources.RemoteChapter
import com.mirad.reader.core.sources.RuleExecutor
import com.mirad.reader.core.sources.rules.RuleEvaluator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DefaultBookSourceEngineTest {
    private fun engine() = DefaultBookSourceEngine(SourceHttpClient(retries = 0), RuleExecutor { RuleEvaluator().evaluate(it) })

    private fun source(base: String, rules: String): BookSource {
        val definition = Json.parseToJsonElement(rules) as JsonObject
        return BookSource("fixture", "夹具书源", base, definition)
    }

    @Test
    fun searchDetailWholePaginatedCatalogAndContentUseRealRuleEvaluator() = runBlocking {
        SourceHttpFixture().use { fixture ->
            val query = AtomicReference("")
            fixture.route("/search") {
                query.set(it.requestURI.rawQuery)
                it.reply("""<div class="book"><a class="title" href="/book">甲与乙</a><span class="author">作者甲</span></div>""")
            }
            fixture.route("/book") { it.reply("""<h1>甲与乙</h1><span class="author">作者甲</span><a class="toc" href="/toc">目录</a>""") }
            fixture.route("/toc") {
                if (it.requestURI.query == "page=2") it.reply("""<li class="chapter"><a href="/c2" data-vip="true">第二章</a></li><li class="chapter"><a href="/c1">第一章</a></li><li class="chapter"><a href="/c3">第三章</a></li>""")
                else it.reply("""<li class="chapter"><a data-volume="1">卷头</a></li><li class="chapter"><a href="/c3">第三章</a></li><a class="next" href="?page=2">下一页</a>""")
            }
            fixture.route("/c1") {
                if (it.requestURI.query == "page=2") it.reply("""<div id="body"><p>第二页正文</p></div><a class="next" href="/c2">下一章</a>""")
                else it.reply("""<div id="body"><p>第一页正文</p><p>广告删除</p></div><a class="next" href="?page=2">下一页</a>""")
            }
            val source = source(fixture.base, """{
                "searchUrl":"${fixture.base}/search?q={{key}}&page={{page}}",
                "ruleSearch":{"bookList":".book","name":"@put:{bid:'.title@href'}.title@text","author":".author@text","bookUrl":".title@href"},
                "ruleBookInfo":{"name":"h1@text","author":".author@text","tocUrl":".toc@href"},
                "ruleToc":{"chapterList":"-li.chapter","chapterName":"@put:{cid:'a@href'}a@text","chapterUrl":"a@href","isVolume":"a@data-volume","isVip":"a@data-vip","nextTocUrl":"a.next@href"},
                "ruleContent":{"content":"#body@html","nextContentUrl":"a.next@href","replaceRegex":"广告删除##"}
            }""")
            val engine = engine()
            val searched = engine.search(source, "A&B 空")
            assertTrue(query.get().contains("q=A%26B+%E7%A9%BA"))
            assertEquals("/book", searched.single().variables["bid"])
            val book = engine.bookInfo(source, searched.single())
            assertEquals("${fixture.base}/toc", book.tocUrl)
            val catalog = engine.toc(source, book)
            assertEquals(listOf("第一章", "第二章", "第三章"), catalog.chapters.map { it.title })
            assertEquals(listOf(0, 1, 2), catalog.chapters.map { it.index })
            assertEquals("/book", catalog.chapters.first().variables["bid"])
            assertEquals("/c1", catalog.chapters.first().variables["cid"])
            assertTrue(catalog.chapters[1].restricted)
            val content = engine.content(source, book, catalog.chapters[0], catalog.chapters[1].url)
            assertEquals("第一页正文\n\n第二页正文", content)
            assertFalse(content.contains("广告"))
            val vipError = runCatching { engine.content(source, book, catalog.chapters[1]) }.exceptionOrNull()
            assertTrue(vipError!!.message!!.contains("付费"))
        }
    }

    @Test
    fun catalogAndContentCyclesFailRatherThanReturningPartialSuccess() = runBlocking {
        SourceHttpFixture().use { fixture ->
            fixture.route("/toc") { it.reply("""<a class="chapter" href="/c1">第一章</a><a class="next" href="/toc">再次</a>""") }
            fixture.route("/c1") { it.reply("""<div id="body">正文</div><a class="next" href="/c1">再次</a>""") }
            val source = source(fixture.base, """{
                "ruleToc":{"chapterList":"a.chapter","chapterName":"text","chapterUrl":"a@href","nextTocUrl":"a.next@href"},
                "ruleContent":{"content":"#body@text","nextContentUrl":"a.next@href"}
            }""")
            val engine = engine()
            val book = RemoteBook(source.id, source.name, "书", "${fixture.base}/toc")
            assertTrue(runCatching { engine.toc(source, book) }.exceptionOrNull()!!.message!!.contains("循环"))
            assertTrue(runCatching { engine.content(source, book, RemoteChapter("一", "${fixture.base}/c1")) }.exceptionOrNull()!!.message!!.contains("循环"))
        }
    }

    @Test
    fun postTemplateHeadersAndExplicitCharsetReachTheSharedHttpClient() = runBlocking {
        SourceHttpFixture().use { fixture ->
            val received = AtomicReference("")
            val gbk = Charset.forName("GBK")
            fixture.route("/post") {
                received.set("${it.requestMethod}|${it.requestHeaders.getFirst("X-Source")}|${String(it.requestBody.readBytes(), gbk)}")
                it.reply("""<a class="book" href="/detail">中文书</a>""", charset = gbk)
            }
            val definition = JsonObject(mapOf(
                "searchUrl" to JsonPrimitive("${fixture.base}/post,{\"method\":\"POST\",\"charset\":\"GBK\",\"body\":\"q={{key}}\",\"headers\":{\"X-Source\":\"test\"}}"),
                "ruleSearch" to (Json.parseToJsonElement("""{"bookList":"a.book","name":"text","bookUrl":"a@href"}""") as JsonObject)
            ))
            val result = engine().search(BookSource("post", "POST源", fixture.base, definition), "中文")
            assertEquals("POST|test|q=中文", received.get())
            assertEquals("中文书", result.single().title)
        }
    }

    @Test
    fun directGetSearchPlaceholderUsesTheDeclaredCharset() = runBlocking {
        SourceHttpFixture().use { fixture ->
            val received = mutableListOf<String>()
            fixture.route("/search") {
                received += it.requestURI.rawQuery
                it.reply("""<a class="book" href="/detail">中文书</a>""", charset = Charset.forName("GBK"))
            }
            for (charset in listOf("GBK", "{{'GBK'}}")) {
                val definition = JsonObject(mapOf(
                    "searchUrl" to JsonPrimitive("${fixture.base}/search?q={{key}},{\"charset\":\"$charset\"}"),
                    "ruleSearch" to Json.parseToJsonElement("""{"bookList":"a.book","name":"text","bookUrl":"a@href"}""")
                ))
                val result = engine().search(BookSource("gbk", "GBK源", fixture.base, definition), "中文 &")
                assertEquals("中文书", result.single().title)
            }
            assertEquals(listOf("q=%D6%D0%CE%C4+%26", "q=%D6%D0%CE%C4+%26"), received)
        }
    }

    @Test
    fun explicitlyEncodedGetSearchExpressionIsNotEncodedAgain() = runBlocking {
        SourceHttpFixture().use { fixture ->
            val received = AtomicReference("")
            fixture.route("/search") {
                received.set(it.requestURI.rawQuery)
                it.reply("""<a class="book" href="/detail">中文书</a>""", charset = Charset.forName("GBK"))
            }
            val definition = JsonObject(mapOf(
                "searchUrl" to JsonPrimitive("${fixture.base}/search?q={{encodeURIComponent(key)}},{\"charset\":\"GBK\"}"),
                "ruleSearch" to Json.parseToJsonElement("""{"bookList":"a.book","name":"text","bookUrl":"a@href"}""")
            ))
            engine().search(BookSource("explicit", "显式编码源", fixture.base, definition), "中文 &")
            assertEquals("q=%E4%B8%AD%E6%96%87%20%26", received.get())
        }
    }

    @Test
    fun supportsPureJavascriptBookInfoInitializationAndRejectsLoginOrEmptyBody() = runBlocking {
        SourceHttpFixture().use { fixture ->
            fixture.route("/book") { it.reply("original") }
            fixture.route("/login") { it.reply("<title>账号登录</title><input type=password>") }
            fixture.route("/empty") { it.reply("<div id=body></div>") }
            val source = source(fixture.base, """{
                "ruleBookInfo":{"init":"@js:JSON.stringify({name:'书名',author:book.author || '作者',toc:source.bookSourceUrl+'/toc'})","name":"$.name","author":"$.author","tocUrl":"$.toc"},
                "ruleContent":{"content":"#body@text"}
            }""")
            val engine = engine()
            val book = engine.bookInfo(source, RemoteBook(source.id, source.name, "旧书名", "${fixture.base}/book"))
            assertEquals("书名", book.title)
            assertEquals("作者", book.author)
            assertEquals("${fixture.base}/toc", book.tocUrl)
            assertTrue(runCatching { engine.content(source, book, RemoteChapter("一", "${fixture.base}/login")) }.exceptionOrNull()!!.message!!.contains("登录"))
            assertTrue(runCatching { engine.content(source, book, RemoteChapter("一", "${fixture.base}/empty")) }.exceptionOrNull()!!.message!!.contains("正文为空"))
        }
    }

    @Test
    fun failedLaterCatalogPageNeverProducesCatalog() = runBlocking {
        SourceHttpFixture().use { fixture ->
            val count = AtomicInteger()
            fixture.route("/toc") {
                count.incrementAndGet()
                if (it.requestURI.query == null) it.reply("""<a class="chapter" href="/c1">一</a><a class="next" href="?page=2">下一页</a>""")
                else it.reply("unavailable", status = 503)
            }
            val source = source(fixture.base, """{"ruleToc":{"chapterList":"a.chapter","chapterName":"text","chapterUrl":"a@href","nextTocUrl":"a.next@href"}}""")
            val result = runCatching { engine().toc(source, RemoteBook(source.id, source.name, "书", "${fixture.base}/toc")) }
            assertTrue(result.isFailure)
            assertEquals(2, count.get())
        }
    }

    @Test
    fun readablePagesWithAnEmbeddedLoginDialogRemainUsable() = runBlocking {
        SourceHttpFixture().use { fixture ->
            val loginDialog = "<aside hidden><input type=password><span>登录后查看个人中心</span></aside>"
            fixture.route("/book") { it.reply("<h1>登录异世界</h1><a class=toc href=/toc>目录</a>$loginDialog") }
            fixture.route("/toc") { it.reply("<a class=chapter href=/c1>第一章</a>$loginDialog") }
            fixture.route("/c1") { it.reply("<title>登录异世界 第一章</title><div id=body>这是一段已经开放的正文。</div>$loginDialog") }
            val source = source(fixture.base, """{
                "ruleBookInfo":{"name":"h1@text","tocUrl":".toc@href"},
                "ruleToc":{"chapterList":"a.chapter","chapterName":"text","chapterUrl":"a@href"},
                "ruleContent":{"content":"#body@text"}
            }""")
            val engine = engine()
            val book = engine.bookInfo(source, RemoteBook(source.id, source.name, "登录异世界", "${fixture.base}/book"))
            val catalog = engine.toc(source, book)
            assertEquals(1, catalog.chapters.size)
            assertEquals("这是一段已经开放的正文。", engine.content(source, book, catalog.chapters.single()))
        }
    }

    @Test
    fun optionalBookInfoUsesKnownTocWithoutASeparateNetworkRequest() = runBlocking {
        val source = source("https://example.com", "{}")
        val book = RemoteBook(source.id, source.name, "书名", "https://example.com/book", tocUrl = "https://example.com/toc")
        assertEquals(book, engine().bookInfo(source, book))
    }
}
