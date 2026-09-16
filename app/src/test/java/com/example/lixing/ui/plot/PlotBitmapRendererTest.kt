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

    /**
     * 竖线的完整记录：`[x, 顶端 y, 底端 y]`。
     *
     * 需要它是因为 v1.0.38 之后「竖线」有三种来源（坐标轴线、定义域边界、端刻度），
     * 光看 x 分不清谁是谁——比如 x 轴横线伸出绘图区后，它的**端刻度**会成为
     * x 最大的一根竖线，把「B/2 落在哪」的断言带偏（实测 expected 691 但 was 910）。
     * 有了 y 范围就能按「只从轴升到曲线」这个特征把边界线挑出来。
     */
    val verticalLines = mutableListOf<FloatArray>()

    /** 所有横线（y 相同、x 不同）的 `[左端, 右端, y]`。用来验证「x 轴是否伸出绘图区」。 */
    val horizontalLines = mutableListOf<FloatArray>()

    override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: android.graphics.Paint) {
        // 渲染器只用 translate 做平移，取矩阵的平移分量即坐标偏移
        val v = FloatArray(9)
        matrix?.getValues(v)
        val dx = v[android.graphics.Matrix.MTRANS_X]
        val dy = v[android.graphics.Matrix.MTRANS_Y]

        if (kotlin.math.abs(startX - stopX) < 0.01f) {
            verticalLineXs += startX + dx
            verticalLines += floatArrayOf(startX + dx, startY + dy, stopY + dy)
        }
        if (kotlin.math.abs(startY - stopY) < 0.01f) {
            horizontalLines += floatArrayOf(
                minOf(startX, stopX) + dx,
                maxOf(startX, stopX) + dx,
                startY + dy,
            )
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

        // 绘图区（未内缩）的左右边界。左右留白对称 8.2%（见 PLOT_* 常量注释）。
        val outerLeft = w * 0.082f
        val outerRight = w - w * 0.082f
        val outerW = outerRight - outerLeft
        // ⚠️ 必须与 `PlotBitmapRenderer.PLOT_INSET_X_RATIO` 保持一致。
        // 该比例历经四轮收敛：0%（曲线顶死框线角）→ 5%（仍占 90% 宽、用户仍嫌「占满」）
        // → 15%（占绘图区 70%，用户认可但嫌轴不明显）
        // → **23.68%**（对齐教材参考图：数据占**画布** 44%、两侧留白各 28%）。
        val insetX = outerW * 0.2368f

        // ⚠️ 不能直接用「所有竖线的 min/max」定位定义域边界：
        // v1.0.38 起 x 轴横线伸出绘图区，其**端刻度**是小竖线，必定位居最右
        // （实测会把 rightMost 从 691 带偏到 910）。
        // 这里按「精确落在内缩后绘图区左右边界」这个**已知几何位置**取线，
        // 而不是靠 y 范围猜——位置本身就是被测对象，用位置筛选最直接。
        val expectedLeft = outerLeft + insetX
        val expectedRight = outerRight - insetX
        val boundaryLines = recorder.verticalLineXs.filter {
            kotlin.math.abs(it - expectedLeft) < 1.5f || kotlin.math.abs(it - expectedRight) < 1.5f
        }
        assertTrue(
            "应能在内缩后绘图区边界处找到定义域竖线；全部竖线=${recorder.verticalLineXs}",
            boundaryLines.size >= 2,
        )
        val leftMost = boundaryLines.min()
        val rightMost = boundaryLines.max()

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
        assertTrue("右侧余量不应过大（margin=$margin）", margin < w * 0.35f)
        // ③ 左右两侧余量应对称（同一次内缩的结果）
        assertEquals(
            "左右余量应对称",
            outerRight - rightMost,
            leftMost - outerLeft,
            1.5f,
        )
        // ④ 用户明确的观感要求：**数据占画布宽约 44%**，与教材参考图一致。
        // 这是对参考图做像素级测量的结果（参考图数据占图宽 44.0%），
        // 比早期「占绘图区 70%」窄得多——「美观」的来源其实是两侧的大片留白。
        // 注意基准是**画布**宽度而不是绘图区宽度：早期那条断言用错了基准。
        val drawnWidth = rightMost - leftMost
        val occupancy = drawnWidth / w
        assertTrue(
            "数据应占画布宽约 44%（实测 ${(occupancy * 100).toInt()}%）",
            occupancy in 0.42f..0.46f,
        )
    }

    /**
     * 回归：坐标轴要「明显」且按教材参考图的样式绘制。
     *
     * 用户反馈「希望图中有更明显的 xy 坐标轴」，并给出参考图指出 x 轴应当**延伸出去**。
     * 本用例钉住三件事（都是这次改动引入的、且容易被后续重构改回去的）：
     * 1. **x 轴横线超出数据范围**（左右都伸出），而不是正好等于绘图区宽度；
     * 2. **竖轴位置**：原点落在 x 范围内时画在 x=0，否则退化到绘图区左边界
     *    （不能无条件画在 x=0，否则 `y=1/x`、`y=2^x` 这类窗口会画到空处）；
     * 3. **定义域边界竖线不再贯穿整个绘图区高度**，而是止于曲线（参考图画法）。
     */
    @Test
    fun `坐标轴按参考图样式绘制`() {
        JLatexMathAndroid.init(RuntimeEnvironment.getApplication())

        fun spec(xMin: Double, xMax: Double) = PlotSpec(
            x = Axis(label = "f", min = xMin, max = xMax, ticks = listOf(xMin, xMax)),
            y = Axis(ticks = listOf(1.0)),
            series = listOf(Series(expr = "x^2")),
            markLines = listOf(MarkLine(x = xMin), MarkLine(x = xMax)),
        )

        val w = 960f
        val h = 560f

        // ---- ① 原点在范围内 ⇒ 竖轴画在 x=0（居中），且 x 轴横线两端都伸出绘图区 ----
        val center = LineRecordingCanvas()
        renderer.drawAll(center, spec(-2.0, 2.0), w, h)
        val horiz = center.horizontalLines
        assertTrue("x 轴横线应被绘制", horiz.isNotEmpty())
        val widest = horiz.maxByOrNull { it[1] - it[0] }!!
        val plotLeft = w * 0.082f
        val plotRight = w - w * 0.082f
        assertTrue(
            "x 轴横线应向左伸出绘图区（左端 ${widest[0]} < plotLeft $plotLeft）",
            widest[0] < plotLeft - 1f,
        )
        assertTrue(
            "x 轴横线应向右伸出绘图区（右端 ${widest[1]} > plotRight $plotRight）",
            widest[1] > plotRight + 1f,
        )
        // 竖轴里应有一根落在画布中心附近（x=0 映射过来正好居中，因为留白对称）
        val centerXs = center.verticalLineXs.filter { kotlin.math.abs(it - w / 2f) < w * 0.03f }
        assertTrue(
            "原点在范围内时，竖轴应画在 x=0（画布中心附近）；实测竖线 x=${center.verticalLineXs}",
            centerXs.isNotEmpty(),
        )

        // ---- ② 原点不在范围内 ⇒ 竖轴退化到绘图区左边界，不会画到空处 ----
        val offCenter = LineRecordingCanvas()
        renderer.drawAll(offCenter, spec(1.0, 3.0), w, h)
        val offVerticals = offCenter.verticalLineXs
        assertTrue("应仍有竖线（左边界轴线 + 两条 markLine）", offVerticals.isNotEmpty())
        val offLeftMost = offVerticals.min()
        val insetX = (plotRight - plotLeft) * 0.2368f
        assertEquals(
            "原点不在范围内时，竖轴应落在绘图区左边界（内缩后）",
            plotLeft + insetX,
            offLeftMost,
            1.5f,
        )
        // 并且不该有竖线落在画布中心（x=0 不在 [1,3] 内）
        assertTrue(
            "原点不在范围内时不应在画布中心画竖轴",
            offVerticals.none { kotlin.math.abs(it - w / 2f) < w * 0.02f },
        )
    }
}
