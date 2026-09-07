package com.example.lixing.domain.rules

import com.example.lixing.domain.model.PointReason
import com.example.lixing.domain.model.TargetType
import java.time.LocalDate

/**
 * 积分规则。纯函数，全部可单测。
 *
 * 设计取舍：
 * - 迟到打卡打 5 折而不是不给分——迟了也比不做好，别把人逼到「反正晚了不如放弃」。
 * - 补卡给 3 折，压低但不为零，避免变成刷分手段。
 * - 惩罚可关（[penaltyEnabled]），因为扣分对一部分人是负激励。
 */
object PointRules {

    /** 按时打卡基础分。 */
    const val BASE_ON_TIME = 10

    /** 迟到折扣。 */
    const val LATE_MULTIPLIER = 0.5f

    /** 补卡折扣。 */
    const val MAKEUP_MULTIPLIER = 0.3f

    /** 关键任务倍数。 */
    const val KEYSTONE_MULTIPLIER = 2

    /** 量化任务超额完成的额外奖励。 */
    const val OVER_TARGET_BONUS = 5

    /** 全天满勤奖励。 */
    const val FULL_DAY_BONUS = 30

    /** 漏卡扣分（取绝对值，实际入账为负）。 */
    const val MISSED_PENALTY = 5

    /** 专注时长奖励：每满 25 分钟给 1 分，鼓励长块专注。 */
    const val FOCUS_MINUTES_PER_POINT = 25

    /** 连续记录里程碑奖励。 */
    val STREAK_BONUSES: Map<Int, Int> = mapOf(
        7 to 50,
        14 to 80,
        30 to 200,
        60 to 350,
        100 to 600,
        200 to 1200,
        365 to 2000,
    )

    /**
     * 单次打卡得分。
     *
     * @param actualValue 实际完成量
     * @param targetValue 目标量
     * @param targetType 量化方式
     * @param isKeystone 是否关键任务
     * @param isLate 是否时段结束后才打卡
     * @param isMakeup 是否隔日补卡
     */
    fun checkInPoints(
        actualValue: Int,
        targetValue: Int,
        targetType: TargetType,
        isKeystone: Boolean,
        isLate: Boolean,
        isMakeup: Boolean,
    ): Int {
        // 达成比例决定基础分是否打折：只做了一半就只拿一半基础分
        val ratio = completionRatio(actualValue, targetValue, targetType)
        if (ratio <= 0f) return 0

        var points = BASE_ON_TIME * ratio.coerceAtMost(1f)

        // 时效折扣。补卡与迟到取更严格的那个，不叠加
        val timeliness = when {
            isMakeup -> MAKEUP_MULTIPLIER
            isLate -> LATE_MULTIPLIER
            else -> 1f
        }
        points *= timeliness

        if (isKeystone) points *= KEYSTONE_MULTIPLIER

        return points.toInt().coerceAtLeast(1)
    }

    /** 超额奖励。仅量化任务、且实际量超过目标时给。 */
    fun overTargetBonus(
        actualValue: Int,
        targetValue: Int,
        targetType: TargetType,
        isKeystone: Boolean,
    ): Int {
        if (!targetType.isQuantified) return 0
        if (targetValue <= 0 || actualValue <= targetValue) return 0
        return if (isKeystone) OVER_TARGET_BONUS * KEYSTONE_MULTIPLIER else OVER_TARGET_BONUS
    }

    /** 漏卡扣分（返回负值）。[penaltyEnabled] 为 false 时返回 0。 */
    fun missedPenalty(penaltyEnabled: Boolean, isKeystone: Boolean): Int {
        if (!penaltyEnabled) return 0
        return -(if (isKeystone) MISSED_PENALTY * 2 else MISSED_PENALTY)
    }

    /** 专注时长换算积分。 */
    fun focusPoints(effectiveMinutes: Int): Int =
        effectiveMinutes / FOCUS_MINUTES_PER_POINT

    /**
     * 命中的连续记录里程碑奖励。没命中返回 0。
     * 只在 streak 恰好等于里程碑那天发，靠 dedupeKey 保证不重发。
     */
    fun streakBonus(streak: Int): Int = STREAK_BONUSES[streak] ?: 0

    fun streakReason(streak: Int): PointReason = when {
        streak >= 100 -> PointReason.STREAK_100
        streak >= 30 -> PointReason.STREAK_30
        else -> PointReason.STREAK_7
    }

    /**
     * 完成比例。BOOLEAN 类型只有 0 或 1。
     * 量化类型按 actual/target，上限不封顶（超额由 [overTargetBonus] 单独算）。
     */
    fun completionRatio(actualValue: Int, targetValue: Int, targetType: TargetType): Float {
        if (!targetType.isQuantified) return if (actualValue > 0) 1f else 0f
        if (targetValue <= 0) return if (actualValue > 0) 1f else 0f
        return (actualValue.toFloat() / targetValue).coerceAtLeast(0f)
    }

    // ---------- 幂等键 ----------
    // 一次性奖励必须带 key，防止结算重跑时重复入账。

    fun keyCheckIn(taskId: String): String = "checkin:$taskId"

    fun keyOverTarget(taskId: String): String = "over:$taskId"

    fun keyMissed(taskId: String): String = "missed:$taskId"

    fun keyFullDay(date: LocalDate): String = "fullday:${date.toEpochDay()}"

    fun keyStreak(streak: Int): String = "streak:$streak"

    fun keyFocus(sessionId: String): String = "focus:$sessionId"

    fun keyAchievement(code: String): String = "achv:$code"

    fun keyCommitment(id: String): String = "commit:$id"
}
