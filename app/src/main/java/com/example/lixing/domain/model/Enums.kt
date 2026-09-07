package com.example.lixing.domain.model

/** 任务性质。影响图标、统计归类，以及默认的量化单位。 */
enum class TaskType(val label: String) {
    LECTURE("听课"),
    PRACTICE("刷题"),
    MEMORIZE("背诵"),
    REVIEW("复盘"),
    CUSTOM("自定义"),
}

/** 任务的量化方式。BOOLEAN 表示不计量、完成即可。 */
enum class TargetType(val label: String, val unit: String) {
    MINUTES("时长", "分钟"),
    COUNT("个数", "个"),
    PAGES("页数", "页"),
    BOOLEAN("完成即可", ""),
    ;

    val isQuantified: Boolean get() = this != BOOLEAN
}

/**
 * 当日任务状态。
 * PENDING 待做 / DONE 已完成 / PARTIAL 部分完成 / MISSED 漏卡 / SKIPPED 已请假。
 */
enum class TaskStatus(val label: String) {
    PENDING("待做"),
    DONE("已完成"),
    PARTIAL("部分完成"),
    MISSED("漏卡"),
    SKIPPED("已请假"),
    ;

    /** 是否算作「有推进」。PARTIAL 也算，用于完成率的加权计算。 */
    val isEngaged: Boolean get() = this == DONE || this == PARTIAL

    /** 是否已结算（不再等待用户操作）。 */
    val isSettled: Boolean get() = this != PENDING
}

/** 任务模板的重复规则。 */
enum class RepeatRule(val label: String) {
    DAILY("每日"),
    WEEKLY_DAYS("指定星期"),
    EVERY_N_DAYS("每 N 天"),
}

/** 积分变动原因。用于流水审计与统计归因。 */
enum class PointReason(val label: String) {
    CHECK_IN_ON_TIME("按时打卡"),
    CHECK_IN_LATE("迟到打卡"),
    CHECK_IN_MAKEUP("补卡"),
    OVER_TARGET("超额完成"),
    KEYSTONE_BONUS("关键任务加成"),
    FULL_DAY_BONUS("全天满勤"),
    STREAK_7("连续 7 天"),
    STREAK_30("连续 30 天"),
    STREAK_100("连续 100 天"),
    MISSED_PENALTY("漏卡扣分"),
    FOCUS_SESSION("专注时长"),
    ACHIEVEMENT("成就奖励"),
    COMMITMENT_SETTLED("承诺结算"),
    MANUAL_ADJUST("手动调整"),
}

/** 心情标签，用于当日复盘。 */
enum class Mood(val label: String, val emoji: String) {
    GREAT("很充实", "😃"),
    GOOD("还不错", "🙂"),
    FLAT("一般", "😐"),
    TIRED("有点累", "😪"),
    BAD("很糟糕", "😣"),
}

/** 成就解锁条件类型。判定逻辑集中在 AchievementEvaluator 里按此枚举分派。 */
enum class AchievementCondition {
    FIRST_CHECK_IN,
    SLOT_COUNT,
    STREAK_DAYS,
    FULL_DAY_COUNT,
    SUBJECT_TOTAL_MINUTES,
    SINGLE_DAY_FOCUS_MINUTES,
    MONTH_COMPLETION_RATE,
    PHASE_FINISHED,
    DDAY_MILESTONE,
    TOTAL_CHECK_INS,
    COMEBACK,
    EARLY_BIRD_STREAK,
    NIGHT_OWL_FIX,
}

/** 专注计时模式。 */
enum class FocusMode(val label: String) {
    POMODORO("番茄钟"),
    COUNT_UP("正计时"),
}

/** 承诺结算结果。 */
enum class CommitmentStatus(val label: String) {
    ACTIVE("进行中"),
    SUCCEEDED("已达成"),
    FAILED("未达成"),
    ABANDONED("已放弃"),
}

/** 阶段性立誓可选择的约束维度。 */
enum class CommitmentMetric(val label: String, val unit: String) {
    COMPLETION_RATE("平均完成度", "%"),
    FOCUS_MINUTES("累计专注时长", "分钟"),
    ACHIEVED_DAYS("达标天数", "天"),
}
