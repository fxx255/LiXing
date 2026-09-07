package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.lixing.domain.model.PointReason
import java.time.Instant
import java.util.UUID
import java.time.LocalDate

/**
 * 积分流水。只追加、不修改，所有积分变动都在这里留痕，便于回溯「这分是怎么来的」。
 *
 * [dedupeKey] 用来防止重复入账：同一个原因 + 同一个关联对象只能记一次。
 * 例如「连续 7 天」奖励在结算重跑时不应该再发一遍。格式约定见 PointRules。
 */
@Entity(
    tableName = "point_ledger",
    indices = [
        Index("date"),
        Index("daily_task_id"),
        Index(value = ["dedupe_key"], unique = true),
    ],
)
data class PointLedgerEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    val date: LocalDate,

    /** 变动值，可正可负。 */
    val delta: Int,

    val reason: PointReason,

    /** 关联的当日任务，可空（如连续奖励、成就奖励）。 */
    @ColumnInfo(name = "daily_task_id")
    val dailyTaskId: String? = null,

    /** 补充说明，展示在流水列表里。 */
    val detail: String = "",

    /**
     * 幂等键。同一 key 只允许入账一次（唯一索引 + INSERT OR IGNORE）。
     * 一次性奖励必须给 key；可重复发生的（如多次手动调整）可留 null。
     */
    @ColumnInfo(name = "dedupe_key")
    val dedupeKey: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
    /** 多端同步的 Lamport 时钟戳，由同步引擎维护；0 = 从未参与同步。 */
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0")
    val syncModifiedAt: Long = 0,
)
