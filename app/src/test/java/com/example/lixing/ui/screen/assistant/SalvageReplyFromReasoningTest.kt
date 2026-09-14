package com.example.lixing.ui.screen.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 续写轮里模型把正文写进 reasoning 通道时，必须能把正文取回来。
 *
 * 症状：回答正文停止增长，而「思考」面板持续变长，用户反馈
 * 「成功生成一张图之后，后续的回复都输出到思考链里了」。
 */
class SalvageReplyFromReasoningTest {

    @Test
    fun `reasoning 里是完整 json 时取回正文`() {
        val reasoning = """分析完毕。{"reply": "## 六、常用近似形式\n\n当 B << f_c 时……", "plan_actions": []}"""

        val salvaged = salvageReplyFromReasoningText(reasoning)

        assertTrue("应取回正文，实际: $salvaged", salvaged.contains("## 六、常用近似形式"))
    }

    @Test
    fun `reasoning 是截断 json 时抢救正文`() {
        val reasoning = "继续推导。{\"reply\": \"## 六、常用近似形式\n\n上面的结果里含有 " +
            "\$\$f^2\$\$ 项。当带通滤波器满足窄带条件时……"

        val salvaged = salvageReplyFromReasoningText(reasoning)

        assertTrue("截断的 JSON 也应抢救出正文，实际: $salvaged", salvaged.contains("## 六、常用近似形式"))
    }

    @Test
    fun `纯思考内容不当作正文`() {
        val reasoning = "让我想想这道题该怎么做。首先需要判断窄带条件是否成立……"

        val salvaged = salvageReplyFromReasoningText(reasoning)

        assertEquals("没有正文结构时不该臆测", "", salvaged)
    }

    @Test
    fun `空 reasoning 返回空`() {
        assertEquals("", salvageReplyFromReasoningText(""))
        assertEquals("", salvageReplyFromReasoningText("   \n  "))
    }

    @Test
    fun `取回结果不能等于 reasoning 本身`() {
        // 防止「把思考原文当成正文再展示一遍」
        val reasoning = "{\"reply\": \"{\\\"reply\\\": \\\"x\\\"}\"}"

        val salvaged = salvageReplyFromReasoningText(reasoning)

        assertTrue("取回结果不应与输入完全相同", salvaged.trim() != reasoning.trim())
    }
}
