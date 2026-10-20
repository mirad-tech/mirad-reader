package com.mirad.reader.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * 章节正文 FTS4 倒排全文检索虚拟表实体。
 *
 * 用于将全书搜索性能从逐章解压扫描（秒级）提升至毫秒级数据库检索。
 */
@Fts4
@Entity(tableName = "chapters_fts")
data class ChapterFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowid: Long? = null,
    val bookId: Long,
    val chapterIndex: Int,
    val chapterTitle: String,
    val content: String,
    val contentLower: String = ""
)
