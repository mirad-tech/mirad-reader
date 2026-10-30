package com.mirad.reader.ui.common

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 磨砂玻璃开关 + 清晰度的语义回归。
 *
 * 关键约定（计划 3.2）：
 * - 清晰度 0..100：0 最朦胧、100 最清晰，**两端都不承担"关闭"含义**；
 * - 关闭时用 `transparencyPercent = 0` 给旧版 APK 做兼容投影，但清晰度本身要保留；
 * - 两条路径（Compose `computeGlassParams` 与 GPU `liquidBlurDp`）方向必须一致。
 */
class GlassAppearanceTest {

    @Test
    fun disablingKeepsClarityButProjectsZeroForLegacyApk() {
        val off = GlassAppearance(enabled = false, clarityPercent = 82)
        assertEquals("旧版 APK 只认识 0 = 关闭", 0, off.transparencyPercent)
        assertEquals("关闭不能丢清晰度，重新开启要恢复", 82, off.clarityPercent)

        val on = off.copy(enabled = true)
        assertEquals("重新开启后投影回到清晰度", 82, on.transparencyPercent)
    }

    @Test
    fun clarityIsClampedAndNeverProjectsZeroWhileEnabled() {
        assertEquals(100, GlassAppearance(enabled = true, clarityPercent = 180).normalized().clarityPercent)
        assertEquals(0, GlassAppearance(enabled = true, clarityPercent = -20).normalized().clarityPercent)
        // 开启状态下即使清晰度 0，也不能投影成 0——否则旧版 APK 会误认为玻璃被关闭。
        assertEquals(1, GlassAppearance(enabled = true, clarityPercent = 0).transparencyPercent)
    }

    @Test
    fun legacyZeroMigratesToDisabledAndPositiveValueBecomesClarity() {
        assertFalse(GlassAppearance.fromLegacyPercent("0").enabled)
        assertEquals(GlassAppearance.DEFAULT_CLARITY, GlassAppearance.fromLegacyPercent("0").clarityPercent)

        val migrated = GlassAppearance.fromLegacyPercent("45")
        assertTrue(migrated.enabled)
        assertEquals(45, migrated.clarityPercent)

        // 旧键缺失/损坏：按默认开启处理，不臆造关闭状态。
        assertEquals(GlassAppearance.DEFAULT, GlassAppearance.fromLegacyPercent(null))
        assertEquals(GlassAppearance.DEFAULT, GlassAppearance.fromLegacyPercent("abc"))
    }

    @Test
    fun jsonRoundTripPreservesBothFields() {
        val value = GlassAppearance(enabled = false, clarityPercent = 27)
        val decoded = GlassAppearanceJson.decode(GlassAppearanceJson.encode(value))
        assertEquals(value, decoded)
    }

    @Test
    fun corruptRecordFallsBackToDefault() {
        assertEquals(GlassAppearance.DEFAULT, GlassAppearanceJson.decode("{not json"))
        assertEquals(GlassAppearance.DEFAULT, GlassAppearanceJson.decode("{}"))
        assertEquals(GlassAppearance.DEFAULT, GlassAppearanceJson.decode(null))
    }

    @Test
    fun composePathBlurDecreasesMonotonicallyAsClarityRises() {
        val blurs = listOf(0, 25, 50, 75, 100).map { clarity ->
            computeGlassParams(
                transparencyPercent = 60,
                style = GlassStyle.Toolbar,
                isDark = false,
                canBlur = true,
                metrics = null,
                clarityPercent = clarity
            ).blurRadius
        }
        blurs.zipWithNext().forEach { (朦胧, 清晰) ->
            assertTrue("清晰度越高模糊必须越小：$blurs", 清晰 <= 朦胧)
        }
        assertEquals("100% 走无模糊分支，不能传一个很小的有效半径", 0.dp, blurs.last())
    }

    @Test
    fun gpuPathBlurMatchesComposeDirection() {
        val gpu = listOf(0, 25, 50, 75, 100).map { liquidBlurDp(GlassStyle.Toolbar, it) }
        gpu.zipWithNext().forEach { (朦胧, 清晰) ->
            assertTrue("GPU 路径方向必须与 Compose 一致：$gpu", 清晰 <= 朦胧)
        }
        assertEquals("0 半径让渲染器走无 effect 分支", 0f, gpu.last(), 0.001f)
    }

    @Test
    fun disabledGlassStillProducesOpaqueStableSurface() {
        val params = computeGlassParams(
            transparencyPercent = GlassAppearance(enabled = false, clarityPercent = 80).transparencyPercent,
            style = GlassStyle.Panel,
            isDark = false,
            canBlur = true,
            metrics = null
        )
        assertEquals("关闭后必须是完全不透明表面", 1f, params.opacity, 0.001f)
        assertEquals("关闭后不再模糊", 0.dp, params.blurRadius)
    }

    @Test
    fun composeAndGpuPanelsKeepSofteningAtMaximumClarity() {
        val panel80 = computeGlassParams(100, GlassStyle.Panel, false, true, null, clarityPercent = 80)
        val panel100 = computeGlassParams(100, GlassStyle.Panel, false, true, null, clarityPercent = 100)
        assertEquals(panel80.blurRadius, panel100.blurRadius)
        assertTrue(panel100.blurRadius > 0.dp)
        assertEquals(liquidBlurDp(GlassStyle.Panel, 80), liquidBlurDp(GlassStyle.Panel, 100), 0f)
        assertEquals(0.dp, computeGlassParams(100, GlassStyle.Toolbar, false, true, null, clarityPercent = 100).blurRadius)
        assertEquals(0f, liquidBlurDp(GlassStyle.Toolbar, 100), 0f)
    }
}
