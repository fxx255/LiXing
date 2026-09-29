package com.example.lixing.domain.time

import java.time.LocalDate
import java.time.LocalTime

/** Places a clock-time interval inside the configured study day, including after midnight. */
object StudyDayWindow {
    fun of(date: LocalDate, start: LocalTime, end: LocalTime, dayStart: LocalTime): SlotWindow {
        require(start != end || start == dayStart) { "时间段跨越学习日切日点，请拆成两天安排" }
        val actualStart = date.plusDays(if (start < dayStart) 1 else 0).atTime(start)
        var actualEnd = actualStart.toLocalDate().atTime(end)
        if (!actualEnd.isAfter(actualStart)) actualEnd = actualEnd.plusDays(1)
        require(!actualEnd.isAfter(date.plusDays(1).atTime(dayStart))) {
            "时间段跨越学习日切日点，请拆成两天安排"
        }
        return SlotWindow(date, actualStart, actualEnd)
    }
}
