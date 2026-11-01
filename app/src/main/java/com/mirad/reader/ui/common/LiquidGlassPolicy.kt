package com.mirad.reader.ui.common

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.ceil

internal const val LIQUID_ANALYSIS_INTERVAL_NANOS = 500_000_000L
internal const val LIQUID_ANALYSIS_MAX_EDGE = 64

/** Analysis geometry is independent of the display layer, which always retains native resolution. */
internal fun liquidAnalysisDimensions(width: Int, height: Int): Pair<Int, Int>? {
    if (width <= 0 || height <= 0) return null
    val scale = min(1.0, LIQUID_ANALYSIS_MAX_EDGE.toDouble() / max(width, height))
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}

/** 清晰度 100 时的薄底；两个角色共用，保证最清晰的一档也仍是一层有色玻璃。 */
private const val LIQUID_TINT_FLOOR_LIGHT = .08f
private const val LIQUID_TINT_FLOOR_DARK = .10f

/** 上下栏在清晰度 0 时的上限：最朦胧的一档也必须看得见背后内容，绝不回到整块主题底。 */
internal const val LIQUID_BAR_CEILING_LIGHT = .46f
internal const val LIQUID_BAR_CEILING_DARK = .52f

internal fun liquidTintFloor(isDark: Boolean): Float =
    if (isDark) LIQUID_TINT_FLOOR_DARK else LIQUID_TINT_FLOOR_LIGHT

internal fun liquidBarCeiling(isDark: Boolean): Float =
    if (isDark) LIQUID_BAR_CEILING_DARK else LIQUID_BAR_CEILING_LIGHT

/**
 * 面板（目录、设置、消息、确认框）的底色曲线。
 *
 * 朦胧端允许接近实心：弹窗压在长内容上，0 档需要一块能读字的表面。清晰度越高越薄，
 * 100 档与其他角色同为 [liquidTintFloor] 的薄底，不再有额外的可读性下限。
 */
internal fun liquidPanelOpacity(percent: Int, isDark: Boolean = false): Float {
    val remaining = 1f - percent.coerceIn(0, 100) / 100f
    val floor = liquidTintFloor(isDark)
    return floor + (1f - floor) * remaining * remaining
}

/**
 * 上下栏（公共顶栏、应用底栏及其选中胶囊、阅读工具栏）的底色曲线。
 *
 * 与 [liquidPanelOpacity] 的区别只在朦胧端：面板可以接近实心，上下栏不行。栏横跨整幅内容，
 * 一旦底色被抬到高不透明度，GPU 采样与模糊再怎么正确也只会看到一块主题白/黑，
 * 旧版调用点传入的 66%／78% 下限正是"白底"的直接来源。这里把 0 档上限压在
 * [liquidBarCeiling]：仍然是最朦胧、最厚的一档（模糊半径最大），但背景的颜色与结构必须透出来。
 */
internal fun liquidBarOpacity(percent: Int, isDark: Boolean = false): Float {
    val remaining = 1f - percent.coerceIn(0, 100) / 100f
    val floor = liquidTintFloor(isDark)
    val ceiling = liquidBarCeiling(isDark)
    return floor + (ceiling - floor) * remaining * remaining
}

/** 按角色取底色：面板走面板曲线，其余（栏与栏内小控件）走上下栏曲线。 */
internal fun liquidSurfaceOpacity(percent: Int, isDark: Boolean, style: GlassStyle): Float =
    if (style == GlassStyle.Panel) liquidPanelOpacity(percent, isDark) else liquidBarOpacity(percent, isDark)

/**
 * 清晰度的有效档位。
 *
 * 弹窗不再单独封顶到 80：它与上下栏共用同一套 0–100 语义，100 档同样零模糊。
 * 用户已确认：100 档看不清文字可以接受，不再用额外柔化把高清晰度悄悄改回来。
 */
internal fun liquidBlurClarity(clarity: Int): Int = clarity.coerceIn(0, 100)

