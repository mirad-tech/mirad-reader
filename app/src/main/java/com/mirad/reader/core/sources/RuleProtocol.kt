package com.mirad.reader.core.sources

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class RuleOperation { EXTRACT, TEMPLATE }

/** 一次请求提取一页的全部字段，避免逐字段跨进程传输页面。 */
@Serializable
data class RuleRequest(
    val operation: RuleOperation = RuleOperation.EXTRACT,
    val content: String = "",
    val baseUrl: String = "",
    val listRule: String? = null,
    val fields: Map<String, String> = emptyMap(),
    val variables: Map<String, String> = emptyMap(),
    val bindings: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class RuleRow(val values: Map<String, String>, val variables: Map<String, String> = emptyMap())

@Serializable
data class RuleResult(val rows: List<RuleRow>, val variables: Map<String, String> = emptyMap())

fun interface RuleExecutor {
    suspend fun execute(request: RuleRequest): RuleResult
}
