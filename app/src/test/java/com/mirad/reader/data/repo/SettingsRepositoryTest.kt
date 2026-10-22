package com.mirad.reader.data.repo

import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.ReaderConfigJson
import com.mirad.reader.core.model.ThemeKey
import com.mirad.reader.data.db.dao.PreferenceDao
import com.mirad.reader.data.db.entity.PreferenceEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {
    private class MemoryPreferences : PreferenceDao {
        val entries = mutableMapOf<String, PreferenceEntity>()
        var writes = 0
        override suspend fun put(preference: PreferenceEntity) { entries[preference.key] = preference; writes++ }
        override suspend fun get(key: String) = entries[key]?.value
        override fun observe(key: String): Flow<String?> = MutableStateFlow(entries[key]?.value)
        override suspend fun remove(key: String) { entries.remove(key) }
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
}
