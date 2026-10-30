package com.mirad.reader.data.sources

import com.mirad.reader.core.sources.SourceImportReport
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.coroutineContext

/** 与网络响应默认上限相同；文档流独立设限，不能依赖文件提供者报告长度。 */
internal const val SOURCE_DOCUMENT_MAX_BYTES = 8 * 1024 * 1024

internal sealed interface SourceImportPayload {
    data class Definitions(val entries: List<JsonElement>) : SourceImportPayload
    data class Links(val urls: List<String>) : SourceImportPayload
}

internal suspend fun readSourceDocument(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        coroutineContext.ensureActive()
        val size = input.read(buffer)
        if (size < 0) break
        if (output.size() + size > SOURCE_DOCUMENT_MAX_BYTES) throw IOException("书源文件超过 8 MiB 限制")
        output.write(buffer, 0, size)
    }
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
}

/** 只识别顶层清单，完整规则内部的站点、搜索、封面等 URL 绝不作为订阅抓取。 */
internal fun parseSourceImport(text: String): SourceImportPayload {
    require(text.toByteArray(Charsets.UTF_8).size <= SOURCE_DOCUMENT_MAX_BYTES) { "书源文件超过 8 MiB 限制" }
    val clean = text.removePrefix("\uFEFF").trim()
    require(clean.isNotEmpty()) { "书源文件为空，现有书源未变更" }
    if (!clean.startsWith("[") && !clean.startsWith("{")) {
        return SourceImportPayload.Links(clean.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList())
    }
    val parsed = try { Json.parseToJsonElement(clean) }
    catch (_: Exception) { throw IllegalArgumentException("文件不是有效的书源 JSON") }
    return when (parsed) {
        is JsonArray -> if (parsed.isNotEmpty() && parsed.all { it is JsonPrimitive && it.isString }) {
            SourceImportPayload.Links(parsed.map { (it as JsonPrimitive).content.trim() }.distinct())
        } else SourceImportPayload.Definitions(parsed.toList())
        is JsonObject -> if ("bookSourceUrl" !in parsed && "bookSourceName" !in parsed && "urls" in parsed) {
            val urls = parsed["urls"] as? JsonArray ?: throw IllegalArgumentException("urls 必须是链接字符串数组")
            require(urls.all { it is JsonPrimitive && it.isString }) { "urls 必须是链接字符串数组" }
            SourceImportPayload.Links(urls.map { (it as JsonPrimitive).content.trim() }.distinct())
        } else SourceImportPayload.Definitions(listOf(parsed))
        else -> throw IllegalArgumentException("书源 JSON 必须是对象或数组")
    }
}

internal fun mergeSourceReports(a: SourceImportReport, b: SourceImportReport) = SourceImportReport(
    added = a.added + b.added, updated = a.updated + b.updated, invalid = a.invalid + b.invalid,
    unsupported = a.unsupported + b.unsupported, messages = a.messages + b.messages,
    failedLinks = a.failedLinks + b.failedLinks, cancelled = a.cancelled || b.cancelled
)
