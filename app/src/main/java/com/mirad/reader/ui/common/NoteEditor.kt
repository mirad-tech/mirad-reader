package com.mirad.reader.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val LocalDialogBodyHeight = staticCompositionLocalOf { Dp.Infinity }

/** 生产和预览共用；横屏输入法挤压时，让编辑区自身滚动并完整保留可见的一行。 */
@Composable
internal fun NoteEditor(value: String, onValueChange: (String) -> Unit) {
    val maximum = LocalDialogBodyHeight.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("笔记内容") },
        modifier = Modifier.fillMaxWidth().heightIn(min = minOf(120.dp, maximum), max = maximum),
        minLines = if (maximum < 120.dp) 1 else 3,
        maxLines = 7
    )
}
