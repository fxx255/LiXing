package com.example.lixing.data.prefs

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** DataStore 的 key 集合。集中一处，避免各处硬编码字符串写错。 */
internal object PreferencesKeys {
    val THEME_SEED = stringPreferencesKey("theme_seed")
    val DARK_MODE = stringPreferencesKey("dark_mode")

    val DAY_START_SECOND = intPreferencesKey("day_start_second")

    val SLOT_REMINDER_ENABLED = booleanPreferencesKey("slot_reminder_enabled")
    val SLOT_REMINDER_LEAD = intPreferencesKey("slot_reminder_lead")
    val URGE_REMINDER_ENABLED = booleanPreferencesKey("urge_reminder_enabled")
    val URGE_REMINDER_LEAD = intPreferencesKey("urge_reminder_lead")
    val SUMMARY_REMINDER_ENABLED = booleanPreferencesKey("summary_reminder_enabled")
    val SUMMARY_REMINDER_SECOND = intPreferencesKey("summary_reminder_second")
    val STREAK_WARNING_ENABLED = booleanPreferencesKey("streak_warning_enabled")
    val STREAK_WARNING_SECOND = intPreferencesKey("streak_warning_second")
    val STREAK_WARNING_FULLSCREEN = booleanPreferencesKey("streak_warning_fullscreen")

    val PENALTY_ENABLED = booleanPreferencesKey("penalty_enabled")
    val ACHIEVE_THRESHOLD = intPreferencesKey("achieve_threshold")
    val MAKEUP_PER_WEEK = intPreferencesKey("makeup_per_week")
    val DAY_OFF_PER_MONTH = intPreferencesKey("day_off_per_month")
    val RESCUE_CARDS_PER_MONTH = intPreferencesKey("rescue_cards_per_month")

    val POMODORO_MINUTES = intPreferencesKey("pomodoro_minutes")
    val POMODORO_BREAK_MINUTES = intPreferencesKey("pomodoro_break_minutes")
    val FOCUS_GUARD_ENABLED = booleanPreferencesKey("focus_guard_enabled")
    val FOCUS_KEEP_SCREEN_ON = booleanPreferencesKey("focus_keep_screen_on")

    val WEEKLY_REPORT_DAY = intPreferencesKey("weekly_report_day")
    val AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
    val BACKUP_KEEP_COUNT = intPreferencesKey("backup_keep_count")

    // ---------- 多端同步 ----------
    val SYNC_AUTO_ENABLED = booleanPreferencesKey("sync_auto_enabled")
    val SYNC_WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")

    // ---------- 英语背诵 ----------
/** 每天最多新学多少条单词/短语（复习不限量）。 */
val ENGLISH_DAILY_NEW_LIMIT = intPreferencesKey("english_daily_new_limit")

// ---------- 应用内更新 ----------
/** 上一次记录到的本机版本号；启动时发现变大了 = 刚完成一次覆盖安装 → 清理安装包。 */
val UPDATE_LAST_SEEN_VERSION_CODE = intPreferencesKey("update_last_seen_version_code")
    /** 是否启用启动时自动检查版本。默认开启（用户随时可在设置关掉）。 */
    val UPDATE_AUTO_ENABLED = booleanPreferencesKey("update_auto_enabled")
    /** 自动检查是否仅限 Wi-Fi 等非计费网络（手动检查不受限）。 */
    val UPDATE_WIFI_ONLY = booleanPreferencesKey("update_wifi_only")
    /** 上次自动检查版本的时间戳（毫秒），用于「每天最多一次」节流。 */
    val UPDATE_LAST_CHECK_AT = longPreferencesKey("update_last_check_at")

    val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    val PERMISSION_GUIDE_SHOWN = booleanPreferencesKey("permission_guide_shown")
    val LAST_MATERIALIZED_DAY = longPreferencesKey("last_materialized_day")

    // ---------- 墨墨背单词 ----------
    /** 用户从墨墨 App 获取的个人 Bearer Token。 */
    val MAIMEMO_TOKEN = stringPreferencesKey("maimemo_token")
    /** 是否启用墨墨同步。默认关闭（离线优先）。 */
    val MAIMEMO_ENABLED = booleanPreferencesKey("maimemo_enabled")
    /**
     * 是否让墨墨进度跟随「多端同步」自动打卡。默认关闭：
     * 自动打卡会有数据写入，必须由用户明确开启。
     */
    val MAIMEMO_AUTO_SYNC = booleanPreferencesKey("maimemo_auto_sync")
    /** 上次自动执行墨墨同步的时间戳，用于节流（毫秒）。 */
    val MAIMEMO_LAST_SYNC_AT = longPreferencesKey("maimemo_last_sync_at")
    // ---------- AI 学习助手 ----------
    /** 是否启用 AI 学习助手。默认关闭（离线优先）。 */
    val AI_ASSISTANT_ENABLED = booleanPreferencesKey("ai_assistant_enabled")
    /** OpenAI 兼容接口地址，如 https://api.deepseek.com 。空表示未配置。 */
    val AI_BASE_URL = stringPreferencesKey("ai_base_url")
    /** 模型名，如 deepseek-chat / kimi-latest / glm-4-flash。 */
    val AI_MODEL = stringPreferencesKey("ai_model")
    /** 接入的模型是否支持图片输入（多模态）。关闭时照片先走本地 OCR。 */
    val AI_VISION_ENABLED = booleanPreferencesKey("ai_vision_enabled")
    /** 是否启用联网搜索（DeepSeek 内置）。默认关闭。 */
    val AI_WEB_SEARCH_ENABLED = booleanPreferencesKey("ai_web_search_enabled")
    /** 长回答被截断时的自动续写保护上限（轮数，0 = 关闭自动续写；正常用不满）。 */
    val ASSISTANT_AUTO_CONTINUE = intPreferencesKey("assistant_auto_continue")
}
