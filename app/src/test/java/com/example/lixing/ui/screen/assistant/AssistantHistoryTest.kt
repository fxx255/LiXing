package com.example.lixing.ui.screen.assistant

import com.example.lixing.data.assistant.buildModelHistory
import com.example.lixing.data.assistant.AiHistoryBudget
import com.example.lixing.domain.assistant.AssistantMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantHistoryTest {

    @Test
    fun `configured token window no longer has the legacy 24 message or 24000 character cap`() {
        val messages = (0 until 80).map { index ->
            AssistantMessage(if (index % 2 == 0) "user" else "assistant", "中".repeat(1000))
        }
        assertEquals(messages, buildModelHistory(messages, tokenBudget = 300_000))
        val compact = buildModelHistory(messages, tokenBudget = 12_000)
        assertTrue(compact.size < messages.size)
        assertEquals(messages.takeLast(compact.size), compact)
    }

    @Test
    fun `each budget bounds message count and keeps the newest history`() {
        val messages = (0 until 220).map { index ->
            AssistantMessage(if (index % 2 == 0) "user" else "assistant", "message-$index")
        }
        AiHistoryBudget.entries.forEach { budget ->
            val history = buildModelHistory(messages, budget)
            assertEquals(budget.maxMessages, history.size)
            assertEquals(messages.takeLast(budget.maxMessages), history)
        }
    }

    @Test
    fun `larger budget retains older complete answers instead of truncating them`() {
        val messages = listOf(
            AssistantMessage("user", "older question"),
            AssistantMessage("assistant", "x".repeat(13_000)),
            AssistantMessage("user", "newer question"),
            AssistantMessage("assistant", "y".repeat(13_000)),
        )
        assertEquals(messages.takeLast(2), buildModelHistory(messages))
        assertEquals(messages, buildModelHistory(messages, AiHistoryBudget.EXTENDED))
        assertEquals(messages.takeLast(1), buildModelHistory(messages, AiHistoryBudget.COMPACT))
    }

    @Test
    fun `budget preserves an oversized newest message with its attachments`() {
        val newest = AssistantMessage(
            "user", "x".repeat(30_000), imagePaths = listOf("photo.jpg"), imageBase64s = listOf("PHOTO"),
        )
        assertEquals(
            listOf(newest),
            buildModelHistory(listOf(AssistantMessage("assistant", "older answer"), newest), AiHistoryBudget.COMPACT),
        )
    }

    @Test
    fun `merges adjacent assistant fragments produced by auto continue`() {
        val messages = listOf(
            AssistantMessage("user", "问题"),
            AssistantMessage("assistant", "第一段"),
            AssistantMessage("assistant", "第二段"),
            AssistantMessage("user", "追问"),
            AssistantMessage("assistant", "回答"),
        )

        val history = buildModelHistory(messages)

        // 连续片段合并成一条，模型不会再看到「一串半截回答」
        assertEquals(4, history.size)
        assertEquals(
            listOf("user", "assistant", "user", "assistant"),
            history.map { it.role },
        )
        assertEquals("第一段第二段", history[1].content)
        assertEquals("追问", history[2].content)
        assertEquals("回答", history[3].content)
    }

    @Test
    fun `drops the oldest messages only when the character budget is exceeded`() {
        val long = "x".repeat(13_000)
        val messages = listOf(
            AssistantMessage("user", "最早的问题"),
            AssistantMessage("assistant", long),
            AssistantMessage("user", "最近的问题"),
            AssistantMessage("assistant", long),
        )

        val history = buildModelHistory(messages)

        assertTrue(history.size < messages.size)
        assertEquals("最近的问题", history.first().content)
    }

    @Test
    fun `returns empty history for an empty conversation`() {
        assertTrue(buildModelHistory(emptyList()).isEmpty())
    }
}
