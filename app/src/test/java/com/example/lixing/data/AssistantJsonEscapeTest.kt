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
    fun `newline followed by ascii letters is not mistaken for latex`() {
        // 回归：正文换行后紧跟英文（f(x)、uv、network）曾被误判成 LaTeX 命令，
        // 于是换行被吃掉、正文里直接冒出字面的 \nf(x)、\nuv
        val raw =
            """{"reply":"先看第一段\nf(x) 的取值，再看\nuv 的说明","plan_actions":[],"english_actions":[]}"""

        val parsed = AssistantResponseParser.parse(raw)

        assertFalse("换行不该变成字面反斜杠 n，实际: ${parsed.reply}", parsed.reply.contains("\\n"))
        assertTrue(parsed.reply.contains("f(x)"))
        assertTrue(parsed.reply.contains("uv 的说明"))
    }

    @Test
    fun `latex n commands are still preserved`() {
        val raw =
            """{"reply":"梯度 \nabla f 与 \neq 0 且 \notin A","plan_actions":[],"english_actions":[]}"""

        val parsed = AssistantResponseParser.parse(raw)

        assertTrue("\\nabla 应保留，实际: ${parsed.reply}", parsed.reply.contains("""\nabla"""))
        assertTrue("\\neq 应保留", parsed.reply.contains("""\neq"""))
        assertTrue("\\notin 应保留", parsed.reply.contains("""\notin"""))
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
