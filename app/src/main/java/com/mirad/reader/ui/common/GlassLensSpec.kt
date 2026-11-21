package com.mirad.reader.ui.common

import androidx.compose.runtime.Immutable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 液态玻璃的形态光学预设（按控件尺寸与视觉层级校准）。
 *
 * 参数基于 dp 与短边比例共同约束，保证手机/平板、窄控件/大面板均有匹配的边缘斜面与折射感。
 */
enum class GlassLensRole(
    val bevelDp: Float,
    val refractDp: Float,
    val falloff: Float,
    val dispersion: Float,
    val rimSoftDp: Float,
    val vibrancy: Float,
    val specStrength: Float,
    val noFold: Boolean
) {
    /** 顶底栏与导航底座：清晰锐利的边缘折射环与高光轮廓，中心文字区保持平坦通透。 */
    Bar(
        bevelDp = 15f,
        refractDp = 38f,
        falloff = 2.0f,
        dispersion = 0.08f,
        rimSoftDp = 1.2f,
        vibrancy = 1.18f,
        specStrength = 0.85f,
        noFold = false
    ),

    /** 面板（弹层、对话框、菜单、浮卡、提示条）：略宽斜面与克制高光，兼容读字可读性。 */
    Panel(
        bevelDp = 18f,
        refractDp = 44f,
        falloff = 2.0f,
        dispersion = 0.08f,
        rimSoftDp = 1.4f,
        vibrancy = 1.14f,
        specStrength = 0.80f,
        noFold = false
    ),

    /** 选中胶囊与圆形图标按钮：参考 LiquidGlassTabBar.droplet 的窄斜面单调不翻折透镜，避免图标重影。 */
    Capsule(
        bevelDp = 9f,
        refractDp = 12f,
        falloff = 1.8f,
        dispersion = 0.05f,
        rimSoftDp = 0.8f,
        vibrancy = 1.22f,
        specStrength = 0.95f,
        noFold = true
    )
}

/**
 * 贴屏平边标记：沿标记边将透镜 SDF 外扩，使该边不产生斜面折射或悬空亮线。
 */
@Immutable
data class GlassFlatEdges(
    val top: Boolean = false,
    val bottom: Boolean = false,
    val left: Boolean = false,
    val right: Boolean = false
) {
    val hasAny: Boolean get() = top || bottom || left || right

    companion object {
        val None = GlassFlatEdges()
        val Bottom = GlassFlatEdges(bottom = true)
    }
}

internal data class GlassCornerRadii(
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float
)

internal data class GlassSampleBounds(
    val loX: Float,
    val loY: Float,
    val hiX: Float,
    val hiY: Float
)

internal data class GlassLensUniforms(
    val paddingPx: Float,
    val widthPx: Float,
    val heightPx: Float,
    val lensCX: Float,
    val lensCY: Float,
    val lensHW: Float,
    val lensHH: Float,
    val radiusTL: Float,
    val radiusTR: Float,
    val radiusBR: Float,
    val radiusBL: Float,
    val bevelPx: Float,
    val refractPx: Float,
    val falloff: Float,
    val rimSoftPx: Float,
    val dispersion: Float,
    val lightX: Float,
    val lightY: Float,
    val specStrength: Float,
    val rimBandMaxPx: Float,
    val hairCenterPx: Float,
    val hairHalfWidthPx: Float,
    val vibrancy: Float,
    val tintR: Float,
    val tintG: Float,
    val tintB: Float,
    val tintA: Float,
    val press: Float,
    val blurRadiusPx: Float,
    val sampleLoX: Float,
    val sampleLoY: Float,
    val sampleHiX: Float,
    val sampleHiY: Float
)

/** 与 Liquid-Glass-Android 对齐的默认光源轴向（30°/210° 左上主光）。 */
internal const val GLASS_LIGHT_DEFAULT_X = 0.866f
internal const val GLASS_LIGHT_DEFAULT_Y = 0.5f

internal const val GLASS_ADAPTIVE_REF_DP = 110f
internal const val GLASS_ADAPTIVE_BEVEL_RATIO = 0.30f
internal const val GLASS_ADAPTIVE_REFRACT_RATIO = 0.70f
internal const val GLASS_ADAPTIVE_RIM_RATIO = 0.05f
internal const val GLASS_FLAT_EDGE_EXTEND_PX = 4096f

private fun quantize(value: Float, step: Float): Float =
    (value / step).roundToInt() * step

