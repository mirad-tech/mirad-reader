package com.mirad.reader.data.downloads

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import com.mirad.reader.MiradApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookDownloadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val container get() = (application as MiradApp).container
    private var running: Job? = null
    private var activeParameters: JobParameters? = null
    private var activeToken: String? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val token = runToken(params)
        if (!container.downloadScheduler.started(token)) return false
        val notifications = BookDownloadNotifications(this)
        setNotification(params, BookDownloadNotifications.ID, notifications.build(), JOB_END_NOTIFICATION_POLICY_DETACH)
        activeParameters = params
        activeToken = token
        running = scope.launch {
            try {
                container.downloadManager.runQueue { notifications.update(it) }
            } catch (_: CancellationException) {
                // onStopJob 已告诉系统是否重试；执行器会保留已完成章节。
            } catch (error: Exception) {
                container.downloadManager.reportRunnerFailure(error)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (activeParameters === params) {
                        activeParameters = null
                        activeToken = null
                        container.downloadScheduler.finished(token) { retry -> jobFinished(params, retry) }
                    }
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val token = runToken(params)
        if (activeToken == token) {
            activeParameters = null
            activeToken = null
            running?.cancel()
        }
        return container.downloadScheduler.stopped(token,
            Build.VERSION.SDK_INT < 31 || params.stopReason != JobParameters.STOP_REASON_CANCELLED_BY_APP)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun runToken(params: JobParameters): String =
        params.extras.getString(BookDownloadScheduler.RUN_TOKEN) ?: "legacy-uidt-${params.jobId}"
}
