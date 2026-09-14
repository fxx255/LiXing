package com.example.lixing.ui.screen.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 多轮续写时合并各轮图表列表，锚点必须按已累积的图表数平移。
 *
 * 背景：长推导撞上输出上限会自动续写。第 1 轮模型输出正文 + plots 若干张；
 * 第 2 轮模型只续写文字，它并不知道前面已有几张图，如果之后再画图，锚点仍从
 * `[[FIGURE:1]]` 起编号。若不平移，`[[FIGURE:1]]` 会错误地指向第 1 轮的第一张图。
 * 平移规则必须与 [splitFigureSegments] 的锚点解析保持一致（大小写不敏感、
 * 允许行内留白、**单独成行**）。
 */
class FigureAnchorOffsetTest {

    @Test
    fun `base 为 0 时原样返回`() {
        val text = "见下图：\n\n[[FIGURE:1]]\n\n后面的文字"
        assertEquals(text, offsetFigureAnchors(text, 0))
    }

    @Test
    fun `单个锚点按 base 平移`() {
        val shifted = offsetFigureAnchors("正文\n\n[[FIGURE:1]]\n\n尾注", 2)
        assertEquals("正文\n\n[[FIGURE:3]]\n\n尾注", shifted)
    }

    @Test
    fun `多个锚点全部平移`() {
        val shifted = offsetFigureAnchors("[[FIGURE:1]]\n文字\n[[FIGURE:2]]", 3)
        assertEquals("[[FIGURE:4]]\n文字\n[[FIGURE:5]]", shifted)
    }

    @Test
    fun `大小写与留白变体同样平移`() {
        val shifted = offsetFigureAnchors("  [[ figure : 2 ]]  ", 1)
        assertEquals("[[FIGURE:3]]", shifted)
    }

    @Test
    fun `行内伪锚点不平移（与切分规则一致）`() {
        val text = "前文 [[FIGURE:1]] 后文"
        assertEquals(text, offsetFigureAnchors(text, 5))
    }

    @Test
    fun `没有锚点的正文原样返回`() {
        val text = "这是一段普通正文，提到图但没有任何锚点。"
        assertEquals(text, offsetFigureAnchors(text, 3))
    }
}
