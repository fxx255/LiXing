package com.example.lixing.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.example.lixing.data.local.entity.EnglishEntryEntity
import com.example.lixing.domain.english.EnglishEntryType
import kotlinx.coroutines.flow.Flow

@Dao
interface EnglishEntryDao {
    @Query(
        """
        SELECT * FROM english_entry
        WHERE (:type IS NULL OR type = :type)
          AND (
            :query = ''
            OR instr(lower(content), lower(:query)) > 0
            OR instr(lower(meaning), lower(:query)) > 0
          )
        ORDER BY updated_at DESC, id DESC
        """,
    )
    fun observe(query: String, type: EnglishEntryType?): Flow<List<EnglishEntryEntity>>

    @Query("SELECT * FROM english_entry WHERE id = :id LIMIT 1")
    suspend fun get(id: String): EnglishEntryEntity?

    @Upsert
    suspend fun upsert(entry: EnglishEntryEntity)

    @Delete
    suspend fun delete(entry: EnglishEntryEntity)

    @Query("DELETE FROM english_entry")
    suspend fun deleteAll()

    // ---------- 背诵复习 ----------

    /**
     * 待复习队列：先排「到期要复习的」，再排「全新没学过的」。
     *
     * - 到期复习：review_reps > 0 且 review_due_at <= :now
     * - 新卡：review_reps = 0（review_due_at 为空也算新卡）
     * 新学数量由调用方按每日上限截断，这里只负责排序与预取。
     */
    @Query(
        """
        SELECT * FROM english_entry
        WHERE type IN ('WORD', 'PHRASE')
          AND (
            (review_reps > 0 AND (review_due_at IS NULL OR review_due_at <= :now))
            OR review_reps = 0
          )
        ORDER BY (review_reps = 0) ASC, review_due_at ASC, created_at ASC
        LIMIT :limit
        """,
    )
    suspend fun dueQueue(now: Long, limit: Int): List<EnglishEntryEntity>

    /** 还没学过的新卡数量（受每日上限约束的对象）。 */
    @Query("SELECT COUNT(*) FROM english_entry WHERE type IN ('WORD', 'PHRASE') AND review_reps = 0")
    suspend fun countNew(): Int

    /** 已经到期、需要复习的卡片数量（复习不限量）。 */
    @Query(
        """
        SELECT COUNT(*) FROM english_entry
        WHERE type IN ('WORD', 'PHRASE')
          AND review_reps > 0
          AND (review_due_at IS NULL OR review_due_at <= :now)
        """,
    )
    suspend fun countDueReview(now: Long): Int

    /** 参与背诵的条目总数（单词 + 短语）。 */
    @Query("SELECT COUNT(*) FROM english_entry WHERE type IN ('WORD', 'PHRASE')")
    suspend fun countReviewable(): Int
}
