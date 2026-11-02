package com.mirad.reader.ui.common

import com.mirad.reader.deleteDroppedBackgroundImages
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
import com.mirad.reader.data.repo.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 范围重置必须维护新旧配置的同一文件引用契约。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingConfigResetPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun resettingDayProjectsAllFiveStyleFieldsToLegacy() = assertResetProjection(false, false)
    @Test fun resettingNightProjectsAllFiveStyleFieldsToLegacy() = assertResetProjection(true, false)
    @Test fun resettingDayTogetherWithLayoutStillProjectsTheNewStyle() = assertResetProjection(false, true)
    @Test fun resettingNightTogetherWithLayoutStillProjectsTheNewStyle() = assertResetProjection(true, true)

    private fun assertResetProjection(dark: Boolean, resetLayout: Boolean) = runTest {
        val dao = MemoryPreferences()
        val cleanups = mutableListOf<List<String>>()
        val referencesAtCleanup = mutableListOf<Set<String>>()
        val settings = SettingsRepository(dao, this) { candidates ->
            cleanups += candidates
            val app = AppAppearanceJson.decode(dao.values[SettingsRepository.KEY_APP_APPEARANCE]?.value)
            val legacy = ReaderConfigJson.decode(dao.values[SettingsRepository.KEY_GLOBAL]?.value)
            referencesAtCleanup += setOfNotNull(app.day.backgroundImageUri, app.night.backgroundImageUri, legacy.backgroundImageUri)
        }
        val originalStyle = (if (dark) ReadingStyle.NIGHT_DEFAULT else ReadingStyle.DAY_DEFAULT).copy(
            theme = if (dark) ThemeKey.BLACK else ThemeKey.GREEN,
            backgroundType = BackgroundType.IMAGE,
            backgroundColor = if (dark) 0xFF101820.toInt() else 0xFFDDFFCC.toInt(),
            backgroundImageUri = "/backgrounds/selected.jpg", backgroundImageAlpha = .37f
        )
        val initial = AppAppearance.DEFAULT.withReadingStyle(dark, originalStyle).copy(mode = AppThemeMode.SYSTEM)
        settings.saveAppAppearance(initial)
        settings.saveGlobal(originalStyle.applyTo(ReaderConfig.DEFAULT.copy(fontSizeSp = 39f)))
        val editor = ReadingConfigEditor(settings, this, StandardTestDispatcher(testScheduler))
        val scopes = buildSet {
            add(if (dark) ResetScope.NIGHT_STYLE else ResetScope.DAY_STYLE)
            if (resetLayout) add(ResetScope.LAYOUT_INTERACTION)
        }

        editor.resetReading(scopes)
        advanceUntilIdle()
        editor.flush()
        advanceUntilIdle()

        val expected = if (dark) ReadingStyle.NIGHT_DEFAULT else ReadingStyle.DAY_DEFAULT
        val savedAppearance = AppAppearanceJson.decode(dao.values[SettingsRepository.KEY_APP_APPEARANCE]?.value)
        val savedGlobal = ReaderConfigJson.decode(dao.values[SettingsRepository.KEY_GLOBAL]?.value)
        assertEquals(expected, savedAppearance.style(dark))
        assertEquals(initial.style(!dark), savedAppearance.style(!dark))
        assertEquals(AppThemeMode.SYSTEM, savedAppearance.mode)
        assertEquals("五个兼容样式字段必须投影重置结果", expected, ReadingStyle.from(savedGlobal))
        assertEquals(if (resetLayout) ReaderConfig.DEFAULT.fontSizeSp else 39f, savedGlobal.fontSizeSp)
        assertEquals(listOf(listOf("/backgrounds/selected.jpg")), cleanups)
        assertTrue("回调发出时所有保留的持久配置都不能再引用候选", referencesAtCleanup.all { "/backgrounds/selected.jpg" !in it })
    }

    @Test fun resettingBothStylesKeepsModeAndDoesNotDeleteAReferencedImage() = runTest {
        val dao = MemoryPreferences()
        val dropped = mutableListOf<String>()
        val settings = SettingsRepository(dao, this) { dropped += it }
        val shared = "/backgrounds/shared.jpg"
        val initial = AppAppearance.DEFAULT.copy(
            mode = AppThemeMode.NIGHT,
            day = ReadingStyle.DAY_DEFAULT.copy(backgroundImageUri = shared),
            night = ReadingStyle.NIGHT_DEFAULT.copy(backgroundImageUri = shared)
        )
        settings.saveAppAppearance(initial)
        settings.saveGlobal(initial.night.applyTo(ReaderConfig.DEFAULT))
        val editor = ReadingConfigEditor(settings, this, StandardTestDispatcher(testScheduler))
        editor.resetReading(setOf(ResetScope.DAY_STYLE))
        advanceUntilIdle()
        assertTrue("夜间组仍引用同一图片", dropped.isEmpty())
        editor.resetReading(setOf(ResetScope.DAY_STYLE, ResetScope.NIGHT_STYLE))
        advanceUntilIdle()
        editor.flush()
        advanceUntilIdle()
        val saved = AppAppearanceJson.decode(dao.values[SettingsRepository.KEY_APP_APPEARANCE]?.value)
        assertEquals(AppThemeMode.NIGHT, saved.mode)
        assertEquals(ReadingStyle.DAY_DEFAULT, saved.day)
        assertEquals(ReadingStyle.NIGHT_DEFAULT, saved.night)
        assertNull(ReaderConfigJson.decode(dao.values[SettingsRepository.KEY_GLOBAL]?.value).backgroundImageUri)
        assertEquals(listOf(shared), dropped)
    }

    @Test fun appearanceOnlySaveCannotDeleteTheImageStillReferencedByPersistedLegacy() = runTest {
        val dao = MemoryPreferences()
        val dropped = mutableListOf<String>()
        val settings = SettingsRepository(dao, this) { dropped += it }
        val old = ReadingStyle.DAY_DEFAULT.copy(backgroundImageUri = "/backgrounds/old.jpg")
        settings.saveAppAppearance(AppAppearance.DEFAULT.copy(day = old))
        settings.saveGlobal(old.applyTo(ReaderConfig.DEFAULT))
        settings.saveAppAppearance(AppAppearance.DEFAULT)
        assertTrue("即使旧键没有排队写入，也必须核对它当前的持久引用", dropped.isEmpty())
        settings.saveGlobal(ReaderConfig.DEFAULT)
        assertEquals(listOf("/backgrounds/old.jpg"), dropped)
    }

    @Test fun aReopenedRepositoryChecksPersistedLegacyBeforeUsingTheRealFileConsumer() = runTest {
        val directory = temporary.newFolder("backgrounds")
        val image = directory.resolve("old.jpg").apply { writeText("synthetic image") }
        val outside = temporary.newFile("outside.jpg").apply { writeText("keep") }
        val old = ReadingStyle.DAY_DEFAULT.copy(backgroundImageUri = image.absolutePath)
        val dao = MemoryPreferences()
        dao.put(PreferenceEntity(SettingsRepository.KEY_GLOBAL, ReaderConfigJson.encode(old.applyTo(ReaderConfig.DEFAULT)), 0L))
        dao.put(PreferenceEntity(SettingsRepository.KEY_APP_APPEARANCE, AppAppearanceJson.encode(AppAppearance.DEFAULT.copy(day = old)), 0L))
        val settings = SettingsRepository(dao, this) { deleteDroppedBackgroundImages(directory, it) }
        settings.currentAppAppearance()

        settings.saveAppAppearance(AppAppearance.DEFAULT)
        assertTrue("尚未修改的磁盘旧键仍引用图片", image.exists())
        settings.saveGlobal(ReaderConfig.DEFAULT)
        assertFalse("两个持久键都解除引用后，实际消费者才删除文件", image.exists())
        deleteDroppedBackgroundImages(directory, listOf(outside.absolutePath))
        assertTrue("消费者只处理专用背景目录", outside.exists())
    }

    @Test fun aFailedAppearanceResetKeepsTheFileUntilTheExistingRetrySucceeds() = runTest {
        val directory = temporary.newFolder("failed-appearance")
        val image = directory.resolve("old.jpg").apply { writeText("synthetic image") }
        val style = ReadingStyle.DAY_DEFAULT.copy(backgroundImageUri = image.absolutePath)
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao, this) { deleteDroppedBackgroundImages(directory, it) }
        settings.saveAppAppearance(AppAppearance.DEFAULT.copy(day = style))
        settings.saveGlobal(style.applyTo(ReaderConfig.DEFAULT))
        val editor = ReadingConfigEditor(settings, this, StandardTestDispatcher(testScheduler))
        dao.failAppearanceWrites = true
        editor.resetReading(setOf(ResetScope.DAY_STYLE))
        advanceUntilIdle()
        assertTrue(settings.appAppearanceSaveFailed.first())
        assertTrue("新记录仍持久引用旧图，不能因旧投影已更新就删除", image.exists())

        dao.failAppearanceWrites = false
        settings.retryAppAppearance(this)
        advanceUntilIdle()
        assertFalse(settings.appAppearanceSaveFailed.first())
        assertFalse(image.exists())
        assertNull(ReaderConfigJson.decode(dao.get(SettingsRepository.KEY_GLOBAL)).backgroundImageUri)
    }

    @Test fun aFailedLegacyResetRetainsTheFileAndFlushRetriesTheProjection() = runTest {
        val directory = temporary.newFolder("failed-legacy")
        val image = directory.resolve("old.jpg").apply { writeText("synthetic image") }
        val style = ReadingStyle.DAY_DEFAULT.copy(backgroundImageUri = image.absolutePath)
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao, this) { deleteDroppedBackgroundImages(directory, it) }
        settings.saveAppAppearance(AppAppearance.DEFAULT.copy(day = style))
        settings.saveGlobal(style.applyTo(ReaderConfig.DEFAULT))
        val editor = ReadingConfigEditor(settings, this, StandardTestDispatcher(testScheduler))
        dao.failGlobalWrites = true
        editor.resetReading(setOf(ResetScope.DAY_STYLE))
        advanceUntilIdle()
        assertEquals(image.absolutePath, ReaderConfigJson.decode(dao.get(SettingsRepository.KEY_GLOBAL)).backgroundImageUri)
        assertTrue(image.exists())

        dao.failGlobalWrites = false
        editor.flush()
        advanceUntilIdle()
        assertNull(ReaderConfigJson.decode(dao.get(SettingsRepository.KEY_GLOBAL)).backgroundImageUri)
        assertFalse(image.exists())
    }

    @Test fun pageCancellationDoesNotCancelAnAcceptedResetOrItsCompatibleProjection() = runTest {
        val dao = MemoryPreferences()
        val style = ReadingStyle.DAY_DEFAULT.copy(backgroundImageUri = "/backgrounds/old.jpg")
        dao.put(PreferenceEntity(SettingsRepository.KEY_GLOBAL, ReaderConfigJson.encode(style.applyTo(ReaderConfig.DEFAULT)), 0L))
        dao.put(PreferenceEntity(SettingsRepository.KEY_APP_APPEARANCE, AppAppearanceJson.encode(AppAppearance.DEFAULT.copy(day = style)), 0L))
        val settings = SettingsRepository(dao, this)
        settings.currentAppAppearance()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val editor = ReadingConfigEditor(settings, this, dispatcher)
        val pageScope = CoroutineScope(SupervisorJob() + dispatcher)
        val hold = CompletableDeferred<Unit>()
        dao.holdNextGlobalRead = hold
        try {
            pageScope.launch(start = CoroutineStart.UNDISPATCHED) {
                editor.resetReading(setOf(ResetScope.DAY_STYLE)).join()
                editor.flush()
            }
            pageScope.cancel()
            hold.complete(Unit)
            advanceUntilIdle()
            assertEquals(ReadingStyle.DAY_DEFAULT, AppAppearanceJson.decode(dao.get(SettingsRepository.KEY_APP_APPEARANCE)).day)
            assertEquals(ReadingStyle.DAY_DEFAULT, ReadingStyle.from(ReaderConfigJson.decode(dao.get(SettingsRepository.KEY_GLOBAL))))
        } finally { pageScope.cancel() }
    }

    private class MemoryPreferences : PreferenceDao {
        val values = mutableMapOf<String, PreferenceEntity>()
        private val flows = mutableMapOf<String, MutableStateFlow<String?>>()
        var failAppearanceWrites = false
        var failGlobalWrites = false
        var holdNextGlobalRead: CompletableDeferred<Unit>? = null
        override suspend fun put(preference: PreferenceEntity) {
            if (preference.key == SettingsRepository.KEY_APP_APPEARANCE && failAppearanceWrites) error("appearance unavailable")
            if (preference.key == SettingsRepository.KEY_GLOBAL && failGlobalWrites) error("legacy unavailable")
            values[preference.key] = preference
            flows.getOrPut(preference.key) { MutableStateFlow(null) }.value = preference.value
        }
        override suspend fun get(key: String): String? {
            if (key == SettingsRepository.KEY_GLOBAL) {
                val hold = holdNextGlobalRead
                holdNextGlobalRead = null
                hold?.await()
            }
            return values[key]?.value
        }
        override fun observe(key: String) = flows.getOrPut(key) { MutableStateFlow(values[key]?.value) }
        override suspend fun remove(key: String) { values.remove(key); flows[key]?.value = null }
        override suspend fun removeByPrefix(prefix: String) {
            values.keys.filter { it.startsWith(prefix) }.toList().forEach { remove(it) }
        }
        override suspend fun getAll() = values.values.toList()
    }
}
