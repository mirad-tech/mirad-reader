package com.mirad.reader.core.sources.rules

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** 静态预检只标记明确依赖；动态构造的宿主访问仍由没有宿主能力的沙箱拒绝。 */
object SourceRules {
    fun unsupportedReason(source: JsonObject): String? {
        val type = (source["bookSourceType"] as? JsonPrimitive)?.intOrNull ?: 0
        if (type != 0) return "只支持文字小说书源（bookSourceType=$type）"
        return try { inspect(source, "", 0) } catch (failure: RuleEvaluationException) { failure.message }
    }

    private fun inspect(value: JsonElement, path: String, depth: Int): String? {
        if (depth > 64) return "书源规则嵌套过深"
        return when (value) {
            is JsonObject -> value.entries.firstNotNullOfOrNull { (key, child) ->
                val field = if (path.isEmpty()) key else "$path.$key"
                val text = (child as? JsonPrimitive)?.contentOrNull.orEmpty()
                when {
                    path.isEmpty() && key in setOf("bookSourceName", "bookSourceGroup", "bookSourceComment", "comment") -> null
                    key.equals("jsLib", true) && text.isNotBlank() -> "$field 依赖共享或外部 jsLib，当前只执行规则内的纯文本 JavaScript"
                    key.equals("webView", true) && ((child as? JsonPrimitive)?.booleanOrNull == true || text == "1") -> "$field 需要 WebView 渲染"
                    key.lowercase() in setOf("webjs", "loginurl", "loginui", "logincheckjs") && text.isNotBlank() -> "$field 需要浏览器或登录会话能力"
                    else -> inspect(child, field, depth + 1)
                }
            }
            is JsonArray -> value.firstNotNullOfOrNull { inspect(it, path, depth + 1) }
            is JsonPrimitive -> {
                val text = value.contentOrNull.orEmpty()
                when {
                    text.contains("@webjs:", true) -> "$path 使用 WebView JavaScript"
                    Regex("(?i)[\"']webView[\"']\\s*:\\s*(true|1)").containsMatchIn(text) -> "$path 的请求选项要求 WebView 渲染"
                    Regex("(?i),\\s*\\{[\\s\\S]*?[\"'](?:js|webJs)[\"']\\s*:").containsMatchIn(text) -> "$path 的请求选项包含暂不支持的响应预处理脚本"
                    else -> RuleSyntax.scripts(text).firstNotNullOfOrNull { scriptReason(it)?.let { reason -> "$path：$reason" } }
                }
            }
        }
    }

    internal fun scriptReason(script: String): String? {
        val code = RuleSyntax.executableText(script)
        val unsupportedHelper = Regex("\\bjava\\s*\\.\\s*([A-Za-z_$][\\w$]*)\\s*\\(")
            .findAll(code).map { it.groupValues[1] }
            .firstOrNull { it !in setOf("get", "put", "base64Decode", "base64Encode") }
        return when {
            Regex("\\b(java\\s*\\.\\s*(ajax|ajaxAll|connect|post|head|downloadFile|importScript|readTxtFile|cacheFile)|fetch|XMLHttpRequest|WebSocket|importScripts)\\s*\\(").containsMatchIn(code) -> "脚本依赖主动网络或外部文件，隔离规则只处理已下载文本"
            Regex("\\b(java\\s*\\.\\s*(startBrowser|startBrowserAwait|webView|webViewGet|webViewPost|getVerificationCode)|document|window)\\b").containsMatchIn(code) -> "脚本依赖浏览器或 WebView 环境"
            Regex("\\b(Packages|JavaAdapter|JavaImporter|importClass|importPackage|getClass)\\b|\\b(?:java|javax|android|org)\\s*\\.\\s*(?:lang|io|net|util|content|app|mozilla)\\b").containsMatchIn(code) -> "脚本依赖 Java 或 Android 宿主对象"
            unsupportedHelper != null -> "脚本依赖尚不支持的宿主方法 java.$unsupportedHelper"
            else -> null
        }
    }
}
