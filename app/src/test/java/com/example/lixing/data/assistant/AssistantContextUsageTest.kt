package com.example.lixing.data.assistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantContextUsageTest {
    @Test
    fun `local estimate is not a fixed character to token conversion`() {
        assertEquals(0L, AssistantTokenEstimator.textTokens(""))
        assertEquals(2L, AssistantTokenEstimator.textTokens("abcdefgh"))
        assertEquals(8L, AssistantTokenEstimator.textTokens("中文公式上下文窗"))
        assertEquals(4L, AssistantTokenEstimator.textTokens("😀"))
        assertTrue(AssistantTokenEstimator.textTokens("x^2+1") > 2)
    }

    @Test
    fun `image base64 is not counted as ordinary text`() {
        val shortImage = Json.parseToJsonElement("""{"max_tokens":8192,"messages":[{"role":"user","content":[{"type":"text","text":"题目"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,a"}}]}]}""").jsonObject
        val longImage = Json.parseToJsonElement(shortImage.toString().replace("base64,a", "base64," + "a".repeat(100_000))).jsonObject
        val shortUsage = AssistantTokenEstimator.payloadUsage(shortImage, 300_000)
        assertEquals(shortUsage, AssistantTokenEstimator.payloadUsage(longImage, 300_000))
        assertTrue(shortUsage.hasImages)
        assertEquals(8192, shortUsage.reservedOutputTokens)
        assertEquals(12 + 8 + 2 + AssistantTokenEstimator.IMAGE_TOKENS, shortUsage.estimatedInputTokens)
    }

    @Test
    fun `actual usage replaces the estimate without subtracting cache hits or adding previous requests`() {
        val estimated = AssistantContextUsage(600_000, 2000, 8192, estimatedOutputTokens = 100)
        val actual = estimated.withServerUsage(UsageSample(3000, 2500, 500))
        assertFalse(actual.isEstimated)
        assertEquals(3500L, actual.occupiedTokens)
        assertEquals(3000L, actual.inputTokens)
        assertTrue(actual.summary().contains("600k"))
        assertTrue(actual.summary().contains("实测"))
        val next = actual.withServerUsage(UsageSample(4000, null, 200))
        assertEquals(4200L, next.occupiedTokens)
        val missing = next.withServerUsage(null)
        assertTrue(missing.isEstimated)
        assertEquals(2100L, missing.occupiedTokens)
    }

    @Test
    fun `unknown window has no invented percentage`() {
        val usage = AssistantContextUsage(estimatedInputTokens = 500)
        assertNull(usage.fraction)
        assertTrue(usage.summary().contains("估算"))
        assertFalse(usage.summary().contains("%"))
        assertEquals("1M", formatContextTokens(1_000_000))
        assertEquals("300k", formatContextTokens(300_000))
    }

    @Test
    fun `window fitting reserves output and drops only eligible oldest history`() {
        val answer = "中".repeat(12_000)
        val payload = Json.parseToJsonElement("""{"max_tokens":8192,"messages":[{"role":"system","content":"系统规则"},{"role":"user","content":"旧问题"},{"role":"assistant","content":"$answer"},{"role":"system","content":"当前时间"},{"role":"user","content":"当前问题"}]}""").jsonObject
        val fitted = fitAssistantContextWindow(payload, 16_000, 2)
        val content = fitted["messages"]!!.jsonArray.map { it.jsonObject["content"]!!.jsonPrimitive.content }
        assertEquals(listOf("系统规则", "当前时间", "当前问题"), content)
        val usage = AssistantTokenEstimator.payloadUsage(fitted, 16_000)
        assertTrue(usage.estimatedInputTokens + usage.reservedOutputTokens <= 16_000)
    }

    @Test
    fun `oversized protected question fails locally rather than being truncated`() {
        val question = "中".repeat(16_000)
        val payload = Json.parseToJsonElement("""{"max_tokens":8192,"messages":[{"role":"system","content":"系统规则"},{"role":"user","content":"$question"}]}""").jsonObject
        val error = runCatching { fitAssistantContextWindow(payload, 16_000, 0) }.exceptionOrNull()
        assertTrue(error is AssistantModelException)
        assertEquals(AssistantModelException.Kind.CONFIG_INVALID, (error as AssistantModelException).kind)
        assertTrue(error.message.orEmpty().contains("不会被截断"))
    }

    @Test
    fun `window validation rejects invalid limits without assuming a provider capability`() {
        listOf(null, 300_000, 600_000, 1_000_000).forEach(AiContextWindow::validate)
        listOf(0, -1, Int.MAX_VALUE).forEach { tokens ->
            assertTrue(runCatching { AiContextWindow.validate(tokens) }.isFailure)
        }
    }
}
