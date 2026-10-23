package com.mirad.reader.ui.common

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** Shared native dialog geometry; Material owns its window, focus and transition. */
@Composable
fun MiradDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null
) {
    val maxBodyHeight = LocalConfiguration.current.screenHeightDp.dp * .55f
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { ApplyReaderDialogWindowPolicy(); confirmButton() },
        dismissButton = dismissButton, title = title, icon = icon,
        modifier = modifier.imePadding().glassBackground(GlassStyle.Panel, MaterialTheme.shapes.extraLarge),
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        text = text?.let { body ->
            { Column(Modifier.fillMaxWidth().heightIn(max = maxBodyHeight)
                .verticalScroll(rememberScrollState()).animateContentSize(tween(180))) { body() } }
        }
    )
}

internal fun publicError(message: String): String = message
    .replace(Regex("(?<![A-Za-z0-9])[A-Za-z]:[\\\\/][^\\s，；。\"']+"), "本地文件")
    .replace(Regex("/(?:data|storage|sdcard)/[^\\s，；。\"']+"), "本地文件")

@Composable
fun ErrorDetails(message: String) {
    var expanded by remember(message) { mutableStateOf(false) }
    val readable = publicError(message)
    Column(Modifier.fillMaxWidth().animateContentSize(tween(180))) {
        Text(if (readable.length <= 90) readable else "操作未完成",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        if (readable.length > 90) {
            MiradTextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起详情" else "查看详情")
            }
            if (expanded) Text(readable, style = MaterialTheme.typography.bodySmall)
        }
    }
}
