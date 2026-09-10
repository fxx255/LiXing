package com.example.lixing.data.assistant

import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.di.IoDispatcher
import android.util.Log
import com.example.lixing.domain.assistant.AssistantContextKind
import com.example.lixing.domain.assistant.AssistantUserProfile
import com.example.lixing.domain.assistant.AssistantMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 直连大模型异常。UI 层据此给出「未配置 / 密钥错误 / 网络 / 服务异常」等提示。 */
class AssistantModelException(
    val kind: Kind,
    message: String,
) : Exception(message) {
    enum class Kind { NOT_CONFIGURED, UNAUTHORIZED, NETWORK, SERVER, INVALID_RESPONSE }
}

/** Incremental output emitted by providers that support Chat Completions streaming. */
sealed interface AssistantStreamEvent {
    data class ReasoningDelta(val text: String) : AssistantStreamEvent
    data class AnswerDelta(val text: String) : AssistantStreamEvent
}

/**
 * App 直连 OpenAI 兼容大模型接口。
 *
 * 设计：接口地址、模型名、密钥全部由用户在设置页填写，
 * 因此换用任意一家 OpenAI 兼容服务（DeepSeek / 月之暗面 / 智谱 / OpenAI / OpenRouter 等）
 * 都不需要改代码或云函数。密钥由 [AiCredentialStore] 用 Android Keystore 加密，不进备份。
 *
 * 系统提示词（含计划修改 JSON 协议）内置在 [SYSTEM_PROMPT]，随请求一起发出。
 */
