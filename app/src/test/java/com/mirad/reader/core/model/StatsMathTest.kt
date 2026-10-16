package com.mirad.reader.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class StatsMathTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(date: LocalDate, hour: Int): Long =
        ZonedDateTime.of(date.atTime(hour, 0), zone).toInstant().toEpochMilli()

    private val today = LocalDate.of(2026, 3, 10)

    @Test
    fun emptySessionsProduceZeroStats() {
        val stats = StatsMath.summarize(emptyList(), at(today, 12), zone)
        assertEquals(0L, stats.totalMs)
        assertEquals(0, stats.streakDays)
        assertTrue(stats.daily.isEmpty())
    }

    @Test
    fun aggregatesTodayWeekMonthAndTotal() {
        val sessions = listOf(
            SessionRecord(1, at(today, 9), at(today, 10), 3_600_000, 1200),
            SessionRecord(1, at(today.minusDays(2), 9), at(today.minusDays(2), 9), 1_800_000, 600),
            SessionRecord(2, at(today.minusDays(10), 9), at(today.minusDays(10), 9), 900_000, 300),
            SessionRecord(2, at(today.minusDays(60), 9), at(today.minusDays(60), 9), 600_000, 100)
        )
        val stats = StatsMath.summarize(sessions, at(today, 20), zone)

        assertEquals(6_900_000L, stats.totalMs)
        assertEquals(3_600_000L, stats.todayMs)
        // 本周 = 今天 + 前 6 天
        assertEquals(5_400_000L, stats.weekMs)
        // 本月 = 近 30 天
        assertEquals(6_300_000L, stats.monthMs)
        assertEquals(2200L, stats.totalChars)
        assertEquals(1200L, stats.todayChars)
        assertEquals(4, stats.sessionCount)
    }

    @Test
    fun streakCountsConsecutiveDaysEndingToday() {
        val sessions = (0..2).map { back ->
            val day = today.minusDays(back.toLong())
            SessionRecord(1, at(day, 9), at(day, 9), 600_000, 100)
        } + SessionRecord(1, at(today.minusDays(5), 9), at(today.minusDays(5), 9), 600_000, 100)
        val stats = StatsMath.summarize(sessions, at(today, 20), zone)
        assertEquals(3, stats.streakDays)
    }

    @Test
    fun streakStillCountsWhenTodayHasNoReadingYet() {
        val sessions = (1..3).map { back ->
            val day = today.minusDays(back.toLong())
            SessionRecord(1, at(day, 9), at(day, 9), 600_000, 100)
        }
        val stats = StatsMath.summarize(sessions, at(today, 8), zone)
        assertEquals(3, stats.streakDays)
    }

    @Test
    fun dailyBucketsCoverThirtyDaysAndSumToRecentTotals() {
        val sessions = listOf(
            SessionRecord(1, at(today, 9), at(today, 9), 600_000, 100),
            SessionRecord(1, at(today.minusDays(1), 9), at(today.minusDays(1), 9), 1_200_000, 200)
        )
        val stats = StatsMath.summarize(sessions, at(today, 20), zone)
        assertEquals(30, stats.daily.size)
        assertEquals(600_000L, stats.daily.last().durationMs)
        assertEquals(1_200_000L, stats.daily[stats.daily.size - 2].durationMs)
    }

    @Test
    fun formatsDurationsInChinese() {
        assertEquals("不足 1 分钟", StatsMath.formatDuration(30_000))
        assertEquals("5 分钟", StatsMath.formatDuration(5 * 60_000L))
        assertEquals("1 小时 23 分", StatsMath.formatDuration(83 * 60_000L))
        assertEquals("2 小时", StatsMath.formatDuration(120 * 60_000L))
    }
}
