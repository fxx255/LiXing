package com.example.lixing.data

import com.example.lixing.data.assistant.deepSeekResponsesUrl
import com.example.lixing.data.assistant.parseDeepSeekResponses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekResponsesTest {

    @Test
    fun `normalizes deepseek base urls to responses endpoint`() {
        assertEquals(
            "https://api.deepseek.com/v1/responses",
            deepSeekResponsesUrl("https://api.deepseek.com"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/responses",
            deepSeekResponsesUrl("https://api.deepseek.com/v1"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/responses",
            deepSeekResponsesUrl("https://api.deepseek.com/chat/completions"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/responses",
            deepSeekResponsesUrl("  https://api.deepseek.com/v1/  "),
        )
    }

    @Test
    fun `parses message output text and citations`() {
        val raw = """
            {
              "output": [
                {
                  "type": "web_search_call",
                  "status": "completed",
                  "action": "search"
                },
                {
                  "type": "message",
                  "content": [
                    {
                      "type": "output_text",
                      "text": "答案正文",
                      "citations": [
                        {"url": "https://example.com/a", "document_title": "标题A"}
                      ]
                    }
                  ]
                },
                {
                  "type": "reasoning",
                  "summary": [
                    {"type": "summary_text", "text": "思考过程"}
                  ]
                }
              ]
            }
        """.trimIndent()

        val output = parseDeepSeekResponses(raw)

        assertEquals("答案正文", output.text)
        assertEquals("思考过程", output.thinking)
        assertEquals(1, output.citations.size)
        assertEquals("https://example.com/a", output.citations.first().url)
        assertEquals("标题A", output.citations.first().title)
    }

    @Test
    fun `ignores web_search_call and tolerates invalid json`() {
        val raw = """
            {"output":[{"type":"web_search_call","status":"in_progress"}]}
        """.trimIndent()
        val output = parseDeepSeekResponses(raw)
        assertEquals("", output.text)
        assertTrue(output.citations.isEmpty())

        val empty = parseDeepSeekResponses("not-json")
        assertEquals("", empty.text)
        assertTrue(empty.citations.isEmpty())
    }
}