@Singleton
class AssistantModelClient @Inject constructor(
    private val prefsRepository: UserPreferencesRepository,
    private val credentialStore: AiCredentialStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /** 大模型响应较慢，读超时给足；连接仍保持短超时。 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun isConfigured(): Boolean = withContext(io) {
        val prefs = prefsRepository.current()
        val profile = credentialStore.activeProfile()
        val baseUrl = profile?.baseUrl ?: prefs.aiBaseUrl
        val model = profile?.model ?: prefs.aiModel
        prefs.aiAssistantEnabled &&
            baseUrl.isNotBlank() &&
            model.isNotBlank() &&
            !credentialStore.load().isNullOrBlank()
    }

    suspend fun isVisionEnabled(): Boolean = withContext(io) {
        credentialStore.activeProfile()?.visionEnabled ?: prefsRepository.current().aiVisionEnabled
    }

    /**
     * 发送一轮对话，返回解析后的回复与计划建议。
     *
     * [imageBase64s] 非空时，以 OpenAI 兼容的 vision 内容块附在最后一条用户消息上；
     * 只有多模态模型才应传图（由设置开关控制）。
     */
    suspend fun chat(
        messages: List<AssistantMessage>,
        context: String,
        imageBase64s: List<String> = emptyList(),
        webSearchEnabled: Boolean = false,
    ): ParsedAssistantReply = chatStreaming(messages, context, imageBase64s, webSearchEnabled) { }

    /**
     * Streams provider-supplied reasoning and answer text, then parses the structured reply.
     *
     * [webSearchEnabled] 为 true 且总开关开启时：
     * - DeepSeek（api.deepseek.com）→ 走 OpenAI Responses API 服务端内置联网搜索；
     * - 小米 MiMo（api.xiaomimimo.com）→ 在 Chat Completions 里注入 web_search 工具；
     * 两种原生路径失败都会自动回退普通对话。
     */
    suspend fun chatStreaming(
        messages: List<AssistantMessage>,
        context: String,
        imageBase64s: List<String> = emptyList(),
        webSearchEnabled: Boolean = false,
        onEvent: suspend (AssistantStreamEvent) -> Unit,
    ): ParsedAssistantReply = withContext(io) {
        val configured = requireConfigured()
        val prefs = prefsRepository.current()
        val userProfile = AssistantUserProfile(prefs.assistantNickname, prefs.assistantCity)
        val webSearchOn = webSearchEnabled &&
            prefs.aiWebSearchEnabled &&
            configured.searchProtocol != AiSearchProtocol.OFF
        if (webSearchOn && configured.searchProtocol == AiSearchProtocol.RESPONSES) {
            try {
                return@withContext chatDeepSeekNativeResponses(configured, messages, context, imageBase64s, userProfile, onEvent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AssistantModel", "Responses native search failed, fallback to normal chat", e)
            }
        }
        val chatCompletionsSearch = webSearchOn && configured.searchProtocol == AiSearchProtocol.CHAT_COMPLETIONS
        val payload = buildChatPayload(
            configured,
            messages,
            context,
            imageBase64s,
            stream = true,
            webSearchEnabled = chatCompletionsSearch,
            userProfile = userProfile,
        )
        val request = Request.Builder()
            .url(completionsUrl(configured.baseUrl))
            .header("Authorization", "Bearer ${configured.apiKey}")
            .post(payload.toString().toRequestBody(mediaType))
            .build()

        val firstOutput = executeStreaming(request, onEvent)
        val output = if (firstOutput.answer.isBlank() && firstOutput.reasoning.isNotBlank()) {
            onEvent(AssistantStreamEvent.ReasoningDelta("\n\n…首轮思考较长，正在续写最终答案…\n"))
            try {
                recoverFinalAnswer(
                    configured = configured,
                    messages = messages,
                    context = context,
                    imageBase64s = imageBase64s,
                    webSearchEnabled = chatCompletionsSearch,
                    previous = firstOutput,
                    onEvent = onEvent,
                )
            } catch (error: Exception) {
                Log.w("AssistantModel", "final answer recovery failed", error)
                firstOutput
            }
        } else {
            firstOutput
        }
        val answer = output.answer.trim()
        if (answer.isEmpty() && output.reasoning.isNotBlank()) {
            return@withContext ParsedAssistantReply(
                reply = buildString {
                    append("### 未完成的解题过程\n\n")
                    append(output.reasoning.takeLast(REASONING_FALLBACK_CHARS).trim())
                },
                actions = emptyList(),
                warnings = listOf("服务商两次只返回思考内容，已展示可恢复的推理尾部"),
            )
        }
        if (answer.isEmpty()) {
            throw AssistantModelException(
                AssistantModelException.Kind.INVALID_RESPONSE,
                if (output.finishReason == "length") "模型输出达到长度上限，未生成最终答案" else "模型未返回有效内容",
            )
        }
        try {
            val userPrompt = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
            val parsed = guardMissingEnglishActions(
                guardMissingPlanActions(AssistantResponseParser.parse(answer), userPrompt),
                userPrompt,
            ).copy(truncated = output.finishReason == "length")
            val distinctCitations = output.citations.distinctBy(Citation::url)
            if (distinctCitations.isEmpty()) {
                parsed
            } else {
                parsed.copy(
                    reply = buildString {
                        append(parsed.reply)
                        append("\n\n### 来源\n")
                        distinctCitations.forEach { citation ->
                            append("- [")
                            append(citation.title.replace("[", "").replace("]", "").ifBlank { citation.url })
                            append("](").append(citation.url).append(")\n")
                        }
                    }.trim(),
                )
            }
        } catch (e: Exception) {
            throw AssistantModelException(
                AssistantModelException.Kind.INVALID_RESPONSE,
                "无法解析模型返回：${e.message ?: "未知错误"}",
            )
        }
    }

    /**
     * Fetches model ids from the standard OpenAI-compatible /models endpoint.
     *
     * [apiKey] 可以为空：此时依次回退到 [profileId] 对应配置已保存的密钥、
     * 以及当前生效配置的密钥。这样编辑已有配置时（密钥框出于安全不回显，
     * 始终为空）也能随时获取模型列表，且可重复点击。
     */
    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String = "",
        profileId: String? = null,
    ): List<String> = withContext(io) {
        require(baseUrl.isNotBlank()) { "请填写接口地址" }
        val key = resolveFetchKey(
            inputKey = apiKey,
            profileKey = profileId?.let { credentialStore.credentialsFor(it)?.apiKey },
            activeKey = credentialStore.load(),
        )
        require(key.isNotEmpty()) { "请填写 API 密钥" }
        val request = Request.Builder()
            .url(modelsUrl(baseUrl))
            .header("Authorization", "Bearer $key")
            .get()
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw AssistantModelException(AssistantModelException.Kind.NETWORK, "无法获取模型列表：${e.message}")
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw AssistantModelException(
                    if (resp.code == 401 || resp.code == 403) AssistantModelException.Kind.UNAUTHORIZED
                    else AssistantModelException.Kind.SERVER,
                    "获取模型列表失败（HTTP ${resp.code}）：${text.take(160)}",
                )
            }
            parseModelIds(text).ifEmpty {
                throw AssistantModelException(AssistantModelException.Kind.INVALID_RESPONSE, "接口没有返回可用模型")
            }
        }
    }

    /**
     * 让模型根据用户问题预判需要哪些本机上下文。
     *
     * 这是一次廉价的小请求（max_tokens 100）；任何失败都回退为空集，
     * 由用户手动勾选的内容兜底，绝不阻塞主对话。
     */
    suspend fun chooseContext(userPrompt: String): Set<AssistantContextKind> =
        withContext(io) {
            try {
                val configured = requireConfigured()
                val baseUrl = configured.baseUrl
                val model = configured.model
                val apiKey = configured.apiKey
                val payload = buildJsonObject {
                    put("model", model)
                    put("temperature", 0.0)
                    put("max_tokens", 100)
                    put("messages", buildJsonArray {
                        add(buildJsonObject { put("role", "system"); put("content", CHOOSE_CONTEXT_PROMPT) })
                        add(buildJsonObject { put("role", "user"); put("content", userPrompt) })
                    })
                }
                val request = Request.Builder()
                    .url(completionsUrl(baseUrl))
                    .header("Authorization", "Bearer $apiKey")
                    .post(payload.toString().toRequestBody(mediaType))
                    .build()
                parseContextKinds(executeForContent(request))
            } catch (e: Exception) {
                Log.w("AssistantModel", "chooseContext failed, fallback to manual only", e)
                emptySet()
            }
        }

    /** 用一次最小请求测试连通性与密钥，返回给用户看的一句话结果。 */
    suspend fun testConnection(): String = withContext(io) {
        val prefs = prefsRepository.current()
        val profile = credentialStore.activeProfile()
        val baseUrl = (profile?.baseUrl ?: prefs.aiBaseUrl).trim()
        val model = (profile?.model ?: prefs.aiModel).trim()
        if (baseUrl.isEmpty() || model.isEmpty()) {
            throw AssistantModelException(
                AssistantModelException.Kind.NOT_CONFIGURED,
                "请先填写接口地址和模型名",
            )
        }
        val apiKey = credentialStore.load()
        if (apiKey.isNullOrBlank()) {
            throw AssistantModelException(
                AssistantModelException.Kind.NOT_CONFIGURED,
                "请先保存 API 密钥",
            )
        }
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", 1)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", "ping") })
            })
        }
        val request = Request.Builder()
            .url(completionsUrl(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> "连接成功，模型「$model」工作正常"
                response.code == 401 || response.code == 403 ->
                    throw AssistantModelException(
                        AssistantModelException.Kind.UNAUTHORIZED,
                        "API 密钥被拒绝（HTTP ${response.code}），请核对后重新保存",
                    )
                else -> {
                    val hint = response.body?.string().orEmpty().take(160)
                    throw AssistantModelException(
                        AssistantModelException.Kind.SERVER,
                        "接口返回 HTTP ${response.code}：$hint",
                    )
                }
            }
        }
    }

    /**
     * 以指定模型配置做一次非流式调用（不占用当前激活配置）。
     * 用于多模态识题转写与饮食校准等辅助任务。
     */
    suspend fun completeWithProfile(
        profileId: String,
        systemPrompt: String,
        userText: String,
        imageBase64s: List<String> = emptyList(),
        maxTokens: Int = 2048,
    ): String = withContext(io) {
        val credentials = credentialStore.credentialsFor(profileId)
            ?: throw AssistantModelException(
                AssistantModelException.Kind.NOT_CONFIGURED,
                "所选多模态模型配置不存在或缺少密钥，请在设置页重新选择",
            )
        val payload = buildJsonObject {
            put("model", credentials.model)
            put("temperature", 0.2)
            put("max_tokens", maxTokens)
            put("stream", false)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", systemPrompt) })
                add(buildJsonObject {
                    put("role", "user")
                    if (imageBase64s.isEmpty()) {
                        put("content", userText)
                    } else {
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", userText) })
                            imageBase64s.forEach { base64 ->
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$base64") })
                                })
                            }
                        })
                    }
                })
            })
        }
        val request = Request.Builder()
            .url(completionsUrl(credentials.baseUrl))
            .header("Authorization", "Bearer ${credentials.apiKey}")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        val response = client.newCall(request).execute()
        val body = response.use { it.body?.string().orEmpty() }
        if (!response.isSuccessful) {
            throw AssistantModelException(
                AssistantModelException.Kind.SERVER,
                "多模态模型调用失败（HTTP ${response.code}）：${body.take(160)}",
            )
        }
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw AssistantModelException(AssistantModelException.Kind.SERVER, "多模态模型返回了无法解析的结果")
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw AssistantModelException(AssistantModelException.Kind.SERVER, "多模态模型未返回内容")
        val content = when (val rawContent = message["content"]) {
            is JsonPrimitive -> rawContent.contentOrNull.orEmpty()
            is JsonArray -> rawContent.mapNotNull { part ->
                (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
            }.joinToString("")
            else -> ""
        }.trim()
        if (content.isEmpty()) {
            throw AssistantModelException(AssistantModelException.Kind.SERVER, "多模态模型未返回有效内容")
        }
        content
    }

    private suspend fun requireConfigured(): ConfiguredModel {
        val prefs = prefsRepository.current()
        val profile = credentialStore.activeProfile()
        val baseUrl = (profile?.baseUrl ?: prefs.aiBaseUrl).trim()
        val model = (profile?.model ?: prefs.aiModel).trim()
        val apiKey = credentialStore.load().orEmpty()
        if (!prefs.aiAssistantEnabled || baseUrl.isEmpty() || model.isEmpty() || apiKey.isBlank()) {
            throw AssistantModelException(
                AssistantModelException.Kind.NOT_CONFIGURED,
                "AI 助手未配置：请在设置页开启并填写接口地址、模型名与 API 密钥",
            )
        }
        return ConfiguredModel(
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey,
            searchProtocol = profile?.searchProtocol ?: AiSearchProtocol.RESPONSES,
            reasoningEffort = profile?.reasoningEffort ?: AiReasoningEffort.LOW,
        )
    }

    private data class ConfiguredModel(
        val baseUrl: String,
        val model: String,
        val apiKey: String,
        val searchProtocol: AiSearchProtocol,
        val reasoningEffort: AiReasoningEffort,
    )

    private fun buildChatPayload(
        configured: ConfiguredModel,
        messages: List<AssistantMessage>,
        context: String,
        imageBase64s: List<String>,
        stream: Boolean,
        webSearchEnabled: Boolean = false,
        userProfile: AssistantUserProfile = AssistantUserProfile(),
    ): JsonObject = buildJsonObject {
        put("model", configured.model)
        put("temperature", 0.4)
        put("max_tokens", MAX_OUTPUT_TOKENS)
        put("stream", stream)
        nativeReasoningRequestFields(
            baseUrl = configured.baseUrl,
            model = configured.model,
            effort = configured.reasoningEffort,
        ).forEach { (key, value) -> put(key, value) }
        // Chat Completions 内置联网搜索（如小米 MiMo）：注入服务端 web_search 工具。
        if (webSearchEnabled) {
            put("tools", buildJsonArray {
                add(buildJsonObject { put("type", "web_search"); put("force_search", true) })
            })
        }
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", buildSystemPrompt(userProfile)) })
            add(buildJsonObject {
                put("role", "system")
                put("content", reasoningInstruction(configured.reasoningEffort))
            })
            if (context.isNotBlank()) {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "以下是用户勾选的本机学习数据（只读上下文）：\n\n$context")
                })
                add(buildJsonObject { put("role", "assistant"); put("content", "好的，我已了解这些上下文。") })
            }
            messages.forEachIndexed { index, message ->
                val withImages = imageBase64s.isNotEmpty() && index == messages.lastIndex && message.role == "user"
                add(buildJsonObject {
                    put("role", message.role)
                    if (withImages) {
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", message.content) })
                            imageBase64s.forEach { base64 ->
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$base64") })
                                })
                            }
                        })
                    } else {
                        put("content", message.content)
                    }
                })
            }
        })
    }

    private data class StreamOutput(
        val answer: String,
        val reasoning: String,
        val finishReason: String?,
        val citations: List<Citation>,
    )

    internal data class Citation(val title: String, val url: String)

    /** Continues a reasoning-only response in a fresh request so the model has a new output budget. */
    private suspend fun recoverFinalAnswer(
        configured: ConfiguredModel,
        messages: List<AssistantMessage>,
        context: String,
        imageBase64s: List<String>,
        webSearchEnabled: Boolean,
        previous: StreamOutput,
        onEvent: suspend (AssistantStreamEvent) -> Unit,
    ): StreamOutput {
        val recoveryMessages = messages + listOf(
            AssistantMessage(
                role = "assistant",
                content = "上一轮内部分析的末尾如下，仅用于续写，不要逐字复述：\n\n" +
                    previous.reasoning.takeLast(RECOVERY_REASONING_CHARS),
            ),
            AssistantMessage(
                role = "user",
                content = "请基于前面的题目和分析，立即生成最终答案。不要再展开冗长的内部思考；正文保留用户需要看到的公式与推导。严格遵守系统要求，只输出完整 JSON 对象。",
            ),
        )
        val payload = buildChatPayload(
            configured = configured,
            messages = recoveryMessages,
            context = context,
            imageBase64s = imageBase64s,
            stream = true,
            webSearchEnabled = webSearchEnabled,
        )
        val request = Request.Builder()
            .url(completionsUrl(configured.baseUrl))
            .header("Authorization", "Bearer ${configured.apiKey}")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        val recovered = executeStreaming(request, onEvent)
        return recovered.copy(
            reasoning = (previous.reasoning + recovered.reasoning).takeLast(MAX_REASONING_CAPTURE_CHARS),
            citations = previous.citations + recovered.citations,
        )
    }

    private suspend fun executeStreaming(
        request: Request,
        onEvent: suspend (AssistantStreamEvent) -> Unit,
    ): StreamOutput {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw AssistantModelException(
                AssistantModelException.Kind.NETWORK,
                "网络错误：${e.message ?: "无法连接模型接口"}",
            )
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                val text = resp.body?.string().orEmpty()
                val kind = if (resp.code == 401 || resp.code == 403) {
                    AssistantModelException.Kind.UNAUTHORIZED
                } else {
                    AssistantModelException.Kind.SERVER
                }
                throw AssistantModelException(kind, "接口返回 HTTP ${resp.code}：${text.take(240).ifEmpty { "无返回内容" }}")
            }
            val body = resp.body ?: throw AssistantModelException(
                AssistantModelException.Kind.INVALID_RESPONSE,
                "模型接口没有返回响应体",
            )
            val answer = StringBuilder()
            val reasoning = StringBuilder()
            val citations = mutableListOf<Citation>()
            var finishReason: String? = null
            var sawSse = false
            val raw = StringBuilder()
            val source = body.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) {
                    if (line.isNotBlank()) raw.append(line)
                    continue
                }
                sawSse = true
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val chunk = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: continue
                citations += extractCitations(chunk["citations"])
                val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: continue
                val message = (choice["delta"] as? JsonObject) ?: (choice["message"] as? JsonObject) ?: continue
                citations += extractCitations(message["annotations"])
                citations += extractCitations(message["citations"])
                finishReason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull ?: finishReason
                extractText(
                    message["reasoning_content"] ?: message["reasoning"] ?: message["reasoning_details"],
                ).takeIf { it.isNotEmpty() }?.let {
                    reasoning.appendTail(it, MAX_REASONING_CAPTURE_CHARS)
                    onEvent(AssistantStreamEvent.ReasoningDelta(it))
                }
                extractText(message["content"]).takeIf { it.isNotEmpty() }?.let {
                    answer.append(it)
                    onEvent(AssistantStreamEvent.AnswerDelta(it))
                }
            }
            if (!sawSse) {
                val root = runCatching { json.parseToJsonElement(raw.toString()) as? JsonObject }.getOrNull()
                    ?: throw AssistantModelException(AssistantModelException.Kind.INVALID_RESPONSE, "模型返回不是合法 JSON")
                val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                val message = choice?.get("message") as? JsonObject
                citations += extractCitations(root["citations"])
                citations += extractCitations(message?.get("annotations"))
                citations += extractCitations(message?.get("citations"))
                finishReason = (choice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull
                val reasoningText = extractText(
                    message?.get("reasoning_content") ?: message?.get("reasoning") ?: message?.get("reasoning_details"),
                )
                val answerText = extractText(message?.get("content"))
                if (reasoningText.isNotEmpty()) {
                    reasoning.appendTail(reasoningText, MAX_REASONING_CAPTURE_CHARS)
                    onEvent(AssistantStreamEvent.ReasoningDelta(reasoningText))
                }
                if (answerText.isNotEmpty()) {
                    answer.append(answerText)
                    onEvent(AssistantStreamEvent.AnswerDelta(answerText))
                }
            }
            return StreamOutput(answer.toString(), reasoning.toString(), finishReason, citations)
        }
    }

    private fun extractCitations(element: JsonElement?): List<Citation> = (element as? JsonArray).orEmpty()
        .mapNotNull { annotation ->
            if (annotation is JsonPrimitive) {
                val url = annotation.contentOrNull?.trim().orEmpty()
                return@mapNotNull url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    ?.let { Citation("", it) }
            }
            val obj = annotation as? JsonObject ?: return@mapNotNull null
            val citation = (obj["url_citation"] as? JsonObject) ?: obj
            val url = (citation["url"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
            val title = (citation["title"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
            Citation(title, url)
        }

    /** 从 OpenAI 兼容响应里取出 choices[0].message.content。 */
    private fun executeForContent(request: Request): String {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw AssistantModelException(
                AssistantModelException.Kind.NETWORK,
                "网络错误：${e.message ?: "无法连接模型接口"}",
            )
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                if (resp.code == 401 || resp.code == 403) {
                    throw AssistantModelException(
                        AssistantModelException.Kind.UNAUTHORIZED,
                        "API 密钥被拒绝（HTTP ${resp.code}）",
                    )
                }
                throw AssistantModelException(
                    AssistantModelException.Kind.SERVER,
                    "接口返回 HTTP ${resp.code}：${text.take(200).ifEmpty { "无返回内容" }}",
                )
            }
            val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: throw AssistantModelException(
                    AssistantModelException.Kind.INVALID_RESPONSE,
                    "模型返回不是合法 JSON",
                )
            val content = root["choices"]?.jsonArray
                ?.firstOrNull()?.let { it as? JsonObject }
                ?.get("message")?.let { it as? JsonObject }
                ?.get("content")?.let(::extractText)
            if (content.isNullOrBlank()) {
                throw AssistantModelException(
                    AssistantModelException.Kind.INVALID_RESPONSE,
                    "模型未返回有效内容",
                )
            }
            return content
        }
    }

    // ---------------- DeepSeek 服务端内置联网搜索（OpenAI Responses API） ----------------

    private suspend fun chatDeepSeekNativeResponses(
        configured: ConfiguredModel,
        messages: List<AssistantMessage>,
        context: String,
        imageBase64s: List<String>,
        userProfile: AssistantUserProfile,
        onEvent: suspend (AssistantStreamEvent) -> Unit,
    ): ParsedAssistantReply {
        val payload = buildDeepSeekResponsesPayload(configured, messages, context, imageBase64s, userProfile)
        val request = Request.Builder()
            .url(deepSeekResponsesUrl(configured.baseUrl))
            .header("Authorization", "Bearer ${configured.apiKey}")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw AssistantModelException(
                AssistantModelException.Kind.NETWORK,
                "网络错误：${e.message ?: "无法连接 DeepSeek 接口"}",
            )
        }
        return response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val kind = if (resp.code == 401 || resp.code == 403) {
                    AssistantModelException.Kind.UNAUTHORIZED
                } else {
                    AssistantModelException.Kind.SERVER
                }
                throw AssistantModelException(kind, "DeepSeek 返回 HTTP ${resp.code}：${text.take(240).ifEmpty { "无返回内容" }}")
            }
            val output = parseDeepSeekResponses(text)
            val answer = output.text.trim()
            if (answer.isEmpty()) {
                throw AssistantModelException(
                    AssistantModelException.Kind.INVALID_RESPONSE,
                    "DeepSeek 未返回有效内容",
                )
            }
            output.thinking.takeIf { it.isNotBlank() }?.let {
                onEvent(AssistantStreamEvent.ReasoningDelta(it))
            }
            onEvent(AssistantStreamEvent.AnswerDelta(answer))
            val parsed = try {
                val userPrompt = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
                guardMissingEnglishActions(
                    guardMissingPlanActions(AssistantResponseParser.parse(answer), userPrompt),
                    userPrompt,
                )
            } catch (e: Exception) {
                throw AssistantModelException(
                    AssistantModelException.Kind.INVALID_RESPONSE,
                    "无法解析模型返回：${e.message ?: "未知错误"}",
                )
            }
            val distinctCitations = output.citations.distinctBy(Citation::url)
            if (distinctCitations.isEmpty()) {
                parsed
            } else {
                parsed.copy(
                    reply = buildString {
                        append(parsed.reply)
                        append("\n\n### 来源\n")
                        distinctCitations.forEach { citation ->
                            append("- [")
                            append(citation.title.replace("[", "").replace("]", "").ifBlank { citation.url })
                            append("](").append(citation.url).append(")\n")
                        }
                    }.trim(),
                )
            }
        }
    }

    private fun buildDeepSeekResponsesPayload(
        configured: ConfiguredModel,
        messages: List<AssistantMessage>,
        context: String,
        imageBase64s: List<String>,
        userProfile: AssistantUserProfile,
    ): JsonObject = buildJsonObject {
        put("model", configured.model)
        put("max_output_tokens", MAX_OUTPUT_TOKENS)
        put("instructions", buildString {
            append(buildSystemPrompt(userProfile))
            append("\n\n").append(reasoningInstruction(configured.reasoningEffort))
        })
        put("tools", buildJsonArray { add(buildJsonObject { put("type", "web_search") }) })
        put("input", buildJsonArray {
            if (context.isNotBlank()) {
                add(buildJsonObject { put("role", "user"); put("content", "以下是你需要依据的本机学习数据（只读）：\n\n$context") })
                add(buildJsonObject { put("role", "assistant"); put("content", "好的，我已了解这些上下文。") })
            }
            messages.forEachIndexed { index, message ->
                val withImages = imageBase64s.isNotEmpty() && index == messages.lastIndex && message.role == "user"
                add(buildJsonObject {
                    put("role", message.role)
                    if (withImages) {
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("type", "input_text"); put("text", message.content) })
                            imageBase64s.forEach { base64 ->
                                add(buildJsonObject {
                                    put("type", "input_image")
                                    put("image_url", "data:image/jpeg;base64,$base64")
                                })
                            }
                        })
                    } else {
                        put("content", message.content)
                    }
                })
            }
        })
    }

    /**
     * 系统提示词 = 通用协议 + 用户个性化（可空，逐条注入）+ 当前时间。
     * 时间永远注入：让「今天/本周/昼夜问候」有唯一基准；个性化条目没填就不出现。
     */
    private fun buildSystemPrompt(user: AssistantUserProfile): String = buildString {
        append(SYSTEM_PROMPT)
        val nickname = user.nickname.trim()
        val city = user.city.trim()
        append("\n\n## 当前时间\n")
        val now = java.time.LocalDateTime.now()
        val weekday = now.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.CHINESE)
        append("现在是 ${now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}（$weekday）。")
        append("涉及「今天/明天/本周/上周」等相对时间一律以这条时间为基准，不要自行猜测日期。")
        if (nickname.isNotEmpty() || city.isNotEmpty()) {
            append("\n\n## 用户个性化（必须遵守）\n")
            if (nickname.isNotEmpty()) {
                append("- 称呼：用「$nickname」称呼用户，问候与正文中自然使用；不要叫「用户」。\n")
            }
            if (city.isNotEmpty()) {
                append("- 所在城市：$city。涉及时区、昼夜问候时按该城市推断；中国境内的城市按 UTC+8。\n")
            }
        }
    }

    private companion object {
        const val MAX_OUTPUT_TOKENS = 8192
        const val RECOVERY_REASONING_CHARS = 12_000
        const val REASONING_FALLBACK_CHARS = 8_000
        const val MAX_REASONING_CAPTURE_CHARS = 64_000

        /** 上下文预判提示词：只输出 JSON，不做任何回答。 */
        const val CHOOSE_CONTEXT_PROMPT = """你是学习 App 的上下文调度器。根据用户的问题，判断回答它需要读取哪些本机数据。
可选种类：
- PLAN：当前学习计划、科目、时段、任务模板（涉及计划/任务/时段调整、安排建议时需要）
- TODAY：今天的任务列表与状态（涉及今天的安排、进度时需要）
- STATS：近 7 天专注与完成率统计（涉及薄弱科目、投入、效率分析时需要）
- ENGLISH：用户积累的英语单词/短语/句子（涉及英语测验、复习、出题时需要）
- WORDS：今天的墨墨背单词列表（涉及今天背的单词时需要）
用户要求今日请假、保留或跳过今日任务时必须选择 TODAY。
只选必要的；闲聊或与学习数据无关的问题一律不选。只输出 JSON：{"needed":["PLAN","TODAY"]}，没有需要的就输出 {"needed":[]}。"""

        /** 计划修改 JSON 协议与行为边界。模型只建议，执行由本地校验 + 用户确认完成。 */
        const val SYSTEM_PROMPT = """你是「砺行」App 里的学习助手。砺行是一个按时段打卡的学习自律应用，
用户的计划由「科目 / 时段 / 任务模板」组成，每天会把模板物化成当日任务。

回答规则：
1. 用简体中文回答，完整、细致、具体、可执行。先给结论，再分步骤解释关键依据，不要省略必要推导。
2. 只基于给出的上下文回答；上下文没有的信息就直说不知道，不要编造。
3. 当且仅当用户明确要求修改计划时，才在 plan_actions 里给出建议；否则 plan_actions 必须是空数组。动作只是待确认方案，在用户确认前绝不能声称“已处理、已提交、已生效”，只能说“已生成方案，请确认”。
4. 你不能物理删除任何数据，不能改已完成任务、积分、成就。用户要求“删掉/取消/今天不做”某些今日任务时，应使用 SKIP_TODAY_TASK 将待做任务仅在今天跳过；不要声称 App 有手动删除今日任务的入口。（唯一例外是用户明确要求删除自己积累的英语条目，见第 11 条的 DELETE_ENGLISH_ENTRY。）
5. 用户可能提供题目图片，或中文/拉丁双通道识别出的文字。应交叉校对两组结果；只有歧义会实质改变题意时才询问用户，不要因个别噪声拒绝整题，也不能擅自补造关键条件。面向用户的回复中不要提及 OCR、识别引擎、乱码等技术细节；当个别文字不清晰时，最多用「识别不清」说明，请用户核对即可。
6. 数学、物理、统计等问题必须给出必要公式和推导。LaTeX 行内公式前后各用两个美元符号；块级公式的起止双美元符号必须各占一行。正文使用 Markdown。
7. 启用联网搜索时，涉及事实、时效信息或外部资料的回答应给出 Markdown 链接来源。
8. 控制内部思考长度，必须为最终 JSON 答案保留充足输出空间；不要把输出额度全部耗在 reasoning 中。
9. 用户说“今天、今日、临时、只改一天”时，只能使用 UPDATE_TODAY_TASK、SKIP_TODAY_TASK、TAKE_TODAY_OFF 或 KEEP_TODAY_TASKS；它们只改变今日数据。只有用户明确要求以后也生效时，才能修改时段或任务模板。
10. 用户要求今天全部请假时只返回一个 TAKE_TODAY_OFF，不要逐项列任务。用户要求“今天请假但保留某些任务”时只返回一个 KEEP_TODAY_TASKS，并把要保留的真实任务 id 放入 keepTaskIds；不要为其余任务逐条生成 SKIP_TODAY_TASK。
11. 当且仅当用户明确要求把内容记入、修改或从其「英语积累」中删除时，才在 english_actions 里给出建议；否则 english_actions 必须是空数组。用户可以输入文字、发题目/笔记照片让你先识别再提炼，也可以让你总结一段材料里的好词好句。
    - 新增时：content 必须是英文原文（单词/短语/句子），meaning 是你给出的中文释义，type 用 WORD / PHRASE / SENTENCE 判断。
    - 从照片或材料里提炼时，只挑真正值得积累的表达，宁少勿多；不要重复用户已经积累过的内容（上下文里出现过的英文原文就不要再新增）。
    - 修改或删除必须使用上下文里出现的真实 id，不许猜测；用户没有说明改什么时不要擅自改动已有条目。
    - 这些动作只是待确认方案，在用户确认前绝不能声称“已加入、已保存、已修改、已删除”，只能说“已生成英语积累方案，请确认”。

输出格式（必须是可以直接 JSON.parse 的单个对象，不要 Markdown 代码块）：
{"reply": "给用户看的正文", "plan_actions": [ ... ], "english_actions": [ ... ]}

plan_actions 支持的类型：
- {"kind":"UPDATE_TIME_SLOT","slotId":数字,"startTime":"HH:mm"可省,"endTime":"HH:mm"可省,"requiredTaskCount":数字可省(0~20,0=该时段任务全部都要完成),"reason":"简短原因"}，时间与该时段「至少完成几项」至少给一个
- {"kind":"UPDATE_TASK_TEMPLATE","templateId":数字, 可选字段:"title"(<=60字)/"targetValue"(1~9999整数)/"timeSlotId"/"repeatRule"(DAILY|WEEKLY_DAYS|EVERY_N_DAYS)/"isKeystone"/"isEnabled", "reason":"..."}
- {"kind":"INSERT_TASK_TEMPLATE","subjectId":数字,"timeSlotId":数字,"title":"...","taskType"(LECTURE|PRACTICE|MEMORIZE|REVIEW|CUSTOM),"targetType"(MINUTES|COUNT|PAGES|BOOLEAN),"targetValue":数字,"repeatRule":同上,"isKeystone":布尔,"note"可省,"reason":"..."}
- {"kind":"UPDATE_TODAY_TASK","taskId":数字, 可选字段:"targetValue"(1~9999整数)/"timeSlotId", "reason":"..."}，至少提供一个可选字段，仅今日生效
- {"kind":"SKIP_TODAY_TASK","taskId":数字,"reason":"..."}，仅跳过今天的待做任务，不删除记录、不影响模板和未来任务
- {"kind":"TAKE_TODAY_OFF","reason":"..."}，整个今日请假，本地校验月度额度
- {"kind":"KEEP_TODAY_TASKS","keepTaskIds":[数字,...],"reason":"..."}，仅保留列出的今日任务，其余今日待做任务批量跳过

english_actions 支持的类型（英语积累，最多一次 20 条）：
- {"kind":"ADD_ENGLISH_ENTRY","type":"WORD|PHRASE|SENTENCE","content":"英文原文","meaning":"中文释义","reason":"..."}
- {"kind":"UPDATE_ENGLISH_ENTRY","id":数字, 可选字段:"type"/"content"/"meaning","reason":"..."}，至少提供一个可选字段
- {"kind":"DELETE_ENGLISH_ENTRY","id":数字,"reason":"..."}

所有 id 必须来自上下文里真实出现的 id，不许猜测。reason 控制在 40 字以内。"""

    }
}

