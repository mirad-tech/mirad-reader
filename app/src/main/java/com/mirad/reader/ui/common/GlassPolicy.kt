package com.mirad.reader.ui.common

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

@Immutable
/**
 * 玻璃外观。
 *
 * 用户可见语义已从"透明度百分比"改为两个独立控制：
 * - [enabled]：磨砂玻璃开关。关闭后走完全不透明的稳定表面，不再做模糊、折射与背景分析。
 * - [clarityPercent]：清晰度，0 = 最朦胧，100 = 最清晰；它实际改变模糊半径，两端都不承担"关闭"含义。
 *
 * [transparencyPercent] 只是给旧版 APK 的兼容投影（关闭写 0，开启按清晰度单调投影 1..100）。
 * 旧值控制的是遮罩浓度，新值控制的是模糊量，两者视觉不保证严格相等，新记录才是事实源。
 */
data class GlassAppearance(
    val enabled: Boolean = true,
    val clarityPercent: Int = DEFAULT_CLARITY
) {
    /** 兼容投影：只用于写回旧键，不得反向覆盖新清晰度。 */
    val transparencyPercent: Int
        get() = if (!enabled) 0 else clarityPercent.coerceIn(MIN_CLARITY, MAX_CLARITY).coerceAtLeast(1)

    val transparencyFraction: Float get() = transparencyPercent / 100f

    fun normalized(): GlassAppearance = GlassAppearance(enabled, clarityPercent.coerceIn(MIN_CLARITY, MAX_CLARITY))

    companion object {
        const val MIN_CLARITY = 0
        const val MAX_CLARITY = 100

        /** 校准点：仓库现有观感对应的清晰度，改造后默认值保持同一档。 */
        const val DEFAULT_CLARITY = 60
        const val DEFAULT_PERCENT = DEFAULT_CLARITY

        val DEFAULT = GlassAppearance(enabled = true, clarityPercent = DEFAULT_CLARITY)

        /** 旧键迁移：0 → 关闭（清晰度取校准默认），>0 → 开启并把旧值当作清晰度初值。 */
        fun fromLegacyPercent(raw: String?): GlassAppearance {
            val parsed = raw?.toIntOrNull() ?: return DEFAULT
            return if (parsed <= 0) {
                GlassAppearance(enabled = false, clarityPercent = DEFAULT_CLARITY)
            } else {
                GlassAppearance(enabled = true, clarityPercent = parsed.coerceIn(1, MAX_CLARITY))
            }
        }

        /** 兼容旧调用：把百分比当成清晰度读（仅用于历史测试与预览参数）。 */
        fun parse(raw: String?): GlassAppearance = fromLegacyPercent(raw)
    }
}

/** 玻璃外观的版本化记录（偏好表 JSON）。字段缺失或损坏都回退默认，且不触发旧值重新迁移。 */
@kotlinx.serialization.Serializable
private data class GlassAppearanceRecord(
    val enabled: Boolean = true,
    val clarityPercent: Int = GlassAppearance.DEFAULT_CLARITY
)

internal object GlassAppearanceJson {
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decode(raw: String?): GlassAppearance {
        if (raw.isNullOrBlank()) return GlassAppearance.DEFAULT
        return runCatching {
            val record = json.decodeFromString(GlassAppearanceRecord.serializer(), raw)
            GlassAppearance(record.enabled, record.clarityPercent).normalized()
        }.getOrDefault(GlassAppearance.DEFAULT)
    }

    fun encode(appearance: GlassAppearance): String =
        json.encodeToString(
            GlassAppearanceRecord.serializer(),
            GlassAppearanceRecord(appearance.enabled, appearance.normalized().clarityPercent)
        )
}

interface GlassAppearanceController {
    val saveFailed: Boolean get() = false
    fun update(appearance: GlassAppearance)
    fun commit()
    fun reset()
    fun retrySave() = commit()
}

val LocalGlassAppearance = staticCompositionLocalOf { GlassAppearance.DEFAULT }
val LocalGlassSaveFailed = staticCompositionLocalOf { false }
val LocalGlassAppearanceController = staticCompositionLocalOf<GlassAppearanceController> {
    object : GlassAppearanceController {
        override fun update(appearance: GlassAppearance) {}
        override fun commit() {}
        override fun reset() {}
    }
}

internal fun glassBlurAllowed(apiLevel: Int, powerSave: Boolean, forceFallback: Boolean): Boolean =
    apiLevel >= 31 && !powerSave && !forceFallback

/** Bound reader sampling cost even on tablet/foldable viewports. */
internal fun glassSnapshotDimensions(width: Int, height: Int): Pair<Int, Int>? {
    if (width <= 0 || height <= 0) return null
    val scale = minOf(.25f, 640f / maxOf(width, height))
    return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
}

/**
 * 局部背景特征分析数据（S3 & T1）：
 * 1. 所有标量亮度字段（p10、p50、p90、minLuminance、maxLuminance）均为已转换的 WCAG 线性相对亮度 [0, 1]，
 *    消费端严禁再次执行 linearSrgb 转换。
 * 2. 所有 RGB 通道字段（adverseDarkR/G/B、adverseLightR/G/B、ambientR/G/B、cellR/G/B）均为原始 sRGB 空间 [0, 1]，
 *    保留真实彩色背景通道，避免将彩色背景还原为等亮度灰色导致 sRGB 通道混合偏差。
 */
