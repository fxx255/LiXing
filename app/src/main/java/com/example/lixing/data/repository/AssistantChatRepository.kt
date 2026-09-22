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

    /**
     * 观察会话消息：页面据此**从数据库**恢复最终正文/图片/信封，
     * 不依赖会丢失的一次性事件。
     */
    fun observeMessages(conversationId: String): Flow<List<AssistantMessageEntity>> =
        dao.observeMessages(conversationId)

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
    ): String = withContext(io) {
        val entity = AssistantMessageEntity(
            conversationId = conversationId,
            role = role,
            content = content,
            imagePaths = imagePaths.takeIf { it.isNotEmpty() }?.let(json::encodeToString).orEmpty(),
            displayContent = displayContent,
            pendingReview = pendingReview,
        )
        dao.insertMessage(entity)
        dao.touchConversation(conversationId, Instant.now())
        // 返回落库 id：调用方要用它在界面上定位这条消息（流式替换正文、挂待确认项），
        // 而不是靠会漂移的列表下标。
        entity.id
    }

    /** 更新某条消息的待确认方案信封（确认/取消之后调用）。 */
    suspend fun updatePendingReview(messageId: String, payload: String) =
        withContext(io) { dao.updatePendingReview(messageId, payload) }

    /**
     * **原子认领**一批确认（计划或英语），供"确认只执行一次"使用。
     *
     * ## 语义
     *
     * 把 [messageId] 上的信封从 [expected] 原子地改成 [next]。
     * 返回 `true` 表示**本次调用赢得了认领权**——调用方此刻才应该去执行副作用
     * （写计划/写英语条目）。返回 `false` 表示认领失败：被另一个 VM/进程抢先、
     * 或者信封内容已变（早已应用过）。
     *
     * ## 为什么必须是**先认领、后执行**
     *
     * 早先是「先应用副作用，再写 applied 标记」两步：
     * - 进程在两步之间退出 ⇒ 落库标记没写 ⇒ 重开后又应用一遍（计划改两遍、英语条目翻倍）；
     * - 两个 VM 同时确认 ⇒ 都通过"读标记"检查 ⇒ 都去执行副作用。
     *
     * 反过来「先标记后执行」也不行：标记写了但副作用没执行，用户以为生效了实际没有。
     * 唯一安全的顺序是：**同一事务里用 CAS 抢到"已应用"标记 → 再执行副作用**；
     * 副作用失败时回滚标记（见 [rollbackClaim]），让用户可以重试。
     *
     * 跨进程原子性由 SQLite 的单条 UPDATE 提供（同一时刻只有一个写入者能命中）。
     */
    suspend fun claimReviewBatch(messageId: String, expected: String, next: String): Boolean =
        withContext(io) { dao.compareAndSetPendingReview(messageId, expected, next) == 1 }

    /**
     * 认领后的副作用**整体失败**时回滚标记，让用户能重试。
     *
     * 只在「当前信封仍是我们写下的 next」时回滚：若期间已被其他路径改动，
     * 不覆盖别人的状态。
     */
    suspend fun rollbackReviewClaim(messageId: String, claimed: String, previous: String) =
        withContext(io) { dao.compareAndSetPendingReview(messageId, claimed, previous) }

    /**
     * 读取**指定消息**上挂着的待确认信封。
     *
     * 为什么需要按消息 id 读而不是「取会话里最后一个信封」：确认动作必须
     * 精确核验**这一批**（固定 message id）有没有被应用过。按会话取最后一个，
     * 在多批方案共存时会核验到别的批次，幂等就失效了。
     */
    suspend fun pendingReviewOf(messageId: String): String =
        withContext(io) { dao.getPendingReview(messageId).orEmpty() }

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
