package com.mirad.reader.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RenderNode
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import com.mirad.reader.reader.ReaderWebView
import com.mirad.reader.reader.ReaderKeys
import com.mirad.reader.ui.common.HardwareGlassBackdrop
import com.mirad.reader.ui.common.LocalGlassBackdrop
import com.mirad.reader.ui.common.LocalHardwareGlassBackdrop
import com.mirad.reader.ui.common.rememberHardwareGlassBackdrop
import com.mirad.reader.ui.common.captureHardwareNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The session owns the WebView; this controller owns only its current native presentation. */
internal class ReaderHardwareSceneController {
    internal var presentation: ReaderNativePresentation? = null
    var hardwarePageSnapshots = 0L
        private set
    var failedPageSnapshots = 0L
        private set

    fun setModalVisible(visible: Boolean) {
        presentation?.setModalVisible(visible)
    }

    fun statsSummary(): String = presentation?.let {
        val hardwareStats = if (Build.VERSION.SDK_INT >= 31) {
            val hardware = it.host as? ReaderBackdropHost
            "hardware=${hardware?.lastHardwareCanvas == true} records=${hardware?.contentRecords ?: 0} " +
                (it.source?.statsSummary() ?: "fallback=true")
        } else "hardware=false records=0 fallback=true"
        "$hardwareStats " +
            "document=${it.document.width}x${it.document.height} host=${it.host.width}x${it.host.height} " +
            "overlay=${it.overlayView.width}x${it.overlayView.height} " +
            "documentA11y=${it.document.importantForAccessibility} webA11y=${it.document.webView.importantForAccessibility} " +
            "pageGpuSnapshots=$hardwarePageSnapshots pageFailures=$failedPageSnapshots"
    } ?: "detached=true"

    /** Immutable GPU buffer for COVER; it is never copied to or scanned on the CPU. */
    suspend fun capturePage(): Bitmap? {
        if (Build.VERSION.SDK_INT < 29) return null
        val current = presentation ?: return null
        val node = runCatching { current.document.recordPage() }.getOrNull() ?: return null
        var captured: Bitmap? = null
        try {
            withContext(Dispatchers.Default) { captured = captureHardwareNode(node) }
            if (presentation !== current) {
                captured?.recycle()
                return null
            }
            if (captured != null) hardwarePageSnapshots++ else failedPageSnapshots++
            return captured
        } catch (error: kotlinx.coroutines.CancellationException) {
            captured?.recycle()
            throw error
        } finally {
            node.discardDisplayList()
        }
    }
}

@Composable
internal fun ReaderHardwareScene(
    controller: ReaderHardwareSceneController,
    backgroundColor: Int,
    revision: Any?,
    webViewFactory: (Context) -> ReaderWebView,
    modifier: Modifier = Modifier,
    onWebViewReleased: (ReaderWebView) -> Unit = {},
    content: @Composable () -> Unit,
    overlays: @Composable () -> Unit
) {
    val source = rememberHardwareGlassBackdrop(revision)
    val currentContent = rememberUpdatedState(content)
    val currentOverlays = rememberUpdatedState(overlays)
    val release = rememberUpdatedState(onWebViewReleased)
    // No bitmap bridge remains in this scene. Unsupported hardware uses the themed solid material.
    CompositionLocalProvider(LocalGlassBackdrop provides null, LocalHardwareGlassBackdrop provides source) {
        val parent = rememberCompositionContext()
        AndroidView(
            modifier = modifier,
            factory = { context ->
                val document = ReaderDocumentPlane(context, webViewFactory(context))
                val contentView = ComposeView(context).apply {
                    id = View.generateViewId()
                    consumeWindowInsets = false
                    setParentCompositionContext(parent)
                    setContent { currentContent.value() }
                }
                val overlayView = ComposeView(context).apply {
                    id = View.generateViewId()
                    consumeWindowInsets = false
                    setParentCompositionContext(parent)
                    setContent { currentOverlays.value() }
                }
                document.addView(contentView, FrameLayout.LayoutParams(-1, -1))
                val host = if (Build.VERSION.SDK_INT >= 31) ReaderBackdropHost(context) else ReaderPlaneHost(context)
                host.setPlanes(document, overlayView)
                val presentation = ReaderNativePresentation(host, document, contentView, overlayView, source)
                controller.presentation = presentation
                if (Build.VERSION.SDK_INT >= 31 && host is ReaderBackdropHost && source != null) {
                    host.onContentRecorded = { source.contentChanged("record") }
                    presentation.observeDocument { source.contentChanged("web") }
                    source.attach(host, host.contentLayer)
                }
                host
            },
            update = { host ->
                val presentation = controller.presentation ?: return@AndroidView
                presentation.document.backdropColor = backgroundColor
                if (Build.VERSION.SDK_INT >= 31 && host is ReaderBackdropHost) {
                    host.backdropColor = backgroundColor
                    host.gpuEnabled = source?.renderingActive == true
                }
            },
            onRelease = { host ->
                val presentation = controller.presentation?.takeIf { it.host === host }
                if (presentation != null) {
                    presentation.setModalVisible(false)
                    if (Build.VERSION.SDK_INT >= 31) presentation.source?.detach(host)
                    host.onContentRecorded = null
                    presentation.releaseDocumentObserver()
                    presentation.contentView.disposeComposition()
                    presentation.overlayView.disposeComposition()
                    presentation.document.removeAllViews()
                    host.removeAllViews()
                    controller.presentation = null
                    release.value(presentation.document.webView)
                }
            }
        )
    }
}

