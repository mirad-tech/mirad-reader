package com.mirad.reader.ui.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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

/** 拇指最窄也留 32dp，否则上千章时只有几个像素、按不住。 */
private val MinThumbHeight = 32.dp

/**
 * 目录右侧的可拖动滚动条。
 *
 * 行为约定：拖动只改变目录列表的可见位置（[LazyListState.scrollToItem]），**不会**跳转
 * 正文；只有点某一章节行才会触发 `onSelect`。只做拖动不做"点轨道跳转"——两者挂在同一
 * 节点上会互相吃掉按下事件，这是上一版实测失效的原因。
 *
 * 尺寸只有一个来源：列表视口高度。拖动增量按"每像素对应多少比例"换算成目标下标，
 * 目标下标经 `LaunchedEffect` 串行化后写入列表，避免一次拖动并发几十个 scrollToItem。
 */
@Composable
internal fun TocScrollBar(
    listState: LazyListState,
    itemCount: Int,
    titleAt: (Int) -> String,
    modifier: Modifier = Modifier
) {
    require(itemCount > 1) { "目录少于两章时不应显示滚动条" }

    val density = LocalDensity.current
    val minThumbPx = with(density) { MinThumbHeight.toPx() }

    val layout = listState.layoutInfo
    val visibleCount = layout.visibleItemsInfo.size.coerceAtLeast(1)
    val viewportPx = layout.viewportSize.height.toFloat()

    // 拇指按真实占比绘制，不用最小高度冒充比例；只有算出来太细时按 32dp 兜底，
    // 此时轨道剩余行程相应缩短，拖动比例仍然自洽。
    val thumbFraction = (visibleCount.toFloat() / itemCount).coerceIn(.005f, 1f)
    val thumbPx = (viewportPx * thumbFraction).coerceAtLeast(minThumbPx)
    val travelPx = (viewportPx - thumbPx).coerceAtLeast(1f)
    val travel by rememberUpdatedState(travelPx)

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var target by remember { mutableIntStateOf(-1) }

    val resting = tocScrollFraction(listState.firstVisibleItemIndex, itemCount, visibleCount)
    val fraction = if (dragging) dragFraction else resting
    val thumbAlpha by animateFloatAsState(if (dragging) .72f else .38f, label = "tocThumbAlpha")

    // 目标下标串行写入：新目标会取消上一次滚动，等价于"最新的拖动位置生效"。
    LaunchedEffect(target) {
        if (target >= 0) listState.scrollToItem(target.coerceIn(0, itemCount - 1))
    }

    Box(modifier = modifier.width(20.dp).fillMaxHeight()) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(20.dp)
                // 语义节点：屏幕阅读器可以按进度直接跳章节，自动化验证也能拿到精确边界
                // （没有它时只能靠截图估坐标，实测极易把手指放到列表上）。
                .semantics {
                    contentDescription = "目录滚动条"
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { value ->
                        target = tocScrollIndex(value, itemCount)
                        true
                    }
                }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        val next = (dragFraction + delta / travel).coerceIn(0f, 1f)
                        dragFraction = next
                        target = tocScrollIndex(next, itemCount)
                    },
                    onDragStarted = {
                        dragFraction = resting
                        dragging = true
                    },
                    onDragStopped = { dragging = false }
                )
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
                    .offset { IntOffset(0, (travelPx * fraction).roundToInt()) }
                    .width(if (dragging) 5.dp else 3.dp)
                    .height(with(density) { thumbPx.toDp() })
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
                            IntOffset(0, (travelPx * fraction - with(density) { 30.dp.toPx() }).roundToInt())
                        }
                        .width(132.dp)
                        .padding(end = 12.dp)
                )
            }
        }
    }
}
