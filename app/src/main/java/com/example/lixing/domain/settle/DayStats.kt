package com.example.lixing.domain.settle

import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.domain.model.TaskStatus
import com.example.lixing.domain.rules.PointRules

/**
 * 一天的统计结果。由 [DayStatsCalculator] 从任务列表算出，
 * 既用于当天实时显示（首页进度环），也用于结算时写入 day_record。
 */
data class DayStats(
    val totalTasks: Int,
    val doneTasks: Int,
    val partialTasks: Int,
    val missedTasks: Int,
    val skippedTasks: Int,
    val pendingTasks: Int,
    /** 加权完成率 0f~1f：DONE 记 1，PARTIAL 按实际/目标折算，请假不计入分母。 */
    val completionRate: Float,
    /** 关键任务是否全部完成。 */
    val keystoneAllDone: Boolean,
    val keystoneTotal: Int,
    val keystoneDone: Int,
    /** 所有设置了“至少完成几项”的时段是否达到要求。 */
    val slotRequirementsMet: Boolean = true,
    /** 由任务上的 focused_minutes 累加而来。 */
    val focusMinutes: Int,
) {
    /** 计入统计的任务数（排除请假）。 */
    val countedTasks: Int get() = totalTasks - skippedTasks

    /** 已结算数（不含 PENDING）。 */
    val settledTasks: Int get() = doneTasks + partialTasks + missedTasks + skippedTasks

    /** 首页进度环用：已完成 / 应完成（PARTIAL 算半个不合适，环上按 DONE 计数更直观）。 */
    val doneRatio: Float
        get() = if (countedTasks == 0) 0f else doneTasks.toFloat() / countedTasks

    /**
     * 是否达成。阈值默认 60%，可在设置里调。
     * 一天没有任何应做任务（全请假或没排任务）时，不算达成也不算断签——
     * 由调用方结合 isDayOff 决定，见 StreakCalculator。
     */
    fun isAchieved(threshold: Float): Boolean =
        countedTasks > 0 && completionRate >= threshold

    /** 满勤：全部计入的任务都 DONE，且关键任务无遗漏。 */
    val isFullDay: Boolean
        get() = countedTasks > 0 && doneTasks == countedTasks && keystoneAllDone && slotRequirementsMet

    companion object {
        val EMPTY = DayStats(
            totalTasks = 0,
            doneTasks = 0,
            partialTasks = 0,
            missedTasks = 0,
            skippedTasks = 0,
            pendingTasks = 0,
            completionRate = 0f,
            keystoneAllDone = true,
            keystoneTotal = 0,
            keystoneDone = 0,
            slotRequirementsMet = true,
            focusMinutes = 0,
        )
    }
}

/** 从任务列表算出 [DayStats]。纯函数。 */
object DayStatsCalculator {

    fun calculate(tasks: List<DailyTaskEntity>): DayStats {
        if (tasks.isEmpty()) return DayStats.EMPTY

        var done = 0
        var partial = 0
        var missed = 0
        var skipped = 0
        var pending = 0
        var keystoneTotal = 0
        var keystoneDone = 0
        var focusMinutes = 0
        // 加权完成度累加：DONE 记 1，PARTIAL 按比例（上限 1，超额不额外加权）
        var weightedSum = 0f
        var weightedDenominator = 0f
        var slotRequirementsMet = true

        val bySlot = tasks.groupBy { it.timeSlotId }

        // 时段设置了最少完成数时，将整个时段作为一个可选任务组计分。
        for ((_, slotTasks) in bySlot) {
            val required = slotTasks.firstOrNull()?.slotRequiredTaskCount?.coerceAtLeast(0) ?: 0
            if (required <= 0) continue
            val active = slotTasks.filter { it.status != TaskStatus.SKIPPED }
            if (active.isEmpty()) continue
            val engaged = active.count { it.status.isEngaged }
            if (engaged < required) slotRequirementsMet = false
            weightedDenominator += 1f
            weightedSum += engaged.toFloat() / required
        }

        for (task in tasks) {
            focusMinutes += task.focusedMinutes

            when (task.status) {
                TaskStatus.DONE -> {
                    done++
                    if ((task.slotRequiredTaskCount).coerceAtLeast(0) <= 0) {
                        weightedSum += 1f; weightedDenominator += 1f
                    }
                }

                TaskStatus.PARTIAL -> {
                    partial++
                    if (task.slotRequiredTaskCount.coerceAtLeast(0) <= 0) {
                        weightedSum += PointRules
                            .completionRatio(task.actualValue, task.targetValue, task.targetType)
                            .coerceIn(0f, 1f)
                        weightedDenominator += 1f
                    }
                }

                TaskStatus.MISSED -> { missed++; if (task.slotRequiredTaskCount <= 0) weightedDenominator += 1f }
                TaskStatus.SKIPPED -> skipped++
                TaskStatus.PENDING -> { pending++; if (task.slotRequiredTaskCount <= 0) weightedDenominator += 1f }
            }

            // 请假的关键任务不计入「关键任务是否全做完」
            if (task.isKeystone && task.status != TaskStatus.SKIPPED) {
                keystoneTotal++
                if (task.status == TaskStatus.DONE) keystoneDone++
            }
        }

        val counted = tasks.size - skipped
        val rate = if (weightedDenominator <= 0f) 0f else weightedSum / weightedDenominator

        return DayStats(
            totalTasks = tasks.size,
            doneTasks = done,
            partialTasks = partial,
            missedTasks = missed,
            skippedTasks = skipped,
            pendingTasks = pending,
            completionRate = rate,
            keystoneAllDone = keystoneTotal == 0 || keystoneDone == keystoneTotal,
            keystoneTotal = keystoneTotal,
            keystoneDone = keystoneDone,
            slotRequirementsMet = slotRequirementsMet,
            focusMinutes = focusMinutes,
        )
    }
}
