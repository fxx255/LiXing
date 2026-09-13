package com.example.lixing.data

import com.example.lixing.data.assistant.AssistantResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型在 reply 里写 LaTeX 时常直接输出单反斜杠（`\frac`、`\alpha`），
 * 这在 JSON 里是非法转义，曾导致整包解析失败、协议原文被当成回答显示。
 */
class AssistantJsonEscapeTest {

    @Test
    fun `latex backslashes in reply do not break parsing`() {
        val raw =
            """{"reply":"由 \frac{a}{b} 与 \alpha 可得","plan_actions":[],"english_actions":[]}"""

        val parsed = AssistantResponseParser.parse(raw)

        assertFalse("不该把协议原文当正文显示", parsed.reply.contains("plan_actions"))
        assertTrue("公式反斜杠应原样保留", parsed.reply.contains("""\frac{a}{b}"""))
        assertTrue(parsed.reply.contains("""\alpha"""))
        assertTrue(parsed.actions.isEmpty())
    }

    @Test
    fun `genuine json newline escape still becomes a real newline`() {
        val raw = """{"reply":"第一段\n第二段","plan_actions":[],"english_actions":[]}"""

        val parsed = AssistantResponseParser.parse(raw)

        assertTrue(parsed.reply.contains("第一段"))
        assertTrue(parsed.reply.contains("第二段"))
        assertFalse("不应留下字面的反斜杠 n", parsed.reply.contains("\\n"))
    }

    @Test
    fun `plan actions survive alongside latex in reply`() {
        val raw =
            """{"reply":"已生成方案 \cdot 请确认","plan_actions":[{"kind":"TAKE_TODAY_OFF","reason":"休息"}],"english_actions":[]}"""

        val parsed = AssistantResponseParser.parse(raw)

        assertFalse(parsed.reply.contains("plan_actions"))
        assertEquals(1, parsed.actions.size)
    }
}
