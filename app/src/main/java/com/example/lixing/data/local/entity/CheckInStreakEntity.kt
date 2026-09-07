package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * 连续记录。全局单行（id 固定为 1），由结算逻辑维护。
 *
 * 「本月剩余补救卡」按 [rescueCardsMonth] 判断是否需要重置：
 * 读到的月份和当前月不一致时，先补满再使用。
 */
@Entity(tableName = "check_in_streak")
data class CheckInStreakEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ID,

    /** 当前连续天数。 */
    @ColumnInfo(name = "current_streak")
    val currentStreak: Int = 0,

    /** 历史最长。断签时用来展示「历史最长 N 天，重新开始」。 */
    @ColumnInfo(name = "longest_streak")
    val longestStreak: Int = 0,

    /** 最后一次达成的日期。判断连续是否延续靠它。 */
    @ColumnInfo(name = "last_achieved_date")
    val lastAchievedDate: LocalDate? = null,

    /** 本月剩余补救卡数量。 */
    @ColumnInfo(name = "rescue_cards_left")
    val rescueCardsLeft: Int = 1,

    /** 补救卡额度所属月份（yyyyMM），跨月自动重置。 */
    @ColumnInfo(name = "rescue_cards_month")
    val rescueCardsMonth: Int = 0,

    /** 累计达成天数（非连续），用于总量类成就。 */
    @ColumnInfo(name = "total_achieved_days")
    val totalAchievedDays: Int = 0,

    /** 本周已用补卡次数与所属周（yyyyWW），用于「每周补卡上限」。 */
    @ColumnInfo(name = "makeups_used_this_week")
    val makeupsUsedThisWeek: Int = 0,

    @ColumnInfo(name = "makeup_week_key")
    val makeupWeekKey: Int = 0,

    /** 本月已用请假天数与所属月份，用于「每月请假上限」。 */
    @ColumnInfo(name = "day_offs_used_this_month")
    val dayOffsUsedThisMonth: Int = 0,

    @ColumnInfo(name = "day_off_month_key")
    val dayOffMonthKey: Int = 0,
    /** 多端同步的 Lamport 时钟戳，由同步引擎维护；0 = 从未参与同步。 */
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0")
    val syncModifiedAt: Long = 0,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
