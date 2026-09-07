package com.example.lixing.domain.usecase

import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.repository.GamificationRepository
import com.example.lixing.data.repository.LevelChange
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.domain.achievement.UnlockedAchievement
import com.example.lixing.domain.model.PointReason
import com.example.lixing.domain.rules.PointRules
import com.example.lixing.domain.settle.CheckInResolver
import com.example.lixing.domain.settle.DayStatsCalculator
import com.example.lixing.domain.settle.StreakCalculator
import com.example.lixing.domain.time.StudyClock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** 打卡结果。UI 靠这个决定弹什么反馈。 */
sealed interface CheckInResult {
    /**
     * 打卡成功。
     * @param pointsGained 本次获得积分（含超额与满勤奖励）
     * @param encouragement 随机激励文案
     */
    data class Success(
        val task: DailyTaskEntity,
        val pointsGained: Int,
        val isLate: Boolean,
        val isMakeup: Boolean,
        val fullDayAchieved: Boolean,
        val levelChange: LevelChange?,
        val newAchievements: List<UnlockedAchievement>,
        val encouragement: String,
    ) : CheckInResult

    /** 补卡被拒。 */
    data class MakeupRejected(val reason: Reason) : CheckInResult {
        enum class Reason { TOO_OLD, QUOTA_EXHAUSTED }
    }

    data object TaskNotFound : CheckInResult
}

/**
 * 打卡编排。
 *
 * 一次打卡要做的事：
 * 1. 判定时效与状态（[CheckInResolver]）；
 * 2. 写任务、发积分（带幂等键，重复打卡不会重复发分）；
 * 3. 如果打成满勤，补发满勤奖励；
 * 4. 当天实时推进连续记录（让首页的火焰数字立刻更新，不必等到次日结算）；
 * 5. 判定成就。
 *
 * 放在 UseCase 而不是 Repository：它跨了 4 个仓库，属于业务编排。
 */
