package com.mirad.reader.ui

import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
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
    val tintFloor = if (MaterialTheme.colorScheme.surface.luminance() < .5f) .78f else .66f
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
            minimumTintAlpha = tintFloor, shadowElevation = 1.dp,
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
                    GlassSurface(
                        Modifier.offset { androidx.compose.ui.unit.IntOffset(left.roundToInt(), ((innerSize.height-height)/2).roundToInt()) }
                            .width(with(density) { width.toDp() }).requiredHeight(with(density) { height.toDp() }),
                        minimumTintAlpha = tintFloor, shadowElevation = 0.dp,
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
                                AppTab.Shelf -> ShelfIcon
                                AppTab.Search -> Icons.Outlined.Search
                                AppTab.Notes -> NotesIcon
                                AppTab.Settings -> Icons.Outlined.Settings
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

private val ShelfIcon = ImageVector.Builder("Shelf", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(12f, 6f); curveTo(9f, 4f, 5f, 4f, 2.5f, 5.5f)
        lineTo(2.5f, 19f); curveTo(5f, 17.5f, 9f, 17.5f, 12f, 19.5f)
        curveTo(15f, 17.5f, 19f, 17.5f, 21.5f, 19f)
        lineTo(21.5f, 5.5f); curveTo(19f, 4f, 15f, 4f, 12f, 6f)
        lineTo(12f, 19.5f)
    }
}.build()

private val NotesIcon = ImageVector.Builder("Notes", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(6f, 3f); lineTo(18f, 3f); quadTo(20f, 3f, 20f, 5f)
        lineTo(20f, 19f); quadTo(20f, 21f, 18f, 21f)
        lineTo(6f, 21f); quadTo(4f, 21f, 4f, 19f)
        lineTo(4f, 5f); quadTo(4f, 3f, 6f, 3f)
        moveTo(8f, 8f); lineTo(16f, 8f)
        moveTo(8f, 12f); lineTo(16f, 12f)
        moveTo(8f, 16f); lineTo(13f, 16f)
    }
}.build()
