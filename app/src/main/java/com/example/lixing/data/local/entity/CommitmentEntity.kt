package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.lixing.domain.model.CommitmentStatus
import com.example.lixing.domain.model.CommitmentMetric
import java.util.UUID
import java.time.LocalDate

/**
 * 自我承诺。给某个阶段（或任意日期区间）设一个完成率目标，到期自动结算。
 * 纯本地记录，不涉及任何支付或对外承诺。
 */
@Entity(
    tableName = "commitment",
    foreignKeys = [
        ForeignKey(
            entity = PhaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["phase_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("phase_id")],
)
data class CommitmentEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    /** 承诺文案，如「强化阶段完成率不低于 85%」。 */
    val title: String,

    /** 关联阶段。可空 = 自定义区间。 */
    @ColumnInfo(name = "phase_id")
    val phaseId: String? = null,

    @ColumnInfo(name = "start_date")
    val startDate: LocalDate,

    @ColumnInfo(name = "end_date")
    val endDate: LocalDate,

    /** 目标完成率（百分比整数，如 85）。 */
    @ColumnInfo(name = "target_rate_percent")
    val targetRatePercent: Int = 85,

    @ColumnInfo(name = "metric", defaultValue = "'COMPLETION_RATE'")
    val metric: CommitmentMetric = CommitmentMetric.COMPLETION_RATE,

    /** 达成后的奖励积分。 */
    @ColumnInfo(name = "reward_points")
    val rewardPoints: Int = 100,

    /** 达成后由用户自行兑现的现实奖励，例如“看一场电影”。 */
    @ColumnInfo(name = "custom_reward", defaultValue = "''")
    val customReward: String = "",

    val status: CommitmentStatus = CommitmentStatus.ACTIVE,

    /** 结算时的实际完成率，未结算为 null。 */
    @ColumnInfo(name = "actual_rate_percent")
    val actualRatePercent: Int? = null,

    @ColumnInfo(name = "settled_date")
    val settledDate: LocalDate? = null,

    /** 给自己的一句话，结算时回看。 */
    val note: String = "",
    /** 多端同步的 Lamport 时钟戳，由同步引擎维护；0 = 从未参与同步。 */
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0")
    val syncModifiedAt: Long = 0,
)
