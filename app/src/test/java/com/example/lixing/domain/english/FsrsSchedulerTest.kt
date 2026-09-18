package com.example.lixing.domain.english

import org.junit.Assert.*
import org.junit.Test

class FsrsSchedulerTest {
    private val now = 1_800_000_000_000L
    @Test fun `default weights initialize new cards and learning steps`() {
        val good = FsrsScheduler.next(MemoryState(), ReviewGrade.GOOD, now)
        assertEquals(2.31, good.stability, .00001)
        assertEquals(2L * FsrsScheduler.DAY, good.dueAt!! - now)
        assertEquals("REVIEW", good.phase)
        assertEquals(60_000L, FsrsScheduler.next(MemoryState(), ReviewGrade.AGAIN, now).dueAt!! - now)
        assertEquals(600_000L, FsrsScheduler.next(MemoryState(), ReviewGrade.HARD, now).dueAt!! - now)
    }
    @Test fun `curve has ninety percent recall at stability and decays over time`() {
        val state = MemoryState(stability = 10.0, difficulty = 5.0, lastAt = now)
        assertEquals(1.0, FsrsScheduler.retrievability(state, now), .000001)
        assertEquals(.9, FsrsScheduler.retrievability(state, now + 10 * FsrsScheduler.DAY), .000001)
        assertTrue(FsrsScheduler.retrievability(state, now + 20 * FsrsScheduler.DAY) < .9)
    }
    @Test fun `long term successful recall lengthens interval more than hard`() {
        val state = MemoryState(10.0, 5.0, "REVIEW", now - 10 * FsrsScheduler.DAY, now, now - 50 * FsrsScheduler.DAY, 5)
        val good = FsrsScheduler.next(state, ReviewGrade.GOOD, now)
        val hard = FsrsScheduler.next(state, ReviewGrade.HARD, now)
        val again = FsrsScheduler.next(state, ReviewGrade.AGAIN, now)
        assertTrue(good.stability > hard.stability)
        assertTrue(hard.stability > state.stability)
        assertTrue(again.stability < state.stability)
        assertEquals("RELEARNING", again.phase)
        assertEquals(1, again.lapses)
    }
    @Test fun `repeated failures and very overdue cards stay finite and bounded`() {
        var state = MemoryState()
        repeat(1000) { state = FsrsScheduler.next(state, ReviewGrade.AGAIN, now + it * 60_000L) }
        for (grade in ReviewGrade.entries) {
            val next = FsrsScheduler.next(state, grade, now + 5000 * FsrsScheduler.DAY)
            assertTrue(next.stability.isFinite() && next.stability > 0)
            assertTrue(next.difficulty in 1.0..10.0)
            assertTrue(next.dueAt!! > next.lastAt!!)
        }
    }
}
