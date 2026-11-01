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
 * 硬件路径背景弯折（RuntimeShader）的开关，与 [LocalGlassEdgeTexture] 的描边分开。
 *
 * 当前是恒为 true 的接入点：本分支没有任何调用方把它设为 false（预览夹具未包含该命令），
 * 保留它只是为了将来能单独关掉折射做对照。不进入偏好，也不参与持久化。
 */
val LocalGlassRefraction = staticCompositionLocalOf { true }

/**
 * 方向性散射过渡 + 内侧暗部 + 受光高光。它是 Compose 渐变描边，不是物理折射模型；
 * 硬件路径(API 33+)另有 RuntimeShader 负责背景弯折，两者叠加成"透镜边缘"。
 *
 * 光向固定为"自上而下、略偏左"，三条线都沿同一条渐变轴取值：
 * 顶边最亮、底边最暗，左右两侧随高度连续过渡，圆角处自然转向。
 * 因此不会出现整圈等亮的白框，也不会出现一圈黑框。
 * 所有描边都裁在面板内，不创建离屏层、不做模糊；Brush 按尺寸、明暗和清晰度缓存。
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
    val thicknessWidthPx = GlassEdgeProfile.thicknessWidthDp(percent, shortSideDp).dp.toPx()
    val contourWidthPx = GlassEdgeProfile.contourWidthDp(percent, shortSideDp).dp.toPx()
    val specularWidthPx = GlassEdgeProfile.specularWidthDp(percent, shortSideDp).dp.toPx()
    val glowWidthPx = GlassEdgeProfile.glowWidthDp(percent, shortSideDp).dp.toPx()
    val path = outline.edgePath()
    val layers = EdgeBrushCache.layers(height, dark, percent)

    clipPath(path) {
        if (tintAlpha < .98f) drawRect(brush = layers.bottomShade)
        if (topSheen && tintAlpha < .98f) drawRect(brush = layers.topSheen)

        for (index in layers.scattering.indices) {
            drawPath(
                path,
                layers.scattering[index],
                style = Stroke(scatteringStrokeWidth(bandWidthPx, index, layers.scattering.size))
            )
        }
        // 背光面的内侧暗部：贴边向内一条宽带，让轮廓有厚度而不是一条线。
        drawPath(path, layers.thickness, style = Stroke(thicknessWidthPx * 2f))
        // 发丝级暗轮廓只负责定位边界。
        drawPath(
            path,
            Color.Black.copy(alpha = GlassEdgeProfile.contourAlpha(percent)),
            style = Stroke(contourWidthPx)
        )
        // 受光面：先一层柔光，再一条细亮线，形成弧面反光而不是等宽白边。
        drawPath(path, layers.glow, style = Stroke(glowWidthPx))
        drawPath(path, layers.specular, style = Stroke(specularWidthPx))
    }
}

/** Stroke 以轮廓线为中心，裁切后可见的内侧展开宽度约为 [bandWidthPx]。 */
private fun scatteringStrokeWidth(bandWidthPx: Float, index: Int, count: Int): Float =
    bandWidthPx * 2f * (1f - index / (count + 0.6f))

private class EdgeLayers(
    val scattering: Array<Brush>,
    val thickness: Brush,
    val specular: Brush,
    val glow: Brush,
    val bottomShade: Brush,
    val topSheen: Brush
)

/** Brush creation is limited to cache misses; draw never allocates a per-frame brush array. */
private object EdgeBrushCache {
    private class Entry(
        val height: Float,
        val dark: Boolean,
        val clarity: Int,
        val layers: EdgeLayers
    )

    private const val MAX_ENTRIES = 8
    private val entries = ArrayDeque<Entry>()

