package com.example.lixing.domain.materialize

import com.example.lixing.data.local.entity.PhaseEntity
import com.example.lixing.data.local.entity.TaskTemplateEntity
import com.example.lixing.data.local.entity.TimeSlotEntity
import com.example.lixing.domain.model.RepeatRule
import com.example.lixing.domain.model.WeekdayMask
import java.time.LocalDate
import kotlin.math.absoluteValue

/**
 * 判断「某个模板在某一天是否应该生成任务」。
 *
 * 四层过滤全部通过才生效：
 * 1. 模板启用 + 所属时段启用；
 * 2. 时段的星期掩码命中当天；
 * 3. 模板的日期区间 / 阶段范围覆盖当天；
 * 4. 模板自己的重复规则命中当天。
 *
 * 纯函数，不碰数据库，方便穷举测试各种边界。
 */
object TemplateEligibility {

    /**
     * @param template 待判断的模板
     * @param slot 模板所属时段（调用方保证 id 对应）
     * @param phase 模板绑定的阶段，模板未绑定阶段时传 null
     * @param date 目标日期（学习日）
     * @param planStart 计划开始日，用作 EVERY_N_DAYS 的兜底锚点
     */
    fun isEligible(
        template: TaskTemplateEntity,
        slot: TimeSlotEntity,
        phase: PhaseEntity?,
        date: LocalDate,
        planStart: LocalDate,
    ): Boolean {
        if (!template.isEnabled || !slot.isEnabled) return false
        if (!WeekdayMask(slot.weekdayMask).contains(date)) return false
        if (!inActiveRange(template, date)) return false
        if (!inPhaseRange(template, phase, date)) return false
        return matchesRepeatRule(template, date, planStart)
    }

    /** 模板自身的生效日期区间。两端都可空，空 = 不限。 */
    fun inActiveRange(template: TaskTemplateEntity, date: LocalDate): Boolean {
        template.activeFrom?.let { if (date < it) return false }
        template.activeUntil?.let { if (date > it) return false }
        return true
    }

    /**
     * 阶段范围。模板 phaseId 为空 = 全程有效。
     * 绑了阶段但阶段查不到（被删了）时按「不生效」处理，避免生成孤儿任务。
     */
    fun inPhaseRange(
        template: TaskTemplateEntity,
        phase: PhaseEntity?,
        date: LocalDate,
    ): Boolean {
        if (template.phaseId == null) return true
        if (phase == null) return false
        return date >= phase.startDate && date <= phase.endDate
    }

    /** 重复规则命中判断。 */
    fun matchesRepeatRule(
        template: TaskTemplateEntity,
        date: LocalDate,
        planStart: LocalDate,
    ): Boolean = when (template.repeatRule) {
        RepeatRule.DAILY -> true

        RepeatRule.WEEKLY_DAYS -> WeekdayMask(template.weekdayMask).contains(date)

        RepeatRule.EVERY_N_DAYS -> {
            val interval = template.intervalDays.coerceAtLeast(1)
            val anchor = template.anchorDate ?: template.activeFrom ?: planStart
            // 锚点之前不生成；之后按间隔取模。用绝对值防御锚点晚于目标日的脏数据
            if (date < anchor) false
            else (date.toEpochDay() - anchor.toEpochDay()).absoluteValue % interval == 0L
        }
    }
}
