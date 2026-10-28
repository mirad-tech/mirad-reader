package com.mirad.reader.core.sources.rules

import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.PathNotFoundException
import com.mirad.reader.core.sources.RuleOperation
import com.mirad.reader.core.sources.RuleRequest
import com.mirad.reader.core.sources.RuleResult
import com.mirad.reader.core.sources.RuleRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.helper.W3CDom
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.util.regex.Pattern
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import org.w3c.dom.NodeList

/** 纯 JVM 规则内核。生产入口仅允许由 SourceRuleService 在隔离进程调用。 */
class RuleEvaluator(private val timeoutMillis: Long = RuleLimits.COMPUTE_TIMEOUT_MS) {
    fun evaluate(request: RuleRequest): RuleResult {
        requireRule(request.content.length <= RuleLimits.MAX_INPUT_CHARS, "网页内容超过规则输入限制")
        requireRule(request.fields.size <= RuleLimits.MAX_FIELDS, "单次提取字段过多")
        requireRule(request.variables.size <= RuleLimits.MAX_VARIABLES, "规则变量数量过多")
        requireRule(request.variables.all { (name, value) -> name.length in 1..128 && value.length <= RuleLimits.MAX_RULE_CHARS }, "规则变量名称或内容过长")
        requireRule(request.baseUrl.length <= RuleLimits.MAX_RULE_CHARS, "规则基础地址过长")
        requireRule(request.bindings.toString().length <= RuleLimits.MAX_INPUT_CHARS, "规则绑定数据过大")
        val session = Session(request, RuleBudget(timeoutMillis))
        return try { session.run() } catch (failure: RuleDeadlineExceeded) {
            throw RuleEvaluationException("规则执行超时")
        } catch (failure: RuleEvaluationException) {
            throw failure
        } catch (failure: Exception) {
            throw RuleEvaluationException("规则解析失败：${failure.message.orEmpty().take(240)}")
        }
    }
}

private data class RuleValue(
    val rawText: String? = null,
    val element: Element? = null,
    val json: JsonElement? = null,
    val groups: List<String>? = null,
) {
    fun raw(): String = rawText ?: element?.outerHtml() ?: when (json) {
        is JsonPrimitive -> if (json == JsonNull) "" else json.content
        null -> ""
        else -> json.toString()
    }
    fun text(): String = element?.text() ?: raw()
    fun js(): JsonElement = json ?: JsonPrimitive(raw())
}

private class Session(private val request: RuleRequest, private val budget: RuleBudget) {
    private val initial = listOf(RuleValue(rawText = request.content))
    private val bindings = request.bindings.toMap()

    fun run(): RuleResult {
        val variables = request.variables.toMutableMap()
        val items = if (request.operation == RuleOperation.TEMPLATE || request.listRule == null) initial
        else rule(request.listRule, initial, variables)
        requireRule(items.size <= RuleLimits.MAX_ROWS, "规则提取的列表过长")
        val rows = items.map { item ->
            budget.check()
            val rowVariables = variables.toMutableMap()
            val fields = linkedMapOf<String, String>()
            request.fields.forEach { (name, expression) ->
                requireRule(expression.length <= RuleLimits.MAX_RULE_CHARS, "字段 $name 的规则过长")
                val value = if (request.operation == RuleOperation.TEMPLATE) template(expression, listOf(item), rowVariables)
                else rule(expression, listOf(item), rowVariables).joinToString("\n") { it.text() }
                fields[name] = budget.output(value)
            }
            rowVariables.forEach { (key, value) -> budget.output(key); budget.output(value) }
            RuleRow(fields, rowVariables.toMap())
        }
        return RuleResult(rows, if (rows.size == 1) rows.single().variables else variables.toMap())
    }

