package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.lixing.domain.model.Mood
import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskStatus
import com.example.lixing.domain.model.TaskType
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.time.LocalTime

/**
 * 当日任务实例。由模板物化而来。
 *
 * 这里刻意把模板的关键字段**快照**下来（标题、目标、时段时间、科目色等），
 * 原因是：用户中途改了模板或删了科目，历史记录不应该跟着变形，
 * 统计和回顾必须还原「当天实际要求的是什么」。
 *
 * (date, template_id) 建唯一索引，这是任务物化幂等的保证——重复物化会被
 * INSERT OR IGNORE 挡掉，不会产生重复任务。
 */
@Entity(
    tableName = "daily_task",
    foreignKeys = [
        ForeignKey(
            entity = TaskTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["template_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["date", "template_id"], unique = true),
        Index("date"),
        Index("template_id"),
        Index(value = ["date", "time_slot_id"]),
        Index("subject_id"),
    ],
)
data class DailyTaskEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    /** 归属日期（按用户设置的「每日起始时间」切日，非自然日）。 */
    val date: LocalDate,

    /** 来源模板。模板被删时置空，但任务记录保留。 */
    @ColumnInfo(name = "template_id")
    val templateId: String?,

    // ---------- 模板快照字段 ----------
    @ColumnInfo(name = "subject_id")
    val subjectId: String,

    @ColumnInfo(name = "subject_name")
    val subjectName: String,

    @ColumnInfo(name = "subject_color_argb")
    val subjectColorArgb: Int,

    @ColumnInfo(name = "time_slot_id")
    val timeSlotId: String,

    @ColumnInfo(name = "slot_name")
    val slotName: String,

    @ColumnInfo(name = "slot_start")
    val slotStart: LocalTime,

    @ColumnInfo(name = "slot_end")
    val slotEnd: LocalTime,

    @ColumnInfo(name = "slot_sort_order")
    val slotSortOrder: Int = 0,

    @ColumnInfo(name = "slot_required_task_count", defaultValue = "0")
    val slotRequiredTaskCount: Int = 0,

    val title: String,

    @ColumnInfo(name = "task_type")
    val taskType: TaskType,

    @ColumnInfo(name = "target_type")
    val targetType: TargetType,

    /** 当天的目标量。允许单独调整今日目标而不动模板。 */
    @ColumnInfo(name = "target_value")
    val targetValue: Int,

    @ColumnInfo(name = "is_keystone")
    val isKeystone: Boolean = false,

    val note: String = "",

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    // ---------- 执行状态 ----------
    val status: TaskStatus = TaskStatus.PENDING,

    /** 实际完成量。BOOLEAN 类型完成时记 1。 */
    @ColumnInfo(name = "actual_value")
    val actualValue: Int = 0,

    /** 打卡时刻。未打卡为 null。 */
    @ColumnInfo(name = "checked_at")
    val checkedAt: Instant? = null,

    /** 是否在时段结束后才打的卡（积分打折）。 */
    @ColumnInfo(name = "is_late")
    val isLate: Boolean = false,

    /** 是否为隔日补卡。 */
    @ColumnInfo(name = "is_makeup")
    val isMakeup: Boolean = false,

    /** 补卡原因，写入流水便于自我审计。 */
    @ColumnInfo(name = "makeup_reason")
    val makeupReason: String? = null,

    /** 由专注计时累计进来的分钟数，用于区分手动填报与实测时长。 */
    @ColumnInfo(name = "focused_minutes")
    val focusedMinutes: Int = 0,

    // ---------- 打卡详情（文字 + 拍照） ----------
    /** 打卡时填写的详细记录，如「做了 1000 题第 3 节，错 5 道，错因：粗算」。 */
    @ColumnInfo(name = "checkin_note")
    val checkinNote: String? = null,

    /** 打卡拍照的本地文件路径（如错题、笔记照片），可空。 */
    @ColumnInfo(name = "checkin_photo")
    val checkinPhoto: String? = null,

    // ---------- 当日复盘 ----------
    val mood: Mood? = null,

    /** 一句话记录。 */
    @ColumnInfo(name = "reflection")
    val reflection: String? = null,

    /** 多端同步的 Lamport 时钟戳，由同步引擎维护；0 = 从未参与同步。 */
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0")
    val syncModifiedAt: Long = 0,
)
