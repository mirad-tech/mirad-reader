package com.mirad.reader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal data class TocScrollbarGeometry(val progress: Float, val thumbSize: Float, val travel: Float) {
    val thumbTop: Float get() = progress * travel
}

internal fun tocScrollbarGeometry(
    trackSize: Float, viewportSize: Int, contentSize: Int, scrollOffset: Int, minimumThumb: Float
): TocScrollbarGeometry? {
    if (trackSize <= 0f || viewportSize <= 0 || contentSize <= viewportSize ||
        contentSize == Int.MAX_VALUE || scrollOffset == Int.MAX_VALUE) return null
    val thumb = (trackSize * viewportSize / contentSize)
        .coerceIn(minOf(minimumThumb, trackSize / 2f), trackSize)
    val progress = (scrollOffset.toFloat() / (contentSize - viewportSize)).coerceIn(0f, 1f)
    return TocScrollbarGeometry(progress, thumb, trackSize - thumb)
}

/** 中间位置按框架提供的内容长度估算；两端直接请求首／末项，保证能到底。 */
internal fun tocScrollTarget(
    progress: Float, itemCount: Int, contentSize: Int, viewportSize: Int
): Pair<Int, Int> {
    if (itemCount <= 1 || contentSize <= viewportSize || viewportSize <= 0) return 0 to 0
    if (progress <= 0f) return 0 to 0
    if (progress >= 1f) return itemCount - 1 to 0
    val itemSize = contentSize.toDouble() / itemCount
    val offset = (contentSize - viewportSize) * progress.toDouble()
    val index = (offset / itemSize).toInt().coerceIn(0, itemCount - 1)
    return index to (offset - index * itemSize).roundToInt().coerceAtLeast(0)
}

/** 只操纵目录的 LazyListState，不调用章节选择或阅读定位。 */
@Composable
internal fun TocFastScrollbar(state: LazyListState, itemCount: Int, modifier: Modifier = Modifier) {
    val indicator = state.scrollIndicatorState ?: return
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var trackSize by remember { mutableIntStateOf(0) }
    var dragProgress by remember(itemCount) { mutableStateOf<Float?>(null) }
    val minimumThumb = with(density) { 32.dp.toPx() }
    // 滚动值只在绘制、语义及手势阶段读取，不为每一帧滚动重组控件。
    val geometry by rememberUpdatedState<() -> TocScrollbarGeometry?>({
        tocScrollbarGeometry(trackSize.toFloat(), indicator.viewportSize,
            indicator.contentSize, indicator.scrollOffset, minimumThumb)
    })
    val move by rememberUpdatedState<(Float) -> Unit>({ progress ->
        val (index, offset) = tocScrollTarget(progress, itemCount, indicator.contentSize, indicator.viewportSize)
        state.requestScrollToItem(index, offset)
    })
    val dragging by remember(itemCount) { derivedStateOf { dragProgress != null } }
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = if (dragging) 1f else .7f)
    Canvas(modifier.onSizeChanged { trackSize = it.height }
        .semantics {
            val progress = dragProgress ?: geometry()?.progress ?: 0f
            contentDescription = "目录滚动条"
            progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
            setProgress { value ->
                if (!value.isFinite()) false else { move(value.coerceIn(0f, 1f)); true }
            }
        }
        .pointerInput(state, itemCount) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val initial = geometry() ?: return@awaitEachGesture
                if (initial.travel <= 0f) return@awaitEachGesture
                val grab = if (down.position.y in initial.thumbTop..(initial.thumbTop + initial.thumbSize))
                    down.position.y - initial.thumbTop else initial.thumbSize / 2f
                down.consume()
                try {
                    var change = down
                    while (true) {
                        val current = geometry() ?: break
                        val target = ((change.position.y - grab) / current.travel.coerceAtLeast(1f)).coerceIn(0f, 1f)
                        dragProgress = target
                        move(target)
                        if (!change.pressed) break
                        change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                    }
                } finally { dragProgress = null }
            }
        }) {
        val current = geometry() ?: return@Canvas
        val progress = dragProgress ?: current.progress
        val x = if (rtl) 12.dp.toPx() else size.width - 12.dp.toPx()
        val width = 4.dp.toPx()
        drawRoundRect(trackColor, Offset(x - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height), CornerRadius(width))
        drawRoundRect(thumbColor, Offset(x - width / 2f, progress * current.travel),
            Size(width, current.thumbSize), CornerRadius(width))
    }
}
