package com.mirad.reader.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.mirad.reader.data.db.entity.HighlightEntity
import com.mirad.reader.data.db.BOOK_CONTENT_GENERATION_PREFIX
import kotlinx.coroutines.flow.Flow

data class DeletedHighlight(val record: HighlightEntity, val generation: String?)

@Dao
interface HighlightDao {

    @Insert
    suspend fun insert(highlight: HighlightEntity): Long

    @Update
    suspend fun update(highlight: HighlightEntity)

    @Delete
    suspend fun delete(highlight: HighlightEntity)

    @Query("SELECT * FROM highlights WHERE id = :id")
    suspend fun getById(id: Long): HighlightEntity?

    @Query("SELECT value FROM preferences WHERE `key` = '" + BOOK_CONTENT_GENERATION_PREFIX + "' || :bookId")
    suspend fun contentGeneration(bookId: Long): String?

    @Transaction
    suspend fun deleteForUndo(id: Long): DeletedHighlight? {
        val record = getById(id) ?: return null
        val generation = contentGeneration(record.bookId)
        delete(record)
        return DeletedHighlight(record, generation)
    }

    /** 比较与插入共用事务，正文发布不能夹在两者之间。返回定位是否因版本变化失效。 */
    @Transaction
    suspend fun restoreForUndo(deleted: DeletedHighlight): Boolean {
        val changed = contentGeneration(deleted.record.bookId) != deleted.generation
        insert(if (changed) deleted.record.copy(chapterIndex = -1) else deleted.record)
        return changed
    }

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
