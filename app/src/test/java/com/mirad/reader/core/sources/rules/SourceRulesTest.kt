package com.mirad.reader.core.sources.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRulesTest {
    @Test fun ordinaryUrlsAndQuotedNetworkWordsAreNotDependencies() {
        assertNull(reason("""{"bookSourceType":0,"bookSourceUrl":"https://example.org/java/ajax","searchUrl":"https://example.org/?q={{key}}","ruleContent":{"content":"@js:result.replace('java.ajax(https://example.org)', '')"}}"""))
    }

    @Test fun onlyTextSourcesAreEnabled() {
        assertTrue(reason("""{"bookSourceType":1}""").orEmpty().contains("文字小说"))
    }

    @Test fun explicitExternalLibraryAndBrowserRequirementsHaveSpecificReasons() {
        assertTrue(reason("""{"jsLib":"https://example.org/a.js"}""").orEmpty().contains("jsLib"))
        assertTrue(reason("""{"ruleContent":{"webJs":"document.body.innerText"}}""").orEmpty().contains("浏览器"))
        assertTrue(reason("""{"searchUrl":"https://example.org,{'webView':true}"}""").orEmpty().contains("WebView"))
    }

    @Test fun scriptRequirementsAreCheckedInsideTemplatesAndTags() {
        for (rule in listOf("{{java.ajax('https://example.org')}}", "<js>new XMLHttpRequest()</js>", "@js:Packages.java.lang.System.exit(0)")) {
            assertNotNull(SourceRules.unsupportedReason(JsonObject(mapOf("rule" to JsonPrimitive(rule)))))
        }
    }

    @Test fun requestResponseScriptsAreExplicitlyUnsupported() {
        assertTrue(reason("""{"searchUrl":"https://example.org,{'js':'result'}"}""").orEmpty().contains("响应预处理"))
    }

    @Test fun pureScriptsAndVariableHelpersStayEnabled() {
        assertNull(reason("""{"ruleContent":{"content":"@js:java.put('chapter', '1'); result.trim()"}}"""))
    }

    @Test fun unsupportedHostHelperReportsItsName() {
        assertTrue(reason("""{"ruleContent":{"content":"@js:java.getCookie('https://example.org')"}}""").orEmpty().contains("java.getCookie"))
    }

    private fun reason(text: String) = SourceRules.unsupportedReason(Json.parseToJsonElement(text) as JsonObject)
}
