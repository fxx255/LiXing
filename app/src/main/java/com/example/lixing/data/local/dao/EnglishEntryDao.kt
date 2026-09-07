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
}
