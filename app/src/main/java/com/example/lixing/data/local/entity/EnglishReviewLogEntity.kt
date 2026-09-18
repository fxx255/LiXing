package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "english_review_log", indices = [Index("entry_id"), Index("reviewed_at")],
    foreignKeys = [ForeignKey(entity = EnglishEntryEntity::class, parentColumns = ["id"],
        childColumns = ["entry_id"], onDelete = ForeignKey.CASCADE)])
data class EnglishReviewLogEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "entry_id") val entryId: String,
    val grade: String,
    @ColumnInfo(name = "reviewed_at") val reviewedAt: Long,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "before_state") val beforeState: String,
    @ColumnInfo(name = "after_state") val afterState: String,
    @ColumnInfo(name = "scheduler_version") val schedulerVersion: String,
    @ColumnInfo(defaultValue = "0") val undone: Boolean = false,
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0") val syncModifiedAt: Long = 0,
)
