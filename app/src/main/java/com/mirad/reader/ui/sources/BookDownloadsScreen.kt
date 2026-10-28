package com.mirad.reader.ui.sources

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.mirad.reader.core.sources.BookDownloadStatus
import com.mirad.reader.core.sources.BookDownloadTask
import com.mirad.reader.core.sources.BookDownloads
import com.mirad.reader.ui.common.*
import com.mirad.reader.ui.design.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun BookDownloadsScreen(
    downloads: BookDownloads,
    actionScope: CoroutineScope,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOpenBook: (Long) -> Unit
) {
    val tasks by downloads.tasks.collectAsState()
    val loadError by downloads.loadError.collectAsState()
    var busyIds by remember { mutableStateOf(emptySet<String>()) }
    var actionErrors by remember { mutableStateOf(emptyMap<String, String>()) }
    var cancelTarget by remember { mutableStateOf<BookDownloadTask?>(null) }
    val context = LocalContext.current
    val notificationsDenied = Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun act(task: BookDownloadTask, label: String, work: suspend () -> Unit) {
        if (task.id in busyIds) return
        busyIds = busyIds + task.id
        actionErrors = actionErrors - task.id
        actionScope.launch {
            try {
                work()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                actionErrors = actionErrors + (task.id to sourceActionError(label, error))
            } finally {
                busyIds = busyIds - task.id
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ScreenHeader("下载列表", onBack = onBack, actions = {
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "在线搜书") }
            })
        }
    ) { padding ->
        BouncingLazyColumn(
            modifier = Modifier.fillMaxSize().glassBackdropSource(),
            contentPadding = sourceListPadding(padding),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            loadError?.let { item { ErrorDetails(it) } }
            if (notificationsDenied) item {
                Text("通知未开启，下载进度会显示在此页。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (tasks.isEmpty()) item {
                SourceEmpty("暂无下载任务")
                MiradButton(onClick = onSearch) { Text("在线搜书") }
            }
            items(tasks.sortedByDescending { it.createdAt }, key = { it.id }) { task ->
                DownloadTaskRow(
                    task = task,
                    busy = task.id in busyIds,
                    actionError = actionErrors[task.id],
                    onPause = { act(task, "暂停失败") { downloads.pause(task.id) } },
                    onResume = { act(task, "启动失败") { downloads.resume(task.id) } },
                    onCancel = { cancelTarget = task },
                    onOpen = { task.bookId?.let(onOpenBook) }
                )
                HorizontalDivider()
            }
        }
    }
    cancelTarget?.let { task ->
        MiradDialog(
            onDismissRequest = { cancelTarget = null },
            title = { Text("取消下载") },
            text = { Text("《${task.title}》的已下载章节将清除。") },
            confirmButton = {
                MiradTextButton(danger = true, onClick = {
                    cancelTarget = null
                    act(task, "取消失败") { downloads.cancel(task.id) }
                }) { Text("取消下载") }
            },
            dismissButton = { MiradTextButton(onClick = { cancelTarget = null }) { Text("保留任务") } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadTaskRow(
    task: BookDownloadTask,
    busy: Boolean,
    actionError: String?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit
) {
    val status = task.status
    Column(
        Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(task.title, style = MaterialTheme.typography.titleMedium)
        Text("${task.catalog.book.author.ifBlank { "作者未提供" }} · ${task.source.name}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${downloadStatusLabel(status)} · ${task.finishedChapters} / ${task.totalChapters} 章",
            style = MaterialTheme.typography.bodyMedium,
            color = if (status == BookDownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (status == BookDownloadStatus.IMPORTING) {
            MiradLinearProgress(modifier = Modifier.fillMaxWidth())
        } else if (status != BookDownloadStatus.COMPLETED && status != BookDownloadStatus.CANCELLED && task.totalChapters > 0) {
            MiradLinearProgress(
                progress = { (task.finishedChapters.toFloat() / task.totalChapters).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        task.message?.takeIf { it.isNotBlank() }?.let { message ->
            if (status == BookDownloadStatus.FAILED) ErrorDetails(message)
            else Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actionError?.let { ErrorDetails(it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            when (status) {
                BookDownloadStatus.QUEUED, BookDownloadStatus.DOWNLOADING, BookDownloadStatus.WAITING_FOR_NETWORK ->
                    MiradTextButton(onClick = onPause, enabled = !busy,
                        modifier = Modifier.semantics { contentDescription = "暂停《${task.title}》" }) { Text("暂停") }
                BookDownloadStatus.PAUSED, BookDownloadStatus.FAILED, BookDownloadStatus.CANCELLED ->
                    MiradTextButton(onClick = onResume, enabled = !busy,
                        modifier = Modifier.semantics { contentDescription = "${if (status == BookDownloadStatus.PAUSED) "继续" else "重试"}《${task.title}》" }) {
                        Text(if (status == BookDownloadStatus.PAUSED) "继续" else "重试")
                    }
                BookDownloadStatus.COMPLETED -> {
                    if (task.bookId != null) MiradButton(onClick = onOpen,
                        modifier = Modifier.semantics { contentDescription = "阅读《${task.title}》" }) { Text("阅读") }
                    else ErrorDetails("无法打开书籍：缺少书架记录")
                }
                BookDownloadStatus.IMPORTING -> Unit
            }
            if (status in setOf(BookDownloadStatus.QUEUED, BookDownloadStatus.DOWNLOADING,
                    BookDownloadStatus.WAITING_FOR_NETWORK, BookDownloadStatus.PAUSED, BookDownloadStatus.FAILED)) {
                MiradTextButton(onClick = onCancel, enabled = !busy, danger = true,
                    modifier = Modifier.semantics { contentDescription = "取消下载《${task.title}》" }) { Text("取消") }
            }
        }
    }
}

internal fun downloadStatusLabel(status: BookDownloadStatus): String = when (status) {
    BookDownloadStatus.QUEUED -> "等待下载"
    BookDownloadStatus.DOWNLOADING -> "下载中"
    BookDownloadStatus.WAITING_FOR_NETWORK -> "等待网络"
    BookDownloadStatus.PAUSED -> "已暂停"
    BookDownloadStatus.FAILED -> "下载失败"
    BookDownloadStatus.IMPORTING -> "正在导入书架"
    BookDownloadStatus.COMPLETED -> "已完成"
    BookDownloadStatus.CANCELLED -> "已取消"
}
