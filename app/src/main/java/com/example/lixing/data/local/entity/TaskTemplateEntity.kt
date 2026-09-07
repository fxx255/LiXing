package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.lixing.domain.model.RepeatRule
import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskType
import java.util.UUID
import java.time.LocalDate

/**
 * 任务模板。每天由 TaskMaterializer 按模板生成 [DailyTaskEntity] 实例。
 *
 * 生效范围有三层过滤，全部满足才会生成当天任务：
 * 1. 日期区间：[activeFrom] ~ [activeUntil]（都可空 = 不限）
 * 2. 阶段：[phaseId] 非空时只在该阶段内生效
 * 3. 重复规则：[repeatRule] + [weekdayMask] / [intervalDays]
 */
@Entity(
    tableName = "task_template",
    foreignKeys = [
        ForeignKey(
            entity = SubjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["subject_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TimeSlotEntity::class,
            parentColumns = ["id"],
            childColumns = ["time_slot_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PhaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["phase_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("subject_id"), Index("time_slot_id"), Index("phase_id")],
)
data class TaskTemplateEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "subject_id")
    val subjectId: String,

    @ColumnInfo(name = "time_slot_id")
    val timeSlotId: String,

    val title: String,

    @ColumnInfo(name = "task_type")
    val taskType: TaskType = TaskType.CUSTOM,

    @ColumnInfo(name = "target_type")
    val targetType: TargetType = TargetType.BOOLEAN,

    /** 目标量。BOOLEAN 时无意义，统一存 1。 */
    @ColumnInfo(name = "target_value")
    val targetValue: Int = 1,

    @ColumnInfo(name = "repeat_rule")
    val repeatRule: RepeatRule = RepeatRule.DAILY,

    /** WEEKLY_DAYS 时生效的星期掩码。 */
    @ColumnInfo(name = "weekday_mask")
    val weekdayMask: Int = 0b111_1111,

    /** EVERY_N_DAYS 的间隔天数，从 [anchorDate] 起算。 */
    @ColumnInfo(name = "interval_days")
    val intervalDays: Int = 1,

    /** EVERY_N_DAYS 的起算日。空则用计划开始日。 */
    @ColumnInfo(name = "anchor_date")
    val anchorDate: LocalDate? = null,

    /** 限定生效的阶段。空 = 全程有效。 */
    @ColumnInfo(name = "phase_id")
    val phaseId: String? = null,

    @ColumnInfo(name = "active_from")
    val activeFrom: LocalDate? = null,

    @ColumnInfo(name = "active_until")
    val activeUntil: LocalDate? = null,

    /** 关键任务：积分翻倍，首页有标记，也是「满勤」判定的必要项。 */
    @ColumnInfo(name = "is_keystone")
    val isKeystone: Boolean = false,

    val note: String = "",

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    /** 停用后不再生成新任务，历史记录保留。 */
    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,
    /** 多端同步的 Lamport 时钟戳，由同步引擎维护；0 = 从未参与同步。 */
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0")
    val syncModifiedAt: Long = 0,
)
