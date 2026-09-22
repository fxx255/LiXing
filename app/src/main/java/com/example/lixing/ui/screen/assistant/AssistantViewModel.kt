package com.example.lixing.ui.screen.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lixing.assistant.AssistantDraftStore
import com.example.lixing.assistant.AssistantGenerationGuard
import com.example.lixing.data.assistant.AiCredentialStore
import com.example.lixing.data.assistant.AssistantContextBuilder
import com.example.lixing.data.assistant.AssistantDiagnostics
import com.example.lixing.data.assistant.AssistantImagePrep
import com.example.lixing.data.assistant.AssistantModelClient
import com.example.lixing.data.assistant.AssistantModelException
import com.example.lixing.data.assistant.AssistantResponseParser
import com.example.lixing.data.assistant.ParsedAssistantReply
import com.example.lixing.data.assistant.PendingPlanReviewPayload
import com.example.lixing.data.assistant.buildModelHistory
import com.example.lixing.data.assistant.decodePendingReview
import com.example.lixing.data.assistant.encodePendingReview
import com.example.lixing.data.assistant.AssistantStreamEvent
import com.example.lixing.data.assistant.shouldUseWebSearch
import com.example.lixing.data.backup.VersionedBackupRepository
import com.example.lixing.data.local.entity.AssistantConversationEntity
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.plot.PlotImageStore
import com.example.lixing.data.repository.AssistantChatRepository
import com.example.lixing.data.repository.EnglishEntryRepository
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.domain.assistant.AssistantContextKind
import com.example.lixing.domain.assistant.AssistantMessage
import com.example.lixing.domain.assistant.EnglishEntryAction
import com.example.lixing.domain.assistant.PlanAction
import com.example.lixing.domain.assistant.PlanApplyResult
import com.example.lixing.domain.assistant.PlanChangeApplier
import com.example.lixing.data.assistant.AssistantFigure
import com.example.lixing.data.assistant.orderedFigures
import com.example.lixing.domain.english.EnglishEntryType
import com.example.lixing.domain.model.TaskStatus
import com.example.lixing.domain.time.StudyClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

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

/**
 * 追问时回溯携带的历史图片：最多几条带图消息，以及 base64 总长度上限（约 6MB 原图）。
 *
 * 带太多会让每轮请求体迅速膨胀、token 翻倍，所以只回溯最近几次拍照。
 */
private const val HISTORY_IMAGE_MESSAGE_LIMIT = 2
private const val HISTORY_IMAGE_MAX_BASE64_CHARS = 8_000_000

/**
 * AI 学习助手页的状态机。
 *
 * ## 与生成任务的关系（重要）
 *
 * 页面**只是订阅者**：生成任务归 [AssistantGenerationManager] 所有，
 * 绘图、确认信封与最终持久化也都在管理器里完成。因此：
 *
 * - 页面销毁（切页/旋转/锁屏）只取消订阅，不会取消生成、也不会丢图；
 * - 重新订阅或新开页面时，从管理器的可重放 `state` + 数据库拿到完整状态；
 * - 本类**不持有任何长网络任务**，`viewModelScope` 里只跑订阅与本地读写。
 */
