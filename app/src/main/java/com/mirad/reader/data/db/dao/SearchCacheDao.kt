package com.mirad.reader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mirad.reader.data.db.entity.ChapterSearchEntity

/** 全书搜索的章节正文缓存。只是可重建的缓存，清空不影响书籍和阅读记录。 */
@Dao
interface SearchCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ChapterSearchEntity>)

    @Query("SELECT COUNT(*) FROM chapter_search WHERE bookId = :bookId")
    suspend fun countForBook(bookId: Long): Int

    @Query("DELETE FROM chapter_search WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)

    /** 只清理可重建的搜索缓存，格式升级不触碰业务表。 */
    @Query("DELETE FROM chapter_search")
    suspend fun deleteAll()

    /**
     * 找出包含 [needle] 的章节，按章节顺序返回。
     *
     * instr 按字面逐字比对、区分大小写，不需要转义通配符；忽略大小写时调用方传入已折叠的 [needle]，
     * 与折叠列比对（折叠列为空表示与原文相同）。返回的只是候选章节，命中位置由调用方在原文上计算。
     */
    @Query(
        """
        SELECT bookId, chapterIndex, chapterTitle, content, contentLower FROM chapter_search
        WHERE bookId = :bookId AND (
            (:caseSensitive = 1 AND instr(content, :needle) > 0) OR
            (:caseSensitive = 0 AND instr(COALESCE(contentLower, content), :needle) > 0)
        )
        ORDER BY chapterIndex ASC
        LIMIT :limit
        """
    )
    suspend fun search(
        bookId: Long,
        needle: String,
        caseSensitive: Boolean,
        limit: Int
    ): List<ChapterSearchEntity>

    @Query(
        """
        SELECT chapterIndex, chapterTitle
        FROM chapter_search
        WHERE bookId = :bookId
        ORDER BY chapterIndex ASC
        """
    )
    suspend fun getIndexedChapters(bookId: Long): List<IndexedChapterBrief>

    @Query(
        """
        SELECT content
        FROM chapter_search
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
