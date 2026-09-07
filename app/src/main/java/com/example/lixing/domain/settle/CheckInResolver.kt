package com.example.lixing.domain.settle

import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskStatus
import com.example.lixing.domain.rules.PointRules
import com.example.lixing.domain.time.SlotWindow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** 一次打卡的判定结果。 */
data class CheckInOutcome(
    /** 更新后的任务实体，直接写库。 */
    val task: DailyTaskEntity,
    val status: TaskStatus,
    val isLate: Boolean,
    val isMakeup: Boolean,
    /** 本次打卡应得基础积分。 */
    val basePoints: Int,
    /** 超额奖励，0 表示无。 */
    val bonusPoints: Int,
) {
    val totalPoints: Int get() = basePoints + bonusPoints
}

/**
 * 打卡判定。决定「按时 / 迟到 / 补卡」以及状态和积分。
 *
 * 时效判定的口径（对应需求 4.2）：
 * - 在所属时段窗口内打卡 → 按时，满额积分；
 * - 时段结束后、但仍在同一学习日内 → 迟到，积分打 5 折；
 * - 隔日补卡 → 补卡，积分打 3 折，且需要填原因。
 *
 * 纯函数：时间由调用方传入，方便单测各种边界。
 */
object CheckInResolver {

    /**
     * @param task 待打卡的任务
     * @param actualValue 本次填报的完成量。BOOLEAN 类型传 1
     * @param now 当前真实时刻
     * @param studyToday 当前所属学习日（由 StudyClock 算出）
     * @param makeupReason 补卡原因，仅补卡时使用
     */
    fun resolve(
        task: DailyTaskEntity,
        actualValue: Int,
        now: LocalDateTime,
        studyToday: LocalDate,
        makeupReason: String? = null,
    ): CheckInOutcome {
        val value = normalizeValue(task.targetType, actualValue)
        val ratio = PointRules.completionRatio(value, task.targetValue, task.targetType)

        // 完成量为 0 视为撤销打卡，交给 revoke 处理，这里按 PENDING 返回不给分
        if (ratio <= 0f) {
            return CheckInOutcome(
                task = task.copy(
                    status = TaskStatus.PENDING,
                    actualValue = 0,
                    checkedAt = null,
                    isLate = false,
                    isMakeup = false,
                    makeupReason = null,
                ),
                status = TaskStatus.PENDING,
                isLate = false,
                isMakeup = false,
                basePoints = 0,
                bonusPoints = 0,
            )
        }

        // 更新已有打卡时，时效以「第一次记录时间」为准，不能因为后来改详情
        // 就把按时变成迟到、扣掉已得的积分。只有首次打卡才用 now 判时效。
        val isFirstCheck = task.checkedAt == null
        val isMakeup = if (isFirstCheck) task.date < studyToday else task.isMakeup
        val window = SlotWindow.of(task.date, task.slotStart, task.slotEnd)
        // 补卡本身已是最严格档，不再叠加迟到
        val isLate = if (isFirstCheck) !isMakeup && window.isAfter(now) else task.isLate
        val checkedAtInstant = if (isFirstCheck) {
            now.atZone(java.time.ZoneId.systemDefault()).toInstant()
        } else {
            task.checkedAt
        }

        // 达到目标算 DONE，未达到但有推进算 PARTIAL
        val status = if (ratio >= 1f) TaskStatus.DONE else TaskStatus.PARTIAL

        val basePoints = PointRules.checkInPoints(
            actualValue = value,
            targetValue = task.targetValue,
            targetType = task.targetType,
            isKeystone = task.isKeystone,
            isLate = isLate,
            isMakeup = isMakeup,
        )
        val bonusPoints = PointRules.overTargetBonus(
            actualValue = value,
            targetValue = task.targetValue,
            targetType = task.targetType,
            isKeystone = task.isKeystone,
        )

        return CheckInOutcome(
            task = task.copy(
                status = status,
                actualValue = value,
                checkedAt = checkedAtInstant,
                isLate = isLate,
                isMakeup = isMakeup,
                makeupReason = if (isMakeup && isFirstCheck) makeupReason else task.makeupReason,
            ),
            status = status,
            isLate = isLate,
            isMakeup = isMakeup,
            basePoints = basePoints,
            bonusPoints = bonusPoints,
        )
    }

    /** 撤销打卡：状态回到 PENDING，清掉打卡痕迹。积分由 Repository 按流水回滚。 */
    fun revoke(task: DailyTaskEntity): DailyTaskEntity = task.copy(
        status = TaskStatus.PENDING,
        actualValue = 0,
        checkedAt = null,
        isLate = false,
        isMakeup = false,
        makeupReason = null,
    )

    /** BOOLEAN 类型只认 0/1；量化类型不允许负数。 */
    private fun normalizeValue(targetType: TargetType, raw: Int): Int =
        if (targetType.isQuantified) raw.coerceAtLeast(0) else if (raw > 0) 1 else 0

    /**
     * 是否允许补卡。默认只能补昨天——补更久以前的意义不大，
     * 而且容易变成「攒着周末一起补」，那就失去按时段执行的意义了。
     */
    fun canMakeUp(
        taskDate: LocalDate,
        studyToday: LocalDate,
        makeupsUsedThisWeek: Int,
        makeupPerWeek: Int,
    ): MakeupCheck {
        if (taskDate >= studyToday) return MakeupCheck.NotNeeded
        if (taskDate < studyToday.minusDays(1)) return MakeupCheck.TooOld
        if (makeupsUsedThisWeek >= makeupPerWeek) return MakeupCheck.QuotaExhausted
        return MakeupCheck.Allowed
    }

    sealed interface MakeupCheck {
        /** 不是补卡场景（当天或未来的任务）。 */
        data object NotNeeded : MakeupCheck

        data object Allowed : MakeupCheck

        /** 超过可补范围（只能补昨天）。 */
        data object TooOld : MakeupCheck

        /** 本周补卡额度用尽。 */
        data object QuotaExhausted : MakeupCheck
    }
}
