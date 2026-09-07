package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.lixing.domain.model.FocusMode
import java.time.Instant
import java.util.UUID
import java.time.LocalDate

/**
 * 专注记录。一次番茄钟或一段正计时。
 *
 * [effectiveMinutes] 是「计入统计的分钟数」，与 end-start 可能不同：
 * 番茄钟提前放弃时按实际时长记，休息时段不计入。
 */
@Entity(
    tableName = "focus_session",
    foreignKeys = [
        ForeignKey(
            entity = DailyTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["daily_task_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = SubjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["subject_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("daily_task_id"), Index("subject_id"), Index("date")],
)
data class FocusSessionEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    /** 归属日期，按切日规则算，便于按天聚合。 */
    val date: LocalDate,

    /** 关联的当日任务。可空——允许不挂任务的自由专注。 */
    @ColumnInfo(name = "daily_task_id")
    val dailyTaskId: String? = null,

    @ColumnInfo(name = "subject_id")
    val subjectId: String? = null,

    @ColumnInfo(name = "started_at")
    val startedAt: Instant,

    /** 结束时刻。进行中的会话为 null。 */
    @ColumnInfo(name = "ended_at")
    val endedAt: Instant? = null,

    val mode: FocusMode = FocusMode.POMODORO,

    /** 计划时长（番茄钟设定值），正计时模式为 0。 */
    @ColumnInfo(name = "planned_minutes")
    val plannedMinutes: Int = 0,

    /** 计入统计的有效分钟数。 */
    @ColumnInfo(name = "effective_minutes")
    val effectiveMinutes: Int = 0,

    /** 切出应用的次数（专注守护统计，不做强制拦截）。 */
    @ColumnInfo(name = "interruption_count")
    val interruptionCount: Int = 0,

    /** 是否把时长累加进任务进度。 */
    @ColumnInfo(name = "counts_toward_task")
    val countsTowardTask: Boolean = true,

    /** 是否正常完成（番茄钟走完全程）。 */
    @ColumnInfo(name = "is_completed")
    val isCompleted: Boolean = false,
    /** 多端同步的 Lamport 时钟戳，由同步引擎维护；0 = 从未参与同步。 */
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0")
    val syncModifiedAt: Long = 0,
)
