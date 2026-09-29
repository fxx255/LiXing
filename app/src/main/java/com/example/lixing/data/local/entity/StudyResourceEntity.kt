package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** A named book, question bank, course, or other source for dated learning content. */
@Entity(
    tableName = "study_resource",
    foreignKeys = [
        ForeignKey(
            entity = StudyPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["plan_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("plan_id"), Index("subject_id")],
)
data class StudyResourceEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "plan_id") val planId: String,
    @ColumnInfo(name = "subject_id") val subjectId: String,
    val name: String,
    val edition: String = "",
    val kind: String = "BOOK",
    @ColumnInfo(name = "is_archived") val isArchived: Boolean = false,
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0") val syncModifiedAt: Long = 0,
)
