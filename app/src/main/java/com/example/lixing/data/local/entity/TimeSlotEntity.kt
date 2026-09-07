package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID
import java.time.LocalTime

/**
 * 时段模板（早读 / 上午 / 午间 / 下午 / 晚间 / 睡前）。
 *
 * 关于跨夜：endTime 小于 startTime 视为跨越午夜（如 23:30-00:30）。
 * 判定逻辑统一走 domain 层的 SlotWindow，实体这里只存原始值。
 */
@Entity(
    tableName = "time_slot",
    foreignKeys = [
        ForeignKey(
            entity = StudyPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["plan_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("plan_id")],
)
data class TimeSlotEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "plan_id")
    val planId: String,

    val name: String,

    @ColumnInfo(name = "start_time")
    val startTime: LocalTime,

    @ColumnInfo(name = "end_time")
    val endTime: LocalTime,

    /** 生效星期的位掩码，见 WeekdayMask。默认每天。 */
    @ColumnInfo(name = "weekday_mask")
    val weekdayMask: Int = 0b111_1111,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    /** 该时段的用途备注，比如「单词第 1 遍」。 */
    val note: String = "",

    @ColumnInfo(name = "required_task_count", defaultValue = "0")
    val requiredTaskCount: Int = 0,

    /** 关掉的时段不再生成任务、不再提醒。 */
    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,
    /** 多端同步的 Lamport 时钟戳，由同步引擎维护；0 = 从未参与同步。 */
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0")
    val syncModifiedAt: Long = 0,
)