@Immutable
data class BackdropMetrics(
    val p10: Float = 0.5f,
    val p50: Float = 0.5f,
    val p90: Float = 0.5f,
    val span: Float = 0f,
    val edgeIntensity: Float = 0f,
    val minLuminance: Float = p10,
    val maxLuminance: Float = p90,
    val sampled: Boolean = true,
    val hasRgbSamples: Boolean = false,
    val adverseDarkR: Float = srgbFromLinear(min(p10, minLuminance)),
    val adverseDarkG: Float = srgbFromLinear(min(p10, minLuminance)),
    val adverseDarkB: Float = srgbFromLinear(min(p10, minLuminance)),
    val adverseLightR: Float = srgbFromLinear(max(p90, maxLuminance)),
    val adverseLightG: Float = srgbFromLinear(max(p90, maxLuminance)),
    val adverseLightB: Float = srgbFromLinear(max(p90, maxLuminance)),
    val ambientLightModeR: Float = srgbFromLinear(p50),
    val ambientLightModeG: Float = srgbFromLinear(p50),
    val ambientLightModeB: Float = srgbFromLinear(p50),
    val ambientDarkModeR: Float = srgbFromLinear(p50),
    val ambientDarkModeG: Float = srgbFromLinear(p50),
    val ambientDarkModeB: Float = srgbFromLinear(p50),
    val cellR: FloatArray? = null,
    val cellG: FloatArray? = null,
    val cellB: FloatArray? = null,
    val cellCols: Int = 0,
    val cellRows: Int = 0,
    val cellDarkModeR: FloatArray? = null,
    val cellDarkModeG: FloatArray? = null,
    val cellDarkModeB: FloatArray? = null
) {
    constructor(
        p10: Float,
        p50: Float,
        p90: Float,
        span: Float,
        edgeIntensity: Float
    ) : this(
        p10 = p10,
        p50 = p50,
        p90 = p90,
        span = span,
        edgeIntensity = edgeIntensity,
        minLuminance = p10,
        maxLuminance = p90,
        sampled = true,
        hasRgbSamples = false
    )

    constructor(
        p10: Float,
        p50: Float,
        p90: Float,
        span: Float,
        edgeIntensity: Float,
        minLuminance: Float,
        maxLuminance: Float,
        sampled: Boolean
    ) : this(
        p10 = p10,
        p50 = p50,
        p90 = p90,
        span = span,
        edgeIntensity = edgeIntensity,
        minLuminance = minLuminance,
        maxLuminance = maxLuminance,
        sampled = sampled,
        hasRgbSamples = false
    )

    companion object {
        val NEUTRAL = BackdropMetrics(
            p10 = 0.5f,
            p50 = 0.5f,
            p90 = 0.5f,
            span = 0f,
            edgeIntensity = 0f,
            minLuminance = 0.5f,
            maxLuminance = 0.5f,
            sampled = false,
            hasRgbSamples = false
        )
    }
}

/**
 * 玻璃质感计算结果（S3 & T1）：
 * - opacity：针对局部最不利背景区域满足 >= 4.5:1 对比度的峰值保护不透明度
 * - ambientOpacity：针对整块玻璃主体背景的全局底色不透明度（100% 偏好下在温和主体背景上显著降低整块蒙层）
 * - cellProtectedOpacities：按 8x8 局部网格求解的局部文字保护不透明度分布（仅在需要额外保护的局部区域叠加）
 */
@Immutable
data class GlassParams(
    val opacity: Float,
    val blurRadius: Dp,
    val highlightAlpha: Float,
    val borderAlpha: Float,
    val ambientOpacity: Float = opacity,
    val cellProtectedOpacities: FloatArray? = null,
    val cellCols: Int = 0,
    val cellRows: Int = 0,
    val rawCellOpacities: FloatArray? = null
) {
    constructor(
        opacity: Float,
        blurRadius: Dp,
        highlightAlpha: Float,
        borderAlpha: Float
    ) : this(
        opacity = opacity,
        blurRadius = blurRadius,
        highlightAlpha = highlightAlpha,
        borderAlpha = borderAlpha,
        ambientOpacity = opacity,
        cellProtectedOpacities = null,
        cellCols = 0,
        cellRows = 0,
        rawCellOpacities = null
    )
}

internal fun linearSrgb(s: Float): Float {
    val clamped = s.coerceIn(0f, 1f)
    return if (clamped <= 0.04045f) {
        clamped / 12.92f
    } else {
        ((clamped + 0.055) / 1.055).pow(2.4).toFloat()
    }
}

internal fun srgbFromLinear(linear: Float): Float {
    val clamped = linear.coerceIn(0f, 1f)
    return if (clamped <= 0.0031308f) {
        clamped * 12.92f
    } else {
        (1.055 * clamped.toDouble().pow(1.0 / 2.4) - 0.055).toFloat().coerceIn(0f, 1f)
    }
}

internal fun relativeLuminanceFromSrgb(r: Float, g: Float, b: Float): Float =
    0.2126f * linearSrgb(r) + 0.7152f * linearSrgb(g) + 0.0722f * linearSrgb(b)

internal fun contrastRatio(lumA: Float, lumB: Float): Float {
    val lighter = max(lumA, lumB)
    val darker = min(lumA, lumB)
    return (lighter + 0.05f) / (darker + 0.05f)
}

/**
 * 真实主题色彩常量（与 ui/theme/Color.kt 严格对应）：
 * - 浅色：Paper (#FCFBF7), InkText (#191D1B), InkTextMuted (#4C554F)
 * - 深色：Night (#15181A), NightText (#E3E6E2), NightTextMuted (#B7BFB9)
 */
private object GlassThemePalette {
    const val LIGHT_SURFACE_R = 252f / 255f
    const val LIGHT_SURFACE_G = 251f / 255f
    const val LIGHT_SURFACE_B = 247f / 255f
    val LIGHT_SURFACE_LUM = relativeLuminanceFromSrgb(LIGHT_SURFACE_R, LIGHT_SURFACE_G, LIGHT_SURFACE_B)
    val LIGHT_PRIMARY_TEXT_LUM = relativeLuminanceFromSrgb(25f / 255f, 29f / 255f, 27f / 255f)
    val LIGHT_SECONDARY_TEXT_LUM = relativeLuminanceFromSrgb(76f / 255f, 85f / 255f, 79f / 255f)

    const val DARK_SURFACE_R = 21f / 255f
    const val DARK_SURFACE_G = 24f / 255f
    const val DARK_SURFACE_B = 26f / 255f
    val DARK_SURFACE_LUM = relativeLuminanceFromSrgb(DARK_SURFACE_R, DARK_SURFACE_G, DARK_SURFACE_B)
    val DARK_PRIMARY_TEXT_LUM = relativeLuminanceFromSrgb(227f / 255f, 230f / 255f, 226f / 255f)
    val DARK_SECONDARY_TEXT_LUM = relativeLuminanceFromSrgb(183f / 255f, 191f / 255f, 185f / 255f)

    const val TARGET_CONTRAST = 4.65f
}

/**
 * 分析位图局部区域的亮度分布。
 * 采样网格限制在最多 8x8，避免在主线程中产生重计算开销。
 * 杜绝对 Bitmap.Config.HARDWARE 直接调用 getPixel()。
 */
