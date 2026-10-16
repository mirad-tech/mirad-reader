package com.mirad.reader.core.model

/**
 * 阅读位置。以「章内字符偏移」为准，页码只用于显示，换字号/换模式后仍可精确恢复。
 */
data class Locator(
    val chapterIndex: Int = 0,
    val charOffset: Int = 0,
    val pageIndex: Int = 0,
    val pageCount: Int = 0
)

/** 进度换算。纯函数，便于单元测试。 */
object ProgressMath {

    /**
     * 全书进度百分比。[chapterCharCounts] 为每章字符数，[chapterIndex] 与 [charOffset] 为当前位置。
     */
    fun percent(chapterCharCounts: List<Long>, chapterIndex: Int, charOffset: Int): Float {
        val total = chapterCharCounts.sum()
        if (total <= 0L) return 0f
        if (chapterIndex <= 0) {
            return (charOffset.coerceAtLeast(0).toDouble() / total).toFloat().coerceIn(0f, 1f)
        }
        if (chapterIndex >= chapterCharCounts.size) return 1f
        var before = 0L
        for (i in 0 until chapterIndex) before += chapterCharCounts[i]
        val inChapter = charOffset.coerceIn(0, chapterCharCounts[chapterIndex].toInt()).toLong()
        return ((before + inChapter).toDouble() / total).toFloat().coerceIn(0f, 1f)
    }

    /** 由百分比反查章节与章内偏移，用于进度条拖动。 */
    fun fromPercent(chapterCharCounts: List<Long>, percent: Float): Locator {
        val total = chapterCharCounts.sum()
        if (total <= 0L || chapterCharCounts.isEmpty()) return Locator()
        val target = (percent.coerceIn(0f, 1f) * total).toLong()
        var acc = 0L
        for (i in chapterCharCounts.indices) {
            val len = chapterCharCounts[i]
            if (acc + len > target || i == chapterCharCounts.lastIndex) {
                return Locator(chapterIndex = i, charOffset = (target - acc).coerceIn(0L, len).toInt())
            }
            acc += len
        }
        return Locator()
    }
}
