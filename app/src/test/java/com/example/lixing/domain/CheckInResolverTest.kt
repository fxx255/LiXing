package com.example.lixing.domain

import com.example.lixing.domain.model.TaskStatus
import com.example.lixing.domain.settle.CheckInResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class CheckInResolverTest {

    private val today = LocalDate.of(2026, 8, 3)

    @Before
    fun setUp() = TestFixtures.resetIds()

    @Test
    fun `check-in within slot is on time`() {
        val task = TestFixtures.task(date = today)
        val now = LocalDateTime.of(today, java.time.LocalTime.of(7, 0))
        val outcome = CheckInResolver.resolve(task, 1, now, today)
        assertEquals(TaskStatus.DONE, outcome.status)
        assertFalse(outcome.isLate)
        assertFalse(outcome.isMakeup)
        assertTrue(outcome.basePoints > 0)
    }

    @Test
    fun `check-in after slot end is late`() {
        val task = TestFixtures.task(date = today) // 早读 6:40-7:30
        val now = LocalDateTime.of(today, java.time.LocalTime.of(9, 0))
        val outcome = CheckInResolver.resolve(task, 1, now, today)
        assertTrue(outcome.isLate)
        assertEquals(TaskStatus.DONE, outcome.status)
    }

    @Test
    fun `check-in on a later day is makeup`() {
        val task = TestFixtures.task(date = today)
        val tomorrow = today.plusDays(1)
        val now = LocalDateTime.of(tomorrow, java.time.LocalTime.of(8, 0))
        val outcome = CheckInResolver.resolve(task, 1, now, tomorrow, makeupReason = "忘了")
        assertTrue(outcome.isMakeup)
        assertFalse(outcome.isLate) // 补卡不再叠加迟到
        assertEquals("忘了", outcome.task.makeupReason)
    }

    @Test
    fun `partial actual value yields PARTIAL status`() {
        val task = TestFixtures.task(
            targetType = com.example.lixing.domain.model.TargetType.COUNT,
            targetValue = 200,
        )
        val now = LocalDateTime.of(today, java.time.LocalTime.of(7, 0))
        val outcome = CheckInResolver.resolve(task, 100, now, today)
        assertEquals(TaskStatus.PARTIAL, outcome.status)
    }

    @Test
    fun `zero actual value revokes to PENDING`() {
        val task = TestFixtures.task(
            targetType = com.example.lixing.domain.model.TargetType.COUNT,
            targetValue = 200,
        )
        val now = LocalDateTime.of(today, java.time.LocalTime.of(7, 0))
        val outcome = CheckInResolver.resolve(task, 0, now, today)
        assertEquals(TaskStatus.PENDING, outcome.status)
        assertEquals(0, outcome.totalPoints)
    }

    @Test
    fun `revoke resets task to pending`() {
        val task = TestFixtures.task(status = TaskStatus.DONE, actualValue = 1)
        val revoked = CheckInResolver.revoke(task)
        assertEquals(TaskStatus.PENDING, revoked.status)
        assertEquals(0, revoked.actualValue)
        org.junit.Assert.assertNull(revoked.checkedAt)
    }

    @Test
    fun `updating detail keeps first check time and on-time status`() {
        // 首次按时打卡：在时段内
        val onTimeNow = LocalDateTime.of(today, java.time.LocalTime.of(7, 0))
        val first = CheckInResolver.resolve(TestFixtures.task(date = today), 1, onTimeNow, today)
        org.junit.Assert.assertFalse(first.isLate)
        val checkedAt = first.task.checkedAt!!

        // 之后（时段已结束）更新详情，不应变成迟到，也不改首次时间
        val lateNow = LocalDateTime.of(today, java.time.LocalTime.of(20, 0))
        val updated = CheckInResolver.resolve(
            first.task.copy(), 1, lateNow, today,
        )
        org.junit.Assert.assertFalse("更新不应判迟到", updated.isLate)
        assertEquals(checkedAt, updated.task.checkedAt)
    }

    @Test
    fun `makeup allowed only for yesterday within quota`() {
        val studyToday = today

        // 昨天 → 允许
        assertEquals(
            CheckInResolver.MakeupCheck.Allowed,
            CheckInResolver.canMakeUp(today.minusDays(1), studyToday, 0, 2),
        )
        // 前天 → 太旧
        assertEquals(
            CheckInResolver.MakeupCheck.TooOld,
            CheckInResolver.canMakeUp(today.minusDays(2), studyToday, 0, 2),
        )
        // 额度用尽
        assertEquals(
            CheckInResolver.MakeupCheck.QuotaExhausted,
            CheckInResolver.canMakeUp(today.minusDays(1), studyToday, 2, 2),
        )
        // 当天 → 非补卡场景
        assertEquals(
            CheckInResolver.MakeupCheck.NotNeeded,
            CheckInResolver.canMakeUp(today, studyToday, 0, 2),
        )
    }
}
