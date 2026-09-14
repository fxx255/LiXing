package com.example.lixing.ui.plot

import android.app.Application
import android.graphics.Bitmap
import com.example.lixing.domain.plot.Axis
import com.example.lixing.domain.plot.MarkArea
import com.example.lixing.domain.plot.PlotSpec
import com.example.lixing.domain.plot.Series
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.noties.jlatexmath.JLatexMathAndroid

/**
 * 图内 LaTeX 公式渲染。
 *
 * 背景：模型经常把 `$S_c(f)$`、`\frac{1}{T}`、`\sum` 直接写进标题和轴标签，
 * 而画布原先只有 `Paint.drawText`（纯文本），于是图上出现一串美元符号和反斜杠。
 * 现在 [PlotBitmapRenderer] 会自动识别并交给 JLatexMath 排版。
 *
 * 公式是否渲染成功没法直接用像素断言（公式本身就是一组前景像素），所以这里用
 * 「能画出非背景像素」做冒烟；并单独验证非法公式会安全回落到纯文本、不会让整张图失败。
 *
 * 注意：Kotlin 里 `$` 在字符串中是模板起始符，LaTeX 的 `$...$` 必须转义成 `\$`；
 * 反斜杠命令用 `\\frac` 这类双反斜杠写法。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlotLatexLabelTest {

    private val renderer = PlotBitmapRenderer(density = 2f)

    /**
     * 公式排版必须先初始化 JLatexMath（同 LiXingApplication）。
     * 漏掉时 TeXFormula 静态初始化失败，而且**粘性**——同一 JVM 内之后
     * 所有用到它的测试都会 `NoClassDefFoundError`（曾把 JLatexCjkMetricsTest 整类带崩）。
     */
    @Before
    fun setUp() {
        JLatexMathAndroid.init(RuntimeEnvironment.getApplication())
    }

    private fun specWith(
        title: String = "",
        xLabel: String = "",
        yLabel: String = "",
        seriesLabel: String = "",
        markAreaLabel: String? = null,
    ) = PlotSpec(
        title = title,
        x = Axis(label = xLabel, min = -5.0, max = 5.0),
        y = Axis(label = yLabel),
        series = listOf(Series(label = seriesLabel, expr = "x^2")),
        markAreas = if (markAreaLabel != null) {
            listOf(MarkArea(x0 = -1.0, x1 = 1.0, label = markAreaLabel))
        } else {
            emptyList()
        },
    )

    /** 统计非背景色像素，判断图上确实画了东西。 */
    private fun paintedPixels(bitmap: Bitmap): Int {
        val background = 0xFF1C1C1E.toInt()
        var count = 0
        var x = 0
        while (x < bitmap.width) {
            var y = 0
            while (y < bitmap.height) {
                if (bitmap.getPixel(x, y) != background) count++
                y += 4
            }
            x += 4
        }
        return count
    }

    private fun render(spec: PlotSpec): Bitmap = renderer.render(spec, 900, 540)

    @Test
    fun `标题里的 LaTeX 能画出来`() {
        val bitmap = render(specWith(title = "功率谱密度 \$S_c(f)\$"))
        assertTrue("带 LaTeX 标题的图应画出内容", paintedPixels(bitmap) > 100)
    }

    @Test
    fun `轴标签里的 LaTeX 能画出来`() {
        val bitmap = render(
            specWith(
                xLabel = "\$f\$ (Hz)",
                yLabel = "\$S_c(f)\$",
                seriesLabel = "\\frac{N_0}{2}",
            ),
        )
        assertTrue("带 LaTeX 轴标签的图应画出内容", paintedPixels(bitmap) > 100)
    }

    @Test
    fun `不带美元符号的裸命令也能识别`() {
        val bitmap = render(specWith(title = "带宽 \\Delta f 与 \\sum 的关系"))
        assertTrue("裸 LaTeX 命令应被当作公式排版", paintedPixels(bitmap) > 100)
    }

    @Test
    fun `非法 LaTeX 安全回落纯文本不崩`() {
        // \frac 缺第二个参数、\begin 不闭合：都属于排不出来的写法
        val bitmap = render(specWith(title = "坏公式 \\frac{1 \$\\begin{equation}"))
        assertTrue("非法公式不应让整张图失败", paintedPixels(bitmap) > 100)
    }

    @Test
    fun `markArea 里的 LaTeX 标签能画出来`() {
        val bitmap = render(specWith(markAreaLabel = "\$B/2\$"))
        assertTrue("区间标注的 LaTeX 应能排版", paintedPixels(bitmap) > 100)
    }

    @Test
    fun `纯文本标签行为不变`() {
        val bitmap = render(specWith(title = "普通标题", xLabel = "时间 t", yLabel = "幅度"))
        assertTrue("纯文本路径应照常工作", paintedPixels(bitmap) > 100)
    }

    @Test
    fun `所有标签都是 LaTeX 时也能出图`() {
        val bitmap = render(
            specWith(
                title = "\$|H(f)|^2\$",
                xLabel = "\$f\$",
                yLabel = "\$S_Y(f)\$",
                seriesLabel = "\$4\\pi^2 N_0(f_c^2+f^2)\$",
                markAreaLabel = "\$2B\$",
            ),
        )
        assertTrue("全 LaTeX 标签应正常出图", paintedPixels(bitmap) > 100)
    }
}