internal fun analyzeLocalBackdrop(
    bitmap: Bitmap?,
    glassRectInBitmap: Rect?
): BackdropMetrics {
    if (bitmap == null || bitmap.isRecycled || glassRectInBitmap == null) {
        return BackdropMetrics.NEUTRAL
    }
    val bw = bitmap.width
    val bh = bitmap.height
    if (bw <= 0 || bh <= 0) return BackdropMetrics.NEUTRAL

    val isHardware = try {
        bitmap.config?.name == "HARDWARE"
    } catch (t: Throwable) {
        false
    }

    val safeBitmap = if (isHardware) {
        runCatching {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }.getOrNull() ?: return BackdropMetrics.NEUTRAL
    } else {
        bitmap
    }

    try {
        return analyzeLocalBackdrop(bw, bh, glassRectInBitmap) { x, y ->
            safeBitmap.getPixel(x, y)
        }
    } finally {
        if (safeBitmap !== bitmap && !safeBitmap.isRecycled) {
            safeBitmap.recycle()
        }
    }
}

internal fun analyzeLocalBackdrop(
    bw: Int,
    bh: Int,
    glassRectInBitmap: Rect?,
    pixelAt: (Int, Int) -> Int
): BackdropMetrics {
    if (bw <= 0 || bh <= 0 || glassRectInBitmap == null) {
        return BackdropMetrics.NEUTRAL
    }
    val left = max(0, min(bw - 1, glassRectInBitmap.left.toInt()))
    val top = max(0, min(bh - 1, glassRectInBitmap.top.toInt()))
    val right = max(left + 1, min(bw, glassRectInBitmap.right.toInt()))
    val bottom = max(top + 1, min(bh, glassRectInBitmap.bottom.toInt()))
    val rectW = right - left
    val rectH = bottom - top
    if (rectW <= 0 || rectH <= 0) return BackdropMetrics.NEUTRAL

    val cols = min(8, max(2, rectW))
    val rows = min(8, max(2, rectH))
    val count = cols * rows
    val gridLuminances = FloatArray(count)
    val gridR = FloatArray(count)
    val gridG = FloatArray(count)
    val gridB = FloatArray(count)
    val gridDarkR = FloatArray(count)
    val gridDarkG = FloatArray(count)
    val gridDarkB = FloatArray(count)
    var idx = 0

    val stepX = rectW.toFloat() / cols
    val stepY = rectH.toFloat() / rows
    val isRealisticBitmap = rectW >= 24 && rectH >= 12
    val probeAlpha = 0.70f

    // 真实位图采样下预读局部像素缓冲，避免在模糊积分与实心探针中重复调用 pixelAt / getPixel
    val localPixels = if (isRealisticBitmap && rectW * rectH <= 640 * 640) {
        IntArray(rectW * rectH).also { buf ->
            var pIdx = 0
            for (py in top until bottom) {
                for (px in left until right) {
                    buf[pIdx++] = pixelAt(px, py)
                }
            }
        }
    } else {
        null
    }

    fun readPixel(x: Int, y: Int): Int {
        val cx = x.coerceIn(left, right - 1)
        val cy = y.coerceIn(top, bottom - 1)
        return if (localPixels != null) {
            localPixels[(cy - top) * rectW + (cx - left)]
        } else {
            pixelAt(cx, cy)
        }
    }

    val subOffsets = floatArrayOf(0.12f, 0.37f, 0.63f, 0.88f)
    val subR = if (isRealisticBitmap) min(3, max(2, min(rectW, rectH) / 36)) else 0
    val hasCoherentPatch = BooleanArray(count)

    for (r in 0 until rows) {
        val cellY0 = top + r * stepY
        val cellY1 = top + (r + 1) * stepY
        val cy = min(bh - 1, (top + (r + 0.5f) * stepY).toInt())
        for (c in 0 until cols) {
            val cellX0 = left + c * stepX
            val cellX1 = left + (c + 1) * stepX
            val cx = min(bw - 1, (left + (c + 0.5f) * stepX).toInt())

            if (!isRealisticBitmap) {
                val pixel = readPixel(cx, cy)
                val red = (pixel shr 16 and 0xFF) / 255f
                val green = (pixel shr 8 and 0xFF) / 255f
                val blue = (pixel and 0xFF) / 255f
                val lum = relativeLuminanceFromSrgb(red, green, blue)
                gridR[idx] = red
                gridG[idx] = green
                gridB[idx] = blue
                gridDarkR[idx] = red
                gridDarkG[idx] = green
                gridDarkB[idx] = blue
                gridLuminances[idx] = lum
                hasCoherentPatch[idx] = true
                idx++
            } else {
                // 1. 计算覆盖整个单元及真实 BlurEffect 扩散邻域（±0.40 * step）的光学模糊积分均值
                val bx0 = max(left, (cellX0 - 0.40f * stepX).toInt())
                val bx1 = min(right - 1, (cellX1 + 0.40f * stepX).toInt())
                val by0 = max(top, (cellY0 - 0.40f * stepY).toInt())
                val by1 = min(bottom - 1, (cellY1 + 0.40f * stepY).toInt())
                val bwSpan = max(1, bx1 - bx0)
                val bhSpan = max(1, by1 - by0)
                val samplesX = min(8, bwSpan + 1)
                val samplesY = min(8, bhSpan + 1)
                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var sampleCount = 0
                for (syIdx in 0 until samplesY) {
                    val sy = by0 + (syIdx * bhSpan) / max(1, samplesY - 1)
                    for (sxIdx in 0 until samplesX) {
                        val sx = bx0 + (sxIdx * bwSpan) / max(1, samplesX - 1)
                        val px = readPixel(sx, sy)
                        sumR += (px shr 16 and 0xFF) / 255f
                        sumG += (px shr 8 and 0xFF) / 255f
                        sumB += (px and 0xFF) / 255f
                        sampleCount++
                    }
                }
                val invSamples = if (sampleCount > 0) 1f / sampleCount else 1f
                val blurR = (sumR * invSamples).coerceIn(0f, 1f)
                val blurG = (sumG * invSamples).coerceIn(0f, 1f)
                val blurB = (sumB * invSamples).coerceIn(0f, 1f)

                var foundPatch = false
                var bestLightR = blurR
                var bestLightG = blurG
                var bestLightB = blurB
                var bestLightMixLum = Float.POSITIVE_INFINITY

                var bestDarkR = blurR
                var bestDarkG = blurG
                var bestDarkB = blurB
                var bestDarkMixLum = Float.NEGATIVE_INFINITY

                // 2. 单元内 4x4 连续实心度探针（Coherent Solid Patch Probes）：
                // 检查每个 (2*subR+1)x(2*subR+1) 连续像素块（至少 5x5 采样像素 = 20x20 屏幕像素）。
                // 若块内全部连续像素通道跨度 <= 0.10（属于实心/准实心背景或色块，而非细碎文字笔画），
                // 则仅用实心探针集合确定该单元代表色，彻底避免文字笔画均值在深色模式下误触发局部黑块。
                for (fy in subOffsets) {
                    val py = (cellY0 + fy * stepY).toInt().coerceIn(top, bottom - 1)
                    val py0 = max(top, py - subR)
                    val py1 = min(bottom - 1, py + subR)
                    for (fx in subOffsets) {
                        val px = (cellX0 + fx * stepX).toInt().coerceIn(left, right - 1)
                        val px0 = max(left, px - subR)
                        val px1 = min(right - 1, px + subR)

                        var pSumR = 0f
                        var pSumG = 0f
                        var pSumB = 0f
                        var minR = 1f
                        var maxR = 0f
                        var minG = 1f
                        var maxG = 0f
                        var minB = 1f
                        var maxB = 0f
                        var pCount = 0

                        for (qy in py0..py1) {
                            for (qx in px0..px1) {
                                val p = readPixel(qx, qy)
                                val pr = (p shr 16 and 0xFF) / 255f
                                val pg = (p shr 8 and 0xFF) / 255f
                                val pb = (p and 0xFF) / 255f
                                pSumR += pr
                                pSumG += pg
                                pSumB += pb
                                if (pr < minR) minR = pr
                                if (pr > maxR) maxR = pr
                                if (pg < minG) minG = pg
                                if (pg > maxG) maxG = pg
                                if (pb < minB) minB = pb
                                if (pb > maxB) maxB = pb
                                pCount++
                            }
                        }
                        val patchSpan = max(maxR - minR, max(maxG - minG, maxB - minB))
                        if (pCount > 0 && patchSpan <= 0.10f) {
                            val invP = 1f / pCount
                            val pr = (pSumR * invP).coerceIn(0f, 1f)
                            val pg = (pSumG * invP).coerceIn(0f, 1f)
                            val pb = (pSumB * invP).coerceIn(0f, 1f)
                            foundPatch = true
                            val lMix = relativeLuminanceFromSrgb(
                                GlassThemePalette.LIGHT_SURFACE_R * probeAlpha + pr * (1f - probeAlpha),
                                GlassThemePalette.LIGHT_SURFACE_G * probeAlpha + pg * (1f - probeAlpha),
                                GlassThemePalette.LIGHT_SURFACE_B * probeAlpha + pb * (1f - probeAlpha)
                            )
                            if (lMix < bestLightMixLum) {
                                bestLightMixLum = lMix
                                bestLightR = pr
                                bestLightG = pg
                                bestLightB = pb
                            }
                            val dMix = relativeLuminanceFromSrgb(
                                GlassThemePalette.DARK_SURFACE_R * probeAlpha + pr * (1f - probeAlpha),
                                GlassThemePalette.DARK_SURFACE_G * probeAlpha + pg * (1f - probeAlpha),
                                GlassThemePalette.DARK_SURFACE_B * probeAlpha + pb * (1f - probeAlpha)
                            )
                            if (dMix > bestDarkMixLum) {
                                bestDarkMixLum = dMix
                                bestDarkR = pr
                                bestDarkG = pg
                                bestDarkB = pb
                            }
                        }
                    }
                }

                hasCoherentPatch[idx] = foundPatch
                gridR[idx] = bestLightR
                gridG[idx] = bestLightG
                gridB[idx] = bestLightB
                gridDarkR[idx] = bestDarkR
                gridDarkG[idx] = bestDarkG
                gridDarkB[idx] = bestDarkB
                gridLuminances[idx] = if (foundPatch) {
                    relativeLuminanceFromSrgb(bestLightR, bestLightG, bestLightB)
                } else {
                    relativeLuminanceFromSrgb(blurR, blurG, blurB)
                }
                idx++
            }
        }
    }

    // 对仅包含高频文字笔画而无实心色块的单元，回退至页面已识别的主实心基底色（Substrate Color）
    if (isRealisticBitmap) {
        val coherentIndices = (0 until count).filter { hasCoherentPatch[it] }
        if (coherentIndices.isNotEmpty() && coherentIndices.size < count) {
            val sortedByLum = coherentIndices.sortedBy { gridLuminances[it] }
            val medianIdx = sortedByLum[sortedByLum.size / 2]
            val subLR = gridR[medianIdx]
            val subLG = gridG[medianIdx]
            val subLB = gridB[medianIdx]
            val subDR = gridDarkR[medianIdx]
            val subDG = gridDarkG[medianIdx]
            val subDB = gridDarkB[medianIdx]
            val subLum = gridLuminances[medianIdx]
            for (i in 0 until count) {
                if (!hasCoherentPatch[i]) {
                    gridR[i] = subLR
                    gridG[i] = subLG
                    gridB[i] = subLB
                    gridDarkR[i] = subDR
                    gridDarkG[i] = subDG
                    gridDarkB[i] = subDB
                    gridLuminances[i] = subLum
                }
            }
        }
    }

    // 保留原始行列网格计算空间边缘复杂度（F5：在排序前计算相邻差）
    var diffSum = 0f
    var diffPairs = 0
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            val cur = gridLuminances[r * cols + c]
            if (c + 1 < cols) {
                diffSum += abs(cur - gridLuminances[r * cols + (c + 1)])
                diffPairs++
            }
            if (r + 1 < rows) {
                diffSum += abs(cur - gridLuminances[(r + 1) * cols + c])
                diffPairs++
            }
        }
    }
    val edgeIntensity = if (diffPairs > 0) (diffSum / diffPairs).coerceIn(0f, 1f) else 0f

    // S3：找出在真实 sRGB 通道混合下对浅色模式最不利（合成后最暗）和对深色模式最不利（合成后最亮）的真实 RGB 样本
    var worstDarkIdx = 0
    var worstDarkProbeLum = Float.MAX_VALUE
    var worstLightIdx = 0
    var worstLightProbeLum = -Float.MAX_VALUE
    var worstDarkCellLum = 1f
    var worstLightCellLum = 0f
    for (i in 0 until count) {
        val lr = gridR[i]
        val lg = gridG[i]
        val lb = gridB[i]
        val lCellLum = relativeLuminanceFromSrgb(lr, lg, lb)
        if (lCellLum < worstDarkCellLum) worstDarkCellLum = lCellLum
        val lightMixLum = relativeLuminanceFromSrgb(
            GlassThemePalette.LIGHT_SURFACE_R * probeAlpha + lr * (1f - probeAlpha),
            GlassThemePalette.LIGHT_SURFACE_G * probeAlpha + lg * (1f - probeAlpha),
            GlassThemePalette.LIGHT_SURFACE_B * probeAlpha + lb * (1f - probeAlpha)
        )
        if (lightMixLum < worstDarkProbeLum) {
            worstDarkProbeLum = lightMixLum
            worstDarkIdx = i
        }

        val dr = gridDarkR[i]
        val dg = gridDarkG[i]
        val db = gridDarkB[i]
        val dCellLum = relativeLuminanceFromSrgb(dr, dg, db)
        if (dCellLum > worstLightCellLum) worstLightCellLum = dCellLum
        val darkMixLum = relativeLuminanceFromSrgb(
            GlassThemePalette.DARK_SURFACE_R * probeAlpha + dr * (1f - probeAlpha),
            GlassThemePalette.DARK_SURFACE_G * probeAlpha + dg * (1f - probeAlpha),
            GlassThemePalette.DARK_SURFACE_B * probeAlpha + db * (1f - probeAlpha)
        )
        if (darkMixLum > worstLightProbeLum) {
            worstLightProbeLum = darkMixLum
            worstLightIdx = i
        }
    }

    // 按亮度排序索引，提取分位数与代表整块玻璃主体背景的真实 RGB（浅色取 p30，深色取 p70）
    val order = IntArray(count) { it }
    for (i in 0 until count - 1) {
        var minIdx = i
        for (j in i + 1 until count) {
            if (gridLuminances[order[j]] < gridLuminances[order[minIdx]]) {
                minIdx = j
            }
        }
        if (minIdx != i) {
            val tmp = order[i]
            order[i] = order[minIdx]
            order[minIdx] = tmp
        }
    }

    val minLum = min(gridLuminances[order[0]], worstDarkCellLum).coerceIn(0f, 1f)
    val maxLum = max(gridLuminances[order[count - 1]], worstLightCellLum).coerceIn(0f, 1f)
    val p10 = gridLuminances[order[(count * 0.10f).toInt().coerceIn(0, count - 1)]].coerceIn(0f, 1f)
    val p50 = gridLuminances[order[(count * 0.50f).toInt().coerceIn(0, count - 1)]].coerceIn(0f, 1f)
    val p90 = gridLuminances[order[(count * 0.90f).toInt().coerceIn(0, count - 1)]].coerceIn(0f, 1f)
    val span = (p90 - p10).coerceIn(0f, 1f)

    val ambientLightIdx = order[(count * 0.30f).toInt().coerceIn(0, count - 1)]
    val ambientDarkIdx = order[(count * 0.70f).toInt().coerceIn(0, count - 1)]

    return BackdropMetrics(
        p10 = p10,
        p50 = p50,
        p90 = p90,
        span = span,
        edgeIntensity = edgeIntensity,
        minLuminance = minLum,
        maxLuminance = maxLum,
        sampled = true,
        hasRgbSamples = true,
        adverseDarkR = gridR[worstDarkIdx],
        adverseDarkG = gridG[worstDarkIdx],
        adverseDarkB = gridB[worstDarkIdx],
        adverseLightR = gridDarkR[worstLightIdx],
        adverseLightG = gridDarkG[worstLightIdx],
        adverseLightB = gridDarkB[worstLightIdx],
        ambientLightModeR = gridR[ambientLightIdx],
        ambientLightModeG = gridG[ambientLightIdx],
        ambientLightModeB = gridB[ambientLightIdx],
        ambientDarkModeR = gridDarkR[ambientDarkIdx],
        ambientDarkModeG = gridDarkG[ambientDarkIdx],
        ambientDarkModeB = gridDarkB[ambientDarkIdx],
        cellR = gridR,
        cellG = gridG,
        cellB = gridB,
        cellCols = cols,
        cellRows = rows,
        cellDarkModeR = gridDarkR,
        cellDarkModeG = gridDarkG,
        cellDarkModeB = gridDarkB
    )
}

