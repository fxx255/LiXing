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
}