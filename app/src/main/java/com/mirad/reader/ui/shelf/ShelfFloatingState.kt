package com.mirad.reader.ui.shelf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mirad.reader.data.imports.ImportItem
import com.mirad.reader.data.imports.ImportStage

/** 只属于当前应用进程；离开页面或重建 Activity 不会重新弹出已经看过的提示。 */
internal class ShelfResumePromptState {
    var consumed by mutableStateOf(false)
        private set
    private var visibleMillis by mutableLongStateOf(0L)

    fun remainingMillis(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Long =
        if (consumed) 0L else (timeoutMillis - visibleMillis).coerceAtLeast(0L)

    fun recordVisibleTime(elapsedMillis: Long, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS) {
        if (consumed) return
        visibleMillis += elapsedMillis.coerceAtLeast(0L)
        if (remainingMillis(timeoutMillis) == 0L) dismiss()
    }

    fun dismiss() { consumed = true }

    companion object { const val DEFAULT_TIMEOUT_MILLIS = 5_000L }
}

internal enum class ShelfFloatingContent { NONE, RESUME, IMPORT }

internal fun resolveShelfFloatingContent(
    available: Boolean,
    importing: Boolean,
    hasResume: Boolean,
    resumeConsumed: Boolean
): ShelfFloatingContent = when {
    !available -> ShelfFloatingContent.NONE
    importing -> ShelfFloatingContent.IMPORT
    hasResume && !resumeConsumed -> ShelfFloatingContent.RESUME
    else -> ShelfFloatingContent.NONE
}

internal data class ShelfImportPresentation(
    val fileName: String?,
    val stage: String,
    val progress: Float?,
    val pendingCount: Int
)

/** 当前队列不是固定批次；只展示真实文件、阶段与待处理数量。 */
internal fun shelfImportPresentation(items: List<ImportItem>): ShelfImportPresentation {
    val active = items.lastOrNull {
        it.stage == ImportStage.COPYING || it.stage == ImportStage.PARSING || it.stage == ImportStage.INDEXING
    }
    return ShelfImportPresentation(
        fileName = active?.displayName ?: items.firstOrNull { it.stage == ImportStage.PENDING }?.displayName,
        stage = when (active?.stage) {
            ImportStage.COPYING -> "正在复制"
            ImportStage.PARSING -> "正在解析"
            ImportStage.INDEXING -> "正在建立索引"
            else -> "准备导入"
        },
        // 复制尚未报告比例（例如长度未知）以及解析阶段均不伪造总进度。
        progress = active?.takeIf { it.stage == ImportStage.COPYING && it.progress > 0f }
            ?.progress?.coerceIn(0f, 1f),
        pendingCount = items.count { it.stage == ImportStage.PENDING }
    )
}