    private fun rule(expression: String, input: List<RuleValue>, variables: MutableMap<String, String>, depth: Int = 0): List<RuleValue> {
        budget.check()
        requireRule(depth <= 32, "规则嵌套超过限制")
        requireRule(expression.length <= RuleLimits.MAX_RULE_CHARS, "规则过长")
        var text = putVariables(expression.trim(), input, variables, depth)
        if (text.isEmpty()) return input

        // 脚本是顺序处理阶段，脚本内部的 &&、|| 不是规则组合符。
        val jsTag = text.indexOf("<js>", ignoreCase = true)
        val jsPrefix = text.indexOf("@js:", ignoreCase = true)
        val jsStart = listOf(jsTag, jsPrefix).filter { it >= 0 }.minOrNull()
        if (jsStart != null) {
            val before = text.substring(0, jsStart).trim()
            val current = if (before.isEmpty()) input else rule(before, input, variables, depth + 1)
            val tagged = jsStart == jsTag
            val end = if (tagged) text.indexOf("</js>", jsStart + 4, true) else text.length
            requireRule(end >= 0, "<js> 规则缺少 </js>")
            val result = javascript(text.substring(jsStart + 4, end), current, variables)
            val after = if (tagged) text.substring(end + 5).trim() else ""
            return if (after.isBlank()) result else rule(after, result, variables, depth + 1)
        }

        val replacements = RuleSyntax.split(text, "##")
        if (replacements.size > 1) {
            val base = if (replacements[0].isBlank()) input else rule(replacements[0], input, variables, depth + 1)
            val pattern = template(replacements[1], input, variables)
            val replacement = template(replacements.getOrElse(2) { "" }, input, variables)
            val firstOnly = replacements.size >= 4
            return base.map { replace(it, pattern, replacement, firstOnly) }
        }

        for (operator in listOf("||", "&&", "%%")) {
            val alternatives = RuleSyntax.split(text, operator)
            if (alternatives.size > 1) {
                if (operator == "||") {
                    for (part in alternatives) {
                        val values = rule(part, input, variables, depth + 1)
                        if (values.any { it.text().isNotBlank() }) return values
                    }
                    return emptyList()
                }
                val groups = alternatives.map { rule(it, input, variables, depth + 1) }
                return if (operator == "&&") bounded(groups.flatten()) else bounded(buildList {
                    for (index in 0 until (groups.maxOfOrNull { it.size } ?: 0)) {
                        groups.forEach { addAll(listOfNotNull(it.getOrNull(index))) }
                    }
                })
            }
        }

        val hasTemplate = text.contains("{{") || text.contains("@get:", true)
        if (hasTemplate) {
            text = template(text, input, variables)
            // 阅读的模板规则返回字符串；显式前缀仍能把结果作为选择器继续处理。
            if (!text.startsWith("@CSS:", true) && !text.startsWith("@XPath:", true) && !text.startsWith("@Json:", true)) {
                return listOf(RuleValue(rawText = text))
            }
        }
        if (text.startsWith("@Json:", true) || text.startsWith("$.") || text.startsWith("$[") || text == "$") {
            return jsonPath(text.removePrefixIgnoreCase("@Json:"), input)
        }
        if (text.startsWith("@XPath:", true) || text.startsWith("//") || text.startsWith("./")) {
            return xpath(text.removePrefixIgnoreCase("@XPath:"), input)
        }
        if (text.startsWith("@Regex:", true) || text.startsWith(":")) {
            val pattern = if (text.startsWith(":")) text.drop(1) else text.removePrefixIgnoreCase("@Regex:")
            return regex(pattern, input)
        }
        if (Regex("^\\$\\d+$").matches(text)) {
            val index = text.drop(1).toInt()
            return input.map { RuleValue(rawText = it.groups?.getOrNull(index).orEmpty()) }
        }
        if (text.startsWith("http://") || text.startsWith("https://")) return listOf(RuleValue(rawText = text))
        if (!text.contains('@') && input.all { it.json is JsonObject || it.raw().trimStart().startsWith('{') }) {
            return jsonPath("$." + text, input)
        }
        return html(text, input)
    }

