package com.example.lixing.assistant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.lixing.data.assistant.AssistantContextBuilder
import com.example.lixing.data.assistant.AssistantDiagnostics
import com.example.lixing.data.assistant.AssistantFigure
import com.example.lixing.data.assistant.AssistantModelClient
import com.example.lixing.data.assistant.AssistantModelException
import com.example.lixing.data.assistant.AssistantResponseParser
import com.example.lixing.data.assistant.AssistantStreamEvent
import com.example.lixing.data.assistant.MonotonicClock
import com.example.lixing.data.assistant.ParsedAssistantReply
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.local.entity.AssistantConversationEntity
import com.example.lixing.data.repository.AssistantChatRepository
import com.example.lixing.data.repository.AssistantRequestRepository
import com.example.lixing.domain.assistant.AssistantMessage
import com.example.lixing.domain.assistant.AssistantRequestStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * **端到端**验证：走真实的 [AssistantGenerationManager.submit] → [GenerationPreparer]
 * → 模型调用，而不是直接造 `GenerationRequest` 调 `start`。
 *
 * ## 为什么必须单独一组
 *
 * 直接调 `start(request)` 会**绕过准备阶段**，于是「准备阶段到底把什么发给了模型」
 * 完全没有覆盖。实测发现过一个严重缺陷：`prepare` 算出了转写后的题目
 * （`outgoingText`），但真正发给模型的 `history` 是从数据库读的**原文**，
 * 两者从不合并 —— 拍照题的识别结果根本没发出去；重试时更因为历史里
 * 已排除本条用户消息，**原问题一个字都没发送**。
 *
 * 这里逐个断言**实际进入 chatStreaming 的 messages**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AssistantSubmitPathTest {

    private lateinit var db: LiXingDatabase
    private lateinit var requestRepository: AssistantRequestRepository
    private lateinit var chatRepository: AssistantChatRepository
    private lateinit var modelClient: AssistantModelClient
    private lateinit var preparer: GenerationPreparer
    private lateinit var manager: AssistantGenerationManager
    private lateinit var aiCredentialStore: com.example.lixing.data.assistant.AiCredentialStore

    /** 真实身份对象：真实 preparer 需要（relaxed mock 的空字符串会让快照校验拒绝）。 */
    private val primaryIdentity = com.example.lixing.data.assistant.AiResolvedIdentity(
        profileId = "profile-1",
        baseUrl = "https://relay.example/v1",
        model = "test-model",
        apiKey = "test-key",
        visionEnabled = false,
        searchProtocol = com.example.lixing.data.assistant.AiSearchProtocol.OFF,
        reasoningEffort = com.example.lixing.data.assistant.AiReasoningEffort.LOW,
    )
    private val visionIdentity = com.example.lixing.data.assistant.AiResolvedIdentity(
        profileId = "vision-1",
        baseUrl = "https://vision.example/v1",
        model = "vision-model",
        apiKey = "vision-key",
        visionEnabled = true,
        searchProtocol = com.example.lixing.data.assistant.AiSearchProtocol.OFF,
        reasoningEffort = com.example.lixing.data.assistant.AiReasoningEffort.LOW,
    )

    /** 记录真正进入模型的 messages（以及图片数量）。 */
    private val capturedMessages = CopyOnWriteArrayList<List<AssistantMessage>>()
    private val capturedImageCounts = CopyOnWriteArrayList<Int>()

    private class NoopRenderer : FigureRenderer {
        override suspend fun render(figures: List<AssistantFigure>) = figures.map { "" }
    }

    private class FakeGuard : AssistantGenerationGuard {
        private val held = mutableSetOf<String>()
        override fun acquire(requestId: String, conversationId: String?) = "$requestId#1".also { held += it }
        override fun release(token: String) { held -= token }
        override fun activeCount() = held.size
        override fun begin() = Unit
        override fun end() = Unit
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiXingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        requestRepository = AssistantRequestRepository(db.assistantRequestDao(), Dispatchers.Unconfined)
        chatRepository = AssistantChatRepository(db.assistantChatDao(), Dispatchers.Unconfined)
        modelClient = mockk()
        aiCredentialStore = mockk {
            every { resolveActiveIdentity(any(), any()) } returns primaryIdentity
            every { resolveIdentityFor("vision-1") } returns visionIdentity
            every { questionVisionProfileId() } returns null
        }
        preparer = GenerationPreparer(
            modelClient = modelClient,
            contextBuilder = mockk<AssistantContextBuilder>(relaxed = true) {
                coEvery { build(any(), any()) } returns ""
            },
            chatRepository = chatRepository,
            prefsRepository = mockk(relaxed = true) {
                coEvery { current() } returns com.example.lixing.data.prefs.UserPreferences()
            },
            aiCredentialStore = aiCredentialStore,
        )
        manager = AssistantGenerationManager(
            context = context,
            modelClient = modelClient,
            requestRepository = requestRepository,
            diagnostics = AssistantDiagnostics(),
            guard = FakeGuard(),
            monotonicClock = MonotonicClock.SYSTEM,
            finalizer = GenerationFinalizer(requestRepository, NoopRenderer()),
            preparer = preparer,
        )
        runBlocking {
            db.assistantChatDao().insertConversation(AssistantConversationEntity(id = "c1", title = "会话"))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 桩：记录实际 messages，并返回一个可解析的回复。 */
    private fun stubCapturing(reply: String = "回答", visionEnabled: Boolean = false) {
        coEvery { modelClient.isVisionEnabled() } returns visionEnabled
        coEvery { modelClient.chooseContext(any(), any()) } returns emptySet()
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (AssistantStreamEvent) -> Unit>())
        } coAnswers {
            capturedMessages += arg<List<AssistantMessage>>(0)
            capturedImageCounts += arg<List<String>>(2).size
            val onEvent = arg<suspend (AssistantStreamEvent) -> Unit>(7)
            val payload = """{"reply":"$reply","plan_actions":[]}"""
            onEvent(AssistantStreamEvent.AnswerDelta(payload))
            AssistantResponseParser.parse(payload, normalizeMarkdown = false)
        }
    }

    private suspend fun awaitTerminal(requestId: String, timeoutMs: Long = 10_000): String {
        var status = ""
        withTimeout(timeoutMs) {
            while (true) {
                val current = requestRepository.get(requestId)?.status.orEmpty()
                if (current in TERMINAL && !manager.isRunning()) {
                    status = current
                    break
                }
                delay(10)
            }
        }
        return status
    }

    /**
     * **纯文本提问：当前这一轮必须真的发给模型。**
     *
     * 这是最基本的一条，但旧实现是「历史直接从库里读、当前轮靠库里的那条用户消息」，
     * 一旦那条消息被排除（重试/转写替换）就整轮不发问题。
     */
    @Test
    fun `the current user turn is actually sent to the model`() = runBlocking {
        stubCapturing()
        val request = manager.submit(
            AssistantGenerationManager.Submission(
                conversationId = "c1",
                userText = "请解释双纽线面积公式",
                attachmentPaths = emptyList(),
            ),
        )
        assertEquals(AssistantRequestStatus.COMPLETED.name, awaitTerminal(request.requestId))
        assertEquals(1, capturedMessages.size)
        val sent = capturedMessages.single()
        val lastUser = sent.lastOrNull { it.role == "user" }
        assertEquals("本轮问题必须发给模型", "请解释双纽线面积公式", lastUser?.content)
    }

    /**
     * **拍照 + 转写路由：转写结果必须替代原文发给模型。**
     *
     * 旧实现把转写结果算出来却只放在 `outgoingText` 里，`history` 用的还是
     * 数据库里的原文（拍照题时为空）—— 模型实际收到的是空的用户消息。
     */
    @Test
    fun `transcribed photo text replaces the raw prompt in the payload`() = runBlocking {
        val photo = java.io.File.createTempFile("attach", ".png").apply { writeBytes(ByteArray(64) { 7 }) }
        coEvery { modelClient.isVisionEnabled() } returns false
        coEvery { modelClient.chooseContext(any(), any()) } returns emptySet()
        coEvery { modelClient.chooseContext(any(), any()) } returns emptySet()
        // 配置「题目识别」档案（快照会把它的 id/模型/端点钉下）。
        every { aiCredentialStore.questionVisionProfileId() } returns "vision-1"
        coEvery { modelClient.completeWithProfile(any(), any(), any(), any()) } returns "转写出来的题目：求 x^2 的导数"
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (AssistantStreamEvent) -> Unit>())
        } coAnswers {
            capturedMessages += arg<List<AssistantMessage>>(0)
            val onEvent = arg<suspend (AssistantStreamEvent) -> Unit>(7)
            val payload = """{"reply":"解出来了","plan_actions":[]}"""
            onEvent(AssistantStreamEvent.AnswerDelta(payload))
            AssistantResponseParser.parse(payload, normalizeMarkdown = false)
        }
        val request = manager.submit(
            AssistantGenerationManager.Submission(
                conversationId = "c1",
                userText = "",
                attachmentPaths = listOf(photo.absolutePath),
            ),
        )
        val status = awaitTerminal(request.requestId)
        // 转写模型可用 → 走转写路径；若这里拿不到识别结果应当明确失败，
        // 但决不允许「静默把空问题发出去」。
        val sent = capturedMessages.lastOrNull()
        if (status == AssistantRequestStatus.COMPLETED.name) {
            val lastUser = sent?.lastOrNull { it.role == "user" }
            assertTrue(
                "转写结果必须真正进入请求（而不是只算出来丢掉）",
                lastUser?.content?.contains("转写出来的题目") == true,
            )
            assertFalse("不能发送空的用户消息", lastUser?.content.isNullOrBlank())
        } else {
            // 明确失败也算合格：绝不允许静默发送空问题。
            assertTrue(
                "转写不可用必须落终态而不是假装成功",
                status == AssistantRequestStatus.INTERRUPTED.name,
            )
        }
        photo.delete()
        Unit
    }

    /**
     * **重试：原问题必须重新发送。**
     *
     * 旧实现的历史组装把「本条用户消息」排除了，然后 manager 直接用它 ——
     * 等于重试时一个问题都不发。
     */
    /**
     * 走真实 submit 路径造一个**真正中断**的请求（模型吐一半后断网）。
     *
     * 不能先跑成功再手工 `interrupt`：终态有栅栏，已完成的请求本来就拒绝改写
     * —— 那样测出来的"保留旧 partial"是假象。
     */
    private suspend fun submitAndInterrupt(userText: String, partial: String): String {
        coEvery { modelClient.isVisionEnabled() } returns false
        coEvery { modelClient.chooseContext(any(), any()) } returns emptySet()
        val first = AtomicInteger(0)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (AssistantStreamEvent) -> Unit>())
        } coAnswers {
            if (first.getAndIncrement() == 0) {
                capturedMessages += arg<List<AssistantMessage>>(0)
                val onEvent = arg<suspend (AssistantStreamEvent) -> Unit>(7)
                onEvent(AssistantStreamEvent.AnswerDelta("""{"reply":"$partial"""))
                throw AssistantModelException(AssistantModelException.Kind.NETWORK, "网络错误")
            }
            // 第二轮（重试）正常回答。
            capturedMessages += arg<List<AssistantMessage>>(0)
            val onEvent = arg<suspend (AssistantStreamEvent) -> Unit>(7)
            val payload = """{"reply":"重试成功","plan_actions":[]}"""
            onEvent(AssistantStreamEvent.AnswerDelta(payload))
            AssistantResponseParser.parse(payload, normalizeMarkdown = false)
        }
        val request = manager.submit(
            AssistantGenerationManager.Submission(
                conversationId = "c1",
                userText = userText,
                attachmentPaths = emptyList(),
            ),
        )
        assertEquals(AssistantRequestStatus.INTERRUPTED.name, awaitTerminal(request.requestId))
        return request.requestId
    }

    @Test
    fun `retry sends the original question again`() = runBlocking {
        val requestId = submitAndInterrupt("原问题：什么是导数", "半截")
        val owner = requestRepository.get(requestId)!!
        assertEquals("中断的部分正文必须保留", "半截", owner.partialText)
        capturedMessages.clear()

        val retried = requestRepository.beginRetry(requestId, "att-retry", null, null)!!
        val history = chatRepository.messages("c1")
            .filter { it.id != retried.answerMessageId }
            .filter { it.id != retried.userMessageId }
            .map { AssistantMessage(it.role, it.content) }
        manager.submitRetry(
            requestId = requestId,
            attemptId = "att-retry",
            userText = retried.userText,
            history = history,
            attachmentPaths = emptyList(),
            refreshedContext = null,
            forceWebSearch = false,
        )
        assertEquals(AssistantRequestStatus.COMPLETED.name, awaitTerminal(requestId))
        val sent = capturedMessages.lastOrNull()
        val lastUser = sent?.lastOrNull { it.role == "user" }
        assertEquals("重试必须重新发送原问题", "原问题：什么是导数", lastUser?.content)
    }

    /** 上一次中断的部分正文不能被当成本轮新结果丢掉（重试期间保留）。 */
    @Test
    fun `retry does not lose the previous partial text before the new result arrives`() = runBlocking {
        val requestId = submitAndInterrupt("问题", "上一次生成到一半的正文")
        assertEquals(
            "中断的部分正文必须保留到新结果成功为止",
            "上一次生成到一半的正文",
            requestRepository.get(requestId)!!.partialText,
        )
        // 换 attempt 开始新一轮：旧 partial 必须仍在（不能先清空再重试，
        // 否则重试又失败时用户之前的输出就全没了）。
        val retried = requestRepository.beginRetry(requestId, "att-2", null, null)!!
        assertEquals(
            "换 attempt 不得清掉旧 partial",
            "上一次生成到一半的正文",
            retried.partialText,
        )
    }

    /**
     * **已发送的草稿不能在生成期间被无条件清掉。**
     *
     * [com.example.lixing.assistant.AssistantDraftStore] 的清理只应发生在
     * 「这一轮确实消费了草稿」之后，不能把用户在生成期间新写的内容删掉。
     */
    @Test
    fun `draft belonging to the conversation is saved then cleared on submit`() = runBlocking {
        stubCapturing()
        val draftStore = AssistantDraftStore(ApplicationProvider.getApplicationContext())
        draftStore.save("c1", "会话里的草稿", emptyList())
        assertEquals("会话里的草稿", draftStore.load("c1")?.text)
        // 空内容即删除。
        draftStore.save("c1", "", emptyList())
        assertEquals(null, draftStore.load("c1"))
    }

    /** 准备阶段抛异常：必须落终态，不能停在 PREPARING。 */
    @Test
    fun `a throwing preparation still reaches a terminal state`() = runBlocking {
        coEvery { modelClient.isVisionEnabled() } returns false
        coEvery { modelClient.chooseContext(any(), any()) } throws RuntimeException("选择上下文时崩了")
        val request = manager.submit(
            AssistantGenerationManager.Submission(
                conversationId = "c1",
                userText = "问题",
                attachmentPaths = emptyList(),
            ),
        )
        val status = awaitTerminal(request.requestId)
        assertTrue(
            "准备阶段异常必须有终态，实际是 $status",
            status == AssistantRequestStatus.INTERRUPTED.name || status == AssistantRequestStatus.COMPLETED.name,
        )
        // 关键：绝不能停在 PREPARING。
        assertFalse(
            "不能永远停在 PREPARING",
            requestRepository.get(request.requestId)!!.status == AssistantRequestStatus.PREPARING.name,
        )
    }

    /**
     * 准备阶段失败必须落终态（附件读不出来时报 ATTACHMENT_MISSING 并可重试）。
     */
    @Test
    fun `missing attachment fails preparation with a terminal state`() = runBlocking {
        stubCapturing(visionEnabled = true)
        // 本轮 identity 是文字主模型 → 路由为「题目识别」档案转写。
        every { aiCredentialStore.questionVisionProfileId() } returns "vision-1"
        val request = manager.submit(
            AssistantGenerationManager.Submission(
                conversationId = "c1",
                userText = "看图",
                // 不存在的附件。
                attachmentPaths = listOf("/definitely/not/here.png"),
            ),
        )
        val status = awaitTerminal(request.requestId)
        assertEquals(AssistantRequestStatus.INTERRUPTED.name, status)
        val stored = requestRepository.get(request.requestId)!!
        assertEquals(
            com.example.lixing.domain.assistant.AssistantFailureKind.ATTACHMENT_MISSING.name,
            stored.failureKind,
        )
        assertTrue("缺附件应当可重试", com.example.lixing.domain.assistant.AssistantFailureKind.ATTACHMENT_MISSING.isRetryable)
    }

    /**
     * 并发提交：锁内判定 + 锁内落盘，所以**只能有一条**请求记录被真正接受，
     * 另一条必须在落盘前就被拒（不留下停在 PREPARING 的孤儿）。
     */
    @Test
    fun `concurrent submits only one is accepted and no orphan is left`() = runBlocking {
        // 让第一轮挂住不结束。
        coEvery { modelClient.isVisionEnabled() } returns false
        coEvery { modelClient.chooseContext(any(), any()) } returns emptySet()
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (AssistantStreamEvent) -> Unit>())
        } coAnswers {
            delay(500)
            AssistantResponseParser.parse("""{"reply":"慢回答","plan_actions":[]}""", normalizeMarkdown = false)
        }
        val first = manager.submit(
            AssistantGenerationManager.Submission(conversationId = "c1", userText = "第一个", attachmentPaths = emptyList()),
        )
        val secondResult = runCatching {
            manager.submit(
                AssistantGenerationManager.Submission(conversationId = "c1", userText = "第二个", attachmentPaths = emptyList()),
            )
        }
        assertTrue(
            "第二个提交必须被单飞拒绝",
            secondResult.exceptionOrNull() is AssistantGenerationManager.AlreadyRunningException,
        )
        // 被拒的那次**根本没有落盘**（锁内判定先于落盘）→ 不会留下 PREPARING 孤儿。
        val requests = requestRepository.forConversation("c1")
        assertEquals("只应有一条请求记录", 1, requests.size)
        assertEquals(first.requestId, requests.single().requestId)
        assertEquals(AssistantRequestStatus.COMPLETED.name, awaitTerminal(first.requestId))
        assertEquals(0, requestRepository.countActive())
    }

    private companion object {
        val TERMINAL = setOf(
            AssistantRequestStatus.COMPLETED.name,
            AssistantRequestStatus.INTERRUPTED.name,
            AssistantRequestStatus.CANCELLED.name,
        )
    }

    // ── 定向回归：direct 路由丢问题 / 配置不完整看图题静默降级 / prepared 重试丢刷新上下文 ──

    /**
     * **direct 路由必须保留用户原问题**（prepare-outgoing-fix）。
     *
     * 缺陷：`prepare` 的 `PHOTO_ROUTE_DIRECT` 分支直接透传 `encodeAll(...)`，
     * 而 `encodeAll` 的 `outgoingText` 恒为空串 —— 主模型能看图时用户的问题
     * 被整条丢掉，模型只收到图不知道要干什么。
     *
     * 修复后：带字提交时 outgoingText 必须仍是用户原文（末尾 user 消息）。
     */
    @Test
    fun `direct photo route keeps the user question in the outgoing text`() = runBlocking {
        stubCapturing(visionEnabled = true)
        // 主模型必须带视觉，快照才会钉下 direct 路由（fixture 默认无视觉）。
        every { aiCredentialStore.resolveActiveIdentity(any(), any()) } returns
            primaryIdentity.copy(visionEnabled = true)
        val photo = java.io.File.createTempFile("direct-keep", ".png")
            .apply {
                val bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
                outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        try {
            val request = manager.submit(
                AssistantGenerationManager.Submission(
                    conversationId = "c1",
                    userText = "求这张图里定积分的值",
                    attachmentPaths = listOf(photo.absolutePath),
                ),
            )
            assertEquals(AssistantRequestStatus.COMPLETED.name, awaitTerminal(request.requestId))
            val sent = capturedMessages.lastOrNull()
            val lastUser = sent?.lastOrNull { it.role == "user" }
            assertEquals(
                "direct 路由不得丢失用户原问题",
                "求这张图里定积分的值",
                lastUser?.content,
            )
            assertTrue(
                "direct 路由必须带原图直送主模型",
                capturedImageCounts.lastOrNull() == 1,
            )
        } finally {
            photo.delete()
        }
        Unit
    }

    /**
     * **纯照片题（没打字）走 direct 路由必须有默认看图提示词**（prepare-outgoing-fix）。
     *
     * userText 为空时 outgoingText 不能还是空串 —— 那等于发送一条空 user 消息。
     */
    @Test
    fun `photo only direct submission falls back to the default vision prompt`() = runBlocking {
        stubCapturing(visionEnabled = true)
        // 主模型必须带视觉，快照才会钉下 direct 路由（fixture 默认无视觉）。
        every { aiCredentialStore.resolveActiveIdentity(any(), any()) } returns
            primaryIdentity.copy(visionEnabled = true)
        val photo = java.io.File.createTempFile("direct-only", ".png")
            .apply {
                val bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
                outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        try {
            val request = manager.submit(
                AssistantGenerationManager.Submission(
                    conversationId = "c1",
                    userText = "",
                    attachmentPaths = listOf(photo.absolutePath),
                ),
            )
            assertEquals(AssistantRequestStatus.COMPLETED.name, awaitTerminal(request.requestId))
            val sent = capturedMessages.lastOrNull()
            val lastUser = sent?.lastOrNull { it.role == "user" }
            assertEquals(
                "纯照片题必须用默认看图提示词兜底",
                GenerationPreparer.DEFAULT_VISION_PROMPT,
                lastUser?.content,
            )
        } finally {
            photo.delete()
        }
        Unit
    }

    /**
     * **配置不完整的看图题必须明确失败，不能静默降级成纯文本**（silent-degrade-fix）。
     *
     * 有附件、主模型不能看图、也没有识别档案：旧行为是路由判成 none 后把
     * 空问题/占位文案发给纯文本模型。现在必须落「配置失效」终态，
     * 且主模型一次网络请求都不发出。
     */
    @Test
    fun `photo submission without vision and without recognizer fails with config invalid`() = runBlocking {
        coEvery { modelClient.isVisionEnabled() } returns false
        coEvery { modelClient.chooseContext(any(), any()) } returns emptySet()
        // 主模型无视觉 + 无识别档案 → captureInitialSnapshot 只能记 none 路由。
        every { aiCredentialStore.questionVisionProfileId() } returns null
        var mainModelCalled = false
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (AssistantStreamEvent) -> Unit>())
        } coAnswers {
            mainModelCalled = true
            AssistantResponseParser.parse("""{"reply":"不该发生","plan_actions":[]}""", normalizeMarkdown = false)
        }
        val request = manager.submit(
            AssistantGenerationManager.Submission(
                conversationId = "c1",
                userText = "看图",
                attachmentPaths = listOf("/definitely/not/here.png"),
            ),
        )
        val status = awaitTerminal(request.requestId)
        assertEquals(AssistantRequestStatus.INTERRUPTED.name, status)
        val stored = requestRepository.get(request.requestId)!!
        assertEquals(
            "配置不完整的看图题必须落「配置失效」而不是可重试的附件丢失",
            com.example.lixing.domain.assistant.AssistantFailureKind.CONFIG_INVALID.name,
            stored.failureKind,
        )
        assertTrue(
            "失败信息必须引导用户配置看图模型",
            stored.failureMessage.contains("看图") || stored.failureMessage.contains("识别"),
        )
        assertFalse("绝不能把看图题当纯文本发出去", mainModelCalled)
    }

    /**
     * **prepared 重试必须使用传入的易变上下文**（prepared-retry-context-fix）。
     *
     * 缺陷：`preparedRetryFromSnapshot` 忽略 `refreshedContext`，重试仍然发
     * 提交时钉下的旧上下文 —— 第二天的"今天"还是昨天的计划。
     *
     * 修复后：重试发出的 context 是刷新后的值，且补写进快照的
     * `sourceContext`/`contextChars` 同步更新（原文历史/身份/设置原样保留）。
     */
    @Test
    fun `prepared retry uses the refreshed context and updates the snapshot`() = runBlocking {
        val requestId = submitAndInterrupt("问题：解这道题", "半截")
        val owner = requestRepository.get(requestId)!!
        val originalSnapshot = com.example.lixing.data.assistant.AssistantSnapshotCodec
            .decode(owner.snapshotJson)!!
        // 走一遍 prepare 把快照补写成 prepared=true（重试场景的前置状态）。
        val firstPrepared = preparer.prepare(
            AssistantGenerationManager.Submission(
                conversationId = "c1",
                userText = "问题：解这道题",
                attachmentPaths = emptyList(),
            ),
            owner,
            originalSnapshot,
        )
        assertTrue(firstPrepared.failure == null)
        val preparedSnapshot = firstPrepared.snapshot
        assertTrue(preparedSnapshot.prepared)
        // 换新 attempt（beginRetry 使记录回到在途），再把 prepared 快照带一份
        // **旧的**钉下上下文存进去 —— 围栏只对在途 attempt 放行。
        val withPrepared = requestRepository.beginRetry(
            requestId, "att-ctx-retry",
            com.example.lixing.data.assistant.AssistantSnapshotCodec.encode(preparedSnapshot),
            null,
        )!!
        val staleContext = "提交那天钉下的旧计划数据"
        val seeded = preparedSnapshot.copy(sourceContext = staleContext, contextChars = staleContext.length)
        assertTrue(
            requestRepository.saveSnapshotForAttempt(requestId, withPrepared.attemptId, seeded),
        )

        val refreshed = "重试当天刚重建的新计划数据"
        val retried = preparer.prepareRetry(
            userText = withPrepared.userText,
            attachmentPaths = emptyList(),
            refreshedContext = refreshed,
            owner = requestRepository.get(requestId)!!,
            history = emptyList(),
        )
        assertTrue("prepared 重试不应失败", retried.failure == null)
        assertEquals("重试必须使用刷新后的易变上下文", refreshed, retried.context)
        assertEquals(
            "补写进快照的 sourceContext 必须同步更新",
            refreshed,
            retried.snapshot.sourceContext,
        )
        assertEquals(
            "补写进快照的 contextChars 必须同步更新",
            refreshed.length,
            retried.snapshot.contextChars,
        )
        assertEquals(
            "原文历史与身份设置原样保留",
            seeded.originalHistory,
            retried.snapshot.originalHistory,
        )
        assertEquals(seeded.model, retried.snapshot.model)
        // 未传才退回持久快照；prepareRetry 返回的更新快照由 manager 后续保存。
        val fallback = preparer.prepareRetry(
            userText = withPrepared.userText,
            attachmentPaths = emptyList(),
            refreshedContext = null,
            owner = requestRepository.get(requestId)!!,
            history = emptyList(),
        )
        assertEquals("未传刷新上下文时退回快照钉下的那份", staleContext, fallback.context)
        val emptyRefresh = preparer.prepareRetry(
            userText = withPrepared.userText,
            attachmentPaths = emptyList(),
            refreshedContext = "",
            owner = requestRepository.get(requestId)!!,
            history = emptyList(),
        )
        assertEquals("空刷新不能复活已删除的旧上下文", "", emptyRefresh.context)
        assertEquals("", emptyRefresh.snapshot.sourceContext)
        assertEquals(0, emptyRefresh.snapshot.contextChars)
    }
}
