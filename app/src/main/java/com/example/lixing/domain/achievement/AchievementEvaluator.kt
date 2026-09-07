package com.example.lixing.domain.achievement

import com.example.lixing.data.local.entity.AchievementEntity
import com.example.lixing.domain.model.AchievementCondition

/**
 * 判定成就所需的全部指标快照。
 *
 * 一次性把指标收集好再判定，避免评估过程里反复查库。
 * 收集逻辑在 GamificationRepository 里，这里只做纯计算。
 */
data class AchievementSnapshot(
    val totalCheckIns: Int = 0,
    val hasAnyCheckIn: Boolean = false,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val fullDayCount: Int = 0,
    val maxSingleDayFocusMinutes: Int = 0,
    /** 各时段的按时打卡累计次数，key 为时段名。 */
    val onTimeCountBySlot: Map<String, Int> = emptyMap(),
    /** 各时段的当前连续完成天数，key 为时段名。 */
    val slotStreakByName: Map<String, Int> = emptyMap(),
    /** 各科目累计分钟数，key 为科目名。 */
    val minutesBySubject: Map<String, Int> = emptyMap(),
    /** 历史最佳的单月平均完成率（百分比整数）。 */
    val bestMonthRatePercent: Int = 0,
    /** 已完整走完的阶段数。 */
    val finishedPhaseCount: Int = 0,
    /** 距目标日剩余天数。为 null 表示没有有效计划。 */
    val daysToTarget: Int? = null,
    /** 断签后重新累积的连续天数（用于 COMEBACK）。 */
    val comebackStreak: Int = 0,
)

/** 一个刚解锁的成就。 */
data class UnlockedAchievement(
    val achievement: AchievementEntity,
    /** 达成时的进度描述，如「连续 32 天」。 */
    val detail: String,
)

/**
 * 成就判定。纯函数。
 *
 * 返回值分两部分：
 * - [Result.unlocked] 本次新解锁的（需要弹庆祝动画 + 发奖励积分）；
 * - [Result.progressUpdates] 进度有变化但还没解锁的（用来刷新卡片进度条）。
 */
object AchievementEvaluator {

    data class Result(
        val unlocked: List<UnlockedAchievement>,
        val progressUpdates: List<AchievementEntity>,
    )

    fun evaluate(
        achievements: List<AchievementEntity>,
        snapshot: AchievementSnapshot,
        unlockedAtProvider: () -> java.time.Instant = { java.time.Instant.now() },
    ): Result {
        val unlocked = ArrayList<UnlockedAchievement>()
        val progressUpdates = ArrayList<AchievementEntity>()

        for (item in achievements) {
            val progress = progressOf(item, snapshot)

            if (item.unlockedAt != null) {
                // 已解锁的只更新进度数字（比如「连续 100 天」解锁后仍想看到当前值）
                if (progress != item.progress) progressUpdates += item.copy(progress = progress)
                continue
            }

            if (progress >= item.threshold && item.threshold > 0 ||
                (item.condition == AchievementCondition.FIRST_CHECK_IN && snapshot.hasAnyCheckIn)
            ) {
                unlocked += UnlockedAchievement(
                    achievement = item.copy(
                        unlockedAt = unlockedAtProvider(),
                        progress = progress,
                        unlockedDetail = describe(item, progress),
                    ),
                    detail = describe(item, progress),
                )
            } else if (progress != item.progress) {
                progressUpdates += item.copy(progress = progress)
            }
        }

        return Result(unlocked, progressUpdates)
    }

    /** 当前进度值。语义随 condition 变化。 */
    fun progressOf(item: AchievementEntity, s: AchievementSnapshot): Int =
        when (item.condition) {
            AchievementCondition.FIRST_CHECK_IN -> if (s.hasAnyCheckIn) 1 else 0

            AchievementCondition.TOTAL_CHECK_INS -> s.totalCheckIns

            // 用历史最长而不是当前连续：拿到过就算拿到了，断签不该收回成就
            AchievementCondition.STREAK_DAYS -> maxOf(s.currentStreak, s.longestStreak)

            AchievementCondition.FULL_DAY_COUNT -> s.fullDayCount

            AchievementCondition.SINGLE_DAY_FOCUS_MINUTES -> s.maxSingleDayFocusMinutes

            AchievementCondition.SLOT_COUNT ->
                s.onTimeCountBySlot[item.extraKey.orEmpty()] ?: 0

            AchievementCondition.EARLY_BIRD_STREAK, AchievementCondition.NIGHT_OWL_FIX ->
                s.slotStreakByName[item.extraKey.orEmpty()] ?: 0

            AchievementCondition.SUBJECT_TOTAL_MINUTES ->
                s.minutesBySubject[item.extraKey.orEmpty()] ?: 0

            AchievementCondition.MONTH_COMPLETION_RATE -> s.bestMonthRatePercent

            AchievementCondition.PHASE_FINISHED -> s.finishedPhaseCount

            // 倒计时是「越小越接近」，转换成「已进入」的布尔量
            AchievementCondition.DDAY_MILESTONE -> {
                val days = s.daysToTarget
                if (days != null && days in 0..item.threshold) item.threshold else 0
            }

            AchievementCondition.COMEBACK -> s.comebackStreak
        }

    /** 解锁时的进度描述文案。 */
    fun describe(item: AchievementEntity, progress: Int): String = when (item.condition) {
        AchievementCondition.FIRST_CHECK_IN -> "迈出第一步"
        AchievementCondition.TOTAL_CHECK_INS -> "累计打卡 $progress 次"
        AchievementCondition.STREAK_DAYS -> "连续 $progress 天"
        AchievementCondition.FULL_DAY_COUNT -> "满勤 $progress 天"
        AchievementCondition.SINGLE_DAY_FOCUS_MINUTES -> "单日专注 ${progress / 60} 小时 ${progress % 60} 分"
        AchievementCondition.SLOT_COUNT -> "${item.extraKey} 按时 $progress 次"
        AchievementCondition.EARLY_BIRD_STREAK -> "连续 $progress 天完成${item.extraKey}"
        AchievementCondition.NIGHT_OWL_FIX -> "连续 $progress 天完成${item.extraKey}复盘"
        AchievementCondition.SUBJECT_TOTAL_MINUTES -> "${item.extraKey} 累计 ${progress / 60} 小时"
        AchievementCondition.MONTH_COMPLETION_RATE -> "月完成率 $progress%"
        AchievementCondition.PHASE_FINISHED -> "走完 $progress 个阶段"
        AchievementCondition.DDAY_MILESTONE -> "进入考前 ${item.threshold} 天"
        AchievementCondition.COMEBACK -> "重启后连续 $progress 天"
    }
}
