package com.mirad.reader.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 一次阅读会话，用于统计时长与字数。 */
@Entity(
    tableName = "sessions",
    indices = [Index("bookId"), Index("startedAt")]
)
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val bookId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val charsRead: Long = 0L
)
