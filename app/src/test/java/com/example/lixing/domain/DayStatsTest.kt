package com.example.lixing.domain

import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskStatus
import com.example.lixing.domain.settle.DayStatsCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DayStatsTest {

    @Before
    fun setUp() = TestFixtures.resetIds()

    @Test
    fun `empty day has zero stats`() {
        val stats = DayStatsCalculator.calculate(emptyList())
        assertEquals(0, stats.totalTasks)
        assertEquals(0f, stats.completionRate)
        assertFalse(stats.isAchieved(0.6f))
    }

    @Test
    fun `all done counts correctly`() {
        val tasks = listOf(
            TestFixtures.task(status = TaskStatus.DONE, actualValue = 1),
            TestFixtures.task(status = TaskStatus.DONE, actualValue = 1),
            TestFixtures.task(status = TaskStatus.DONE, actualValue = 1),
        )
        val stats = DayStatsCalculator.calculate(tasks)
        assertEquals(3, stats.totalTasks)
        assertEquals(3, stats.doneTasks)
        assertEquals(1f, stats.completionRate, 0.01f)
        assertTrue(stats.isAchieved(0.6f))
        assertTrue(stats.isFullDay)
    }

    @Test
    fun `partial counts proportionally`() {
        val tasks = listOf(
            TestFixtures.task(
                targetType = TargetType.COUNT,
                targetValue = 100,
                status = TaskStatus.PARTIAL,
                actualValue = 50,
            ),
        )
        val stats = DayStatsCalculator.calculate(tasks)
        assertEquals(0.5f, stats.completionRate, 0.01f)
        assertFalse(stats.isFullDay)
    }

    @Test
    fun `skipped tasks excluded from denominator`() {
        val tasks = listOf(
            TestFixtures.task(status = TaskStatus.DONE, actualValue = 1),
            TestFixtures.task(status = TaskStatus.SKIPPED),
        )
        val stats = DayStatsCalculator.calculate(tasks)
        assertEquals(2, stats.totalTasks)
        assertEquals(1, stats.countedTasks)
        assertEquals(1f, stats.completionRate, 0.01f)
    }

    @Test
    fun `achieved respects threshold`() {
        // 5 个任务完成 3 个 = 60%
        val tasks = (0 until 5).map { i ->
            if (i < 3) TestFixtures.task(status = TaskStatus.DONE, actualValue = 1)
            else TestFixtures.task(status = TaskStatus.PENDING)
        }
        val stats = DayStatsCalculator.calculate(tasks)
        assertEquals(0.6f, stats.completionRate, 0.01f)
        assertTrue(stats.isAchieved(0.6f))
        assertFalse(stats.isAchieved(0.7f))
    }

    @Test
    fun `all skipped day is not achieved`() {
        val tasks = listOf(
            TestFixtures.task(status = TaskStatus.SKIPPED),
            TestFixtures.task(status = TaskStatus.SKIPPED),
        )
        val stats = DayStatsCalculator.calculate(tasks)
        assertEquals(0, stats.countedTasks)
        assertFalse(stats.isAchieved(0.1f))
    }

    @Test
    fun `keystone all done detected`() {
        val tasks = listOf(
            TestFixtures.task(isKeystone = true, status = TaskStatus.DONE, actualValue = 1),
            TestFixtures.task(isKeystone = true, status = TaskStatus.DONE, actualValue = 1),
        )
        val stats = DayStatsCalculator.calculate(tasks)
        assertTrue(stats.keystoneAllDone)

        val withMissed = listOf(
            TestFixtures.task(isKeystone = true, status = TaskStatus.DONE, actualValue = 1),
            TestFixtures.task(isKeystone = true, status = TaskStatus.MISSED),
        )
        val stats2 = DayStatsCalculator.calculate(withMissed)
        assertFalse(stats2.keystoneAllDone)
    }

    @Test
    fun `focus minutes aggregated`() {
        val t1 = TestFixtures.task(status = TaskStatus.DONE, actualValue = 1)
        val t2 = TestFixtures.task(status = TaskStatus.DONE, actualValue = 1)
        val tasks = listOf(t1.copy(focusedMinutes = 30), t2.copy(focusedMinutes = 60))
        val stats = DayStatsCalculator.calculate(tasks)
        assertEquals(90, stats.focusMinutes)
    }
}
