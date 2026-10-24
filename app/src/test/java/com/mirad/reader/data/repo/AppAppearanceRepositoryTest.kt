package com.mirad.reader.data.repo

import com.mirad.reader.core.model.AppAppearance
import com.mirad.reader.core.model.AppAppearanceJson
import com.mirad.reader.core.model.AppThemeMode
import com.mirad.reader.core.model.BackgroundType
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.ReaderConfigJson
import com.mirad.reader.core.model.ReadingStyle
import com.mirad.reader.core.model.ThemeKey
import com.mirad.reader.data.db.dao.PreferenceDao
import com.mirad.reader.data.db.entity.PreferenceEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppAppearanceRepositoryTest {
    private class MemoryPreferences : PreferenceDao {
        val entries = mutableMapOf<String, PreferenceEntity>()
        private val flows = mutableMapOf<String, MutableStateFlow<String?>>()
        val writes = mutableListOf<PreferenceEntity>()
        var beforePut: suspend (PreferenceEntity) -> Unit = {}
        var failNextAppearanceWrite = false

        fun seed(key: String, value: String) {
            entries[key] = PreferenceEntity(key, value)
            flows[key]?.value = value
        }

        override suspend fun put(preference: PreferenceEntity) {
            beforePut(preference)
            if (preference.key == SettingsRepository.KEY_APP_APPEARANCE && failNextAppearanceWrite) {
                failNextAppearanceWrite = false
                throw IllegalStateException("Appearance write failed")
            }
            entries[preference.key] = preference
            flows.getOrPut(preference.key) { MutableStateFlow(null) }.value = preference.value
            writes += preference
        }

        override suspend fun get(key: String): String? = entries[key]?.value
        override fun observe(key: String): Flow<String?> = flows.getOrPut(key) { MutableStateFlow(entries[key]?.value) }
        override suspend fun remove(key: String) {
            entries.remove(key)
            flows[key]?.value = null
        }
        override suspend fun removeByPrefix(prefix: String) {
            entries.keys.filter { it.startsWith(prefix) }.toList().forEach { key ->
                entries.remove(key)
                flows[key]?.value = null
            }
        }
        override suspend fun getAll(): List<PreferenceEntity> = entries.values.toList()
        fun appearance(): AppAppearance = AppAppearanceJson.decode(entries[SettingsRepository.KEY_APP_APPEARANCE]?.value)
        fun appearanceWriteCount(): Int = writes.count { it.key == SettingsRepository.KEY_APP_APPEARANCE }
    }

    @Test fun migrationEmitsTheLegacySelectionFirstAndPersistsOnlyOnce() = runTest {
        val dao = MemoryPreferences()
        val old = ReaderConfig.DEFAULT.copy(theme = ThemeKey.GREEN, fontSizeSp = 35f, backgroundImageAlpha = .61f)
        val legacyJson = ReaderConfigJson.encode(old)
        dao.seed(SettingsRepository.KEY_GLOBAL, legacyJson)
        val settings = SettingsRepository(dao, this)

        val first = settings.appAppearance.first()
        assertEquals(ThemeKey.GREEN, first.day.theme)
        assertEquals(.61f, first.day.backgroundImageAlpha)
        assertEquals(1, dao.appearanceWriteCount())
        assertEquals(first, settings.currentAppAppearance())
        assertEquals(first, SettingsRepository(dao, this).currentAppAppearance())
        assertEquals(1, dao.appearanceWriteCount())
        assertEquals(legacyJson, dao.get(SettingsRepository.KEY_GLOBAL))
    }

    @Test fun concurrentFirstReadersShareOneMigration() = runTest {
        val dao = MemoryPreferences()
        dao.seed(SettingsRepository.KEY_GLOBAL, ReaderConfigJson.encode(ReaderConfig.DEFAULT.copy(theme = ThemeKey.BLACK)))
        val settings = SettingsRepository(dao, this)
        val first = async { settings.appAppearance.first() }
        val second = async { settings.currentAppAppearance() }
        assertEquals(first.await(), second.await())
        assertEquals(1, dao.appearanceWriteCount())
        assertEquals(ThemeKey.BLACK, dao.appearance().night.theme)
    }

    @Test fun newInstallationUsesSystemPaperAndDarkDefaults() = runTest {
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao, this)
        assertEquals(AppAppearance.DEFAULT, settings.currentAppAppearance())
        assertEquals(AppAppearance.DEFAULT, dao.appearance())
        assertNull(dao.get(SettingsRepository.KEY_GLOBAL))
    }

    @Test fun savedAppearanceWinsOverLaterLegacyConfigChanges() = runTest {
        val dao = MemoryPreferences()
        val saved = AppAppearance(mode = AppThemeMode.DAY, night = ReadingStyle.NIGHT_DEFAULT.copy(theme = ThemeKey.BLACK))
        dao.seed(SettingsRepository.KEY_APP_APPEARANCE, AppAppearanceJson.encode(saved))
        dao.seed(SettingsRepository.KEY_GLOBAL, ReaderConfigJson.encode(ReaderConfig.DEFAULT.copy(theme = ThemeKey.GREEN)))
        val settings = SettingsRepository(dao, this)
        assertEquals(saved, settings.appAppearance.first())
        settings.saveGlobal(ReaderConfig.DEFAULT.copy(theme = ThemeKey.SEPIA))
        assertEquals(saved, settings.currentAppAppearance())
        assertEquals(saved, SettingsRepository(dao, this).currentAppAppearance())
        assertEquals(0, dao.appearanceWriteCount())
    }

    @Test fun corruptedExistingRecordIsNotReplacedByLegacyMigration() = runTest {
        val dao = MemoryPreferences()
        dao.seed(SettingsRepository.KEY_APP_APPEARANCE, "{damaged")
        dao.seed(SettingsRepository.KEY_GLOBAL, ReaderConfigJson.encode(ReaderConfig.DEFAULT.copy(theme = ThemeKey.GREEN)))
        val settings = SettingsRepository(dao, this)
        assertEquals(AppAppearance.DEFAULT, settings.appAppearance.first())
        assertEquals("{damaged", dao.get(SettingsRepository.KEY_APP_APPEARANCE))
        assertEquals(0, dao.appearanceWriteCount())
    }

    @Test fun queuedChangesBeforeFirstReadKeepCallOrderAndBothStyleGroups() = runTest {
        val dao = MemoryPreferences()
        dao.seed(SettingsRepository.KEY_GLOBAL, ReaderConfigJson.encode(ReaderConfig.DEFAULT.copy(theme = ThemeKey.GREEN)))
        val settings = SettingsRepository(dao, this)
        settings.queueAppThemeMode(AppThemeMode.NIGHT, this)
        settings.queueReadingConfig(ReaderConfig.DEFAULT.copy(theme = ThemeKey.BLACK, fontSizeSp = 30f), true, this)
        settings.queueReadingConfig(ReaderConfig.DEFAULT.copy(theme = ThemeKey.SEPIA, fontSizeSp = 33f), false, this)

        val current = settings.currentAppAppearance()
        assertEquals(AppThemeMode.NIGHT, current.mode)
        assertEquals(ThemeKey.SEPIA, current.day.theme)
        assertEquals(ThemeKey.BLACK, current.night.theme)
        assertEquals(33f, settings.currentGlobal().fontSizeSp)
        settings.flushReadingConfig(this)
        advanceUntilIdle()
        assertEquals(current, dao.appearance())
        assertEquals(33f, settings.currentGlobal().fontSizeSp)
    }

    @Test fun delayedStyleEventDoesNotResetTheNewModeOrTheOtherGroup() = runTest {
        val dao = MemoryPreferences()
        val saved = AppAppearance(night = ReadingStyle.NIGHT_DEFAULT.copy(theme = ThemeKey.BLACK, backgroundImageUri = "/night.jpg"))
        dao.seed(SettingsRepository.KEY_APP_APPEARANCE, AppAppearanceJson.encode(saved))
        val settings = SettingsRepository(dao, this)
        settings.currentAppAppearance()
        val oldDayConfig = saved.resolveReaderConfig(ReaderConfig.DEFAULT, false)
        settings.queueAppThemeMode(AppThemeMode.NIGHT, this)
        settings.queueReadingConfig(oldDayConfig.copy(theme = ThemeKey.GREEN, fontSizeSp = 34f), false, this)
        val current = settings.appAppearance.first()
        assertEquals(AppThemeMode.NIGHT, current.mode)
        assertEquals(ThemeKey.GREEN, current.day.theme)
        assertEquals(saved.night, current.night)
        settings.flushReadingConfig(this)
        advanceUntilIdle()
        assertEquals(current, dao.appearance())
    }

    @Test fun rapidChangesCoalesceAndExitFlushSavesBothRecords() = runTest {
        val dao = MemoryPreferences()
        dao.seed(SettingsRepository.KEY_APP_APPEARANCE, AppAppearanceJson.encode(AppAppearance.DEFAULT))
        val settings = SettingsRepository(dao, this)
        settings.currentAppAppearance()
        for (size in 12..40) {
            settings.queueReadingConfig(ReaderConfig.DEFAULT.copy(fontSizeSp = size.toFloat(), theme = ThemeKey.GREEN), false, this)
        }
        settings.queueAppThemeMode(AppThemeMode.NIGHT, this)
        settings.flushReadingConfig(this)
        advanceUntilIdle()
        assertEquals(1, dao.appearanceWriteCount())
        assertEquals(1, dao.writes.count { it.key == SettingsRepository.KEY_GLOBAL })
        assertEquals(40f, settings.currentGlobal().fontSizeSp)
        assertEquals(settings.currentGlobal(), settings.currentGlobal())
        assertEquals(AppThemeMode.NIGHT, dao.appearance().mode)
        assertEquals(ThemeKey.GREEN, dao.appearance().day.theme)
    }

    @Test fun queuedModeAndReadingChangesSurviveUiScopeCancellation() = runTest {
        val dao = MemoryPreferences()
        dao.seed(SettingsRepository.KEY_APP_APPEARANCE, AppAppearanceJson.encode(AppAppearance.DEFAULT))
        val settings = SettingsRepository(dao, this)
        settings.currentAppAppearance()
        val uiScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        settings.queueReadingConfig(ReaderConfig.DEFAULT.copy(theme = ThemeKey.SEPIA, fontSizeSp = 37f), false, uiScope)
        settings.queueAppThemeMode(AppThemeMode.NIGHT, uiScope)
        advanceTimeBy(150)
        uiScope.cancel()
        advanceUntilIdle()
        assertEquals(AppThemeMode.NIGHT, dao.appearance().mode)
        assertEquals(ThemeKey.SEPIA, dao.appearance().day.theme)
        assertEquals(37f, ReaderConfigJson.decode(dao.get(SettingsRepository.KEY_GLOBAL)).fontSizeSp)
    }

    @Test fun failedSaveRetainsLatestUiStateAndRetryCommitsIt() = runTest {
        val dao = MemoryPreferences()
        dao.seed(SettingsRepository.KEY_APP_APPEARANCE, AppAppearanceJson.encode(AppAppearance.DEFAULT))
        val settings = SettingsRepository(dao, this)
        settings.currentAppAppearance()
        dao.failNextAppearanceWrite = true
        settings.queueAppThemeMode(AppThemeMode.NIGHT, this)
        settings.flushAppAppearance(this)
        advanceUntilIdle()
        assertTrue(settings.appAppearanceSaveFailed.first())
        assertEquals(AppThemeMode.NIGHT, settings.currentAppAppearance().mode)
        assertEquals(AppThemeMode.NIGHT, settings.appAppearance.first().mode)
        assertEquals(AppThemeMode.SYSTEM, dao.appearance().mode)
        settings.retryAppAppearance(this)
        advanceUntilIdle()
        assertFalse(settings.appAppearanceSaveFailed.first())
        assertEquals(AppThemeMode.NIGHT, dao.appearance().mode)
    }

    @Test fun failedInitialMigrationKeepsLegacyAndCanBeRetried() = runTest {
        val dao = MemoryPreferences()
        val legacy = ReaderConfigJson.encode(ReaderConfig.DEFAULT.copy(theme = ThemeKey.GREEN))
        dao.seed(SettingsRepository.KEY_GLOBAL, legacy)
        dao.failNextAppearanceWrite = true
        val settings = SettingsRepository(dao, this)
        assertEquals(ThemeKey.GREEN, settings.currentAppAppearance().day.theme)
        assertTrue(settings.appAppearanceSaveFailed.first())
        assertNull(dao.get(SettingsRepository.KEY_APP_APPEARANCE))
        assertEquals(legacy, dao.get(SettingsRepository.KEY_GLOBAL))
        settings.retryAppAppearance(this)
        advanceUntilIdle()
        assertEquals(ThemeKey.GREEN, dao.appearance().day.theme)
        assertFalse(settings.appAppearanceSaveFailed.first())
    }

    @Test fun explicitSaveReportsFailureButKeepsTheUnsavedValueForRetry() = runTest {
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao, this)
        dao.failNextAppearanceWrite = true
        val requested = AppAppearance.DEFAULT.copy(mode = AppThemeMode.DAY)
        try {
            settings.saveAppAppearance(requested)
            fail("Direct save must report a failed disk write")
        } catch (_: IllegalStateException) {
            assertEquals(requested, settings.currentAppAppearance())
        }
        assertTrue(settings.appAppearanceSaveFailed.first())
        settings.retryAppAppearance(this)
        advanceUntilIdle()
        assertEquals(requested, dao.appearance())
        assertFalse(settings.appAppearanceSaveFailed.first())
    }

    @Test fun staleInFlightFailureCannotRollBackNewModeOrStyle() = runTest {
        val dao = MemoryPreferences()
        dao.seed(SettingsRepository.KEY_APP_APPEARANCE, AppAppearanceJson.encode(AppAppearance.DEFAULT))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var appearanceCalls = 0
        dao.beforePut = { preference ->
            if (preference.key == SettingsRepository.KEY_APP_APPEARANCE && ++appearanceCalls == 1) {
                entered.complete(Unit)
                release.await()
                throw IllegalStateException("Old write failed")
            }
        }
        val settings = SettingsRepository(dao, this)
        settings.currentAppAppearance()
        settings.queueAppThemeMode(AppThemeMode.DAY, this)
        settings.flushAppAppearance(this)
        runCurrent()
        entered.await()

        settings.queueReadingConfig(ReaderConfig.DEFAULT.copy(
            theme = ThemeKey.BLACK,
            backgroundType = BackgroundType.IMAGE,
            backgroundImageUri = "/night.jpg",
            backgroundImageAlpha = .38f
        ), true, this)
        settings.queueAppThemeMode(AppThemeMode.NIGHT, this)
        settings.flushReadingConfig(this)
        assertEquals(AppThemeMode.NIGHT, settings.appAppearance.first().mode)
        assertEquals(ThemeKey.BLACK, settings.currentAppAppearance().night.theme)

        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(settings.currentAppAppearance(), dao.appearance())
        assertEquals(AppThemeMode.NIGHT, dao.appearance().mode)
        assertEquals("/night.jpg", dao.appearance().night.backgroundImageUri)
        assertEquals(.38f, dao.appearance().night.backgroundImageAlpha)
        assertFalse(settings.appAppearanceSaveFailed.first())
    }

    @Test fun oldSettingsApiDoesNotEagerlyCreateANewAppearanceRecord() = runTest {
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao, this)
        settings.currentGlobal()
        settings.queueGlobalConfig(ReaderConfig.DEFAULT.copy(fontSizeSp = 26f), this)
        settings.flushGlobalConfig(this)
        advanceUntilIdle()
        assertEquals(26f, settings.currentGlobal().fontSizeSp)
        assertNull(dao.get(SettingsRepository.KEY_APP_APPEARANCE))
    }
}
