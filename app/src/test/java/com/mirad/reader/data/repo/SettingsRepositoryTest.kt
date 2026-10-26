package com.mirad.reader.data.repo

import com.mirad.reader.core.model.AppAppearance
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.ReaderConfigJson
import com.mirad.reader.core.model.ReadingStyle
import com.mirad.reader.core.model.ThemeKey
import com.mirad.reader.data.db.dao.PreferenceDao
import com.mirad.reader.data.db.entity.PreferenceEntity
import com.mirad.reader.ui.common.GlassAppearance
import com.mirad.reader.ui.common.GlassAppearanceJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {
    private class MemoryPreferences : PreferenceDao {
        val entries = mutableMapOf<String, PreferenceEntity>()
        val flows = mutableMapOf<String, MutableStateFlow<String?>>()
        var writes = 0
        var failNextPut = false
        /** 按键注入：put 挂起直到解除（模拟写入受阻）。 */
        var blockGlobalPuts: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var blockAppearancePuts: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        /** 按键注入：下一次对旧兼容键的 put 抛异常（模拟写入失败）。 */
        var failGlobalPuts = false
        override suspend fun put(preference: PreferenceEntity) {
            // 启动迁移标记与业务写入失败注入无关：直接落库，也不计入业务写入计数。
            if (preference.key == SettingsRepository.KEY_BOOK_CONFIG_CLEARED) {
                entries[preference.key] = preference
                flows.getOrPut(preference.key) { MutableStateFlow(null) }.value = preference.value
                return
            }
            when (preference.key) {
                SettingsRepository.KEY_GLOBAL -> {
                    blockGlobalPuts?.await()
                    if (failGlobalPuts) {
                        failGlobalPuts = false
                        throw IllegalStateException("Legacy global write failed")
                    }
                }
                SettingsRepository.KEY_APP_APPEARANCE -> blockAppearancePuts?.await()
            }
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
        override suspend fun removeByPrefix(prefix: String) {
            entries.keys.filter { it.startsWith(prefix) }.toList().forEach { key ->
                entries.remove(key)
                flows[key]?.value = null
            }
        }
        override suspend fun getAll() = entries.values.toList()
    }

    @Test fun reopeningBeforeDebounceReadsLatestSize() = runTest {
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao)
        settings.queueGlobalConfig(ReaderConfig.DEFAULT.copy(fontSizeSp = 32f), this)
        assertEquals(32f, settings.currentGlobal().fontSizeSp)
        assertEquals(0, dao.writes)
        advanceUntilIdle()
        assertEquals(32f, ReaderConfigJson.decode(dao.get(SettingsRepository.KEY_GLOBAL)).fontSizeSp)
    }

    @Test fun rapidChangesOnlyPersistLastValueAndExitFlushes() = runTest {
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao)
        for (size in 12..40) settings.queueGlobalConfig(ReaderConfig.DEFAULT.copy(fontSizeSp = size.toFloat()), this)
        settings.flushGlobalConfig(this)
        advanceUntilIdle()
        assertEquals(1, dao.writes)
        assertEquals(40f, ReaderConfigJson.decode(dao.get(SettingsRepository.KEY_GLOBAL)).fontSizeSp)
    }

    @Test fun legacyBookConfigKeysAreClearedOnceAtStartup() = runTest {
        val dao = MemoryPreferences()
        dao.put(PreferenceEntity("reader_config_book_1", "{}", 0L))
        dao.put(PreferenceEntity("reader_config_book_2", "{}", 0L))
        dao.put(PreferenceEntity(SettingsRepository.KEY_GLOBAL, ReaderConfigJson.encode(ReaderConfig.DEFAULT), 0L))
        val settings = SettingsRepository(dao, this)
        advanceUntilIdle()
        assertNull(dao.get("reader_config_book_1"))
        assertNull(dao.get("reader_config_book_2"))
        assertEquals("1", dao.get(SettingsRepository.KEY_BOOK_CONFIG_CLEARED))
        // 标记存在后再次构造不重复清理，也不动其它键。
        SettingsRepository(dao, this)
        advanceUntilIdle()
        assertNotNull(dao.get(SettingsRepository.KEY_GLOBAL))
    }

    @Test fun resettingCannotBeOverwrittenByPendingSliderSave() = runTest {
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao)
        settings.saveGlobal(ReaderConfig.DEFAULT.copy(fontSizeSp = 22f))
        settings.queueGlobalConfig(ReaderConfig.DEFAULT.copy(fontSizeSp = 40f), this)
        settings.saveGlobal(ReaderConfig.DEFAULT.copy(fontSizeSp = 22f))
        settings.flushGlobalConfig(this)
        advanceUntilIdle()
        assertEquals(22f, settings.currentGlobal().fontSizeSp)
    }

    @Test fun settingsAreUniversalAcrossBooksAndNewBooksInheritLatestConfig() = runTest {
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao)
        // 在书 1 中修改字号为 28sp，主题为绿色
        val customConfig = ReaderConfig.DEFAULT.copy(fontSizeSp = 28f, theme = ThemeKey.GREEN)
        settings.queueGlobalConfig(customConfig, this)
        // 书 2 应当立即通用继承此配置，无需重复设置
        assertEquals(28f, settings.currentGlobal().fontSizeSp)
        assertEquals(ThemeKey.GREEN, settings.currentGlobal().theme)
        // 任何新打开的书（书 3）也直接生效
        assertEquals(28f, settings.currentGlobal().fontSizeSp)
        assertEquals(ThemeKey.GREEN, settings.currentGlobal().theme)
        settings.flushGlobalConfig(this)
        advanceUntilIdle()
        // 落盘后重新读取，新书依然保持最新通用阅读偏好
        assertEquals(28f, settings.currentGlobal().fontSizeSp)
        assertEquals(ThemeKey.GREEN, settings.currentGlobal().theme)
    }

    @Test fun globalSettingSurvivesImmediatePageExit() = runTest {
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao)
        settings.queueGlobalConfig(ReaderConfig.DEFAULT.copy(fontSizeSp = 29f), this)
        assertEquals(29f, settings.currentGlobal().fontSizeSp)
        assertEquals(29f, settings.currentGlobal().fontSizeSp)
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
        settings.queueGlassAppearance(GlassAppearance(clarityPercent = 78), uiScope)
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
        settings.queueGlassAppearance(GlassAppearance(clarityPercent = 35), this)
        assertEquals(35, settings.glassAppearance.first().transparencyPercent)
        // Entry 2 (e.g. Reader Sheet) immediately sees 35% and updates to 85% before debounce finishes
        advanceTimeBy(100)
        settings.queueGlassAppearance(GlassAppearance(clarityPercent = 85), this)
        assertEquals(85, settings.glassAppearance.first().transparencyPercent)
        advanceUntilIdle()
        // 一次逻辑落库现在写两行（新记录 + 旧版兼容投影），两次快速修改仍只落库一次。
        assertEquals(2, dao.writes)
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
        settings.queueGlassAppearance(GlassAppearance(clarityPercent = 42), this)
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
            override suspend fun removeByPrefix(prefix: String) {
                entries.keys.filter { it.startsWith(prefix) }.toList().forEach { key ->
                    entries.remove(key)
                    flows[key]?.value = null
                }
            }
            override suspend fun getAll() = entries.values.toList()
        }
        val repoScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val settings = SettingsRepository(dao, repoScope)

        // 1. Trigger first write (40%) and let it enter dao.put()
        settings.queueGlassAppearance(GlassAppearance(clarityPercent = 40), this)
        settings.flushGlassAppearance(this)
        testScheduler.runCurrent()
        firstWriteEntered.await()

        // 2. While first write is suspended in dao.put(), user selects 85% and commits
        settings.queueGlassAppearance(GlassAppearance(clarityPercent = 85), this)
        settings.flushGlassAppearance(this)
        assertEquals(85, settings.currentGlassAppearance().transparencyPercent)

        // 3. Release first (stale) failing write; second write (85%) then runs and succeeds
        releaseFirstWrite.complete(Unit)
        advanceUntilIdle()

        assertFalse("Stale write failure must not leave glassSaveFailed=true after newer write succeeds", settings.glassSaveFailed.first())
        assertEquals(85, settings.currentGlassAppearance().transparencyPercent)
        assertEquals("85", dao.get(SettingsRepository.KEY_GLASS_TRANSPARENCY))
    }

    /**
     * 模拟真实 Room：put 立即写库，但 observe 的失效通知要等 [invalidate] 手动触发才重新发射。
     * 在"落库完成、失效通知未到"的窗口里，global / glassAppearance 流必须继续发射最新值，
     * 否则受控滑块会闪回旧值（SettingsRepository 的 pending 即最新值缓存）。
     */
    private class AsyncInvalidationPreferences : PreferenceDao {
        val entries = mutableMapOf<String, PreferenceEntity>()
        val flows = mutableMapOf<String, MutableStateFlow<String?>>()
        override suspend fun put(preference: PreferenceEntity) {
            entries[preference.key] = preference
        }
        override suspend fun get(key: String) = entries[key]?.value
        override fun observe(key: String): Flow<String?> =
            flows.getOrPut(key) { MutableStateFlow(entries[key]?.value) }
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
        override suspend fun getAll() = entries.values.toList()
        fun invalidate(key: String) {
            flows.getOrPut(key) { MutableStateFlow(null) }.value = entries[key]?.value
        }
    }

    @Test fun globalFlowDoesNotFallBackToStaleSavedValueBeforeInvalidation() = runTest {
        val dao = AsyncInvalidationPreferences()
        dao.put(PreferenceEntity(SettingsRepository.KEY_GLOBAL, ReaderConfigJson.encode(ReaderConfig.DEFAULT), 0L))
        val settings = SettingsRepository(dao)
        settings.queueGlobalConfig(ReaderConfig.DEFAULT.copy(fontSizeSp = 30f), this)
        advanceTimeBy(500) // 越过 400ms 防抖：已落库，失效通知尚未到达
        assertEquals("落库后、失效通知前不能回退到旧库值", 30f, settings.global.first().fontSizeSp)
        dao.invalidate(SettingsRepository.KEY_GLOBAL)
        assertEquals("失效通知到达后仍是最新值", 30f, settings.global.first().fontSizeSp)
    }

    /** 旧键只在新记录不存在时迁移一次；新记录成为事实源后，旧键变化不得反向覆盖。 */
    @Test fun glassAppearanceMigratesLegacyPercentOnce() = runTest {
        val dao = MemoryPreferences()
        dao.put(PreferenceEntity(SettingsRepository.KEY_GLASS_TRANSPARENCY, "45", 0L))
        val settings = SettingsRepository(dao, this)
        val migrated = settings.currentGlassAppearance()
        assertTrue("旧值 > 0 应迁移为开启", migrated.enabled)
        assertEquals(45, migrated.clarityPercent)

        settings.saveGlassAppearance(GlassAppearance(enabled = true, clarityPercent = 20))
        // 旧键被外部改成 99（旧版 APK 回写等场景）
        dao.put(PreferenceEntity(SettingsRepository.KEY_GLASS_TRANSPARENCY, "99", 0L))
        val reopened = SettingsRepository(dao, this)
        assertEquals("新记录是事实源，旧投影不能覆盖它", 20, reopened.currentGlassAppearance().clarityPercent)
    }

    @Test fun glassInitialReadPreservesChangesQueuedWhileReadingNewRecord() = runTest {
        assertGlassLoadPreservesQueuedChange("new-read")
    }

    @Test fun glassMigrationPreservesChangesQueuedWhileReadingLegacyRecord() = runTest {
        assertGlassLoadPreservesQueuedChange("legacy-read")
    }

    @Test fun glassMigrationPreservesChangesQueuedWhileWritingMigratedRecord() = runTest {
        assertGlassLoadPreservesQueuedChange("migration-write")
    }

    /** 确定性暂停真实 repository 的 DAO 边界，再模拟 UI 更新及立即离页 flush。 */
    private suspend fun TestScope.assertGlassLoadPreservesQueuedChange(blockAt: String) {
        val storage = MemoryPreferences()
        storage.put(PreferenceEntity(SettingsRepository.KEY_GLASS_TRANSPARENCY, "45", 0L))
        if (blockAt == "new-read") {
            storage.put(PreferenceEntity(SettingsRepository.KEY_GLASS_APPEARANCE,
                GlassAppearanceJson.encode(GlassAppearance(clarityPercent = 45)), 0L))
        }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val dao = object : PreferenceDao by storage {
            override suspend fun get(key: String): String? {
                val saved = storage.get(key)
                if ((blockAt == "new-read" && key == SettingsRepository.KEY_GLASS_APPEARANCE) ||
                    (blockAt == "legacy-read" && key == SettingsRepository.KEY_GLASS_TRANSPARENCY)) {
                    entered.complete(Unit)
                    release.await()
                }
                return saved
            }

            override suspend fun put(preference: PreferenceEntity) {
                if (blockAt == "migration-write" && !entered.isCompleted &&
                    preference.key == SettingsRepository.KEY_GLASS_APPEARANCE) {
                    entered.complete(Unit)
                    release.await()
                }
                storage.put(preference)
            }
        }
        val settings = SettingsRepository(dao, this)
        val initialEmission = async { settings.glassAppearance.first() }
        testScheduler.runCurrent()
        assertTrue("首次加载必须已进入指定 DAO 挂起点", entered.isCompleted)

        val latest = GlassAppearance(enabled = false, clarityPercent = 83)
        settings.queueGlassAppearance(latest, this)
        settings.flushGlassAppearance(this)
        testScheduler.runCurrent()
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals("首次流发射不能用旧值覆盖新选择", latest, initialEmission.await())
        assertEquals(latest, settings.currentGlassAppearance())
        assertEquals(latest, GlassAppearanceJson.decode(storage.get(SettingsRepository.KEY_GLASS_APPEARANCE)))
        assertEquals("0", storage.get(SettingsRepository.KEY_GLASS_TRANSPARENCY))
        assertEquals(latest, SettingsRepository(storage, this).currentGlassAppearance())
    }

    /** 新记录损坏回退默认，且不得再次用旧值迁移。 */
    @Test fun corruptGlassRecordFallsBackToDefaultWithoutReusingLegacy() = runTest {
        val dao = MemoryPreferences()
        dao.put(PreferenceEntity(SettingsRepository.KEY_GLASS_APPEARANCE, "{broken", 0L))
        dao.put(PreferenceEntity(SettingsRepository.KEY_GLASS_TRANSPARENCY, "88", 0L))
        val settings = SettingsRepository(dao, this)
        assertEquals(GlassAppearance.DEFAULT, settings.currentGlassAppearance())
    }

    /** 关闭玻璃：旧键写 0，重新打开仓库后仍是关闭且清晰度保留。 */
    @Test fun disabledGlassWritesZeroProjectionAndKeepsClarity() = runTest {
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao, this)
        settings.saveGlassAppearance(GlassAppearance(enabled = false, clarityPercent = 73))

        assertEquals("0", dao.entries[SettingsRepository.KEY_GLASS_TRANSPARENCY]?.value)
        val reopened = SettingsRepository(dao, this).currentGlassAppearance()
        assertFalse(reopened.enabled)
        assertEquals(73, reopened.clarityPercent)
    }

    @Test fun glassAppearanceFlowDoesNotFallBackToStaleSavedValueBeforeInvalidation() = runTest {
        val dao = AsyncInvalidationPreferences()
        val settings = SettingsRepository(dao)
        settings.queueGlassAppearance(GlassAppearance(clarityPercent = 78), this)
        advanceTimeBy(500)
        assertEquals("落库后、失效通知前不能回退到旧库值", 78, settings.glassAppearance.first().transparencyPercent)
        dao.invalidate(SettingsRepository.KEY_GLASS_TRANSPARENCY)
        assertEquals("失效通知到达后仍是最新值", 78, settings.glassAppearance.first().transparencyPercent)
        assertEquals("78", dao.get(SettingsRepository.KEY_GLASS_TRANSPARENCY))
    }

    // ---------------------------------------------------------------- 背景图清理时序（审查 P2）
    // 旧图只在**持久化成功**后按已落库记录的引用差集清理；内存更新与保存失败都不触发。

    private fun appearanceWithDayBackground(uri: String?): AppAppearance =
        AppAppearance.DEFAULT.copy(day = ReadingStyle.DAY_DEFAULT.copy(backgroundImageUri = uri))

    @Test fun droppedBackgroundOnlyAfterSuccessfulPersistNotOnMemoryUpdate() = runTest {
        val dao = MemoryPreferences()
        val dropped = mutableListOf<List<String>>()
        val settings = SettingsRepository(dao, this) { uris -> dropped += uris }
        settings.saveAppAppearance(appearanceWithDayBackground("/bg/a.jpg"))
        assertTrue("首次持久化没有可失去引用的旧图", dropped.isEmpty())
        settings.queueAppAppearance(appearanceWithDayBackground("/bg/b.jpg"), this)
        advanceTimeBy(100)
        assertTrue("防抖未到期、尚未落库：不允许清理", dropped.isEmpty())
        advanceUntilIdle()
        assertEquals("落库成功后清理失去引用的旧图", listOf(listOf("/bg/a.jpg")), dropped)
    }

    @Test fun backgroundSharedByBothGroupsIsNeverDropped() = runTest {
        val dao = MemoryPreferences()
        val dropped = mutableListOf<List<String>>()
        val settings = SettingsRepository(dao, this) { uris -> dropped += uris }
        val shared = AppAppearance.DEFAULT.copy(
            day = ReadingStyle.DAY_DEFAULT.copy(backgroundImageUri = "/bg/a.jpg"),
            night = ReadingStyle.NIGHT_DEFAULT.copy(backgroundImageUri = "/bg/a.jpg")
        )
        settings.saveAppAppearance(shared)
        // 只改日间组，夜间组仍引用 /bg/a.jpg
        settings.saveAppAppearance(shared.copy(day = ReadingStyle.DAY_DEFAULT.copy(backgroundImageUri = "/bg/b.jpg")))
        assertTrue("另一组仍在使用的图不能清理", dropped.isEmpty())
    }

    @Test fun failedPersistKeepsOldBackgroundUntilRetrySucceeds() = runTest {
        val dao = MemoryPreferences()
        val dropped = mutableListOf<List<String>>()
        val settings = SettingsRepository(dao, this) { uris -> dropped += uris }
        settings.saveAppAppearance(appearanceWithDayBackground("/bg/a.jpg"))
        dao.failNextPut = true
        settings.queueAppAppearance(appearanceWithDayBackground("/bg/b.jpg"), this)
        advanceUntilIdle()
        assertTrue("保存失败：旧图必须保留", dropped.isEmpty())
        settings.retryAppAppearance(this)
        advanceUntilIdle()
        assertEquals("重试成功后才清理", listOf(listOf("/bg/a.jpg")), dropped)
    }

    @Test fun builtinBackgroundsAreNeverDropped() = runTest {
        val dao = MemoryPreferences()
        val dropped = mutableListOf<List<String>>()
        val settings = SettingsRepository(dao, this) { uris -> dropped += uris }
        settings.saveAppAppearance(appearanceWithDayBackground("builtin:paper"))
        settings.saveAppAppearance(appearanceWithDayBackground("/bg/b.jpg"))
        assertTrue("内置素材不是文件，不进清理列表", dropped.isEmpty())
    }

    @Test fun rapidReplacementsDropEachUnreferencedUriOnce() = runTest {
        val dao = MemoryPreferences()
        val dropped = mutableListOf<List<String>>()
        val settings = SettingsRepository(dao, this) { uris -> dropped += uris }
        settings.saveAppAppearance(appearanceWithDayBackground("/bg/a.jpg"))
        settings.queueAppAppearance(appearanceWithDayBackground("/bg/b.jpg"), this)
        advanceUntilIdle()
        settings.queueAppAppearance(appearanceWithDayBackground("/bg/c.jpg"), this)
        advanceUntilIdle()
        assertEquals("A→B→C 各自差集：a、b 依次清理", listOf(listOf("/bg/a.jpg"), listOf("/bg/b.jpg")), dropped)
    }

    @Test fun shelfLayoutRoundTripsAndDefaultsToGrid() = runTest {
        val dao = MemoryPreferences()
        val settings = SettingsRepository(dao)
        assertTrue("无记录时默认网格", settings.shelfLayoutGrid.first())
        settings.queueShelfLayout(false, this)
        assertFalse("切换后立即反映（pending 优先）", settings.shelfLayoutGrid.first())
        advanceUntilIdle()
        assertEquals("LIST", dao.get(SettingsRepository.KEY_SHELF_LAYOUT))
        settings.queueShelfLayout(true, this)
        advanceUntilIdle()
        assertTrue(settings.shelfLayoutGrid.first())
        assertEquals("GRID", dao.get(SettingsRepository.KEY_SHELF_LAYOUT))
    }

    // ------------------------------------------- 双键协调（复核验收 5/6/7）
    // 旧图清理必须等 app_appearance_v1 与 reader_config_global 都确认成功落库；
    // 走 queueReadingConfig 的真实仓库双写路径，不模拟单键与删除回调。

    private suspend fun SettingsRepository.seedDayBackground(dao: MemoryPreferences, uri: String) {
        saveAppAppearance(appearanceWithDayBackground(uri))
        saveGlobal(ReaderConfig.DEFAULT.copy(backgroundType = "IMAGE", backgroundImageUri = uri))
    }

    /** 复核验收 5：新键先成功、旧兼容键写入受阻 → 不清理；解除后补发。 */
    @Test fun cleanupWaitsWhileLegacyGlobalPutIsBlocked() = runTest {
        val dao = MemoryPreferences()
        val dropped = mutableListOf<List<String>>()
        val settings = SettingsRepository(dao, this) { uris -> dropped += uris }
        settings.seedDayBackground(dao, "/bg/a.jpg")
        val block = kotlinx.coroutines.CompletableDeferred<Unit>()
        dao.blockGlobalPuts = block
        settings.queueReadingConfig(
            ReaderConfig.DEFAULT.copy(backgroundType = "IMAGE", backgroundImageUri = "/bg/b.jpg"),
            isDark = false, scope = this
        )
        advanceUntilIdle()
        assertTrue("兼容键受阻：旧图必须保留", dropped.isEmpty())
        assertEquals("新外观键此时已成功", "/bg/b.jpg",
            com.mirad.reader.core.model.AppAppearanceJson.decode(dao.get(SettingsRepository.KEY_APP_APPEARANCE)).day.backgroundImageUri)
        block.complete(Unit)
        advanceUntilIdle()
        assertEquals("兼容键成功后补发清理", listOf(listOf("/bg/a.jpg")), dropped)
        // 复核验收 6：删除前依据持久化记录核对——兼容键不再引用旧图
        val savedGlobal = ReaderConfigJson.decode(dao.get(SettingsRepository.KEY_GLOBAL))
        assertEquals("/bg/b.jpg", savedGlobal.backgroundImageUri)
    }

    /** 复核验收 5：新键先成功、旧兼容键写入失败 → 不清理；重试成功后才清理。 */
    @Test fun cleanupDefersWhileLegacyGlobalPutFailsUntilRetry() = runTest {
        val dao = MemoryPreferences()
        val dropped = mutableListOf<List<String>>()
        val settings = SettingsRepository(dao, this) { uris -> dropped += uris }
        settings.seedDayBackground(dao, "/bg/a.jpg")
        dao.failGlobalPuts = true
        settings.queueReadingConfig(
            ReaderConfig.DEFAULT.copy(backgroundType = "IMAGE", backgroundImageUri = "/bg/b.jpg"),
            isDark = false, scope = this
        )
        advanceUntilIdle()
        assertTrue("兼容键失败：旧图必须保留", dropped.isEmpty())
        settings.flushGlobalConfig(this)
        advanceUntilIdle()
        assertEquals("兼容键重试成功后清理", listOf(listOf("/bg/a.jpg")), dropped)
    }

    /** 复核验收 7：相反写入顺序——兼容键先成功、新键后成功，成功即发不重复。 */
    @Test fun cleanupFiresWhenAppearancePersistArrivesAfterGlobal() = runTest {
        val dao = MemoryPreferences()
        val dropped = mutableListOf<List<String>>()
        val settings = SettingsRepository(dao, this) { uris -> dropped += uris }
        settings.seedDayBackground(dao, "/bg/a.jpg")
        val block = kotlinx.coroutines.CompletableDeferred<Unit>()
        dao.blockAppearancePuts = block
        settings.queueReadingConfig(
            ReaderConfig.DEFAULT.copy(backgroundType = "IMAGE", backgroundImageUri = "/bg/b.jpg"),
            isDark = false, scope = this
        )
        advanceUntilIdle()
        assertTrue("新键受阻：无差集自然不清理", dropped.isEmpty())
        block.complete(Unit)
        advanceUntilIdle()
        assertEquals("新键成功且兼容键已就绪：立即清理", listOf(listOf("/bg/a.jpg")), dropped)
    }

    /** 复核验收 6：推迟期间继续替换，候选合并不丢失。 */
    @Test fun deferredCleanupCandidatesAreMergedAcrossRapidReplacements() = runTest {
        val dao = MemoryPreferences()
        val dropped = mutableListOf<List<String>>()
        val settings = SettingsRepository(dao, this) { uris -> dropped += uris }
        settings.seedDayBackground(dao, "/bg/a.jpg")
        val block = kotlinx.coroutines.CompletableDeferred<Unit>()
        dao.blockGlobalPuts = block
        settings.queueReadingConfig(
            ReaderConfig.DEFAULT.copy(backgroundType = "IMAGE", backgroundImageUri = "/bg/b.jpg"),
            isDark = false, scope = this
        )
        advanceUntilIdle()
        settings.queueReadingConfig(
            ReaderConfig.DEFAULT.copy(backgroundType = "IMAGE", backgroundImageUri = "/bg/c.jpg"),
            isDark = false, scope = this
        )
        advanceUntilIdle()
        assertTrue(dropped.isEmpty())
        block.complete(Unit)
        advanceUntilIdle()
        assertEquals("两批候选合并一次发出，不因推迟丢失", listOf(listOf("/bg/a.jpg", "/bg/b.jpg")), dropped)
    }

    /** 复核验收 7：新键写入失败 → 不清理；重试成功后按最终持久化状态清理。 */
    @Test fun cleanupSkippedWhenAppearancePersistFails() = runTest {
        val dao = MemoryPreferences()
        val dropped = mutableListOf<List<String>>()
        val settings = SettingsRepository(dao, this) { uris -> dropped += uris }
        settings.seedDayBackground(dao, "/bg/a.jpg")
        dao.failNextPut = true // 新键下一次写入失败
        settings.queueReadingConfig(
            ReaderConfig.DEFAULT.copy(backgroundType = "IMAGE", backgroundImageUri = "/bg/b.jpg"),
            isDark = false, scope = this
        )
        advanceUntilIdle()
        assertTrue("新键失败：不产生清理候选", dropped.isEmpty())
        settings.flushReadingConfig(this)
        advanceUntilIdle()
        assertEquals("重试成功后按最终持久化状态清理", listOf(listOf("/bg/a.jpg")), dropped)
    }
}
