package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.lixing.domain.model.Mood
import java.time.LocalDate

/**
 * 每日汇总。一天一行，在当天结算（次日 00:05 的 Worker 或用户当天手动触发）时写入。
 *
 * 存在的意义：
 * - 连续记录、热力图、完成率趋势都只需要读这张表，不用每次去扫 daily_task；
 * - 「当天是否达成」是个需要冻结的判定结果，事后改模板不该影响历史。
 */
@Entity(tableName = "day_record")
data class DayRecordEntity(
    @PrimaryKey
    val date: LocalDate,

    /** 当天应做任务总数（不含请假）。 */
    @ColumnInfo(name = "total_tasks")
    val totalTasks: Int = 0,

    @ColumnInfo(name = "done_tasks")
    val doneTasks: Int = 0,

    @ColumnInfo(name = "partial_tasks")
    val partialTasks: Int = 0,

    @ColumnInfo(name = "missed_tasks")
    val missedTasks: Int = 0,

    @ColumnInfo(name = "skipped_tasks")
    val skippedTasks: Int = 0,

    /** 加权完成率 0f~1f：DONE 记 1，PARTIAL 按实际/目标折算。 */
    @ColumnInfo(name = "completion_rate")
    val completionRate: Float = 0f,

    /** 当天专注总分钟数。 */
    @ColumnInfo(name = "focus_minutes")
    val focusMinutes: Int = 0,

    /** 当天净得积分。 */
    @ColumnInfo(name = "points_earned")
    val pointsEarned: Int = 0,

    /** 是否达成（完成率 ≥ 阈值，默认 60%），决定连续记录是否延续。 */
    @ColumnInfo(name = "is_achieved")
    val isAchieved: Boolean = false,

    /** 是否满勤（全部任务 DONE 且关键任务无遗漏）。 */
    @ColumnInfo(name = "is_full_day")
    val isFullDay: Boolean = false,

    /** 整天请假。请假日不打断连续记录、不计入完成率统计。 */
    @ColumnInfo(name = "is_day_off")
    val isDayOff: Boolean = false,

    /** 是否用掉了补救卡来保住连续记录。 */
    @ColumnInfo(name = "used_rescue_card")
    val usedRescueCard: Boolean = false,

    /** 当日复盘：心情 + 一句话。 */
    val mood: Mood? = null,

    val reflection: String? = null,

    /** 是否已结算。未结算的当天允许继续打卡改写。 */
    @ColumnInfo(name = "is_settled")
    val isSettled: Boolean = false,
    /** 多端同步的 Lamport 时钟戳，由同步引擎维护；0 = 从未参与同步。 */
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0")
    val syncModifiedAt: Long = 0,
)
