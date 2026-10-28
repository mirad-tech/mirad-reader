package com.mirad.reader.data.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SourceHttpClientTest {
    @Test
    fun followsRelativeRedirectRetainsPostAndDecodesDeclaredCharset() = runBlocking {
        SourceHttpFixture().use { fixture ->
            val gbk = Charset.forName("GBK")
            fixture.route("/start") { exchange ->
                exchange.responseHeaders.add("Location", "final")
                exchange.sendResponseHeaders(307, -1)
                exchange.close()
            }
            fixture.route("/final") { exchange ->
                assertEquals("POST", exchange.requestMethod)
                assertEquals("书名=一二", String(exchange.requestBody.readBytes(), gbk))
                assertEquals("custom", exchange.requestHeaders.getFirst("X-Source"))
                exchange.reply("正文编码正确", charset = gbk)
            }
            val response = SourceHttpClient().request(
                "${fixture.base}/start", mapOf("X-Source" to "custom"), "POST", "书名=一二", "GBK"
            )
            assertEquals("正文编码正确", response.body)
            assertEquals("${fixture.base}/final", response.url)
        }
    }

    @Test
    fun retriesTransientGetOnceAndRejectsOversizedPages() = runBlocking {
        SourceHttpFixture().use { fixture ->
            val requests = AtomicInteger()
            fixture.route("/retry") { exchange -> exchange.reply("ok", if (requests.incrementAndGet() == 1) 503 else 200) }
            fixture.route("/large") { exchange -> exchange.reply("x".repeat(1024)) }
            assertEquals("ok", SourceHttpClient().request("${fixture.base}/retry").body)
            assertEquals(2, requests.get())
            val error = runCatching { SourceHttpClient(maxPageBytes = 64, retries = 0).request("${fixture.base}/large") }.exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue(error!!.message.orEmpty().contains("超过"))
        }
    }

    @Test
    fun sharesSixGlobalAndTwoPerHostPermits() = runBlocking {
        SourceHttpFixture().use { fixture ->
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val perHost = ConcurrentHashMap<String, AtomicInteger>()
            val perHostMaximum = ConcurrentHashMap<String, AtomicInteger>()
            fixture.route("/slow") { exchange ->
                val host = exchange.requestHeaders.getFirst("Host")
                val domain = perHost.getOrPut(host) { AtomicInteger() }
                maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                perHostMaximum.getOrPut(host) { AtomicInteger() }.accumulateAndGet(domain.incrementAndGet(), ::maxOf)
                try {
                    Thread.sleep(150)
                    exchange.reply("ok")
                } finally { active.decrementAndGet(); domain.decrementAndGet() }
            }
            val client = SourceHttpClient()
            (0 until 24).map { index -> async(Dispatchers.Default) {
                client.request("http://127.0.0.${index % 4 + 1}:${fixture.server.address.port}/slow")
            } }.awaitAll()
            assertTrue(maximum.get() in 3..6)
            assertTrue(perHostMaximum.values.all { it.get() <= 2 })
        }
    }

    @Test
    fun cancellingBlockedResponseReleasesRequest() = runBlocking {
        SourceHttpFixture().use { fixture ->
            val started = AtomicInteger()
            fixture.route("/blocked") { exchange ->
                started.incrementAndGet()
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.flush()
                Thread.sleep(3000)
                exchange.close()
            }
            fixture.route("/ok") { it.reply("next") }
            val client = SourceHttpClient(readTimeoutMs = 1000, requestTimeoutMs = 2000, retries = 0)
            val job = launch(Dispatchers.Default) { client.request("${fixture.base}/blocked") }
            withTimeout(2000) { while (started.get() == 0) delay(10) }
            withTimeout(2000) { job.cancelAndJoin() }
            assertFalse(job.isActive)
            assertEquals("next", client.request("${fixture.base}/ok").body)
        }
    }

    @Test
    fun queueWaitDoesNotConsumeTheNetworkExecutionTimeout() = runBlocking {
        SourceHttpFixture().use { fixture ->
            fixture.route("/queued") { exchange ->
                Thread.sleep(120)
                exchange.reply("ok")
            }
            val client = SourceHttpClient(requestTimeoutMs = 350, retries = 0)
            // 同域最多两个并发；后半请求排队超过350ms，取得许可后仍有完整网络预算。
            val responses = (0 until 12).map { async(Dispatchers.Default) { client.request("${fixture.base}/queued") } }.awaitAll()
            assertEquals(12, responses.size)
            assertTrue(responses.all { it.body == "ok" })
        }
    }

    @Test
    fun internalTimeoutIsReportedAsNetworkFailureInsteadOfUserCancellation() = runBlocking {
        SourceHttpFixture().use { fixture ->
            fixture.route("/timeout") { exchange ->
                Thread.sleep(500)
                exchange.reply("late")
            }
            val error = runCatching { SourceHttpClient(requestTimeoutMs = 100, retries = 0).request("${fixture.base}/timeout") }.exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue(error!!.message!!.contains("超时"))
        }
    }

    @Test
    fun dropsCredentialsAcrossRedirectOriginAndRejectsRedirectLoop() = runBlocking {
        SourceHttpFixture().use { fixture ->
            fixture.route("/cross") { exchange ->
                exchange.responseHeaders.add("Location", "http://localhost:${fixture.server.address.port}/target")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            fixture.route("/target") { exchange ->
                assertEquals(null, exchange.requestHeaders.getFirst("Authorization"))
                assertEquals(null, exchange.requestHeaders.getFirst("Cookie"))
                exchange.reply("safe")
            }
            fixture.route("/loop") { exchange ->
                exchange.responseHeaders.add("Location", "/loop")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            assertEquals("safe", SourceHttpClient().request("${fixture.base}/cross", mapOf("Authorization" to "secret", "Cookie" to "session=test")).body)
            assertTrue(runCatching { SourceHttpClient(retries = 0).request("${fixture.base}/loop") }.exceptionOrNull()!!.message!!.contains("循环"))
        }
    }
}
