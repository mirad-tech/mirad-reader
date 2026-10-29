package com.mirad.reader.ui.common

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isEmpty

/** 正文保持未被键盘压缩的坐标系，控制层只应用一次输入法边距。 */
internal class GlassViewportHeight {
    private var width = 0
    private var unobscuredHeight = 0

    fun measure(width: Int, height: Int, imeVisible: Boolean): Int {
        if (this.width != width) {
            this.width = width
            unobscuredHeight = height
        } else if (!imeVisible || unobscuredHeight == 0) {
            unobscuredHeight = height
        }
        return if (imeVisible) maxOf(height, unobscuredHeight) else height
    }
}

/** 两个原生兄弟平面：正文采样不包含使用该采样的玻璃控件。 */
internal open class GlassPlaneView(context: Context) : FrameLayout(context) {
    protected var contentPlane: View? = null
        private set
    protected var overlayPlane: View? = null
        private set
    var onContentRecorded: (() -> Unit)? = null
    private val viewportHeight = GlassViewportHeight()
    private var imeVisible = false
    private var contentBlocked = false
    private var previousFocus: View? = null
    private var lastContentFocus: View? = null
    private var previousFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    private var previousAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    // 菜单接管原生焦点早于模态可见回调，先保留正文最近的真实焦点。
    private val focusObserver = ViewTreeObserver.OnGlobalFocusChangeListener { _, focused ->
        if (focused != null && contentPlane?.findFocus() === focused) lastContentFocus = focused
    }

    init {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeVisible != visible) {
                imeVisible = visible
                requestLayout()
            }
            // Both Compose siblings need the original safe-area and keyboard information.
            insets
        }
    }

    fun setPlanes(content: View, overlay: View) {
        check(isEmpty()) { "Glass planes can only be attached once" }
        contentPlane = content
        overlayPlane = overlay
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** 普通页面的模态隔离；阅读会话另行管理 WebView、音量键及其原生焦点。 */
    fun setContentBlocked(blocked: Boolean) {
        if (contentBlocked == blocked) return
        val content = contentPlane as? ViewGroup ?: return
        contentBlocked = blocked
        if (blocked) {
            previousFocus = content.findFocus() ?: lastContentFocus
            previousFocusability = content.descendantFocusability
            previousAccessibility = content.importantForAccessibility
            content.clearFocus()
            content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else {
            content.descendantFocusability = previousFocusability
            content.importantForAccessibility = previousAccessibility
            val target = previousFocus
            // 等控制平面的焦点组退出模态后再恢复，避免同一帧的清焦点覆盖正文恢复。
            post {
                if (!contentBlocked && target?.isAttachedToWindow == true && target.isFocusable) target.requestFocus()
            }
            previousFocus = null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(focusObserver)
        ViewCompat.requestApplyInsets(this)
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnGlobalFocusChangeListener(focusObserver)
        previousFocus = null
        lastContentFocus = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val documentHeight = viewportHeight.measure(width, height, imeVisible)
        val exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        contentPlane?.measure(exactWidth, MeasureSpec.makeMeasureSpec(documentHeight, MeasureSpec.EXACTLY))
        // If adjustResize physically shortens this host, do not also shrink Compose before
        // its imePadding runs. Both planes keep window coordinates; the host clips at the IME.
        overlayPlane?.measure(exactWidth, MeasureSpec.makeMeasureSpec(documentHeight, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        contentPlane?.let { it.layout(0, 0, measuredWidth, it.measuredHeight) }
        overlayPlane?.let { it.layout(0, 0, measuredWidth, it.measuredHeight) }
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        if (child === contentPlane) contentInvalidated()
        super.onDescendantInvalidated(child, target)
    }

    protected open fun contentInvalidated() = Unit
}
