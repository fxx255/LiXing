package com.example.lixing.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.example.lixing.ui.theme.DarkModePref
import com.example.lixing.ui.theme.ThemeSeed
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lixing_prefs")

/**
 * 偏好设置读写。对外只暴露 [preferences] 这一个 Flow + 若干 update 方法，
 * 调用方不用关心 key 的存在与否。
 */
@Singleton
class UserPreferencesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val preferences: Flow<UserPreferences> = context.dataStore.data.map { p ->
        UserPreferences(
            themeSeed = p[PreferencesKeys.THEME_SEED]
                ?.let { runCatching { ThemeSeed.valueOf(it) }.getOrNull() } ?: ThemeSeed.DAWN,
            darkMode = p[PreferencesKeys.DARK_MODE]
                ?.let { runCatching { DarkModePref.valueOf(it) }.getOrNull() }
                ?: DarkModePref.FOLLOW_SYSTEM,
            dayStartTime = p[PreferencesKeys.DAY_START_SECOND]
                ?.let { LocalTime.ofSecondOfDay(it.toLong()) } ?: LocalTime.of(4, 0),
            slotReminderEnabled = p[PreferencesKeys.SLOT_REMINDER_ENABLED] ?: true,
            slotReminderLeadMinutes = p[PreferencesKeys.SLOT_REMINDER_LEAD] ?: 5,
            urgeReminderEnabled = p[PreferencesKeys.URGE_REMINDER_ENABLED] ?: true,
            urgeReminderLeadMinutes = p[PreferencesKeys.URGE_REMINDER_LEAD] ?: 15,
            summaryReminderEnabled = p[PreferencesKeys.SUMMARY_REMINDER_ENABLED] ?: true,
            summaryReminderTime = p[PreferencesKeys.SUMMARY_REMINDER_SECOND]
                ?.let { LocalTime.ofSecondOfDay(it.toLong()) } ?: LocalTime.of(22, 0),
            streakWarningEnabled = p[PreferencesKeys.STREAK_WARNING_ENABLED] ?: true,
            streakWarningTime = p[PreferencesKeys.STREAK_WARNING_SECOND]
                ?.let { LocalTime.ofSecondOfDay(it.toLong()) } ?: LocalTime.of(21, 0),
            streakWarningFullScreen = p[PreferencesKeys.STREAK_WARNING_FULLSCREEN] ?: false,
            penaltyEnabled = p[PreferencesKeys.PENALTY_ENABLED] ?: true,
            achieveThresholdPercent = p[PreferencesKeys.ACHIEVE_THRESHOLD] ?: 60,
            makeupPerWeek = p[PreferencesKeys.MAKEUP_PER_WEEK] ?: 2,
            dayOffPerMonth = p[PreferencesKeys.DAY_OFF_PER_MONTH] ?: 4,
            rescueCardsPerMonth = p[PreferencesKeys.RESCUE_CARDS_PER_MONTH] ?: 1,
            pomodoroMinutes = p[PreferencesKeys.POMODORO_MINUTES] ?: 45,
            pomodoroBreakMinutes = p[PreferencesKeys.POMODORO_BREAK_MINUTES] ?: 10,
            focusGuardEnabled = p[PreferencesKeys.FOCUS_GUARD_ENABLED] ?: true,
            focusKeepScreenOn = p[PreferencesKeys.FOCUS_KEEP_SCREEN_ON] ?: false,
            weeklyReportDay = p[PreferencesKeys.WEEKLY_REPORT_DAY]
                ?.let { runCatching { DayOfWeek.of(it) }.getOrNull() } ?: DayOfWeek.SUNDAY,
            autoBackupEnabled = p[PreferencesKeys.AUTO_BACKUP_ENABLED] ?: true,
            backupKeepCount = p[PreferencesKeys.BACKUP_KEEP_COUNT] ?: 7,
            syncAutoEnabled = p[PreferencesKeys.SYNC_AUTO_ENABLED] ?: true,
            syncWifiOnly = p[PreferencesKeys.SYNC_WIFI_ONLY] ?: true,
            updateAutoEnabled = p[PreferencesKeys.UPDATE_AUTO_ENABLED] ?: true,
            updateWifiOnly = p[PreferencesKeys.UPDATE_WIFI_ONLY] ?: true,
            onboardingDone = p[PreferencesKeys.ONBOARDING_DONE] ?: false,
            permissionGuideShown = p[PreferencesKeys.PERMISSION_GUIDE_SHOWN] ?: false,
            lastMaterializedDay = p[PreferencesKeys.LAST_MATERIALIZED_DAY] ?: 0L,
            maimemoToken = p[PreferencesKeys.MAIMEMO_TOKEN] ?: "",
            maimemoEnabled = p[PreferencesKeys.MAIMEMO_ENABLED] ?: false,
            maimemoAutoSync = p[PreferencesKeys.MAIMEMO_AUTO_SYNC] ?: false,
            aiAssistantEnabled = p[PreferencesKeys.AI_ASSISTANT_ENABLED] ?: false,
            aiBaseUrl = p[PreferencesKeys.AI_BASE_URL] ?: "",
            aiModel = p[PreferencesKeys.AI_MODEL] ?: "",
            aiVisionEnabled = p[PreferencesKeys.AI_VISION_ENABLED] ?: false,
            aiWebSearchEnabled = p[PreferencesKeys.AI_WEB_SEARCH_ENABLED] ?: false,
        )
    }

    suspend fun current(): UserPreferences = preferences.first()

    // ---------- 外观 ----------

    suspend fun setThemeSeed(seed: ThemeSeed) = edit { it[PreferencesKeys.THEME_SEED] = seed.name }

    suspend fun setDarkMode(mode: DarkModePref) = edit { it[PreferencesKeys.DARK_MODE] = mode.name }

    // ---------- 时间 ----------

    suspend fun setDayStartTime(time: LocalTime) =
        edit { it[PreferencesKeys.DAY_START_SECOND] = time.toSecondOfDay() }

    // ---------- 提醒 ----------

    suspend fun setSlotReminderEnabled(enabled: Boolean) =
        edit { it[PreferencesKeys.SLOT_REMINDER_ENABLED] = enabled }

    suspend fun setSlotReminderLead(minutes: Int) =
        edit { it[PreferencesKeys.SLOT_REMINDER_LEAD] = minutes.coerceIn(0, 60) }

    suspend fun setUrgeReminderEnabled(enabled: Boolean) =
        edit { it[PreferencesKeys.URGE_REMINDER_ENABLED] = enabled }

    suspend fun setUrgeReminderLead(minutes: Int) =
        edit { it[PreferencesKeys.URGE_REMINDER_LEAD] = minutes.coerceIn(0, 60) }

    suspend fun setSummaryReminderEnabled(enabled: Boolean) =
        edit { it[PreferencesKeys.SUMMARY_REMINDER_ENABLED] = enabled }

    suspend fun setSummaryReminderTime(time: LocalTime) =
        edit { it[PreferencesKeys.SUMMARY_REMINDER_SECOND] = time.toSecondOfDay() }

    suspend fun setStreakWarningEnabled(enabled: Boolean) =
        edit { it[PreferencesKeys.STREAK_WARNING_ENABLED] = enabled }

    suspend fun setStreakWarningTime(time: LocalTime) =
        edit { it[PreferencesKeys.STREAK_WARNING_SECOND] = time.toSecondOfDay() }

    suspend fun setStreakWarningFullScreen(enabled: Boolean) =
        edit { it[PreferencesKeys.STREAK_WARNING_FULLSCREEN] = enabled }

    // ---------- 激励规则 ----------

    suspend fun setPenaltyEnabled(enabled: Boolean) =
        edit { it[PreferencesKeys.PENALTY_ENABLED] = enabled }

    suspend fun setAchieveThreshold(percent: Int) =
        edit { it[PreferencesKeys.ACHIEVE_THRESHOLD] = percent.coerceIn(10, 100) }

    suspend fun setMakeupPerWeek(count: Int) =
        edit { it[PreferencesKeys.MAKEUP_PER_WEEK] = count.coerceIn(0, 14) }

    suspend fun setDayOffPerMonth(days: Int) =
        edit { it[PreferencesKeys.DAY_OFF_PER_MONTH] = days.coerceIn(0, 31) }

    suspend fun setRescueCardsPerMonth(count: Int) =
        edit { it[PreferencesKeys.RESCUE_CARDS_PER_MONTH] = count.coerceIn(0, 5) }

    // ---------- 专注 ----------

    suspend fun setPomodoroMinutes(minutes: Int) =
        edit { it[PreferencesKeys.POMODORO_MINUTES] = minutes.coerceIn(5, 180) }

    suspend fun setPomodoroBreakMinutes(minutes: Int) =
        edit { it[PreferencesKeys.POMODORO_BREAK_MINUTES] = minutes.coerceIn(0, 60) }

    suspend fun setFocusGuardEnabled(enabled: Boolean) =
        edit { it[PreferencesKeys.FOCUS_GUARD_ENABLED] = enabled }

    suspend fun setFocusKeepScreenOn(enabled: Boolean) =
        edit { it[PreferencesKeys.FOCUS_KEEP_SCREEN_ON] = enabled }

    // ---------- 报告与备份 ----------

    suspend fun setWeeklyReportDay(day: DayOfWeek) =
        edit { it[PreferencesKeys.WEEKLY_REPORT_DAY] = day.value }

    suspend fun setAutoBackupEnabled(enabled: Boolean) =
        edit { it[PreferencesKeys.AUTO_BACKUP_ENABLED] = enabled }

    suspend fun setBackupKeepCount(count: Int) =
        edit { it[PreferencesKeys.BACKUP_KEEP_COUNT] = count.coerceIn(1, 30) }

    // ---------- 多端同步 ----------

    suspend fun setSyncAutoEnabled(enabled: Boolean) =
        edit { it[PreferencesKeys.SYNC_AUTO_ENABLED] = enabled }

    suspend fun setSyncWifiOnly(enabled: Boolean) =
        edit { it[PreferencesKeys.SYNC_WIFI_ONLY] = enabled }

    // ---------- 应用内更新 ----------

    suspend fun setUpdateAutoEnabled(enabled: Boolean) =
        edit { it[PreferencesKeys.UPDATE_AUTO_ENABLED] = enabled }

    suspend fun setUpdateWifiOnly(enabled: Boolean) =
        edit { it[PreferencesKeys.UPDATE_WIFI_ONLY] = enabled }

    /** 上次自动检查版本的时间戳（毫秒），0 表示从未检查过。 */
    suspend fun updateLastCheckAt(): Long =
        context.dataStore.data.first()[PreferencesKeys.UPDATE_LAST_CHECK_AT] ?: 0L

    suspend fun markUpdateChecked(atMillis: Long) =
        edit { it[PreferencesKeys.UPDATE_LAST_CHECK_AT] = atMillis }

    // ---------- 引导状态 ----------

    suspend fun setOnboardingDone(done: Boolean) =
        edit { it[PreferencesKeys.ONBOARDING_DONE] = done }

    suspend fun setPermissionGuideShown(shown: Boolean) =
        edit { it[PreferencesKeys.PERMISSION_GUIDE_SHOWN] = shown }

    suspend fun setLastMaterializedDay(epochDay: Long) =
        edit { it[PreferencesKeys.LAST_MATERIALIZED_DAY] = epochDay }

    suspend fun setMaimemoToken(token: String) =
        edit { it[PreferencesKeys.MAIMEMO_TOKEN] = token.trim() }

    suspend fun setMaimemoEnabled(enabled: Boolean) =
        edit { it[PreferencesKeys.MAIMEMO_ENABLED] = enabled }

    suspend fun setMaimemoAutoSync(enabled: Boolean) =
        edit { it[PreferencesKeys.MAIMEMO_AUTO_SYNC] = enabled }

    /** 上次自动执行墨墨同步的时间戳（毫秒），0 表示从未执行过。 */
    suspend fun maimemoLastSyncAt(): Long =
        context.dataStore.data.first()[PreferencesKeys.MAIMEMO_LAST_SYNC_AT] ?: 0L

    suspend fun markMaimemoSynced(atMillis: Long) =
        edit { it[PreferencesKeys.MAIMEMO_LAST_SYNC_AT] = atMillis }
    suspend fun setAiAssistantEnabled(enabled: Boolean) =
        edit { it[PreferencesKeys.AI_ASSISTANT_ENABLED] = enabled }

    suspend fun setAiBaseUrl(url: String) =
        edit { it[PreferencesKeys.AI_BASE_URL] = url.trim() }

    suspend fun setAiModel(model: String) =
        edit { it[PreferencesKeys.AI_MODEL] = model.trim() }

    suspend fun setAiVisionEnabled(enabled: Boolean) =
        edit { it[PreferencesKeys.AI_VISION_ENABLED] = enabled }

    suspend fun setAiConfiguration(baseUrl: String, model: String, visionEnabled: Boolean) =
        context.dataStore.edit {
            it[PreferencesKeys.AI_BASE_URL] = baseUrl.trim()
            it[PreferencesKeys.AI_MODEL] = model.trim()
            it[PreferencesKeys.AI_VISION_ENABLED] = visionEnabled
        }

    suspend fun setAiWebSearchEnabled(enabled: Boolean) =
        edit { it[PreferencesKeys.AI_WEB_SEARCH_ENABLED] = enabled }

    /** 用备份中的完整设置原子替换当前设置。敏感的墨墨 Token 不进入备份。 */
    suspend fun replaceAll(p: UserPreferences) {
        context.dataStore.edit { out ->
            // 备份不携带凭据；恢复时保留当前设备已经配置的 Token。
            val localMaimemoToken = out[PreferencesKeys.MAIMEMO_TOKEN]
            // 同步配置是每台设备自己的（凭据也在本机），恢复备份时保留本机设置。
            val localSyncAutoEnabled = out[PreferencesKeys.SYNC_AUTO_ENABLED]
            val localSyncWifiOnly = out[PreferencesKeys.SYNC_WIFI_ONLY]
            // 墨墨自动同步依赖本机 Token，属于每台设备自己的设置，恢复备份时保留本机开关。
            val localMaimemoAutoSync = out[PreferencesKeys.MAIMEMO_AUTO_SYNC]
            val localMaimemoLastSyncAt = out[PreferencesKeys.MAIMEMO_LAST_SYNC_AT]
            out.clear()
            out[PreferencesKeys.THEME_SEED] = p.themeSeed.name
            out[PreferencesKeys.DARK_MODE] = p.darkMode.name
            out[PreferencesKeys.DAY_START_SECOND] = p.dayStartTime.toSecondOfDay()
            out[PreferencesKeys.SLOT_REMINDER_ENABLED] = p.slotReminderEnabled
            out[PreferencesKeys.SLOT_REMINDER_LEAD] = p.slotReminderLeadMinutes
            out[PreferencesKeys.URGE_REMINDER_ENABLED] = p.urgeReminderEnabled
            out[PreferencesKeys.URGE_REMINDER_LEAD] = p.urgeReminderLeadMinutes
            out[PreferencesKeys.SUMMARY_REMINDER_ENABLED] = p.summaryReminderEnabled
            out[PreferencesKeys.SUMMARY_REMINDER_SECOND] = p.summaryReminderTime.toSecondOfDay()
            out[PreferencesKeys.STREAK_WARNING_ENABLED] = p.streakWarningEnabled
            out[PreferencesKeys.STREAK_WARNING_SECOND] = p.streakWarningTime.toSecondOfDay()
            out[PreferencesKeys.STREAK_WARNING_FULLSCREEN] = p.streakWarningFullScreen
            out[PreferencesKeys.PENALTY_ENABLED] = p.penaltyEnabled
            out[PreferencesKeys.ACHIEVE_THRESHOLD] = p.achieveThresholdPercent
            out[PreferencesKeys.MAKEUP_PER_WEEK] = p.makeupPerWeek
            out[PreferencesKeys.DAY_OFF_PER_MONTH] = p.dayOffPerMonth
            out[PreferencesKeys.RESCUE_CARDS_PER_MONTH] = p.rescueCardsPerMonth
            out[PreferencesKeys.POMODORO_MINUTES] = p.pomodoroMinutes
            out[PreferencesKeys.POMODORO_BREAK_MINUTES] = p.pomodoroBreakMinutes
            out[PreferencesKeys.FOCUS_GUARD_ENABLED] = p.focusGuardEnabled
            out[PreferencesKeys.FOCUS_KEEP_SCREEN_ON] = p.focusKeepScreenOn
            out[PreferencesKeys.WEEKLY_REPORT_DAY] = p.weeklyReportDay.value
            out[PreferencesKeys.AUTO_BACKUP_ENABLED] = p.autoBackupEnabled
            out[PreferencesKeys.BACKUP_KEEP_COUNT] = p.backupKeepCount
            out[PreferencesKeys.MAIMEMO_ENABLED] = p.maimemoEnabled
            out[PreferencesKeys.MAIMEMO_AUTO_SYNC] = localMaimemoAutoSync ?: p.maimemoAutoSync
            out[PreferencesKeys.AI_ASSISTANT_ENABLED] = p.aiAssistantEnabled
            out[PreferencesKeys.AI_BASE_URL] = p.aiBaseUrl
            out[PreferencesKeys.AI_MODEL] = p.aiModel
            out[PreferencesKeys.AI_VISION_ENABLED] = p.aiVisionEnabled
            out[PreferencesKeys.AI_WEB_SEARCH_ENABLED] = p.aiWebSearchEnabled
            if (localMaimemoToken != null) out[PreferencesKeys.MAIMEMO_TOKEN] = localMaimemoToken
            if (localMaimemoLastSyncAt != null) {
                out[PreferencesKeys.MAIMEMO_LAST_SYNC_AT] = localMaimemoLastSyncAt
            }
            if (localSyncAutoEnabled != null) out[PreferencesKeys.SYNC_AUTO_ENABLED] = localSyncAutoEnabled
            if (localSyncWifiOnly != null) out[PreferencesKeys.SYNC_WIFI_ONLY] = localSyncWifiOnly
            out[PreferencesKeys.ONBOARDING_DONE] = p.onboardingDone
            out[PreferencesKeys.PERMISSION_GUIDE_SHOWN] = p.permissionGuideShown
            out[PreferencesKeys.LAST_MATERIALIZED_DAY] = p.lastMaterializedDay
        }
    }

    /** 清空全部偏好（数据重置用）。 */
    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    private suspend inline fun edit(crossinline block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }
}
