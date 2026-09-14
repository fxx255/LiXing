package com.example.lixing.ui.screen.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插图锚点切分：正文里的 `[[FIGURE:n]]` 要把回答切成「文字 / 图 / 文字 / 图」交替，
 * 让生成的图表出现在讲解它的那段文字中间，而不是统一堆在气泡末尾（v1.0.23 bug）。
 */
class FigureAnchorSplitTest {

    @Test
    fun `没有锚点时不切分`() {
        val result = splitFigureSegments("只有正文，没有图。", figureCount = 2)
        assertEquals(1, result.size)
        assertEquals("只有正文，没有图。", result[0].text)
        assertNull(result[0].figureIndex)
    }

    @Test
    fun `没有图时锚点也要剥掉不留字面量`() {
        // 图没渲染出来（plots 被截断丢掉 / 渲染失败）时，锚点绝不能原样显示给用户。
        // 以前 figureCount<=0 直接返回原文，正文里就裸着 [[FIGURE:1]]。
        val content = "正文\n[[FIGURE:1]]\n后面"
        val result = splitFigureSegments(content, figureCount = 0)
        assertTrue(
            "锚点必须从文字里剥掉，实际: $result",
            result.none { it.text.contains("[[FIGURE:") },
        )
        assertEquals(2, result.size)
        assertEquals("正文", result[0].text)
        assertEquals("后面", result[1].text)
    }

    @Test
    fun `只有锚点没有别的文字时返回空段`() {
        val result = splitFigureSegments("[[FIGURE:1]]", figureCount = 0)
        assertEquals(1, result.size)
        assertEquals("", result[0].text)
    }

    @Test
    fun `锚点把正文切成三段`() {
        val content = "由图可见主瓣宽度为 2/T。\n[[FIGURE:1]]\n接下来分析旁瓣结构。"
        val result = splitFigureSegments(content, figureCount = 1)
        assertEquals(3, result.size)
        assertEquals("由图可见主瓣宽度为 2/T。", result[0].text)
        assertNull(result[0].figureIndex)
        assertEquals(0, result[1].figureIndex)
        assertEquals("接下来分析旁瓣结构。", result[2].text)
    }

    @Test
    fun `多张图按出现顺序排列`() {
        val content = "开头\n[[FIGURE:1]]\n中间说明\n[[FIGURE:2]]\n结尾"
        val result = splitFigureSegments(content, figureCount = 2)
        assertEquals(5, result.size)
        assertEquals(0, result[1].figureIndex)
        assertEquals("中间说明", result[2].text)
        assertEquals(1, result[3].figureIndex)
        assertEquals("结尾", result[4].text)
    }

    @Test
    fun `锚点序号是 1-based 对应 plots 下标 0`() {
        val result = splitFigureSegments("A\n[[FIGURE:2]]\nB", figureCount = 3)
        assertEquals(1, result[1].figureIndex)
    }

    @Test
    fun `越界锚点被丢弃且不成段`() {
        // 模型写了 3 但只有 1 张图：不该产生占位，也不该把文字吞掉
        val result = splitFigureSegments("前\n[[FIGURE:3]]\n后", figureCount = 1)
        assertEquals(2, result.size)
        assertEquals("前", result[0].text)
        assertEquals("后", result[1].text)
        assertTrue(result.none { it.figureIndex != null })
    }

    @Test
    fun `锚点允许行内留白与大小写与空格`() {
        val variants = listOf(
            "前\n[[FIGURE:1]]\n后",
            "前\n  [[FIGURE:1]]  \n后",
            "前\n[[figure: 1]]\n后",
            "前\n[[ Figure : 1 ]]\n后",
        )
        variants.forEach { content ->
            val result = splitFigureSegments(content, figureCount = 1)
            assertEquals("$content 未识别", 3, result.size)
            assertEquals("$content 未识别", 0, result[1].figureIndex)
        }
    }

    @Test
    fun `行内出现的锚点不算锚点`() {
        // 提示词要求单独一行；夹在句子里的按普通文字处理，避免误吞正文
        val content = "这里[[FIGURE:1]]不是锚点"
        val result = splitFigureSegments(content, figureCount = 1)
        assertEquals(1, result.size)
        assertNull(result[0].figureIndex)
    }

    @Test
    fun `锚点在文首或文末时不产生空文字段`() {
        val head = splitFigureSegments("[[FIGURE:1]]\n后面的话", figureCount = 1)
        assertEquals(2, head.size)
        assertEquals(0, head[0].figureIndex)
        assertEquals("后面的话", head[1].text)

        val tail = splitFigureSegments("前面的话\n[[FIGURE:1]]", figureCount = 1)
        assertEquals(2, tail.size)
        assertEquals("前面的话", tail[0].text)
        assertEquals(0, tail[1].figureIndex)
    }

    @Test
    fun `纯标题锚点不掉内容`() {
        val content = "## 分析\n[[FIGURE:1]]\n### 结论"
        val result = splitFigureSegments(content, figureCount = 1)
        assertEquals(3, result.size)
        assertEquals("## 分析", result[0].text)
        assertEquals("### 结论", result[2].text)
    }
}
