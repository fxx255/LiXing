package com.example.lixing.assistant

import com.example.lixing.data.assistant.AiCredentialStore
import com.example.lixing.data.assistant.AiReasoningEffort
import com.example.lixing.data.assistant.AiSearchProtocol
import com.example.lixing.data.assistant.AssistantContextBuilder
import com.example.lixing.data.assistant.AssistantModelClient
import com.example.lixing.data.assistant.AssistantModelException
import com.example.lixing.data.assistant.AssistantImagePrep
import com.example.lixing.data.assistant.AssistantRequestPolicy
import com.example.lixing.data.assistant.AssistantRequestSnapshot
import com.example.lixing.data.assistant.AssistantSnapshotCodec
import com.example.lixing.data.assistant.SnapshotHistoryMessage
import com.example.lixing.data.assistant.buildModelHistory
import com.example.lixing.data.assistant.endpointIdentityOf
import com.example.lixing.data.assistant.shouldUseWebSearch
import com.example.lixing.data.assistant.toPolicy
import com.example.lixing.data.local.entity.AssistantRequestEntity
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.repository.AssistantChatRepository
import com.example.lixing.domain.assistant.AssistantContextKind
import com.example.lixing.domain.assistant.AssistantMessage
import com.example.lixing.domain.time.StudyClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 生成前的**准备阶段**：选上下文、转写照片、裁历史、取偏好。
 *
 * ## 为什么独立于 ViewModel
 *
 * 这些步骤里 `chooseContext` 与照片转写都是**网络调用**，可以跑好几秒。
 * 它们以前写在 ViewModel 的 `viewModelScope` 里，于是「切页 / 旋转 / 锁屏」
 * 会连准备阶段一起取消 —— 用户回到页面只看到一条没有下文的用户消息，
 * 而且因为记录还停在 PREPARING，重启后会被当成遗留任务。
 *
 * 现在整段准备都归应用级管理器，与页面生命周期无关。
 *
 * ## 返回失败而不是抛异常
 *
 * 「模型不能看图、也没配识别模型」「附件读不出来」这些都是**可预期**的
 * 终态，必须落到请求记录上（ATTACHMENT_MISSING），让用户看到明确原因和重试入口，
 * 而不是让记录永远卡在 PREPARING。
 */
