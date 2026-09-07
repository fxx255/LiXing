package com.example.lixing.data.repository

import com.example.lixing.data.local.dao.EnglishEntryDao
import com.example.lixing.data.local.entity.EnglishEntryEntity
import com.example.lixing.di.IoDispatcher
import com.example.lixing.domain.english.EnglishEntryType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EnglishEntryRepository @Inject constructor(
    private val dao: EnglishEntryDao,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    fun observe(query: String, type: EnglishEntryType?): Flow<List<EnglishEntryEntity>> =
        dao.observe(query.trim(), type)

    /** 读取单条积累，供 AI 助手的编辑/删除建议做本地校验。 */
    suspend fun get(id: String): EnglishEntryEntity? = withContext(io) { dao.get(id) }

    suspend fun save(
        existing: EnglishEntryEntity?,
        type: EnglishEntryType,
        content: String,
        meaning: String,
    ): String = withContext(io) {
        val cleanContent = content.trim()
        val cleanMeaning = meaning.trim()
        require(cleanContent.isNotEmpty()) { "请输入要积累的英文内容" }
        require(cleanMeaning.isNotEmpty()) { "请输入释义" }
        require(cleanContent.length <= MAX_CONTENT_LENGTH) { "英文内容不能超过 $MAX_CONTENT_LENGTH 个字符" }
        require(cleanMeaning.length <= MAX_MEANING_LENGTH) { "释义不能超过 $MAX_MEANING_LENGTH 个字符" }

        val now = Instant.now()
        val entry = EnglishEntryEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            type = type,
            content = cleanContent,
            meaning = cleanMeaning,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        dao.upsert(entry)
        entry.id
    }

    suspend fun delete(entry: EnglishEntryEntity) = withContext(io) { dao.delete(entry) }

    companion object {
        const val MAX_CONTENT_LENGTH = 500
        const val MAX_MEANING_LENGTH = 2_000
    }
}