    private fun javascript(script: String, input: List<RuleValue>, variables: MutableMap<String, String>): List<RuleValue> {
        val value = if (input.size == 1) input.single().js() else JsonArray(input.map { it.js() })
        return bounded(RuleJavaScript(budget, bindings, variables, request.content, request.baseUrl).evaluate(script, value).map { RuleValue(json = it) })
    }

    private fun template(expression: String, input: List<RuleValue>, variables: MutableMap<String, String>): String {
        budget.check()
        if (expression.trimStart().startsWith("@js:", true)) {
            return javascript(expression.trimStart().drop(4), input, variables).joinToString("\n") { it.text() }
        }
        val out = StringBuilder()
        var index = 0
        while (index < expression.length) {
            budget.check()
            when {
                expression.regionMatches(index, "<js>", 0, 4, true) -> {
                    val end = expression.indexOf("</js>", index + 4, true)
                    requireRule(end >= 0, "<js> 模板缺少 </js>")
                    out.append(javascript(expression.substring(index + 4, end), input, variables).joinToString("\n") { it.text() })
                    index = end + 5
                }
                expression.startsWith("{{", index) -> {
                    val end = RuleSyntax.closingBrace(expression, index)
                    val code = expression.substring(index + 2, end - 1)
                    val value = if (code.startsWith("@") || code.startsWith("$.") || code.startsWith("//")) rule(code, input, variables)
                    else javascript(code, input, variables)
                    out.append(value.joinToString("\n") { it.text() })
                    index = end + 1
                }
                expression.regionMatches(index, "@get:", 0, 5, true) -> {
                    val start = index + 5
                    if (expression.getOrNull(start) == '{') {
                        val end = RuleSyntax.closingBrace(expression, start)
                        out.append(variables[expression.substring(start + 1, end)].orEmpty())
                        index = end + 1
                    } else {
                        val end = expression.indexOfFirstFrom(start) { !(it.isLetterOrDigit() || it == '_' || it == '-') }
                        out.append(variables[expression.substring(start, end)].orEmpty())
                        index = end
                    }
                }
                else -> out.append(expression[index++])
            }
            requireRule(out.length <= RuleLimits.MAX_OUTPUT_CHARS, "模板输出过长")
        }
        return out.toString()
    }

    private fun putVariables(expression: String, input: List<RuleValue>, variables: MutableMap<String, String>, depth: Int): String {
        var text = expression
        var start = text.indexOf("@put:", ignoreCase = true)
        while (start >= 0) {
            val firstScript = listOf(text.indexOf("<js>", ignoreCase = true), text.indexOf("@js:", ignoreCase = true)).filter { it >= 0 }.minOrNull()
            if (firstScript != null && start > firstScript) break
            val open = start + 5
            requireRule(text.getOrNull(open) == '{', "@put 需要 {变量:规则}")
            val end = RuleSyntax.closingBrace(text, open)
            val entries = RuleSyntax.split(text.substring(open + 1, end), ",")
            for (entry in entries) {
                val separator = RuleSyntax.split(entry, ":")
                requireRule(separator.size >= 2, "@put 缺少变量值规则")
                val name = RuleSyntax.unquote(separator.first())
                val valueRule = RuleSyntax.unquote(separator.drop(1).joinToString(":"))
                requireRule(name.length in 1..128, "变量名称长度不合法")
                requireRule(name in variables || variables.size < RuleLimits.MAX_VARIABLES, "规则变量数量过多")
                val value = rule(valueRule, input, variables, depth + 1).joinToString("\n") { it.text() }
                requireRule(value.length <= RuleLimits.MAX_RULE_CHARS, "规则变量内容过长")
                variables[name] = value
            }
            text = text.removeRange(start, end + 1)
            start = text.indexOf("@put:", ignoreCase = true)
        }
        return text.trim()
    }

