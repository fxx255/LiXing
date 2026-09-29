package com.example.lixing.domain

import com.example.lixing.domain.planning.AvailabilityWindow
import com.example.lixing.domain.planning.PlanningEngine
import com.example.lixing.domain.time.StudyDayWindow
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanningEngineTest {
    private val date = LocalDate.of(2026, 9, 29)
    private val dayStart = LocalTime.of(4, 0)

    @Test fun `adjacent windows and bookings allow exact remaining placement`() {
        val windows = PlanningEngine.validateWindows(date, dayStart, listOf(
            AvailabilityWindow("09:00", "10:00"), AvailabilityWindow("10:00", "11:00")))
        val occupied = listOf(StudyDayWindow.of(date, LocalTime.of(9, 0), LocalTime.of(9, 30), dayStart))
        assertEquals(LocalTime.of(9, 30), PlanningEngine.place(30, windows, occupied)?.start?.toLocalTime())
        assertEquals(LocalTime.of(10, 0), PlanningEngine.place(60, windows, occupied)?.start?.toLocalTime())
        assertNull(PlanningEngine.place(61, windows, occupied))
    }

    @Test fun `availability after midnight belongs to previous study date`() {
        val windows = PlanningEngine.validateWindows(date, dayStart,
            listOf(AvailabilityWindow("00:30", "02:00")))
        assertEquals(date.plusDays(1), windows.single().start.toLocalDate())
        assertEquals(90, PlanningEngine.capacityMinutes(windows))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `overlapping availability is rejected`() {
        PlanningEngine.validateWindows(date, dayStart, listOf(
            AvailabilityWindow("09:00", "10:30"), AvailabilityWindow("10:00", "11:00")))
    }
}
