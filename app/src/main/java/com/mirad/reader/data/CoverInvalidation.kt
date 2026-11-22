package com.mirad.reader.data

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 封面文件失效通知。
 *
 * 数据层替换或删除封面文件后调用 [evict]；界面层的位图缓存注册监听并移除对应条目。
 * 数据层因此不再依赖 ui.shelf.CoverCache。缓存尚未创建时没有任何位图，注册前的通知可以安全丢弃。
 */
object CoverInvalidation {
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun register(listener: (String) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun unregister(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    fun evict(path: String?) {
        if (path.isNullOrBlank()) return
        listeners.forEach { it(path) }
    }
}
