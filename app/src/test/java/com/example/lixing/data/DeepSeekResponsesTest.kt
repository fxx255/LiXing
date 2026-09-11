package com.example.lixing.data

import com.example.lixing.data.assistant.deepSeekResponsesUrl
import com.example.lixing.data.assistant.parseDeepSeekResponses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `marks response as searched only when a search really happened`() {
        // 服务端发起了搜索：即使没带引用也算搜过
        val withCall = parseDeepSeekResponses(
            """{"output":[{"type":"web_search_call","status":"completed"},
               {"type":"message","content":[{"type":"output_text","text":"答案"}]}]}""",
        )
        assertTrue(withCall.searched)

        // 没有 web_search_call，但正文带了引用来源，同样认定搜过
        val withCitations = parseDeepSeekResponses(
            """{"output":[{"type":"message","content":[{"type":"output_text","text":"答案",
               "citations":[{"url":"https://example.com/a","document_title":"A"}]}]}]}""",
        )
        assertTrue(withCitations.searched)

        // 关键场景：请求被接受、回答正常，但服务端压根没搜——必须能被识别出来
        val silentlyIgnored = parseDeepSeekResponses(
            """{"output":[{"type":"message","content":[{"type":"output_text","text":"答案"}]}]}""",
        )
        assertFalse(silentlyIgnored.searched)
        assertEquals("答案", silentlyIgnored.text)
    }

    @Test
    fun `collects citations from annotations as well`() {
        val output = parseDeepSeekResponses(
            """{"output":[{"type":"message","content":[{"type":"output_text","text":"答案",
               "annotations":[{"type":"url_citation","url":"https://openai.com/x","title":"X"}]}]}]}""",
        )
        assertEquals(1, output.citations.size)
        assertEquals("https://openai.com/x", output.citations.first().url)
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
