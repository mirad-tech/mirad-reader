package com.mirad.reader.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** 每本书的阅读进度（一书一行）。 */
@Entity(
    tableName = "progress",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ProgressEntity(
    @PrimaryKey val bookId: Long,
    val chapterIndex: Int = 0,
    /** 章内字符偏移，用于跨排版/字号精确恢复。 */
    val charOffset: Int = 0,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val percent: Float = 0f,
    val updatedAt: Long = 0L
)
