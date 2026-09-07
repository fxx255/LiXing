package com.example.lixing.domain.usecase

import com.example.lixing.data.local.entity.DayRecordEntity
import com.example.lixing.data.prefs.UserPreferences
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.repository.GamificationRepository
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.domain.achievement.UnlockedAchievement
import com.example.lixing.domain.model.PointReason
import com.example.lixing.domain.rules.PointRules
import com.example.lixing.domain.settle.CommitmentOutcome
import com.example.lixing.domain.settle.DayStats
import com.example.lixing.domain.settle.DayStatsCalculator
import com.example.lixing.domain.settle.StreakCalculator
import com.example.lixing.domain.time.StudyClock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** 启动例行工作的结果：补结算的日子 + 到期结算的承诺。 */
data class StartupSettlement(
    val days: List<SettleResult>,
    val commitments: List<CommitmentOutcome>,
)

/** 一天结算的结果，用于次日提示「昨天怎么样」。 */
data class SettleResult(
    val date: LocalDate,
    val stats: DayStats,
    val achieved: Boolean,
    val streakAfter: Int,
    val streakBroken: Boolean,
    val rescueUsed: Boolean,
    val previousStreak: Int,
    val penaltyPoints: Int,
    val newAchievements: List<UnlockedAchievement>,
)

/**
 * 当天结算。
 *
 * 做四件事：
 * 1. 把仍是 PENDING 的任务标成 MISSED，并按设置扣分；
 * 2. 算出当天最终统计，写入 day_record 并标记 is_settled；
 * 3. 推进连续记录（含补救卡逻辑）与里程碑奖励；
 * 4. 判定成就。
 *
 * 幂等：已结算的日子直接跳过；积分靠 dedupeKey 防重复。
 * 调用点有两个——每天 00:05 的 Worker，以及 App 启动时的补结算。
 */
