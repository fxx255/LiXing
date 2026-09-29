package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskType
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** A confirmed, date-specific plan. Template occurrences have one stable override row. */
@Entity(
    tableName = "scheduled_task",
    foreignKeys = [
        ForeignKey(
            entity = StudyPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["plan_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("plan_id"),
        Index("study_date"),
        Index("subject_id"),
        Index("resource_id"),
        Index("goal_id"),
        Index(value = ["plan_id", "study_date", "source_template_id"], unique = true),
    ],
)
data class ScheduledTaskEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "plan_id") val planId: String,
    @ColumnInfo(name = "study_date") val studyDate: LocalDate,
    @ColumnInfo(name = "source_template_id") val sourceTemplateId: String? = null,
    @ColumnInfo(name = "subject_id") val subjectId: String,
    @ColumnInfo(name = "time_slot_id") val timeSlotId: String,
    @ColumnInfo(name = "resource_id") val resourceId: String? = null,
    @ColumnInfo(name = "goal_id") val goalId: String? = null,
    @ColumnInfo(name = "round_key") val roundKey: String = "FIRST",
    val title: String,
    @ColumnInfo(name = "task_type") val taskType: TaskType = TaskType.CUSTOM,
    @ColumnInfo(name = "target_type") val targetType: TargetType = TargetType.BOOLEAN,
    @ColumnInfo(name = "target_value") val targetValue: Int = 1,
    @ColumnInfo(name = "content_json") val contentJson: String = "",
    @ColumnInfo(name = "planned_minutes") val plannedMinutes: Int? = null,
    @ColumnInfo(name = "start_time") val startTime: LocalTime? = null,
    @ColumnInfo(name = "end_time") val endTime: LocalTime? = null,
    /** ACTIVE for standalone; OVERRIDE / SUPPRESS / DEFAULT for template occurrences. */
    val state: String = "ACTIVE",
    @ColumnInfo(name = "is_locked") val isLocked: Boolean = false,
    @ColumnInfo(name = "baseline_minutes") val baselineMinutes: Int? = plannedMinutes,
    @ColumnInfo(name = "baseline_value") val baselineValue: Int? = targetValue,
    val note: String = "",
    @ColumnInfo(name = "revision") val revision: Long = 1,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0") val syncModifiedAt: Long = 0,
)
