package com.example.lixing.reminder

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.lixing.MainActivity
import com.example.lixing.R
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.data.local.dao.PlanningDao
import com.example.lixing.domain.model.TaskStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import javax.inject.Inject

/**
 * 时段提醒闹钟的接收器。
 *
 * AlarmManager 精确闹钟触发后，这里查出时段与任务，拼出一条通知。
 * 用 goAsync() 把广播的 10 秒窗口让给协程去查库——查询很快，足够。
 */
@AndroidEntryPoint
class SlotReminderReceiver : BroadcastReceiver() {

    @Inject
    lateinit var planRepository: PlanRepository

    @Inject
    lateinit var taskRepository: TaskRepository

    @Inject
    lateinit var planningDao: PlanningDao

    override fun onReceive(context: Context, intent: Intent) {
        val dateEpoch = intent.getLongExtra(EXTRA_DATE_EPOCH, -1L)
        val type = intent.getIntExtra(EXTRA_TYPE, ReminderScheduler.TYPE_SLOT_START)
        if (dateEpoch == -1L) return
        val slotId = intent.getStringExtra(EXTRA_SLOT_ID)
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        val expectedTaskStart = intent.getStringExtra(EXTRA_TASK_START)
        if (type == ReminderScheduler.TYPE_TASK_START && taskId.isNullOrBlank() ||
            type != ReminderScheduler.TYPE_TASK_START && slotId.isNullOrBlank()) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    if (type == ReminderScheduler.TYPE_TASK_START) {
                        showTaskReminder(context, requireNotNull(taskId), LocalDate.ofEpochDay(dateEpoch), expectedTaskStart)
                    } else showReminder(context, requireNotNull(slotId), LocalDate.ofEpochDay(dateEpoch), type)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun showTaskReminder(context: Context, taskId: String, date: LocalDate,
        expectedStart: String?) {
        val plan = planRepository.getActivePlan() ?: return
        val entry = planningDao.getScheduledTask(taskId) ?: return
        if (entry.planId != plan.id || entry.studyDate != date ||
            entry.startTime?.toString() != expectedStart ||
            entry.state !in setOf("ACTIVE", "OVERRIDE")) return
        val execution = taskRepository.getTasksOfDay(date).firstOrNull { it.scheduleId == entry.id }
        if (execution != null && execution.status != TaskStatus.PENDING) return
        notify(context, taskId.hashCode() * 7 + ReminderScheduler.TYPE_TASK_START,
            taskId.hashCode(), NotificationChannels.CHANNEL_SLOT,
            "该开始「${entry.title}」了", "${entry.startTime} · ${entry.title}")
    }

    private suspend fun showReminder(
        context: Context,
        slotId: String,
        date: LocalDate,
        type: Int,
    ) {
        val slot = planRepository.getTimeSlot(slotId) ?: return
        if (planRepository.getActivePlan()?.id != slot.planId || !slot.isEnabled) return
        val tasks = taskRepository.getTasksOfSlot(date, slotId)

        val title: String
        val body: String
        val channelId: String

        when (type) {
            ReminderScheduler.TYPE_SLOT_URGE -> {
                val remaining = tasks.filter { it.status == com.example.lixing.domain.model.TaskStatus.PENDING }
                if (remaining.isEmpty()) return // 都做完了就不催了
                channelId = NotificationChannels.CHANNEL_URGE
                title = "「${slot.name}」还有 ${remaining.size} 项没完成"
                body = remaining.joinToString("、") { it.title }
            }

            else -> {
                channelId = NotificationChannels.CHANNEL_SLOT
                title = "该进入「${slot.name}」了"
                body = if (tasks.isEmpty()) slot.note.ifEmpty { "开始学习吧" }
                else tasks.joinToString("、") { it.title }
            }
        }

        notify(context, slotId.hashCode() * 7 + type, slotId.hashCode(), channelId, title, body)
    }

    private fun notify(context: Context, notificationId: Int, requestCode: Int,
        channelId: String, title: String, body: String) {
        // 点通知回到 App 的今日页
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            requestCode,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            // 未授予通知权限时静默失败
        }
    }

    companion object {
        const val EXTRA_SLOT_ID = "slot_id"
        const val EXTRA_DATE_EPOCH = "date_epoch"
        const val EXTRA_TYPE = "type"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TASK_START = "task_start"
    }
}
