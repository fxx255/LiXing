package com.example.lixing.data.repository

import com.example.lixing.data.local.dao.AssistantRequestDao
import com.example.lixing.data.local.entity.AssistantMessageEntity
import com.example.lixing.data.local.entity.AssistantRequestEntity
import com.example.lixing.di.IoDispatcher
import com.example.lixing.domain.assistant.AssistantFailureKind
import com.example.lixing.domain.assistant.AssistantRequestStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本机在途请求记录仓库。
 *
 * 只做「持久化 + 状态迁移」这一件事：不含任何协程作用域、不持有 ViewModel，
 * 因此可以被应用级管理器安全共享，也能在没有任何界面存活时继续工作。
 *
 * **跨端隔离**：本表不在 `SYNC_TABLE_SPECS` 里，也不在备份导出表清单里，
 * 所以进行中的请求绝不会因为同步或备份恢复而在别的设备上被当成待执行任务。
 */
@Singleton
class AssistantRequestRepository @Inject constructor(
    private val dao: AssistantRequestDao,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 事务：用户消息 + 预留回答位置 + 请求记录，**一起**落盘。
     *
     * 三件事必须在同一个事务里，否则会出现两类不可恢复的坏状态：
     * - 用户消息已进会话、请求记录没落盘 → 一条永远无法重试的孤儿用户消息；
     * - 回答占位已进会话、请求没落盘 → 一个永远空着的回答气泡（还会进同步/备份）。
     *
     * 提交失败时异常向上抛，调用方保留草稿并明确报错。
     */
    suspend fun createRequest(
        conversationId: String,
        userMessageId: String,
        answerMessageId: String,
        attemptId: String,
        userText: String,
        attachmentPaths: List<String>,
        snapshotJson: String,
        /** 用户消息的展示内容（图片题的紧凑文案）；null 表示与正文一致。 */
        userDisplayContent: String? = null,
        status: AssistantRequestStatus = AssistantRequestStatus.PREPARING,
    ): AssistantRequestEntity = withContext(io) {
        // user / answer 两条消息必须严格先后：Room 把 Instant 落库成 epochMillis（毫秒精度），
        // 各自独立 Instant.now() 在同一毫秒内会得到相同 created_at，而 AssistantChatDao
        // 按 created_at ASC, id ASC 排序 —— UUID 的字典序是随机的，会让 answer 偶发
        // 排在 user 前面（重启恢复测试曾复现消息次序反转）。所以只捕获一次时间，
        // answer 显式加 1ms 保证必然晚于 user；秒级粒度截断时 1ms 同样在毫秒列上可分辨。
        val now = Instant.now()
        val user = AssistantMessageEntity(
            id = userMessageId,
            conversationId = conversationId,
            role = "user",
            content = userText,
            imagePaths = encodeList(attachmentPaths),
            displayContent = userDisplayContent,
            createdAt = now,
        )
        val answer = AssistantMessageEntity(
            id = answerMessageId,
            conversationId = conversationId,
            role = "assistant",
            content = "",
            // 数据库毫秒精度要求 user → answer 严格先后：+1ms 保证排序稳定。
            createdAt = now.plusMillis(1),
        )
        val request = AssistantRequestEntity(
            conversationId = conversationId,
            userMessageId = userMessageId,
            answerMessageId = answerMessageId,
            attemptId = attemptId,
            status = status.name,
            userText = userText,
            attachmentPaths = encodeList(attachmentPaths),
            snapshotJson = snapshotJson,
        )
        dao.insertRequestWithMessages(request, user, answer)
        request
    }

    suspend fun get(requestId: String): AssistantRequestEntity? = withContext(io) {
        dao.getRequest(requestId)
    }

    suspend fun forConversation(conversationId: String): List<AssistantRequestEntity> = withContext(io) {
        dao.getRequestsForConversation(conversationId)
    }

    /**
     * 观察会话的请求记录：状态迁移、重试、终态都会推送，
     * 让界面在不依赖一次性事件的情况下恢复重试入口与进行中状态。
     */
    fun observeForConversation(conversationId: String): kotlinx.coroutines.flow.Flow<List<AssistantRequestEntity>> =
        dao.observeRequestsForConversation(conversationId)

    suspend fun activeForConversation(conversationId: String): AssistantRequestEntity? = withContext(io) {
        dao.getActiveRequestForConversation(conversationId)
    }

    suspend fun anyActive(): AssistantRequestEntity? = withContext(io) { dao.getAnyActiveRequest() }

    suspend fun countActive(): Int = withContext(io) { dao.countActiveRequests() }

    /**
     * 标记进入 RUNNING（已发出第一个 HTTP 请求）。
     *
     * 带 attemptId + 在途状态条件：旧 attempt 的迟到调用不会把新 attempt 的状态改掉，
     * 也不会把已经收尾的请求复活成 RUNNING。
     */
    suspend fun markRunning(requestId: String, attemptId: String): Boolean = withContext(io) {
        dao.updateStatusIfInFlight(
            requestId = requestId,
            attemptId = attemptId,
            status = AssistantRequestStatus.RUNNING.name,
            updatedAt = Instant.now(),
        ) > 0
    }

    /**
     * 节流写入当前轮部分正文。
     *
     * **只在 PREPARING/RUNNING 时生效**：收尾/取消之后再调不会把终态改回 RUNNING
     * （那正是「完成瞬间闪过 COMPLETED、随即永远停在生成中」的根因）。
     * 返回 false 表示这次写入被拒（attempt 过期或已是终态），属于正常情况。
     */
    suspend fun savePartial(
        requestId: String,
        attemptId: String,
        partialText: String,
        status: AssistantRequestStatus = AssistantRequestStatus.RUNNING,
    ): Boolean = withContext(io) {
        dao.updatePartial(requestId, attemptId, partialText, status.name, Instant.now()) > 0
    }

    /**
     * 原子收尾：正文写进固定回答位置 + 标记 COMPLETED。
     *
     * 返回 false 表示收尾被拒（attempt 过期 / 已终态 / 回答位置不属于本请求）。
     * **调用方必须把它当失败处理**，不能吞掉 —— 否则界面会显示一条永远不会出现的
     * 「已完成」状态，而库里其实还是 RUNNING。
     */
    suspend fun complete(
        requestId: String,
        attemptId: String,
        answerMessageId: String,
        text: String,
    ): Boolean = withContext(io) {
        // 「回答位置已被删除」会在事务内抛异常并回滚；对外统一成 false（收尾被拒），
        // 调用方据此走中断路径，而不是把异常当成未知崩溃。
        runCatching {
            dao.completeRequest(requestId, attemptId, answerMessageId, text, Instant.now())
        }.getOrDefault(false)
    }

    /**
     * 原子收尾（确认轮）：正文 + 图片 + 待确认信封 + COMPLETED 在**同一个事务**里。
     *
     * 计划/英语动作只在**完整且有效**的回复上解析，未闭合 JSON、部分正文、
     * 推理通道都不能触发动作 —— 所以这里的 [answerContent] 与 `pendingReview`
     * 必须来自同一个已解析对象，由调用方保证。
     *
     * 返回 false 表示收尾被拒，调用方必须上报失败而不是假成功。
     */
    suspend fun completeWithReview(
        requestId: String,
        attemptId: String,
        answerMessageId: String,
        text: String,
        pendingReview: String,
        answerImagePaths: List<String>,
    ): Boolean = withContext(io) {
        // 同 complete()：回答位置不存在时事务回滚，这里统一成 false。
        runCatching {
            dao.completeRequestWithEnvelope(
                requestId = requestId,
                attemptId = attemptId,
                answerMessageId = answerMessageId,
                text = text,
                pendingReview = pendingReview,
                imagePaths = encodeList(answerImagePaths),
                updatedAt = Instant.now(),
            )
        }.getOrDefault(false)
    }

    /**
     * 中断：保留已生成的部分正文（**只留在请求记录里**），记录失败归类。
     *
     * 不把部分正文写进 `assistant_message`：那条消息会进跨端同步与备份，
     * 半成品不该被同步出去。界面按 requestId 把本机部分正文合并展示。
     */
    suspend fun interrupt(
        requestId: String,
        attemptId: String,
        partialText: String,
        kind: AssistantFailureKind,
        message: String?,
    ): Boolean = withContext(io) {
        dao.interruptRequest(
            requestId = requestId,
            attemptId = attemptId,
            partialText = partialText,
            kind = kind.name,
            message = message.orEmpty(),
            updatedAt = Instant.now(),
        )
    }

    /** 取消：保留本机部分正文与终态，同样不写进会话消息。 */
    suspend fun cancel(requestId: String, attemptId: String, partialText: String): Boolean = withContext(io) {
        dao.cancelRequest(
            requestId = requestId,
            attemptId = attemptId,
            partialText = partialText,
            updatedAt = Instant.now(),
        )
    }

    /**
     * 换一个 attemptId 并重置状态；用于重试。
     *
     * **原子 CAS**：条件为 `attempt_id = 当前值 AND status = 'INTERRUPTED'`，
     * 所以并发双击重试只有一个能成功，旧 attempt 的迟到重试也会被拦下。
     * 返回 null 表示本次重试没抢到（已被别人重试 / 状态不允许 / 记录不存在）。
     */
    suspend fun beginRetry(
        requestId: String,
        newAttemptId: String,
        refreshedSnapshotJson: String?,
        refreshedAttachments: List<String>?,
    ): AssistantRequestEntity? = withContext(io) {
        val existing = dao.getRequest(requestId) ?: return@withContext null
        val updated = dao.beginRetryIfInterrupted(
            requestId = requestId,
            expectedAttemptId = existing.attemptId,
            newAttemptId = newAttemptId,
            snapshotJson = refreshedSnapshotJson ?: existing.snapshotJson,
            attachmentPaths = refreshedAttachments?.let { encodeList(it) } ?: existing.attachmentPaths,
            updatedAt = Instant.now(),
        )
        if (updated == 0) return@withContext null
        dao.getRequest(requestId)
    }

    /**
     * 启动扫描：把**确实遗留**的在途请求转成 INTERRUPTED。
     *
     * 只在应用启动、任何新任务创建之前调用一次 —— 所以此刻表里出现的
     * PREPARING/RUNNING 一定是上个进程留下的，而不会是当前进程正在跑的。
     *
     * [excludeRequestIds] 是当前进程**正在持有**的 requestId（活动任务或其重试）：
     * 即使调用方晚了一点、或初始化被重复调用，也不能把正在跑的任务标成中断。
     *
     * 只改状态、不发起任何网络请求：重启后绝不自动重发，把决定权留给用户。
     * 返回被标记的中断记录，供界面显示恢复入口。
     */
    suspend fun recoverOrphans(excludeRequestIds: Set<String> = emptySet()): List<AssistantRequestEntity> =
        withContext(io) {
            val orphans = dao.getUnfinishedRequests().filterNot { it.requestId in excludeRequestIds }
            val now = Instant.now()
            orphans.forEach { orphan ->
                dao.interruptRequest(
                    requestId = orphan.requestId,
                    attemptId = orphan.attemptId,
                    partialText = orphan.partialText,
                    kind = AssistantFailureKind.NETWORK.name,
                    message = "应用上次退出时这一轮还没结束，已停止。可点左侧重试继续。",
                    updatedAt = now,
                )
            }
            orphans.map { it.copy(status = AssistantRequestStatus.INTERRUPTED.name) }
        }

    /**
     * 补写身份快照（request + attempt + 在途三重围栏）。
     *
     * 返回 false 表示写入被拒（attempt 已换 / 已终态 / 记录不存在）——
     * 调用方必须把它当失败处理，不能"补写失败就继续发网络"，
     * 否则重试时没有可校验的身份。
     */
    suspend fun saveSnapshotForAttempt(
        requestId: String,
        attemptId: String,
        snapshot: com.example.lixing.data.assistant.AssistantRequestSnapshot,
    ): Boolean = withContext(io) {
        val encoded = com.example.lixing.data.assistant.AssistantSnapshotCodec.encode(snapshot)
        if (encoded.isBlank()) return@withContext false
        dao.updateSnapshotForAttempt(requestId, attemptId, encoded, Instant.now()) > 0
    }

    suspend fun delete(requestId: String) = withContext(io) { dao.deleteRequest(requestId) }

    /** 删除会话时清理其请求记录（消息由外键级联删除完成）。 */
    suspend fun deleteForConversation(conversationId: String) = withContext(io) {
        dao.deleteRequestsForConversation(conversationId)
    }

    /** 清理一周前已终结的记录，避免表无限增长；在途记录永不清理。 */
    suspend fun purgeStale() = withContext(io) {
        dao.purgeFinishedBefore(Instant.now().minusSeconds(7 * 24 * 3600))
    }

    fun decodeList(raw: String): List<String> = runCatching {
        if (raw.isBlank()) emptyList() else json.decodeFromString<List<String>>(raw)
    }.getOrDefault(emptyList())

    private fun encodeList(values: List<String>): String =
        if (values.isEmpty()) "" else json.encodeToString(values)
}
