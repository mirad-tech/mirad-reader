package com.mirad.reader.ui.common

import android.view.Surface
import com.mirad.reader.ui.computeTapLiquidStretch
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

class GlassLensSpecTest {
    @Test fun inversePowerBevelSlopeEndpointsAndMonotonicity() {
        for (falloff in listOf(0f, 1.5f, 2.0f, 3.0f)) {
            assertEquals(1f, inversePowerBevelSlope(0f, falloff), 0.0001f)
            assertEquals(1f, inversePowerBevelSlope(-0.2f, falloff), 0.0001f)
            assertEquals(0f, inversePowerBevelSlope(1f, falloff), 0.0001f)
            assertEquals(0f, inversePowerBevelSlope(1.5f, falloff), 0.0001f)

            var prev = inversePowerBevelSlope(0f, falloff)
            for (step in 1..20) {
                val t = step / 20f
                val curr = inversePowerBevelSlope(t, falloff)
                assertTrue("non-finite at falloff=$falloff t=$t", curr.isFinite())
                assertTrue("not monotonic at falloff=$falloff t=$t: curr=$curr prev=$prev", curr < prev)
                prev = curr
            }
        }
        // falloff = 0 退化为平方斜面 (1 - t)^2
        for (step in 0..10) {
            val t = step / 10f
            val expected = (1f - t) * (1f - t)
            assertEquals(expected, inversePowerBevelSlope(t, 0f), 0.0001f)
        }
    }

    @Test fun monotonicCapIsFinitePositiveAndEnforcedForCapsule() {
        for (bevel in listOf(6f, 20f, 54f)) {
            for (falloff in listOf(0f, 1.5f, 1.8f, 2.0f, 3.0f)) {
                val cap = inversePowerMonotonicCap(bevel, falloff)
                assertTrue("cap=$cap bevel=$bevel falloff=$falloff", cap.isFinite() && cap > 0f)
            }
        }
        for (density in listOf(2f, 3f, 4f)) {
            val uniforms = resolveGlassLensUniforms(
                widthPx = 96f * density,
                heightPx = 44f * density,
                density = density,
                paddingPx = 20,
                blurRadiusPx = 10f * density,
                corners = GlassCornerRadii(22f * density, 22f * density, 22f * density, 22f * density),
                role = GlassLensRole.Capsule,
                flatEdges = GlassFlatEdges.None,
                clarityPercent = 60,
                tintR = 1f, tintG = 1f, tintB = 1f, tintA = 0.14f
            )!!
            val cap = inversePowerMonotonicCap(uniforms.bevelPx, uniforms.falloff)
            assertTrue(
                "Capsule refractPx=${uniforms.refractPx} exceeded monotonic cap=$cap at density=$density",
                uniforms.refractPx <= cap + 0.5f
            )
        }
    }

    @Test fun adaptiveLensScaleRespectsShortSideAcrossControlsAndDensities() {
        data class Case(val wDp: Float, val hDp: Float, val role: GlassLensRole)
        val cases = listOf(
            Case(44f, 44f, GlassLensRole.Capsule),
            Case(360f, 62f, GlassLensRole.Bar),
            Case(360f, 240f, GlassLensRole.Panel)
        )
        for (density in listOf(2f, 3f, 4f)) {
            val resolved = cases.map { c ->
                val u = resolveGlassLensUniforms(
                    widthPx = c.wDp * density,
                    heightPx = c.hDp * density,
                    density = density,
                    paddingPx = 16,
                    blurRadiusPx = 12f * density,
                    corners = GlassCornerRadii(22f * density, 22f * density, 22f * density, 22f * density),
                    role = c.role,
                    flatEdges = GlassFlatEdges.None,
                    clarityPercent = 60,
                    tintR = 0.98f, tintG = 0.98f, tintB = 0.96f, tintA = 0.14f
                )!!
                val minDim = min(u.widthPx, u.heightPx)
                assertTrue(u.bevelPx <= minDim * GLASS_ADAPTIVE_BEVEL_RATIO + 0.5f)
                assertTrue(u.bevelPx >= 2f)
                assertTrue(u.rimBandMaxPx <= minDim * GLASS_ADAPTIVE_RIM_RATIO + 0.5f)
                assertTrue(u.rimBandMaxPx >= 2f)
                u
            }
            // 小控件的折射位移应明显收敛于大面板（110dp 以下按平方收缩）
            val small = resolved[0]
            val medium = resolved[1]
            val large = resolved[2]
            assertTrue(small.refractPx < medium.refractPx)
            assertTrue(medium.refractPx <= large.refractPx)
        }
    }

