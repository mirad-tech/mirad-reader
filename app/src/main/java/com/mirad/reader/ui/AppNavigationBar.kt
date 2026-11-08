package com.mirad.reader.ui

import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mirad.reader.ui.common.GlassSurface
import com.mirad.reader.ui.common.LocalPageSafeInsets
import com.mirad.reader.ui.common.glassSafeInsets

@Composable
internal fun AppNavigationBar(
    selected: AppTab,
    motion: AppPagerMotion? = null,
    enabled: Boolean = true,
    onSelect: (AppTab) -> Unit
) {
    val safeInsets = LocalPageSafeInsets.current ?: glassSafeInsets()
    val progress = motion?.progress ?: selected.ordinal.toFloat()
    val dragging = motion?.isDragging == true
    val stretch by androidx.compose.animation.core.animateFloatAsState(
        if (dragging) motion?.dragStretch ?: 0f else 0f, label = "navigationStretch")
    val heightScale by androidx.compose.animation.core.animateFloatAsState(
        if (dragging) 1.04f else 1f, label = "navigationPress")
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val latestMotion by rememberUpdatedState(motion)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestSelected by rememberUpdatedState(selected)
    var innerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    LaunchedEffect(enabled) { if (!enabled) motion?.cancelDrag() }
    DisposableEffect(motion) { onDispose { motion?.releaseHost() } }
    Box(Modifier.fillMaxWidth()
        .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
        .padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        GlassSurface(Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            shape = RoundedCornerShape(29.dp)) {
            Box(Modifier.padding(5.dp)
                .onSizeChanged { innerSize = it }
                .pointerInput(motion, rtl) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val controller = latestMotion
                        val slot = size.width / AppTab.entries.size.toFloat()
                        val index = controller?.progress ?: latestSelected.ordinal.toFloat()
                        val currentCenter = ((if (rtl) AppTab.entries.lastIndex - index else index) + .5f) * slot
                        if (!latestEnabled || controller == null || kotlin.math.abs(down.position.x - currentCenter) > slot / 2f) return@awaitEachGesture
                        val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                        if (!latestEnabled) return@awaitEachGesture
                        held.consume()
                        controller.beginDrag()
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        try {
                            var completed = false
                            while (true) {
                                // 在子项的 Main pass 点击处理之前消费，包含原地长按后的 UP。
                                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == held.id } ?: break
                                if (!latestEnabled || change.isConsumed) {
                                    change.consume()
                                    break
                                }
                                val delta = change.positionChange().x
                                change.consume()
                                if (!change.pressed) {
                                    completed = true
                                    break
                                }
                                controller.dragBy(delta / slot * if (rtl) -1f else 1f)
                            }
                            if (completed && latestEnabled) controller.finishDrag() else controller.cancelDrag()
                        } finally {
                            if (controller.isDragging) controller.cancelDrag()
                        }
                    }
                }) {
                // 覆盖层不参与父布局测量，鼓起不能反馈增大底座。
                Box(Modifier.matchParentSize()) {
                if (innerSize.width > 0 && innerSize.height > 0) {
                    val slot = innerSize.width / AppTab.entries.size.toFloat()
                    val inset = with(density) { 2.dp.toPx() }
                    val width = (slot - inset * 2) * (1f + .12f * stretch.coerceIn(0f, 1f))
                    val height = innerSize.height * heightScale.coerceIn(1f, 1.04f)
                    val visualProgress = if (rtl) AppTab.entries.lastIndex - progress else progress
                    val left = ((visualProgress + .5f) * slot - width / 2f)
                        .coerceIn(0f, (innerSize.width - width).coerceAtLeast(0f))
                    // 胶囊自己也会采样背后内容并叠一层薄底色，与底座叠加是"选中抬升"的有意设计：
                    // 底色降到上下栏曲线后两者都只有十几分之一，实测没有双层白胶囊或暗圈。
                    // 若以后要单独调胶囊，改这里的填充浓度，不要再给材质加不透明度下限。
                    GlassSurface(
                        Modifier.offset { androidx.compose.ui.unit.IntOffset(left.roundToInt(), ((innerSize.height-height)/2).roundToInt()) }
                            .width(with(density) { width.toDp() }).requiredHeight(with(density) { height.toDp() }),
                        shape = RoundedCornerShape(50)) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = .12f)))
                    }
                }
                }
                Row {
                    AppTab.entries.forEach { tab ->
                        val weight = (1f - kotlin.math.abs(progress - tab.ordinal)).coerceIn(0f, 1f)
                        val tint = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.onSurface,
                            MaterialTheme.colorScheme.onPrimaryContainer, weight)
                        Column(Modifier.weight(1f).heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .selectable(selected == tab, enabled = enabled, role = Role.Tab,
                                onClick = { if (motion != null) motion.tap(tab) else onSelect(tab) })
                            .semantics { contentDescription = "${tab.title}导航" }
                            .padding(horizontal = 4.dp, vertical = 5.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Icon(when (tab) {
                                AppTab.Shelf -> MiradIcons.Shelf
                                AppTab.Search -> MiradIcons.Search
                                AppTab.Notes -> MiradIcons.Notes
                                AppTab.Settings -> MiradIcons.Settings
                            }, null, tint = tint, modifier = Modifier.size(20.dp).graphicsLayer {
                                scaleX = 1f + weight * if (dragging) .06f else 0f
                                scaleY = scaleX
                            })
                            Text(tab.title, style = MaterialTheme.typography.labelSmall, color = tint,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
