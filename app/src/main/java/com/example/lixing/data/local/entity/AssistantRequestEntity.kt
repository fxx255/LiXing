package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

/**
 * 一次**在途生成请求**的本机持久记录（schema 13 → 14 新增）。
 *
 * 为什么必须落库：以前「正在生成」只活在 `AssistantViewModel` 的内存里，
 * 进程一被回收（切后台被冻结、厂商 ROM 查杀）就什么都不剩 ——
 * 用户回来只看到一段没有下文的半截回答，既不知道是不是失败，也没有重试入口。
 *
 * 本表**只服务于本机**：
 * - 不进入 [com.example.lixing.data.sync.SyncTables.SYNC_TABLE_SPECS]，
 *   所以进行中的碎片、重试快照、诊断都不会跨端同步，更不会被别的设备拿去自动执行；
 * - 不进入版本化备份的导出表清单，备份导入也不会把历史在途状态变成待执行网络任务。
 *
 * 状态机见 [com.example.lixing.domain.assistant.AssistantRequestStatus]：
 * `PREPARING → RUNNING → COMPLETED / INTERRUPTED / CANCELLED`。
 */
@Entity(
    tableName = "assistant_request",
    foreignKeys = [
        ForeignKey(
            entity = AssistantConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversation_id"),
        Index("status"),
        Index("updated_at"),
    ],
)
data class AssistantRequestEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    /** 本轮用户消息在 `assistant_message` 里的 id；重试要复用它，绝不重复插入用户消息。 */
    @ColumnInfo(name = "user_message_id")
    val userMessageId: String,
    /**
     * 预留的回答位置（`assistant_message` 的 id）。
     *
     * 事务里**先**把这条 assistant 消息写进去（内容可先为空），再开始网络请求，
     * 这样重试只会更新同一条回答，不会在会话里留下多个半截气泡。
     */
    @ColumnInfo(name = "answer_message_id")
    val answerMessageId: String,
    /** 每次重试换一个新的 attemptId；晚到的旧回调凭它作废。 */
    @ColumnInfo(name = "attempt_id")
    val attemptId: String,
    /** [com.example.lixing.domain.assistant.AssistantRequestStatus.name]。 */
    val status: String,
    /** 已确认（上一轮）的正文快照，续写/重试时用它作锚。 */
    @ColumnInfo(name = "confirmed_text")
    val confirmedText: String = "",
    /** 当前轮**部分**正文，按节流策略落盘；重试时保留为「中断内容」。 */
    @ColumnInfo(name = "partial_text")
    val partialText: String = "",
    /** 已解析出的图槽位（JSON 数组，缺图位置保留 null 占位）。 */
    @ColumnInfo(name = "figure_slots", defaultValue = "")
    val figureSlots: String = "",
    /** 本轮用户输入的原文；失败重试时原样复用。 */
    @ColumnInfo(name = "user_text", defaultValue = "")
    val userText: String = "",
    /**
     * 首次提交时的本机附件绝对路径（JSON 数组）。
     *
     * 首次发送前已复制到 App 私有持久目录，所以重试不依赖短时 URI 授权或缓存文件。
     */
    @ColumnInfo(name = "attachment_paths", defaultValue = "")
    val attachmentPaths: String = "",
    /**
     * 重试所需快照（JSON）：模型/端点身份、推理与联网设置、只读上下文与历史。
     *
     * **绝不**包含 API 密钥或 Authorization；密钥运行时从既有凭证存储取。
     */
    @ColumnInfo(name = "snapshot_json", defaultValue = "")
    val snapshotJson: String = "",
    /** 失败归类，用来决定是否给「重试」入口以及给出什么说明。 */
    @ColumnInfo(name = "failure_kind", defaultValue = "")
    val failureKind: String = "",
    @ColumnInfo(name = "failure_message", defaultValue = "")
    val failureMessage: String = "",
    /** 当前已发生的 HTTP 请求轮次（首轮 + 恢复 + 续写），用于诊断。 */
    @ColumnInfo(name = "rounds", defaultValue = "0")
    val rounds: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant = createdAt,
)