internal class ReaderNativePresentation(
    val host: ReaderPlaneHost,
    val document: ReaderDocumentPlane,
    val contentView: ComposeView,
    val overlayView: ComposeView,
    val source: HardwareGlassBackdrop?
) {
    private var modal = false
    private var originalFocusability = document.descendantFocusability
    private var originalAccessibility = document.importantForAccessibility
    private var originalWebAccessibility = document.webView.importantForAccessibility
    private var originalContentAccessibility = contentView.importantForAccessibility
    private var previousNativeFocus: View? = null
    private var previousContentObserver: (() -> Unit)? = null
    private var contentObserver: (() -> Unit)? = null

    fun observeDocument(onChanged: () -> Unit) {
        previousContentObserver = document.webView.onBackdropContentChanged
        contentObserver = { previousContentObserver?.invoke(); onChanged() }
        document.webView.onBackdropContentChanged = contentObserver
    }

    fun releaseDocumentObserver() {
        if (document.webView.onBackdropContentChanged === contentObserver) {
            document.webView.onBackdropContentChanged = previousContentObserver
        }
        contentObserver = null
        previousContentObserver = null
    }

    fun setModalVisible(visible: Boolean) {
        if (modal == visible) return
        modal = visible
        ReaderKeys.blockedByOverlay = visible
        if (visible) {
            originalFocusability = document.descendantFocusability
            originalAccessibility = document.importantForAccessibility
            originalWebAccessibility = document.webView.importantForAccessibility
            originalContentAccessibility = contentView.importantForAccessibility
            previousNativeFocus = document.findFocus()
            document.clearFocus()
            document.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            document.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            // Embedded Compose/WebView accessibility providers can be traversed directly by the
            // outer AndroidView bridge, so exclude each provider as well as its native parent.
            document.webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            contentView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else {
            document.descendantFocusability = originalFocusability
            document.importantForAccessibility = originalAccessibility
            document.webView.importantForAccessibility = originalWebAccessibility
            contentView.importantForAccessibility = originalContentAccessibility
            previousNativeFocus?.takeIf { it.isAttachedToWindow && it.isFocusable }?.requestFocus()
            previousNativeFocus = null
        }
    }
}

@android.annotation.SuppressLint("ViewConstructor") // Programmatic child; its WebView is owned by the reader session.
internal class ReaderDocumentPlane(context: Context, val webView: ReaderWebView) : FrameLayout(context) {
    var backdropColor: Int = android.graphics.Color.WHITE
        set(value) {
            if (field != value) { field = value; setBackgroundColor(value) }
        }

    init { addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }

    @RequiresApi(29)
    fun recordPage(): RenderNode? {
        if (!isHardwareAccelerated || webView.width <= 0 || webView.height <= 0) return null
        return RenderNode("MiradCoverPage").also { node ->
            node.setPosition(0, 0, webView.width, webView.height)
            val canvas = node.beginRecording(webView.width, webView.height)
            try {
                canvas.drawColor(backdropColor)
                drawChild(canvas, webView, drawingTime)
            } finally { node.endRecording() }
        }
    }
}

