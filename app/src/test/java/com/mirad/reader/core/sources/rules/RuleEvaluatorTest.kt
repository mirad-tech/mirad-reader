package com.mirad.reader.core.sources.rules

import com.mirad.reader.core.sources.RuleOperation
import com.mirad.reader.core.sources.RuleRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RuleEvaluatorTest {
    private val engine = RuleEvaluator()
    private val html = """
        <div id="books"><article class="book"><a href="/1">第一本</a><b>甲</b></article>
        <article class="book"><a href="/2">第二本</a><b>乙</b></article>
        <article class="book"><a href="/3">第三本</a><b>丙</b></article></div>
    """.trimIndent()

    @Test fun defaultListSelectorsAndAttributesUseEachRow() {
        val result = engine.evaluate(RuleRequest(content = html, listRule = "id.books@children", fields = linkedMapOf("name" to "tag.a@text", "url" to "tag.a@href")))
        assertEquals(listOf("第一本", "第二本", "第三本"), result.rows.map { it.values["name"] })
        assertEquals(listOf("/1", "/2", "/3"), result.rows.map { it.values["url"] })
    }

    @Test fun indexesExclusionAndReverseSlicesAreDeterministic() {
        fun names(rule: String) = engine.evaluate(RuleRequest(content = html, listRule = rule, fields = mapOf("name" to "a@text"))).rows.map { it.values["name"] }
        assertEquals(listOf("第三本"), names("class.book.-1"))
        assertEquals(listOf("第一本", "第三本"), names("class.book[!1]"))
        assertEquals(listOf("第三本", "第二本", "第一本"), names("class.book[::-1]"))
        assertEquals(listOf("第一本", "第二本"), names("class.book[0:2]"))
    }

    @Test fun cssBracketsDoNotSplitCombinedRules() {
        val result = value("@CSS:a[href='/1']@text&&a[href='/3']@text", html)
        assertEquals("第一本\n第三本", result)
    }

    @Test fun combinationsMergeFallbackAndInterleave() {
        assertEquals("第一本\n第二本\n第三本", value(".absent@text||a@text", html))
        assertEquals("第一本\n甲\n第二本\n乙\n第三本\n丙", value("a@text%%b@text", html))
    }

    @Test fun xpathListsKeepRelativeContextAndReadAttributes() {
        val result = engine.evaluate(RuleRequest(content = html, listRule = "//article", fields = mapOf("name" to "./a/text()", "url" to "@XPath:./a/@href")))
        assertEquals(listOf("第一本", "第二本", "第三本"), result.rows.map { it.values["name"] })
        assertEquals(listOf("/1", "/2", "/3"), result.rows.map { it.values["url"] })
        assertEquals("3", value("@XPath:count(//article)", html))
    }

    @Test fun jsonPathListsAndMissingFallbackWork() {
        val result = engine.evaluate(RuleRequest(content = """{"books":[{"name":"甲","id":1},{"name":"乙","id":2}]}""", listRule = "$.books[*]", fields = mapOf("name" to "$.name", "id" to "$.id")))
        assertEquals(listOf("甲", "乙"), result.rows.map { it.values["name"] })
        assertEquals("1", result.rows[0].values["id"])
        assertEquals("甲", value("$.missing||$.name", """{"name":"甲"}"""))
    }

    @Test fun allInOneRegexPreservesCaptureGroupsPerRow() {
        val result = engine.evaluate(RuleRequest(content = "甲=/a;乙=/b;", listRule = ":([^=;]+)=([^;]+);", fields = mapOf("name" to "$1", "url" to "$2")))
        assertEquals(listOf("甲", "乙"), result.rows.map { it.values["name"] })
        assertEquals(listOf("/a", "/b"), result.rows.map { it.values["url"] })
    }

    @Test fun regexReplacementAndFirstMatchExtractionAreDifferent() {
        assertEquals("ab", value("##[0-9]+##", "a12b34"))
        assertEquals("12", value("##([0-9]+)##$1###", "a12b34"))
        assertEquals("a12b34", value("", "a12b34"))
    }

    @Test fun putGetVariablesSurviveStagesWithoutLeakingBetweenRows() {
        val result = engine.evaluate(RuleRequest(content = html, listRule = "class.book", fields = linkedMapOf("name" to "@put:{bid:'a@href'}a@text", "url" to "https://example.org@get:{bid}")))
        assertEquals(listOf("/1", "/2", "/3"), result.rows.map { it.variables["bid"] })
        assertFalse(result.variables.containsKey("bid"))
        val next = engine.evaluate(RuleRequest(operation = RuleOperation.TEMPLATE, variables = result.rows[1].variables, fields = mapOf("url" to "https://example.org@get:{bid}")))
        assertEquals("https://example.org/2", next.rows.single().values["url"])
    }

    @Test fun templatePreservesJsonHeadersAndEvaluatesExpressions() {
        val result = engine.evaluate(RuleRequest(operation = RuleOperation.TEMPLATE, bindings = json("""{"key":"a&b","page":2,"book":{"name":"测试"}}"""), fields = linkedMapOf("url" to "https://example.org/?q={{encodeURIComponent(key)}}&page={{page + 1}}", "headers" to """{"X-Book":"{{book.name}}","Accept":"text/html"}""", "plain" to "https://example.org/path@name")))
        assertEquals("https://example.org/?q=a%26b&page=3", result.rows.single().values["url"])
        assertEquals("""{"X-Book":"测试","Accept":"text/html"}""", result.rows.single().values["headers"])
        assertEquals("https://example.org/path@name", result.rows.single().values["plain"])
    }

    @Test fun templateScriptTagsCanBeCombinedWithLiteralUrlParts() {
        val result = engine.evaluate(RuleRequest(operation = RuleOperation.TEMPLATE, bindings = json("""{"page":2}"""), fields = mapOf("url" to "https://example.org/<js>page + 1</js>/index", "full" to "@js:'https://example.org/' + page")))
        assertEquals("https://example.org/3/index", result.rows.single().values["url"])
        assertEquals("https://example.org/2", result.rows.single().values["full"])
    }

    @Test fun scriptCanTransformTextAndProduceStructuredRows() {
        assertEquals("第一本!", value("a.0@text<js>result + '!'</js>", html))
        val result = engine.evaluate(RuleRequest(content = "", listRule = "@js:[{name:'甲',id:1},{name:'乙',id:2}]", fields = mapOf("name" to "name", "id" to "$.id")))
        assertEquals(listOf("甲", "乙"), result.rows.map { it.values["name"] })
    }

    @Test fun scriptJsonStringInitializerFeedsJsonPathAndDefaultFields() {
        val result = engine.evaluate(RuleRequest(content = "original", listRule = "@js:JSON.stringify({name:'书名',author:'作者',toc:'/toc'})", fields = mapOf("name" to "$.name", "author" to "author", "tocUrl" to "$.toc")))
        assertEquals(mapOf("name" to "书名", "author" to "作者", "tocUrl" to "/toc"), result.rows.single().values)
    }

    @Test fun scriptUsesTextHelpersWithoutJavaObjects() {
        val result = engine.evaluate(RuleRequest(fields = linkedMapOf("a" to "@js:java.put('token', '值'); java.base64Decode(java.base64Encode('中文'))", "b" to "@js:java.get('token')")))
        assertEquals("中文", result.rows.single().values["a"])
        assertEquals("值", result.rows.single().values["b"])
        assertEquals("值", result.variables["token"])
    }

    @Test fun scopesDoNotPersistScriptGlobals() {
        assertEquals("1", value("@js:leak = 1; leak", ""))
        assertEquals("undefined", value("@js:typeof leak", ""))
    }

    @Test fun rawNetworkAndJavaAccessAreRejected() {
        val scripts = listOf("java.ajax('https://example.org')", "Packages.java.lang.Runtime.getRuntime()", "document.cookie", "new XMLHttpRequest()")
        scripts.forEach { script ->
            assertThrows(RuleEvaluationException::class.java) { value("@js:$script", "") }
        }
    }

    @Test fun obfuscatedHostAccessStillHasNoHostObject() {
        assertEquals("undefined|undefined|undefined", value("@js:[typeof this['Pack'+'ages'],typeof this['fetch'],typeof result['get'+'Class']].join('|')", "hello"))
        assertThrows(RuleEvaluationException::class.java) { value("@js:java['a'+'jax']('https://example.org')", "") }
        assertEquals("undefined", value("@js:typeof java.get['get'+'Class']", ""))
    }

    @Test fun javascriptLoopCannotSwallowDeadlineAndNextCallStillWorks() {
        val failure = assertThrows(RuleEvaluationException::class.java) {
            RuleEvaluator(40).evaluate(RuleRequest(fields = mapOf("value" to "@js:try { while(true) {} } catch(e) { while(true) {} }")))
        }
        assertTrue(failure.message.orEmpty().contains("超时"))
        assertEquals("2", value("@js:1+1", ""))
    }

    @Test fun catastrophicRegexHasADeadline() {
        val failure = assertThrows(RuleEvaluationException::class.java) {
            RuleEvaluator(40).evaluate(RuleRequest(content = "a".repeat(40_000) + "!", fields = mapOf("value" to ":(a+)+$")))
        }
        assertTrue(failure.message.orEmpty().contains("超时"))
    }

    @Test fun requestAndOutputLimitsAreEnforced() {
        assertThrows(RuleEvaluationException::class.java) { value("", "a".repeat(RuleLimits.MAX_INPUT_CHARS + 1)) }
        assertThrows(RuleEvaluationException::class.java) { value("@js:'a'.repeat(${RuleLimits.MAX_OUTPUT_CHARS + 1})", "") }
    }

    private fun value(rule: String, content: String) = engine.evaluate(RuleRequest(content = content, fields = mapOf("value" to rule))).rows.single().values.getValue("value")
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
}
