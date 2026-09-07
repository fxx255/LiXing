package com.example.lixing.data.repository

import com.example.lixing.data.local.dao.DailyTaskDao
import com.example.lixing.data.local.dao.DayRecordDao
import com.example.lixing.data.local.dao.FocusSessionDao
import com.example.lixing.data.local.dao.GamificationDao
import com.example.lixing.data.local.entity.AchievementEntity
import com.example.lixing.data.local.entity.CheckInStreakEntity
import com.example.lixing.data.local.entity.CommitmentEntity
import com.example.lixing.data.local.entity.PointLedgerEntity
import com.example.lixing.data.local.entity.UserProfileEntity
import com.example.lixing.di.IoDispatcher
import com.example.lixing.domain.achievement.AchievementCatalog
import com.example.lixing.domain.achievement.AchievementEvaluator
import com.example.lixing.domain.achievement.AchievementSnapshot
import com.example.lixing.domain.achievement.UnlockedAchievement
import com.example.lixing.domain.model.CommitmentStatus
import com.example.lixing.domain.model.CommitmentMetric
import com.example.lixing.domain.model.PointReason
import com.example.lixing.domain.rules.LevelRules
import com.example.lixing.domain.rules.PointRules
import com.example.lixing.domain.settle.CommitmentOutcome
import com.example.lixing.domain.settle.CommitmentProgress
import com.example.lixing.domain.settle.CommitmentSettler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** 一次积分入账后的等级变化，用于触发升级弹窗。 */
data class LevelChange(
    val oldLevel: Int,
    val newLevel: Int,
    val newTitle: String,
    val unlockedNewTitle: Boolean,
) {
    val leveledUp: Boolean get() = newLevel > oldLevel
}

/**
 * 激励系统门面：积分、等级、连续记录、成就、档案。
 *
 * 积分入账统一走 [award]，它同时负责：写流水（带幂等键）→ 累加档案总分 → 检测升级。
 * 所有一次性奖励都必须带 dedupeKey，否则结算重跑会重复发分。
 */
