package com.mirad.reader.data.repo

import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.ReaderConfigJson
import com.mirad.reader.data.db.dao.PreferenceDao
import com.mirad.reader.data.db.entity.PreferenceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * 阅读配置仓库：全局默认配置 + 每本书覆盖配置（覆盖值以 JSON 存放）。
 */
class SettingsRepository(private val dao: PreferenceDao) {

    val global: Flow<ReaderConfig> = dao.observe(KEY_GLOBAL).map { ReaderConfigJson.decode(it) }

    suspend fun currentGlobal(): ReaderConfig = ReaderConfigJson.decode(dao.get(KEY_GLOBAL))

    suspend fun saveGlobal(config: ReaderConfig) {
        dao.put(PreferenceEntity(KEY_GLOBAL, ReaderConfigJson.encode(config), System.currentTimeMillis()))
    }

    suspend fun updateGlobal(transform: (ReaderConfig) -> ReaderConfig) {
        saveGlobal(transform(currentGlobal()))
    }

    /** 本书是否已有独立配置。 */
    suspend fun hasBookConfig(bookId: Long): Boolean = dao.get(bookKey(bookId)) != null

    fun observeEffective(bookId: Long): Flow<ReaderConfig> = combine(
        dao.observe(bookKey(bookId)),
        dao.observe(KEY_GLOBAL)
    ) { override, global ->
        if (override == null) ReaderConfigJson.decode(global) else ReaderConfigJson.decode(override)
    }

    /** 实际生效配置：有本书覆盖则用覆盖值，否则用全局值。 */
    suspend fun effective(bookId: Long): ReaderConfig {
        val override = dao.get(bookKey(bookId))
        return if (override == null) currentGlobal() else ReaderConfigJson.decode(override)
    }

    suspend fun saveBookConfig(bookId: Long, config: ReaderConfig) {
        dao.put(PreferenceEntity(bookKey(bookId), ReaderConfigJson.encode(config), System.currentTimeMillis()))
    }

    suspend fun updateBookConfig(bookId: Long, transform: (ReaderConfig) -> ReaderConfig) {
        saveBookConfig(bookId, transform(effective(bookId)))
    }

    suspend fun clearBookConfig(bookId: Long) = dao.remove(bookKey(bookId))

    private fun bookKey(bookId: Long) = "reader_config_book_$bookId"

    companion object {
        const val KEY_GLOBAL = "reader_config_global"
    }
}
