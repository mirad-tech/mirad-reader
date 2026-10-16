package com.mirad.reader.core.model

/**
 * 定位数据（阅读进度 / 划线 / 书签）的失效判断。
 *
 * 背景：重新识别编码、重新分章之后，正文内容与章节边界都会变，而这些定位数据是按
 * `(chapterIndex, charOffset)` 存的——它们不会自动跟着变。实测后果：整章消失后，
 * 划线与书签会指向不存在的章节，笔记列表里仍然列出来，但**点进去没有任何反应、
 * 也没有任何解释**，变成一堆死条目。
 *
 * 这里只做最小判断：这一条还能不能落到正文上。真正的"重锚"（按章节标题 + 前后文
 * 片段换算新偏移）是后续工作；在那之前，至少让失效**可见**，而不是静默失效。
 */
object NoteAnchor {

    /**
     * @param chapterIndex 目标章节序号
     * @param offset 章内字符偏移
     * @param chapterCharCounts 各章字符数，下标即章节序号
     */
    fun isStale(chapterIndex: Int, offset: Int, chapterCharCounts: List<Int>): Boolean {
        val count = chapterCharCounts.getOrNull(chapterIndex) ?: return true
        return offset < 0 || offset > count
    }

    /** 给界面用的一句话说明。 */
    const val STALE_HINT = "位置已失效（正文重新识别或重新分章过）"
}
