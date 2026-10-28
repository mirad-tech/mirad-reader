package com.mirad.reader.core.sources.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.WrapFactory
import org.mozilla.javascript.lc.type.TypeInfo

/** 每次脚本使用全新 scope；只注入 JS 原生值，绝不注入 Java 包装对象。 */
internal class RuleJavaScript(
    private val budget: RuleBudget,
    private val bindings: Map<String, JsonElement>,
    private val variables: MutableMap<String, String>,
    private val source: String,
    private val baseUrl: String,
) {
    fun evaluate(script: String, input: JsonElement): List<JsonElement> {
        requireRule(script.length <= RuleLimits.MAX_RULE_CHARS, "JavaScript 规则过长")
        SourceRules.scriptReason(script)?.let { throw RuleEvaluationException(it) }
        budget.check()
        val factory = object : ContextFactory() {
            override fun makeContext(): Context = super.makeContext().apply {
                setInterpretedMode(true)
                languageVersion = Context.VERSION_ES6
                instructionObserverThreshold = 2_000
                maximumInterpreterStackDepth = 128
                setClassShutter { false }
                wrapFactory = object : WrapFactory() {
                    override fun wrapAsJavaObject(cx: Context, scope: Scriptable, javaObject: Any, staticType: Class<*>?): Scriptable {
                        throw RuleEvaluationException("规则不能访问 Java 或 Android 对象")
                    }
                    override fun wrapAsJavaObject(cx: Context, scope: Scriptable, javaObject: Any, staticType: TypeInfo): Scriptable {
                        throw RuleEvaluationException("规则不能访问 Java 或 Android 对象")
                    }
                    override fun wrapNewObject(cx: Context, scope: Scriptable, obj: Any): Scriptable {
                        throw RuleEvaluationException("规则不能创建 Java 或 Android 对象")
                    }
                    override fun wrapJavaClass(cx: Context, scope: Scriptable, javaClass: Class<*>): Scriptable {
                        throw RuleEvaluationException("规则不能访问 Java 类")
                    }
                }.apply { isJavaPrimitiveWrap = false }
            }
            override fun observeInstructionCount(cx: Context, instructionCount: Int) = budget.check()
        }
        return factory.call { cx ->
            val scope = cx.initSafeStandardObjects()
            val reserved = setOf("java", "result", "src", "content", "baseUrl", "variables", "get", "put")
            bindings.forEach { (name, value) ->
                if (name !in reserved) ScriptableObject.putProperty(scope, name, nativeJson(cx, scope, value))
            }
            ScriptableObject.putProperty(scope, "result", nativeJson(cx, scope, input))
            ScriptableObject.putProperty(scope, "src", source)
            ScriptableObject.putProperty(scope, "content", source)
            ScriptableObject.putProperty(scope, "baseUrl", baseUrl)
            val variableObject = cx.newObject(scope)
            variables.forEach { (name, value) -> ScriptableObject.putProperty(variableObject, name, value) }
            ScriptableObject.putProperty(scope, "variables", variableObject)
            val helpers = cx.newObject(scope) as ScriptableObject
            fun function(name: String, action: (Array<out Any?>) -> Any?) {
                val fn = object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
                    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                        budget.check()
                        return action(args)
                    }
                }
                helpers.defineProperty(name, fn, ScriptableObject.READONLY or ScriptableObject.PERMANENT)
                if (name == "get" || name == "put") scope.defineProperty(name, fn, ScriptableObject.READONLY or ScriptableObject.PERMANENT)
            }
            function("get") { args -> variables[args.firstOrNull()?.let(Context::toString).orEmpty()].orEmpty() }
            function("put") { args ->
                val name = args.firstOrNull()?.let(Context::toString).orEmpty()
                val value = args.getOrNull(1)?.let(Context::toString).orEmpty()
                requireRule(name.length in 1..128 && value.length <= RuleLimits.MAX_RULE_CHARS, "规则变量名称或内容过长")
                requireRule(name in variables || variables.size < RuleLimits.MAX_VARIABLES, "规则变量数量超过限制")
                variables[name] = value
                ScriptableObject.putProperty(variableObject, name, value)
                value
            }
            function("base64Decode") { args -> String(java.util.Base64.getDecoder().decode(Context.toString(args.firstOrNull())), Charsets.UTF_8) }
            function("base64Encode") { args -> java.util.Base64.getEncoder().encodeToString(Context.toString(args.firstOrNull()).toByteArray(Charsets.UTF_8)) }
            helpers.sealObject()
            scope.defineProperty("java", helpers, ScriptableObject.READONLY or ScriptableObject.PERMANENT)
            val value = cx.evaluateString(scope, script, "source-rule", 1, null)
            budget.check()
            if (value is NativeArray) {
                requireRule(value.length <= RuleLimits.MAX_ROWS, "JavaScript 返回的列表过长")
                (0 until value.length.toInt()).map { jsonValue(cx, scope, value.get(it, value)) }
            } else listOf(jsonValue(cx, scope, value))
        }
    }

    private fun nativeJson(cx: Context, scope: Scriptable, value: JsonElement): Any? =
        cx.evaluateString(scope, "(" + value.toString() + ")", "rule-bindings", 1, null)

    private fun jsonValue(cx: Context, scope: Scriptable, value: Any?): JsonElement {
        budget.check()
        return when {
            value == null || value is Undefined || value === Scriptable.NOT_FOUND -> JsonNull
            value is String || value is CharSequence -> JsonPrimitive(value.toString().also { requireRule(it.length <= RuleLimits.MAX_OUTPUT_CHARS, "脚本输出过长") })
            value is Boolean -> JsonPrimitive(value)
            value is Number -> Context.toString(value).let { number ->
                if (value.toDouble().isFinite()) Json.parseToJsonElement(number) else JsonPrimitive(number)
            }
            value is Scriptable -> {
                val text = Context.toString(NativeJSON.stringify(cx, scope, value, null, null))
                requireRule(text.length <= RuleLimits.MAX_OUTPUT_CHARS, "脚本输出过长")
                if (text == "undefined") JsonNull else Json.parseToJsonElement(text)
            }
            else -> throw RuleEvaluationException("脚本只能返回文本、数字、布尔值和 JSON")
        }
    }
}
