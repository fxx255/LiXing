package com.example.lixing.ui.screen.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lixing.assistant.AssistantGenerationGuard
import com.example.lixing.data.assistant.AiCredentialStore
import com.example.lixing.data.assistant.AssistantContextBuilder
import com.example.lixing.data.assistant.AssistantImagePrep
import com.example.lixing.data.assistant.AssistantModelClient
import com.example.lixing.data.assistant.AssistantModelException
import com.example.lixing.data.assistant.ParsedAssistantReply
import com.example.lixing.data.assistant.AssistantStreamEvent
import com.example.lixing.data.assistant.LocalTextRecognizer
import com.example.lixing.data.assistant.OcrProgress
import com.example.lixing.data.assistant.shouldUseWebSearch
import com.example.lixing.data.backup.VersionedBackupRepository
import com.example.lixing.data.local.entity.AssistantConversationEntity
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.repository.AssistantChatRepository
import com.example.lixing.data.repository.EnglishEntryRepository
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.domain.assistant.AssistantContextKind
import com.example.lixing.domain.assistant.AssistantMessage
import com.example.lixing.domain.assistant.EnglishEntryAction
import com.example.lixing.domain.assistant.PlanAction
import com.example.lixing.domain.assistant.PlanChangeApplier
import com.example.lixing.domain.english.EnglishEntryType
import com.example.lixing.domain.model.TaskStatus
import com.example.lixing.domain.time.StudyClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    val planReviewOpen: Boolean = false,
    val planReviewDate: LocalDate? = null,
    val applying: Boolean = false,
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
    /** Text-only models use local OCR; vision-enabled models receive the photos directly. */
    val ocrBusy: Boolean = false,
    val ocrProgress: OcrProgress? = null,
    val ocrPreview: OcrPreview? = null,
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

data class OcrPreview(
    val prompt: String,
    val photoPaths: List<String>,
    val markdown: String,
    val warnings: List<String>,
    val hasBlockingErrors: Boolean,
)

private const val REASONING_OMITTED_PREFIX = "…较早的思考内容已省略…\n"
internal const val MAX_REASONING_DISPLAY_CHARS = 12_000

