package com.mirad.reader.ui.reader

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isEmpty

/** Keeps IME occlusion out of the scene's layout; the control layer applies the IME inset once. */
internal class ReaderViewportHeight {
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

/** Two native siblings: document pixels are never recorded together with their glass consumers. */
internal open class ReaderPlaneHost(context: Context) : FrameLayout(context) {
    protected var contentPlane: View? = null
        private set
    protected var overlayPlane: View? = null
        private set
    var onContentRecorded: (() -> Unit)? = null
    private val viewportHeight = ReaderViewportHeight()
    private var imeVisible = false

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
        check(isEmpty()) { "Reader backdrop planes can only be attached once" }
        contentPlane = content
        overlayPlane = overlay
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
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
