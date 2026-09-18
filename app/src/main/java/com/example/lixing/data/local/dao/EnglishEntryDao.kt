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

    // New cards are identified by never having been reviewed, not by consecutive successes.
    @Query("SELECT * FROM english_entry WHERE type IN ('WORD', 'PHRASE') AND (review_last_at IS NOT NULL OR review_reps > 0) AND (review_due_at IS NULL OR review_due_at <= :now) ORDER BY review_due_at, id")
    suspend fun dueReviews(now: Long): List<EnglishEntryEntity>

    @Query("SELECT * FROM english_entry WHERE type IN ('WORD', 'PHRASE') AND review_last_at IS NULL AND review_reps = 0 ORDER BY created_at, id LIMIT :limit")
    suspend fun newCards(limit: Int): List<EnglishEntryEntity>

    @Query("SELECT COUNT(*) FROM english_entry WHERE type IN ('WORD', 'PHRASE') AND review_last_at IS NULL AND review_reps = 0")
    suspend fun countNew(): Int

    @Query("SELECT COUNT(*) FROM english_entry WHERE type IN ('WORD', 'PHRASE') AND (review_last_at IS NOT NULL OR review_reps > 0) AND (review_due_at IS NULL OR review_due_at <= :now)")
    suspend fun countDueReview(now: Long): Int

    @Query("SELECT COUNT(*) FROM english_entry WHERE type IN ('WORD', 'PHRASE') AND COALESCE(first_learned_at, review_last_at) >= :start AND COALESCE(first_learned_at, review_last_at) < :end")
    suspend fun learnedBetween(start: Long, end: Long): Int

    @Query("SELECT COUNT(*) FROM english_entry WHERE type IN ('WORD', 'PHRASE')")
    suspend fun countReviewable(): Int

    @Query("SELECT MIN(review_due_at) FROM english_entry WHERE type IN ('WORD', 'PHRASE') AND review_last_at IS NOT NULL AND review_interval_days = 0 AND review_due_at > :now")
    suspend fun nextLearningDue(now: Long): Long?

    @Query("SELECT * FROM english_review_log WHERE entry_id = :entryId ORDER BY reviewed_at, id")
    suspend fun history(entryId: String): List<com.example.lixing.data.local.entity.EnglishReviewLogEntity>

    @Query("SELECT * FROM english_review_log WHERE id = :id")
    suspend fun reviewLog(id: String): com.example.lixing.data.local.entity.EnglishReviewLogEntity?

    @Upsert
    suspend fun upsertLog(log: com.example.lixing.data.local.entity.EnglishReviewLogEntity)

    @Query("SELECT * FROM dictionary_cache WHERE word = :word")
    suspend fun dictionaryCache(word: String): com.example.lixing.data.local.entity.DictionaryCacheEntity?

    @Upsert
    suspend fun cacheDictionary(entry: com.example.lixing.data.local.entity.DictionaryCacheEntity)
}
