package com.mirad.reader.ui.common

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import com.mirad.reader.ui.common.bouncingVerticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mirad.reader.ui.common.GlassDropdownMenu
import com.mirad.reader.ui.common.GlassStyle
import com.mirad.reader.ui.common.glassBackground
import com.mirad.reader.ui.design.Spacing
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class GlassOverlayKind(val priority: Int) { Sheet(0), Menu(1), Dialog(2) }

private class OverlayBackOwner(override val lifecycle: Lifecycle) : OnBackPressedDispatcherOwner {
    override val onBackPressedDispatcher = OnBackPressedDispatcher()
}

private class GlassOverlayEntry(
    val id: Any,
    val kind: GlassOverlayKind,
    val localContext: State<CompositionLocalContext>,
    val dismiss: () -> Unit,
    val content: @Composable () -> Unit
) {
    var backDispatcher: OnBackPressedDispatcher? = null
    var parkedFocus: FocusRequester? = null
}

/** 浮层属于当前页面，由页面销毁时释放，应用不持有全局浮层单例。 */
@Stable
internal class GlassOverlayState internal constructor() {
    private val entries = mutableStateListOf<GlassOverlayEntry>()
    internal val hasEntries: Boolean get() = entries.isNotEmpty()
    internal var windowOrigin by mutableStateOf(Offset.Zero)
        private set
    private val orderedEntries: List<GlassOverlayEntry>
        get() = entries.sortedBy { it.kind.priority }

    private fun add(entry: GlassOverlayEntry) { entries += entry }
    private fun remove(entry: GlassOverlayEntry) { entries.remove(entry) }

    companion object {
        @Composable
        internal fun Register(
            host: GlassOverlayState,
            kind: GlassOverlayKind,
            onDismiss: () -> Unit,
            content: @Composable () -> Unit
        ) {
            val latestContent = rememberUpdatedState(content)
            val latestDismiss = rememberUpdatedState(onDismiss)
            val latestLocals = rememberUpdatedState(currentCompositionLocalContext)
            val entry = remember(host, kind) {
                GlassOverlayEntry(Any(), kind, latestLocals, { latestDismiss.value() }) {
                    latestContent.value()
                }
            }
            DisposableEffect(host, entry) {
                host.add(entry)
                onDispose { host.remove(entry) }
            }
        }
    }

