package com.mirad.reader.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import kotlin.reflect.KProperty

enum class FeedbackKind { INFO, SUCCESS, ERROR }

val LocalRootFeedback = staticCompositionLocalOf<FeedbackState?> { null }

data class UiFeedback(val id: Long, val owner: String, val text: String, val kind: FeedbackKind)

/** A disposed screen cannot publish late callbacks, even if its work continues elsewhere. */
@Stable
class FeedbackState(val owner: String) {
    var message by mutableStateOf<UiFeedback?>(null)
        private set
    private var sequence = 0L
    private var active = true

    fun show(text: String, kind: FeedbackKind = FeedbackKind.INFO) {
        if (active) message = UiFeedback(++sequence, owner, text, kind)
    }

    fun dismiss(id: Long? = message?.id) {
        if (message?.id == id) message = null
    }

    fun dispose() { active = false; message = null }
    operator fun getValue(thisRef: Any?, property: KProperty<*>): String? = message?.text
    operator fun setValue(thisRef: Any?, property: KProperty<*>, text: String?) {
        if (text == null) dismiss() else show(text)
    }
}

@Composable
fun rememberFeedbackState(owner: String): FeedbackState {
    val state = remember(owner) { FeedbackState(owner) }
    DisposableEffect(state) { onDispose { state.dispose() } }
    return state
}

@Composable
fun FeedbackHost(state: FeedbackState, modifier: Modifier = Modifier) {
    val message = state.message
    var displayed by remember { mutableStateOf<UiFeedback?>(null) }
    SideEffect { if (message != null) displayed = message }
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(message?.id) {
        if (message != null) {
            delay(accessibility?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = 3000L, containsIcons = true,
                containsText = true, containsControls = true
            ) ?: 3000L)
            state.dismiss(message.id)
        }
    }
    val distance = with(LocalDensity.current) { 8.dp.roundToPx() }
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = fadeIn(tween(160)) + slideInVertically(tween(160)) { distance },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { distance }
    ) {
        displayed?.let { value ->
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    .glassBackground(GlassStyle.Panel, MaterialTheme.shapes.large)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                shape = MaterialTheme.shapes.large,
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(when (value.kind) {
                        FeedbackKind.INFO -> Icons.Default.Info
                        FeedbackKind.SUCCESS -> Icons.Default.CheckCircle
                        FeedbackKind.ERROR -> Icons.Default.Warning
                    }, contentDescription = when (value.kind) {
                        FeedbackKind.INFO -> "提示"
                        FeedbackKind.SUCCESS -> "成功"
                        FeedbackKind.ERROR -> "错误"
                    }, tint = if (value.kind == FeedbackKind.ERROR) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text(value.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { state.dismiss(value.id) }) {
                        Icon(Icons.Default.Close, "关闭提示", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