@Singleton
class GenerationPreparer @Inject constructor(
    private val modelClient: AssistantModelClient,
    private val contextBuilder: AssistantContextBuilder,
    private val chatRepository: AssistantChatRepository,
    private val prefsRepository: UserPreferencesRepository,
    private val aiCredentialStore: AiCredentialStore,
) {
    /** 准备结果：[failure] 非空表示这一轮无法开始，调用方须落终态。 */
    data class Prepared(
        val outgoingText: String,
        val history: List<AssistantMessage>,
        val context: String,
        val planRetryContext: String?,
        val imageBase64s: List<String>,
        val webSearchEnabled: Boolean,
        val maxContinuations: Int,
        val modelKey: String,
        val protocol: String,
        /** 安全端点身份（主机+路径，去查询串与凭证），用于 usage 归集。 */
        val endpointIdentity: String,
        /** 本轮的不可变运行时策略（档案 id / 模型 / 端点 / 联网设置）。 */
        val policy: AssistantRequestPolicy = AssistantRequestPolicy(),
        /** 提交时钉下的身份/设置快照；重试时据此校验配置未被改过。 */
        val snapshot: AssistantRequestSnapshot,
        /** 展示给用户的上下文说明（「本次未附带本机数据」/清单）。 */
        val contextNote: String,
        val failure: String? = null,
    ) {
        /**
         * 诊断/usage 归集键：**安全端点身份 + 模型 + 协议**。
         *
         * 必须带端点身份：只按模型名归集会把「同一个模型名挂在两个供应商」的样本
         * 混在一起，算出来的缓存命中率没有意义。
         */
        val usageGroupKey: String
            get() = AssistantRequestSnapshot(
                model = modelKey,
                endpointIdentity = endpointIdentity,
                protocol = protocol,
            ).usageGroupKey
    }

    /**
     * 新提问的准备：**全部以 captureInitialSnapshot 钉下的 [initial] 为准**。
     *
     * 身份/历史/设置都在提交时已钉下，这里只做网络工作（转写、选上下文），
     * 并把结果补写进快照（copy 初始快照，只改 context / outgoing / prepared）。
     * 不再重读活动档案、不再重建历史 —— 那会覆盖掉初始快照。
     */
    suspend fun prepare(
        submission: AssistantGenerationManager.Submission,
        owner: AssistantRequestEntity,
        initial: AssistantRequestSnapshot,
    ): Prepared {
        if (!initial.isComplete) {
            return failure(owner, "本轮恢复信息不完整，无法安全准备；请重新发送这道题")
        }
        // **配置不完整的看图题必须明确失败**：提交时有附件，但主模型不能看图、
        // 也没有可用的识别档案 ⇒ 不能静默降级成纯文本提问（模型只能凭一句
        // "看图"瞎编）。这里抛 CONFIG_INVALID —— 管理器的异常分类会把准备阶段
        // 的失败正确落成「配置失效」终态，而不是可重试的"附件丢失"。
        if (submission.attachmentPaths.isNotEmpty() &&
            initial.photoRoute == AssistantRequestSnapshot.PHOTO_ROUTE_NONE
        ) {
            throw AssistantModelException(
                AssistantModelException.Kind.CONFIG_INVALID,
                "当前主模型不能看图，也没有配置题目识别模型；请在设置里选择一个能看图的模型，或配置题目识别模型后重新发送",
            )
        }
        val policy = initial.toPolicy()
        // 照片按**初始快照**里钉下的路由走（不再探测当前配置）。
        val photo = when (initial.photoRoute) {
            AssistantRequestSnapshot.PHOTO_ROUTE_NONE ->
                PhotoOutcome(emptyList(), submission.userText, route = AssistantRequestSnapshot.PHOTO_ROUTE_NONE)
            AssistantRequestSnapshot.PHOTO_ROUTE_DIRECT -> {
                val encoded = encodeAll(submission.attachmentPaths)
                if (encoded.failure != null) return failure(owner, encoded.failure)
                // 主模型能看图：**保留用户原问题**；纯照片题（没打字）用默认看图提示词。
                // encodeAll 的 outgoingText 恒为空串 —— 直接透传会把问题整条丢掉，
                // 模型只收到图不知道要干什么。
                encoded.copy(outgoingText = submission.userText.ifBlank { DEFAULT_VISION_PROMPT })
            }
            AssistantRequestSnapshot.PHOTO_ROUTE_TRANSCRIBE -> {
                // 用初始快照钉下的识别档案转写（不是"当前配的识别档案"）。
                transcribe(submission.attachmentPaths, submission.userText, initial.visionProfileId, initial)
            }
            else -> return failure(owner, "这一轮的照片路由无法识别，请重新发送")
        }
        if (photo.failure != null) {
            return failure(owner, photo.failure)
        }
        val outgoing = photo.outgoingText
        val prefs = prefsRepository.current()
        val today = StudyClock(dayStart = prefs.dayStartTime).today()
        val manual = submission.contextKinds
        // 自动选择是一次网络调用；失败不该让整轮提问失败，退化为「只用用户勾选的」。
        val auto = runCatching { modelClient.chooseContext(outgoing, policy) }
            .getOrElse { error -> if (error is kotlinx.coroutines.CancellationException) throw error else emptySet() }
        val kinds = requiredAssistantContext(
            prompt = outgoing,
            selected = manual + auto,
            inPlanChangeFlow = submission.inPlanChangeFlow,
        )
        val context = runCatching { contextBuilder.build(kinds, today) }
            .getOrElse { error -> if (error is kotlinx.coroutines.CancellationException) throw error else "" }
        // 自纠正兜底：这次没带计划数据时，先备好「补上计划数据」的版本。
        val planRetryContext = planRetryContextFor(kinds, today)
        val note = contextNote(kinds, auto, manual)
        // **初始历史**来自快照，绝不重读当前会话（用户此刻的编辑不能改变这轮的上下文）。
        val history = initial.originalHistory.map { AssistantMessage(it.role, it.text, id = it.id) } +
            AssistantMessage("user", outgoing)
        // 客户端会解析**同一份**身份；这里沿用初始快照的安全身份做展示/归组。
        val effectiveWeb = policy.effectiveWebSearchEnabled
        return Prepared(
            outgoingText = outgoing,
            history = history,
            context = context,
            planRetryContext = planRetryContext,
            imageBase64s = photo.imageBase64s,
            webSearchEnabled = shouldUseWebSearch(outgoing, initial.forceWebSearch) && effectiveWeb,
            maxContinuations = initial.maxContinuations,
            modelKey = initial.model,
            protocol = initial.protocol,
            endpointIdentity = initial.endpointIdentity,
            policy = policy,
            // 补写结果：copy 初始快照，只改准备产出，身份/历史/设置原样保留。
            snapshot = initial.copy(
                prepared = true,
                sourceContext = context,
                sourceUserText = outgoing,
                contextChars = context.length,
                historyMessages = history.size,
                hasImages = photo.imageBase64s.isNotEmpty(),
                contextKinds = kinds.map { it.name },
            ),
            contextNote = note,
        )
    }

    /**
     * **网络之前**钉下的初始快照：原问题、原历史（有界）、照片路由、
     * 主/识别档案 id 与安全身份、推理与联网设置、续写上限。
     *
     * 在 [AssistantGenerationManager.submit] 里、`createRequest` 的原子插入**之前**
     * 调用，随请求一起落库 —— 这样"准备阶段被打断"也有东西可重试。
     * **不含**密钥与图片 base64。
     */
    suspend fun captureInitialSnapshot(
        submission: AssistantGenerationManager.Submission,
        conversationId: String,
        userMessageId: String,
        answerMessageId: String,
    ): AssistantRequestSnapshot {
        val prefs = prefsRepository.current()
        val identity = aiCredentialStore.resolveActiveIdentity(prefs.aiBaseUrl, prefs.aiModel)
        val baseUrl = identity?.baseUrl ?: prefs.aiBaseUrl
        val model = identity?.model ?: prefs.aiModel
        val endpoint = endpointIdentityOf(baseUrl)
        // 历史在**此刻**就钉下：之后用户改会话/删消息都不能改变这轮的上下文。
        val bounded = boundedHistory(conversationId, userMessageId, answerMessageId)
        // 照片路由也在此刻决定（legacy 偏好 aiVisionEnabled 仍然生效）。
        // 快照的可路由集合只有 none/direct/transcribe；「有附件但主模型不能看图、
        // 也没有可用识别档案」这个降级信号由 **hasImages=true + photoRoute=none**
        // 携带，prepare 据此明确失败 —— 绝不静默把看图题当成纯文本发出去。
        val visionDirect = (identity?.visionEnabled ?: false) || prefs.aiVisionEnabled
        val recognizerId = if (visionDirect) null else aiCredentialStore.questionVisionProfileId()
        val recognizer = recognizerId?.let { aiCredentialStore.resolveIdentityFor(it) }
        val route = when {
            submission.attachmentPaths.isEmpty() -> AssistantRequestSnapshot.PHOTO_ROUTE_NONE
            visionDirect -> AssistantRequestSnapshot.PHOTO_ROUTE_DIRECT
            recognizer != null -> AssistantRequestSnapshot.PHOTO_ROUTE_TRANSCRIBE
            else -> AssistantRequestSnapshot.PHOTO_ROUTE_NONE
        }
        val today = StudyClock(dayStart = prefs.dayStartTime).today()
        val kinds = requiredAssistantContext(
            prompt = submission.userText,
            selected = submission.contextKinds,
            inPlanChangeFlow = submission.inPlanChangeFlow,
        )
        return AssistantRequestSnapshot(
            model = model,
            endpointIdentity = endpoint,
            protocol = protocolOf(baseUrl, identity?.searchProtocol),
            reasoningEffort = (identity?.reasoningEffort ?: AiReasoningEffort.LOW).name,
            webSearchEnabled = shouldUseWebSearch(submission.userText, submission.forceWebSearch),
            hasImages = submission.attachmentPaths.isNotEmpty(),
            sourceUserText = submission.userText,
            // v2 完整复原信息（显式写 version：缺字段解码为 v1）。
            version = AssistantRequestSnapshot.VERSION_V2,
            prepared = false,
            forceWebSearch = submission.forceWebSearch,
            effectiveWebSearchEnabled = prefs.aiWebSearchEnabled,
            maxContinuations = prefs.assistantAutoContinue.coerceAtLeast(0),
            primaryProfileId = identity?.profileId.orEmpty(),
            visionProfileId = recognizer?.profileId.orEmpty(),
            visionModel = recognizer?.model.orEmpty(),
            visionEndpointIdentity = recognizer?.let { endpointIdentityOf(it.baseUrl) }.orEmpty(),
            photoRoute = route,
            originalHistory = bounded.map { SnapshotHistoryMessage(it.id.orEmpty(), it.role, it.content) },
            manualContextKinds = submission.contextKinds.map { it.name },
            inPlanChangeFlow = submission.inPlanChangeFlow,
        ).let { snapshot ->
            // 快照里的 contextKinds 记录**预估**种类（不含网络自动选择），供诊断对照。
            snapshot.copy(contextKinds = kinds.map { it.name })
        }
    }

    /**
     * 重试的准备：**快照是权威**，按 [AssistantRequestSnapshot.prepared] 分两条路。
     *
     * - `prepared = false`（准备被打断）⇒ 用**原路由、原附件、原识别档案**重跑准备：
     *   转写路由会真的重新转写，绝不把图直发文字主模型；
     * - `prepared = true` ⇒ 用快照里已保存的转写文本/上下文，直接进入生成。
     *
     * 两条路都沿用快照钉下的身份与设置；易变的计划/日期数据在本方法内刷新。
     */
    suspend fun prepareRetry(
        userText: String,
        attachmentPaths: List<String>,
        refreshedContext: String?,
        owner: AssistantRequestEntity,
        history: List<AssistantMessage>,
    ): Prepared {
        val snapshot = AssistantSnapshotCodec.decode(owner.snapshotJson)
        when {
            snapshot == null -> return failure(
                owner,
                "这一轮没有恢复信息，无法安全重发。请重新发送这道题。",
            )
            snapshot.isLegacy -> return failure(
                owner,
                "这一轮的恢复信息来自旧版本，不完整，无法安全重发。请重新发送这道题。",
            )
            !snapshot.isComplete -> return failure(
                owner,
                "这一轮的恢复信息不完整，无法安全重发。请重新发送这道题。",
            )
        }
        val policy = snapshot.toPolicy()
        if (snapshot.prepared) {
            return preparedRetryFromSnapshot(snapshot, policy, owner, refreshedContext)
        }
        // 未完成准备的快照：按**原路由**重跑准备（转写路由真的重新转写）。
        val submission = AssistantGenerationManager.Submission(
            conversationId = owner.conversationId,
            userText = snapshot.sourceUserText,
            attachmentPaths = attachmentPaths,
            contextKinds = snapshot.manualContextKinds.mapNotNull { name ->
                runCatching { AssistantContextKind.valueOf(name) }.getOrNull()
            }.toSet(),
            inPlanChangeFlow = snapshot.inPlanChangeFlow,
            forceWebSearch = snapshot.forceWebSearch,
        )
        return prepare(submission, owner, snapshot)
    }

    /**
     * prepared 快照的重试：转写文本/上下文/历史/设置全部来自快照。
     *
     * 照片按**快照钉下的路由**补齐：
     * - `direct` ⇒ 主模型能看图，重试必须把**原图**重新编码再发 —— 不然准备已完成
     *   的断流/重试会静默降级成「看图题变成无图空问」。路径按落库记录的
     *   [owner.attachmentPaths] 重读（与 `beginRetry` 回写的持久化附件一致），
     *   编码**数量不符即失败**，绝不偷偷少传一张图；
     * - `transcribe` ⇒ 仍用快照里保存的转写文本，**不发任何图**给文字主模型
     *   （重新转写属于"准备被打断"分支的职责，这里准备已完成）；
     * - `none` ⇒ 本来就无图。
     */
    private suspend fun preparedRetryFromSnapshot(
        snapshot: AssistantRequestSnapshot,
        policy: AssistantRequestPolicy,
        owner: AssistantRequestEntity,
        refreshedContext: String?,
    ): Prepared {
        val outgoing = snapshot.sourceUserText
        val history = snapshot.originalHistory.map { AssistantMessage(it.role, it.text, id = it.id) } +
            AssistantMessage("user", outgoing)
        // **易变上下文以传入的为准**（重试时刚重建的计划/任务数据、当天日期），
        // null 表示没有刷新结果；空字符串也是有效刷新，不能复活已删除的旧计划数据。
        val context = refreshedContext ?: snapshot.sourceContext
        val imageBase64s = when (snapshot.photoRoute) {
            AssistantRequestSnapshot.PHOTO_ROUTE_DIRECT -> {
                val paths = owner.attachmentPaths.trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { runCatching { decodeAttachmentList(it) }.getOrDefault(emptyList()) }
                    ?: emptyList()
                // 缺失恢复信息（无附件路径）⇒ 明确失败；调用方落 ATTACHMENT_MISSING 终态，
                // 主模型一次网络请求都不会发出。
                if (paths.isEmpty()) {
                    return failure(owner, "原附件路径没有随本轮记录保存，无法带上原图重试；请重新发送这道题")
                }
                val encoded = encodeAll(paths)
                if (encoded.failure != null) return failure(owner, encoded.failure)
                encoded.imageBase64s
            }
            // 转写路由：准备已完成 ⇒ 用保存的转写文本，主模型 0 张图。
            else -> emptyList()
        }
        return Prepared(
            outgoingText = outgoing,
            history = history,
            context = context,
            planRetryContext = null,
            imageBase64s = imageBase64s,
            webSearchEnabled = snapshot.webSearchEnabled && policy.effectiveWebSearchEnabled,
            maxContinuations = snapshot.maxContinuations,
            modelKey = snapshot.model,
            protocol = snapshot.protocol,
            endpointIdentity = snapshot.endpointIdentity,
            policy = policy,
            // 补写快照：上下文用本轮**刷新后**的值（原文历史与身份/设置原样保留）。
            snapshot = snapshot.copy(
                sourceContext = context,
                contextChars = context.length,
            ),
            contextNote = "",
        )
    }

    private fun failure(owner: AssistantRequestEntity, message: String) = Prepared(
        outgoingText = owner.userText,
        history = emptyList(),
        context = "",
        planRetryContext = null,
        imageBase64s = emptyList(),
        webSearchEnabled = false,
        maxContinuations = 0,
        modelKey = "",
        protocol = "",
        endpointIdentity = "",
        snapshot = AssistantRequestSnapshot(),
        contextNote = "",
        failure = message,
    )

    private data class PhotoOutcome(
        val imageBase64s: List<String>,
        val outgoingText: String,
        val failure: String? = null,
        /** 本次照片实际走的路由（直送/转写/无图）。 */
        val route: String = AssistantRequestSnapshot.PHOTO_ROUTE_NONE,
        /** 转写路由使用的识别档案 id。 */
        val visionProfileId: String? = null,
    )


    /**
     * 转写：用**快照钉下的识别档案**（id + 模型 + 端点身份）转写原附件。
     *
     * - 识别档案被删/换模型/换端点 ⇒ 明确失败，绝不静默换一个识别模型；
     * - 转写失败按失败返回（调用方落终态），取消原样传播。
     */
    private suspend fun transcribe(
        photoPaths: List<String>,
        prompt: String,
        visionProfileId: String,
        snapshot: AssistantRequestSnapshot,
    ): PhotoOutcome {
        if (visionProfileId.isBlank()) {
            return PhotoOutcome(
                emptyList(), prompt,
                failure = "本轮未记录题目识别模型，无法转写原图片；请重新发送",
                route = AssistantRequestSnapshot.PHOTO_ROUTE_TRANSCRIBE,
            )
        }
        val vision = aiCredentialStore.resolveIdentityFor(visionProfileId)
        if (vision == null) {
            return PhotoOutcome(
                emptyList(), prompt,
                failure = "题目识别模型配置已被删除或缺少密钥，请重新选择后再试",
                route = AssistantRequestSnapshot.PHOTO_ROUTE_TRANSCRIBE,
            )
        }
        if (snapshot.visionModel.isNotBlank() && vision.model != snapshot.visionModel ||
            snapshot.visionEndpointIdentity.isNotBlank() &&
            endpointIdentityOf(vision.baseUrl) != snapshot.visionEndpointIdentity
        ) {
            return PhotoOutcome(
                emptyList(), prompt,
                failure = "题目识别模型已改变（要求 ${snapshot.visionModel} @ " +
                    "${snapshot.visionEndpointIdentity}），请重新发送而不是重试",
                route = AssistantRequestSnapshot.PHOTO_ROUTE_TRANSCRIBE,
            )
        }
        val encoded = encodeAll(photoPaths)
        if (encoded.failure != null) return encoded
        val transcription = runCatching {
            modelClient.completeWithProfile(
                profileId = visionProfileId,
                systemPrompt = VISION_TRANSCRIBE_PROMPT,
                userText = "请完整转写图片中的全部题目内容。",
                imageBase64s = encoded.imageBase64s,
            )
        }.getOrElse { error -> if (error is kotlinx.coroutines.CancellationException) throw error else null }
        if (transcription.isNullOrBlank()) {
            return PhotoOutcome(
                emptyList(), prompt,
                failure = "题目识别没能读出内容，请重拍或直接在设置里换一个能看图的模型",
                route = AssistantRequestSnapshot.PHOTO_ROUTE_TRANSCRIBE,
                visionProfileId = visionProfileId,
            )
        }
        return PhotoOutcome(
            imageBase64s = emptyList(),
            outgoingText = buildString {
                if (prompt.isNotBlank()) append(prompt).append("\n\n")
                append(transcription)
            }.trim(),
            route = AssistantRequestSnapshot.PHOTO_ROUTE_TRANSCRIBE,
            visionProfileId = visionProfileId,
        )
    }

    /**
     * 编码全部附件；**数量不符即失败**，绝不偷偷少传一张图。
     */
    private suspend fun encodeAll(photoPaths: List<String>): PhotoOutcome {
        val encoded = withContext(Dispatchers.Default) {
            photoPaths.mapNotNull(AssistantImagePrep::encodeForVision)
        }
        return if (encoded.size == photoPaths.size) {
            PhotoOutcome(encoded, "")
        } else {
            PhotoOutcome(
                emptyList(),
                "",
                "有图片读取失败（原附件可能已被清理），请重新选择图片后再发送",
            )
        }
    }

    /**
     * 解码落库的附件路径 JSON（[AssistantRequestEntity.attachmentPaths]）。
     *
     * 解析失败一律按空列表兜底：坏数据走"明确失败 + 重新发送"的路径，
     * 绝不让重试偷偷降级成无图请求。与仓库写入侧用的是同一种 JSON 字符串列表编码。
     */
    private fun decodeAttachmentList(raw: String): List<String> = runCatching {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        json.decodeFromString(ListSerializer(String.serializer()), raw)
    }.getOrDefault(emptyList())

    /**
     * 组历史（仅保留给既有调用方/测试使用）：在有界历史上追加当前轮。
     */
    private suspend fun historyFor(
        owner: AssistantRequestEntity,
        currentUserTurn: String,
        includeCurrentUserTurn: Boolean,
    ): List<AssistantMessage> {
        val history = boundedHistory(owner.conversationId, owner.userMessageId, owner.answerMessageId)
        return if (includeCurrentUserTurn) history + AssistantMessage("user", currentUserTurn) else history
    }

    /** **有界历史**：排除本请求自己的消息位与空回答，保留其余（按会话顺序）。 */
    private suspend fun boundedHistory(
        conversationId: String,
        userMessageId: String,
        answerMessageId: String,
    ): List<AssistantMessage> {
        val messages = runCatching { chatRepository.messages(conversationId) }
            .getOrElse { error -> if (error is kotlinx.coroutines.CancellationException) throw error else emptyList() }
        val trimmed = messages.filterNot { message ->
            message.id == answerMessageId ||
                message.id == userMessageId ||
                (message.role == "assistant" && message.content.isBlank())
        }
        return buildModelHistory(trimmed.map { AssistantMessage(it.role, it.content, id = it.id) })
    }

    private fun contextNote(
        kinds: Set<AssistantContextKind>,
        auto: Set<AssistantContextKind>,
        manual: Set<AssistantContextKind>,
    ): String = if (kinds.isEmpty()) {
        "本次未附带本机数据"
    } else {
        val names = kinds.joinToString("、") { it.label }
        if ((auto - manual).isNotEmpty()) "$names（含模型自动选择）" else names
    }

    /**
     * 备好「补上计划数据」的上下文，供自纠正重试用。
     *
     * 这次已经带了计划数据就返回 null —— 没什么可补的。
     * 用 runCatching 兜住：拼上下文失败绝不能反过来把正常回答也搞挂。
     */
    private suspend fun planRetryContextFor(
        kinds: Set<AssistantContextKind>,
        today: java.time.LocalDate,
    ): String? {
        if (AssistantContextKind.PLAN in kinds) return null
        return try {
            contextBuilder.build(kinds + AssistantContextKind.PLAN, today).takeIf { it.isNotBlank() }
        } catch (error: Throwable) {
            // 取消必须传播；其余（本机拼上下文失败）兜底为 null。
            if (error is kotlinx.coroutines.CancellationException) throw error
            null
        }
    }

    companion object {
        const val DEFAULT_VISION_PROMPT =
            "请先准确识别图片中的题目，再给出细致解答。数学公式使用 LaTeX，并写出完整推导过程。"

        const val VISION_TRANSCRIBE_PROMPT =
            "你是题目转写器。请把图片中的题目完整转写为 Markdown：中文保持原文；" +
                "数学公式使用 LaTeX 并以 $$...$$ 包裹，块级公式的起止 $$ 各占一行。" +
                "不要解题，不要输出额外解释。"

        /**
         * 端点协议身份：只影响诊断与快照，不含密钥。
         *
         * 必须考虑**档案的联网协议**：一个非 deepseek 地址也可能被用户配成
         * Responses 协议；早先只看 URL 里有没有 "deepseek"，会把协议判错。
         */
        fun protocolOf(baseUrl: String, searchProtocol: AiSearchProtocol? = null): String =
            when (searchProtocol) {
                AiSearchProtocol.RESPONSES -> "responses"
                AiSearchProtocol.CHAT_COMPLETIONS -> "chat_completions"
                AiSearchProtocol.OFF ->
                    if ("deepseek" in baseUrl.lowercase()) "responses" else "chat_completions"
                null ->
                    if ("deepseek" in baseUrl.lowercase()) "responses" else "chat_completions"
            }
    }
}

/**
 * 放宽计划数据注入：处于「计划变更流程」时无条件带上计划数据。
 *
 * 与界面层 `requiredAssistantContext` 同一规则的实现（管理器在准备阶段也要用），
 * 判据之所以宽松：多注入一次计划数据只多花几百 token，
 * 而漏判的代价是整轮改计划直接失败、模型还会**编造 id**。
 */
internal fun requiredAssistantContext(
    prompt: String,
    selected: Set<AssistantContextKind>,
    inPlanChangeFlow: Boolean,
): Set<AssistantContextKind> {
    val inferred = com.example.lixing.ui.screen.assistant.inferAssistantContext(prompt)
    val todayMarkers = listOf(
        "今日请假", "今天请假", "今日不做", "今天不做", "只保留", "仅保留",
        "跳过今日", "跳过今天", "取消今日任务", "取消今天任务", "删除今日任务", "删掉今天",
    )
    val required = if (todayMarkers.any(prompt::contains)) {
        setOf(AssistantContextKind.TODAY)
    } else {
        emptySet()
    }
    val flowRequired = if (inPlanChangeFlow) setOf(AssistantContextKind.PLAN) else emptySet()
    return selected + inferred + required + flowRequired
}
