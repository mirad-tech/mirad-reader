package com.mirad.reader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mirad.reader.data.db.entity.ReadingSessionEntity

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: ReadingSessionEntity): Long

    @Query("SELECT * FROM sessions ORDER BY startedAt ASC")
    suspend fun getAll(): List<ReadingSessionEntity>

    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM sessions WHERE bookId = :bookId")
    suspend fun totalDurationForBook(bookId: Long): Long

    @Query("DELETE FROM sessions WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)
}
