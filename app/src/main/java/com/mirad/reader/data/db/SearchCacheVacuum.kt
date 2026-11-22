package com.mirad.reader.data.db

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mirad.reader.data.db.entity.PreferenceEntity
import kotlinx.coroutines.CancellationException

/**
 * 3→4 迁移删除了旧的 FTS4 搜索缓存表，但 SQLite 不会自动缩小数据库文件。
 *
 * 启动后空闲时检查一次：空闲页超过 [MIN_RECLAIM_BYTES] 才执行 VACUUM，否则直接记为完成。
 * 成功或无需回收后写入标记，不再检查；失败不写标记，下次启动再试。
 */
internal object SearchCacheVacuum {
    internal const val KEY_DONE = "search_cache_vacuum_v4"
    internal const val START_DELAY_MS = 15_000L
    private const val MIN_RECLAIM_BYTES = 4L * 1024 * 1024

    suspend fun runOnce(database: AppDatabase) {
        val preferences = database.preferenceDao()
        try {
            if (preferences.get(KEY_DONE) != null) return
            val db = database.openHelper.writableDatabase
            val freeBytes = pragmaLong(db, "PRAGMA freelist_count") * pragmaLong(db, "PRAGMA page_size")
            if (freeBytes >= MIN_RECLAIM_BYTES) db.execSQL("VACUUM")
            preferences.put(PreferenceEntity(KEY_DONE, "1", System.currentTimeMillis()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("SearchCacheVacuum", "回收搜索缓存空间失败，下次启动重试", e)
        }
    }

    private fun pragmaLong(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
}