/**
 * S3：直接針對真实背景 sRGB 通道 (bgR, bgG, bgB) 校验给定不透明度 alpha 是否同时满足：
 * 1. 底部无高光 (h = 0)、中部半高光 (h = 0.5 * topHighlightAlpha)、顶部最大高光 (h = topHighlightAlpha) 三个位置；
 * 2. 主文字与次要文字两个前景层级；
 * 3. 真实 sRGB 通道合成与 WCAG 线性相对亮度混合两种计算模型；
 * 对比度均不低于 4.52:1（留 0.02 安全余量保证 >= 4.5:1）。
 */
internal fun meetsThemeContrastForRgb(
    alpha: Float,
    isDark: Boolean,
    bgR: Float,
    bgG: Float,
    bgB: Float,
    topHighlightAlpha: Float
): Boolean {
    val a = alpha.coerceIn(0f, 1f)
    val r0 = bgR.coerceIn(0f, 1f)
    val g0 = bgG.coerceIn(0f, 1f)
    val b0 = bgB.coerceIn(0f, 1f)
    val bgLum = relativeLuminanceFromSrgb(r0, g0, b0)

    val surfR: Float
    val surfG: Float
    val surfB: Float
    val surfLum: Float
    val primaryLum: Float
    val secondaryLum: Float

    if (isDark) {
        surfR = GlassThemePalette.DARK_SURFACE_R
        surfG = GlassThemePalette.DARK_SURFACE_G
        surfB = GlassThemePalette.DARK_SURFACE_B
        surfLum = GlassThemePalette.DARK_SURFACE_LUM
        primaryLum = GlassThemePalette.DARK_PRIMARY_TEXT_LUM
        secondaryLum = GlassThemePalette.DARK_SECONDARY_TEXT_LUM
    } else {
        surfR = GlassThemePalette.LIGHT_SURFACE_R
        surfG = GlassThemePalette.LIGHT_SURFACE_G
        surfB = GlassThemePalette.LIGHT_SURFACE_B
        surfLum = GlassThemePalette.LIGHT_SURFACE_LUM
        primaryLum = GlassThemePalette.LIGHT_PRIMARY_TEXT_LUM
        secondaryLum = GlassThemePalette.LIGHT_SECONDARY_TEXT_LUM
    }

    val baseR = surfR * a + r0 * (1f - a)
    val baseG = surfG * a + g0 * (1f - a)
    val baseB = surfB * a + b0 * (1f - a)
    val bottomSrgbLum = relativeLuminanceFromSrgb(baseR, baseG, baseB)
    val bottomLinearLum = surfLum * a + bgLum * (1f - a)

    val target = GlassThemePalette.TARGET_CONTRAST
    if (contrastRatio(primaryLum, bottomSrgbLum) < target) return false
    if (contrastRatio(primaryLum, bottomLinearLum) < target) return false
    if (contrastRatio(secondaryLum, bottomSrgbLum) < target) return false
    if (contrastRatio(secondaryLum, bottomLinearLum) < target) return false

    val hMax = topHighlightAlpha.coerceIn(0f, 1f)
    if (hMax > 0f) {
        for (h in floatArrayOf(hMax * 0.5f, hMax)) {
            val topR = 1f * h + baseR * (1f - h)
            val topG = 1f * h + baseG * (1f - h)
            val topB = 1f * h + baseB * (1f - h)
            val topSrgbLum = relativeLuminanceFromSrgb(topR, topG, topB)
            val topLinearLum = 1f * h + bottomLinearLum * (1f - h)

            if (contrastRatio(primaryLum, topSrgbLum) < target) return false
            if (contrastRatio(primaryLum, topLinearLum) < target) return false
            if (contrastRatio(secondaryLum, topSrgbLum) < target) return false
            if (contrastRatio(secondaryLum, topLinearLum) < target) return false
        }
    }
    return true
}

