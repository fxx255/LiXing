package com.example.lixing.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.lixing.data.repository.GamificationRepository
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.domain.usecase.SettleDayUseCase
import com.example.lixing.reminder.ReminderScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate

/**
 * 每天跑一次的例行工作：
 * 1. 物化今天的任务；
 * 2. 结算昨天（标漏卡、发积分、推进连续记录、判成就）；
 * 3. 结算已到期的自我承诺；
 * 4. 重新排今天和明天的提醒闹钟。
 *
 * 由 PeriodicWorkRequest 在每天 00:05 左右触发（WorkManager 不保证精确时刻，
 * 但结算不依赖精确时间，只要“当天结算过”就行）。
 */
@HiltWorker
class DailyWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val taskRepository: TaskRepository,
    private val gamification: GamificationRepository,
    private val settleDay: SettleDayUseCase,
    private val reminderScheduler: ReminderScheduler,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val today = LocalDate.now()

            // 1. 物化今天 + 结算昨天
            taskRepository.materializeDay(today)
            settleDay.settle(today.minusDays(1))

            // 2. 结算可能还欠着的更早日子（极端情况）
            settleDay.settleOverdue(today)

            // 3. 到期承诺结算。必须在日结算之后——承诺最后几天的 day_record
            //    要等日结算写完才有终值，顺序反了好的承诺会被判失败
            gamification.settleExpiredCommitments(today)

            // 4. 重排提醒
            reminderScheduler.scheduleUpcoming()

            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "daily_worker"
    }
}
