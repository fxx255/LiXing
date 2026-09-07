package com.example.lixing.data

import android.graphics.Rect
import com.example.lixing.data.assistant.OcrBlock
import com.example.lixing.data.assistant.OcrBlockKind
import com.example.lixing.data.assistant.OcrPageResult
import com.example.lixing.data.assistant.formulaDetectionTiles
import com.example.lixing.data.assistant.isFormulaRecognitionUsable
import com.example.lixing.data.assistant.pageToMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MathOcrMarkdownTest {
    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    @Test
    fun `inline and display formulas keep their geometry driven delimiters`() {
        val markdown = pageToMarkdown(
            OcrPageResult(
                pageIndex = 0,
                width = 1000,
                height = 1000,
                blocks = listOf(
                    OcrBlock(OcrBlockKind.TEXT, "已知", rect(10, 10, 100, 50), 1f),
                    OcrBlock(OcrBlockKind.INLINE_FORMULA, "x^2=1", rect(110, 10, 260, 50), .9f),
                    OcrBlock(OcrBlockKind.DISPLAY_FORMULA, "x=\\pm1", rect(250, 120, 750, 200), .9f),
                ),
            ),
        )

        assertTrue(markdown, markdown.contains("已知 ${'$'}${'$'}x^2=1${'$'}${'$'}"))
        assertTrue(markdown, markdown.contains("${'$'}${'$'}\nx=\\pm1\n${'$'}${'$'}"))
    }

    @Test
    fun `table markdown remains a standalone block`() {
        val table = "| 条件 | 结果 |\n| --- | --- |\n| x=1 | y=2 |"
        assertEquals(
            "题目\n$table",
            pageToMarkdown(
                OcrPageResult(
                    0,
                    1000,
                    1000,
                    listOf(
                        OcrBlock(OcrBlockKind.TEXT, "题目", rect(10, 10, 100, 50), 1f),
                        OcrBlock(OcrBlockKind.TABLE, table, rect(10, 100, 900, 500), 1f),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `wide pages keep a full pass and overlapping detail tiles`() {
        val tiles = formulaDetectionTiles(width = 1240, height = 480)

        assertEquals(0, tiles.first().left)
        assertEquals(0, tiles.first().top)
        assertEquals(1240, tiles.first().right)
        assertEquals(480, tiles.first().bottom)
        assertTrue(tiles.size >= 3)
        assertEquals(0, tiles.drop(1).minOf { it.left })
        assertEquals(1240, tiles.drop(1).maxOf { it.right })
        tiles.drop(1).zipWithNext().forEach { (left, right) ->
            assertTrue(left.right > right.left)
        }
    }

    @Test
    fun `malformed or low confidence formulas fall back to plain OCR`() {
        assertTrue(isFormulaRecognitionUsable("D(x_0)=\\{d\\in\\mathbb{R}\\mid f(x_0+d)>f(x_0)\\}", .8f))
        assertTrue(!isFormulaRecognitionUsable("f(\\x\\cdot)\\mid\\pi<0", .9f))
        assertTrue(!isFormulaRecognitionUsable("x^{2", .9f))
        assertTrue(!isFormulaRecognitionUsable("x=1", .1f))
        assertTrue(!isFormulaRecognitionUsable("^{77}", .9f, 39, 34))
        assertTrue(
            !isFormulaRecognitionUsable(
                "x\\neq0,f(\\begin{array}{l}{}\\\\{}\\end{array})\\neq0," +
                    "f(\\begin{array}{l}{}\\\\{}\\end{array})\\neq0," +
                    "f(\\begin{array}{l}{}\\\\{}\\end{array})\\neq0," +
                    "f(\\begin{array}{l}{}\\\\{}\\end{array})",
                .9f,
                136,
                37,
            ),
        )
    }
}
