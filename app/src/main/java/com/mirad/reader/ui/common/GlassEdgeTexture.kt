package com.mirad.reader.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 预览对照开关；生产环境恒为 true，不参与布局、偏好或阅读位置。 */
val LocalGlassEdgeTexture = staticCompositionLocalOf { true }

/** 只关闭背景弯折，保留相同光影，供预览独立验证真实折射。 */
internal val LocalGlassRefraction = staticCompositionLocalOf { true }

/**
 * 光向固定在左上方。直边用恒定法线，圆角用椭圆法线的环形渐变，切点颜色一致。
 * 所有 API 共用此几何光影；API 33+ 的背景折射另由生产 RuntimeShader 完成。
 * 路径与 Brush 按真实轮廓缓存；不创建离屏层，也不逐帧回读或计算法线。
 */
internal fun DrawScope.drawGlassEdgeTexture(
    outline: Outline,
    surface: Color,
    clarity: Int,
    tintAlpha: Float,
    topSheen: Boolean
) {
    if (size.width <= 0f || size.height <= 0f || density <= 0f) return
    val percent = clarity.coerceIn(0, 100)
    val dark = surface.luminance() < .5f
    val shortSide = minOf(size.width, size.height) / density
    val band = GlassEdgeProfile.bandWidthDp(percent, shortSide).dp.toPx()
    val thickness = GlassEdgeProfile.thicknessWidthDp(percent, shortSide).dp.toPx()
    val contour = GlassEdgeProfile.contourWidthDp(percent, shortSide).dp.toPx()
    val specular = GlassEdgeProfile.specularWidthDp(percent, shortSide).dp.toPx()
    val glow = GlassEdgeProfile.glowWidthDp(percent, shortSide).dp.toPx()
    val layers = EdgeBrushCache.layers(outline, dark, percent)
    clipPath(layers.path) {
        if (tintAlpha < .98f) drawRect(brush = layers.bottomShade)
        if (topSheen && tintAlpha < .98f) drawRect(brush = layers.topSheen)
        for (part in layers.parts) {
            for (index in part.scattering.indices) {
                val width = band * 2f * (1f - index / (part.scattering.size + .6f))
                drawPath(part.path, part.scattering[index], style = Stroke(width))
            }
            drawPath(part.path, part.thickness, style = Stroke(thickness * 2f))
            // Stroke 的一半位于轮廓外：乘二后，可见宽度才是 profile 指定的宽度。
            drawPath(part.path, part.glow, style = Stroke(glow * 2f))
            drawPath(part.path, part.specular, style = Stroke(specular * 2f))
        }
        drawPath(layers.path, Color.Black.copy(alpha = GlassEdgeProfile.contourAlpha(percent)),
            style = Stroke(contour))
    }
}

private class EdgePart(
    val path: Path,
    val scattering: Array<Brush>,
    val thickness: Brush,
    val glow: Brush,
    val specular: Brush
)

private class EdgeLayers(val path: Path, val parts: List<EdgePart>, dark: Boolean) {
    val bottomShade = Brush.verticalGradient(
        .88f to Color.Transparent,
        1f to Color.Black.copy(alpha = if (dark) .025f else .015f))
    val topSheen = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (dark) .035f else .04f),
        .20f to Color.Transparent)
}

private object EdgeBrushCache {
    private data class Entry(val outline: Outline, val dark: Boolean, val clarity: Int, val layers: EdgeLayers)
    private val entries = ArrayDeque<Entry>()

    fun layers(outline: Outline, dark: Boolean, clarity: Int): EdgeLayers {
        entries.firstOrNull { it.outline == outline && it.dark == dark && it.clarity == clarity }
            ?.let { return it.layers }
        val path = when (outline) {
            is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
            is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
            is Outline.Generic -> outline.path
        }
        val parts = mutableListOf<EdgePart>()
        fun part(path: Path, brush: (Float, Float) -> Brush) {
            val scatter = GlassEdgeProfile.scatteringIntensity(clarity, dark)
            parts += EdgePart(path,
                Array(GlassEdgeProfile.BROAD_BAND_COUNT) { index ->
                    val scale = scatter * GlassEdgeProfile.broadBandScale(index)
                    brush((if (dark) .34f else .23f) * scale, (if (dark) .20f else .26f) * scale)
                },
                brush(0f, .13f * GlassEdgeProfile.thicknessIntensity(clarity, dark)),
                brush((if (dark) .46f else .40f) * GlassEdgeProfile.glowIntensity(clarity, dark), 0f),
                brush((if (dark) .76f else .62f) * GlassEdgeProfile.specularIntensity(clarity, dark), 0f))
        }
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, nx: Float, ny: Float) {
            if (x1 == x2 && y1 == y2) return
            val line = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            val facing = GlassEdgeProfile.lightFacing(nx, ny)
            part(line) { light, shade -> SolidColor(edgeColor(facing, light, shade)) }
        }
        fun corner(center: Offset, radius: CornerRadius, start: Float) {
            if (radius.x <= 0f || radius.y <= 0f) return
            val arc = Path().apply {
                addArc(Rect(center.x - radius.x, center.y - radius.y,
                    center.x + radius.x, center.y + radius.y), start, 90f)
            }
            part(arc) { light, shade ->
                val stops = Array(33) { index ->
                    val angle = index * (Math.PI * 2 / 32)
                    val x = cos(angle).toFloat() / radius.x
                    val y = sin(angle).toFloat() / radius.y
                    val length = hypot(x, y)
                    index / 32f to edgeColor(GlassEdgeProfile.lightFacing(x / length, y / length), light, shade)
                }
                Brush.sweepGradient(colorStops = stops, center = center)
            }
        }
        val rect = (outline as? Outline.Rounded)?.roundRect
        if (outline !is Outline.Generic) {
            val bounds = outline.bounds
            val tl = rect?.topLeftCornerRadius ?: CornerRadius.Zero
            val tr = rect?.topRightCornerRadius ?: CornerRadius.Zero
            val br = rect?.bottomRightCornerRadius ?: CornerRadius.Zero
            val bl = rect?.bottomLeftCornerRadius ?: CornerRadius.Zero
            val l = bounds.left; val t = bounds.top; val r = bounds.right; val b = bounds.bottom
            line(l + tl.x, t, r - tr.x, t, 0f, -1f)
            corner(Offset(r - tr.x, t + tr.y), tr, 270f)
            line(r, t + tr.y, r, b - br.y, 1f, 0f)
            corner(Offset(r - br.x, b - br.y), br, 0f)
            line(r - br.x, b, l + bl.x, b, 0f, 1f)
            corner(Offset(l + bl.x, b - bl.y), bl, 90f)
            line(l, b - bl.y, l, t + tl.y, -1f, 0f)
            corner(Offset(l + tl.x, t + tl.y), tl, 180f)
        } else {
            // 任意路径继续使用几何无关的稳定降级，不猜测其法线或改写轮廓。
            part(path) { light, shade -> Brush.linearGradient(
                0f to Color.White.copy(alpha = light), .5f to Color.Transparent,
                1f to Color.Black.copy(alpha = shade),
                start = outline.bounds.topLeft, end = outline.bounds.bottomRight) }
        }
        val result = EdgeLayers(path, parts, dark)
        if (entries.size >= 8) entries.removeFirst()
        entries.addLast(Entry(outline, dark, clarity, result))
        return result
    }
}

private fun edgeColor(facing: Float, light: Float, shade: Float): Color =
    if (facing >= 0f) Color.White.copy(alpha = (light * facing * facing).coerceIn(0f, 1f))
    else Color.Black.copy(alpha = (-shade * facing).coerceIn(0f, 1f))
