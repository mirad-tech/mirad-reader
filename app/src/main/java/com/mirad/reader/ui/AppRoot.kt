package com.mirad.reader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import com.mirad.reader.AppContainer
import com.mirad.reader.ui.notes.NotesScreen
import com.mirad.reader.ui.reader.ReaderScreen
import com.mirad.reader.ui.settings.SettingsScreen
import com.mirad.reader.ui.shelf.HiddenBooksScreen
import com.mirad.reader.ui.shelf.ShelfScreen
import com.mirad.reader.ui.stats.StatsScreen

/** 屏幕（自建返回栈，不引 navigation-compose）。 */
sealed interface Screen {
    data object Shelf : Screen
    data class Reader(val bookId: Long) : Screen
    data object Stats : Screen
    data object Settings : Screen
    data class SettingsDetail(val category: com.mirad.reader.ui.common.SettingsCategory) : Screen
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

    fun navigate(screen: Screen) {
        if (backStack.last() != screen) backStack.add(screen)
    }

    fun goBack() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    BackHandler(enabled = backStack.size > 1) { goBack() }

    val stateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    stateHolder.SaveableStateProvider(current.toString()) {
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
            container = container, category = current.category, onBack = { goBack() },
            onOpenHidden = { navigate(Screen.HiddenBooks) }
        )
        Screen.Settings -> SettingsScreen(
            container = container,
            onOpenCategory = { navigate(Screen.SettingsDetail(it)) },
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
