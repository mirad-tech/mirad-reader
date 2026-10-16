package com.mirad.reader.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mirad.reader.ui.design.Spacing

/** 操作面板里的一项。 */
data class ActionItem(
    val label: String,
    val hint: String? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

/**
 * 统一的长按/更多操作面板：书籍长按、分组长按共用。
 */
@Composable
fun ActionSheet(
    title: String,
    onDismiss: () -> Unit,
    items: List<ActionItem>,
    subtitle: String? = null,
    header: (@Composable () -> Unit)? = null
) {
    SheetScaffold(title = title, subtitle = subtitle, onDismiss = onDismiss) {
        if (header != null) {
            Box(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
                header()
            }
            RowDivider()
        }
        items.forEach { item ->
            ListRow(
                title = item.label,
                subtitle = item.hint,
                danger = item.danger,
                onClick = {
                    onDismiss()
                    item.onClick()
                }
            )
        }
        if (items.isEmpty()) {
            SheetEmpty("暂无可用操作")
        }
    }
}

/**
 * 统一空状态：占位图形 + 标题 + 说明 + 可选按钮。
 * 之前书架、笔记、搜索各写了一套，观感与留白都不同。
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    badge: String = "书",
    description: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(Spacing.xxxl)
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(Spacing.lg))
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (description != null) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            if (actionText != null && onAction != null) {
                Spacer(modifier = Modifier.height(Spacing.lg))
                OutlinedButton(onClick = onAction) { Text(actionText) }
            }
        }
    }
}
