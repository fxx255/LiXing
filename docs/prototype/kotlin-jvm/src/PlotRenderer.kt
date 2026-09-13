package plot

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.min

/**
 * 结构化参数 → 位图。
 *
 * 这是整个方案里**唯一依赖具体绘图 API** 的一层：PC 上走 Java2D，
 * Android 上把 Graphics2D 换成 android.graphics.Canvas 即可 ——
 * 数据模型、刻度算法、表达式采样、坐标变换、断线处理全部原样复用。
 */
class PlotRenderer(private val theme: Theme = Theme()) {

    data class Theme(
        val background: Int = 0xFF1C1C1E.toInt(),
        val text: Int = 0xFFC9C9CE.toInt(),
        val subText: Int = 0xFF8E8E93.toInt(),
        val axis: Int = 0xFF4A4A4F.toInt(),
        val grid: Int = 0xFF2B2B2F.toInt(),
        val markLine: Int = 0xFF6A6A70.toInt(),
        val markArea: Int = 0x1FFFC857,
        val seriesColors: List<Int> = listOf(
            0xFF5AA9FF.toInt(),
            0xFFFFC857.toInt(),
            0xFF3DDC97.toInt(),
            0xFFFF7EB6.toInt(),
            0xFFB28DFF.toInt(),
        ),
        val titleSize: Int = 18,
        val labelSize: Int = 14,
        val tickSize: Int = 13,
        val fontName: String = "Microsoft YaHei",
    )

    fun render(spec: PlotSpec): BufferedImage {
        val image = BufferedImage(spec.widthPx, spec.heightPx, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.color = Color(theme.background)
            g.fillRect(0, 0, spec.widthPx, spec.heightPx)
            drawAll(g, spec)
        } finally {
            g.dispose()
        }
        return image
    }

