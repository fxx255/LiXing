package com.example.lixing.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomWarnings
import androidx.room.Update
import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.domain.model.TaskStatus
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/**
 * 当日任务读写。
 *
 * 物化用 [insertIgnore]（OnConflictStrategy.IGNORE）配合 (date, template_id) 唯一索引，
 * 这就是幂等的实现方式：重复物化直接被数据库挡掉，不需要先查再插。
 */
@Dao
interface DailyTaskDao {

    @Query("SELECT * FROM daily_task WHERE date = :date ORDER BY slot_sort_order, slot_start, sort_order, id")
    fun observeTasksOfDay(date: LocalDate): Flow<List<DailyTaskEntity>>

    @Query("SELECT * FROM daily_task WHERE date = :date ORDER BY slot_sort_order, slot_start, sort_order, id")
    suspend fun getTasksOfDay(date: LocalDate): List<DailyTaskEntity>

    @Query(
        """
        SELECT * FROM daily_task
        WHERE date BETWEEN :from AND :to
        ORDER BY date, slot_sort_order, slot_start, sort_order, id
        """,
    )
    fun observeTasksBetween(from: LocalDate, to: LocalDate): Flow<List<DailyTaskEntity>>

    @Query("SELECT * FROM daily_task WHERE date BETWEEN :from AND :to ORDER BY date, slot_start")
    suspend fun getTasksBetween(from: LocalDate, to: LocalDate): List<DailyTaskEntity>

    @Query("SELECT * FROM daily_task WHERE id = :id")
    suspend fun getTask(id: String): DailyTaskEntity?

    @Query("SELECT * FROM daily_task WHERE id = :id")
    fun observeTask(id: String): Flow<DailyTaskEntity?>

    @Query("SELECT * FROM daily_task WHERE date = :date AND time_slot_id = :slotId ORDER BY sort_order, id")
    suspend fun getTasksOfSlot(date: LocalDate, slotId: String): List<DailyTaskEntity>

    /** 是否已经为某天物化过任务。物化前先查这个，省掉一次批量 insert。 */
    @Query("SELECT COUNT(*) FROM daily_task WHERE date = :date")
    suspend fun countTasksOfDay(date: LocalDate): Int

    @Query("SELECT COUNT(*) FROM daily_task WHERE date = :date AND status = :status")
    suspend fun countByStatus(date: LocalDate, status: TaskStatus): Int

    /** 幂等插入：撞唯一索引 (date, template_id) 时直接忽略。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(tasks: List<DailyTaskEntity>)

    @Insert
    suspend fun insert(task: DailyTaskEntity)

    @Update
    suspend fun update(task: DailyTaskEntity)

    @Update
    suspend fun updateAll(tasks: List<DailyTaskEntity>)

    @Query("DELETE FROM daily_task WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM daily_task WHERE date = :date AND template_id = :templateId AND status = 'PENDING' AND focused_minutes = 0")
    suspend fun deletePendingTemplate(date: LocalDate, templateId: String)

    @Query("DELETE FROM daily_task WHERE date = :date AND time_slot_id = :slotId AND status = 'PENDING' AND focused_minutes = 0")
    suspend fun deletePendingSlot(date: LocalDate, slotId: String)

    @Query("DELETE FROM daily_task WHERE date = :date AND subject_id = :subjectId AND status = 'PENDING' AND focused_minutes = 0")
    suspend fun deletePendingSubject(date: LocalDate, subjectId: String)

    /**
     * 把某天所有仍处于 PENDING 的任务标记为 MISSED。当天结算时调用。
     * 请假（SKIPPED）不受影响。
     */
    @Query(
        """
        UPDATE daily_task SET status = 'MISSED'
        WHERE date = :date AND status = 'PENDING'
        """,
    )
    suspend fun markPendingAsMissed(date: LocalDate)

    /** 整天请假：把待做任务置为 SKIPPED（已完成的保留成绩）。 */
    @Query(
        """
        UPDATE daily_task SET status = 'SKIPPED'
        WHERE date = :date AND status IN ('PENDING', 'MISSED')
        """,
    )
    suspend fun markDayAsSkipped(date: LocalDate)

    /** 取消整天请假：把 SKIPPED 恢复为 PENDING。 */
    @Query(
        """
        UPDATE daily_task SET status = 'PENDING'
        WHERE date = :date AND status = 'SKIPPED'
        """,
    )
    suspend fun unmarkDaySkipped(date: LocalDate)

