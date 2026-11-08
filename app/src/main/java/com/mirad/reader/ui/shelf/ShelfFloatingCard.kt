package com.mirad.reader.ui.shelf

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mirad.reader.data.imports.ImportItem
import com.mirad.reader.data.repo.ShelfBook
import com.mirad.reader.ui.common.GlassStyle
import com.mirad.reader.ui.common.GlassSurface
import com.mirad.reader.ui.common.MiradLinearProgress
import kotlinx.coroutines.delay

private sealed interface ShelfCardPresentation {
    data class Import(val task: ShelfImportPresentation) : ShelfCardPresentation
    data class Resume(val book: ShelfBook) : ShelfCardPresentation
}

/** 续读与导入共用一张非模态玻璃卡，实际挂在 Scaffold 的控制层。 */
@Composable
internal fun ShelfFloatingCard(
    available: Boolean,
    resume: ShelfBook?,
    prompt: ShelfResumePromptState,
    importing: Boolean,
    importItems: List<ImportItem>,
    onOpenBook: (Long) -> Unit
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var touching by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val content = resolveShelfFloatingContent(available && resumed, importing, resume != null, prompt.consumed)
    val accessibility = LocalAccessibilityManager.current
    val timeout = accessibility?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = ShelfResumePromptState.DEFAULT_TIMEOUT_MILLIS,
        containsIcons = true, containsText = true, containsControls = true
    ) ?: ShelfResumePromptState.DEFAULT_TIMEOUT_MILLIS
    LaunchedEffect(prompt, content, touching, focused, timeout) {
        if (content == ShelfFloatingContent.RESUME && !touching && !focused) {
            val started = SystemClock.elapsedRealtime()
            try {
                delay(prompt.remainingMillis(timeout))
                prompt.dismiss()
            } finally {
                // 暂避、切页与后台只累计已经可见的时间；导入不会被这个超时关闭。
                prompt.recordVisibleTime(SystemClock.elapsedRealtime() - started, timeout)
            }
        }
    }
    val visibleCard = when (content) {
        ShelfFloatingContent.IMPORT -> ShelfCardPresentation.Import(shelfImportPresentation(importItems))
        ShelfFloatingContent.RESUME -> ShelfCardPresentation.Resume(checkNotNull(resume))
        ShelfFloatingContent.NONE -> null
    }
    var lastVisibleCard by remember { mutableStateOf<ShelfCardPresentation?>(null) }
    if (visibleCard != null) SideEffect { lastVisibleCard = visibleCard }

    AnimatedVisibility(
        visible = visibleCard != null,
        enter = fadeIn(tween(160)), exit = fadeOut(tween(160))
    ) {
        // 退场期间绘制最后一帧的业务内容，不从最新队列或续读书籍重新选卡。
        val card = visibleCard ?: lastVisibleCard ?: return@AnimatedVisibility
        GlassSurface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                .onFocusChanged { focused = it.hasFocus }
                .focusGroup()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        touching = true
                        try {
                            do { val event = awaitPointerEvent(PointerEventPass.Initial) }
                            while (event.changes.any { it.pressed })
                        } finally { touching = false }
                    }
                },
            style = GlassStyle.Panel,
            shape = MaterialTheme.shapes.large
        ) {
            if (card is ShelfCardPresentation.Import) {
                val task = card.task
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(task.stage, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f))
                        if (task.pendingCount > 0) Text("待处理 ${task.pendingCount} 项",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    task.fileName?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (task.progress != null) {
                        MiradLinearProgress(progress = { task.progress },
                            modifier = Modifier.fillMaxWidth().height(3.dp))
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
                    }
                }
            } else if (card is ShelfCardPresentation.Resume) {
                val book = card.book
                val actionsEnabled = visibleCard is ShelfCardPresentation.Resume
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clip(MaterialTheme.shapes.medium)
                        .clickable(enabled = actionsEnabled, role = Role.Button, onClickLabel = "继续阅读") {
                            onOpenBook(book.book.id)
                        }
                        .padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BookCover(book.book, Modifier.size(40.dp, 56.dp), titleSize = 12)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("继续阅读", style = MaterialTheme.typography.labelMedium)
                            Text(book.book.title, style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("已读 ${(book.percent * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(enabled = actionsEnabled, onClick = prompt::dismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭继续阅读",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
