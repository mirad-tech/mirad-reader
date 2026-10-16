package com.mirad.reader.ui.shelf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mirad.reader.AppContainer
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.repo.BookMaintenance
import com.mirad.reader.ui.design.Spacing
import kotlinx.coroutines.launch

/**
 * 「显示乱码？换个编码试试」对话框。
 *
 * 每行直接给出该编码解出来的前 100 字预览——中文只要看一眼就知道哪个是对的，
 * 比让用户理解 GBK/GB18030 的差别可靠得多。选"自动检测"则重跑一遍判定。
 */
@Composable
fun EncodingDialog(
    container: AppContainer,
    book: BookEntity,
    onDismiss: () -> Unit,
    onApplied: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var previews by remember { mutableStateOf<List<BookMaintenance.Preview>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(book.id) {
        loading = true
        // 失败不能静默：旧实现直接 getOrDefault(emptyList())，用户只看到一个空列表，
        // 无从判断是"没有候选"还是"正文读不出来"。
        previews = runCatching { container.maintenance.previews(book) }
            .onFailure { error = "读取候选编码失败：${it.message ?: it::class.simpleName}" }
            .getOrDefault(emptyList())
        if (previews.isEmpty()) {
            error = error ?: "没能读出正文内容（文件可能已缺失或损坏），可尝试重新导入"
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重新识别编码") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(
                    text = "当前识别为：${book.encoding ?: "未知"}。下面每种编码都解出了一段预览，选读起来正常的那一行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (loading) {
                    Text(
                        text = "正在读取原文件…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                previews.forEach { preview ->
                    val isSelected = selected == preview.charset
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { selected = preview.charset }
                            .padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "${preview.charset}　${preview.source}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = preview.text.ifBlank { "（解不出内容）" },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working && previews.isNotEmpty(),
                onClick = {
                    val charset = selected ?: previews.firstOrNull()?.charset ?: return@TextButton
                    val auto = previews.firstOrNull()?.charset == charset
                    working = true
                    scope.launch {
                        runCatching { container.maintenance.redetect(book, if (auto) null else charset) }
                            .onSuccess { result ->
                                working = false
                                onApplied("已按 ${result.encoding} 重新识别，共 ${result.chapters} 章")
                            }
                            .onFailure {
                                working = false
                                error = it.message ?: "重新识别失败"
                            }
                    }
                }
            ) { Text(if (working) "处理中…" else "应用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
