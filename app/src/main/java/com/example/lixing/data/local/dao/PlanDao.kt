package com.example.lixing.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.example.lixing.data.local.entity.PhaseEntity
import com.example.lixing.data.local.entity.StudyPlanEntity
import com.example.lixing.data.local.entity.SubjectEntity
import com.example.lixing.data.local.entity.TimeSlotEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** 计划、阶段、科目、时段的读写。全部返回 Flow，交给 ViewModel 转 StateFlow。 */
@Dao
interface PlanDao {

    // ---------------- StudyPlan ----------------

    @Query("SELECT * FROM study_plan WHERE is_active = 1 LIMIT 1")
    fun observeActivePlan(): Flow<StudyPlanEntity?>

    @Query("SELECT * FROM study_plan WHERE is_active = 1 LIMIT 1")
    suspend fun getActivePlan(): StudyPlanEntity?

    @Query("SELECT * FROM study_plan ORDER BY created_at DESC")
    fun observeAllPlans(): Flow<List<StudyPlanEntity>>

    @Query("SELECT * FROM study_plan WHERE id = :id")
    suspend fun getPlan(id: String): StudyPlanEntity?

    @Query("SELECT COUNT(*) FROM study_plan")
    suspend fun countPlans(): Int

    @Insert
    suspend fun insertPlan(plan: StudyPlanEntity)

    @Update
    suspend fun updatePlan(plan: StudyPlanEntity)

    @Delete
    suspend fun deletePlan(plan: StudyPlanEntity)

    @Query("UPDATE study_plan SET is_active = 0")
    suspend fun deactivateAllPlans()

    /** 切换启用的计划。同一时刻只允许一个 active。 */
    @Transaction
    suspend fun activatePlan(planId: String) {
        deactivateAllPlans()
        setPlanActive(planId)
    }

    @Query("UPDATE study_plan SET is_active = 1 WHERE id = :planId")
    suspend fun setPlanActive(planId: String)

    // ---------------- Phase ----------------

    @Query("SELECT * FROM phase WHERE plan_id = :planId ORDER BY sort_order, start_date")
    fun observePhases(planId: String): Flow<List<PhaseEntity>>

    @Query("SELECT * FROM phase WHERE plan_id = :planId ORDER BY sort_order, start_date")
    suspend fun getPhases(planId: String): List<PhaseEntity>

    @Query("SELECT * FROM phase WHERE id = :id")
    suspend fun getPhase(id: String): PhaseEntity?

    /**
     * 今天所处的阶段。区间重叠时取 sort_order 最小的那个，保证结果稳定。
     */
    @Query(
        """
        SELECT * FROM phase
        WHERE plan_id = :planId AND start_date <= :date AND end_date >= :date
        ORDER BY sort_order LIMIT 1
        """,
    )
    fun observeCurrentPhase(planId: String, date: LocalDate): Flow<PhaseEntity?>

    @Query(
        """
        SELECT * FROM phase
        WHERE plan_id = :planId AND start_date <= :date AND end_date >= :date
        ORDER BY sort_order LIMIT 1
        """,
    )
    suspend fun getCurrentPhase(planId: String, date: LocalDate): PhaseEntity?

    @Upsert
    suspend fun upsertPhase(phase: PhaseEntity)

    @Insert
    suspend fun insertPhases(phases: List<PhaseEntity>)

    @Delete
    suspend fun deletePhase(phase: PhaseEntity)

    // ---------------- Subject ----------------

    @Query(
        "SELECT * FROM subject WHERE plan_id = :planId AND is_archived = 0 ORDER BY sort_order, id",
    )
    fun observeSubjects(planId: String): Flow<List<SubjectEntity>>

    @Query("SELECT * FROM subject WHERE plan_id = :planId ORDER BY sort_order, id")
    suspend fun getSubjects(planId: String): List<SubjectEntity>

    @Query("SELECT * FROM subject WHERE id = :id")
    suspend fun getSubject(id: String): SubjectEntity?

    @Upsert
    suspend fun upsertSubject(subject: SubjectEntity)

    @Insert
    suspend fun insertSubjects(subjects: List<SubjectEntity>)

    @Delete
    suspend fun deleteSubject(subject: SubjectEntity)

    // ---------------- TimeSlot ----------------

    @Query(
        "SELECT * FROM time_slot WHERE plan_id = :planId AND is_enabled = 1 ORDER BY sort_order, start_time",
    )
    fun observeTimeSlots(planId: String): Flow<List<TimeSlotEntity>>

    @Query("SELECT * FROM time_slot WHERE plan_id = :planId ORDER BY sort_order, start_time")
    suspend fun getTimeSlots(planId: String): List<TimeSlotEntity>

    @Query("SELECT * FROM time_slot WHERE id = :id")
    suspend fun getTimeSlot(id: String): TimeSlotEntity?

    @Upsert
    suspend fun upsertTimeSlot(slot: TimeSlotEntity)

    @Insert
    suspend fun insertTimeSlots(slots: List<TimeSlotEntity>)

    @Delete
    suspend fun deleteTimeSlot(slot: TimeSlotEntity)

    // ---------------- 清库（导入 / 重置用） ----------------

    @Query("DELETE FROM study_plan")
    suspend fun deleteAllPlans()
}
