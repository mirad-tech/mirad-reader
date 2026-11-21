package com.mirad.reader.ui.common

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

internal enum class GlassScrollEdge { Top, Bottom }

/**
 * 顶部/底部滚动渐进模糊层（参考 Liquid-Glass-Android ScrollEdgeBlurView）：
 * - API 31+ 直接复用 [LocalHardwareGlassBackdrop] 已录制的 `contentLayer`，
 *   以弱模糊宽带（0.35×）+ 强模糊窄带（1.0×）经 `RenderEffect.createBlendModeEffect(..., BlendMode.DST_IN)` 硬件遮罩叠加，
 *   实现内容滚入悬浮顶/底栏间隙时从清晰平滑过渡到模糊；
 * - 顶边仅向下扩展 margin、底边仅向上扩展 margin，利用 `TileMode.CLAMP` 彻底杜绝贴屏侧透明黑混入；
 * - 零额外父视图重录、零 `saveLayer` FBO 分配、零触摸拦截；`height <= 0.dp`、清晰度 100% 或关闭玻璃时不分配 `RenderNode`。
 */
@Composable
internal fun GlassScrollEdgeBlur(
    edge: GlassScrollEdge,
    height: Dp,
    modifier: Modifier = Modifier
) {
    val appearance = LocalGlassAppearance.current
    val legacy = LocalGlassLegacy.current
    val clarity = appearance.clarityPercent
    val maxBlurDp = remember(clarity) {
        (liquidBlurDp(GlassStyle.Toolbar, clarity) * 1.1f).coerceIn(0f, 26f)
    }
    if (!appearance.enabled || legacy || height <= 0.dp || maxBlurDp <= 0.5f || Build.VERSION.SDK_INT < 31) {
        Spacer(modifier = modifier.fillMaxWidth().height(0.dp))
        return
    }

    val hardware = LocalHardwareGlassBackdrop.current
    if (hardware == null) {
        Spacer(modifier = modifier.fillMaxWidth().height(0.dp))
        return
    }

    val surface = MaterialTheme.colorScheme.surface
    val isDark = surface.luminance() < 0.5f
    val edgeTintAlpha = remember(clarity, isDark) {
        (liquidBarOpacity(clarity, isDark) * 0.38f).coerceIn(0f, 0.22f)
    }

    val renderer = remember { ScrollEdgeBlurRenderer() }
    DisposableEffect(hardware, renderer) {
        hardware.addConsumer(renderer)
        onDispose {
            hardware.removeConsumer(renderer)
            renderer.release()
        }
    }

    val hardwareReady = hardware.renderingActive && hardware.contentReady
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var positionSignal by remember { mutableStateOf(Offset.Unspecified) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .onGloballyPositioned {
                coordinates = it
                positionSignal = it.positionInWindow()
            }
            .drawBehind {
                val w = size.width
                val h = size.height
                if (!hardwareReady || w <= 1f || h <= 1f) return@drawBehind

                var drewHardware = false
                val node = hardware.node
                val host = hardware.host
                val positioned = positionSignal != Offset.Unspecified && coordinates?.isAttached == true
                val pos = coordinates?.takeIf { positioned }?.positionInWindow()
                if (hardware.renderingActive &&
                    hardware.contentReady &&
                    node?.hasDisplayList() == true &&
                    host != null &&
                    pos != null
                ) {
                    drawIntoCanvas { canvas ->
                        if (canvas.nativeCanvas.isHardwareAccelerated) {
                            host.getLocationInWindow(renderer.hostLocation)
                            drewHardware = renderer.draw(
                                canvas = canvas.nativeCanvas,
                                content = node,
                                x = pos.x - renderer.hostLocation[0],
                                y = pos.y - renderer.hostLocation[1],
                                width = w,
                                height = h,
                                maxBlurPx = maxBlurDp * density,
                                topEdge = edge == GlassScrollEdge.Top
                            )
                        }
                    }
                }
                if (!drewHardware) return@drawBehind

                val tintColor = surface.copy(alpha = edgeTintAlpha)
                val startY = if (edge == GlassScrollEdge.Top) 0f else h
                val endY = if (edge == GlassScrollEdge.Top) h else 0f
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to tintColor,
                        0.65f to tintColor.copy(alpha = tintColor.alpha * 0.35f),
                        1f to Color.Transparent,
                        startY = startY,
                        endY = endY
                    )
                )
            }
    )
}

@RequiresApi(31)
private class ScrollEdgeBlurRenderer {
    val hostLocation = IntArray(2)
    private val weakNode = RenderNode("MiradScrollEdgeWeak")
    private val strongNode = RenderNode("MiradScrollEdgeStrong")

    private var previousContent: RenderNode? = null
    private var previousX = Float.NaN
    private var previousY = Float.NaN
    private var previousWidth = -1
    private var previousHeight = -1
    private var previousMargin = -1
    private var previousTopEdge: Boolean? = null

    private var lastEffectBlurPx = -1f
    private var lastEffectHeight = -1
    private var lastEffectMargin = -1
    private var lastEffectTopEdge: Boolean? = null

