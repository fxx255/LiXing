package com.example.lixing.ui.screen.assistant

import com.example.lixing.domain.assistant.AssistantMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantHistoryTest {

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
