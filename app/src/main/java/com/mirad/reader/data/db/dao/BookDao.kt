package com.mirad.reader.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.mirad.reader.data.db.entity.BookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Delete
    suspend fun delete(book: BookEntity)

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: Long): BookEntity?

    @Query("SELECT * FROM books ORDER BY isPinned DESC, lastReadAt DESC, addedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE fileSize = :fileSize AND title = :title LIMIT 1")
    suspend fun findDuplicate(title: String, fileSize: Long): BookEntity?

    @Query("UPDATE books SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE books SET isHidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE books SET groupId = :groupId WHERE id = :id")
    suspend fun setGroup(id: Long, groupId: Long?)

    @Query("UPDATE books SET lastReadAt = :time WHERE id = :id")
    suspend fun touchLastRead(id: Long, time: Long)

    @Query("UPDATE books SET title = :title, author = :author WHERE id = :id")
    suspend fun updateMeta(id: Long, title: String, author: String?)

    @Query("UPDATE books SET finished = :finished WHERE id = :id")
    suspend fun setFinished(id: Long, finished: Boolean)

    @Query("UPDATE books SET totalChapters = :chapters, totalChars = :chars WHERE id = :id")
    suspend fun updateCounts(id: Long, chapters: Int, chars: Long)

    @Query("UPDATE books SET totalChapters = :chapters, totalChars = :chars, chapterRuleId = :ruleId, chapterRegex = :regex, encoding = :encoding WHERE id = :id")
    suspend fun updateIndexInfo(
        id: Long,
        chapters: Int,
        chars: Long,
        ruleId: String?,
        regex: String?,
        encoding: String?
    )
}
