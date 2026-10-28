package com.mirad.reader.ui.common

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
    companion object {
        val NEUTRAL = BackdropMetrics(sampled = false)
    }
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