internal fun solveRequiredAlphaForRgbContrast(
    nominalAlpha: Float,
    isDark: Boolean,
    bgR: Float,
    bgG: Float,
    bgB: Float,
    topHighlightAlpha: Float
): Float {
    val start = nominalAlpha.coerceIn(0f, 1f)
    if (meetsThemeContrastForRgb(start, isDark, bgR, bgG, bgB, topHighlightAlpha)) {
        return start
    }
    var low = start
    var high = 1f
    repeat(18) {
        val mid = (low + high) * 0.5f
        if (meetsThemeContrastForRgb(mid, isDark, bgR, bgG, bgB, topHighlightAlpha)) {
            high = mid
        } else {
            low = mid
        }
    }
    return high.coerceIn(start, 1f)
}

/**
 * 针对仅提供标量线性亮度（未携带真实 RGB 样本）的保守界校验：
 * 同时验证等亮度灰阶 (bgSrgb, bgSrgb, bgSrgb) 与单通道饱和色极点分配（B -> R -> G），
 * 确保任意具有该线性亮度的彩色背景均满足 >= 4.5:1。
 */
private fun meetsThemeContrastAtAlpha(
    alpha: Float,
    isDark: Boolean,
    backdropLinearLuminance: Float,
    topHighlightAlpha: Float
): Boolean {
    val bgLum = backdropLinearLuminance.coerceIn(0f, 1f)
    val bgSrgb = srgbFromLinear(bgLum)
    if (!meetsThemeContrastForRgb(alpha, isDark, bgSrgb, bgSrgb, bgSrgb, topHighlightAlpha)) {
        return false
    }
    val bLin = min(1f, bgLum / 0.0722f)
    val remAfterB = max(0f, bgLum - 0.0722f * bLin)
    val rLin = min(1f, remAfterB / 0.2126f)
    val remAfterR = max(0f, remAfterB - 0.2126f * rLin)
    val gLin = min(1f, remAfterR / 0.7152f)
    val satR = srgbFromLinear(rLin)
    val satG = srgbFromLinear(gLin)
    val satB = srgbFromLinear(bLin)
    return meetsThemeContrastForRgb(alpha, isDark, satR, satG, satB, topHighlightAlpha)
}

