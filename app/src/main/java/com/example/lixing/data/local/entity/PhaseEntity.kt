package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID
import java.time.LocalDate

/**
 * 阶段（基础 / 强化 / 冲刺）。按日期区间划分，首页自动高亮「今天落在哪个阶段」。
 * 阶段区间允许留空隙但不应重叠；重叠时取 sortOrder 最小的那个作为当前阶段。
 */
@Entity(
    tableName = "phase",
    foreignKeys = [
        ForeignKey(
            entity = StudyPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["plan_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("plan_id"), Index(value = ["plan_id", "start_date"])],
)
data class PhaseEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "plan_id")
    val planId: String,

    val name: String,

    @ColumnInfo(name = "start_date")
    val startDate: LocalDate,

    @ColumnInfo(name = "end_date")
    val endDate: LocalDate,

    /** 阶段说明，比如「重理解，不要死抠难题」。 */
    val description: String = "",

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
    /** 多端同步的 Lamport 时钟戳，由同步引擎维护；0 = 从未参与同步。 */
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0")
    val syncModifiedAt: Long = 0,
)
