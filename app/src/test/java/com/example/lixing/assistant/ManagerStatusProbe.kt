package com.example.lixing.assistant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.lixing.data.assistant.AssistantDiagnostics
import com.example.lixing.data.assistant.AssistantModelClient
import com.example.lixing.data.assistant.AssistantResponseParser
import com.example.lixing.data.assistant.AssistantStreamEvent
import com.example.lixing.data.assistant.MonotonicClock
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.local.entity.AssistantConversationEntity
import com.example.lixing.data.repository.AssistantRequestRepository
import com.example.lixing.domain.assistant.AssistantMessage
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 诊断：确认「完成瞬间闪过 COMPLETED、随即被兜底写入改回 RUNNING」已消失。
 *
 * 这是本轮整改的首要根因（旧 `finally` 里的 `savePartial` 默认状态 RUNNING）。
 * 保留这个探针作为**可复核的证据**：先前的探针在 t=0ms 打印 COMPLETED、
 * t=100ms 起就一直打印 RUNNING。现在全程必须是 COMPLETED。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ManagerStatusProbe {

    private class FakeGuard : AssistantGenerationGuard {
        private val held = mutableSetOf<String>()
        override fun acquire(requestId: String, conversationId: String?) = "$requestId#1".also { held += it }
        override fun release(token: String) { held -= token }
        override fun activeCount() = held.size
        override fun begin() = Unit
        override fun end() = Unit
    }

    private class NoopRenderer : FigureRenderer {
        override suspend fun render(figures: List<com.example.lixing.data.assistant.AssistantFigure>) =
            figures.map { "" }
    }

    @Test
    fun probeTerminalStateIsStable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LiXingDatabase::class.java)
            .allowMainThreadQueries().build()
        val repo = AssistantRequestRepository(db.assistantRequestDao(), Dispatchers.Unconfined)
        db.assistantChatDao().insertConversation(AssistantConversationEntity(id = "c1", title = "t"))
        val created = repo.createRequest(
            conversationId = "c1", userMessageId = "u1", answerMessageId = "a1",
            attemptId = "att1", userText = "q", attachmentPaths = emptyList(), snapshotJson = "",
        )
        val model = mockk<AssistantModelClient>()
        coEvery {
            model.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (AssistantStreamEvent) -> Unit>())
        } coAnswers {
            val onEvent = arg<suspend (AssistantStreamEvent) -> Unit>(7)
            onEvent(AssistantStreamEvent.AnswerDelta("""{"reply":"hello","plan_actions":[]}"""))
            AssistantResponseParser.parse("""{"reply":"hello","plan_actions":[]}""", normalizeMarkdown = false)
        }
        val manager = AssistantGenerationManager(
            context = context,
            modelClient = model,
            requestRepository = repo,
            diagnostics = AssistantDiagnostics(),
            guard = FakeGuard(),
            monotonicClock = MonotonicClock.SYSTEM,
            finalizer = GenerationFinalizer(repo, NoopRenderer()),
            preparer = mockk(relaxed = true),
        )
        manager.start(
            AssistantGenerationManager.GenerationRequest(
                conversationId = "c1", userMessageId = "u1", answerMessageId = "a1",
                attemptId = "att1", userText = "q",
                history = listOf(AssistantMessage("user", "q")),
                context = "", imageBase64s = emptyList(), webSearchEnabled = false,
                forceWebSearch = false, maxContinuations = 0, modelKey = "m", protocol = "p",
            ),
            created.requestId,
        )
        val seen = mutableListOf<String>()
        repeat(20) {
            delay(50)
            val status = repo.get(created.requestId)?.status.orEmpty()
            seen += status
            println("PROBE t=${it * 50}ms status=$status")
        }
        val distinct = seen.distinct()
        println("PROBE distinct-statuses=$distinct")
        // 一旦出现终态，就不能再变回在途状态。
        val firstTerminal = seen.indexOfFirst { it == "COMPLETED" }
        check(firstTerminal >= 0) { "从未到达 COMPLETED：$distinct" }
        val afterTerminal = seen.drop(firstTerminal)
        check(afterTerminal.all { it == "COMPLETED" }) {
            "终态被复活了：$afterTerminal"
        }
        println("PROBE RESULT=TERMINAL-STABLE")
        db.close()
    }
}
