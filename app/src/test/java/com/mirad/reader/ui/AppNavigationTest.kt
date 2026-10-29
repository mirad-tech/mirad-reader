package com.mirad.reader.ui

import com.mirad.reader.ui.settings.AppSettingsPage
import com.mirad.reader.ui.settings.SettingsRoute
import org.junit.Assert.*
import org.junit.Test

class AppNavigationTest {
    @Test fun tabsKeepIndependentStacksAndBackDoesNotReplayTabSwitches() {
        val state = AppNavigationState()
        state.select(AppTab.Settings)
        state.open(Screen.Stats)
        state.select(AppTab.Search)
        state.open(Screen.SourceManager)
        state.select(AppTab.Settings)
        assertEquals(Screen.Stats, state.current)
        state.back()
        assertEquals(Screen.Settings, state.current)
        state.back()
        assertEquals(AppTab.Shelf, state.selectedTab)
        assertFalse(state.canGoBack)
    }

    @Test fun reselectingCurrentTabReturnsToItsRoot() {
        val state = AppNavigationState()
        state.select(AppTab.Settings)
        state.open(Screen.SettingsDetail(SettingsRoute.App(AppSettingsPage.APPEARANCE)))
        state.select(AppTab.Settings)
        assertEquals(Screen.Settings, state.current)
        assertTrue(state.isRoot)
    }

    @Test fun backFromAnotherRootReturnsToTheShelfHomeEvenAfterInterruptedReading() {
        val state = AppNavigationState()
        state.open(Screen.Reader(7))
        state.open(Screen.Downloads)
        state.select(AppTab.Notes)
        state.back()
        assertEquals(Screen.Shelf, state.current)
        assertFalse(state.canGoBack)
    }

    @Test fun readerReturnsToTheExactEntryAndDownloadNotificationsDoNotDuplicateIt() {
        val state = AppNavigationState()
        state.select(AppTab.Notes)
        state.open(Screen.Reader(42))
        state.open(Screen.Downloads)
        state.open(Screen.Downloads)
        state.back()
        assertEquals(Screen.Reader(42), state.current)
        state.back()
        assertEquals(Screen.Notes, state.current)
    }

    @Test fun restoreIncludesOtherTabStacksAndSettingsRoutes() {
        val state = AppNavigationState()
        state.open(Screen.Reader(17))
        state.select(AppTab.Settings)
        val detail = Screen.SettingsDetail(SettingsRoute.App(AppSettingsPage.APPEARANCE))
        state.open(detail)
        val restored = AppNavigationState(state.snapshot())
        assertEquals(detail, restored.current)
        restored.select(AppTab.Shelf)
        assertEquals(Screen.Reader(17), restored.current)
        restored.back()
        assertEquals(Screen.Shelf, restored.current)
    }
}
