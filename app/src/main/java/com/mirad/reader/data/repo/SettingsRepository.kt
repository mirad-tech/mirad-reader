package com.mirad.reader.data.repo

import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.ReaderConfigJson
import com.mirad.reader.data.db.dao.PreferenceDao
import com.mirad.reader.data.db.entity.PreferenceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
class SettingsRepository(private val dao: PreferenceDao) {
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

    companion object {
        const val KEY_GLOBAL = "reader_config_global"
    }
}
