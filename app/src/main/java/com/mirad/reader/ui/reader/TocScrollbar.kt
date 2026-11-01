package com.mirad.reader.ui.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 目录滚动条的纯换算，独立出来便于单测。
 *
 * 目录可能上千章，靠逐屏滑动很难定位；这里只负责"位置 ↔ 章节序号"的换算，
 * 不涉及任何跳转正文的动作。
 */
internal fun tocScrollFraction(firstVisibleIndex: Int, itemCount: Int, visibleCount: Int): Float {
    if (itemCount <= 0) return 0f
    val maxFirst = (itemCount - visibleCount).coerceAtLeast(1)
    return (firstVisibleIndex.toFloat() / maxFirst).coerceIn(0f, 1f)
}

internal fun tocScrollIndex(fraction: Float, itemCount: Int): Int {
    if (itemCount <= 0 || fraction.isNaN()) return 0
    return (fraction.coerceIn(0f, 1f) * (itemCount - 1)).roundToInt().coerceIn(0, itemCount - 1)
}

/** 拇指占轨道的最小比例，保证几百章时仍抓得住。 */
private const val TOC_MIN_THUMB_FRACTION = .06f

/**
 * 目录右侧的可拖动滚动条。
 *
 * 行为约定：点按或拖动这里只改变目录列表的可见位置（[LazyListState.scrollToItem]），
 * **不会**跳转正文；只有点某一章节行才会触发 `onSelect`。拖动时在拇指上方显示该位置
 * 的章节名，避免盲拖。
 */
@Composable
internal fun TocScrollBar(
    listState: LazyListState,
    itemCount: Int,
    titleAt: (Int) -> String,
    modifier: Modifier = Modifier
) {
    if (itemCount <= 1) return

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val minThumbPx = with(density) { 36.dp.toPx() }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    val visibleCount = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
    val thumbFraction = (visibleCount.toFloat() / itemCount).coerceIn(TOC_MIN_THUMB_FRACTION, 1f)
    val resting = tocScrollFraction(listState.firstVisibleItemIndex, itemCount, visibleCount)
    val fraction = dragFraction ?: resting
    val thumbAlpha by animateFloatAsState(if (dragging) .72f else .38f, label = "tocThumbAlpha")

    Box(modifier = modifier.width(20.dp).fillMaxHeight()) {
        // 轨道高度取列表视口高度：它一定与滚动条同高，且在 Row 里不受"无界高度"影响。
        // 依赖 BoxWithConstraints 的 maxHeight 时，实测拿到无界值 → 比例恒为 0，
        // 一按就把列表拉回第一页。
        val trackHeightPx = listState.layoutInfo.viewportSize.height
            .toFloat().takeIf { it > 0f } ?: with(density) { 320.dp.toPx() }
        val thumbHeightPx = (trackHeightPx * thumbFraction).coerceAtLeast(minThumbPx)
        val thumbTravelPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)
        val offsetPx = thumbTravelPx * fraction

        fun fractionAt(y: Float): Float =
            ((y - thumbHeightPx / 2f) / thumbTravelPx).coerceIn(0f, 1f)

        fun scrollTo(target: Float) {
            scope.launch { listState.scrollToItem(tocScrollIndex(target, itemCount)) }
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(20.dp)
                // 点击与拖动共用一个手势循环：拆成两个 pointerInput 时，点击检测会先吃掉
                // 按下事件，拖动永远收不到位移（模拟器上实测完全不响应）。
                .pointerInput(itemCount, thumbFraction) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 尺寸必须在这里读：pointerInput 块启动时布局还没跑完，size 仍是 0，
                        // 拿它换算会把比例恒算成 0（实测表现为"一按就跳回第一页"）。
                        val track = size.height.toFloat().coerceAtLeast(1f)
                        val thumb = (track * thumbFraction).coerceAtLeast(minThumbPx)
                        val travel = (track - thumb).coerceAtLeast(1f)
                        fun fractionOf(y: Float): Float = ((y - thumb / 2f) / travel).coerceIn(0f, 1f)
                        var lastY = down.position.y
                        var moved = 0f
                        dragging = true
                        dragFraction = fractionOf(lastY)
                        scrollTo(dragFraction ?: 0f)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val dy = change.position.y - lastY
                            lastY = change.position.y
                            moved += abs(dy)
                            if (moved > 8f) {
                                change.consume()
                                val next = ((dragFraction ?: 0f) + dy / travel).coerceIn(0f, 1f)
                                dragFraction = next
                                scrollTo(next)
                            }
                        }
                        dragging = false
                        dragFraction = null
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 9.dp)
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .14f))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 8.dp)
                    .offset { IntOffset(0, offsetPx.roundToInt()) }
                    .width(if (dragging) 5.dp else 3.dp)
                    .height(with(density) { thumbHeightPx.toDp() })
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = thumbAlpha))
            )
            if (dragging) {
                Text(
                    text = titleAt(tocScrollIndex(fraction, itemCount)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset {
                            IntOffset(0, (offsetPx - with(density) { 30.dp.toPx() }).roundToInt())
                        }
                        .width(132.dp)
                        .padding(end = 12.dp)
                )
            }
        }
    }
}
