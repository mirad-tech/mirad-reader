package com.mirad.reader.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 恢复默认对话框：设置页「阅读设置」卡底部与阅读面板底部共用。
 * 多选三个范围，默认只勾「恢复排版与交互」；确认按 danger 样式。
 */
@Composable
fun ResetReadingDialog(
    onDismiss: () -> Unit,
    onConfirm: (Set<ResetScope>) -> Unit
) {
    var layoutInteraction by remember { mutableStateOf(true) }
    var dayStyle by remember { mutableStateOf(false) }
    var nightStyle by remember { mutableStateOf(false) }
    val anyChecked = layoutInteraction || dayStyle || nightStyle

    MiradDialog(
        onDismissRequest = onDismiss,
        title = { Text("恢复默认") },
        text = {
            Column {
                ResetScopeRow(
                    label = "恢复排版与交互",
                    detail = "重置字号、行距、翻页与交互等设置；日/夜配色与外观不受影响",
                    checked = layoutInteraction,
                    onCheckedChange = { layoutInteraction = it }
                )
                ResetScopeRow(
                    label = "恢复日间配色",
                    detail = "恢复默认浅色主题与背景；其他设置不变",
                    checked = dayStyle,
                    onCheckedChange = { dayStyle = it }
                )
                ResetScopeRow(
                    label = "恢复夜间配色",
                    detail = "恢复默认深色主题与背景；其他设置不变",
                    checked = nightStyle,
                    onCheckedChange = { nightStyle = it }
                )
            }
        },
        confirmButton = {
            MiradTextButton(
                danger = true,
                enabled = anyChecked,
                onClick = {
                    val scopes = buildSet {
                        if (layoutInteraction) add(ResetScope.LAYOUT_INTERACTION)
                        if (dayStyle) add(ResetScope.DAY_STYLE)
                        if (nightStyle) add(ResetScope.NIGHT_STYLE)
                    }
                    onDismiss()
                    onConfirm(scopes)
                }
            ) { Text("恢复", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { MiradTextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ResetScopeRow(
    label: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(Modifier.padding(start = 4.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