private fun solveRequiredAlphaForContrast(
    nominalAlpha: Float,
    isDark: Boolean,
    backdropLinearLuminance: Float,
    topHighlightAlpha: Float
): Float {
    val start = nominalAlpha.coerceIn(0f, 1f)
    if (meetsThemeContrastAtAlpha(start, isDark, backdropLinearLuminance, topHighlightAlpha)) {
        return start
    }
    var low = start
    var high = 1f
    repeat(18) {
        val mid = (low + high) * 0.5f
        if (meetsThemeContrastAtAlpha(mid, isDark, backdropLinearLuminance, topHighlightAlpha)) {
            high = mid
        } else {
            low = mid
        }
    }
    return high.coerceIn(start, 1f)
}

/**
 * 依据 PLAN 与 S3/T1 规范计算玻璃参数：
 * 1. 基础遮罩不透明度按 1 - 透明度/100 计算；在 60%–100% 高透区间，面板/深色固定厚度补偿随高透明端平滑减弱（T1.1）
 * 2. 减轻浅色高光白雾，并在绘制时集中于顶部边缘（T1.1）
 * 3. 基于真实 RGB 样本求解最不利区域峰值不透明度 opacity 与整块主体背景 ambientOpacity，以及 8x8 局部文字保护场（S3 & T1.2 & T1.3）
 * 4. 模糊半径在 12-24dp 随复杂度调整，面板比工具栏略厚
 * 5. 0% 透明度跳过模糊，不透明度为 1f；无法模糊或降级模式下使用至少 94% 不透明度的安全底色
 */