    private fun drawAll(g: Graphics2D, spec: PlotSpec) {
        val titleFont = Font(theme.fontName, Font.PLAIN, theme.titleSize)
        val labelFont = Font(theme.fontName, Font.PLAIN, theme.labelSize)
        val tickFont = Font(theme.fontName, Font.PLAIN, theme.tickSize)

        // ---- 1. 采样：表达式与点集在这一步汇合，之后只认点集 ----
        val sampled: List<List<Pair<Double, Double?>>> = spec.series.map { s ->
            val xMin = spec.x.min ?: s.points?.minOfOrNull { it.first } ?: -5.0
            val xMax = spec.x.max ?: s.points?.maxOfOrNull { it.first } ?: 5.0
            sampleSeries(s, xMin, xMax, 900)
        }
        val allPoints = sampled.flatten()

        // ---- 2. 数据范围 ----
        val xLo = spec.x.min ?: allPoints.minOfOrNull { it.first } ?: -1.0
        val xHi = spec.x.max ?: allPoints.maxOfOrNull { it.first } ?: 1.0
        val yAuto = autoRange(allPoints.map { it.second })
        val yLo = spec.y.min ?: yAuto.first
        val yHi = spec.y.max ?: yAuto.second
        val spanX = if (xHi - xLo == 0.0) 1.0 else xHi - xLo
        val spanY = if (yHi - yLo == 0.0) 1.0 else yHi - yLo

        // ---- 3. 布局与坐标变换 ----
        val padLeft = 78.0
        val padRight = 30.0
        val padTop = if (spec.title.isNotEmpty()) 56.0 else 28.0
        val padBottom = 58.0
        val plotX = padLeft
        val plotY = padTop
        val plotW = spec.widthPx - padLeft - padRight
        val plotH = spec.heightPx - padTop - padBottom

        fun sx(x: Double): Double = plotX + (x - xLo) / spanX * plotW
        fun sy(y: Double): Double = plotY + plotH - (y - yLo) / spanY * plotH

        val xTicks = spec.x.ticks ?: niceTicks(xLo, xHi, 6)
        val yTicks = spec.y.ticks ?: niceTicks(yLo, yHi, 5)

        // ---- 4. markArea（垫在网格下面）----
        for (area in spec.markAreas) {
            val a0 = sx(area.x0)
            val a1 = sx(area.x1)
            g.color = Color(theme.markArea, true)
            g.fillRect(min(a0, a1).toInt(), plotY.toInt(), abs(a1 - a0).toInt(), plotH.toInt())
            area.label?.let {
                g.font = tickFont
                g.color = Color(theme.subText)
                val w = g.fontMetrics.stringWidth(it)
                // 画在区域内部顶部：markLine 的标签占的是绘图区上方，两者同高会叠成 "fBc"
                g.drawString(it, ((a0 + a1) / 2 - w / 2).toFloat(), (plotY + 18).toFloat())
            }
        }

        // ---- 5. 网格 ----
        if (spec.x.grid || spec.y.grid) {
            g.color = Color(theme.grid)
            g.stroke = BasicStroke(1f)
            for (t in xTicks) {
                val px = sx(t)
                g.drawLine(px.toInt(), plotY.toInt(), px.toInt(), (plotY + plotH).toInt())
            }
            for (t in yTicks) {
                val py = sy(t)
                g.drawLine(plotX.toInt(), py.toInt(), (plotX + plotW).toInt(), py.toInt())
            }
        }

        // ---- 6. 序列：面积 → 折线 ----
        spec.series.forEachIndexed { index, s ->
            val pts = sampled[index]
            if (pts.isEmpty()) return@forEachIndexed
            val baseColor = Color(theme.seriesColors[s.colorIndex % theme.seriesColors.size])

            if (s.fill) {
                val fillColor = Color(baseColor.red, baseColor.green, baseColor.blue, 38)
                fillArea(g, pts, ::sx, ::sy, fillColor, plotY + plotH)
            }
            val lineColor = Color(
                baseColor.red, baseColor.green, baseColor.blue,
                (255 * s.opacity.coerceIn(0.05, 1.0)).toInt(),
            )
            if (s.style == "marker") {
                g.color = lineColor
                for ((x, y) in pts) {
                    if (y == null) continue
                    val px = sx(x).toInt()
                    val py = sy(y).toInt()
                    g.fillOval(px - 3, py - 3, 7, 7)
                }
            } else {
                drawPolyline(g, pts, ::sx, ::sy, lineColor, s.width, s.style == "dashed")
            }
        }

        // ---- 7. 坐标轴 ----
        g.color = Color(theme.axis)
        g.stroke = BasicStroke(1.2f)
        g.drawLine(plotX.toInt(), (plotY + plotH).toInt(), (plotX + plotW).toInt(), (plotY + plotH).toInt())
        g.drawLine(plotX.toInt(), plotY.toInt(), plotX.toInt(), (plotY + plotH).toInt())

        // ---- 8. 刻度与标签 ----
        g.font = tickFont
        g.color = Color(theme.subText)
        for (t in xTicks) {
            val text = spec.x.tickLabels[t] ?: formatTick(t)
            val w = g.fontMetrics.stringWidth(text)
            g.drawString(text, (sx(t) - w / 2).toFloat(), (plotY + plotH + 20).toFloat())
        }
        for (t in yTicks) {
            val text = spec.y.tickLabels[t] ?: formatTick(t)
            val w = g.fontMetrics.stringWidth(text)
            g.drawString(text, (plotX - 10 - w).toFloat(), (sy(t) + 4).toFloat())
        }

        // ---- 9. 轴标题 ----
        g.font = labelFont
        g.color = Color(theme.text)
        if (spec.x.label.isNotEmpty()) {
            val w = g.fontMetrics.stringWidth(spec.x.label)
            g.drawString(spec.x.label, (plotX + plotW / 2 - w / 2).toFloat(), (spec.heightPx - 14).toFloat())
        }
        if (spec.y.label.isNotEmpty()) {
            val label = if (spec.y.unit.isNotEmpty()) "${spec.y.label} (${spec.y.unit})" else spec.y.label
            val w = g.fontMetrics.stringWidth(label)
            // getTransform() 返回的是副本：必须用 g.rotate() 直接改当前变换，画完再恢复。
            // 否则要么文字根本没旋转，要么把后续所有绘制都带上旋转。
            val saved = g.transform
            g.rotate(-Math.PI / 2)
            g.drawString(label, (-(plotY + plotH / 2 + w / 2)).toFloat(), 24f)
            g.transform = saved
        }

        // ---- 10. 标题 ----
        if (spec.title.isNotEmpty()) {
            g.font = titleFont
            g.color = Color(theme.text)
            val fm = g.fontMetrics
            val w = fm.stringWidth(spec.title)
            g.drawString(spec.title, ((spec.widthPx - w) / 2).toFloat(), 34f)
        }

        // ---- 11. markLine ----
        for (line in spec.markLines) {
            val x = line.x
            if (x != null) {
                g.color = Color(theme.markLine)
                g.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(7f, 5f), 0f)
                g.drawLine(sx(x).toInt(), plotY.toInt(), sx(x).toInt(), (plotY + plotH).toInt())
                line.label?.let {
                    g.font = tickFont
                    g.color = Color(theme.subText)
                    val w = g.fontMetrics.stringWidth(it)
                    g.drawString(it, (sx(x) - w / 2).toFloat(), (plotY - 8).toFloat())
                }
            }
        }

