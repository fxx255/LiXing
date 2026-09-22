package com.example.lixing.ui.screen.assistant

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.example.lixing.assistant.AssistantDraftStore
import com.example.lixing.assistant.AssistantGenerationManager
import com.example.lixing.data.assistant.AssistantDiagnostics
import com.example.lixing.data.local.entity.AssistantMessageEntity
import com.example.lixing.data.local.entity.AssistantRequestEntity
import com.example.lixing.data.prefs.UserPreferences
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.repository.AssistantChatRepository
import com.example.lixing.data.repository.AssistantRequestRepository
import com.example.lixing.domain.assistant.AssistantContextKind
import com.example.lixing.domain.assistant.AssistantRequestStatus
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Real VM with controllable persistence boundaries; no network or real IO dispatcher. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AssistantViewModelSubmissionTest {
    private val dispatcher = StandardTestDispatcher()
    private val rows = mutableMapOf<String, MutableStateFlow<List<AssistantMessageEntity>>>()
    private val requestRows = mutableMapOf<String, MutableStateFlow<List<AssistantRequestEntity>>>()
    private val drafts = mutableMapOf<String?, AssistantDraftStore.Draft>()
    private val active = MutableStateFlow(AssistantGenerationManager.ActiveState())
    private val events = MutableSharedFlow<AssistantGenerationManager.GenerationEvent>()
    private val chats = mockk<AssistantChatRepository>(relaxed = true)
    private val requests = mockk<AssistantRequestRepository>(relaxed = true)
    private val manager = mockk<AssistantGenerationManager>(relaxed = true)
    private val draftStore = mockk<AssistantDraftStore>(relaxed = true)
    private val prefs = mockk<UserPreferencesRepository>(relaxed = true)
    private val store = ViewModelStore()
    private lateinit var vm: AssistantViewModel
    private var revision = 0L
    private var sendCount = 0
    private var submitted: AssistantGenerationManager.Submission? = null
    private var submissionGate: CompletableDeferred<Unit>? = null
    private var loadGate: CompletableDeferred<Unit>? = null
    private var attachmentGate: CompletableDeferred<Unit>? = null
    private var completeBeforeReturn = false

    private fun flow(id: String) = rows.getOrPut(id) { MutableStateFlow(emptyList()) }
    private fun requestFlow(id: String) = requestRows.getOrPut(id) { MutableStateFlow(emptyList()) }
    private fun tick() = dispatcher.scheduler.advanceUntilIdle()
    private fun record(id: String) = AssistantRequestEntity(
        requestId = "request-$id", conversationId = id, userMessageId = "user-$id",
        answerMessageId = "answer-$id", attemptId = "attempt-$id", status = "RUNNING",
    )

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { prefs.current() } returns UserPreferences()
        every { chats.observeConversations() } returns emptyFlow()
        every { chats.observeMessages(any()) } answers { flow(firstArg()) }
        coEvery { chats.messages(any()) } coAnswers { flow(firstArg()).value }
        every { chats.decodeImagePaths(any()) } returns emptyList()
        coEvery { chats.startConversation(any()) } returns "created"
        every { requests.observeForConversation(any()) } answers { requestFlow(firstArg()) }
        coEvery { requests.forConversation(any()) } coAnswers { requestFlow(firstArg()).value }
        every { manager.state } returns active
        every { manager.events } returns events
        every { manager.isRunning() } answers { active.value.isRunning }
        coEvery { manager.recoverOnStartup() } returns Unit
        every { draftStore.reserveSave(any(), any(), any()) } answers {
            val id = firstArg<String?>()
            val text = secondArg<String>()
            val photos = thirdArg<List<String>>()
            if (text.isEmpty() && photos.isEmpty()) drafts.remove(id)
            else drafts[id] = AssistantDraftStore.Draft(text, photos.toList())
            AssistantDraftStore.Reservation(id, ++revision)
        }
        coEvery { draftStore.load(any()) } coAnswers {
            val captured = drafts[firstArg<String?>()]
            val gate = loadGate.also { loadGate = null }
            gate?.await()
            captured
        }
        coEvery { draftStore.persistAttachments(any()) } coAnswers {
            attachmentGate?.await()
            firstArg<List<String>>()
        }
        coEvery { manager.submit(any(), any(), any(), any(), any()) } coAnswers {
            sendCount++
            val submission = firstArg<AssistantGenerationManager.Submission>().also { submitted = it }
            val record = record(submission.conversationId)
            flow(submission.conversationId).value = listOf(
                AssistantMessageEntity(id = record.userMessageId, conversationId = record.conversationId,
                    role = "user", content = submission.userText),
                AssistantMessageEntity(id = record.answerMessageId, conversationId = record.conversationId,
                    role = "assistant", content = if (completeBeforeReturn) "最终正文" else ""),
            )
            if (!completeBeforeReturn) active.value = AssistantGenerationManager.ActiveState(
                requestId = record.requestId, attemptId = record.attemptId,
                conversationId = record.conversationId, answerMessageId = record.answerMessageId,
                phase = AssistantRequestStatus.RUNNING, partialText = "已经生成的正文",
                reasoning = "思考片段", answerStarted = true,
            )
            submissionGate?.await()
            record
        }
        vm = AssistantViewModel(
            prefsRepository = prefs, modelClient = mockk(relaxed = true), contextBuilder = mockk(relaxed = true),
            applier = mockk(relaxed = true), versionedBackupRepository = mockk(relaxed = true),
            planRepository = mockk(relaxed = true), taskRepository = mockk(relaxed = true),
            chatRepository = chats, englishEntryRepository = mockk(relaxed = true),
            aiCredentialStore = mockk(relaxed = true), generationGuard = mockk(relaxed = true),
            plotImageStore = mockk(relaxed = true), diagramImageStore = mockk(relaxed = true),
            generationManager = manager, requestRepository = requests, diagnostics = AssistantDiagnostics(),
            draftStore = draftStore, reviewTransactions = mockk(relaxed = true), io = dispatcher,
        )
        store.put("submission", vm)
        tick()
    }

    @After fun teardown() { store.clear(); Dispatchers.resetMain() }
    private fun open(id: String) { vm.openConversation(id); tick() }

    @Test fun reopeningCurrentConversationRetainsItsMessages() = runTest(dispatcher) {
        flow("A").value = listOf(AssistantMessageEntity(
            id = "saved", conversationId = "A", role = "assistant", content = "已保存答案",
        ))
        open("A")
        vm.openConversation("A"); tick()
        assertEquals("已保存答案", vm.state.value.messages.single().content)
    }

    @Test fun rapidABANavigationRestartsTheAuthoritativePageSubscription() = runTest(dispatcher) {
        flow("A").value = listOf(AssistantMessageEntity(
            id = "saved", conversationId = "A", role = "assistant", content = "A答案",
        ))
        open("A")
        vm.openConversation("B"); vm.openConversation("A"); tick()
        assertEquals("A", vm.state.value.currentConversationId)
        assertEquals("A答案", vm.state.value.messages.single().content)
    }

    @Test fun sendCapturesSettingsAndConversationBeforeLaunch() = runTest(dispatcher) {
        open("A")
        vm.updateInput("  A问题  ")
        vm.toggleContextKind(AssistantContextKind.TODAY)
        vm.setForceWebSearch(true)
        vm.send()
        vm.openConversation("B")
        vm.updateInput("B草稿")
        vm.toggleContextKind(AssistantContextKind.TODAY)
        vm.setForceWebSearch(false)
        tick()
        assertEquals("A", submitted?.conversationId)
        assertEquals("A问题", submitted?.userText)
        assertEquals(setOf(AssistantContextKind.TODAY), submitted?.contextKinds)
        assertEquals(true, submitted?.forceWebSearch)
        assertEquals("B", vm.state.value.currentConversationId)
        assertEquals("B草稿", vm.state.value.input)
        assertFalse(vm.state.value.busy)
        assertNull(drafts["A"])
    }

    @Test fun databaseRowsBeforeSubmitReturnAreNotDuplicatedAndActiveReasoningSurvives() = runTest(dispatcher) {
        open("A"); vm.updateInput("问题")
        val gate = CompletableDeferred<Unit>().also { submissionGate = it }
        vm.send(); tick()
        assertEquals(2, vm.state.value.messages.size)
        gate.complete(Unit); tick()
        assertEquals(listOf("user-A", "answer-A"), vm.state.value.messages.map { it.id })
        assertEquals("思考片段", vm.state.value.activeReasoning)
        assertTrue(vm.state.value.activeAnswerStarted)
    }

    @Test fun fastCompletionDoesNotBecomeBusyAgainOrLoseFinalText() = runTest(dispatcher) {
        completeBeforeReturn = true
        open("A"); vm.updateInput("问题"); vm.send(); tick()
        assertFalse(vm.state.value.busy)
        assertEquals("最终正文", vm.state.value.messages.single { it.role == "assistant" }.content)
        assertEquals(2, vm.state.value.messages.size)
    }

    @Test fun pendingSubmissionBlocksSecondSendWhenManagerIsStillEmpty() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { manager.submit(any(), any(), any(), any(), any()) } coAnswers { sendCount++; gate.await(); record("A") }
        open("A"); vm.updateInput("问题"); vm.send(); tick()
        active.value = AssistantGenerationManager.ActiveState()
        vm.send(); tick()
        assertEquals(1, sendCount)
        gate.complete(Unit); tick()
    }

    @Test fun subsequentIdenticalEditIsNotConsumedOnAcceptance() = runTest(dispatcher) {
        open("A"); vm.updateInput("相同文字")
        val gate = CompletableDeferred<Unit>().also { submissionGate = it }
        vm.send(); tick()
        vm.updateInput("第二次编辑")
        vm.updateInput("相同文字")
        gate.complete(Unit); tick()
        assertEquals("相同文字", vm.state.value.input)
        assertEquals("相同文字", drafts["A"]?.text)
    }

    @Test fun submittedNewConversationDraftDoesNotReturnWhenOpeningNewPage() = runTest(dispatcher) {
        vm.updateInput("新对话问题"); vm.send(); tick()
        assertEquals("created", vm.state.value.currentConversationId)
        assertNull(drafts[null])
        vm.startNewConversation(); tick()
        assertEquals("", vm.state.value.input)
    }

    @Test fun laterDraftDuringFirstSendSurvivesCreationOfConversation() = runTest(dispatcher) {
        vm.updateInput("同样的新问题")
        val gate = CompletableDeferred<Unit>().also { submissionGate = it }
        vm.send(); tick()
        vm.updateInput("修改过")
        vm.updateInput("同样的新问题")
        gate.complete(Unit); tick()
        assertEquals("created", vm.state.value.currentConversationId)
        assertEquals("同样的新问题", vm.state.value.input)
        vm.openConversation("B"); tick()
        open("created")
        assertEquals("同样的新问题", vm.state.value.input)
    }

    @Test fun delayedNewConversationCreationDoesNotSelectItOverB() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { chats.startConversation(any()) } coAnswers { gate.await(); "created" }
        vm.updateInput("新问题"); vm.send(); tick()
        open("B"); vm.updateInput("B草稿")
        gate.complete(Unit); tick()
        assertEquals("created", submitted?.conversationId)
        assertEquals("B", vm.state.value.currentConversationId)
        assertEquals("B草稿", vm.state.value.input)
    }

    @Test fun creationFailurePreservesDraftAndReportsError() = runTest(dispatcher) {
        coEvery { chats.startConversation(any()) } throws IOException("磁盘写入失败")
        vm.updateInput("必须保留"); vm.send(); tick()
        assertEquals("必须保留", vm.state.value.input)
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.busy)
        assertEquals(0, sendCount)
    }

    @Test fun missingPhotoReferenceReachesValidationAndIsPreservedOnRejection() = runTest(dispatcher) {
        val path = "/missing/synthetic-photo.png"
        coEvery { manager.submit(any(), any(), any(), any(), any()) } coAnswers {
            submitted = firstArg(); throw IOException("附件已不可用")
        }
        open("A"); vm.updateInput("图片题"); vm.onPhotoTaken(path); tick()
        vm.send(); tick()
        assertEquals(listOf(path), submitted?.attachmentPaths)
        assertEquals(listOf(path), vm.state.value.pendingPhotoPaths)
        assertNotNull(vm.state.value.error)
    }

    @Test fun latePhotoCopyIsSavedOnlyInOriginalDraftIncludingReturningToA() = runTest(dispatcher) {
        open("A"); vm.updateInput("A草稿")
        val gate = CompletableDeferred<Unit>().also { attachmentGate = it }
        vm.onPhotoTaken("/synthetic/new-photo.png"); tick()
        open("B"); vm.updateInput("B草稿")
        gate.complete(Unit); tick()
        assertTrue(vm.state.value.pendingPhotoPaths.isEmpty())
        assertEquals("B草稿", vm.state.value.input)
        open("A")
        assertEquals("A草稿", vm.state.value.input)
        assertEquals(listOf("/synthetic/new-photo.png"), vm.state.value.pendingPhotoPaths)
    }

    @Test fun latePhotoFailureAndLateDraftFailureDoNotChangeB() = runTest(dispatcher) {
        open("A")
        val photoGate = CompletableDeferred<Unit>()
        val persistGate = CompletableDeferred<Unit>()
        coEvery { draftStore.persistAttachments(any()) } coAnswers { photoGate.await(); throw IOException("photo") }
        coEvery { draftStore.persist(any()) } coAnswers { persistGate.await(); throw IOException("draft") }
        vm.onPhotoTaken("/synthetic/photo"); vm.updateInput("A编辑"); tick()
        vm.openConversation("B"); tick()
        // Future B edits need not fail; only A's already-started writes fail.
        coEvery { draftStore.persist(any()) } returns Unit
        photoGate.complete(Unit); persistGate.complete(Unit); tick()
        assertEquals("B", vm.state.value.currentConversationId)
        assertNull(vm.state.value.error)
    }

    @Test fun photoDraftReadCannotOverwriteEditMadeWhileItWasSuspended() = runTest(dispatcher) {
        open("A"); vm.updateInput("原草稿"); tick()
        val gate = CompletableDeferred<Unit>().also { loadGate = it }
        vm.onPhotoTaken("/synthetic/delayed.png"); tick()
        open("B"); open("A"); vm.updateInput("A的新编辑"); tick(); open("B")
        gate.complete(Unit); tick(); open("A")
        assertEquals("A的新编辑", vm.state.value.input)
        assertEquals(listOf("/synthetic/delayed.png"), vm.state.value.pendingPhotoPaths)
    }

    @Test fun lateDraftLoadCannotReplaceNewPhotoOnlyDraft() = runTest(dispatcher) {
        drafts["A"] = AssistantDraftStore.Draft("旧草稿", emptyList())
        val gate = CompletableDeferred<Unit>().also { loadGate = it }
        vm.openConversation("A"); tick()
        vm.onPhotoTaken("/synthetic/current.png"); tick()
        gate.complete(Unit); tick()
        assertEquals("", vm.state.value.input)
        assertEquals(listOf("/synthetic/current.png"), vm.state.value.pendingPhotoPaths)
    }

    @Test fun removingPendingPhotoDoesNotDeleteHistoryFile() = runTest(dispatcher) {
        val file = File.createTempFile("assistant-history", ".png")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            open("A"); vm.onPhotoTaken(file.path); tick()
            vm.removePendingPhoto(file.path); tick()
            assertTrue(file.isFile)
            assertTrue(vm.state.value.pendingPhotoPaths.isEmpty())
        } finally { file.delete() }
    }

    @Test fun interruptedPartialRestoresWhenRequestRowsArriveBeforeMessages() = runTest(dispatcher) {
        open("A")
        requestFlow("A").value = listOf(record("A").copy(status = "INTERRUPTED", partialText = "已保存片段"))
        tick()
        flow("A").value = listOf(
            AssistantMessageEntity(id = "user-A", conversationId = "A", role = "user", content = "问题"),
            AssistantMessageEntity(id = "answer-A", conversationId = "A", role = "assistant", content = ""),
        )
        tick()
        assertEquals("已保存片段", vm.state.value.messages.last().content)
        assertEquals("request-A", vm.state.value.retryRequestId)
        assertEquals(0, sendCount)
    }

    @Test fun interruptedPartialRestoresAfterMessagesAndCompletedAnswerWins() = runTest(dispatcher) {
        flow("A").value = listOf(
            AssistantMessageEntity(id = "user-A", conversationId = "A", role = "user", content = "问题"),
            AssistantMessageEntity(id = "answer-A", conversationId = "A", role = "assistant", content = ""),
        )
        open("A")
        requestFlow("A").value = listOf(record("A").copy(status = "INTERRUPTED", partialText = "很长的中断片段"))
        tick()
        assertEquals("很长的中断片段", vm.state.value.messages.last().content)
        requestFlow("A").value = listOf(record("A").copy(status = "COMPLETED", partialText = "很长的中断片段"))
        flow("A").value = flow("A").value.map { if (it.id == "answer-A") it.copy(content = "终稿") else it }
        tick()
        assertEquals("终稿", vm.state.value.messages.last().content)
        assertNull(vm.state.value.retryRequestId)
    }
}