@Singleton
class CheckInUseCase @Inject constructor(
    private val taskRepository: TaskRepository,
    private val planRepository: PlanRepository,
    private val gamification: GamificationRepository,
    private val prefsRepository: UserPreferencesRepository,
    private val settleDay: SettleDayUseCase,
) {
    suspend operator fun invoke(
        taskId: String,
        actualValue: Int,
        clock: StudyClock,
        makeupReason: String? = null,
        note: String? = null,
        photo: String? = null,
    ): CheckInResult {
        val task = taskRepository.getTask(taskId) ?: return CheckInResult.TaskNotFound
        val prefs = prefsRepository.current()
        val now = clock.nowDateTime()
        val today = clock.today()

        // 补卡额度检查
        val streakRow = gamification.getStreak()
        val weekKey = StreakCalculator.weekKeyOf(today)
        val usedThisWeek =
            if (streakRow.makeupWeekKey == weekKey) streakRow.makeupsUsedThisWeek else 0

        when (CheckInResolver.canMakeUp(task.date, today, usedThisWeek, prefs.makeupPerWeek)) {
            CheckInResolver.MakeupCheck.TooOld ->
                return CheckInResult.MakeupRejected(CheckInResult.MakeupRejected.Reason.TOO_OLD)

            CheckInResolver.MakeupCheck.QuotaExhausted ->
                return CheckInResult.MakeupRejected(CheckInResult.MakeupRejected.Reason.QUOTA_EXHAUSTED)

            else -> Unit
        }

        val outcome = CheckInResolver.resolve(
            task = task,
            actualValue = actualValue,
            now = now,
            studyToday = today,
            makeupReason = makeupReason,
        )

        // 完成量归零 = 撤销打卡，走单独路径（清掉打卡详情）
        if (outcome.status == com.example.lixing.domain.model.TaskStatus.PENDING) {
            taskRepository.updateTask(outcome.task.copy(checkinNote = null, checkinPhoto = null))
            gamification.revokeTaskPoints(taskId)
            refreshDayRecord(task.date, prefs.achieveThreshold)
            return CheckInResult.Success(
                task = outcome.task,
                pointsGained = 0,
                isLate = false,
                isMakeup = false,
                fullDayAchieved = false,
                levelChange = null,
                newAchievements = emptyList(),
                encouragement = "已取消打卡",
            )
        }

        // 附带打卡详情。note 三态：null=不动（如同步调用），空白=删除，有值=更新。
        // photo 同理：null=不动，否则用新值（空列表编码后为 null 即删除）。
        val detailedTask = outcome.task.copy(
            checkinNote = when {
                note == null -> outcome.task.checkinNote
                note.isBlank() -> null
                else -> note
            },
            checkinPhoto = when {
                photo == null -> outcome.task.checkinPhoto
                photo.isBlank() -> null
                else -> photo
            },
        )
        taskRepository.updateTask(detailedTask)

        // 重新打卡时先清掉旧流水，避免「改了完成量后积分只增不减」
        if (task.status.isEngaged) gamification.revokeTaskPoints(taskId)

        var levelChange = gamification.award(
            date = task.date,
            delta = outcome.basePoints,
            reason = when {
                outcome.isMakeup -> PointReason.CHECK_IN_MAKEUP
                outcome.isLate -> PointReason.CHECK_IN_LATE
                else -> PointReason.CHECK_IN_ON_TIME
            },
            dailyTaskId = taskId,
            detail = task.title,
            dedupeKey = PointRules.keyCheckIn(taskId),
        )

        if (outcome.bonusPoints > 0) {
            val bonusChange = gamification.award(
                date = task.date,
                delta = outcome.bonusPoints,
                reason = PointReason.OVER_TARGET,
                dailyTaskId = taskId,
                detail = "${task.title} 超额完成",
                dedupeKey = PointRules.keyOverTarget(taskId),
            )
            if (bonusChange.leveledUp) levelChange = bonusChange
        }

        gamification.incrementCheckIns()

        // 记录补卡用量
        if (outcome.isMakeup) {
            gamification.saveStreak(
                streakRow.copy(
                    makeupsUsedThisWeek = usedThisWeek + 1,
                    makeupWeekKey = weekKey,
                ),
            )
        }

        // 满勤检查 + 当天连续记录推进
        val stats = taskRepository.computeStats(task.date)
        var fullDayAchieved = false
        if (stats.isFullDay) {
            val fullDayChange = gamification.award(
                date = task.date,
                delta = PointRules.FULL_DAY_BONUS,
                reason = PointReason.FULL_DAY_BONUS,
                detail = "全天满勤",
                dedupeKey = PointRules.keyFullDay(task.date),
            )
            // dedupeKey 挡掉重复时 leveledUp 为 false，这里用它判断是否首次达成
            fullDayAchieved = fullDayChange.newLevel > 0 || fullDayChange.leveledUp
            if (fullDayChange.leveledUp) levelChange = fullDayChange
        }

        refreshDayRecord(task.date, prefs.achieveThreshold)

        // 只有给今天打卡才实时推进连续记录；补昨天的卡由结算逻辑负责
        if (task.date == today) {
            settleDay.advanceStreakForToday(today, prefs)
        }

        val achievements = settleDay.evaluateAchievements(today)

        return CheckInResult.Success(
            task = detailedTask,
            pointsGained = outcome.totalPoints,
            isLate = outcome.isLate,
            isMakeup = outcome.isMakeup,
            fullDayAchieved = fullDayAchieved,
            levelChange = levelChange.takeIf { it.leveledUp },
            newAchievements = achievements,
            encouragement = Encouragements.pick(
                isLate = outcome.isLate,
                isMakeup = outcome.isMakeup,
                isFullDay = stats.isFullDay,
                streak = gamification.getStreak().currentStreak,
            ),
        )
    }

    /** 撤销打卡。 */
    suspend fun revoke(taskId: String, clock: StudyClock) {
        val task = taskRepository.getTask(taskId) ?: return
        val prefs = prefsRepository.current()
        taskRepository.updateTask(CheckInResolver.revoke(task))
        gamification.revokeTaskPoints(taskId)
        refreshDayRecord(task.date, prefs.achieveThreshold)
        if (task.date == clock.today()) {
            settleDay.advanceStreakForToday(clock.today(), prefs)
        }
    }

    /** 把最新统计写回 day_record（不做结算，只更新数字）。 */
    private suspend fun refreshDayRecord(date: LocalDate, threshold: Float) {
        val tasks = taskRepository.getTasksOfDay(date)
        val stats = DayStatsCalculator.calculate(tasks)
        val existing = taskRepository.getDayRecord(date)
        val points = gamification.getPointsOfDay(date)

        taskRepository.upsertDayRecord(
            (existing ?: com.example.lixing.data.local.entity.DayRecordEntity(date = date)).copy(
                totalTasks = stats.totalTasks,
                doneTasks = stats.doneTasks,
                partialTasks = stats.partialTasks,
                missedTasks = stats.missedTasks,
                skippedTasks = stats.skippedTasks,
                completionRate = stats.completionRate,
                focusMinutes = stats.focusMinutes,
                pointsEarned = points,
                isAchieved = stats.isAchieved(threshold),
                isFullDay = stats.isFullDay,
            ),
        )
    }
}
