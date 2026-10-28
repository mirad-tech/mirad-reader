package com.mirad.reader.data.sources

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicReference

class BookSourceRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun definition(url: String, name: String, extra: String = ""): String = """{
        "bookSourceUrl":"$url","bookSourceName":"$name","searchUrl":"$url/search?q={{key}}",
        "ruleSearch":{"bookList":".book","name":"a@text","bookUrl":"a@href"},
        "ruleToc":{"chapterList":"a.chapter","chapterName":"text","chapterUrl":"a@href"},
        "ruleContent":{"content":"#content@text"}${if (extra.isEmpty()) "" else ",$extra"}
    }"""

    @Test
    fun importRetainsUnknownFieldsStableIdentityAndEnabledSettingAcrossRefreshAndReload() = runBlocking {
        SourceHttpFixture().use { fixture ->
            val payload = AtomicReference(definition(fixture.base, "旧名称", """"future":{"nested":17}"""))
            fixture.route("/sources") { it.reply(payload.get()) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val root = temporary.newFolder()
                val repo = BookSourceRepository(root, SourceHttpClient(), scope)
                assertEquals(1, repo.importUrl("${fixture.base}/sources").added)
                val first = repo.sources.value.single()
                assertNotNull(first.definition["future"])
                assertEquals("${fixture.base}/sources", first.importUrl)
                repo.setEnabled(first.id, false)
                payload.set(definition(fixture.base, "新名称", """"enabled":true,"future":{"nested":18}"""))
                assertEquals(1, repo.importUrl("${fixture.base}/sources").updated)
                assertEquals(first.id, repo.sources.value.single().id)
                assertFalse(repo.sources.value.single().enabled)
                val reloaded = BookSourceRepository(root, SourceHttpClient(), scope)
                val stored = withTimeout(2000) { reloaded.sources.first { it.isNotEmpty() }.single() }
                assertEquals("新名称", stored.name)
                assertFalse(stored.enabled)
                assertEquals(1, reloaded.subscriptions.value.size)
                assertEquals(2, root.listFiles()!!.size)
                repo.remove(first.id)
                assertTrue(repo.sources.value.isEmpty())
                repo.removeSubscription("${fixture.base}/sources")
                assertTrue(repo.subscriptions.value.isEmpty())
            } finally { scope.cancel() }
        }
    }

    @Test
    fun badOrEmptyRefreshPreservesOldSourcesAndReportsMixedInvalidUnsupportedEntries() = runBlocking {
        SourceHttpFixture().use { fixture ->
            val payload = AtomicReference(definition(fixture.base, "保留"))
            fixture.route("/sources") { it.reply(payload.get()) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val repo = BookSourceRepository(temporary.newFolder(), SourceHttpClient(), scope)
                repo.importUrl("${fixture.base}/sources")
                val original = repo.sources.value
                payload.set("<html>not json</html>")
                assertEquals(1, repo.importUrl("${fixture.base}/sources").invalid)
                assertEquals(original, repo.sources.value)
                payload.set("[]")
                assertEquals(1, repo.importUrl("${fixture.base}/sources").invalid)
                assertEquals(original, repo.sources.value)
                payload.set("""[null,{"bookSourceName":"missing URL"},${definition("${fixture.base}/new", "新源")},${definition("${fixture.base}/web", "网页登录", """"loginUrl":"/login"""")}]""")
                val report = repo.importUrl("${fixture.base}/sources")
                assertEquals(2, report.added)
                assertEquals(2, report.invalid)
                assertEquals(1, report.unsupported)
                assertEquals(3, repo.sources.value.size)
                assertNotNull(repo.sources.value.first { it.name == "网页登录" }.unsupportedReason)
            } finally { scope.cancel() }
        }
    }

    @Test
    fun corruptedEntryDoesNotPreventHealthyEntriesLoading() = runBlocking {
        SourceHttpFixture().use { fixture ->
            fixture.route("/sources") { it.reply("[${definition("${fixture.base}/a", "A")},${definition("${fixture.base}/b", "B")}]") }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val root = temporary.newFolder()
                val repo = BookSourceRepository(root, SourceHttpClient(), scope)
                repo.importUrl("${fixture.base}/sources")
                root.listFiles()!!.first { it.name.startsWith(repo.sources.value.first().id) }.writeText("broken")
                val loaded = BookSourceRepository(root, SourceHttpClient(), scope)
                withTimeout(2000) { loaded.loadError.first { it != null } }
                assertEquals(1, loaded.sources.value.size)
                val index = Json.parseToJsonElement(root.resolve("index.json").readText()) as JsonObject
                assertNotNull(index["entries"])
            } finally { scope.cancel() }
        }
    }

    @Test
    fun missingRequiredRulesStayVisibleWithAnUnsupportedReason() = runBlocking {
        SourceHttpFixture().use { fixture ->
            fixture.route("/missing") { it.reply("""{"bookSourceUrl":"${fixture.base}","bookSourceName":"缺规则","unknown":7}""") }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val repo = BookSourceRepository(temporary.newFolder(), SourceHttpClient(), scope)
                val report = repo.importUrl("${fixture.base}/missing")
                assertEquals(1, report.unsupported)
                assertTrue(repo.sources.value.single().unsupportedReason!!.contains("ruleContent.content"))
                assertNotNull(repo.sources.value.single().definition["unknown"])
            } finally { scope.cancel() }
        }
    }
}
