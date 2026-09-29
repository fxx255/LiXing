package com.example.lixing.domain.planning

import com.example.lixing.domain.time.SlotWindow
import com.example.lixing.domain.time.StudyDayWindow
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** A clock range within one configured study day. */
@Serializable
data class AvailabilityWindow(val start: String, val end: String) {
    fun resolve(date: LocalDate, dayStart: LocalTime): SlotWindow =
        StudyDayWindow.of(date, LocalTime.parse(start), LocalTime.parse(end), dayStart)
}

object AvailabilityCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(windows: List<AvailabilityWindow>): String = json.encodeToString(windows)
    fun decode(raw: String): List<AvailabilityWindow>? =
        runCatching { json.decodeFromString<List<AvailabilityWindow>>(raw) }.getOrNull()
}

/** Deterministic local placement. Half-open windows allow adjacent tasks. */
object PlanningEngine {
    fun validateWindows(date: LocalDate, dayStart: LocalTime, windows: List<AvailabilityWindow>): List<SlotWindow> {
        val resolved = windows.map { it.resolve(date, dayStart) }.sortedBy { it.start }
        require(resolved.zipWithNext().none { (left, right) -> left.end > right.start }) {
            "可用时间段不能互相重叠"
        }
        return resolved
    }

    fun intersect(left: SlotWindow, right: SlotWindow): SlotWindow? {
        val start = maxOf(left.start, right.start)
        val end = minOf(left.end, right.end)
        return if (start < end) SlotWindow(left.date, start, end) else null
    }

    fun availableForSlot(slot: SlotWindow, dayWindows: List<SlotWindow>?): List<SlotWindow> =
        (dayWindows ?: listOf(slot)).mapNotNull { intersect(slot, it) }.sortedBy { it.start }

    fun capacityMinutes(windows: List<SlotWindow>): Int = windows.sumOf { it.duration.toMinutes().toInt() }

    fun place(minutes: Int, available: List<SlotWindow>, occupied: List<SlotWindow>): SlotWindow? {
        require(minutes in 1..1440)
        val booked = occupied.sortedBy { it.start }
        for (window in available.sortedBy { it.start }) {
            var cursor = window.start
            for (busy in booked) {
                if (busy.end <= cursor || busy.start >= window.end) continue
                val freeEnd = minOf(busy.start, window.end)
                if (java.time.Duration.between(cursor, freeEnd).toMinutes() >= minutes) {
                    return SlotWindow(window.date, cursor, cursor.plusMinutes(minutes.toLong()))
                }
                cursor = maxOf(cursor, busy.end)
                if (cursor >= window.end) break
            }
            if (java.time.Duration.between(cursor, window.end).toMinutes() >= minutes) {
                return SlotWindow(window.date, cursor, cursor.plusMinutes(minutes.toLong()))
            }
        }
        return null
    }
}
