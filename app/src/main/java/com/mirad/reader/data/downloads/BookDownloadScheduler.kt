package com.mirad.reader.data.downloads

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

/** 只由可见界面的操作/恢复调用 schedule；系统重调度沿用已有用户任务。 */
class BookDownloadScheduler(private val context: Context, hasPending: () -> Boolean) {
    private val gate = DownloadSchedulingGate(hasPending)

    fun schedule() = gate.schedule { token ->
        if (Build.VERSION.SDK_INT >= 34) {
            val network = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, BookDownloadJobService::class.java))
                .setUserInitiated(true).setRequiredNetwork(network)
                .setExtras(PersistableBundle().apply { putString(RUN_TOKEN, token) }).build()
            val result = context.getSystemService(JobScheduler::class.java).schedule(info)
            check(result == JobScheduler.RESULT_SUCCESS) { "后台任务未能启动，请回到下载页重试" }
        } else {
            val request = OneTimeWorkRequestBuilder<BookDownloadWorker>()
                .setInputData(workDataOf(RUN_TOKEN to token))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // 刚结束的 worker 与新点击之间也要保留下一次调度，不能被 KEEP 吞掉。
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }

    fun started(token: String): Boolean = gate.started(token)

    fun finished(token: String, finish: (Boolean) -> Unit = {}): Boolean = gate.finished(token, finish)
    fun stopped(token: String, mayRetry: Boolean): Boolean = gate.stopped(token, mayRetry)

    fun cancelIfIdle() = gate.cancelIfIdle {
        if (Build.VERSION.SDK_INT >= 34) context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        else WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        BookDownloadNotifications(context).clear()
    }

    companion object {
        internal const val RUN_TOKEN = "book_download_run_token"
        private const val JOB_ID = 7307
        private const val WORK_NAME = "book-source-download-queue"

        fun isNetworkAvailable(context: Context): Boolean {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val network = connectivity.activeNetwork ?: return false
            return connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
    }
}
