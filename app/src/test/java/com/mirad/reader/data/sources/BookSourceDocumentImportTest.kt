package com.mirad.reader.data.sources

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class BookSourceDocumentImportTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun definition(url: String, name: String = "测试源", extra: String = "") = """{
        "bookSourceUrl":"$url","bookSourceName":"$name","searchUrl":"$url/search?q={{key}}",
        "ruleSearch":{"bookList":".book","name":"a@text","bookUrl":"a@href"},
        "ruleToc":{"chapterList":"a","chapterName":"text","chapterUrl":"href"},
        "ruleContent":{"content":"#content@text"}$extra
    }"""

    private suspend fun withRepo(work: suspend (BookSourceRepository, SourceHttpFixture, File, CoroutineScope) -> Unit) {
        SourceHttpFixture().use { fixture ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val root = temporary.newFolder()
            try { work(BookSourceRepository(root, SourceHttpClient(retries = 0), scope), fixture, root, scope) }
            finally { scope.cancel() }
        }
    }

    @Test fun bomDocumentPreservesRulesUnknownFieldsAndDoesNotFetchEmbeddedUrls() = runBlocking {
        withRepo { repo, fixture, _, _ ->
            val hits = AtomicInteger()
            fixture.route("/") { hits.incrementAndGet(); it.reply("unexpected") }
            val raw = definition(fixture.base, extra = """, "header":{"Authorization":"synthetic-only"},"urls":["${fixture.base}/never-fetch"]""")
            val report = repo.importDocument({ ByteArrayInputStream(("\uFEFF" + raw).toByteArray()) })
            assertEquals(1, report.added)
            assertNotNull(repo.sources.value.single().definition["header"])
            assertTrue(repo.sources.value.single().importUrl.isEmpty())
            assertTrue(repo.subscriptions.value.isEmpty())
            assertEquals(0, hits.get())
        }
    }

    @Test fun twoHundredSixDefinitionsRemainStableAcrossReloadAndFileUpdate() = runBlocking {
        withRepo { repo, fixture, root, scope ->
            val raw = (0 until 206).joinToString(prefix = "[", postfix = "]") {
                definition("${fixture.base}/$it", "源$it", ",\"future\":{\"value\":$it}")
            }
            assertEquals(206, repo.importText(raw).added)
            val first = repo.sources.value.first()
            repo.setEnabled(first.id, false)
            assertEquals(206, repo.importText(raw).updated)
            assertFalse(repo.sources.value.first { it.id == first.id }.enabled)
            val reloaded = BookSourceRepository(root, SourceHttpClient(), scope)
            val values = withTimeout(3000) { reloaded.sources.first { it.size == 206 } }
            assertFalse(values.first { it.id == first.id }.enabled)
            assertNotNull(values.first().definition["future"])
            assertTrue(reloaded.subscriptions.value.isEmpty())
        }
    }

    @Test fun fileUpdatesDoNotLoseExistingNetworkSubscriptionOrEnabledState() = runBlocking {
        withRepo { repo, fixture, _, _ ->
            fixture.route("/rules") { it.reply(definition(fixture.base)) }
            repo.importUrl("${fixture.base}/rules")
            val source = repo.sources.value.single()
            repo.setEnabled(source.id, false)
            repo.importText(definition(fixture.base, "文件更新"))
            assertEquals(source.id, repo.sources.value.single().id)
            assertFalse(repo.sources.value.single().enabled)
            assertEquals("${fixture.base}/rules", repo.sources.value.single().importUrl)
            assertEquals(1, repo.subscriptions.value.size)
            assertEquals(1, repo.importUrl("${fixture.base}/rules").updated)
            assertFalse(repo.sources.value.single().enabled)
        }
    }

    @Test fun emptyMalformedUnsupportedAndOverLimitInputsDoNotEraseStoredSources() = runBlocking {
        withRepo { repo, fixture, _, _ ->
            repo.importText(definition(fixture.base))
            val original = repo.sources.value
            for (raw in listOf("", "[]", "{", "{\"urls\":[]}")) {
                assertEquals(1, repo.importText(raw).invalid)
                assertEquals(original, repo.sources.value)
            }
            val mixed = repo.importText("[null,{\"bookSourceName\":\"bad\"}," +
                definition("${fixture.base}/login", extra = ",\"loginUrl\":\"/login\"") + "]")
            assertEquals(2, mixed.invalid)
            assertEquals(1, mixed.unsupported)
            assertEquals(1, mixed.added)
            val count = repo.sources.value.size
            try {
                repo.importDocument({ ByteArrayInputStream(ByteArray(SOURCE_DOCUMENT_MAX_BYTES + 1)) })
                fail("over-limit document accepted")
            } catch (_: IOException) { }
            assertEquals(count, repo.sources.value.size)
            try {
                repo.importDocument({ ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)) })
                fail("malformed UTF8 accepted")
            } catch (_: IOException) { }
            assertEquals(count, repo.sources.value.size)
        }
    }

    @Test fun allThreeLinkListFormatsDeduplicateAndRefreshThroughTheExistingUrlPath() = runBlocking {
        withRepo { repo, fixture, _, _ ->
            val hits = AtomicInteger()
            fixture.route("/rules") { hits.incrementAndGet(); it.reply(definition(fixture.base)) }
            val url = "${fixture.base}/rules"
            for (raw in listOf("$url\n$url\n", "[\"$url\",\"$url\"]", "{\"urls\":[\"$url\",\"$url\"]}")) {
                val reports = mutableListOf<Int>()
                val report = repo.importText(raw) { reports += it.total }
                assertEquals(1, report.added + report.updated)
                assertTrue(reports.all { it == 1 })
            }
            assertEquals(3, hits.get())
            assertEquals(1, repo.sources.value.size)
            assertEquals(1, repo.subscriptions.value.size)
        }
    }

    @Test fun aFailedLinkDoesNotRollBackEarlierSuccessOrStopFollowingLinks() = runBlocking {
        withRepo { repo, fixture, _, _ ->
            fixture.route("/a") { it.reply(definition("${fixture.base}/one")) }
            fixture.route("/b") { it.reply("unavailable", 404) }
            fixture.route("/c") { it.reply(definition("${fixture.base}/two")) }
            val completed = mutableListOf<Int>()
            val report = repo.importText("${fixture.base}/a\n${fixture.base}/b\n${fixture.base}/c") { completed += it.completed }
            assertEquals(2, report.added)
            assertEquals(1, report.failedLinks)
            assertEquals(listOf(0, 1, 2, 3), completed)
            assertEquals(2, repo.sources.value.size)
        }
    }

    @Test fun cancellingAfterACommittedLinkRetainsItAndDoesNotFetchTheNextLink() = runBlocking {
        withRepo { repo, fixture, _, _ ->
            val nextHits = AtomicInteger()
            fixture.route("/first") { it.reply(definition(fixture.base)) }
            fixture.route("/next") { nextHits.incrementAndGet(); it.reply(definition("${fixture.base}/next-site")) }
            lateinit var job: Job
            var added = 0
            job = launch(start = CoroutineStart.LAZY) {
                repo.importText("${fixture.base}/first\n${fixture.base}/next") {
                    added = it.report.added
                    if (it.completed == 1) job.cancel()
                }
            }
            job.start(); job.join()
            assertTrue(job.isCancelled)
            assertEquals(1, added)
            assertEquals(1, repo.sources.value.size)
            assertEquals(0, nextHits.get())
        }
    }
}
