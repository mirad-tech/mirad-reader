package com.mirad.reader.preview

import com.mirad.reader.data.repo.SettingsRepository
import com.mirad.reader.ui.common.GlassAppearance
import com.mirad.reader.ui.common.GlassAppearanceJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewInMemoryPreferenceDaoTest {
    @Test fun disabledPreviewKeepsItsClarityAfterSubscriptionAndReopen() = runTest {
        val expected = GlassAppearance(enabled = false, clarityPercent = 60)
        val dao = PreviewInMemoryPreferenceDao(expected)
        assertEquals(expected, SettingsRepository(dao, this).glassAppearance.first())
        assertEquals(expected, SettingsRepository(dao, this).currentGlassAppearance())
        assertEquals("0", dao.get(SettingsRepository.KEY_GLASS_TRANSPARENCY))
    }

    @Test fun zeroClarityPreviewRemainsEnabledAfterSubscription() = runTest {
        val expected = GlassAppearance(enabled = true, clarityPercent = 0)
        val dao = PreviewInMemoryPreferenceDao(expected)
        assertEquals(expected, GlassAppearanceJson.decode(dao.get(SettingsRepository.KEY_GLASS_APPEARANCE)))
        assertEquals(expected, SettingsRepository(dao, this).glassAppearance.first())
        assertEquals("1", dao.get(SettingsRepository.KEY_GLASS_TRANSPARENCY))
    }

    @Test fun initializedPreviewDoesNotNeedAWritableMigration() = runTest {
        val expected = GlassAppearance(enabled = false, clarityPercent = 100)
        val dao = PreviewInMemoryPreferenceDao(expected, failWrites = true)
        assertEquals(expected, SettingsRepository(dao, this).glassAppearance.first())
    }

    @Test fun reopeningRepositoryKeepsEditedAppearanceInsteadOfStartupValues() = runTest {
        val dao = PreviewInMemoryPreferenceDao(GlassAppearance(enabled = true, clarityPercent = 0))
        val settings = SettingsRepository(dao, this)
        val updated = GlassAppearance(enabled = false, clarityPercent = 73)
        settings.saveGlassAppearance(updated)
        assertEquals(updated, SettingsRepository(dao, this).glassAppearance.first())
        assertEquals(updated, GlassAppearanceJson.decode(dao.get(SettingsRepository.KEY_GLASS_APPEARANCE)))
    }
}
