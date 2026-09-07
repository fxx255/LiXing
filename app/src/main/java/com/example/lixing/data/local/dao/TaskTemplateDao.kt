package com.example.lixing.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.example.lixing.data.local.entity.TaskTemplateEntity
import kotlinx.coroutines.flow.Flow

/**
 * 任务模板读写。
 *
 * 模板本身不跨表连接查询——物化时需要的科目/时段信息由 Repository 分别取好后组装，
 * 这样 DAO 保持简单，也避免 Room 关系查询在字段快照场景下的歧义。
 */
@Dao
interface TaskTemplateDao {

    @Query(
        """
        SELECT t.* FROM task_template t
        INNER JOIN subject s ON s.id = t.subject_id
        WHERE s.plan_id = :planId
        ORDER BY t.sort_order, t.id
        """,
    )
    fun observeTemplates(planId: String): Flow<List<TaskTemplateEntity>>

    @Query(
        """
        SELECT t.* FROM task_template t
        INNER JOIN subject s ON s.id = t.subject_id
        WHERE s.plan_id = :planId AND t.is_enabled = 1
        ORDER BY t.sort_order, t.id
        """,
    )
    suspend fun getEnabledTemplates(planId: String): List<TaskTemplateEntity>

    @Query("SELECT * FROM task_template WHERE subject_id = :subjectId ORDER BY sort_order, id")
    fun observeTemplatesBySubject(subjectId: String): Flow<List<TaskTemplateEntity>>

    @Query("SELECT * FROM task_template WHERE time_slot_id = :slotId ORDER BY sort_order, id")
    fun observeTemplatesBySlot(slotId: String): Flow<List<TaskTemplateEntity>>

    @Query("SELECT * FROM task_template WHERE id = :id")
    suspend fun getTemplate(id: String): TaskTemplateEntity?

    @Query("SELECT COUNT(*) FROM task_template")
    suspend fun countTemplates(): Int

    @Upsert
    suspend fun upsertTemplate(template: TaskTemplateEntity)

    @Insert
    suspend fun insertTemplates(templates: List<TaskTemplateEntity>)

    @Delete
    suspend fun deleteTemplate(template: TaskTemplateEntity)

    @Query("UPDATE task_template SET is_enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}
