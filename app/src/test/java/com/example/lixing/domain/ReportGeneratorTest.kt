package com.example.lixing.domain

import com.example.lixing.data.local.dao.SlotCompletion
import com.example.lixing.data.local.dao.SubjectMinutes
import com.example.lixing.data.local.entity.DayRecordEntity
import com.example.lixing.domain.report.ReportGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReportGeneratorTest {

    private fun record(date: LocalDate, rate: Float, focus: Int = 60, achieved: Boolean = rate >= 0.6f): DayRecordEntity =
        DayRecordEntity(
            date = date,
            totalTasks = 5,
            completionRate = rate,
            focusMinutes = focus,
            isAchieved = achieved,
        )

    private val monday = LocalDate.of(2026, 8, 3)

    @Test
    fun `high completion gives steady suggestion`() {
        val records = listOf(
            record(monday, 0.95f),
            record(monday.plusDays(1), 0.92f),
        )
        val report = ReportGenerator.generate("本周", records, emptyList(), emptyList())
        assertTrue(report.completionRate > 0.9f)
        assertTrue(report.suggestion.contains("稳"))
    }

    @Test
    fun `empty records yields zero rate and fallback suggestion`() {
        val report = ReportGenerator.generate("本周", emptyList(), emptyList(), emptyList())
        assertEquals(0f, report.completionRate)
        assertTrue(report.suggestion.isNotEmpty())
    }

    @Test
    fun `worst slot drives suggestion`() {
        val records = listOf(record(monday, 0.6f))
        val slots = listOf(
            SlotCompletion("1", "早读", 10, 9),
            SlotCompletion("2", "晚间", 10, 2), // 完成率 20%
        )
        val report = ReportGenerator.generate("本周", records, slots, emptyList())
        assertEquals("早读", report.bestSlot)
        assertTrue(report.suggestion.contains("晚间"))
    }

    @Test
    fun `delta vs previous computed`() {
        val prev = listOf(record(monday.minusDays(7), 0.8f))
        val cur = listOf(record(monday, 0.6f))
        val report = ReportGenerator.generate("本周", cur, emptyList(), emptyList(), prev)
        assertNotNull(report.deltaVsPrevious)
        // 60% vs 80% => -20 百分点
        assertEquals(-20, report.deltaVsPrevious)
    }

    @Test
    fun `focus minutes summed`() {
        val records = listOf(record(monday, 0.6f, focus = 90), record(monday.plusDays(1), 0.6f, focus = 30))
        val report = ReportGenerator.generate("本周", records, emptyList(), emptyList())
        assertEquals(120, report.totalFocusMinutes)
    }

    @Test
    fun `weakest subject is min minutes`() {
        val subjects = listOf(
            SubjectMinutes("1", "数学", 600),
            SubjectMinutes("2", "英语", 100),
        )
        val report = ReportGenerator.generate("本周", listOf(record(monday, 0.6f)), emptyList(), subjects)
        assertEquals("英语", report.weakestSubject)
    }
}
