package com.mirad.reader.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.imports.ImportUpdatePreview
import com.mirad.reader.data.imports.ResumeImpact
import com.mirad.reader.data.repo.BookMaintenance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/** 仅属于当前页面的重关联请求，不进入普通导入批次。 */
internal class RelinkRequest(private val maintenance: BookMaintenance) {
    var busy by mutableStateOf(false)
        private set
    var preview by mutableStateOf<ImportUpdatePreview?>(null)
        private set
    private var operation: Job? = null
    private var decision: CompletableDeferred<Boolean>? = null

    suspend fun run(bookId: Long, path: String, displayName: String): BookEntity? {
        check(!busy)
        busy = true
        operation = currentCoroutineContext().job
        try {
            return maintenance.relinkSource(bookId, path, path, displayName) { impact ->
                withContext(Dispatchers.Main.immediate) {
                    val answer = CompletableDeferred<Boolean>()
                    decision = answer
                    preview = impact
                    try {
                        answer.await()
                    } finally {
                        preview = null
                        decision = null
                    }
                }
            }
        } finally {
            operation = null
            busy = false
        }
    }

    fun decide(confirmed: Boolean) { decision?.complete(confirmed) }
    fun dispose() { operation?.cancel(); decision?.cancel() }
}

@Composable
internal fun rememberRelinkRequest(maintenance: BookMaintenance, owner: Any): RelinkRequest {
    val request = remember(maintenance, owner) { RelinkRequest(maintenance) }
    val pageActive = LocalPagerPageActive.current
    DisposableEffect(request) { onDispose { request.dispose() } }
    LaunchedEffect(request, pageActive) { if (!pageActive) request.dispose() }
    val preview = request.preview
    if (preview != null) {
        MiradDialog(
            onDismissRequest = { request.decide(false) },
            title = { Text("确认重新关联") },
            text = {
                Column {
                    if (preview.resume == ResumeImpact.CHAPTER_START) {
                        Text("将从「${preview.chapterTitle.orEmpty()}」开头继续")
                    }
                    if (preview.lostLocations > 0) {
                        Text("${preview.lostLocations} 条书签或划线将失去定位，原摘录和笔记会保留。")
                    }
                }
            },
            confirmButton = { MiradTextButton(onClick = { request.decide(true) }) { Text("重新关联") } },
            dismissButton = { MiradTextButton(onClick = { request.decide(false) }) { Text("取消") } }
        )
    }
    return request
}