    @Composable
    internal fun Render(
        modifier: Modifier,
        onModalVisibilityChanged: (Boolean) -> Unit,
        content: @Composable () -> Unit
    ) {
        val layers = orderedEntries
        val hardwareBackdrop = LocalHardwareGlassBackdrop.current
        val legacyBackdrop = LocalGlassBackdrop.current
        val top = layers.lastOrNull()
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val navigationOwner = remember(lifecycle) { OverlayBackOwner(lifecycle) }
        val focusManager = LocalFocusManager.current
        val windowView = LocalView.current
        val keyboard = LocalSoftwareKeyboardController.current
        val density = LocalDensity.current
        val imeVisible = WindowInsets.ime.getBottom(density) > 0
        val baseFocus = remember { FocusRequester() }
        var hadModal by remember { mutableStateOf(false) }
        var baseFocused by remember { mutableStateOf(false) }
        var restoreComposeFocus by remember { mutableStateOf(false) }
        val latestModalCallback by rememberUpdatedState(onModalVisibilityChanged)

        SideEffect { latestModalCallback(top != null) }
        DisposableEffect(Unit) { onDispose { latestModalCallback(false) } }
        LaunchedEffect(top?.id) {
            if (top != null && !hadModal) restoreComposeFocus = baseFocused
            if (top == null && hadModal && restoreComposeFocus) baseFocus.requestFocus()
            hadModal = top != null
        }

        fun back() {
            val current = top ?: return
            dispatchGlassOverlayBack(
                kind = current.kind,
                imeVisible = imeVisible,
                contentHandlesBack = current.backDispatcher?.hasEnabledCallbacks() == true,
                navigationHandlesBack = navigationOwner.onBackPressedDispatcher.hasEnabledCallbacks(),
                hideIme = {
                    keyboard?.hide()
                    focusManager.clearFocus(force = true)
                    current.parkedFocus?.requestFocus()
                },
                contentBack = { current.backDispatcher?.onBackPressed() },
                navigationBack = { navigationOwner.onBackPressedDispatcher.onBackPressed() },
                dismiss = current.dismiss
            )
        }

        CompositionLocalProvider(LocalGlassOverlayHost provides this) {
            Box(modifier.fillMaxSize().onGloballyPositioned { windowOrigin = it.positionInWindow() }) {
                CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides navigationOwner) {
                    Box(Modifier.fillMaxSize()
                        .then(if (top != null) Modifier.clearAndSetSemantics { } else Modifier)
                        .focusRequester(baseFocus).focusRestorer()
                        .onFocusChanged { baseFocused = it.hasFocus }
                        .focusProperties { onEnter = { if (top != null) cancelFocusChange() } }
                        .focusGroup()) { content() }
                }
                layers.forEach { entry ->
                    key(entry.id) {
                        val active = entry === top
                        val owner = remember(lifecycle) { OverlayBackOwner(lifecycle) }
                        val requester = remember { FocusRequester() }
                        val parkedFocus = remember { FocusRequester() }
                        SideEffect {
                            entry.backDispatcher = owner.onBackPressedDispatcher
                            entry.parkedFocus = parkedFocus
                        }
                        DisposableEffect(entry, owner) {
                            onDispose {
                                if (entry.backDispatcher === owner.onBackPressedDispatcher) entry.backDispatcher = null
                                if (entry.parkedFocus === parkedFocus) entry.parkedFocus = null
                            }
                        }
                        LaunchedEffect(active) {
                            if (active) {
                                focusManager.clearFocus(force = true)
                                // A group's default request selects its first TextField.
                                // New sheets start on a non-input target; existing sheets
                                // may restore the control focused before a nested dialog.
                                if (!requester.restoreFocusedChild()) parkedFocus.requestFocus()
                            } else requester.saveFocusedChild()
                        }
                        CompositionLocalProvider(entry.localContext.value) {
                            CompositionLocalProvider(
                                LocalHardwareGlassBackdrop provides hardwareBackdrop,
                                LocalGlassBackdrop provides legacyBackdrop,
                                LocalFocusManager provides focusManager,
                                LocalView provides windowView,
                                LocalSoftwareKeyboardController provides keyboard,
                                LocalOnBackPressedDispatcherOwner provides owner
                            ) {
                                Box(Modifier.fillMaxSize()
                                    .then(if (!active) Modifier.clearAndSetSemantics { } else Modifier)
                                    .focusRequester(requester)
                                    .focusProperties {
                                        onEnter = { if (!active) cancelFocusChange() }
                                        onExit = { if (active) cancelFocusChange() }
                                    }.focusGroup()
                                    .onPreviewKeyEvent { event ->
                                        if (!active || event.type != KeyEventType.KeyDown) false
                                        else when (event.key) {
                                            Key.Escape -> { back(); true }
                                            Key.Tab -> {
                                                val direction = if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next
                                                if (!focusManager.moveFocus(direction)) {
                                                    focusManager.clearFocus(force = true)
                                                    requester.requestFocus(direction)
                                                }
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                                    .pointerInput(active) {
                                        if (active) awaitPointerEventScope {
                                            while (true) {
                                                awaitPointerEvent(PointerEventPass.Final).changes.forEach {
                                                    // 保留按下/抬起的模态隔离。移动事件留给子列表越过 touch slop，
                                                    // 否则 Final 阶段提前消费移动会取消滚动与边界回弹。
                                                    if (!it.isConsumed && it.previousPressed != it.pressed) it.consume()
                                                }
                                            }
                                        }
                                    }) {
                                    Box(Modifier.size(1.dp).focusRequester(parkedFocus).focusable()
                                        .clearAndSetSemantics { })
                                    entry.content()
                                }
                            }
                        }
                    }
                }
            }
        }
        // This belongs to the Activity dispatcher. Business navigation handlers above
        // are isolated so a lower sheet cannot dismiss a confirmation on top of it.
        BackHandler(enabled = top != null) { back() }
    }
}

internal val LocalGlassOverlayHost = staticCompositionLocalOf<GlassOverlayState?> { null }

@Composable
internal fun GlassOverlayHost(
    modifier: Modifier = Modifier,
    onModalVisibilityChanged: (Boolean) -> Unit = {},
    content: @Composable () -> Unit
) {
    remember { GlassOverlayState() }.Render(modifier, onModalVisibilityChanged, content)
}

/** Standard Material sheet gestures and nested scrolling, drawn in this window. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun GlassInWindowSheet(
    host: GlassOverlayState,
    title: String,
    onDismiss: () -> Unit,
    subtitle: String?,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier,
    fillHeight: Boolean,
    maxHeightFraction: Float,
    content: @Composable ColumnScope.() -> Unit
) {
    val state = rememberStandardBottomSheetState(initialValue = SheetValue.Hidden, skipHiddenState = false)
    val scaffold = rememberBottomSheetScaffoldState(state)
    val scope = rememberCoroutineScope()
    val latestDismiss by rememberUpdatedState(onDismiss)
    var closing by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    var contentHeight by remember { mutableIntStateOf(0) }
    val close = {
        if (!closing) {
            closing = true
            scope.launch { state.hide(); latestDismiss() }
        }
        Unit
    }
    fun expandSheet(): Boolean {
        if (closing) return false
        scope.launch { if (!closing) state.expand() }
        return true
    }
    fun collapseSheet(): Boolean {
        if (closing) return false
        scope.launch { if (!closing) state.partialExpand() }
        return true
    }
    LaunchedEffect(state.currentValue) {
        if (state.currentValue != SheetValue.Hidden) shown = true
        else if (shown && !closing) { closing = true; latestDismiss() }
    }
    GlassOverlayState.Register(host, GlassOverlayKind.Sheet, close) {
        val density = LocalDensity.current
        val imeVisible = WindowInsets.ime.getBottom(density) > 0
        val safe = glassSafeInsets().union(WindowInsets.ime)
        // A partially expanded sheet extends below its visible anchor. Keep its scroll content
        // inside the safe viewport instead of painting controls under the gesture bar or IME.
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(safe).clipToBounds()) {
            val available = with(density) { maxHeight.roundToPx() }
            val peek = with(density) { glassSheetPeekHeight(contentHeight, available).toDp() }
            // The fraction belongs to the reading viewport, not to the reduced IME
            // area. Otherwise opening the keyboard shrinks the sheet a second time.
            val sceneHeight = maxHeight + with(density) {
                (safe.getTop(density) + safe.getBottom(density)).toDp()
            }
            val maxBodyHeight = minOf(sceneHeight * maxHeightFraction.coerceIn(.1f, 1f), maxHeight)
            LaunchedEffect(contentHeight) {
                if (contentHeight > 0 && !shown && !closing) state.show()
            }
            LaunchedEffect(imeVisible, shown, state.hasExpandedState) {
                if (imeVisible && shown && !closing && state.hasExpandedState) state.expand()
            }
            BottomSheetScaffold(
                modifier = Modifier.fillMaxSize(),
                scaffoldState = scaffold,
                sheetPeekHeight = peek,
                sheetMaxWidth = 720.dp,
                sheetShape = MaterialTheme.shapes.extraLarge,
                sheetContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                sheetContentColor = MaterialTheme.colorScheme.onSurface,
                sheetTonalElevation = 0.dp,
                sheetShadowElevation = 0.dp,
                sheetDragHandle = null,
                sheetSwipeEnabled = !closing,
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                snackbarHost = {},
                sheetContent = {
                    CompositionLocalProvider(com.mirad.reader.ui.common.LocalEdgeBounceAllowed provides { delta ->
                        delta < 0f && state.currentValue == SheetValue.Expanded && state.targetValue == SheetValue.Expanded && !closing
                    }) {
                    Column(Modifier.fillMaxWidth().onSizeChanged { contentHeight = it.height }
                        .glassBackground(GlassStyle.Panel, MaterialTheme.shapes.extraLarge)
                        .semantics { paneTitle = title; isTraversalGroup = true }) {
                        BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally)
                            .clickable(enabled = !closing) {
                                if (state.currentValue == SheetValue.PartiallyExpanded && state.hasExpandedState) expandSheet()
                                else close()
                            }.semantics(mergeDescendants = true) {
                                if (!closing) {
                                    dismiss { if (closing) false else { close(); true } }
                                    if (state.currentValue == SheetValue.PartiallyExpanded && state.hasExpandedState) expand { expandSheet() }
                                    else if (state.hasPartiallyExpandedState) collapse { collapseSheet() }
                                }
                            })
                        Column(modifier.fillMaxWidth().heightIn(max = maxBodyHeight)) {
                            Row(Modifier.fillMaxWidth().padding(start = Spacing.xl, end = Spacing.md, bottom = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                }
                                actions()
                            }
                            Column(Modifier.fillMaxWidth().weight(1f, fill = fillHeight)
                                .then(if (fillHeight) Modifier else Modifier.bouncingVerticalScroll(rememberScrollState())),
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm), content = content)
                            Spacer(Modifier.height(Spacing.md))
                        }
                    }
                    }
                }
            ) {
                GlassModalScrim(close)
            }
        }
    }
}

@Composable
private fun GlassModalScrim(onDismiss: () -> Unit, dim: Boolean = true) {
    val dismiss by rememberUpdatedState(onDismiss)
    Box(Modifier.fillMaxSize()
        .then(if (dim) Modifier.background(MaterialTheme.colorScheme.scrim.copy(alpha = .32f)) else Modifier)
        .pointerInput(Unit) { detectTapGestures(onTap = { dismiss() }) }
        .semantics { onClick("关闭") { dismiss(); true } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlassInWindowDialog(
    host: GlassOverlayState,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier,
    dismissButton: (@Composable () -> Unit)?,
    title: (@Composable () -> Unit)?,
    text: (@Composable () -> Unit)?,
    icon: (@Composable () -> Unit)?
) {
    GlassOverlayState.Register(host, GlassOverlayKind.Dialog, onDismissRequest) {
        GlassModalScrim(onDismissRequest)
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(glassSafeInsets().union(WindowInsets.ime)).clipToBounds(), contentAlignment = Alignment.Center) {
            val compact = maxHeight < 240.dp
            val outerVertical = if (compact) 0.dp else 24.dp
            val innerPadding = if (compact) 16.dp else 24.dp
            val verticalPadding = if (compact) 4.dp else innerPadding
            val availableHeight = (maxHeight - outerVertical * 2f).coerceAtLeast(0.dp)
            val bodyLimit = availableHeight * .55f
            val outerScroll = rememberScrollState()
            val bodyScroll = rememberScrollState()
            val bodyBringIntoView = remember { BringIntoViewRequester() }
            var bodyCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
            var focusedCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
            LaunchedEffect(availableHeight, compact, bodyCoordinates, focusedCoordinates) {
                // Insets can shrink an already-focused field without another focus event.
                // Wait for the new layout, then request the full, unclipped focus rectangle.
                withFrameNanos { }
                val owner = bodyCoordinates
                val focused = focusedCoordinates
                if (owner?.isAttached == true && focused?.isAttached == true) {
                    val target = owner.localBoundingBoxOf(focused, clipBounds = false)
                    if (!target.isEmpty) bodyBringIntoView.bringIntoView(target)
                }
            }
            Surface(modifier.padding(horizontal = 24.dp, vertical = outerVertical)
                .widthIn(max = 560.dp).fillMaxWidth().heightIn(max = availableHeight)
                .glassBackground(GlassStyle.Panel, MaterialTheme.shapes.extraLarge)
                .semantics { dialog(); isTraversalGroup = true },
                color = androidx.compose.ui.graphics.Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.extraLarge) {
                // One scroll container owns caret visibility in a short IME viewport.
                // Keep the body call at the same composition position while insets change.
                Column(Modifier.padding(horizontal = innerPadding, vertical = verticalPadding).bouncingVerticalScroll(outerScroll),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp)) {
                    icon?.let { Box(Modifier.align(Alignment.CenterHorizontally)) { it() } }
                    title?.let { ProvideTextStyle(MaterialTheme.typography.headlineSmall, it) }
                    text?.let { body ->
                        CompositionLocalProvider(
                            LocalDialogBodyHeight provides if (compact)
                                (availableHeight - verticalPadding * 2f).coerceAtLeast(0.dp) else androidx.compose.ui.unit.Dp.Infinity,
                            LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                            com.mirad.reader.ui.common.LocalEdgeBounceAllowed provides { delta ->
                                if (delta < 0f) !outerScroll.canScrollForward else !outerScroll.canScrollBackward
                            }
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                Column(Modifier.fillMaxWidth().then(
                                    if (compact) Modifier
                                    else Modifier.heightIn(max = bodyLimit).bouncingVerticalScroll(bodyScroll)
                                ).bringIntoViewRequester(bodyBringIntoView)
                                    .onFocusedBoundsChanged { focusedCoordinates = it }
                                    .onGloballyPositioned { bodyCoordinates = it }) { body() }
                            }
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}

/** Uses the same anchor box as DropdownMenu while the portal remains at reader root. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlassPortalMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    com.mirad.reader.ui.common.ResetEdgeBounceOnOverlay(expanded)
    val host = LocalGlassOverlayHost.current
    if (host == null) {
        GlassDropdownMenu(expanded, onDismissRequest, modifier, content)
        return
    }
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    Box(Modifier.size(0.dp).onGloballyPositioned { coordinates ->
        val bounds = coordinates.parentLayoutCoordinates?.boundsInWindow() ?: coordinates.boundsInWindow()
        anchor = IntRect(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
    })
    if (expanded && anchor.width > 0 && anchor.height > 0) GlassOverlayState.Register(host, GlassOverlayKind.Menu, onDismissRequest) {
        GlassModalScrim(onDismissRequest, dim = false)
        val safeInsets = glassSafeInsets().union(WindowInsets.ime)
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val margin = with(density) { 8.dp.roundToPx() }
        val left = safeInsets.getLeft(density, direction) + margin
        val top = safeInsets.getTop(density) + margin
        val right = safeInsets.getRight(density, direction) + margin
        val bottom = safeInsets.getBottom(density) + margin
        Layout(modifier = Modifier.fillMaxSize().clipToBounds(), content = {
            Surface(modifier.width(IntrinsicSize.Max).widthIn(min = 160.dp, max = 280.dp)
                .glassBackground(GlassStyle.Panel, MaterialTheme.shapes.large)
                .semantics { paneTitle = "更多"; isTraversalGroup = true },
                color = androidx.compose.ui.graphics.Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(vertical = 8.dp).bouncingVerticalScroll(rememberScrollState()), content = content)
            }
        }) { measurables, constraints ->
            val safe = IntRect(left, top, max(left, constraints.maxWidth - right), max(top, constraints.maxHeight - bottom))
            val placeable = measurables.single().measure(Constraints(maxWidth = safe.width, maxHeight = safe.height))
            val origin = host.windowOrigin
            val localAnchor = anchor.translate(-origin.x.roundToInt(), -origin.y.roundToInt())
            val position = glassMenuPosition(localAnchor, IntSize(placeable.width, placeable.height), safe, direction)
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(position.x, position.y) }
        }
    }
}

internal fun glassSheetPeekHeight(contentHeight: Int, availableHeight: Int): Int =
    min(contentHeight.coerceAtLeast(0), availableHeight.coerceAtLeast(0) / 2)

/** Lower sheet navigation must never consume a confirmation or menu's Back action. */
internal fun dispatchGlassOverlayBack(
    kind: GlassOverlayKind,
    imeVisible: Boolean,
    contentHandlesBack: Boolean,
    navigationHandlesBack: Boolean,
    hideIme: () -> Unit,
    contentBack: () -> Unit,
    navigationBack: () -> Unit,
    dismiss: () -> Unit
) {
    when {
        imeVisible -> hideIme()
        contentHandlesBack -> contentBack()
        kind == GlassOverlayKind.Sheet && navigationHandlesBack -> navigationBack()
        else -> dismiss()
    }
}

internal fun glassMenuPosition(anchor: IntRect, menu: IntSize, safe: IntRect, direction: LayoutDirection): IntOffset {
    val preferredX = if (direction == LayoutDirection.Ltr) anchor.left else anchor.right - menu.width
    val alternateX = if (direction == LayoutDirection.Ltr) anchor.right - menu.width else anchor.left
    val x = when {
        preferredX >= safe.left && preferredX + menu.width <= safe.right -> preferredX
        alternateX >= safe.left && alternateX + menu.width <= safe.right -> alternateX
        else -> preferredX.coerceIn(safe.left, max(safe.left, safe.right - menu.width))
    }
    val y = when {
        anchor.bottom + menu.height <= safe.bottom -> max(safe.top, anchor.bottom)
        anchor.top - menu.height >= safe.top -> anchor.top - menu.height
        else -> anchor.bottom.coerceIn(safe.top, max(safe.top, safe.bottom - menu.height))
    }
    return IntOffset(x, y)
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun glassSafeInsets(): WindowInsets =
    WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
