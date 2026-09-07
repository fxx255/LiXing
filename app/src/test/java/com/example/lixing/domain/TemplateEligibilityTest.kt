package com.example.lixing.domain

import com.example.lixing.data.local.entity.PhaseEntity
import com.example.lixing.data.local.entity.TaskTemplateEntity
import com.example.lixing.data.local.entity.TimeSlotEntity
import com.example.lixing.domain.materialize.TemplateEligibility
import com.example.lixing.domain.model.RepeatRule
import com.example.lixing.domain.model.WeekdayMask
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class TemplateEligibilityTest {

    private val planStart = LocalDate.of(2026, 3, 1)

    private fun slot(
        mask: Int = WeekdayMask.EVERY_DAY.value,
        enabled: Boolean = true,
    ) = TimeSlotEntity(
        id = "slot-1", planId = "plan-1", name = "早读",
        startTime = LocalTime.of(6, 40), endTime = LocalTime.of(7, 30),
        weekdayMask = mask, sortOrder = 0, isEnabled = enabled,
    )

    private fun template(
        enabled: Boolean = true,
        phaseId: String? = null,
        activeFrom: LocalDate? = null,
        activeUntil: LocalDate? = null,
        repeatRule: RepeatRule = RepeatRule.DAILY,
        weekdayMask: Int = WeekdayMask.EVERY_DAY.value,
        intervalDays: Int = 1,
        anchorDate: LocalDate? = null,
    ) = TaskTemplateEntity(
        id = "template-1", subjectId = "subject-1", timeSlotId = "slot-1", title = "背单词",
        isEnabled = enabled, phaseId = phaseId,
        activeFrom = activeFrom, activeUntil = activeUntil,
        repeatRule = repeatRule, weekdayMask = weekdayMask,
        intervalDays = intervalDays, anchorDate = anchorDate,
    )

    private val baseDate = LocalDate.of(2026, 8, 3) // 周一

    @Test
    fun `enabled daily template on active slot is eligible`() {
        assertTrue(
            TemplateEligibility.isEligible(
                template(), slot(), null, baseDate, planStart,
            ),
        )
    }

    @Test
    fun `disabled template is not eligible`() {
        assertFalse(
            TemplateEligibility.isEligible(
                template(enabled = false), slot(), null, baseDate, planStart,
            ),
        )
    }

    @Test
    fun `disabled slot is not eligible`() {
        assertFalse(
            TemplateEligibility.isEligible(
                template(), slot(enabled = false), null, baseDate, planStart,
            ),
        )
    }

    @Test
    fun `slot weekday mask filters day`() {
        // 时段只在周末生效，baseDate 是周一 → 不生效
        val weekendOnly = slot(mask = WeekdayMask.WEEKEND.value)
        assertFalse(
            TemplateEligibility.isEligible(template(), weekendOnly, null, baseDate, planStart),
        )
        val saturday = baseDate.with(DayOfWeek.SATURDAY)
        assertTrue(
            TemplateEligibility.isEligible(template(), weekendOnly, null, saturday, planStart),
        )
    }

    @Test
    fun `active date range filters`() {
        val t = template(activeFrom = baseDate, activeUntil = baseDate.plusDays(5))
        assertTrue(TemplateEligibility.isEligible(t, slot(), null, baseDate, planStart))
        assertFalse(TemplateEligibility.isEligible(t, slot(), null, baseDate.plusDays(6), planStart))
        assertFalse(TemplateEligibility.isEligible(t, slot(), null, baseDate.minusDays(1), planStart))
    }

    @Test
    fun `phase binding filters by phase range`() {
        val phase = PhaseEntity(
            id = "phase-10", planId = "plan-1", name = "强化",
            startDate = LocalDate.of(2026, 8, 1),
            endDate = LocalDate.of(2026, 10, 31),
            sortOrder = 1,
        )
        val t = template(phaseId = "phase-10")
        assertTrue(TemplateEligibility.isEligible(t, slot(), phase, baseDate, planStart))
        // 阶段外
        assertFalse(
            TemplateEligibility.isEligible(t, slot(), phase, LocalDate.of(2026, 11, 15), planStart),
        )
    }

    @Test
    fun `bound phase missing means not eligible`() {
        val t = template(phaseId = "missing-phase")
        assertFalse(TemplateEligibility.isEligible(t, slot(), null, baseDate, planStart))
    }

    @Test
    fun `weekly days repeat rule`() {
        val t = template(repeatRule = RepeatRule.WEEKLY_DAYS, weekdayMask = WeekdayMask.of(DayOfWeek.MONDAY).value)
        assertTrue(TemplateEligibility.isEligible(t, slot(), null, baseDate, planStart))
        assertFalse(TemplateEligibility.isEligible(t, slot(), null, baseDate.plusDays(1), planStart))
    }

    @Test
    fun `every n days uses interval`() {
        val t = template(
            repeatRule = RepeatRule.EVERY_N_DAYS,
            intervalDays = 2,
            anchorDate = baseDate,
        )
        assertTrue(TemplateEligibility.isEligible(t, slot(), null, baseDate, planStart))
        assertFalse(TemplateEligibility.isEligible(t, slot(), null, baseDate.plusDays(1), planStart))
        assertTrue(TemplateEligibility.isEligible(t, slot(), null, baseDate.plusDays(2), planStart))
    }

    @Test
    fun `every n days before anchor not eligible`() {
        val t = template(
            repeatRule = RepeatRule.EVERY_N_DAYS,
            intervalDays = 2,
            anchorDate = baseDate,
        )
        assertFalse(TemplateEligibility.isEligible(t, slot(), null, baseDate.minusDays(2), planStart))
    }
}
