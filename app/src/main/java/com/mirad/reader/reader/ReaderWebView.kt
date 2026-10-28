package com.mirad.reader.reader

import android.content.Context
import android.graphics.Canvas
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.webkit.WebView
import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebViewClient

/** 原生选区工具条上的自定义动作。 */
enum class SelectionAction { HIGHLIGHT, NOTE, SEARCH }

/**
 * 阅读用 WebView：在原生文本选择工具条上追加「划线 / 笔记 / 搜索」，
 * 并把单击（翻页手势）与长按（文本选择）分离。
 */
class ReaderWebView(context: Context) : WebView(context) {

    var onSelectionAction: ((SelectionAction) -> Unit)? = null
    var onTap: ((x: Float, y: Float) -> Unit)? = null

    /** 手指按下：阅读会话据此立即暂停自动阅读（在 WebView 开始拖动之前）。 */
    var onTouchDown: (() -> Unit)? = null

    /** Paint changes are independent of persisted chapter/page/character positions. */
    internal var onVisualInvalidated: (() -> Unit)? = null
    /** Actual document changes for GPU readability analysis; a redraw alone is not a change. */
    internal var onBackdropContentChanged: (() -> Unit)? = null
    private var backdropCaptureDepth = 0

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        notifyVisualInvalidated()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (l != oldl || t != oldt) {
            notifyVisualInvalidated()
            notifyBackdropContentChanged()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            notifyVisualInvalidated()
            notifyBackdropContentChanged()
        }
    }

    internal fun notifyBackdropContentChanged() { onBackdropContentChanged?.invoke() }

    private fun notifyVisualInvalidated() {
        if (backdropCaptureDepth == 0) onVisualInvalidated?.invoke()
    }

    internal fun <T> withoutBackdropInvalidation(block: () -> T): T {
        backdropCaptureDepth++
        return try { block() } finally { backdropCaptureDepth-- }
    }

    /** Match the scroll transform normally supplied by View's parent before drawing. */
    internal fun drawVisibleBackdrop(canvas: Canvas) = withoutBackdropInvalidation {
        val save = canvas.save()
        try {
            canvas.translate(-scrollX.toFloat(), -scrollY.toFloat())
            draw(canvas)
        } finally { canvas.restoreToCount(save) }
    }

    /** 本次手势是否已经进入长按（长按选词不触发翻页）。 */
    private var longPressed = false

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = false

            override fun onLongPress(e: MotionEvent) {
                longPressed = true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // 长按选词结束时不再当成一次正文单击，避免"选完字顺手翻页/关菜单"。
                if (longPressed) return true
                onTap?.invoke(e.x, e.y)
                return true
            }
        }
    )

    init {
        // 返回 false：事件继续交给 WebView，保证长按选词、链接点击等原生行为不受影响。
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                longPressed = false
                onTouchDown?.invoke()
            }
            gestureDetector.onTouchEvent(event)
            false
        }
        isLongClickable = true
        isHapticFeedbackEnabled = true
    }

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
        if (callback == null) return super.startActionMode(callback, type)
        val wrapper = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                val created = callback.onCreateActionMode(mode, menu)
                menu?.add(Menu.NONE, MENU_HIGHLIGHT, 100, "划线")
                menu?.add(Menu.NONE, MENU_NOTE, 101, "笔记")
                menu?.add(Menu.NONE, MENU_SEARCH, 102, "搜索")
                return created
            }

            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean =
                callback.onPrepareActionMode(mode, menu)

            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                when (item?.itemId) {
                    MENU_HIGHLIGHT -> {
                        onSelectionAction?.invoke(SelectionAction.HIGHLIGHT)
                        mode?.finish()
                        return true
                    }
                    MENU_NOTE -> {
                        onSelectionAction?.invoke(SelectionAction.NOTE)
                        mode?.finish()
                        return true
                    }
                    MENU_SEARCH -> {
                        onSelectionAction?.invoke(SelectionAction.SEARCH)
                        mode?.finish()
                        return true
                    }
                }
                return callback.onActionItemClicked(mode, item)
            }

            override fun onDestroyActionMode(mode: ActionMode?) = callback.onDestroyActionMode(mode)
        }
        return super.startActionMode(wrapper, type)
    }

    companion object {
        private const val MENU_HIGHLIGHT = 0x4D01
        private const val MENU_NOTE = 0x4D02
        private const val MENU_SEARCH = 0x4D03
    }
}


/** 统一阅读文档的 WebView 配置、虚拟源隔离与就绪回报。 */
@SuppressLint("SetJavaScriptEnabled")
internal fun ReaderWebView.bindReader(
    resources: ReaderResourceProvider,
    bridge: ReaderJsBridge,
    currentChapterUrl: () -> String,
    onRendered: () -> Unit
) {
    val view = this
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        loadsImagesAutomatically = true
        blockNetworkImage = false
        builtInZoomControls = false
        displayZoomControls = false
        setSupportZoom(false)
        textZoom = 100
        cacheMode = WebSettings.LOAD_NO_CACHE
        mediaPlaybackRequiresUserGesture = true
    }
    view.setBackgroundColor(Color.TRANSPARENT)
    view.isVerticalScrollBarEnabled = false
    view.isHorizontalScrollBarEnabled = false
    view.addJavascriptInterface(bridge, "MiradBridge")
    view.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val uri = request.url
            if (uri.host == ReaderResourceProvider.HOST) {
                return resources.intercept(uri)
            }
            // 阻断一切外部网络请求（EPUB 中的外链图片/脚本也不会联网）
            return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean = true

        override fun onPageFinished(view: WebView, url: String) {
            // about:blank 等中间页面会先触发回调，必须按 URL 过滤；
            // 同时忽略已被新一次 loadUrl 取代的旧章节，避免旧页面的回调把新章标成就绪。
            val expected = currentChapterUrl()
            val normalized = url.substringBefore('#').substringBefore('?').trimEnd('/')
            if (normalized == expected) {
                onRendered()
            }
        }
    }
    view.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
            android.util.Log.i(
                "MiradWebView",
                "${message.message()} @${message.sourceId()}:${message.lineNumber()}"
            )
            return true
        }
    }
}
