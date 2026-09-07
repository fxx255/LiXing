package com.example.lixing.domain

import com.example.lixing.domain.materialize.DailyTaskReconciler
import com.example.lixing.domain.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalTime

class DailyTaskReconcilerTest {

    @Before
    fun resetIds() = TestFixtures.resetIds()

    @Test
    fun `plan snapshot changes update today without losing progress`() {
        val checkedAt = Instant.parse("2026-08-05T01:00:00Z")
        val old = TestFixtures.task(
            title = "旧任务",
            status = TaskStatus.PARTIAL,
            actualValue = 20,
            slotStart = LocalTime.of(8, 0),
        ).copy(checkedAt = checkedAt, focusedMinutes = 15, checkinNote = "已完成一部分")
        val fresh = old.copy(
            id = "task-fresh",
            title = "新任务",
            slotStart = LocalTime.of(9, 0),
            status = TaskStatus.PENDING,
            actualValue = 0,
            checkedAt = null,
            focusedMinutes = 0,
            checkinNote = null,
        )

        val result = DailyTaskReconciler.reconcile(listOf(old), listOf(fresh))

        assertEquals(1, result.updates.size)
        with(result.updates.single()) {
            assertEquals(old.id, id)
            assertEquals("新任务", title)
            assertEquals(LocalTime.of(9, 0), slotStart)
            assertEquals(TaskStatus.PARTIAL, status)
            assertEquals(20, actualValue)
            assertEquals(checkedAt, this.checkedAt)
            assertEquals(15, focusedMinutes)
            assertEquals("已完成一部分", checkinNote)
        }
    }

    @Test
    fun `reconcile adds newly eligible task and only removes obsolete pending task`() {
        val pending = TestFixtures.task(title = "不再安排").copy(templateId = "1")
        val completed = TestFixtures.task(title = "已有成绩", status = TaskStatus.DONE, actualValue = 1)
            .copy(templateId = "2")
        val newlyEligible = TestFixtures.task(title = "今天新增").copy(id = "task-new", templateId = "3")

        val result = DailyTaskReconciler.reconcile(
            existing = listOf(pending, completed),
            expected = listOf(newlyEligible),
        )

        assertEquals(listOf(pending.id), result.deleteIds)
        assertEquals(listOf(newlyEligible), result.inserts)
        assertTrue(result.updates.isEmpty())
        // completed 没进入 deleteIds：已经产生的成绩不能因编辑计划而丢失。
        assertTrue(completed.id !in result.deleteIds)
    }
}
