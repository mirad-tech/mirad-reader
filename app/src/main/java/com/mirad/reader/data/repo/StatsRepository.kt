package com.mirad.reader.data.repo

import com.mirad.reader.core.model.ReadingStats
import com.mirad.reader.core.model.SessionRecord
import com.mirad.reader.core.model.StatsMath
import com.mirad.reader.data.db.dao.SessionDao
import com.mirad.reader.data.db.entity.ReadingSessionEntity

/** 阅读统计仓库。 */
class StatsRepository(private val sessionDao: SessionDao) {

    suspend fun record(bookId: Long, startedAt: Long, endedAt: Long, charsRead: Long) {
        if (endedAt <= startedAt) return
        val duration = endedAt - startedAt
        // 过滤掉误触产生的极短会话（小于 5 秒不计数）。
        if (duration < MIN_SESSION_MS) return
        sessionDao.insert(
            ReadingSessionEntity(
                bookId = bookId,
                startedAt = startedAt,
                endedAt = endedAt,
                durationMs = duration.coerceAtMost(MAX_SESSION_MS),
                charsRead = charsRead.coerceAtLeast(0L)
            )
        )
    }

    suspend fun stats(): ReadingStats = StatsMath.summarize(sessions(), System.currentTimeMillis())

    suspend fun totalDurationForBook(bookId: Long): Long = sessionDao.totalDurationForBook(bookId)

    suspend fun sessions(): List<SessionRecord> = sessionDao.getAll().map {
        SessionRecord(it.bookId, it.startedAt, it.endedAt, it.durationMs, it.charsRead)
    }

    companion object {
        const val MIN_SESSION_MS = 5_000L

        /** 单次会话上限 6 小时，避免忘记关闭导致统计失真。 */
        const val MAX_SESSION_MS = 6 * 60 * 60 * 1000L
    }
}
