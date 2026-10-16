package com.mirad.reader.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 一次阅读会话（与存储层解耦，便于纯函数统计与测试）。 */
data class SessionRecord(
    val bookId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val charsRead: Long
)

data class DailyStat(
    val date: LocalDate,
    val durationMs: Long,
    val chars: Long
)

data class ReadingStats(
    val totalMs: Long = 0L,
    val todayMs: Long = 0L,
    val weekMs: Long = 0L,
    val monthMs: Long = 0L,
    val totalChars: Long = 0L,
    val todayChars: Long = 0L,
    val streakDays: Int = 0,
    val sessionCount: Int = 0,
    val daily: List<DailyStat> = emptyList()
) {
    val todayMinutes: Int get() = (todayMs / 60000L).toInt()
    val totalHours: Float get() = totalMs / 3600000f
}

/** 阅读统计聚合。纯函数。 */
object StatsMath {

    fun summarize(
        sessions: List<SessionRecord>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): ReadingStats {
        if (sessions.isEmpty()) return ReadingStats()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val weekStart = today.minusDays(6)
        val monthStart = today.minusDays(29)

        var totalMs = 0L
        var todayMs = 0L
        var weekMs = 0L
        var monthMs = 0L
        var totalChars = 0L
        var todayChars = 0L
        val byDay = HashMap<LocalDate, Long>()
        val charsByDay = HashMap<LocalDate, Long>()

        for (session in sessions) {
            val duration = session.durationMs.coerceAtLeast(0L)
            totalMs += duration
            totalChars += session.charsRead.coerceAtLeast(0L)
            // 会话跨天时按开始时间归属，统计口径简单可解释。
            val day = Instant.ofEpochMilli(session.startedAt).atZone(zone).toLocalDate()
            byDay[day] = (byDay[day] ?: 0L) + duration
            charsByDay[day] = (charsByDay[day] ?: 0L) + session.charsRead.coerceAtLeast(0L)
            if (day == today) {
                todayMs += duration
                todayChars += session.charsRead.coerceAtLeast(0L)
            }
            if (!day.isBefore(weekStart) && !day.isAfter(today)) weekMs += duration
            if (!day.isBefore(monthStart) && !day.isAfter(today)) monthMs += duration
        }

        // 连续阅读天数：从今天（或昨天）往前连续有记录的天数
        var streak = 0
        var cursor = if ((byDay[today] ?: 0L) > 0L) today else today.minusDays(1)
        while ((byDay[cursor] ?: 0L) > 0L) {
            streak++
            cursor = cursor.minusDays(1)
        }

        val daily = (0 until 30).map { back ->
            val day = today.minusDays((29 - back).toLong())
            DailyStat(day, byDay[day] ?: 0L, charsByDay[day] ?: 0L)
        }

        return ReadingStats(
            totalMs = totalMs,
            todayMs = todayMs,
            weekMs = weekMs,
            monthMs = monthMs,
            totalChars = totalChars,
            todayChars = todayChars,
            streakDays = streak,
            sessionCount = sessions.size,
            daily = daily
        )
    }

    /** 把毫秒格式化为「1 小时 23 分」之类的中文文本。 */
    fun formatDuration(ms: Long): String {
        val totalMinutes = ms / 60000L
        if (totalMinutes < 1) return "不足 1 分钟"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "$hours 小时 $minutes 分"
            hours > 0 -> "$hours 小时"
            else -> "$minutes 分钟"
        }
    }
}