/** 解析上下文预判返回：只接受合法的种类名，其余忽略；非 JSON 返回空集。 */
internal fun parseContextKinds(raw: String): Set<AssistantContextKind> {
    val trimmed = raw.trim()
    val jsonStart = trimmed.indexOf('{')
    val jsonEnd = trimmed.lastIndexOf('}')
    val candidate = if (jsonStart >= 0 && jsonEnd > jsonStart) {
        trimmed.substring(jsonStart, jsonEnd + 1)
    } else trimmed
    val root = runCatching { Json.parseToJsonElement(candidate) }.getOrNull() as? JsonObject
        ?: return emptySet()
    val needed = root["needed"]?.jsonArray ?: return emptySet()
    return needed.mapNotNull { element ->
        val name = (element as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        runCatching { AssistantContextKind.valueOf(name.trim().uppercase()) }.getOrNull()
    }.toSet()
}

internal fun guardMissingPlanActions(
    parsed: ParsedAssistantReply,
    userPrompt: String,
): ParsedAssistantReply {
    if (parsed.actions.isNotEmpty() || !looksLikePlanChangeRequest(userPrompt)) return parsed
    val notice = "本次没有生成可确认的修改方案，因此没有执行任何计划变更。"
    return parsed.copy(
        reply = "$notice\n\n${parsed.reply}".trim(),
        warnings = (parsed.warnings + "模型未返回完整的计划动作，本次未修改任何数据").distinct(),
    )
}

private fun looksLikePlanChangeRequest(prompt: String): Boolean {
    val compact = prompt.lowercase()
    return listOf(
        "请假", "修改计划", "调整计划", "调整任务", "改一下", "改成", "删掉",
        "删除", "取消任务", "跳过", "今天不做", "今日不做", "只保留", "仅保留",
    ).any(compact::contains)
}

/**
 * 用户明确要求写英语积累、但模型没给出任何英语动作时，明确告知没有写入，
 * 避免用户误以为已经保存成功。
 */
internal fun guardMissingEnglishActions(
    parsed: ParsedAssistantReply,
    userPrompt: String,
): ParsedAssistantReply {
    if (parsed.englishActions.isNotEmpty() || !looksLikeEnglishEntryRequest(userPrompt)) return parsed
    val notice = "本次没有生成可确认的英语积累变更，因此没有写入任何内容。"
    return parsed.copy(
        reply = "$notice\n\n${parsed.reply}".trim(),
        warnings = (parsed.warnings + "模型未返回完整的英语积累动作，本次未修改任何数据").distinct(),
    )
}

private fun looksLikeEnglishEntryRequest(prompt: String): Boolean {
    val compact = prompt.lowercase()
    return listOf(
        "加入英语积累", "记入英语积累", "存入英语积累", "添加到英语积累", "加入积累",
        "记到英语", "保存到英语", "写进英语积累", "删掉英语积累", "删除英语积累",
        "修改英语积累", "加入我的英语", "好词好句",
    ).any(compact::contains)
}

/** 把用户填的接口地址规范化成 chat completions 端点。 */
internal fun completionsUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
}