    private fun jsonPath(path: String, input: List<RuleValue>): List<RuleValue> = bounded(input.flatMap { item ->
        budget.check()
        val jsonText = item.raw()
        val result: Any? = try { JsonPath.read<Any?>(jsonText, path) } catch (_: PathNotFoundException) { null }
        val values = when (result) { is List<*> -> result; null -> emptyList<Any>(); else -> listOf(result) }
        values.map { RuleValue(json = fromJavaJson(it)) }
    })

    private fun fromJavaJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to fromJavaJson(it.value) })
        is List<*> -> JsonArray(value.map(::fromJavaJson))
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private fun xpath(path: String, input: List<RuleValue>): List<RuleValue> = bounded(input.flatMap { item ->
        budget.check()
        val root = item.element ?: Jsoup.parse(item.raw(), request.baseUrl)
        val converter = W3CDom().namespaceAware(false)
        val document = converter.fromJsoup(root)
        val context = converter.contextNode(document)
        val expression = XPathFactory.newInstance().newXPath().compile(path)
        if (Regex("^\\s*(string|normalize-space|concat|count|boolean|number|substring|translate)\\s*\\(").containsMatchIn(path)) {
            listOf(RuleValue(rawText = expression.evaluate(context)))
        } else {
            val nodes = expression.evaluate(context, XPathConstants.NODESET) as NodeList
            requireRule(nodes.length <= RuleLimits.MAX_ROWS, "XPath 返回的列表过长")
            (0 until nodes.length).map { index ->
                val node = nodes.item(index)
                val original = node.getUserData(W3CDom.SourceProperty)
                when (original) {
                    is Element -> RuleValue(element = original)
                    is TextNode -> RuleValue(rawText = original.text())
                    else -> RuleValue(rawText = node.nodeValue ?: node.textContent.orEmpty())
                }
            }
        }
    })

    private fun regex(patternText: String, input: List<RuleValue>): List<RuleValue> {
        val pattern = Pattern.compile(patternText)
        val result = mutableListOf<RuleValue>()
        input.forEach { item ->
            val matcher = pattern.matcher(budget.text(item.raw()))
            while (matcher.find()) {
                budget.check()
                requireRule(result.size < RuleLimits.MAX_ROWS, "正则返回的列表过长")
                result += RuleValue(rawText = matcher.group(), groups = (0..matcher.groupCount()).map { matcher.group(it).orEmpty() })
            }
        }
        return result
    }

    private fun replace(input: RuleValue, patternText: String, replacement: String, firstOnly: Boolean): RuleValue {
        val pattern = Pattern.compile(patternText)
        val matcher = pattern.matcher(budget.text(input.text()))
        if (firstOnly) {
            if (!matcher.find()) return RuleValue(rawText = "")
            val only = pattern.matcher(budget.text(matcher.group()))
            return RuleValue(rawText = only.replaceFirst(replacement))
        }
        val output = StringBuffer()
        while (matcher.find()) {
            matcher.appendReplacement(output, replacement)
            requireRule(output.length <= RuleLimits.MAX_OUTPUT_CHARS, "正则替换输出过长")
        }
        matcher.appendTail(output)
        requireRule(output.length <= RuleLimits.MAX_OUTPUT_CHARS, "正则替换输出过长")
        return RuleValue(rawText = output.toString())
    }

    private fun html(rule: String, input: List<RuleValue>): List<RuleValue> {
        val css = rule.startsWith("@CSS:", true) || rule.startsWith("@@")
        val text = if (css) rule.removePrefixIgnoreCase("@CSS:").removePrefix("@@") else rule
        val parts = RuleSyntax.split(text, "@").filter { it.isNotEmpty() }
        var current = input
        parts.forEachIndexed { index, part ->
            budget.check()
            val terminal = index == parts.lastIndex && (part in setOf("text", "textNodes", "ownText", "html", "all", "href", "src", "content", "alt", "title", "value") ||
                (parts.size == 1 && rule.startsWith('@') && !css) ||
                (parts.size > 1 && !part.contains('.') && !part.contains('[') && !part.contains(':') && part != "children"))
            if (terminal) {
                current = current.flatMap { item ->
                    val element = item.element ?: Jsoup.parse(item.raw(), request.baseUrl)
                    when (part) {
                        "text" -> listOf(RuleValue(rawText = element.text()))
                        "ownText" -> listOf(RuleValue(rawText = element.ownText()))
                        "textNodes" -> element.textNodes().map { RuleValue(rawText = it.text()) }
                        "html" -> listOf(RuleValue(rawText = element.html()))
                        "all" -> listOf(RuleValue(rawText = element.outerHtml()))
                        else -> listOf(RuleValue(rawText = element.attr(part)))
                    }
                }
            } else {
                current = bounded(current.flatMap { item ->
                    val element = item.element ?: Jsoup.parse(item.raw(), request.baseUrl)
                    select(element, part, css).map { RuleValue(element = it) }
                })
            }
        }
        return current
    }

    private fun select(root: Element, original: String, forceCss: Boolean): List<Element> {
        if (forceCss) return root.select(original).toList()
        var selector = original
        var indices: String? = null
        val bracket = Regex("\\[(!?[\\d,:-]*)\\]$").find(selector)
        if (bracket != null) { indices = bracket.groupValues[1]; selector = selector.substring(0, bracket.range.first) }
        val suffix = Regex("\\.(!?-?\\d+(?::-?\\d*(?::-?\\d+)?)?(?:,-?\\d+)*)$").find(selector)
        if (suffix != null) { indices = suffix.groupValues[1]; selector = selector.substring(0, suffix.range.first) }
        val selected = when {
            selector == "children" -> root.children().toList()
            selector.startsWith("class.") -> root.getElementsByClass(selector.drop(6)).toList()
            selector.startsWith("id.") -> listOfNotNull(root.getElementById(selector.drop(3)))
            selector.startsWith("tag.") -> root.getElementsByTag(selector.drop(4)).toList()
            selector.startsWith("text.") -> root.getElementsContainingOwnText(selector.drop(5)).toList()
            else -> root.select(selector).toList()
        }
        return if (indices == null) selected else indexed(selected, indices)
    }

    private fun indexed(values: List<Element>, expression: String): List<Element> {
        val exclude = expression.startsWith('!')
        val text = expression.removePrefix("!")
        val indices = mutableListOf<Int>()
        fun position(raw: String, default: Int): Int = raw.toIntOrNull()?.let { if (it < 0) values.size + it else it } ?: default
        text.split(',').forEach { part ->
            if (part.contains(':')) {
                val range = part.split(':')
                val step = range.getOrNull(2)?.toIntOrNull() ?: 1
                requireRule(step != 0, "选择器切片步长不能为零")
                val from = position(range[0], if (step > 0) 0 else values.lastIndex)
                val until = position(range.getOrElse(1) { "" }, if (step > 0) values.size else -1)
                var i = from
                while (if (step > 0) i < until else i > until) {
                    requireRule(indices.size < RuleLimits.MAX_ROWS, "选择器索引范围过大")
                    indices += i
                    i += step
                }
            } else indices += position(part, 0)
        }
        return if (exclude) values.filterIndexed { index, _ -> index !in indices } else indices.mapNotNull(values::getOrNull)
    }

    private fun bounded(values: List<RuleValue>): List<RuleValue> {
        budget.check()
        requireRule(values.size <= RuleLimits.MAX_ROWS, "规则返回的列表过长")
        return values
    }
}

private fun String.removePrefixIgnoreCase(prefix: String): String = if (startsWith(prefix, true)) substring(prefix.length) else this
private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
    for (i in start until length) if (predicate(this[i])) return i
    return length
}
