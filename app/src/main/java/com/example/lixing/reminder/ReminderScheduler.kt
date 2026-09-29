package com.example.lixing.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.lixing.data.local.entity.TimeSlotEntity
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.data.local.dao.PlanningDao
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.domain.planning.AvailabilityCodec
import com.example.lixing.domain.planning.PlanningEngine
import com.example.lixing.domain.time.StudyClock
import com.example.lixing.domain.time.StudyDayWindow
import com.example.lixing.domain.model.WeekdayMask
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
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
    private val planningDao: PlanningDao,
    private val prefs: UserPreferencesRepository,
) {
    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /** 为指定日期的所有启用时段排提醒。 */
    suspend fun scheduleFor(date: LocalDate) {
        val plan = planRepository.getActivePlan() ?: return
        val slots = planRepository.getTimeSlots(plan.id)
        val dayStart = prefs.current().dayStartTime
        val dayWindows = planningDao.getDayPolicy(plan.id, date)?.let { policy ->
            AvailabilityCodec.decode(policy.windowsJson)?.let {
                PlanningEngine.validateWindows(date, dayStart, it)
            }
        }
        val slotReminderStarts = mutableMapOf<String, java.time.LocalDateTime>()
        slots.forEach { slot ->
            val slotWindow = runCatching { StudyDayWindow.of(date, slot.startTime, slot.endTime, dayStart) }.getOrNull()
            val available = slotWindow != null && PlanningEngine.availableForSlot(slotWindow, dayWindows).isNotEmpty()
            if (slot.isEnabled && WeekdayMask(slot.weekdayMask).contains(date) && available) {
                val effective = PlanningEngine.availableForSlot(requireNotNull(slotWindow), dayWindows)
                slotReminderStarts[slot.id] = effective.first().start
                scheduleSlot(slot, date, effective)
            } else cancelSlot(slot.id, date)
        }
        planningDao.getScheduledTasks(plan.id, date).forEach { task ->
            val active = task.state in setOf("ACTIVE", "OVERRIDE")
            val slotStart = slotReminderStarts[task.timeSlotId]
            val nearSlotStart = slotStart != null &&
                task.startTime?.takeIf { task.endTime != null }?.let { start ->
                    val taskAt = StudyDayWindow.of(date, start, requireNotNull(task.endTime), dayStart).start
                    kotlin.math.abs(java.time.Duration.between(taskAt, slotStart).toMinutes()) <= 10
                } == true
            if (active && task.startTime != null && task.endTime != null && !nearSlotStart) {
                scheduleTask(task.id, date, task.startTime, task.endTime, dayStart)
            } else cancelTask(task.id, date)
        }
        Log.d(TAG, "Scheduled reminders for $date: ${slots.size} slots")
    }

    /** 排今天 + 明天。 */
    suspend fun scheduleUpcoming() {
        val today = StudyClock(dayStart = prefs.current().dayStartTime).today()
        scheduleFor(today)
        scheduleFor(today.plusDays(1))
    }

    private fun scheduleSlot(slot: TimeSlotEntity, date: LocalDate,
        available: List<com.example.lixing.domain.time.SlotWindow>) {
        val am = alarmManager ?: return
        cancelSlot(slot.id, date)
        val zone = ZoneId.systemDefault()

        // 1. 时段开始提醒（提前 N 分钟，默认 5）
        val leadMinutes = 5
        val slotStartAt = available.first().start
        val remindAt = slotStartAt.minusMinutes(leadMinutes.toLong())
        val startMillis = remindAt.atZone(zone).toInstant().toEpochMilli()
        if (startMillis > System.currentTimeMillis()) {
            setExact(am, startMillis, slotPendingIntent(slot.id, date, TYPE_SLOT_START))
        }

        // 2. 时段结束前的催办（提前 15 分钟）
        val urgeAt = available.last().end.minusMinutes(15)
        val urgeMillis = urgeAt.atZone(zone).toInstant().toEpochMilli()
        if (urgeMillis > System.currentTimeMillis()) {
            setExact(am, urgeMillis, slotPendingIntent(slot.id, date, TYPE_SLOT_URGE))
        }
    }

    private fun cancelSlot(slotId: String, date: LocalDate) {
        val am = alarmManager ?: return
        am.cancel(slotPendingIntent(slotId, date, TYPE_SLOT_START))
        am.cancel(slotPendingIntent(slotId, date, TYPE_SLOT_URGE))
    }

    private fun scheduleTask(id: String, date: LocalDate, start: java.time.LocalTime,
        end: java.time.LocalTime, dayStart: java.time.LocalTime) {
        val am = alarmManager ?: return
        val pi = taskPendingIntent(id, date, start)
        am.cancel(pi)
        val actual = StudyDayWindow.of(date, start, end, dayStart).start.minusMinutes(5)
        val millis = actual.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (millis > System.currentTimeMillis()) setExact(am, millis, pi)
    }

    private fun cancelTask(id: String, date: LocalDate) {
        alarmManager?.cancel(taskPendingIntent(id, date, null))
    }

    private fun taskPendingIntent(id: String, date: LocalDate, start: java.time.LocalTime?): PendingIntent {
        val intent = Intent(context, SlotReminderReceiver::class.java).apply {
            action = "com.example.lixing.TASK_REMINDER:$id:$date"
            putExtra(SlotReminderReceiver.EXTRA_TASK_ID, id)
            start?.let { putExtra(SlotReminderReceiver.EXTRA_TASK_START, it.toString()) }
            putExtra(SlotReminderReceiver.EXTRA_DATE_EPOCH, date.toEpochDay())
            putExtra(SlotReminderReceiver.EXTRA_TYPE, TYPE_TASK_START)
        }
        return PendingIntent.getBroadcast(context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
        const val TYPE_TASK_START = 2
    }
}
