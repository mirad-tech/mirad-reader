package com.mirad.reader.ui

import androidx.compose.runtime.derivedStateOf
import android.app.Activity
import androidx.compose.foundation.pager.HorizontalPager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import com.mirad.reader.ui.common.*
import kotlin.math.abs
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.mirad.reader.ui.common.LocalAppBottomBar
import com.mirad.reader.ui.common.LocalAppRootPage
import com.mirad.reader.ui.common.LocalRootFeedback
import com.mirad.reader.ui.common.FeedbackKind
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mirad.reader.AppContainer
import com.mirad.reader.core.model.AppThemeMode
import com.mirad.reader.core.model.AppAppearance
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.ui.common.AppAppearanceController
import com.mirad.reader.ui.common.ApplyReaderWindowPolicy
import com.mirad.reader.ui.common.ReaderWindowPolicy
import com.mirad.reader.ui.common.ReadingConfigEditor
import com.mirad.reader.ui.common.ResetScope
import com.mirad.reader.ui.common.LocalAppAppearance
import com.mirad.reader.ui.common.LocalAppAppearanceController
import com.mirad.reader.ui.common.LocalAppAppearanceSaveFailed
import com.mirad.reader.ui.common.LocalAppDarkTheme
import com.mirad.reader.ui.common.LocalAppSystemDark
import com.mirad.reader.ui.common.MiradTextButton
import com.mirad.reader.ui.common.GlassAppearance
import com.mirad.reader.ui.common.GlassAppearanceController
import com.mirad.reader.ui.common.LocalGlassAppearance
import com.mirad.reader.ui.common.LocalGlassAppearanceController
import com.mirad.reader.ui.common.LocalGlassSaveFailed
import com.mirad.reader.ui.notes.NotesScreen
import com.mirad.reader.ui.reader.ReaderScreen
import com.mirad.reader.ui.settings.SettingsScreen
import com.mirad.reader.ui.shelf.HiddenBooksScreen
import com.mirad.reader.ui.shelf.ShelfScreen
import com.mirad.reader.ui.sources.BookDownloadsScreen
import com.mirad.reader.ui.sources.SourceManagerScreen
import com.mirad.reader.ui.sources.SourceSearchScreen
import com.mirad.reader.ui.stats.StatsScreen
import com.mirad.reader.ui.theme.MiradTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch

/**
 * 全应用导航。用列表做返回栈，子页面（统计/设置/笔记/隐藏书籍）都能正确回退，
 * 而不是一律跳回书架。
 */