/**
 * 逆幂（引力透镜）斜面厚度剖面：
 * - `t = 0`（最外沿）时 `slope = 1`
 * - `t = 1`（斜面内沿）时 `slope = 0`
 * - `falloff = 0` 时退化为平方斜面 `(1 - t)²`
 */
internal fun inversePowerBevelSlope(t: Float, falloff: Float): Float {
    val tc = t.coerceIn(0f, 1f)
    return if (falloff > 0.001f) {
        val gB = 5f.pow(-falloff)
        val raw = (1f + 4f * tc).pow(-falloff)
        ((raw - gB) / (1f - gB)).coerceIn(0f, 1f)
    } else {
        val edge = 1f - tc
        edge * edge
    }
}

/**
 * 单调不翻折折射位移上限：当 `refractPx <= monotonicCap` 时采样坐标沿深度严格单调，
 * 边缘仅延展放大而不折返产生压缩镜像重影。
 */
internal fun inversePowerMonotonicCap(bevelPx: Float, falloff: Float): Float {
    val b = bevelPx.coerceAtLeast(0f)
    return if (falloff > 0.001f) {
        b * (1f - 5f.pow(-falloff)) / (4f * falloff)
    } else {
        b * 0.5f
    }
}

/**
 * 向内采样安全边界（录制坐标空间），保证极端窄小尺寸下严格满足 `lo <= hi`。
 */
internal fun computeInwardSampleBounds(
    paddingPx: Float,
    widthPx: Float,
    heightPx: Float
): GlassSampleBounds {
    val p = paddingPx.coerceAtLeast(0f)
    val w = widthPx.coerceAtLeast(0f)
    val h = heightPx.coerceAtLeast(0f)
    val insetX = if (w > 2f) 1f else min(0.5f, w * 0.5f)
    val insetY = if (h > 2f) 1f else min(0.5f, h * 0.5f)
    val loX = p + insetX
    val hiX = max(loX, p + w - insetX)
    val loY = p + insetY
    val hiY = max(loY, p + h - insetY)
    return GlassSampleBounds(loX = loX, loY = loY, hiX = hiX, hiY = hiY)
}

/**
 * 将视觉角色、组件尺寸与当前外观档位解析为量化后的 AGSL 着色器 uniforms。
 * 返回值完全由输入数值决定，静止帧下参数稳定等价以跳过 RenderEffect 重建。
 */
