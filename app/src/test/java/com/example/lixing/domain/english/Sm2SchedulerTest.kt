package com.example.lixing.domain.english

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class Sm2SchedulerTest {

    private val now = Instant.parse("2026-09-07T10:00:00Z")

    @Test
    fun `first GOOD schedules one day later`() {
        val result = Sm2Scheduler.next(Sm2State(), ReviewGrade.GOOD, now)
        assertEquals(1, result.state.intervalDays)
        assertEquals(1, result.state.reps)
        assertEquals(now.plus(1, ChronoUnit.DAYS), result.dueAt)
    }

    @Test
    fun `second GOOD schedules six days later`() {
        val first = Sm2Scheduler.next(Sm2State(), ReviewGrade.GOOD, now)
        val second = Sm2Scheduler.next(first.state, ReviewGrade.GOOD, now)
        assertEquals(6, second.state.intervalDays)
        assertEquals(2, second.state.reps)
    }

    @Test
    fun `interval grows by ease from third GOOD on`() {
        var state = Sm2State()
        repeat(2) { state = Sm2Scheduler.next(state, ReviewGrade.GOOD, now).state }
        val third = Sm2Scheduler.next(state, ReviewGrade.GOOD, now)
        // ease 2.5 + 0.3 = 2.8；6 * 2.8 ≈ 17 天
        assertTrue("间隔应明显变长，实际 ${third.state.intervalDays}", third.state.intervalDays > 6)
        assertEquals(now.plus(third.state.intervalDays.toLong(), ChronoUnit.DAYS), third.dueAt)
    }

    @Test
    fun `AGAIN resets reps and comes back in ten minutes`() {
        val learned = Sm2State(ease = 2.5, intervalDays = 6, reps = 3, lapses = 0)
        val result = Sm2Scheduler.next(learned, ReviewGrade.AGAIN, now)
        assertEquals(0, result.state.reps)
        assertEquals(1, result.state.lapses)
        assertEquals(0, result.state.intervalDays)
        assertEquals(now.plus(10, ChronoUnit.MINUTES), result.dueAt)
        assertTrue(result.state.ease < 2.5)
    }

    @Test
    fun `HARD grows interval slowly and lowers ease`() {
        val learned = Sm2State(ease = 2.5, intervalDays = 10, reps = 3)
        val result = Sm2Scheduler.next(learned, ReviewGrade.HARD, now)
        // 只增长 20%，且算作学过一次（reps 递增，卡片离开「新卡」池）
        assertEquals(12, result.state.intervalDays)
        assertEquals(4, result.state.reps)
        assertTrue(result.state.ease < 2.5)
    }

    @Test
    fun `HARD on a brand new card still leaves the new pool`() {
        val result = Sm2Scheduler.next(Sm2State(), ReviewGrade.HARD, now)
        assertEquals(1, result.state.intervalDays)
        assertEquals(1, result.state.reps)
    }

    @Test
    fun `ease never drops below the minimum`() {
        var state = Sm2State()
        repeat(20) { state = Sm2Scheduler.next(state, ReviewGrade.AGAIN, now).state }
        assertEquals(Sm2Scheduler.MIN_EASE, state.ease, 0.0001)
    }

    @Test
    fun `previewIntervals grows monotonically`() {
        val intervals = Sm2Scheduler.previewIntervals(5, now)
        assertEquals(listOf(1, 6), intervals.take(2))
        for (i in 1 until intervals.size) {
            assertTrue("间隔必须递增：$intervals", intervals[i] > intervals[i - 1])
        }
    }
}