    /** 键只取高度、明暗与清晰度：渐变端点只跟高度有关，宽度变化不该让缓存失效。 */
    fun layers(height: Float, dark: Boolean, clarity: Int): EdgeLayers {
        for (entry in entries) {
            if (entry.height == height && entry.dark == dark && entry.clarity == clarity) {
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
        val built = EdgeLayers(
            scattering = scattering,
            thickness = thicknessBrush(height, dark, GlassEdgeProfile.thicknessIntensity(clarity, dark)),
            specular = specularBrush(height, dark, GlassEdgeProfile.specularIntensity(clarity, dark)),
            glow = glowBrush(height, dark, GlassEdgeProfile.glowIntensity(clarity, dark)),
            bottomShade = bottomShade(dark),
            topSheen = topSheen(dark)
        )
        if (entries.size >= MAX_ENTRIES) entries.removeFirst()
        entries.addLast(Entry(height, dark, clarity, built))
        return built
    }

    /**
     * 渐变端点：只沿纵向铺满控件，上亮下暗。
     *
     * 刻意不加横向偏移：线性渐变超出端点会钳位到端色，而"横向偏移按宽度取比例"在宽栏上
     * 会把 t 推过 1（393dp 宽、73dp 高的顶栏，右下角 t≈3.3），右半边上边缘会整段失去高光。
     */
    private fun lightStart(): Offset = Offset.Zero

    private fun lightEnd(height: Float): Offset = Offset(0f, height)

    private fun bottomShade(dark: Boolean): Brush = Brush.verticalGradient(
        0.88f to Color.Transparent,
        1.00f to Color.Black.copy(alpha = if (dark) .025f else .015f)
    )

    private fun topSheen(dark: Boolean): Brush = Brush.verticalGradient(
        0.00f to Color.White.copy(alpha = if (dark) .035f else .04f),
        0.20f to Color.Transparent
    )

    private fun scatteringBrush(height: Float, dark: Boolean, intensity: Float): Brush =
        Brush.linearGradient(
            colorStops = if (dark) arrayOf(
                0.00f to Color.White.copy(alpha = (.34f * intensity).coerceIn(0f, 1f)),
                0.26f to Color.White.copy(alpha = (.17f * intensity).coerceIn(0f, 1f)),
                0.54f to Color.Transparent,
                0.80f to Color.Black.copy(alpha = (.08f * intensity).coerceIn(0f, 1f)),
                1.00f to Color.Black.copy(alpha = (.20f * intensity).coerceIn(0f, 1f))
            ) else arrayOf(
                0.00f to Color.White.copy(alpha = (.23f * intensity).coerceIn(0f, 1f)),
                0.26f to Color.White.copy(alpha = (.11f * intensity).coerceIn(0f, 1f)),
                0.54f to Color.Transparent,
                0.80f to Color.Black.copy(alpha = (.11f * intensity).coerceIn(0f, 1f)),
                1.00f to Color.Black.copy(alpha = (.26f * intensity).coerceIn(0f, 1f))
            ),
            start = lightStart(),
            end = lightEnd(height)
        )

    private fun thicknessBrush(height: Float, dark: Boolean, intensity: Float): Brush =
        Brush.linearGradient(
            colorStops = arrayOf(
                0.00f to Color.Transparent,
                0.42f to Color.Transparent,
                0.72f to Color.Black.copy(alpha = (.05f * intensity).coerceIn(0f, 1f)),
                1.00f to Color.Black.copy(alpha = (.13f * intensity).coerceIn(0f, 1f))
            ),
            start = lightStart(),
            end = lightEnd(height)
        )

    private fun specularBrush(height: Float, dark: Boolean, intensity: Float): Brush =
        Brush.linearGradient(
            colorStops = if (dark) arrayOf(
                0.00f to Color.White.copy(alpha = (.76f * intensity).coerceIn(0f, 1f)),
                0.22f to Color.White.copy(alpha = (.40f * intensity).coerceIn(0f, 1f)),
                0.48f to Color.White.copy(alpha = (.06f * intensity).coerceIn(0f, 1f)),
                0.66f to Color.Transparent,
                1.00f to Color.Transparent
            ) else arrayOf(
                0.00f to Color.White.copy(alpha = (.62f * intensity).coerceIn(0f, 1f)),
                0.22f to Color.White.copy(alpha = (.30f * intensity).coerceIn(0f, 1f)),
                0.48f to Color.White.copy(alpha = (.05f * intensity).coerceIn(0f, 1f)),
                0.66f to Color.Transparent,
                1.00f to Color.Transparent
            ),
            start = lightStart(),
            end = lightEnd(height)
        )

    private fun glowBrush(height: Float, dark: Boolean, intensity: Float): Brush =
        Brush.linearGradient(
            colorStops = if (dark) arrayOf(
                0.00f to Color.White.copy(alpha = (.46f * intensity).coerceIn(0f, 1f)),
                0.30f to Color.White.copy(alpha = (.26f * intensity).coerceIn(0f, 1f)),
                0.58f to Color.Transparent,
                1.00f to Color.Transparent
            ) else arrayOf(
                0.00f to Color.White.copy(alpha = (.40f * intensity).coerceIn(0f, 1f)),
                0.30f to Color.White.copy(alpha = (.20f * intensity).coerceIn(0f, 1f)),
                0.58f to Color.Transparent,
                1.00f to Color.Transparent
            ),
            start = lightStart(),
            end = lightEnd(height)
        )
}

private fun Outline.edgePath(): Path = when (this) {
    is Outline.Rectangle -> Path().apply { addRect(rect) }
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Generic -> path
}
