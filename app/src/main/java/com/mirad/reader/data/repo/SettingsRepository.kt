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
 * 阅读配置仓库：全局默认配置 + 每本书覆盖配置（覆盖值以 JSON 存放）。
 */
class SettingsRepository(private val dao: PreferenceDao) {
    private val bookWrites = Mutex()
    private val pendingBooks = ConcurrentHashMap<Long, ReaderConfig>()
    private val saveJobs = ConcurrentHashMap<Long, Job>()
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

    /** UI previews are immediate; pending values survive leaving and reopening the reader. */
    fun queueBookConfig(bookId: Long, config: ReaderConfig, scope: CoroutineScope) {
        pendingBooks[bookId] = config
        saveJobs.remove(bookId)?.cancel()
        saveJobs[bookId] = scope.launch {
            delay(400)
            persistPending(bookId)
        }
    }

    fun flushBookConfig(bookId: Long, scope: CoroutineScope) {
        saveJobs.remove(bookId)?.cancel()
        scope.launch { persistPending(bookId) }
    }

    private suspend fun persistPending(bookId: Long) = bookWrites.withLock {
        val value = pendingBooks[bookId] ?: return@withLock
        dao.put(PreferenceEntity(bookKey(bookId), ReaderConfigJson.encode(value), System.currentTimeMillis()))
        pendingBooks.remove(bookId, value)
    }

    val global: Flow<ReaderConfig> = combine(dao.observe(KEY_GLOBAL), pendingGlobal) { saved, pending ->
        pending ?: ReaderConfigJson.decode(saved)
    }

    suspend fun currentGlobal(): ReaderConfig = pendingGlobal.value ?: ReaderConfigJson.decode(dao.get(KEY_GLOBAL))

    suspend fun saveGlobal(config: ReaderConfig) = globalWrites.withLock {
        dao.put(PreferenceEntity(KEY_GLOBAL, ReaderConfigJson.encode(config), System.currentTimeMillis()))
    }

    suspend fun updateGlobal(transform: (ReaderConfig) -> ReaderConfig) {
        saveGlobal(transform(currentGlobal()))
    }

    /** 本书是否已有独立配置。 */
    suspend fun hasBookConfig(bookId: Long): Boolean = pendingBooks.containsKey(bookId) || dao.get(bookKey(bookId)) != null

    fun observeEffective(bookId: Long): Flow<ReaderConfig> = combine(
        dao.observe(bookKey(bookId)),
        dao.observe(KEY_GLOBAL)
    ) { override, global ->
        if (override == null) ReaderConfigJson.decode(global) else ReaderConfigJson.decode(override)
    }

    /** 实际生效配置：有本书覆盖则用覆盖值，否则用全局值。 */
    suspend fun effective(bookId: Long): ReaderConfig {
        pendingBooks[bookId]?.let { return it }
        val override = dao.get(bookKey(bookId))
        return if (override == null) currentGlobal() else ReaderConfigJson.decode(override)
    }

    suspend fun saveBookConfig(bookId: Long, config: ReaderConfig) = bookWrites.withLock {
        dao.put(PreferenceEntity(bookKey(bookId), ReaderConfigJson.encode(config), System.currentTimeMillis()))
    }

    suspend fun updateBookConfig(bookId: Long, transform: (ReaderConfig) -> ReaderConfig) {
        saveBookConfig(bookId, transform(effective(bookId)))
    }

    suspend fun clearBookConfig(bookId: Long) {
        saveJobs.remove(bookId)?.cancel()
        pendingBooks.remove(bookId)
        bookWrites.withLock { dao.remove(bookKey(bookId)) }
    }

    private fun bookKey(bookId: Long) = "reader_config_book_$bookId"

    companion object {
        const val KEY_GLOBAL = "reader_config_global"
    }
}
