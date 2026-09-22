package com.example.lixing.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.lixing.data.local.entity.AssistantConversationEntity
import com.example.lixing.data.local.entity.AssistantMessageEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** AI 助手会话与消息的读写。 */
@Dao
interface AssistantChatDao {

    @Query("SELECT * FROM assistant_conversation ORDER BY updated_at DESC")
    fun observeConversations(): Flow<List<AssistantConversationEntity>>

    /**
     * 观察某个会话的消息（每次落库都会重新发射）。
     *
     * 用途：页面**不依赖一次性事件**也能拿到最终正文/图片/待确认信封。
     * 生成收尾时若页面恰好不在（切页、旋转、被回收），那条 Completed 事件
     * 就永久错过了；订上这条 flow 后，落库那一刻界面会自动补回正确内容。
     */
    @Query("SELECT * FROM assistant_message WHERE conversation_id = :conversationId ORDER BY created_at ASC, id ASC")
    fun observeMessages(conversationId: String): Flow<List<AssistantMessageEntity>>

    @Query("SELECT * FROM assistant_conversation ORDER BY updated_at DESC")
    suspend fun getConversations(): List<AssistantConversationEntity>

    @Query("SELECT * FROM assistant_conversation WHERE id = :id LIMIT 1")
    suspend fun getConversation(id: String): AssistantConversationEntity?

    @Insert
    suspend fun insertConversation(conversation: AssistantConversationEntity)

    @Query("UPDATE assistant_conversation SET updated_at = :updatedAt WHERE id = :id")
    suspend fun touchConversation(id: String, updatedAt: Instant)

    @Query("DELETE FROM assistant_conversation WHERE id = :id")
    suspend fun deleteConversation(id: String)

    /**
     * 删除单条消息（按 id）。
     *
     * 语义上对应用户/系统把某条消息移出会话。生成管理器的收尾**必须**能发现
     * 「回答位置已经不在了」，否则会把正文写进不存在的行、请求却标成 COMPLETED。
     */
    @Query("DELETE FROM assistant_message WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("SELECT * FROM assistant_message WHERE conversation_id = :conversationId ORDER BY created_at ASC, id ASC")
    suspend fun getMessages(conversationId: String): List<AssistantMessageEntity>

    @Insert
    suspend fun insertMessage(message: AssistantMessageEntity)

    /**
     * 只更新某条消息挂着的「待确认方案」信封。
     *
     * 用在确认/取消之后：要么清空（没有待确认项了），要么标记为已应用
     * （保留痕迹，界面上按钮置灰而不消失）。
     */
    @Query("UPDATE assistant_message SET pending_review = :payload WHERE id = :messageId")
    suspend fun updatePendingReview(messageId: String, payload: String)

    /** 读取指定消息的信封；消息不存在时为 null。 */
    @Query("SELECT pending_review FROM assistant_message WHERE id = :messageId LIMIT 1")
    suspend fun getPendingReview(messageId: String): String?

    /** 读取指定消息所属会话 id；消息不存在时为 null。确认事务要据此核验归属。 */
    @Query("SELECT conversation_id FROM assistant_message WHERE id = :messageId LIMIT 1")
    suspend fun getMessageConversationId(messageId: String): String?

    /**
     * 同时核验「消息存在 + 归属该会话」后读取信封。
     *
     * 为什么不能只按 messageId 读：确认事务必须先证明「这条消息仍属于传入的会话」；
     * 只按 id 读会把另一个会话里的历史批次也当成当前批次。
     */
    @Query(
        "SELECT pending_review FROM assistant_message " +
            "WHERE id = :messageId AND conversation_id = :conversationId LIMIT 1",
    )
    suspend fun getPendingReviewInConversation(conversationId: String, messageId: String): String?

    /**
     * **CAS 认领**（窄范围版）：额外核验消息仍属于 [conversationId]。
     *
     * 返回受影响行数：1 = 本次调用赢得认领；0 = 被抢先 / 信封已变 / 消息不在此会话。
     * 它只是"最外层的一次写入"；真正的原子性由调用方把它们包进
     * `database.withTransaction {}` 提供 —— 单条 UPDATE 无法让
     * 「认领 + 计划/英语实际写入」成为整体。
     */
    @Query(
        "UPDATE assistant_message SET pending_review = :next " +
            "WHERE id = :messageId AND conversation_id = :conversationId AND pending_review = :expected",
    )
    suspend fun compareAndSetPendingReviewInConversation(
        conversationId: String,
        messageId: String,
        expected: String,
        next: String,
    ): Int

    /**
     * 按会话更新待确认方案信封。
     *
     * 为什么按会话而不是按消息 id：界面上的 [AssistantMessage] 是领域模型、不带数据库 id
     * （确认流程只拿到列表下标）。一个会话里同时只会挂着一份待确认方案，所以按
     * `pending_review != ''` 定位是安全且唯一的。
     */
    @Query(
        "UPDATE assistant_message SET pending_review = :payload " +
            "WHERE conversation_id = :conversationId AND pending_review != ''",
    )
    suspend fun updatePendingReviewForConversation(conversationId: String, payload: String)

    /**
     * **CAS 认领**：把某条消息上的信封从 [expected] 原子地改成 [next]。
     *
     * 返回受影响行数：**1 表示本次调用成功认领了这批确认**；
     * 0 表示被别人抢先（另一个 VM/进程）、内容已变（已被应用过）或消息已不存在。
     *
     * 这是「确认只执行一次」的**原子基石**：先认领、再执行副作用、最后不需要再补标记
     * （认领本身就写下了已应用标记）。早先"先应用副作用、再写 applied 标记"是两步，
     * 进程在这两步之间退出 ⇒ 重开后又应用一遍 ⇒ 重复执行（计划改两遍/英语条目翻倍）。
     */
    @Query(
        "UPDATE assistant_message SET pending_review = :next " +
            "WHERE id = :messageId AND pending_review = :expected",
    )
    suspend fun compareAndSetPendingReview(messageId: String, expected: String, next: String): Int
}
