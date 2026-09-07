package com.example.lixing.data.repository

import com.example.lixing.data.local.dao.EnglishEntryDao
import com.example.lixing.data.local.entity.EnglishEntryEntity
import com.example.lixing.di.IoDispatcher
import com.example.lixing.domain.english.DueCounts
import com.example.lixing.domain.english.EnglishEntryType
import com.example.lixing.domain.english.ReviewGrade
import com.example.lixing.domain.english.Sm2Scheduler
import com.example.lixing.domain.english.Sm2State
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

    // ---------- 背诵复习 ----------

    /** 待背诵数量快照：到期要复习的 + 还能新学的（新学受每日上限约束）。 */
    suspend fun dueCounts(now: Instant = Instant.now(), dailyNewLimit: Int): DueCounts =
        withContext(io) {
            val dueReview = dao.countDueReview(now.toEpochMilli())
            val newTotal = dao.countNew()
            DueCounts(
                dueReview = dueReview,
                newAvailable = minOf(newTotal, dailyNewLimit.coerceAtLeast(0)),
                newTotal = newTotal,
                reviewableTotal = dao.countReviewable(),
            )
        }

    /**
     * 取一轮背诵队列：到期复习的全部 + 新卡最多 [dailyNewLimit] 条。
     *
     * 复习不限量（到期就该复习），只有新学受上限控制，避免一天灌进几百条新词。
     */
    suspend fun reviewQueue(
        now: Instant = Instant.now(),
        dailyNewLimit: Int,
    ): List<EnglishEntryEntity> = withContext(io) {
        val counts = dueCounts(now, dailyNewLimit)
        val limit = counts.dueReview + counts.newAvailable
        if (limit <= 0) return@withContext emptyList()
        dao.dueQueue(now.toEpochMilli(), limit)
    }

    /**
     * 给一条卡片打分并写回复习状态（SM-2 调度由 [Sm2Scheduler] 纯函数算出）。
     *
     * @return 更新后的条目，供 UI 直接复用
     */
    suspend fun grade(
        entry: EnglishEntryEntity,
        grade: ReviewGrade,
        now: Instant = Instant.now(),
    ): EnglishEntryEntity = withContext(io) {
        val result = Sm2Scheduler.next(
            state = Sm2State(
                ease = entry.reviewEase,
                intervalDays = entry.reviewIntervalDays,
                reps = entry.reviewReps,
                lapses = entry.reviewLapses,
            ),
            grade = grade,
            now = now,
        )
        val updated = entry.copy(
            reviewEase = result.state.ease,
            reviewIntervalDays = result.state.intervalDays,
            reviewReps = result.state.reps,
            reviewLapses = result.state.lapses,
            reviewDueAt = result.dueAt,
            reviewLastAt = now,
        )
        dao.upsert(updated)
        updated
    }

    companion object {
        const val MAX_CONTENT_LENGTH = 500
        const val MAX_MEANING_LENGTH = 2_000
    }
}
