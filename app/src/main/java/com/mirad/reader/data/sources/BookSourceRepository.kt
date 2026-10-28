package com.mirad.reader.data.sources

import com.mirad.reader.core.sources.BookSource
import com.mirad.reader.core.sources.SourceImportReport
import com.mirad.reader.core.sources.SourceRepository
import com.mirad.reader.core.sources.SourceSubscription
import com.mirad.reader.core.sources.rules.SourceRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** 每条定义使用独立文件；只有索引原子替换成功后，新批次才对读者可见。 */
class BookSourceRepository(
    private val root: File,
    private val http: SourceHttpClient,
    scope: CoroutineScope
) : SourceRepository {
    @Serializable
    private data class SourceIndex(
        val entries: Map<String, String> = emptyMap(),
        val subscriptions: List<SourceSubscription> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val mutableSources = MutableStateFlow<List<BookSource>>(emptyList())
    private val mutableSubscriptions = MutableStateFlow<List<SourceSubscription>>(emptyList())
    private val mutableLoadError = MutableStateFlow<String?>(null)
    override val sources = mutableSources.asStateFlow()
    override val subscriptions = mutableSubscriptions.asStateFlow()
    override val loadError = mutableLoadError.asStateFlow()
    private var index = SourceIndex()
    private val ready = scope.async(Dispatchers.IO) { load() }

    private fun load() {
        try {
            if (!root.exists() && !root.mkdirs()) throw IOException("无法创建书源目录")
            val indexFile = File(root, "index.json")
            if (!indexFile.exists()) return
            index = json.decodeFromString(indexFile.readText())
            val errors = mutableListOf<String>()
            val loaded = index.entries.mapNotNull { (id, filename) ->
                try {
                    require(filename.matches(Regex("[0-9a-f]{64}-[0-9a-f-]{36}\\.json"))) { "书源文件名无效" }
                    json.decodeFromString<BookSource>(File(root, filename).readText()).also {
                        require(it.id == id && stableId(it.url) == id) { "书源身份不匹配" }
                    }
                } catch (error: Exception) {
                    errors += "$id：${error.message ?: "读取失败"}"
                    null
                }
            }
            mutableSubscriptions.value = index.subscriptions
            mutableSources.value = sorted(loaded)
            mutableLoadError.value = errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
        } catch (error: Exception) {
            mutableLoadError.value = "书源读取失败：${error.message ?: "文件损坏"}"
        }
    }

    override suspend fun importUrl(url: String): SourceImportReport {
        ready.await()
        val importUrl = SourceHttpClient.checkedUrl(url).toExternalForm()
        val response = http.request(importUrl)
        val parsed = try {
            json.parseToJsonElement(response.body)
        } catch (error: Exception) {
            return SourceImportReport(invalid = 1, messages = listOf("响应不是有效的书源 JSON：${error.message}"))
        }
        val elements = when (parsed) {
            is JsonArray -> parsed.toList()
            is JsonObject -> listOf(parsed)
            else -> return SourceImportReport(invalid = 1, messages = listOf("书源 JSON 必须是对象或数组"))
        }
        val messages = mutableListOf<String>()
        var invalid = 0
        var unsupported = 0
        val accepted = linkedMapOf<String, BookSource>()
        elements.forEachIndexed { position, element ->
            try {
                val definition = element as? JsonObject ?: error("条目必须是对象")
                val name = (definition["bookSourceName"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val sourceUrl = (definition["bookSourceUrl"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                require(name.isNotEmpty()) { "缺少 bookSourceName" }
                SourceHttpClient.checkedUrl(sourceUrl)
                val reason = SourceRules.unsupportedReason(definition) ?: missingRequiredRule(definition)
                if (reason != null) {
                    unsupported++
                    messages += "$name：$reason"
                }
                val id = stableId(sourceUrl)
                accepted[id] = BookSource(
                    id = id, name = name, url = sourceUrl, definition = definition,
                    enabled = (definition["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                    importUrl = importUrl, unsupportedReason = reason
                )
            } catch (error: Exception) {
                invalid++
                messages += "第 ${position + 1} 项：${error.message ?: "定义无效"}"
            }
        }
        if (accepted.isEmpty()) return SourceImportReport(
            invalid = invalid.coerceAtLeast(1), unsupported = unsupported,
            messages = messages.ifEmpty { listOf("书源数组为空，现有书源未变更") }
        )
        return mutex.withLock {
            val current = sources.value.associateBy { it.id }.toMutableMap()
            val updated = accepted.keys.count { it in current }
            accepted.forEach { (id, source) -> current[id] = source.copy(enabled = current[id]?.enabled ?: source.enabled) }
            val nextSubscriptions = subscriptions.value.filterNot { it.url == importUrl } +
                SourceSubscription(importUrl, System.currentTimeMillis())
            persist(current.values.toList(), nextSubscriptions, accepted.keys)
            SourceImportReport(accepted.size - updated, updated, invalid, unsupported, messages)
        }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        ready.await()
        mutex.withLock {
            if (sources.value.none { it.id == id && it.enabled != enabled }) return
            persist(sources.value.map { if (it.id == id) it.copy(enabled = enabled) else it }, subscriptions.value, setOf(id))
        }
    }

    override suspend fun remove(id: String) {
        ready.await()
        mutex.withLock { persist(sources.value.filterNot { it.id == id }, subscriptions.value, emptySet()) }
    }

    override suspend fun removeSubscription(url: String) {
        ready.await()
        mutex.withLock { persist(sources.value, subscriptions.value.filterNot { it.url == url }, emptySet()) }
    }

    private suspend fun persist(next: List<BookSource>, subscriptions: List<SourceSubscription>, changed: Set<String>) {
        withContext(Dispatchers.IO + NonCancellable) {
            val entries = index.entries.filterKeys { id -> next.any { it.id == id } }.toMutableMap()
            next.filter { it.id in changed || it.id !in entries }.forEach { source ->
                val filename = "${source.id}-${UUID.randomUUID()}.json"
                atomicWrite(File(root, filename), json.encodeToString(source))
                entries[source.id] = filename
            }
            val nextIndex = SourceIndex(entries, subscriptions)
            atomicWrite(File(root, "index.json"), json.encodeToString(nextIndex))
            val obsolete = index.entries.values.toSet() - entries.values.toSet()
            index = nextIndex
            mutableSources.value = sorted(next)
            mutableSubscriptions.value = subscriptions
            obsolete.filter { it.matches(Regex("[0-9a-f]{64}-[0-9a-f-]{36}\\.json")) }.forEach { File(root, it).delete() }
        }
    }

    private fun atomicWrite(file: File, content: String) {
        val temporary = File(root, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun sorted(values: List<BookSource>) = values.sortedWith(compareBy<BookSource> { it.name.lowercase() }.thenBy { it.id })

    private fun missingRequiredRule(definition: JsonObject): String? {
        val required = listOf("searchUrl", "ruleSearch.bookList", "ruleSearch.name", "ruleSearch.bookUrl",
            "ruleToc.chapterList", "ruleToc.chapterName", "ruleToc.chapterUrl", "ruleContent.content")
        val missing = required.filter { path ->
            val value = path.split('.').fold(definition as kotlinx.serialization.json.JsonElement?) { item, key ->
                (item as? JsonObject)?.get(key)
            }
            (value as? JsonPrimitive)?.contentOrNull.isNullOrBlank()
        }
        return missing.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "缺少必要规则：")
    }

    companion object {
        fun stableId(url: String): String = MessageDigest.getInstance("SHA-256")
            .digest(url.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
