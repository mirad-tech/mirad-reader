package com.mirad.reader.reader

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * reader.js → 原生层的回调桥。JS 桥接线程不是主线程，统一转回主线程。
 *
 * 注意：每个 `@JavascriptInterface` 方法内部必须显式取**函数属性**再调用。
 * 直接写 `onReady(a, b)` 会解析到同名成员函数自身——元数相同时会无限递归自我 post，
 * 真正的回调永远不执行（onReady / onMetrics / onChapterChanged 都曾踩到）。
 */
class ReaderJsBridge(
    private val onReady: (pages: Int, textLength: Int) -> Unit,
    private val onState: (page: Int, pages: Int, offset: Int, textLength: Int) -> Unit,
    private val onSelection: (start: Int, end: Int, text: String) -> Unit,
    /** 版心几何回报：正文列相对屏幕的居中偏移与列宽（CSS px = dp）。 */
    private val onMetrics: (insetLeft: Int, columnWidth: Int) -> Unit = { _, _ -> },
    /** JS 已在同一文档内换到别的章（滚动模式无感接章），原生层据此同步章节状态。 */
    private val onChapterChanged: (index: Int) -> Unit = {},
    /** JS 原地换章失败时，显式请求原生重载目标章（唯一的失败兜底入口）。 */
    private val onRequestNextChapter: (index: Int) -> Unit = {},
    /** 自动下滑到达本章末尾（章节代次随章号校验）。 */
    private val onAutoScrollEnd: (chapterIndex: Int) -> Unit = {},
    /** 合并后的真实正文变化；不把 Android onDraw 当成内容变化。 */
    private val onVisualChanged: () -> Unit = {}
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onVisualChanged() {
        val callback = this.onVisualChanged
        main.post { callback() }
    }

    @JavascriptInterface
    fun onAutoScrollEnd(chapterIndex: Int) {
        val callback = this.onAutoScrollEnd
        main.post { callback(chapterIndex) }
    }

    @JavascriptInterface
    fun onRequestNextChapter(index: Int) {
        // 显式取函数属性，避免调用到本方法自身导致递归
        val callback = this.onRequestNextChapter
        main.post { callback(index) }
    }

    @JavascriptInterface
    fun onChapterChanged(index: Int) {
        val callback = this.onChapterChanged
        main.post { callback(index) }
    }

    @JavascriptInterface
    fun onReady(pages: Int, textLength: Int) {
        val callback = this.onReady
        main.post { callback(pages, textLength) }
    }

    @JavascriptInterface
    fun onState(page: Int, pages: Int, offset: Int, textLength: Int, scrollY: Int) {
        val callback = this.onState
        main.post { callback(page, pages, offset, textLength) }
    }

    @JavascriptInterface
    fun onMetrics(insetLeft: Int, columnWidth: Int) {
        val callback = this.onMetrics
        main.post { callback(insetLeft, columnWidth) }
    }

    @JavascriptInterface
    fun onSelection(start: Int, end: Int, text: String) {
        val callback = this.onSelection
        main.post { callback(start, end, text) }
    }
}
