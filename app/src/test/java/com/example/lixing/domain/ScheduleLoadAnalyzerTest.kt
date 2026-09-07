package com.example.lixing.domain

import com.example.lixing.data.local.entity.TimeSlotEntity
import com.example.lixing.domain.schedule.ScheduleLoadAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class ScheduleLoadAnalyzerTest {

    private fun slot(start: String, end: String, name: String = "段") = TimeSlotEntity(
        id = "slot-$start", planId = "1", name = name,
        startTime = LocalTime.parse(start), endTime = LocalTime.parse(end),
        isEnabled = true,
    )

    @Test
    fun `moderate schedule has no warnings`() {
        val slots = listOf(
            slot("06:40", "07:30", "早读"),
            slot("08:00", "11:30", "上午"),
            slot("14:00", "17:30", "下午"),
        )
        val load = ScheduleLoadAnalyzer.analyze(slots)
        assertFalse(load.isOverPacked)
        assertTrue(load.warnings.isEmpty())
        assertTrue(load.restMinutes > 0)
    }

    @Test
    fun `over ten hours triggers over-packed warning`() {
        val slots = listOf(
            slot("06:00", "12:00"),
            slot("13:00", "18:00"),
            slot("19:00", "23:30"),
        )
        val load = ScheduleLoadAnalyzer.analyze(slots)
        assertTrue(load.scheduledMinutes > ScheduleLoadAnalyzer.MAX_SCHEDULED_MINUTES)
        assertTrue(load.isOverPacked)
        assertTrue(load.warnings.any { it.contains("小时") })
    }

    @Test
    fun `back-to-back slots with no gap warn about rest`() {
        val slots = listOf(
            slot("08:00", "11:30", "上午"),
            slot("11:30", "12:00", "紧接着"), // 0 空档
        )
        val load = ScheduleLoadAnalyzer.analyze(slots)
        assertTrue(load.warnings.any { it.contains("空隙") })
    }

    @Test
    fun `long block over 3_5h warns`() {
        val slots = listOf(slot("08:00", "12:00")) // 4h
        val load = ScheduleLoadAnalyzer.analyze(slots)
        assertTrue(load.hasLongBlock)
        assertTrue(load.warnings.any { it.contains("休息") })
    }

    @Test
    fun `scheduled minutes computed`() {
        val slots = listOf(slot("08:00", "10:00"), slot("14:00", "15:30"))
        val load = ScheduleLoadAnalyzer.analyze(slots)
        assertEquals(120 + 90, load.scheduledMinutes)
    }

    @Test
    fun `disabled slots ignored`() {
        val s = slot("08:00", "10:00").copy(isEnabled = false)
        val load = ScheduleLoadAnalyzer.analyze(listOf(s))
        assertEquals(0, load.scheduledMinutes)
    }
}
