package com.example.lixing.data.assistant

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.ceil

@Serializable
data class AssistantContextUsage(
    val windowTokens: Int? = null,
    val estimatedInputTokens: Long = 0,
    val reservedOutputTokens: Int = 0,
    val serverInputTokens: Long? = null,
    val serverOutputTokens: Long? = null,
    val hasImages: Boolean = false,
    val estimatedOutputTokens: Long = 0,
) {
    val inputTokens: Long get() = serverInputTokens ?: estimatedInputTokens
    val isEstimated: Boolean get() = serverInputTokens == null
    val occupiedTokens: Long get() = inputTokens + (serverOutputTokens ?: estimatedOutputTokens)
    val fraction: Float? get() = windowTokens?.takeIf { it > 0 }?.let { occupiedTokens.toFloat() / it }

    fun withServerUsage(sample: UsageSample?): AssistantContextUsage = copy(
        serverInputTokens = sample?.inputTokens,
        serverOutputTokens = sample?.outputTokens,
    )

    fun summary(): String {
        val prefix = when {
            isEstimated -> "本次请求估算"
            serverOutputTokens == null -> "输入实测 / 输出估算"
            else -> "最近请求实测"
        }
        val window = windowTokens?.let { " / ${formatContextTokens(it.toLong())}" }.orEmpty()
        val percentage = fraction?.let { "（${ceil(it * 1000).toInt() / 10.0}%）" }.orEmpty()
        return "$prefix ${formatContextTokens(occupiedTokens)}$window tokens$percentage"
    }
}

fun formatContextTokens(tokens: Long): String = when {
    tokens >= 1_000_000 && tokens % 1_000_000 == 0L -> "${tokens / 1_000_000}M"
    tokens >= 1_000 && tokens % 1_000 == 0L -> "${tokens / 1_000}k"
    else -> tokens.toString()
}

object AssistantTokenEstimator {
    const val IMAGE_TOKENS = 4096L

    fun textTokens(text: String): Long {
        var tokens = 0L
        var asciiRun = 0
        var index = 0
        while (index < text.length) {
            val point = text.codePointAt(index)
            if (point in 'a'.code..'z'.code || point in 'A'.code..'Z'.code || point in '0'.code..'9'.code) {
                asciiRun++
            } else {
                tokens += (asciiRun + 3L) / 4
                asciiRun = 0
                tokens += if (point > 0xFFFF) 4 else 1
            }
            index += Character.charCount(point)
        }
        return tokens + (asciiRun + 3L) / 4
    }

    fun messageTokens(message: com.example.lixing.domain.assistant.AssistantMessage): Long =
        8 + textTokens(message.content) + message.imageBase64s.count { it.isNotBlank() } * IMAGE_TOKENS

    fun payloadUsage(payload: JsonObject, windowTokens: Int?): AssistantContextUsage {
        var tokens = 12L
        var hasImages = false
        tokens += textTokens((payload["instructions"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        payload["tools"]?.let { tokens += textTokens(it.toString()) }
        payload["web_search"]?.let { tokens += textTokens(it.toString()) }
        val messages = (payload["messages"] ?: payload["input"]) as? JsonArray
        for (element in messages.orEmpty()) {
            val message = element as? JsonObject ?: continue
            tokens += 8
            when (val content = message["content"]) {
                is JsonPrimitive -> tokens += textTokens(content.contentOrNull.orEmpty())
                is JsonArray -> for (part in content) {
                    val block = part as? JsonObject ?: continue
                    if ((block["type"] as? JsonPrimitive)?.contentOrNull in setOf("image_url", "input_image")) {
                        tokens += IMAGE_TOKENS
                        hasImages = true
                    } else {
                        tokens += textTokens((block["text"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                    }
                }
                else -> Unit
            }
        }
        val reserve = ((payload["max_tokens"] ?: payload["max_output_tokens"]) as? JsonPrimitive)?.intOrNull ?: 0
        return AssistantContextUsage(windowTokens, tokens, reserve, hasImages = hasImages)
    }
}

object AiContextWindow {
    val presets = listOf(32_000, 64_000, 128_000, 256_000, 300_000, 600_000, 1_000_000)
    const val MIN_TOKENS = 16_000
    const val MAX_TOKENS = 2_000_000

    fun validate(tokens: Int?) {
        require(tokens == null || tokens in MIN_TOKENS..MAX_TOKENS) { "上下文窗口请填写 16000～2000000 tokens，或留空表示未知" }
    }
}

fun fitAssistantContextWindow(payload: JsonObject, windowTokens: Int?, removableMessages: Int): JsonObject {
    if (windowTokens == null) return payload
    AiContextWindow.validate(windowTokens)
    val usage = AssistantTokenEstimator.payloadUsage(payload, windowTokens)
    val key = if (payload.containsKey("messages")) "messages" else "input"
    val messages = (payload[key] as? JsonArray)?.toMutableList() ?: return payload
    val prefix = if (key == "messages") 1 else 0
    var tokens = usage.estimatedInputTokens
    var removed = 0
    while (tokens + usage.reservedOutputTokens > windowTokens && removed < removableMessages) {
        val message = messages.removeAt(prefix) as JsonObject
        val sample = AssistantTokenEstimator.payloadUsage(JsonObject(mapOf("input" to JsonArray(listOf(message)))), null)
        tokens -= sample.estimatedInputTokens - 12
        removed++
    }
    if (tokens + usage.reservedOutputTokens > windowTokens) {
        throw AssistantModelException(
            AssistantModelException.Kind.CONFIG_INVALID,
            "当前问题、图片、系统提示与输出预留估算超过配置的 ${formatContextTokens(windowTokens.toLong())} tokens 窗口；请减少输入或确认模型支持的窗口设置。当前问题不会被截断。",
        )
    }
    return JsonObject(payload + (key to JsonArray(messages)))
}
