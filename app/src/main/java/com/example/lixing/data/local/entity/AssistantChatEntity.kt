package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID
import java.time.Instant

/** 一次 AI 助手会话。标题取自第一条用户消息。 */
@Entity(tableName = "assistant_conversation")
data class AssistantConversationEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant = createdAt,
    /** 多端同步的 Lamport 时钟戳，由同步引擎维护；0 = 从未参与同步。 */
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0")
    val syncModifiedAt: Long = 0,
)

/** 会话里的一条消息。会话删除时级联删除。 */
@Entity(
    tableName = "assistant_message",
    foreignKeys = [
        ForeignKey(
            entity = AssistantConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversation_id")],
)
data class AssistantMessageEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    /** user / assistant。 */
    val role: String,
    val content: String,
    /** Local assistant photo paths encoded as a JSON array. */
    @ColumnInfo(name = "image_paths", defaultValue = "")
    val imagePaths: String = "",
    /** Optional compact UI text; null keeps the historical behavior of displaying content. */
    @ColumnInfo(name = "display_content")
    val displayContent: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
    /** 多端同步的 Lamport 时钟戳，由同步引擎维护；0 = 从未参与同步。 */
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0")
    val syncModifiedAt: Long = 0,
    /**
     * 挂在这条消息上的**待确认计划修改方案**（JSON 信封）。空串 = 没有待确认项。
     *
     * 为什么要落库：它以前只是 ViewModel 里的内存状态，进程一被回收
     * （**用户锁屏后回来**最常见，其次是切走助手界面）就直接没了 ——
     * 用户还没来得及点「确认」的修改凭空消失，界面上连个痕迹都不留。
     *
     * 存的是信封而不是动作本身：内含模型给出的 `plan_actions` **原始 JSON**、
     * 各条的勾选状态、以及是否已应用（已应用时按钮置灰但保留）。
     * 恢复时用现有解析逻辑重新解析并重新校验 —— 计划可能已被别处改过。
     */
    @ColumnInfo(name = "pending_review", defaultValue = "")
    val pendingReview: String = "",
)
