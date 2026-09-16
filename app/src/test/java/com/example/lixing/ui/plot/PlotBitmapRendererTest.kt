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
     * 这里要同时盯住两个**互相拉扯**的诉求（历史上一改就顾此失彼）：
     * 1. **坐标轴范围必须严格等于定义域**。v1.0.34 把范围外扩 4% 后定义域被撑成 ±2.08，
     *    `±B/2` 的竖线落进框内，用户看到「曲线越过 −B/2..B/2 继续外延」；
     * 2. **曲线与框线之间要留出视觉余量**。v1.0.36 把范围改回精确后，曲线又正好顶死在
     *    绘图区边框上，用户反馈「既不直观无法看出曲线特征，也不美观」。
     *
     * 正确解法是把两者**解耦**：范围不动（保证刻度位置正确），只把绘制的矩形**向内缩**。
     * 所以本用例断言的是：边界竖线既不与框线重合（有可见余量），也不偏离定义域应有的
     * 位置（即余量只来自内缩，而不是范围被改了）。
     *
     * 用记录型 Canvas 读实际绘制坐标 —— Robolectric 既不栅格化 Path、`measureText`
     * 每字符又恒返回 1px，像素断言在这里都是假绿。
     */
    @Test
    fun `定义域边界位置精确且与框线留有可见余量`() {
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

        val verticals = recorder.verticalLineXs
        assertTrue("应有竖线被绘制（markLine/坐标轴）", verticals.size >= 2)
        val leftMost = verticals.min()
        val rightMost = verticals.max()

        // 绘图区（未内缩）的左右边界
        val outerLeft = w * 0.082f
        val outerRight = w - w * 0.028f
        val outerW = outerRight - outerLeft
        // ⚠️ 必须与 `PlotBitmapRenderer.PLOT_INSET_X_RATIO` 保持一致。
        // 该比例历经三轮收敛：0%（曲线顶死框线角）→ 5%（仍占 90% 宽、用户仍嫌「占满」）
        // → 15%（曲线占绘图区 70%，与教材题 3.3(c) 观感一致）。
        val insetX = outerW * 0.15f

        // ① 定义域上界的竖线位置 = 内缩后的右边界（= 范围未变，仅绘图区缩进）
        assertEquals(
            "B/2 的竖线应落在内缩后的绘图区右边界（范围精确 + 有内缩余量）",
            outerRight - insetX,
            rightMost,
            1.5f,
        )
        // ② 余量必须真实存在且大小合理：既不能贴死框线，也不能大到浪费面积
        val margin = outerRight - rightMost
        assertTrue("曲线右侧必须有可见余量（margin=$margin）", margin > w * 0.02f)
        assertTrue("右侧余量不应过大（margin=$margin）", margin < w * 0.20f)
        // ③ 左右两侧余量应对称（同一次内缩的结果）
        assertEquals(
            "左右余量应对称",
            outerRight - rightMost,
            leftMost - outerLeft,
            1.5f,
        )
        // ④ 用户明确的观感要求：**曲线应占绘图区宽度约 70%**，而不是顶满。
        // 这条把「70% 左右最好最美观」这个主观诉求固化成可回归的数字，
        // 避免以后有人把内缩调回 0/5% 又觉得「没差别」。
        val drawnWidth = rightMost - leftMost
        val occupancy = drawnWidth / outerW
        assertTrue(
            "曲线应占绘图区宽约 70%（实测 ${(occupancy * 100).toInt()}%）",
            occupancy in 0.65f..0.75f,
        )
    }
}