    /** 某时段请假。 */
    @Query(
        """
        UPDATE daily_task SET status = 'SKIPPED'
        WHERE date = :date AND time_slot_id = :slotId AND status IN ('PENDING', 'MISSED')
        """,
    )
    suspend fun markSlotAsSkipped(date: LocalDate, slotId: String)

    /** 把当日复盘（心情 + 一句话）写到当天所有任务上，方便按任务回看当时状态。 */
    @Query("UPDATE daily_task SET mood = :mood, reflection = :reflection WHERE date = :date")
    suspend fun updateDayReflection(date: LocalDate, mood: String?, reflection: String?)

    /** 累加专注时长到任务上。 */
    @Query(
        """
        UPDATE daily_task
        SET focused_minutes = focused_minutes + :minutes
        WHERE id = :taskId
        """,
    )
    suspend fun addFocusedMinutes(taskId: String, minutes: Int)

    // ---------------- 统计用聚合 ----------------

    /** 各科目累计有效分钟数（按 MINUTES 类型任务的实际值 + 专注时长）。 */
    @Query(
        """
        SELECT subject_id AS subjectId, subject_name AS subjectName,
               SUM(CASE WHEN target_type = 'MINUTES' THEN actual_value ELSE 0 END)
                 + SUM(focused_minutes) AS minutes
        FROM daily_task
        WHERE date BETWEEN :from AND :to AND status != 'SKIPPED'
        GROUP BY subject_id
        """,
    )
    suspend fun getSubjectMinutes(from: LocalDate, to: LocalDate): List<SubjectMinutes>

    /** 各时段完成率，用于找出「最容易崩的时段」。 */
    @Query(
        """
        SELECT time_slot_id AS slotId, slot_name AS slotName,
               COUNT(*) AS total,
               SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END) AS done
        FROM daily_task
        WHERE date BETWEEN :from AND :to AND status != 'SKIPPED'
        GROUP BY time_slot_id
        ORDER BY MIN(slot_sort_order), MIN(slot_start)
        """,
    )
    @SuppressWarnings(RoomWarnings.QUERY_MISMATCH)
    suspend fun getSlotCompletion(from: LocalDate, to: LocalDate): List<SlotCompletion>

    /** 某时段的按时打卡次数，用于「早读 N 次」类成就。 */
    @Query(
        """
        SELECT COUNT(*) FROM daily_task
        WHERE slot_name = :slotName AND status = 'DONE' AND is_late = 0
        """,
    )
    suspend fun countOnTimeInSlot(slotName: String): Int

    /** 全部已打卡次数。 */
    @Query("SELECT COUNT(*) FROM daily_task WHERE status IN ('DONE', 'PARTIAL')")
    suspend fun countAllCheckIns(): Int

    /** 最早的一次打卡时间，用于「首次打卡」成就。 */
    @Query("SELECT MIN(checked_at) FROM daily_task WHERE checked_at IS NOT NULL")
    suspend fun getFirstCheckInAt(): Instant?

    /** 某科目历史累计分钟数（不限区间）。 */
    @Query(
        """
        SELECT COALESCE(
            SUM(CASE WHEN target_type = 'MINUTES' THEN actual_value ELSE 0 END)
              + SUM(focused_minutes), 0)
        FROM daily_task
        WHERE subject_name = :subjectName AND status != 'SKIPPED'
        """,
    )
    suspend fun getTotalMinutesOfSubject(subjectName: String): Int

    @Query("DELETE FROM daily_task")
    suspend fun deleteAll()

    /**
     * 只删「某日及以后」的任务。换计划时用：未来的任务要按新模板重建，
     * 但历史打卡（含详细记录与照片）必须原样保留。
     */
    @Query("DELETE FROM daily_task WHERE date >= :from")
    suspend fun deleteFrom(from: LocalDate)

    /** 供 Kotlin 手动去重用的全表查询。 */
    @Query("SELECT * FROM daily_task ORDER BY date, title, time_slot_id, actual_value DESC, id ASC")
    suspend fun getAllForDedupe(): List<DailyTaskEntity>
}

/** 科目时长聚合结果。 */
data class SubjectMinutes(
    val subjectId: String,
    val subjectName: String,
    val minutes: Int,
)

/** 时段完成情况聚合结果。 */
data class SlotCompletion(
    val slotId: String,
    val slotName: String,
    val total: Int,
    val done: Int,
    val weightedRate: Float? = null,
) {
    val rate: Float get() = weightedRate ?: if (total == 0) 0f else done.toFloat() / total
}
