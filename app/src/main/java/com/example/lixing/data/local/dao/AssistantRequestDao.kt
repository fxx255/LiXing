package com.example.lixing.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.lixing.data.local.entity.AssistantMessageEntity
import com.example.lixing.data.local.entity.AssistantRequestEntity
import java.time.Instant

/**
 * 在途生成请求的读写。
 *
 * 关键点：**创建用户消息、预留回答位置、写入请求记录必须在同一个事务里**
 * （见 [insertRequestWithMessages]）。否则「消息已进会话、请求记录没落盘」的崩溃窗口
 * 会留下一条永远无法重试的孤儿用户消息。
 */
@Dao
interface AssistantRequestDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRequest(request: AssistantRequestEntity)

    @Update
    suspend fun updateRequest(request: AssistantRequestEntity)

    @Query("SELECT * FROM assistant_request WHERE request_id = :requestId LIMIT 1")
    suspend fun getRequest(requestId: String): AssistantRequestEntity?

    @Query("SELECT * FROM assistant_request WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    suspend fun getRequestsForConversation(conversationId: String): List<AssistantRequestEntity>

    /**
     * 观察某个会话的请求记录（状态迁移 / 重试 / 中断都会重新发射）。
     *
     * 用途：重试入口、进行中的部分正文、终态归属都要能从**数据库**恢复，
     * 而不是只依赖一次性事件 —— 页面切走再回来时事件早没了。
     */
    @Query("SELECT * FROM assistant_request WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    fun observeRequestsForConversation(conversationId: String): kotlinx.coroutines.flow.Flow<List<AssistantRequestEntity>>

    /**
     * 启动时判定「确实是遗留的」在途请求。
     *
     * 只看 `status IN ('PREPARING','RUNNING')`：当前进程正在跑的任务不可能出现在这里，
     * 因为本应用同一时刻最多只有一个活动生成，且启动扫描先于任何新任务创建。
     */
    @Query(
        "SELECT * FROM assistant_request WHERE status IN ('PREPARING','RUNNING') " +
            "ORDER BY created_at ASC",
    )
    suspend fun getUnfinishedRequests(): List<AssistantRequestEntity>

    @Query(
        "SELECT * FROM assistant_request WHERE conversation_id = :conversationId " +
            "AND status IN ('PREPARING','RUNNING') ORDER BY created_at DESC LIMIT 1",
    )
    suspend fun getActiveRequestForConversation(conversationId: String): AssistantRequestEntity?

    @Query("SELECT * FROM assistant_request WHERE status IN ('PREPARING','RUNNING') LIMIT 1")
    suspend fun getAnyActiveRequest(): AssistantRequestEntity?

    @Query("SELECT COUNT(*) FROM assistant_request WHERE status IN ('PREPARING','RUNNING')")
    suspend fun countActiveRequests(): Int

    @Query("UPDATE assistant_request SET status = :status, updated_at = :updatedAt WHERE request_id = :requestId")
    suspend fun updateStatus(requestId: String, status: String, updatedAt: Instant)

    /**
     * 在途状态迁移（不改正文）：只允许 `PREPARING/RUNNING` 的记录被改成新状态。
     *
     * 用于 `markRunning` 与启动恢复：终态记录不会被复活。
     */
    @Query(
        "UPDATE assistant_request SET status = :status, updated_at = :updatedAt " +
            "WHERE request_id = :requestId AND attempt_id = :attemptId " +
            "AND status IN ('PREPARING','RUNNING')",
    )
    suspend fun updateStatusIfInFlight(
        requestId: String,
        attemptId: String,
        status: String,
        updatedAt: Instant,
    ): Int

    /**
     * 只更新正文与状态。
     *
     * 两道条件缺一不可：
     * - `attempt_id`：晚到的旧 attempt 回调不会覆盖新一轮的结果；
     * - `status IN ('PREPARING','RUNNING')`：**终态不可复活**。没有这一条时，
     *   收尾/取消之后再跑一次的 `savePartial`（默认状态 RUNNING）会把已经
     *   COMPLETED 的记录改回 RUNNING，界面于是永远停在「生成中」。
     */
    @Query(
        "UPDATE assistant_request SET partial_text = :partialText, status = :status, updated_at = :updatedAt " +
            "WHERE request_id = :requestId AND attempt_id = :attemptId " +
            "AND status IN ('PREPARING','RUNNING')",
    )
    suspend fun updatePartial(
        requestId: String,
        attemptId: String,
        partialText: String,
        status: String,
        updatedAt: Instant,
    ): Int

    /**
     * 终态迁移：`RUNNING/PREPARING → COMPLETED`。
     *
     * 与 [updatePartial] 分开，是因为收尾必须**允许**写终态，
     * 而普通增量写入必须被终态挡住。
     */
    @Query(
        "UPDATE assistant_request SET partial_text = :partialText, status = 'COMPLETED', " +
            "updated_at = :updatedAt WHERE request_id = :requestId AND attempt_id = :attemptId " +
            "AND status IN ('PREPARING','RUNNING')",
    )
    suspend fun markCompleted(
        requestId: String,
        attemptId: String,
        partialText: String,
        updatedAt: Instant,
    ): Int

    /** 终态迁移：`RUNNING/PREPARING → INTERRUPTED`。 */
    @Query(
        "UPDATE assistant_request SET partial_text = :partialText, status = 'INTERRUPTED', " +
            "updated_at = :updatedAt WHERE request_id = :requestId AND attempt_id = :attemptId " +
            "AND status IN ('PREPARING','RUNNING')",
    )
    suspend fun markInterrupted(
        requestId: String,
        attemptId: String,
        partialText: String,
        updatedAt: Instant,
    ): Int

    /** 终态迁移：`RUNNING/PREPARING → CANCELLED`。 */
    @Query(
        "UPDATE assistant_request SET partial_text = :partialText, status = 'CANCELLED', " +
            "updated_at = :updatedAt WHERE request_id = :requestId AND attempt_id = :attemptId " +
            "AND status IN ('PREPARING','RUNNING')",
    )
    suspend fun markCancelled(
        requestId: String,
        attemptId: String,
        partialText: String,
        updatedAt: Instant,
    ): Int

    /**
     * 重试换 attempt：**原子 CAS**，只允许从终态（INTERRUPTED）发起。
     *
     * 条件里带 `attempt_id = :expectedAttemptId`，所以「旧 attempt 迟到」
     * 或「同一请求并发两次重试」都只有一个能成功，不会互相覆盖。
     */
    @Query(
        "UPDATE assistant_request SET attempt_id = :newAttemptId, status = 'PREPARING', " +
            "failure_kind = '', failure_message = '', " +
            "snapshot_json = :snapshotJson, attachment_paths = :attachmentPaths, updated_at = :updatedAt " +
            "WHERE request_id = :requestId AND attempt_id = :expectedAttemptId AND status = 'INTERRUPTED'",
    )
    suspend fun beginRetryIfInterrupted(
        requestId: String,
        expectedAttemptId: String,
        newAttemptId: String,
        snapshotJson: String,
        attachmentPaths: String,
        updatedAt: Instant,
    ): Int

    @Query("DELETE FROM assistant_request WHERE request_id = :requestId")
    suspend fun deleteRequest(requestId: String)

    /**
     * 只更新身份快照，**不动状态**，且必须命中**当前 attempt 且在途**的记录。
     *
     * request+attempt+inflight 三重围栏：迟到的旧 attempt 补写不会覆盖
     * 新 attempt 的快照；已终态的记录也不会被复活式改写。
     */
    @Query(
        "UPDATE assistant_request SET snapshot_json = :snapshotJson, updated_at = :updatedAt " +
            "WHERE request_id = :requestId AND attempt_id = :attemptId " +
            "AND status IN ('PREPARING','RUNNING')",
    )
    suspend fun updateSnapshotForAttempt(
        requestId: String,
        attemptId: String,
        snapshotJson: String,
        updatedAt: Instant,
    ): Int

    /** 记录失败归类与说明。单独一条语句，便于在状态迁移之后补充原因。 */
    @Query(
        "UPDATE assistant_request SET failure_kind = :kind, failure_message = :message, " +
            "updated_at = :updatedAt WHERE request_id = :requestId",
    )
    suspend fun markFailureRow(requestId: String, kind: String, message: String, updatedAt: Instant)

    /** 把待确认信封与图片挂到固定的回答位置上（收尾时调用）。 */
    @Query(
        "UPDATE assistant_message SET pending_review = :pendingReview, image_paths = :imagePaths " +
            "WHERE id = :messageId",
    )
    suspend fun applyAnswerEnvelopeRow(messageId: String, pendingReview: String, imagePaths: String)

    @Query("DELETE FROM assistant_request WHERE conversation_id = :conversationId")
    suspend fun deleteRequestsForConversation(conversationId: String)

    /** 清理终态的老记录，避免表无限增长（在途记录永不清理）。 */
    @Query(
        "DELETE FROM assistant_request WHERE status IN ('COMPLETED','CANCELLED') AND updated_at < :before",
    )
    suspend fun purgeFinishedBefore(before: Instant): Int

    @Insert
    suspend fun insertMessage(message: AssistantMessageEntity)

    @Query("UPDATE assistant_conversation SET updated_at = :updatedAt WHERE id = :id")
    suspend fun touchConversation(id: String, updatedAt: Instant)

    @Query("UPDATE assistant_message SET content = :content WHERE id = :messageId")
    suspend fun updateMessageContent(messageId: String, content: String): Int

    @Query("SELECT * FROM assistant_message WHERE id = :messageId LIMIT 1")
    suspend fun getMessage(messageId: String): AssistantMessageEntity?

    /**
     * 事务：把用户消息、预留回答位置、请求记录**一起**写入。
     *
     * 顺序很关键 —— 回答消息先落库，重试时才有固定的更新目标
     * （[AssistantRequestEntity.answerMessageId]），而不是每次重试都往会话尾部
     * 追加一条新的半截回答。
     *
     * 用户消息必须**在同一个事务里**：以前它是调用方单独插入的，
     * 「用户消息已进会话、请求记录没落盘」的崩溃窗口会留下一条永远无法重试的
     * 孤儿用户消息，而且 `user_message_id` 指向的记录也不存在。
     */
    @Transaction
    suspend fun insertRequestWithMessages(
        request: AssistantRequestEntity,
        userMessage: AssistantMessageEntity,
        answer: AssistantMessageEntity,
    ) {
        insertMessage(userMessage)
        insertMessage(answer)
        insertRequest(request)
        touchConversation(request.conversationId, Instant.now())
    }

    /**
     * 原子收尾：把最终正文写进**固定的**回答位置、标记请求完成。
     *
     * 三道校验缺一不可：
     * - `attempt_id` —— 晚到的旧协程不能把新一轮标成完成；
     * - `status IN ('PREPARING','RUNNING')` —— 终态不可复活，也不能被改写；
     * - `answer_message_id = :answerMessageId` —— 回答位置必须是这条请求**自己的**，
     *   否则会把正文写进别人的气泡。
     *
     * 返回 false 表示这次收尾被拒（attempt 过期/已终态/回答位置不符），
     * 调用方**必须**把它当成失败上报，绝不能假成功。
     */
    @Transaction
    suspend fun completeRequest(
        requestId: String,
        attemptId: String,
        answerMessageId: String,
        text: String,
        updatedAt: Instant,
    ): Boolean {
        val owned = getRequest(requestId) ?: return false
        if (owned.answerMessageId != answerMessageId) return false
        val updated = markCompleted(requestId, attemptId, text, updatedAt)
        if (updated == 0) return false
        // **必须校验更新行数**：回答消息可能已被删除（用户删了会话、
        // 或同步把它清了）。若忽略返回值，请求会被标成 COMPLETED，
        // 正文却写进了不存在的行 —— 用户看到一条永远空着的回答，
        // 而且重试入口也不会出现（状态已是终态）。
        //
        // 更新行数为 0 时整个事务回滚（状态也不会变成 COMPLETED）。
        if (updateMessageContent(answerMessageId, text) == 0) {
            throw IllegalStateException("回答位置不存在（messageId=$answerMessageId），收尾回滚")
        }
        return true
    }

    /**
     * 原子收尾（确认轮）：正文 + 图片 + 待确认信封 + COMPLETED **同一个事务**。
     *
     * 以前这里是三次独立 DAO 调用（注释却写着「原子」）：正文写进去了、信封写失败，
     * 或信封写了、状态没落，都会留下「回答已完成但按钮没了」/「状态 RUNNING 却已有正文」
     * 这种半成品，重启后还会被误判成中断并给出重试入口。
     *
     * 校验与 [completeRequest] 相同：attempt、终态、回答位置归属。
     */
    @Transaction
    suspend fun completeRequestWithEnvelope(
        requestId: String,
        attemptId: String,
        answerMessageId: String,
        text: String,
        pendingReview: String,
        imagePaths: String,
        updatedAt: Instant,
    ): Boolean {
        val owned = getRequest(requestId) ?: return false
        if (owned.answerMessageId != answerMessageId) return false
        val updated = markCompleted(requestId, attemptId, text, updatedAt)
        if (updated == 0) return false
        // 回答位置必须真实存在；行数为 0 说明它已被删除（删除会话/同步清理）。
        // 忽略返回值会让请求标成 COMPLETED 而正文丢失 —— 整个事务回滚。
        if (updateMessageContent(answerMessageId, text) == 0) {
            throw IllegalStateException("回答位置不存在（messageId=$answerMessageId），收尾回滚")
        }
        applyAnswerEnvelopeRow(answerMessageId, pendingReview, imagePaths)
        return true
    }

    /**
     * 原子中断：部分正文 + 失败归类 + 终态。
     *
     * 部分正文**只写进请求记录**（本机瞬时状态），**绝不**写进
     * `assistant_message`：那条消息会进入跨端同步与备份，半成品不该被同步出去。
     * 界面在本地把请求里的正文合并展示即可。
     */
    @Transaction
    suspend fun interruptRequest(
        requestId: String,
        attemptId: String,
        partialText: String,
        kind: String,
        message: String,
        updatedAt: Instant,
    ): Boolean {
        val updated = markInterrupted(requestId, attemptId, partialText, updatedAt)
        if (updated == 0) return false
        markFailureRow(requestId, kind, message, updatedAt)
        return true
    }

    /** 原子取消：部分正文 + 终态。同样**不**写进会话消息，避免同步半成品。 */
    @Transaction
    suspend fun cancelRequest(
        requestId: String,
        attemptId: String,
        partialText: String,
        updatedAt: Instant,
    ): Boolean {
        val updated = markCancelled(requestId, attemptId, partialText, updatedAt)
        if (updated == 0) return false
        markFailureRow(requestId, "CANCELLED", "", updatedAt)
        return true
    }

    @Transaction
    suspend fun deleteConversationRequestsAndMessages(conversationId: String) {
        deleteRequestsForConversation(conversationId)
    }

    /** 事务化记录失败原因（与状态迁移分开，方便先确认 attempt 仍然有效）。 */
    @Transaction
    suspend fun markFailure(requestId: String, kind: String, message: String) {
        markFailureRow(requestId, kind, message, Instant.now())
    }

    /** 事务化写入「回答位置」的待确认信封与图片。 */
    @Transaction
    suspend fun applyAnswerEnvelope(messageId: String, pendingReview: String, imagePaths: String) {
        applyAnswerEnvelopeRow(messageId, pendingReview, imagePaths)
    }
}
