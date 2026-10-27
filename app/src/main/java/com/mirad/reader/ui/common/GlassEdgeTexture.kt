package com.mirad.reader.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * 边缘质感开关：仅为预览对照保留“临时禁用边缘”的观测能力，生产环境恒为 true。
 * 只影响边缘绘制与折射，不参与模糊、布局或阅读位置计算。
 */
val LocalGlassEdgeTexture = staticCompositionLocalOf { true }

/**
 * 方向性散射宽带 + 暗轮廓 + 窄镜面高光。它是 Compose 渐变描边，不是物理折射模型。
 * 六条宽带覆盖磨砂过渡，最后两条细线把边缘重新收锐。所有描边都裁在面板内，
 * 不创建离屏层、不做模糊；渐变 Brush 按尺寸、明暗和清晰度缓存。
 */
internal fun DrawScope.drawGlassEdgeTexture(
    outline: Outline,
    surface: Color,
    clarity: Int,
    tintAlpha: Float,
    topSheen: Boolean
) {
    val width = size.width
    val height = size.height
    if (width <= 0f || height <= 0f || density <= 0f) return

    val dark = surface.luminance() < .5f
    val percent = clarity.coerceIn(0, 100)
    val shortSideDp = minOf(width, height) / density
    val bandWidthPx = GlassEdgeProfile.bandWidthDp(percent, shortSideDp).dp.toPx()
    val contourWidthPx = GlassEdgeProfile.contourWidthDp(percent, shortSideDp).dp.toPx()
    val specularWidthPx = GlassEdgeProfile.specularWidthDp(percent, shortSideDp).dp.toPx()
    val path = outline.edgePath()
    val layers = EdgeBrushCache.layers(width, height, dark, percent)

    clipPath(path) {
        if (tintAlpha < .98f) drawRect(brush = EdgeBrushCache.bottomShade(dark))
        if (topSheen && tintAlpha < .98f) drawRect(brush = EdgeBrushCache.topSheen(dark))

        for (index in layers.scattering.indices) {
            drawPath(
                path,
                layers.scattering[index],
                style = Stroke(scatteringStrokeWidth(bandWidthPx, index, layers.scattering.size))
            )
        }
        drawPath(
            path,
            Color.Black.copy(alpha = GlassEdgeProfile.contourAlpha(percent)),
            style = Stroke(contourWidthPx)
        )
        drawPath(path, layers.specular, style = Stroke(specularWidthPx))
    }
}

/** Stroke is centered on the outline; clipping keeps the visible inner spread at [bandWidthPx]. */
private fun scatteringStrokeWidth(bandWidthPx: Float, index: Int, count: Int): Float =
    bandWidthPx * 2f * (1f - index / (count + 0.6f))

private class EdgeLayers(val scattering: Array<Brush>, val specular: Brush)

/** Brush creation is limited to cache misses; draw never allocates a per-frame brush array. */
private object EdgeBrushCache {
    private class Entry(
        val width: Float,
        val height: Float,
        val dark: Boolean,
        val clarity: Int,
        val layers: EdgeLayers
    )

    private const val MAX_ENTRIES = 8
    private val entries = ArrayDeque<Entry>()
    private var shadeKey = Int.MIN_VALUE
    private var shade: Brush? = null
    private var sheenKey = Int.MIN_VALUE
    private var sheen: Brush? = null

    fun layers(width: Float, height: Float, dark: Boolean, clarity: Int): EdgeLayers {
        for (entry in entries) {
            if (entry.width == width && entry.height == height && entry.dark == dark &&
                entry.clarity == clarity
            ) {
                return entry.layers
            }
        }
        val scatterIntensity = GlassEdgeProfile.scatteringIntensity(clarity, dark)
        val scattering = Array(GlassEdgeProfile.BROAD_BAND_COUNT) { index ->
            scatteringBrush(
                height,
                dark,
                GlassEdgeProfile.broadBandScale(index) * scatterIntensity
            )
        }
        val specular = specularBrush(
            height,
            dark,
            GlassEdgeProfile.specularIntensity(clarity, dark)
        )
        val built = EdgeLayers(scattering, specular)
        if (entries.size >= MAX_ENTRIES) entries.removeFirst()
        entries.addLast(Entry(width, height, dark, clarity, built))
        return built
    }

    fun bottomShade(dark: Boolean): Brush {
        val key = if (dark) 1 else 0
        shade?.let { if (shadeKey == key) return it }
        val brush = Brush.verticalGradient(
            0.88f to Color.Transparent,
            1.00f to Color.Black.copy(alpha = if (dark) .14f else .07f)
        )
        shadeKey = key
        shade = brush
        return brush
    }

    fun topSheen(dark: Boolean): Brush {
        val key = if (dark) 1 else 0
        sheen?.let { if (sheenKey == key) return it }
        val brush = Brush.verticalGradient(
            0.00f to Color.White.copy(alpha = if (dark) .05f else .08f),
            0.20f to Color.Transparent
        )
        sheenKey = key
        sheen = brush
        return brush
    }

    private fun scatteringBrush(height: Float, dark: Boolean, intensity: Float): Brush =
        Brush.linearGradient(
            colorStops = if (dark) arrayOf(
                0.00f to Color.White.copy(alpha = (.36f * intensity).coerceIn(0f, 1f)),
                0.26f to Color.White.copy(alpha = (.18f * intensity).coerceIn(0f, 1f)),
                0.52f to Color.Transparent,
                0.80f to Color.Black.copy(alpha = (.08f * intensity).coerceIn(0f, 1f)),
                1.00f to Color.Black.copy(alpha = (.22f * intensity).coerceIn(0f, 1f))
            ) else arrayOf(
                0.00f to Color.White.copy(alpha = (.24f * intensity).coerceIn(0f, 1f)),
                0.26f to Color.White.copy(alpha = (.12f * intensity).coerceIn(0f, 1f)),
                0.52f to Color.Transparent,
                0.80f to Color.Black.copy(alpha = (.12f * intensity).coerceIn(0f, 1f)),
                1.00f to Color.Black.copy(alpha = (.28f * intensity).coerceIn(0f, 1f))
            ),
            start = Offset.Zero,
            end = Offset(0f, height)
        )

    private fun specularBrush(height: Float, dark: Boolean, intensity: Float): Brush =
        Brush.linearGradient(
            colorStops = if (dark) arrayOf(
                0.00f to Color.White.copy(alpha = (.76f * intensity).coerceIn(0f, 1f)),
                0.24f to Color.White.copy(alpha = (.38f * intensity).coerceIn(0f, 1f)),
                0.52f to Color.White.copy(alpha = (.05f * intensity).coerceIn(0f, 1f)),
                0.80f to Color.Transparent,
                1.00f to Color.Transparent
            ) else arrayOf(
                0.00f to Color.White.copy(alpha = (.48f * intensity).coerceIn(0f, 1f)),
                0.24f to Color.White.copy(alpha = (.22f * intensity).coerceIn(0f, 1f)),
                0.52f to Color.White.copy(alpha = (.03f * intensity).coerceIn(0f, 1f)),
                0.80f to Color.Transparent,
                1.00f to Color.Transparent
            ),
            start = Offset.Zero,
            end = Offset(0f, height)
        )
}

private fun Outline.edgePath(): Path = when (this) {
    is Outline.Rectangle -> Path().apply { addRect(rect) }
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Generic -> path
}
