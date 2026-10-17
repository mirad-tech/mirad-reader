package com.mirad.reader.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Keep Material's touch, keyboard and accessibility handling, with a quieter track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiradSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val active = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f)
    val inactive = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (enabled) 1f else 0.38f)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Slider(
        value = value.coerceIn(valueRange),
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        thumb = { Box(Modifier.size(18.dp).background(active, CircleShape)) },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val fraction = ((state.value - valueRange.start) /
                    (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
                val start = Offset(if (rtl) size.width else 0f, size.height / 2)
                val end = Offset(if (rtl) 0f else size.width, size.height / 2)
                drawLine(inactive, start, end, size.height, StrokeCap.Round)
                if (fraction > 0f) {
                    drawLine(active, start, Offset(start.x + (end.x - start.x) * fraction, start.y), size.height, StrokeCap.Round)
                }
            }
        }
    )
}

@Composable
fun MiradLinearProgress(progress: (() -> Float)? = null, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.outlineVariant
    if (progress == null) {
        LinearProgressIndicator(
            modifier = modifier.height(4.dp), color = MaterialTheme.colorScheme.primary,
            trackColor = track, strokeCap = StrokeCap.Round, gapSize = 0.dp
        )
    } else {
        LinearProgressIndicator(
            progress = { progress().coerceIn(0f, 1f) },
            modifier = modifier.height(4.dp), color = MaterialTheme.colorScheme.primary,
            trackColor = track, strokeCap = StrokeCap.Round, gapSize = 0.dp,
            drawStopIndicator = {}
        )
    }
}
