package com.example.lixing.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.example.lixing.data.local.entity.LearningGoalEntity
import com.example.lixing.data.local.entity.LearningUnitEntity
import com.example.lixing.data.local.entity.ManualStudyTimeEntity
import com.example.lixing.data.local.entity.PlanDayPolicyEntity
import com.example.lixing.data.local.entity.PlanChangeSetEntity
import com.example.lixing.data.local.entity.PlanChangeReceiptEntity
import com.example.lixing.data.local.entity.PlanningSyncConflictEntity
import com.example.lixing.data.local.entity.ScheduledTaskEntity
import com.example.lixing.data.local.entity.StudyResourceEntity
import com.example.lixing.data.local.entity.TaskContentProgressEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface PlanningDao {
    @Query("SELECT * FROM planning_sync_conflict WHERE resolved_at IS NULL ORDER BY detected_at DESC")
    fun observeSyncConflicts(): Flow<List<PlanningSyncConflictEntity>>

    @Upsert suspend fun upsertSyncConflict(conflict: PlanningSyncConflictEntity)

    @Query("UPDATE planning_sync_conflict SET resolved_at = :resolvedAt WHERE peer_id = :peerId AND resolved_at IS NULL")
    suspend fun resolveSyncConflictsForPeer(peerId: String, resolvedAt: Long)

    @Query("SELECT * FROM plan_change_receipt WHERE id = :id")
    suspend fun getChangeReceipt(id: String): PlanChangeReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChangeReceipt(receipt: PlanChangeReceiptEntity)

    @Query("SELECT * FROM plan_change_set WHERE id = :id")
    suspend fun getChangeSet(id: String): PlanChangeSetEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChangeSet(change: PlanChangeSetEntity)

    @Query("SELECT * FROM plan_day_policy WHERE plan_id = :planId AND study_date = :date LIMIT 1")
    suspend fun getDayPolicy(planId: String, date: LocalDate): PlanDayPolicyEntity?

    @Query("SELECT * FROM plan_day_policy WHERE plan_id = :planId AND study_date BETWEEN :from AND :to ORDER BY study_date")
    fun observeDayPolicies(planId: String, from: LocalDate, to: LocalDate): Flow<List<PlanDayPolicyEntity>>

    @Query("SELECT * FROM plan_day_policy WHERE plan_id = :planId AND study_date BETWEEN :from AND :to ORDER BY study_date")
    suspend fun getDayPolicies(planId: String, from: LocalDate, to: LocalDate): List<PlanDayPolicyEntity>

    @Upsert suspend fun upsertDayPolicy(policy: PlanDayPolicyEntity)

    @Query("DELETE FROM plan_day_policy WHERE id = :id")
    suspend fun deleteDayPolicy(id: String)

    @Query("SELECT * FROM study_resource WHERE plan_id = :planId ORDER BY name, id")
    suspend fun getResources(planId: String): List<StudyResourceEntity>

    @Query("SELECT * FROM study_resource WHERE plan_id = :planId ORDER BY name, id")
    fun observeResources(planId: String): Flow<List<StudyResourceEntity>>

    @Query("SELECT * FROM study_resource WHERE id = :id")
    suspend fun getResource(id: String): StudyResourceEntity?

    @Upsert suspend fun upsertResource(resource: StudyResourceEntity)

    @Query("SELECT * FROM learning_unit WHERE resource_id = :resourceId ORDER BY sort_order, id")
    suspend fun getUnits(resourceId: String): List<LearningUnitEntity>

    @Upsert suspend fun upsertUnit(unit: LearningUnitEntity)

    @Query("SELECT * FROM learning_goal WHERE plan_id = :planId ORDER BY priority DESC, due_date, id")
    suspend fun getGoals(planId: String): List<LearningGoalEntity>

    @Query("SELECT * FROM learning_goal WHERE plan_id = :planId ORDER BY priority DESC, due_date, id")
    fun observeGoals(planId: String): Flow<List<LearningGoalEntity>>

    @Query("SELECT * FROM learning_goal WHERE id = :id")
    suspend fun getGoal(id: String): LearningGoalEntity?

    @Upsert suspend fun upsertGoal(goal: LearningGoalEntity)

    @Query("SELECT * FROM scheduled_task WHERE plan_id = :planId AND study_date = :date ORDER BY start_time, id")
    suspend fun getScheduledTasks(planId: String, date: LocalDate): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_task WHERE plan_id = :planId AND study_date BETWEEN :from AND :to ORDER BY study_date, start_time, id")
    fun observeScheduledTasks(planId: String, from: LocalDate, to: LocalDate): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_task WHERE plan_id = :planId AND study_date BETWEEN :from AND :to ORDER BY study_date, start_time, id")
    suspend fun getScheduledTasksBetween(planId: String, from: LocalDate, to: LocalDate): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_task WHERE id = :id")
    suspend fun getScheduledTask(id: String): ScheduledTaskEntity?

    @Query("SELECT * FROM scheduled_task WHERE plan_id = :planId AND study_date = :date AND source_template_id = :templateId LIMIT 1")
    suspend fun getTemplateOccurrence(planId: String, date: LocalDate, templateId: String): ScheduledTaskEntity?

    @Upsert suspend fun upsertScheduledTask(task: ScheduledTaskEntity)

    @Query("SELECT * FROM task_content_progress WHERE daily_task_id = :taskId ORDER BY recorded_at, id")
    suspend fun getContentProgress(taskId: String): List<TaskContentProgressEntity>

    @Query("SELECT p.* FROM task_content_progress p INNER JOIN daily_task t ON t.id = p.daily_task_id WHERE t.date = :date ORDER BY p.recorded_at, p.id")
    fun observeContentProgressOfDate(date: LocalDate): Flow<List<TaskContentProgressEntity>>

    @Query("SELECT * FROM task_content_progress WHERE goal_id = :goalId ORDER BY recorded_at, id")
    suspend fun getGoalProgress(goalId: String): List<TaskContentProgressEntity>

    @Query("SELECT p.* FROM task_content_progress p INNER JOIN learning_goal g ON g.id = p.goal_id WHERE g.plan_id = :planId ORDER BY p.recorded_at, p.id")
    fun observeGoalProgressForPlan(planId: String): Flow<List<TaskContentProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertContentProgress(progress: TaskContentProgressEntity): Long

    @Query("SELECT * FROM manual_study_time WHERE study_date BETWEEN :from AND :to ORDER BY study_date, recorded_at, id")
    suspend fun getManualTimes(from: LocalDate, to: LocalDate): List<ManualStudyTimeEntity>

    @Query("SELECT * FROM manual_study_time WHERE study_date BETWEEN :from AND :to ORDER BY study_date, recorded_at, id")
    fun observeManualTimes(from: LocalDate, to: LocalDate): Flow<List<ManualStudyTimeEntity>>

    @Query("SELECT * FROM manual_study_time WHERE daily_task_id = :taskId ORDER BY recorded_at, id")
    suspend fun getManualTimesOfTask(taskId: String): List<ManualStudyTimeEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertManualTime(time: ManualStudyTimeEntity): Long
}
