package com.mirad.reader.reader

import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONTokener
import kotlin.coroutines.resume

/** 所有脚本仍由会话当前的 WebView 执行，共用可取消回调与缺失视图的处理。 */
internal class ReaderScript(private val webView: () -> ReaderWebView?) {
    fun evaluate(script: String) {
        // 加载期间桥接对象尚未注入；window.* 脚本继续保持原调用语义。
        val guarded = if (script.startsWith("MiradReader.")) "if (window.MiradReader) { $script }" else script
        webView()?.evaluateJavascript(guarded, null)
    }

    suspend fun boolean(script: String): Boolean? = result(script) { it == "true" }
    suspend fun raw(script: String): String? = result(script) { it }
    suspend fun text(): String? = result("MiradReader.getText()") {
        runCatching { JSONTokener(it).nextValue() as? String }.getOrNull()
    }

    private suspend fun <T> result(script: String, decode: (String?) -> T): T? = suspendCancellableCoroutine { continuation ->
        val view = webView()
        if (view == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        runCatching {
            view.evaluateJavascript(script) { value ->
                val decoded = decode(value)
                if (continuation.isActive) continuation.resume(decoded)
            }
        }.onFailure {
            if (continuation.isActive) continuation.resume(null)
        }
    }
}
