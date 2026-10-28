package com.mirad.reader.data.sources

import com.mirad.reader.core.epub.ZipEntryReader
import com.mirad.reader.core.sources.BookSource
import com.mirad.reader.core.sources.BookSourceEngine
import com.mirad.reader.core.sources.RemoteBook
import com.mirad.reader.core.sources.RemoteChapter
import com.mirad.reader.core.sources.RuleExecutor
import com.mirad.reader.core.sources.RuleOperation
import com.mirad.reader.core.sources.RuleRequest
import com.mirad.reader.core.sources.RuleResult
import com.mirad.reader.core.sources.SourceCatalog
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

/** 网络始终留在宿主；规则执行器只能对给定文本和绑定求值。 */
class DefaultBookSourceEngine(
    private val http: SourceHttpClient,
    private val executor: RuleExecutor
) : BookSourceEngine {
    private data class Page(val response: SourceHttpResponse, val variables: Map<String, String>)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(source: BookSource, query: String, page: Int): List<RemoteBook> {
        supported(source)
        val rules = source.definition.objectAt("ruleSearch")
        val bindings = bindings(source, key = query, page = page)
        val fetched = fetch(source, source.definition.text("searchUrl").required("缺少搜索地址"), source.url, emptyMap(), bindings)
        val result = extract(fetched, rules.text("bookList").required("缺少搜索列表规则"), fields(rules,
            "name", "author", "bookUrl", "tocUrl", "coverUrl", "intro", "lastChapter"), bindings)
        if (result.rows.isEmpty()) restricted(fetched.response.body, inspectForms = true)?.let { throw IOException(it) }
        return result.rows.mapNotNull { row ->
            val title = row.values["name"].orEmpty().trim()
            val url = row.values["bookUrl"].orEmpty().trim()
            if (title.isEmpty() || url.isEmpty()) return@mapNotNull null
            RemoteBook(
                sourceId = source.id, sourceName = source.name, title = title,
                bookUrl = absolute(fetched.response.url, url), author = row.values["author"].orEmpty().trim(),
                tocUrl = optionalUrl(fetched.response.url, row.values["tocUrl"]),
                coverUrl = optionalUrl(fetched.response.url, row.values["coverUrl"]),
                intro = row.values["intro"].orEmpty(), latestChapter = row.values["lastChapter"].orEmpty(),
                variables = fetched.variables + result.variables + row.variables
            )
        }.distinctBy { it.bookUrl }
    }

    override suspend fun bookInfo(source: BookSource, book: RemoteBook): RemoteBook {
        supported(source)
        val rules = source.definition.objectAt("ruleBookInfo")
        if (rules.isEmpty()) return book.copy(tocUrl = book.tocUrl.ifBlank { book.bookUrl })
        val bind = bindings(source, book = book)
        val page = fetch(source, book.bookUrl, source.url, book.variables, bind)
        restricted(page.response.body, inspectText = false)?.let { throw IOException(it) }
        val result = extract(page, rules.text("init").takeIf { it.isNotBlank() }, fields(rules, "name", "author", "tocUrl", "coverUrl", "intro", "lastChapter"), bind)
        if (result.rows.size != 1) throw IOException(restricted(page.response.body, inspectForms = true) ?: "详情预处理必须返回一本书的内容")
        val row = result.rows.firstOrNull()
        val values = row?.values.orEmpty()
        if (rules.text("name").isNotBlank() && values["name"].isNullOrBlank()) {
            throw IOException(restricted(page.response.body, inspectForms = true) ?: "详情书名提取为空，无法核对书籍")
        }
        return book.copy(
            title = values["name"].orEmpty().trim().ifBlank { book.title },
            author = if (rules.text("author").isBlank()) book.author else values["author"].orEmpty().trim(),
            bookUrl = if (splitRequest(book.bookUrl).second.isNotEmpty()) book.bookUrl else page.response.url,
            tocUrl = optionalUrl(page.response.url, values["tocUrl"]).ifBlank { book.tocUrl.ifBlank { book.bookUrl } },
            coverUrl = optionalUrl(page.response.url, values["coverUrl"]).ifBlank { book.coverUrl },
            intro = values["intro"].orEmpty().ifBlank { book.intro },
            latestChapter = values["lastChapter"].orEmpty().ifBlank { book.latestChapter },
            variables = page.variables + result.variables + row?.variables.orEmpty()
        )
    }

    override suspend fun toc(source: BookSource, book: RemoteBook): SourceCatalog {
        supported(source)
        val rules = source.definition.objectAt("ruleToc")
        val chapterList = rules.text("chapterList").required("缺少目录列表规则")
        val reverse = chapterList.startsWith('-') || truth(rules.text("isReverse"))
        val listRule = chapterList.removePrefix("-")
        val pending = ArrayDeque<String>().apply { add(book.tocUrl.ifBlank { book.bookUrl }) }
        val visited = mutableSetOf<String>()
        val chapters = linkedMapOf<String, RemoteChapter>()
        var variables = book.variables
        var pageIndex = 0
        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            if (++pageIndex > 1000) throw IOException("目录分页超过 1000 页，未获得完整目录")
            val url = pending.removeFirst()
            val bind = bindings(source, book = book, page = pageIndex)
            val page = fetch(source, url, book.tocUrl.ifBlank { book.bookUrl }, variables, bind)
            if (!visited.add(page.response.requestIdentity)) {
                throw IOException("目录分页或重定向形成循环，未获得完整目录")
            }
            restricted(page.response.body, inspectText = false)?.let { throw IOException(it) }
            val result = extract(page, listRule, fields(rules, "chapterName", "chapterUrl", "isVolume", "isVip", "isPay"), bind)
            variables = page.variables + result.variables
            if (result.rows.isEmpty()) throw IOException(restricted(page.response.body, inspectForms = true) ?: "目录页未提取到章节，未获得完整目录")
            result.rows.forEach { row ->
                val volume = truth(row.values["isVolume"].orEmpty())
                if (volume) return@forEach
                val title = row.values["chapterName"].orEmpty().trim()
                val chapterUrl = row.values["chapterUrl"].orEmpty().trim()
                if (title.isBlank() || chapterUrl.isBlank()) throw IOException("目录项缺少章节名或地址，未获得完整目录")
                val resolved = absolute(page.response.url, chapterUrl)
                val chapter = RemoteChapter(
                    title = title, url = resolved,
                    restricted = truth(row.values["isVip"].orEmpty()) || truth(row.values["isPay"].orEmpty()),
                    variables = variables + row.variables
                )
                val previous = chapters[resolved]
                chapters[resolved] = previous?.copy(restricted = previous.restricted || chapter.restricted) ?: chapter
            }
            val next = extract(page.copy(variables = variables), null, fields(rules, "nextTocUrl"), bind)
            variables += next.variables + next.rows.firstOrNull()?.variables.orEmpty()
            val urls = next.rows.firstOrNull()?.values?.get("nextTocUrl").orEmpty().lineSequence()
                .map { it.trim() }.filter { it.isNotEmpty() }.map { absolute(page.response.url, it) }.distinct().toList()
            urls.forEach {
                if (pending.none { queued -> pendingRequestKey(queued) == pendingRequestKey(it) }) pending.add(it)
            }
        }
        if (chapters.isEmpty()) throw IOException("目录没有可阅读章节")
        val ordered = chapters.values.toList().let { if (reverse) it.reversed() else it }
            .mapIndexed { index, chapter -> chapter.copy(index = index) }
        return SourceCatalog(book.copy(variables = variables), ordered)
    }

    override suspend fun content(source: BookSource, book: RemoteBook, chapter: RemoteChapter, nextChapterUrl: String?): String {
        supported(source)
        if (chapter.restricted) throw IOException("该章节标记为登录或付费章节，无法下载")
        val rules = source.definition.objectAt("ruleContent")
        val contentRule = rules.text("content").required("缺少正文规则")
        val pending = ArrayDeque<String>().apply { add(chapter.url) }
        val visited = mutableSetOf<String>()
        val parts = mutableListOf<String>()
        var contentBytes = 0L
        var variables = book.variables + chapter.variables
        var pageIndex = 0
        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            if (++pageIndex > 500) throw IOException("正文分页超过 500 页，章节不完整")
            val url = pending.removeFirst()
            val bind = bindings(source, book = book, chapter = chapter, page = pageIndex)
            val page = fetch(source, url, chapter.url, variables, bind)
            if (!visited.add(page.response.requestIdentity)) {
                throw IOException("正文分页或重定向形成循环，章节不完整")
            }
            restricted(page.response.body, inspectText = false)?.let { throw IOException(it) }
            val result = extract(page, null, linkedMapOf("content" to contentRule).apply {
                rules.text("nextContentUrl").takeIf { it.isNotBlank() }?.let { put("nextContentUrl", it) }
            }, bind)
            val row = result.rows.firstOrNull()
            var text = row?.values?.get("content").orEmpty()
            variables = page.variables + result.variables + row?.variables.orEmpty()
            if (rules.text("replaceRegex").isNotBlank()) {
                val replaced = executor.execute(RuleRequest(
                    content = text, baseUrl = page.response.url,
                    fields = mapOf("content" to rules.text("replaceRegex").let { if (it.startsWith("##")) it else "##$it" }),
                    variables = variables, bindings = bind
                ))
                text = replaced.rows.firstOrNull()?.values?.get("content").orEmpty()
                variables += replaced.variables + replaced.rows.firstOrNull()?.variables.orEmpty()
            }
            text = plainText(text)
            if (text.isBlank()) throw IOException(restricted(page.response.body, inspectForms = true) ?: "正文为空，章节不完整")
            restricted(text)?.let { throw IOException(it) }
            // 与落盘的 UTF-8 上限一致，包含页面之间的两个换行；超限页不进入聚合集合。
            val nextBytes = contentBytes + text.toByteArray(Charsets.UTF_8).size + if (parts.isEmpty()) 0 else 2
            if (nextBytes > ZipEntryReader.MAX_CHAPTER_BYTES) throw IOException("章节累计正文超过 8MB 上限，已停止下载")
            parts += text
            contentBytes = nextBytes
            row?.values?.get("nextContentUrl").orEmpty().lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() }.map { absolute(page.response.url, it) }.distinct().forEach { next ->
                    if (nextChapterUrl != null && pendingRequestKey(next) == pendingRequestKey(nextChapterUrl)) return@forEach
                    if (pending.none { pendingRequestKey(it) == pendingRequestKey(next) }) pending.add(next)
                }
        }
        return parts.joinToString("\n\n")
    }

    private suspend fun fetch(
        source: BookSource, request: String, baseUrl: String, variables: Map<String, String>, bindings: JsonObject
    ): Page {
        val (urlTemplate, optionTemplate) = splitRequest(request)
        val query = (bindings["key"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val keyPlaceholder = Regex("\\{\\{\\s*key\\s*\\}\\}")
        // 先确定请求字符集，再编码直接占位；显式 encodeURIComponent 等脚本负责自己的编码。
        var requestVariables = variables
        val preparedOptions = if (keyPlaceholder.containsMatchIn(urlTemplate) && optionTemplate.isNotBlank()) {
            val prepared = executor.execute(RuleRequest(
                operation = RuleOperation.TEMPLATE, baseUrl = baseUrl, variables = variables, bindings = bindings,
                fields = mapOf("options" to optionTemplate)
            ))
            val row = prepared.rows.firstOrNull() ?: throw IOException("请求选项模板没有返回结果")
            requestVariables += prepared.variables + row.variables
            row.values["options"].orEmpty()
        } else null
        val queryCharset = preparedOptions?.takeIf { it.isNotBlank() }?.let {
            (json.parseToJsonElement(it) as? JsonObject ?: throw IOException("请求选项必须是 JSON 对象")).text("charset")
        }.orEmpty().ifBlank { "UTF-8" }
        val render = executor.execute(RuleRequest(
            operation = RuleOperation.TEMPLATE, baseUrl = baseUrl, variables = requestVariables, bindings = bindings,
            fields = linkedMapOf("url" to urlTemplate.replace(keyPlaceholder, URLEncoder.encode(query, queryCharset))).apply {
                if (preparedOptions == null) put("options", optionTemplate)
                put("header", source.definition["header"].let { if (it is JsonPrimitive) it.contentOrNull.orEmpty() else it?.toString().orEmpty() })
            }
        ))
        val row = render.rows.firstOrNull() ?: throw IOException("请求模板没有返回结果")
        val (renderedUrl, inlineOptions) = splitRequest(row.values["url"].orEmpty())
        val optionsText = (preparedOptions ?: row.values["options"].orEmpty()).ifBlank { inlineOptions }
        val options = if (optionsText.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(optionsText) as? JsonObject
            ?: throw IOException("请求选项必须是 JSON 对象")
        if (truth(options.text("webView")) || options.text("js").isNotBlank() || options.text("webJs").isNotBlank()) {
            throw IOException("该请求需要浏览器或宿主脚本能力，暂不支持")
        }
        val headers = readHeaders(row.values["header"].orEmpty()) +
            readHeaders(options["headers"]?.let(::jsonText).orEmpty()) + readHeaders(options["header"]?.let(::jsonText).orEmpty())
        val response = http.request(
            absolute(baseUrl, renderedUrl), headers = headers,
            method = options.text("method").ifBlank { "GET" },
            body = options["body"]?.let(::jsonText), charset = options.text("charset").ifBlank { null }
        )
        return Page(response, requestVariables + render.variables + row.variables)
    }

    private suspend fun extract(page: Page, list: String?, fields: Map<String, String>, bindings: JsonObject): RuleResult =
        executor.execute(RuleRequest(
            content = page.response.body, baseUrl = page.response.url, listRule = list,
            fields = fields, variables = page.variables,
            bindings = JsonObject(bindings + ("result" to JsonPrimitive(page.response.body)) + ("baseUrl" to JsonPrimitive(page.response.url)))
        ))

    private fun bindings(source: BookSource, key: String = "", page: Int = 1, book: RemoteBook? = null, chapter: RemoteChapter? = null) = buildJsonObject {
        put("key", key)
        put("page", page)
        put("source", JsonObject(source.definition + mapOf("bookSourceUrl" to JsonPrimitive(source.url), "bookSourceName" to JsonPrimitive(source.name))))
        if (book != null) put("book", buildJsonObject {
            put("name", book.title); put("title", book.title); put("author", book.author)
            put("bookUrl", book.bookUrl); put("tocUrl", book.tocUrl); put("coverUrl", book.coverUrl)
            put("intro", book.intro); put("origin", source.url)
        })
        if (chapter != null) put("chapter", buildJsonObject {
            put("name", chapter.title); put("title", chapter.title); put("url", chapter.url); put("index", chapter.index)
        })
    }

    private fun fields(rules: JsonObject, vararg names: String) = names.mapNotNull { name ->
        rules.text(name).takeIf { it.isNotBlank() }?.let { name to it }
    }.toMap(LinkedHashMap())

    private fun readHeaders(value: String): Map<String, String> = if (value.isBlank()) emptyMap() else {
        val parsed = json.parseToJsonElement(value) as? JsonObject ?: throw IOException("请求头必须是 JSON 对象")
        parsed.mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull ?: throw IOException("请求头值必须是字符串") }
    }

    private fun supported(source: BookSource) {
        source.unsupportedReason?.let { throw IOException(it) }
    }

    private fun restricted(content: String, inspectForms: Boolean = false, inspectText: Boolean = true): String? {
        val document = Jsoup.parse(content)
        val title = document.title().lowercase()
        if (Regex("(?:^|[\\s|_—:：-])(?:验证码|访问验证|安全验证|(?:用户|账号|会员)?登录)(?:$|[\\s|_—:：-])|\\b(?:sign in|access denied|security check)\\b").containsMatchIn(title) ||
            (inspectForms && document.select("input[type=password],input[name*=captcha],input[id*=captcha]").isNotEmpty())) {
            return "网站要求登录或验证码，无法获取完整内容"
        }
        val text = document.text()
        return if (inspectText && text.length < 1600 && Regex("请(?:先)?登录(?:后)?(?:阅读|查看|继续)|登录后阅读|登录后查看|请完成验证|请输入验证码|访问过于频繁|订阅本章|购买本章|付费后阅读").containsMatchIn(text))
            "网站要求登录、付费或验证，无法获取完整内容" else null
    }

    private fun plainText(value: String): String {
        val trimmed = value.trim()
        if (!Regex("</?(?:p|div|br|section|span|article|html|body)\\b", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) return trimmed
        val document = Jsoup.parse(trimmed)
        document.select("script,style").remove()
        document.select("br").forEach { it.before("\n") }
        document.select("p,div,section").forEach { it.before("\n"); it.after("\n") }
        return document.wholeText().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun splitRequest(request: String): Pair<String, String> {
        if (request.trimStart().startsWith("@js:") || request.trimStart().startsWith("<js>")) return request to ""
        var depth = 0
        var quote = '\u0000'
        var escaped = false
        request.forEachIndexed { index, char ->
            if (escaped) { escaped = false; return@forEachIndexed }
            if (char == '\\') { escaped = true; return@forEachIndexed }
            if (quote != '\u0000') {
                if (char == quote) quote = '\u0000'
            } else when (char) {
                '\'', '"', '`' -> if (depth > 0) quote = char
                '{', '(', '[' -> depth++
                '}', ')', ']' -> depth--
                ',' -> if (depth == 0 && request.substring(index + 1).trimStart().startsWith('{')) {
                    return request.substring(0, index).trim() to request.substring(index + 1).trim()
                }
            }
        }
        return request.trim() to ""
    }

    private fun absolute(base: String, value: String): String {
        val (url, options) = splitRequest(value)
        val resolved = SourceHttpClient.resolveUrl(splitRequest(base).first, url).toExternalForm()
        return if (options.isBlank()) resolved else "$resolved,$options"
    }

    private fun optionalUrl(base: String, value: String?) = value.orEmpty().trim().let { if (it.isEmpty()) "" else absolute(base, it) }
    private fun pendingRequestKey(value: String) = splitRequest(value).let { (url, options) -> url.substringBefore('#') + options }
    private fun truth(value: String) = value.trim().lowercase().let { it.isNotEmpty() && it !in setOf("false", "0", "null", "undefined") }
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.objectAt(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())
    private fun jsonText(value: JsonElement) = if (value is JsonPrimitive) value.contentOrNull.orEmpty() else value.toString()
    private fun String.required(message: String) = if (isBlank()) throw IOException(message) else this
}
