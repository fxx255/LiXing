package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * 用户档案。全局单行（id 固定为 1）。
 *
 * [totalPoints] 是流水的物化和——每次入账时同步累加，避免统计页每次都去 SUM 全表。
 * 等级由 totalPoints 反算（见 LevelRules），这里存一份是为了检测「升级」这个瞬时事件。
 */
@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ID,

    /** 昵称，默认空，展示时回退到「同学」。 */
    val nickname: String = "",

    /** 累计总积分（等于流水 delta 之和）。 */
    @ColumnInfo(name = "total_points")
    val totalPoints: Int = 0,

    /** 当前等级。由 totalPoints 派生，存一份便于检测升级事件。 */
    val level: Int = 1,

    /** 当前称号，随等级解锁。 */
    val title: String = "初心者",

    /** 累计专注分钟数。 */
    @ColumnInfo(name = "total_focus_minutes")
    val totalFocusMinutes: Int = 0,

    /** 累计打卡次数（DONE + PARTIAL）。 */
    @ColumnInfo(name = "total_check_ins")
    val totalCheckIns: Int = 0,

    /** 首次使用日期，用于「加入天数」。 */
    @ColumnInfo(name = "joined_date")
    val joinedDate: LocalDate,
    /** 多端同步的 Lamport 时钟戳，由同步引擎维护；0 = 从未参与同步。 */
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0")
    val syncModifiedAt: Long = 0,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
