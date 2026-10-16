package com.mirad.reader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mirad.reader.AppContainer
import com.mirad.reader.ui.reader.ReaderScreen
import com.mirad.reader.ui.settings.SettingsScreen
import com.mirad.reader.ui.shelf.ShelfScreen
import com.mirad.reader.ui.stats.StatsScreen

/** 屏幕（自建导航，不引 navigation-compose）。 */
sealed interface Screen {
    data object Shelf : Screen
    data class Reader(val bookId: Long) : Screen
    data object Stats : Screen
    data object Settings : Screen
}

@Composable
fun AppRoot(container: AppContainer, systemDark: Boolean) {
    var screen by remember { mutableStateOf<Screen>(Screen.Shelf) }

    BackHandler(enabled = screen != Screen.Shelf) {
        screen = Screen.Shelf
    }

    when (val current = screen) {
        Screen.Shelf -> ShelfScreen(
            container = container,
            onOpenBook = { bookId -> screen = Screen.Reader(bookId) },
            onOpenStats = { screen = Screen.Stats },
            onOpenSettings = { screen = Screen.Settings }
        )
        is Screen.Reader -> ReaderScreen(
            container = container,
            bookId = current.bookId,
            systemDark = systemDark,
            onBack = { screen = Screen.Shelf }
        )
        Screen.Stats -> StatsScreen(container = container, onBack = { screen = Screen.Shelf })
        Screen.Settings -> SettingsScreen(container = container, onBack = { screen = Screen.Shelf })
    }
}
