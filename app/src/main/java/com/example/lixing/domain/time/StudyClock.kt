package com.example.lixing.domain.time

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 学习日历。所有「今天是哪天」的判断都必须走这里，不允许直接调 LocalDate.now()。
 *
 * 核心是切日点 [dayStart]：默认 04:00，意思是凌晨 3 点的打卡仍算「昨天」的成绩。
 * 熬夜到 1 点收尾的人不该因为过了午夜就被判断签。
 *
 * 用可注入的 [clock] 而非静态调用，是为了单测能自由控制时间。
 */
class StudyClock(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val dayStart: LocalTime = LocalTime.of(4, 0),
) {
    val zone: ZoneId get() = clock.zone

    fun nowDateTime(): LocalDateTime = LocalDateTime.now(clock)

    /** 当前所属的「学习日」。 */
    fun today(): LocalDate = studyDateOf(nowDateTime())

    /**
     * 把某个真实时刻映射到它所属的学习日。
     * 时刻的钟点早于切日点 → 归属前一天。
     */
    fun studyDateOf(dateTime: LocalDateTime): LocalDate =
        if (dateTime.toLocalTime() < dayStart) dateTime.toLocalDate().minusDays(1)
        else dateTime.toLocalDate()

    /** 某个学习日的真实起始时刻。 */
    fun startOf(studyDate: LocalDate): LocalDateTime = studyDate.atTime(dayStart)

    /** 某个学习日的真实结束时刻（下一天切日点，不含）。 */
    fun endOf(studyDate: LocalDate): LocalDateTime = studyDate.plusDays(1).atTime(dayStart)

    /** 某个学习日是否已经过完。 */
    fun isPast(studyDate: LocalDate): Boolean = today() > studyDate

    fun isToday(studyDate: LocalDate): Boolean = today() == studyDate

    /** 复制一份换掉切日点的实例（设置变更时用）。 */
    fun withDayStart(newDayStart: LocalTime): StudyClock = StudyClock(clock, newDayStart)

    companion object {
        /** 测试用：固定在某个时刻。 */
        fun fixedAt(dateTime: LocalDateTime, dayStart: LocalTime = LocalTime.of(4, 0)): StudyClock {
            val zone = ZoneId.systemDefault()
            return StudyClock(
                clock = Clock.fixed(dateTime.atZone(zone).toInstant(), zone),
                dayStart = dayStart,
            )
        }
    }
}
