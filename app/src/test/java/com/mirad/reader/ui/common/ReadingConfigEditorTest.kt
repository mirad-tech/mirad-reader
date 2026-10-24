package com.mirad.reader.ui.common

import com.mirad.reader.core.model.AppAppearance
import com.mirad.reader.core.model.AppThemeMode
import com.mirad.reader.core.model.FontFamilyKey
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.ReaderConfigJson
import com.mirad.reader.core.model.ReadingStyle
import com.mirad.reader.core.model.ThemeKey
import com.mirad.reader.data.db.dao.PreferenceDao
import com.mirad.reader.data.db.entity.PreferenceEntity
import com.mirad.reader.data.repo.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingConfigEditorTest {
    private class MemoryPreferences : PreferenceDao {
        private val values = mutableMapOf<String, PreferenceEntity>()
        private val flows = mutableMapOf<String, MutableStateFlow<String?>>()
        var holdNextGlobalRead: CompletableDeferred<Unit>? = null
        var failNextGlobalRead = false
        override suspend fun get(key: String): String? {
            val value = values[key]?.value
            if (key == SettingsRepository.KEY_GLOBAL) {
                if (failNextGlobalRead) { failNextGlobalRead = false; error("read unavailable") }
                val hold = holdNextGlobalRead
                holdNextGlobalRead = null
                hold?.await()
            }
            return value
        }
        override suspend fun put(preference: PreferenceEntity) {
            values[preference.key] = preference
            flows.getOrPut(preference.key) { MutableStateFlow(null) }.value = preference.value
        }
        override fun observe(key: String) = flows.getOrPut(key) { MutableStateFlow(values[key]?.value) }
        override suspend fun remove(key: String) { values.remove(key); flows[key]?.value = null }
        override suspend fun removeByPrefix(prefix: String) {
            values.keys.filter { it.startsWith(prefix) }.toList().forEach { key ->
                values.remove(key)
                flows[key]?.value = null
            }
        }
        override suspend fun getAll() = values.values.toList()
    }

    @Test fun editsQueuedDuringAReadPreserveOrderAndUseTheLatestConfiguration() = runTest {
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao, this)
        // 直接种库值：saveGlobal 现在会留下 pending 缓存，currentGlobal 就不再触发 dao.get，
        // 本测试的挂起注入点（holdNextGlobalRead）会失效。
        dao.put(PreferenceEntity(SettingsRepository.KEY_GLOBAL, ReaderConfigJson.encode(ReaderConfig.DEFAULT), 0L))
        settings.currentAppAppearance()
        val editor = ReadingConfigEditor(settings, this, StandardTestDispatcher(testScheduler))
        val hold = CompletableDeferred<Unit>()
        dao.holdNextGlobalRead = hold
        val order = mutableListOf<Int>()
        editor.update(false, { it.copy(fontSizeSp = it.fontSizeSp + 1f) }) { _, _ -> order += 1 }
        editor.update(false, { it.copy(theme = ThemeKey.GREEN) }) { _, _ -> order += 2 }
        editor.update(false, { it.copy(fontSizeSp = it.fontSizeSp + 1f) }) { _, _ -> order += 3 }
        assertTrue(order.isEmpty())
        hold.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3), order)
        assertEquals(ReaderConfig.DEFAULT.fontSizeSp + 2f, settings.currentGlobal().fontSizeSp)
        assertEquals(ThemeKey.GREEN, settings.currentAppAppearance().day.theme)
    }

    @Test fun slowFontImportAppliesOnlyFontFieldsToCurrentValuesAndKeepsBothProfiles() = runTest {
        val settings = SettingsRepository(MemoryPreferences(), this)
        settings.saveGlobal(ReaderConfig.DEFAULT)
        settings.currentAppAppearance()
        val editor = ReadingConfigEditor(settings, this, StandardTestDispatcher(testScheduler))
        val imported = CompletableDeferred<Unit>()
        var currentUpdate: ((ReaderConfig) -> ReaderConfig) -> Unit = { editor.update(false, it) }
        val importJob = launch(start = CoroutineStart.UNDISPATCHED) {
            imported.await()
            currentUpdate { it.copy(fontFamily = FontFamilyKey.CUSTOM, customFontAsset = null, customFontPath = "/fonts/new.ttf") }
        }
        editor.update(false, { it.copy(fontSizeSp = 38f, theme = ThemeKey.GREEN) })
        advanceUntilIdle()
        settings.queueAppThemeMode(AppThemeMode.NIGHT, this)
        editor.update(true, { it.copy(theme = ThemeKey.BLACK) })
        advanceUntilIdle()
        currentUpdate = { editor.update(true, it) }
        imported.complete(Unit)
        advanceUntilIdle()
        assertTrue(importJob.isCompleted)
        val appearance = settings.currentAppAppearance()
        val current = settings.currentGlobal()
        assertEquals(38f, current.fontSizeSp)
        assertEquals("/fonts/new.ttf", current.customFontPath)
        assertEquals(AppThemeMode.NIGHT, appearance.mode)
        assertEquals(ThemeKey.GREEN, appearance.day.theme)
        assertEquals(ThemeKey.BLACK, appearance.night.theme)
    }

    @Test fun aRecreatedUiOwnerSharesTheQueueWithAnOlderOwnerStillReading() = runTest {
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao, this)
        dao.put(PreferenceEntity(SettingsRepository.KEY_GLOBAL, ReaderConfigJson.encode(ReaderConfig.DEFAULT), 0L))
        settings.currentAppAppearance()
        val oldOwner = ReadingConfigEditor(settings, this, StandardTestDispatcher(testScheduler))
        val newOwner = ReadingConfigEditor(settings, this, StandardTestDispatcher(testScheduler))
        val hold = CompletableDeferred<Unit>()
        dao.holdNextGlobalRead = hold
        val completed = mutableListOf<String>()
        oldOwner.update(false, { it.copy(fontSizeSp = it.fontSizeSp + 1f) }) { _, _ -> completed += "old" }
        newOwner.update(false, { it.copy(theme = ThemeKey.GREEN, fontSizeSp = it.fontSizeSp + 1f) }) { _, _ -> completed += "new" }
        runCurrent()
        assertTrue("the new owner must not pass the suspended read", completed.isEmpty())
        hold.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("old", "new"), completed)
        assertEquals(ReaderConfig.DEFAULT.fontSizeSp + 2f, settings.currentGlobal().fontSizeSp)
        assertEquals(ThemeKey.GREEN, settings.currentAppAppearance().day.theme)
    }

    @Test fun pageCancellationDoesNotCancelAnAcceptedEditAndFlushWaitsForItsRead() = runTest {
        val marker = ThreadLocal<String>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher + marker.asContextElement("application"))
        val pageScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val dao = MemoryPreferences()
            val settings = SettingsRepository(dao, appScope)
            dao.put(PreferenceEntity(SettingsRepository.KEY_GLOBAL, ReaderConfigJson.encode(ReaderConfig.DEFAULT), 0L))
            settings.currentAppAppearance()
            val callbacks = mutableListOf<String?>()
            val editor = ReadingConfigEditor(settings, appScope, dispatcher + marker.asContextElement("ui"))
            val hold = CompletableDeferred<Unit>()
            dao.holdNextGlobalRead = hold
            pageScope.launch(start = CoroutineStart.UNDISPATCHED) {
                editor.update(false, { it.copy(fontSizeSp = 41f) }) { _, _ -> callbacks += marker.get() }
                editor.flush()
            }
            pageScope.cancel()
            hold.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("ui"), callbacks)
            assertEquals(41f, ReaderConfigJson.decode(dao.get(SettingsRepository.KEY_GLOBAL)).fontSizeSp)
        } finally { pageScope.cancel(); appScope.cancel() }
    }

    @Test fun callbackResolvesTheModeThatChangedWhileAConfigurationReadWasSuspended() = runTest {
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao, this)
        settings.saveGlobal(ReaderConfig.DEFAULT)
        settings.currentAppAppearance()
        val editor = ReadingConfigEditor(settings, this, StandardTestDispatcher(testScheduler))
        val hold = CompletableDeferred<Unit>()
        dao.holdNextGlobalRead = hold
        var applied: AppAppearance? = null
        editor.update(false, { it.copy(fontSizeSp = 33f) }) { _, appearance -> applied = appearance }
        settings.queueAppThemeMode(AppThemeMode.NIGHT, this)
        hold.complete(Unit)
        advanceUntilIdle()
        assertEquals(AppThemeMode.NIGHT, applied?.mode)
        assertTrue(applied!!.effectiveDark(false))
    }

    @Test fun aQueuedResetRunsAfterOlderEditsAndRetainsApplicationMode() = runTest {
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao, this)
        dao.put(PreferenceEntity(SettingsRepository.KEY_GLOBAL, ReaderConfigJson.encode(ReaderConfig.DEFAULT), 0L))
        settings.saveAppAppearance(AppAppearance.DEFAULT.copy(mode = AppThemeMode.NIGHT))
        val editor = ReadingConfigEditor(settings, this, StandardTestDispatcher(testScheduler))
        val hold = CompletableDeferred<Unit>()
        dao.holdNextGlobalRead = hold
        editor.update(true, { it.copy(fontSizeSp = 40f, theme = ThemeKey.BLACK) })
        editor.resetReading(setOf(ResetScope.LAYOUT_INTERACTION, ResetScope.DAY_STYLE, ResetScope.NIGHT_STYLE))
        hold.complete(Unit)
        advanceUntilIdle()
        // 三范围全勾：排版恢复默认；五个样式字段按 LAYOUT_INTERACTION 规则保留当前值；外观回到默认（仅保模式）。
        val global = settings.currentGlobal()
        assertEquals(ReaderConfig.DEFAULT.fontSizeSp, global.fontSizeSp)
        assertEquals(ThemeKey.BLACK, global.theme)
        assertEquals(AppAppearance.DEFAULT.copy(mode = AppThemeMode.NIGHT), settings.currentAppAppearance())
    }

    @Test fun layoutInteractionResetRestoresDefaultsButKeepsBothStylesAndMode() = runTest {
        val settings = SettingsRepository(MemoryPreferences(), this)
        settings.saveGlobal(ReaderConfig.DEFAULT)
        settings.currentAppAppearance()
        settings.queueAppThemeMode(AppThemeMode.NIGHT, this)
        val editor = ReadingConfigEditor(settings, this, StandardTestDispatcher(testScheduler))
        editor.update(false, { it.copy(theme = ThemeKey.GREEN) })
        editor.update(true, { it.copy(theme = ThemeKey.BLACK) })
        editor.update(true, { it.copy(fontSizeSp = 40f, horizontalPaddingDp = 32) })
        advanceUntilIdle()

        editor.resetReading(setOf(ResetScope.LAYOUT_INTERACTION))
        advanceUntilIdle()

        val global = settings.currentGlobal()
        assertEquals(ReaderConfig.DEFAULT.fontSizeSp, global.fontSizeSp)
        assertEquals(ReaderConfig.DEFAULT.horizontalPaddingDp, global.horizontalPaddingDp)
        val appearance = settings.currentAppAppearance()
        assertEquals(AppThemeMode.NIGHT, appearance.mode)
        assertEquals(ThemeKey.GREEN, appearance.day.theme)
        assertEquals(ThemeKey.BLACK, appearance.night.theme)
    }

    @Test fun dayStyleResetOnlyRestoresTheDayGroup() = runTest {
        val settings = SettingsRepository(MemoryPreferences(), this)
        settings.saveGlobal(ReaderConfig.DEFAULT)
        settings.currentAppAppearance()
        val editor = ReadingConfigEditor(settings, this, StandardTestDispatcher(testScheduler))
        editor.update(false, { it.copy(theme = ThemeKey.GREEN, fontSizeSp = 40f) })
        editor.update(true, { it.copy(theme = ThemeKey.BLACK) })
        advanceUntilIdle()

        editor.resetReading(setOf(ResetScope.DAY_STYLE))
        advanceUntilIdle()

        val appearance = settings.currentAppAppearance()
        assertEquals(ReadingStyle.DAY_DEFAULT, appearance.day)
        assertEquals(ThemeKey.BLACK, appearance.night.theme)
        assertEquals(40f, settings.currentGlobal().fontSizeSp)
    }

    @Test fun nightStyleResetOnlyRestoresTheNightGroup() = runTest {
        val settings = SettingsRepository(MemoryPreferences(), this)
        settings.saveGlobal(ReaderConfig.DEFAULT)
        settings.currentAppAppearance()
        val editor = ReadingConfigEditor(settings, this, StandardTestDispatcher(testScheduler))
        editor.update(false, { it.copy(theme = ThemeKey.GREEN) })
        editor.update(true, { it.copy(theme = ThemeKey.BLACK) })
        advanceUntilIdle()

        editor.resetReading(setOf(ResetScope.NIGHT_STYLE))
        advanceUntilIdle()

        val appearance = settings.currentAppAppearance()
        assertEquals(ReadingStyle.NIGHT_DEFAULT, appearance.night)
        assertEquals(ThemeKey.GREEN, appearance.day.theme)
    }

    @Test fun aFailedReadDoesNotReplaceSavedSettingsWithDefaults() = runTest {
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao, this)
        val saved = ReaderConfig.DEFAULT.copy(fontSizeSp = 35f)
        dao.put(PreferenceEntity(SettingsRepository.KEY_GLOBAL, ReaderConfigJson.encode(saved), 0L))
        settings.currentAppAppearance()
        val failures = mutableListOf<String?>()
        val editor = ReadingConfigEditor(settings, this, StandardTestDispatcher(testScheduler), onFailure = { failures += it.message })
        dao.failNextGlobalRead = true
        editor.update(false, { it.copy(fontSizeSp = 42f) })
        runCurrent()
        assertEquals(listOf("read unavailable"), failures)
        assertEquals(saved, settings.currentGlobal())
    }
}
