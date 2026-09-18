package com.example.lixing.data.repository

import androidx.room.withTransaction
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.local.entity.EnglishReviewLogEntity
import com.example.lixing.domain.english.FsrsScheduler
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.ZoneId
import java.time.LocalTime
import com.example.lixing.data.local.dao.EnglishEntryDao
import com.example.lixing.data.local.entity.EnglishEntryEntity
import com.example.lixing.di.IoDispatcher
import com.example.lixing.domain.english.DueCounts
import com.example.lixing.domain.english.EnglishEntryType
import com.example.lixing.domain.english.ReviewGrade
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EnglishEntryRepository @Inject constructor(
    private val dao: EnglishEntryDao,
    private val database: LiXingDatabase,
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
        database.withTransaction {
            val fresh = existing?.let { dao.get(it.id) ?: error("词条已被删除，请返回后重试") }
            val entry = fresh?.copy(type = type, content = cleanContent, meaning = cleanMeaning, updatedAt = now)
                ?: EnglishEntryEntity(type = type, content = cleanContent, meaning = cleanMeaning, createdAt = now, updatedAt = now)
            dao.upsert(entry)
            entry.id
        }
    }

    suspend fun delete(entry: EnglishEntryEntity) = withContext(io) { dao.delete(entry) }

    // ---------- 背诵复习 ----------

    suspend fun dueCounts(now: Instant = Instant.now(), dailyNewLimit: Int,
        dayStart: LocalTime = LocalTime.of(4, 0), zone: ZoneId = ZoneId.systemDefault()): DueCounts = withContext(io) {
        val local = now.atZone(zone)
        val date = if (local.toLocalTime() < dayStart) local.toLocalDate().minusDays(1) else local.toLocalDate()
        val start = date.atTime(dayStart).atZone(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atTime(dayStart).atZone(zone).toInstant().toEpochMilli()
        val remaining = (dailyNewLimit - dao.learnedBetween(start, end)).coerceAtLeast(0)
        val newTotal = dao.countNew()
        DueCounts(dao.countDueReview(now.toEpochMilli()), minOf(newTotal, remaining), newTotal, dao.countReviewable())
    }

    suspend fun reviewQueue(now: Instant = Instant.now(), dailyNewLimit: Int,
        dayStart: LocalTime = LocalTime.of(4, 0)): List<EnglishEntryEntity> = withContext(io) {
        val counts = dueCounts(now, dailyNewLimit, dayStart)
        dao.dueReviews(now.toEpochMilli()) + dao.newCards(counts.newAvailable)
    }

    suspend fun nextLearningDue(now: Long) = withContext(io) { dao.nextLearningDue(now) }
    suspend fun history(id: String) = withContext(io) { dao.history(id).filterNot { it.undone }.asReversed() }

    /** One transaction and unique event ID make duplicate submissions harmless. */
    suspend fun grade(entry: EnglishEntryEntity, grade: ReviewGrade, eventId: String,
        sessionId: String, now: Instant = Instant.now()): EnglishReviewLogEntity = withContext(io) {
        database.withTransaction {
            dao.reviewLog(eventId)?.let { return@withTransaction it }
            val fresh = dao.get(entry.id) ?: error("词条已被删除")
            require(fresh.memory() == entry.memory()) { "这个词的复习进度已变化，请刷新后重试" }
            val before = fresh.memory()
            val after = FsrsScheduler.next(before, grade, now.toEpochMilli())
            val log = EnglishReviewLogEntity(eventId, entry.id, grade.name, now.toEpochMilli(), sessionId,
                Json.encodeToString(before), Json.encodeToString(after), FsrsScheduler.VERSION)
            dao.upsert(fresh.withMemory(after))
            dao.upsertLog(log)
            log
        }
    }

    suspend fun undo(eventId: String): Boolean = withContext(io) {
        database.withTransaction {
            val log = dao.reviewLog(eventId) ?: return@withTransaction false
            if (log.undone) return@withTransaction false
            val active = dao.history(log.entryId).filterNot { it.undone }
            require(active.lastOrNull()?.id == eventId) { "该词已有更新的背诵记录，不能撤销" }
            dao.upsertLog(log.copy(undone = true))
            reconcileEnglishMemory(dao, log.entryId)
            true
        }
    }

    companion object {
        const val MAX_CONTENT_LENGTH = 500
        const val MAX_MEANING_LENGTH = 2_000
    }
}
