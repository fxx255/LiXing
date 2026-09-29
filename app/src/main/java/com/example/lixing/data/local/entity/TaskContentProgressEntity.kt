package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** Current cumulative completion snapshot for a daily task. Earlier snapshots remain auditable. */
@Entity(
    tableName = "task_content_progress",
    foreignKeys = [
        ForeignKey(
            entity = DailyTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["daily_task_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("daily_task_id"), Index("goal_id"), Index("supersedes_id")],
)
data class TaskContentProgressEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "daily_task_id") val dailyTaskId: String,
    @ColumnInfo(name = "goal_id") val goalId: String? = null,
    @ColumnInfo(name = "resource_id") val resourceId: String? = null,
    @ColumnInfo(name = "round_key") val roundKey: String = "FIRST",
    /** RANGE or COUNT. RANGE stores closed intervals as versioned JSON. */
    val mode: String,
    @ColumnInfo(name = "completed_json") val completedJson: String = "",
    val quantity: Int = 0,
    @ColumnInfo(name = "supersedes_id") val supersedesId: String? = null,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0") val syncModifiedAt: Long = 0,
)
