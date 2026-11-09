package com.mirad.reader.ui.common

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor

/** Keep Material's touch, keyboard and accessibility handling, with a quieter track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiradSlider(
    value: Float,
    hapticStep: Float,
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
    val sliderValue = value.coerceIn(valueRange)
    val currentValue = rememberUpdatedState(sliderValue)
    val ticks = remember(hapticStep) { SliderHapticTicks(hapticStep) }
    val interactions = remember(ticks) {
        val source = MutableInteractionSource()
        object : MutableInteractionSource by source {
            override suspend fun emit(interaction: Interaction) {
                trackDrag(interaction)
                source.emit(interaction)
            }

            override fun tryEmit(interaction: Interaction): Boolean {
                trackDrag(interaction)
                return source.tryEmit(interaction)
            }

            // 发出拖动开始事件时就记录起点，异步收集可能晚于首次值更新。
            private fun trackDrag(interaction: Interaction) {
                when (interaction) {
                    is DragInteraction.Start -> ticks.start(currentValue.value)
                    is DragInteraction.Stop, is DragInteraction.Cancel -> ticks.stop()
                    else -> Unit
                }
            }
        }
    }
    val haptic = LocalHapticFeedback.current
    Slider(
        value = sliderValue,
        onValueChange = { updated ->
            if (enabled && ticks.update(updated, SystemClock.elapsedRealtime())) {
                haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }
            onValueChange(updated)
        },
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = {
            ticks.stop()
            onValueChangeFinished?.invoke()
        },
        interactionSource = interactions,
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

/** 手势内只记录相邻的滑动值，快速扫过多个刻度时也只触发一次。 */
internal class SliderHapticTicks(private val step: Float) {
    private var previous: Float? = null
    private var lastTickMillis: Long? = null

    fun start(value: Float) {
        previous = value
    }

    fun stop() {
        previous = null
    }

    fun update(value: Float, nowMillis: Long): Boolean {
        val prior = previous ?: return false
        previous = value
        if (prior == value) return false
        val from = prior.toDouble() / step
        val to = value.toDouble() / step
        // 比较刻度编号时容忍浮点步进误差，落在刻度上算一次，离开该刻度不重复。
        val tolerance = 0.0001
        val crossed = if (value > prior) {
            floor(to + tolerance) > floor(from + tolerance)
        } else {
            ceil(to - tolerance) < ceil(from - tolerance)
        }
        if (!crossed || lastTickMillis?.let { nowMillis - it < 60L } == true) return false
        lastTickMillis = nowMillis
        return true
    }
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
