package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.util.UUID

/** An explicit availability override for one study day. Empty windows mean no availability. */
@Entity(
    tableName = "plan_day_policy",
    foreignKeys = [ForeignKey(
        entity = StudyPlanEntity::class,
        parentColumns = ["id"],
        childColumns = ["plan_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["plan_id", "study_date"], unique = true)],
)
data class PlanDayPolicyEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "plan_id") val planId: String,
    @ColumnInfo(name = "study_date") val studyDate: LocalDate,
    @ColumnInfo(name = "windows_json") val windowsJson: String,
    @ColumnInfo(name = "max_planned_minutes") val maxPlannedMinutes: Int? = null,
    @ColumnInfo(name = "is_locked") val isLocked: Boolean = false,
    val reason: String = "",
    val revision: Long = 1,
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0") val syncModifiedAt: Long = 0,
)
