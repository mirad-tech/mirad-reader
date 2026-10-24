package com.mirad.reader.ui.common

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal const val LIQUID_ANALYSIS_INTERVAL_NANOS = 500_000_000L
internal const val LIQUID_ANALYSIS_MAX_EDGE = 64

/** Analysis geometry is independent of the display layer, which always retains native resolution. */
internal fun liquidAnalysisDimensions(width: Int, height: Int): Pair<Int, Int>? {
    if (width <= 0 || height <= 0) return null
    val scale = min(1.0, LIQUID_ANALYSIS_MAX_EDGE.toDouble() / max(width, height))
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}

internal fun liquidBaseOpacity(percent: Int): Float {
    val remaining = 1f - percent.coerceIn(0, 100) / 100f
    return .02f + .98f * remaining * remaining
}

internal fun liquidBlurDp(style: GlassStyle): Float = when (style) {
    GlassStyle.Toolbar -> 10f
    GlassStyle.Panel -> 14f
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
