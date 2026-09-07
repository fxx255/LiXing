package com.example.lixing.domain.seed

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 把种子模板里的相对日期解析成具体日期。
 *
 * 阶段边界写成「月-日」，实际落到哪一年取决于用户设定的目标日：
 * 目标日在 12 月，那么 7/31、8/1、10/31 都属于目标日所在的那一年。
 * 跨年备考（比如从上一年 3 月开始）时 [MonthDaySpec.yearOffsetFromTarget] 可以往前挪。
 *
 * 纯函数，可单测。
 */
object SeedResolver {

    /**
     * 考研初试的默认日期：12 月的第三个周六。
     * 近年初试都在 12 月下旬的周末开考，这个规则能落到正确的那一天附近；
     * 教育部公布确切日期后用户可以在设置里改。
     */
    fun defaultTargetDate(referenceDate: LocalDate): LocalDate {
        val thisYear = thirdSaturdayOfDecember(referenceDate.year)
        // 已经过了今年的考试日，就算下一年的
        return if (referenceDate <= thisYear) thisYear else thirdSaturdayOfDecember(referenceDate.year + 1)
    }

    fun thirdSaturdayOfDecember(year: Int): LocalDate =
        LocalDate.of(year, 12, 1)
            .with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.SATURDAY))

    /**
     * 解析阶段的实际起止日期。
     *
     * @param startDate 计划开始日
     * @param targetDate 目标日（考试日）
     * @return 与 [phases] 等长、顺序一致的日期区间列表。
     *         已按计划区间裁剪：越界的阶段会被夹到 [startDate]..[targetDate] 内。
     *         完全落在计划之外的阶段返回 null（调用方应跳过）。
     */
    fun resolvePhaseRanges(
        phases: List<SeedPhase>,
        startDate: LocalDate,
        targetDate: LocalDate,
    ): List<ResolvedPhase?> = phases.map { phase ->
        val rawStart = phase.startMonthDay?.let { resolveMonthDay(it, targetDate) } ?: startDate
        val rawEnd = phase.endMonthDay?.let { resolveMonthDay(it, targetDate) } ?: targetDate

        // 夹到计划区间内
        val start = maxOf(rawStart, startDate)
        val end = minOf(rawEnd, targetDate)

        // 裁剪后区间为空 = 这个阶段不适用于当前计划周期（比如 8 月才开始备考，基础阶段就没了）
        if (start > end) null else ResolvedPhase(phase, start, end)
    }

    /** 把「月-日 + 年偏移」落到具体年份。 */
    fun resolveMonthDay(spec: MonthDaySpec, targetDate: LocalDate): LocalDate {
        val year = targetDate.year + spec.yearOffsetFromTarget
        // 用 lengthOfMonth 夹一下，防御 2 月 30 日这类脏数据
        val month = spec.month.coerceIn(1, 12)
        val ym = java.time.YearMonth.of(year, month)
        return LocalDate.of(year, month, spec.day.coerceIn(1, ym.lengthOfMonth()))
    }
}

/** 解析后的阶段。 */
data class ResolvedPhase(
    val seed: SeedPhase,
    val startDate: LocalDate,
    val endDate: LocalDate,
)