@Composable
fun AppRoot(container: AppContainer, systemDark: Boolean) {
    val navigation = rememberSaveable(saver = AppNavigationState.StateSaver) { AppNavigationState() }
    val current = navigation.current
    val keyboard = LocalSoftwareKeyboardController.current
    val openDownloads by container.openDownloads.collectAsState()

    var loadGeneration by remember { mutableIntStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    val appearanceFlow = remember(container, loadGeneration) {
        container.settings.appAppearance.catch { error ->
            if (error is CancellationException) throw error
            loadFailed = true
        }
    }
    val appAppearance by appearanceFlow.collectAsState(initial = null)
    val appSaveFailed by container.settings.appAppearanceSaveFailed.collectAsState(initial = false)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val configEditor = remember(container) {
        ReadingConfigEditor(container.settings, container.appScope, onFailure = {
            container.feedback.show("设置更新失败，请重试。", FeedbackKind.ERROR)
        })
    }
    val appController = remember(container, configEditor, lifecycle) {
        object : AppAppearanceController {
            override fun setMode(mode: AppThemeMode) {
                container.settings.queueAppThemeMode(mode, container.appScope)
            }
            override fun commit() = container.settings.flushAppAppearance(container.appScope)
            override fun retrySave() = container.settings.retryAppAppearance(container.appScope)
            override fun updateReadingConfig(isDark: Boolean, transform: (ReaderConfig) -> ReaderConfig,
                onApplied: (ReaderConfig, AppAppearance) -> Unit) {
                configEditor.update(isDark, transform) { config, appearance ->
                    onApplied(config, appearance)
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) configEditor.flush()
                }
            }
            override fun resetReadingConfig(
                scopes: Set<ResetScope>,
                onApplied: (ReaderConfig, AppAppearance) -> Unit
            ) {
                configEditor.resetReading(scopes) { config, appearance ->
                    onApplied(config, appearance)
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) configEditor.flush()
                }
            }
            override fun commitReadingConfig() { configEditor.flush() }
        }
    }
    DisposableEffect(configEditor, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) configEditor.flush()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            configEditor.flush()
        }
    }

    val glassAppearance by container.settings.glassAppearance.collectAsState(initial = GlassAppearance.DEFAULT)
    val glassSaveFailed by container.settings.glassSaveFailed.collectAsState(initial = false)
    val glassController = remember(container) {
        object : GlassAppearanceController {
            override val saveFailed: Boolean
                get() = glassSaveFailed
            override fun update(appearance: GlassAppearance) {
                container.settings.queueGlassAppearance(appearance, container.appScope)
            }
            override fun commit() {
                container.settings.flushGlassAppearance(container.appScope)
            }
            override fun reset() {
                container.settings.resetGlassAppearance(container.appScope)
            }
            override fun retrySave() {
                container.settings.retryGlassAppearance(container.appScope)
            }
        }
    }

    fun navigate(screen: Screen) = navigation.open(screen)
    fun goBack() = navigation.back()
    fun openSourcePage(screen: Screen) = navigation.open(screen)

    LaunchedEffect(container, openDownloads) {
        if (openDownloads) {
            openSourcePage(Screen.Downloads)
            container.openDownloads.value = false
        }
    }

    BackHandler(enabled = navigation.canGoBack) { goBack() }

    val stateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val appearance = appAppearance
    val darkTheme = appearance?.effectiveDark(systemDark) ?: systemDark
    if (current !is Screen.Reader) {
        ApplyReaderWindowPolicy((LocalContext.current as? Activity)?.window, ReaderWindowPolicy(false, darkTheme))
    }
    MiradTheme(darkTheme = darkTheme) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    if (appearance == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loadFailed) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("无法读取外观设置")
                    MiradTextButton(onClick = { loadFailed = false; loadGeneration++ }) { Text("重试") }
                }
            } else CircularProgressIndicator()
        }
        return@Surface
    }
    CompositionLocalProvider(
        LocalRootFeedback provides container.feedback,
        LocalAppRootPage provides navigation.isRoot,
        LocalAppBottomBar provides {},
        LocalAppAppearance provides appearance,
        LocalAppDarkTheme provides darkTheme,
        LocalAppSystemDark provides systemDark,
        LocalAppAppearanceSaveFailed provides appSaveFailed,
        LocalAppAppearanceController provides appController,
        LocalGlassAppearance provides glassAppearance,
        LocalGlassAppearanceController provides glassController,
        LocalGlassSaveFailed provides glassSaveFailed
    ) {
        val renderScreen: @Composable (AppTab, Screen) -> Unit = { tab, screen ->
            CompositionLocalProvider(LocalAppRootPage provides (screen == tab.root), LocalPagerPage provides tab.name, LocalPagerPageIndex provides tab.ordinal) {
        stateHolder.SaveableStateProvider("${tab.name}:$screen") {
            com.mirad.reader.ui.common.PageSurface(
                pageKey = screen.toString(),
                bounded = screen != Screen.Shelf && screen !is Screen.Reader,
                animate = false
            ) {
                when (screen) {
                    Screen.Shelf -> ShelfScreen(
                        container = container,
                        onOpenBook = { bookId -> navigate(Screen.Reader(bookId)) },
                        onOpenStats = { navigate(Screen.Stats) },
                        onOpenSettings = { navigate(Screen.Settings) },
                        onOpenNotes = { navigate(Screen.Notes) },
                        onOpenHidden = { navigate(Screen.HiddenBooks) },
                        onOnlineSearch = { openSourcePage(Screen.OnlineSearch) },
                        onSourceManager = { openSourcePage(Screen.SourceManager) }
                    )
                    is Screen.Reader -> ReaderScreen(
                        container = container,
                        bookId = screen.bookId,
                        systemDark = systemDark,
                        onBack = { goBack() }
                    )
                    Screen.Stats -> StatsScreen(container = container, onBack = { goBack() })
                    is Screen.SettingsDetail -> SettingsScreen(
                        container = container, route = screen.route, onBack = { goBack() },
                        onOpenRoute = { navigate(Screen.SettingsDetail(it)) },
                        onOpenHidden = { navigate(Screen.HiddenBooks) }
                    )
                    Screen.Settings -> SettingsScreen(
                        container = container,
                        onOpenRoute = { navigate(Screen.SettingsDetail(it)) },
                        onBack = { goBack() },
                        onOpenHidden = { navigate(Screen.HiddenBooks) },
                        onOpenStats = { navigate(Screen.Stats) }
                    )
                    Screen.Notes -> NotesScreen(
                        container = container,
                        onBack = { goBack() },
                        onOpenBook = { bookId -> navigate(Screen.Reader(bookId)) }
                    )
                    Screen.HiddenBooks -> HiddenBooksScreen(container = container, onBack = { goBack() })
                    Screen.SourceManager -> SourceManagerScreen(
                        repository = container.sources,
                        onBack = { goBack() },
                        onSearch = { openSourcePage(Screen.OnlineSearch) }
                    )
                    Screen.OnlineSearch -> SourceSearchScreen(
                        repository = container.sources,
                        search = container.sourceSearch,
                        downloads = container.bookDownloads,
                        actionScope = container.appScope,
                        onBack = { goBack() },
                        onSources = { openSourcePage(Screen.SourceManager) },
                        onDownloads = { openSourcePage(Screen.Downloads) }
                    )
                    Screen.Downloads -> BookDownloadsScreen(
                        downloads = container.bookDownloads,
                        actionScope = container.appScope,
                        onBack = { goBack() },
                        onSearch = { openSourcePage(Screen.OnlineSearch) },
                        onOpenBook = { navigate(Screen.Reader(it)) }
                    )
                }
            }
        }
            }
        }
        val motion = rememberAppPagerMotion(navigation)
        BackHandler(enabled = motion.isDragging) { motion.cancelDrag() }
        val routes = AppTab.entries.map { navigation.screenFor(it) }
        var previousRoutes by remember { mutableStateOf(routes) }
        LaunchedEffect(routes) {
            if (previousRoutes != routes) motion.cancelDrag()
            previousRoutes = routes
        }
        val chrome = remember { PagerChrome() }
        if (current is Screen.Reader) {
            LaunchedEffect(current) { motion.cancelDrag() }
            renderScreen(navigation.selectedTab, current)
        } else {
            PagerChromeHost(chrome, navigation.selectedTab.name, pageProgress = { motion.progress },
                onAvailability = { if (!it && motion.isDragging) motion.cancelDrag() },
                navigation = { available -> AppNavigationBar(navigation.selectedTab, motion = motion, enabled = available) {
                    keyboard?.hide(); navigation.select(it)
                } }
            ) {
                HorizontalPager(state = motion.pager, userScrollEnabled = false,
                    beyondViewportPageCount = 1, key = { AppTab.entries[it].name }, modifier = Modifier.fillMaxSize()) { index ->
                    val tab = AppTab.entries[index]
                    val route = navigation.screenFor(tab)
                    val visible by remember(index, motion) { derivedStateOf { abs(index - motion.progress) < 1f } }
                    val active by remember(tab, navigation) { derivedStateOf { tab == navigation.selectedTab } }
                    val parentBack = requireNotNull(LocalOnBackPressedDispatcherOwner.current)
                    val inactiveBack = remember(tab, parentBack) {
                        object : OnBackPressedDispatcherOwner {
                            override val lifecycle = requireNotNull(parentBack).lifecycle
                            override val onBackPressedDispatcher = OnBackPressedDispatcher()
                        }
                    }
                    CompositionLocalProvider(LocalPagerPageVisible provides visible,
                        LocalPagerPageActive provides active,
                        LocalOnBackPressedDispatcherOwner provides if (active) parentBack else inactiveBack) {
                        renderScreen(tab, if (route is Screen.Reader) tab.root else route)
                    }
                }
            }
        }

    }
    }
    }
}
