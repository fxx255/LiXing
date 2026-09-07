package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.util.UUID
import androidx.room.PrimaryKey

/**
 * 科目（数学 / 英语 / 政治 / 专业课）。
 * 颜色存 ARGB Int，方便直接构造 Compose 的 Color；图标存名字，UI 层映射到 ImageVector。
 */
@Entity(
    tableName = "subject",
    foreignKeys = [
        ForeignKey(
            entity = StudyPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["plan_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("plan_id")],
)
data class SubjectEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "plan_id")
    val planId: String,

    val name: String,

    /** ARGB 色值。 */
    @ColumnInfo(name = "color_argb")
    val colorArgb: Int,

    /** 目标总时长（分钟）。0 表示不设目标。用于「单科累计 X 小时」类成就与进度展示。 */
    @ColumnInfo(name = "target_total_minutes")
    val targetTotalMinutes: Int = 0,

    /** 图标标识，见 UI 层的 SubjectIcons 映射表。 */
    @ColumnInfo(name = "icon_key")
    val iconKey: String = "book",

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    /** 归档的科目不再生成任务，但保留历史统计。 */
    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false,
    /** 多端同步的 Lamport 时钟戳，由同步引擎维护；0 = 从未参与同步。 */
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0")
    val syncModifiedAt: Long = 0,
)
