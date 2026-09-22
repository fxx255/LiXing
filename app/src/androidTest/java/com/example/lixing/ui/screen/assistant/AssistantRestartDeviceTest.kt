package com.example.lixing.ui.screen.assistant

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lixing.MainActivity
import com.example.lixing.assistant.AssistantAcceptanceDependencies
import com.example.lixing.assistant.newAcceptanceViewModel
import com.example.lixing.data.assistant.AiSearchProtocol
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Run phase=prepare, force-stop the package from the host, then phase=recover. */
@RunWith(AndroidJUnit4::class)
class AssistantRestartDeviceTest {
    private suspend fun waitUntil(predicate: suspend () -> Boolean) = withTimeout(60_000) {
        while (!predicate()) delay(25)
    }

    private suspend fun fake(path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val c = URL("$BASE/$path").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 3000; c.readTimeout = 3000
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            check(c.responseCode == 200)
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    @Test fun processRestartRestoresPartialWithoutAutomaticRequest() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("assistantIsolated") == "true")
        val phase = requireNotNull(args.getString("phase"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val deps = EntryPointAccessors.fromApplication(context, AssistantAcceptanceDependencies::class.java)
        val record = context.getSharedPreferences("assistant_restart_acceptance", Context.MODE_PRIVATE)
        if (phase == "prepare") {
            check(!deps.manager().isRunning())
            fake("control", JSONObject().put("scenario", "longtable").put("chunk_delay", 0.25)
                .put("reset_count", true))
            deps.credentials().upsertProfile(null, "重启自动验收", "$BASE/v1", "acceptance-fixture",
                "dummy-acceptance-key", visionEnabled = true, searchProtocol = AiSearchProtocol.OFF)
            deps.prefs().setAiConfiguration("$BASE/v1", "acceptance-fixture", true)
            deps.prefs().setAiAssistantEnabled(true)
            deps.prefs().setAiWebSearchEnabled(false)
            deps.prefs().setAssistantAutoContinue(0)
            val activity = ActivityScenario.launch(MainActivity::class.java)
            val id = deps.chats().startConversation("进程恢复验收-${UUID.randomUUID()}")
            val vm = withContext(Dispatchers.Main) { deps.newAcceptanceViewModel() }
            withContext(Dispatchers.Main) { vm.openConversation(id) }
            waitUntil { vm.state.value.currentConversationId == id }
            withContext(Dispatchers.Main) { vm.updateInput("请生成测试长表格。"); vm.send() }
            waitUntil {
                deps.requests().forConversation(id).singleOrNull()?.let {
                    it.status == "RUNNING" && it.partialText.isNotBlank()
                } == true
            }
            val request = deps.requests().forConversation(id).single()
            assertTrue(deps.manager().isRunning())
            check(record.edit().putString("conversation", id).putString("request", request.requestId)
                .putString("user", request.userMessageId).putString("answer", request.answerMessageId)
                .putString("partial", request.partialText).putString("attempt", request.attemptId).commit())
            // Deliberately leave generation active. The host now force-stops this process.
            activity.close()
            return@runBlocking
        }
        check(phase == "recover")
        val id = requireNotNull(record.getString("conversation", null))
        val requestId = requireNotNull(record.getString("request", null))
        val countsBefore = modelRequestCounts()
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val store = ViewModelStore()
        try {
            val vm = withContext(Dispatchers.Main) {
                deps.newAcceptanceViewModel().also { store.put("restart", it); it.openConversation(id) }
            }
            waitUntil { vm.state.value.retryRequestId == requestId }
            val request = requireNotNull(deps.requests().get(requestId))
            assertEquals("INTERRUPTED", request.status)
            assertEquals(record.getString("attempt", null), request.attemptId)
            assertTrue(request.partialText.startsWith(requireNotNull(record.getString("partial", null))))
            assertFalse(deps.manager().isRunning())
            assertEquals(0, deps.guard().activeCount())
            assertEquals(countsBefore, modelRequestCounts())
            val expectedIds = listOf(record.getString("user", null), record.getString("answer", null))
            assertEquals(expectedIds, deps.chats().messages(id).map { it.id })
            waitUntil { vm.state.value.messages.any { it.id == request.answerMessageId && it.content.isNotBlank() } }
            fake("control", JSONObject().put("scenario", "normal").put("chunk_delay", 0.06))
            withContext(Dispatchers.Main) { vm.retryInterrupted() }
            waitUntil { deps.requests().get(requestId)?.status == "COMPLETED" && !vm.state.value.busy }
            assertEquals(expectedIds, deps.chats().messages(id).map { it.id })
            assertEquals(0, deps.guard().activeCount())
            check(record.edit().clear().commit())
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            deps.manager().activeRequestId()?.let { deps.manager().cancel(it) }
            activity.close()
        }
    }

    private suspend fun modelRequestCounts(): Map<String, Int> {
        val counts = fake("status").getJSONObject("counts")
        // Polling /status increments its own counter; only model HTTP requests matter.
        return counts.keys().asSequence()
            .filter { it.startsWith("chat_") || it.startsWith("responses_") }
            .associateWith { counts.getInt(it) }
    }

    companion object { private const val BASE = "http://127.0.0.1:16417" }
}
