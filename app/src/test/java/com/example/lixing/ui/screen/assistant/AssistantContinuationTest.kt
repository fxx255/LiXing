package com.example.lixing.ui.screen.assistant

import com.example.lixing.data.assistant.AssistantResponseParser
import com.example.lixing.data.assistant.mergeAssistantContinuation
import com.example.lixing.data.assistant.normalizeAssistantMarkdown
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantContinuationTest {
    private val dollar = '$'
    private fun parseFragment(text: String): String = AssistantResponseParser.parse(
        buildJsonObject { put("reply", text) }.toString(), normalizeMarkdown = false,
    ).reply

    @Test fun `formula split between JSON replies is normalized only after merging`() {
        val first = "结论：双纽线 $dollar\\rho^2=\\cos 2"
        val second = "\\theta$dollar 所围面积为 ${dollar}1$dollar。"
        val joined = mergeAssistantContinuation(parseFragment(first), parseFragment(second))
        assertEquals(first + second, joined)
        assertEquals("结论：双纽线 $$\\rho^2=\\cos 2\\theta$$ 所围面积为 $$" + "1$$。", normalizeAssistantMarkdown(joined))
    }

    @Test fun `replayed incomplete paragraph replaces the truncated formula`() {
        val start = "已完成的推导。\n\n"
        val prefix = "**结论：双纽线 $$\\rho^2=\\cos 2"
        val complete = prefix + "\\theta$$ 所围面积为 1。**"
        assertEquals(start + complete, mergeAssistantContinuation(start + prefix, complete))
    }

    @Test fun `literal continuation preserves whitespace and repeated mathematical digits`() {
        assertEquals("公式 x=22", mergeAssistantContinuation("公式 x=2", "2"))
        assertEquals("abc\n\n后文", mergeAssistantContinuation("abc", "\n\n后文"))
        assertEquals("", mergeAssistantContinuation("", ""))
    }

    @Test fun `overlapping replay is not duplicated`() {
        val overlap = "这是前一轮已经写出的足够长的一段结论。"
        assertEquals("前文。" + overlap + "后文。", mergeAssistantContinuation("前文。" + overlap, overlap + "后文。"))
    }
}
