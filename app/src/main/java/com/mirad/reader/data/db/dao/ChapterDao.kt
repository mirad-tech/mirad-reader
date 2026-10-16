package com.mirad.reader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mirad.reader.data.db.entity.ChapterEntity

@Dao
interface ChapterDao {

    @Insert
    suspend fun insertAll(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index` ASC")
    suspend fun getForBook(bookId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index` ASC")
    fun observeForBook(bookId: Long): kotlinx.coroutines.flow.Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND `index` = :index LIMIT 1")
    suspend fun getChapter(bookId: Long, index: Int): ChapterEntity?

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId")
    suspend fun countForBook(bookId: Long): Int
}
