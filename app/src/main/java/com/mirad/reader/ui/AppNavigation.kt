package com.mirad.reader.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import com.mirad.reader.ui.settings.AppSettingsPage
import com.mirad.reader.ui.settings.ReadingSettingsPage
import com.mirad.reader.ui.settings.SettingsRoute

sealed interface Screen {
    data object Shelf : Screen
    data class Reader(val bookId: Long) : Screen
    data object Stats : Screen
    data object Settings : Screen
    data class SettingsDetail(val route: SettingsRoute) : Screen
    data object Notes : Screen
    data object HiddenBooks : Screen
    data object SourceManager : Screen
    data object OnlineSearch : Screen
    data object Downloads : Screen
}

internal enum class AppTab(val title: String, val root: Screen) {
    Shelf("书架", Screen.Shelf), Search("搜书", Screen.OnlineSearch),
    Notes("笔记", Screen.Notes), Settings("设置", Screen.Settings)
}

/** 每个入口独立保存返回栈；切换入口不把其他入口压入历史。 */
@Stable
internal class AppNavigationState(saved: List<String> = emptyList()) {
    var selectedTab by mutableStateOf(AppTab.entries.find { it.name == saved.firstOrNull() } ?: AppTab.Shelf)
        private set
    private val stacks = mutableStateMapOf<AppTab, List<Screen>>().apply {
        AppTab.entries.forEach { tab ->
            val restored = saved.drop(1).filter { it.startsWith("${tab.name}|") }
                .mapNotNull { decodeScreen(it.substringAfter('|')) }
            put(tab, if (restored.firstOrNull() == tab.root) restored else listOf(tab.root))
        }
    }
    val current: Screen get() = stacks.getValue(selectedTab).last()
    val isRoot: Boolean get() = current == selectedTab.root
    val canGoBack: Boolean get() = !isRoot || selectedTab != AppTab.Shelf

    fun select(tab: AppTab) {
        if (selectedTab == tab) stacks[tab] = listOf(tab.root)
        selectedTab = tab
    }

    fun open(screen: Screen) {
        val root = AppTab.entries.find { it.root == screen }
        if (root != null) { select(root); return }
        val stack = stacks.getValue(selectedTab)
        val existing = stack.indexOfLast { it == screen }
        stacks[selectedTab] = if (existing >= 0) stack.take(existing + 1) else stack + screen
    }

    fun back() {
        val stack = stacks.getValue(selectedTab)
        if (stack.size > 1) stacks[selectedTab] = stack.dropLast(1)
        else {
            selectedTab = AppTab.Shelf
            stacks[AppTab.Shelf] = listOf(Screen.Shelf)
        }
    }

    fun snapshot(): List<String> = listOf(selectedTab.name) + AppTab.entries.flatMap { tab ->
        stacks.getValue(tab).map { "${tab.name}|${encodeScreen(it)}" }
    }

    companion object {
        val StateSaver = Saver<AppNavigationState, List<String>>(save = { it.snapshot() }, restore = { AppNavigationState(it) })
    }
}

private fun encodeScreen(screen: Screen): String = when (screen) {
    is Screen.Reader -> "reader:${screen.bookId}"
    is Screen.SettingsDetail -> when (val route = screen.route) {
        is SettingsRoute.Reading -> "reading:${route.page.name}"
        is SettingsRoute.App -> "app:${route.page.name}"
    }
    else -> screen.toString()
}

private fun decodeScreen(value: String): Screen? = when (value) {
    "Shelf" -> Screen.Shelf
    "OnlineSearch" -> Screen.OnlineSearch
    "Notes" -> Screen.Notes
    "Settings" -> Screen.Settings
    "Stats" -> Screen.Stats
    "HiddenBooks" -> Screen.HiddenBooks
    "SourceManager" -> Screen.SourceManager
    "Downloads" -> Screen.Downloads
    else -> when (value.substringBefore(':')) {
        "reader" -> value.substringAfter(':').toLongOrNull()?.let { Screen.Reader(it) }
        "reading" -> ReadingSettingsPage.entries.find { it.name == value.substringAfter(':') }
            ?.let { Screen.SettingsDetail(SettingsRoute.Reading(it)) }
        "app" -> AppSettingsPage.entries.find { it.name == value.substringAfter(':') }
            ?.let { Screen.SettingsDetail(SettingsRoute.App(it)) }
        else -> null
    }
}
