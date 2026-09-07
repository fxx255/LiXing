package com.example.lixing.ui.screen.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantReasoningBufferTest {
    @Test
    fun `short reasoning is appended unchanged`() {
        assertEquals("第一段第二段", appendReasoningForDisplay("第一段", "第二段", maxChars = 20))
    }

    @Test
    fun `long reasoning keeps a bounded tail and one omission marker`() {
        val first = appendReasoningForDisplay("12345", "67890", maxChars = 6)
        val second = appendReasoningForDisplay(first, "ABC", maxChars = 6)

        assertTrue(second.startsWith("…较早的思考内容已省略…\n"))
        assertTrue(second.endsWith("890ABC"))
        assertEquals(1, Regex("较早的思考内容已省略").findAll(second).count())
    }
}
