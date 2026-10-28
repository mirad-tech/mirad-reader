package com.mirad.reader.data.sources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SourceHttpResponse(
    val body: String,
    val url: String,
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val requestIdentity: String
)

class SourceHttpException(val statusCode: Int, message: String) : IOException(message)

/** 书源导入、规则请求与章节下载共享此实例，取消会立即断开当前连接。 */
class SourceHttpClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
    private val requestTimeoutMs: Long = 45_000,
    private val maxPageBytes: Int = 8 * 1024 * 1024,
    private val retries: Int = 1
) {
    private val global = Semaphore(6)
    private val domains = ConcurrentHashMap<String, Semaphore>()
    private class RequestBudget(var remainingMillis: Long)

    suspend fun request(
        url: String,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: String? = null,
        charset: String? = null
    ): SourceHttpResponse = withContext(Dispatchers.IO) {
        val budget = RequestBudget(requestTimeoutMs)
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            try {
                return@withContext followRedirects(url, headers, method, body, charset, budget)
            } catch (error: IOException) {
                coroutineContext.ensureActive()
                val retryable = error !is SourceHttpException ||
                    error.statusCode in setOf(408, 429, 500, 502, 503, 504)
                if (!retryable || budget.remainingMillis <= 0 || attempt++ >= retries || method.uppercase(Locale.ROOT) != "GET") throw error
                delay(200L * attempt)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private suspend fun followRedirects(
        initial: String,
        initialHeaders: Map<String, String>,
        initialMethod: String,
        initialBody: String?,
        charset: String?,
        budget: RequestBudget
    ): SourceHttpResponse {
        var url = checkedUrl(initial)
        var method = initialMethod.uppercase(Locale.ROOT)
        require(method == "GET" || method == "POST") { "书源仅支持 GET 或 POST 请求" }
        var body = initialBody
        var headers = initialHeaders
        val visited = mutableSetOf<String>()
        repeat(9) {
            val identity = requestIdentity(url, headers, method, body, charset)
            if (!visited.add(identity)) throw IOException("请求重定向形成循环")
            val response = domains.getOrPut(url.host.lowercase(Locale.ROOT)) { Semaphore(2) }.withPermit {
                global.withPermit {
                    // 排队不消耗网络执行预算；重定向和重试共用剩余预算。
                    val started = System.nanoTime()
                    try {
                        withTimeoutOrNull(budget.remainingMillis) { exchange(url, headers, method, body, charset, identity) }
                            ?: throw IOException("网络请求超时")
                    } finally {
                        budget.remainingMillis -= (System.nanoTime() - started) / 1_000_000
                    }
                }
            }
            if (response.statusCode !in setOf(301, 302, 303, 307, 308)) return response
            val location = response.headers.entries.firstOrNull { it.key.equals("Location", true) }
                ?.value?.firstOrNull() ?: throw IOException("重定向缺少目标地址")
            val target = resolveUrl(url.toExternalForm(), location)
            if (url.protocol != target.protocol || url.host != target.host || url.port != target.port) {
                headers = headers.filterKeys { !it.equals("Authorization", true) && !it.equals("Cookie", true) }
            }
            if (response.statusCode == 303 || (response.statusCode in setOf(301, 302) && method == "POST")) {
                method = "GET"
                body = null
            }
            url = target
        }
        throw IOException("请求重定向次数过多")
    }

    private suspend fun exchange(
        url: URL,
        headers: Map<String, String>,
        method: String,
        body: String?,
        charset: String?,
        identity: String
    ): SourceHttpResponse = suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<HttpURLConnection?>()
        continuation.invokeOnCancellation { active.get()?.disconnect() }
        try {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = method
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) MiradReader/1.0")
                setRequestProperty("Accept-Encoding", "gzip")
                headers.forEach { (key, value) ->
                    require(!key.contains('\r') && !key.contains('\n') && !value.contains('\r') && !value.contains('\n')) {
                        "请求头含非法换行"
                    }
                    setRequestProperty(key, value)
                }
            }
            active.set(connection)
            if (!continuation.isActive) throw CancellationException("请求已取消")
            if (method == "POST") {
                val bytes = body.orEmpty().toByteArray(charset?.let(Charset::forName) ?: Charsets.UTF_8)
                connection.doOutput = true
                if (headers.keys.none { it.equals("Content-Type", true) }) {
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=${charset ?: "UTF-8"}")
                }
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val responseHeaders = connection.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
            if (status in setOf(301, 302, 303, 307, 308)) {
                if (continuation.isActive) continuation.resume(SourceHttpResponse("", url.toExternalForm(), status, responseHeaders, identity))
            } else {
                if (status !in 200..299) throw SourceHttpException(status, when (status) {
                    401 -> "网站要求登录（HTTP 401）"
                    403 -> "网站拒绝访问或要求验证（HTTP 403）"
                    429 -> "网站请求过于频繁（HTTP 429）"
                    else -> "网站请求失败（HTTP $status）"
                })
                if (connection.contentLengthLong > maxPageBytes) throw IOException("页面超过 ${maxPageBytes / 1024} KB 限制")
                val bytes = ByteArrayOutputStream()
                val input = connection.inputStream.let {
                    if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(it) else it
                }
                input.use { stream ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        if (!continuation.isActive) throw CancellationException("请求已取消")
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (bytes.size() + read > maxPageBytes) throw IOException("页面超过 ${maxPageBytes / 1024} KB 限制")
                        bytes.write(buffer, 0, read)
                    }
                }
                val data = bytes.toByteArray()
                val encoding = charset ?: Regex("charset\\s*=\\s*[\"']?([^;\\s\"'>]+)", RegexOption.IGNORE_CASE)
                    .find(connection.contentType.orEmpty())?.groupValues?.get(1)
                    ?: Regex("charset\\s*=\\s*[\"']?([^;\\s\"'>]+)", RegexOption.IGNORE_CASE)
                        .find(String(data, 0, minOf(data.size, 4096), Charsets.ISO_8859_1))?.groupValues?.get(1)
                    ?: "UTF-8"
                val text = String(data, Charset.forName(encoding)).removePrefix("\uFEFF")
                if (continuation.isActive) continuation.resume(SourceHttpResponse(text, url.toExternalForm(), status, responseHeaders, identity))
            }
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        } finally {
            active.getAndSet(null)?.disconnect()
        }
    }

    /** 记录实际发出的请求；重定向改变方法或正文后重新计算，不把大请求体留在分页去重集合中。 */
    private fun requestIdentity(url: URL, headers: Map<String, String>, method: String, body: String?, charset: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        field(url.toExternalForm().substringBefore('#'))
        field(method)
        field(charset?.let(Charset::forName)?.name() ?: "UTF-8")
        field(if (method == "POST") body.orEmpty() else "")
        headers.mapKeys { it.key.lowercase(Locale.ROOT) }.toSortedMap().forEach { (key, value) ->
            field(key)
            field(value)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun resolveUrl(baseUrl: String, reference: String): URL {
            val relative = reference.trim()
            val resolved = when {
                relative.startsWith('?') -> baseUrl.substringBefore('#').substringBefore('?') + relative
                relative.startsWith('#') -> baseUrl.substringBefore('#') + relative
                else -> URL(URL(baseUrl), relative).toExternalForm()
            }
            return checkedUrl(resolved)
        }

        fun checkedUrl(value: String): URL {
            val uri = URI(value.trim().replace(" ", "%20"))
            require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank()) {
                "地址必须是有效的 HTTP 或 HTTPS URL"
            }
            require(uri.userInfo == null) { "地址不能包含用户名或密码" }
            return URL(uri.toASCIIString())
        }
    }
}
