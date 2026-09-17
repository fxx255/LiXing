package com.example.lixing.data.assistant

import android.app.Application
import com.example.lixing.data.prefs.UserPreferences
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.domain.assistant.AssistantMessage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AssistantStreamRecoveryTest {
    private val requests = mutableListOf<JsonObject>()

    private fun client(responses: List<Pair<Int, String>>): AssistantModelClient {
        val prefs = mockk<UserPreferencesRepository>()
        coEvery { prefs.current() } returns UserPreferences(aiAssistantEnabled = true)
        val credentials = mockk<AiCredentialStore>()
        every { credentials.activeProfile() } returns AiModelProfile(
            "test", "test", "https://openrouter.ai/api/v1", "test-model", true,
            AiSearchProtocol.OFF, AiReasoningEffort.HIGH, true,
        )
        every { credentials.load() } returns "test-key"
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            val index = requests.size
            requests += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val (code, body) = responses[index]
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("test").body(body.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        return AssistantModelClient(prefs, credentials, Dispatchers.Unconfined).also {
            AssistantModelClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(it, transport)
        }
    }

    private fun sse(reasoning: String = "", content: String = "", finish: String = "stop"): String {
        val delta = buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject {
                put("delta", buildJsonObject { put("reasoning_content", reasoning); put("content", content) })
            }) })
        }
        // Some endpoints omit delta from their final finish_reason chunk.
        return "data: $delta\n\ndata: {\"choices\":[{\"finish_reason\":\"$finish\"}]}\n\ndata: [DONE]\n\n"
    }

    @Test fun `reasoning only recovery lowers effort and keeps picture on original question`() = runBlocking {
        val model = client(listOf(200 to sse(reasoning = "analysis", finish = "length"),
            200 to sse(content = """{"reply":"final answer"}""")))
        val result = model.chatStreaming(listOf(AssistantMessage("user", "question")), "", listOf("image-data")) {}
        assertEquals("final answer", result.reply)
        assertEquals(2, requests.size)
        val recovery = requests.last()
        assertEquals(16384, recovery["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("low", recovery["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val messages = recovery["messages"]!!.jsonArray
        assertTrue(messages[2].jsonObject["content"] is JsonArray)
        assertTrue(messages.last().jsonObject["content"] is JsonPrimitive)
    }

    @Test fun `reasoning including draft JSON is never returned as a final answer`() = runBlocking {
        val thought = "Need check this draft {\"reply\":\"unverified draft\"}"
        val model = client(List(2) { 200 to sse(reasoning = thought, finish = "length") })
        val error = runCatching { model.chatStreaming(listOf(AssistantMessage("user", "question")), "") {} }.exceptionOrNull()
        assertTrue(error is AssistantModelException)
        assertEquals(2, requests.size)
        assertFalse(error!!.message.orEmpty().contains("unverified draft"))
    }

    @Test fun `finish reason without delta still triggers continuation and preserves raw delimiters`() = runBlocking {
        val model = client(listOf(200 to sse(content = """{"reply":"value ${'$'}x"}""", finish = "length")))
        val result = model.chatStreaming(listOf(AssistantMessage("user", "question")), "") {}
        assertTrue(result.truncated)
        assertEquals("value ${'$'}x", result.reply)
    }

    @Test fun `recovery budget rejection retries compatible budget once`() = runBlocking {
        val model = client(listOf(200 to sse(reasoning = "analysis"), 400 to "max_tokens exceeds limit",
            200 to sse(content = """{"reply":"done"}""")))
        assertEquals("done", model.chatStreaming(listOf(AssistantMessage("user", "question")), "") {}.reply)
        assertEquals(listOf(8192, 16384, 8192), requests.map { it["max_tokens"]!!.jsonPrimitive.int })
    }

    @Test fun `cancellation during recovery is propagated`() = runBlocking {
        val model = client(listOf(200 to sse(reasoning = "first"), 200 to sse(reasoning = "second")))
        val error = runCatching {
            model.chatStreaming(listOf(AssistantMessage("user", "question")), "") {
                if (it is AssistantStreamEvent.ReasoningDelta && it.text == "second") throw CancellationException("cancel")
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }
}