/**
 * 玻璃底色的唯一入口：开关、降级与角色曲线都汇到这里。
 *
 * "uniform" 指同一容器只铺一层统一底色，不再按文字或局部背景另加色块；浓度本身按 [style] 区分。
 */
internal fun uniformGlassOpacity(
    appearance: GlassAppearance,
    isDark: Boolean,
    available: Boolean,
    style: GlassStyle = GlassStyle.Toolbar
): Float = when {
    !appearance.enabled -> 1f
    !available -> .94f
    else -> liquidSurfaceOpacity(appearance.clarityPercent, isDark, style)
}

/**
 * 边缘折射的最大径向位移。
 *
 * 旧的 2.1dp 在默认 60 档（工具栏模糊 8.5dp）里基本被磨平，边缘只剩一条描边，
 * 用户反馈的"只有磨砂、没有玻璃边缘"首先来自这里：位移小于模糊半径时，
 * 采样偏移只是把已经糊掉的像素再挪一点，看不出弯折。提高到 4.6dp 后，
 * 60 档仍能辨认贴边的压缩/拉伸，100 档（零模糊）则直接读出背景结构的错位。
 * 小控件另由 [liquidRefractionStrengthDp] 按短边限幅，避免整块被拉伸。
 */
internal const val LIQUID_REFRACTION_STRENGTH_DP = 4.6f

/** 影响带的绝对值上限；旧的 6dp 太窄，厚度没有过渡空间。 */
internal const val LIQUID_REFRACTION_RIM_DP = 9.5f

/** 影响带同时受短边限幅，小胶囊里不会把整个表面都变成折射区。 */
internal fun liquidRefractionRimDp(shortSideDp: Float): Float =
    min(LIQUID_REFRACTION_RIM_DP, shortSideDp.coerceAtLeast(0f) * .18f)

/** 位移按短边限幅：胶囊/小面板上取短边的 9%，大栏与大面板才用满 [LIQUID_REFRACTION_STRENGTH_DP]。 */
internal fun liquidRefractionStrengthDp(shortSideDp: Float): Float =
    min(LIQUID_REFRACTION_STRENGTH_DP, shortSideDp.coerceAtLeast(0f) * .09f)

/**
 * 材质离屏节点的外扩留白（像素）。
 *
 * 两段需求是叠加的：模糊要在四周留够 2 倍半径，折射还要在轮廓之外再多取一段最大位移。
 * 相加后节点内侧始终有 `半径 + 位移 + 1` 的安全余量，采样点不会落进模糊的钳位环；
 * 若像旧版那样取较大者，高清晰档（半径变小）会出现 `半径 < 位移` 的区间，
 * 贴边就会读到被钳位的边缘像素。
 */
internal fun liquidMaterialPaddingPx(blurRadiusPx: Float, refraction: Boolean, density: Float): Int {
    val blurPad = blurRadiusPx.coerceAtLeast(0f) * 2f
    val refractionPad = if (refraction) LIQUID_REFRACTION_STRENGTH_DP * density + 1f else 0f
    return ceil(blurPad + refractionPad).toInt()
}

/**
 * 折射位移随"离轮廓的距离"变化的曲线，t=0 在轮廓上、t=1 到影响带内沿。
 *
 * 三次多项式，两个端点被钉住：weightAt(0)=1（贴边位移最大），weightAt(1)=0 且该处斜率为 0
 * （与未偏移的中心平滑相接，没有折角）。系数满足 L+Q+C=1 与 L+2Q+3C=0，
 * shader 里用同一组系数，避免两边各写一条曲线。
 */
internal object LiquidRefractionCurve {
    const val LINEAR = .8f
    const val QUADRATIC = 1.4f
    const val CUBIC = -1.2f

    fun weightAt(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        val shift = LINEAR * x + QUADRATIC * x * x + CUBIC * x * x * x
        return (1f - shift).coerceIn(0f, 1f)
    }
}

/**
 * GPU 路径的模糊半径。
 *
 * 与 Compose 路径共用同一套清晰度语义：0 = 最朦胧，100 = 不模糊（面板与工具栏一致）。
 * 面板只是基础半径更厚，不再把高清晰度折算回 80 档。
 */
