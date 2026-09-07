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
)
