package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID
import java.time.LocalDate

/**
 * 学习计划。允许存在多个计划（比如「考研」和「考证」），但同一时刻只有一个 active。
 */
@Entity(tableName = "study_plan")
data class StudyPlanEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    val name: String,

    /** 计划开始日。 */
    @ColumnInfo(name = "start_date")
    val startDate: LocalDate,

    /** 目标日（考试日），用于 D-Day 倒计时。 */
    @ColumnInfo(name = "target_date")
    val targetDate: LocalDate,

    /** 是否为当前启用的计划。首页只读 active 的那一个。 */
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    /** 备注/目标院校之类的自由文本。 */
    val note: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /** 多端同步的 Lamport 时钟戳，由同步引擎维护；0 = 从未参与同步。 */
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0")
    val syncModifiedAt: Long = 0,
)
