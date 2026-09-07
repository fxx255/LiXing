package com.example.lixing.domain

import com.example.lixing.domain.model.WeekdayMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class WeekdayMaskTest {

    @Test
    fun `every day mask contains all days`() {
        val mask = WeekdayMask.EVERY_DAY
        DayOfWeek.entries.forEach { assertTrue(mask.contains(it)) }
        assertEquals("每天", mask.describe())
    }

    @Test
    fun `weekdays mask contains Mon to Fri`() {
        val mask = WeekdayMask.WEEKDAYS
        listOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        ).forEach { assertTrue(mask.contains(it)) }
        assertFalse(mask.contains(DayOfWeek.SATURDAY))
        assertFalse(mask.contains(DayOfWeek.SUNDAY))
        assertEquals("工作日", mask.describe())
    }

    @Test
    fun `weekend mask contains Sat and Sun`() {
        val mask = WeekdayMask.WEEKEND
        assertFalse(mask.contains(DayOfWeek.FRIDAY))
        assertTrue(mask.contains(DayOfWeek.SATURDAY))
        assertTrue(mask.contains(DayOfWeek.SUNDAY))
        assertEquals("周末", mask.describe())
    }

    @Test
    fun `toggle adds and removes`() {
        var mask = WeekdayMask.NONE
        mask = mask.toggle(DayOfWeek.WEDNESDAY)
        assertTrue(mask.contains(DayOfWeek.WEDNESDAY))
        mask = mask.toggle(DayOfWeek.WEDNESDAY)
        assertFalse(mask.contains(DayOfWeek.WEDNESDAY))
        assertTrue(mask.isEmpty)
    }

    @Test
    fun `of builds from specific days`() {
        val mask = WeekdayMask.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)
        assertTrue(mask.contains(DayOfWeek.MONDAY))
        assertFalse(mask.contains(DayOfWeek.TUESDAY))
        assertTrue(mask.contains(DayOfWeek.THURSDAY))
        assertEquals("周一、周四", mask.describe())
    }

    @Test
    fun `contains works with LocalDate`() {
        val monday = LocalDate.of(2026, 8, 3) // 周一
        val mask = WeekdayMask.of(DayOfWeek.MONDAY)
        assertTrue(mask.contains(monday))
        assertFalse(mask.contains(monday.plusDays(1)))
    }
}
