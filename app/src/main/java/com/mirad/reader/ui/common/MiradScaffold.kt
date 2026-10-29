package com.mirad.reader.ui.common

import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

internal val LocalAppBottomBar = staticCompositionLocalOf<@Composable () -> Unit> { {} }
internal val LocalAppRootPage = staticCompositionLocalOf { false }
internal val LocalPageSafeInsets = staticCompositionLocalOf<WindowInsets?> { null }

/** 页面正文和玻璃控件分属两个原生平面，浮层不进入自己的背景采样。 */
@Composable
internal fun GlassScene(
    modal: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
    controls: @Composable () -> Unit
) {
    val source = rememberHardwareGlassBackdrop(Unit)
    val body = rememberUpdatedState(content)
    val chrome = rememberUpdatedState(controls)
    val background = MaterialTheme.colorScheme.background.toArgb()
    val savedState = rememberSaveableStateHolder()
    CompositionLocalProvider(LocalGlassBackdrop provides null, LocalHardwareGlassBackdrop provides source) {
        val parent = rememberCompositionContext()
        AndroidView(
            modifier = modifier,
            factory = { context ->
                val bodyView = ComposeView(context).apply {
                    id = View.generateViewId()
                    consumeWindowInsets = false
                    setParentCompositionContext(parent)
                    setContent { savedState.SaveableStateProvider("content") {
                        Box(Modifier.fillMaxSize().focusRestorer().focusGroup()) { body.value() }
                    } }
                }
                val controlsView = ComposeView(context).apply {
                    id = View.generateViewId()
                    consumeWindowInsets = false
                    setParentCompositionContext(parent)
                    setContent { savedState.SaveableStateProvider("controls") { chrome.value() } }
                }
                val host = if (Build.VERSION.SDK_INT >= 31) GlassBackdropView(context) else GlassPlaneView(context)
                host.setPlanes(bodyView, controlsView)
                if (Build.VERSION.SDK_INT >= 31 && host is GlassBackdropView && source != null) {
                    host.onContentRecorded = { source.contentChanged("page") }
                    source.attach(host, host.contentLayer)
                }
                host
            },
            update = { host ->
                host.setContentBlocked(modal)
                if (Build.VERSION.SDK_INT >= 31 && host is GlassBackdropView) {
                    host.backdropColor = background
                    host.gpuEnabled = source?.renderingActive == true
                }
            },
            onRelease = { host ->
                host.setContentBlocked(false)
                if (Build.VERSION.SDK_INT >= 31) source?.detach(host)
                host.onContentRecorded = null
                (0 until host.childCount).forEach { (host.getChildAt(it) as? ComposeView)?.disposeComposition() }
                host.removeAllViews()
            }
        )
    }
}

/** 统一安全区与控件位置；正文只接收留白，实际顶栏和底栏在控制平面绘制。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MiradScaffold(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.background,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = LocalAppBottomBar.current,
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    val localOverlays = LocalGlassOverlayHost.current
    val overlays = localOverlays ?: remember { GlassOverlayState() }
    var modal by remember { mutableStateOf(false) }
    var topHeight by remember { mutableIntStateOf(-1) }
    var bottomHeight by remember { mutableIntStateOf(-1) }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val safeInsets = LocalPageSafeInsets.current ?: glassSafeInsets()
    val keyboard = WindowInsets.isImeVisible
    val rootFeedback = LocalRootFeedback.current
    CompositionLocalProvider(LocalGlassOverlayHost provides overlays, LocalPageSafeInsets provides safeInsets) {
        GlassScene(modal, modifier.fillMaxSize(), content = {
            Box(Modifier.fillMaxSize().background(containerColor)) {
                // 直接传递已测留白，避免第二个Scaffold的可变padding在首帧改写滚动范围。
                if (topHeight >= 0 && bottomHeight >= 0) {
                    val padding = with(density) { PaddingValues(
                        start = safeInsets.getLeft(density, direction).toDp(),
                        top = maxOf(topHeight, safeInsets.getTop(density)).toDp(),
                        end = safeInsets.getRight(density, direction).toDp(),
                        bottom = maxOf(bottomHeight, safeInsets.getBottom(density)).toDp()
                    ) }
                    CompositionLocalProvider(LocalContentColor provides contentColorFor(containerColor)) { content(padding) }
                }
            }
        }, controls = {
            overlays.Render(Modifier, { modal = it }) {
                // 控制平面的空白区域必须允许事件穿透到正文，不能放全屏 Material Surface。
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.align(androidx.compose.ui.Alignment.TopCenter)
                        .onSizeChangedForChrome { topHeight = it }) { topBar() }
                    Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                        .onSizeChangedForChrome { bottomHeight = it }) {
                            if (!keyboard) bottomBar()
                    }
                    val bottom = with(density) { bottomHeight.coerceAtLeast(0).toDp() }
                    Box(Modifier.align(androidx.compose.ui.Alignment.BottomEnd).imePadding()
                        .padding(end = 20.dp, bottom = bottom + 16.dp)) { floatingActionButton() }
                    Column(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth()
                        .imePadding().padding(bottom = bottom)) {
                            snackbarHost()
                            if (rootFeedback != null) FeedbackHost(rootFeedback)
                    }
                }
            }
        })
    }
}

private fun Modifier.onSizeChangedForChrome(onHeight: (Int) -> Unit): Modifier =
    this.then(Modifier.onSizeChanged { onHeight(it.height) })
