package com.example.lixing.data.repository

import com.example.lixing.data.local.dao.AssistantChatDao
import com.example.lixing.data.local.entity.AssistantConversationEntity
import com.example.lixing.data.local.entity.AssistantMessageEntity
import com.example.lixing.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 助手会话持久化。
 *
 * 会话与消息随版本化备份迁移；删除会话会级联删除其全部消息。
 */
@Singleton
class AssistantChatRepository @Inject constructor(
    private val dao: AssistantChatDao,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeConversations(): Flow<List<AssistantConversationEntity>> = dao.observeConversations()

    suspend fun messages(conversationId: String): List<AssistantMessageEntity> =
        withContext(io) { dao.getMessages(conversationId) }

    /** 以第一条用户消息创建会话，标题截取前 24 个字符。 */
    suspend fun startConversation(firstUserMessage: String): String = withContext(io) {
        val now = Instant.now()
        val conversation = AssistantConversationEntity(
            title = titleOf(firstUserMessage),
            createdAt = now,
            updatedAt = now,
        )
        dao.insertConversation(conversation)
        conversation.id
    }

    /** 追加一条消息并把会话顶到最近。 */
    suspend fun appendMessage(conversationId: String, role: String, content: String) =
        appendMessage(conversationId, role, content, emptyList(), null)

    suspend fun appendMessage(
        conversationId: String,
        role: String,
        content: String,
        imagePaths: List<String>,
        displayContent: String? = null,
        /** 挂在这条消息上的待确认方案信封（空串 = 无）。见 [AssistantMessageEntity.pendingReview]。 */
        pendingReview: String = "",
    ) =
        withContext(io) {
            dao.insertMessage(
                AssistantMessageEntity(
                    conversationId = conversationId,
                    role = role,
                    content = content,
                    imagePaths = imagePaths.takeIf { it.isNotEmpty() }?.let(json::encodeToString).orEmpty(),
                    displayContent = displayContent,
                    pendingReview = pendingReview,
                ),
            )
            dao.touchConversation(conversationId, Instant.now())
        }

    /** 更新某条消息的待确认方案信封（确认/取消之后调用）。 */
    suspend fun updatePendingReview(messageId: String, payload: String) =
        withContext(io) { dao.updatePendingReview(messageId, payload) }

    /**
     * 按会话更新待确认方案信封。
     *
     * [payload] 传空串即「清空」（用户放弃了方案），传已应用的信封即「置灰保留」。
     */
    suspend fun updatePendingReviewForConversation(conversationId: String, payload: String) =
        withContext(io) { dao.updatePendingReviewForConversation(conversationId, payload) }

    fun decodeImagePaths(raw: String): List<String> = runCatching {
        if (raw.isBlank()) emptyList() else json.decodeFromString<List<String>>(raw)
    }.getOrDefault(emptyList())

    suspend fun deleteConversation(id: String) = withContext(io) { dao.deleteConversation(id) }

    companion object {
        fun titleOf(message: String): String {
            val clean = message.trim().replace('\n', ' ')
            return if (clean.length <= 24) clean.ifEmpty { "新对话" } else clean.take(24) + "…"
        }
    }
}
