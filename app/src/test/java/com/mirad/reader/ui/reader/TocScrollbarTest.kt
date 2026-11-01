package com.mirad.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 目录滚动条的位置换算。只测纯函数：拖动和点击的手势本身需要在设备上验收，
 * 这里锁住"比例与章节序号互为边界安全"的部分。
 */
class TocScrollbarTest {

    @Test
    fun indexMapsTheWholeRangeInclusive() {
        assertEquals(0, tocScrollIndex(0f, 100))
        assertEquals(99, tocScrollIndex(1f, 100))
        assertEquals(0, tocScrollIndex(-1f, 100))
        assertEquals(99, tocScrollIndex(2f, 100))
        assertEquals(50, tocScrollIndex(.505f, 100))
    }

    @Test
    fun indexIsSafeForDegenerateInputs() {
        assertEquals(0, tocScrollIndex(.5f, 0))
        assertEquals(0, tocScrollIndex(.5f, 1))
        assertEquals(0, tocScrollIndex(Float.NaN, 100))
        assertEquals(0, tocScrollIndex(Float.NEGATIVE_INFINITY, 100))
        assertEquals(99, tocScrollIndex(Float.POSITIVE_INFINITY, 100))
    }

    @Test
    fun fractionStartsAtZeroAndEndsAtOneAtTheLastPage() {
        // 一屏可见 8 条时，最后可用的首个可见下标是 count - visible。
        assertEquals(0f, tocScrollFraction(0, 100, 8), .0001f)
        assertEquals(1f, tocScrollFraction(92, 100, 8), .0001f)
        assertEquals(.5f, tocScrollFraction(46, 100, 8), .0001f)
    }

    @Test
    fun fractionIsBoundedWhenTheListIsShorterThanTheViewport() {
        assertEquals(0f, tocScrollFraction(0, 3, 10), .0001f)
        assertEquals(0f, tocScrollFraction(0, 0, 10), .0001f)
        assertEquals(1f, tocScrollFraction(50, 3, 10), .0001f)
    }

    @Test
    fun fractionAndIndexStayConsistentForLongLists() {
        // 上千章时拖动任意比例都不应越界，且首尾闭合。
        for (count in listOf(1, 2, 37, 1200)) {
            for (step in 0..20) {
                val fraction = step / 20f
                val index = tocScrollIndex(fraction, count)
                assert(index in 0 until count) { "count=$count fraction=$fraction index=$index" }
            }
        }
    }
}
