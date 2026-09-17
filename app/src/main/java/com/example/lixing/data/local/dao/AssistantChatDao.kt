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
}