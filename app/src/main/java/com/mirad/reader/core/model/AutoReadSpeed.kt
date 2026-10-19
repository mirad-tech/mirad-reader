package com.mirad.reader.core.model

/**
 * 自动阅读速度的唯一定义处。
 *
 * 分页模式用「翻页间隔」（秒/页），滚动模式用「下滑速度」（秒/屏）。
 * 两者都落在 [MIN_SECONDS]..[MAX_SECONDS]、步进 1 秒；UI 滑杆、配置读回和
 * JS 侧速度换算都从这里取值，避免三处各写一套范围。
 */
object AutoReadSpeed {
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 120

    /** 分页默认 20 秒/页。 */
    const val DEFAULT_PAGE_SECONDS = 20

    /** 滚动默认 30 秒/屏。 */
    const val DEFAULT_SCROLL_SECONDS = 30

    fun clamp(seconds: Int): Int = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    /** 当前模式下实际生效的秒数；配置损坏或旧值越界时自动收敛到合法范围。 */
    fun forConfig(config: ReaderConfig): Int =
        if (config.pageMode == PageMode.SCROLL) {
            clamp(config.autoScrollSecondsPerScreen)
        } else {
            clamp(config.autoPageSeconds)
        }

    /** 自动滚动到章末后的停留时间：始终复用「翻页间隔」。 */
    fun chapterEndDwellSeconds(config: ReaderConfig): Int = clamp(config.autoPageSeconds)

    /** 设置页入口右侧显示的当前模式速度。 */
    fun summary(config: ReaderConfig): String =
        if (config.pageMode == PageMode.SCROLL) {
            "${clamp(config.autoScrollSecondsPerScreen)} 秒/屏"
        } else {
            "${clamp(config.autoPageSeconds)} 秒/页"
        }
}
