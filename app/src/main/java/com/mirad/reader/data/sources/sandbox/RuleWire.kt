package com.mirad.reader.data.sources.sandbox

import com.mirad.reader.core.sources.RuleResult
import com.mirad.reader.core.sources.RuleRequest
import com.mirad.reader.core.sources.rules.RuleEvaluator
import com.mirad.reader.core.sources.rules.RuleLimits
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream

@Serializable
internal data class SandboxReply(val result: RuleResult? = null, val error: String? = null)

internal object RuleWire {
    const val RUN = 1
    const val CANCEL = 2
    const val FINISHED = 3
    const val INPUT = "input"
    const val OUTPUT = "output"
    const val REQUEST_ID = "request_id"
    const val ERROR = "error"
    val json = Json { ignoreUnknownKeys = true }

    /** 解码和执行失败也必须有完整响应，不能仅关闭管道而丢失失败阶段。 */
    fun evaluate(text: String): SandboxReply {
        val request = try {
            json.decodeFromString<RuleRequest>(text)
        } catch (failure: Exception) {
            return SandboxReply(error = diagnostic("请求解码", failure))
        }
        return try {
            SandboxReply(result = RuleEvaluator().evaluate(request))
        } catch (failure: Exception) {
            SandboxReply(error = failure.message.orEmpty().ifBlank { "规则执行失败" }.take(300))
        }
    }

    fun encode(reply: SandboxReply): ByteArray = bytes(json.encodeToString(reply))

    fun diagnostic(stage: String, failure: Throwable): String = "规则隔离服务${stage}失败（${failure.javaClass.simpleName}）"

    fun read(stream: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            require(total <= RuleLimits.MAX_WIRE_BYTES) { "规则进程通信数据过大" }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    fun bytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8).also {
        require(it.size <= RuleLimits.MAX_WIRE_BYTES) { "规则进程通信数据过大" }
    }
}