internal fun computeGlassParams(
    transparencyPercent: Int,
    style: GlassStyle,
    isDark: Boolean,
    canBlur: Boolean,
    metrics: BackdropMetrics?,
    /** 清晰度 0..100；50 附近等于改造前的观感，100 走无模糊分支。 */
    clarityPercent: Int = 50
): GlassParams {
    val clampedTransparency = transparencyPercent.coerceIn(0, 100)
    if (clampedTransparency == 0) {
        return GlassParams(
            opacity = 1f,
            blurRadius = 0.dp,
            highlightAlpha = if (isDark) 0.025f else 0.08f,
            borderAlpha = if (isDark) 0.12f else 0.20f,
            ambientOpacity = 1f
        )
    }

    if (!canBlur) {
        return GlassParams(
            opacity = 0.94f,
            blurRadius = 0.dp,
            highlightAlpha = if (isDark) 0.025f else 0.10f,
            borderAlpha = if (isDark) 0.15f else 0.30f,
            ambientOpacity = 0.94f
        )
    }

    val baseAlpha = 1f - (clampedTransparency / 100f)
    // T1.1：在 <=60% 保持标准基础保护量；在 60%->100% 高透端，固定的面板(+0.10)/深色(+0.08)厚度补偿随高透明端减弱至 15%
    val highTransFactor = if (clampedTransparency <= 60) {
        1f
    } else {
        val t = ((100f - clampedTransparency) / 40f).coerceIn(0f, 1f)
        0.15f + 0.85f * t
    }
    val styleExtra = (if (style == GlassStyle.Panel) 0.10f else 0.0f) * highTransFactor
    val darkExtra = (if (isDark) 0.08f else 0.0f) * highTransFactor
    val minFloor = if (clampedTransparency > 60) 0.04f else 0f
    val nominalAlpha = max(minFloor, baseAlpha + styleExtra + darkExtra).coerceIn(0f, 1f)

    // T1.1：减轻浅色模式整块高光蒙层，高透明端收敛至 0.05f；深色控制在 0.025f
    val highlightAlpha = if (isDark) {
        0.025f
    } else if (clampedTransparency <= 60) {
        0.10f
    } else {
        val t = ((100f - clampedTransparency) / 40f).coerceIn(0f, 1f)
        0.05f + 0.05f * t
    }
    val borderAlpha = if (isDark) 0.24f else 0.68f

    // 未采样（null）时采用保守的不利背景亮度
    val m = metrics ?: if (isDark) {
        BackdropMetrics(p10 = 0.5f, p50 = 0.5f, p90 = 0.85f, span = 0.35f, edgeIntensity = 0.2f, minLuminance = 0.15f, maxLuminance = 0.95f, sampled = false)
    } else {
        BackdropMetrics(p10 = 0.15f, p50 = 0.5f, p90 = 0.5f, span = 0.35f, edgeIntensity = 0.2f, minLuminance = 0.05f, maxLuminance = 0.85f, sampled = false)
    }

    val adverseScalarLum = if (isDark) {
        max(m.p90.coerceIn(0f, 1f), m.maxLuminance.coerceIn(0f, 1f))
    } else {
        min(m.p10.coerceIn(0f, 1f), m.minLuminance.coerceIn(0f, 1f))
    }

    val requiredPeakAlpha: Float
    val ambientOpacity: Float
    var cellProtected: FloatArray? = null
    var rawCells: FloatArray? = null

    if (m.hasRgbSamples) {
        val advR = if (isDark) m.adverseLightR else m.adverseDarkR
        val advG = if (isDark) m.adverseLightG else m.adverseDarkG
        val advB = if (isDark) m.adverseLightB else m.adverseDarkB
        val rgbPeak = solveRequiredAlphaForRgbContrast(nominalAlpha, isDark, advR, advG, advB, highlightAlpha)
        val graySrgb = srgbFromLinear(adverseScalarLum)
        val grayPeak = solveRequiredAlphaForRgbContrast(nominalAlpha, isDark, graySrgb, graySrgb, graySrgb, highlightAlpha)
        requiredPeakAlpha = max(rgbPeak, grayPeak).coerceIn(nominalAlpha, 1f)

        val ambR = if (isDark) m.ambientDarkModeR else m.ambientLightModeR
        val ambG = if (isDark) m.ambientDarkModeG else m.ambientLightModeG
        val ambB = if (isDark) m.ambientDarkModeB else m.ambientLightModeB
        val rawAmbient = solveRequiredAlphaForRgbContrast(nominalAlpha, isDark, ambR, ambG, ambB, highlightAlpha)
            .coerceIn(nominalAlpha, requiredPeakAlpha)

        // 若整块玻璃最不利处与主体背景所需不透明度差距 <= 0.08（如排版正文页边距与字行的微弱模糊均值波动），
        // 直接采用统一的峰值安全底色（仍远低于 60% 档位），避免无意义的微幅局部起伏。
        ambientOpacity = if (requiredPeakAlpha - rawAmbient <= 0.08f) {
            requiredPeakAlpha
        } else {
            rawAmbient
        }

        val cR = if (isDark) (m.cellDarkModeR ?: m.cellR) else m.cellR
        val cG = if (isDark) (m.cellDarkModeG ?: m.cellG) else m.cellG
        val cB = if (isDark) (m.cellDarkModeB ?: m.cellB) else m.cellB
        val cols = m.cellCols
        val rows = m.cellRows
        if (cR != null && cG != null && cB != null && cols > 0 && rows > 0 && cR.size == cols * rows) {
            val directCells = FloatArray(cols * rows)
            for (i in 0 until cols * rows) {
                directCells[i] = solveRequiredAlphaForRgbContrast(
                    ambientOpacity,
                    isDark,
                    cR[i],
                    cG[i],
                    cB[i],
                    highlightAlpha
                ).coerceIn(ambientOpacity, 1f)
            }
            rawCells = directCells

            val cells = FloatArray(cols * rows)
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    var maxCellAlpha = ambientOpacity
                    val r0 = max(0, r - 1)
                    val r1 = min(rows - 1, r + 1)
                    val c0 = max(0, c - 1)
                    val c1 = min(cols - 1, c + 1)
                    for (nr in r0..r1) {
                        for (nc in c0..c1) {
                            val req = directCells[nr * cols + nc]
                            if (req > maxCellAlpha) maxCellAlpha = req
                        }
                    }
                    cells[r * cols + c] = maxCellAlpha.coerceIn(ambientOpacity, 1f)
                }
            }
            cellProtected = cells
        }
    } else {
        // 未携带真实 RGB 样本时（如通过标量构造 BackdropMetrics），使用对任意饱和色均成立的保守界求解，
        // 并在存在跨度/边缘波动时附加适度纹理保护。
        val baseReq = solveRequiredAlphaForContrast(
            nominalAlpha = nominalAlpha,
            isDark = isDark,
            backdropLinearLuminance = adverseScalarLum,
            topHighlightAlpha = highlightAlpha
        )
        val textureBoost = if (!m.sampled) 0f else (m.span * 0.03f + m.edgeIntensity * 0.02f)
        requiredPeakAlpha = (baseReq + textureBoost).coerceIn(nominalAlpha, 1f)
        ambientOpacity = requiredPeakAlpha
    }

    // 模糊半径在 12-24dp 内，面板略厚；清晰度在这里实际生效（100% 走无模糊分支）。
    val clarity = clarityPercent.coerceIn(0, 100)
    val baseBlur = if (style == GlassStyle.Panel) 16f else 13f
    val maxBlur = if (style == GlassStyle.Panel) 24f else 20f
    val complexity = (m.span * 0.6f + m.edgeIntensity * 0.4f).coerceIn(0f, 1f)
    val adaptiveBlur = (baseBlur + complexity * (maxBlur - baseBlur)).coerceIn(12f, 24f)
    // 50% 是改造前观感的校准点：更高更清晰（更小的模糊半径），更低更朦胧；100% 直接不为 0 传半径。
    val clarityScale = 1f + (50 - clarity) / 50f * 0.75f
    val blurRadius = if (clarity >= 100) 0.dp else (adaptiveBlur * clarityScale).coerceIn(1f, 34f).dp

    return GlassParams(
        opacity = requiredPeakAlpha,
        blurRadius = blurRadius,
        highlightAlpha = highlightAlpha,
        borderAlpha = borderAlpha,
        ambientOpacity = ambientOpacity,
        cellProtectedOpacities = cellProtected,
        cellCols = m.cellCols,
        cellRows = m.cellRows,
        rawCellOpacities = rawCells
    )
}