@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val prefsRepository: UserPreferencesRepository,
    private val modelClient: AssistantModelClient,
    private val contextBuilder: AssistantContextBuilder,
    private val applier: PlanChangeApplier,
    private val versionedBackupRepository: VersionedBackupRepository,
    private val planRepository: PlanRepository,
    private val taskRepository: TaskRepository,
    private val chatRepository: AssistantChatRepository,
    private val englishEntryRepository: EnglishEntryRepository,
    private val aiCredentialStore: AiCredentialStore,
    private val generationGuard: AssistantGenerationGuard,
    private val plotImageStore: PlotImageStore,
    private val diagramImageStore: com.example.lixing.data.diagram.DiagramImageStore,
    /** 应用级生成管理器：持有生成任务，与页面生命周期解耦。 */
    private val generationManager: com.example.lixing.assistant.AssistantGenerationManager,
    private val requestRepository: com.example.lixing.data.repository.AssistantRequestRepository,
    private val diagnostics: AssistantDiagnostics,
    private val draftStore: AssistantDraftStore,
    /** 确认批次的事务化应用：认领 + 实际计划/英语写入 + 最终信封一次完成。 */
    private val reviewTransactions: com.example.lixing.data.repository.AssistantReviewTransactionRepository,
    @param:com.example.lixing.di.IoDispatcher private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()
    private var photoRouteJob: Job? = null

    /**
     * 本轮回答对应的**固定回答位置**（数据库消息 id）。
     *
     * 流式增量按它替换正文，而不是按列表下标 —— 下标会随历史加载、删除会话而漂移，
     * 用它定位过就会把正文写进别的气泡。
     */
    private var activeAnswerMessageId: String? = null

    /**
     * 当前跟踪的生成身份（requestId + attemptId）。
     *
     * 事件围栏的依据：只有三者（request / attempt / 会话）都匹配的事件
     * 才允许写进界面。`Started` 时锁定，收尾或失败时清空。
     */
    private var activeRequestId: String? = null
    private var activeAttemptId: String? = null

    /**
     * 「刚被发送消费掉」的草稿基准（key = 会话 id）。
     *
     * 用于区分两种草稿：
     * - 仍是**发送前那份**（用户没重新输入、也没加新照片）⇒ 收尾时可以清掉；
     * - 用户已经在生成期间写了新内容或加了新附件 ⇒ 绝不能清。
     *
     * **必须同时比对文字与附件**：早先只比文字，于是「生成期间又拍了一张照片
     * 想接着问」的用户，回答一到就被把那张照片从草稿里悄悄抹掉。
     *
     * [ConsumedDraftBaseline.slot] 记录草稿实际居住的**槽位**：从「未落库新对话」
     * 发送时槽位是 `null`（NEW_CONVERSATION_KEY），消费与收尾清理都必须打回**同一个
     * 槽** —— 打到已创建会话的空槽上等于没清，旧草稿会在下次新建对话时「复活」。
     */
    private data class ConsumedDraftBaseline(
        val slot: String?,
        val text: String,
        val photoPaths: List<String>,
    )

    private val consumedDrafts = mutableMapOf<String, ConsumedDraftBaseline>()

    /**
     * 草稿槽（会话 id 或 `null` 新会话槽）的**同步编辑序号**。
     *
     * 每个会改动草稿的意图（输入、加/删照片、消费发送、迟到照片归属）都在
     * **launch 之前**同步 +1；异步草稿读取（init/open/new 的 load）捕获读取时的
     * 序号，返回后序号变了就说明期间有过更晚的编辑/消费 ⇒ 一个字段都不许写。
     * 与 [navigationEpoch] 一起构成 A/B/A 迟到草稿覆盖的**双围栏**。
     *
     * 只在主线程（viewModelScope 默认调度器）读写，无需加锁。
     */
    private val draftEditRevisions = HashMap<String, Long>()

    private fun draftRevisionOf(slot: String?): Long =
        draftEditRevisions[slot ?: AssistantDraftStore.NEW_CONVERSATION_KEY] ?: 0L

    private fun bumpDraftRevision(slot: String?) {
        val key = slot ?: AssistantDraftStore.NEW_CONVERSATION_KEY
        draftEditRevisions[key] = (draftEditRevisions[key] ?: 0L) + 1L
    }

    /**
     * 在途发送操作数：**独立于 busy 的同步重入闸**。
     *
     * `busy` 由渲染层直接跟随 manager 的 ActiveState；提交挂起窗口内 ActiveState
     * 还是空的，busy 会被清回 false —— 只靠 busy 判重入就会在第一次 submit 返回前
     * 放进第二次 send。这个 token 在点击瞬间同步占用、submit 返回（含失败）后释放。
     */
    private val pendingSubmissions = java.util.concurrent.atomic.AtomicInteger(0)

    /** 导航轮次围栏：见 openConversation / startNewConversation 与各恢复路径的使用。 */
    private var navigationEpoch = 0L
    private val pageSelection = MutableStateFlow<Pair<String?, Long>>(null to 0L)

    /**
     * 「最新用户消息」本地缓存：**当前页**观察流里见到的最新 user 消息 id。
     *
     * [refreshRetryEntry] 要求重试候选必须是**最新一条用户消息对应的请求**：
     * 请求流与消息流到达顺序不定，只依赖单一流会错判（消息流还没吐出最新一行时，
     * 请求流可能先带来「更老的」中断候选）。两个流各自到达都重算重试入口，
     * 消息流到达时更新这里并触发重算 —— 两个方向都收敛到同一结论。
     */
    @Volatile
    private var latestUserMessageIdByPage: String? = null

    /** 当前页请求观察流是否已**至少发射一次**（区分「空列表」与「尚未加载」）。 */
    @Volatile
    private var currentPageRequestsLoaded = false

    @Volatile
    private var currentPageMessagesLoaded = false

    /** 当前页**消息行缓存**：供请求流先到时也能立即重算合并（partial 恢复）。 */
    @Volatile
    private var currentPageMessageRows: List<com.example.lixing.data.local.entity.AssistantMessageEntity>? = null

    /**
     * 重试入口的**代围栏**：每次重算前同步 +1，异步读库返回后只有代数仍匹配
     * 才允许写界面 —— 连续两次重算交错时，晚返回的旧读绝不能顶掉新读。
     */
    @Volatile
    private var retryEntryEpoch = 0L

    /**
     * 用户 id 围栏：重算返回后若「最新用户消息」已变（新问题已发出），
     * 本次结果同样作废 —— 旧中断绝不能在成功的新问题之后复活重试入口。
     */
    private fun retryEntryOwns(latestUserIdAtStart: String?): Boolean =
        latestUserMessageIdByPage == latestUserIdAtStart

    /**
     * 重试 UI 操作 token：每次点击同步 +1，任何失败/取消路径只有在 token 仍持有
     * （没有更新的点击）且导航轮次未变时才允许清 `retrying`/写错误 ——
     * 迟到的旧重试返回绝不能清掉新点击的进行中状态。
     * 导航（openConversation/startNewConversation）也作废它（见下方作废调用）。
     */
    @Volatile
    private var retryOperationSeq = 0L

    /**
     * 确认的幂等**不靠内存集合**。
     *
     * 早先用两个 `mutableSetOf<String>()`（计划/英语各一个）做"已应用"闸门，
     * 有两个真实缺陷：
     * 1. **进程在"应用副作用"与"写标记"之间退出** ⇒ 标记没写 ⇒ 重开后又应用一遍；
     * 2. **跨 VM/多入口并发** ⇒ 都通过内存检查 ⇒ 都执行副作用（计划改两遍、英语条目翻倍）。
     *
     * 现在统一走**数据库原子认领**（见 [chatRepository.claimReviewBatch]）：
     * 先 CAS 抢到已应用标记、再执行副作用；副作用整体失败时回滚标记。
     */

    init {
        viewModelScope.launch {
            val prefs = prefsRepository.current()
            _state.update {
                it.copy(
                    enabled = prefs.aiAssistantEnabled,
                    configured = prefs.aiAssistantEnabled && prefs.aiBaseUrl.isNotBlank() && prefs.aiModel.isNotBlank(),
                )
            }
        }
        viewModelScope.launch {
            chatRepository.observeConversations().collect { conversations ->
                _state.update { it.copy(conversations = conversations) }
            }
        }
        // 订阅应用级管理器的事件（增量通知）。
        //
        // 关键：这里只是**订阅**。页面销毁只会取消这个收集协程，
        // 绝不会取消正在进行的生成 —— 生成任务归 [AssistantGenerationManager] 所有。
        viewModelScope.launch {
            generationManager.events.collect { event ->
                try {
                    handleGenerationEvent(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 单条事件处理失败绝不能杀掉事件收集器：后续增量/收尾事件仍要到达。
                    android.util.Log.w("AssistantVM", "处理生成事件失败", e)
                }
            }
        }
        // 订阅**可重放**的活动状态：页面重新订阅时能立刻恢复当前正文，
        // 而不是干等下一个增量事件（那会让切页回来时正文空白一段）。
        viewModelScope.launch {
            generationManager.state.collect { active -> renderActiveState(active) }
        }
        // 订阅**数据库**里的当前会话消息与请求记录。
        //
        // 为什么必须有这条「不依赖事件」的通路：Completed/Failed 是一次性事件，
        // 页面切走、旋转或被系统回收时**永久错过**。此时管理器已经把最终结果
        // 落库了，但界面永远停在流式中间态。订上 DB flow 后，落库那一刻
        // 界面自动补齐最终正文/图片/待确认方案/重试入口。
        viewModelScope.launch {
            pageSelection.collectLatest { (conversationId, pageEpoch) ->
                if (conversationId == null) return@collectLatest
                if (navigationEpoch != pageEpoch) return@collectLatest
                // 新页面的权威 DB 流开始工作：登记后，迟到的 openConversation
                // 一次性读库就不许再整体覆写消息/信封（见 [dbFlowAppliedConversation]）。
                dbFlowAppliedConversation = conversationId
                // 新页进入：请求行/消息行缓存清空，两个流的**首个**发射各自触发重算。
                currentPageRequests = emptyList()
                currentPageRequestsLoaded = false
                currentPageMessageRows = null
                currentPageMessagesLoaded = false
                // 请求流：缓存行、**重算消息合并**（partial 恢复依赖请求行）并刷新重试入口
                // —— 请求/消息谁先到都必须收敛到同一结论。
                launch {
                    requestRepository.observeForConversation(conversationId).collect { requests ->
                        if (navigationEpoch != pageEpoch) return@collect
                        if (_state.value.currentConversationId != conversationId) return@collect
                        currentPageRequests = requests
                        currentPageRequestsLoaded = true
                        applyStoredRequests(conversationId, requests)
                    }
                }
                launch {
                    chatRepository.observeMessages(conversationId).collect { stored ->
                        if (navigationEpoch != pageEpoch) return@collect
                        applyStoredMessages(conversationId, stored)
                        // 消息流到达：更新「最新用户消息」并重算重试入口。
                        if (_state.value.currentConversationId != conversationId) return@collect
                        currentPageMessagesLoaded = true
                        latestUserMessageIdByPage = stored.lastOrNull { it.role == "user" }?.id
                        refreshRetryEntry()
                    }
                }
            }
        }
        // 重启扫描：把上个进程遗留的在途请求转成可重试的中断。
        // 只改状态、不发网络请求 —— 重启后绝不自动调用模型。
        //
        // 该调用是应用级、**幂等且只生效一次**的（见 recoverOnStartup），
        // 所以反复进出本页不会把正在跑的任务标成中断。
        viewModelScope.launch {
            generationManager.recoverOnStartup()
            refreshRetryEntry()
        }
        // 恢复本会话草稿：跨进程保留用户没发出去的内容。
        // 双围栏：load 前固定（导航轮次, 草稿编辑序号）；挂起返回后任一变化
        // （已经切了会话 / 发送已消费过 / 用户已编辑）就一个字段都不写，
        // 迟到读取绝不能顶掉当前页面的输入。
        // **归属与序号在 launch 之前同步捕获**（此刻主线程串行，最可靠）：
        // init 里允许用户此刻只有照片没有文字（photo-only 编辑），
        // revision 围栏同样保护它。
        val initialConversationId = _state.value.currentConversationId
        val initialEpoch = navigationEpoch
        val initialRevision = draftRevisionOf(initialConversationId)
        viewModelScope.launch {
            val draft = try {
                draftStore.load(initialConversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportDraftIoFailure(e, initialConversationId, initialEpoch)
                return@launch
            }
            if (navigationEpoch != initialEpoch) return@launch
            if (draftRevisionOf(initialConversationId) != initialRevision) return@launch
            _state.update { state ->
                if (navigationEpoch != initialEpoch) return@update state
                if (draftRevisionOf(initialConversationId) != initialRevision) return@update state
                if (state.input.isNotEmpty() || state.pendingPhotoPaths.isNotEmpty()) return@update state
                state.copy(input = draft?.text.orEmpty(), pendingPhotoPaths = draft?.photoPaths.orEmpty())
            }
        }
    }

    /**
     * 草稿 IO 失败（load/persist/附件操作）的**归属提示**。
     *
     * 只把失败文案写到**捕获时所属的那一页**（会话 + 导航轮次都未变才写）：
     * 导航过程中 outgoing 页的草稿 persist 失败绝不能显示到切过去的新页 B 上。
     * 取消原样上抛由各调用点处理；这里只接非取消异常。
     * 默认参数在**调用瞬间**求值：非挂起调用点直接调用即可获得正确归属。
     */
    private fun reportDraftIoFailure(
        error: Exception,
        ownerConversationId: String?,
        ownerEpoch: Long,
    ) {
        _state.update { state ->
            if (state.currentConversationId != ownerConversationId || navigationEpoch != ownerEpoch) {
                return@update state
            }
            state.copy(error = "草稿读写失败：${error.message ?: "存储异常"}")
        }
    }

    /**
     * 把管理器的可重放状态渲染进界面。
     *
     * ## 三个必须同时成立的性质
     *
     * 1. **不能粘住**：`busy` 直接跟随 `active.isRunning`。
     *    早先写成 `busy = active.isRunning || it.busy`，于是生成结束后
     *    `busy` 永远是 true（`||` 让 true 无法回落），界面一直显示「生成中」、
     *    输入框永远禁用。
     * 2. **active 清空后也要收尾**：管理器在收尾后会把 active 重置为 `null`。
     *    若此时页面正好不在（或错过了那条一次性事件），
     *    这里必须从**数据库**把最终正文/图片/待确认方案补回来，
     *    否则用户切回来看到的是流式中间态、甚至是空气泡。
     * 3. **不能被旧的 DB partial 覆盖较新的 active 正文**：数据库里的
     *    `partial_text` 是节流写入的旧值；当内存里已有更新的正文时以内存为准。
     */
    private fun renderActiveState(active: com.example.lixing.assistant.AssistantGenerationManager.ActiveState) {
        val requestId = active.requestId
        if (requestId == null || active.phase == null) {
            // 管理器已经没有活动任务：清掉围栏与「生成中」，并**从库里补齐终态**。
            activeRequestId = null
            activeAttemptId = null
            _state.update { it.copy(busy = false, retrying = false) }
            // 只在本屏确实有一个「进行中的回答位置」时才回读，
            // 避免每次 active 抖动都去读库。
            activeAnswerMessageId?.let { answerId ->
                activeAnswerMessageId = null
                viewModelScope.launch { reconcileAnswerFromDatabase(answerId) }
            }
            return
        }
        if (active.conversationId != null && active.conversationId != _state.value.currentConversationId) return
        if (!active.isRunning) {
            // 非在途 phase（终态残留）：不采纳身份、不拉 busy —— 终态恢复由 DB 流/
            // reconcile 负责，避免把一个已收尾的轮重新锁进跟踪状态。
            return
        }
        // 从可重放状态补回固定回答位置：错过 Started 事件也不会丢气泡定位。
        active.answerMessageId?.let { activeAnswerMessageId = it }
        activeRequestId = requestId
        activeAttemptId = active.attemptId
        val answerId = active.answerMessageId ?: activeAnswerMessageId
        _state.update {
            it.copy(
                activeReasoning = active.reasoning,
                activeAnswerStarted = active.answerStarted,
                // 直接跟随，绝不用 `||`（那会让 busy 粘住）。
                busy = active.isRunning,
            )
        }
        if (answerId != null && active.partialText.isNotEmpty()) {
            replaceAnswerText(answerId, active.partialText)
        }
    }

    /**
     * 上次合并进界面的待确认信封（owner 消息 id + 原始 JSON）。
     *
     * 同一个**不变**的信封不重建预览，从而保留用户的勾选状态；
     * 信封为空或内容变化时才按落库内容重建/撤掉旧预览。
     * 只允许在有效围栏内更新（见 [navigationEpoch]）。
     */
    private var lastEnvelopeKey: Pair<String, String>? = null

    /**
     * 当前页上**权威 DB 观察流**已经合并过消息的会话 id（每次导航同步清空）。
     *
     * openConversation 的**迟到一次性读库**返回时，若 DB 流已经为同一会话
     * 合并过数据（同一 epoch 内 observer 先到），本次迟到的整体覆写必须跳过：
     * 它可能顶掉 observer 已经合并的更新行，也会无视用户在恢复预览上做出的
     * 新勾选。DB 流是权威通路，一次性读只是冷启动兜底。
     */
    private var dbFlowAppliedConversation: String? = null

    /**
     * 用数据库里的最终状态校正界面。
     *
     * 这是「错过 Completed 事件」的兜底：管理器收尾后 active 会清空，
     * 如果页面此刻不在（切页、旋转、被回收），那条一次性事件就永久错过了。
     *
     * 复用 [mergeStoredMessagesIntoUi] 的**统一终态合并**（终态即使较短也替换），
     * 并带完整围栏：读取前固定 (会话, 导航轮次)；挂起返回后三者任一变化
     * （会话切走 / 新导航 / 管理器已开新一轮生成）就**一个字段都不改** ——
     * 尤其不能无条件把 busy 压回 false（会覆盖新任务），也不能用 runCatching
     * 吞掉 CancellationException。
     */
    private suspend fun reconcileAnswerFromDatabase(answerMessageId: String) {
        val conversationId = _state.value.currentConversationId ?: return
        val epoch = navigationEpoch
        val messages = try {
            chatRepository.messages(conversationId)
        } catch (e: CancellationException) {
            throw e // 取消必须原样上抛（结构化并发），绝不能折成「读库失败」。
        } catch (e: Exception) {
            return
        }
        // 挂起返回后的完整围栏：会话 / 导航轮次 / 生成身份 任一变化 ⇒ 放弃本次恢复。
        if (_state.value.currentConversationId != conversationId) return
        if (navigationEpoch != epoch) return
        if (generationManager.isRunning()) return
        val restoredImages = withContext(io) {
            buildMap {
                for (row in messages) {
                    put(
                        row.id,
                        chatRepository.decodeImagePaths(row.imagePaths).map(::restoreFigurePath),
                    )
                }
            }
        }
        _state.update { state ->
            // state.update 的 lambda 内也可能与并发更新交错，再围栏一次。
            if (state.currentConversationId != conversationId || navigationEpoch != epoch) return@update state
            state.copy(messages = mergeStoredMessagesIntoUi(messages, state.messages, restoredImages))
        }
        restorePendingReviewFromStored(messages, conversationId, epoch)
        // 信封恢复也会挂起；最后一次挂起之后再次核验归属。
        if (navigationEpoch != epoch || _state.value.currentConversationId != conversationId) return
        if (generationManager.state.value.let { it.isRunning && it.conversationId == conversationId }) return
        _state.update { it.copy(busy = false, retrying = false) }
        refreshRetryEntry()
    }

    /** 图片路径若已被清理：框图可凭台账重画，曲线图保持原路径由占位框兜底。 */
    private fun restoreFigurePath(path: String): String {
        if (path.isBlank()) return path
        val file = File(path)
        return if (file.isFile && file.length() > 0) path else diagramImageStore.restore(path) ?: path
    }

    /**
     * 把**数据库里的有序消息行**合并进界面（DB 消息流变化时的常驻通路）。
     *
     * 围栏：进入时固定导航轮次，挂起解码图片后**逐项校验**
     * （会话 / 导航轮次），任一变化 ⇒ 迟到 DB 流一个字段都不许写。
     * 正文与信封规则见 [mergeStoredMessagesIntoUi] 与 [restorePendingReviewFromStored]。
     */
    private suspend fun applyStoredMessages(
        conversationId: String,
        stored: List<com.example.lixing.data.local.entity.AssistantMessageEntity>,
    ) {
        // 读取前固定归属；挂起后逐项校验（见围栏说明）。
        val epoch = navigationEpoch
        if (_state.value.currentConversationId != conversationId) return
        val restoredImages = withContext(io) {
            buildMap {
                for (row in stored) {
                    put(
                        row.id,
                        chatRepository.decodeImagePaths(row.imagePaths).map(::restoreFigurePath),
                    )
                }
            }
        }
        _state.update { state ->
            // 围栏：会话切走 / 新导航开始后，迟到 DB 流一个字段都不许写。
            if (state.currentConversationId != conversationId || navigationEpoch != epoch) return@update state
            currentPageMessageRows = stored
            state.copy(messages = mergeStoredMessagesIntoUi(stored, state.messages, restoredImages))
        }
        restorePendingReviewFromStored(stored, conversationId, epoch)
        refreshRetryEntry()
    }

    /**
     * DB 行 ↔ 界面消息 的**统一终态合并**（供 [applyStoredMessages] 与
     * [reconcileAnswerFromDatabase] 共用；不读可变围栏入参，由调用方负责围栏）。
     *
     * - 按**稳定 id** 合并（绝不按 role+content 丢消息 —— 不同 id 的合法消息必须保留）；
     * - **DB 新增行**进 UI（回答占位由管理器落库、页面此前错过也必须出现）；
     * - **活动 overlay**：若管理器**正在运行**且身份匹配当前合并的会话，该回答位
     *   用 `active.partialText` 覆盖（内存流式正文比节流写库的 partial 新）；
     *   非运行 / 身份不匹配 / 会话不匹配 ⇒ 一律 DB 权威（终态较短也替换）；
     * - **本机保留片段**：非 COMPLETED 请求行里的 partialText（中断/取消/重试在途）
     *   **有意不写进** `assistant_message`（避免同步/备份半成品），只存在请求记录里；
     *   按 answerMessageId 恢复进空正文行，让重启后用户仍能看到已生成的部分回答；
     *   **已完成请求的 DB 正文永远权威**，绝不复活旧 partial；
     * - 界面上不在库里的**无 id 乐观消息**追加保留（不静默丢用户刚发的内容）；
     *   有 id 而不在库里的一律丢弃（DB 是消息集合的权威）。
     */
    private fun mergeStoredMessagesIntoUi(
        storedRows: List<com.example.lixing.data.local.entity.AssistantMessageEntity>,
        uiMessages: List<AssistantMessage>,
        restoredImages: Map<String, List<String>>,
        storedRequests: List<com.example.lixing.data.local.entity.AssistantRequestEntity> = currentPageRequests,
    ): List<AssistantMessage> {
        // 活动 overlay 身份：**只有真正在跑才生效**。必须同时核对**会话** ——
        // 另一个会话正在生成的任务绝不能把本页的正文/片段遮住。
        val active = generationManager.state.value
        val overlayApplies = active.isRunning &&
            active.requestId != null && active.attemptId != null &&
            active.conversationId == _state.value.currentConversationId &&
            active.answerMessageId != null
        val overlayAnswerId = if (overlayApplies) active.answerMessageId else null
        // 本机保留片段：任何**非 COMPLETED**请求行里的 partialText（中断/取消/重试
        // 在途），key = answerMessageId。中断 partial **有意不写进**
        // `assistant_message`（避免同步/备份半成品），只存在请求记录里；
        // 重试后新 attempt 的 partial 还为空时也靠它保留旧片段。
        // **已完成请求绝不参与** —— DB 正文永远权威，绝不复活旧 partial。
        val localPartialByAnswerId = buildMap {
            for (request in storedRequests) {
                if (request.status == com.example.lixing.domain.assistant.AssistantRequestStatus.COMPLETED.name) {
                    continue
                }
                if (request.partialText.isNotBlank()) put(request.answerMessageId, request.partialText)
            }
        }
        // ① 按 DB 行序生成基准列表：按 id 对上的消息更新字段，DB 新增行转成消息插入。
        //    overlay 用**本会话**的 row（会话列匹配）才允许叠加当前 partial。
        val merged = storedRows.map { row ->
            val current = uiMessages.lastOrNull { it.id == row.id }
            val content = when {
                row.id == overlayAnswerId && row.conversationId == active.conversationId ->
                    // 运行中：叠加管理器**当前**的 partial（不是旧 DB 片段）；
                    // **新 attempt 的 partial 还为空时保留本页已恢复的片段**，
                    // 直到第一条新正文到达（重试后正文闪没就是这个缺失）。
                    active.partialText.ifBlank { row.content.ifBlank { localPartialByAnswerId[row.id].orEmpty() } }
                row.content.isBlank() -> {
                    // 无 DB 正文 + 请求行带本机 partial（中断/取消/重试在途）⇒ 恢复；
                    // 否则保持 DB 权威（空占位）。已完成请求绝不落到这个分支。
                    localPartialByAnswerId[row.id].orEmpty().ifBlank { row.content }
                }
                else -> row.content // 终态：DB 权威，较短也替换（流式累积可能带锚点残留）。
            }
            AssistantMessage(
                role = row.role,
                content = content,
                imagePaths = restoredImages[row.id].orEmpty().ifEmpty { current?.imagePaths.orEmpty() },
                displayContent = row.displayContent ?: current?.displayContent,
                id = row.id,
            )
        }
        // ② 界面上不在库里的**无 id 乐观消息**追加保留；有 id 的以 DB 为准丢弃。
        val appended = uiMessages.filter { it.id == null }
        return if (appended.isEmpty()) merged else merged + appended
    }

    /** 信封「清除旧预览」动作：空/已取消/不可解析的落库信封 ⇒ 撤掉所有待确认状态。 */
    private fun clearPendingReviewState() {
        lastEnvelopeKey = null
        _state.update {
            it.copy(
                pendingActions = emptyList(),
                pendingActionsOwnerIndex = null,
                pendingActionsOwnerMessageId = null,
                planReviewAppliedCount = 0,
                pendingEnglishActions = emptyList(),
                pendingEnglishOwnerIndex = null,
                planReviewOpen = false,
                englishReviewOpen = false,
                planReviewDate = null,
            )
        }
    }

    /**
     * 从**有序 DB 行**恢复 plan + English 信封（不依赖一次性事件、不丢 Completed）。
     *
     * - 空信封（含 lastOrNull 无命中）⇒ **正确走到清除分支**：撤掉所有旧预览 /
     *   owner / batch 状态（已取消的旧信封绝不复活）；
     * - 同一个**不变**的信封 ⇒ 保留用户的勾选状态（不重建预览）。
     * `lastEnvelopeKey` 只在通过围栏后更新。
     */
    private suspend fun restorePendingReviewFromStored(
        stored: List<com.example.lixing.data.local.entity.AssistantMessageEntity>,
        conversationId: String,
        epoch: Long = navigationEpoch,
    ) {
        if (_state.value.currentConversationId != conversationId) return
        if (navigationEpoch != epoch) return
        // **不 `?: return`**：没有非空信封行时也必须走到清除分支，撤掉旧预览，
        // 否则用户已放弃/已取消的信封会在 DB 流每次重放时复活。
        val ownerRow = stored.lastOrNull { it.pendingReview.isNotBlank() }
        if (ownerRow == null) {
            clearPendingReviewState()
            return
        }
        if (lastEnvelopeKey == ownerRow.id to ownerRow.pendingReview) {
            // 同一个不变的信封：预览已建好、勾选是用户的最新选择，别重建。
            return
        }
        val today = StudyClock(dayStart = prefsRepository.current().dayStartTime).today()
        val restored = restorePendingReview(
            payloads = stored.map { it.pendingReview },
            messageIds = stored.map { it.id },
            today = today,
        )
        if (_state.value.currentConversationId != conversationId) return
        if (navigationEpoch != epoch) return
        if (restored == null) {
            // 信封解析不出任何待确认项（损坏/为空）：与空信封一致，撤掉旧预览。
            clearPendingReviewState()
            return
        }
        // 围栏内才登记键。
        lastEnvelopeKey = ownerRow.id to ownerRow.pendingReview
        _state.update { state ->
            state.copy(
                pendingActions = restored.previews,
                pendingActionsOwnerIndex = indexOfMessage(restored.ownerMessageId.orEmpty(), state)
                    .takeIf { it >= 0 } ?: restored.ownerIndex,
                pendingActionsOwnerMessageId = restored.ownerMessageId,
                planReviewAppliedCount = restored.appliedCount,
                planReviewDate = today,
                // 英语与计划同一信封：一起恢复，不能只补计划。
                pendingEnglishActions = restored.englishPreviews,
                pendingEnglishOwnerIndex = if (restored.englishPreviews.isNotEmpty()) {
                    indexOfMessage(restored.ownerMessageId.orEmpty(), state).takeIf { it >= 0 }
                } else {
                    null
                },
            )
        }
    }

    /**
     * 当前页**请求行缓存**：由 init 里当前会话的请求观察流维护。[refreshRetryEntry]
     * 以它为请求行权威来源（已加载时**绝不**回退一次性读库），[applyStoredRequests]
     * 用它 + [currentPageMessageRows] 重算合并；与消息流**任意到达顺序**下都收敛。
     * 导航时清空，新页首个发射重建。
     */
    private var currentPageRequests: List<com.example.lixing.data.local.entity.AssistantRequestEntity> =
        emptyList()

    /**
     * **请求流到达**时的消息重算：partial 恢复（[mergeStoredMessagesIntoUi]）同时
     * 依赖消息行与请求行，请求行变化（如 manager 收尾落库/回滚中断）必须重算合并，
     * 而不只是刷新重试入口 —— 否则「消息先到、请求后到」的顺序下 partial 永远不出现。
     */
    private suspend fun applyStoredRequests(
        conversationId: String,
        requests: List<com.example.lixing.data.local.entity.AssistantRequestEntity>,
    ) {
        val epoch = navigationEpoch
        if (_state.value.currentConversationId != conversationId) return
        // 未有消息行缓存（消息流还没首射）⇒ 本轮只刷新入口，等消息流到达再合并。
        val stored = currentPageMessageRows ?: run { refreshRetryEntry(); return }
        val restoredImages = withContext(io) {
            buildMap {
                for (row in stored) {
                    put(row.id, chatRepository.decodeImagePaths(row.imagePaths).map(::restoreFigurePath))
                }
            }
        }
        _state.update { state ->
            // 围栏：会话切走 / 新导航开始后，迟到流一个字段都不许写。
            if (state.currentConversationId != conversationId || navigationEpoch != epoch) return@update state
            state.copy(messages = mergeStoredMessagesIntoUi(stored, state.messages, restoredImages, requests))
        }
        restorePendingReviewFromStored(stored, conversationId, epoch)
        refreshRetryEntry()
    }

    /**
     * 事件围栏：这条事件是不是**属于当前正在跟踪的那一次生成**。
     *
     * 三个条件缺一不可（requestId + attemptId + 会话）：
     * - 只比对会话不够：同一会话里连续两轮生成的前一轮迟到事件会污染新一轮；
     * - 只比对 requestId 不够：同一请求重试后 attemptId 变了，旧 attempt 的
     *   迟到增量会把新正文覆盖掉；
     * - 只比对 request/attempt 不够：用户切到别的会话时，事件仍会写进当前气泡。
     *
     * 早先 `Reasoning` / `AnswerDelta` 完全没有这层判断，切会话或旧 attempt
     * 迟到都会污染界面。
     */
    private fun isCurrentTurn(
        requestId: String,
        attemptId: String,
        conversationId: String?,
    ): Boolean = isSameTurn(
        eventRequestId = requestId,
        eventAttemptId = attemptId,
        eventConversationId = conversationId,
        trackedRequestId = activeRequestId,
        trackedAttemptId = activeAttemptId,
        currentConversationId = _state.value.currentConversationId,
    )

    /** 把管理器事件翻译成界面状态。全部是纯数据，不涉及任何页面引用。 */
    private suspend fun handleGenerationEvent(event: com.example.lixing.assistant.AssistantGenerationManager.GenerationEvent) {
        when (event) {
            is com.example.lixing.assistant.AssistantGenerationManager.GenerationEvent.Started -> {
                if (event.conversationId != _state.value.currentConversationId) return
                // **只认管理器当前的 request + attempt + 会话**：旧 attempt 的迟到
                // Started 不能改回跟踪身份；**管理器已空闲**（current.requestId ==
                // null，说明这轮其实已经收尾）时也不能接受 —— 否则旧事件会把
                // busy 重新拉起。Completed 已由 DB 流兜底恢复，无需旧 Started。
                val current = generationManager.state.value
                val isCurrentManagerTurn = current.requestId == event.requestId &&
                    current.attemptId == event.attemptId &&
                    current.conversationId == event.conversationId
                if (!isCurrentManagerTurn) return
                // Started 是「锁定本轮身份」的地方：记下 request + attempt，
                // 后续所有增量与收尾事件都要用它们围栏。
                activeRequestId = event.requestId
                activeAttemptId = event.attemptId
                activeAnswerMessageId = event.answerMessageId
                _state.update { it.copy(busy = true, retrying = false, error = null) }
            }

            is com.example.lixing.assistant.AssistantGenerationManager.GenerationEvent.Reasoning -> {
                // 用事件**自带**的 conversationId 做会话围栏（不再靠"当前是否在跟踪"）。
                if (!isCurrentTurn(event.requestId, event.attemptId, event.conversationId)) return
                val active = generationManager.state.value
                if (active.requestId == event.requestId && active.attemptId == event.attemptId && active.isRunning) {
                    renderActiveState(active)
                }
            }

            is com.example.lixing.assistant.AssistantGenerationManager.GenerationEvent.AnswerDelta -> {
                if (!isCurrentTurn(event.requestId, event.attemptId, event.conversationId)) return
                // State is published before its notification. A queued delta may already
                // be older than the displayed state; never replay it over newer text.
                val active = generationManager.state.value
                if (active.requestId == event.requestId && active.attemptId == event.attemptId && active.isRunning) {
                    renderActiveState(active)
                }
            }

            is com.example.lixing.assistant.AssistantGenerationManager.GenerationEvent.Completed -> {
                if (!isCurrentTurn(event.requestId, event.attemptId, event.conversationId)) return
                onTurnCompleted(event)
            }

            is com.example.lixing.assistant.AssistantGenerationManager.GenerationEvent.Failed -> {
                if (!isCurrentTurn(event.requestId, event.attemptId, event.conversationId)) return
                onTurnFailed(event)
            }
        }
    }

    /**
     * 更新输入框内容，并把草稿**跨进程**保存下来。
     *
     * 以前草稿只活在内存里：进程被回收（切后台被查杀）后，用户刚打的一半内容
     * 连同拍好的照片全没了 —— 照片是现拍的，丢了只能重拍。
     *
     * 落盘采用「同步 reserve + 异步 persist」（见 [persistDraft]）：
     * 快速连按按键时每个意图都先在**无 IO 的调用里**登记好顺序，
     * 慢的磁盘写在后面排队，且只有**最新** intent 才会真正写盘。
     */
    fun updateInput(value: String) {
        _state.update { it.copy(input = value) }
        // 编辑意图本身同步登记序号（先于 persist 的异步落盘）：
        // 异步草稿读取（init/open/new）返回后看到序号已变就不会覆盖这次编辑。
        bumpDraftRevision(_state.value.currentConversationId)
        persistDraft(value, _state.value.pendingPhotoPaths)
    }

    /**
     * 草稿保存：**同步** reserve（无 IO，登记 revision 顺序）→ **异步** persist（磁盘）。
     *
     * 为什么不能直接 launch 一个 `save`：磁盘写入慢于按键，两个协程可能
     * **乱序落盘** —— 第一个字还没写完，第三个字先写进磁盘，等第一个迟到
     * 落盘时，草稿被旧内容覆盖，用户刚打的字就丢了。reserve 在 launch 之前
     * 同步完成，保证 revision 顺序与用户输入顺序一致；persist 只认**最新**
     * revision（被更新 reserve 取代的旧 reservation 是空操作），旧写绝不会
     * 覆盖新编辑。
     */
    private fun persistDraft(text: String, photoPaths: List<String>) {
        val conversationId = _state.value.currentConversationId
        val epoch = navigationEpoch
        bumpDraftRevision(conversationId)
        val reservation = draftStore.reserveSave(conversationId, text, photoPaths)
        viewModelScope.launch {
            try {
                draftStore.persist(reservation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportDraftIoFailure(e, conversationId, epoch)
            }
        }
    }

    /**
     * 导航前的**同步**草稿预留：reserve（无 IO）在点击瞬间完成，
     * 落盘排到后面。切会话瞬间先把 outgoing 页的草稿占位登记成最新 revision，
     * 在途旧 persist 全部作废 —— 这就是草稿库「pending 预留」支持立即 A/B/A 的用法。
     *
     * **只登记磁盘意图，不 bump 草稿编辑序号**：编辑序号代表「内容真的变了」，
     * 导航预留的内容与界面当前输入一致，bump 会破坏 send 捕获的序号与消费
     * 判定的对应关系（见 [submit] 的 draftRevisionAtSend）。
     */
    private fun reserveDraftSync(slot: String?, text: String, photoPaths: List<String>) {
        val epoch = navigationEpoch
        val reservation = draftStore.reserveSave(slot, text, photoPaths)
        viewModelScope.launch {
            try {
                draftStore.persist(reservation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportDraftIoFailure(e, slot, epoch)
            }
        }
    }

    fun toggleReasoningExpanded() = _state.update { it.copy(reasoningExpanded = !it.reasoningExpanded) }

    /**
     * 助手页的「智能搜索」开关。
     *
     * 开启时顺带把设置页的总开关也打开：两个开关串联，总开关默认关着，
     * 曾导致用户在这里打开了却完全不生效，且界面没有任何提示。
     */
    fun setForceWebSearch(enabled: Boolean) {
        _state.update { it.copy(forceWebSearch = enabled) }
        if (enabled) {
            viewModelScope.launch {
                if (!prefsRepository.current().aiWebSearchEnabled) {
                    prefsRepository.setAiWebSearchEnabled(true)
                }
            }
        }
    }

    fun toggleContextKind(kind: AssistantContextKind) = _state.update { state ->
        val kinds = state.contextKinds.toMutableSet()
        if (kind in kinds) kinds.remove(kind) else kinds.add(kind)
        state.copy(contextKinds = kinds)
    }

    // ---------------- 会话管理 ----------------

    fun showHistory() = _state.update { it.copy(historyOpen = true) }

    fun closeHistory() = _state.update { it.copy(historyOpen = false) }

    /**
     * 打开一条历史会话：读取消息并续聊。
     *
     * 待确认方案会从落库的信封里**恢复**（以前这里是无条件清空的）：用户
     * 「还没点确认就锁屏 / 切走再回来」时，方案和按钮都不该消失。
     */
    fun openConversation(id: String) {
        // 导航轮次同步 +1：旧会话所有在途 DB 恢复协程从此失效（A/B/A 围栏）。
        navigationEpoch += 1
        val epoch = navigationEpoch
        // 切走之前先把当前会话的草稿**同步预留**落盘：否则用户在两个会话之间
        // 来回切时，前一个会话没发出去的内容会被后一个的草稿覆盖掉。
        reserveDraftSync(_state.value.currentConversationId, _state.value.input, _state.value.pendingPhotoPaths)
        // 目标页草稿槽的编辑序号**导航瞬间同步捕获**：此后任何编辑/消费
        // （用户打字、发送、迟到照片）都会使其失效，草稿载入就不会顶掉它们。
        val targetDraftRevision = draftRevisionOf(id)
        // 旧页面在途确认的返回值全部作废（同步）。
        invalidateApplyOperations()
        // 重试与入口缓存同属旧页：作废在途重试 token，清掉请求行/最新用户消息缓存，
        // 入口代数 +1 让在途重算全部作废（A/B/A 围栏）。
        retryOperationSeq++
        currentPageRequests = emptyList()
        currentPageRequestsLoaded = false
        currentPageMessageRows = null
        currentPageMessagesLoaded = false
        latestUserMessageIdByPage = null
        retryEntryEpoch++
        // 目标页**立即同步选中**：挂起读库之前就切走 —— 绝不等读库返回。
        // 输入/照片/错误/重试状态/活动生成身份/操作局部 applying 全部清旧，
        // 旧页在途照片复制与旧输入绝不能先写进来；消息列表一并清空，
        // 读库回来后再一次性填充目标会话的消息。
        activeRequestId = null
        activeAttemptId = null
        activeAnswerMessageId = null
        lastEnvelopeKey = null
        // 新导航轮次：权威 DB 流尚未为本页合并过数据（collectLatest 会重新登记），
        // openConversation 的一次性读库此时是合法的冷启动兜底。
        dbFlowAppliedConversation = null
        _state.update {
            it.copy(
                historyOpen = false,
                currentConversationId = id,
                messages = emptyList(),
                input = "",
                pendingPhotoPaths = emptyList(),
                error = null,
                busy = generationManager.state.value.let { active ->
                    active.isRunning && active.conversationId == id
                },
                activeReasoning = "",
                activeAnswerStarted = false,
                pendingActions = emptyList(),
                pendingActionsOwnerIndex = null,
                pendingActionsOwnerMessageId = null,
                planReviewAppliedCount = 0,
                planReviewOpen = false,
                planReviewDate = null,
                pendingEnglishActions = emptyList(),
                pendingEnglishOwnerIndex = null,
                englishReviewOpen = false,
                lastContextNote = null,
                applyMessage = null,
                retryRequestId = null,
                retryAnswerMessageId = null,
                retryReason = null,
                retrying = false,
                applying = false,
                applyingEnglish = false,
            )
        }
        pageSelection.value = id to epoch
        renderActiveState(generationManager.state.value)
        // Messages and envelopes have one authoritative source: observeMessages.
        // Draft IO is independent; a slow message/preview read must not suppress it.
        viewModelScope.launch {
            val draft = try {
                draftStore.load(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportDraftIoFailure(e, id, epoch)
                return@launch
            }
            if (navigationEpoch != epoch || _state.value.currentConversationId != id ||
                draftRevisionOf(id) != targetDraftRevision) return@launch
            _state.update { it.copy(input = draft?.text.orEmpty(), pendingPhotoPaths = draft?.photoPaths.orEmpty()) }
        }
        viewModelScope.launch { refreshRetryEntry() }
    }

    /** 从落库的信封恢复出来的待确认方案。 */
    private data class RestoredPlanReview(
        val previews: List<PendingPlanAction>,
        val ownerIndex: Int,
        /** 已应用过 ⇒ 按钮置灰，不再提供待确认项。 */
        val appliedCount: Int,
        /** 产生这批方案的**固定消息 id**（确认时用来核验批次）。 */
        val ownerMessageId: String?,
        /** 这批是否已经应用过：已应用的批次**不允许再次应用**（幂等）。 */
        val applied: Boolean,
        /** 同一信封里的**英语**待确认项（与计划同批，必须一起恢复）。 */
        val englishPreviews: List<PendingEnglishAction> = emptyList(),
        /** 英语这一批已应用的条数（用于灰态提示）。 */
        val englishAppliedCount: Int = 0,
    )

    /**
     * 从各条消息的 `pendingReview` 信封里恢复待确认方案。
     *
     * 只认**最后一条**带信封的消息 —— 与 [AssistantUiState.pendingActionsOwnerIndex]
     * 的语义一致：待确认项永远挂在最新那条回答上。
     *
     * 恢复时**重新解析并重新校验**（而不是盲目信任当初的结果）：
     * 计划可能已经被别处改过，那样对应条目会带上 problem，而不是静默生效或整体丢弃。
     *
     * 同时返回**该消息的固定 id**（批次标识）：确认动作必须精确落到这一批上。
     */
    private suspend fun restorePendingReview(
        payloads: List<String>,
        messageIds: List<String>,
        today: LocalDate,
    ): RestoredPlanReview? {
        val index = payloads.indexOfLast { it.isNotBlank() }
        if (index < 0) return null
        val ownerMessageId = messageIds.getOrNull(index)
        val payload = decodePendingReview(payloads[index]) ?: return null
        val actions = AssistantResponseParser.parseActionsJson(payload.actionsJson)
        val englishActions = AssistantResponseParser.parseEnglishActionsJson(payload.englishActionsJson)
        val englishPreviews = if (payload.englishApplied) {
            // 英语已应用：与计划一致 —— 不显示待确认项，但保留条数用于灰态提示。
            emptyList<PendingEnglishAction>()
        } else {
            buildEnglishPreviews(englishActions).orEmpty().mapIndexed { i, preview ->
                preview.copy(selected = payload.englishSelected.getOrElse(i) { true })
            }
        }
        if (payload.applied) {
            // 计划已确认过：不显示待确认项，但保留数量用于「已应用 N 项」的灰态提示。
            // **注意**：这里仍要返回对象 —— 否则英语那一批会被一起丢掉。
            return RestoredPlanReview(
                previews = emptyList(),
                ownerIndex = index,
                appliedCount = actions.size,
                ownerMessageId = ownerMessageId,
                applied = true,
                englishPreviews = englishPreviews,
                englishAppliedCount = englishActions.size.takeIf { payload.englishApplied } ?: 0,
            )
        }
        // 计划可能为空而英语非空（模型只给了英语变更）：也必须返回，
        // 不能因为"计划没动作"就把英语方案一起丢掉。
        if (actions.isEmpty() && englishPreviews.isEmpty() && englishActions.isEmpty()) return null
        val previews = buildPreviews(actions, today).mapIndexed { i, preview ->
            preview.copy(selected = payload.selected.getOrElse(i) { true })
        }
        return RestoredPlanReview(
            previews = previews,
            ownerIndex = index,
            appliedCount = 0,
            ownerMessageId = ownerMessageId,
            applied = false,
            englishPreviews = englishPreviews,
            englishAppliedCount = 0,
        )
    }

    /** 开始全新对话：清空界面；旧会话仍保留在历史里。 */
    fun startNewConversation() {
        // 导航轮次同步 +1：旧会话所有在途 DB 恢复协程从此失效（A/B/A 围栏）。
        navigationEpoch += 1
        val epoch = navigationEpoch
        // 当前页的 outgoing 草稿**同步预留**：先按它居住的槽位登记（可能还是
        // null 新会话槽），再切页。之后立即占用新页的 null 草稿编辑序号，
        // 在途 null 槽草稿读取返回后看到序号变了就不会覆盖新页输入。
        val outgoingSlot = _state.value.currentConversationId
        reserveDraftSync(outgoingSlot, _state.value.input, _state.value.pendingPhotoPaths)
        // 旧页面在途确认的返回值全部作废（同步）。
        invalidateApplyOperations()
        // 重试与入口缓存同属旧页：作废在途重试 token，清掉请求行/最新用户消息缓存。
        retryOperationSeq++
        currentPageRequests = emptyList()
        currentPageRequestsLoaded = false
        currentPageMessageRows = null
        currentPageMessagesLoaded = false
        latestUserMessageIdByPage = null
        retryEntryEpoch++
        // 换会话必须清掉信封基准：否则上一个会话的信封键会让新会话的恢复被误判为「不变」。
        lastEnvelopeKey = null
        // 新导航轮次：权威 DB 流尚未为本页合并过数据。
        dbFlowAppliedConversation = null
        // 清掉活动生成身份与「进行中回答位置」：这些字段属于旧页面的跟踪状态，
        // 新页面只能从管理器可重放 state 里恢复属于自己的那一轮。
        activeRequestId = null
        activeAttemptId = null
        activeAnswerMessageId = null
        _state.update {
            it.copy(
                messages = emptyList(),
                currentConversationId = null,
                pendingActions = emptyList(),
                pendingActionsOwnerIndex = null,
                pendingActionsOwnerMessageId = null,
                planReviewAppliedCount = 0,
                planReviewOpen = false,
                planReviewDate = null,
                pendingEnglishActions = emptyList(),
                pendingEnglishOwnerIndex = null,
                englishReviewOpen = false,
                lastContextNote = null,
                applyMessage = null,
                historyOpen = false,
                // 新页面同步清空：旧输入/旧照片/旧错误/旧重试状态绝不能带进新页。
                input = "",
                pendingPhotoPaths = emptyList(),
                error = null,
                retryRequestId = null,
                retryAnswerMessageId = null,
                retryReason = null,
                retrying = false,
                busy = false,
                applying = false,
                applyingEnglish = false,
            )
        }
        pageSelection.value = null to epoch
        // 新对话有自己的草稿槽位（NEW_CONVERSATION_KEY），载入它。
        // 双围栏：导航轮次 + 该槽草稿编辑序号；openConversation / 发送消费 / 用户编辑
        // 任一在挂起期间发生，迟到读取都一个字段都不许写。
        // **序号同步捕获（launch 之前）**：此刻串行，之后的任何编辑都会使本读取失效。
        val draftRevision = draftRevisionOf(null)
        viewModelScope.launch {
            val draft = try {
                draftStore.load(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportDraftIoFailure(e, null, epoch)
                return@launch
            }
            if (navigationEpoch != epoch) return@launch
            if (_state.value.currentConversationId != null) return@launch
            if (draftRevisionOf(null) != draftRevision) return@launch
            _state.update { state ->
                if (navigationEpoch != epoch) return@update state
                if (state.currentConversationId != null) return@update state
                if (draftRevisionOf(null) != draftRevision) return@update state
                if (state.input.isNotEmpty() || state.pendingPhotoPaths.isNotEmpty()) return@update state
                state.copy(input = draft?.text.orEmpty(), pendingPhotoPaths = draft?.photoPaths.orEmpty())
            }
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            // 先取消该会话正在进行的生成：否则迟到的写入会把已删除的会话又建回来。
            val activeId = generationManager.activeRequestId()
            if (activeId != null) {
                val activeConversation = requestRepository.get(activeId)?.conversationId
                if (activeConversation == id) generationManager.cancel(activeId)
            }
            // **先**收集已入会话的附件路径：这些是历史内容的一部分，
            // 草稿清理时不能误删（否则用户回看消息时图片打不开）。
            val sentAttachmentPaths = chatRepository.messages(id)
                .flatMap { chatRepository.decodeImagePaths(it.imagePaths) }
                .distinct()
                .toSet()
            sentAttachmentPaths.forEach { File(it).delete() }
            // 请求记录随会话一起清理（消息由外键级联删除）。
            runCatching { requestRepository.deleteForConversation(id) }
            // 草稿与其**尚未发送**的附件一并清理；
            // 已入会话的附件已在上面按消息删掉，这里不再重复删或误删别的文件。
            runCatching { draftStore.clear(id, sentAttachmentPaths) }
            chatRepository.deleteConversation(id)
            if (_state.value.currentConversationId == id) {
                startNewConversation()
            }
        }
    }

    // ---------------- 对话 ----------------

    fun onPhotoTaken(path: String) {
        val ownerSlot = _state.value.currentConversationId
        val epoch = navigationEpoch
        viewModelScope.launch {
            try {
                val photo = draftStore.persistAttachments(listOf(path)).firstOrNull()
                    ?: error("照片没能保存到本地，请重试")
                // load may suspend while the user edits A through another visit.
                // Check the edit version before reserving so an old read never becomes
                // the newest save intent. Everything after that check is synchronous.
                var latest: AssistantDraftStore.Draft?
                while (true) {
                    val revision = draftRevisionOf(ownerSlot)
                    latest = draftStore.load(ownerSlot)
                    if (revision == draftRevisionOf(ownerSlot)) break
                }
                if (_state.value.currentConversationId == ownerSlot) {
                    latest = AssistantDraftStore.Draft(_state.value.input, _state.value.pendingPhotoPaths)
                }
                val photos = (latest?.photoPaths.orEmpty() + photo).distinct()
                if (photos.size > MAX_PENDING_PHOTOS) {
                    reportDraftIoFailure(IllegalStateException("一次最多发送 $MAX_PENDING_PHOTOS 张照片"), ownerSlot, epoch)
                    return@launch
                }
                bumpDraftRevision(ownerSlot)
                val reservation = draftStore.reserveSave(ownerSlot, latest?.text.orEmpty(), photos)
                if (_state.value.currentConversationId == ownerSlot) {
                    _state.update { it.copy(pendingPhotoPaths = photos) }
                }
                draftStore.persist(reservation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportDraftIoFailure(e, ownerSlot, epoch)
            }
        }
    }

    /**
     * 移除一张待发送照片：**只移除草稿里的引用，绝不删除文件**。
     *
     * 早先这里直接 `File(path).delete()` —— 同一张图可能已被之前的会话消息
     * 引用（用户发送后又撤回重发），删文件会让历史消息里的图片打不开。
     * 孤儿文件由会话删除/清理路径统一处理，这里不越权。
     */
    fun removePendingPhoto(path: String) {
        _state.update { state ->
            state.copy(pendingPhotoPaths = state.pendingPhotoPaths - path)
        }
        persistDraft(_state.value.input, _state.value.pendingPhotoPaths)
    }

    /**
     * 发送：**点击瞬间同步捕获全部输入设置**，之后绝不重读当前状态。
     *
     * launch 之后再读 `_state` 读到的可能已是用户切走后的另一页（B）——
     * 文字、照片、联网开关、上下文勾选、计划流程归属都必须在这一行之前固定。
     *
     * 缺失的附件路径**不过滤**：filter 掉会让带图题静默降级成纯文本发给模型，
     * 用户看到的答案完全错位。原样交给管理器/准备阶段校验并明确报错。
     *
     * 防重入是**独立的操作 token**（[pendingSubmissions]）：`busy` 直接跟随管理器
     * 的 ActiveState，提交挂起窗口内 ActiveState 还是空、busy 会被快速完成清回
     * false —— 只靠 busy 判重入就会放进第二次发送。token 在这里同步 +1、
     * submit 返回（含所有失败分支）后 -1。
     */
    fun send() {
        val current = _state.value
        val text = current.input.trim()
        // **不过滤缺失文件**：交给管理器校验，缺失时明确报错而不是静默降级。
        val photoPaths = current.pendingPhotoPaths.toList()
        if (text.isEmpty() && photoPaths.isEmpty()) return
        if (current.busy || current.retrying) return
        if (!pendingSubmissions.compareAndSet(0, 1)) return // 已有一次提交在途
        // 捕获点击瞬间的完整归属与设置。
        val ownerSlot = current.currentConversationId // null = 未落库新会话
        val epoch = navigationEpoch
        val contextKinds = current.contextKinds
        val forceWeb = current.forceWebSearch
        val planFlow = inPlanChangeFlow(current)
        val draftRevision = draftRevisionOf(ownerSlot)
        val rawInput = current.input
        viewModelScope.launch {
            try {
                submit(
                    text = text,
                    photoPaths = photoPaths,
                    forceWebSearch = forceWeb,
                    contextKinds = contextKinds,
                    inPlanChangeFlow = planFlow,
                    ownerSlot = ownerSlot,
                    pageEpoch = epoch,
                    draftRevisionAtSend = draftRevision,
                    rawInputAtSend = rawInput,
                )
            } finally {
                pendingSubmissions.decrementAndGet()
            }
        }
    }

    /**
     * 提交一轮提问。所有输入在 [send] 点击瞬间捕获并传入，本方法**绝不重读
     * 当前 `_state` 来决定要发什么**。
     *
     * 归属规则：
     * - [ownerSlot] 是点击瞬间的会话归属（null = 从「未落库新对话」发出）；
     *   新建会话若在此挂起期间发生导航，创建出的会话仍归本次操作所有，
     *   但**只有原页面轮次仍当前**才允许在界面上选中它 —— 迟到的 submit
     *   绝不能把用户从 B 拉回 A；
     * - 成功后的用户/占位气泡按**稳定 request 消息 id 去重**：DB 可能已
     *   同时包含两行（DB 流先吐出），乐观追加绝不产生重复气泡；
     * - 接受后 busy/reasoning 从**管理器当前 ActiveState** 派生，绝不无条件
     *   busy=true —— submit 返回时请求可能已经全部跑完；
     * - 消费草稿只按**发送时的那个草稿槽与编辑序号**：从 null 槽发出就清
     *   null 槽；期间用户的新编辑（序号已变）一律保留，绝不清错。
     */
    private suspend fun submit(
        text: String,
        photoPaths: List<String>,
        forceWebSearch: Boolean,
        contextKinds: Set<AssistantContextKind>,
        inPlanChangeFlow: Boolean,
        ownerSlot: String?,
        pageEpoch: Long,
        draftRevisionAtSend: Long,
        rawInputAtSend: String,
    ) {
        val outgoing = text
        // 首条消息才创建会话，避免空会话进历史。
        // 创建出的会话归**本次操作**所有（即便导航已切走），但只在原页面轮次
        // 仍当前时才写进 currentConversationId —— 不能把用户从 B 拉回 A。
        // 创建失败（存储异常等）绝不能击穿 send 的协程：按原归属页报错收敛。
        val conversationId = ownerSlot ?: run {
            val createdId = try {
                chatRepository.startConversation(
                    outgoing.ifBlank { if (photoPaths.isEmpty()) "新对话" else "图片题目" },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { state ->
                    val onOwnerPage = navigationEpoch == pageEpoch &&
                        state.currentConversationId == null
                    if (!onOwnerPage) return@update state
                    state.copy(
                        error = "这一轮没能保存到本地（${error.message ?: "存储异常"}），内容已保留在输入框，请重试",
                    )
                }
                return
            }
            createdId
        }
        // 围栏：submit 返回后所有界面写入前都要校验「仍在原归属页」。
        fun ownsPage(state: AssistantUiState): Boolean =
            navigationEpoch == pageEpoch &&
                (state.currentConversationId == conversationId ||
                    ownerSlot == null && state.currentConversationId == null)
        val submission = com.example.lixing.assistant.AssistantGenerationManager.Submission(
            conversationId = conversationId,
            userText = outgoing,
            attachmentPaths = photoPaths,
            userDisplayContent = null,
            contextKinds = contextKinds,
            inPlanChangeFlow = inPlanChangeFlow,
            forceWebSearch = forceWebSearch,
            imageBase64s = emptyList(),
        )
        // 落盘 + 单飞判定都在管理器**锁内**完成：失败时保留草稿，不发起网络。
        // 失败/拒绝只报给**原归属页**（仍在原页且导航未变）；被拒绝的输入
        // 保留在原归属会话的草稿里，绝不写进切走后的 B 页。
        val request = try {
            generationManager.submit(submission)
        } catch (error: com.example.lixing.assistant.AssistantGenerationManager.AlreadyRunningException) {
            _state.update { state ->
                if (!ownsPage(state)) return@update state
                state.copy(
                    error = "另一个会话正在生成回答，请等它结束后再发送",
                )
            }
            return
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update { state ->
                if (!ownsPage(state)) return@update state
                state.copy(
                    error = "这一轮没能保存到本地（${error.message ?: "存储异常"}），内容已保留在输入框，请重试",
                )
            }
            return
        }
        if (ownerSlot == null && ownsPage(_state.value)) {
            _state.update { it.copy(currentConversationId = conversationId) }
            pageSelection.value = conversationId to navigationEpoch
        }
        // 记录已落盘且已占用活动状态：界面进入「生成中」。
        //
        // **按稳定 id 去重**：DB 流可能已经把 user + 占位两行吐进界面
        // （createRequest 事务先于 submit 返回），乐观追加前先查同 id 气泡
        // 是否存在 —— 否则用户会看到两条一模一样的提问。
        //
        // activeAnswerMessageId / 清输入等**只在原归属页围栏内**写：
        // 迟到的 submit 绝不能改已切走的 B 页的跟踪字段。
        _state.update { state ->
            if (!ownsPage(state)) return@update state
            // **只消费「发送时那份输入」**：序号未变（无后续编辑）才清匹配的
            // 输入/照片；序号已变 ⇒ 用户在生成期间又编辑过（哪怕文本恰好
            // 相同或复用了同一路径），一律保留，绝不误清。
            val revisionUnchanged = draftRevisionOf(ownerSlot) == draftRevisionAtSend
            val clearInput = revisionUnchanged && state.input == rawInputAtSend
            val clearPhotos = revisionUnchanged &&
                state.pendingPhotoPaths.all { it in photoPaths }
            activeAnswerMessageId = request.answerMessageId
            val hasUserBubble = state.messages.any { it.id == request.userMessageId }
            val hasAnswerBubble = state.messages.any { it.id == request.answerMessageId }
            // manager 可重放状态里若已有本轮的推理/正文（快速开始的生成），
            // **保留**而不是清空 —— 否则已到达的增量会闪没。
            val managerActive = generationManager.state.value
            val ownsActive = managerActive.requestId == request.requestId
            state.copy(
                input = if (clearInput) "" else state.input,
                pendingPhotoPaths = if (clearPhotos) emptyList() else state.pendingPhotoPaths,
                messages = buildList {
                    addAll(state.messages)
                    if (!hasUserBubble) {
                        add(
                            AssistantMessage(
                                "user",
                                outgoing,
                                photoPaths,
                                null,
                                id = request.userMessageId,
                            ),
                        )
                    }
                    if (!hasAnswerBubble) {
                        add(
                            AssistantMessage(
                                "assistant",
                                "",
                                emptyList(),
                                null,
                                id = request.answerMessageId,
                            ),
                        )
                    }
                },
                retrying = false,
                error = null,
                retryRequestId = null,
                retryAnswerMessageId = null,
                retryReason = null,
                // 保留 manager 当前已有的推理/正文状态（快速开始的生成），
                // 绝不清空 —— ownsActive 时优先采纳 manager 可重放状态里的值。
                activeReasoning = if (ownsActive) {
                    managerActive.reasoning.ifEmpty { state.activeReasoning }
                } else {
                    state.activeReasoning
                },
                activeAnswerStarted = if (ownsActive) {
                    state.activeAnswerStarted || managerActive.answerStarted
                } else {
                    state.activeAnswerStarted
                },
                reasoningExpanded = true,
                // busy 从**管理器当前状态**派生：submit 返回时请求可能已经
                // 跑完（快速完成），无条件 true 会让界面永远卡在「生成中」。
                busy = managerActive.isRunning && managerActive.conversationId == conversationId,
            )
        }
        if (ownerSlot == null && ownsPage(_state.value) && draftRevisionOf(null) != draftRevisionAtSend) {
            val remaining = _state.value
            bumpDraftRevision(conversationId)
            val target = draftStore.reserveSave(conversationId, remaining.input, remaining.pendingPhotoPaths)
            bumpDraftRevision(null)
            val source = draftStore.reserveSave(null, "", emptyList())
            try {
                draftStore.persist(target)
                draftStore.persist(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportDraftIoFailure(e, conversationId, pageEpoch)
            }
        }
        // 消费「这一轮发送时占据的那个草稿槽」：从 null 槽发出就清 null 槽，
        // 从已有会话发出就清该会话槽。**只消费发送时固定的那个编辑序号**：
        // 期间用户又编辑了（序号变了）⇒ 保留新编辑，什么都不清。
        if (draftRevisionOf(ownerSlot) == draftRevisionAtSend) {
            // 收尾清理基准：只有草稿仍是"发送后留下的那份"（空文字+空附件）才清。
            consumedDrafts[conversationId] = ConsumedDraftBaseline(
                slot = ownerSlot,
                text = "",
                photoPaths = emptyList(),
            )
            bumpDraftRevision(ownerSlot)
            val reservation = draftStore.reserveSave(ownerSlot, text = "", photoPaths = emptyList())
            try {
                draftStore.persist(reservation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportDraftIoFailure(e, ownerSlot, pageEpoch)
            }
        }
    }

    // ---------------- 应用级任务：提交、重试、取消 ----------------
    /**
     * 输入框左侧「重新发送」。
     *
     * 复用**原用户消息和回答 ID**：最终只会有一条用户消息和一个回答位置，
     * 不会重复插入用户消息、也不会重复确认动作。新一轮用新 attemptId，
     * 旧 attempt 的迟到回调会被仓库的 attempt + 在途状态条件拦下。
     *
     * 防重入是**同步**的（`retrying` 立刻置位）：连点两次只会提交一次。
     */
    fun retryInterrupted() {
        val requestId = _state.value.retryRequestId ?: return
        if (_state.value.busy || _state.value.retrying) return // 立即防重入
        _state.update { it.copy(retrying = true, error = null) }
        // **点击瞬间同步捕获页面输入设置**：launch 之后 `_state` 可能已是切走后的
        // 另一页 —— 重试的 forceWebSearch / contextKinds 必须来自点击那一页。
        val capturedEpoch = navigationEpoch
        val capturedConversationId = _state.value.currentConversationId
        val capturedContextKinds = _state.value.contextKinds
        val capturedForceWebSearch = _state.value.forceWebSearch
        // beginRetry 之前的 UI 操作 token：任何失败路径只有在 token 仍持有
        // （没有更新的重试点击/导航）时才允许写界面。
        val opToken = ++retryOperationSeq
        // Record the attempt before the transaction: cancellation can happen after commit
        // but before its return value reaches this coroutine.
        var knownAttempt: String? = null
        viewModelScope.launch {
            // 归属围栏的失败收敛：只有 token 仍持有（没有更新的点击）且导航轮次
            // 未变时才允许清 retrying/写错误 —— 迟到的旧返回绝不碰新点击的状态。
            suspend fun fail(op: Long, error: String?) {
                if (op != retryOperationSeq || navigationEpoch != capturedEpoch) return
                _state.update { it.copy(retrying = false, error = error) }
            }
            try {
                val existing = requestRepository.get(requestId)
                if (existing == null) {
                    fail(opToken, null)
                    refreshRetryEntry()
                    return@launch
                }
                // **新问题已经发出后，旧请求不能插队重放**：它是历史中断记录，
                // 只供回看与显式重试，不能混进新问题的生成任务。
                val ownerConversation = existing.conversationId
                if (ownerConversation != capturedConversationId) {
                    fail(opToken, "这条中断属于另一个会话，请先打开该会话再重试")
                    refreshRetryEntry()
                    return@launch
                }
                // 同会话内若已有一条**更晚**的用户消息，说明用户已经重新提问，
                // 这一条应保持为历史中断记录，不再从输入栏重放。
                val latestUserMessageId = chatRepository.messages(ownerConversation)
                    .lastOrNull { it.role == "user" }?.id
                if (latestUserMessageId != null && latestUserMessageId != existing.userMessageId) {
                    if (opToken == retryOperationSeq && navigationEpoch == capturedEpoch) {
                        _state.update {
                            it.copy(
                                retrying = false,
                                retryRequestId = null,
                                retryAnswerMessageId = null,
                                retryReason = null,
                                error = "你已经发过新的问题了，这条中断已保留为历史记录",
                            )
                        }
                    }
                    return@launch
                }
                if (generationManager.isRunning()) {
                    fail(opToken, "另一个会话正在生成回答，请稍后再重试")
                    return@launch
                }
                // 重试前重新校验附件：缺失时明确提示重新选择，
                // 绝不偷偷把带图题目降级成纯文本。
                val attachments = requestRepository.decodeList(existing.attachmentPaths)
                val missing = attachments.filterNot { File(it).isFile }
                if (missing.isNotEmpty()) {
                    if (opToken == retryOperationSeq && navigationEpoch == capturedEpoch) {
                        _state.update {
                            it.copy(
                                retrying = false,
                                retryReason = "原附件已不可用（${missing.size} 张图片丢失），请重新选择图片后再发送",
                                error = "原附件已被清理，无法重试。请重新选择图片后发送。",
                            )
                        }
                    }
                    return@launch
                }
                // 配置未改验证：把**当初实际调用的身份**（模型 + 端点 + 协议）与
                // 当前会调用的身份逐项比对。不一致就不能把旧题目静默发给另一个供应商。
                val prefs = prefsRepository.current()
                val identityMismatch = retryIdentityMismatch(existing.snapshotJson)
                if (identityMismatch != null) {
                    fail(
                        opToken,
                        "$identityMismatch。请重新发送这条问题，" +
                            "避免把题目发给你没预期的模型或供应商。",
                    )
                    return@launch
                }
                // ── 所有挂起的校验/上下文都在 beginRetry **之前**完成 ──
                // 重试要**刷新易变的计划/任务数据与当前日期**；重建失败时退回提交时
                // 留下的那份只读上下文。**空刷新是有效结果**：绝不复活已删除的旧
                // sourceContext —— 保持空上下文，让「计划已不存在」如实发给模型。
                val storedSnapshot = com.example.lixing.data.assistant.AssistantSnapshotCodec
                    .decode(existing.snapshotJson)
                val refreshedContext: String? = try {
                    val kinds = inferAssistantContext(existing.userText) + capturedContextKinds
                    val today = StudyClock(dayStart = prefs.dayStartTime).today()
                    contextBuilder.build(kinds, today)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // 重建失败：回退快照里钉下的只读上下文（由 preparer 兜底）。
                }
                // 重试要重发**同一道题**：数据库里的 userText 对图片题可能只是占位文案，
                // 快照里存着当初真正发给模型的原文（含转写结果）。
                val retryUserText = storedSnapshot?.sourceUserText?.takeIf { it.isNotBlank() }
                    ?: existing.userText
                val history = buildModelHistory(
                    chatRepository.messages(ownerConversation)
                        .filter { it.id != existing.answerMessageId }
                        .filter { it.id != existing.userMessageId }
                        .map { AssistantMessage(it.role, it.content) },
                )
                // 围栏：以上挂起期间若有更晚的重试点击 / 导航，本次全部作废。
                if (opToken != retryOperationSeq || navigationEpoch != capturedEpoch) return@launch
                val newAttempt = java.util.UUID.randomUUID().toString()
                knownAttempt = newAttempt
                // 原子 CAS：并发/重复点重试只有一个能抢到；状态已不是 INTERRUPTED 时返回 null。
                val updated = requestRepository.beginRetry(
                    requestId = requestId,
                    newAttemptId = newAttempt,
                    refreshedSnapshotJson = null,
                    refreshedAttachments = attachments,
                )
                // beginRetry **提交成功**：无论返回路径是否被取消，回滚都按已知 id 执行。
                if (updated == null) {
                    fail(opToken, null)
                    refreshRetryEntry()
                    return@launch
                }
                val conversationId = updated.conversationId
                if (opToken == retryOperationSeq && navigationEpoch == capturedEpoch) {
                    activeAnswerMessageId = updated.answerMessageId
                }
                // ── 立即交接（前面所有挂起都已完成）──
                generationManager.submitRetry(
                    requestId = requestId,
                    attemptId = newAttempt,
                    history = history,
                    attachmentPaths = attachments,
                    // null 表示"无刷新结果"；空串是有效刷新（不复活旧 sourceContext）。
                    refreshedContext = refreshedContext,
                    userText = retryUserText,
                    forceWebSearch = capturedForceWebSearch,
                )
                // 接受成功后 busy 从**管理器当前状态**派生：submitRetry 返回时
                // 请求可能已经全部跑完（快速完成），无条件 busy=true 会让界面
                // 卡在「生成中」；而「正在跑且属于本会话」才显示生成中。
                if (opToken == retryOperationSeq && navigationEpoch == capturedEpoch) {
                    _state.update {
                        it.copy(
                            retryRequestId = null,
                            retryAnswerMessageId = null,
                            retryReason = null,
                            busy = generationManager.state.value.let { active ->
                                active.isRunning && active.conversationId == conversationId
                            },
                        )
                    }
                }
            } catch (already: com.example.lixing.assistant.AssistantGenerationManager.AlreadyRunningException) {
                fail(opToken, "另一个会话正在生成回答，请稍后再重试")
            } catch (e: CancellationException) {
                throw e // 回滚（含被采纳轮的保护）统一在 finally 按**当前**管理器身份判断。
            } catch (error: Exception) {
                fail(opToken, error.message ?: "重试失败")
            } finally {
                // 只要 beginRetry 已提交而管理器没有把这条 attempt 跑起来
                // （包括协程在 submitRetry 之前/之中被取消），就必须回滚：
                // PREPARING 永驻会让记录既不能重试也不能被扫描救回。
                rollbackRejectedRetry(requestId, knownAttempt)
                if (opToken == retryOperationSeq && navigationEpoch == capturedEpoch) {
                    _state.update { it.copy(retrying = false) }
                }
            }
        }
    }

    /**
     * 交接失败的回滚：管理器**当前**没有在这条 request+newAttempt 上跑时，
     * 把 beginRetry 写出的 PREPARING 行**立即**收成 INTERRUPTED（保留
     * partial/confirmed）。
     *
     * - **回滚时读当前管理器身份**（不是交接前捕获的快照）：submitRetry 已把
     *   owner/ActiveState 登记好后调用协程才被取消的场合，当前身份已匹配 ——
     *   这一轮是活着的，回滚绝不能把它掐断（VM 销毁前后都成立）；
     * - **NonCancellable**：调用方常处于取消中，任何挂起点都会再抛取消 ——
     *   不落账的回滚等于没回滚；
     * - beginRetry 重置过 failure 字段，回滚时补一个可重试的 NETWORK 归类，
     *   让重试入口马上恢复。
     */
    private suspend fun rollbackRejectedRetry(requestId: String, newAttemptId: String?) {
        val attempt = newAttemptId ?: return
        withContext(NonCancellable) {
            runCatching {
                val record = requestRepository.get(requestId)
                if (record == null || record.attemptId != attempt ||
                    record.status !in setOf("PREPARING", "RUNNING")) return@runCatching
                // **当前**管理器身份：只有确认 manager 没有在跑这条 attempt 才回滚。
                val active = generationManager.state.value
                if (active.requestId == requestId && active.attemptId == attempt) return@runCatching
                requestRepository.interrupt(
                    requestId = requestId,
                    attemptId = attempt,
                    partialText = record.partialText,
                    kind = com.example.lixing.domain.assistant.AssistantFailureKind.NETWORK,
                    message = "重试未能开始，可再点重试继续",
                )
                if (activeRequestId == requestId && activeAttemptId == attempt) activeAnswerMessageId = null
                currentPageRequestsLoaded = false
                refreshRetryEntry()
            }
        }
    }

    /**
     * 重试前的身份校验：当初提交时钉下的身份与**现在会实际调用**的身份是否一致。
     *
     * ## 快照缺失时**不能**跳过校验
     *
     * 早先 `decode(...) ?: return null` 把「快照为空」当成"旧记录，放行"。
     * 但快照为空恰恰发生在**准备阶段就被打断**的请求上 —— 那时根本没跑完
     * `prepare()`，快照从未写入。放行它意味着：一个我们**完全不知道**
     * 当初发给谁的请求，会被静默发到当前配置的任意供应商。
     *
     * 现在区分两种情况：
     * - 快照**存在且一致** ⇒ 放行；
     * - 快照**缺失** ⇒ 明确告知无法核验、建议重新发送（不静默放行）。
     */
    private suspend fun retryIdentityMismatch(snapshotJson: String): String? {
        val snapshot = com.example.lixing.data.assistant.AssistantSnapshotCodec.decode(snapshotJson)
        if (snapshot == null) {
            return "这条请求缺少可核验的配置记录（可能是准备阶段就被中断了），" +
                "无法确认当初使用的是哪个模型与接口"
        }
        // **钉住快照里的主档案**（与 preparer 同语义）：快照记录了当初实际使用的
        // primaryProfileId —— 原档案仍可用时按它校验（切换活动档案不算不一致）；
        // **原档案已删除/密钥失效时必须拒绝**，绝不静默回退到另一个供应商
        // （preparer 对缺失的钉住档案同样是失败）。仅当快照根本没有钉住档案
        // （legacy 偏好配置）时才回退到当前活动配置。
        val prefs = prefsRepository.current()
        val identity = if (snapshot.primaryProfileId.isNotBlank()) {
            aiCredentialStore.resolveIdentityFor(snapshot.primaryProfileId)
                ?: return "这条请求当时使用的模型配置已不存在，无法按原配置重试。请重新发送这道题。"
        } else {
            aiCredentialStore.resolveActiveIdentity(prefs.aiBaseUrl, prefs.aiModel)
                ?: return "当前没有可用的模型配置（密钥缺失），无法按原配置重试"
        }
        val baseUrl = identity.baseUrl
        val model = identity.model
        val protocol = com.example.lixing.assistant.GenerationPreparer.protocolOf(
            baseUrl,
            identity.searchProtocol,
        )
        val endpoint = com.example.lixing.data.assistant.endpointIdentityOf(baseUrl)
        return when {
            model != snapshot.model ->
                "原请求使用的模型是「${snapshot.model}」，当前已改为「$model」"
            endpoint != snapshot.endpointIdentity ->
                "原请求使用的接口是「${snapshot.endpointIdentity}」，当前已改为「$endpoint」"
            protocol != snapshot.protocol ->
                "原请求使用的协议是「${snapshot.protocol}」，当前已改为「$protocol」"
            else -> null
        }
    }

    /** 用户主动取消当前生成：真正取消网络请求，**不自动重发**。 */
    fun cancelGeneration() {
        val requestId = generationManager.activeRequestId() ?: return
        val attemptId = generationManager.state.value.attemptId
        viewModelScope.launch {
            try {
                generationManager.cancel(requestId, attemptId)
            } finally {
                // 围栏：只有**这一轮**仍是当前跟踪轮时才清 busy ——
                // 取消挂起期间若有新一轮开始/收尾，旧取消绝不能清掉它。
                val cleared = isSameTurn(
                    eventRequestId = requestId,
                    eventAttemptId = attemptId ?: "",
                    eventConversationId = generationManager.state.value.conversationId,
                    trackedRequestId = activeRequestId,
                    trackedAttemptId = activeAttemptId,
                    currentConversationId = _state.value.currentConversationId,
                )
                if (cleared) {
                    _state.update { it.copy(busy = false, retrying = false) }
                    refreshRetryEntry()
                }
            }
        }
    }

    /**
     * 重新计算「输入框左侧重试入口」是否该出现。
     *
     * 规则：取**当前会话请求行**里最新一条 INTERRUPTED 且可重试的请求，并且
     * 它必须是**最新一条用户消息**对应的请求 —— 更新的成功提问之后绝不能残留
     * 更老的中断入口。
     *
     * 双围栏（见 [retryEntryEpoch] / [retryEntryOwns]）：
     * - 读库前固定「最新用户消息 id」与入口代数；挂起返回后任一变化 ⇒ 本次
     *   结果整体作废（晚返回的旧读不能顶掉新读）；
     * - 状态写入时再次核验，避免与并发更新的交错。
     */
    private suspend fun refreshRetryEntry() {
        val conversationId = _state.value.currentConversationId
        if (conversationId == null) {
            _state.update { it.copy(retryRequestId = null, retryAnswerMessageId = null, retryReason = null) }
            return
        }
        val entryEpoch = ++retryEntryEpoch
        val epochAtStart = navigationEpoch
        val latestUserIdAtStart = latestUserMessageIdByPage
        // 请求行来源：已加载的当前页缓存优先（**空缓存 ≠ 未加载**）；观察流尚未
        // 首射时兜底一次性读库。**不满足缓存命中时必须回读**，避免回滚后缓存过期。
        val requests: List<com.example.lixing.data.local.entity.AssistantRequestEntity> =
            if (currentPageRequestsLoaded) {
                currentPageRequests
            } else {
                try {
                    requestRepository.forConversation(conversationId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            }
        // 「最新用户消息」尚未由消息观察流送达时兜底读库（否则新页首算会误判为无约束）。
        val latestUserMessageId = latestUserIdAtStart
            ?: try {
                chatRepository.messages(conversationId).lastOrNull { it.role == "user" }?.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        // 挂起返回后的围栏：会话/导航/入口代数/最新用户消息 任一变化 ⇒ 作废。
        if (_state.value.currentConversationId != conversationId) return
        if (navigationEpoch != epochAtStart) return
        if (retryEntryEpoch != entryEpoch) return
        if (!retryEntryOwns(latestUserIdAtStart)) return
        // **先取最新一条请求，再看它本身是否可展示** —— 绝不跳过它去找更老的中断。
        val candidate = requests.lastOrNull()
        val kind = com.example.lixing.domain.assistant.AssistantFailureKind.fromName(candidate?.failureKind)
        val showable = candidate != null &&
            candidate.status == com.example.lixing.domain.assistant.AssistantRequestStatus.INTERRUPTED.name &&
            (kind?.isRetryable ?: true) &&
            (latestUserMessageId == null || candidate.userMessageId == latestUserMessageId)
        _state.update {
            // state.update 内与并发更新交错，再核验一次归属。
            if (_state.value.currentConversationId != conversationId) return@update it
            if (navigationEpoch != epochAtStart) return@update it
            if (retryEntryEpoch != entryEpoch) return@update it
            if (!retryEntryOwns(latestUserIdAtStart)) return@update it
            it.copy(
                retryRequestId = if (showable) candidate?.requestId else null,
                retryAnswerMessageId = if (showable) candidate?.answerMessageId else null,
                retryReason = if (showable) describeFailure(kind) else null,
            )
        }
    }

    private fun describeFailure(kind: com.example.lixing.domain.assistant.AssistantFailureKind?): String =
        when (kind) {
            com.example.lixing.domain.assistant.AssistantFailureKind.NETWORK -> "网络中断"
            com.example.lixing.domain.assistant.AssistantFailureKind.SERVER -> "服务端未正常响应"
            com.example.lixing.domain.assistant.AssistantFailureKind.NO_CONTENT -> "没有生成有效正文"
            com.example.lixing.domain.assistant.AssistantFailureKind.INVALID_RESPONSE -> "返回内容无法解析"
            com.example.lixing.domain.assistant.AssistantFailureKind.ATTACHMENT_MISSING -> "附件已不可用"
            com.example.lixing.domain.assistant.AssistantFailureKind.CONFIG_INVALID -> "模型配置已失效"
            com.example.lixing.domain.assistant.AssistantFailureKind.CANCELLED -> "已取消"
            else -> "上次生成中断"
        }

    /**
     * 收尾：正文、图片与待确认信封**已由管理器原子落库**。
     *
     * 本方法只把这些结果搬到界面上，**不再**绘图、不再写库 ——
     * 以前这两件事写在这里，于是「收尾恰好发生在页面已销毁时」图和方案就永久丢了。
     * 现在页面销毁也照样保存完整，重新订阅即可看到。
     */
    private suspend fun onTurnCompleted(
        event: com.example.lixing.assistant.AssistantGenerationManager.GenerationEvent.Completed,
    ) {
        val conversationId = event.conversationId
        val answerMessageId = event.answerMessageId
        val attemptId = event.attemptId
        val result = event.result
        // 挂起前固定完整围栏：轮身份 + 会话 + **导航轮次** —— 迟到的 Completed
        // 绝不能写进已经切走的页面（A/B/A）。
        val epoch = navigationEpoch
        if (activeRequestId != event.requestId || activeAttemptId != attemptId) return
        if (_state.value.currentConversationId != conversationId) return
        // ── 权威 DB 对账 ── 管理器已把正文/图片/信封**原子落库**。这里读权威消息行
        // 并复用统一合并；预览由落库信封恢复（同一来源，不再双轨重建）。
        // **读库失败必须 return**：拿不到行就绝不能拿空列表去「对账」清掉现有气泡。
        val storedMessages = try {
            chatRepository.messages(conversationId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        // 预览/图片恢复失败只跳过本次补齐，绝不能杀掉事件收集器。
        try {
            applyStoredMessages(conversationId, storedMessages)
            if (_state.value.currentConversationId != conversationId) return
            if (navigationEpoch != epoch) return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AssistantVM", "完成恢复失败", e)
        }
        // 挂起之后的完整围栏：同一轮仍在跟踪、页面/导航未变才写收尾状态。
        if (activeRequestId != event.requestId || activeAttemptId != attemptId) return
        if (_state.value.currentConversationId != conversationId || navigationEpoch != epoch) return
        // 图片兜底：DB 流尚未推送（或信封行已带图但合并早于此）时按固定回答位补挂。
        if (imageApplyWanted(result, storedMessages, answerMessageId)) {
            _state.update { state ->
                if (state.currentConversationId != conversationId || navigationEpoch != epoch) return@update state
                val paths = storedMessages.lastOrNull { it.id == answerMessageId }
                    ?.let { runCatching { chatRepository.decodeImagePaths(it.imagePaths) }.getOrDefault(emptyList()) }
                    .orEmpty()
                if (paths.isEmpty()) return@update state
                state.copy(messages = replaceMessageImagesIn(answerMessageId, state.messages, paths))
            }
        }
        _state.update { it.copy(busy = false, retrying = false) }
        activeAnswerMessageId = null
        activeRequestId = null
        activeAttemptId = null
        refreshRetryEntry()
        // 草稿清理**只在仍与发送内容一致时**进行；清理失败绝不能杀掉事件收集器。
        try {
            clearDraftIfUnchanged(conversationId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AssistantVM", "收尾草稿清理失败", e)
        }
    }

    /** Completed 时是否需要把落库图片挂到回答气泡（有图且尚未挂上）。 */
    private fun imageApplyWanted(
        result: com.example.lixing.data.assistant.ParsedAssistantReply,
        storedMessages: List<com.example.lixing.data.local.entity.AssistantMessageEntity>,
        answerMessageId: String,
    ): Boolean {
        val storedRow = storedMessages.lastOrNull { it.id == answerMessageId } ?: return false
        val storedImages = runCatching { chatRepository.decodeImagePaths(storedRow.imagePaths) }
            .getOrDefault(emptyList())
        return storedImages.isNotEmpty() || result.orderedFigures().isNotEmpty()
    }

    /**
     * 清理草稿，但**只在草稿仍等于"发送后留下的那份"时**才清。
     *
     * 逻辑在 [AssistantDraftStore.clearIfBaselineMatches]（原子、按会话串行）；
     * 这里按**发送时占据的那个草稿槽**（可能仍是 null 新会话槽）提供基准，
     * 并清掉内存记录。基准不存在（从未记录）时保守起见**不清**。
     */
    private suspend fun clearDraftIfUnchanged(conversationId: String) {
        val baseline = consumedDrafts[conversationId] ?: return
        val cleared = try {
            draftStore.clearIfBaselineMatches(
                conversationId = baseline.slot,
                baselineText = baseline.text,
                baselinePhotos = baseline.photoPaths,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportDraftIoFailure(e, _state.value.currentConversationId, navigationEpoch)
            return
        }
        if (cleared) consumedDrafts.remove(conversationId)
    }

    /** 失败：保留部分正文与原因，给出可重试入口（仅可重试类别）。 */
    private suspend fun onTurnFailed(
        event: com.example.lixing.assistant.AssistantGenerationManager.GenerationEvent.Failed,
    ) {
        // 围栏（挂起前）：这条失败必须仍属于**当前跟踪轮** —— handleGenerationEvent
        // 已按 request+attempt+会话核对过；本地中断 partial 的恢复交给 DB 流
        // （mergeStoredMessagesIntoUi 按 answerMessageId 合并请求行 partial）。
        if (activeRequestId != event.requestId || activeAttemptId != event.attemptId) return
        activeAnswerMessageId = null
        // 本轮结束：清空事件围栏，避免下一轮之前还有旧事件写进来。
        activeRequestId = null
        activeAttemptId = null
        _state.update {
            it.copy(
                busy = false,
                retrying = false,
                activeReasoning = "",
                activeAnswerStarted = false,
                error = if (event.kind == com.example.lixing.domain.assistant.AssistantFailureKind.CANCELLED) {
                    null
                } else {
                    "生成中断：${event.message}"
                },
            )
        }
        // 中断时**不删草稿**：这一轮没成功，用户可能要重发同样内容；
        // 而且中断时用户多半正在输入框里写别的。
        refreshRetryEntry()
    }

    /** 按固定回答消息 id 替换正文，避免列表下标漂移时写错气泡。 */
    private fun replaceAnswerText(answerMessageId: String, text: String) {
        _state.update { state ->
            val index = indexOfMessage(answerMessageId, state)
            if (index < 0) return@update state
            val list = state.messages.toMutableList()
            list[index] = list[index].copy(content = text)
            state.copy(messages = list)
        }
    }

    /** 纯函数版：在给定列表上替换正文，供一次性组合多次改动（正文 + 图片）。 */
    private fun replaceAnswerTextIn(
        answerMessageId: String,
        state: AssistantUiState,
        text: String,
    ): List<AssistantMessage> {
        val index = state.messages.indexOfLast { it.id == answerMessageId }
        if (index < 0) return state.messages
        val list = state.messages.toMutableList()
        list[index] = list[index].copy(content = text)
        return list
    }

    /** 纯函数版：在给定列表上挂图片。 */
    private fun replaceMessageImagesIn(
        answerMessageId: String,
        messages: List<AssistantMessage>,
        imagePaths: List<String>,
    ): List<AssistantMessage> {
        val index = messages.indexOfLast { it.id == answerMessageId }
        if (index < 0) return messages
        val list = messages.toMutableList()
        list[index] = list[index].copy(imagePaths = imagePaths)
        return list
    }

    /**
     * 找到回答消息在界面列表里的下标。
     *
     * 用**固定消息 id** 而不是「最后一条 assistant」：重试、切会话、
     * 追加新消息都会让位置变化，按位置找会把正文写进别的气泡。
     */
    private fun indexOfMessage(answerMessageId: String, state: AssistantUiState): Int =
        state.messages.indexOfLast { it.id == answerMessageId }

    /** 只持久化非敏感的重试快照：模型/端点身份、推理与联网设置、只读上下文。 */
    private fun buildSnapshotJson(
        model: String,
        endpointKind: String,
        reasoningEffort: String,
        webSearchEnabled: Boolean,
        context: String,
    ): String = buildString {
        append("{\"model\":\"").append(model.replace("\"", "")).append("\",")
        append("\"endpoint\":\"").append(endpointKind).append("\",")
        append("\"reasoning\":\"").append(reasoningEffort).append("\",")
        append("\"webSearch\":").append(webSearchEnabled).append(",")
        append("\"chars\":").append(context.length).append("}")
    }


    // ---------------- 计划建议 ----------------

    fun togglePendingAction(index: Int) = _state.update { state ->
        state.copy(
            pendingActions = state.pendingActions.mapIndexed { i, item ->
                if (i == index && item.problem == null) item.copy(selected = !item.selected) else item
            },
        )
    }

    fun openPlanReview() = _state.update { state ->
        if (state.pendingActions.isEmpty()) state else state.copy(planReviewOpen = true)
    }

    fun closePlanReview() = _state.update { it.copy(planReviewOpen = false) }

    /** 放弃这版待确认方案。 */
    fun dismissPendingActions() {
        val conversationId = _state.value.currentConversationId
        _state.update {
            it.copy(
                pendingActions = emptyList(),
                pendingActionsOwnerIndex = null,
                planReviewOpen = false,
                planReviewDate = null,
                planReviewAppliedCount = 0,
            )
        }
        // 落库的信封也要清掉：否则重开这个会话时，已经被用户放弃的方案又会「复活」。
        if (conversationId != null) {
            viewModelScope.launch {
                runCatching { chatRepository.updatePendingReviewForConversation(conversationId, "") }
            }
        }
    }

    // ---------------- 英语积累变更 ----------------

    fun toggleEnglishAction(index: Int) = _state.update { state ->
        state.copy(
            pendingEnglishActions = state.pendingEnglishActions.mapIndexed { i, item ->
                if (i == index && item.problem == null) item.copy(selected = !item.selected) else item
            },
        )
    }

    fun openEnglishReview() = _state.update { state ->
        if (state.pendingEnglishActions.isEmpty()) state else state.copy(englishReviewOpen = true)
    }

    fun closeEnglishReview() = _state.update { it.copy(englishReviewOpen = false) }

    fun dismissEnglishActions() = _state.update {
        it.copy(
            pendingEnglishActions = emptyList(),
            pendingEnglishOwnerIndex = null,
            englishReviewOpen = false,
        )
    }

    /** 应用勾选的英语积累变更：逐条落库，失败项不影响其余条目。 */
    fun applySelectedEnglish() {
        val selected = _state.value.pendingEnglishActions.filter { it.selected && it.problem == null }
        if (selected.isEmpty() || _state.value.applyingEnglish) return
        // **在点击瞬间**同步捕获会话/批次/选择并置 busy（含旧数据无批次 id 分支）。
        val conversationId = _state.value.currentConversationId
        val batchMessageId = _state.value.pendingActionsOwnerMessageId
        val operationId = nextEnglishApplyOperation()
        _state.update { it.copy(applyingEnglish = true, applyMessage = null) }
        if (batchMessageId == null) {
            // 旧数据（信封尚未带固定 id）：降级为旧行为，不做原子认领。
            viewModelScope.launch { applyEnglishWithoutClaim(selected, operationId, conversationId) }
            return
        }
        viewModelScope.launch {
            try {
                applyEnglishWithClaim(selected, batchMessageId, conversationId, operationId)
            } catch (e: Exception) {
                // 取消必须原样上抛（结构化并发），不能被折成一条错误文案。
                if (e is CancellationException) throw e
                // 迟到的失败只许改**自己那页**的状态，不能污染新批次/新页面。
                if (!operationOwnsEnglishPage(operationId, batchMessageId, conversationId)) return@launch
                _state.update {
                    it.copy(applyingEnglish = false, applyMessage = "应用失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    /**
     * 英语确认：整个批次（认领 + 实际写入 + 最终信封）在**一个 Room 事务**里完成。
     *
     * 计划与英语的已应用标记在同一个信封里但**相互独立**：
     * 认领英语时只置 `englishApplied`，不动计划的 `applied`；反之亦然。
     * 这样才能「应用了一类、另一类还能继续确认」，不会把另一类的待确认项丢掉。
     *
     * [conversationId] 是点击瞬间捕获的归属；[operationId] 是本次点击的围栏。
     */
    private suspend fun applyEnglishWithClaim(
        selected: List<PendingEnglishAction>,
        batchMessageId: String,
        conversationId: String?,
        operationId: Long,
    ) {
        if (conversationId == null) {
            // 无会话可落（理论上不可达）：不认领，按不可执行收敛。
            if (!operationOwnsEnglishPage(operationId, batchMessageId, conversationId)) return
            markEnglishAlreadyApplied()
            return
        }
        val outcome = reviewTransactions.applyEnglish(
            conversationId = conversationId,
            messageId = batchMessageId,
            selected = selected.map { it.action },
        )
        // 事务返回后才动界面；归属已变就**什么都不改**，不许清掉新页面的 busy/新批次。
        if (!operationOwnsEnglishPage(operationId, batchMessageId, conversationId)) return
        when (outcome) {
            is com.example.lixing.data.repository.EnglishReviewTransactionResult.Applied ->
                finishEnglishApply(outcome.outcome)

            is com.example.lixing.data.repository.EnglishReviewTransactionResult.NothingApplied ->
                // 全部失败：标记未被消费（事务回滚），界面保持原「未成功」表现。
                finishEnglishApply(outcome.outcome)

            is com.example.lixing.data.repository.EnglishReviewTransactionResult.AlreadyConsumed ->
                markEnglishAlreadyApplied()

            is com.example.lixing.data.repository.EnglishReviewTransactionResult.StaleEnvelope ->
                // 信封已不在/损坏：按原「已应用过」文案收敛（旧路径同语义）。
                markEnglishAlreadyApplied()

            is com.example.lixing.data.repository.EnglishReviewTransactionResult.Rejected ->
                // 所选动作不属于这批方案：**没有动作被执行**，只显示原因，
                // 不声称「已应用」，也不清掉当前待确认项。
                _state.update {
                    it.copy(applyingEnglish = false, applyMessage = "未执行：${outcome.reason}")
                }
        }
    }

    /** 把英语执行结果落到界面（沿用原「新增/修改/删除 + 失败项」文案）。 */
    private fun finishEnglishApply(outcome: com.example.lixing.data.repository.EnglishApplyOutcome) {
        val mapped = EnglishApplyOutcome(
            added = outcome.added,
            updated = outcome.updated,
            deleted = outcome.deleted,
            failures = outcome.failures.map { failure ->
                val title = pendingEnglishTitleFor(failure.action)
                "$title（${failure.message}）"
            },
        )
        _state.update {
            it.copy(
                applyingEnglish = false,
                applyMessage = englishApplyMessage(mapped),
                pendingEnglishActions = emptyList(),
                pendingEnglishOwnerIndex = null,
                englishReviewOpen = false,
            )
        }
    }

    /** 用动作本体找回它的展示标题（找不到时给个稳定兜底）。 */
    private fun pendingEnglishTitleFor(action: com.example.lixing.domain.assistant.EnglishEntryAction): String {
        val state = _state.value
        return state.pendingEnglishActions.firstOrNull { it.action == action }?.title
            ?: "英语变更"
    }

    /**
     * 旧数据（无批次 id）的英语降级路径：保持旧行为，不做原子认领。
     *
     * [operationId]/[capturedConversationId] 由入口在点击瞬间捕获并传入，
     * 这里**不得**再递增操作号或置 busy（否则会顶掉更新的操作）。
     */
    private suspend fun applyEnglishWithoutClaim(
        selected: List<PendingEnglishAction>,
        operationId: Long,
        capturedConversationId: String?,
    ) {
        try {
            val outcome = runEnglishActions(selected)
            // 围栏：operationId 仍最新 且 还停在同一个会话（旧数据无批次 id，按 null 精确比较）。
            if (!operationOwnsEnglishPage(operationId, null, capturedConversationId)) return
            _state.update {
                it.copy(
                    applyingEnglish = false,
                    applyMessage = englishApplyMessage(outcome),
                    pendingEnglishActions = emptyList(),
                    pendingEnglishOwnerIndex = null,
                    englishReviewOpen = false,
                )
            }
        } catch (e: Exception) {
            // 取消必须原样上抛，且先于任何归属检查；否则页面会永久 busy。
            if (e is CancellationException) throw e
            if (!operationOwnsEnglishPage(operationId, null, capturedConversationId)) return
            _state.update {
                it.copy(
                    applyingEnglish = false,
                    applyMessage = "应用失败：${e.message ?: "未知错误"}",
                )
            }
        }
    }

    private data class EnglishApplyOutcome(
        val added: Int,
        val updated: Int,
        val deleted: Int,
        val failures: List<String>,
    ) {
        val total: Int get() = added + updated + deleted
    }

    /** 逐条执行英语副作用；单条失败不影响其余条目。 */
    private suspend fun runEnglishActions(selected: List<PendingEnglishAction>): EnglishApplyOutcome {
        var added = 0
        var updated = 0
        var deleted = 0
        val failures = mutableListOf<String>()
        selected.forEach { item ->
            runCatching {
                when (val action = item.action) {
                    is EnglishEntryAction.Add -> {
                        englishEntryRepository.save(null, action.type, action.content, action.meaning)
                        added++
                    }
                    is EnglishEntryAction.Update -> {
                        val existing = englishEntryRepository.get(action.id)
                            ?: error("要修改的条目已不存在")
                        englishEntryRepository.save(
                            existing,
                            action.type ?: existing.type,
                            action.content ?: existing.content,
                            action.meaning ?: existing.meaning,
                        )
                        updated++
                    }
                    is EnglishEntryAction.Delete -> {
                        val existing = englishEntryRepository.get(action.id)
                            ?: error("要删除的条目已不存在")
                        englishEntryRepository.delete(existing)
                        deleted++
                    }
                }
            }.onFailure { error ->
                failures += "${item.title}（${error.message ?: "未知错误"}）"
            }
        }
        return EnglishApplyOutcome(added, updated, deleted, failures)
    }

    private fun englishApplyMessage(outcome: EnglishApplyOutcome): String = buildString {
        append("英语积累已更新：新增 ${outcome.added} 条，修改 ${outcome.updated} 条，删除 ${outcome.deleted} 条")
        if (outcome.failures.isNotEmpty()) {
            append("；${outcome.failures.size} 条未成功：")
            append(outcome.failures.joinToString("；"))
        }
    }

    private fun markEnglishAlreadyApplied() {
        _state.update {
            it.copy(
                applyingEnglish = false,
                applyMessage = "这批英语变更已经应用过了，未重复执行",
                pendingEnglishActions = emptyList(),
                pendingEnglishOwnerIndex = null,
                englishReviewOpen = false,
            )
        }
    }

    /** 这一批英语变更是否已落库标记为「已应用」。 */
    private suspend fun isEnglishBatchAlreadyApplied(messageId: String): Boolean {
        val raw = runCatching { chatRepository.pendingReviewOf(messageId) }.getOrNull().orEmpty()
        return decodePendingReview(raw)?.englishApplied == true
    }

    /** 只把英语批次的已应用标记写回指定消息，保留计划批次的状态不变。 */
    private suspend fun markEnglishBatchApplied(messageId: String) {
        val raw = runCatching { chatRepository.pendingReviewOf(messageId) }.getOrNull().orEmpty()
        val payload = decodePendingReview(raw) ?: return
        if (payload.englishApplied) return // 幂等
        chatRepository.updatePendingReview(
            messageId,
            encodePendingReview(payload.copy(englishApplied = true)),
        )
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    fun consumeApplyMessage() = _state.update { it.copy(applyMessage = null) }

    /** 应用勾选项：点击瞬间固定批次归属，事务完成后再按围栏更新界面。 */
    fun applySelected() {
        val selected = _state.value.pendingActions.filter { it.selected && it.problem == null }
        if (selected.isEmpty() || _state.value.applying) return
        // **在点击瞬间**同步捕获会话/批次/选择并置 busy：launch 之后再读 state，
        // 读到的可能已经是用户切走后的另一页。batchId 允许为 null（旧数据无固定批次 id）。
        val conversationId = _state.value.currentConversationId
        val batchMessageId = _state.value.pendingActionsOwnerMessageId
        val operationId = nextPlanApplyOperation()
        _state.update { it.copy(applying = true, applyMessage = null) }
        if (batchMessageId == null) {
            // 没有批次 id：这是旧数据（信封尚未带固定 id）。
            // 仍然允许应用，但**不能**做原子认领 —— 保持旧行为并明确提示。
            viewModelScope.launch { applyPlanWithoutClaim(selected, operationId, conversationId) }
            return
        }
        viewModelScope.launch {
            try {
                applyPlanWithClaim(selected, batchMessageId, conversationId, operationId)
            } catch (e: Exception) {
                // 取消必须原样上抛（结构化并发），不能被折成一条错误文案。
                if (e is CancellationException) throw e
                // 迟到的失败只许改**自己那页**的状态，不能污染新批次/新页面。
                if (!operationOwnsPlanPage(operationId, batchMessageId, conversationId)) return@launch
                _state.update {
                    it.copy(applying = false, applyMessage = "应用失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    /**
     * 计划确认：恢复点先建（事务外），然后整个批次
     * （认领 + 实际计划写入 + 最终信封）在**一个 Room 事务**里完成。
     *
     * 认领失败 = 别的入口已经在处理这一批，这里直接以"已应用"收场，
     * 绝不再执行一遍副作用。事务里绝不跑备份/网络。
     *
     * [conversationId] 是点击瞬间捕获的归属；[operationId] 是本次点击的围栏，
     * 事务返回后只有仍持有它（且会话/批次匹配）才允许改界面。
     */
    private suspend fun applyPlanWithClaim(
        selected: List<PendingPlanAction>,
        batchMessageId: String,
        conversationId: String?,
        operationId: Long,
    ) {
        if (conversationId == null) {
            // 无会话可落（理论上不可达）：不认领，按不可执行收敛。
            if (!operationOwnsPlanPage(operationId, batchMessageId, conversationId)) return
            markAlreadyApplied()
            return
        }
        val prefs = prefsRepository.current()
        val today = StudyClock(dayStart = prefs.dayStartTime).today()
        // **恢复点在事务之前**：事务内部只有本地 DB 写（备份/网络一律在外面）。
        versionedBackupRepository.createLocalVersion(reason = "before_ai_apply")
        val outcome = reviewTransactions.applyPlan(
            conversationId = conversationId,
            messageId = batchMessageId,
            selected = selected.map { it.action },
            today = today,
            dayOffLimit = prefs.dayOffPerMonth,
        )
        // 事务返回后才动界面；归属已变（切会话/换批次/新操作）就**什么都不改**，
        // 不许把新页面的 applying 或新批次清掉。
        if (!operationOwnsPlanPage(operationId, batchMessageId, conversationId)) return
        when (outcome) {
            is com.example.lixing.data.repository.PlanReviewTransactionResult.Applied -> {
                val results = outcome.results
                val okCount = results.count { it.success }
                val failures = results.filterNot { it.success }
                // 部分成功 ⇒ 标记已消费；全部失败 ⇒ 事务已回滚、标记未消费。
                // 两者界面表现与旧路径一致：失败项留在列表里，提示回对话重新生成方案。
                val message = buildApplyMessage(okCount, failures)
                updateStateAfterPlanApply(okCount, failures)
                _state.update { it.copy(applying = false, applyMessage = message) }
            }

            is com.example.lixing.data.repository.PlanReviewTransactionResult.NothingApplied -> {
                // 全部失败：事务已回滚、标记未消费（可原样重试），逐条失败反馈沿用。
                val failures = outcome.results.filterNot { it.success }
                val message = buildApplyMessage(0, failures)
                updateStateAfterPlanApply(0, failures)
                _state.update { it.copy(applying = false, applyMessage = message) }
            }

            is com.example.lixing.data.repository.PlanReviewTransactionResult.AlreadyConsumed ->
                markAlreadyApplied()

            is com.example.lixing.data.repository.PlanReviewTransactionResult.StaleEnvelope -> {
                // 信封已不在（被清掉/损坏）：按原「已失效」文案收敛，**绝不**显示已应用。
                _state.update {
                    it.copy(
                        applying = false,
                        applyMessage = "这批方案已失效，未重复执行",
                        pendingActions = emptyList(),
                        pendingActionsOwnerIndex = null,
                        pendingActionsOwnerMessageId = null,
                    )
                }
            }

            is com.example.lixing.data.repository.PlanReviewTransactionResult.Rejected -> {
                // 所选动作不属于这批方案：**没有任何动作被执行**，只显示拒绝原因，
                // 不能声称「已经应用过」。保留其它待确认项（含另一类）不动。
                _state.update {
                    it.copy(applying = false, applyMessage = "未执行：${outcome.reason}")
                }
            }
        }
    }

    // ---------------- 确认围栏（点击瞬间固定归属，返回后按围栏改界面） ----------------

    /** 计划/英语各自的递增操作号：确认界面只允许**最新一次**点击的返回值改状态。 */
    private var planApplyOperationSeq = 0L
    private var englishApplyOperationSeq = 0L

    private fun nextPlanApplyOperation(): Long = ++planApplyOperationSeq

    private fun nextEnglishApplyOperation(): Long = ++englishApplyOperationSeq

    /**
     * 导航瞬间**同步作废**计划/英语两路确认操作号：旧页面在途确认的返回值
     * 从此永远不再持有页面 —— 即使新页面恰好停留在同一个会话/批次上，
     * 迟到的旧结果也一个字段都不能改（A/B/A 围栏的一部分）。
     */
    private fun invalidateApplyOperations() {
        planApplyOperationSeq++
        englishApplyOperationSeq++
    }

    /**
     * 本次确认是否仍拥有页面与批次。
     *
     * 三个条件同时满足才允许写 UI：
     * 1. 没有更新的同类确认点击（operationId 仍是当前计数）；
     * 2. 还停留在同一个会话（没切走/切页）；
     * 3. 待确认批次还是同一个固定消息 id（没有被新方案顶掉）。
     *
     * [batchMessageId] 与 state 里的 `pendingActionsOwnerMessageId` 一样**可空**：
     * 旧数据没有批次 id，两者都为 null 时按 null 精确相等比较 ——
     * **绝不**把 null 转成空串，否则 null vs "" 永远不相等，busy 永远清不掉。
     *
     * 返回 false ⇒ 什么都不改：既不清 busy，也不写失败文案，
     * 避免迟到的旧结果覆盖新页面/新批次。
     */
    private fun operationOwnsPage(
        isPlan: Boolean,
        operationId: Long,
        batchMessageId: String?,
        conversationId: String?,
    ): Boolean {
        if (isPlan) {
            if (operationId != planApplyOperationSeq) return false
        } else {
            if (operationId != englishApplyOperationSeq) return false
        }
        val state = _state.value
        return state.currentConversationId == conversationId &&
            state.pendingActionsOwnerMessageId == batchMessageId
    }

    private fun operationOwnsPlanPage(
        operationId: Long,
        batchMessageId: String?,
        conversationId: String?,
    ): Boolean = operationOwnsPage(
        isPlan = true,
        operationId = operationId,
        batchMessageId = batchMessageId,
        conversationId = conversationId,
    )

    private fun operationOwnsEnglishPage(
        operationId: Long,
        batchMessageId: String?,
        conversationId: String?,
    ): Boolean = operationOwnsPage(
        isPlan = false,
        operationId = operationId,
        batchMessageId = batchMessageId,
        conversationId = conversationId,
    )

    /**
     * 旧数据（无批次 id）的降级路径：保持旧行为，不做原子认领。
     *
     * [operationId]/[capturedConversationId] 由入口在点击瞬间捕获并传入，
     * 这里**不得**再递增操作号或置 busy（否则会顶掉更新的操作）。
     */
    private suspend fun applyPlanWithoutClaim(
        selected: List<PendingPlanAction>,
        operationId: Long,
        capturedConversationId: String?,
    ) {
        try {
            val (okCount, failures) = runApplier(selected)
            // 围栏：operationId 仍最新 且 还停在同一个会话（旧数据无批次 id，按 null 精确比较）。
            if (!operationOwnsPlanPage(operationId, null, capturedConversationId)) return
            val message = buildApplyMessage(okCount, failures)
            updateStateAfterPlanApply(okCount, failures)
            _state.update { it.copy(applying = false, applyMessage = message) }
        } catch (e: Exception) {
            // 取消必须原样上抛，且先于任何归属检查。
            if (e is CancellationException) throw e
            if (!operationOwnsPlanPage(operationId, null, capturedConversationId)) return
            _state.update {
                it.copy(applying = false, applyMessage = "应用失败：${e.message ?: "未知错误"}")
            }
        }
    }

    /** 已被别处应用过：不重复执行，只把界面收敛到「已应用」状态。 */
    private fun markAlreadyApplied() {
        _state.update {
            it.copy(
                applying = false,
                applyMessage = "这批方案已经应用过了，未重复执行",
                pendingActions = emptyList(),
                pendingActionsOwnerIndex = null,
                planReviewOpen = false,
            )
        }
    }

    /** 执行计划副作用，返回（成功数, 失败列表）。 */
    private suspend fun runApplier(
        selected: List<PendingPlanAction>,
    ): Pair<Int, List<PlanApplyResult>> {
        versionedBackupRepository.createLocalVersion(reason = "before_ai_apply")
        val prefs = prefsRepository.current()
        val today = StudyClock(dayStart = prefs.dayStartTime).today()
        val results = applier.apply(selected.map { it.action }, today, prefs.dayOffPerMonth)
        return results.count { it.success } to results.filterNot { it.success }
    }

    private fun buildApplyMessage(
        okCount: Int,
        failures: List<PlanApplyResult>,
    ): String = buildString {
        append("已应用 $okCount 条修改（应用前已自动创建恢复点）")
        if (failures.isNotEmpty()) {
            append("；${failures.size} 条未应用：")
            append(failures.joinToString("；") { it.message })
        }
    }

    private fun updateStateAfterPlanApply(
        okCount: Int,
        failures: List<PlanApplyResult>,
    ) {
        _state.update { state ->
            val failedActions = failures.map { it.action }.toSet()
            val remaining = state.pendingActions.filter { it.action in failedActions }
                .map { it.copy(selected = false, problem = "应用失败：请返回对话后重新生成方案") }
            state.copy(
                pendingActions = remaining,
                pendingActionsOwnerIndex = if (remaining.isNotEmpty()) {
                    state.pendingActionsOwnerIndex
                } else {
                    null
                },
                planReviewOpen = remaining.isNotEmpty(),
                planReviewDate = state.planReviewDate.takeIf { remaining.isNotEmpty() },
                planReviewAppliedCount = state.planReviewAppliedCount + okCount,
            )
        }
    }

    /**
     * 这一批方案是否已经落库标记为「已应用」。
     *
     * 读**固定消息**上的信封（不是"会话里最后一个信封"）：
     * 后者会在多批方案共存时读到错的那一批。
     */
    private suspend fun isBatchAlreadyApplied(messageId: String): Boolean {
        val raw = runCatching { chatRepository.pendingReviewOf(messageId) }.getOrNull().orEmpty()
        return decodePendingReview(raw)?.applied == true
    }

    /**
     * 把「已应用」写回**指定批次**的落库信封。
     *
     * 这样用户锁屏 / 切走再回来时，入口按钮会以**灰态**保留（「已应用 N 项修改」），
     * 而不是凭空消失 —— 否则用户根本判断不了那次确认到底有没有生效。
     *
     * 只改 `applied` 标记，保留 `actionsJson`：条数还要用它算。
     */
    private suspend fun markReviewApplied(messageId: String) {
        val raw = runCatching { chatRepository.pendingReviewOf(messageId) }.getOrNull().orEmpty()
        val payload = decodePendingReview(raw) ?: return
        if (payload.applied) return // 已经是已应用：幂等，不重复写
        chatRepository.updatePendingReview(messageId, encodePendingReview(payload.copy(applied = true)))
    }

    // ---------------- 预览构建 ----------------

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private suspend fun buildPreviews(
        actions: List<PlanAction>,
        today: LocalDate,
    ): List<PendingPlanAction> =
        actions.map { action ->
            when (action) {
                is PlanAction.UpdateTimeSlot -> previewUpdateTimeSlot(action)
                is PlanAction.UpdateTaskTemplate -> previewUpdateTemplate(action)
                is PlanAction.InsertTaskTemplate -> previewInsertTemplate(action)
                is PlanAction.UpdateTodayTask -> previewUpdateTodayTask(action, today)
                is PlanAction.SkipTodayTask -> previewSkipTodayTask(action, today)
                is PlanAction.TakeTodayOff -> previewTakeTodayOff(action, today)
                is PlanAction.KeepTodayTasks -> previewKeepTodayTasks(action, today)
            }
        }

    private suspend fun previewUpdateTimeSlot(action: PlanAction.UpdateTimeSlot): PendingPlanAction {
        val slot = planRepository.getTimeSlot(action.slotId)
            ?: return invalidPreview(action, "调整时段（id=${action.slotId}）", "时段不存在")
        val newStart = action.startTime ?: slot.startTime
        val newEnd = action.endTime ?: slot.endTime
        val newRequired = action.requiredTaskCount
        val before = "${slot.startTime.format(timeFmt)}-${slot.endTime.format(timeFmt)}" +
            requiredSuffix(slot.requiredTaskCount)
        val after = "${newStart.format(timeFmt)}-${newEnd.format(timeFmt)}" +
            requiredSuffix(newRequired ?: slot.requiredTaskCount)
        val problem = when {
            newStart == newEnd -> "开始时间与结束时间相同"
            before == after -> "方案与当前时段相同"
            else -> null
        }
        return PendingPlanAction(
            action,
            title = "调整时段「${slot.name}」",
            before = before,
            after = after,
            scope = PlanChangeScope.LONG_TERM,
            problem = problem,
        )
    }

    /** 时段预览里「每时段至少完成几项」的后缀；0 = 全部都要完成。 */
    private fun requiredSuffix(count: Int): String =
        if (count > 0) " · 至少${count}项" else " · 全部"

    private suspend fun previewUpdateTemplate(action: PlanAction.UpdateTaskTemplate): PendingPlanAction {
        val template = planRepository.getTemplate(action.templateId)
            ?: return invalidPreview(action, "调整任务（id=${action.templateId}）", "任务模板不存在")
        val currentSlot = planRepository.getTimeSlot(template.timeSlotId)
            ?: return invalidPreview(action, "调整任务「${template.title}」", "当前时段不存在")
        val targetSlot = action.timeSlotId?.let { planRepository.getTimeSlot(it) }
        if (action.timeSlotId != null && targetSlot == null) {
            return invalidPreview(action, "调整任务「${template.title}」", "目标时段不存在")
        }
        val before = templateSummary(
            title = template.title,
            slotName = currentSlot.name,
            target = targetText(template.targetValue, template.targetType.unit, template.targetType.isQuantified),
            repeat = template.repeatRule.label,
            isKeystone = template.isKeystone,
            isEnabled = template.isEnabled,
        )
        val after = templateSummary(
            title = action.title ?: template.title,
            slotName = targetSlot?.name ?: currentSlot.name,
            target = targetText(
                action.targetValue ?: template.targetValue,
                template.targetType.unit,
                template.targetType.isQuantified,
            ),
            repeat = (action.repeatRule ?: template.repeatRule).label,
            isKeystone = action.isKeystone ?: template.isKeystone,
            isEnabled = action.isEnabled ?: template.isEnabled,
        )
        return PendingPlanAction(
            action = action,
            title = "调整任务「${template.title}」",
            before = before,
            after = after,
            scope = PlanChangeScope.LONG_TERM,
            problem = "方案与当前任务相同".takeIf { before == after },
        )
    }

    private suspend fun previewInsertTemplate(action: PlanAction.InsertTaskTemplate): PendingPlanAction {
        val subject = planRepository.getSubject(action.subjectId)
        val slot = planRepository.getTimeSlot(action.timeSlotId)
        if (subject == null || slot == null) {
            return invalidPreview(action, "新增任务「${action.title}」", "科目或时段不存在")
        }
        return PendingPlanAction(
            action = action,
            title = "新增任务「${action.title}」",
            before = "当前计划中无此任务",
            after = "${subject.name} · ${slot.name} · " +
                "${targetText(action.targetValue, action.targetType.unit, action.targetType.isQuantified)} · " +
                action.repeatRule.label,
            scope = PlanChangeScope.LONG_TERM,
        )
    }

    private suspend fun previewUpdateTodayTask(
        action: PlanAction.UpdateTodayTask,
        today: LocalDate,
    ): PendingPlanAction {
        val task = taskRepository.getTask(action.taskId)
            ?: return invalidPreview(action, "调整今日任务（id=${action.taskId}）", "任务不存在", PlanChangeScope.TODAY)
        val targetSlot = action.timeSlotId?.let { planRepository.getTimeSlot(it) }
        val subject = planRepository.getSubject(task.subjectId)
        val before = "${task.slotName} · ${targetText(task.targetValue, task.targetType.unit, task.targetType.isQuantified)}"
        val after = "${targetSlot?.name ?: task.slotName} · " + targetText(
            action.targetValue ?: task.targetValue,
            task.targetType.unit,
            task.targetType.isQuantified,
        )
        val problem = when {
            task.date != today -> "该任务不属于今天（${task.date}）"
            task.status != TaskStatus.PENDING -> "只能调整尚未打卡的任务（当前：${task.status.label}）"
            action.targetValue != null && !task.targetType.isQuantified -> "完成型任务不能调整目标量"
            action.timeSlotId != null && targetSlot == null -> "目标时段不存在"
            targetSlot != null && subject?.planId != targetSlot.planId -> "目标时段不属于当前计划"
            before == after -> "方案与当前任务相同"
            else -> null
        }
        return PendingPlanAction(
            action = action,
            title = "临时调整「${task.title}」",
            before = before,
            after = after,
            scope = PlanChangeScope.TODAY,
            problem = problem,
        )
    }

    private suspend fun previewSkipTodayTask(
        action: PlanAction.SkipTodayTask,
        today: LocalDate,
    ): PendingPlanAction {
        val task = taskRepository.getTask(action.taskId)
            ?: return invalidPreview(action, "仅今日跳过任务（id=${action.taskId}）", "任务不存在", PlanChangeScope.TODAY)
        val before = "${task.slotName} · ${task.status.label} · " +
            targetText(task.targetValue, task.targetType.unit, task.targetType.isQuantified)
        val problem = when {
            task.date != today -> "该任务不属于今天（${task.date}）"
            task.status != TaskStatus.PENDING -> "只能跳过尚未打卡的任务（当前：${task.status.label}）"
            else -> null
        }
        return PendingPlanAction(
            action = action,
            title = "仅今日跳过「${task.title}」",
            before = before,
            after = "仅今日跳过 · 不计入完成率和漏卡扣分 · 明天照常安排",
            scope = PlanChangeScope.TODAY,
            problem = problem,
        )
    }

    private suspend fun previewTakeTodayOff(
        action: PlanAction.TakeTodayOff,
        today: LocalDate,
    ): PendingPlanAction {
        val tasks = taskRepository.getTasksOfDay(today)
        val record = taskRepository.getDayRecord(today)
        val affected = tasks.count { it.status == TaskStatus.PENDING || it.status == TaskStatus.MISSED }
        val prefs = prefsRepository.current()
        val used = taskRepository.getDayRecordsBetween(
            today.withDayOfMonth(1),
            today.withDayOfMonth(today.lengthOfMonth()),
        ).count { it.isDayOff }
        val problem = when {
            record?.isSettled == true -> "今天已经结算"
            record?.isDayOff == true -> "今天已经处于请假状态"
            affected == 0 -> "今天没有可请假的待做任务"
            used >= prefs.dayOffPerMonth -> "本月请假额度已用完（${prefs.dayOffPerMonth} 天）"
            else -> null
        }
        return PendingPlanAction(
            action = action,
            title = "今日整天请假",
            before = "今日 ${tasks.count { it.status != TaskStatus.SKIPPED }} 项有效任务，其中 $affected 项可请假",
            after = "今日整天请假 · 待做任务全部跳过 · 不计完成率且不打断连续记录 · " +
                "本月剩余 ${maxOf(0, prefs.dayOffPerMonth - used - 1)} 天",
            scope = PlanChangeScope.TODAY,
            problem = problem,
        )
    }

    private suspend fun previewKeepTodayTasks(
        action: PlanAction.KeepTodayTasks,
        today: LocalDate,
    ): PendingPlanAction {
        val tasks = taskRepository.getTasksOfDay(today)
        val record = taskRepository.getDayRecord(today)
        val byId = tasks.associateBy { it.id }
        val keepTasks = action.keepTaskIds.mapNotNull(byId::get)
        val missing = action.keepTaskIds.filterNot(byId::containsKey)
        val skippedCount = tasks.count { it.id !in action.keepTaskIds && it.status == TaskStatus.PENDING }
        val problem = when {
            record?.isSettled == true -> "今天已经结算"
            record?.isDayOff == true -> "今天已整日请假"
            missing.isNotEmpty() -> "要保留的任务不存在或不属于今天：${missing.joinToString()}"
            keepTasks.any { it.status == TaskStatus.SKIPPED || it.status == TaskStatus.MISSED } ->
                "要保留的任务中包含已跳过或已漏卡任务"
            skippedCount == 0 -> "除保留任务外，没有其他今日待做任务"
            else -> null
        }
        return PendingPlanAction(
            action = action,
            title = "今日仅保留 ${keepTasks.size} 项任务",
            before = "今日 ${tasks.count { it.status != TaskStatus.SKIPPED }} 项有效任务",
            after = "保留：${keepTasks.joinToString("、") { it.title }}；其余 $skippedCount 项仅今日跳过",
            scope = PlanChangeScope.TODAY,
            problem = problem,
        )
    }

    // ---------------- 英语积累预览构建 ----------------

    /** 返回 null 表示本轮没有英语积累变更，保留界面上原有的待确认项。 */
    private suspend fun buildEnglishPreviews(
        actions: List<EnglishEntryAction>,
    ): List<PendingEnglishAction>? {
        if (actions.isEmpty()) return null
        return actions.take(MAX_ENGLISH_ACTIONS).map { action ->
            when (action) {
                is EnglishEntryAction.Add -> previewAddEnglish(action)
                is EnglishEntryAction.Update -> previewUpdateEnglish(action)
                is EnglishEntryAction.Delete -> previewDeleteEnglish(action)
            }
        }
    }

    private fun previewAddEnglish(action: EnglishEntryAction.Add): PendingEnglishAction {
        val content = action.content.trim()
        val meaning = action.meaning.trim()
        val problem = when {
            content.isEmpty() -> "英文内容为空"
            meaning.isEmpty() -> "释义为空"
            content.length > EnglishEntryRepository.MAX_CONTENT_LENGTH ->
                "英文内容超过 ${EnglishEntryRepository.MAX_CONTENT_LENGTH} 个字符"
            meaning.length > EnglishEntryRepository.MAX_MEANING_LENGTH ->
                "释义超过 ${EnglishEntryRepository.MAX_MEANING_LENGTH} 个字符"
            else -> null
        }
        return PendingEnglishAction(
            action = action,
            title = "新增${englishTypeLabel(action.type)}「${content.take(30)}」",
            before = "当前英语积累中没有这一条",
            after = "${englishTypeLabel(action.type)} · $content —— $meaning",
            problem = problem,
        )
    }

    private suspend fun previewUpdateEnglish(action: EnglishEntryAction.Update): PendingEnglishAction {
        val existing = englishEntryRepository.get(action.id)
            ?: return invalidEnglishPreview(action, "修改英语积累（id=${action.id}）", "要修改的条目不存在")
        val newType = action.type ?: existing.type
        val newContent = action.content?.trim() ?: existing.content
        val newMeaning = action.meaning?.trim() ?: existing.meaning
        val before = "${englishTypeLabel(existing.type)} · ${existing.content} —— ${existing.meaning}"
        val after = "${englishTypeLabel(newType)} · $newContent —— $newMeaning"
        val problem = when {
            newContent.isBlank() -> "英文内容为空"
            newMeaning.isBlank() -> "释义为空"
            newContent.length > EnglishEntryRepository.MAX_CONTENT_LENGTH ->
                "英文内容超过 ${EnglishEntryRepository.MAX_CONTENT_LENGTH} 个字符"
            newMeaning.length > EnglishEntryRepository.MAX_MEANING_LENGTH ->
                "释义超过 ${EnglishEntryRepository.MAX_MEANING_LENGTH} 个字符"
            before == after -> "内容与当前条目相同"
            else -> null
        }
        return PendingEnglishAction(
            action = action,
            title = "修改英语积累「${existing.content.take(30)}」",
            before = before,
            after = after,
            problem = problem,
        )
    }

    private suspend fun previewDeleteEnglish(action: EnglishEntryAction.Delete): PendingEnglishAction {
        val existing = englishEntryRepository.get(action.id)
            ?: return invalidEnglishPreview(action, "删除英语积累（id=${action.id}）", "要删除的条目不存在")
        return PendingEnglishAction(
            action = action,
            title = "删除英语积累「${existing.content.take(30)}」",
            before = "${englishTypeLabel(existing.type)} · ${existing.content} —— ${existing.meaning}",
            after = "删除后本机与之后的新备份中都不再保留",
        )
    }

    private fun invalidEnglishPreview(
        action: EnglishEntryAction,
        title: String,
        problem: String,
    ) = PendingEnglishAction(
        action = action,
        title = title,
        before = "无法读取当前内容",
        after = "不会应用",
        problem = problem,
    )

    private fun englishTypeLabel(type: EnglishEntryType): String = when (type) {
        EnglishEntryType.WORD -> "单词"
        EnglishEntryType.PHRASE -> "短语"
        EnglishEntryType.SENTENCE -> "句子"
    }

    private fun invalidPreview(
        action: PlanAction,
        title: String,
        problem: String,
        scope: PlanChangeScope = PlanChangeScope.LONG_TERM,
    ) = PendingPlanAction(
        action = action,
        title = title,
        before = "无法读取当前内容",
        after = "不会应用",
        scope = scope,
        problem = problem,
    )

    private fun targetText(value: Int, unit: String, quantified: Boolean): String =
        if (quantified) "$value$unit" else "完成即可"

    private fun templateSummary(
        title: String,
        slotName: String,
        target: String,
        repeat: String,
        isKeystone: Boolean,
        isEnabled: Boolean,
    ): String = buildList {
        add(title)
        add(slotName)
        add(target)
        add(repeat)
        if (isKeystone) add("关键任务")
        add(if (isEnabled) "启用" else "停用")
    }.joinToString(" · ")

    private companion object {
        const val MAX_PENDING_PHOTOS = 9
        const val MAX_ENGLISH_ACTIONS = 20
        const val DEFAULT_VISION_PROMPT =
            "请先准确识别图片中的题目，再给出细致解答。数学公式使用 LaTeX，并写出完整推导过程。"
        const val VISION_TRANSCRIBE_PROMPT =
            "你是题目转写器。请把图片中的题目完整转写为 Markdown：中文保持原文；数学公式使用 LaTeX 并以 $$...$$ 包裹，块级公式的起止 $$ 各占一行。不要解题，不要输出额外解释。"
    }
}

internal fun requiredAssistantContext(
    prompt: String,
    selected: Set<AssistantContextKind>,
    /**
     * 本轮是否处于「计划变更流程」中（见 [inPlanChangeFlow]）。
     *
     * 为什么要单独判：`inferAssistantContext` 是**关键词猜意图**，而改计划的说法经常不含
     * 「计划」二字（「以后都改」「政治都放到晚上」「英语改成题目专项」）。一旦漏判，
     * 模型就拿不到任何带 `[id=...]` 的计划数据，于是只能拒绝生成（表现为「确认页起不来」）
     * 或**编造 id**（点「接受」时报「任务模板不存在（id=…）」）—— 这正是
     * 「第一轮能改、第二轮改不了」的根因。
     *
     * 判据放宽是有意的：多注入一次计划数据只多花几百 token，
     * 而漏判的代价是整轮改计划直接失败。
     */
    inPlanChangeFlow: Boolean = false,
): Set<AssistantContextKind> {
    val inferred = inferAssistantContext(prompt)
    val todayMarkers = listOf(
        "今日请假", "今天请假", "今日不做", "今天不做", "只保留", "仅保留",
        "跳过今日", "跳过今天", "取消今日任务", "取消今天任务", "删除今日任务", "删掉今天",
    )
    val required = if (todayMarkers.any(prompt::contains)) {
        setOf(AssistantContextKind.TODAY)
    } else {
        emptySet()
    }
    // 「正在改计划」⇒ 无条件带上计划数据，不依赖关键词是否命中
    val flowRequired = if (inPlanChangeFlow) setOf(AssistantContextKind.PLAN) else emptySet()
    return selected + inferred + required + flowRequired
}

/** 助手给出待确认方案时惯用的措辞；命中即认为下一轮仍在计划变更流程里。 */
private val PLAN_FLOW_MARKERS = listOf(
    "待确认方案", "请确认", "确认后生效", "计划变更", "调整方案",
)

/**
 * 本轮是否处于「计划变更流程」中 —— 也就是：用户这句话多半是在回应上一轮的方案。
 *
 * 两个判据：① 手上还有待确认动作；② 上一条助手消息在谈方案。
 * 只要其一成立就把计划数据带上，因为**模型生成任何计划动作都必须引用真实 id**。
 *
 * ⚠️ 这里宁可放宽：误判的代价只是多带一段计划数据（几百 token），
 * 漏判的代价是整轮改计划失败、还可能让模型编造 id。
 */
internal fun inPlanChangeFlow(state: AssistantUiState): Boolean {
    if (state.pendingActions.isNotEmpty()) return true
    val lastAssistant = state.messages.lastOrNull { it.role == "assistant" } ?: return false
    val text = lastAssistant.displayContent ?: lastAssistant.content
    return PLAN_FLOW_MARKERS.any(text::contains)
}

/**
 * 模型声明「需要计划数据但上下文里没有」时使用的约定标记。
 *
 * 提示词里要求模型缺数据时**只回这一行**（见 `AssistantModelClient.SYSTEM_PROMPT`），
 * 客户端收到后会自动补上计划数据重试一次，用户无感。
 */
internal const val NEED_PLAN_CONTEXT_MARKER = "[[NEED_PLAN_CONTEXT]]"

/**
 * 约定标记没能被消化掉时替换给用户的提示。
 *
 * 出现它说明补注入也没拿到计划数据（例如当前没有生效中的计划），
 * 此时必须告诉用户**下一步做什么**，而不是只留一句「我拿不到数据」。
 */
internal const val PLAN_CONTEXT_UNAVAILABLE_HINT =
    "（没能读到你的计划数据，暂时无法生成修改方案。请在输入框上方确认「当前计划」已开启，或检查是否还有进行中的计划。）"

/**
 * 模型这一轮是否在声明「缺计划数据 / 拿不到 id」。
 *
 * 两道判据并用：
 * 1. **约定标记** —— 模型遵守提示词时最可靠；
 * 2. **措辞兜底** —— 模型未必遵守约定，于是再看它是否「既没产出任何计划动作，
 *    又在正文里同时提到『没有拿到 / 缺少』+『计划 / 模板 / 时段』+『id』」。
 *
 * 误判的代价很小（多带一段计划数据、多重试一次请求），
 * 而漏判的代价是模型编造 id、要等用户点「接受」才发现失败 —— 所以这里偏向宽松。
 */
internal fun replyNeedsPlanContext(reply: ParsedAssistantReply): Boolean {
    if (NEED_PLAN_CONTEXT_MARKER in reply.reply) return true
    // 已经产出了计划动作 ⇒ 说明它手上有数据，不是「缺数据」
    if (reply.actions.isNotEmpty()) return false
    val text = reply.reply
    // 判据以 **id** 为准，而不是「计划/模板/时段/任务」这些名词：
    // 实测模型具体怎么说很随意，有时只说「我这一轮拿到的上下文里一个真实 id 都没有」，
    // 一个业务名词都不带 —— 那样会被名词条件挡掉。
    // 而「缺 id」正是这个问题的核心症状，提到它才是真的在要计划数据。
    //
    // 反面：不要求名词也不放宽到「提到 id 就算」——因为误触发的代价不只是多一次请求，
    // 还会**丢弃第一次已经生成的回答**（重试会重置正文），用户会看到回答闪一下重来。
    val complainsMissing = listOf(
        "没有拿到", "没拿到", "拿不到", "缺少", "没有提供", "没有找到",
        "无法生成", "没有生成", "都没有", "需要带",
    ).any(text::contains)
    return complainsMissing && ID_WORD.containsMatchIn(text)
}

/**
 * 独立的 `id` 字样。
 *
 * ⚠️ 不能直接用 `contains("id")` —— `provide`、`idea`、`consider` 里都含 "id"，
 * 会把普通英文回答误判成「缺 id」，进而白触发一次补注入重试。
 * 中文文本里 id 两侧通常是汉字（属非单词字符），所以 `\b` 依然能正确匹配。
 */
private val ID_WORD = Regex("""\bid\b""", RegexOption.IGNORE_CASE)

internal fun inferAssistantContext(prompt: String): Set<AssistantContextKind> {
    val compact = prompt.lowercase().replace(Regex("\\s+"), "")
    val inferred = mutableSetOf<AssistantContextKind>()

    val planMarkers = listOf(
        "当前计划", "我的计划", "学习计划", "计划安排", "计划内容", "计划是什么",
        "调整计划", "修改计划", "科目安排", "时段安排", "任务模板", "上午的安排",
        "下午的安排", "晚上的安排", "早上的安排",
    )
    val todayMarkers = listOf(
        "今日任务", "今天任务", "今天的任务", "今天要做", "今日要做", "今天安排",
        "今日安排", "今天进度", "今日进度", "今天完成", "今日完成", "今天还有",
        "今日还有", "今日待办", "今天待办",
    )
    val statsMarkers = listOf(
        "最近7天", "近7天", "最近七天", "近七天", "近期统计", "学习统计", "完成率",
        "薄弱科目", "专注时长", "学习效率", "各科投入", "学习投入", "最近复盘",
    )
    val englishMarkers = listOf(
        "英语积累", "我的英语积累", "积累的英语", "英语小测", "英语测验", "英语复习",
        "短语积累", "句子积累", "好词好句", "记入英语", "加入英语", "存入英语",
        "加入积累", "记到英语", "修改英语积累", "删掉英语积累",
    )
    val wordsMarkers = listOf(
        "墨墨", "今日单词", "今天的单词", "今天背的单词", "背单词列表",
    )

    val refersToToday = listOf("今天", "今日", "当天").any(compact::contains)
    val asksAboutTasks = listOf("任务", "安排", "进度", "待办", "做什么", "完成").any(compact::contains)
    val refersToRecentPeriod = listOf("最近", "近期", "近一周", "过去一周").any(compact::contains)
    val asksAboutPerformance = listOf(
        "学习", "表现", "状态", "效率", "投入", "完成", "复盘", "怎么样", "如何",
    ).any(compact::contains)

    if (planMarkers.any(compact::contains) || "计划" in compact) inferred += AssistantContextKind.PLAN
    if (todayMarkers.any(compact::contains) || (refersToToday && asksAboutTasks)) {
        inferred += AssistantContextKind.TODAY
    }
    if (statsMarkers.any(compact::contains) || (refersToRecentPeriod && asksAboutPerformance)) {
        inferred += AssistantContextKind.STATS
    }
    if (englishMarkers.any(compact::contains)) inferred += AssistantContextKind.ENGLISH
    if (wordsMarkers.any(compact::contains)) inferred += AssistantContextKind.WORDS
    return inferred
}
