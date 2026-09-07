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

    override fun onReceive(context: Context, intent: Intent) {
        val slotId = intent.getStringExtra(EXTRA_SLOT_ID)?.takeIf { it.isNotBlank() } ?: return
        val dateEpoch = intent.getLongExtra(EXTRA_DATE_EPOCH, -1L)
        val type = intent.getIntExtra(EXTRA_TYPE, ReminderScheduler.TYPE_SLOT_START)
        if (dateEpoch == -1L) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    showReminder(context, slotId, LocalDate.ofEpochDay(dateEpoch), type)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun showReminder(
        context: Context,
        slotId: String,
        date: LocalDate,
        type: Int,
    ) {
        val slot = planRepository.getTimeSlot(slotId) ?: return
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

        // 点通知回到 App 的今日页
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            slotId.hashCode(),
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
            NotificationManagerCompat.from(context).notify(slotId.hashCode() * 7 + type, notification)
        } catch (e: SecurityException) {
            // 未授予通知权限时静默失败
        }
    }

    companion object {
        const val EXTRA_SLOT_ID = "slot_id"
        const val EXTRA_DATE_EPOCH = "date_epoch"
        const val EXTRA_TYPE = "type"
    }
}
