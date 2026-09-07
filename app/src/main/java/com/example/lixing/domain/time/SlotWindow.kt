package com.example.lixing.domain.time

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 一个时段在某个具体日期上的时间窗口。
 *
 * 处理跨夜：endTime < startTime 视为跨越午夜，结束时刻落到次日。
 * 例如 23:30-00:30 在 3 月 5 日 → 3/5 23:30 ~ 3/6 00:30。
 *
 * 全部纯函数，无 Android 依赖，可直接单测。
 */
data class SlotWindow(
    val date: LocalDate,
    val start: LocalDateTime,
    val end: LocalDateTime,
) {
    val duration: Duration get() = Duration.between(start, end)

    /** now 是否落在窗口内（含首尾）。 */
    fun contains(now: LocalDateTime): Boolean = !now.isBefore(start) && !now.isAfter(end)

    fun isBefore(now: LocalDateTime): Boolean = now.isBefore(start)

    fun isAfter(now: LocalDateTime): Boolean = now.isAfter(end)

    /** 距开始还有多久，已开始则为 0。 */
    fun untilStart(now: LocalDateTime): Duration =
        if (now.isBefore(start)) Duration.between(now, start) else Duration.ZERO

    /** 距结束还有多久，已结束则为 0。 */
    fun untilEnd(now: LocalDateTime): Duration =
        if (now.isBefore(end)) Duration.between(now, end) else Duration.ZERO

    /** 窗口内已过去的比例 0f~1f，用于时段进度条。 */
    fun progress(now: LocalDateTime): Float {
        val total = duration.toMillis()
        if (total <= 0) return if (now.isBefore(start)) 0f else 1f
        val elapsed = Duration.between(start, now).toMillis()
        return (elapsed.toFloat() / total).coerceIn(0f, 1f)
    }

    companion object {
        /** 由时段的起止时刻构造某天的窗口，自动处理跨夜。 */
        fun of(date: LocalDate, startTime: LocalTime, endTime: LocalTime): SlotWindow {
            val start = date.atTime(startTime)
            val end = if (endTime > startTime) {
                date.atTime(endTime)
            } else {
                // 相等也按跨夜处理（24 小时时段），避免零长度窗口
                date.plusDays(1).atTime(endTime)
            }
            return SlotWindow(date, start, end)
        }
    }
}

/** 时段相对当前时刻的状态，用于首页徽章。 */
enum class SlotState(val label: String) {
    UPCOMING("未开始"),
    ONGOING("进行中"),
    PASSED("已过"),
    ;

    companion object {
        fun of(window: SlotWindow, now: LocalDateTime): SlotState = when {
            window.isBefore(now) -> UPCOMING
            window.isAfter(now) -> PASSED
            else -> ONGOING
        }
    }
}
