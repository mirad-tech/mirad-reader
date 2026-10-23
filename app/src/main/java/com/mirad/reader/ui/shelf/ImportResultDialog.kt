package com.mirad.reader.ui.shelf

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mirad.reader.data.imports.ImportSummary
import com.mirad.reader.ui.common.MiradDialog
import com.mirad.reader.ui.common.MiradTextButton
import com.mirad.reader.ui.common.publicError

@Composable
internal fun ImportResultDialog(result: ImportSummary, onDismiss: () -> Unit) {
    var expanded by remember(result.batchId) { mutableStateOf(false) }
    val title = when {
        result.failed > 0 && result.success == 0 -> "导入失败"
        result.failed > 0 -> "部分导入失败"
        result.success == 0 -> "没有新增书籍"
        else -> "导入完成"
    }
    MiradDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.animateContentSize(tween(180)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("成功 ${result.success} 本 · 跳过 ${result.skipped} 本 · 失败 ${result.failed} 本")
                if (result.skipped > 0 && result.failed == 0) Text("跳过的书籍已在书架中。", style = MaterialTheme.typography.bodySmall)
                if (result.messages.isNotEmpty()) {
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
        confirmButton = { MiradTextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
