package com.mirad.reader.ui

import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal fun navigationPagePosition(value: Float): Pair<Int, Float> {
    val position = value.coerceIn(0f, AppTab.entries.lastIndex.toFloat())
    val page = position.roundToInt()
    return page to (position - page)
}

/**
 * 点击跨 Tab 切换时的瞬态液态拉伸曲线（参考 LiquidGlassTabBar.animateDropletTo）：
 * - 起止点 (t=0, t=1) 严格归零；
 * - 中段按 sin(π * t) 达到峰值，且跨越的 Tab 距离越远拉伸越明显。
 */
internal fun computeTapLiquidStretch(progress: Float, startPage: Float, targetPage: Int): Float {
    val span = abs(targetPage - startPage)
    if (span < 0.05f) return 0f
    val t = ((progress - startPage) / (targetPage - startPage)).coerceIn(0f, 1f)
    val wave = sin(PI.toFloat() * t).coerceIn(0f, 1f)
    val distanceFactor = min(1f, span / 2.5f)
    return (wave * (0.45f + 0.55f * distanceFactor)).coerceIn(0f, 1f)
}

/** 导航胶囊和页面只读取一个分页进度；手势状态不写持久化。 */
@Stable
internal class AppPagerMotion(
    val pager: PagerState,
    private val navigation: AppNavigationState,
    private val scope: CoroutineScope
) {
    val progress: Float get() = pager.currentPage + pager.currentPageOffsetFraction
    var isDragging by mutableStateOf(false)
        private set
    var dragStretch by mutableFloatStateOf(0f)
        private set
    private var tapStartPage by mutableFloatStateOf(0f)
    private var tapTargetPage by mutableIntStateOf(-1)
    private var requestedPosition = 0f
    private var animation: Job? = null
    private var mirroredTab = navigation.selectedTab
    private var externalTarget: AppTab? = null

    val liquidStretch: Float
        get() = when {
            isDragging -> dragStretch
            tapTargetPage >= 0 -> computeTapLiquidStretch(progress, tapStartPage, tapTargetPage)
            else -> 0f
        }

    internal fun synchronizeSelection(tab: AppTab) {
        if (tab == mirroredTab) return
        animation?.cancel()
        isDragging = false
        dragStretch = 0f
        tapTargetPage = -1
        mirroredTab = tab
        externalTarget = tab
        pager.requestScrollToPage(tab.ordinal)
    }

    internal fun pageChanged(page: Int) {
        val tab = AppTab.entries[page]
        if (externalTarget != null && externalTarget != tab) return
        externalTarget = null
        mirroredTab = tab
        navigation.activate(tab)
    }

    fun tap(tab: AppTab) {
        animation?.cancel()
        isDragging = false
        dragStretch = 0f
        externalTarget = null
        val start = progress
        tapStartPage = start
        tapTargetPage = if (abs(tab.ordinal - start) >= 0.05f) tab.ordinal else -1
        if (navigation.selectedTab == tab) navigation.select(tab)
        animation = scope.launch {
            try {
                pager.animateScrollToPage(tab.ordinal, animationSpec = spring(dampingRatio = .78f, stiffness = 460f))
            } finally {
                if (tapTargetPage == tab.ordinal) tapTargetPage = -1
            }
        }
    }

    fun beginDrag() {
        animation?.cancel()
        externalTarget = null
        tapTargetPage = -1
        requestedPosition = progress
        isDragging = true
        dragStretch = 0f
    }

    fun dragBy(deltaInPages: Float) {
        if (!isDragging) return
        requestedPosition = (requestedPosition + deltaInPages).coerceIn(0f, AppTab.entries.lastIndex.toFloat())
        dragStretch = (abs(deltaInPages) * 8f).coerceIn(0f, 1f)
        val (page, offset) = navigationPagePosition(requestedPosition)
        pager.requestScrollToPage(page, offset)
    }

    fun finishDrag() {
        if (!isDragging) return
        val target = navigationPagePosition(requestedPosition).first
        val start = progress
        isDragging = false
        dragStretch = 0f
        tapStartPage = start
        tapTargetPage = if (abs(target - start) >= 0.05f) target else -1
        animation = scope.launch {
            try {
                pager.animateScrollToPage(target, animationSpec = spring(dampingRatio = .78f, stiffness = 460f))
            } finally {
                if (tapTargetPage == target) tapTargetPage = -1
            }
        }
    }

    fun cancelDrag() {
        animation?.cancel()
        val target = if (!isDragging && externalTarget != null) externalTarget!!.ordinal
            else navigationPagePosition(if (isDragging) requestedPosition else progress).first
        isDragging = false
        dragStretch = 0f
        tapTargetPage = -1
        externalTarget = null
        mirroredTab = AppTab.entries[target]
        navigation.activate(mirroredTab)
        pager.requestScrollToPage(target)
    }

    /** 阅读器接管窗口时丢弃旧拖动目标，不能由待处理的分页位置激活旧入口。 */
    internal fun releaseHost() {
        if (navigation.screenFor(navigation.selectedTab) !is Screen.Reader) {
            cancelDrag()
            return
        }
        animation?.cancel()
        isDragging = false
        dragStretch = 0f
        tapTargetPage = -1
        mirroredTab = navigation.selectedTab
        externalTarget = navigation.selectedTab
        pager.requestScrollToPage(navigation.selectedTab.ordinal)
    }
}

@Composable
internal fun rememberAppPagerMotion(navigation: AppNavigationState): AppPagerMotion {
    val pager = rememberPagerState(initialPage = navigation.selectedTab.ordinal) { AppTab.entries.size }
    val scope = rememberCoroutineScope()
    val motion = remember(pager, navigation, scope) { AppPagerMotion(pager, navigation, scope) }
    LaunchedEffect(navigation.selectedTab) { motion.synchronizeSelection(navigation.selectedTab) }
    LaunchedEffect(motion) {
        snapshotFlow { pager.currentPage }.collect { motion.pageChanged(it) }
    }
    return motion
}
