package com.example.lixing.data

import com.example.lixing.data.repository.completedMonthsBefore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CompletedMonthPolicyTest {
    @Test
    fun `current partial month is never included`() {
        val today = LocalDate.of(2026, 8, 8)
        val months = completedMonthsBefore(today, 3)

        assertEquals(listOf(YearMonth.of(2026, 7), YearMonth.of(2026, 6), YearMonth.of(2026, 5)), months)
        assertFalse(months.contains(YearMonth.of(2026, 8)))
    }
}
