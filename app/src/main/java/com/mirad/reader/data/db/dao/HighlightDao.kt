package com.mirad.reader.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.mirad.reader.data.db.entity.HighlightEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HighlightDao {

    @Insert
    suspend fun insert(highlight: HighlightEntity): Long

    @Update
    suspend fun update(highlight: HighlightEntity)

    @Delete
    suspend fun delete(highlight: HighlightEntity)

    @Query("DELETE FROM highlights WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)

    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY chapterIndex ASC, startOffset ASC")
    fun observeForBook(bookId: Long): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId AND chapterIndex = :chapterIndex ORDER BY startOffset ASC")
    suspend fun getForChapter(bookId: Long, chapterIndex: Int): List<HighlightEntity>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY chapterIndex ASC, startOffset ASC")
    suspend fun getAllForBook(bookId: Long): List<HighlightEntity>

    @Query("SELECT * FROM highlights")
    suspend fun getAll(): List<HighlightEntity>
}
