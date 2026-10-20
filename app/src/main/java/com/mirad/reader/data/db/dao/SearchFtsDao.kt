package com.mirad.reader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mirad.reader.data.db.entity.ChapterFtsEntity

@Dao
interface SearchFtsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ChapterFtsEntity>)

    @Query("SELECT COUNT(*) FROM chapters_fts WHERE bookId = :bookId")
    suspend fun countForBook(bookId: Long): Int

    @Query("DELETE FROM chapters_fts WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)

    @Query(
        """
        SELECT rowid, bookId, chapterIndex, chapterTitle, content, contentLower FROM chapters_fts 
        WHERE bookId = :bookId AND (
            (:caseSensitive = 1 AND content LIKE '%' || :escapedQuery || '%' ESCAPE '\') OR
            (:caseSensitive = 0 AND contentLower LIKE '%' || :escapedQuery || '%' ESCAPE '\')
        )
        LIMIT :limit
        """
    )
    suspend fun search(
        bookId: Long,
        escapedQuery: String,
        caseSensitive: Boolean,
        limit: Int
    ): List<ChapterFtsEntity>

    @Query(
        """
        SELECT chapterIndex, chapterTitle 
        FROM chapters_fts 
        WHERE bookId = :bookId 
        ORDER BY chapterIndex ASC
        """
    )
    suspend fun getIndexedChapters(bookId: Long): List<IndexedChapterBrief>

    @Query(
        """
        SELECT content 
        FROM chapters_fts 
        WHERE bookId = :bookId AND chapterIndex = :chapterIndex 
        LIMIT 1
        """
    )
    suspend fun getChapterContent(bookId: Long, chapterIndex: Int): String?
}

data class IndexedChapterBrief(
    val chapterIndex: Int,
    val chapterTitle: String
)
