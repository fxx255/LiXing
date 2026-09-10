package com.example.lixing.data

import com.example.lixing.data.assistant.completionsUrl
import com.example.lixing.data.assistant.extractText
import com.example.lixing.data.assistant.AiReasoningEffort
import com.example.lixing.data.assistant.ParsedAssistantReply
import com.example.lixing.data.assistant.guardMissingPlanActions
import com.example.lixing.data.assistant.modelsUrl
import com.example.lixing.data.assistant.nativeReasoningRequestFields
import com.example.lixing.data.assistant.parseModelIds
import com.example.lixing.data.assistant.resolveFetchKey
import com.example.lixing.data.assistant.shouldUseWebSearch
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantModelClientTest {

    @Test
    fun `normalizes provider base urls to chat completions endpoint`() {
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            completionsUrl("https://api.deepseek.com"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            completionsUrl("https://api.deepseek.com/v1/"),
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            completionsUrl("https://api.openai.com/v1/chat/completions"),
        )
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            completionsUrl("  https://openrouter.ai/api/v1  "),
        )
    }

    @Test
    fun `normalizes provider urls to models endpoint`() {
        assertEquals("https://api.openai.com/v1/models", modelsUrl("https://api.openai.com/v1/"))
        assertEquals(
            "https://api.openai.com/v1/models",
            modelsUrl("https://api.openai.com/v1/chat/completions"),
        )
        assertEquals("https://example.com/models", modelsUrl("https://example.com/models"))
    }

    @Test
    fun `parses sorted unique model ids`() {
        val raw = """{"data":[{"id":"z-model"},{"id":"a-model"},{"id":"a-model"},{"object":"model"}]}"""
        assertEquals(listOf("a-model", "z-model"), parseModelIds(raw))
        assertEquals(emptyList<String>(), parseModelIds("not-json"))
    }

    @Test
    fun `resolves fetch key from input then saved then active key`() {
        // 输入框有值：以输入为准（可用于临时更换密钥）
        assertEquals("input-key", resolveFetchKey("input-key", "saved-key", "active-key"))
        assertEquals("input-key", resolveFetchKey("  input-key  ", "saved-key", null))
        // 输入框为空（编辑已有配置时密钥不回显）：回退到该配置已保存的密钥
        assertEquals("saved-key", resolveFetchKey("", "saved-key", "active-key"))
        assertEquals("saved-key", resolveFetchKey("   ", "  saved-key  ", null))
        // 该配置没存过密钥：回退到当前生效配置
        assertEquals("active-key", resolveFetchKey("", null, "active-key"))
        assertEquals("active-key", resolveFetchKey("", "  ", "active-key"))
        // 都没有：返回空串，由上层提示「请填写 API 密钥」
        assertEquals("", resolveFetchKey("", null, null))
    }

    @Test
    fun `extracts text from typed content arrays`() {
        val value = Json.parseToJsonElement(
            """[{"type":"text","text":"第一段"},{"type":"output_text","content":"第二段"}]""",
        )
        assertEquals("第一段第二段", extractText(value))
    }

    @Test
    fun `detects search intent and supports a forced search turn`() {
        assertTrue(shouldUseWebSearch("请搜索一下今年的考试政策", forced = false))
        assertTrue(shouldUseWebSearch("解释这道题", forced = true))
        assertFalse(shouldUseWebSearch("解释这道二次函数题", forced = false))
    }

    @Test
    fun `uses provider native reasoning controls only where compatible`() {
        val openAi = nativeReasoningRequestFields(
            "https://api.openai.com/v1",
            "gpt-5-mini",
            AiReasoningEffort.LOW,
        )
        assertEquals("low", openAi["reasoning_effort"]?.toString()?.trim('"'))

        val openRouter = nativeReasoningRequestFields(
            "https://openrouter.ai/api/v1",
            "deepseek/deepseek-r1",
            AiReasoningEffort.MEDIUM,
        )
        assertEquals("medium", openRouter["reasoning"]?.let { Json.parseToJsonElement(it.toString()) }
            ?.let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("effort")?.toString()?.trim('"'))

        assertTrue(
            nativeReasoningRequestFields(
                "https://api.deepseek.com",
                "deepseek-reasoner",
                AiReasoningEffort.LOW,
            ).isEmpty(),
        )
    }

    @Test
    fun `plan change request without actions is explicitly marked as not applied`() {
        val guarded = guardMissingPlanActions(
            ParsedAssistantReply("好的，已为你处理今日请假。", emptyList(), emptyList()),
            "帮我今日请假，只保留背单词",
        )

        assertTrue(guarded.reply.startsWith("本次没有生成可确认的修改方案"))
        assertTrue(guarded.warnings.any { "未修改任何数据" in it })
    }

    @Test
    fun `ordinary answer without actions is not changed`() {
        val parsed = ParsedAssistantReply("普通回答", emptyList(), emptyList())
        assertEquals(parsed, guardMissingPlanActions(parsed, "解释这道题"))
    }
}
