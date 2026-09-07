package com.example.lixing.domain.report

import com.example.lixing.data.local.dao.SlotCompletion
import com.example.lixing.data.local.dao.SubjectMinutes
import com.example.lixing.data.local.entity.DayRecordEntity
import kotlin.math.roundToInt

/**
 * 一份周报/月报。全部由纯数据算出，不碰库、不读时钟，可直接单测。
 */
data class PeriodReport(
    val label: String,
    /** 平均完成率 0f~1f。 */
    val completionRate: Float,
    /** 达成天数。 */
    val achievedDays: Int,
    /** 有记录的天数（非请假）。 */
    val recordedDays: Int,
    val totalFocusMinutes: Int,
    val totalPoints: Int,
    /** 完成率最高的时段名。 */
    val bestSlot: String?,
    /** 投入最少的科目名（薄弱科目）。 */
    val weakestSubject: String?,
    /** 上一周期的完成率，用于环比。null = 无上一周期数据。 */
    val previousRate: Float?,
    /** 规则生成的建议文案。 */
    val suggestion: String,
) {
    /** 环比变化（百分点），正 = 比上周好。用四舍五入，避免浮点截断差 1。 */
    val deltaVsPrevious: Int?
        get() = previousRate?.let { ((completionRate - it) * 100).roundToInt() }
}

/**
 * 报告生成器。
 *
 * 建议是规则生成的（不调大模型）：
 * 看完成率和最薄弱维度，给一句针对当前状态的话，而不是空泛的口号。
 */
object ReportGenerator {

    /**
     * @param records 本周期每日记录
     * @param slots 本周期各时段完成情况
     * @param subjects 本周期各科目投入（分钟）
     * @param previousRecords 上一周期记录，用于环比
     */
    fun generate(
        label: String,
        records: List<DayRecordEntity>,
        slots: List<SlotCompletion>,
        subjects: List<SubjectMinutes>,
        previousRecords: List<DayRecordEntity> = emptyList(),
        totalPoints: Int = 0,
    ): PeriodReport {
        val counted = records.filter { !it.isDayOff && it.totalTasks > 0 }
        val rate = if (counted.isEmpty()) 0f
        else counted.map { it.completionRate }.average().toFloat()

        val achieved = records.count { it.isAchieved }
        val focus = records.sumOf { it.focusMinutes }

        val bestSlot = slots.maxByOrNull { it.rate }?.slotName
        val weakestSubject = subjects.minByOrNull { it.minutes }?.subjectName

        val prevCounted = previousRecords.filter { !it.isDayOff && it.totalTasks > 0 }
        val prevRate = if (prevCounted.isEmpty()) null
        else prevCounted.map { it.completionRate }.average().toFloat()

        return PeriodReport(
            label = label,
            completionRate = rate,
            achievedDays = achieved,
            recordedDays = counted.size,
            totalFocusMinutes = focus,
            totalPoints = totalPoints,
            bestSlot = bestSlot,
            weakestSubject = weakestSubject,
            previousRate = prevRate,
            suggestion = suggest(rate, bestSlot, slots, weakestSubject, prevRate),
        )
    }

    /** 规则建议：挑最突出的一个点说。 */
    private fun suggest(
        rate: Float,
        bestSlot: String?,
        slots: List<SlotCompletion>,
        weakestSubject: String?,
        prevRate: Float?,
    ): String {
        if (rate >= 0.9f) return "完成率保持在九成以上，节奏非常稳，注意别透支。"

        // 环比明显下滑
        if (prevRate != null && rate < prevRate - 0.1f) {
            return "完成率比上一周期降了 ${((prevRate - rate) * 100).toInt()} 个百分点，先把目标量调小一点，找回手感。"
        }

        // 找最容易崩的时段
        val worstSlot = slots.filter { it.total >= 3 }.minByOrNull { it.rate }
        if (worstSlot != null && worstSlot.rate < 0.5f) {
            return "「${worstSlot.slotName}」完成率只有 ${(worstSlot.rate * 100).toInt()}%，把它的任务拆小或换个时间，别硬扛。"
        }

        if (rate < 0.5f && weakestSubject != null) {
            return "整体完成率偏低，先从「$weakestSubject」里最简单的一项做起，每天完成一件也行。"
        }

        if (bestSlot != null) {
            return "「$bestSlot」是你状态最好的时段，把最难的任务放进去。"
        }

        return "先把每天的待办控制在 3 件以内，完成比完美重要。"
    }
}
