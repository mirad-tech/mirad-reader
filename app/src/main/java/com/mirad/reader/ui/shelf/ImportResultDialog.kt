package com.mirad.reader.ui.shelf

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mirad.reader.data.imports.ImportStage
import com.mirad.reader.data.imports.ImportSummary
import com.mirad.reader.data.imports.ImportUpdatePreview
import com.mirad.reader.data.imports.ResumeImpact
import com.mirad.reader.ui.common.MiradDialog
import com.mirad.reader.ui.common.MiradTextButton
import com.mirad.reader.ui.common.publicError

@Composable
internal fun ImportResultDialog(
    result: ImportSummary,
    onPreview: (String, Long) -> Unit,
    onUpdate: (String, ImportUpdatePreview) -> Unit,
    onResolveWithoutUpdate: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var expanded by remember(result.batchId) { mutableStateOf(false) }
    val item = result.pending.firstOrNull { it.stage == ImportStage.REVIEW } ?: result.pending.firstOrNull()
    val waiting = result.pending.count { it.stage == ImportStage.WAITING_READER }
    val needsReview = result.pending.size - waiting
    val review = item?.review
    val preview = review?.preview
    val title = when {
        review != null -> "确认更新书籍"
        item != null -> "等待更新书籍"
        result.failed > 0 && result.success + result.updated == 0 -> "导入失败"
        result.failed > 0 -> "部分导入失败"
        result.success + result.updated == 0 -> "没有新增书籍"
        else -> "导入完成"
    }
    MiradDialog(
        onDismissRequest = { if (item == null) onDismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.animateContentSize(tween(180)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("新增 ${result.success} · 更新 ${result.updated} · 待确认 $needsReview · 跳过 ${result.skipped} · 失败 ${result.failed}")
                if (waiting > 0) Text("等待退出阅读 $waiting 本", style = MaterialTheme.typography.bodySmall)
                if (item != null && review != null) {
                    Text("${item.displayName} · 新版 ${review.newChapters} 章")
                    item.message?.let { Text(publicError(it), style = MaterialTheme.typography.bodySmall) }
                    review.candidates.forEach { candidate ->
                        val details = listOfNotNull(candidate.author, "${candidate.chapters} 章", candidate.addedAt
                            .takeIf { it > 0L && review.candidates.size > 1 }
                            ?.let { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                                .format(java.util.Date(it)) }).joinToString(" · ")
                        MiradTextButton(onClick = { onPreview(item.id, candidate.bookId) }) {
                            Text(if (preview?.bookId == candidate.bookId) "已选《${candidate.title}》 · $details"
                                else "核对《${candidate.title}》 · $details")
                        }
                    }
                    if (preview != null) {
                        val impact = when (preview.resume) {
                            ResumeImpact.UNREAD -> "原书尚未开始阅读"
                            ResumeImpact.EXACT -> "保留原阅读位置 · ${preview.chapterTitle.orEmpty()}"
                            ResumeImpact.CHAPTER_START -> "原文位置已变化，将从「${preview.chapterTitle.orEmpty()}」开头继续"
                            ResumeImpact.UNAVAILABLE -> "无法确认原阅读章节，请保留旧书"
                        }
                        Text(impact)
                        if (preview.lostLocations > 0) Text("${preview.lostLocations} 条书签或划线将失去定位，原摘录和笔记会保留。")
                    }
                    MiradTextButton(onClick = { onResolveWithoutUpdate(item.id, true) }) { Text("另存新书") }
                } else if (item != null) {
                    Text("${item.displayName} · 退出原书后更新")
                } else if (result.messages.isNotEmpty()) {
                    MiradTextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "收起详情" else "查看详情")
                    }
                    if (expanded) result.messages.forEach {
                        Text(publicError(it), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        dismissButton = item?.let { { MiradTextButton(onClick = { onResolveWithoutUpdate(it.id, false) }) { Text("跳过") } } },
        confirmButton = {
            if (item == null) MiradTextButton(onClick = onDismiss) { Text("关闭") }
            else if (review != null) MiradTextButton(
                onClick = { preview?.let { onUpdate(item.id, it) } },
                enabled = preview != null && preview.resume != ResumeImpact.UNAVAILABLE
            ) { Text("更新这本") }
        }
    )
}
