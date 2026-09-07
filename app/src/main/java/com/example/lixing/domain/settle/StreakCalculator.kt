package com.example.lixing.domain.settle

import com.example.lixing.data.local.entity.CheckInStreakEntity
import com.example.lixing.data.local.entity.DayRecordEntity
import java.time.LocalDate

/** 一次连续记录推进的结果。 */
data class StreakUpdate(
    val streak: CheckInStreakEntity,
    /** 本次推进后的连续天数。 */
    val currentStreak: Int,
    /** 是否刚刷新了历史最长。 */
    val newRecord: Boolean,
    /** 是否断签了。 */
    val broken: Boolean,
    /** 是否消耗了补救卡来保住连续。 */
    val rescueUsed: Boolean,
    /** 断签前的连续天数，用于「上次坚持了 N 天」文案。 */
    val previousStreak: Int,
)

/**
 * 连续记录计算。
 *
 * 规则：
 * - 当天达成（完成率 ≥ 阈值）→ 连续 +1；
 * - 当天未达成，但还有补救卡 → 消耗一张，连续保持；
 * - 当天未达成且无补救卡 → 断签，归零；
 * - 请假日（整天）→ 既不 +1 也不断签，直接跳过；
 * - 中间有天数缺口（用户几天没开 App，那几天也没结算）→ 视为断签。
 *
 * 纯函数：输入当前 streak 状态 + 当天判定，输出新状态。不碰数据库、不读时钟。
 */
object StreakCalculator {

    data class RebuiltCurrent(
        val days: Int,
        val lastContinuousDate: LocalDate?,
    )

    /**
     * @param current 当前连续记录状态
     * @param date 正在结算的学习日
     * @param achieved 当天是否达成
     * @param isDayOff 当天是否整天请假
     * @param rescueCardsPerMonth 每月补救卡额度（跨月自动补满）
     * @param allowRescue 是否允许在这次结算里用补救卡。补结算历史天数时传 false，
     *        避免一次性把额度全用在很久以前的日子上。
     */
    fun advance(
        current: CheckInStreakEntity,
        date: LocalDate,
        achieved: Boolean,
        isDayOff: Boolean,
        rescueCardsPerMonth: Int,
        allowRescue: Boolean = true,
    ): StreakUpdate {
        val monthKey = monthKeyOf(date)
        // 跨月先把补救卡补满，再决定要不要用
        val cardsLeft = if (current.rescueCardsMonth != monthKey) {
            rescueCardsPerMonth
        } else {
            current.rescueCardsLeft
        }
        val normalized = current.copy(
            rescueCardsLeft = cardsLeft,
            rescueCardsMonth = monthKey,
        )

        val last = normalized.lastAchievedDate
        val previousStreak = normalized.currentStreak

        // 请假日：状态原样保留（含补救卡月度重置），只更新月份键
        if (isDayOff) {
            return StreakUpdate(
                streak = normalized,
                currentStreak = previousStreak,
                newRecord = false,
                broken = false,
                rescueUsed = false,
                previousStreak = previousStreak,
            )
        }

        if (achieved) {
            // 当天达到阈值时已实时推进；次日结算同一天必须幂等，不能重置成 1。
            if (last == date) {
                return StreakUpdate(
                    streak = normalized,
                    currentStreak = previousStreak,
                    newRecord = false,
                    broken = false,
                    rescueUsed = false,
                    previousStreak = previousStreak,
                )
            }
            // 连续的判定：上次达成日是不是紧邻的前一天。
            // 注意请假日会造成日期间隔，这里靠 lastAchievedDate 不更新来天然处理——
            // 请假不改 lastAchievedDate，所以隔一天请假后达成会被判为「不连续」。
            // 因此调用方在请假日必须走上面的分支，且请假日应把 lastAchievedDate 顺延，
            // 由 [carryOverDayOff] 负责。
            val continues = last != null && last.plusDays(1) == date
            val newStreak = if (continues) previousStreak + 1 else 1
            val longest = maxOf(normalized.longestStreak, newStreak)

            return StreakUpdate(
                streak = normalized.copy(
                    currentStreak = newStreak,
                    longestStreak = longest,
                    lastAchievedDate = date,
                    totalAchievedDays = normalized.totalAchievedDays + 1,
                ),
                currentStreak = newStreak,
                newRecord = newStreak > previousStreak && newStreak == longest && newStreak > 1,
                broken = false,
                rescueUsed = false,
                previousStreak = previousStreak,
            )
        }

        // 未达成。只有「本来还连着」才谈得上救——连续已经是 0 就不浪费卡
        val wasContinuous = last != null && last.plusDays(1) == date && previousStreak > 0
        if (wasContinuous && allowRescue && normalized.rescueCardsLeft > 0) {
            return StreakUpdate(
                streak = normalized.copy(
                    rescueCardsLeft = normalized.rescueCardsLeft - 1,
                    // 补救等于把这天记为「不中断」，顺延 lastAchievedDate 才能接上明天
                    lastAchievedDate = date,
                ),
                currentStreak = previousStreak,
                newRecord = false,
                broken = false,
                rescueUsed = true,
                previousStreak = previousStreak,
            )
        }

        return StreakUpdate(
            streak = normalized.copy(
                currentStreak = 0,
                lastAchievedDate = null,
            ),
            currentStreak = 0,
            newRecord = false,
            broken = previousStreak > 0,
            rescueUsed = false,
            previousStreak = previousStreak,
        )
    }

