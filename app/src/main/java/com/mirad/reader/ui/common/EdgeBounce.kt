package com.mirad.reader.ui.common

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/** 仅消费滚动容器在边界剩余的纵向位移，不接管 pointer down 或横向手势。 */
internal class EdgeBounceState {
    var offsetPx by mutableFloatStateOf(0f)
        private set

    fun pull(delta: Float, maxOffset: Float): Float {
        if (!delta.isFinite() || !maxOffset.isFinite() || maxOffset <= 0f) return 0f
        offsetPx = (offsetPx + delta * RESISTANCE).coerceIn(-maxOffset, maxOffset)
        return delta
    }

    fun unwind(delta: Float): Float {
        if (!delta.isFinite() || offsetPx == 0f || delta * offsetPx >= 0f) return 0f
        val consumed = sign(delta) * min(abs(delta), abs(offsetPx) / RESISTANCE)
        offsetPx += consumed * RESISTANCE
        if (abs(offsetPx) < .01f) offsetPx = 0f
        return consumed
    }

    fun animate(value: Float) { offsetPx = value }
    fun reset() { offsetPx = 0f }

    companion object {
        const val RESISTANCE = .25f
        const val MAX_DP = 24f
        const val STIFFNESS = 400f
        const val DAMPING = 1f
    }
}

internal class EdgeBounceCoordinator {
    var generation by mutableIntStateOf(0)
        private set
    fun reset() { generation++ }
}

internal val LocalEdgeBounceCoordinator = staticCompositionLocalOf<EdgeBounceCoordinator?> { null }
/** 弹层仍能沿该方向移动时返回 false，让未消费的位移继续交给外层。 */
internal val LocalEdgeBounceAllowed = staticCompositionLocalOf<(Float) -> Boolean> { { true } }
/** 预览专用观察口：默认不记录，不向可见 UI 或无障碍树输出技术信息。 */
internal val LocalEdgeBounceObserver = staticCompositionLocalOf<((Int, Float, Boolean) -> Unit)?> { null }

@Composable
internal fun ResetEdgeBounceOnOverlay(visible: Boolean = true) {
    val coordinator = LocalEdgeBounceCoordinator.current
    LaunchedEffect(visible) { if (visible) coordinator?.reset() }
}

internal fun Modifier.edgeBounce(): Modifier = composed {
    val state = remember { EdgeBounceState() }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val maxOffset = with(LocalDensity.current) { EdgeBounceState.MAX_DP.dp.toPx() }
    val allow by rememberUpdatedState(LocalEdgeBounceAllowed.current)
    val observer by rememberUpdatedState(LocalEdgeBounceObserver.current)
    val generation = LocalEdgeBounceCoordinator.current?.generation
    var job by remember { mutableStateOf<Job?>(null) }
    var animationGeneration by remember { mutableIntStateOf(0) }
    val id = remember(state) { System.identityHashCode(state) }

    fun cancel(reset: Boolean) {
        animationGeneration++
        job?.cancel()
        job = null
        if (reset) state.reset()
        observer?.invoke(id, state.offsetPx, false)
    }
    fun settle() {
        if (job?.isActive == true || state.offsetPx == 0f) return
        val token = ++animationGeneration
        job = scope.launch {
            AnimationState(state.offsetPx).animateTo(0f,
                spring(dampingRatio = EdgeBounceState.DAMPING, stiffness = EdgeBounceState.STIFFNESS, visibilityThreshold = .1f)) {
                if (token == animationGeneration) {
                    state.animate(value)
                    observer?.invoke(id, value, true)
                }
            }
            if (token == animationGeneration) {
                state.reset()
                job = null
                observer?.invoke(id, 0f, false)
            }
        }
    }
    LaunchedEffect(generation, maxOffset) { cancel(reset = true) }
    DisposableEffect(lifecycle, state) {
        val listener = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) cancel(reset = true)
        }
        lifecycle.addObserver(listener)
        onDispose { lifecycle.removeObserver(listener); cancel(reset = true) }
    }
    val connection = remember(state, maxOffset) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                cancel(reset = false)
                val consumed = state.unwind(available.y)
                observer?.invoke(id, state.offsetPx, false)
                return Offset(0f, consumed)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y == 0f || !allow(available.y)) return Offset.Zero
                val result = state.pull(available.y, maxOffset)
                observer?.invoke(id, state.offsetPx, false)
                return Offset(0f, result)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                settle()
                return Velocity.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val atBoundary = available.y != 0f && allow(available.y)
                if (atBoundary && state.offsetPx == 0f && abs(available.y) >= 60f) {
                    // 把边界剩余速度换成一帧的受阻位移；不再启动第二段惯性滚动。
                    state.pull(available.y / 60f, maxOffset)
                    observer?.invoke(id, state.offsetPx, false)
                }
                settle()
                return if (atBoundary) Velocity(0f, available.y) else Velocity.Zero
            }
        }
    }
    this.nestedScroll(connection).graphicsLayer { translationY = state.offsetPx }
}

/** 短内容也保留可接收纵向拖动的滚动容器，不伪造滚动范围。 */
internal fun Modifier.bouncingVerticalScroll(state: ScrollState): Modifier =
    edgeBounce().verticalScroll(state, overscrollEffect = null)

@Composable
internal fun BouncingLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyListScope.() -> Unit
) {
    LazyColumn(modifier.edgeBounce(), state, contentPadding,
        verticalArrangement = verticalArrangement, overscrollEffect = null, content = content)
}

@Composable
internal fun BouncingLazyVerticalGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: LazyGridScope.() -> Unit
) {
    LazyVerticalGrid(columns, modifier.edgeBounce(), state, contentPadding,
        verticalArrangement = verticalArrangement, horizontalArrangement = horizontalArrangement,
        overscrollEffect = null, content = content)
}
