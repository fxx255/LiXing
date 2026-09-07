package com.example.lixing.data.prefs

import com.example.lixing.ui.theme.DarkModePref
import com.example.lixing.ui.theme.ThemeSeed
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * 用户偏好设置的内存快照。
 *
 * 关于 [dayStartTime]：这是「切日点」。设为 03:00 时，凌晨 1 点的打卡算前一天，
 * 符合熬夜学习的实际感受。默认 04:00。
 */
data class UserPreferences(
    // ---------- 外观 ----------
    val themeSeed: ThemeSeed = ThemeSeed.DAWN,
    val darkMode: DarkModePref = DarkModePref.FOLLOW_SYSTEM,

    // ---------- 时间 ----------
    /** 每日起始时间（切日点）。 */
    val dayStartTime: LocalTime = LocalTime.of(4, 0),

    // ---------- 提醒 ----------
    val slotReminderEnabled: Boolean = true,
    /** 时段开始前多少分钟提醒。 */
    val slotReminderLeadMinutes: Int = 5,
    val urgeReminderEnabled: Boolean = true,
    /** 时段结束前多少分钟催办。 */
    val urgeReminderLeadMinutes: Int = 15,
    val summaryReminderEnabled: Boolean = true,
    /** 晚间复盘提醒时间。 */
    val summaryReminderTime: LocalTime = LocalTime.of(22, 0),
    val streakWarningEnabled: Boolean = true,
    /** 连续记录告警时间（当天此时仍 0 打卡则强提醒）。 */
    val streakWarningTime: LocalTime = LocalTime.of(21, 0),
    val streakWarningFullScreen: Boolean = false,

    // ---------- 激励规则 ----------
    /** 是否启用漏卡扣分。关掉后只记录不扣分。 */
    val penaltyEnabled: Boolean = true,
    /** 当天达成的完成率阈值（0~100），决定连续记录是否延续。 */
    val achieveThresholdPercent: Int = 60,
    /** 每周补卡上限。 */
    val makeupPerWeek: Int = 2,
    /** 每月请假上限（天）。 */
    val dayOffPerMonth: Int = 4,
    /** 每月补救卡数量。 */
    val rescueCardsPerMonth: Int = 1,

    // ---------- 专注 ----------
    val pomodoroMinutes: Int = 45,
    val pomodoroBreakMinutes: Int = 10,
    /** 是否统计切出应用次数。 */
    val focusGuardEnabled: Boolean = true,
    val focusKeepScreenOn: Boolean = false,

    // ---------- 报告与备份 ----------
    /** 周报生成日。 */
    val weeklyReportDay: DayOfWeek = DayOfWeek.SUNDAY,
    val autoBackupEnabled: Boolean = true,
    /** 保留的备份份数。 */
    val backupKeepCount: Int = 7,

    // ---------- 多端同步 ----------
    /** 是否启用自动同步（应用启动后、操作停止 30 秒后触发）。手动同步不受此限制。 */
    val syncAutoEnabled: Boolean = true,
    /** 自动同步是否仅限 Wi-Fi 等非计费网络。手动同步不受限。 */
    val syncWifiOnly: Boolean = true,

    // ---------- 应用内更新 ----------
    /** 启动时是否自动检查新版本。手动检查不受此限制。 */
    val updateAutoEnabled: Boolean = true,
    /** 自动检查是否仅限 Wi-Fi。手动检查不受限。 */
    val updateWifiOnly: Boolean = true,

    // ---------- 引导状态 ----------
    // ---------- 墨墨背单词 ----------
    val maimemoToken: String = "",
    val maimemoEnabled: Boolean = false,
    /**
     * 墨墨进度是否跟随「多端同步」自动打卡（默认关闭）。
     *
     * 自动打卡只推进不回退：墨墨完成比例上升才更新任务，绝不撤销已有打卡与积分。
     * 且两次执行至少相隔 15 分钟，避免频繁调用墨墨接口。
     */
    val maimemoAutoSync: Boolean = false,
    // ---------- AI 学习助手 ----------
    val aiAssistantEnabled: Boolean = false,
    /** OpenAI 兼容接口地址，如 https://api.deepseek.com 。空表示未配置。 */
    val aiBaseUrl: String = "",
    /** 模型名，如 deepseek-chat。空表示未配置。 */
    val aiModel: String = "",
    /** 模型是否多模态。关闭时助手照片先经本地 OCR 提取文字。 */
    val aiVisionEnabled: Boolean = false,
    /** 是否启用联网搜索（DeepSeek 内置）。默认关闭。 */
    val aiWebSearchEnabled: Boolean = false,

    /** 是否完成首次引导。false 时启动进引导页。 */
    val onboardingDone: Boolean = false,
    /** 是否已展示过权限引导。 */
    val permissionGuideShown: Boolean = false,
    /** 最后一次物化任务的日期（epochDay），避免同一天重复物化。 */
    val lastMaterializedDay: Long = 0L,
) {
    val achieveThreshold: Float get() = achieveThresholdPercent / 100f
}
