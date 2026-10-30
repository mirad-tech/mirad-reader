package com.mirad.reader.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Only the incoming screen exists; never crossfade two reader sessions. */
@Composable
fun PageSurface(pageKey: String, bounded: Boolean, animate: Boolean, content: @Composable () -> Unit) {
    val overlays = remember(pageKey) { GlassOverlayState() }
    val pager = LocalPagerChrome.current
    val owner = LocalPagerPage.current
    DisposableEffect(pager, owner, overlays) {
        if (owner != null) pager?.overlays?.set(owner, overlays)
        onDispose { if (owner != null && pager?.overlays?.get(owner) === overlays) pager.overlays.remove(owner) }
    }
    val alpha = remember(pageKey) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(pageKey) { if (animate) alpha.animateTo(1f, tween(160)) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.then(if (bounded) Modifier.widthIn(max = 720.dp) else Modifier)
            .fillMaxSize().graphicsLayer { this.alpha = alpha.value }) {
            CompositionLocalProvider(LocalGlassOverlayHost provides overlays) { content() }
        }
    }
}
