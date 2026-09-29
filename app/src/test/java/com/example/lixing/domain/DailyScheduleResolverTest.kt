package com.example.lixing.domain

import com.example.lixing.data.local.entity.ScheduledTaskEntity
import com.example.lixing.data.local.entity.SubjectEntity
import com.example.lixing.data.local.entity.TimeSlotEntity
import com.example.lixing.domain.materialize.DailyTaskReconciler
import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskStatus
import com.example.lixing.domain.planning.ContentInterval
import com.example.lixing.domain.planning.DailyScheduleResolver
import com.example.lixing.domain.planning.IntervalMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class DailyScheduleResolverTest {
    private val date = LocalDate.of(2026, 9, 30)
    private val subject = SubjectEntity(id = "math", planId = "plan", name = "数学", colorArgb = 0xff123456.toInt())
    private val slot = TimeSlotEntity(id = "am", planId = "plan", name = "上午", startTime = LocalTime.of(9, 0), endTime = LocalTime.of(12, 0))

    @Test
    fun `template override keeps task identity and preserves the next day`() {
        val old = TestFixtures.task(date = date, title = "刷题 20 题").copy(
            id = "old-id", templateId = "template", subjectId = "math", timeSlotId = "am",
        )
        val override = ScheduledTaskEntity(
            id = "override", planId = "plan", studyDate = date, sourceTemplateId = "template",
            subjectId = "math", timeSlotId = "am", title = "第 190–210 题",
            targetType = TargetType.COUNT, targetValue = 21, plannedMinutes = 60,
            state = "OVERRIDE",
        )
        val today = DailyScheduleResolver.resolve(date, listOf(old), listOf(override), mapOf("am" to slot), mapOf("math" to subject))
        assertEquals(1, today.size)
        assertEquals("old-id", today.single().id)
        assertEquals("override", today.single().scheduleId)
        assertEquals(21, today.single().targetValue)

        val tomorrow = DailyScheduleResolver.resolve(date.plusDays(1), listOf(old.copy(date = date.plusDays(1))),
            emptyList(), mapOf("am" to slot), mapOf("math" to subject))
        assertEquals("刷题 20 题", tomorrow.single().title)
    }

    @Test
    fun `independent same title tasks retain distinct identities`() {
        val base = ScheduledTaskEntity(
            id = "a", planId = "plan", studyDate = date, subjectId = "math", timeSlotId = "am",
            title = "刷题", state = "ACTIVE",
        )
        val resolved = DailyScheduleResolver.resolve(date, emptyList(), listOf(base, base.copy(id = "b")),
            mapOf("am" to slot), mapOf("math" to subject))
        assertEquals(2, resolved.size)
        assertEquals(2, resolved.map { it.id }.toSet().size)
    }

    @Test
    fun `running focus freezes an otherwise pending dated task`() {
        val old = TestFixtures.task(date = date, title = "原题号").copy(scheduleId = "dated")
        val fresh = old.copy(title = "新题号")
        val result = DailyTaskReconciler.reconcile(listOf(old), listOf(fresh), setOf(old.id))
        assertTrue(result.updates.isEmpty())
    }

    @Test
    fun `completed noncontiguous questions have exact remaining intervals`() {
        val remaining = IntervalMath.remaining(
            listOf(ContentInterval(190, 210)),
            listOf(ContentInterval(190, 198), ContentInterval(200, 203)),
        )
        assertEquals(listOf(ContentInterval(199, 199), ContentInterval(204, 210)), remaining)
        assertEquals(8, remaining.sumOf { it.size })
    }
}