        // ---- 12. 图例 ----
        if (spec.legend || spec.series.size > 1) {
            g.font = tickFont
            var yy = plotY + 14
            spec.series.forEachIndexed { index, s ->
                if (s.label.isEmpty()) return@forEachIndexed
                val color = Color(theme.seriesColors[s.colorIndex % theme.seriesColors.size])
                g.color = color
                g.stroke = BasicStroke(2.4f)
                g.drawLine((plotX + 14).toInt(), (yy - 4).toInt(), (plotX + 42).toInt(), (yy - 4).toInt())
                g.color = Color(theme.subText)
                g.drawString(s.label, (plotX + 50).toFloat(), yy.toFloat())
                yy += 20
            }
        }
    }

    /** 画折线：null 处断开——发散点（1/x 在 0 等）绝不能连成一条竖直长线。 */
    private fun drawPolyline(
        g: Graphics2D,
        pts: List<Pair<Double, Double?>>,
        sx: (Double) -> Double,
        sy: (Double) -> Double,
        color: Color,
        width: Float,
        dashed: Boolean,
    ) {
        g.stroke = BasicStroke(
            width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f,
            if (dashed) floatArrayOf(9f, 6f) else null, 0f,
        )
        g.color = color
        val path = Path2D.Double()
        var started = false
        for ((x, y) in pts) {
            if (y == null || !y.isFinite()) {
                started = false
                continue
            }
            val px = sx(x)
            val py = sy(y)
            if (!started) {
                path.moveTo(px, py)
                started = true
            } else {
                path.lineTo(px, py)
            }
        }
        g.draw(path)
    }

    /** 面积填充：按连续段分别闭合到基线。 */
    private fun fillArea(
        g: Graphics2D,
        pts: List<Pair<Double, Double?>>,
        sx: (Double) -> Double,
        sy: (Double) -> Double,
        color: Color,
        baseline: Double,
    ) {
        val path = Path2D.Double()
        var segment = mutableListOf<Pair<Double, Double>>()

        fun flush() {
            if (segment.size >= 2) {
                path.moveTo(segment.first().first, baseline)
                for (p in segment) path.lineTo(p.first, p.second)
                path.lineTo(segment.last().first, baseline)
                path.closePath()
            }
            segment = mutableListOf()
        }

        for ((x, y) in pts) {
            if (y == null || !y.isFinite()) {
                flush()
            } else {
                segment += sx(x) to sy(y)
            }
        }
        flush()
        g.color = color
        g.fill(path)
    }

    private fun formatTick(v: Double): String = when {
        abs(v - v.toLong()) < 1e-9 -> v.toLong().toString()
        abs(v) < 1e-3 -> String.format("%.2e", v)
        abs(v) >= 10000 -> String.format("%.0f", v)
        else -> {
            val s = String.format("%.4f", v).trimEnd('0').trimEnd('.')
            s.ifEmpty { "0" }
        }
    }
}
