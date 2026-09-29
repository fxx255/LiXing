package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** Optional chapter/section outline. Question ranges do not need one row per question. */
@Entity(
    tableName = "learning_unit",
    foreignKeys = [
        ForeignKey(
            entity = StudyResourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["resource_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("resource_id"), Index("parent_id")],
)
data class LearningUnitEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "resource_id") val resourceId: String,
    @ColumnInfo(name = "parent_id") val parentId: String? = null,
    val title: String,
    val kind: String = "SECTION",
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "range_start") val rangeStart: Int? = null,
    @ColumnInfo(name = "range_end") val rangeEnd: Int? = null,
    @ColumnInfo(name = "numbering_scope") val numberingScope: String = "GLOBAL",
    @ColumnInfo(name = "estimated_minutes") val estimatedMinutes: Int? = null,
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0") val syncModifiedAt: Long = 0,
)