/** Keeps streaming UI updates bounded while retaining the most recent reasoning. */
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
 * AI 学习助手页的状态机。
 *
 * 会话持久化在本地数据库：首条消息发出时创建会话，之后每条消息追加；
 * 历史会话可回看续聊，也可整条删除。计划建议仍只存内存、逐条确认后应用。
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
    private val textRecognizer: LocalTextRecognizer,
    private val aiCredentialStore: AiCredentialStore,
    private val generationGuard: AssistantGenerationGuard,
) : ViewModel() {

    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()
    private var ocrJob: Job? = null
    private var photoRouteJob: Job? = null

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
    }

    fun updateInput(value: String) = _state.update { it.copy(input = value) }

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

    /** 打开一条历史会话：读取消息并续聊。计划建议不跨会话保留。 */
    fun openConversation(id: String) {
        viewModelScope.launch {
            val messages = chatRepository.messages(id)
            _state.update {
                it.copy(
                    historyOpen = false,
                    currentConversationId = id,
                    messages = messages.map { m ->
                        AssistantMessage(
                            role = m.role,
                            content = m.content,
                            imagePaths = chatRepository.decodeImagePaths(m.imagePaths),
                            displayContent = m.displayContent,
                        )
                    },
                    pendingActions = emptyList(),
                    pendingActionsOwnerIndex = null,
                    planReviewOpen = false,
                    planReviewDate = null,
                    pendingEnglishActions = emptyList(),
                    pendingEnglishOwnerIndex = null,
                    englishReviewOpen = false,
                    lastContextNote = null,
                    applyMessage = null,
                )
            }
        }
    }

    /** 开始全新对话：清空界面；旧会话仍保留在历史里。 */
    fun startNewConversation() = _state.update {
        it.copy(
            messages = emptyList(),
            currentConversationId = null,
            pendingActions = emptyList(),
            pendingActionsOwnerIndex = null,
            planReviewOpen = false,
            planReviewDate = null,
            pendingEnglishActions = emptyList(),
            pendingEnglishOwnerIndex = null,
            englishReviewOpen = false,
            lastContextNote = null,
            applyMessage = null,
            historyOpen = false,
        )
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            chatRepository.messages(id)
                .flatMap { chatRepository.decodeImagePaths(it.imagePaths) }
                .distinct()
                .forEach { File(it).delete() }
            chatRepository.deleteConversation(id)
            if (_state.value.currentConversationId == id) {
                startNewConversation()
            }
        }
    }

    // ---------------- 对话 ----------------

    fun onPhotoTaken(path: String) = _state.update { state ->
        if (state.pendingPhotoPaths.size >= MAX_PENDING_PHOTOS) {
            File(path).delete()
            state.copy(error = "一次最多发送 $MAX_PENDING_PHOTOS 张照片")
        } else {
            state.copy(pendingPhotoPaths = state.pendingPhotoPaths + path)
        }
    }

    fun removePendingPhoto(path: String) = _state.update { state ->
        File(path).delete()
        state.copy(pendingPhotoPaths = state.pendingPhotoPaths - path)
    }

    fun send() {
        val current = _state.value
        val text = current.input.trim()
        val photoPaths = current.pendingPhotoPaths.filter { File(it).isFile }
        if (current.ocrBusy) return
        if (photoPaths.isNotEmpty()) {
            performDirectPhotoSend(text, photoPaths)
            return
        }
        if ((text.isEmpty() && photoPaths.isEmpty()) || _state.value.busy) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    input = "",
                    busy = true,
                    error = null,
                    activeReasoning = "",
                    activeAnswerStarted = false,
                    reasoningExpanded = true,
                )
            }
            // 生成期间启动前台服务保活，防止熄屏/切后台后进程被冻结或查杀导致输出丢失。
            generationGuard.begin()
            try {
                val prefs = prefsRepository.current()
                val outgoing = text
                val webSearchEnabled = shouldUseWebSearch(outgoing, _state.value.forceWebSearch)
                val displayContent: String? = null
                val userMessage = AssistantMessage("user", outgoing, photoPaths, displayContent)
                _state.update {
                    it.copy(
                        pendingPhotoPaths = emptyList(),
                        messages = it.messages + userMessage,
                    )
                }
                // 首条消息才创建会话，避免空会话进历史。
                val conversationTitle = outgoing
                val conversationId = _state.value.currentConversationId
                    ?: chatRepository.startConversation(conversationTitle).also { id ->
                        _state.update { it.copy(currentConversationId = id) }
                    }
                chatRepository.appendMessage(conversationId, "user", outgoing, photoPaths, displayContent)
                val today = StudyClock(dayStart = prefs.dayStartTime).today()
                // 手动勾选的始终附带；其余由模型按问题预判补充。
                val manual = _state.value.contextKinds
                val auto = modelClient.chooseContext(outgoing)
                val kinds = requiredAssistantContext(outgoing, manual + auto)
                val context = contextBuilder.build(kinds, today)
                val contextNote = if (kinds.isEmpty()) {
                    "本次未附带本机数据"
                } else {
                    val names = kinds.joinToString("、") { it.label }
                    if ((auto - manual).isNotEmpty()) "$names（含模型自动选择）" else names
                }
                // 只带最近 10 轮对话，控制费用与隐私面。
                runChatTurn(
                    conversationId = conversationId,
                    context = context,
                    imageBase64s = emptyList(),
                    webSearchEnabled = webSearchEnabled,
                    forceWebSearch = _state.value.forceWebSearch,
                    today = today,
                ).let { lastReply ->
                    _state.update { state ->
                        state.copy(
                            busy = false,
                            activeReasoning = "",
                            activeAnswerStarted = false,
                            lastContextNote = contextNote,
                            error = lastReply?.warnings?.joinToString("；")?.takeIf { it.isNotEmpty() },
                        )
                    }
                }
            } catch (e: AssistantModelException) {
                _state.update { it.copy(busy = false, activeReasoning = "", activeAnswerStarted = false, error = e.message) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        activeReasoning = "",
                        activeAnswerStarted = false,
                        error = "请求失败：${e.message ?: "未知错误"}",
                    )
                }
            } finally {
                generationGuard.end()
            }
        }
    }

    /** 发送照片后直接进入回答流：多模态直送 / 静默转写 / 静默本地 OCR，不展示中间预览。 */
    private fun performDirectPhotoSend(prompt: String, photoPaths: List<String>) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            // 照片链路的转写/OCR 阶段也可能较久，与后续回答生成共用引用计数保活。
            generationGuard.begin()
            try {
                val visionEnabled = modelClient.isVisionEnabled()
                val recognizerId = if (visionEnabled) null else aiCredentialStore.questionVisionProfileId()
                val imageBase64s: List<String>
                val outgoing: String
                when {
                    visionEnabled -> {
                        imageBase64s = encodePhotos(photoPaths)
                        outgoing = prompt.ifBlank { DEFAULT_VISION_PROMPT }
                    }
                    recognizerId != null -> {
                        val transcription = runCatching {
                            modelClient.completeWithProfile(
                                profileId = recognizerId,
                                systemPrompt = VISION_TRANSCRIBE_PROMPT,
                                userText = "请完整转写图片中的全部题目内容。",
                                imageBase64s = encodePhotos(photoPaths),
                            )
                        }.getOrNull()
                            ?: localOcrText(photoPaths)
                            ?: error("多模态转写与本地 OCR 均未识别出内容")
                        imageBase64s = emptyList()
                        outgoing = buildOutgoingText(prompt, transcription)
                    }
                    else -> {
                        val ocrText = localOcrText(photoPaths)
                            ?: error("未能从照片中识别出文字，请调整光线或手动输入")
                        imageBase64s = emptyList()
                        outgoing = buildOutgoingText(prompt, ocrText)
                    }
                }
                performPhotoSend(outgoing, photoPaths, prompt, imageBase64s)
            } catch (e: CancellationException) {
                _state.update { it.copy(busy = false) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.message ?: "图片处理失败") }
            } finally {
                generationGuard.end()
            }
        }
    }

    private suspend fun encodePhotos(photoPaths: List<String>): List<String> {
        val encoded = withContext(Dispatchers.Default) {
            photoPaths.mapNotNull(AssistantImagePrep::encodeForVision)
        }
        check(encoded.size == photoPaths.size) { "有图片读取失败，请重新拍摄或选择" }
        return encoded
    }

    private suspend fun localOcrText(photoPaths: List<String>): String? =
        runCatching { textRecognizer.recognizeDocument(photoPaths) }
            .getOrNull()?.markdown?.takeIf { it.isNotBlank() }

    private fun buildOutgoingText(prompt: String, recognized: String): String = buildString {
        if (prompt.isNotBlank()) append(prompt).append("\n\n")
        append(recognized)
    }.trim()

    private fun startOcrPreview(prompt: String, photoPaths: List<String>, notice: String? = null) {
        ocrJob?.cancel()
        ocrJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    ocrBusy = true,
                    ocrProgress = OcrProgress(0, photoPaths.size, "正在准备 OCR", 0f),
                    ocrPreview = null,
                    error = notice,
                )
            }
            try {
                val result = textRecognizer.recognizeDocument(photoPaths) { progress ->
                    _state.update { it.copy(ocrProgress = progress) }
                }
                _state.update {
                    it.copy(
                        ocrBusy = false,
                        ocrProgress = null,
                        ocrPreview = OcrPreview(
                            prompt = prompt,
                            photoPaths = photoPaths,
                            markdown = result.markdown,
                            warnings = result.warnings,
                            hasBlockingErrors = result.hasBlockingErrors,
                        ),
                    )
                }
            } catch (_: CancellationException) {
                _state.update { it.copy(ocrBusy = false, ocrProgress = null) }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        ocrBusy = false,
                        ocrProgress = null,
                        error = "OCR 失败：${error.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    fun updateOcrPreview(markdown: String) = _state.update { state ->
        state.ocrPreview?.let { preview ->
            state.copy(
                ocrPreview = preview.copy(
                    markdown = markdown,
                    hasBlockingErrors = markdown.contains("公式识别失败"),
                ),
            )
        } ?: state
    }

    fun cancelOcrPreview() = _state.update { it.copy(ocrPreview = null) }

    fun cancelOcr() {
        photoRouteJob?.cancel()
        ocrJob?.cancel()
        _state.update { it.copy(ocrBusy = false, ocrProgress = null) }
    }

    fun retryOcrPreview() {
        val preview = _state.value.ocrPreview ?: return
        startOcrPreview(preview.prompt, preview.photoPaths)
    }

    /**
     * 一轮问答 = 首次请求 + 输出撞长度上限（finish_reason=length）时的自适应续写。
     *
     * 没有固定轮数上限：只要模型每轮仍在产出「实打实的长内容」（整段被截断 =
     * 把 max_tokens 用满了），就继续发「继续」接着写；同时有三道刹车防失控：
     * 1. 用户设置的保护上限（默认 4 轮，可在设置页调 0~8，0 = 关闭自动续写）；
     * 2. 单段过短（< 200 字符）说明模型已收尾或在原地打转 → 停止；
     * 3. 累计输出超过总熔断长度 → 收尾并明示。
     * 返回最后一段回复（含 warnings，供调用方展示）。
     */
    private suspend fun runChatTurn(
        conversationId: String,
        context: String,
        imageBase64s: List<String>,
        webSearchEnabled: Boolean,
        forceWebSearch: Boolean = false,
        today: LocalDate,
    ): ParsedAssistantReply? {
        val maxContinuations = prefsRepository.current().assistantAutoContinue.coerceAtLeast(0)
        val minMeaningfulChars = 200
        val totalCharFuse = 240_000 // 约 12 万汉字量级的绝对熔断，正常永远到不了
        var images = imageBase64s
        var continuation = 0
        var totalChars = 0
        var lastReply: ParsedAssistantReply? = null
        while (true) {
            val history = _state.value.messages.takeLast(10)
            val reply = modelClient.chatStreaming(
                history, context, images, webSearchEnabled, forceWebSearch,
            ) { event ->
                when (event) {
                    is AssistantStreamEvent.ReasoningDelta -> _state.update {
                        it.copy(activeReasoning = appendReasoningForDisplay(it.activeReasoning, event.text))
                    }
                    is AssistantStreamEvent.AnswerDelta -> _state.update { it.copy(activeAnswerStarted = true) }
                }
            }
            lastReply = reply
            images = emptyList() // 续写轮不再带图：首轮图片内容已在对话历史里
            totalChars += reply.reply.length

            val hitLimit = reply.truncated
            val producedSomething = reply.reply.length >= minMeaningfulChars
            val withinUserCap = continuation < maxContinuations
            val withinFuse = totalChars < totalCharFuse
            // 继续的条件：确实被截断 + 本轮产出够长（排除原地打转）+ 未触发任何刹车
            val shouldContinue = hitLimit && producedSomething && withinUserCap && withinFuse
            // 继续写时原样入会话；收尾（不再续写且被截断）时才做修饰并明示
            val text = if (hitLimit && !shouldContinue) polishTruncatedTail(reply.reply) else reply.reply
            appendAssistantTurn(conversationId, text, reply, today)
            if (!shouldContinue) {
                when {
                    !hitLimit -> Unit // 自然写完，无需提示
                    !producedSomething ->
                        _state.update {
                            it.copy(error = "这一段几乎没有新内容，已停止自动续写；可发送「继续」重试")
                        }
                    continuation >= maxContinuations && maxContinuations > 0 ->
                        _state.update {
                            it.copy(error = "回答较长，已达自动续写保护上限（设置页可调整），发送「继续」可接着生成")
                        }
                    !withinFuse ->
                        _state.update {
                            it.copy(error = "回答非常长，已到自动续写的总长度保险丝，发送「继续」可接着生成")
                        }
                    else -> Unit
                }
                return lastReply
            }
            continuation++
            val continueText = "继续，从刚才中断的地方接着输出，不要重复已有内容。"
            chatRepository.appendMessage(conversationId, "user", continueText, emptyList(), null)
            _state.update { it.copy(messages = it.messages + AssistantMessage("user", continueText)) }
        }
    }

    /** 把一段回复写入会话与消息列表，并挂上计划/英语变更的待确认项。 */
    private suspend fun appendAssistantTurn(
        conversationId: String,
        text: String,
        reply: ParsedAssistantReply,
        today: LocalDate,
    ) {
        chatRepository.appendMessage(conversationId, "assistant", text)
        val previews = reply.actions.takeIf { it.isNotEmpty() }?.let { buildPreviews(it, today) }
        val englishPreviews = buildEnglishPreviews(reply.englishActions)
        _state.update { state ->
            // 待确认项只挂到产生它的那条回复下面，不再自动跳到确认页。
            val updatedMessages = state.messages + AssistantMessage("assistant", text)
            val ownerIndex = updatedMessages.lastIndex
            state.copy(
                messages = updatedMessages,
                pendingActions = previews ?: state.pendingActions,
                pendingActionsOwnerIndex = if (previews != null) ownerIndex else state.pendingActionsOwnerIndex,
                planReviewDate = if (previews != null) today else state.planReviewDate,
                pendingEnglishActions = englishPreviews ?: state.pendingEnglishActions,
                pendingEnglishOwnerIndex = if (englishPreviews != null) ownerIndex else state.pendingEnglishOwnerIndex,
            )
        }
    }

    /** 截断收尾：去掉悬空的加粗标记、补齐未闭合的公式块，并附加截断说明。 */
    private fun polishTruncatedTail(text: String): String {
        var t = text.trimEnd()
        if (t.endsWith("**")) t = t.removeSuffix("**").trimEnd()
        // $$ 出现奇数次 → 有未闭合的公式块，补一个闭合
        if (t.split("$$").size % 2 == 0) t += "\n$$"
        return "$t\n\n——（回答达到单次输出上限被截断）"
    }

    private fun performPhotoSend(
        outgoing: String,
        photoPaths: List<String>,
        displayContent: String?,
        imageBase64s: List<String>,
    ) {
        if (outgoing.isBlank()) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    ocrPreview = null,
                    busy = true,
                    error = null,
                    activeReasoning = "",
                    activeAnswerStarted = false,
                    reasoningExpanded = true,
                )
            }
            generationGuard.begin()
            try {
                val prefs = prefsRepository.current()
                val webSearchEnabled = shouldUseWebSearch(outgoing, _state.value.forceWebSearch)
                val userMessage = AssistantMessage("user", outgoing, photoPaths, displayContent)
                _state.update {
                    it.copy(
                        input = "",
                        pendingPhotoPaths = emptyList(),
                        messages = it.messages + userMessage,
                    )
                }
                val conversationTitle = displayContent?.takeIf { it.isNotBlank() } ?: "图片题目"
                val conversationId = _state.value.currentConversationId
                    ?: chatRepository.startConversation(conversationTitle).also { id ->
                        _state.update { it.copy(currentConversationId = id) }
                    }
                chatRepository.appendMessage(conversationId, "user", outgoing, photoPaths, displayContent)
                val today = StudyClock(dayStart = prefs.dayStartTime).today()
                val manual = _state.value.contextKinds
                val auto = modelClient.chooseContext(outgoing)
                val kinds = requiredAssistantContext(outgoing, manual + auto)
                val modelContext = contextBuilder.build(kinds, today)
                val contextNote = if (kinds.isEmpty()) "本次未附带本机数据" else {
                    val names = kinds.joinToString("、") { it.label }
                    if ((auto - manual).isNotEmpty()) "$names（含模型自动选择）" else names
                }
                runChatTurn(
                    conversationId = conversationId,
                    context = modelContext,
                    imageBase64s = imageBase64s,
                    webSearchEnabled = webSearchEnabled,
                    forceWebSearch = _state.value.forceWebSearch,
                    today = today,
                ).let { lastReply ->
                    _state.update { state ->
                        state.copy(
                            busy = false,
                            activeReasoning = "",
                            activeAnswerStarted = false,
                            lastContextNote = contextNote,
                            error = lastReply?.warnings?.joinToString("；")?.takeIf { it.isNotEmpty() },
                        )
                    }
                }
            } catch (error: AssistantModelException) {
                _state.update { it.copy(busy = false, activeReasoning = "", activeAnswerStarted = false, error = error.message) }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        activeReasoning = "",
                        activeAnswerStarted = false,
                        error = "请求失败：${error.message ?: "未知错误"}",
                    )
                }
            } finally {
                generationGuard.end()
            }
        }
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

    fun dismissPendingActions() = _state.update {
        it.copy(
            pendingActions = emptyList(),
            pendingActionsOwnerIndex = null,
            planReviewOpen = false,
            planReviewDate = null,
        )
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
        viewModelScope.launch {
            _state.update { it.copy(applyingEnglish = true, applyMessage = null) }
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
            val message = buildString {
                append("英语积累已更新：新增 $added 条，修改 $updated 条，删除 $deleted 条")
                if (failures.isNotEmpty()) {
                    append("；${failures.size} 条未成功：")
                    append(failures.joinToString("；"))
                }
            }
            _state.update {
                it.copy(
                    applyingEnglish = false,
                    applyMessage = message,
                    pendingEnglishActions = emptyList(),
                    pendingEnglishOwnerIndex = null,
                    englishReviewOpen = false,
                )
            }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    fun consumeApplyMessage() = _state.update { it.copy(applyMessage = null) }

    /** 应用勾选项：先做恢复点，再逐条落库。 */
    fun applySelected() {
        val selected = _state.value.pendingActions.filter { it.selected && it.problem == null }
        if (selected.isEmpty() || _state.value.applying) return
        viewModelScope.launch {
            _state.update { it.copy(applying = true, applyMessage = null) }
            try {
                versionedBackupRepository.createLocalVersion(reason = "before_ai_apply")
                val prefs = prefsRepository.current()
                val today = StudyClock(dayStart = prefs.dayStartTime).today()
                val results = applier.apply(selected.map { it.action }, today, prefs.dayOffPerMonth)
                val okCount = results.count { it.success }
                val failures = results.filterNot { it.success }
                val message = buildString {
                    append("已应用 $okCount 条修改（应用前已自动创建恢复点）")
                    if (failures.isNotEmpty()) {
                        append("；${failures.size} 条未应用：")
                        append(failures.joinToString("；") { it.message })
                    }
                }
                _state.update { state ->
                    val failedActions = failures.map { it.action }.toSet()
                    val remaining = state.pendingActions.filter { it.action in failedActions }
                        .map { it.copy(selected = false, problem = "应用失败：请返回对话后重新生成方案") }
                    state.copy(
                        applying = false,
                        applyMessage = message,
                        pendingActions = remaining,
                        planReviewOpen = remaining.isNotEmpty(),
                        planReviewDate = state.planReviewDate.takeIf { remaining.isNotEmpty() },
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(applying = false, applyMessage = "应用失败：${e.message ?: "未知错误"}")
                }
            }
        }
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
    return selected + inferred + required
}

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
