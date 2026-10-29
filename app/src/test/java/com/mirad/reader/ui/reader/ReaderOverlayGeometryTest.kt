package com.mirad.reader.ui.reader

import com.mirad.reader.ui.common.*

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderOverlayGeometryTest {
    @Test fun menuAtTheRightEdgeUsesItsAnchorEndAndStaysBelowTheButton() {
        assertEquals(IntOffset(172, 96), glassMenuPosition(IntRect(312, 48, 360, 96), IntSize(188, 240), IntRect(8, 32, 382, 740), LayoutDirection.Ltr))
    }

    @Test fun rtlMenuPrefersItsAnchorEndWithoutLeavingTheSafeArea() {
        assertEquals(IntOffset(20, 96), glassMenuPosition(IntRect(20, 48, 68, 96), IntSize(188, 240), IntRect(8, 32, 382, 740), LayoutDirection.Rtl))
    }

    @Test fun keyboardOcclusionMovesMenuAboveTheAnchor() {
        assertEquals(IntOffset(172, 100), glassMenuPosition(IntRect(312, 340, 360, 388), IntSize(188, 240), IntRect(8, 32, 382, 420), LayoutDirection.Ltr))
    }

    @Test fun narrowLandscapeSafeAreaClampsTheMenuInBothDirections() {
        val safe = IntRect(80, 40, 540, 280)
        for (direction in LayoutDirection.entries) {
            val position = glassMenuPosition(IntRect(540, 180, 588, 228), IntSize(280, 240), safe, direction)
            assertTrue(position.x >= safe.left && position.x + 280 <= safe.right)
            assertTrue(position.y >= safe.top && position.y + 240 <= safe.bottom)
        }
    }

    @Test fun shortSheetDoesNotGainAnEmptyHalfScreenAndTallSheetHasPartialAnchor() {
        assertEquals(180, glassSheetPeekHeight(180, 800))
        assertEquals(400, glassSheetPeekHeight(700, 800))
        assertEquals(140, glassSheetPeekHeight(700, 280))
        assertEquals(0, glassSheetPeekHeight(0, 800))
    }

    @Test fun backHidesKeyboardBeforeCallingAnyOverlayOrNavigationHandler() {
        assertEquals(listOf("ime"), backActions(GlassOverlayKind.Dialog, ime = true, content = true, navigation = true))
    }

    @Test fun backOnAConfirmationOrMenuDoesNotNavigateTheSettingsBehindIt() {
        assertEquals(listOf("dismiss"), backActions(GlassOverlayKind.Dialog, navigation = true))
        assertEquals(listOf("dismiss"), backActions(GlassOverlayKind.Menu, navigation = true))
    }

    @Test fun settingsCategoryBackStaysInsideItsSheetUntilTheCategoryIsClosed() {
        assertEquals(listOf("navigation"), backActions(GlassOverlayKind.Sheet, navigation = true))
        assertEquals(listOf("dismiss"), backActions(GlassOverlayKind.Sheet))
    }

    @Test fun topLayerContentGetsBackBeforeItsProducerNavigation() {
        assertEquals(listOf("content"), backActions(GlassOverlayKind.Sheet, content = true, navigation = true))
    }

    private fun backActions(kind: GlassOverlayKind, ime: Boolean = false, content: Boolean = false, navigation: Boolean = false): List<String> {
        val actions = mutableListOf<String>()
        dispatchGlassOverlayBack(kind, ime, content, navigation,
            hideIme = { actions += "ime" }, contentBack = { actions += "content" },
            navigationBack = { actions += "navigation" }, dismiss = { actions += "dismiss" })
        return actions
    }
}