internal fun resolveGlassLensUniforms(
    widthPx: Float,
    heightPx: Float,
    density: Float,
    paddingPx: Int,
    blurRadiusPx: Float,
    corners: GlassCornerRadii,
    role: GlassLensRole,
    flatEdges: GlassFlatEdges,
    clarityPercent: Int,
    tintR: Float,
    tintG: Float,
    tintB: Float,
    tintA: Float,
    press: Float = 0f,
    lightX: Float = GLASS_LIGHT_DEFAULT_X,
    lightY: Float = GLASS_LIGHT_DEFAULT_Y,
    backdropLuminance: Float? = null
): GlassLensUniforms? {
    if (widthPx <= 0.5f || heightPx <= 0.5f || density <= 0f) return null

    val w = quantize(widthPx, 0.5f).coerceAtLeast(1f)
    val h = quantize(heightPx, 0.5f).coerceAtLeast(1f)
    val blur = if (blurRadiusPx <= 0.01f) 0f else quantize(blurRadiusPx, 0.5f).coerceAtLeast(0f)
    val clampedPress = quantize(press.coerceIn(0f, 1f), 0.01f).coerceIn(0f, 1f)
    val alpha = quantize(tintA.coerceIn(0f, 1f), 0.002f).coerceIn(0f, 1f)

    val radiusCap = min(w, h) * 0.5f
    val rTL = quantize(corners.topLeft.coerceIn(0f, radiusCap), 0.5f).coerceIn(0f, radiusCap)
    val rTR = quantize(corners.topRight.coerceIn(0f, radiusCap), 0.5f).coerceIn(0f, radiusCap)
    val rBR = quantize(corners.bottomRight.coerceIn(0f, radiusCap), 0.5f).coerceIn(0f, radiusCap)
    val rBL = quantize(corners.bottomLeft.coerceIn(0f, radiusCap), 0.5f).coerceIn(0f, radiusCap)

    var lensCX = w * 0.5f
    var lensCY = h * 0.5f
    var lensHW = w * 0.5f
    var lensHH = h * 0.5f
    if (flatEdges.hasAny) {
        val halfExtend = GLASS_FLAT_EDGE_EXTEND_PX * 0.5f
        if (flatEdges.top) {
            lensCY -= halfExtend
            lensHH += halfExtend
        }
        if (flatEdges.bottom) {
            lensCY += halfExtend
            lensHH += halfExtend
        }
        if (flatEdges.left) {
            lensCX -= halfExtend
            lensHW += halfExtend
        }
        if (flatEdges.right) {
            lensCX += halfExtend
            lensHW += halfExtend
        }
    }

    val minDim = min(w, h)
    val refPx = GLASS_ADAPTIVE_REF_DP * density
    val bevelEff = min(role.bevelDp * density, minDim * GLASS_ADAPTIVE_BEVEL_RATIO).coerceAtLeast(2f)
    val refractCap = GLASS_ADAPTIVE_REFRACT_RATIO * minDim * min(1f, minDim / refPx)
    val refractRaw = min(role.refractDp * density, refractCap)
    val refractFinal = if (role.noFold) {
        min(refractRaw, inversePowerMonotonicCap(bevelEff, role.falloff))
    } else {
        refractRaw
    }

    val rimBandBasePx = (2.0f * density).coerceAtLeast(4f)
    val rimBandMax = min(rimBandBasePx, minDim * GLASS_ADAPTIVE_RIM_RATIO).coerceAtLeast(2f)
    val hairCenterPx = (0.35f * density).coerceIn(0.8f, 1.5f)
    val hairHalfWidthPx = (0.70f * density).coerceIn(1.5f, 2.8f)

    // 低清晰度大面板趋近实底（tintA > 0.75）时平滑收敛光学效果，避免实底上残留生硬亮线
    val opticalPresence = if (alpha > 0.75f) {
        ((1f - alpha) / 0.25f).coerceIn(0f, 1f)
    } else {
        1f
    }
    val clearBoost = if (clarityPercent >= 100) 1.12f else 1f
    val backdropBoost = if (clarityPercent >= 80 && backdropLuminance != null) {
        (1.08f - 0.16f * backdropLuminance.coerceIn(0f, 1f)).coerceIn(0.92f, 1.08f)
    } else {
        1f
    }
    val (qx, qy) = quantizeLightDirection(lightX, lightY)
    val rimSoftPx = quantize((role.rimSoftDp * density).coerceIn(0f, bevelEff * 0.25f) * opticalPresence, 0.5f)
        .coerceAtLeast(0f)
    val effectiveRefract = quantize(refractFinal * opticalPresence, 0.5f).coerceAtLeast(0f)
    val effectiveSpec = quantize((role.specStrength * clearBoost * backdropBoost * opticalPresence).coerceIn(0f, 1.5f), 0.01f)
        .coerceIn(0f, 1.5f)
    val effectiveDispersion = quantize((role.dispersion * opticalPresence).coerceIn(0f, 0.5f), 0.005f)
        .coerceIn(0f, 0.5f)
    val effectiveVibrancy = quantize(1f + (role.vibrancy - 1f) * opticalPresence, 0.01f)
        .coerceAtLeast(1f)

    val safePad = paddingPx.coerceAtLeast(0).toFloat()
    val bounds = computeInwardSampleBounds(safePad, w, h)

    return GlassLensUniforms(
        paddingPx = safePad,
        widthPx = w,
        heightPx = h,
        lensCX = lensCX,
        lensCY = lensCY,
        lensHW = lensHW,
        lensHH = lensHH,
        radiusTL = rTL,
        radiusTR = rTR,
        radiusBR = rBR,
        radiusBL = rBL,
        bevelPx = bevelEff,
        refractPx = effectiveRefract,
        falloff = role.falloff,
        rimSoftPx = rimSoftPx,
        dispersion = effectiveDispersion,
        lightX = qx,
        lightY = qy,
        specStrength = effectiveSpec,
        rimBandMaxPx = rimBandMax,
        hairCenterPx = hairCenterPx,
        hairHalfWidthPx = hairHalfWidthPx,
        vibrancy = effectiveVibrancy,
        tintR = tintR.coerceIn(0f, 1f),
        tintG = tintG.coerceIn(0f, 1f),
        tintB = tintB.coerceIn(0f, 1f),
        tintA = alpha,
        press = clampedPress,
        blurRadiusPx = blur,
        sampleLoX = bounds.loX,
        sampleLoY = bounds.loY,
        sampleHiX = bounds.hiX,
        sampleHiY = bounds.hiY
    )
}
