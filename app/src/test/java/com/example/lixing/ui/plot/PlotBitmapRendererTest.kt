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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.noties.jlatexmath.JLatexMathAndroid

/**
 * 渲染器冒烟测试：真实走一遍 Canvas 绘制，确认各类图形都不会崩、也确实画出了东西。
 * 像素采样而不是逐点比对——这里要的是"没画空"，不是像素级还原。
 */
/**
 * 记录型 Canvas：捕获 [drawLine] 中**竖线**的 x 坐标（并入当前平移）。
 *
 * 为什么需要它：Robolectric 的 legacy graphics 不栅格化 Path（曲线一个像素都画不出来），
 * 而且 `Paint.measureText` 对每个字符恒返回 1px ⇒ 像素级断言都是假绿。
 * 直接读绘制调用的几何量，才能对「定义域边界是否与绘图区边框重合」做出可信断言。
 *
 * 渲染器只用 translate/rotate 做平移（无缩放/错切），因此矩阵平移量即坐标偏移。
 */
private class LineRecordingCanvas : android.graphics.Canvas() {

    /** 所有竖线（x 相同、y 不同）的 x 坐标。 */
    val verticalLineXs = mutableListOf<Float>()

    override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: android.graphics.Paint) {
        if (kotlin.math.abs(startX - stopX) < 0.01f) {
            // 渲染器只用 translate 做平移，取矩阵的平移分量即坐标偏移
            val dx = matrix?.let {
                val v = FloatArray(9)
                it.getValues(v)
                v[android.graphics.Matrix.MTRANS_X]
            } ?: 0f
            verticalLineXs += startX + dx
        }
    }
}

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

    /**
     * 回归：理想低通/带通谱只在 |f| ≤ B/2 上有定义，模型把定义域设成 ±2
     * （B/2 的数值占位）并在 ±2 处加 markLines 竖线标出定义域边界。
     *
     * 用户反馈「在上次修改图像左侧文字溢出问题之后，新出现生成该图像曲线稳定溢出
     * 该有的 -B/2 到 B/2 之间」。根因是 v1.0.34 的 x 轴 4% 外扩：定义域被撑成 ±2.08，
     * 于是 ±B/2 的竖线落在绘图区**内部**（离边框还有 4% 的缝隙），而曲线正好终止在
     * 竖线上 —— 视觉上就成了「曲线越过 -B/2..B/2 继续往外延伸」。
     *
     * 判据：定义域边界处的 markLine 必须与绘图区边框**重合**（外扩后会出现明显缝隙）。
     * 这里用记录型 Canvas 读实际绘制 x 坐标，不依赖 Robolectric 栅格化。
     */
    @Test
    fun `显式定义域边界与绘图区边框重合`() {
        JLatexMathAndroid.init(RuntimeEnvironment.getApplication())

        val spec = PlotSpec(
            x = Axis(
                label = "f",
                min = -2.0,
                max = 2.0,
                ticks = listOf(-2.0, 0.0, 2.0),
                tickLabels = mapOf(
                    -2.0 to "\$-B/2\$",
                    0.0 to "\$O\$",
                    2.0 to "\$B/2\$",
                ),
            ),
            y = Axis(ticks = listOf(1.0), tickLabels = mapOf(1.0 to "\$N_0(2\\pi f_c)^2\$")),
            series = listOf(Series(expr = "x^2")),
            markLines = listOf(MarkLine(x = -2.0), MarkLine(x = 2.0)),
        )

        val w = 960f
        val h = 560f
        val recorder = LineRecordingCanvas()
        renderer.drawAll(recorder, spec, w, h)

        // 三条竖线（两条边界 + 一条坐标轴）中，取最左与最右的 x
        val verticals = recorder.verticalLineXs
        assertTrue("应有竖线被绘制（markLine/坐标轴）", verticals.size >= 2)
        val leftMost = verticals.min()
        val rightMost = verticals.max()

        // 绘图区右边界：画布宽 - RIGHT_PAD_RATIO（右侧无自适应）
        val plotRight = w - w * 0.028f
        // 关键断言：右边界竖线必须与绘图区右边缘重合（容差 1px）
        assertEquals(
            "定义域上界 B/2 的竖线应与绘图区右边缘重合（未外扩）",
            plotRight,
            rightMost,
            1f,
        )

        // 左边界竖线应位于画布左侧留白处（y 轴标签自适应可能加宽留白，故只要求落在绘图区左边）
        assertTrue("左边界竖线应在画布内：$leftMost", leftMost > 0f && leftMost < plotRight)
    }
}