    @Test fun flatBottomExtendsOnlyBottomLensGeometry() {
        val normal = resolveGlassLensUniforms(
            widthPx = 1080f,
            heightPx = 600f,
            density = 3f,
            paddingPx = 28,
            blurRadiusPx = 14f,
            corners = GlassCornerRadii(84f, 84f, 0f, 0f),
            role = GlassLensRole.Panel,
            flatEdges = GlassFlatEdges.None,
            clarityPercent = 60,
            tintR = 1f, tintG = 1f, tintB = 1f, tintA = 0.3f
        )!!
        val flatBottom = resolveGlassLensUniforms(
            widthPx = 1080f,
            heightPx = 600f,
            density = 3f,
            paddingPx = 28,
            blurRadiusPx = 14f,
            corners = GlassCornerRadii(84f, 84f, 0f, 0f),
            role = GlassLensRole.Panel,
            flatEdges = GlassFlatEdges.Bottom,
            clarityPercent = 60,
            tintR = 1f, tintG = 1f, tintB = 1f, tintA = 0.3f
        )!!
        assertEquals(normal.lensCX, flatBottom.lensCX, 0.001f)
        assertEquals(normal.lensHW, flatBottom.lensHW, 0.001f)
        assertEquals(normal.lensCY + GLASS_FLAT_EDGE_EXTEND_PX * 0.5f, flatBottom.lensCY, 0.001f)
        assertEquals(normal.lensHH + GLASS_FLAT_EDGE_EXTEND_PX * 0.5f, flatBottom.lensHH, 0.001f)
    }

    @Test fun inwardSampleBoundsStayOrderedAndInsideRecordedArea() {
        for (padding in listOf(0, 40)) {
            for (width in listOf(1f, 2f, 1080f)) {
                for (height in listOf(1f, 2f, 240f)) {
                    val b = computeInwardSampleBounds(padding.toFloat(), width, height)
                    assertTrue("loX > hiX for p=$padding w=$width", b.loX <= b.hiX)
                    assertTrue("loY > hiY for p=$padding h=$height", b.loY <= b.hiY)
                    assertTrue(b.loX >= padding.toFloat())
                    assertTrue(b.hiX <= padding + width)
                    assertTrue(b.loY >= padding.toFloat())
                    assertTrue(b.hiY <= padding + height)
                }
            }
        }
    }

    @Test fun subQuantizationJitterProducesIdenticalUniforms() {
        val base = resolveGlassLensUniforms(
            widthPx = 1000.0f,
            heightPx = 180.0f,
            density = 3f,
            paddingPx = 30,
            blurRadiusPx = 30.0f,
            corners = GlassCornerRadii(66.0f, 66.0f, 66.0f, 66.0f),
            role = GlassLensRole.Bar,
            flatEdges = GlassFlatEdges.None,
            clarityPercent = 60,
            tintR = 0.99f, tintG = 0.98f, tintB = 0.97f, tintA = 0.1408f,
            press = 0.0f
        )!!
        val jittered = resolveGlassLensUniforms(
            widthPx = 1000.08f,
            heightPx = 179.94f,
            density = 3f,
            paddingPx = 30,
            blurRadiusPx = 30.05f,
            corners = GlassCornerRadii(66.04f, 65.96f, 66.03f, 65.98f),
            role = GlassLensRole.Bar,
            flatEdges = GlassFlatEdges.None,
            clarityPercent = 60,
            tintR = 0.99f, tintG = 0.98f, tintB = 0.97f, tintA = 0.1406f,
            press = 0.002f
        )!!
        assertEquals(base, jittered)
    }

    @Test fun clarityHundredKeepsZeroBlurWithClearSpecBoostAndOpaquePanelAttenuates() {
        val normalBar = resolveGlassLensUniforms(
            widthPx = 960f, heightPx = 180f, density = 3f,
            paddingPx = 30, blurRadiusPx = 24f,
            corners = GlassCornerRadii(66f, 66f, 66f, 66f),
            role = GlassLensRole.Bar, flatEdges = GlassFlatEdges.None,
            clarityPercent = 60, tintR = 1f, tintG = 1f, tintB = 1f, tintA = 0.1408f
        )!!
        val clearBar = resolveGlassLensUniforms(
            widthPx = 960f, heightPx = 180f, density = 3f,
            paddingPx = 0, blurRadiusPx = 0f,
            corners = GlassCornerRadii(66f, 66f, 66f, 66f),
            role = GlassLensRole.Bar, flatEdges = GlassFlatEdges.None,
            clarityPercent = 100, tintR = 1f, tintG = 1f, tintB = 1f, tintA = 0.08f
        )!!
        assertEquals(0f, clearBar.blurRadiusPx, 0f)
        assertTrue(clearBar.refractPx > 0f)
        assertTrue(clearBar.specStrength > normalBar.specStrength)

        val opaquePanel = resolveGlassLensUniforms(
            widthPx = 960f, heightPx = 600f, density = 3f,
            paddingPx = 40, blurRadiusPx = 42f,
            corners = GlassCornerRadii(84f, 84f, 0f, 0f),
            role = GlassLensRole.Panel, flatEdges = GlassFlatEdges.Bottom,
            clarityPercent = 0, tintR = 1f, tintG = 1f, tintB = 1f, tintA = 1.0f
        )!!
        assertEquals(0f, opaquePanel.refractPx, 0f)
        assertEquals(0f, opaquePanel.specStrength, 0f)
        assertEquals(0f, opaquePanel.dispersion, 0f)
    }

