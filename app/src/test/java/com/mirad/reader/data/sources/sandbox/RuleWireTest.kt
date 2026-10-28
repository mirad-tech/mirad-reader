package com.mirad.reader.data.sources.sandbox

import com.mirad.reader.core.sources.RuleRequest
import com.mirad.reader.core.sources.RuleOperation
import com.mirad.reader.core.sources.rules.RuleLimits
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class RuleWireTest {
    @Test fun pageLargerThanBinderBudgetRoundTripsThroughStream() {
        val request = RuleRequest(content = "正文".repeat(400_000), fields = mapOf("content" to "text"))
        val bytes = RuleWire.bytes(RuleWire.json.encodeToString(request))
        assertTrue(bytes.size > 1024 * 1024)
        val restored = RuleWire.json.decodeFromString<RuleRequest>(RuleWire.read(ByteArrayInputStream(bytes)))
        assertEquals(request, restored)
    }

    @Test fun oversizedStreamIsRejectedWithoutReadingForever() {
        val infinite = object : InputStream() {
            var consumed = 0
            override fun read(): Int { consumed++; return 'x'.code }
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                buffer.fill('x'.code.toByte(), off, off + len)
                consumed += len
                return len
            }
        }
        assertThrows(IllegalArgumentException::class.java) { RuleWire.read(infinite) }
        assertTrue(infinite.consumed <= RuleLimits.MAX_WIRE_BYTES + 16 * 1024)
    }

    @Test fun malformedRequestReturnsStructuredErrorWithoutContentLeak() {
        val reply = RuleWire.evaluate("private-url-and-content")
        val restored = RuleWire.json.decodeFromString<SandboxReply>(String(RuleWire.encode(reply), Charsets.UTF_8))
        assertTrue(restored.error.orEmpty().contains("请求解码"))
        assertTrue(!restored.error.orEmpty().contains("private-url-and-content"))
    }

    @Test fun templateHasANonemptyRoundTripReply() {
        val request = RuleRequest(operation = RuleOperation.TEMPLATE, fields = mapOf("url" to "https://example.invalid/search"))
        val reply = RuleWire.evaluate(RuleWire.json.encodeToString(request))
        val restored = RuleWire.json.decodeFromString<SandboxReply>(String(RuleWire.encode(reply), Charsets.UTF_8))
        assertEquals("https://example.invalid/search", restored.result!!.rows.single().values["url"])
    }
}
