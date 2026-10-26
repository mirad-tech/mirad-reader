package com.mirad.reader.data.repo

import com.mirad.reader.core.model.AppAppearance
import com.mirad.reader.core.model.AppAppearanceJson
import com.mirad.reader.core.model.AppThemeMode
import com.mirad.reader.core.model.BuiltinBackgrounds
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.ReaderConfigJson
import com.mirad.reader.core.model.ReadingStyle
import com.mirad.reader.data.db.dao.PreferenceDao
import com.mirad.reader.data.db.entity.PreferenceEntity
import com.mirad.reader.ui.common.GlassAppearance
import com.mirad.reader.ui.common.GlassAppearanceJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 阅读配置仓库：全书通用阅读配置。
 * 用户在任何一本书籍或全局设置中所做的修改，均作为统一的通用阅读习惯同步生效，
 * 避免用户在打开新书或切换书籍时需要重新配置。
 */
class SettingsRepository @JvmOverloads constructor(
    private val dao: PreferenceDao,
    private val repositoryScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /**
     * 外观**成功落库**后，从已持久化记录里消失的自定义背景图 URI（内置素材除外）。
     * 只在持久化成功路径触发，回调异常不影响保存；文件清理由注入方负责。
     */
    private val onBackgroundImageDropped: (List<String>) -> Unit = {}
) {
    /** Shared across UI owners: an Activity recreation must not create a second read/modify/write queue. */
    internal val readingConfigEdits = Mutex()
    private val globalWrites = Mutex()
    private val pendingGlobal = MutableStateFlow<ReaderConfig?>(null)
    private var globalSaveJob: Job? = null

    init {
        // 一次性清理历史版本的按书覆盖键（reader_config_book_%）：统一全局配置后这些键不再读写。
        // 用标记键保证只执行一次；失败静默，下次启动重试。
        repositoryScope.launch {
            runCatching {
                if (dao.get(KEY_BOOK_CONFIG_CLEARED) == null) {
                    dao.removeByPrefix(BOOK_CONFIG_KEY_PREFIX)
                    dao.put(PreferenceEntity(KEY_BOOK_CONFIG_CLEARED, "1", System.currentTimeMillis()))
                }
            }
        }
    }

    fun queueGlobalConfig(config: ReaderConfig, scope: CoroutineScope) {
        pendingGlobal.value = config
        globalSaveJob?.cancel()
        globalSaveJob = scope.launch { delay(400); persistGlobal() }
    }

    fun flushGlobalConfig(scope: CoroutineScope) {
        globalSaveJob?.cancel()
        scope.launch { persistGlobal() }
    }

    private suspend fun persistGlobal() = globalWrites.withLock {
        val value = pendingGlobal.value ?: return@withLock
        // 失败不抛（防抖 job 无人接异常会崩进程）：lastPersistedGlobal 不更新意味着
        // "兼容键仍有未确认成功的写入"，背景清理继续推迟，待下次 flush 重试。
        runCatching {
            dao.put(PreferenceEntity(KEY_GLOBAL, ReaderConfigJson.encode(value), System.currentTimeMillis()))
        }.onSuccess {
            lastPersistedGlobal = value
            maybeNotifyBackgroundCleanup()
        }
    }

    val global: Flow<ReaderConfig> = combine(dao.observe(KEY_GLOBAL), pendingGlobal) { saved, pending ->
        pending ?: ReaderConfigJson.decode(saved)
    }

    suspend fun currentGlobal(): ReaderConfig = pendingGlobal.value ?: ReaderConfigJson.decode(dao.get(KEY_GLOBAL))

    suspend fun saveGlobal(config: ReaderConfig) = globalWrites.withLock {
        // 先更新缓存再落库：写入完成前的窗口里 global 流也不能回退到旧库值。
        pendingGlobal.value = config
        dao.put(PreferenceEntity(KEY_GLOBAL, ReaderConfigJson.encode(config), System.currentTimeMillis()))
        lastPersistedGlobal = config
        maybeNotifyBackgroundCleanup()
    }

    suspend fun updateGlobal(transform: (ReaderConfig) -> ReaderConfig) {
        saveGlobal(transform(currentGlobal()))
    }

    private data class AppearanceSnapshot(val generation: Long, val value: AppAppearance)

    private val appearanceWrites = Mutex()
    private val appearanceStateLock = Any()
    private val appearanceState = MutableStateFlow<AppAppearance?>(null)
    private val unloadedAppearanceChanges = mutableListOf<(AppAppearance) -> AppAppearance>()
    private var appearanceGeneration = 0L
    private var appearanceDirty = false
    private var appearanceInitialSaveNeeded = false
    private var appearanceSaveJob: Job? = null
    private val _appAppearanceSaveFailed = MutableStateFlow(false)
    val appAppearanceSaveFailed: Flow<Boolean> = _appAppearanceSaveFailed

    /** 先读取/迁移，再发出第一帧的真实配置，避免默认样式覆盖用户的旧选择。 */
    val appAppearance: Flow<AppAppearance> = flow {
        currentAppAppearance()
        emitAll(appearanceState.filterNotNull())
    }

    suspend fun currentAppAppearance(): AppAppearance {
        appearanceState.value?.let { return it }
        return withContext(NonCancellable) {
            appearanceWrites.withLock {
                loadAppearanceLocked()
                val initial = synchronized(appearanceStateLock) {
                    if (appearanceInitialSaveNeeded) appearanceSnapshotLocked() else null
                }
                if (initial != null) persistAppearanceSnapshotLocked(initial, propagateFailure = false)
                appearanceState.value!!
            }
        }
    }

    /** 替换完整记录；日夜入口使用 queueAppThemeMode，样式编辑使用 queueReadingConfig。 */
    fun queueAppAppearance(appearance: AppAppearance, scope: CoroutineScope? = null) {
        queueAppearanceChange({ appearance.normalized() }, scope)
    }

    fun queueAppThemeMode(mode: AppThemeMode, scope: CoroutineScope? = null) {
        queueAppearanceChange({ it.copy(mode = mode) }, scope)
    }

    /** 只修改显式指定的样式组，不让过期的界面配置覆盖新模式或另一组。 */
    fun queueReadingConfig(config: ReaderConfig, isDark: Boolean, scope: CoroutineScope) {
        val style = ReadingStyle.from(config).normalized(isDark)
        queueAppearanceChange({ it.withReadingStyle(isDark, style) }, scope)
        // 保留旧版本可以读取的字段，同时所有新界面以 AppAppearance 为外观事实源。
        queueGlobalConfig(style.applyTo(config), appearanceScope(scope))
    }

    fun flushReadingConfig(scope: CoroutineScope? = null) {
        flushAppAppearance(scope)
        flushGlobalConfig(appearanceScope(scope))
    }

    fun flushAppAppearance(scope: CoroutineScope? = null) {
        synchronized(appearanceStateLock) {
            appearanceSaveJob?.cancel()
            appearanceSaveJob = null
            val expectedGeneration = appearanceGeneration
            appearanceScope(scope).launch {
                withContext(NonCancellable) { persistAppAppearance(expectedGeneration) }
            }
        }
    }

    fun retryAppAppearance(scope: CoroutineScope? = null) = flushAppAppearance(scope)

    suspend fun saveAppAppearance(appearance: AppAppearance) {
        val expectedGeneration = synchronized(appearanceStateLock) {
            appearanceSaveJob?.cancel()
            appearanceSaveJob = null
            changeAppearanceLocked { appearance.normalized() }
        }
        withContext(NonCancellable) { persistAppAppearance(expectedGeneration, propagateFailure = true) }
    }

    private fun queueAppearanceChange(
        transform: (AppAppearance) -> AppAppearance,
        scope: CoroutineScope?
    ) {
        synchronized(appearanceStateLock) {
            val expectedGeneration = changeAppearanceLocked(transform)
            appearanceSaveJob?.cancel()
            appearanceSaveJob = appearanceScope(scope).launch {
                delay(400)
                // 取消的是合并等待，不中断已经进入数据库的写入；新代次随后按序保存。
                withContext(NonCancellable) { persistAppAppearance(expectedGeneration) }
            }
        }
    }

    private fun changeAppearanceLocked(transform: (AppAppearance) -> AppAppearance): Long {
        appearanceGeneration++
        val current = appearanceState.value
        if (current == null) {
            unloadedAppearanceChanges += transform
        } else {
            appearanceState.value = transform(current).normalized()
        }
        appearanceDirty = true
        return appearanceGeneration
    }

    /** 同一仓库拥有这一记录；所有入口通过上面的增量操作共享最新内存状态。 */
    private suspend fun loadAppearanceLocked() {
        if (appearanceState.value != null) return
        val raw = dao.get(KEY_APP_APPEARANCE)
        val initial = if (raw == null) {
            // 迁移只读已保存的旧配置。队列里的新修改随后按顺序合并，不能先污染迁移来源。
            AppAppearance.fromLegacy(ReaderConfigJson.decode(dao.get(KEY_GLOBAL)))
        } else {
            AppAppearanceJson.decode(raw)
        }
        synchronized(appearanceStateLock) {
            appearanceState.value = unloadedAppearanceChanges.fold(initial) { value, change -> change(value).normalized() }
            unloadedAppearanceChanges.clear()
            appearanceInitialSaveNeeded = raw == null
            appearanceDirty = appearanceDirty || appearanceInitialSaveNeeded
        }
        // 加载值即当前持久化事实，作为后续差集比较的基准
        lastPersistedAppearance = initial
    }

    private fun appearanceSnapshotLocked(): AppearanceSnapshot? =
        if (appearanceDirty) appearanceState.value?.let { AppearanceSnapshot(appearanceGeneration, it) } else null

    private suspend fun persistAppAppearance(expectedGeneration: Long, propagateFailure: Boolean = false) =
        appearanceWrites.withLock {
            if (synchronized(appearanceStateLock) { expectedGeneration != appearanceGeneration }) return@withLock
            loadAppearanceLocked()
            val snapshot = synchronized(appearanceStateLock) {
                if (expectedGeneration == appearanceGeneration) appearanceSnapshotLocked() else null
            } ?: return@withLock
            persistAppearanceSnapshotLocked(snapshot, propagateFailure)
        }

    private suspend fun persistAppearanceSnapshotLocked(snapshot: AppearanceSnapshot, propagateFailure: Boolean) {
        try {
            val previous = lastPersistedAppearance
            dao.put(PreferenceEntity(KEY_APP_APPEARANCE, AppAppearanceJson.encode(snapshot.value), System.currentTimeMillis()))
            synchronized(appearanceStateLock) {
                appearanceInitialSaveNeeded = false
                if (appearanceGeneration == snapshot.generation) {
                    appearanceDirty = false
                    _appAppearanceSaveFailed.value = false
                }
            }
            lastPersistedAppearance = snapshot.value
            // 依据"前一次持久化值 → 本次持久化值"的差集登记清理候选：
            // 只有成功写入数据库的记录才有资格宣布旧图无引用，
            // 内存配置更新（onApplied/防抖排队）绝不触发清理。
            // 候选在此暂存而非直接删除——还要等旧兼容键 reader_config_global 也确认成功，
            // 否则旧版 APK 回退读到引用旧图的配置时文件已被删。
            val dropped = droppedBackgroundUris(previous, snapshot.value)
            if (dropped.isNotEmpty()) {
                synchronized(cleanupLock) { pendingBackgroundCleanup += dropped }
            }
            maybeNotifyBackgroundCleanup()
        } catch (error: Exception) {
            synchronized(appearanceStateLock) {
                if (appearanceGeneration == snapshot.generation) _appAppearanceSaveFailed.value = true
            }
            if (propagateFailure) throw error
        }
    }

    /** 已持久化记录不再引用、且非内置素材的自定义背景 URI；两组共享或新旧相同的不会出现。 */
    private fun droppedBackgroundUris(previous: AppAppearance?, current: AppAppearance): List<String> {
        if (previous == null) return emptyList()
        val before = listOfNotNull(previous.day.backgroundImageUri, previous.night.backgroundImageUri)
            .filter { it.isNotBlank() }
        if (before.isEmpty()) return emptyList()
        val after = setOfNotNull(current.day.backgroundImageUri, current.night.backgroundImageUri)
        return before.filter { it !in after && BuiltinBackgrounds.fromUri(it) == null }.distinct()
    }

    /** 最近一次成功落库的外观；只在 appearanceWrites 临界区内读写。 */
    private var lastPersistedAppearance: AppAppearance? = null

    /**
     * 背景清理的双键协调：新外观键与旧兼容键（reader_config_global）都确认成功落库后，
     * 才把登记的候选交给清理回调。兼容键写入阻塞或失败时候选保留不丢，重试成功后补发。
     */
    private val cleanupLock = Any()
    private val pendingBackgroundCleanup = mutableListOf<String>()

    /** 最近一次成功落库的兼容配置；与当前排队值一致视为"兼容键已确认成功"。 */
    @Volatile private var lastPersistedGlobal: ReaderConfig? = null

    private fun maybeNotifyBackgroundCleanup() {
        val batch = synchronized(cleanupLock) {
            // 兼容键仍有未确认成功的写入（阻塞/失败/排队中）：推迟清理
            if (pendingGlobal.value !== lastPersistedGlobal) return
            if (pendingBackgroundCleanup.isEmpty()) return
            val uris = pendingBackgroundCleanup.toList()
            pendingBackgroundCleanup.clear()
            uris
        }
        if (batch.isNotEmpty()) runCatching { onBackgroundImageDropped(batch) }
    }

    private fun appearanceScope(scope: CoroutineScope?): CoroutineScope = CoroutineScope(
        (scope?.coroutineContext ?: repositoryScope.coroutineContext).minusKey(Job) + SupervisorJob()
    )

    private val glassWrites = Mutex()
    private val pendingGlass = MutableStateFlow<GlassAppearance?>(null)
    private val _glassSaveFailed = MutableStateFlow(false)
    val glassSaveFailed: Flow<Boolean> = _glassSaveFailed
    private var glassSaveJob: Job? = null
    private val glassWriteGeneration = java.util.concurrent.atomic.AtomicLong(0L)

    fun queueGlassAppearance(appearance: GlassAppearance, scope: CoroutineScope? = null) {
        val sanitized = appearance.normalized()
        val gen = glassWriteGeneration.incrementAndGet()
        pendingGlass.value = sanitized
        glassSaveJob?.cancel()
        val context = (scope?.coroutineContext ?: repositoryScope.coroutineContext).minusKey(Job) + SupervisorJob()
        glassSaveJob = CoroutineScope(context).launch {
            delay(400)
            persistGlass(expectedGen = gen)
        }
    }

    fun flushGlassAppearance(scope: CoroutineScope? = null) {
        glassSaveJob?.cancel()
        val gen = glassWriteGeneration.get()
        val context = (scope?.coroutineContext ?: repositoryScope.coroutineContext).minusKey(Job) + SupervisorJob()
        CoroutineScope(context).launch { persistGlass(expectedGen = gen) }
    }

    fun retryGlassAppearance(scope: CoroutineScope? = null) {
        flushGlassAppearance(scope)
    }

    private suspend fun persistGlass(expectedGen: Long = glassWriteGeneration.get()) = glassWrites.withLock {
        if (expectedGen < glassWriteGeneration.get()) return@withLock
        val value = pendingGlass.value ?: return@withLock
        runCatching {
            // 新记录是事实源；旧键只是给旧版 APK 的单调投影，写入失败会在下次 flush 自愈。
            dao.put(PreferenceEntity(KEY_GLASS_APPEARANCE, GlassAppearanceJson.encode(value), System.currentTimeMillis()))
            dao.put(PreferenceEntity(KEY_GLASS_TRANSPARENCY, value.transparencyPercent.toString(), System.currentTimeMillis()))
        }.onSuccess {
            // 与 persistGlobal 同理：pendingGlass 保留为最新值缓存，避免失效通知到达前闪回旧库值。
            if (glassWriteGeneration.get() == expectedGen) {
                _glassSaveFailed.value = false
            }
        }.onFailure {
            if (glassWriteGeneration.get() == expectedGen) {
                _glassSaveFailed.value = true
            }
        }
    }

    val glassAppearance: Flow<GlassAppearance> = flow {
        // 首次订阅前完成一次性迁移，避免界面先看到默认值再跳变。
        emit(currentGlassAppearance())
        emitAll(combine(dao.observe(KEY_GLASS_APPEARANCE), pendingGlass) { saved, pending ->
            pending ?: GlassAppearanceJson.decode(saved)
        })
    }

    suspend fun currentGlassAppearance(): GlassAppearance {
        pendingGlass.value?.let { return it }
        return withContext(NonCancellable) {
            glassWrites.withLock {
                pendingGlass.value?.let { return@withLock it }
                val stored = dao.get(KEY_GLASS_APPEARANCE)
                val migrated = if (stored != null) {
                    GlassAppearanceJson.decode(stored)
                } else {
                    // 只有新记录不存在时才迁移旧百分比；损坏的新记录一律回退默认，不重复迁移。
                    val legacy = GlassAppearance.fromLegacyPercent(dao.get(KEY_GLASS_TRANSPARENCY))
                    runCatching {
                        dao.put(PreferenceEntity(KEY_GLASS_APPEARANCE, GlassAppearanceJson.encode(legacy), System.currentTimeMillis()))
                        dao.put(PreferenceEntity(KEY_GLASS_TRANSPARENCY, legacy.transparencyPercent.toString(), System.currentTimeMillis()))
                    }
                    legacy
                }
                // DAO 挂起期间 UI 可以排入新设置；只发布到尚未被新值占用的缓存。
                pendingGlass.compareAndSet(null, migrated)
                pendingGlass.value ?: migrated
            }
        }
    }

    suspend fun saveGlassAppearance(appearance: GlassAppearance) = glassWrites.withLock {
        val sanitized = appearance.normalized()
        val gen = glassWriteGeneration.incrementAndGet()
        pendingGlass.value = sanitized
        runCatching {
            dao.put(PreferenceEntity(KEY_GLASS_APPEARANCE, GlassAppearanceJson.encode(sanitized), System.currentTimeMillis()))
            dao.put(PreferenceEntity(KEY_GLASS_TRANSPARENCY, sanitized.transparencyPercent.toString(), System.currentTimeMillis()))
        }.onSuccess {
            if (glassWriteGeneration.get() == gen) {
                _glassSaveFailed.value = false
            }
        }.onFailure { err ->
            if (glassWriteGeneration.get() == gen) {
                _glassSaveFailed.value = true
            }
            throw err
        }
    }

    fun resetGlassAppearance(scope: CoroutineScope? = null) {
        queueGlassAppearance(GlassAppearance.DEFAULT, scope)
        flushGlassAppearance(scope)
    }

    // 书架布局记忆（网格/列表）：与 glass 相同的"专用键 + 防抖写 + pending 优先"三件套。
    private val shelfLayoutWrites = Mutex()
    private val pendingShelfLayout = MutableStateFlow<Boolean?>(null)
    private var shelfLayoutSaveJob: Job? = null

    fun queueShelfLayout(grid: Boolean, scope: CoroutineScope? = null) {
        pendingShelfLayout.value = grid
        shelfLayoutSaveJob?.cancel()
        val context = (scope?.coroutineContext ?: repositoryScope.coroutineContext).minusKey(Job) + SupervisorJob()
        shelfLayoutSaveJob = CoroutineScope(context).launch {
            delay(400)
            shelfLayoutWrites.withLock {
                val value = pendingShelfLayout.value ?: return@withLock
                runCatching {
                    dao.put(PreferenceEntity(KEY_SHELF_LAYOUT, if (value) "GRID" else "LIST", System.currentTimeMillis()))
                }
            }
        }
    }

    /** 书架是否为网格布局；无记录或值异常时默认网格（与历史行为一致）。 */
    val shelfLayoutGrid: Flow<Boolean> =
        combine(dao.observe(KEY_SHELF_LAYOUT), pendingShelfLayout) { saved, pending ->
            pending ?: (saved != "LIST")
        }

    companion object {
        const val KEY_GLOBAL = "reader_config_global"
        const val KEY_APP_APPEARANCE = "app_appearance_v1"
        /** 新记录：磨砂开关 + 清晰度。 */
        const val KEY_GLASS_APPEARANCE = "ui_glass_appearance_v1"
        /** 旧版兼容投影（0 = 关闭，1..100 = 开启）；只写不读，除迁移外。 */
        const val KEY_GLASS_TRANSPARENCY = "ui_glass_transparency_percent"
        const val KEY_SHELF_LAYOUT = "shelf_layout"

        /** 历史按书覆盖键的前缀与一次性清理标记。 */
        const val BOOK_CONFIG_KEY_PREFIX = "reader_config_book_"
        const val KEY_BOOK_CONFIG_CLEARED = "migration_book_config_cleared"
    }
}
