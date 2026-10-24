package com.mirad.reader.ui.common

import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlin.math.ceil

/** Material and foreground have independent hardware layers; only the material is refracted. */
@RequiresApi(31)
internal fun Modifier.hardwareGlassBackground(
    source: HardwareGlassBackdrop, style: GlassStyle, shape: Shape
): Modifier = composed {
    val percent = LocalGlassAppearance.current.transparencyPercent
    val surface = MaterialTheme.colorScheme.surface
    val baseOpacity = liquidBaseOpacity(percent)
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
                    size.width, size.height, density, liquidBlurDp(style), outline)
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

        source.recordMaterial(baseOpacity, 0f, liquidBlurDp(style),
            Build.VERSION.SDK_INT >= 33 && outline !is Outline.Generic,
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
    private val refraction by lazy(LazyThreadSafetyMode.NONE) {
        if (Build.VERSION.SDK_INT >= 33) LiquidEdgeRefraction() else null
    }
    private var appliedMaterialEffect: RenderEffect? = null
    private var participating = false
    val materialReady: Boolean get() = material.hasDisplayList()
    var materialRecords = 0L
        private set
    var materialRecoveries = 0L
        private set

    fun draw(canvas: Canvas, content: RenderNode, x: Float, y: Float, width: Float, height: Float,
        density: Float, blurDp: Float, outline: Outline) {
        val radius = blurDp * density
        val padding = ceil(radius * 2).toInt()
        participating = true
        if (radius != lastBlurRadius) {
            lastBlurRadius = radius
            blur = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
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
        val effect = if (Build.VERSION.SDK_INT >= 33 && outline !is Outline.Generic) {
            refraction?.effect(width, height, outline, padding.toFloat(), density, blur!!)
        } else blur
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

@RequiresApi(33)
private class LiquidEdgeRefraction {
    private val shader = RuntimeShader("""
        uniform shader content;
        uniform float2 size;
        uniform float4 corners;
        uniform float padding;
        uniform float rim;
        uniform float strength;
        float distanceToEdge(float2 p) {
            float r = p.x < size.x * 0.5 ?
                (p.y < size.y * 0.5 ? corners.x : corners.w) :
                (p.y < size.y * 0.5 ? corners.y : corners.z);
            float2 q = abs(p - size * 0.5) - (size * 0.5 - r);
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        }
        half4 main(float2 coord) {
            float2 p = coord - padding;
            float d = distanceToEdge(p);
            float weight = 1.0 - smoothstep(0.0, rim, max(-d, 0.0));
            float2 gradient = float2(
                distanceToEdge(p+float2(0.5,0.0))-distanceToEdge(p-float2(0.5,0.0)),
                distanceToEdge(p+float2(0.0,0.5))-distanceToEdge(p-float2(0.0,0.5)));
            float2 normal = gradient / max(length(gradient), 0.001);
            return content.eval(coord + normal * strength * weight * weight);
        }
    """.trimIndent())
    private var lastWidth = -1f
    private var lastHeight = -1f
    private var lastPadding = -1f
    private var lastDensity = -1f
    private var lastTopLeft = -1f
    private var lastTopRight = -1f
    private var lastBottomRight = -1f
    private var lastBottomLeft = -1f
    private var lastBlur: RenderEffect? = null
    private var cached: RenderEffect? = null

    fun effect(width: Float, height: Float, outline: Outline, padding: Float, density: Float, blur: RenderEffect): RenderEffect {
        val rect = (outline as? Outline.Rounded)?.roundRect
        val topLeft = rect?.topLeftCornerRadius?.x ?: 0f
        val topRight = rect?.topRightCornerRadius?.x ?: 0f
        val bottomRight = rect?.bottomRightCornerRadius?.x ?: 0f
        val bottomLeft = rect?.bottomLeftCornerRadius?.x ?: 0f
        if (width == lastWidth && height == lastHeight && padding == lastPadding && density == lastDensity &&
            topLeft == lastTopLeft && topRight == lastTopRight && bottomRight == lastBottomRight &&
            bottomLeft == lastBottomLeft && blur === lastBlur) cached?.let { return it }
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("corners", topLeft, topRight, bottomRight, bottomLeft)
        shader.setFloatUniform("padding", padding)
        shader.setFloatUniform("rim", 8 * density)
        shader.setFloatUniform("strength", 1.5f * density)
        // RuntimeShader uniforms are snapshotted when the effect is created.
        val effect = RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"), blur)
        lastWidth = width; lastHeight = height; lastPadding = padding; lastDensity = density
        lastTopLeft = topLeft; lastTopRight = topRight; lastBottomRight = bottomRight; lastBottomLeft = bottomLeft
        lastBlur = blur
        cached = effect
        return effect
    }
}
