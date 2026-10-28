package com.mirad.reader.core.sources.rules

/** 规则是外部程序；计算、数据量和递归深度都必须有界。 */
object RuleLimits {
    const val MAX_INPUT_CHARS = 4 * 1024 * 1024
    const val MAX_OUTPUT_CHARS = 4 * 1024 * 1024
    const val MAX_WIRE_BYTES = 16 * 1024 * 1024
    const val MAX_RULE_CHARS = 64 * 1024
    const val MAX_ROWS = 30_000
    const val MAX_FIELDS = 64
    const val MAX_VARIABLES = 128
    const val COMPUTE_TIMEOUT_MS = 3_000L
    const val PROCESS_TIMEOUT_MS = 5_000L
    const val REQUEST_TIMEOUT_MS = 12_000L
}

class RuleEvaluationException(message: String) : IllegalArgumentException(message)

/** Error 不允许被脚本的 try/catch 吞掉；生产环境还有独立进程看门狗兜底。 */
internal class RuleDeadlineExceeded : Error("规则执行超时")

internal class RuleBudget(timeoutMillis: Long) {
    private val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    private var outputChars = 0L

    fun check() {
        if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted) {
            throw RuleDeadlineExceeded()
        }
    }

    fun output(text: String): String {
        check()
        outputChars += text.length
        requireRule(outputChars <= RuleLimits.MAX_OUTPUT_CHARS, "规则输出超过大小限制")
        return text
    }

    fun text(text: String): CharSequence = TimedText(text, this)
}

/** Java 正则在回溯中也会读取字符，使灾难性回溯能及时结束。 */
private class TimedText(private val value: String, private val budget: RuleBudget) : CharSequence {
    override val length: Int get() = value.length
    override fun get(index: Int): Char {
        budget.check()
        return value[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        TimedText(value.substring(startIndex, endIndex), budget)
    override fun toString(): String = value
}

internal fun requireRule(condition: Boolean, message: String) {
    if (!condition) throw RuleEvaluationException(message)
}
