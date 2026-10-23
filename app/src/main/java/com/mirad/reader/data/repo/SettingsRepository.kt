package com.mirad.reader.data.repo

import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.ReaderConfigJson
import com.mirad.reader.data.db.dao.PreferenceDao
import com.mirad.reader.data.db.entity.PreferenceEntity
import com.mirad.reader.ui.common.GlassAppearance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private val repositoryScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val bookWrites = Mutex()
    private val globalWrites = Mutex()
    private val pendingGlobal = MutableStateFlow<ReaderConfig?>(null)
    private var globalSaveJob: Job? = null

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
        dao.put(PreferenceEntity(KEY_GLOBAL, ReaderConfigJson.encode(value), System.currentTimeMillis()))
        pendingGlobal.compareAndSet(value, null)
    }

    /** 阅读设置全书通用：书籍内调整设置直接同步为全局通用设置。 */
    fun queueBookConfig(bookId: Long, config: ReaderConfig, scope: CoroutineScope) {
        queueGlobalConfig(config, scope)
    }

    fun flushBookConfig(bookId: Long, scope: CoroutineScope) {
        flushGlobalConfig(scope)
    }

    val global: Flow<ReaderConfig> = combine(dao.observe(KEY_GLOBAL), pendingGlobal) { saved, pending ->
        pending ?: ReaderConfigJson.decode(saved)
    }

    suspend fun currentGlobal(): ReaderConfig = pendingGlobal.value ?: ReaderConfigJson.decode(dao.get(KEY_GLOBAL))

    suspend fun saveGlobal(config: ReaderConfig) = globalWrites.withLock {
        pendingGlobal.value = null
        dao.put(PreferenceEntity(KEY_GLOBAL, ReaderConfigJson.encode(config), System.currentTimeMillis()))
    }

    suspend fun updateGlobal(transform: (ReaderConfig) -> ReaderConfig) {
        saveGlobal(transform(currentGlobal()))
    }

    /** 阅读设置已全书通用，不再维护独立单书覆盖。 */
    suspend fun hasBookConfig(bookId: Long): Boolean = false

    fun observeEffective(bookId: Long): Flow<ReaderConfig> = global

    /** 实际生效配置：统一采用全局通用阅读配置。 */
    suspend fun effective(bookId: Long): ReaderConfig = currentGlobal()

    suspend fun saveBookConfig(bookId: Long, config: ReaderConfig) = saveGlobal(config)

    suspend fun updateBookConfig(bookId: Long, transform: (ReaderConfig) -> ReaderConfig) {
        saveGlobal(transform(currentGlobal()))
    }

    suspend fun clearBookConfig(bookId: Long) {
        bookWrites.withLock { dao.remove(bookKey(bookId)) }
    }

    private fun bookKey(bookId: Long) = "reader_config_book_$bookId"

    private val glassWrites = Mutex()
    private val pendingGlass = MutableStateFlow<GlassAppearance?>(null)
    private val _glassSaveFailed = MutableStateFlow(false)
    val glassSaveFailed: Flow<Boolean> = _glassSaveFailed
    private var glassSaveJob: Job? = null
    private val glassWriteGeneration = java.util.concurrent.atomic.AtomicLong(0L)

    fun queueGlassAppearance(appearance: GlassAppearance, scope: CoroutineScope? = null) {
        val sanitized = GlassAppearance(appearance.transparencyPercent.coerceIn(0, 100))
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
            dao.put(PreferenceEntity(KEY_GLASS_TRANSPARENCY, value.transparencyPercent.toString(), System.currentTimeMillis()))
        }.onSuccess {
            if (glassWriteGeneration.get() == expectedGen) {
                _glassSaveFailed.value = false
                pendingGlass.compareAndSet(value, null)
            }
        }.onFailure {
            if (glassWriteGeneration.get() == expectedGen) {
                _glassSaveFailed.value = true
            }
        }
    }

    val glassAppearance: Flow<GlassAppearance> = combine(dao.observe(KEY_GLASS_TRANSPARENCY), pendingGlass) { saved, pending ->
        pending ?: GlassAppearance.parse(saved)
    }

    suspend fun currentGlassAppearance(): GlassAppearance =
        pendingGlass.value ?: GlassAppearance.parse(dao.get(KEY_GLASS_TRANSPARENCY))

    suspend fun saveGlassAppearance(appearance: GlassAppearance) = glassWrites.withLock {
        val sanitized = GlassAppearance(appearance.transparencyPercent.coerceIn(0, 100))
        val gen = glassWriteGeneration.incrementAndGet()
        pendingGlass.value = sanitized
        runCatching {
            dao.put(PreferenceEntity(KEY_GLASS_TRANSPARENCY, sanitized.transparencyPercent.toString(), System.currentTimeMillis()))
        }.onSuccess {
            if (glassWriteGeneration.get() == gen) {
                _glassSaveFailed.value = false
                pendingGlass.compareAndSet(sanitized, null)
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

    companion object {
        const val KEY_GLOBAL = "reader_config_global"
        const val KEY_GLASS_TRANSPARENCY = "ui_glass_transparency_percent"
    }
}