@Singleton
class GamificationRepository @Inject constructor(
    private val dao: GamificationDao,
    private val dayRecordDao: DayRecordDao,
    private val dailyTaskDao: DailyTaskDao,
    private val focusSessionDao: FocusSessionDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    // ---------------- 读 ----------------

    val profile: Flow<UserProfileEntity?> = dao.observeProfile()
    val streak: Flow<CheckInStreakEntity?> = dao.observeStreak()
    val achievements: Flow<List<AchievementEntity>> = dao.observeAchievements()
    val recentLedger: Flow<List<PointLedgerEntity>> = dao.observeRecentLedger()

    suspend fun getProfile(): UserProfileEntity? = withContext(io) { dao.getProfile() }

    suspend fun getStreak(): CheckInStreakEntity = withContext(io) {
        dao.getStreak() ?: CheckInStreakEntity().also { dao.upsertStreak(it) }
    }

    suspend fun getPointsOfDay(date: LocalDate): Int = withContext(io) { dao.getPointsOfDay(date) }

    suspend fun getPointsBetween(from: LocalDate, to: LocalDate): Int =
        withContext(io) { dao.getPointsBetween(from, to) }

    suspend fun getLedgerOfDay(date: LocalDate): List<PointLedgerEntity> =
        withContext(io) { dao.getLedgerOfDay(date) }

    // ---------------- 初始化 ----------------

    /**
     * 首次启动的预置：档案、连续记录、成就目录。
     * 幂等——已存在的不覆盖，所以每次启动都可以放心调用（新版本追加的成就也会补进去）。
     */
    suspend fun ensureInitialized(today: LocalDate) = withContext(io) {
        if (dao.getProfile() == null) {
            dao.upsertProfile(UserProfileEntity(joinedDate = today))
        }
        if (dao.getStreak() == null) {
            dao.upsertStreak(CheckInStreakEntity())
        }
        // code 有唯一索引，IGNORE 策略保证已解锁的成就不被重置
        dao.insertAchievementsIgnore(AchievementCatalog.all())
        repairPrematureMonthAchievements(today)
    }

    /**
     * 旧版本曾把正在进行的本月按“已有几天”求平均，导致月初提前解锁。
     * 只在没有任何已结束自然月真正达标时回滚这两个成就及误发积分。
     */
    private suspend fun repairPrematureMonthAchievements(today: LocalDate) {
        val bestCompletedMonthRate = bestMonthRate(today)
        val invalid = dao.getAchievements().filter {
            it.condition == com.example.lixing.domain.model.AchievementCondition.MONTH_COMPLETION_RATE &&
                it.unlockedAt != null && bestCompletedMonthRate < it.threshold
        }
        if (invalid.isEmpty()) return

        dao.updateAchievements(
            invalid.map {
                it.copy(
                    unlockedAt = null,
                    unlockedDetail = null,
                    progress = bestCompletedMonthRate,
                )
            },
        )
        dao.deleteLedgerByDedupeKeys(invalid.map { PointRules.keyAchievement(it.code) })
        applyPointsToProfile(0)
    }

    // ---------------- 积分 ----------------

    /**
     * 入账并返回等级变化。
     *
     * @param dedupeKey 幂等键。非空时重复调用只会入账一次。
     * @return 等级变化。delta 被去重挡掉时返回等级不变的结果。
     */
    suspend fun award(
        date: LocalDate,
        delta: Int,
        reason: PointReason,
        dailyTaskId: String? = null,
        detail: String = "",
        dedupeKey: String? = null,
    ): LevelChange = withContext(io) {
        if (delta == 0) return@withContext noChange()

        val rowId = dao.insertLedger(
            PointLedgerEntity(
                date = date,
                delta = delta,
                reason = reason,
                dailyTaskId = dailyTaskId,
                detail = detail,
                dedupeKey = dedupeKey,
            ),
        )
        // -1 表示撞了 dedupe_key，已经发过，不重复累加
        if (rowId == -1L) return@withContext noChange()

        applyPointsToProfile(delta)
    }

    /** 批量入账，只在最后算一次等级变化。 */
    suspend fun awardAll(entries: List<PointLedgerEntity>): LevelChange = withContext(io) {
        if (entries.isEmpty()) return@withContext noChange()
        val ids = dao.insertLedgers(entries)
        val accepted = entries.filterIndexed { i, _ -> ids.getOrNull(i)?.let { it != -1L } == true }
        val total = accepted.sumOf { it.delta }
        if (total == 0) noChange() else applyPointsToProfile(total)
    }

    /**
     * 撤销某任务的全部积分（取消打卡时用）。
     * 按任务删流水后重算总分，比逐条回滚更不容易出错——流水本身就是唯一真相。
     */
    suspend fun revokeTaskPoints(taskId: String) = withContext(io) {
        val profile = dao.getProfile() ?: return@withContext
        dao.deleteLedgerOfTask(taskId)
        val recomputed = dao.getTotalPoints().coerceAtLeast(0)
        val newLevel = LevelRules.levelOf(recomputed)
        dao.upsertProfile(
            profile.copy(
                totalPoints = recomputed,
                level = newLevel,
                title = LevelRules.titleOf(newLevel),
            ),
        )
    }

    private suspend fun applyPointsToProfile(delta: Int): LevelChange {
        val profile = dao.getProfile() ?: UserProfileEntity(joinedDate = LocalDate.now())
        val oldLevel = profile.level
        // 以流水为准重算总分，不再增量累加——任何漂移/丢分都会在下次入账时自愈
        val newTotal = dao.getTotalPoints().coerceAtLeast(0)
        val newLevel = LevelRules.levelOf(newTotal)
        val newTitle = LevelRules.titleOf(newLevel)

        dao.upsertProfile(
            profile.copy(
                totalPoints = newTotal,
                level = newLevel,
                title = newTitle,
            ),
        )

        return LevelChange(
            oldLevel = oldLevel,
            newLevel = newLevel,
            newTitle = newTitle,
            unlockedNewTitle = LevelRules.unlockedNewTitle(oldLevel, newLevel),
        )
    }

    private fun noChange(): LevelChange = LevelChange(0, 0, "", false)

    // ---------------- 连续记录 ----------------

    suspend fun saveStreak(streak: CheckInStreakEntity) = withContext(io) { dao.upsertStreak(streak) }

    /** 打卡计数 +1（档案统计用）。 */
    suspend fun incrementCheckIns(count: Int = 1) = withContext(io) {
        val p = dao.getProfile() ?: return@withContext
        dao.upsertProfile(p.copy(totalCheckIns = p.totalCheckIns + count))
    }

    /** 累加专注时长到档案。 */
    suspend fun addFocusMinutes(minutes: Int) = withContext(io) {
        val p = dao.getProfile() ?: return@withContext
        dao.upsertProfile(p.copy(totalFocusMinutes = p.totalFocusMinutes + minutes))
    }

    // ---------------- 成就 ----------------

    /**
     * 收集指标并判定成就。返回本次新解锁的列表（调用方负责弹庆祝动画）。
     * 奖励积分在这里一并发掉，带 dedupeKey 防重复。
     */
    suspend fun evaluateAchievements(
        today: LocalDate,
        daysToTarget: Int?,
        finishedPhaseCount: Int,
        subjectNames: List<String>,
        slotNames: List<String>,
    ): List<UnlockedAchievement> = withContext(io) {
        val snapshot = collectSnapshot(today, daysToTarget, finishedPhaseCount, subjectNames, slotNames)
        val all = dao.getAchievements()
        val result = AchievementEvaluator.evaluate(all, snapshot)

        if (result.progressUpdates.isNotEmpty()) {
            dao.updateAchievements(result.progressUpdates)
        }
        if (result.unlocked.isNotEmpty()) {
            dao.updateAchievements(result.unlocked.map { it.achievement })
            // 成就奖励积分
            result.unlocked.forEach { u ->
                award(
                    date = today,
                    delta = u.achievement.rewardPoints,
                    reason = PointReason.ACHIEVEMENT,
                    detail = "成就「${u.achievement.title}」",
                    dedupeKey = PointRules.keyAchievement(u.achievement.code),
                )
            }
        }
        result.unlocked
    }

    private suspend fun collectSnapshot(
        today: LocalDate,
        daysToTarget: Int?,
        finishedPhaseCount: Int,
        subjectNames: List<String>,
        slotNames: List<String>,
    ): AchievementSnapshot {
        val streakRow = dao.getStreak() ?: CheckInStreakEntity()

        val onTimeBySlot = slotNames.associateWith { dailyTaskDao.countOnTimeInSlot(it) }
        val minutesBySubject = subjectNames.associateWith {
            dailyTaskDao.getTotalMinutesOfSubject(it)
        }
        // 各时段的当前连续天数：从今天往回找，直到某天该时段没完成
        val slotStreak = slotNames.associateWith { slotStreakOf(it, today) }

        return AchievementSnapshot(
            totalCheckIns = dailyTaskDao.countAllCheckIns(),
            hasAnyCheckIn = dailyTaskDao.getFirstCheckInAt() != null,
            currentStreak = streakRow.currentStreak,
            longestStreak = streakRow.longestStreak,
            fullDayCount = dayRecordDao.countFullDays(),
            maxSingleDayFocusMinutes = dayRecordDao.getMaxSingleDayFocusMinutes(),
            onTimeCountBySlot = onTimeBySlot,
            slotStreakByName = slotStreak,
            minutesBySubject = minutesBySubject,
            bestMonthRatePercent = bestMonthRate(today),
            finishedPhaseCount = finishedPhaseCount,
            daysToTarget = daysToTarget,
            comebackStreak = if (streakRow.longestStreak > streakRow.currentStreak) {
                streakRow.currentStreak
            } else {
                0
            },
        )
    }

    /** 某时段的当前连续完成天数。上限 400 天，避免极端情况扫全表。 */
    private suspend fun slotStreakOf(slotName: String, today: LocalDate): Int {
        var streak = 0
        var cursor = today
        repeat(400) {
            val tasks = dailyTaskDao.getTasksOfDay(cursor).filter { it.slotName == slotName }
            when {
                // 该时段当天没排任务：不算断，继续往前找
                tasks.isEmpty() -> Unit
                tasks.all { it.status == com.example.lixing.domain.model.TaskStatus.DONE } -> streak++
                // 今天还没到点、任务仍待做，不算断
                cursor == today && tasks.any { it.status == com.example.lixing.domain.model.TaskStatus.PENDING } -> Unit
                else -> return streak
            }
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    /** 历史最佳完整自然月完成率（百分比）。本月尚未结束，绝不参与。 */
    private suspend fun bestMonthRate(today: LocalDate): Int {
        var best = 0
        completedMonthsBefore(today, 18).forEach { month ->
            val from = month.atDay(1)
            val to = month.atEndOfMonth()
            val rate = dayRecordDao.getAverageCompletionRate(from, to)
            if (rate != null) best = maxOf(best, (rate * 100).toInt())
        }
        return best
    }

    // ---------------- 承诺 ----------------

    val commitments: Flow<List<CommitmentEntity>> = dao.observeCommitments()
    val activeCommitment: Flow<CommitmentEntity?> = dao.observeActiveCommitment()

    suspend fun saveCommitment(commitment: CommitmentEntity): String =
        withContext(io) {
            dao.upsertCommitment(commitment)
            commitment.id
        }

    suspend fun deleteCommitment(id: String) = withContext(io) { dao.deleteCommitment(id) }

    /** 主动放弃。不发奖励也不扣分，只是如实记下没走完。 */
    suspend fun abandonCommitment(commitment: CommitmentEntity, today: LocalDate) = withContext(io) {
        dao.upsertCommitment(
            commitment.copy(
                status = CommitmentStatus.ABANDONED,
                settledDate = today,
            ),
        )
    }

    /**
     * 进行中承诺的实时进度。区间取 startDate ~ min(today, endDate)，
     * 只统计已经过去的日子，不让未来的空白日拉低当前完成率。
     */
    suspend fun commitmentProgress(
        commitment: CommitmentEntity,
        today: LocalDate,
    ): CommitmentProgress = withContext(io) {
        val until = minOf(today, commitment.endDate)
        val value = if (until < commitment.startDate) null
        else commitmentMetricValue(commitment, commitment.startDate, until)
        CommitmentSettler.progressOfValue(commitment, value, today)
    }

    /**
     * 结算所有已到期的承诺，返回本次结算结果（调用方负责提示用户）。
     *
     * 必须在当天结算（[com.example.lixing.domain.usecase.SettleDayUseCase.settleOverdue]）
     * **之后**调用：承诺最后几天的 day_record 要等日结算写完才有终值，
     * 顺序反了会把未结算的尾巴当成 0，好的承诺被判失败。
     *
     * 幂等两层：只捞 ACTIVE，结算后状态变终态就再也捞不到；
     * 奖励积分带 [PointRules.keyCommitment] 幂等键，流水唯一索引兜底。
     */
    suspend fun settleExpiredCommitments(today: LocalDate): List<CommitmentOutcome> =
        withContext(io) {
            dao.getExpiredCommitments(today).map { commitment ->
                val value = commitmentMetricValue(commitment, commitment.startDate, commitment.endDate)
                val outcome = CommitmentSettler.settleValue(commitment, value, today)
                dao.upsertCommitment(outcome.settled)

                if (outcome.rewardPoints > 0) {
                    award(
                        date = today,
                        delta = outcome.rewardPoints,
                        reason = PointReason.COMMITMENT_SETTLED,
                        detail = "承诺达成「${commitment.title}」",
                        dedupeKey = PointRules.keyCommitment(commitment.id),
                    )
                }
                outcome
            }
        }

    private suspend fun commitmentMetricValue(
        commitment: CommitmentEntity,
        from: LocalDate,
        to: LocalDate,
    ): Int? {
        val records = dayRecordDao.getRecordsBetween(from, to)
            .filter { !it.isDayOff && it.totalTasks > 0 }
        if (records.isEmpty()) return null
        return when (commitment.metric) {
            CommitmentMetric.COMPLETION_RATE ->
                (records.map { it.completionRate }.average() * 100).toInt().coerceAtLeast(0)
            CommitmentMetric.FOCUS_MINUTES -> records.sumOf { it.focusMinutes }
            CommitmentMetric.ACHIEVED_DAYS -> records.count { it.isAchieved }
        }
    }

    // ---------------- 重置 ----------------

    suspend fun clearAll() = withContext(io) {
        dao.deleteAllLedger()
        dao.deleteAllAchievements()
        dao.deleteAllCommitments()
        dao.upsertStreak(CheckInStreakEntity())
    }
}

/** 返回今天之前已经完整结束的自然月，最近月份在前。 */
internal fun completedMonthsBefore(today: LocalDate, count: Int): List<YearMonth> {
    var month = YearMonth.from(today).minusMonths(1)
    return List(count.coerceAtLeast(0)) {
        month.also { month = month.minusMonths(1) }
    }
}
