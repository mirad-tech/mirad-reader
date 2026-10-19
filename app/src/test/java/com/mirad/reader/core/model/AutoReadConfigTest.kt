package com.mirad.reader.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自动阅读速度字段的兼容性：旧配置没有这两个字段时必须回落到默认值，
 * 越界值要收敛到 5..120，且不改变数据库结构。
 */
class AutoReadConfigTest {

    @Test
    fun defaultsMatchTheInteractionSpec() {
        val config = ReaderConfig.DEFAULT
        assertEquals(20, config.autoPageSeconds)
        assertEquals(30, config.autoScrollSecondsPerScreen)
    }

    @Test
    fun oldJsonWithoutAutoReadFieldsStillDecodes() {
        val old = """{"fontSizeSp":22.0,"pageMode":"SCROLL","theme":"DARK"}"""
        val config = ReaderConfigJson.decode(old)
        assertEquals(22f, config.fontSizeSp)
        assertEquals(PageMode.SCROLL, config.pageMode)
        assertEquals(20, config.autoPageSeconds)
        assertEquals(30, config.autoScrollSecondsPerScreen)
    }

    @Test
    fun speedsRoundTripThroughBookConfigJson() {
        val config = ReaderConfig.DEFAULT.copy(autoPageSeconds = 45, autoScrollSecondsPerScreen = 90)
        val decoded = ReaderConfigJson.decode(ReaderConfigJson.encode(config))
        assertEquals(45, decoded.autoPageSeconds)
        assertEquals(90, decoded.autoScrollSecondsPerScreen)
    }

    @Test
    fun speedRangeIsClampedToOneSecondSteps() {
        assertEquals(5, AutoReadSpeed.clamp(0))
        assertEquals(5, AutoReadSpeed.clamp(-20))
        assertEquals(20, AutoReadSpeed.clamp(20))
        assertEquals(120, AutoReadSpeed.clamp(121))
        assertEquals(120, AutoReadSpeed.clamp(9999))
    }

    @Test
    fun effectiveSpeedFollowsCurrentMode() {
        val paged = ReaderConfig.DEFAULT.copy(pageMode = PageMode.PAGED, autoPageSeconds = 12, autoScrollSecondsPerScreen = 80)
        assertEquals(12, AutoReadSpeed.forConfig(paged))
        assertEquals("12 秒/页", AutoReadSpeed.summary(paged))

        val scroll = paged.copy(pageMode = PageMode.SCROLL)
        assertEquals(80, AutoReadSpeed.forConfig(scroll))
        assertEquals("80 秒/屏", AutoReadSpeed.summary(scroll))
    }

    @Test
    fun chapterEndDwellAlwaysUsesPageInterval() {
        val config = ReaderConfig.DEFAULT.copy(
            pageMode = PageMode.SCROLL,
            autoPageSeconds = 37,
            autoScrollSecondsPerScreen = 90
        )
        assertEquals(37, AutoReadSpeed.chapterEndDwellSeconds(config))
    }
}
