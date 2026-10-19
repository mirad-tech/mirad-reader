package com.mirad.reader.reader

import android.content.Context
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.webkit.WebView

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
