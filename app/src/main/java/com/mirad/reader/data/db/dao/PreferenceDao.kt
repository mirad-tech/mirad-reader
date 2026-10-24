package com.mirad.reader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mirad.reader.data.db.entity.PreferenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PreferenceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(preference: PreferenceEntity)

    @Query("SELECT value FROM preferences WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM preferences WHERE key = :key")
    fun observe(key: String): Flow<String?>

    @Query("DELETE FROM preferences WHERE key = :key")
    suspend fun remove(key: String)

    /** 按前缀批量删除：一次性迁移清理历史遗留键。 */
    @Query("DELETE FROM preferences WHERE `key` LIKE :prefix || '%'")
    suspend fun removeByPrefix(prefix: String)

    @Query("SELECT * FROM preferences")
    suspend fun getAll(): List<PreferenceEntity>
}
