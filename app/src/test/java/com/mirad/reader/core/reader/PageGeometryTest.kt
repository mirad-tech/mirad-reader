package com.mirad.reader.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版心几何与分页公式的不变量。
 *
 * 这些公式在 reader.js 里有一份等价实现（`columnGeometry()` / `applyLayout()`），
 * 两边必须同源——历史上就是因为 Kotlin 侧仍是 `pad*2`、JS 侧已改成
 * `max(pad*2, vw-colW)`，"与 reader.js 保持一致"的注释变成假的，
 * 宽屏上右侧那一列会露在留白区里。
 */
class PageGeometryTest {

    private fun geometry(viewportWidth: Int, pad: Int, colMax: Int) =
        ReaderCss.PageMath.columnGeometry(viewportWidth, pad, colMax)

    @Test
    fun narrowScreenOnlyUsesPadding() {
        // 手机竖屏：单行上限不起作用，列宽 = 视口 - 两侧留白
        val g = geometry(viewportWidth = 430, pad = 20, colMax = 0)
        assertEquals(390, g.colWidth)
        assertEquals(20, g.inset)
        assertEquals(40, g.gap)
    }

    @Test
    fun wideScreenIsCenteredAndGapCoversTheMargins() {
        // 宽屏：列宽被 colMax 限制，居中后两侧留白远大于 pad —— 此时列间距必须放大
        val g = geometry(viewportWidth = 852, pad = 20, colMax = 612)
        assertEquals(612, g.colWidth)
        assertEquals(120, g.inset)
        assertEquals(852 - 612, g.gap)
        assertTrue("列间距不得小于两侧留白总量", g.gap >= g.inset * 2)
    }

    @Test
    fun nextColumnNeverStartsInsideTheViewport() {
        // 这条正是"两边都显示文字"的判据：下一列的左边缘必须落在屏幕之外
        val cases = listOf(
            Triple(430, 20, 0),
            Triple(852, 20, 612),
            Triple(852, 40, 612),
            Triple(1280, 20, 756),
            Triple(2076, 20, 612)
        )
        for ((vw, pad, colMax) in cases) {
            val g = geometry(vw, pad, colMax)
            val nextColumnLeft = g.inset + g.colWidth + g.gap
            assertTrue(
                "viewport=$vw pad=$pad colMax=$colMax → 下一列左边缘 $nextColumnLeft 必须 >= 视口 $vw",
                nextColumnLeft >= vw
            )
        }
    }

    @Test
    fun paddingIsReducedWhenItWouldSqueezeTheColumnTooMuch() {
        // 极窄屏 + 很大留白：留白要被收缩，保证正文至少还有 minColumnWidth
        val g = geometry(viewportWidth = 200, pad = 80, colMax = 0)
        assertTrue(g.colWidth >= 192)
    }

    @Test
    fun pageCountRoundsUpSoTheLastColumnStaysReachable() {
        // 4 列：flowWidth = 4*612 + 3*240 = 3168，step = 852 → 恰好 4 页
        assertEquals(4, ReaderCss.PageMath.pageCount(4 * 612 + 3 * 240, 612, 240))
        // 末列只溢出一像素也必须算一页（ceil），不能被抹掉
        assertEquals(3, ReaderCss.PageMath.pageCount(2 * 612 + 2 * 240 + 1, 612, 240))
    }
}