@Singleton
class SettleDayUseCase @Inject constructor(
    private val taskRepository: TaskRepository,
    private val planRepository: PlanRepository,
    private val gamification: GamificationRepository,
    private val prefsRepository: UserPreferencesRepository,
) {
    /** 打卡协程、启动结算和 Worker 可能同时推进同一行连续记录，必须串行化读改写。 */
    private val streakMutex = Mutex()

    /**
     * 结算某一天。
     *
     * @param allowRescue 是否允许用补救卡。补很久以前的账时传 false，
     *        免得一次把额度花在早已过去的日子上。
     */
    suspend fun settle(
        date: LocalDate,
        prefs: UserPreferences? = null,
        allowRescue: Boolean = true,
    ): SettleResult? {
        val existing = taskRepository.getDayRecord(date)
        if (existing?.isSettled == true) return null

        val settings = prefs ?: prefsRepository.current()

        val isDayOff = existing?.isDayOff == true

        // 1. 漏卡标记与扣分。请假日不标漏卡
        var penaltyTotal = 0
        if (!isDayOff) {
            val pending = taskRepository.getTasksOfDay(date)
                .filter { it.status == com.example.lixing.domain.model.TaskStatus.PENDING }

            if (pending.isNotEmpty()) {
                pending.groupBy { it.timeSlotId }.values.forEach { slotTasks ->
                    val required = slotTasks.first().slotRequiredTaskCount.coerceAtLeast(0)
                    val alreadyDone = taskRepository.getTasksOfSlot(date, slotTasks.first().timeSlotId)
                        .count { it.status.isEngaged }
                    var remaining = if (required > 0) (required - alreadyDone).coerceAtLeast(0) else Int.MAX_VALUE
                    slotTasks.forEach { task ->
                        val status = if (remaining > 0) {
                            if (remaining != Int.MAX_VALUE) remaining--
                            com.example.lixing.domain.model.TaskStatus.MISSED
                        } else com.example.lixing.domain.model.TaskStatus.SKIPPED
                        taskRepository.updateTask(task.copy(status = status))
                        if (status == com.example.lixing.domain.model.TaskStatus.MISSED && settings.penaltyEnabled) {
                            val penalty = PointRules.missedPenalty(true, task.isKeystone)
                            penaltyTotal += penalty
                            gamification.award(
                                date = date,
                                delta = penalty,
                                reason = PointReason.MISSED_PENALTY,
                                dailyTaskId = task.id,
                                detail = "漏卡：${task.title}",
                                dedupeKey = PointRules.keyMissed(task.id),
                            )
                        }
                    }
                }

                if (false && settings.penaltyEnabled) {
                    if (false) pending.forEach { task ->
                        val penalty = PointRules.missedPenalty(true, task.isKeystone)
                        penaltyTotal += penalty
                        gamification.award(
                            date = date,
                            delta = penalty,
                            reason = PointReason.MISSED_PENALTY,
                            dailyTaskId = task.id,
                            detail = "漏卡：${task.title}",
                            dedupeKey = PointRules.keyMissed(task.id),
                        )
                    }
                }
            }
        }

        // 2. 最终统计
        val tasks = taskRepository.getTasksOfDay(date)
        val stats = DayStatsCalculator.calculate(tasks)
        val achieved = !isDayOff && stats.isAchieved(settings.achieveThreshold)

        // 满勤奖励（可能在打卡时已发过，dedupeKey 会挡掉重复）
        if (stats.isFullDay && !isDayOff) {
            gamification.award(
                date = date,
                delta = PointRules.FULL_DAY_BONUS,
                reason = PointReason.FULL_DAY_BONUS,
                detail = "全天满勤",
                dedupeKey = PointRules.keyFullDay(date),
            )
        }

        // 3. 连续记录
        val update = streakMutex.withLock {
            val before = gamification.getStreak()
            val result = StreakCalculator.advance(
                current = before,
                date = date,
                achieved = achieved,
                isDayOff = isDayOff,
                rescueCardsPerMonth = settings.rescueCardsPerMonth,
                allowRescue = allowRescue,
            )
            // 请假日要顺延 lastAchievedDate，否则请假后第二天会被判成不连续
            val finalStreak = if (isDayOff) {
                StreakCalculator.carryOverDayOff(result.streak, date)
            } else {
                result.streak
            }
            gamification.saveStreak(finalStreak)
            result
        }

        // 连续里程碑奖励
        val milestone = PointRules.streakBonus(update.currentStreak)
        if (milestone > 0) {
            gamification.award(
                date = date,
                delta = milestone,
                reason = PointRules.streakReason(update.currentStreak),
                detail = "连续 ${update.currentStreak} 天",
                dedupeKey = PointRules.keyStreak(update.currentStreak),
            )
        }

        // 4. 写入结算结果
        val points = gamification.getPointsOfDay(date)
        taskRepository.upsertDayRecord(
            (existing ?: DayRecordEntity(date = date)).copy(
                totalTasks = stats.totalTasks,
                doneTasks = stats.doneTasks,
                partialTasks = stats.partialTasks,
                missedTasks = stats.missedTasks,
                skippedTasks = stats.skippedTasks,
                completionRate = stats.completionRate,
                focusMinutes = stats.focusMinutes,
                pointsEarned = points,
                isAchieved = achieved,
                isFullDay = stats.isFullDay && !isDayOff,
                usedRescueCard = update.rescueUsed,
                isSettled = true,
            ),
        )

        val achievements = evaluateAchievements(date)

        return SettleResult(
            date = date,
            stats = stats,
            achieved = achieved,
            streakAfter = update.currentStreak,
            streakBroken = update.broken,
            rescueUsed = update.rescueUsed,
            previousStreak = update.previousStreak,
            penaltyPoints = penaltyTotal,
            newAchievements = achievements,
        )
    }

    /**
     * 补结算：把今天之前所有没结算的日子按顺序结掉。
     * 用户几天没开 App 时，启动就会走到这里。
     *
     * @return 每一天的结算结果，按日期升序。
     */
    suspend fun settleOverdue(today: LocalDate): List<SettleResult> {
        val prefs = prefsRepository.current()
        val pending = taskRepository.getUnsettledBefore(today)
        val results = ArrayList<SettleResult>(pending.size)

        pending.sortedBy { it.date }.forEach { record ->
            // 只有昨天允许用补救卡——更早的日子已经无从挽回
            val allowRescue = record.date == today.minusDays(1)
            settle(record.date, prefs, allowRescue)?.let { results += it }
        }
        return results
    }

    /** 修正旧版本同一天被实时推进和次日结算两次而产生的连续天数漂移。 */
    private suspend fun repairCurrentStreak(today: LocalDate) = streakMutex.withLock {
        val records = taskRepository.getDayRecordsBetween(today.minusDays(399), today)
        if (records.isEmpty()) return@withLock
        val rebuilt = StreakCalculator.rebuildCurrent(records, today)
        val stored = gamification.getStreak()
        if (stored.currentStreak != rebuilt.days || stored.lastAchievedDate != rebuilt.lastContinuousDate) {
            gamification.saveStreak(
                stored.copy(
                    currentStreak = rebuilt.days,
                    longestStreak = maxOf(stored.longestStreak, rebuilt.days),
                    lastAchievedDate = rebuilt.lastContinuousDate,
                ),
            )
        }
    }

    /**
     * 当天实时推进连续记录（不做结算、不标漏卡）。
     * 打卡后立刻调用，好让首页的火焰数字马上更新。
     */
    suspend fun advanceStreakForToday(today: LocalDate, prefs: UserPreferences) = streakMutex.withLock {
        val record = taskRepository.getDayRecord(today) ?: return@withLock
        if (record.isDayOff) return@withLock

        val stats = taskRepository.computeStats(today)
        val achieved = stats.isAchieved(prefs.achieveThreshold)
        val streak = gamification.getStreak()

        // 已经把今天记为达成过，就不重复 +1
        if (streak.lastAchievedDate == today) {
            if (!achieved) {
                // 完成量被改回阈值以下：回退今天的达成
                gamification.saveStreak(
                    streak.copy(
                        currentStreak = (streak.currentStreak - 1).coerceAtLeast(0),
                        lastAchievedDate = today.minusDays(1).takeIf { streak.currentStreak > 1 },
                        totalAchievedDays = (streak.totalAchievedDays - 1).coerceAtLeast(0),
                    ),
                )
            }
            return@withLock
        }

        if (!achieved) return@withLock

        val update = StreakCalculator.advance(
            current = streak,
            date = today,
            achieved = true,
            isDayOff = false,
            rescueCardsPerMonth = prefs.rescueCardsPerMonth,
            allowRescue = false,
        )
        gamification.saveStreak(update.streak)

        val milestone = PointRules.streakBonus(update.currentStreak)
        if (milestone > 0) {
            gamification.award(
                date = today,
                delta = milestone,
                reason = PointRules.streakReason(update.currentStreak),
                detail = "连续 ${update.currentStreak} 天",
                dedupeKey = PointRules.keyStreak(update.currentStreak),
            )
        }
    }

    /** 收集当前计划信息并判定成就。 */
    suspend fun evaluateAchievements(today: LocalDate): List<UnlockedAchievement> {
        val plan = planRepository.getActivePlan()
        val daysToTarget = plan?.let {
            ChronoUnit.DAYS.between(today, it.targetDate).toInt()
        }
        val phases = plan?.let { planRepository.getPhases(it.id) } ?: emptyList()
        val finishedPhases = phases.count { it.endDate < today }
        val subjects = plan?.let { planRepository.getSubjects(it.id) }?.map { it.name } ?: emptyList()
        val slots = plan?.let { planRepository.getTimeSlots(it.id) }?.map { it.name } ?: emptyList()

        return gamification.evaluateAchievements(
            today = today,
            daysToTarget = daysToTarget,
            finishedPhaseCount = finishedPhases,
            subjectNames = subjects,
            slotNames = slots,
        )
    }

    /**
     * 启动时的例行工作：物化最近几天 → 补结算 → 到期承诺结算。
     * 返回补结算的结果，UI 可以据此提示「昨天漏了 3 项」「承诺达成」。
     *
     * 承诺结算必须排在 [settleOverdue] 之后：承诺最后几天的 day_record
     * 要等日结算写完才有终值，顺序反了会把未结算的尾巴当成 0，好的承诺被判失败。
     * 这个顺序在这里保证，调用方不需要关心。
     */
    suspend fun onAppStart(clock: StudyClock): StartupSettlement {
        val today = clock.today()
        gamification.ensureInitialized(today)
        taskRepository.materializeRecent(today)
        // 物化之后去重：清掉历史遗留的同名重复任务（换计划后 template_id 变了，
        // (date, template_id) 唯一索引挡不住跨批次的同名项）
        taskRepository.dedupeTasks()
        val days = settleOverdue(today)
        repairCurrentStreak(today)
        return StartupSettlement(
            days = days,
            commitments = gamification.settleExpiredCommitments(today),
        )
    }
}
