package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.util.UUID

/** A bounded content target. Progress is tracked separately from study minutes. */
@Entity(
    tableName = "learning_goal",
    foreignKeys = [
        ForeignKey(
            entity = StudyPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["plan_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("plan_id"), Index("subject_id"), Index("resource_id")],
)
data class LearningGoalEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "plan_id") val planId: String,
    @ColumnInfo(name = "subject_id") val subjectId: String,
    @ColumnInfo(name = "resource_id") val resourceId: String?,
    val title: String,
    /** Versioned ContentSelection JSON. */
    @ColumnInfo(name = "scope_json") val scopeJson: String = "",
    @ColumnInfo(name = "initial_progress_json") val initialProgressJson: String = "",
    @ColumnInfo(name = "start_date") val startDate: LocalDate? = null,
    @ColumnInfo(name = "due_date") val dueDate: LocalDate? = null,
    @ColumnInfo(name = "round_key") val roundKey: String = "FIRST",
    @ColumnInfo(name = "activity_type") val activityType: String = "PRACTICE",
    @ColumnInfo(name = "priority") val priority: Int = 0,
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0") val syncModifiedAt: Long = 0,
)
