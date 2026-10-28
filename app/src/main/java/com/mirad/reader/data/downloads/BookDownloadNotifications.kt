package com.mirad.reader.data.downloads

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mirad.reader.MainActivity
import com.mirad.reader.MiradApp
import com.mirad.reader.core.sources.BookDownloadStatus
import com.mirad.reader.core.sources.BookDownloadTask
import kotlinx.coroutines.launch

internal class BookDownloadNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var lastUpdate = 0L
    private var lastStatus: BookDownloadStatus? = null

    fun build(task: BookDownloadTask? = null): Notification {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "书籍下载", NotificationManager.IMPORTANCE_LOW))
        val completed = task?.status == BookDownloadStatus.COMPLETED
        val failed = task?.status == BookDownloadStatus.FAILED
        val open = PendingIntent.getActivity(context, ID,
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val status = when (task?.status) {
            BookDownloadStatus.COMPLETED -> "已加入书架"
            BookDownloadStatus.IMPORTING -> "正在加入书架"
            BookDownloadStatus.WAITING_FOR_NETWORK -> "等待网络"
            BookDownloadStatus.FAILED -> task.message ?: "下载失败"
            BookDownloadStatus.PAUSED -> "已暂停"
            BookDownloadStatus.CANCELLED -> "已取消"
            else -> task?.let { "${it.finishedChapters} / ${it.totalChapters} 章" } ?: "正在准备下载"
        }
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(if (completed) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_sys_download)
            .setContentTitle(task?.title ?: "书籍下载")
            .setContentText(status)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(!completed && !failed && task?.status !in setOf(BookDownloadStatus.PAUSED, BookDownloadStatus.CANCELLED))
            .setAutoCancel(completed || failed)
            .apply {
                if (task != null && task.status in setOf(BookDownloadStatus.DOWNLOADING, BookDownloadStatus.QUEUED)) {
                    setProgress(task.totalChapters, task.finishedChapters, false)
                }
                if (task != null && task.status in setOf(BookDownloadStatus.DOWNLOADING, BookDownloadStatus.QUEUED, BookDownloadStatus.WAITING_FOR_NETWORK)) {
                    addAction(0, "暂停", action(task.id, ACTION_PAUSE, 1))
                    addAction(0, "取消", action(task.id, ACTION_CANCEL, 2))
                }
            }.build()
    }

    fun update(task: BookDownloadTask) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (task.status == lastStatus && now - lastUpdate < 500) return
        lastStatus = task.status
        lastUpdate = now
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            manager.notify(ID, build(task))
        }
    }

    fun clear() = manager.cancel(ID)

    private fun action(id: String, action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode, Intent(context, BookDownloadActionReceiver::class.java).setAction(action).putExtra("task_id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        const val ID = 7307
        const val CHANNEL = "book_downloads"
        const val ACTION_OPEN = "com.mirad.reader.action.OPEN_DOWNLOADS"
        const val ACTION_PAUSE = "com.mirad.reader.action.PAUSE_DOWNLOAD"
        const val ACTION_CANCEL = "com.mirad.reader.action.CANCEL_DOWNLOAD"
    }
}

class BookDownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("task_id") ?: return
        if (intent.action !in setOf(BookDownloadNotifications.ACTION_PAUSE, BookDownloadNotifications.ACTION_CANCEL)) return
        val pending = goAsync()
        val container = (context.applicationContext as MiradApp).container
        container.appScope.launch {
            try {
                if (intent.action == BookDownloadNotifications.ACTION_PAUSE) container.bookDownloads.pause(id)
                else container.bookDownloads.cancel(id)
            } catch (error: Exception) {
                container.downloadManager.reportRunnerFailure(error)
            } finally { pending.finish() }
        }
    }
}