internal fun liquidBlurDp(style: GlassStyle, clarityPercent: Int = 50): Float {
    val base = when (style) {
        GlassStyle.Toolbar -> 10f
        GlassStyle.Panel -> 14f
    }
    val clarity = liquidBlurClarity(clarityPercent)
    if (clarity >= 100) return 0f
    val scale = 1f + (50 - clarity) / 50f * 0.75f
    return (base * scale).coerceIn(1f, 26f)
}

internal data class LiquidBackdropMetrics(val darkest: Int, val lightest: Int, val fingerprint: Long)

/** Called only by the analysis worker; no image or array is retained in UI state. */
internal fun analyzeLiquidPixels(pixels: IntArray): LiquidBackdropMetrics? {
    if (pixels.isEmpty()) return null
    var darkest = pixels[0]
    var lightest = pixels[0]
    var minLum = Float.POSITIVE_INFINITY
    var maxLum = Float.NEGATIVE_INFINITY
    var hash = -3750763034362895579L
    for (pixel in pixels) {
        val lum = liquidColorLuminance(pixel)
        if (lum < minLum) { minLum = lum; darkest = pixel }
        if (lum > maxLum) { maxLum = lum; lightest = pixel }
        hash = (hash xor pixel.toLong()) * 1099511628211L
    }
    return LiquidBackdropMetrics(darkest, lightest, hash)
}

internal fun liquidColorLuminance(color: Int): Float = relativeLuminanceFromSrgb(
    ((color ushr 16) and 255) / 255f,
    ((color ushr 8) and 255) / 255f,
    (color and 255) / 255f
)

private fun compositeLiquidColor(background: Int, tint: Int, alpha: Float): Int {
    fun channel(shift: Int) = (((background ushr shift) and 255) * (1 - alpha) +
        ((tint ushr shift) and 255) * alpha).roundToInt().coerceIn(0, 255)
    return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/**
 * The result controls only the foreground-shaped protection layer. It never raises the panel tint.
 * Unknown samples start conservatively; a sampled clear background can smoothly release protection.
 */
internal fun liquidProtectionOpacity(
    metrics: LiquidBackdropMetrics?, surface: Int, textColors: List<Int>, baseOpacity: Float
): Float {
    if (metrics == null) return .9f
    val candidates = intArrayOf(metrics.darkest, metrics.lightest)
    var required = .12f
    for (background in candidates) {
        val material = compositeLiquidColor(background, surface, baseOpacity)
        for (text in textColors) {
            val foregroundLum = liquidColorLuminance(text)
            fun passes(alpha: Float): Boolean = contrastRatio(
                foregroundLum, liquidColorLuminance(compositeLiquidColor(material, surface, alpha))
            ) >= 4.5f
            if (passes(0f)) continue
            if (!passes(1f)) { required = .98f; continue }
            var low = 0f
            var high = 1f
            repeat(12) {
                val mid = (low + high) * .5f
                if (passes(mid)) high = mid else low = mid
            }
            required = max(required, high)
        }
    }
    return required.coerceIn(.12f, .98f)
}

/** A single in-flight request and a start-to-start interval survive scene invalidation. */
internal class LiquidAnalysisGate(private val intervalNanos: Long = LIQUID_ANALYSIS_INTERVAL_NANOS) {
    var generation = 0L
        private set
    var inFlight = false
        private set
    var dirty = true
        private set
    private var lastStarted: Long? = null

    fun markDirty() { dirty = true }
    fun invalidate() { generation++; dirty = true }
    fun waitNanos(now: Long): Long = lastStarted?.let { max(0L, intervalNanos - (now - it)) } ?: 0L
    fun begin(now: Long): Long? {
        if (inFlight || !dirty || waitNanos(now) > 0) return null
        inFlight = true
        dirty = false
        lastStarted = now
        return generation
    }
    fun finish(token: Long): Boolean { inFlight = false; return token == generation }
}
