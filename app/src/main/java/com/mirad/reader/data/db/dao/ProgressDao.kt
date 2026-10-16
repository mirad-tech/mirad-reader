package com.mirad.reader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mirad.reader.data.db.entity.ProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ProgressEntity)

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun get(bookId: Long): ProgressEntity?

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    fun observe(bookId: Long): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress")
    fun observeAll(): Flow<List<ProgressEntity>>

    @Query("DELETE FROM progress WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)
}
