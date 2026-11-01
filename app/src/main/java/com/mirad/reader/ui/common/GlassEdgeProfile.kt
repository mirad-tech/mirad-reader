package com.mirad.reader.ui.common

/**
 * 玻璃边缘的纯参数模型。绘制由 Compose Brush 完成；硬件路径另有 RuntimeShader 做背景弯折，
 * 这里只负责"看得见的厚度"：散射过渡、内侧暗部、受光高光三条线。
 *
 * 三个宽度都按短边限幅，小胶囊上不会铺满整个表面；三条线都随清晰度收窄，
 * 0 档最朦胧时边缘更软，100 档零模糊时更锐，但任何一档都不允许变成整圈等亮白框。
 */
internal object GlassEdgeProfile {
    /** 散射带最多两条：贴边一条窄的，外面一条更宽更弱的过渡。 */
    const val BROAD_BAND_COUNT = 2

    private val broadBandScales = floatArrayOf(.34f, .92f)

    /** 窄散射过渡：厚度在这里连续落到中心底色，不再只有一条硬边。 */
    fun bandWidthDp(clarity: Int, shortSideDp: Float): Float {
        val limit = shortSideLimit(shortSideDp, .12f)
        if (limit <= 0f) return 0f
        val ratio = clarityRatio(clarity)
        val requested = 3.4f + (1.9f - 3.4f) * ratio
        return minOf(requested, limit)
    }

    /** 内侧暗部：比散射带更宽一点，负责"玻璃有厚度"的背光面。 */
    fun thicknessWidthDp(clarity: Int, shortSideDp: Float): Float {
        val limit = shortSideLimit(shortSideDp, .18f)
        if (limit <= 0f) return 0f
        val ratio = clarityRatio(clarity)
        val requested = bandWidthDp(clarity, shortSideDp) * (1.7f - .25f * ratio)
        return minOf(requested, limit)
    }

    /** 单条暗轮廓线已经收成发丝级，只用来定位轮廓，不再靠它表达厚度。 */
    fun contourWidthDp(clarity: Int, shortSideDp: Float): Float {
        val limit = shortSideLimit(shortSideDp, .05f)
        if (limit <= 0f) return 0f
        val ratio = clarityRatio(clarity)
        return minOf(.55f + (.38f - .55f) * ratio, limit)
    }

    /** 受光面的亮线比散射带更细；清晰档进一步收窄，避免高清晰时出现塑料亮边。 */
    fun specularWidthDp(clarity: Int, shortSideDp: Float): Float {
        val limit = shortSideLimit(shortSideDp, .04f)
        if (limit <= 0f) return 0f
        val ratio = clarityRatio(clarity)
        return minOf(1.05f + (.7f - 1.05f) * ratio, limit)
    }

    /**
     * 亮线外侧的柔光：宽度约为亮线的两倍、透明度更低，让高光有圆角弧面感，
     * 而不是一条等宽的 1px 白边。
     */
    fun glowWidthDp(clarity: Int, shortSideDp: Float): Float {
        val limit = shortSideLimit(shortSideDp, .07f)
        if (limit <= 0f) return 0f
        return minOf(specularWidthDp(clarity, shortSideDp) * 2.6f, limit)
    }

    /** 磨砂时暗轮廓更实，清晰时让窄镜面线承担更多锐度。 */
    fun contourAlpha(clarity: Int): Float = .048f - .018f * clarityRatio(clarity)

    /** 磨砂档略加强窄散射，清晰档保留轻薄的镜面线。 */
    fun scatteringIntensity(clarity: Int, darkSurface: Boolean): Float {
        val matte = 1f - clarityRatio(clarity)
        return if (darkSurface) .45f + .15f * matte else .35f + .12f * matte
    }

    /** 背光面的内侧暗部：磨砂档更明显，清晰档收薄但保留，避免高清晰时边缘发虚。 */
    fun thicknessIntensity(clarity: Int, darkSurface: Boolean): Float {
        val matte = 1f - clarityRatio(clarity)
        return if (darkSurface) .30f + .10f * matte else .24f + .10f * matte
    }

    /** 窄镜面高光随清晰度增强，深色底能承载更亮的反光。 */
    fun specularIntensity(clarity: Int, darkSurface: Boolean): Float {
        val ratio = clarityRatio(clarity)
        return if (darkSurface) .40f + .24f * ratio else .30f + .18f * ratio
    }

    /** 柔光强度低于亮线，保证"一到两个层次"而不是一圈白框。 */
    fun glowIntensity(clarity: Int, darkSurface: Boolean): Float =
        specularIntensity(clarity, darkSurface) * .42f

    fun broadBandScale(index: Int): Float = broadBandScales[index]

    private fun clarityRatio(clarity: Int): Float = clarity.coerceIn(0, 100) / 100f

    private fun shortSideLimit(shortSideDp: Float, fraction: Float): Float {
        if (shortSideDp.isNaN() || shortSideDp <= 0f) return 0f
        return shortSideDp * fraction
    }
}
