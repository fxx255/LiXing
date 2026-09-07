package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.lixing.domain.model.AchievementCondition
import java.util.UUID
import java.time.Instant

/**
 * 成就。全量成就在首次启动时按 AchievementCatalog 预置入库，
 * 解锁只是把 [unlockedAt] 从 null 改成时间戳，这样「未解锁的成就」也能展示为灰卡片。
 *
 * [code] 是稳定标识（唯一索引），版本升级新增成就时按 code upsert，不会重复。
 */
@Entity(
    tableName = "achievement",
    indices = [Index(value = ["code"], unique = true)],
)
data class AchievementEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    /** 稳定标识，如 "STREAK_7"、"SUBJECT_MINUTES_1200"。 */
    val code: String,

    val title: String,

    val description: String,

    /** 图标标识，UI 层映射到 ImageVector。 */
    @ColumnInfo(name = "icon_key")
    val iconKey: String = "trophy",

    /** 条件类型，判定逻辑按此分派。 */
    val condition: AchievementCondition,

    /** 条件阈值，含义随 [condition] 变化（天数 / 次数 / 分钟数 / 百分比）。 */
    val threshold: Int = 0,

    /** 附加参数，如 SLOT_COUNT 需要指定时段名、SUBJECT_TOTAL_MINUTES 需要科目名。 */
    @ColumnInfo(name = "extra_key")
    val extraKey: String? = null,

    /** 解锁奖励积分。 */
    @ColumnInfo(name = "reward_points")
    val rewardPoints: Int = 0,

    /** 分档：1 铜 / 2 银 / 3 金。用于卡片配色和排序。 */
    val tier: Int = 1,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    /** 解锁时刻。null = 未解锁。 */
    @ColumnInfo(name = "unlocked_at")
    val unlockedAt: Instant? = null,

    /** 解锁时的进度快照，用于卡片上显示「达成时：连续 32 天」。 */
    @ColumnInfo(name = "unlocked_detail")
    val unlockedDetail: String? = null,

    /** 当前进度值，用于未解锁卡片上的进度条。 */
    @ColumnInfo(name = "progress")
    val progress: Int = 0,
    /** 多端同步的 Lamport 时钟戳，由同步引擎维护；0 = 从未参与同步。 */
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0")
    val syncModifiedAt: Long = 0,
)
