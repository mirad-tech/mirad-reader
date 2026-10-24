package com.mirad.reader.ui

import android.app.Activity
import android.widget.Toast
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
import com.mirad.reader.ui.stats.StatsScreen
import com.mirad.reader.ui.theme.MiradTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch

/** 屏幕（自建返回栈，不引 navigation-compose）。 */
sealed interface Screen {
    data object Shelf : Screen
    data class Reader(val bookId: Long) : Screen
    data object Stats : Screen
    data object Settings : Screen
    data class SettingsDetail(val route: com.mirad.reader.ui.settings.SettingsRoute) : Screen
    data object Notes : Screen
    data object HiddenBooks : Screen
}

/**
 * 全应用导航。用列表做返回栈，子页面（统计/设置/笔记/隐藏书籍）都能正确回退，
 * 而不是一律跳回书架。
 */
@Composable
fun AppRoot(container: AppContainer, systemDark: Boolean) {
    val backStack = remember { mutableStateListOf<Screen>(Screen.Shelf) }
    val current = backStack.last()

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
            Toast.makeText(container.context, "设置更新失败，请重试。", Toast.LENGTH_SHORT).show()
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

    fun navigate(screen: Screen) {
        if (backStack.last() != screen) backStack.add(screen)
    }

    fun goBack() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    BackHandler(enabled = backStack.size > 1) { goBack() }

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
        LocalAppAppearance provides appearance,
        LocalAppDarkTheme provides darkTheme,
        LocalAppSystemDark provides systemDark,
        LocalAppAppearanceSaveFailed provides appSaveFailed,
        LocalAppAppearanceController provides appController,
        LocalGlassAppearance provides glassAppearance,
        LocalGlassAppearanceController provides glassController,
        LocalGlassSaveFailed provides glassSaveFailed
    ) {
        stateHolder.SaveableStateProvider(current.toString()) {
            com.mirad.reader.ui.common.PageSurface(
                pageKey = current.toString(),
                bounded = current != Screen.Shelf && current !is Screen.Reader,
                animate = current !is Screen.Reader
            ) {
                when (current) {
                    Screen.Shelf -> ShelfScreen(
                        container = container,
                        onOpenBook = { bookId -> navigate(Screen.Reader(bookId)) },
                        onOpenStats = { navigate(Screen.Stats) },
                        onOpenSettings = { navigate(Screen.Settings) },
                        onOpenNotes = { navigate(Screen.Notes) },
                        onOpenHidden = { navigate(Screen.HiddenBooks) }
                    )
                    is Screen.Reader -> ReaderScreen(
                        container = container,
                        bookId = current.bookId,
                        systemDark = systemDark,
                        onBack = { goBack() }
                    )
                    Screen.Stats -> StatsScreen(container = container, onBack = { goBack() })
                    is Screen.SettingsDetail -> SettingsScreen(
                        container = container, route = current.route, onBack = { goBack() },
                        onOpenRoute = { navigate(Screen.SettingsDetail(it)) },
                        onOpenHidden = { navigate(Screen.HiddenBooks) }
                    )
                    Screen.Settings -> SettingsScreen(
                        container = container,
                        onOpenRoute = { navigate(Screen.SettingsDetail(it)) },
                        onBack = { goBack() },
                        onOpenHidden = { navigate(Screen.HiddenBooks) }
                    )
                    Screen.Notes -> NotesScreen(
                        container = container,
                        onBack = { goBack() },
                        onOpenBook = { bookId -> navigate(Screen.Reader(bookId)) }
                    )
                    Screen.HiddenBooks -> HiddenBooksScreen(container = container, onBack = { goBack() })
                }
            }
        }
    }
    }
    }
}