internal const val PROTECTION_FIELD_SIZE = 64

/**
 * T1.1 & T1.2：将局部网格保护不透明度合成为连续、光滑、无网格伪影且在受保护区域内部平坦达标的二维不透明度场。
 * - 使用到受保护单元矩形 [c, c+1] x [r, r+1] 的盒欧氏距离（Box SDF）：
 *   在单元内部（dist == 0）及向外 plateauMargin（0.42 个单元）包络带内保持 100% 目标不透明度不衰减，
 *   彻底解决 T1.1 单元偏心处、边界处与文字承载位置对比度衰减的问题；
 * - 在外围过渡带（plateauMargin .. falloffRadius）采用 C2 连续五阶多项式衰减并叠加可分离高斯平滑，
 *   配合单次位图绘制，彻底消除 T1.2 矩形裁切硬边、相邻圆斑凹陷（井字纹）与重叠叠加条带。
 */
internal fun buildContinuousProtectionField(
    rawCellOpacities: FloatArray,
    cols: Int,
    rows: Int,
    ambientOpacity: Float,
    fieldWidth: Int = PROTECTION_FIELD_SIZE,
    fieldHeight: Int = PROTECTION_FIELD_SIZE
): FloatArray {
    val size = fieldWidth * fieldHeight
    if (cols <= 0 || rows <= 0 || rawCellOpacities.size < cols * rows) {
        return FloatArray(size) { ambientOpacity.coerceIn(0f, 1f) }
    }
    val minCell = rawCellOpacities.minOrNull() ?: ambientOpacity
    val maxCell = rawCellOpacities.maxOrNull() ?: ambientOpacity
    if (maxCell - minCell <= 0.015f) {
        val uniform = max(ambientOpacity, maxCell).coerceIn(0f, 1f)
        return FloatArray(size) { uniform }
    }

    val plateauMargin = 0.42f
    val falloffRadius = 1.85f
    val invFalloffSpan = 1f / (falloffRadius - plateauMargin)

    val initialField = FloatArray(size)
    val hardGuarantee = FloatArray(size)

    for (fy in 0 until fieldHeight) {
        val gy = ((fy + 0.5f) / fieldHeight.toFloat()) * rows.toFloat()
        for (fx in 0 until fieldWidth) {
            val gx = ((fx + 0.5f) / fieldWidth.toFloat()) * cols.toFloat()
            var bestAlpha = ambientOpacity
            var guaranteeAlpha = ambientOpacity

            for (r in 0 until rows) {
                val dy = max(0f, max(r.toFloat() - gy, gy - (r + 1).toFloat()))
                if (dy >= falloffRadius) continue
                for (c in 0 until cols) {
                    val cellTarget = rawCellOpacities[r * cols + c]
                    if (cellTarget <= ambientOpacity + 0.002f) continue
                    val dx = max(0f, max(c.toFloat() - gx, gx - (c + 1).toFloat()))
                    if (dx >= falloffRadius) continue
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (dist <= plateauMargin) {
                        if (cellTarget > bestAlpha) bestAlpha = cellTarget
                        if (dist <= 0.36f && cellTarget > guaranteeAlpha) {
                            guaranteeAlpha = cellTarget
                        }
                    } else if (dist < falloffRadius) {
                        val t = ((dist - plateauMargin) * invFalloffSpan).coerceIn(0f, 1f)
                        val weight = 1f - t * t * t * (t * (6f * t - 15f) + 10f)
                        val candidate = ambientOpacity + (cellTarget - ambientOpacity) * weight
                        if (candidate > bestAlpha) bestAlpha = candidate
                    }
                }
            }
            val idx = fy * fieldWidth + fx
            initialField[idx] = bestAlpha.coerceIn(ambientOpacity, 1f)
            hardGuarantee[idx] = guaranteeAlpha.coerceIn(ambientOpacity, 1f)
        }
    }

    val temp = FloatArray(size)
    val smoothed = FloatArray(size)
    val kernel = floatArrayOf(0.10f, 0.22f, 0.36f, 0.22f, 0.10f)

    for (fy in 0 until fieldHeight) {
        val rowOffset = fy * fieldWidth
        for (fx in 0 until fieldWidth) {
            var sum = 0f
            for (k in -2..2) {
                val sx = (fx + k).coerceIn(0, fieldWidth - 1)
                sum += initialField[rowOffset + sx] * kernel[k + 2]
            }
            temp[rowOffset + fx] = sum
        }
    }
    for (fy in 0 until fieldHeight) {
        for (fx in 0 until fieldWidth) {
            var sum = 0f
            for (k in -2..2) {
                val sy = (fy + k).coerceIn(0, fieldHeight - 1)
                sum += temp[sy * fieldWidth + fx] * kernel[k + 2]
            }
            val idx = fy * fieldWidth + fx
            smoothed[idx] = max(sum, hardGuarantee[idx]).coerceIn(ambientOpacity, 1f)
        }
    }
    return smoothed
}

/**
 * 迟滞平滑判断：
 * 1. 当 targetOpacity > currentOpacity（需要增强对比度保护）时，绝不迟滞，立即提升到安全下限（R2）。
 * 2. 仅当保护释放或微小波动（变化 < 1.5% 且模糊半径变化 < 0.8dp）时保持迟滞稳定。
 */
internal fun shouldApplyHysteresis(
    currentOpacity: Float,
    targetOpacity: Float,
    currentBlur: Dp,
    targetBlur: Dp
): Boolean {
    if (targetOpacity > currentOpacity + 0.001f) {
        return false
    }
    val deltaOpacity = abs(currentOpacity - targetOpacity)
    val deltaBlur = abs(currentBlur.value - targetBlur.value)
    return deltaOpacity < 0.015f && deltaBlur < 0.8f
}
