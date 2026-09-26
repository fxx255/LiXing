package com.example.lixing.ui.screen.assistant

import com.example.lixing.data.local.entity.AssistantConversationEntity
import com.example.lixing.domain.assistant.AssistantContextKind
import com.example.lixing.domain.assistant.AssistantMessage
import com.example.lixing.domain.assistant.EnglishEntryAction
import com.example.lixing.domain.assistant.PlanAction
import java.time.LocalDate
enum class PlanChangeScope { TODAY, LONG_TERM }

/** 一条待确认的计划建议：明确展示修改前后、作用范围与本地校验问题。 */
data class PendingPlanAction(
    val action: PlanAction,
    val title: String,
    val before: String,
    val after: String,
    val scope: PlanChangeScope,
    val selected: Boolean = true,
    val problem: String? = null,
)

data class AssistantUiState(
    val enabled: Boolean = false,
    val configured: Boolean = false,
    val messages: List<AssistantMessage> = emptyList(),
    val input: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val contextKinds: Set<AssistantContextKind> = emptySet(),
    /** 上一次实际附带的上下文说明，展示在输入框上方。 */
    val lastContextNote: String? = null,
    val pendingActions: List<PendingPlanAction> = emptyList(),
    /** [pendingActions] 由哪一条消息产生；按钮只挂在那条气泡下面。null 表示没有待确认项。 */
    val pendingActionsOwnerIndex: Int? = null,
    /**
     * 已确认应用过的方案条数（挂在 [pendingActionsOwnerIndex] 那条消息上）。
     *
     * 用来把入口按钮**置灰保留**而不是让它凭空消失 —— 用户想知道
     * 「我刚才那次确认到底有没有生效」，按钮没了就无从判断。
     */
    val planReviewAppliedCount: Int = 0,
    val planReviewOpen: Boolean = false,
    val planReviewDate: LocalDate? = null,
    val applying: Boolean = false,
    /**
     * 待确认方案所属的**固定消息 id**（批次标识）。
     *
     * 为什么不能只用下标：确认动作必须精确落到**产生这批方案的那条回答**上。
     * 会话切换、历史加载、删除、追加消息都会让列表下标漂移，
     * 按下标更新会把信封写到别的消息上（「按会话更新所有 pending_review」
     * 是同一类错误）。用固定 id 才能在事务里核验「是不是同一批」。
     */
    val pendingActionsOwnerMessageId: String? = null,
    /** 待确认的英语积累变更（新增 / 修改 / 删除）。 */
    val pendingEnglishActions: List<PendingEnglishAction> = emptyList(),
    /** [pendingEnglishActions] 由哪一条消息产生；按钮只挂在那条气泡下面。 */
    val pendingEnglishOwnerIndex: Int? = null,
    val englishReviewOpen: Boolean = false,
    val applyingEnglish: Boolean = false,
    val applyMessage: String? = null,
    /** 当前会话 id；null 表示尚未落库的新对话。 */
    val currentConversationId: String? = null,
    val conversations: List<AssistantConversationEntity> = emptyList(),
    val historyOpen: Boolean = false,
    /** Photos waiting to be sent together with the next message. */
    val pendingPhotoPaths: List<String> = emptyList(),
    /** Transient provider reasoning; never written to chat history or backups. */
    val activeReasoning: String = "",
    val activeAnswerStarted: Boolean = false,
    val reasoningExpanded: Boolean = true,
    /** Forces provider-backed web search even when the prompt has no obvious search phrase. */
    val forceWebSearch: Boolean = false,
    /**
     * 输入框左侧「重新发送」入口：[retryRequestId] 非空时才显示。
     *
     * 只在**当前会话最新未完成且可重试**的请求上出现；没有中断时不占据常驻提示行。
     */
    val retryRequestId: String? = null,
    /** 可重试请求对应的是哪条回答消息，用于把状态标在那条气泡上。 */
    val retryAnswerMessageId: String? = null,
    /** 显示给用户的失败归类说明（例如「网络中断」）。 */
    val retryReason: String? = null,
    /** 重试进行中：按钮立即防重入。 */
    val retrying: Boolean = false,
    /** 重启后识别出的历史中断记录数，用于提示可回看。 */
    val recoveredInterruptions: Int = 0,
)

/** 一条待确认的英语积累变更：展示变更前后，删除项也要用户明确勾选。 */
data class PendingEnglishAction(
    val action: EnglishEntryAction,
    val title: String,
    val before: String,
    val after: String,
    val selected: Boolean = true,
    val problem: String? = null,
)

private const val REASONING_OMITTED_PREFIX = "…较早的思考内容已省略…\n"
internal const val MAX_REASONING_DISPLAY_CHARS = 12_000

/**
 * 事件围栏：这条生成事件是否属于**当前正在跟踪的那一次生成**。
 *
 * 三条件缺一不可，每条都对应一类真实的串扰：
 * - **会话**：用户在 A 会话等待时切到 B 会话，A 的增量绝不能写进 B 的气泡；
 * - **requestId**：同一会话里连续两轮生成，前一轮的迟到事件不能污染新一轮；
 * - **attemptId**：同一请求重试后 attemptId 变了，旧 attempt 的迟到增量
 *   会把新正文整段覆盖掉（这是「重试后正文错乱」的直接原因）。
 *
 * 抽成纯函数是为了能直接单测：ViewModel 依赖太多，真构造一个来做断言
 * 既慢又脆，而围栏逻辑本身完全由这几个入参决定。
 */
internal fun isSameTurn(
    eventRequestId: String,
    eventAttemptId: String,
    eventConversationId: String?,
    trackedRequestId: String?,
    trackedAttemptId: String?,
    currentConversationId: String?,
): Boolean {
    // ① 会话必须一致（事件没带会话时跳过这一层，交给后面两层判断）。
    if (eventConversationId != null && eventConversationId != currentConversationId) return false
    // ② 必须已经锁定过一轮生成；否则任何事件的来源都不可信。
    val tracked = trackedRequestId ?: return false
    if (eventRequestId != tracked) return false
    // ③ attempt 一旦锁定就必须匹配。
    return trackedAttemptId == null || eventAttemptId == trackedAttemptId
}

/**
 * 把推理增量并入展示缓冲区，并限制总长度。
 *
 * 这是**纯展示**辅助（只影响思考面板），与生成算法无关，所以留在界面层。
 * 生成算法（合并续写、锚点平移、历史裁剪）已统一到
 * [com.example.lixing.data.assistant.AssistantGenerationSupport]，界面与管理器共用一份。
 */
internal fun appendReasoningForDisplay(
    current: String,
    delta: String,
    maxChars: Int = MAX_REASONING_DISPLAY_CHARS,
): String {
    require(maxChars > 0)
    val body = current.removePrefix(REASONING_OMITTED_PREFIX) + delta
    return if (body.length <= maxChars) body else REASONING_OMITTED_PREFIX + body.takeLast(maxChars)
}
