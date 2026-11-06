package com.mirad.reader.ui.common

import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlin.math.ceil

/**
 * 背景在独立的硬件层中模糊，按材质角色叠加底色后再绘制前景。
 */
@RequiresApi(31)
internal fun Modifier.hardwareGlassBackground(
    source: HardwareGlassBackdrop, style: GlassStyle, shape: Shape
): Modifier = composed {
    val appearance = LocalGlassAppearance.current
    val percent = appearance.transparencyPercent
    val clarity = appearance.clarityPercent
    val surface = MaterialTheme.colorScheme.surface
    val baseOpacity by animateFloatAsState(
        liquidSurfaceOpacity(clarity, surface.luminance() < .5f, style),
        animationSpec = if (appearance.enabled) tween(180) else snap(), label = "liquidTint")
    val blurDp by animateFloatAsState(liquidBlurDp(style, clarity), tween(180), label = "liquidBlur")
    val active = source.renderingActive && source.contentReady && percent > 0
    val renderer = remember { LiquidBackdropRenderer() }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var positionSignal by remember { mutableStateOf(Offset.Unspecified) }
    DisposableEffect(source, renderer) {
        source.addConsumer(renderer)
        onDispose { source.removeConsumer(renderer); renderer.release() }
    }

    this.onGloballyPositioned {
        coordinates = it
        // LayoutCoordinates identity usually survives a drag. Publish its position separately so
        // changing only the outer placement also invalidates this material's cached display list.
        positionSignal = it.positionInWindow()
    }.clip(shape).drawWithContent {
        val outline = shape.createOutline(size, layoutDirection, this)
        val node = source.node
        val host = source.host
        val currentActive = source.renderingActive
        val ready = source.contentReady
        val hasNode = node?.hasDisplayList() == true
        val positioned = positionSignal != Offset.Unspecified && coordinates?.isAttached == true
        val position = coordinates?.takeIf { positioned }?.positionInWindow()
        val reason = when {
            percent <= 0 -> "zero-transparency"
            !currentActive -> "inactive-source"
            !ready -> "content-not-ready"
            host == null -> "missing-host"
            !hasNode -> "missing-display-list"
            position == null -> "missing-coordinates"
            size.width <= 0 || size.height <= 0 -> "empty-size"
            else -> "none"
        }
        if (reason != "none" || node == null || host == null || position == null) {
            renderer.pause()
            source.recordGuard(renderer, reason, active, currentActive, positioned, hasNode, null)
            drawOutline(outline, surface)
            drawContent()
            return@drawWithContent
        }
        var hardware = false
        drawIntoCanvas { canvas ->
            hardware = canvas.nativeCanvas.isHardwareAccelerated
            if (hardware) {
                host.getLocationInWindow(renderer.hostLocation)
                renderer.draw(canvas.nativeCanvas, node,
                    position.x - renderer.hostLocation[0], position.y - renderer.hostLocation[1],
                    size.width, size.height, blurDp * density)
            }
        }
        if (!hardware) {
            renderer.pause()
            source.recordGuard(renderer, "software-canvas", active, currentActive, positioned, hasNode, false)
            drawOutline(outline, surface)
            drawContent()
            return@drawWithContent
        }
        source.recordGuard(renderer, "none", active, currentActive, positioned, hasNode, true)
        drawOutline(outline, surface.copy(alpha = baseOpacity))
        drawContent()

        source.recordMaterial(baseOpacity, 0f, blurDp,
            renderer.materialReady, renderer.materialRecords, renderer.materialRecoveries)
    }
}

@RequiresApi(31)
private class LiquidBackdropRenderer {
    val hostLocation = IntArray(2)
    private val material = RenderNode("MiradLiquidMaterial")
    private var previousContent: RenderNode? = null
    private var previousX = Float.NaN
    private var previousY = Float.NaN
    private var previousWidth = -1f
    private var previousHeight = -1f
    private var previousPadding = -1
    private var lastBlurRadius = -1f
    private var blur: RenderEffect? = null
    private var appliedMaterialEffect: RenderEffect? = null
    private var participating = false
    val materialReady: Boolean get() = material.hasDisplayList()
    var materialRecords = 0L
        private set
    var materialRecoveries = 0L
        private set

    fun draw(canvas: Canvas, content: RenderNode, x: Float, y: Float, width: Float, height: Float,
        blurRadiusPx: Float) {
        // 清晰度 100% 时半径为 0，直接绘制背景节点。
        val hasBlur = blurRadiusPx > 0.01f
        val padding = liquidMaterialPaddingPx(blurRadiusPx)
        participating = true
        if (hasBlur && blurRadiusPx != lastBlurRadius) {
            lastBlurRadius = blurRadiusPx
            blur = RenderEffect.createBlurEffect(blurRadiusPx, blurRadiusPx, Shader.TileMode.CLAMP)
        }
        // A detached native child may lose its display list even though its Java wrapper and
        // geometry cache survive a 0%/fallback interval. Re-establish both list and effect on return.
        val hadDisplayList = material.hasDisplayList()
        val record = !hadDisplayList || previousContent !== content || previousX != x || previousY != y ||
            previousWidth != width || previousHeight != height || previousPadding != padding
        if (record) {
            if (!hadDisplayList && materialRecords > 0) materialRecoveries++
            val w = ceil(width).toInt() + padding * 2
            val h = ceil(height).toInt() + padding * 2
            material.setPosition(0, 0, w, h)
            val recording = material.beginRecording(w, h)
            try {
                recording.translate(padding - x, padding - y)
                recording.drawRenderNode(content)
            } finally { material.endRecording() }
            previousContent = content; previousX = x; previousY = y
            previousWidth = width; previousHeight = height; previousPadding = padding
            materialRecords++
        }
        val effect = if (hasBlur) blur else null
        if (record || effect !== appliedMaterialEffect) {
            material.setRenderEffect(effect)
            appliedMaterialEffect = effect
        }
        val save = canvas.save()
        try {
            canvas.translate(-padding.toFloat(), -padding.toFloat())
            canvas.drawRenderNode(material)
        } finally { canvas.restoreToCount(save) }
    }

    fun pause() {
        if (!participating) return
        participating = false
        previousContent = null
        previousWidth = -1f
        previousHeight = -1f
        appliedMaterialEffect = null
    }

    fun release() { pause(); material.discardDisplayList() }
}
