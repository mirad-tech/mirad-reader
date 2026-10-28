package com.mirad.reader.data.downloads

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.mirad.reader.MiradApp
import kotlinx.coroutines.CancellationException

class BookDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MiradApp).container
        val notifications = BookDownloadNotifications(applicationContext)
        val token = inputData.getString(BookDownloadScheduler.RUN_TOKEN) ?: id.toString()
        if (!container.downloadScheduler.started(token)) return Result.success()
        return try {
            setForeground(ForegroundInfo(BookDownloadNotifications.ID, notifications.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            container.downloadManager.runQueue { notifications.update(it) }
            val retry = container.downloadScheduler.finished(token)
            if (retry) Result.retry() else Result.success()
        } catch (error: CancellationException) {
            container.downloadScheduler.stopped(token, mayRetry = true)
            throw error
        } catch (error: Exception) {
            container.downloadManager.reportRunnerFailure(error)
            container.downloadScheduler.stopped(token, mayRetry = false)
            Result.failure()
        }
    }
}
