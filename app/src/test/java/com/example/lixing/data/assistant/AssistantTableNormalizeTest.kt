package com.example.lixing.data.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格在 CommonMark 下渲染不出来，有两个互相独立的成因，这里各自钉住：
 *
 * 1. 表格与上文之间**缺少空行** ⇒ Markwon 把整块表格当普通段落（实测 TableSpan 数量为 0）；
 * 2. 行宽公式拆分把**表格行拆断** ⇒ `| a | $$x$$ |` 被拆成四行，表格结构消失。
 *
 * 另外覆盖单元格内裸 `|`（绝对值/范数）会导致 TablePlugin 列数错乱的转义。
 */
class AssistantTableNormalizeTest {

    @Test
    fun `adds blank line before table glued to previous paragraph`() {
        val md = "利用公式，\n| 频率点 | 值 |\n| --- | --- |\n| 0 | 4 |"

        val out = normalizeAssistantMarkdown(md)

        assertTrue("表格前应补空行，实际:\n$out", out.contains("利用公式，\n\n| 频率点"))
    }

    @Test
    fun `adds blank line after table glued to next paragraph`() {
        val md = "| 频率点 | 值 |\n| --- | --- |\n| 0 | 4 |\n带内起伏为"

        val out = normalizeAssistantMarkdown(md)

        assertTrue("表格后应补空行，实际:\n$out", out.contains("| 0 | 4 |\n\n带内起伏为"))
    }

    @Test
    fun `keeps existing blank lines untouched`() {
        val md = "前面一段\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n\n后面一段"

        val out = normalizeAssistantMarkdown(md)

        assertEquals(md, out)
    }

    @Test
    fun `escapes bare pipe inside math in table cell`() {
        val md = "| 条件 | 值 |\n| --- | --- |\n| \$\$|f| \\le B/2\$\$ | 4 |"

        val out = normalizeAssistantMarkdown(md)

        // 公式内的裸竖线必须转义，否则表格插件会把它当单元格分隔符，列数错乱
        val expectedCell = "\$\$\\|f\\| \\le B/2\$\$"
        assertTrue(
            "公式内的竖线应转义，期望包含 <$expectedCell>，实际:\n$out",
            out.contains(expectedCell),
        )
        // 单元格分隔符必须保留（不能被一起转义）
        assertTrue("单元格分隔符不能被转义，实际:\n$out", out.contains("| 4 |"))
        // 表格结构（表头 + 分隔行 + 数据行）保持完整
        assertEquals("表格行数不应变化", 3, out.lines().count { '|' in it })
    }

    @Test
    fun `does not touch pipes inside code fence`() {
        val md = "```\n| a | b |\n| --- | --- |\n```"

        val out = normalizeAssistantMarkdown(md)

        assertEquals(md, out)
    }

    @Test
    fun `table without separator row is left alone`() {
        val md = "| a | b |\n普通一行"

        val out = normalizeAssistantMarkdown(md)

        assertFalse("没有分隔行就不是表格，不该补空行:\n$out", out.contains("\n\n| a | b |"))
    }
}
