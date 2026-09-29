package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.util.UUID

/** Minutes studied without a timer; corrections supersede an earlier record. */
@Entity(tableName = "manual_study_time", indices = [Index("daily_task_id"), Index("subject_id"), Index("study_date")])
data class ManualStudyTimeEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "daily_task_id") val dailyTaskId: String? = null,
    @ColumnInfo(name = "plan_id") val planId: String? = null,
    @ColumnInfo(name = "subject_id") val subjectId: String? = null,
    @ColumnInfo(name = "subject_name") val subjectName: String = "",
    @ColumnInfo(name = "study_date") val studyDate: LocalDate,
    val minutes: Int,
    @ColumnInfo(name = "counts_toward_task") val countsTowardTask: Boolean = true,
    @ColumnInfo(name = "supersedes_id") val supersedesId: String? = null,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0") val syncModifiedAt: Long = 0,
)
