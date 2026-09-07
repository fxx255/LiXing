package com.example.lixing.domain

import com.example.lixing.domain.settle.StreakCalculator
import com.example.lixing.data.local.entity.DayRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StreakCalculatorTest {

    private val today = LocalDate.of(2026, 8, 3)

    @Test
    fun `first achievement starts streak at 1`() {
        val result = StreakCalculator.advance(
            current = TestFixtures.streak(current = 0, lastAchieved = null),
            date = today,
            achieved = true,
            isDayOff = false,
            rescueCardsPerMonth = 1,
        )
        assertEquals(1, result.currentStreak)
        assertEquals(1, result.streak.currentStreak)
        assertFalse(result.broken)
    }

    @Test
    fun `consecutive day increments streak`() {
        val result = StreakCalculator.advance(
            current = TestFixtures.streak(current = 5, lastAchieved = today.minusDays(1)),
            date = today,
            achieved = true,
            isDayOff = false,
            rescueCardsPerMonth = 1,
        )
        assertEquals(6, result.currentStreak)
    }

    @Test
    fun `settling a day already advanced in real time is idempotent`() {
        val result = StreakCalculator.advance(
            current = TestFixtures.streak(current = 6, lastAchieved = today),
            date = today,
            achieved = true,
            isDayOff = false,
            rescueCardsPerMonth = 1,
        )

        assertEquals(6, result.currentStreak)
        assertEquals(today, result.streak.lastAchievedDate)
    }

    @Test
    fun `rebuild current streak ignores unfinished today and follows achieved history`() {
        val records = listOf(
            DayRecordEntity(date = today, isAchieved = false, isSettled = false),
            DayRecordEntity(date = today.minusDays(1), isAchieved = true, isSettled = true),
            DayRecordEntity(date = today.minusDays(2), isDayOff = true, isSettled = true),
            DayRecordEntity(date = today.minusDays(3), isAchieved = true, isSettled = true),
            DayRecordEntity(date = today.minusDays(4), isAchieved = false, isSettled = true),
        )

        val rebuilt = StreakCalculator.rebuildCurrent(records, today)
        assertEquals(2, rebuilt.days)
        assertEquals(today.minusDays(1), rebuilt.lastContinuousDate)
    }

    @Test
    fun `gap day breaks streak back to 1`() {
        // lastAchieved 是前天，今天达成 → 不连续，重置为 1
        val result = StreakCalculator.advance(
            current = TestFixtures.streak(current = 5, lastAchieved = today.minusDays(2)),
            date = today,
            achieved = true,
            isDayOff = false,
            rescueCardsPerMonth = 1,
        )
        assertEquals(1, result.currentStreak)
    }

    @Test
    fun `missed day without rescue breaks streak`() {
        val result = StreakCalculator.advance(
            current = TestFixtures.streak(current = 5, lastAchieved = today.minusDays(1), rescueLeft = 0),
            date = today,
            achieved = false,
            isDayOff = false,
            rescueCardsPerMonth = 0,
            allowRescue = false,
        )
        assertEquals(0, result.currentStreak)
        assertTrue(result.broken)
        assertEquals(5, result.previousStreak)
    }

    @Test
    fun `missed day with rescue card keeps streak`() {
        val result = StreakCalculator.advance(
            current = TestFixtures.streak(current = 5, lastAchieved = today.minusDays(1), rescueLeft = 1),
            date = today,
            achieved = false,
            isDayOff = false,
            rescueCardsPerMonth = 1,
            allowRescue = true,
        )
        assertEquals(5, result.currentStreak)
        assertTrue(result.rescueUsed)
        assertFalse(result.broken)
        assertEquals(0, result.streak.rescueCardsLeft)
    }

    @Test
    fun `day off does not change streak`() {
        val before = TestFixtures.streak(current = 5, lastAchieved = today.minusDays(1))
        val result = StreakCalculator.advance(
            current = before,
            date = today,
            achieved = false,
            isDayOff = true,
            rescueCardsPerMonth = 1,
        )
        assertEquals(5, result.currentStreak)
        assertFalse(result.broken)
        assertFalse(result.rescueUsed)
    }

    @Test
    fun `carry over day off extends lastAchievedDate`() {
        val before = TestFixtures.streak(current = 5, lastAchieved = today.minusDays(1))
        val carried = StreakCalculator.carryOverDayOff(before, today)
        assertEquals(today, carried.lastAchievedDate)
    }

    @Test
    fun `carry over does not resurrect old streak`() {
        val before = TestFixtures.streak(current = 5, lastAchieved = today.minusDays(10))
        val carried = StreakCalculator.carryOverDayOff(before, today)
        assertEquals(today.minusDays(10), carried.lastAchievedDate)
    }

    @Test
    fun `longest streak tracks record`() {
        val result = StreakCalculator.advance(
            current = TestFixtures.streak(current = 9, longest = 9, lastAchieved = today.minusDays(1)),
            date = today,
            achieved = true,
            isDayOff = false,
            rescueCardsPerMonth = 1,
        )
        assertEquals(10, result.streak.longestStreak)
        assertTrue(result.newRecord)
    }

    @Test
    fun `rescue cards refill on new month`() {
        // 上个月 0 张，今天进入新月份 → 应该补满
        val result = StreakCalculator.advance(
            current = TestFixtures.streak(current = 5, lastAchieved = today.minusDays(1), rescueLeft = 0, rescueMonth = 0),
            date = today,
            achieved = false,
            isDayOff = false,
            rescueCardsPerMonth = 2,
            allowRescue = true,
        )
        // 有 2 张可用，应该消耗一张
        assertTrue(result.rescueUsed)
        assertEquals(5, result.currentStreak)
    }

    @Test
    fun `rescue not allowed on non-continuous day`() {
        // lastAchieved 是前天，今天没达成 → 不算“本来连着”，不浪费卡
        val result = StreakCalculator.advance(
            current = TestFixtures.streak(current = 5, lastAchieved = today.minusDays(2), rescueLeft = 1),
            date = today,
            achieved = false,
            isDayOff = false,
            rescueCardsPerMonth = 1,
            allowRescue = true,
        )
        assertTrue(result.broken)
        assertFalse(result.rescueUsed)
        assertEquals(1, result.streak.rescueCardsLeft)
    }

    @Test
    fun `month key and week key are stable`() {
        val date = LocalDate.of(2026, 8, 3)
        assertEquals(202608, StreakCalculator.monthKeyOf(date))
        // 周键应该是正整数且同周内一致
        val wk = StreakCalculator.weekKeyOf(date)
        assertEquals(wk, StreakCalculator.weekKeyOf(date))
    }
}
