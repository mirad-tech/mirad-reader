package com.mirad.reader.ui.reader

import android.graphics.Bitmap
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import com.mirad.reader.core.model.PageAnim
import com.mirad.reader.core.model.PageMode
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.reader.ReaderSession
import kotlinx.coroutines.launch
import androidx.compose.runtime.State
import kotlinx.coroutines.CoroutineScope
import com.mirad.reader.reader.ReaderSessionState

internal class ReaderPageTurn(
    val overlay: androidx.compose.ui.graphics.ImageBitmap?,
    val overlayX: () -> Float,
    val turnFromTap: (Int) -> Unit,
    val clearOverlay: () -> Unit
)

/** 手动与自动翻页共用捕获、动画和释放链。 */
@Composable
internal fun rememberReaderPageTurn(
    session: ReaderSession,
    config: ReaderConfig,
    stateHolder: State<ReaderSessionState>,
    scope: CoroutineScope,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner?,
    hardwareScene: ReaderHardwareSceneController,
    isSessionActive: () -> Boolean,
    onNotice: (String) -> Unit
): ReaderPageTurn {
    val state by stateHolder
    // COVER 翻页动画的旧页位图
    var overlay by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var overlayX by remember { mutableFloatStateOf(0f) }
    var animating by remember { mutableStateOf(false) }
    var manualTurnJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var captureRunning by remember { mutableStateOf(false) }
    var coverAnimator by remember { mutableStateOf<android.animation.ValueAnimator?>(null) }
    var coverBitmap by remember { mutableStateOf<Bitmap?>(null) }

    fun clearOverlay() {
        manualTurnJob?.cancel()
        manualTurnJob = null
        coverAnimator?.let { animator ->
            animator.removeAllListeners()
            animator.removeAllUpdateListeners()
            animator.cancel()
        }
        coverAnimator = null
        val released = coverBitmap
        coverBitmap = null
        overlay = null
        overlayX = 0f
        animating = false
        // Let Compose remove the old Image node before releasing its GPU buffer.
        val owner = session.webView
        if (owner != null && released != null) owner.postOnAnimation {
            owner.postOnAnimation { if (!released.isRecycled) released.recycle() }
        } else if (released != null && !released.isRecycled) released.recycle()
    }
    DisposableEffect(session) { onDispose { clearOverlay() } }
    DisposableEffect(lifecycleOwner, session) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) clearOverlay()
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    suspend fun performTurn(delta: Int, auto: Boolean = false): Boolean {
        if (captureRunning) return false
        // 上下滚动模式：点击左右两侧按屏平滑滚动，不整页跳（翻页动画也不适用）
        if (config.pageMode == PageMode.SCROLL) {
            session.scrollStep(delta)
            return false
        }
        val view = session.webView
        val useOverlay = config.pageAnim == PageAnim.COVER && view != null && state.ready && !animating
        if (!useOverlay) {
            val switched = session.turnPage(delta, pauseAuto = !auto)
            if (!auto && !switched && session.state.value.ready) {
                val s = session.state.value
                if ((delta > 0 && s.page >= s.pages - 1 && s.chapterIndex >= s.chapterCount - 1) ||
                    (delta < 0 && s.page <= 0 && s.chapterIndex <= 0)
                ) {
                    onNotice(if (delta > 0) "已经是最后一章" else "已经是第一章")
                }
            }
            return switched
        }
        val pageView = view ?: return false
        var switched = false
        fun showCapturedPage(bitmap: Bitmap?) {
            if (bitmap == null) {
                switched = session.turnPage(delta, pauseAuto = !auto)
                return
            }
            coverBitmap = bitmap
            overlay = bitmap.asImageBitmap()
            overlayX = 0f
            animating = true
            switched = session.turnPage(delta, pauseAuto = !auto)
            val distance = if (delta > 0) -pageView.width.toFloat() else pageView.width.toFloat()
            coverAnimator = android.animation.ValueAnimator.ofFloat(0f, distance).apply {
                duration = 260
                addUpdateListener { overlayX = it.animatedValue as Float }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        clearOverlay()
                    }

                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        clearOverlay()
                    }
                })
                start()
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            captureRunning = true
            try {
                val bitmap = hardwareScene.capturePage()
                if (!isSessionActive()) bitmap?.recycle() else showCapturedPage(bitmap)
            } finally { captureRunning = false }
        } else {
            captureView(pageView, ::showCapturedPage)
        }
        return switched
    }
    fun turnFromTap(delta: Int) {
        if (captureRunning || manualTurnJob?.isActive == true) return
        manualTurnJob = scope.launch { performTurn(delta) }
    }

    // 自动翻页复用界面层的 performTurn（含覆盖翻页动画）；每次重组更新为最新配置。
    SideEffect {
        session.autoPageTurn = { performTurn(1, auto = true) }
    }
    DisposableEffect(session) {
        onDispose { session.autoPageTurn = null }
    }

    // 在 graphicsLayer 中读取位移，沿用原来的绘制阶段订阅，不让每帧动画重组整页。
    return ReaderPageTurn(overlay, { overlayX }, ::turnFromTap, ::clearOverlay)
}

/** 用软件层捕获当前页位图，用于覆盖翻页动画；失败时返回 null（自动退化为普通翻页）。 */
private fun captureView(view: View, onResult: (Bitmap?) -> Unit) {
    if (view.width <= 0 || view.height <= 0) {
        onResult(null)
        return
    }
    val bitmap = runCatching {
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    }.getOrNull() ?: return onResult(null)
    val success = runCatching {
        val canvas = android.graphics.Canvas(bitmap)
        view.draw(canvas)
        hasVisibleContent(bitmap)
    }.getOrDefault(false)
    if (success) {
        onResult(bitmap)
    } else {
        bitmap.recycle()
        onResult(null)
    }
}

/** 抽样检查位图是否有实际内容，避免把"空白快照"当成有效动画帧。 */
private fun hasVisibleContent(bitmap: Bitmap): Boolean {
    val stepX = (bitmap.width / 12).coerceAtLeast(1)
    val stepY = (bitmap.height / 12).coerceAtLeast(1)
    var visible = 0
    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (android.graphics.Color.alpha(pixel) > 8) visible++
            x += stepX
        }
        y += stepY
    }
    return visible >= 6
}
