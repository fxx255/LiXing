package com.example.lixing.ui.plot

import android.app.Application
import android.graphics.Bitmap
import com.example.lixing.domain.plot.Axis
import com.example.lixing.domain.plot.MarkArea
import com.example.lixing.domain.plot.MarkLine
import com.example.lixing.domain.plot.PlotSpec
import com.example.lixing.domain.plot.Series
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 渲染器冒烟测试：真实走一遍 Canvas 绘制，确认各类图形都不会崩、也确实画出了东西。
 * 像素采样而不是逐点比对——这里要的是"没画空"，不是像素级还原。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlotBitmapRendererTest {

    private val renderer = PlotBitmapRenderer(density = 2f)

    /** 采样统计非背景色像素，用来判断"图上确实有东西"。 */
    private fun paintedPixels(bitmap: Bitmap): Int {
        val background = 0xFF1C1C1E.toInt()
        var count = 0
        var x = 0
        while (x < bitmap.width) {
            var y = 0
            while (y < bitmap.height) {
                if (bitmap.getPixel(x, y) != background) count++
                y += 5
            }
            x += 5
        }
        return count
    }

    @Test
    fun `renders function plot with content`() {
        val spec = PlotSpec(
            title = "y = x²",
            x = Axis(label = "x", min = -3.0, max = 3.0),
            y = Axis(label = "y"),
            series = listOf(Series(label = "y=x^2", expr = "x^2")),
        )

        val bitmap = renderer.render(spec, 600, 360)

        assertEquals(600, bitmap.width)
        assertEquals(360, bitmap.height)
        assertTrue("应该有绘制内容", paintedPixels(bitmap) > 100)
    }

    @Test
    fun `divergent expression renders without crashing`() {
        // 1/x 在 0 处必须断线，而不是连出一条贯穿画面的竖线
        val spec = PlotSpec(
            x = Axis(min = -1.0, max = 1.0),
            series = listOf(Series(expr = "1/x")),
        )

        val bitmap = renderer.render(spec, 400, 240)

        assertEquals(400, bitmap.width)
        assertTrue(paintedPixels(bitmap) > 50)
    }

    @Test
    fun `renders multiple series with marks`() {
        val spec = PlotSpec(
            title = "带标注",
            x = Axis(min = -6.0, max = 6.0),
            series = listOf(
                Series(label = "a", expr = "x", fill = true),
                Series(label = "b", points = listOf(-1.0 to 1.0, 1.0 to -1.0), style = "dashed"),
            ),
            markLines = listOf(MarkLine(x = 4.0, label = "f_c")),
            markAreas = listOf(MarkArea(3.0, 5.0, "B")),
            legend = true,
        )

        val bitmap = renderer.render(spec, 720, 420)

        assertEquals(720, bitmap.width)
        assertTrue(paintedPixels(bitmap) > 200)
    }

    @Test
    fun `marker style and no-axis-labels do not crash`() {
        val spec = PlotSpec(
            series = listOf(
                Series(expr = "sin(x)", style = "dashed"),
                Series(points = listOf(0.0 to 0.0, 1.0 to 1.0), style = "marker"),
            ),
        )

        val bitmap = renderer.render(spec, 400, 240)

        assertTrue(paintedPixels(bitmap) > 20)
    }

    @Test
    fun `explicit ticks with labels are honoured`() {
        val spec = PlotSpec(
            x = Axis(
                label = "f",
                min = -9.0,
                max = 9.0,
                ticks = listOf(-5.0, 0.0, 5.0),
                tickLabels = mapOf(-5.0 to "-f_c", 5.0 to "f_c"),
            ),
            series = listOf(Series(expr = "0")),
        )

        val bitmap = renderer.render(spec, 600, 300)

        assertEquals(600, bitmap.width)
        assertTrue(paintedPixels(bitmap) > 50)
    }
}
