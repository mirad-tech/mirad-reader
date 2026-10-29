package com.mirad.reader.ui.common

/**
 * 玻璃边缘的纯参数模型。绘制仍由 Compose Brush 完成，不模拟真实光学折射。
 */
internal object GlassEdgeProfile {
    const val BROAD_BAND_COUNT = 1

    private val broadBandScales = floatArrayOf(.32f)

    /** 窄散射过渡保留薄片轮廓，不再堆叠宽带模拟厚度。 */
    fun bandWidthDp(clarity: Int, shortSideDp: Float): Float {
        val limit = shortSideLimit(shortSideDp, .12f)
        if (limit <= 0f) return 0f
        val ratio = clarityRatio(clarity)
        val requested = 3.2f + (1.8f - 3.2f) * ratio
        return minOf(requested, limit)
    }

    /** 单条暗轮廓线随清晰度略收窄，并为极矮面板保留边距。 */
    fun contourWidthDp(clarity: Int, shortSideDp: Float): Float {
        val limit = shortSideLimit(shortSideDp, .05f)
        if (limit <= 0f) return 0f
        val ratio = clarityRatio(clarity)
        return minOf(.6f + (.45f - .6f) * ratio, limit)
    }

    /** 窄镜面线比散射带更细，并在清晰档进一步收窄。 */
    fun specularWidthDp(clarity: Int, shortSideDp: Float): Float {
        val limit = shortSideLimit(shortSideDp, .04f)
        if (limit <= 0f) return 0f
        val ratio = clarityRatio(clarity)
        return minOf(1f + (.65f - 1f) * ratio, limit)
    }

    /** 磨砂时暗轮廓更实，清晰时让窄镜面线承担更多锐度。 */
    fun contourAlpha(clarity: Int): Float = .065f - .025f * clarityRatio(clarity)

    /** 磨砂档略加强窄散射，清晰档保留轻薄的镜面线。 */
    fun scatteringIntensity(clarity: Int, darkSurface: Boolean): Float {
        val matte = 1f - clarityRatio(clarity)
        return if (darkSurface) .45f + .15f * matte else .35f + .12f * matte
    }

    /** 窄镜面高光随清晰度增强，深色底能承载更亮的反光。 */
    fun specularIntensity(clarity: Int, darkSurface: Boolean): Float {
        val ratio = clarityRatio(clarity)
        return if (darkSurface) .42f + .26f * ratio else .30f + .18f * ratio
    }

    fun broadBandScale(index: Int): Float = broadBandScales[index]

    private fun clarityRatio(clarity: Int): Float = clarity.coerceIn(0, 100) / 100f

    private fun shortSideLimit(shortSideDp: Float, fraction: Float): Float {
        if (shortSideDp.isNaN() || shortSideDp <= 0f) return 0f
        return shortSideDp * fraction
    }
}
