package com.mirad.reader.reader

/**
 * 阅读页的音量键翻页入口。
 *
 * 只有阅读会话存在时才注册 handler；其他界面（书架/设置等）保持音量键的原生行为。
 * [pager] 的参数为 -1（上一页/上滚一屏）或 +1（下一页/下滚一屏），返回 true 表示已消费按键。
 */
object ReaderKeys {
    var pager: ((Int) -> Boolean)? = null
}
