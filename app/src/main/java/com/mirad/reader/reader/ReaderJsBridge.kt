package com.mirad.reader.reader

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * reader.js → 原生层的回调桥。JS 桥接线程不是主线程，统一转回主线程。
 */
class ReaderJsBridge(
    private val onReady: (pages: Int, textLength: Int) -> Unit,
    private val onState: (page: Int, pages: Int, offset: Int, textLength: Int) -> Unit,
    private val onSelection: (start: Int, end: Int, text: String) -> Unit,
    /** 版心几何回报：正文列相对屏幕的居中偏移与列宽（CSS px = dp）。 */
    private val onMetrics: (insetLeft: Int, columnWidth: Int) -> Unit = { _, _ -> }
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onReady(pages: Int, textLength: Int) {
        main.post { onReady(pages, textLength) }
    }

    @JavascriptInterface
    fun onState(page: Int, pages: Int, offset: Int, textLength: Int, scrollY: Int) {
        main.post { onState(page, pages, offset, textLength) }
    }

    @JavascriptInterface
    fun onMetrics(insetLeft: Int, columnWidth: Int) {
        main.post { onMetrics(insetLeft, columnWidth) }
    }

    @JavascriptInterface
    fun onSelection(start: Int, end: Int, text: String) {
        main.post { onSelection(start, end, text) }
    }
}
