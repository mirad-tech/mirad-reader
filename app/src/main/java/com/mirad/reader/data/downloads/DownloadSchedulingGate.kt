package com.mirad.reader.data.downloads

import java.util.UUID

/** 入队唤醒和调度收尾必须在同一把锁内交接，不能吞掉完成瞬间的新下载。 */
internal class DownloadSchedulingGate(private val hasPending: () -> Boolean) {
    private var scheduledToken: String? = null
    private val retired = mutableSetOf<String>()

    @Synchronized
    fun schedule(start: (String) -> Unit) {
        if (scheduledToken != null) return
        val token = UUID.randomUUID().toString()
        start(token)
        scheduledToken = token
    }

    @Synchronized
    fun started(token: String): Boolean {
        if (token in retired) return false
        // 系统重建应用进程后，由首次实际启动的任务认领执行权。
        if (scheduledToken == null) scheduledToken = token
        return scheduledToken == token
    }

    @Synchronized
    fun finished(token: String, finish: (Boolean) -> Unit = {}): Boolean {
        if (scheduledToken != token) { finish(false); return false }
        val retry = hasPending()
        finish(retry)
        if (!retry) retire(token)
        return retry
    }

    @Synchronized
    fun stopped(token: String, mayRetry: Boolean): Boolean {
        if (scheduledToken != token) return false
        val retry = mayRetry && hasPending()
        if (!retry) retire(token)
        return retry
    }

    @Synchronized
    fun cancelIfIdle(cancel: () -> Unit) {
        if (hasPending()) return
        scheduledToken?.let(::retire)
        cancel()
    }

    private fun retire(token: String) {
        retired += token
        scheduledToken = null
    }
}
