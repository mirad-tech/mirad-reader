package com.mirad.reader.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mirad.reader.ui.design.Sizes
import com.mirad.reader.ui.design.Spacing

/**
 * 全项目**唯一**的底部弹层封装。
 *
 * 统一了：拖拽把手、标题/副标题、右上角文字操作、内容最大高度与外边距。
 * 任何新弹层都走这里，避免每个弹层各写一套高度和留白。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SheetScaffold(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
    /** true：内容撑满弹层剩余高度（适合列表）；false：按内容高度收缩（适合操作面板）。 */
    fillHeight: Boolean = false,
    /** 弹层最大高度占屏幕比例；阅读设置这类需要"边调边看"的面板要调小。 */
    maxHeightFraction: Float = Sizes.sheetMaxHeightFraction,
    content: @Composable ColumnScope.() -> Unit
) {
    // 用屏幕高度显式设上限：ModalBottomSheet 传入的约束可能是无界的，
    // 此时 fillMaxHeight(fraction) 不生效，内容会溢出屏幕底部导致尾部操作点不到。
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * maxHeightFraction
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        contentWindowInsets = { WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout) }
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.xl, end = Spacing.md, bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                actions()
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (fillHeight) Modifier.weight(1f)
                        else Modifier.weight(1f, fill = false)
                    )
                    // 非列表型弹层（操作面板等）内容可能超出一屏：必须可滚动，
                    // 否则尾部的操作会被裁掉、永远点不到。
                    .then(
                        if (fillHeight) Modifier
                        else Modifier.verticalScroll(rememberScrollState())
                    ),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                content()
            }
            Spacer(modifier = Modifier.height(Spacing.md))
        }
    }
}

/** 弹层右上角的文字操作。 */
@Composable
fun SheetActionButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled) { Text(text) }
}

/** 弹层里的空状态占位（统一样式，避免每个弹层自己写一段灰字）。 */
@Composable
fun SheetEmpty(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.xxxl),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
