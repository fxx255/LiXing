package com.example.lixing.data.repository

import com.example.lixing.data.local.dao.FocusSessionDao
import com.example.lixing.data.local.entity.FocusSessionEntity
import com.example.lixing.di.IoDispatcher
import com.example.lixing.domain.model.FocusMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 专注记录的读写 + 计时生命周期。
 *
 * 「进行中」用 ended_at == null 表示。进程被杀后靠 [getRunningSession] 恢复，
 * 所以计时状态必须落库，不能只存在内存里。
 */
@Singleton
class FocusRepository @Inject constructor(
    private val dao: FocusSessionDao,
    private val taskRepository: TaskRepository,
    private val gamification: GamificationRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    fun observeRunningSession(): Flow<FocusSessionEntity?> = dao.observeRunningSession()

    fun observeSessionsOfDay(date: LocalDate): Flow<List<FocusSessionEntity>> =
        dao.observeSessionsOfDay(date)

    suspend fun getRunningSession(): FocusSessionEntity? = withContext(io) { dao.getRunningSession() }

    suspend fun getSession(id: String): FocusSessionEntity? = withContext(io) { dao.getSession(id) }

    suspend fun getMinutesOfDay(date: LocalDate): Int = withContext(io) { dao.getMinutesOfDay(date) }

    suspend fun getTotalMinutes(): Int = withContext(io) { dao.getTotalMinutes() }

    /** 开始一段专注，返回会话 id。若已有进行中的会话则直接复用。 */
    suspend fun start(
        date: LocalDate,
        dailyTaskId: String?,
        subjectId: String?,
        mode: FocusMode,
        plannedMinutes: Int,
    ): String = withContext(io) {
        dao.getRunningSession()?.let { return@withContext it.id }

        val session = FocusSessionEntity(
            date = date,
            dailyTaskId = dailyTaskId,
            subjectId = subjectId,
            startedAt = Instant.now(),
            mode = mode,
            plannedMinutes = plannedMinutes,
        )
        dao.insert(session)
        session.id
    }

    /**
     * 结束当前会话。
     * @param effectiveMinutes 实际有效分钟数（扣除放弃的部分）
     * @param completed 是否正常走完
     */
    suspend fun finish(
        sessionId: String,
        effectiveMinutes: Int,
        completed: Boolean,
        countsTowardTask: Boolean = true,
    ) = withContext(io) {
        val session = dao.getSession(sessionId) ?: return@withContext
        dao.update(
            session.copy(
                endedAt = Instant.now(),
                effectiveMinutes = effectiveMinutes,
                isCompleted = completed,
                countsTowardTask = countsTowardTask,
            ),
        )

        // 计入任务进度 + 档案专注时长 + 积分
        if (countsTowardTask && effectiveMinutes > 0) {
            session.dailyTaskId?.let { taskId ->
                taskRepository.addFocusedMinutes(taskId, effectiveMinutes)
            }
            gamification.addFocusMinutes(effectiveMinutes)

            val points = com.example.lixing.domain.rules.PointRules.focusPoints(effectiveMinutes)
            if (points > 0) {
                gamification.award(
                    date = session.date,
                    delta = points,
                    reason = com.example.lixing.domain.model.PointReason.FOCUS_SESSION,
                    detail = "专注 $effectiveMinutes 分钟",
                    dedupeKey = com.example.lixing.domain.rules.PointRules.keyFocus(sessionId),
                )
            }
        }
    }

    /** 放弃当前会话（不计入有效时长，但保留记录）。 */
    suspend fun abandon(sessionId: String) = withContext(io) {
        val session = dao.getSession(sessionId) ?: return@withContext
        // 放弃的会话不算一次有效专注，因此其中断次数也不应进入统计。
        dao.update(
            session.copy(
                endedAt = Instant.now(),
                effectiveMinutes = 0,
                interruptionCount = 0,
                isCompleted = false,
            ),
        )
    }

    suspend fun incrementInterruption(sessionId: String) =
        withContext(io) { dao.incrementInterruption(sessionId) }

    suspend fun addInterruptions(sessionId: String, count: Int) {
        if (count <= 0) return
        withContext(io) { dao.addInterruptions(sessionId, count) }
    }
}