/** Normalizes a provider URL to its OpenAI-compatible model listing endpoint. */
internal fun modelsUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
        .removeSuffix("/chat/completions")
    return if (trimmed.endsWith("/models")) trimmed else "$trimmed/models"
}

/** 归一化到 DeepSeek OpenAI Responses API 端点。 */
internal fun deepSeekResponsesUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
        .removeSuffix("/chat/completions")
        .removeSuffix("/v1")
    return "${trimmed.trimEnd('/')}/v1/responses"
}

/** DeepSeek Responses API 解析结果。 */
internal data class DeepSeekResponsesOutput(
    val text: String,
    val thinking: String,
    val citations: List<AssistantModelClient.Citation>,
)

/** 解析 DeepSeek Responses API 的非流式响应：收集 output_text / 思考与 citations，忽略 web_search_call 等状态项。 */
internal fun parseDeepSeekResponses(raw: String): DeepSeekResponsesOutput {
    val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        ?: return DeepSeekResponsesOutput("", "", emptyList())
    val output = root["output"] as? JsonArray
        ?: return DeepSeekResponsesOutput("", "", emptyList())

    val text = StringBuilder()
    val thinking = StringBuilder()
    val citations = mutableListOf<AssistantModelClient.Citation>()
    for (item in output) {
        val obj = item as? JsonObject ?: continue
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
        when (type) {
            "message" -> {
                val content = obj["content"] as? JsonArray ?: continue
                for (part in content) {
                    val partObj = part as? JsonObject ?: continue
                    val partType = (partObj["type"] as? JsonPrimitive)?.contentOrNull
                    val partText = (partObj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    when (partType) {
                        "output_text" -> text.append(partText)
                        "output_thought" -> thinking.append(partText)
                    }
                    extractDeepSeekCitations(partObj["citations"], citations)
                }
            }
            "reasoning" -> {
                val summary = obj["summary"] as? JsonArray ?: continue
                for (part in summary) {
                    val partObj = part as? JsonObject ?: continue
                    if ((partObj["type"] as? JsonPrimitive)?.contentOrNull == "summary_text") {
                        thinking.append((partObj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                    }
                }
            }
            // "web_search_call" 等仅表示搜索状态，忽略。
        }
    }
    return DeepSeekResponsesOutput(text.toString(), thinking.toString(), citations)
}

private fun extractDeepSeekCitations(element: JsonElement?, out: MutableList<AssistantModelClient.Citation>) {
    val arr = element as? JsonArray ?: return
    for (c in arr) {
        val obj = c as? JsonObject ?: continue
        val url = (obj["url"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) continue
        val title = (obj["document_title"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            .ifBlank { (obj["title"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty() }
        out.add(AssistantModelClient.Citation(title, url))
    }
}

internal fun nativeReasoningRequestFields(
    baseUrl: String,
    model: String,
    effort: AiReasoningEffort,
): JsonObject = buildJsonObject {
    val value = effort.name.lowercase()
    val normalizedUrl = baseUrl.lowercase()
    when {
        "openrouter.ai" in normalizedUrl -> put(
            "reasoning",
            buildJsonObject { put("effort", value) },
        )
        "api.openai.com" in normalizedUrl && isOpenAiReasoningModel(model) -> put("reasoning_effort", value)
    }
}

private fun isOpenAiReasoningModel(model: String): Boolean {
    val normalized = model.trim().lowercase()
    return normalized.startsWith("gpt-5") ||
        normalized.startsWith("o1") ||
        normalized.startsWith("o3") ||
        normalized.startsWith("o4")
}

internal fun reasoningInstruction(effort: AiReasoningEffort): String = when (effort) {
    AiReasoningEffort.LOW ->
        "本轮使用低思考强度。优先快速、直接作答；普通问题不要展开冗长内部分析或反复验证。复杂题仍须在最终答案中保留必要公式和关键推导。"
    AiReasoningEffort.MEDIUM ->
        "本轮使用中等思考强度。对关键条件做充分检查，但控制内部分析长度，并及时输出完整最终答案。"
    AiReasoningEffort.HIGH ->
        "本轮使用高思考强度。适合复杂证明与多步规划；可以深入检查，但仍必须为完整最终答案保留输出空间。"
}

/** Whether this turn needs a provider-backed web search. */
internal fun shouldUseWebSearch(userPrompt: String, forced: Boolean): Boolean {
    if (forced) return true
    val normalized = userPrompt.trim().lowercase()
    return WEB_SEARCH_INTENT_MARKERS.any(normalized::contains)
}

internal fun parseModelIds(raw: String): List<String> {
    val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyList()
    return (root["data"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonObject)?.get("id") as? JsonPrimitive }
        .mapNotNull(JsonPrimitive::contentOrNull)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .sorted()
}

/**
 * 解析「获取模型列表」实际使用的密钥。
 *
 * 优先级：输入框现填值 → 该配置已保存的密钥 → 当前生效配置的密钥。
 * 编辑已有配置时输入框为空（密钥不回显），前两级回退保证按钮始终可用。
 */
internal fun resolveFetchKey(inputKey: String, profileKey: String?, activeKey: String?): String =
    inputKey.trim()
        .ifEmpty { profileKey.orEmpty().trim() }
        .ifEmpty { activeKey.orEmpty().trim() }

private val WEB_SEARCH_INTENT_MARKERS = listOf(
    "搜索",
    "搜一下",
    "联网",
    "查一下",
    "查询一下",
    "最新",
    "新闻",
    "官网",
    "今年",
    "近期",
    "实时",
    "当前版本",
    "截至",
)

/** Supports both the common string content and providers returning typed content arrays. */
internal fun extractText(element: JsonElement?): String = when (element) {
    is JsonPrimitive -> element.contentOrNull.orEmpty()
    is JsonArray -> element.joinToString("") { item ->
        val obj = item as? JsonObject
        extractText(obj?.get("text") ?: obj?.get("content") ?: obj?.get("output_text") ?: obj?.get("data"))
    }
    else -> ""
}

private fun StringBuilder.appendTail(text: String, maxChars: Int) {
    append(text)
    if (length > maxChars) delete(0, length - maxChars)
}
