package com.example.lixing.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import com.example.lixing.R

/**
 * 通知渠道。分三类，用户可以分别开关：
 * - 时段提醒（slot）：时段开始前
 * - 催办（urge）：时段快结束还有任务没做
 * - 成就与总结（summary）：解锁成就、每日复盘、周报
 *
 * 专注计时用的前台服务走单独的 low-importance 渠道，不打扰。
 */
object NotificationChannels {

    const val CHANNEL_SLOT = "slot_reminder"
    const val CHANNEL_URGE = "urge_reminder"
    const val CHANNEL_SUMMARY = "summary"
    const val CHANNEL_FOCUS = "focus_service"
    const val CHANNEL_ASSISTANT = "assistant_generation"

    fun createAll(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return

        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_SLOT,
                    context.getString(R.string.channel_slot_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.channel_slot_desc)
                    enableVibration(true)
                },
                NotificationChannel(
                    CHANNEL_URGE,
                    context.getString(R.string.channel_urge_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.channel_urge_desc)
                    enableVibration(true)
                },
                NotificationChannel(
                    CHANNEL_SUMMARY,
                    context.getString(R.string.channel_summary_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.channel_summary_desc)
                },
                NotificationChannel(
                    CHANNEL_FOCUS,
                    context.getString(R.string.channel_focus_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.channel_focus_desc)
                    setShowBadge(false)
                },
                NotificationChannel(
                    CHANNEL_ASSISTANT,
                    context.getString(R.string.channel_assistant_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.channel_assistant_desc)
                    setShowBadge(false)
                },
            ),
        )
    }
}
