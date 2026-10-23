package com.mirad.reader.ui.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.webkit.WebView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mirad.reader.reader.ReaderWebView
import com.mirad.reader.ui.common.GlassBackdropState
import com.mirad.reader.ui.common.LocalGlassAppearance
import com.mirad.reader.ui.common.LocalGlassBackdrop
import com.mirad.reader.ui.common.LocalGlassLegacy
import com.mirad.reader.ui.common.glassSnapshotDimensions

/** Samples paint-invalidated visible content without changing WebView layout or layer type. */
@Composable
internal fun WebViewGlassBackdrop(view: ReaderWebView?, visible: Boolean, revision: Any?) {
    val source = LocalGlassBackdrop.current
    val legacy = LocalGlassLegacy.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var attached by remember(view) { mutableStateOf(view?.isAttachedToWindow == true) }
    val binding = remember(view, source) {
        if (view != null && source != null) WebViewBackdropBinding(view, source) else null
    }
    DisposableEffect(lifecycle, binding) {
        val observer = LifecycleEventObserver { _, _ ->
            binding?.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        lifecycle.addObserver(observer)
        binding?.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(view, binding) {
        val paintListener: () -> Unit = { binding?.invalidate() }
        val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> binding?.invalidate() }
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { attached = true }
            override fun onViewDetachedFromWindow(v: View) {
                attached = false
                binding?.stopImmediately()
            }
        }
        view?.onVisualInvalidated = paintListener
        view?.addOnLayoutChangeListener(layoutListener)
        view?.addOnAttachStateChangeListener(attachListener)
        onDispose {
            if (view?.onVisualInvalidated === paintListener) view.onVisualInvalidated = null
            view?.removeOnLayoutChangeListener(layoutListener)
            view?.removeOnAttachStateChangeListener(attachListener)
            binding?.dispose()
        }
    }
    val appearance = LocalGlassAppearance.current
    val needsGlass = appearance.transparencyPercent > 0
    val capable = !legacy && source?.enabled == true && view?.isHardwareAccelerated == true && needsGlass
    SideEffect {
        if (!needsGlass || !capable) {
            binding?.stopImmediately()
            if (!needsGlass) {
                source?.clearAndStop("webview_transparency_zero")
            }
        } else {
            binding?.setEnabled(
                enabled = visible && capable && attached,
                retainExitFrame = !visible && capable && attached
            )
        }
    }
    LaunchedEffect(binding, revision, needsGlass, capable) {
        if (needsGlass && capable) {
            binding?.invalidate()
        }
    }
}

private data class CapturedBackdrop(val bitmap: Bitmap, val size: IntSize, val origin: Offset)

private class WebViewBackdropBinding(
    private val view: ReaderWebView,
    private val source: GlassBackdropState
) {
    private var enabled = false
    private var foreground = false
    private var requestedEnabled = false
    private var requestedRetention = false
    private var retaining = false
    private var disposed = false
    private var ready = false
    private var awaitingVisualState = false
    private var generation = 0L
    private var published: Bitmap? = null
    private val release = Runnable { clear() }
    private val controller = BackdropCaptureController<CapturedBackdrop>(
        nowNanos = System::nanoTime,
        schedule = { view.postOnAnimation(it) },
        cancel = { view.removeCallbacks(it) },
        capture = ::capture,
        publish = ::publish,
        discard = { it.bitmap.recycle() },
        clear = ::clear
    )

    fun setEnabled(enabled: Boolean, retainExitFrame: Boolean) {
        if (disposed) return
        requestedEnabled = enabled
        requestedRetention = retainExitFrame
        val active = enabled && foreground
        val retain = retainExitFrame && foreground
        if (this.enabled == active && retaining == retain) return
        this.enabled = active
        retaining = retain
        generation++
        ready = false
        awaitingVisualState = false
        view.removeCallbacks(release)
        if (active) {
            requestVisualState()
        } else {
            controller.stop(clearFrame = !retain)
            if (retain) view.postDelayed(release, 180)
        }
    }

    /** Resume directly: Compose can coalesce stopped/started state while its frame clock is paused. */
    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) stopImmediately() else setEnabled(requestedEnabled, requestedRetention)
    }

    fun invalidate() {
        if (!enabled || disposed) return
        if (ready) controller.invalidate() else requestVisualState()
    }

    private fun requestVisualState() {
        if (awaitingVisualState || view.width <= 0 || view.height <= 0 || !view.isAttachedToWindow) return
        awaitingVisualState = true
        val token = generation
        view.postVisualStateCallback(token, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (disposed || !enabled || generation != requestId) return
                awaitingVisualState = false
                ready = true
                controller.start()
            }
        })
    }

    fun stopImmediately() {
        enabled = false
        retaining = false
        generation++
        ready = false
        awaitingVisualState = false
        view.removeCallbacks(release)
        controller.stop()
    }

    fun dispose() {
        disposed = true
        stopImmediately()
    }

    private fun capture(): CapturedBackdrop? {
        if (!view.isAttachedToWindow || !view.isShown || view.windowVisibility != View.VISIBLE) return null
        val dimensions = glassSnapshotDimensions(view.width, view.height) ?: return null
        val started = System.nanoTime()
        source.captureAttempts++
        var bitmap: Bitmap? = null
        return try {
            val frame = createBitmap(dimensions.first, dimensions.second, Bitmap.Config.ARGB_8888)
            bitmap = frame
            val canvas = Canvas(frame)
            canvas.scale(frame.width.toFloat() / view.width, frame.height.toFloat() / view.height)
            view.drawVisibleBackdrop(canvas)
            if (!hasNonTransparentPixels(frame)) {
                frame.recycle()
                source.captureFailures++
                null
            } else {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                CapturedBackdrop(frame, IntSize(view.width, view.height), Offset(location[0].toFloat(), location[1].toFloat()))
            }
        } catch (_: RuntimeException) {
            bitmap?.recycle()
            source.captureFailures++
            null
        } catch (_: OutOfMemoryError) {
            bitmap?.recycle()
            clear()
            source.captureFailures++
            null
        } finally {
            source.captureTimeNanos += System.nanoTime() - started
        }
    }

    private fun publish(frame: CapturedBackdrop): Boolean {
        val previous = published
        if (previous != null && previous.width == frame.bitmap.width && previous.height == frame.bitmap.height && previous.sameAs(frame.bitmap)) {
            frame.bitmap.recycle()
            source.setImage(source.image, frame.size, frame.origin)
            return false
        }
        // Published bitmaps stay immutable while RenderThread may still reference them.
        published = frame.bitmap
        source.publishedCaptures++
        source.setImage(frame.bitmap.asImageBitmap(), frame.size, frame.origin)
        return true
    }

    private fun clear() {
        published = null
        source.setImage(null)
    }
}

/** Transparency detects missing draws, not whether a legitimate page contains text. */
private fun hasNonTransparentPixels(bitmap: Bitmap): Boolean {
    val stepX = maxOf(1, bitmap.width / 12)
    val stepY = maxOf(1, bitmap.height / 12)
    var visible = 0
    for (y in 0 until bitmap.height step stepY) {
        for (x in 0 until bitmap.width step stepX) {
            if (android.graphics.Color.alpha(bitmap[x, y]) > 8) visible++
        }
    }
    return visible >= 6
}
