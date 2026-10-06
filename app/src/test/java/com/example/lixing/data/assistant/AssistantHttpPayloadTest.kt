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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **真实 HTTP payload** 验收（`network-service-review.md` 第 1 条）。
 *
 * 为什么必须抓真实请求体而不是只看组装函数的返回值：
 * 「组装出来的字符串是对的」和「真正发出去的请求体是对的」是两件事 ——
 * 早先的 bug 恰恰在后者：`buildChatPayload` 遍历了包含最新问题的全部 messages，
 * 又把时间/本机数据追加到**最后**，于是请求里的最后一条不是当前问题。
 * 只有抓 `chain.request().body` 才能真正证明顺序与归属。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AssistantHttpPayloadTest {

    private val requests = mutableListOf<JsonObject>()

    private fun client(
        baseUrl: String = "https://api.deepseek.com/v1",
        searchProtocol: AiSearchProtocol = AiSearchProtocol.OFF,
        historyBudget: AiHistoryBudget = AiHistoryBudget.STANDARD,
        contextWindowTokens: Int? = null,
        responses: List<Pair<Int, String>> = listOf(
            200 to """data: {"choices":[{"delta":{"content":"{\"reply\":\"ok\"}"}}]}

data: [DONE]

""",
        ),
    ): AssistantModelClient {
        val prefs = mockk<UserPreferencesRepository>()
        coEvery { prefs.current() } returns UserPreferences(
            aiAssistantEnabled = true,
            aiWebSearchEnabled = true,
            assistantNickname = "小明",
            assistantCity = "北京",
        )
        val credentials = mockk<AiCredentialStore>()
        every { credentials.activeProfile() } returns AiModelProfile(
            "p", "p", baseUrl, "test-model", true, searchProtocol, AiReasoningEffort.MEDIUM, true,
            historyBudget,
            contextWindowTokens,
        )
        every { credentials.load() } returns "test-key"
        every { credentials.credentialsFor("p") } returns AiProfileCredentials(baseUrl, "test-model", "test-key")
        every { credentials.resolveActiveIdentity(any(), any()) } returns AiResolvedIdentity(
            profileId = "p",
            baseUrl = baseUrl,
            model = "test-model",
            apiKey = "test-key",
            visionEnabled = true,
            searchProtocol = searchProtocol,
            reasoningEffort = AiReasoningEffort.MEDIUM,
            historyBudget = historyBudget,
            contextWindowTokens = contextWindowTokens,
        )
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            requests += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val (code, body) = responses[requests.size - 1]
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("test")
                .body(body.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        return AssistantModelClient(prefs, credentials, Dispatchers.Unconfined).also {
            AssistantModelClient::class.java.getDeclaredField("client")
                .apply { isAccessible = true }.set(it, transport)
        }
    }

    /** 抓到的最后一条 user 消息内容。 */
    private fun lastUserContent(payload: JsonObject): String {
        val messages = payload["messages"]!!.jsonArray
        return messages
            .map { it.jsonObject }
            .last { it["role"]?.jsonPrimitive?.content == "user" }
            .let { it["content"]!! }
            .let { content ->
                // 多模态时 content 是数组，正文在 text 部分里。
                if (content is JsonArray) {
                    content.map { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }.joinToString("")
                } else {
                    content.jsonPrimitive.content
                }
            }
    }

    private fun messageRoles(payload: JsonObject): List<String> =
        payload["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content }

    @Test
    fun `configured token window retains long history and emits estimated occupancy`() = runBlocking {
        val history = (0 until 60).map { index ->
            AssistantMessage(if (index % 2 == 0) "user" else "assistant", "history-$index:" + "中".repeat(1000))
        }
        val events = mutableListOf<AssistantStreamEvent>()
        client(contextWindowTokens = 300_000).chatStreaming(history + AssistantMessage("user", "follow up"), "") { events += it }
        val payload = requests.single()
        val content = payload["messages"]!!.jsonArray.map { it.jsonObject["content"]!!.jsonPrimitive.content }
        assertEquals(history.map { it.content }, content.filter { it.startsWith("history-") })
        val usage = events.filterIsInstance<AssistantStreamEvent.ContextUsage>().single().usage
        assertEquals(300_000, usage.windowTokens)
        assertTrue(usage.isEstimated)
        assertTrue(usage.estimatedInputTokens > 60_000)
        assertTrue(payload["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.content == "true")
        assertFalse(payload.containsKey("context_length"))
    }

    @Test
    fun `complete chat payload fits token window after system context and output reservation`() = runBlocking {
        val history = (0 until 20).map { index ->
            AssistantMessage(if (index % 2 == 0) "user" else "assistant", "history-$index:" + "中".repeat(2000))
        }
        client(contextWindowTokens = 32_000).chatStreaming(
            history + AssistantMessage("user", "follow up"), "当前计划", imageBase64s = listOf("CURRENT_IMAGE"),
        ) {}
        val payload = requests.single()
        val usage = AssistantTokenEstimator.payloadUsage(payload, 32_000)
        assertTrue(usage.estimatedInputTokens + usage.reservedOutputTokens <= 32_000)
        val content = payload["messages"]!!.jsonArray
        assertTrue(content.size < history.size)
        assertTrue(content.last().toString().contains("follow up"))
        assertTrue(content.last().toString().contains("CURRENT_IMAGE"))
        assertTrue(content.toString().contains("当前计划"))
    }

    @Test
    fun `responses uses the pinned token window and retains more than 24 historical messages`() = runBlocking {
        val history = (0 until 50).map { index ->
            AssistantMessage(if (index % 2 == 0) "user" else "assistant", "history-$index:" + "中".repeat(1000))
        }
        val events = mutableListOf<AssistantStreamEvent>()
        val model = client(
            contextWindowTokens = 32_000,
            searchProtocol = AiSearchProtocol.RESPONSES,
            responses = listOf(200 to """event: response.completed
data: {"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","text":"{\"reply\":\"ok\"}"}]}]}}

"""),
        )
        model.chatStreaming(
            history + AssistantMessage("user", "follow up"), "", webSearchEnabled = true,
            policy = AssistantRequestPolicy(contextWindowTokens = 600_000, contextWindowPinned = true, effectiveWebSearchEnabled = true),
        ) { events += it }
        val content = requests.single()["input"]!!.jsonArray.map { it.jsonObject["content"]!!.jsonPrimitive.content }
        assertEquals(history.map { it.content }, content.filter { it.startsWith("history-") })
        assertEquals("follow up", content.last())
        assertEquals(600_000, events.filterIsInstance<AssistantStreamEvent.ContextUsage>().single().usage.windowTokens)
    }

    @Test
    fun `pinned unknown window is not replaced by a newly configured profile limit`() = runBlocking {
        val events = mutableListOf<AssistantStreamEvent>()
        client(contextWindowTokens = 600_000).chatStreaming(
            listOf(AssistantMessage("user", "q")), "",
            policy = AssistantRequestSnapshot().toPolicy(),
        ) { events += it }
        assertEquals(null, events.filterIsInstance<AssistantStreamEvent.ContextUsage>().single().usage.windowTokens)
    }

    @Test
    fun `token overflow is rejected before any HTTP request without changing the current question`() = runBlocking {
        val question = "中".repeat(40_000)
        val error = runCatching {
            client(contextWindowTokens = 32_000).chatStreaming(listOf(AssistantMessage("user", question)), "") {}
        }.exceptionOrNull()
        assertTrue(error is AssistantModelException)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `chat payload applies profile budget without truncating current question`() = runBlocking {
        val history = (0 until 30).map { index ->
            AssistantMessage(if (index % 2 == 0) "user" else "assistant", "history-$index")
        }
        val current = "当前问题".repeat(10_000)
        listOf(AiHistoryBudget.COMPACT, AiHistoryBudget.STANDARD, AiHistoryBudget.EXTENDED).forEach { budget ->
            requests.clear()
            client(historyBudget = budget).chatStreaming(history + AssistantMessage("user", current), "本机数据") {}
            val payload = requests.single()
            val content = payload["messages"]!!.jsonArray.map { it.jsonObject["content"]!!.jsonPrimitive.content }
            assertEquals(history.takeLast(budget.maxMessages).map { it.content }, content.filter { it.startsWith("history-") })
            assertEquals(current, lastUserContent(payload))
            assertTrue(content.any { it.contains("本机数据") })
            assertFalse(payload.containsKey("context_length"))
            assertFalse(payload.containsKey("num_ctx"))
        }
    }

    @Test
    fun `responses payload uses the pinned budget instead of the changed profile`() = runBlocking {
        val history = (0 until 30).map { index ->
            AssistantMessage(if (index % 2 == 0) "user" else "assistant", "history-$index")
        }
        val model = client(
            searchProtocol = AiSearchProtocol.RESPONSES,
            historyBudget = AiHistoryBudget.EXTENDED,
            responses = listOf(200 to """event: response.completed
data: {"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","text":"{\"reply\":\"ok\"}"}]}]}}

"""),
        )
        model.chatStreaming(
            messages = history + AssistantMessage("user", "继续回答"),
            context = "本机数据",
            webSearchEnabled = true,
            policy = AssistantRequestPolicy(
                historyBudget = AiHistoryBudget.COMPACT,
                effectiveWebSearchEnabled = true,
            ),
        ) {}
        val payload = requests.single()
        val input = payload["input"]!!.jsonArray.map { it.jsonObject["content"]!!.jsonPrimitive.content }
        assertEquals(history.takeLast(12).map { it.content }, input.filter { it.startsWith("history-") })
        assertEquals("继续回答", input.last())
        assertTrue(input.any { it.contains("本机数据") })
        assertFalse(payload.containsKey("context_length"))
    }

    @Test
    fun `continuation preserves the original question and answer tail beyond the history budget`() = runBlocking {
        val original = "original question ".repeat(2_000)
        val answerTail = "a".repeat(CONTINUATION_ECHO_CHARS)
        client(historyBudget = AiHistoryBudget.COMPACT).chatStreaming(
            listOf(
                AssistantMessage("user", "older question"),
                AssistantMessage("assistant", "older answer"),
                AssistantMessage("user", original),
                AssistantMessage("assistant", answerTail),
                AssistantMessage("user", CONTINUE_INSTRUCTION),
            ),
            "",
        ) {}
        val content = requests.single()["messages"]!!.jsonArray.map { it.jsonObject["content"]!!.jsonPrimitive.content }
        assertTrue(content.contains(original))
        assertTrue(content.contains(answerTail))
        assertEquals(CONTINUE_INSTRUCTION, content.last())
    }

    @Test
    fun `answer recovery preserves an oversized original question and its image`() = runBlocking {
        val original = "original question ".repeat(2_000)
        val model = client(
            historyBudget = AiHistoryBudget.COMPACT,
            responses = listOf(
                200 to """data: {"choices":[{"delta":{"reasoning_content":"analysis"},"finish_reason":"length"}]}

data: [DONE]

""",
                200 to """data: {"choices":[{"delta":{"content":"{\"reply\":\"ok\"}"},"finish_reason":"stop"}]}

data: [DONE]

""",
            ),
        )
        val answer = model.chatStreaming(
            messages = listOf(AssistantMessage("user", original)),
            context = "",
            imageBase64s = listOf("ORIGINAL_IMAGE"),
        ) {}
        assertEquals("ok", answer.reply)
        assertEquals(2, requests.size)
        val content = requests.last()["messages"]!!.jsonArray.map { it.jsonObject["content"]!! }
        val question = content.filterIsInstance<JsonArray>().single()
        assertEquals(original, question.first().jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(question.toString().contains("ORIGINAL_IMAGE"))
        assertTrue(content.last() is JsonPrimitive)
    }

    @Test
    fun `chat payload applies the character budget as well as the message cap`() = runBlocking {
        val messages = listOf(
            AssistantMessage("user", "old question"),
            AssistantMessage("assistant", "x".repeat(13_000)),
            AssistantMessage("user", "recent question"),
            AssistantMessage("assistant", "y".repeat(13_000)),
            AssistantMessage("user", "follow up"),
        )
        client().chatStreaming(messages, "") {}
        val content = requests.single()["messages"]!!.jsonArray.map { it.jsonObject["content"]!!.jsonPrimitive.content }
        assertFalse(content.contains("old question"))
        assertFalse(content.contains("x".repeat(13_000)))
        assertTrue(content.contains("recent question"))
        assertTrue(content.contains("y".repeat(13_000)))
        assertEquals("follow up", content.last())
    }

    // ── 顺序：当前问题必须是最后一条 user 消息 ──

    @Test
    fun `current question is the final user message in the real payload`() = runBlocking {
        val model = client()
        model.chatStreaming(
            messages = listOf(
                AssistantMessage("user", "第一个问题"),
                AssistantMessage("assistant", "第一个回答"),
                AssistantMessage("user", "当前问题"),
            ),
            context = "本机数据",
        ) {}
        val payload = requests.single()
        assertEquals("当前问题", lastUserContent(payload))
    }

    /**
     * 易变上下文（时间 + 本机数据）必须排在**历史之后、当前问题之前**。
     *
     * 早先它们被追加到 messages 末尾，于是「最后一条消息」是上下文而不是问题。
     */
    @Test
    fun `volatile context is not appended after the current question`() = runBlocking {
        val model = client()
        model.chatStreaming(
            messages = listOf(AssistantMessage("user", "当前问题")),
            context = "今天的计划：数学两页",
        ) {}
        val payload = requests.single()
        val messages = payload["messages"]!!.jsonArray.map { it.jsonObject }
        // 最后一条必须是当前问题（user），不能是 system 的上下文段。
        assertEquals("user", messages.last()["role"]!!.jsonPrimitive.content)
        assertEquals("当前问题", lastUserContent(payload))
        // 本机数据仍必须真的出现在请求里（不能因为调整顺序就丢掉）。
        val all = messages.joinToString("\n") {
            it["content"]?.let { c ->
                if (c is JsonArray) c.toString() else c.jsonPrimitive.content
            }.orEmpty()
        }
        assertTrue("本机数据必须仍被发送", all.contains("数学两页"))
    }

    /** 稳定段在前：system 稳定段是第一条，历史在其后。 */
    @Test
    fun `stable system segment comes first and history follows`() = runBlocking {
        val model = client()
        model.chatStreaming(
            messages = listOf(
                AssistantMessage("user", "旧问题"),
                AssistantMessage("assistant", "旧回答"),
                AssistantMessage("user", "当前问题"),
            ),
            context = "数据",
        ) {}
        val roles = messageRoles(requests.single())
        assertEquals("system", roles.first())
        assertEquals("最后一条必须是当前问题", "user", roles.last())
    }

    // ── 图片归属：必须挂在原始问题上，不能挂到指令上 ──

    @Test
    fun `image is attached to the original user question not to a later instruction`() = runBlocking {
        val model = client()
        model.chatStreaming(
            messages = listOf(AssistantMessage("user", "带图问题")),
            context = "",
            imageBase64s = listOf("BASE64IMAGE"),
        ) {}
        val payload = requests.single()
        val messages = payload["messages"]!!.jsonArray.map { it.jsonObject }
        val withImage = messages.indexOfFirst { element ->
            (element["content"] as? JsonArray)?.any { part ->
                part.jsonObject["type"]?.jsonPrimitive?.content == "image_url"
            } == true
        }
        assertTrue("必须有一条带图消息", withImage >= 0)
        assertEquals(
            "图片必须挂在 user 消息上",
            "user",
            messages[withImage]["role"]!!.jsonPrimitive.content,
        )
        // 图片内容必须真的在请求体里。
        val all = messages.joinToString("\n") { it.toString() }
        assertTrue(all.contains("BASE64IMAGE"))
    }

    /** 没有图片时不得凭空出现 image_url 部分（避免把纯文本问题变成多模态）。 */
    @Test
    fun `text only request contains no image part`() = runBlocking {
        val model = client()
        model.chatStreaming(
            messages = listOf(AssistantMessage("user", "纯文本问题")),
            context = "",
        ) {}
        val body = requests.single().toString()
        assertFalse(body.contains("image_url"))
    }

    @Test
    fun `photo only history does not emit an empty text part in chat payload`() = runBlocking {
        val model = client(baseUrl = "https://relay.example/v1")
        model.chatStreaming(
            messages = listOf(
                AssistantMessage("user", "", imageBase64s = listOf("PRIOR_IMAGE")),
                AssistantMessage("assistant", "先前的题目解答"),
                AssistantMessage("user", "错判概率能否写成 erfc 函数"),
            ),
            context = "",
        ) {}
        val messages = requests.single()["messages"]!!.jsonArray.map { it.jsonObject }
        val content = messages.first { it["content"] is JsonArray }["content"]!!.jsonArray
        assertTrue(content.any { it.jsonObject["type"]!!.jsonPrimitive.content == "image_url" })
        assertTrue(content.none {
            it.jsonObject["type"]!!.jsonPrimitive.content == "text" &&
                it.jsonObject["text"]!!.jsonPrimitive.content.isBlank()
        })
        assertEquals("错判概率能否写成 erfc 函数", lastUserContent(requests.single()))
    }

    @Test
    fun `photo only current turn contains images without blank text parts`() = runBlocking {
        val model = client()
        model.chatStreaming(
            messages = listOf(AssistantMessage("user", " ")),
            context = "",
            imageBase64s = listOf("", " ", "CURRENT_IMAGE"),
        ) {}
        val content = requests.single()["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        assertEquals(1, content.size)
        assertEquals("image_url", content.single().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,CURRENT_IMAGE",
            content.single().jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `auxiliary vision request contains no empty text part`() = runBlocking {
        val model = client(responses = listOf(
            200 to """{"choices":[{"message":{"content":"识别到的题目"}}]}""",
        ))
        val answer = model.completeWithProfile("p", "转写题目", "", listOf("CURRENT_IMAGE"))
        assertEquals("识别到的题目", answer)
        val content = requests.single()["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        assertEquals(1, content.size)
        assertEquals("image_url", content.single().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `empty current question fails locally without sending the preceding question`() = runBlocking {
        val model = client()
        val error = runCatching {
            model.chatStreaming(
                messages = listOf(
                    AssistantMessage("user", "旧问题"),
                    AssistantMessage("assistant", "旧回答"),
                    AssistantMessage("user", " "),
                ),
                context = "",
            ) {}
        }.exceptionOrNull()
        assertTrue(error is AssistantModelException)
        assertEquals(AssistantModelException.Kind.CONFIG_INVALID, (error as AssistantModelException).kind)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `empty historical messages are excluded without losing valid turns`() = runBlocking {
        val model = client()
        model.chatStreaming(
            messages = listOf(
                AssistantMessage("user", "旧问题"),
                AssistantMessage("assistant", "旧回答"),
                AssistantMessage("user", "  "),
                AssistantMessage("assistant", "\n"),
                AssistantMessage("user", "当前问题"),
            ),
            context = "",
        ) {}
        val content = requests.single()["messages"]!!.jsonArray.map {
            it.jsonObject["content"]!!.jsonPrimitive.content
        }
        assertTrue(content.all { it.isNotBlank() })
        assertTrue(content.containsAll(listOf("旧问题", "旧回答", "当前问题")))
    }

    @Test
    fun `photo only history does not emit an empty text part in responses payload`() = runBlocking {
        val model = client(
            searchProtocol = AiSearchProtocol.RESPONSES,
            responses = listOf(200 to """event: response.completed
data: {"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","text":"{\"reply\":\"ok\"}"}]}]}}

"""),
        )
        model.chatStreaming(
            messages = listOf(
                AssistantMessage("user", "", imageBase64s = listOf("PRIOR_IMAGE")),
                AssistantMessage("assistant", "旧回答"),
                AssistantMessage("assistant", " "),
                AssistantMessage("user", "追问"),
            ),
            context = "",
            webSearchEnabled = true,
        ) {}
        val input = requests.single()["input"]!!.jsonArray.map { it.jsonObject }
        val content = input.first { it["content"] is JsonArray }["content"]!!.jsonArray
        assertTrue(content.any { it.jsonObject["type"]!!.jsonPrimitive.content == "input_image" })
        assertTrue(content.none {
            it.jsonObject["type"]!!.jsonPrimitive.content == "input_text" &&
                it.jsonObject["text"]!!.jsonPrimitive.content.isBlank()
        })
        assertTrue(input.filter { it["content"] is JsonPrimitive }.all {
            it["content"]!!.jsonPrimitive.content.isNotBlank()
        })
        assertEquals("追问", input.last()["content"]!!.jsonPrimitive.content)
    }

    // ── Responses 协议：最后一条 input 也必须是当前问题 ──

    @Test
    fun `responses payload keeps the current question last`() = runBlocking {
        val model = client(
            searchProtocol = AiSearchProtocol.RESPONSES,
            responses = listOf(
                200 to """event: response.output_text.delta
data: {"type":"response.output_text.delta","delta":"{\"reply\":\"ok\"}"}

event: response.completed
data: {"type":"response.completed","response":{"output":[]}}

""",
            ),
        )
        model.chatStreaming(
            messages = listOf(
                AssistantMessage("user", "旧问题"),
                AssistantMessage("assistant", "旧回答"),
                AssistantMessage("user", "当前问题"),
            ),
            context = "数据",
            webSearchEnabled = true,
        ) {}
        val payload = requests.single()
        val input = payload["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            "Responses 的 input 最后一条必须是当前问题",
            "当前问题",
            input.last()["content"]!!.jsonPrimitive.content,
        )
        assertEquals("user", input.last()["role"]!!.jsonPrimitive.content)
    }

    /** 两种协议都必须真的带上 stream 标志（否则拿不到逐片正文）。 */
    @Test
    fun `both protocols request streaming`() = runBlocking {
        val chat = client()
        chat.chatStreaming(listOf(AssistantMessage("user", "q")), "") {}
        assertTrue("chat 必须请求流式", requests.last()["stream"]!!.jsonPrimitive.content == "true")

        requests.clear()
        val responses = client(
            searchProtocol = AiSearchProtocol.RESPONSES,
            responses = listOf(
                200 to """event: response.completed
data: {"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","text":"{\"reply\":\"ok\"}"}]}]}}

""",
            ),
        )
        responses.chatStreaming(listOf(AssistantMessage("user", "q")), "", webSearchEnabled = true) {}
        assertTrue(
            "responses 必须请求流式",
            requests.last()["stream"]!!.jsonPrimitive.content == "true",
        )
    }

    /**
     * 模型名必须真的写进请求体。
     *
     * 推理档位的表达**因供应商而异**，测试要断言的是真实契约：
     * - OpenRouter ⇒ `reasoning.effort`；
     * - OpenAI 推理模型 ⇒ `reasoning_effort`；
     * - DeepSeek 等**没有**这个字段的端点 ⇒ **绝不能**塞进去（会 400），
     *   它的推理强度通过系统提示词里的「## 推理设定」表达。
     */
    @Test
    fun `model reaches the request body`() = runBlocking {
        val model = client()
        model.chatStreaming(listOf(AssistantMessage("user", "q")), "") {}
        val payload = requests.single()
        assertEquals("test-model", payload["model"]!!.jsonPrimitive.content)
    }

    /** DeepSeek 端点不得收到它不认识的推理字段（否则直接 400）。 */
    @Test
    fun `deepseek endpoint is not sent an unsupported reasoning field`() = runBlocking {
        val model = client(baseUrl = "https://api.deepseek.com/v1")
        model.chatStreaming(listOf(AssistantMessage("user", "q")), "") {}
        val payload = requests.single()
        assertFalse("DeepSeek 不支持 reasoning/reasoning_effort", payload.containsKey("reasoning"))
        assertFalse(payload.containsKey("reasoning_effort"))
        // 但推理强度必须仍然被表达出来（走系统提示词的稳定段）。
        val system = payload["messages"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["role"]!!.jsonPrimitive.content == "system" }
        assertTrue(
            "推理设定必须出现在系统提示词里",
            system["content"]!!.jsonPrimitive.content.contains("推理设定"),
        )
    }

    /** OpenRouter 端点必须收到 reasoning.effort（它靠这个字段控制思考强度）。 */
    @Test
    fun `openrouter endpoint receives reasoning effort field`() = runBlocking {
        val model = client(baseUrl = "https://openrouter.ai/api/v1")
        model.chatStreaming(listOf(AssistantMessage("user", "q")), "") {}
        val payload = requests.single()
        assertEquals(
            "medium",
            payload["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content,
        )
    }

    /** max_tokens 必须是合理上限（不能是 0 或缺省导致回答被截断）。 */
    @Test
    fun `max tokens is present and positive`() = runBlocking {
        val model = client()
        model.chatStreaming(listOf(AssistantMessage("user", "q")), "") {}
        assertTrue(requests.single()["max_tokens"]!!.jsonPrimitive.int > 0)
    }
}