    @Test fun reducedTransparencyDisablesGlassBlur() {
        assertTrue(glassBlurAllowed(apiLevel = 36, powerSave = false, forceFallback = false, reducedTransparency = false))
        assertFalse(glassBlurAllowed(apiLevel = 36, powerSave = false, forceFallback = false, reducedTransparency = true))
        assertFalse(glassBlurAllowed(apiLevel = 36, powerSave = true, forceFallback = false, reducedTransparency = false))
        assertFalse(glassBlurAllowed(apiLevel = 30, powerSave = false, forceFallback = false, reducedTransparency = false))
    }

    @Test fun glassAppearancePreservesDynamicHighlightAndBackwardCompatibility() {
        val withDynamic = GlassAppearance(enabled = true, clarityPercent = 72, dynamicHighlight = true)
        val encoded = GlassAppearanceJson.encode(withDynamic)
        val decoded = GlassAppearanceJson.decode(encoded)
        assertEquals(withDynamic, decoded)
        assertTrue(decoded.dynamicHighlight)

        // 旧记录未包含 dynamicHighlight 字段时默认 false
        val legacyJson = """{"enabled":true,"clarityPercent":65}"""
        val legacyDecoded = GlassAppearanceJson.decode(legacyJson)
        assertEquals(65, legacyDecoded.clarityPercent)
        assertFalse(legacyDecoded.dynamicHighlight)
    }

    @Test fun sensorLightDirectionFlatFallbackRotationsAndUnitLength() {
        // 平放（gx=0, gy=0）时平滑回归默认 30°/210° 光向
        val (fx, fy) = computeSensorLightDirection(gx = 0f, gy = 0f, rotation = Surface.ROTATION_0, smoothing = 1f)
        assertEquals(GLASS_LIGHT_DEFAULT_X, fx, 0.01f)
        assertEquals(GLASS_LIGHT_DEFAULT_Y, fy, 0.01f)

        // 四个旋转朝向下的输出必须始终是有限单位向量
        for (rotation in listOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)) {
            val (lx, ly) = computeSensorLightDirection(gx = 3.5f, gy = 7.2f, rotation = rotation, smoothing = 0.5f)
            assertTrue(lx.isFinite() && ly.isFinite())
            val len = sqrt(lx * lx + ly * ly)
            assertEquals(1f, len, 0.002f)
        }

        // 异常 NaN / Infinity 传感器上报必须安全回退为有限单位向量且不抛异常
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val (nx, ny) = computeSensorLightDirection(gx = bad, gy = 1f, prevX = bad, prevY = bad, smoothing = bad)
            assertTrue(nx.isFinite() && ny.isFinite())
            val len = sqrt(nx * nx + ny * ny)
            assertEquals(1f, len, 0.002f)

            val (qx, qy) = quantizeLightDirection(bad, 0.5f)
            assertEquals(GLASS_LIGHT_DEFAULT_X, qx, 0.001f)
            assertEquals(GLASS_LIGHT_DEFAULT_Y, qy, 0.001f)
        }
    }

    @Test fun backdropMeanLuminanceAndHysteresisThresholds() {
        val darkMetrics = analyzeLiquidPixels(intArrayOf(0xFF000000.toInt(), 0xFF15181A.toInt()))!!
        val brightMetrics = analyzeLiquidPixels(intArrayOf(0xFFFFFFFF.toInt(), 0xFFFCFBF7.toInt()))!!
        assertTrue(darkMetrics.meanLuminance < 0.1f)
        assertTrue(brightMetrics.meanLuminance > 0.9f)

        // 滞回区间：从暗到亮需 > 0.60，从亮到暗需 < 0.45
        assertFalse(resolveOverLightHysteresis(0.55f, previousOverLight = false))
        assertTrue(resolveOverLightHysteresis(0.65f, previousOverLight = false))
        assertTrue(resolveOverLightHysteresis(0.50f, previousOverLight = true))
        assertFalse(resolveOverLightHysteresis(0.40f, previousOverLight = true))
    }

    @Test fun tapLiquidStretchZeroAtEndpointsAndScalesWithTabSpan() {
        assertEquals(0f, computeTapLiquidStretch(0f, 0f, 3), 0.0001f)
        assertEquals(0f, computeTapLiquidStretch(3f, 0f, 3), 0.0001f)
        assertEquals(0f, computeTapLiquidStretch(1f, 1f, 1), 0.0001f)

        val midShort = computeTapLiquidStretch(0.5f, 0f, 1)
        val midLong = computeTapLiquidStretch(1.5f, 0f, 3)
        assertTrue(midShort > 0.4f)
        assertTrue("Longer tab leap must stretch more: short=$midShort long=$midLong", midLong > midShort)
        assertTrue(abs(midLong) <= 1f)
    }
}
