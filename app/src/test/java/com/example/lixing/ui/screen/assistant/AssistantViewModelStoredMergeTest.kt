package com.example.lixing.ui.screen.assistant

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.example.lixing.assistant.AssistantDraftStore
import com.example.lixing.assistant.AssistantGenerationManager
import com.example.lixing.data.assistant.AiCredentialStore
import com.example.lixing.data.assistant.AiModelProfile
import com.example.lixing.data.assistant.AiResolvedIdentity
import com.example.lixing.data.assistant.AiSearchProtocol
import com.example.lixing.data.assistant.AssistantDiagnostics
import com.example.lixing.data.assistant.AssistantRequestSnapshot
import com.example.lixing.data.assistant.AssistantSnapshotCodec
import com.example.lixing.data.assistant.PendingPlanReviewPayload
import com.example.lixing.data.assistant.encodePendingReview
import com.example.lixing.data.assistant.endpointIdentityOf
import com.example.lixing.data.diagram.DiagramImageStore
import com.example.lixing.data.local.entity.AssistantMessageEntity
import com.example.lixing.data.local.entity.AssistantRequestEntity
import com.example.lixing.data.prefs.UserPreferences
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.repository.AssistantChatRepository
import com.example.lixing.data.repository.AssistantRequestRepository
import com.example.lixing.data.repository.EnglishEntryRepository
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.data.repository.PlanReviewTransactionResult
import com.example.lixing.domain.assistant.AssistantFailureKind
import com.example.lixing.domain.assistant.AssistantRequestStatus
import com.example.lixing.domain.assistant.PlanAction
import com.example.lixing.domain.assistant.PlanApplyResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.coVerify
import java.time.LocalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real ViewModel tests with one injected scheduler and deferred repository reads. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AssistantViewModelStoredMergeTest {

    private val dispatcher = StandardTestDispatcher()

    // 按会话可控的 DB 消息流（DB 落库 / 迟到发射都由测试直接写 value）。
    private val messagesA = MutableStateFlow<List<AssistantMessageEntity>>(emptyList())
    private val messagesB = MutableStateFlow<List<AssistantMessageEntity>>(emptyList())

    private val activeState = MutableStateFlow(AssistantGenerationManager.ActiveState())
    private val events = MutableSharedFlow<AssistantGenerationManager.GenerationEvent>(extraBufferCapacity = 64)

    /** 按会话可控的请求记录表（refreshRetryEntry / applyStoredRequests 的数据源）。 */
    private val requestsByConversation = mutableMapOf<String, List<AssistantRequestEntity>>()

    /** 挡住**下一次** `requestRepository.forConversation("A")` 的门闩；null = 不挡。 */
    private var gateForRequestsA: CompletableDeferred<Unit>? = null

    /** 挡住**下一次** `chatRepository.messages("A")` 的门闩；null = 不挡。 */
    private var gateForMessagesA: CompletableDeferred<Unit>? = null

    /** 重试身份校验需要读到的当前 prefs（各测试自定）。 */
    private var prefsForRetry = UserPreferences(
        aiAssistantEnabled = true,
        aiBaseUrl = "https://pinned.example.com/v1",
        aiModel = "pinned-model",
    )

    /** aiCredentialStore.activeProfile() 的返回值（null = 无档案，走旧式偏好）。 */
    private var activeProfile: AiModelProfile? = null

    /** resolveIdentityFor(profileId) 的返回值（null = 档案已删除）。 */
    private var resolvedIdentityForPinned: AiResolvedIdentity? = null

    private lateinit var store: ViewModelStore
    private lateinit var viewModel: AssistantViewModel

    private lateinit var generationManager: AssistantGenerationManager
    private lateinit var requestRepository: AssistantRequestRepository
    private lateinit var planRepository: PlanRepository
    private lateinit var chatRepository: AssistantChatRepository
    private val contextBuilder = mockk<com.example.lixing.data.assistant.AssistantContextBuilder>(relaxed = true)
    private var reviewDelegate: com.example.lixing.data.repository.AssistantReviewTransactionRepository? = null
    private val reviewBoundary = mockk<com.example.lixing.data.repository.AssistantReviewTransactionRepository>()

    private fun swapReviewTransactions(value: com.example.lixing.data.repository.AssistantReviewTransactionRepository) {
        reviewDelegate = value
    }

    // ---------------- fixtures ----------------

    private fun row(
        conversationId: String,
        id: String,
        role: String,
        content: String,
        imagePaths: String = "",
        pendingReview: String = "",
    ) = AssistantMessageEntity(
        id = id,
        conversationId = conversationId,
        role = role,
        content = content,
        imagePaths = imagePaths,
        pendingReview = pendingReview,
    )

    /** 可控的请求记录行（默认可重试的 NETWORK 中断）。 */
    private fun requestRow(
        requestId: String,
        conversationId: String,
        userMessageId: String = "u-$requestId",
        answerMessageId: String = "a-$requestId",
        attemptId: String = "t-$requestId",
        status: AssistantRequestStatus = AssistantRequestStatus.INTERRUPTED,
        failureKind: AssistantFailureKind = AssistantFailureKind.NETWORK,
        snapshot: AssistantRequestSnapshot? = null,
    ) = AssistantRequestEntity(
        requestId = requestId,
        conversationId = conversationId,
        userMessageId = userMessageId,
        answerMessageId = answerMessageId,
        attemptId = attemptId,
        status = status.name,
        failureKind = failureKind.name,
        userText = "原问题",
        snapshotJson = snapshot?.let(AssistantSnapshotCodec::encode).orEmpty(),
    )

    /** 身份快照：钉住档案的模型 / 端点 / Responses 协议。 */
    private fun pinnedSnapshot(
        primaryProfileId: String = "",
        model: String = "pinned-model",
        baseUrl: String = "https://pinned.example.com/v1",
    ) = AssistantRequestSnapshot(
        model = model,
        endpointIdentity = endpointIdentityOf(baseUrl),
        protocol = "responses",
        version = AssistantRequestSnapshot.VERSION_V2,
        primaryProfileId = primaryProfileId,
    )

    /** 档案 / 身份 fixture：与 [pinnedSnapshot] 默认值一致。 */
    private fun pinnedProfileFixture(profileId: String = "profile-pinned"): Pair<AiModelProfile, AiResolvedIdentity> =
        AiModelProfile(
            id = profileId,
            name = "钉住档案",
            baseUrl = "https://pinned.example.com/v1",
            model = "pinned-model",
            visionEnabled = false,
            searchProtocol = AiSearchProtocol.RESPONSES,
            reasoningEffort = com.example.lixing.data.assistant.AiReasoningEffort.LOW,
            hasApiKey = true,
        ) to AiResolvedIdentity(
            profileId = profileId,
            baseUrl = "https://pinned.example.com/v1",
            model = "pinned-model",
            apiKey = "sk-test",
            visionEnabled = false,
            searchProtocol = AiSearchProtocol.RESPONSES,
            reasoningEffort = com.example.lixing.data.assistant.AiReasoningEffort.LOW,
        )

    /** 测试自有的图片路径 fixture 解析（仅构造 stub 输出，不是生产 helper 的复制）。 */
    private fun pathsFromJson(raw: String): List<String> =
        Regex("\"([^\"]*)\"").findAll(raw).map { it.groupValues[1] }.toList()

    private val planActionsJson =
        """[{"kind":"UPDATE_TIME_SLOT","slotId":"slot-1","endTime":"09:00","reason":"提前结束"}]"""
    private val englishActionsJson =
        """[{"kind":"ADD_ENGLISH_ENTRY","type":"WORD","content":"serendipity","meaning":"意外发现之乐"}]"""

    private val envelope = encodePendingReview(
        PendingPlanReviewPayload(
            actionsJson = planActionsJson,
            selected = listOf(true),
            englishActionsJson = englishActionsJson,
            englishSelected = listOf(true),
        ),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        gateForMessagesA = null
        gateForRequestsA = null
        requestsByConversation.clear()
        prefsForRetry = UserPreferences(
            aiAssistantEnabled = true,
            aiBaseUrl = "https://pinned.example.com/v1",
            aiModel = "pinned-model",
        )
        activeProfile = null
        resolvedIdentityForPinned = null

        chatRepository = mockk<AssistantChatRepository> {
            every { observeConversations() } returns emptyFlow()
            every { observeMessages("A") } returns messagesA
            every { observeMessages("B") } returns messagesB
            every { decodeImagePaths(any()) } answers { pathsFromJson(firstArg()) }
            // 门闩真实挂起：首次调用取走 gate 并 await；无 gate 时直接返回当前流值。
            coEvery { messages(any()) } coAnswers {
                val gate = gateForMessagesA
                if (gate != null) {
                    gateForMessagesA = null
                    gate.await()
                }
                if (firstArg<String>() == "A") messagesA.value else messagesB.value
            }
        }
        requestRepository = mockk<AssistantRequestRepository> {
            every { observeForConversation(any()) } returns emptyFlow()
            // 请求表同样可控：按会话读 map，并支持用门闩制造「请求表迟到」。
            coEvery { forConversation(any()) } coAnswers {
                val conversationId = firstArg<String>()
                val gate = if (conversationId == "A") gateForRequestsA else null
                if (gate != null) {
                    gateForRequestsA = null
                    gate.await()
                }
                requestsByConversation[conversationId].orEmpty()
            }
            coEvery { this@mockk.get(any<String>()) } coAnswers { requestsByConversation.values.flatten().firstOrNull { it.requestId == firstArg<String>() } }
            coEvery { decodeList(any()) } answers {
                Regex("\"([^\"]*)\"").findAll(firstArg()).map { it.groupValues[1] }.toList()
            }
        }
        val prefsRepository = mockk<UserPreferencesRepository> {
            coEvery { current() } answers { prefsForRetry }
        }
        planRepository = mockk<PlanRepository>(relaxed = true) {
            // 预览校验走「时段不存在」的 invalid preview：条目保留但带 problem，
            // 避免依赖深层 plan 数据；预览条数断言不受影响。
            coEvery { getTimeSlot(any()) } returns null
        }
        val diagramImageStore = mockk<DiagramImageStore>(relaxed = true) {
            // restore 返回 null ⇒ 回退原路径，图片槽位断言稳定。
            every { restore(any()) } returns null
        }
        val draftStore = mockk<AssistantDraftStore>(relaxed = true) {
            coEvery { load(any()) } returns null
        }
        val aiCredentialStore = mockk<AiCredentialStore> {
            every { activeProfile() } answers { activeProfile }
            every { resolveIdentityFor(any()) } answers { resolvedIdentityForPinned }
        }
        generationManager = mockk<AssistantGenerationManager>(relaxed = true) {
            every { state } returns activeState
            every { events } returns this@AssistantViewModelStoredMergeTest.events
            every { isRunning() } answers { activeState.value.isRunning }
            // recoverOnStartup() = awaitStartup()，实际签名为 suspend () -> Unit。
            coEvery { recoverOnStartup() } returns Unit
        }

        coEvery { requestRepository.beginRetry(any(), any(), any(), any()) } coAnswers {
            val id = firstArg<String>()
            val previous = requestsByConversation.values.flatten().first { it.requestId == id }
            previous.copy(attemptId = secondArg(), status = "PREPARING").also { next ->
                requestsByConversation[previous.conversationId] =
                    requestsByConversation[previous.conversationId].orEmpty().map {
                        if (it.requestId == id) next else it
                    }
            }
        }
        coEvery { requestRepository.interrupt(any(), any(), any(), any(), any()) } coAnswers {
            val id = firstArg<String>()
            val attempt = secondArg<String>()
            val partial = thirdArg<String>()
            requestsByConversation.replaceAll { _, records -> records.map {
                if (it.requestId == id && it.attemptId == attempt)
                    it.copy(status = "INTERRUPTED", partialText = partial) else it
            } }
            true
        }
        coEvery { generationManager.submitRetry(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val request = requestsByConversation.values.flatten().first { it.requestId == firstArg<String>() }
            activeState.value = AssistantGenerationManager.ActiveState(
                requestId = request.requestId, attemptId = secondArg(), conversationId = request.conversationId,
                answerMessageId = request.answerMessageId, phase = AssistantRequestStatus.RUNNING,
            )
        }
        coEvery { reviewBoundary.applyPlan(any(), any(), any(), any(), any()) } coAnswers {
            requireNotNull(reviewDelegate).applyPlan(firstArg(), secondArg(), thirdArg(), arg(3), arg(4))
        }
        coEvery { reviewBoundary.applyEnglish(any(), any(), any()) } coAnswers {
            requireNotNull(reviewDelegate).applyEnglish(firstArg(), secondArg(), thirdArg())
        }
        val englishEntryRepository = mockk<EnglishEntryRepository>(relaxed = true)
        viewModel = AssistantViewModel(
            prefsRepository = prefsRepository,
            modelClient = mockk(relaxed = true),
            contextBuilder = contextBuilder,
            applier = mockk(relaxed = true),
            versionedBackupRepository = mockk(relaxed = true),
            reviewPreviewBuilder = AssistantReviewPreviewBuilder(
                prefsRepository, planRepository, mockk(relaxed = true), englishEntryRepository),
            chatRepository = chatRepository,
            englishEntryRepository = englishEntryRepository,
            aiCredentialStore = aiCredentialStore,
            generationGuard = mockk(relaxed = true),
            plotImageStore = mockk(relaxed = true),
            diagramImageStore = diagramImageStore,
            generationManager = generationManager,
            requestRepository = requestRepository,
            diagnostics = AssistantDiagnostics(),
            draftStore = draftStore,
            reviewTransactions = reviewBoundary,
            io = dispatcher,
        )
        store = ViewModelStore().apply { put("test", viewModel) }
        // 让 init 的订阅协程全部就位（events/state/DB flow 的 collector 注册完成）。
        dispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        if (::store.isInitialized) store.clear()
        Dispatchers.resetMain()
    }

    // ---------------- 时序 helpers（无忙循环） ----------------

    private fun runToIdle() = dispatcher.scheduler.advanceUntilIdle()

    private fun runCurrent() = dispatcher.scheduler.runCurrent()

    /** Drain the injected scheduler; deliberately suspended boundaries remain suspended. */
    private suspend fun TestScope.awaitState(
        predicate: (AssistantUiState) -> Boolean,
    ) {
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue("Expected VM state after all available work", predicate(viewModel.state.value))
    }

    private suspend fun TestScope.settleRealtime() {
        dispatcher.scheduler.advanceUntilIdle()
    }

    // ---------------- 场景 1：submit 落库的新行按 id 进 UI，不重复 ----------------

    @Test
    fun `db rows inserted for the running turn appear in ui by id without duplicates`() =
        runTest(dispatcher) {
            viewModel.openConversation("A")
            awaitState { it.currentConversationId == "A" }
            assertTrue(viewModel.state.value.messages.isEmpty())

            // 模拟 submit 事务落库：用户消息 + 回答占位（VM 不经手，DB 流推送）。
            messagesA.value = listOf(
                row("A", "u1", "user", "问题"),
                row("A", "a1", "assistant", ""),
            )
            awaitState { s -> s.messages.any { it.id == "a1" } }
            val state = viewModel.state.value
            val ids = state.messages.map { it.id }
            assertTrue("DB 新增行必须进 UI，实际=$ids", ids.containsAll(listOf("u1", "a1")))
            assertEquals("不允许重复消息气泡", ids.size, ids.toSet().size)
            assertEquals("user", state.messages.first { it.id == "u1" }.role)
            assertEquals("assistant", state.messages.first { it.id == "a1" }.role)

            // DB 流重放（重复发射同样的行）：仍不产生重复。
            messagesA.value = messagesA.value
            awaitState { s -> s.messages.map { it.id } == ids }
            assertEquals(ids, viewModel.state.value.messages.map { it.id })
        }

    // ---------------- 场景 3：短终态覆盖长 partial；overlay 只认运行中的当前身份 ----------------

    @Test
    fun `short terminal db content replaces longer partial once manager is idle`() =
        runTest(dispatcher) {
            messagesA.value = listOf(
                row("A", "u1", "user", "问题"),
                row("A", "a1", "assistant", "很长的流式中间正文……"),
            )
            viewModel.openConversation("A")
            awaitState { s -> s.messages.firstOrNull { it.id == "a1" }?.content == "很长的流式中间正文……" }

            // 收尾：active 清空（非运行），DB 行更新为**较短**的终态。
            activeState.value = AssistantGenerationManager.ActiveState()
            messagesA.value = listOf(
                row("A", "u1", "user", "问题"),
                row("A", "a1", "assistant", "终"),
            )
            awaitState { s -> s.messages.firstOrNull { it.id == "a1" }?.content == "终" }
            assertEquals(
                "终态必须按 DB 内容替换，即使更短",
                "终",
                viewModel.state.value.messages.first { it.id == "a1" }.content,
            )
            assertFalse("非运行恢复不得粘住 busy", viewModel.state.value.busy)
        }

    @Test
    fun `running overlay shows current partial and survives late stale db rows`() =
        runTest(dispatcher) {
            // 管理器正在跑 r1/t1：DB 行是节流旧值，UI 必须显示 manager 当前 partial。
            activeState.value = AssistantGenerationManager.ActiveState(
                requestId = "r1",
                attemptId = "t1",
                conversationId = "A",
                phase = AssistantRequestStatus.RUNNING,
                answerMessageId = "a1",
                partialText = "流式正文",
            )
            messagesA.value = listOf(
                row("A", "u1", "user", "问题"),
                row("A", "a1", "assistant", "旧 partial"),
            )
            viewModel.openConversation("A")
            awaitState { s -> s.messages.firstOrNull { it.id == "a1" }?.content == "流式正文" }

            // 旧 attempt 的迟到 DB 行到达：当前轮仍在跑，正文不被旧行回退。
            messagesA.value = listOf(
                row("A", "u1", "user", "问题"),
                row("A", "a1", "assistant", "迟到的DB片段"),
            )
            settleRealtime()
            assertEquals(
                "运行中旧行不得回退 overlay",
                "流式正文",
                viewModel.state.value.messages.first { it.id == "a1" }.content,
            )

            // 新 attempt t2 开跑：overlay 切到新 attempt 的 partial。
            activeState.value = AssistantGenerationManager.ActiveState(
                requestId = "r1",
                attemptId = "t2",
                conversationId = "A",
                phase = AssistantRequestStatus.RUNNING,
                answerMessageId = "a1",
                partialText = "新一轮正文",
            )
            messagesA.value = listOf(
                row("A", "u1", "user", "问题"),
                row("A", "a1", "assistant", "迟到的DB片段2"),
            )
            awaitState { s -> s.messages.firstOrNull { it.id == "a1" }?.content == "新一轮正文" }

            // 全部收尾：active 清空，DB 终态（更短）权威生效。
            activeState.value = AssistantGenerationManager.ActiveState()
            messagesA.value = listOf(
                row("A", "u1", "user", "问题"),
                row("A", "a1", "assistant", "终"),
            )
            awaitState { s -> s.messages.firstOrNull { it.id == "a1" }?.content == "终" }
            assertFalse(viewModel.state.value.busy)
        }

    // ---------------- 场景 4：仅凭 DB 终态恢复（错过 Completed 事件） ----------------

    @Test
    fun `db terminal rows alone restore text images and plan plus english envelope`() =
        runTest(dispatcher) {
            messagesA.value = listOf(
                row("A", "u1", "user", "帮我调整计划", imagePaths = """["/history/x.png"]"""),
                row(
                    conversationId = "A",
                    id = "a1",
                    role = "assistant",
                    content = "已按你的要求整理方案",
                    imagePaths = """["/fig/a.png","/fig/b.png"]""",
                    pendingReview = envelope,
                ),
            )
            // 全程没有任何 Completed 事件：只靠 DB 流恢复。
            viewModel.openConversation("A")
            awaitState { s -> s.pendingActions.size == 1 && s.pendingEnglishActions.size == 1 }
            val answer = viewModel.state.value.messages.first { it.id == "a1" }
            assertEquals("已按你的要求整理方案", answer.content)
            assertEquals("图片槽位按 DB 恢复", listOf("/fig/a.png", "/fig/b.png"), answer.imagePaths)
            assertEquals("批次 owner 是固定消息 id", "a1", viewModel.state.value.pendingActionsOwnerMessageId)
            assertFalse(viewModel.state.value.busy)
        }

    @Test
    fun `empty envelope clears all old preview state and stays cleared`() = runTest(dispatcher) {
        messagesA.value = listOf(
            row("A", "u1", "user", "改计划"),
            row("A", "a1", "assistant", "方案如下", pendingReview = envelope),
        )
        viewModel.openConversation("A")
        awaitState { s -> s.pendingActions.size == 1 }

        // 用户放弃/信封已清空：DB 流发射**空信封**行 ⇒ 清除分支必须可达。
        messagesA.value = listOf(
            row("A", "u1", "user", "改计划"),
            row("A", "a1", "assistant", "方案如下"),
        )
        awaitState { s -> s.pendingActions.isEmpty() && s.pendingEnglishActions.isEmpty() }
        assertNull(viewModel.state.value.pendingActionsOwnerIndex)
        assertNull(viewModel.state.value.pendingActionsOwnerMessageId)
        assertNull(viewModel.state.value.pendingEnglishOwnerIndex)
        assertEquals(0, viewModel.state.value.planReviewAppliedCount)
        assertFalse(viewModel.state.value.planReviewOpen)
        assertFalse(viewModel.state.value.englishReviewOpen)

        // 再次发射空行：保持清空，不复活。
        messagesA.value = listOf(row("A", "a1", "assistant", "方案如下"))
        settleRealtime()
        assertTrue(viewModel.state.value.pendingActions.isEmpty())
        assertTrue(viewModel.state.value.pendingEnglishActions.isEmpty())
    }

    // ---------------- 场景 6：A/B/A 迟到 DB 恢复不得污染新页面 ----------------

    @Test
    fun `late a-read after opening b does not pollute b page`() = runTest(dispatcher) {
        messagesA.value = listOf(
            row("A", "ua", "user", "A的问题"),
            row("A", "aa", "assistant", "A的回答", pendingReview = envelope),
        )
        // 挡住 A 的 messages() 读取，制造「A 迟到」窗口。
        val gate = CompletableDeferred<Unit>()
        gateForMessagesA = gate
        viewModel.openConversation("A")
        runCurrent() // A 的恢复协程挂在 gate 上
        viewModel.openConversation("B")
        awaitState { it.currentConversationId == "B" }
        assertTrue(viewModel.state.value.messages.isEmpty())

        // 释放 A 的读取：导航 epoch / 会话围栏必须拦下 A 的迟到恢复。
        gate.complete(Unit)
        settleRealtime()
        assertEquals("B", viewModel.state.value.currentConversationId)
        assertTrue("A 的迟到消息不得进 B 页面", viewModel.state.value.messages.isEmpty())
        assertTrue("A 的迟到信封不得进 B 页面", viewModel.state.value.pendingActions.isEmpty())
        assertTrue(viewModel.state.value.pendingEnglishActions.isEmpty())
        assertNull(viewModel.state.value.pendingActionsOwnerMessageId)
    }

    @Test
    fun `late a-read after startNewConversation does not pollute new page`() = runTest(dispatcher) {
        messagesA.value = listOf(row("A", "ua", "user", "A的问题"))
        val gate = CompletableDeferred<Unit>()
        gateForMessagesA = gate
        viewModel.openConversation("A")
        runCurrent()
        viewModel.startNewConversation()
        awaitState { it.currentConversationId == null }
        assertTrue(viewModel.state.value.messages.isEmpty())

        gate.complete(Unit)
        settleRealtime()
        assertNull("新对话页面不得被 A 的迟到恢复污染", viewModel.state.value.currentConversationId)
        assertTrue(viewModel.state.value.messages.isEmpty())
        assertTrue(viewModel.state.value.pendingActions.isEmpty())
        assertTrue(viewModel.state.value.pendingEnglishActions.isEmpty())
    }

    // ---------------- reconcile 围栏：不得覆盖新任务的 busy ----------------

    @Test
    fun `late reconcile after a new turn started does not clear busy`() = runTest(dispatcher) {
        messagesA.value = listOf(
            row("A", "u1", "user", "问题"),
            row("A", "a1", "assistant", "部分正文"),
        )
        viewModel.openConversation("A")
        awaitState { s -> s.messages.firstOrNull { it.id == "a1" } != null }

        // 新一轮 Started（r2/a2）锁定 busy。
        activeState.value = AssistantGenerationManager.ActiveState(
            requestId = "r2", attemptId = "t2", conversationId = "A",
            answerMessageId = "a2", phase = AssistantRequestStatus.RUNNING,
        )
        events.tryEmit(
            AssistantGenerationManager.GenerationEvent.Started(
                requestId = "r2", attemptId = "t2", conversationId = "A", answerMessageId = "a2",
            ),
        )
        awaitState { it.busy }

        // 本轮收尾：active 清空触发 reconcile；读取被门闩挂起。
        val gate = CompletableDeferred<Unit>()
        gateForMessagesA = gate
        activeState.value = AssistantGenerationManager.ActiveState()
        runCurrent() // reconcile 挂在 gate 上
        // 挂起期间新一轮（r3）已在管理器开跑。
        activeState.value = AssistantGenerationManager.ActiveState(
            requestId = "r3",
            attemptId = "t3",
            conversationId = "A",
            phase = AssistantRequestStatus.RUNNING,
            answerMessageId = "a3",
            partialText = "",
        )
        awaitState { it.busy }

        // 释放 A 的迟到恢复：isRunning 围栏必须放弃，不得把新任务压回非 busy。
        gate.complete(Unit)
        settleRealtime()
        assertEquals("A", viewModel.state.value.currentConversationId)
        assertTrue("新任务的 busy 不得被迟到的 reconcile 清掉", viewModel.state.value.busy)
    }

    // ================= Batch B：retry / generation event fences =================

    // ---------------- B1：重试入口只认「最新用户问题」的中断 ----------------

    /**
     * Batch B「refreshRetryEntry … checks it belongs to the latest user message」：
     * 会话里有一条旧的中断请求，之后用户又发了一个**成功**的新问题。
     * 旧中断必须从重试入口里隐藏（重放它会插队到新问题之前）。
     */
    @Test
    fun `interrupted request older than the latest user question is not offered for retry`() =
        runTest(dispatcher) {
            messagesA.value = listOf(
                row("A", "u-old", "user", "旧问题"),
                row("A", "a-old", "assistant", "旧回答中断了"),
                row("A", "u-new", "user", "新问题"),
                row("A", "a-new", "assistant", "新回答已完成"),
            )
            requestsByConversation["A"] = listOf(
                requestRow("req-old", "A", userMessageId = "u-old", answerMessageId = "a-old"),
            )
            viewModel.openConversation("A")
            awaitState { it.currentConversationId == "A" }
            // 等待请求表读取与入口刷新真实落地。
            settleRealtime()
            assertNull(
                "旧中断在更新提问之后不得再进重试入口（retryRequestId=${viewModel.state.value.retryRequestId}）",
                viewModel.state.value.retryRequestId,
            )
        }

    /**
     * 对照组：同一个中断请求**确实是**最新用户问题时，入口必须照常出现。
     */
    @Test
    fun `interrupted request of the latest user question keeps the retry entry`() =
        runTest(dispatcher) {
            messagesA.value = listOf(
                row("A", "u1", "user", "问题"),
                row("A", "a1", "assistant", "回答中断了"),
            )
            requestsByConversation["A"] = listOf(
                requestRow("req-1", "A", userMessageId = "u1", answerMessageId = "a1"),
            )
            viewModel.openConversation("A")
            awaitState { it.currentConversationId == "A" }
            settleRealtime()
            assertEquals("req-1", viewModel.state.value.retryRequestId)
            assertEquals("a1", viewModel.state.value.retryAnswerMessageId)
        }

    /**
     * 迟到的请求表读取同样受导航围栏约束：A/B/A 中先打开 A（请求表被门闩挂起），
     * 切到 B 后释放 A —— A 的旧中断不得把重试入口写回当前页面。
     */
    @Test
    fun `late request-table read cannot restore retry entry on another page`() =
        runTest(dispatcher) {
            requestsByConversation["A"] = listOf(
                requestRow("req-a", "A", userMessageId = "u-a", answerMessageId = "a-a"),
            )
            messagesA.value = listOf(
                row("A", "u-a", "user", "A的问题"),
                row("A", "a-a", "assistant", "A的回答"),
            )
            val gate = CompletableDeferred<Unit>()
            gateForRequestsA = gate
            viewModel.openConversation("A")
            runCurrent() // A 的恢复链挂在请求表门闩上
            viewModel.openConversation("B")
            awaitState { it.currentConversationId == "B" }

            gate.complete(Unit)
            settleRealtime()
            assertEquals("B", viewModel.state.value.currentConversationId)
            assertNull(
                "A 的迟到请求表读取不得把重试入口带进 B 页面",
                viewModel.state.value.retryRequestId,
            )
        }

    /**
     * retryInterrupted 的最新问题围栏：入口已显示时用户又发了新问题（DB 更新），
     * 再点重试必须被拒绝、清掉入口，且不得调用 beginRetry / submitRetry。
     */
    @Test
    fun `retry click after a newer user question is rejected without submitting`() =
        runTest(dispatcher) {
            messagesA.value = listOf(
                row("A", "u1", "user", "原问题"),
                row("A", "a1", "assistant", ""),
            )
            requestsByConversation["A"] = listOf(
                requestRow("req-1", "A", userMessageId = "u1", answerMessageId = "a1"),
            )
            viewModel.openConversation("A")
            awaitState { it.currentConversationId == "A" }
            settleRealtime()
            assertEquals("req-1", viewModel.state.value.retryRequestId)

            // 用户又发了一个新问题：最新 user 行已是另一条消息。
            messagesA.value = listOf(
                row("A", "u1", "user", "原问题"),
                row("A", "a1", "assistant", ""),
                row("A", "u2", "user", "新问题"),
                row("A", "a2", "assistant", ""),
            )
            settleRealtime()

            viewModel.retryInterrupted()
            runToIdle()
            assertNull(
                "被拒后重试入口必须清掉",
                viewModel.state.value.retryRequestId,
            )
            assertFalse(viewModel.state.value.busy)
            assertFalse(viewModel.state.value.retrying)
            coVerify(exactly = 0) { requestRepository.beginRetry(any(), any(), any(), any()) }
            coVerify(exactly = 0) { generationManager.submitRetry(any(), any(), any(), any(), any(), any(), any()) }
        }

    private fun seedRetry() {
        resolvedIdentityForPinned = pinnedProfileFixture().second
        messagesA.value = listOf(row("A", "u1", "user", "原问题"), row("A", "a1", "assistant", ""))
        requestsByConversation["A"] = listOf(requestRow(
            "req-1", "A", userMessageId = "u1", answerMessageId = "a1",
            snapshot = pinnedSnapshot(primaryProfileId = "profile-pinned").copy(sourceContext = "已删除的旧计划"),
        ).copy(partialText = "已生成片段"))
        viewModel.openConversation("A")
        runToIdle()
        assertEquals("req-1", viewModel.state.value.retryRequestId)
    }

    @Test fun `rejected retry handoff restores interrupted status and partial`() = runTest(dispatcher) {
        seedRetry()
        coEvery { generationManager.submitRetry(any(), any(), any(), any(), any(), any(), any()) } throws
            AssistantGenerationManager.AlreadyRunningException()
        viewModel.retryInterrupted(); runToIdle()
        val record = requestsByConversation.getValue("A").single()
        assertEquals("INTERRUPTED", record.status)
        assertEquals("已生成片段", record.partialText)
        assertFalse(viewModel.state.value.retrying)
        coVerify(exactly = 1) {
            requestRepository.interrupt("req-1", record.attemptId, "已生成片段", any(), any())
        }
    }

    @Test fun `disposing page during unadopted retry handoff settles attempt`() = runTest(dispatcher) {
        seedRetry()
        val gate = CompletableDeferred<Unit>()
        coEvery { generationManager.submitRetry(any(), any(), any(), any(), any(), any(), any()) } coAnswers { gate.await() }
        viewModel.retryInterrupted(); runToIdle()
        assertEquals("PREPARING", requestsByConversation.getValue("A").single().status)
        store.clear(); runToIdle()
        assertEquals("INTERRUPTED", requestsByConversation.getValue("A").single().status)
        assertEquals("已生成片段", requestsByConversation.getValue("A").single().partialText)
    }

    @Test fun `cancellation after retry transaction commits still settles its attempt`() = runTest(dispatcher) {
        seedRetry()
        val gate = CompletableDeferred<Unit>()
        coEvery { requestRepository.beginRetry(any(), any(), any(), any()) } coAnswers {
            val next = requestsByConversation.getValue("A").single().copy(
                attemptId = secondArg(), status = "PREPARING",
            )
            requestsByConversation["A"] = listOf(next)
            gate.await()
            next
        }
        viewModel.retryInterrupted(); runToIdle()
        assertEquals("PREPARING", requestsByConversation.getValue("A").single().status)
        store.clear(); runToIdle()
        assertEquals("INTERRUPTED", requestsByConversation.getValue("A").single().status)
        assertEquals("已生成片段", requestsByConversation.getValue("A").single().partialText)
        coVerify(exactly = 0) { generationManager.submitRetry(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `disposing page after manager adoption does not interrupt its retry`() = runTest(dispatcher) {
        seedRetry()
        val gate = CompletableDeferred<Unit>()
        coEvery { generationManager.submitRetry(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            activeState.value = AssistantGenerationManager.ActiveState(
                requestId = firstArg(), attemptId = secondArg(), conversationId = "A",
                answerMessageId = "a1", phase = AssistantRequestStatus.RUNNING,
            )
            gate.await()
        }
        viewModel.retryInterrupted(); runToIdle()
        store.clear(); runToIdle()
        coVerify(exactly = 0) { requestRepository.interrupt(any(), any(), any(), any(), any()) }
        assertTrue(activeState.value.isRunning)
    }

    @Test fun `retry validates context before claiming attempt and keeps valid empty context`() = runTest(dispatcher) {
        seedRetry()
        val gate = CompletableDeferred<Unit>()
        coEvery { contextBuilder.build(any(), any()) } coAnswers { gate.await(); "" }
        viewModel.retryInterrupted(); runToIdle()
        coVerify(exactly = 0) { requestRepository.beginRetry(any(), any(), any(), any()) }
        viewModel.updateInput("生成期间的新草稿")
        viewModel.retryInterrupted(); runToIdle()
        gate.complete(Unit); runToIdle()
        coVerify(exactly = 1) { generationManager.submitRetry(any(), any(), any(), any(), any(), "", any()) }
        assertEquals("生成期间的新草稿", viewModel.state.value.input)
    }

    @Test fun `retry history read failure releases retry flag and preserves partial`() = runTest(dispatcher) {
        seedRetry()
        coEvery { chatRepository.messages("A") } throws java.io.IOException("synthetic read failure")
        viewModel.retryInterrupted(); runToIdle()
        assertFalse(viewModel.state.value.retrying)
        assertTrue(viewModel.state.value.error != null)
        assertEquals("INTERRUPTED", requestsByConversation.getValue("A").single().status)
        coVerify(exactly = 0) { requestRepository.beginRetry(any(), any(), any(), any()) }
    }

    @Test fun `deleted pinned profile is rejected even if current profile could match`() = runTest(dispatcher) {
        seedRetry()
        resolvedIdentityForPinned = null
        viewModel.retryInterrupted(); runToIdle()
        assertFalse(viewModel.state.value.retrying)
        assertTrue(viewModel.state.value.error != null)
        coVerify(exactly = 0) { requestRepository.beginRetry(any(), any(), any(), any()) }
    }

    @Test fun `late started after manager finished cannot resurrect busy`() = runTest(dispatcher) {
        messagesA.value = listOf(row("A", "u1", "user", "问题"), row("A", "a1", "assistant", "最终答案"))
        viewModel.openConversation("A"); runToIdle()
        events.tryEmit(AssistantGenerationManager.GenerationEvent.Started("r1", "t1", "A", "a1"))
        runToIdle()
        assertFalse(viewModel.state.value.busy)
        assertEquals("最终答案", viewModel.state.value.messages.last().content)
    }

    @Test fun `queued incremental notifications neither duplicate reasoning nor roll back text`() = runTest(dispatcher) {
        messagesA.value = listOf(row("A", "u1", "user", "问题"), row("A", "a1", "assistant", ""))
        viewModel.openConversation("A"); runToIdle()
        activeState.value = AssistantGenerationManager.ActiveState(
            requestId = "r1", attemptId = "t1", conversationId = "A", answerMessageId = "a1",
            phase = AssistantRequestStatus.RUNNING, reasoning = "完整思考", partialText = "较新的完整正文",
            answerStarted = true,
        )
        runToIdle()
        events.tryEmit(AssistantGenerationManager.GenerationEvent.Reasoning("r1", "t1", "A", "完整思考"))
        events.tryEmit(AssistantGenerationManager.GenerationEvent.AnswerDelta("r1", "t1", "A", "旧", "旧正文"))
        runToIdle()
        assertEquals("完整思考", viewModel.state.value.activeReasoning)
        assertEquals("较新的完整正文", viewModel.state.value.messages.last().content)
    }

    // ---------------- B2：重试身份 = 钉住的档案，与当前活动档案无关 ----------------

    /**
     * Batch B「retryIdentityMismatch must resolve snapshot.primaryProfileId with
     * AiCredentialStore.resolveIdentityFor」：快照钉住了档案 P（Responses 协议），
     * 用户已把**活动档案**切到别处。重试仍按钉住档案校验：一致 ⇒ 放行。
     *
     * 这是 Batch B 的目标行为；当前生产实现读的是活动档案，活动档案失配 ⇒
     * 该测试在生产补上钉住档案解析前会失败（记录为生产缺口，而不是放松断言）。
     */
    @Test
    fun `pinned profile identity passes validation even when active profile moved elsewhere`() =
        runTest(dispatcher) {
            val (profile, identity) = pinnedProfileFixture("profile-pinned")
            activeProfile = AiModelProfile(
                id = "profile-other",
                name = "别的档案",
                baseUrl = "https://other.example.com/v1",
                model = "other-model",
                visionEnabled = false,
                searchProtocol = AiSearchProtocol.CHAT_COMPLETIONS,
                reasoningEffort = com.example.lixing.data.assistant.AiReasoningEffort.LOW,
                hasApiKey = true,
            )
            resolvedIdentityForPinned = identity
            prefsForRetry = UserPreferences(
                aiAssistantEnabled = true,
                aiBaseUrl = "https://legacy.example.com/v1",
                aiModel = "legacy-model",
            )
            requestsByConversation["A"] = listOf(
                requestRow(
                    "req-1",
                    "A",
                    userMessageId = "u1",
                    answerMessageId = "a1",
                    snapshot = pinnedSnapshot(primaryProfileId = profile.id),
                ),
            )
            messagesA.value = listOf(
                row("A", "u1", "user", "问题"),
                row("A", "a1", "assistant", ""),
            )
            viewModel.openConversation("A")
            awaitState { it.currentConversationId == "A" }
            settleRealtime()

            viewModel.retryInterrupted()
            runToIdle()

            assertNull(
                "钉住档案身份一致 ⇒ 重试必须放行（error=${viewModel.state.value.error}）",
                viewModel.state.value.error,
            )
            assertTrue(
                "放行 ⇒ 提交后 busy（snapshot 走 submitRetry 锁住生成）",
                viewModel.state.value.busy,
            )
            coVerify(exactly = 1) {
                generationManager.submitRetry(
                    requestId = "req-1",
                    attemptId = any(),
                    history = any(),
                    attachmentPaths = any(),
                    refreshedContext = any(),
                    userText = any(),
                    forceWebSearch = any(),
                )
            }
        }

    /**
     * 钉住档案自身失配（模型名被改）⇒ 必须拒绝并给出一致的具体说明。
     */
    @Test
    fun `changed pinned profile model is rejected with a specific mismatch message`() =
        runTest(dispatcher) {
            val (profile, identity) = pinnedProfileFixture("profile-pinned")
            activeProfile = profile
            resolvedIdentityForPinned = identity.copy(model = "renamed-model")
            prefsForRetry = UserPreferences(
                aiAssistantEnabled = true,
                aiBaseUrl = "https://legacy.example.com/v1",
                aiModel = "legacy-model",
            )
            requestsByConversation["A"] = listOf(
                requestRow(
                    "req-1",
                    "A",
                    userMessageId = "u1",
                    answerMessageId = "a1",
                    snapshot = pinnedSnapshot(primaryProfileId = profile.id),
                ),
            )
            messagesA.value = listOf(
                row("A", "u1", "user", "问题"),
                row("A", "a1", "assistant", ""),
            )
            viewModel.openConversation("A")
            awaitState { it.currentConversationId == "A" }
            settleRealtime()

            viewModel.retryInterrupted()
            runToIdle()

            assertFalse(viewModel.state.value.retrying)
            assertTrue(
                "钉住档案模型变更必须被拒绝（error=${viewModel.state.value.error}）",
                viewModel.state.value.error?.contains("renamed-model") == true,
            )
            coVerify(exactly = 0) { generationManager.submitRetry(any(), any(), any(), any(), any(), any(), any()) }
        }

    // ---------------- B3：陈旧 Started / Completed 事件围栏 ----------------

    /**
     * Batch B「Started must match manager's CURRENT request+attempt+conversation,
     * not only tracked requestId」—— 场景 8 的 Started 半部：
     * 管理器已开跑 attempt2，此时到达 attempt1 的迟到 Started。
     * 它不得把跟踪身份/回答位改回旧 attempt（否则后续增量会写错气泡）。
     */
    @Test
    fun `stale started for old attempt does not reset tracking while attempt2 runs`() =
        runTest(dispatcher) {
            messagesA.value = listOf(
                row("A", "u1", "user", "问题"),
                row("A", "a1", "assistant", ""),
            )
            viewModel.openConversation("A")
            awaitState { it.currentConversationId == "A" }

            // 管理器开跑 attempt2（可重放状态先到，Started 事件后到）。
            activeState.value = AssistantGenerationManager.ActiveState(
                requestId = "r1",
                attemptId = "t2",
                conversationId = "A",
                phase = AssistantRequestStatus.RUNNING,
                answerMessageId = "a1",
                partialText = "attempt2 正文",
            )
            awaitState { it.busy }
            events.tryEmit(
                AssistantGenerationManager.GenerationEvent.Started(
                    requestId = "r1", attemptId = "t2", conversationId = "A", answerMessageId = "a1",
                ),
            )
            awaitState { s -> s.messages.firstOrNull { it.id == "a1" }?.content == "attempt2 正文" }

            // attempt1 的迟到 Started：不得改写围栏身份，也不得动正文。
            events.tryEmit(
                AssistantGenerationManager.GenerationEvent.Started(
                    requestId = "r1", attemptId = "t1", conversationId = "A", answerMessageId = "a1",
                ),
            )
            settleRealtime()
            // 正确围栏下，attempt1 的事件此后一律被拒：用一条 attempt1 的增量验证。
            events.tryEmit(
                AssistantGenerationManager.GenerationEvent.AnswerDelta(
                    requestId = "r1", attemptId = "t1", conversationId = "A",
                    text = "旧增量", accumulated = "旧增量",
                ),
            )
            settleRealtime()
            assertEquals(
                "旧 attempt 的迟到 Started 不得改回围栏身份，后续增量也不得入正文",
                "attempt2 正文",
                viewModel.state.value.messages.first { it.id == "a1" }.content,
            )
            assertTrue("新 attempt 仍在跑，busy 不得被动", viewModel.state.value.busy)
        }

    /**
     * 陈旧 Completed（同会话旧 attempt）不得越过新 attempt 收尾：
     * 管理器已开跑 attempt2 期间，attempt1 的迟到 Completed 到达 ——
     * busy 必须保持（新 attempt 未结束），正文不得被旧结果改写。
     */
    @Test
    fun `stale completed from old attempt does not end the newer attempt`() =
        runTest(dispatcher) {
            messagesA.value = listOf(
                row("A", "u1", "user", "问题"),
                row("A", "a1", "assistant", "旧 partial"),
            )
            viewModel.openConversation("A")
            awaitState { it.currentConversationId == "A" }

            activeState.value = AssistantGenerationManager.ActiveState(
                requestId = "r1",
                attemptId = "t2",
                conversationId = "A",
                phase = AssistantRequestStatus.RUNNING,
                answerMessageId = "a1",
                partialText = "attempt2 正文",
            )
            awaitState { it.busy }
            events.tryEmit(
                AssistantGenerationManager.GenerationEvent.Started(
                    requestId = "r1", attemptId = "t2", conversationId = "A", answerMessageId = "a1",
                ),
            )
            awaitState { s -> s.messages.firstOrNull { it.id == "a1" }?.content == "attempt2 正文" }

            events.tryEmit(
                AssistantGenerationManager.GenerationEvent.Completed(
                    requestId = "r1",
                    attemptId = "t1",
                    conversationId = "A",
                    answerMessageId = "a1",
                    result = com.example.lixing.data.assistant.ParsedAssistantReply(
                        reply = "旧 attempt 的最终回答",
                        actions = emptyList(),
                        warnings = emptyList(),
                    ),
                    timings = com.example.lixing.data.assistant.GenerationTimings(completedMs = 1L),
                    mergedText = "旧 attempt 的最终回答",
                ),
            )
            settleRealtime()
            assertTrue(
                "旧 attempt 的 Completed 不得结束仍在跑的新 attempt",
                viewModel.state.value.busy,
            )
            assertEquals(
                "旧 attempt 的最终回答不得改写新 attempt 的正文",
                "attempt2 正文",
                viewModel.state.value.messages.first { it.id == "a1" }.content,
            )
            assertNull("旧 Completed 不得清掉新 attempt 的事件围栏", viewModel.state.value.error)
        }

    /**
     * 旧会话的 Completed 不得清掉新任务的界面状态（Batch B「Do not clear new
     * task's active IDs on old completion」）：r1 属于 A，在 B 页面收到 ——
     * B 的 busy / 围栏身份不受影响。
     */
    @Test
    fun `completed for another conversation does not clear new task flags`() =
        runTest(dispatcher) {
            messagesA.value = listOf(
                row("A", "u1", "user", "A的问题"),
                row("A", "a1", "assistant", ""),
            )
            viewModel.openConversation("A")
            awaitState { it.currentConversationId == "A" }
            activeState.value = AssistantGenerationManager.ActiveState(
                requestId = "r1", attemptId = "t1", conversationId = "A",
                answerMessageId = "a1", phase = AssistantRequestStatus.RUNNING,
            )
            events.tryEmit(
                AssistantGenerationManager.GenerationEvent.Started(
                    requestId = "r1", attemptId = "t1", conversationId = "A", answerMessageId = "a1",
                ),
            )
            awaitState { it.busy }

            // A is still generating; B must not show A's local busy indicator.
            viewModel.openConversation("B")
            awaitState { it.currentConversationId == "B" }
            assertFalse(viewModel.state.value.busy)

            // 用户在 B 开新一轮：围栏身份切到 r2/t2。
            activeState.value = AssistantGenerationManager.ActiveState(
                requestId = "r2", attemptId = "t2", conversationId = "B",
                answerMessageId = "a-b", phase = AssistantRequestStatus.RUNNING,
            )
            events.tryEmit(
                AssistantGenerationManager.GenerationEvent.Started(
                    requestId = "r2", attemptId = "t2", conversationId = "B", answerMessageId = "a-b",
                ),
            )
            awaitState { s -> s.busy }

            // A 的 r1/t1 迟到 Completed：不属于当前跟踪轮 ⇒ 不得清 B 的 busy/围栏。
            events.tryEmit(
                AssistantGenerationManager.GenerationEvent.Completed(
                    requestId = "r1",
                    attemptId = "t1",
                    conversationId = "A",
                    answerMessageId = "a1",
                    result = com.example.lixing.data.assistant.ParsedAssistantReply(
                        reply = "A 的最终回答", actions = emptyList(), warnings = emptyList(),
                    ),
                    timings = com.example.lixing.data.assistant.GenerationTimings(completedMs = 1L),
                    mergedText = "A 的最终回答",
                ),
            )
            settleRealtime()
            assertTrue("B 的新任务 busy 不得被旧会话 Completed 清掉", viewModel.state.value.busy)
            assertEquals("B", viewModel.state.value.currentConversationId)
        }

    // ---------------- B4：确认事务 A/B/A：迟到结果不得污染新页面 ----------------

    /** 构造「A 页面上有一批待确认方案」的最小 DB 状态。 */
    private fun seedPlanBatchOnA() {
        coEvery { planRepository.getTimeSlot("slot-1") } returns com.example.lixing.data.local.entity.TimeSlotEntity(
            id = "slot-1", planId = "synthetic-plan", name = "测试时段",
            startTime = LocalTime.of(8, 0), endTime = LocalTime.of(10, 0),
        )
        messagesA.value = listOf(
            row("A", "u1", "user", "帮我调整计划"),
            row("A", "a1", "assistant", "方案如下", pendingReview = envelope),
        )
    }

    /**
     * 场景 9（计划半部）：在 A 确认计划（事务被门闩挂起），切到 B ——
     * B 页面有**自己的**新批次；释放 A 的成功结果后，
     * B 的 applying / 批次状态一个字段都不得被 A 的迟到收尾改掉。
     */
    @Test
    fun `late plan confirm success after navigation does not pollute the new page`() =
        runTest(dispatcher) {
            seedPlanBatchOnA()
            viewModel.openConversation("A")
            awaitState { s -> s.pendingActions.size == 1 && s.pendingActionsOwnerMessageId == "a1" }

            val gate = CompletableDeferred<Unit>()
            val reviewTransactionsSlot = mockk<com.example.lixing.data.repository.AssistantReviewTransactionRepository> {
                coEvery { applyPlan(any(), any(), any(), any(), any()) } coAnswers {
                    gate.await()
                    PlanReviewTransactionResult.Applied(
                        listOf(
                            PlanApplyResult(
                                action = PlanAction.UpdateTimeSlot(
                                    slotId = "slot-1",
                                    startTime = null,
                                    endTime = LocalTime.of(9, 0),
                                    reason = "提前结束",
                                ),
                                success = true,
                                message = "ok",
                            ),
                        ),
                    )
                }
            }
            // 重新装配 VM：只有 reviewTransactions 换成可控门闩（其余照 setUp）。
            swapReviewTransactions(reviewTransactionsSlot)

            viewModel.applySelected()
            runCurrent() // 确认协程挂在事务门闩上
            assertTrue("点击瞬间 applying 必须置位", viewModel.state.value.applying)

            // 导航到 B：A 的确认操作立即失去页面归属。
            viewModel.openConversation("B")
            awaitState { it.currentConversationId == "B" }
            viewModel.updateInput("B 页面的新输入")
            runCurrent()

            gate.complete(Unit)
            settleRealtime()
            assertEquals("B", viewModel.state.value.currentConversationId)
            assertFalse("迟到成功不得动新页面的 applying", viewModel.state.value.applying)
            assertTrue(
                "迟到成功不得把 A 的确认文案带进 B",
                viewModel.state.value.applyMessage == null ||
                    viewModel.state.value.applyMessage?.contains("应用失败") == true,
            )
            assertEquals(
                "B 自己的输入不受影响",
                "B 页面的新输入",
                viewModel.state.value.input,
            )
        }

    /**
     * 场景 9（英语半部 + 旧数据路径）：同一信封里的英语批次在 A 确认后迟到返回，
     * 同样不得污染 B；另外验证**旧数据无批次 id** 的降级路径只释放自己的
     * applyingEnglish 标志（English 半部 busy）。
     */
    @Test
    fun `late english confirm result after navigation leaves the new page untouched`() =
        runTest(dispatcher) {
            seedPlanBatchOnA()
            viewModel.openConversation("A")
            awaitState { s -> s.pendingEnglishActions.size == 1 && s.pendingActionsOwnerMessageId == "a1" }

            val gate = CompletableDeferred<Unit>()
            val reviewTransactionsSlot = mockk<com.example.lixing.data.repository.AssistantReviewTransactionRepository> {
                coEvery { applyEnglish(any(), any(), any()) } coAnswers {
                    gate.await()
                    com.example.lixing.data.repository.EnglishReviewTransactionResult.Applied(
                        com.example.lixing.data.repository.EnglishApplyOutcome(added = 1, updated = 0, deleted = 0),
                    )
                }
            }
            swapReviewTransactions(reviewTransactionsSlot)

            viewModel.applySelectedEnglish()
            runCurrent()
            assertTrue(viewModel.state.value.applyingEnglish)

            viewModel.openConversation("B")
            awaitState { it.currentConversationId == "B" }

            gate.complete(Unit)
            settleRealtime()
            assertFalse("迟到英语成功不得动新页面", viewModel.state.value.applyingEnglish)
            assertEquals("B", viewModel.state.value.currentConversationId)
        }

    @Test
    fun `english confirmation releases busy after its transaction completes`() = runTest(dispatcher) {
        seedPlanBatchOnA()
        viewModel.openConversation("A")
        awaitState { it.pendingEnglishActions.size == 1 }
        val gate = CompletableDeferred<Unit>()
        val boundary = mockk<com.example.lixing.data.repository.AssistantReviewTransactionRepository> {
            coEvery { applyEnglish(any(), any(), any()) } coAnswers {
                gate.await()
                com.example.lixing.data.repository.EnglishReviewTransactionResult.Applied(
                    com.example.lixing.data.repository.EnglishApplyOutcome(1, 0, 0),
                )
            }
        }
        swapReviewTransactions(boundary)
        viewModel.applySelectedEnglish(); runCurrent()
        assertTrue(viewModel.state.value.applyingEnglish)
        gate.complete(Unit); runToIdle()
        assertFalse(viewModel.state.value.applyingEnglish)
        assertTrue(viewModel.state.value.applyMessage?.contains("英语积累已更新") == true)
    }

    // ---------------- B5：确认 + 生成并存的 A/B/A 迟到结果 ----------------

    /**
     * Batch B「Navigation invalidates old operation tokens so A/B/A cannot accept
     * old results」的组合案例：A 的确认挂起期间，用户在 B 又点了一次确认
     * （新 operation token）；释放 A 之后，B 的批次状态必须原样保留。
     */
    @Test
    fun `second confirm on the new page keeps its batch while the first one lands late`() =
        runTest(dispatcher) {
            seedPlanBatchOnA()
            viewModel.openConversation("A")
            awaitState { s -> s.pendingActions.size == 1 }

            val gateA = CompletableDeferred<Unit>()
            val gateB = CompletableDeferred<Unit>()
            var calls = 0
            val reviewTransactionsSlot =
                mockk<com.example.lixing.data.repository.AssistantReviewTransactionRepository> {
                    coEvery { applyPlan(any(), any(), any(), any(), any()) } coAnswers {
                        // 第一次调用（A 的批次）挂 gateA；第二次（B 的批次）挂 gateB。
                        if (++calls == 1) {
                            gateA.await()
                            PlanReviewTransactionResult.Applied(
                                listOf(
                                    PlanApplyResult(
                                        action = PlanAction.UpdateTimeSlot(
                                            slotId = "slot-1",
                                            startTime = null,
                                            endTime = LocalTime.of(9, 0),
                                            reason = "提前结束",
                                        ),
                                        success = true,
                                        message = "ok",
                                    ),
                                ),
                            )
                        } else {
                            gateB.await()
                            PlanReviewTransactionResult.Applied(emptyList())
                        }
                    }
                }
            swapReviewTransactions(reviewTransactionsSlot)

            viewModel.applySelected()
            runCurrent()
            assertTrue(viewModel.state.value.applying)

            // 切到 B：B 页面有新批次（DB 流吐给 B）。
            viewModel.openConversation("B")
            awaitState { it.currentConversationId == "B" }
            messagesB.value = listOf(
                row("B", "u-b", "user", "B的问题"),
                row("B", "a-b", "assistant", "B的方案", pendingReview = envelope),
            )
            awaitState { s -> s.pendingActionsOwnerMessageId == "a-b" }

            // B 上的第二次确认：新 operation token。
            viewModel.applySelected()
            runCurrent()
            assertTrue(viewModel.state.value.applying)

            // 先释放 A 的迟到结果：归属已变 ⇒ 一个字段都不许动。
            gateA.complete(Unit)
            settleRealtime()
            assertTrue("A 的迟到结果不得清掉 B 的 applying", viewModel.state.value.applying)
            assertEquals("a-b", viewModel.state.value.pendingActionsOwnerMessageId)

            // 再释放 B 自己的结果：正常收尾。
            gateB.complete(Unit)
            settleRealtime()
            assertFalse(viewModel.state.value.applying)
            assertEquals(
                "B 的批次归属保持不变",
                "a-b",
                viewModel.state.value.pendingActionsOwnerMessageId,
            )
        }
}
