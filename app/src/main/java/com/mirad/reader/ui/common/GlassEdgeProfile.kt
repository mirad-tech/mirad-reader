package com.mirad.reader.ui.common

/**
 * 玻璃边缘的纯参数模型。绘制仍由 Compose Brush 完成，不模拟真实光学折射。
 */
internal object GlassEdgeProfile {
    const val BROAD_BAND_COUNT = 6
    const val CONTOUR_STROKE_COUNT = 1
    const val SPECULAR_STROKE_COUNT = 1
    const val TOTAL_STROKE_COUNT = BROAD_BAND_COUNT + CONTOUR_STROKE_COUNT + SPECULAR_STROKE_COUNT

    private val broadBandScales = floatArrayOf(.035f, .05f, .07f, .10f, .15f, .24f)

    /** 0% 为 12dp、100% 为 7.2dp，且任一方向都不超过面板短边的 20%。 */
    fun bandWidthDp(clarity: Int, shortSideDp: Float): Float {
        val limit = shortSideLimit(shortSideDp, .20f)
        if (limit <= 0f) return 0f
        val ratio = clarityRatio(clarity)
        val requested = 12f + (7.2f - 12f) * ratio
        return minOf(requested, limit)
    }

    /** 单条暗轮廓线随清晰度略收窄，并为极矮面板保留边距。 */
    fun contourWidthDp(clarity: Int, shortSideDp: Float): Float {
        val limit = shortSideLimit(shortSideDp, .05f)
        if (limit <= 0f) return 0f
        val ratio = clarityRatio(clarity)
        return minOf(1.25f + (.8f - 1.25f) * ratio, limit)
    }

    /** 窄镜面线比散射带更细，并在清晰档进一步收窄。 */
    fun specularWidthDp(clarity: Int, shortSideDp: Float): Float {
        val limit = shortSideLimit(shortSideDp, .04f)
        if (limit <= 0f) return 0f
        val ratio = clarityRatio(clarity)
        return minOf(1.1f + (.55f - 1.1f) * ratio, limit)
    }

    /** 磨砂时暗轮廓更实，清晰时让窄镜面线承担更多锐度。 */
    fun contourAlpha(clarity: Int): Float = ((.30f + (.18f - .30f) * clarityRatio(clarity)) * 1.15f).coerceIn(0f, 1f)

    /** 六条方向散射带在磨砂档更柔和、稍强；清晰档减弱宽带存在感。 */
    fun scatteringIntensity(clarity: Int, darkSurface: Boolean): Float {
        val matte = 1f - clarityRatio(clarity)
        return (if (darkSurface) .68f + .24f * matte else .72f + .28f * matte) * 1.10f
    }

    /** 窄镜面高光随清晰度增强，深色底能承载更亮的反光。 */
    fun specularIntensity(clarity: Int, darkSurface: Boolean): Float {
        val ratio = clarityRatio(clarity)
        return (if (darkSurface) .22f + .78f * ratio else .18f + .56f * ratio) * 1.20f
    }

    fun broadBandScale(index: Int): Float = broadBandScales[index]

    private fun clarityRatio(clarity: Int): Float = clarity.coerceIn(0, 100) / 100f

    private fun shortSideLimit(shortSideDp: Float, fraction: Float): Float {
        if (shortSideDp.isNaN() || shortSideDp <= 0f) return 0f
        return shortSideDp * fraction
    }
}
