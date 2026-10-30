package com.mirad.reader.ui.common

import android.graphics.Canvas
import kotlin.math.abs
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal val LocalPagerChrome = staticCompositionLocalOf<PagerChrome?> { null }
internal val LocalPagerPage = staticCompositionLocalOf<String?> { null }
internal val LocalPagerPageIndex = staticCompositionLocalOf { 0 }
internal val LocalPagerPageActive = staticCompositionLocalOf { true }
internal val LocalPagerPageVisible = staticCompositionLocalOf { true }

/** 仅登记页内内容节点，根层导航不会录入自己的背景。 */
@Stable
internal class PagerChrome {
    val overlays = mutableStateMapOf<String, GlassOverlayState>()
    private val bodies = mutableMapOf<GlassBackdropView, Int>()
    var progress: () -> Float = { 0f }
    var reversed = false
    var bottomHeight by mutableIntStateOf(-1)
    internal fun add(view: GlassBackdropView, page: Int) { bodies[view] = page }
    internal fun remove(view: View) { if (Build.VERSION.SDK_INT >= 31 && view is GlassBackdropView) bodies.remove(view) }
    internal fun version(root: View): Any {
        if (Build.VERSION.SDK_INT < 31) return 0
        return listOf(progress(), reversed, root.width, root.height,
            bodies.map { (view, page) -> listOf(view, page, view.width, view.height, view.isShown, view.contentLayer.width, view.contentLayer.height, view.contentRecords) })
    }
    internal fun draw(canvas: Canvas, root: View) {
        if (Build.VERSION.SDK_INT < 31) return
        val origin = IntArray(2); root.getLocationInWindow(origin)
        val position = progress()
        val viewportWidth = if (root is GlassBackdropView) root.contentLayer.width else root.width
        if (com.mirad.reader.BuildConfig.DEBUG) android.util.Log.d("PagerComposite", "p=$position rootRecord=${if (root is GlassBackdropView) root.contentRecords else -1} root=${root.width}x${root.height} canvas=${canvas.clipBounds} bodies=" + bodies.map { (v, i) ->
            val at = IntArray(2); v.getLocationInWindow(at)
            "$i:${v.width}x${v.height}/node=${v.contentLayer.width}x${v.contentLayer.height}/shown=${v.isShown}/ready=${v.contentLayer.hasDisplayList()}/at=${at.toList()}/records=${v.contentRecords}"
        })
        bodies.forEach { (view, page) ->
            if (abs(page - position) < 1f && view.contentLayer.hasDisplayList()) {
                val at = IntArray(2); view.getLocationInWindow(at)
                val saved = canvas.save()
                val x = (page - position) * viewportWidth * (if (reversed) -1f else 1f) + (viewportWidth - view.contentLayer.width) / 2f
                canvas.translate(x, (at[1] - origin[1]).toFloat())
                canvas.clipRect(0, 0, view.contentLayer.width, view.contentLayer.height)
                canvas.drawRenderNode(view.contentLayer)
                canvas.restoreToCount(saved)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PagerChromeHost(
    state: PagerChrome,
    activePage: String,
    pageProgress: () -> Float,
    onAvailability: (Boolean) -> Unit,
    navigation: @Composable (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    val empty = remember { GlassOverlayState() }
    val overlays = state.overlays[activePage] ?: empty
    val direction = LocalLayoutDirection.current
    SideEffect { state.progress = pageProgress; state.reversed = direction == LayoutDirection.Rtl }
    val feedback = LocalRootFeedback.current
    CompositionLocalProvider(LocalPagerChrome provides state) {
        GlassScene(overlays.hasEntries, composite = state, content = content, controls = {
            val keyboard = WindowInsets.isImeVisible
            val available = !keyboard && !overlays.hasEntries
            SideEffect { onAvailability(available) }
            overlays.Render(Modifier.fillMaxSize(), {}) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.align(Alignment.BottomCenter).onSizeChangedForChrome { state.bottomHeight = it }) {
                        if (!keyboard) navigation(available)
                    }
                    if (feedback != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .imePadding().padding(bottom = with(androidx.compose.ui.platform.LocalDensity.current) { state.bottomHeight.coerceAtLeast(0).toDp() })) {
                        FeedbackHost(feedback)
                    }
                }
            }
        })
    }
}
