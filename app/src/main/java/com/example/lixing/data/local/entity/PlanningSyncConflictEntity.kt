package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Rejected remote batch kept locally while its peer cursor remains unchanged. */
@Entity(tableName = "planning_sync_conflict")
data class PlanningSyncConflictEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "peer_id") val peerId: String,
    val reason: String,
    val summary: String,
    @ColumnInfo(name = "rows_json") val rowsJson: String,
    @ColumnInfo(name = "detected_at") val detectedAt: Long,
    @ColumnInfo(name = "resolved_at") val resolvedAt: Long? = null,
)
