package com.mirad.reader.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import com.mirad.reader.core.sources.SourceFailure
import com.mirad.reader.ui.common.ErrorDetails
import com.mirad.reader.ui.common.MiradLinearProgress
import com.mirad.reader.ui.design.Spacing

@Composable
internal fun sourceListPadding(padding: PaddingValues): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = padding.calculateStartPadding(direction) + Spacing.lg,
        end = padding.calculateEndPadding(direction) + Spacing.lg,
        top = padding.calculateTopPadding() + Spacing.sm,
        bottom = padding.calculateBottomPadding() + Spacing.xl
    )
}

@Composable
internal fun SourceProgress(label: String, completed: Int, total: Int, running: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("$label $completed / $total", style = MaterialTheme.typography.bodyMedium)
        if (running) {
            if (total > 0) MiradLinearProgress(
                progress = { (completed.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            ) else MiradLinearProgress(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun SourceFailureRow(failure: SourceFailure) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Text(failure.sourceName, style = MaterialTheme.typography.titleSmall)
        ErrorDetails(failure.message)
    }
}

internal fun sourceActionError(action: String, error: Exception): String =
    "$action：${error.message?.takeIf { it.isNotBlank() } ?: "请稍后重试"}"

@Composable
internal fun SourceEmpty(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