    fun draw(
        canvas: Canvas,
        content: RenderNode,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        maxBlurPx: Float,
        topEdge: Boolean
    ): Boolean {
        val w = ceil(width).toInt()
        val h = ceil(height).toInt()
        if (w <= 0 || h <= 0 || maxBlurPx <= 0.5f) return false

        val margin = ceil(maxBlurPx * 2f).toInt().coerceAtLeast(8)
        val quantizedBlur = ((maxBlurPx * 2f).toInt() / 2f).coerceAtLeast(1f)

        val hadDisplayLists = weakNode.hasDisplayList() && strongNode.hasDisplayList()
        val needRecord = !hadDisplayLists ||
            previousContent !== content ||
            previousX != x ||
            previousY != y ||
            previousWidth != w ||
            previousHeight != h ||
            previousMargin != margin ||
            previousTopEdge != topEdge

        if (needRecord) {
            recordProxy(weakNode, content, x, y, w, h, margin, topEdge)
            recordProxy(strongNode, content, x, y, w, h, margin, topEdge)
            previousContent = content
            previousX = x
            previousY = y
            previousWidth = w
            previousHeight = h
            previousMargin = margin
            previousTopEdge = topEdge
        }

        val needEffectUpdate = needRecord ||
            quantizedBlur != lastEffectBlurPx ||
            h != lastEffectHeight ||
            margin != lastEffectMargin ||
            topEdge != lastEffectTopEdge

        if (needEffectUpdate) {
            updateRenderEffects(quantizedBlur, h, margin, topEdge)
            lastEffectBlurPx = quantizedBlur
            lastEffectHeight = h
            lastEffectMargin = margin
            lastEffectTopEdge = topEdge
        }

        val save = canvas.save()
        try {
            canvas.clipRect(0f, 0f, w.toFloat(), h.toFloat())
            canvas.drawRenderNode(weakNode)
            canvas.drawRenderNode(strongNode)
        } finally {
            canvas.restoreToCount(save)
        }
        return true
    }

    private fun recordProxy(
        proxy: RenderNode,
        content: RenderNode,
        x: Float,
        y: Float,
        w: Int,
        h: Int,
        margin: Int,
        topEdge: Boolean
    ) {
        val totalH = h + margin
        if (topEdge) {
            // 顶边贴屏侧不扩展空边，仅向下扩展 margin，利用 CLAMP 采样避免透明黑混入顶边
            proxy.setPosition(0, 0, w, totalH)
            val rc = proxy.beginRecording(w, totalH)
            try {
                rc.translate(-x, -y)
                rc.drawRenderNode(content)
            } finally {
                proxy.endRecording()
            }
        } else {
            // 底边贴屏侧不扩展空边，仅向上扩展 margin，利用 CLAMP 采样避免透明黑混入底边
            proxy.setPosition(0, -margin, w, h)
            val rc = proxy.beginRecording(w, totalH)
            try {
                rc.translate(-x, margin - y)
                rc.drawRenderNode(content)
            } finally {
                proxy.endRecording()
            }
        }
    }

    private fun updateRenderEffects(quantizedBlur: Float, h: Int, margin: Int, top: Boolean) {
        val weakBlurPx = (quantizedBlur * 0.35f).coerceAtLeast(0.5f)
        val weakBlurEffect = RenderEffect.createBlurEffect(weakBlurPx, weakBlurPx, Shader.TileMode.CLAMP)
        val strongBlurEffect = RenderEffect.createBlurEffect(quantizedBlur, quantizedBlur, Shader.TileMode.CLAMP)

        // 在 proxy 局部坐标系中：topEdge 可见区为 [0, h]，bottomEdge 可见区为 [margin, margin + h]
        val visibleStart = if (top) 0f else (margin + h).toFloat()
        val visibleWideEnd = if (top) h.toFloat() else margin.toFloat()
        val visibleNarrowEnd = if (top) h * 0.55f else margin + h * 0.45f

        val wideGradient = LinearGradient(
            0f,
            visibleStart,
            0f,
            visibleWideEnd,
            intArrayOf(
                android.graphics.Color.WHITE,
                android.graphics.Color.WHITE,
                android.graphics.Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.25f, 1f),
            Shader.TileMode.CLAMP
        )
        val narrowGradient = LinearGradient(
            0f,
            visibleStart,
            0f,
            visibleNarrowEnd,
            intArrayOf(
                android.graphics.Color.WHITE,
                android.graphics.Color.TRANSPARENT
            ),
            null,
            Shader.TileMode.CLAMP
        )

        weakNode.setRenderEffect(
            RenderEffect.createBlendModeEffect(
                weakBlurEffect,
                RenderEffect.createShaderEffect(wideGradient),
                BlendMode.DST_IN
            )
        )
        strongNode.setRenderEffect(
            RenderEffect.createBlendModeEffect(
                strongBlurEffect,
                RenderEffect.createShaderEffect(narrowGradient),
                BlendMode.DST_IN
            )
        )
    }

    fun release() {
        weakNode.setRenderEffect(null)
        strongNode.setRenderEffect(null)
        weakNode.discardDisplayList()
        strongNode.discardDisplayList()
        previousContent = null
        previousX = Float.NaN
        previousY = Float.NaN
        previousWidth = -1
        previousHeight = -1
        previousMargin = -1
        previousTopEdge = null
        lastEffectBlurPx = -1f
        lastEffectHeight = -1
        lastEffectMargin = -1
        lastEffectTopEdge = null
    }
}
