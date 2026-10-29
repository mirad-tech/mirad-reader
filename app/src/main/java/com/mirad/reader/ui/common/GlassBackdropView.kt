package com.mirad.reader.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.RenderNode
import android.os.Trace
import android.view.View
import androidx.annotation.RequiresApi

/**
 * 只录制内容平面的 GPU 桥接，控制平面独立绘制。
 * 阅读页继续持有自己的 WebView；此容器不接管业务会话。
 * [contentLayer] 不能包含浮层，避免玻璃采到自身。
 */
@RequiresApi(31)
internal class GlassBackdropView(context: Context) : GlassPlaneView(context) {
    val contentLayer = RenderNode("MiradReaderContent")
    var gpuEnabled = true
        set(value) {
            if (field != value) { field = value; contentDirty = true; invalidate() }
        }
    var backdropColor: Int = android.graphics.Color.WHITE
        set(value) {
            if (field != value) { field = value; contentDirty = true; invalidate() }
        }
    var contentRecords = 0L
        private set
    var recordDurationNanos = 0L
        private set
    var lastHardwareCanvas = false
        private set
    private var contentDirty = true

    override fun contentInvalidated() {
        contentDirty = true
        super.contentInvalidated()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val content = contentPlane ?: return
        lastHardwareCanvas = canvas.isHardwareAccelerated
        if (gpuEnabled && canvas.isHardwareAccelerated && content.width > 0 && content.height > 0) {
            if (contentDirty || !contentLayer.hasDisplayList() || contentLayer.width != content.width || contentLayer.height != content.height) {
            val start = System.nanoTime()
            try {
                Trace.beginSection("Mirad.recordReaderContent")
                contentLayer.setPosition(0, 0, content.width, content.height)
                val recording = contentLayer.beginRecording(content.width, content.height)
                try {
                    recording.drawColor(backdropColor)
                    // drawChild supplies the actual child bounds, scroll and display-list transform.
                    drawChild(recording, content, drawingTime)
                } finally { contentLayer.endRecording() }
                contentDirty = false
                contentRecords++
                onContentRecorded?.invoke()
            } finally {
                Trace.endSection()
                recordDurationNanos += System.nanoTime() - start
            }
            }
            canvas.drawRenderNode(contentLayer)
        } else {
            drawChild(canvas, content, drawingTime)
        }
        overlayPlane?.let { drawChild(canvas, it, drawingTime) }
    }

    override fun onDetachedFromWindow() {
        contentLayer.discardDisplayList()
        super.onDetachedFromWindow()
    }
}
