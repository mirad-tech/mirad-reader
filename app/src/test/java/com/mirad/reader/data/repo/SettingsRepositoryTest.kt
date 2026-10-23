package com.mirad.reader.data.repo

import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.ReaderConfigJson
import com.mirad.reader.core.model.ThemeKey
import com.mirad.reader.data.db.dao.PreferenceDao
import com.mirad.reader.data.db.entity.PreferenceEntity
import com.mirad.reader.ui.common.GlassAppearance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {
    private class MemoryPreferences : PreferenceDao {
        val entries = mutableMapOf<String, PreferenceEntity>()
        val flows = mutableMapOf<String, MutableStateFlow<String?>>()
        var writes = 0
        var failNextPut = false
        override suspend fun put(preference: PreferenceEntity) {
            if (failNextPut) {
                failNextPut = false
                throw IllegalStateException("Disk write failure")
            }
            entries[preference.key] = preference
            flows.getOrPut(preference.key) { MutableStateFlow(null) }.value = preference.value
            writes++
        }
        override suspend fun get(key: String) = entries[key]?.value
        override fun observe(key: String): Flow<String?> =
            flows.getOrPut(key) { MutableStateFlow(entries[key]?.value) }
        override suspend fun remove(key: String) {
            entries.remove(key)
            flows[key]?.value = null
        }
        override suspend fun getAll() = entries.values.toList()
    }

    @Test fun reopeningBeforeDebounceReadsLatestSize() = runTest {
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao)
        settings.queueBookConfig(1, ReaderConfig.DEFAULT.copy(fontSizeSp = 32f), this)
        assertEquals(32f, settings.effective(1).fontSizeSp)
        assertEquals(0, dao.writes)
        advanceUntilIdle()
        assertEquals(32f, ReaderConfigJson.decode(dao.get(SettingsRepository.KEY_GLOBAL)).fontSizeSp)
    }

    @Test fun rapidChangesOnlyPersistLastValueAndExitFlushes() = runTest {
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao)
        for (size in 12..40) settings.queueBookConfig(1, ReaderConfig.DEFAULT.copy(fontSizeSp = size.toFloat()), this)
        settings.flushBookConfig(1, this)
        advanceUntilIdle()
        assertEquals(1, dao.writes)
        assertEquals(40f, ReaderConfigJson.decode(dao.get(SettingsRepository.KEY_GLOBAL)).fontSizeSp)
    }

    @Test fun resettingCannotBeOverwrittenByPendingSliderSave() = runTest {
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao)
        settings.saveGlobal(ReaderConfig.DEFAULT.copy(fontSizeSp = 22f))
        settings.queueBookConfig(1, ReaderConfig.DEFAULT.copy(fontSizeSp = 40f), this)
        settings.saveGlobal(ReaderConfig.DEFAULT.copy(fontSizeSp = 22f))
        settings.flushBookConfig(1, this)
        advanceUntilIdle()
        assertEquals(22f, settings.effective(1).fontSizeSp)
    }

    @Test fun settingsAreUniversalAcrossBooksAndNewBooksInheritLatestConfig() = runTest {
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao)
        // 在书 1 中修改字号为 28sp，主题为绿色
        val customConfig = ReaderConfig.DEFAULT.copy(fontSizeSp = 28f, theme = ThemeKey.GREEN)
        settings.queueBookConfig(1, customConfig, this)
        // 书 2 应当立即通用继承此配置，无需重复设置
        assertEquals(28f, settings.effective(2).fontSizeSp)
        assertEquals(ThemeKey.GREEN, settings.effective(2).theme)
        // 任何新打开的书（书 3）也直接生效
        assertEquals(28f, settings.effective(3).fontSizeSp)
        assertEquals(ThemeKey.GREEN, settings.effective(3).theme)
        settings.flushBookConfig(1, this)
        advanceUntilIdle()
        // 落盘后重新读取，新书依然保持最新通用阅读偏好
        assertEquals(28f, settings.effective(999).fontSizeSp)
        assertEquals(ThemeKey.GREEN, settings.effective(999).theme)
    }

    @Test fun globalSettingSurvivesImmediatePageExit() = runTest {
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao)
        settings.queueGlobalConfig(ReaderConfig.DEFAULT.copy(fontSizeSp = 29f), this)
        assertEquals(29f, settings.currentGlobal().fontSizeSp)
        assertEquals(29f, settings.effective(2).fontSizeSp)
        settings.flushGlobalConfig(this)
        advanceUntilIdle()
        assertEquals(29f, ReaderConfigJson.decode(dao.get(SettingsRepository.KEY_GLOBAL)).fontSizeSp)
        assertEquals(1, dao.writes)
    }

    @Test fun globalConfigQueueFollowsCallerScopeCancellationUnlessFlushed() = runTest {
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao)
        val transientScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        settings.queueGlobalConfig(ReaderConfig.DEFAULT.copy(fontSizeSp = 31f), transientScope)
        advanceTimeBy(100)
        transientScope.cancel()
        advanceUntilIdle()
        // B1: queueGlobalConfig follows the caller scope's Job lifecycle
        assertNull(dao.get(SettingsRepository.KEY_GLOBAL))
    }

    @Test fun glassAppearanceSurvivesCallerScopeCancellationWithinDebounceWindow() = runTest {
        val dao = MemoryPreferences()
        val repoScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val settings = SettingsRepository(dao, repoScope)
        val uiScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        settings.queueGlassAppearance(GlassAppearance(78), uiScope)
        assertEquals(78, settings.currentGlassAppearance().transparencyPercent)
        advanceTimeBy(150)
        // Simulate configuration change / page exit cancelling UI scope before 400ms
        uiScope.cancel()
        advanceUntilIdle()
        assertEquals("78", dao.get(SettingsRepository.KEY_GLASS_TRANSPARENCY))
        assertEquals(78, settings.currentGlassAppearance().transparencyPercent)
    }

    @Test fun glassAppearanceDualEntrySyncAndResetDefault() = runTest {
        val dao = MemoryPreferences()
        val repoScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val settings = SettingsRepository(dao, repoScope)
        // Entry 1 (e.g. Global Settings) updates to 35%
        settings.queueGlassAppearance(GlassAppearance(35), this)
        assertEquals(35, settings.glassAppearance.first().transparencyPercent)
        // Entry 2 (e.g. Reader Sheet) immediately sees 35% and updates to 85% before debounce finishes
        advanceTimeBy(100)
        settings.queueGlassAppearance(GlassAppearance(85), this)
        assertEquals(85, settings.glassAppearance.first().transparencyPercent)
        advanceUntilIdle()
        assertEquals(1, dao.writes)
        assertEquals("85", dao.get(SettingsRepository.KEY_GLASS_TRANSPARENCY))

        // Reset to default (60%)
        settings.resetGlassAppearance(this)
        advanceUntilIdle()
        assertEquals(60, settings.currentGlassAppearance().transparencyPercent)
        assertEquals("60", dao.get(SettingsRepository.KEY_GLASS_TRANSPARENCY))
    }

    @Test fun glassAppearanceSaveFailureRetainsInMemoryValueAndExposesError() = runTest {
        val dao = MemoryPreferences()
        val repoScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val settings = SettingsRepository(dao, repoScope)
        dao.failNextPut = true
        settings.queueGlassAppearance(GlassAppearance(42), this)
        advanceUntilIdle()
        assertTrue(settings.glassSaveFailed.first())
        // In-memory state must remain 42 so UI does not silently revert while user is still on screen
        assertEquals(42, settings.currentGlassAppearance().transparencyPercent)
        assertEquals(42, settings.glassAppearance.first().transparencyPercent)

        // Subsequent retry/flush succeeds and clears failure flag
        settings.retryGlassAppearance(this)
        advanceUntilIdle()
        assertFalse(settings.glassSaveFailed.first())
        assertEquals("42", dao.get(SettingsRepository.KEY_GLASS_TRANSPARENCY))
    }

    @Test fun staleFailedWriteDoesNotOverwriteNewerQueuedSelectionOrState() = runTest {
        val firstWriteEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseFirstWrite = kotlinx.coroutines.CompletableDeferred<Unit>()
        var callIndex = 0
        val dao = object : PreferenceDao {
            val entries = mutableMapOf<String, PreferenceEntity>()
            val flows = mutableMapOf<String, MutableStateFlow<String?>>()
            override suspend fun put(preference: PreferenceEntity) {
                callIndex++
                if (callIndex == 1) {
                    firstWriteEntered.complete(Unit)
                    releaseFirstWrite.await()
                    throw IllegalStateException("Stale write failure")
                }
                entries[preference.key] = preference
                flows.getOrPut(preference.key) { MutableStateFlow(null) }.value = preference.value
            }
            override suspend fun get(key: String) = entries[key]?.value
            override fun observe(key: String): Flow<String?> =
                flows.getOrPut(key) { MutableStateFlow(entries[key]?.value) }
            override suspend fun remove(key: String) {
                entries.remove(key)
                flows[key]?.value = null
            }
            override suspend fun getAll() = entries.values.toList()
        }
        val repoScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val settings = SettingsRepository(dao, repoScope)

        // 1. Trigger first write (40%) and let it enter dao.put()
        settings.queueGlassAppearance(GlassAppearance(40), this)
        settings.flushGlassAppearance(this)
        testScheduler.runCurrent()
        firstWriteEntered.await()

        // 2. While first write is suspended in dao.put(), user selects 85% and commits
        settings.queueGlassAppearance(GlassAppearance(85), this)
        settings.flushGlassAppearance(this)
        assertEquals(85, settings.currentGlassAppearance().transparencyPercent)

        // 3. Release first (stale) failing write; second write (85%) then runs and succeeds
        releaseFirstWrite.complete(Unit)
        advanceUntilIdle()

        assertFalse("Stale write failure must not leave glassSaveFailed=true after newer write succeeds", settings.glassSaveFailed.first())
        assertEquals(85, settings.currentGlassAppearance().transparencyPercent)
        assertEquals("85", dao.get(SettingsRepository.KEY_GLASS_TRANSPARENCY))
    }
}
