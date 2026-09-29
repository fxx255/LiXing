package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Immutable record of one confirmed planning change. */
@Entity(
    tableName = "plan_change_set",
    foreignKeys = [ForeignKey(entity = StudyPlanEntity::class, parentColumns = ["id"],
        childColumns = ["plan_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("plan_id")],
)
data class PlanChangeSetEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "plan_id") val planId: String,
    val source: String,
    @ColumnInfo(name = "actions_json") val actionsJson: String,
    @ColumnInfo(name = "selected_json") val selectedJson: String,
    @ColumnInfo(name = "base_fingerprint") val baseFingerprint: String,
    @ColumnInfo(name = "after_fingerprint") val afterFingerprint: String,
    @ColumnInfo(name = "applied_at") val appliedAt: Long,
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0") val syncModifiedAt: Long = 0,
)

/** Local evidence is written in the same transaction as the business rows. */
@Entity(tableName = "plan_change_receipt")
data class PlanChangeReceiptEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "operations_sha256") val operationsSha256: String,
    @ColumnInfo(name = "applied_at") val appliedAt: Long,
)
