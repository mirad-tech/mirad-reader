package com.mirad.reader.tts

/**
 * 朗读界面与控制协调的生产动作定义。
 *
 * 统一工具栏点击判定、起始句计算、启动计划解析与跨章守卫，供 UI 与单元测试共用，
 * 避免在测试中复制业务算法。
 */
object ReaderTtsActions {

    enum class StartPlan {
        ResumeExisting,
        StartFromBeginning,
        StartFromCurrent
    }

    /**
     * 工具栏 TTS 按钮点击：正在朗读时暂停；未在朗读（含暂停态或未启动）时打开控制面板。
     */
    inline fun onToolbarClick(
        isPlaying: Boolean,
        onPause: () -> Unit,
        onOpenPanel: () -> Unit
    ) {
        if (isPlaying) {
            onPause()
        } else {
            onOpenPanel()
        }
    }

    /**
     * 解析朗读启动意图：
     * - 已有活跃任务且要求继续且非从头播 -> 继续播放当前清单；
     * - 从本章开头 -> 从第 0 句开始全新朗读；
     * - 否则 -> 从当前阅读位置所在句开始朗读。
     */
    fun resolveStartPlan(resume: Boolean, active: Boolean, fromBeginning: Boolean): StartPlan {
        return when {
            resume && active && !fromBeginning -> StartPlan.ResumeExisting
            fromBeginning -> StartPlan.StartFromBeginning
            else -> StartPlan.StartFromCurrent
        }
    }

    /**
     * 计算开始朗读的句子序号。
     */
    fun computeStartIndex(
        sentences: List<SentenceSplitter.Sentence>,
        currentOffset: Int,
        fromBeginning: Boolean
    ): Int {
        if (fromBeginning || sentences.isEmpty()) return 0
        return sentences.indexOfLast { it.start <= currentOffset }.coerceAtLeast(0)
    }

    /**
     * 校验在途异步请求（例如等待 jsText 回调）在恢复后是否仍然有效。
     * 若在途期间用户切章或换书，则该请求失效作废。
     */
    fun isRequestValid(
        targetBookId: Long,
        currentBookId: Long,
        targetChapter: Int,
        currentChapter: Int
    ): Boolean {
        return targetBookId == currentBookId && targetChapter == currentChapter
    }

    /**
     * 校验章节读完事件是否允许自动接下一章。
     * 必须开关开启，且已读完的书与章严格匹配当前屏幕上的书与章。
     */
    fun canAutoAdvanceChapter(
        finishedBookId: Long,
        currentBookId: Long,
        finishedChapter: Int,
        currentChapter: Int,
        autoNextChapterEnabled: Boolean
    ): Boolean {
        return autoNextChapterEnabled && finishedBookId == currentBookId && finishedChapter == currentChapter
    }
}
