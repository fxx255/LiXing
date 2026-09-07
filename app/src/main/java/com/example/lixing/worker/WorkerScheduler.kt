package com.example.lixing.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 注册每日周期 Worker。
 *
 * WorkManager 的周期任务最小间隔是 15 分钟，且只能在“大约这个时间”触发。
 * 我们用 setInitialDelay 把它推到凌晨，配合 KEEP 策略避免重复注册。
 * 精确的“到点提醒”靠 AlarmManager，Worker 只负责结算与物化，不要求精确。
 */
@Singleton
class WorkerScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun scheduleDailyWorker() {
        val request = PeriodicWorkRequestBuilder<DailyWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(false)
                    .build(),
            )
            // 推到凌晨 00:05 附近开始第一个周期
            .setInitialDelay(initialDelayToNextMidnight(), TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DailyWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** 距下一个 00:05 还有多少毫秒。 */
    private fun initialDelayToNextMidnight(): Long {
        val now = java.util.Calendar.getInstance()
        val target = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 5)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return (target.timeInMillis - now.timeInMillis).coerceAtLeast(0)
    }
}
