package com.example.lixing.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.lixing.data.local.entity.FocusSessionEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** 专注记录读写。 */
@Dao
interface FocusSessionDao {

    /** 进行中的会话（ended_at 为空）。进程被杀后恢复计时状态靠它。 */
    @Query("SELECT * FROM focus_session WHERE ended_at IS NULL ORDER BY started_at DESC LIMIT 1")
    suspend fun getRunningSession(): FocusSessionEntity?

    @Query("SELECT * FROM focus_session WHERE ended_at IS NULL ORDER BY started_at DESC LIMIT 1")
    fun observeRunningSession(): Flow<FocusSessionEntity?>

    @Query("SELECT * FROM focus_session WHERE id = :id")
    suspend fun getSession(id: String): FocusSessionEntity?

    @Query("SELECT * FROM focus_session WHERE date = :date ORDER BY started_at")
    fun observeSessionsOfDay(date: LocalDate): Flow<List<FocusSessionEntity>>

    @Query("SELECT * FROM focus_session WHERE date BETWEEN :from AND :to ORDER BY started_at")
    suspend fun getSessionsBetween(from: LocalDate, to: LocalDate): List<FocusSessionEntity>

    @Query(
        """
        SELECT COALESCE(SUM(effective_minutes), 0) FROM focus_session
        WHERE date = :date AND ended_at IS NOT NULL
        """,
    )
    suspend fun getMinutesOfDay(date: LocalDate): Int

    @Query(
        """
        SELECT COALESCE(SUM(effective_minutes), 0) FROM focus_session
        WHERE subject_id = :subjectId AND ended_at IS NOT NULL
        """,
    )
    suspend fun getMinutesOfSubject(subjectId: String): Int

    @Query("SELECT COALESCE(SUM(effective_minutes), 0) FROM focus_session WHERE ended_at IS NOT NULL")
    suspend fun getTotalMinutes(): Int

    /** 完成的番茄钟个数，用于成就。 */
    @Query("SELECT COUNT(*) FROM focus_session WHERE is_completed = 1 AND mode = 'POMODORO'")
    suspend fun countCompletedPomodoros(): Int

    @Insert
    suspend fun insert(session: FocusSessionEntity)

    @Update
    suspend fun update(session: FocusSessionEntity)

    @Query("UPDATE focus_session SET interruption_count = interruption_count + 1 WHERE id = :id")
    suspend fun incrementInterruption(id: String)

    @Query("UPDATE focus_session SET interruption_count = interruption_count + :count WHERE id = :id")
    suspend fun addInterruptions(id: String, count: Int)

    @Query("DELETE FROM focus_session WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM focus_session")
    suspend fun deleteAll()
}
