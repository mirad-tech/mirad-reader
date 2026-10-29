package com.mirad.reader.ui.common

import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** Material and foreground have independent hardware layers; only the material is refracted. */
@RequiresApi(31)
internal fun Modifier.hardwareGlassBackground(
    source: HardwareGlassBackdrop, style: GlassStyle, shape: Shape
): Modifier = composed {
    val appearance = LocalGlassAppearance.current
    val percent = appearance.transparencyPercent
    // 清晰度与开关同源：关闭时 percent=0 走稳定表面，100% 时 blur=0 走无模糊分支。
    val clarity = appearance.clarityPercent
    val edgeTexture = LocalGlassEdgeTexture.current
    val surface = MaterialTheme.colorScheme.surface
    val baseOpacity by animateFloatAsState(
        liquidMaterialOpacity(clarity, surface.luminance() < .5f, style),
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

    this.shadow(if (style == GlassStyle.Panel) 4.dp else 2.dp, shape, clip = false).onGloballyPositioned {
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
            if (edgeTexture && appearance.enabled) drawGlassEdgeTexture(outline, surface, clarity, 1f, topSheen = false)
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
                    size.width, size.height, density, blurDp, outline, edgeTexture)
            }
        }
        if (!hardware) {
            renderer.pause()
            source.recordGuard(renderer, "software-canvas", active, currentActive, positioned, hasNode, false)
            drawOutline(outline, surface)
            if (edgeTexture && appearance.enabled) drawGlassEdgeTexture(outline, surface, clarity, 1f, topSheen = false)
            drawContent()
            return@drawWithContent
        }
        source.recordGuard(renderer, "none", active, currentActive, positioned, hasNode, true)
        drawOutline(outline, surface.copy(alpha = baseOpacity))
        if (edgeTexture) drawGlassEdgeTexture(outline, surface, clarity, baseOpacity, topSheen = true)
        drawContent()

        source.recordMaterial(baseOpacity, 0f, blurDp,
            edgeTexture && Build.VERSION.SDK_INT >= 33 && outline !is Outline.Generic,
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
        density: Float, blurDp: Float, outline: Outline, edgeEnabled: Boolean) {
        val radius = blurDp * density
        // 清晰度 100% 会传 0：createBlurEffect 不接受 0 半径，必须明确走"无模糊"分支，
        // 而不是把 0 传给要求有效半径的路径。
        val hasBlur = radius > 0.01f
        val hasRefraction = edgeEnabled && Build.VERSION.SDK_INT >= 33 && outline !is Outline.Generic
        val padding = liquidMaterialPaddingPx(radius, hasRefraction, density)
        participating = true
        if (hasBlur && radius != lastBlurRadius) {
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
        val effect = when {
            hasRefraction -> refraction?.effect(width, height, outline, padding.toFloat(), density, if (hasBlur) blur else null)
            hasBlur -> blur
            else -> null
        }
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

    fun effect(width: Float, height: Float, outline: Outline, padding: Float, density: Float, blur: RenderEffect?): RenderEffect {
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
        shader.setFloatUniform("rim", liquidRefractionRimDp(minOf(width, height) / density) * density)
        shader.setFloatUniform("strength", LIQUID_REFRACTION_STRENGTH_DP * density)
        // RuntimeShader uniforms are snapshotted when the effect is created.
        val edge = RenderEffect.createRuntimeShaderEffect(shader, "content")
        val effect = if (blur == null) edge else RenderEffect.createChainEffect(edge, blur)
        lastWidth = width; lastHeight = height; lastPadding = padding; lastDensity = density
        lastTopLeft = topLeft; lastTopRight = topRight; lastBottomRight = bottomRight; lastBottomLeft = bottomLeft
        lastBlur = blur
        cached = effect
        return effect
    }
}
