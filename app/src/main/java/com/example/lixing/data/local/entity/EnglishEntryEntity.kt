package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.lixing.domain.english.EnglishEntryType
import java.util.UUID
import java.time.Instant

/** 用户主动记录的一条英语积累。全部内容只保存在本机和用户主动生成的版本备份中。 */
@Entity(
    tableName = "english_entry",
    indices = [
        Index("type"),
        Index("updated_at"),
    ],
)
data class EnglishEntryEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val type: EnglishEntryType,
    /** 单词、短语或完整句子的英文原文。 */
    val content: String,
    /** 用户填写的释义，不限制必须使用中文。 */
    val meaning: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant = createdAt,
    /** 多端同步的 Lamport 时钟戳，由同步引擎维护；0 = 从未参与同步。 */
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0")
    val syncModifiedAt: Long = 0,

    // ---------- 背诵复习（SM-2 间隔重复）----------
    /** 下次复习到期时间。null = 还没学过（新卡），视为立即可学。 */
    @ColumnInfo(name = "review_due_at")
    val reviewDueAt: Instant? = null,
    /** SM-2 难度系数（ease factor）；默认 2.5，下限 1.3。越大间隔拉得越快。 */
    @ColumnInfo(name = "review_ease", defaultValue = "2.5")
    val reviewEase: Double = DEFAULT_EASE,
    /** 当前复习间隔（天）。0 表示尚未进入稳定复习节奏。 */
    @ColumnInfo(name = "review_interval_days", defaultValue = "0")
    val reviewIntervalDays: Int = 0,
    /** 连续答对次数（reps）。0 = 新卡，尚未答对过一次。 */
    @ColumnInfo(name = "review_reps", defaultValue = "0")
    val reviewReps: Int = 0,
    /** 「忘记」次数，用于统计与后续算法调整。 */
    @ColumnInfo(name = "review_lapses", defaultValue = "0")
    val reviewLapses: Int = 0,
    /** 上次复习时间。 */
    @ColumnInfo(name = "review_last_at")
    val reviewLastAt: Instant? = null,
) {
    companion object {
        const val DEFAULT_EASE = 2.5
    }
}
