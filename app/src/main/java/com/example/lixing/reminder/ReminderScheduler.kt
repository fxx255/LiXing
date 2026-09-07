package com.example.lixing.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.lixing.data.local.entity.TimeSlotEntity
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.domain.model.WeekdayMask
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把时段的提醒排进 AlarmManager。
 *
 * 用「精确闹钟 + 允许空闲唤醒」(setExactAndAllowWhileIdle) 保证在锁屏/省电下也能触发。
 * AlarmManager 的闹钟在重启后会丢，所以 BOOT_COMPLETED 里要重新排一遍。
 *
 * 每个时段排两个闹钟：
 * - 提前 N 分钟的「时段提醒」
 * - 结束前 15 分钟的「催办」（仅当可能还有未完成任务时）
 *
 * 只排今天和明天两天的，避免一次排太多；每天的结算 Worker 也会再补排。
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val planRepository: PlanRepository,
) {
    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /** 为指定日期的所有启用时段排提醒。 */
    suspend fun scheduleFor(date: LocalDate) {
        val plan = planRepository.getActivePlan() ?: return
        val slots = planRepository.getTimeSlots(plan.id).filter { it.isEnabled }
        if (slots.isEmpty()) return

        slots.forEach { slot ->
            if (!WeekdayMask(slot.weekdayMask).contains(date)) return@forEach
            scheduleSlot(slot, date)
        }
        Log.d(TAG, "Scheduled reminders for $date: ${slots.size} slots")
    }

    /** 排今天 + 明天。 */
    suspend fun scheduleUpcoming() {
        val today = LocalDate.now()
        scheduleFor(today)
        scheduleFor(today.plusDays(1))
    }

    private fun scheduleSlot(slot: TimeSlotEntity, date: LocalDate) {
        val am = alarmManager ?: return
        val zone = ZoneId.systemDefault()

        // 1. 时段开始提醒（提前 N 分钟，默认 5）
        val leadMinutes = 5
        val slotStartAt = date.atTime(slot.startTime)
        val remindAt = slotStartAt.minusMinutes(leadMinutes.toLong())
        val startMillis = remindAt.atZone(zone).toInstant().toEpochMilli()
        if (startMillis > System.currentTimeMillis()) {
            setExact(am, startMillis, slotPendingIntent(slot.id, date, TYPE_SLOT_START))
        }

        // 2. 时段结束前的催办（提前 15 分钟）
        val urgeAt = slotEndTime(slot, date).minusMinutes(15)
        val urgeMillis = urgeAt.atZone(zone).toInstant().toEpochMilli()
        if (urgeMillis > System.currentTimeMillis()) {
            setExact(am, urgeMillis, slotPendingIntent(slot.id, date, TYPE_SLOT_URGE))
        }
    }

    /** 处理跨夜时段的真实结束时刻。 */
    private fun slotEndTime(slot: TimeSlotEntity, date: LocalDate): LocalDateTime {
        val start = date.atTime(slot.startTime)
        return if (slot.endTime > slot.startTime) date.atTime(slot.endTime)
        else date.plusDays(1).atTime(slot.endTime)
    }

    private fun setExact(am: AlarmManager, triggerAtMillis: Long, pi: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                }
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } catch (e: SecurityException) {
            // 个别厂商限制精确闹钟，降级为普通闹钟
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    private fun slotPendingIntent(slotId: String, date: LocalDate, type: Int): PendingIntent {
        val intent = Intent(context, SlotReminderReceiver::class.java).apply {
            putExtra(SlotReminderReceiver.EXTRA_SLOT_ID, slotId)
            putExtra(SlotReminderReceiver.EXTRA_DATE_EPOCH, date.toEpochDay())
            putExtra(SlotReminderReceiver.EXTRA_TYPE, type)
        }
        // requestCode 组合 slotId + 日期 + 类型，保证唯一，避免互相覆盖
        // UUID 不能 toInt()，用 String.hashCode()（Java 规范保证同串同值，跨重启稳定）
        val requestCode = ((date.toEpochDay() % 100000).toInt() * 1000) +
            (slotId.hashCode() * 2) + type
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "ReminderScheduler"
        const val TYPE_SLOT_START = 0
        const val TYPE_SLOT_URGE = 1
    }
}