    /**
     * 从每日汇总重建当前连续天数，用来修复旧版本重复结算造成的 1/2 跳动。
     * 今天尚未达标且未结算时不算断签；请假日和已使用补救卡的日子只衔接、不加天数。
     */
    fun rebuildCurrent(records: List<DayRecordEntity>, today: LocalDate): RebuiltCurrent {
        val byDate = records.associateBy { it.date }
        var cursor = today
        var achievedDays = 0
        var latestContinuous: LocalDate? = null
        var sawAchieved = false

        repeat(400) { index ->
            val record = byDate[cursor]
            if (record == null) {
                if (index == 0) {
                    cursor = cursor.minusDays(1)
                    return@repeat
                }
                return RebuiltCurrent(achievedDays, latestContinuous.takeIf { sawAchieved })
            }

            when {
                record.isAchieved -> {
                    achievedDays++
                    sawAchieved = true
                    if (latestContinuous == null) latestContinuous = cursor
                }
                record.isDayOff || record.usedRescueCard -> {
                    if (latestContinuous == null) latestContinuous = cursor
                }
                index == 0 && !record.isSettled -> Unit
                else -> return RebuiltCurrent(achievedDays, latestContinuous.takeIf { sawAchieved })
            }
            cursor = cursor.minusDays(1)
        }
        return RebuiltCurrent(achievedDays, latestContinuous.takeIf { sawAchieved })
    }

    /**
     * 请假日顺延：把 lastAchievedDate 推到请假日，这样请假后第二天达成仍算连续。
     * 只在原本就连着的情况下顺延，避免把早已断掉的连续凭请假接起来。
     */
    fun carryOverDayOff(
        current: CheckInStreakEntity,
        date: LocalDate,
    ): CheckInStreakEntity {
        val last = current.lastAchievedDate ?: return current
        return if (last.plusDays(1) == date) current.copy(lastAchievedDate = date) else current
    }

    /** yyyyMM。 */
    fun monthKeyOf(date: LocalDate): Int = date.year * 100 + date.monthValue

    /** yyyyWW（ISO 周）。补卡额度按周重置用。 */
    fun weekKeyOf(date: LocalDate): Int {
        val weekFields = java.time.temporal.WeekFields.ISO
        val week = date.get(weekFields.weekOfWeekBasedYear())
        val year = date.get(weekFields.weekBasedYear())
        return year * 100 + week
    }
}
