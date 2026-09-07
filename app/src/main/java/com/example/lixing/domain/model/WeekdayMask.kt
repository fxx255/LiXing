package com.example.lixing.domain.model

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 星期集合的位掩码表示。周一 = bit0 … 周日 = bit6。
 *
 * 用 Int 存进 Room 比存字符串省事得多，也方便做「本周哪几天生效」的位运算。
 * 全部方法都是纯函数，可直接单测。
 */
@JvmInline
value class WeekdayMask(val value: Int) {

    operator fun contains(day: DayOfWeek): Boolean =
        value and bitOf(day) != 0

    fun contains(date: LocalDate): Boolean = contains(date.dayOfWeek)

    operator fun plus(day: DayOfWeek): WeekdayMask =
        WeekdayMask(value or bitOf(day))

    operator fun minus(day: DayOfWeek): WeekdayMask =
        WeekdayMask(value and bitOf(day).inv())

    fun toggle(day: DayOfWeek): WeekdayMask =
        if (contains(day)) this - day else this + day

    val isEmpty: Boolean get() = value and ALL_BITS == 0

    val days: List<DayOfWeek>
        get() = DayOfWeek.entries.filter { contains(it) }

    /** 「每天 / 工作日 / 周末 / 周一、周三」这类人类可读描述。 */
    fun describe(): String = when {
        isEmpty -> "未选择"
        value and ALL_BITS == ALL_BITS -> "每天"
        value and ALL_BITS == WEEKDAYS.value -> "工作日"
        value and ALL_BITS == WEEKEND.value -> "周末"
        else -> days.joinToString("、") { SHORT_NAMES[it.ordinal] }
    }

    companion object {
        private const val ALL_BITS = 0b111_1111

        val SHORT_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

        val NONE = WeekdayMask(0)
        val EVERY_DAY = WeekdayMask(ALL_BITS)
        val WEEKDAYS = WeekdayMask(0b001_1111)
        val WEEKEND = WeekdayMask(0b110_0000)

        /** DayOfWeek.MONDAY.ordinal == 0，正好对上 bit0。 */
        fun bitOf(day: DayOfWeek): Int = 1 shl day.ordinal

        fun of(vararg days: DayOfWeek): WeekdayMask =
            WeekdayMask(days.fold(0) { acc, d -> acc or bitOf(d) })

        fun of(days: Iterable<DayOfWeek>): WeekdayMask =
            WeekdayMask(days.fold(0) { acc, d -> acc or bitOf(d) })
    }
}
